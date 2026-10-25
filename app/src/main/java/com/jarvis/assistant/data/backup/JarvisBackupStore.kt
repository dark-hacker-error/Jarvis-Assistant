package com.jarvis.assistant.data.backup

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.util.Base64
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Survives an app uninstall by storing one encrypted snapshot in the public
 * Downloads collection. App-private storage is deleted by Android on uninstall,
 * while files created through MediaStore in Downloads are not.
 *
 * The payload is encrypted with AES-256-GCM. The key is derived from
 * ANDROID_ID, the package name and the signing-certificate fingerprint. On
 * Android 8+ ANDROID_ID is stable for the same app-signing key, so a reinstall
 * signed with the same key can decrypt the snapshot. A different signing key
 * (or a different device) fails closed and the app keeps working with local
 * data instead of crashing.
 *
 * No permission is required on Android 10+ because the app owns the MediaStore
 * row it creates. Android 8/9 fall back to a direct file and require the legacy
 * WRITE_EXTERNAL_STORAGE permission.
 */
class JarvisBackupStore(private val context: Context) {

    data class BackupFile(
        val payload: String,
        val savedAt: Long,
        val location: String
    )

    data class Status(
        val available: Boolean,
        val savedAt: Long = 0L,
        val location: String = "",
        val error: String = ""
    )

    private val resolver = context.contentResolver
    private val secureRandom = SecureRandom()

    fun write(payload: String): Result<BackupFile> = runCatching {
        val now = System.currentTimeMillis()
        val envelope = encrypt(payload, now)
        val bytes = envelope.toString().toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_BACKUP_BYTES) {
            throw IOException("Backup is larger than the ${MAX_BACKUP_BYTES / 1024 / 1024} MB limit")
        }
        val location = writeBytes(bytes)
        Log.i(TAG, "Backup written to $location (${bytes.size} bytes)")
        BackupFile(payload = payload, savedAt = now, location = location)
    }.recoverCatching { error ->
        Log.e(TAG, "Could not write the persistent backup", error)
        throw IOException(describe(error), error)
    }

    fun read(): Result<BackupFile> = runCatching {
        val (bytes, location) = readBytes()
        if (bytes.isEmpty()) throw IOException("Backup file is empty")
        if (bytes.size > MAX_BACKUP_BYTES) throw IOException("Backup file is too large")
        val envelope = try {
            JSONObject(String(bytes, Charsets.UTF_8))
        } catch (e: Exception) {
            throw IOException("Backup file is not valid JSON", e)
        }
        if (envelope.optString("format") != ENVELOPE_FORMAT) {
            throw IOException("Unrecognised backup format")
        }
        if (envelope.optInt("version", 0) != ENVELOPE_VERSION) {
            throw IOException("Unsupported backup version ${envelope.optInt("version", 0)}")
        }
        val payload = decrypt(envelope)
        BackupFile(
            payload = payload,
            savedAt = envelope.optLong("createdAt", 0L),
            location = location
        )
    }.recoverCatching { error ->
        throw IOException(describe(error), error)
    }

    fun status(): Status {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val uri = findDownloadUri() ?: return Status(available = false)
                Status(
                    available = true,
                    savedAt = lastModified(uri),
                    location = DISPLAY_LOCATION
                )
            } else {
                val file = legacyFile()
                if (!file.exists()) return Status(available = false)
                Status(
                    available = true,
                    savedAt = file.lastModified(),
                    location = DISPLAY_LOCATION
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not inspect the backup file", e)
            Status(available = false, error = describe(e))
        }
    }

    fun delete(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val uri = findDownloadUri() ?: return true
                resolver.delete(uri, null, null) > 0
            } else {
                val file = legacyFile()
                !file.exists() || file.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not delete the backup file", e)
            false
        }
    }

    // ------------------------------------------------------------------
    // Encryption
    // ------------------------------------------------------------------

    private fun encrypt(payload: String, createdAt: Long): JSONObject {
        val salt = ByteArray(SALT_BYTES).also(secureRandom::nextBytes)
        val iv = ByteArray(IV_BYTES).also(secureRandom::nextBytes)
        val key = deriveKey(salt)
        val cipher = Cipher.getInstance(CIPHER)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(aad())
        val encrypted = cipher.doFinal(payload.toByteArray(Charsets.UTF_8))
        return JSONObject()
            .put("format", ENVELOPE_FORMAT)
            .put("version", ENVELOPE_VERSION)
            .put("createdAt", createdAt)
            .put("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .put("iv", Base64.encodeToString(iv, Base64.NO_WRAP))
            .put("data", Base64.encodeToString(encrypted, Base64.NO_WRAP))
    }

    private fun decrypt(envelope: JSONObject): String {
        val salt = Base64.decode(envelope.optString("salt"), Base64.NO_WRAP)
        val iv = Base64.decode(envelope.optString("iv"), Base64.NO_WRAP)
        val encrypted = Base64.decode(envelope.optString("data"), Base64.NO_WRAP)
        if (salt.size < 8 || iv.size != IV_BYTES || encrypted.isEmpty()) {
            throw IOException("Backup envelope is incomplete")
        }
        val key = deriveKey(salt)
        val cipher = Cipher.getInstance(CIPHER)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(aad())
        return try {
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        } catch (e: Exception) {
            throw IOException(
                "Could not decrypt the backup. It was probably created by a different signing key or on another device.",
                e
            )
        }
    }

    private fun deriveKey(salt: ByteArray): SecretKeySpec {
        val secret = buildString {
            append(KEY_PREFIX)
            append('|').append(context.packageName)
            append('|').append(androidId())
            append('|').append(signingFingerprint())
        }
        val spec = PBEKeySpec(secret.toCharArray(), salt, PBKDF2_ITERATIONS, 256)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val keyBytes = factory.generateSecret(spec).encoded
        return SecretKeySpec(keyBytes, "AES")
    }

    private fun aad(): ByteArray =
        "jarvis-backup|${context.packageName}|$ENVELOPE_VERSION".toByteArray(Charsets.UTF_8)

    private fun androidId(): String = try {
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()
    } catch (e: Exception) {
        Log.w(TAG, "ANDROID_ID unavailable", e)
        ""
    }

    private fun signingFingerprint(): String {
        return try {
            val packageManager = context.packageManager
            val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNING_CERTIFICATES
                ).signingInfo?.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNATURES
                ).signatures
            }
            val bytes = signatures?.firstOrNull()?.toByteArray() ?: ByteArray(0)
            if (bytes.isEmpty()) return context.packageName
            MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { byte -> "%02x".format(byte) }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read the signing certificate; using package name", e)
            context.packageName
        }
    }

    // ------------------------------------------------------------------
    // Downloads storage
    // ------------------------------------------------------------------

    private fun writeBytes(bytes: ByteArray): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            writeViaMediaStore(bytes)
            DISPLAY_LOCATION
        } else {
            writeLegacy(bytes)
            DISPLAY_LOCATION
        }
    }

    private fun readBytes(): Pair<ByteArray, String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val uri = findDownloadUri()
                ?: throw FileNotFoundException("No Jarvis backup found in $DISPLAY_LOCATION")
            val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw IOException("Could not open the Jarvis backup file")
            bytes to DISPLAY_LOCATION
        } else {
            val file = legacyFile()
            if (!file.exists()) throw FileNotFoundException("No Jarvis backup found in $DISPLAY_LOCATION")
            file.readBytes() to DISPLAY_LOCATION
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun writeViaMediaStore(bytes: ByteArray) {
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val existing = findDownloadUri()
        if (existing != null) {
            try {
                resolver.openOutputStream(existing, "wt")?.use { stream ->
                    stream.write(bytes)
                    stream.flush()
                } ?: throw IOException("Could not open the backup file for writing")
                return
            } catch (e: Exception) {
                // The row may be stale or owned by a previous install. Replace it.
                Log.w(TAG, "Updating the existing backup failed; recreating it", e)
                try {
                    resolver.delete(existing, null, null)
                } catch (_: Exception) {
                    // Best effort.
                }
            }
        }

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, BACKUP_FILE_NAME)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
            put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_DIR)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values)
            ?: throw IOException("Could not create the backup file in Downloads")
        try {
            resolver.openOutputStream(uri, "wt")?.use { stream ->
                stream.write(bytes)
                stream.flush()
            } ?: throw IOException("Could not open the new backup file for writing")
            val ready = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            resolver.update(uri, ready, null, null)
        } catch (e: Exception) {
            try {
                resolver.delete(uri, null, null)
            } catch (_: Exception) {
                // Best effort cleanup.
            }
            throw e
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun findDownloadUri(): Uri? {
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND " +
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
        val args = arrayOf(BACKUP_FILE_NAME, "$RELATIVE_DIR%")
        val order = "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"

        try {
            resolver.query(collection, projection, selection, args, order)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    return ContentUris.withAppendedId(collection, cursor.getLong(0))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "MediaStore query with relative path failed; retrying by name", e)
        }

        // Some OEM providers do not index RELATIVE_PATH consistently. A name-only
        // lookup is still safe because the file is encrypted and versioned.
        return try {
            resolver.query(
                collection,
                projection,
                "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
                arrayOf(BACKUP_FILE_NAME),
                order
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    ContentUris.withAppendedId(collection, cursor.getLong(0))
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "MediaStore lookup failed", e)
            null
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun lastModified(uri: Uri): Long {
        return try {
            resolver.query(
                uri,
                arrayOf(MediaStore.MediaColumns.DATE_MODIFIED),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) * 1_000L else 0L
            } ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    @Suppress("DEPRECATION")
    private fun writeLegacy(bytes: ByteArray) {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            throw IOException(
                "Storage permission is required to write the backup on Android 9 and older"
            )
        }
        val directory = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "Jarvis"
        )
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("Could not create $DISPLAY_LOCATION")
        }
        val target = File(directory, BACKUP_FILE_NAME)
        val temporary = File(directory, "$BACKUP_FILE_NAME.tmp")
        temporary.writeBytes(bytes)
        if (!temporary.renameTo(target)) {
            target.writeBytes(bytes)
            temporary.delete()
        }
    }

    @Suppress("DEPRECATION")
    private fun legacyFile(): File = File(
        File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "Jarvis"
        ),
        BACKUP_FILE_NAME
    )

    private fun describe(error: Throwable): String {
        val message = error.message?.trim().orEmpty()
        return message.ifBlank { error.javaClass.simpleName }
    }

    companion object {
        private const val TAG = "JarvisBackupStore"
        private const val ENVELOPE_FORMAT = "jarvis-backup"
        private const val ENVELOPE_VERSION = 1
        private const val KEY_PREFIX = "jarvis-backup-key-v1"
        private const val CIPHER = "AES/GCM/NoPadding"
        private const val PBKDF2_ITERATIONS = 100_000
        private const val SALT_BYTES = 16
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
        private const val MAX_BACKUP_BYTES = 8 * 1024 * 1024

        const val BACKUP_FILE_NAME = "jarvis_backup.json"
        const val RELATIVE_DIR = "Download/Jarvis/"
        const val DISPLAY_LOCATION = "Downloads/Jarvis/$BACKUP_FILE_NAME"
    }
}
