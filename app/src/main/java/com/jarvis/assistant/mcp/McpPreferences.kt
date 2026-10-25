package com.jarvis.assistant.mcp

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.jarvis.assistant.data.backup.BackupTriggers
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistence for MCP server toggles and custom server definitions.
 *
 * Built-in toggles are harmless and live in normal preferences. Custom server
 * records can contain bearer tokens, so they are kept in encrypted storage
 * whenever the platform keystore is available. If encrypted storage cannot be
 * initialised or its Keystore key is later invalidated, reads/writes fall back
 * to normal app-private preferences instead of crashing or silently dropping
 * the user's configuration.
 */
class McpPreferences(context: Context) {

    private val appContext = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(NORMAL_PREFS, Context.MODE_PRIVATE)

    private val securePrefs: SharedPreferences? = try {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            SECURE_PREFS,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        Log.e(TAG, "Encrypted MCP preferences unavailable", e)
        null
    }

    private val gson = Gson()

    @Volatile
    private var secureStorageBroken = false

    val hasSecureStorage: Boolean
        get() = securePrefs != null && !secureStorageBroken

    fun isBuiltInEnabled(serverId: String): Boolean =
        prefs.getBoolean("$BUILTIN_PREFIX$serverId", false)

    fun setBuiltInEnabled(serverId: String, enabled: Boolean) {
        prefs.edit().putBoolean("$BUILTIN_PREFIX$serverId", enabled).apply()
        BackupTriggers.request("mcp-toggle")
    }

    fun getCustomServers(): List<McpCustomServer> {
        val raw = readCustomRaw() ?: return emptyList()
        return try {
            val type = object : TypeToken<List<McpCustomServer>>() {}.type
            gson.fromJson<List<McpCustomServer>>(raw, type).orEmpty()
                .filter { !it.id.isNullOrBlank() && !it.url.isNullOrBlank() }
                .map {
                    it.copy(
                        name = it.name.orEmpty().ifBlank { "Custom MCP" },
                        url = it.url.orEmpty(),
                        token = it.token.orEmpty()
                    )
                }
        } catch (e: Exception) {
            Log.w(TAG, "Could not parse custom MCP servers", e)
            emptyList()
        }
    }

    fun saveCustomServers(servers: List<McpCustomServer>) {
        val json = try {
            gson.toJson(servers)
        } catch (e: Exception) {
            Log.e(TAG, "Could not serialise custom MCP servers", e)
            return
        }
        if (!writeCustomRaw(json)) {
            Log.e(TAG, "Could not save custom MCP servers to any preference store")
        }
        BackupTriggers.request("mcp-servers")
    }

    fun getCachedTools(serverId: String): List<McpTool> {
        val raw = prefs.getString("cached_tools_$serverId", null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<McpTool>>() {}.type
            gson.fromJson<List<McpTool>>(raw, type).orEmpty()
        } catch (e: Exception) {
            Log.w(TAG, "Could not parse cached MCP tools for $serverId", e)
            emptyList()
        }
    }

    fun setCachedTools(serverId: String, tools: List<McpTool>) {
        try {
            prefs.edit()
                .putString("cached_tools_$serverId", gson.toJson(tools))
                .apply()
        } catch (e: Exception) {
            Log.w(TAG, "Could not cache MCP tools for $serverId", e)
        }
    }

    fun clearCachedTools(serverId: String) {
        prefs.edit().remove("cached_tools_$serverId").apply()
    }

    // ------------------------------------------------------------------
    // Backup / restore
    // ------------------------------------------------------------------

    fun exportState(): JSONObject {
        val json = JSONObject()
        try {
            json.put("customServers", JSONArray(gson.toJson(getCustomServers())))
        } catch (e: Exception) {
            Log.w(TAG, "Could not export custom MCP servers", e)
        }
        val builtIn = JSONObject()
        try {
            prefs.all.forEach { (key, value) ->
                if (key.startsWith(BUILTIN_PREFIX) && value is Boolean) {
                    builtIn.put(key.removePrefix(BUILTIN_PREFIX), value)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not export built-in MCP toggles", e)
        }
        json.put("builtInEnabled", builtIn)
        return json
    }

    /**
     * Merges backed-up MCP state into the current install. Existing servers and
     * toggles are never removed or overwritten; only missing entries and blank
     * tokens are filled in.
     */
    fun importState(json: JSONObject?) {
        if (json == null) return

        val incoming = try {
            val type = object : TypeToken<List<McpCustomServer>>() {}.type
            gson.fromJson<List<McpCustomServer>>(
                json.optJSONArray("customServers")?.toString() ?: "[]",
                type
            ).orEmpty()
        } catch (e: Exception) {
            Log.w(TAG, "Could not parse backed-up custom MCP servers", e)
            emptyList()
        }

        if (incoming.isNotEmpty()) {
            val current = getCustomServers().toMutableList()
            var changed = false
            incoming.forEach { raw ->
                val candidate = sanitizeImportedServer(raw) ?: return@forEach
                val index = current.indexOfFirst {
                    it.id == candidate.id || it.url.equals(candidate.url, ignoreCase = true)
                }
                if (index < 0) {
                    current += candidate
                    changed = true
                } else if (current[index].token.isBlank() && candidate.token.isNotBlank()) {
                    current[index] = current[index].copy(token = candidate.token)
                    changed = true
                }
            }
            if (changed) saveCustomServers(current)
        }

        val builtIn = json.optJSONObject("builtInEnabled") ?: return
        val editor = prefs.edit()
        var changed = false
        val keys = builtIn.keys()
        while (keys.hasNext()) {
            val id = keys.next()
            // Ignore unknown IDs from a corrupted or hand-edited backup so a
            // restore cannot create phantom servers in the settings list.
            if (BuiltInMcpRegistry.findServer(id) == null) continue
            val key = "$BUILTIN_PREFIX$id"
            if (!prefs.contains(key) && builtIn.opt(id) is Boolean) {
                editor.putBoolean(key, builtIn.getBoolean(id))
                changed = true
            }
        }
        if (changed) editor.apply()
    }

    /**
     * A backup is untrusted input. Re-validate every field that the add-server
     * form normally validates, so a modified backup cannot inject an insecure
     * URL, a multiline token, or an ID that collides with another server.
     */
    private fun sanitizeImportedServer(server: McpCustomServer): McpCustomServer? {
        // Gson can leave non-null Kotlin fields as null when a hand-edited
        // backup omits them, so read every field defensively.
        val id = (server.id as String?)?.trim().orEmpty()
        if (!CUSTOM_ID_REGEX.matches(id)) return null
        val name = (server.name as String?)?.trim().orEmpty().replace(Regex("\\s+"), " ")
        if (name.length !in 1..60 || name.any { it.isISOControl() }) return null
        val url = McpManager.normalizeMcpUrl((server.url as String?).orEmpty()) ?: return null
        val token = (server.token as String?)?.trim().orEmpty()
        if (token.contains('\n') || token.contains('\r')) return null
        return server.copy(id = id, name = name, url = url, token = token)
    }

    // ------------------------------------------------------------------
    // Encrypted storage helpers
    // ------------------------------------------------------------------

    private fun readCustomRaw(): String? {
        val secure = securePrefs
        if (secure != null && !secureStorageBroken) {
            try {
                val value = secure.getString(KEY_CUSTOM_SERVERS, null)
                if (value != null) return value
            } catch (e: Exception) {
                Log.e(TAG, "Encrypted MCP preferences could not be read; falling back", e)
                secureStorageBroken = true
            }
        }
        return try {
            prefs.getString(KEY_CUSTOM_SERVERS, null)
        } catch (e: Exception) {
            Log.e(TAG, "Could not read fallback MCP preferences", e)
            null
        }
    }

    private fun writeCustomRaw(json: String): Boolean {
        val secure = securePrefs
        if (secure != null && !secureStorageBroken) {
            try {
                if (secure.edit().putString(KEY_CUSTOM_SERVERS, json).commit()) return true
            } catch (e: Exception) {
                Log.e(TAG, "Encrypted MCP preferences could not be written; falling back", e)
                secureStorageBroken = true
            }
        }
        return try {
            prefs.edit().putString(KEY_CUSTOM_SERVERS, json).commit()
        } catch (e: Exception) {
            Log.e(TAG, "Could not write fallback MCP preferences", e)
            false
        }
    }

    private companion object {
        const val TAG = "McpPreferences"
        const val NORMAL_PREFS = "jarvis_mcp_prefs"
        const val SECURE_PREFS = "jarvis_mcp_secure_prefs"
        const val KEY_CUSTOM_SERVERS = "custom_mcp_servers"
        const val BUILTIN_PREFIX = "builtin_enabled_"

        /** Same shape as IDs generated by [McpManager.addCustomServer]. */
        val CUSTOM_ID_REGEX = Regex("^[A-Za-z0-9_-]{4,64}$")
    }
}
