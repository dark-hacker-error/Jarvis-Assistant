package com.jarvis.assistant.wake

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.util.PermissionHelper

/**
 * Shared contract between the ultra-light wake service (Part 1, `:wake`
 * process) and the heavy conversation service (Part 2, main process).
 *
 * Keeping the action strings and extras here avoids a dependency from the
 * wake process on any Part 2 class. The wake process only needs this object,
 * [WakeWordService], the keyword spotter and the speaker verifier.
 */
object WakeContract {

    private const val TAG = "WakeContract"

    const val ACTION_START = "com.jarvis.assistant.wake.action.START"
    const val ACTION_STOP = "com.jarvis.assistant.wake.action.STOP"
    const val ACTION_PAUSE_FOR_SESSION = "com.jarvis.assistant.wake.action.PAUSE_FOR_SESSION"
    const val ACTION_RESUME_AFTER_SESSION = "com.jarvis.assistant.wake.action.RESUME_AFTER_SESSION"
    const val ACTION_STATE_CHANGED = "com.jarvis.assistant.wake.action.STATE_CHANGED"

    /**
     * Part 2's public action. The string is duplicated here instead of
     * referencing [com.jarvis.assistant.background.JarvisConversationService]
     * so the `:wake` process never loads the heavy service class.
     */
    const val ACTION_START_CONVERSATION = "com.jarvis.assistant.START_CONVERSATION"
    const val CONVERSATION_SERVICE_CLASS =
        "com.jarvis.assistant.background.JarvisConversationService"

    const val EXTRA_RUNNING = "com.jarvis.assistant.wake.extra.RUNNING"
    const val EXTRA_LISTENING = "com.jarvis.assistant.wake.extra.LISTENING"

    /** Set by Part 1 so Part 2 can play the wake greeting and skip a duplicate verify. */
    const val EXTRA_WAKE_TRIGGERED = "com.jarvis.assistant.extra.WAKE_TRIGGERED"

    /** Recent 16 kHz PCM captured before the wake word, used as Part 2 pre-roll. */
    const val EXTRA_WAKE_PREROLL = "com.jarvis.assistant.extra.WAKE_PREROLL"

    fun isEnabled(context: Context): Boolean {
        val app = context.applicationContext as? JarvisApp ?: return false
        return app.preferences.isWakeServiceEnabled
    }

    /**
     * True when Part 1 is allowed to own the microphone. The wake service is
     * the default owner; Part 2 only falls back to its legacy in-process
     * standby listener when this is false.
     */
    fun shouldOwnWake(context: Context): Boolean =
        isEnabled(context) && PermissionHelper.hasRecordAudioPermission(context)

    /**
     * True only when Part 1 is enabled *and* actually running. Callers use
     * this to decide whether the legacy in-process listener may take over;
     * if the wake service failed to start, Jarvis must not lose wake support.
     */
    fun isActiveWakeOwner(context: Context): Boolean =
        shouldOwnWake(context) && isServiceRunning(context)

    /** Start (or re-start) Part 1. Safe to call repeatedly. */
    fun start(context: Context): Boolean {
        if (!shouldOwnWake(context)) return false
        return try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, WakeWordService::class.java).setAction(ACTION_START)
            )
            WakeStatus.refresh(context)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "Unable to start wake service: ${t.message}")
            false
        }
    }

    fun stop(context: Context) {
        sendIfRunning(context, ACTION_STOP)
        WakeStatus.set(running = false, listening = false)
    }

    fun pauseForSession(context: Context) {
        sendIfRunning(context, ACTION_PAUSE_FOR_SESSION)
    }

    fun resumeAfterSession(context: Context) {
        sendIfRunning(context, ACTION_RESUME_AFTER_SESSION)
    }

    fun notifyState(context: Context, running: Boolean, listening: Boolean) {
        try {
            val intent = Intent(ACTION_STATE_CHANGED)
                .setPackage(context.packageName)
                .putExtra(EXTRA_RUNNING, running)
                .putExtra(EXTRA_LISTENING, listening)
            context.sendBroadcast(intent)
        } catch (t: Throwable) {
            Log.w(TAG, "Unable to broadcast wake state: ${t.message}")
        }
    }

    /**
     * `getRunningServices` is deprecated but remains the only supported way
     * for one process of an app to inspect another process of the same app
     * without binding (which would create the service).
     */
    @Suppress("DEPRECATION")
    fun isServiceRunning(context: Context): Boolean {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return false
        return try {
            manager.getRunningServices(Int.MAX_VALUE).any {
                it.service.className == WakeWordService::class.java.name
            }
        } catch (_: Throwable) {
            false
        }
    }

    @Suppress("DEPRECATION")
    fun isConversationServiceRunning(context: Context): Boolean {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return false
        return try {
            manager.getRunningServices(Int.MAX_VALUE).any {
                it.service.className == CONVERSATION_SERVICE_CLASS
            }
        } catch (_: Throwable) {
            false
        }
    }

    private fun sendIfRunning(context: Context, action: String) {
        if (!isServiceRunning(context)) return
        try {
            context.startService(
                Intent(context, WakeWordService::class.java).setAction(action)
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Unable to send $action to wake service: ${t.message}")
        }
    }
}
