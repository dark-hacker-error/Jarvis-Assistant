package com.jarvis.assistant.security

import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest
import kotlin.system.exitProcess

/**
 * JARVIS ASSISTANT — SECURITY MODULE
 * Developer       : Tech GPTx
 * Copyright © 2026: Tech GPTx. All Rights Reserved.
 * GitHub          : https://github.com/techgptx/Playlist
 */
object SecurityManager {

    const val DEVELOPER_NAME    = "Tech GPTx"
    const val COPYRIGHT_NOTICE  = "Copyright © 2026 Tech GPTx. All Rights Reserved."
    const val GITHUB_REPO       = "https://github.com/techgptx/Playlist"
    const val LICENSE_NAME      = "TAMCL v1.0 — Content creators MUST credit Tech GPTx"

    private const val EXPECTED_PACKAGE = "com.jarvis.assistant"

    fun verify(context: Context) {
        checkPackageName(context)
    }

    private fun checkPackageName(context: Context) {
        if (context.packageName != EXPECTED_PACKAGE) {
            showTamperAlert(
                context,
                "Clone Detected",
                "This is an unauthorised clone of the JARVIS app by $DEVELOPER_NAME.\n\n" +
                "Original: $EXPECTED_PACKAGE\n" +
                "Found   : ${context.packageName}\n\n" +
                "The app will now close. Please download the official version from:\n$GITHUB_REPO"
            )
        }
    }

    @Suppress("unused")
    private fun checkSignature(context: Context) {
        val EXPECTED_SHA256 = "PASTE_YOUR_APK_SHA256_FINGERPRINT_HERE"

        val actual = getApkSignatureSha256(context) ?: run {
            showTamperAlert(context, "Verification Failed", "Unable to verify app authenticity. Closing.")
            return
        }
        if (!actual.equals(EXPECTED_SHA256, ignoreCase = true)) {
            showTamperAlert(
                context,
                "Tampered APK Detected",
                "This APK has been re-signed or modified.\n\n" +
                "$DEVELOPER_NAME only distributes APKs signed with the original certificate.\n\n" +
                "Download the official APK from:\n$GITHUB_REPO"
            )
        }
    }

    private fun getApkSignatureSha256(context: Context): String? {
        return try {
            val sigs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val info = context.packageManager.getPackageInfo(
                    context.packageName, PackageManager.GET_SIGNING_CERTIFICATES
                )
                info.signingInfo?.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                val info = context.packageManager.getPackageInfo(
                    context.packageName, PackageManager.GET_SIGNATURES
                )
                info.signatures
            }
            val firstSig = sigs?.firstOrNull() ?: return null
            val md = MessageDigest.getInstance("SHA-256")
            val digest = md.digest(firstSig.toByteArray())
            digest.joinToString(":") { "%02X".format(it) }
        } catch (e: Exception) {
            null
        }
    }

    private fun showTamperAlert(context: Context, title: String, message: String) {
        try {
            AlertDialog.Builder(context)
                .setTitle("⚠️ $title")
                .setMessage(message)
                .setCancelable(false)
                .setPositiveButton("OK") { _, _ -> exitProcess(1) }
                .show()
        } catch (_: Exception) {
            exitProcess(1)
        }
    }
}
