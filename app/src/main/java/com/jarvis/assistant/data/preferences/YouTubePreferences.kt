package com.jarvis.assistant.data.preferences

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.jarvis.assistant.data.backup.BackupTriggers

class YouTubePreferences(context: Context) {

    companion object {
        private const val TAG = "YouTubePreferences"
        private const val ENCRYPTED_FILE = "jarvis_youtube_secure_prefs"
        private const val FALLBACK_FILE = "jarvis_youtube_prefs"
        private const val KEY_YOUTUBE_API_KEY = "key_youtube_api_key"
        private const val KEY_YOUTUBE_ENABLED = "key_youtube_enabled"
    }

    private val appContext = context.applicationContext
    private val legacyPrefs = appContext.getSharedPreferences(FALLBACK_FILE, Context.MODE_PRIVATE)
    private val securePrefs: SharedPreferences? = try {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
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

    val canStoreSecurely: Boolean get() = securePrefs != null

    var youtubeApiKey: String
        get() {
            val secureValue = try {
                securePrefs?.getString(KEY_YOUTUBE_API_KEY, "")?.trim().orEmpty()
            } catch (e: Exception) {
                Log.e(TAG, "Could not read the encrypted YouTube API key; trying legacy storage", e)
                ""
            }
            if (secureValue.isNotEmpty()) return secureValue
            return try {
                legacyPrefs.getString(KEY_YOUTUBE_API_KEY, "")?.trim().orEmpty()
            } catch (e: Exception) {
                Log.e(TAG, "Could not read the legacy YouTube API key", e)
                ""
            }
        }
        set(value) {
            val clean = value.trim()
            val secure = securePrefs
            val saved = try {
                if (secure != null) {
                    if (clean.isEmpty()) secure.edit().remove(KEY_YOUTUBE_API_KEY).commit()
                    else secure.edit().putString(KEY_YOUTUBE_API_KEY, clean).commit()
                } else {
                    if (clean.isEmpty()) legacyPrefs.edit().remove(KEY_YOUTUBE_API_KEY).commit()
                    else legacyPrefs.edit().putString(KEY_YOUTUBE_API_KEY, clean).commit()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Could not save the YouTube API key to encrypted storage", e)
                false
            }
            if (!saved) {
                // Keep the key functional instead of losing it when the Keystore
                // is temporarily unavailable.
                if (clean.isEmpty()) legacyPrefs.edit().remove(KEY_YOUTUBE_API_KEY).commit()
                else legacyPrefs.edit().putString(KEY_YOUTUBE_API_KEY, clean).commit()
            } else if (secure != null) {
                legacyPrefs.edit().remove(KEY_YOUTUBE_API_KEY).apply()
            }
            BackupTriggers.request("youtube-key")
        }

    var isYouTubeEnabled: Boolean
        get() = try {
            (securePrefs ?: legacyPrefs).getBoolean(KEY_YOUTUBE_ENABLED, true)
        } catch (e: Exception) {
            true
        }
        set(value) {
            try {
                (securePrefs ?: legacyPrefs).edit().putBoolean(KEY_YOUTUBE_ENABLED, value).apply()
            } catch (e: Exception) {
                legacyPrefs.edit().putBoolean(KEY_YOUTUBE_ENABLED, value).apply()
            }
            BackupTriggers.request("youtube-enabled")
        }

    /** Restores a backed-up key only when this install has none. */
    fun restoreApiKeyIfEmpty(value: String): Boolean {
        val clean = value.trim()
        if (clean.isEmpty() || youtubeApiKey.isNotBlank()) return false
        return try {
            youtubeApiKey = clean
            youtubeApiKey.isNotBlank()
        } catch (e: Exception) {
            Log.e(TAG, "Could not restore the YouTube API key", e)
            false
        }
    }

    private fun migrateLegacyKey() {
        val legacy = try {
            legacyPrefs.getString(KEY_YOUTUBE_API_KEY, null)?.trim().orEmpty()
        } catch (e: Exception) {
            Log.w(TAG, "Could not read the legacy YouTube key", e)
            ""
        }
        if (legacy.isEmpty()) return
        val secure = securePrefs ?: run {
            // Never delete the only copy just because encrypted storage is not
            // available on this device.
            Log.w(TAG, "Encrypted storage unavailable; keeping the legacy YouTube key")
            return
        }
        try {
            if (secure.getString(KEY_YOUTUBE_API_KEY, null).isNullOrEmpty()) {
                check(secure.edit().putString(KEY_YOUTUBE_API_KEY, legacy).commit())
            }
            if (!secure.getString(KEY_YOUTUBE_API_KEY, null).isNullOrEmpty()) {
                legacyPrefs.edit().remove(KEY_YOUTUBE_API_KEY).apply()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Legacy YouTube API-key migration failed", e)
        }
    }
}
