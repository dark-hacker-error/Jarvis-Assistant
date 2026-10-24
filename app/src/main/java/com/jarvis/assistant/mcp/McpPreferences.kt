package com.jarvis.assistant.mcp

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Persistence for MCP server toggles and custom server definitions.
 *
 * Built-in toggles are harmless and live in normal preferences. Custom server
 * records can contain bearer tokens, so they are kept in encrypted storage
 * whenever the platform keystore is available. If encrypted storage cannot be
 * initialized, the UI warns the user and falls back to normal app-private
 * preferences rather than silently dropping their configuration.
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

    val hasSecureStorage: Boolean
        get() = securePrefs != null

    fun isBuiltInEnabled(serverId: String): Boolean =
        prefs.getBoolean("builtin_enabled_$serverId", false)

    fun setBuiltInEnabled(serverId: String, enabled: Boolean) {
        prefs.edit().putBoolean("builtin_enabled_$serverId", enabled).apply()
    }

    fun getCustomServers(): List<McpCustomServer> {
        val raw = customPrefs().getString(KEY_CUSTOM_SERVERS, null) ?: return emptyList()
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
        try {
            customPrefs().edit()
                .putString(KEY_CUSTOM_SERVERS, gson.toJson(servers))
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "Could not save custom MCP servers", e)
        }
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

    private fun customPrefs(): SharedPreferences = securePrefs ?: prefs

    private companion object {
        const val TAG = "McpPreferences"
        const val NORMAL_PREFS = "jarvis_mcp_prefs"
        const val SECURE_PREFS = "jarvis_mcp_secure_prefs"
        const val KEY_CUSTOM_SERVERS = "custom_mcp_servers"
    }
}
