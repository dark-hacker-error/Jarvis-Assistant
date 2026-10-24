package com.jarvis.assistant.system

import androidx.annotation.Keep
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Runs in the Shizuku UserService process, not in the normal app process.
 *
 * Keep this class deliberately small and dependency-free: Shizuku loads it
 * through reflection in a separate process. It returns JSON so the app can
 * distinguish "command ran and failed" from "the privileged service itself
 * could not be reached".
 */
@Keep
class JarvisShellService : IJarvisShellService.Stub() {

    override fun runCommand(command: String?, timeoutMs: Int): String {
        val safeCommand = command?.trim().orEmpty()
        if (safeCommand.isEmpty()) {
            return failure("Empty command")
        }
        if (safeCommand.length > MAX_COMMAND_LENGTH) {
            return failure("Command is too long (maximum $MAX_COMMAND_LENGTH characters)")
        }

        val cappedTimeout = timeoutMs.coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)
        var process: Process? = null
        try {
            process = ProcessBuilder("/system/bin/sh", "-c", safeCommand)
                .redirectErrorStream(true)
                .start()

            val output = StringBuilder()
            val outputLock = Any()
            var totalBytes = 0
            val readerThread = Thread {
                try {
                    BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8)).use { reader ->
                        val buffer = CharArray(4096)
                        while (true) {
                            val read = reader.read(buffer)
                            if (read < 0) break
                            synchronized(outputLock) {
                                totalBytes += read
                                if (output.length < MAX_OUTPUT_CHARS) {
                                    val remaining = MAX_OUTPUT_CHARS - output.length
                                    output.append(buffer, 0, minOf(read, remaining))
                                }
                            }
                        }
                    }
                } catch (_: Exception) {
                    // The process may be destroyed on timeout; output captured
                    // before that point is still returned.
                }
            }
            readerThread.isDaemon = true
            readerThread.start()

            val finished = process.waitFor(cappedTimeout.toLong(), TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                process.waitFor(1, TimeUnit.SECONDS)
            }
            readerThread.join(750)

            val exitCode = if (finished) {
                try {
                    process.exitValue()
                } catch (_: Exception) {
                    -1
                }
            } else {
                -1
            }

            val capture = synchronized(outputLock) {
                output.toString() to (totalBytes > MAX_OUTPUT_CHARS)
            }
            val result = JSONObject()
                .put("success", finished && exitCode == 0)
                .put("exitCode", exitCode)
                .put("output", capture.first)
                .put("truncated", capture.second)
                .put("timedOut", !finished)
            if (!finished) {
                result.put("error", "Command timed out after ${cappedTimeout / 1000} seconds")
            } else if (exitCode != 0) {
                result.put("error", "Command exited with code $exitCode")
            }
            return result.toString()
        } catch (e: Exception) {
            return failure(e.message ?: e.javaClass.simpleName)
        } finally {
            try {
                process?.destroy()
            } catch (_: Exception) {
                // Best effort cleanup only.
            }
        }
    }

    override fun destroy() {
        // Shizuku's reserved transaction. The remote process must exit when
        // the user unbinds or revokes access.
        System.exit(0)
    }

    private fun failure(message: String): String = JSONObject()
        .put("success", false)
        .put("exitCode", -1)
        .put("output", "")
        .put("error", message)
        .toString()

    private companion object {
        const val MIN_TIMEOUT_MS = 1_000
        const val MAX_TIMEOUT_MS = 60_000
        const val MAX_COMMAND_LENGTH = 2_000
        const val MAX_OUTPUT_CHARS = 20_000
    }
}
