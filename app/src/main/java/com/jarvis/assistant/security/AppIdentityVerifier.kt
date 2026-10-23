package com.jarvis.assistant.security

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.jarvis.assistant.BuildConfig
import java.security.MessageDigest

/** Local identity check. Remote publisher verification still requires the configured HTTPS service. */
object AppIdentityVerifier {
    fun currentSignerSha256(context: Context): List<String> = runCatching {
        val packageManager = context.packageManager
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo?.apkContentsSigners.orEmpty()
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures.orEmpty()
        }
        signatures.map { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
                .joinToString("") { byte -> "%02X".format(byte) }
        }.distinct().sorted()
    }.getOrDefault(emptyList())

    fun isTrustedRelease(context: Context): Boolean {
        if (BuildConfig.DEBUG) return true
        val expected = BuildConfig.PINNED_RELEASE_CERT_SHA256
            .split(',', ';', '\n')
            .map { it.filter(Char::isLetterOrDigit).uppercase() }
            .filter { it.length == 64 }
            .toSet()
        if (expected.isEmpty()) return false
        return currentSignerSha256(context).any { it in expected }
    }

    fun report(context: Context): String {
        val signers = currentSignerSha256(context).ifEmpty { listOf("Unavailable") }
        val localSignature = if (BuildConfig.DEBUG) "Debug build (not publisher verified)"
        else if (isTrustedRelease(context)) "Matches the pinned release certificate"
        else "NOT VERIFIED — release certificate pin missing or mismatched"
        val remote = if (BuildConfig.VERIFICATION_ENDPOINT.startsWith("https://")) {
            "Endpoint configured, but this build does not yet implement a remote verification protocol"
        } else "Remote publisher verification is not configured"
        return buildString {
            appendLine("Developer: ${BuildConfig.DEVELOPER_NAME}")
            appendLine("Copyright: ${BuildConfig.COPYRIGHT_OWNER}")
            appendLine("Official application ID: ${context.packageName}")
            appendLine("Current signing certificate SHA-256:")
            signers.forEach(::appendLine)
            appendLine("Local signature status: $localSignature")
            append("Server status: $remote")
        }
    }
}
