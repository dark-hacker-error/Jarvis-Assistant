package com.jarvis.assistant.data.memory

import com.jarvis.assistant.data.backup.BackupTriggers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class MemoryRepository(private val memoryDao: MemoryDao) {

    /**
     * Serialises writes so the same fact cannot be inserted twice when several
     * learning paths (explicit "remember", short-term turns and finalizeTurn)
     * fire at nearly the same time.
     */
    private val writeMutex = Mutex()

    /**
     * Bumped after every write. Live conversations observe this so a memory
     * saved from the Memory screen or a "yaad rakho" command becomes known to
     * the running Gemini session without reconnecting it.
     */
    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision.asStateFlow()

    private fun bumpRevision() {
        _revision.value = _revision.value + 1
    }

    fun getAllMemoriesFlow(): Flow<List<MemoryEntity>> = memoryDao.getAllMemoriesFlow()

    suspend fun getAllMemories(): List<MemoryEntity> = memoryDao.getAllMemories()

    fun getMemoriesByCategoryFlow(category: String): Flow<List<MemoryEntity>> {
        return if (category == "All") {
            memoryDao.getAllMemoriesFlow()
        } else {
            memoryDao.getMemoriesByCategoryFlow(category)
        }
    }

    fun searchMemoriesFlow(query: String): Flow<List<MemoryEntity>> = memoryDao.searchMemoriesFlow(query)

    suspend fun searchMemories(query: String): List<MemoryEntity> = memoryDao.searchMemories(query)

    suspend fun saveMemory(
        rawText: String,
        category: String = MemoryEntity.CATEGORY_NOTES,
        key: String = "",
        value: String = ""
    ): Long = writeMutex.withLock {
        val cleanRaw = rawText.trim().replace(Regex("\\s+"), " ")
        if (cleanRaw.isBlank()) return@withLock -1L

        val calculatedKey = key.trim().ifBlank { cleanRaw.take(30).trim() }
        val calculatedValue = value.trim().ifBlank { cleanRaw }

        // Exact duplicate: keep the existing row instead of growing the table
        // every time a learning path sees the same sentence again.
        val duplicate = memoryDao.findByRawText(cleanRaw)
        if (duplicate != null) {
            return@withLock duplicate.id
        }

        // Stable profile keys (name, birthday, favourite song, ...) replace the
        // previous value instead of accumulating contradictory copies.
        if (isStableKey(calculatedKey)) {
            val existing = memoryDao.findByKey(calculatedKey)
            if (existing != null) {
                memoryDao.updateMemory(
                    existing.copy(
                        category = category,
                        key = calculatedKey,
                        value = calculatedValue,
                        rawText = cleanRaw,
                        timestamp = System.currentTimeMillis()
                    )
                )
                bumpRevision()
                BackupTriggers.request("memory-update")
                return@withLock existing.id
            }
        }

        val entity = MemoryEntity(
            category = category,
            key = calculatedKey,
            value = calculatedValue,
            rawText = cleanRaw
        )
        val id = memoryDao.insertMemory(entity)
        bumpRevision()
        BackupTriggers.request("memory-save")
        id
    }

    suspend fun updateMemory(memory: MemoryEntity) {
        writeMutex.withLock {
            memoryDao.updateMemory(memory)
        }
        bumpRevision()
        BackupTriggers.request("memory-update")
    }

    suspend fun deleteMemoryById(id: Long) {
        writeMutex.withLock {
            memoryDao.deleteMemoryById(id)
        }
        bumpRevision()
        BackupTriggers.request("memory-delete")
    }

    suspend fun deleteMemoriesByKeyword(keyword: String): Int {
        val deleted = writeMutex.withLock {
            memoryDao.deleteMemoriesByKeyword(keyword)
        }
        if (deleted > 0) {
            bumpRevision()
            BackupTriggers.request("memory-delete")
        }
        return deleted
    }

    suspend fun clearAll() {
        writeMutex.withLock {
            memoryDao.clearAllMemories()
        }
        bumpRevision()
        BackupTriggers.request("memory-clear")
    }

    private fun isStableKey(key: String): Boolean =
        key.startsWith("user_") ||
            key.startsWith("pref_") ||
            key.startsWith("relation_") ||
            key.startsWith("favorite_")
}
