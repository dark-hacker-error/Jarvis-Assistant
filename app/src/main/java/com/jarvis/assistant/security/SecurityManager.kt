package com.jarvis.assistant.security

import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.jarvis.assistant.BuildConfig
import java.security.MessageDigest
import kotlin.system.exitProcess

/**
 * JARVIS ASSISTANT — SECURITY MODULE
 * Developer       : Roshan Hacker
 * Copyright © 2026: Roshan Hacker. All Rights Reserved.
 * Website         : https://roshanx.netlify.app/
 */
object SecurityManager {

    const val DEVELOPER_NAME    = "Roshan Hacker"
    const val COPYRIGHT_NOTICE  = "Copyright © 2026 Roshan Hacker. All Rights Reserved."
    const val GITHUB_REPO       = "https://roshanx.netlify.app/"
    const val LICENSE_NAME      = "TAMCL v1.0 — Content creators MUST credit Roshan Hacker"

    private const val EXPECTED_PACKAGE = "com.jarvis.assistant"

    fun verify(context: Context) {
        checkPackageName(context)
        checkSignature(context)
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

    private fun checkSignature(context: Context) {
        // Debug builds are signed with the developer's local debug key, so the
        // release pin intentionally does not apply to them.
        if (BuildConfig.DEBUG) return

        val expected = BuildConfig.PINNED_RELEASE_CERT_SHA256
            .filter(Char::isLetterOrDigit)
            .uppercase()
        if (expected.length != 64) {
            // Release packaging already refuses to build without this pin. Stay
            // non-destructive at runtime if a custom build bypasses that check.
            Log.w("SecurityManager", "Release certificate pin is not configured; signature check skipped")
            return
        }

        val signers = AppIdentityVerifier.currentSignerSha256(context)
        if (signers.isEmpty()) {
            showTamperAlert(context, "Verification Failed", "Unable to verify app authenticity. Closing.")
            return
        }
        if (signers.none { it.equals(expected, ignoreCase = true) }) {
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
