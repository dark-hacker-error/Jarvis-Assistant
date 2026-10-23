package com.jarvis.assistant.automation.agent

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Ensures strict sequential execution of all UI mutation actions.
 * Guarantees that clicks, text inputs, scrolls, and key presses NEVER run concurrently,
 * preventing race conditions, overlapping gestures, and corrupted UI states.
 */
object SerializedActionExecutor {
    private const val TAG = "SerializedActionExec"
    private val mutex = Mutex()
    private val isCancelled = AtomicBoolean(false)
    private val taskGeneration = AtomicLong(0L)

    val isBusy: Boolean
        get() = mutex.isLocked

    fun cancelAllPendingActions() {
        Log.w(TAG, "Cancelling all active/pending UI actions")
        isCancelled.set(true)
        taskGeneration.incrementAndGet()
    }

    fun resetCancellation() {
        taskGeneration.incrementAndGet()
        isCancelled.set(false)
    }

    /** Starts a fresh task epoch; actions queued by an older task remain invalid. */
    fun startNewTask(): Long {
        isCancelled.set(false)
        return taskGeneration.incrementAndGet()
    }

    /**
     * Executes the given UI mutation block exclusively under a Mutex.
     * Only one block can execute at a time across the entire application.
     */
    suspend fun <T> execute(
        actionName: String,
        timeoutMs: Long = 15_000L,
        expectedGeneration: Long? = null,
        block: suspend () -> T
    ): T? {
        // Capture before waiting for the mutex so cancellation followed by a new task
        // cannot accidentally revive an older queued mutation.
        val submittedGeneration = expectedGeneration ?: taskGeneration.get()
        return withContext(Dispatchers.IO) {
        if (isCancelled.get() || submittedGeneration != taskGeneration.get()) {
            Log.w(TAG, "Action '$actionName' rejected: Task is cancelled")
            return@withContext null
        }

        val startTime = System.currentTimeMillis()
        Log.d(TAG, "Requesting lock for UI action: '$actionName'")

        mutex.withLock {
            if (isCancelled.get() || submittedGeneration != taskGeneration.get()) {
                Log.w(TAG, "Action '$actionName' aborted after lock acquisition: Task is cancelled")
                return@withLock null
            }

            Log.i(TAG, ">>> STARTING SERIALIZED ACTION: '$actionName'")
            try {
                val result = withTimeoutOrNull(timeoutMs) {
                    block()
                }

                val elapsed = System.currentTimeMillis() - startTime
                if (result == null) {
                    Log.e(TAG, "!!! ACTION TIMED OUT after ${elapsed}ms: '$actionName'")
                } else {
                    Log.i(TAG, "<<< COMPLETED ACTION '$actionName' in ${elapsed}ms")
                }
                return@withLock result
            } catch (e: CancellationException) {
                Log.w(TAG, "Action '$actionName' was cancelled: ${e.message}")
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Exception during action '$actionName': ${e.message}", e)
                return@withLock null
            }
        }
        }
    }
}
