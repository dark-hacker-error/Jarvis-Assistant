package com.jarvis.assistant.system

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.IBinder
import android.util.Log
import com.jarvis.assistant.BuildConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import rikka.shizuku.Shizuku

/**
 * Central Shizuku access point.
 *
 * Uses the current Shizuku UserService API instead of the removed
 * `Shizuku.newProcess` API. The remote service runs with adb shell (uid 2000)
 * or root (uid 0) privileges, while all permission and dangerous-action gates
 * remain in this process.
 */
class ShizukuManager(context: Context) {

    data class ShellResult(
        val success: Boolean,
        val exitCode: Int,
        val output: String,
        val error: String = ""
    )

    private val appContext = context.applicationContext
    private val bindMutex = Mutex()

    @Volatile
    private var shellService: IJarvisShellService? = null

    @Volatile
    private var pendingBind: CompletableDeferred<IJarvisShellService?>? = null

    @Volatile
    private var lastBindError: String = ""

    private val userServiceArgs by lazy {
        Shizuku.UserServiceArgs(
            ComponentName(appContext, JarvisShellService::class.java)
        )
            .daemon(false)
            .processNameSuffix("jarvis_shell")
            .debuggable(BuildConfig.DEBUG)
            .version(USER_SERVICE_VERSION)
            .tag(USER_SERVICE_TAG)
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = if (binder != null) {
                try {
                    IJarvisShellService.Stub.asInterface(binder)
                } catch (e: Exception) {
                    Log.e(TAG, "Could not create shell service interface", e)
                    null
                }
            } else {
                null
            }
            shellService = service
            lastBindError = if (service == null) "Shizuku returned an empty shell service" else ""
            pendingBind?.complete(service)
            pendingBind = null
            Log.i(TAG, "Shizuku user service connected: ${service != null}")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            shellService = null
            Log.w(TAG, "Shizuku user service disconnected")
        }

        override fun onBindingDied(name: ComponentName?) {
            shellService = null
            Log.w(TAG, "Shizuku user service binding died")
        }

        override fun onNullBinding(name: ComponentName?) {
            shellService = null
            lastBindError = "Shizuku could not start the privileged shell service"
            pendingBind?.complete(null)
            pendingBind = null
            Log.w(TAG, "Shizuku user service returned a null binding")
        }
    }

    /**
     * True when either the Shizuku service is already alive or one of its known
     * packages is installed. Checking the binder first also covers installs
     * whose package name is hidden by a vendor/OEM package-visibility quirk.
     */
    fun isShizukuInstalled(): Boolean {
        if (isBinderAvailable()) return true
        return installedShizukuPackage() != null
    }

    /** Current package is moe.shizuku.manager; legacy builds used the API name. */
    fun installedShizukuPackage(): String? {
        for (pkg in SHIZUKU_PACKAGES) {
            try {
                appContext.packageManager.getPackageInfo(pkg, 0)
                return pkg
            } catch (_: PackageManager.NameNotFoundException) {
                // Try the next known package name.
            } catch (e: Exception) {
                Log.w(TAG, "Could not inspect Shizuku package $pkg: ${e.message}")
            }
        }
        return null
    }

    fun isBinderAvailable(): Boolean = try {
        Shizuku.pingBinder()
    } catch (e: Exception) {
        Log.w(TAG, "Shizuku binder unavailable: ${e.message}")
        false
    }

    fun hasPermission(): Boolean {
        if (!isBinderAvailable()) return false
        return try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            Log.w(TAG, "Could not check Shizuku permission: ${e.message}")
            false
        }
    }

    fun shouldShowPermissionRationale(): Boolean = try {
        isBinderAvailable() && Shizuku.shouldShowRequestPermissionRationale()
    } catch (_: Exception) {
        false
    }

    /**
     * Returns false when the Shizuku binder is not ready or the request was
     * rejected by the Shizuku app, so the UI can show a manual-grant fallback.
     */
    fun requestPermission(requestCode: Int = PERMISSION_REQUEST_CODE): Boolean {
        if (!isBinderAvailable()) return false
        return try {
            Shizuku.requestPermission(requestCode)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Shizuku permission request failed", e)
            false
        }
    }

    fun isUserServiceReady(): Boolean {
        val service = shellService ?: return false
        return try {
            service.asBinder().isBinderAlive
        } catch (_: Exception) {
            false
        }
    }

    fun isReady(): Boolean = hasPermission() && isUserServiceReady()

    fun statusText(): String {
        if (!isShizukuInstalled()) {
            return "Shizuku app install nahi hai"
        }
        if (!isBinderAvailable()) {
            return "Shizuku service running nahi hai"
        }
        if (!hasPermission()) {
            return if (shouldShowPermissionRationale()) {
                "Permission denied • Settings me manually allow karein"
            } else {
                "Shizuku permission required"
            }
        }
        val uid = try {
            Shizuku.getUid()
        } catch (_: Exception) {
            -1
        }
        val mode = when (uid) {
            0 -> "ROOT"
            2000 -> "ADB SHELL"
            else -> "UID $uid"
        }
        return if (isUserServiceReady()) {
            "Connected • $mode • service ready"
        } else {
            "Permission granted • shell service initializing"
        }
    }

    fun statusDetails(): String {
        if (!isShizukuInstalled()) {
            return "Shizuku is required for system-level controls. Install it, start it with wireless debugging/ADB or root, then return here."
        }
        if (!isBinderAvailable()) {
            return "Shizuku is installed but its service is not running. Open Shizuku and start it, then return here."
        }
        if (!hasPermission()) {
            return "Jarvis has not been granted Shizuku access yet. Tap 'Request Shizuku Permission'."
        }
        val version = try {
            Shizuku.getVersion()
        } catch (_: Exception) {
            -1
        }
        val uid = try {
            Shizuku.getUid()
        } catch (_: Exception) {
            -1
        }
        return buildString {
            append("Shizuku API v")
            append(version)
            append(" • UID ")
            append(uid)
            append(if (uid == 0) " (root)" else " (adb shell)")
            if (lastBindError.isNotBlank()) {
                append("\n")
                append(lastBindError)
            }
        }
    }

    fun openShizukuManager(): Boolean {
        val packages = buildList {
            installedShizukuPackage()?.let(::add)
            addAll(SHIZUKU_PACKAGES)
        }.distinct()
        for (pkg in packages) {
            val launchIntent = appContext.packageManager.getLaunchIntentForPackage(pkg) ?: continue
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return try {
                appContext.startActivity(launchIntent)
                true
            } catch (e: Exception) {
                Log.w(TAG, "Could not open Shizuku package $pkg: ${e.message}")
                false
            }
        }
        return openShizukuDownloadPage()
    }

    fun openShizukuDownloadPage(): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            appContext.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Binds the privileged user service. Safe to call repeatedly; only one
     * binding is attempted at a time.
     */
    suspend fun ensureUserService(): Boolean {
        if (!hasPermission()) return false
        val current = shellService
        if (current != null && isUserServiceReady()) return true

        return bindMutex.withLock {
            val rechecked = shellService
            if (rechecked != null && isUserServiceReady()) {
                return@withLock true
            }
            val deferred = CompletableDeferred<IJarvisShellService?>()
            pendingBind = deferred
            try {
                withContext(Dispatchers.Main) {
                    Shizuku.bindUserService(userServiceArgs, serviceConnection)
                }
            } catch (e: Exception) {
                lastBindError = e.message ?: "Could not bind Shizuku user service"
                pendingBind = null
                deferred.complete(null)
                Log.e(TAG, lastBindError, e)
            }

            val service = try {
                withTimeout(BIND_TIMEOUT_MS) { deferred.await() }
            } catch (_: TimeoutCancellationException) {
                lastBindError = "Shizuku shell service connection timed out"
                pendingBind = null
                null
            } catch (e: Exception) {
                lastBindError = e.message ?: "Shizuku binding failed"
                pendingBind = null
                null
            }
            service != null && isUserServiceReady()
        }
    }

    suspend fun runShell(command: String, timeoutMs: Int = DEFAULT_TIMEOUT_MS): ShellResult {
        val safeCommand = command.trim()
        if (safeCommand.isBlank()) {
            return ShellResult(false, -1, "", "Empty command")
        }
        if (safeCommand.length > MAX_COMMAND_LENGTH) {
            return ShellResult(false, -1, "", "Command is too long")
        }
        if (!hasPermission()) {
            return ShellResult(false, -1, "", "Shizuku permission is required")
        }
        if (!ensureUserService()) {
            return ShellResult(
                false,
                -1,
                "",
                lastBindError.ifBlank { "Shizuku privileged shell service is not available" }
            )
        }

        val service = shellService
            ?: return ShellResult(false, -1, "", "Shizuku shell service is not connected")

        return withContext(Dispatchers.IO) {
            try {
                val raw = service.runCommand(
                    safeCommand,
                    timeoutMs.coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)
                )
                val json = JSONObject(raw)
                ShellResult(
                    success = json.optBoolean("success", false),
                    exitCode = json.optInt("exitCode", -1),
                    output = json.optString("output", ""),
                    error = json.optString("error", "")
                )
            } catch (e: Exception) {
                shellService = null
                lastBindError = e.message ?: "Shizuku shell call failed"
                Log.e(TAG, "Shizuku shell command failed", e)
                ShellResult(false, -1, "", lastBindError)
            }
        }
    }

    fun clearService() {
        shellService = null
        pendingBind?.complete(null)
        pendingBind = null
    }

    companion object {
        private const val TAG = "ShizukuManager"
        const val SHIZUKU_PACKAGE = "moe.shizuku.manager"
        val SHIZUKU_PACKAGES = listOf(
            "moe.shizuku.manager",
            "moe.shizuku.privileged.api"
        )
        const val PERMISSION_REQUEST_CODE = 0x5A17
        private const val USER_SERVICE_VERSION = 1
        private const val USER_SERVICE_TAG = "jarvis_shell_v1"
        private const val BIND_TIMEOUT_MS = 12_000L
        private const val DEFAULT_TIMEOUT_MS = 30_000
        private const val MIN_TIMEOUT_MS = 1_000
        private const val MAX_TIMEOUT_MS = 60_000
        private const val MAX_COMMAND_LENGTH = 2_000
    }
}
