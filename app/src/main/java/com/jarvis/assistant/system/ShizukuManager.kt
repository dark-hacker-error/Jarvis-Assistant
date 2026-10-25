package com.jarvis.assistant.system

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.DeadObjectException
import android.os.IBinder
import android.os.RemoteException
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
 *
 * Connection lifecycle:
 *  - one bind attempt at a time, guarded by [bindMutex];
 *  - a timed-out or dead binding is always unbound before the next attempt so
 *    Android/Shizuku cannot keep handing back the same stale connection;
 *  - the remote binder is watched with [IBinder.linkToDeath], so a Shizuku
 *    restart clears the cached interface instead of leaving a dead stub;
 *  - [runShell] retries exactly once after a dead binder, which covers the
 *    normal "Shizuku was restarted while Jarvis was in the background" case.
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
    private var shellBinder: IBinder? = null

    @Volatile
    private var pendingBind: CompletableDeferred<IJarvisShellService?>? = null

    @Volatile
    private var activeConnection: ServiceConnection? = null

    @Volatile
    private var deathRecipient: IBinder.DeathRecipient? = null

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

    /**
     * Creates a fresh [ServiceConnection] for one bind attempt. A separate
     * connection per attempt keeps late callbacks from an older, timed-out
     * attempt from being mistaken for the current one.
     */
    private fun createConnection(
        deferred: CompletableDeferred<IJarvisShellService?>
    ): ServiceConnection = object : ServiceConnection {

        @Volatile
        private var attachedBinder: IBinder? = null

        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder == null || !binder.isBinderAlive) {
                lastBindError = "Shizuku returned a dead shell service"
                clearPending(deferred)
                deferred.complete(null)
                return
            }
            val service = try {
                IJarvisShellService.Stub.asInterface(binder)
            } catch (e: Exception) {
                Log.e(TAG, "Could not create shell service interface", e)
                null
            }
            if (service == null) {
                lastBindError = "Shizuku returned an empty shell service"
                clearPending(deferred)
                deferred.complete(null)
                return
            }
            attachedBinder = binder
            attachService(service, binder)
            clearPending(deferred)
            deferred.complete(service)
            Log.i(TAG, "Shizuku user service connected")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            handleServiceLost(attachedBinder, "Shizuku user service disconnected")
            clearPending(deferred)
            deferred.complete(null)
        }

        override fun onBindingDied(name: ComponentName?) {
            handleServiceLost(attachedBinder, "Shizuku user service binding died")
            clearPending(deferred)
            deferred.complete(null)
            // Android requires an unbind before the same binding can be used
            // again. Do not kill the remote process here; it is already gone
            // or about to be recreated by Shizuku.
            unbindQuietly(this, remove = false)
        }

        override fun onNullBinding(name: ComponentName?) {
            lastBindError = "Shizuku could not start the privileged shell service"
            handleServiceLost(attachedBinder, lastBindError)
            clearPending(deferred)
            deferred.complete(null)
            // A null binding leaves a broken binding registered. Remove it so
            // the next tap starts a clean attempt.
            unbindQuietly(this, remove = true)
        }
    }

    private fun attachService(service: IJarvisShellService, binder: IBinder) {
        detachDeathRecipient()
        shellService = service
        shellBinder = binder
        lastBindError = ""
        val recipient = IBinder.DeathRecipient {
            // Runs on a binder thread. Only clear state that still belongs to
            // this binder; a newer connection may already have replaced it.
            synchronized(this) {
                if (shellBinder === binder) {
                    shellService = null
                    shellBinder = null
                    lastBindError = "Shizuku shell service stopped"
                    Log.w(TAG, "Shizuku shell binder died")
                }
            }
        }
        try {
            binder.linkToDeath(recipient, 0)
            deathRecipient = recipient
        } catch (e: RemoteException) {
            Log.w(TAG, "Could not watch the Shizuku shell binder", e)
        }
    }

    private fun detachDeathRecipient() {
        val recipient = deathRecipient ?: return
        val binder = shellBinder
        deathRecipient = null
        if (binder != null) {
            try {
                binder.unlinkToDeath(recipient, 0)
            } catch (_: Exception) {
                // The binder is already dead; nothing to unlink.
            }
        }
    }

    private fun handleServiceLost(binder: IBinder?, reason: String) {
        synchronized(this) {
            val current = shellBinder
            if (binder == null || current == null || current === binder) {
                shellService = null
                shellBinder = null
                lastBindError = reason
                Log.w(TAG, reason)
            }
        }
    }

    private fun clearPending(deferred: CompletableDeferred<IJarvisShellService?>) {
        if (pendingBind === deferred) {
            pendingBind = null
        }
    }

    private fun unbindQuietly(connection: ServiceConnection?, remove: Boolean) {
        if (connection == null) return
        try {
            Shizuku.unbindUserService(userServiceArgs, connection, remove)
        } catch (e: Exception) {
            Log.w(TAG, "Could not unbind the Shizuku user service", e)
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
            val binder = service.asBinder()
            binder != null && binder.isBinderAlive
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
     * binding is attempted at a time and a failed attempt is fully unbound.
     */
    suspend fun ensureUserService(): Boolean {
        if (!hasPermission()) {
            lastBindError = "Shizuku permission is required"
            return false
        }
        if (isUserServiceReady()) return true

        return bindMutex.withLock {
            if (isUserServiceReady()) {
                return@withLock true
            }
            // A previous attempt may have left a dead stub behind.
            clearServiceInternal(unbind = true, removeRemote = false)

            val deferred = CompletableDeferred<IJarvisShellService?>()
            val connection = createConnection(deferred)
            pendingBind = deferred
            activeConnection = connection

            try {
                withContext(Dispatchers.Main) {
                    Shizuku.bindUserService(userServiceArgs, connection)
                }
            } catch (e: Exception) {
                lastBindError = e.message ?: "Could not bind Shizuku user service"
                clearPending(deferred)
                deferred.complete(null)
                unbindQuietly(connection, remove = true)
                activeConnection = null
                Log.e(TAG, lastBindError, e)
                return@withLock false
            }

            val service = try {
                withTimeout(BIND_TIMEOUT_MS) { deferred.await() }
            } catch (_: TimeoutCancellationException) {
                lastBindError = "Shizuku shell service connection timed out"
                clearPending(deferred)
                // Kill the half-started service so the next attempt is clean.
                unbindQuietly(connection, remove = true)
                activeConnection = null
                Log.w(TAG, lastBindError)
                null
            } catch (e: Exception) {
                lastBindError = e.message ?: "Shizuku binding failed"
                clearPending(deferred)
                unbindQuietly(connection, remove = true)
                activeConnection = null
                Log.w(TAG, lastBindError, e)
                null
            }

            val ready = service != null && isUserServiceReady()
            if (!ready && lastBindError.isBlank()) {
                lastBindError = "Shizuku shell service is not ready"
            }
            if (ready) {
                activeConnection = connection
            }
            ready
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

        var lastError = ""
        repeat(MAX_EXECUTION_ATTEMPTS) { attempt ->
            if (!ensureUserService()) {
                lastError = lastBindError.ifBlank {
                    "Shizuku privileged shell service is not available"
                }
                return ShellResult(false, -1, "", lastError)
            }

            val service = shellService
            if (service == null) {
                lastError = "Shizuku shell service is not connected"
                clearServiceInternal(unbind = true, removeRemote = false)
                return@repeat
            }
            val binder = try {
                service.asBinder()
            } catch (e: Exception) {
                null
            }
            if (binder == null || !binder.isBinderAlive) {
                lastError = "Shizuku shell service died"
                clearServiceInternal(unbind = true, removeRemote = false)
                return@repeat
            }

            try {
                val raw = withContext(Dispatchers.IO) {
                    service.runCommand(
                        safeCommand,
                        timeoutMs.coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)
                    )
                }
                val json = JSONObject(raw)
                return ShellResult(
                    success = json.optBoolean("success", false),
                    exitCode = json.optInt("exitCode", -1),
                    output = json.optString("output", ""),
                    error = json.optString("error", "")
                )
            } catch (e: DeadObjectException) {
                lastError = "Shizuku shell service died while running the command"
                clearServiceInternal(unbind = true, removeRemote = false)
                Log.w(TAG, lastError, e)
                if (attempt >= MAX_EXECUTION_ATTEMPTS - 1) {
                    return ShellResult(false, -1, "", lastError)
                }
            } catch (e: RemoteException) {
                lastError = e.message ?: "Shizuku shell call failed"
                clearServiceInternal(unbind = true, removeRemote = false)
                Log.w(TAG, "Shizuku shell binder call failed", e)
                if (attempt >= MAX_EXECUTION_ATTEMPTS - 1) {
                    return ShellResult(false, -1, "", lastError)
                }
            } catch (e: Exception) {
                // A malformed response is a protocol problem, not a dead binder;
                // retrying would run the command twice for no benefit.
                lastError = e.message ?: "Shizuku shell call failed"
                Log.e(TAG, "Shizuku shell command failed", e)
                return ShellResult(false, -1, "", lastError)
            }
        }
        return ShellResult(false, -1, "", lastError.ifBlank { "Shizuku shell service is not available" })
    }

    /**
     * Drops the cached interface. Used when the Shizuku binder is restarted or
     * when the app wants to force a fresh bind.
     */
    fun clearService() {
        clearServiceInternal(unbind = true, removeRemote = false)
    }

    private fun clearServiceInternal(unbind: Boolean, removeRemote: Boolean) {
        val connection = activeConnection
        detachDeathRecipient()
        synchronized(this) {
            shellService = null
            shellBinder = null
        }
        pendingBind?.let { deferred ->
            pendingBind = null
            deferred.complete(null)
        }
        if (unbind) {
            unbindQuietly(connection, remove = removeRemote)
        }
        activeConnection = null
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
        private const val MAX_EXECUTION_ATTEMPTS = 2
    }
}
