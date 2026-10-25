package com.jarvis.assistant.data.backup

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.jarvis.assistant.data.memory.MemoryEntity
import com.jarvis.assistant.data.memory.MemoryManager
import com.jarvis.assistant.data.memory.MemoryRepository
import com.jarvis.assistant.data.model.ChatTurn
import com.jarvis.assistant.data.preferences.AppPreferences
import com.jarvis.assistant.data.preferences.YouTubePreferences
import com.jarvis.assistant.data.repository.ChatRepository
import com.jarvis.assistant.mcp.McpManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Coordinates the uninstall-surviving snapshot.
 *
 * The snapshot contains the long-term memory database, chat history, personal
 * preferences, both API keys and the custom MCP configuration. It is written to
 * [JarvisBackupStore] (encrypted, Downloads/Jarvis) after relevant changes and
 * restored automatically on the first launch after a reinstall.
 *
 * Restores are deliberately conservative:
 *  - automatic restore only runs when local data is empty;
 *  - an explicit "Restore" merges missing records instead of overwriting;
 *  - existing local values always win, so a stale backup can never roll back
 *    something the user changed after the backup was written.
 */
class BackupCoordinator(
    context: Context,
    private val preferences: AppPreferences,
    private val chatRepository: ChatRepository,
    private val memoryRepository: MemoryRepository,
    private val memoryManager: MemoryManager,
    private val youTubePreferences: YouTubePreferences,
    private val mcpManager: McpManager,
    private val scope: CoroutineScope
) {

    data class BackupStatus(
        val available: Boolean = false,
        val lastSavedAt: Long = 0L,
        val lastRestoredAt: Long = 0L,
        val location: String = "",
        val error: String = ""
    )

    data class RestoreReport(
        val restored: Boolean = false,
        val memories: Int = 0,
        val chatTurns: Int = 0,
        val geminiKey: Boolean = false,
        val youtubeKey: Boolean = false,
        val customServers: Int = 0,
        val preferencesRestored: Boolean = false,
        val error: String = ""
    )

    private data class MemoryRecord(
        val category: String,
        val key: String,
        val value: String,
        val rawText: String,
        val timestamp: Long
    )

    private val store = JarvisBackupStore(context.applicationContext)
    private val gson = Gson()
    private val restoreMutex = Mutex()
    private val backupMutex = Mutex()
    private val scheduleLock = Any()

    private var scheduledJob: Job? = null

    @Volatile
    private var restoreInProgress = false

    private val _status = MutableStateFlow(BackupStatus())
    val status: StateFlow<BackupStatus> = _status.asStateFlow()

    private val _lastRestore = MutableStateFlow(RestoreReport())
    val lastRestore: StateFlow<RestoreReport> = _lastRestore.asStateFlow()

    /**
     * Installs the data-change hook, restores after a reinstall and starts the
     * periodic safety backup. Safe to call more than once.
     */
    fun start() {
        BackupTriggers.install { reason -> requestBackup(reason) }
        scope.launch {
            val report = restoreIfNeeded()
            if (report.restored) {
                Log.i(TAG, "Restored ${report.memories} memories and ${report.chatTurns} chat turns from the persistent backup")
            } else if (report.error.isNotBlank()) {
                Log.i(TAG, "No restore performed: ${report.error}")
            }
            // Never overwrite a good backup with an empty first-launch state.
            backupNow("startup")
            refreshStatus()
        }
        scope.launch {
            while (isActive) {
                delay(PERIODIC_BACKUP_MS)
                backupNow("periodic")
            }
        }
    }

    /** Schedules a backup after a short throttle window. */
    fun requestBackup(reason: String) {
        if (restoreInProgress) {
            Log.d(TAG, "Ignoring $reason backup request while a restore is running")
            return
        }
        synchronized(scheduleLock) {
            if (scheduledJob?.isActive == true) return
            scheduledJob = scope.launch {
                delay(DEBOUNCE_MS)
                backupNow(reason)
            }
        }
    }

    /** Writes immediately. Used on app background and by the explicit UI action. */
    fun requestImmediateBackup(reason: String) {
        if (restoreInProgress) {
            Log.d(TAG, "Ignoring $reason immediate backup while a restore is running")
            return
        }
        synchronized(scheduleLock) {
            scheduledJob?.cancel()
            scheduledJob = null
        }
        scope.launch { backupNow(reason) }
    }

    suspend fun backupNow(reason: String, force: Boolean = false): Result<Unit> =
        withContext(Dispatchers.IO) {
            if (restoreInProgress) {
                Log.d(TAG, "Skipping $reason backup because a restore is running")
                return@withContext Result.success(Unit)
            }
            backupMutex.withLock {
                try {
                    val payload = buildPayload(force)
                    if (payload == null) {
                        Log.d(TAG, "Skipping $reason backup because there is no user data yet")
                        return@withLock Result.success(Unit)
                    }
                    val file = store.write(payload.toString()).getOrThrow()
                    _status.value = BackupStatus(
                        available = true,
                        lastSavedAt = file.savedAt,
                        lastRestoredAt = _status.value.lastRestoredAt,
                        location = file.location
                    )
                    Log.i(TAG, "Persistent backup saved ($reason)")
                    Result.success(Unit)
                } catch (e: Exception) {
                    val message = e.message?.trim().orEmpty().ifBlank { e.javaClass.simpleName }
                    Log.w(TAG, "Persistent backup failed ($reason): $message", e)
                    _status.value = _status.value.copy(error = message)
                    Result.failure(e)
                }
            }
        }

    /** Automatic restore used during startup. */
    suspend fun restoreIfNeeded(): RestoreReport = restore(automatic = true)

    /** Explicit restore from Settings; merges missing data into existing local data. */
    suspend fun restoreNow(): RestoreReport = restore(automatic = false)

    private suspend fun restore(automatic: Boolean): RestoreReport = withContext(Dispatchers.IO) {
        restoreMutex.withLock {
            // Block new backups for the whole restore. Otherwise a debounced
            // write triggered by a partially restored preference could
            // overwrite the good snapshot with incomplete data.
            restoreInProgress = true
            synchronized(scheduleLock) {
                scheduledJob?.cancel()
                scheduledJob = null
            }
            try {
                backupMutex.withLock { restoreLocked(automatic) }
            } finally {
                restoreInProgress = false
            }
        }
    }

    private suspend fun restoreLocked(automatic: Boolean): RestoreReport {
            if (automatic && preferences.isBackupRestoreDone()) {
                return RestoreReport()
            }

            val localHasData = hasLocalUserData()
            if (automatic && localHasData) {
                // This installation already has data (update, not a reinstall).
                // Never let an older snapshot replace it.
                preferences.markBackupRestoreDone()
                return RestoreReport()
            }

            val file = store.read().getOrElse { error ->
                val message = error.message?.trim().orEmpty().ifBlank { "No backup found" }
                Log.i(TAG, "Persistent backup unavailable: $message")
                return RestoreReport(error = message)
            }

            val payload = try {
                JSONObject(file.payload)
            } catch (e: Exception) {
                return RestoreReport(error = "Backup payload is not valid JSON")
            }
            if (payload.optString("format") != PAYLOAD_FORMAT ||
                payload.optInt("version", 0) != PAYLOAD_VERSION
            ) {
                return RestoreReport(error = "Unsupported backup payload version")
            }

            var memories = 0
            var chatTurns = 0
            var customServers = 0
            var geminiKey = false
            var youtubeKey = false
            var preferencesRestored = false

            return try {
                preferencesRestored = preferences.restoreBackupPreferences(
                    payload.optJSONObject("preferences")
                )

                val apiKeys = payload.optJSONObject("apiKeys")
                val gemini = apiKeys?.optString("gemini").orEmpty().trim()
                if (gemini.isNotEmpty() && preferences.apiKey.isBlank()) {
                    preferences.apiKey = gemini
                    geminiKey = preferences.apiKey.isNotBlank()
                }
                val youtube = apiKeys?.optString("youtube").orEmpty().trim()
                if (youtube.isNotEmpty() && youTubePreferences.youtubeApiKey.isBlank()) {
                    youtubeKey = youTubePreferences.restoreApiKeyIfEmpty(youtube)
                }

                val turns = parseChatTurns(payload.optJSONArray("chatHistory"))
                if (chatRepository.turns.value.isEmpty() && turns.isNotEmpty()) {
                    preferences.saveChatHistory(turns)
                    chatRepository.reloadFromPreferences()
                    chatTurns = turns.size
                }

                val records = parseMemories(payload.optJSONArray("memories"))
                for (record in records) {
                    if (record.rawText.isBlank()) continue
                    val id = memoryRepository.saveMemory(
                        rawText = record.rawText,
                        category = record.category.ifBlank { MemoryEntity.CATEGORY_NOTES },
                        key = record.key,
                        value = record.value
                    )
                    if (id > 0) memories++
                }

                val beforeServers = mcpManager.customServers().size
                mcpManager.restoreBackupState(payload.optJSONObject("mcp"))
                customServers = (mcpManager.customServers().size - beforeServers).coerceAtLeast(0)

                memoryManager.reloadShortTermHistory()
                preferences.markBackupRestoreDone()
                val now = System.currentTimeMillis()
                _status.value = _status.value.copy(
                    available = true,
                    lastSavedAt = file.savedAt,
                    lastRestoredAt = now,
                    location = file.location,
                    error = ""
                )
                val report = RestoreReport(
                    restored = memories > 0 || chatTurns > 0 || geminiKey || youtubeKey ||
                        customServers > 0 || preferencesRestored,
                    memories = memories,
                    chatTurns = chatTurns,
                    geminiKey = geminiKey,
                    youtubeKey = youtubeKey,
                    customServers = customServers,
                    preferencesRestored = preferencesRestored
                )
                _lastRestore.value = report
                Log.i(
                    TAG,
                    "Backup restore complete: memories=$memories chat=$chatTurns " +
                        "gemini=$geminiKey youtube=$youtubeKey servers=$customServers prefs=$preferencesRestored"
                )
                report
            } catch (e: Exception) {
                val message = e.message?.trim().orEmpty().ifBlank { e.javaClass.simpleName }
                Log.e(TAG, "Backup restore failed: $message", e)
                _status.value = _status.value.copy(error = message)
                RestoreReport(error = message)
            }
    }

    suspend fun refreshStatus() = withContext(Dispatchers.IO) {
        val stored = store.status()
        _status.value = _status.value.copy(
            available = stored.available,
            lastSavedAt = if (stored.savedAt > 0L) stored.savedAt else _status.value.lastSavedAt,
            location = stored.location.ifBlank { _status.value.location },
            error = stored.error.ifBlank { _status.value.error }
        )
    }

    /** Deletes the persistent snapshot after an explicit user action. */
    suspend fun deleteBackup(): Boolean = withContext(Dispatchers.IO) {
        backupMutex.withLock {
            val deleted = store.delete()
            if (deleted) {
                _status.value = BackupStatus()
                Log.i(TAG, "Persistent backup deleted by the user")
            }
            deleted
        }
    }

    fun statusText(): String {
        val current = _status.value
        return when {
            current.error.isNotBlank() -> "Backup error: ${current.error}"
            !current.available -> "No persistent backup yet"
            current.lastSavedAt > 0L -> "Backup: ${formatTime(current.lastSavedAt)} • ${current.location}"
            else -> "Backup found • ${current.location}"
        }
    }

    private suspend fun buildPayload(force: Boolean): JSONObject? {
        val memories = memoryRepository.getAllMemories()
        val turns = chatRepository.turns.value
        val customServers = mcpManager.customServers()
        val apiKey = preferences.apiKey
        val youtubeKey = youTubePreferences.youtubeApiKey

        if (!force && memories.isEmpty() && turns.isEmpty() && customServers.isEmpty() &&
            apiKey.isBlank() && youtubeKey.isBlank() && !preferences.hasStoredPersonalData()
        ) {
            return null
        }

        val memoryArray = JSONArray()
        memories.forEach { memory ->
            memoryArray.put(
                JSONObject()
                    .put("category", memory.category)
                    .put("key", memory.key)
                    .put("value", memory.value)
                    .put("rawText", memory.rawText)
                    .put("timestamp", memory.timestamp)
            )
        }

        val payload = JSONObject()
            .put("format", PAYLOAD_FORMAT)
            .put("version", PAYLOAD_VERSION)
            .put("createdAt", System.currentTimeMillis())
            .put("memories", memoryArray)
            .put("chatHistory", JSONArray(gson.toJson(turns)))
            .put("preferences", preferences.exportBackupPreferences())
            .put("apiKeys", JSONObject()
                .put("gemini", apiKey)
                .put("youtube", youtubeKey)
            )
            .put("mcp", mcpManager.exportBackupState())
        return payload
    }

    private suspend fun hasLocalUserData(): Boolean {
        if (memoryRepository.getAllMemories().isNotEmpty()) return true
        if (chatRepository.turns.value.isNotEmpty()) return true
        if (preferences.apiKey.isNotBlank()) return true
        if (youTubePreferences.youtubeApiKey.isNotBlank()) return true
        if (mcpManager.customServers().isNotEmpty()) return true
        return preferences.hasStoredPersonalData()
    }

    private fun parseMemories(array: JSONArray?): List<MemoryRecord> {
        if (array == null) return emptyList()
        val type = object : TypeToken<List<MemoryRecord>>() {}.type
        return try {
            gson.fromJson<List<MemoryRecord>>(array.toString(), type).orEmpty()
        } catch (e: Exception) {
            Log.w(TAG, "Could not parse backed-up memories", e)
            emptyList()
        }
    }

    private fun parseChatTurns(array: JSONArray?): List<ChatTurn> {
        if (array == null) return emptyList()
        val type = object : TypeToken<List<ChatTurn>>() {}.type
        return try {
            gson.fromJson<List<ChatTurn>>(array.toString(), type).orEmpty()
                .filter { it.userTranscript.isNotBlank() || it.jarvisResponse.isNotBlank() }
        } catch (e: Exception) {
            Log.w(TAG, "Could not parse backed-up chat history", e)
            emptyList()
        }
    }

    private fun formatTime(timestamp: Long): String {
        if (timestamp <= 0L) return "unknown time"
        return try {
            SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date(timestamp))
        } catch (_: Exception) {
            "unknown time"
        }
    }

    private companion object {
        const val TAG = "BackupCoordinator"
        const val PAYLOAD_FORMAT = "jarvis-backup-payload"
        const val PAYLOAD_VERSION = 1
        const val DEBOUNCE_MS = 2_500L
        const val PERIODIC_BACKUP_MS = 15 * 60 * 1000L
    }
}
