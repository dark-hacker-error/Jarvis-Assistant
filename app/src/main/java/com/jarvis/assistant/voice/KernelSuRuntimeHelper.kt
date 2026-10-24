package com.jarvis.assistant.voice

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.service.voice.VoiceInteractionService
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Safe KernelSU bootstrap for the native voice add-on.
 *
 * Rules enforced here:
 *  - Only `su -c` is used. No mount, no /system or /vendor writes, no KernelSU
 *    module, no SELinux change, no kernel tuning and no endless wake lock.
 *  - Only official Android runtime commands are executed: `settings`, `cmd`
 *    and `appops`.
 *  - Every command uses the package name from Context and the fixed component
 *    name declared in the manifest. No caller-provided shell fragment is ever
 *    interpolated, so there is no shell-injection surface.
 *  - Each command has a finite timeout.
 *
 * This helper does not touch the existing ShizukuManager, accessibility
 * service, MCP manager or command pipeline. Vosk is retained only for the
 * existing speaker-biometrics model.
 */
object KernelSuRuntimeHelper {

    private const val TAG = "KernelSuRuntime"
    private const val ROOT_TIMEOUT_SECONDS = 20L
    private const val VOICE_SERVICE_CLASS = ".JarvisVoiceInteractionService"
    private val SAFE_PACKAGE = Regex("[A-Za-z0-9._]+")

    data class CommandResult(
        val command: String,
        val exitCode: Int,
        val output: String,
        val required: Boolean
    )

    data class ActivationResult(
        val success: Boolean,
        val results: List<CommandResult>,
        val error: String? = null
    ) {
        val legacyNotificationsSilenced: Boolean
            get() = results.any {
                it.command.contains("POST_NOTIFICATION") && it.exitCode == 0
            }
    }

    /**
     * Applies the minimum runtime configuration needed for the system to bind
     * [com.jarvis.assistant.JarvisVoiceInteractionService] as the assistant.
     *
     * The POST_NOTIFICATION app-op is optional. It exists only because the
     * original JarvisConversationService owns a foreground notification while
     * it runs. The user asked for zero visible notifications and explicitly
     * forbade editing that existing service, so the notification is hidden with
     * an app-op instead of changing the service. It can be reverted at any time
     * with:
     *
     *   appops set <package> POST_NOTIFICATION allow
     */
    suspend fun activate(context: Context): ActivationResult = withContext(Dispatchers.IO) {
        val packageName = context.packageName
        if (!SAFE_PACKAGE.matches(packageName)) {
            return@withContext ActivationResult(
                success = false,
                results = emptyList(),
                error = "Unsafe package name; refusing to build root commands."
            )
        }

        val commands = buildList {
            add(
                CommandSpec(
                    "settings put secure voice_interaction_service $packageName/$VOICE_SERVICE_CLASS",
                    required = true
                )
            )
            add(CommandSpec("cmd deviceidle whitelist +$packageName", required = true))
            add(CommandSpec("appops set $packageName RECORD_AUDIO allow", required = true))
            add(CommandSpec("appops set $packageName SYSTEM_ALERT_WINDOW allow", required = true))

            // Android 13+ only. Optional because some OEM builds rename or
            // remove this app-op. A failure here must not break voice setup.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(CommandSpec("appops set $packageName POST_NOTIFICATION ignore", required = false))
            }
        }

        val results = mutableListOf<CommandResult>()
        for (spec in commands) {
            val result = runSu(spec.command, spec.required)
            results += result
            if (spec.required && result.exitCode != 0) {
                val reason = result.output.ifBlank { "exit code ${result.exitCode}" }
                Log.e(TAG, "Required root command failed: ${spec.command} -> $reason")
                return@withContext ActivationResult(
                    success = false,
                    results = results,
                    error = "KernelSU command failed: $reason"
                )
            }
            if (!spec.required && result.exitCode != 0) {
                Log.w(TAG, "Optional root command skipped: ${spec.command} -> ${result.output}")
            }
        }

        ActivationResult(success = true, results = results)
    }

    /**
     * True only when the system currently has the add-on bound as the active
     * voice interaction service. Merely writing the secure setting is not
     * enough; the system may need a moment (or a reboot) to bind it.
     */
    fun isVoiceInteractionActive(context: Context): Boolean {
        return try {
            VoiceInteractionService.isActiveService(
                context,
                ComponentName(
                    context.packageName,
                    "com.jarvis.assistant.JarvisVoiceInteractionService"
                )
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Unable to query active voice interaction service: ${t.message}")
            false
        }
    }

    /** Checks the secure setting without requiring root; useful for UI status. */
    fun isVoiceInteractionConfigured(context: Context): Boolean {
        return try {
            val configured = android.provider.Settings.Secure.getString(
                context.contentResolver,
                "voice_interaction_service"
            ).orEmpty()
            val component = ComponentName(
                context.packageName,
                "com.jarvis.assistant.JarvisVoiceInteractionService"
            )
            configured.contains(component.packageName) &&
                configured.contains("JarvisVoiceInteractionService")
        } catch (t: Throwable) {
            Log.w(TAG, "Unable to read voice interaction setting: ${t.message}")
            false
        }
    }

    private data class CommandSpec(val command: String, val required: Boolean)

    private fun runSu(command: String, required: Boolean): CommandResult {
        return try {
            val process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()

            val finished = process.waitFor(ROOT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return CommandResult(
                    command = command,
                    exitCode = -1,
                    output = "Timed out after ${ROOT_TIMEOUT_SECONDS}s",
                    required = required
                )
            }

            val output = try {
                process.inputStream.bufferedReader().use { it.readText() }.trim()
            } catch (_: Throwable) {
                ""
            }

            CommandResult(
                command = command,
                exitCode = process.exitValue(),
                output = output,
                required = required
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Unable to execute root command: ${t.message}", t)
            CommandResult(
                command = command,
                exitCode = -1,
                output = t.message ?: "root execution failed",
                required = required
            )
        }
    }
}
