package com.jarvis.assistant.data.preferences

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class YouTubePreferences(context: Context) {

    companion object {
        private const val TAG = "YouTubePreferences"
        private const val ENCRYPTED_FILE = "jarvis_youtube_secure_prefs"
        private const val FALLBACK_FILE = "jarvis_youtube_prefs"
        private const val KEY_YOUTUBE_API_KEY = "key_youtube_api_key"
        private const val KEY_YOUTUBE_ENABLED = "key_youtube_enabled"
    }

    private val legacyPrefs = context.getSharedPreferences(FALLBACK_FILE, Context.MODE_PRIVATE)
    private val securePrefs: SharedPreferences? = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            ENCRYPTED_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        Log.e(TAG, "Encrypted YouTube API-key storage could not be initialized", e)
        null
    }

    init {
        migrateLegacyKey()
    }

    var youtubeApiKey: String
        get() = securePrefs?.getString(KEY_YOUTUBE_API_KEY, "") ?: ""
        set(value) {
            val secure = checkNotNull(securePrefs) { "Secure YouTube API-key storage is unavailable on this device." }
            val clean = value.trim()
            val saved = if (clean.isEmpty()) secure.edit().remove(KEY_YOUTUBE_API_KEY).commit()
            else secure.edit().putString(KEY_YOUTUBE_API_KEY, clean).commit()
            check(saved) { "Could not save YouTube API key to encrypted storage." }
        }

    var isYouTubeEnabled: Boolean
        get() = (securePrefs ?: legacyPrefs).getBoolean(KEY_YOUTUBE_ENABLED, true)
        set(value) = (securePrefs ?: legacyPrefs).edit().putBoolean(KEY_YOUTUBE_ENABLED, value).apply()

    private fun migrateLegacyKey() {
        val legacy = legacyPrefs.getString(KEY_YOUTUBE_API_KEY, null)?.trim().orEmpty()
        if (legacy.isEmpty()) return
        val secure = securePrefs ?: run {
            legacyPrefs.edit().remove(KEY_YOUTUBE_API_KEY).commit()
            return
        }
        try {
            if (secure.getString(KEY_YOUTUBE_API_KEY, null).isNullOrEmpty()) {
                check(secure.edit().putString(KEY_YOUTUBE_API_KEY, legacy).commit())
            }
            if (!secure.getString(KEY_YOUTUBE_API_KEY, null).isNullOrEmpty()) {
                legacyPrefs.edit().remove(KEY_YOUTUBE_API_KEY).commit()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Legacy YouTube API-key migration failed", e)
        }
    }
}
