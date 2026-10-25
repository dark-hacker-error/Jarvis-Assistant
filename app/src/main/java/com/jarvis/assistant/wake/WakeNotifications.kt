package com.jarvis.assistant.wake

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.jarvis.assistant.R
import com.jarvis.assistant.ui.home.MainActivity

/**
 * Notifications for Part 1.
 *
 * The service notification is deliberately low-importance, silent and
 * ongoing. It is the Android-required visible surface for a microphone
 * foreground service, not a user-facing alert.
 */
object WakeNotifications {

    private const val TAG = "WakeNotifications"

    const val CHANNEL_ID = "jarvis_wake_service"
    const val SERVICE_NOTIFICATION_ID = 4201
    const val PROMPT_NOTIFICATION_ID = 4202

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Jarvis wake service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps the offline \"Jarvis\" wake word and owner voice check running."
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
            lockscreenVisibility = Notification.VISIBILITY_SECRET
        }
        manager.createNotificationChannel(channel)
    }

    fun buildServiceNotification(
        context: Context,
        listening: Boolean,
        sessionActive: Boolean
    ): Notification {
        val (title, text) = when {
            sessionActive -> "Jarvis is awake" to
                "Conversation active. Wake listening resumes automatically."
            listening -> "Jarvis is listening" to
                "Say \"Jarvis\" to wake me. Owner voice verification is on."
            else -> "Jarvis wake service" to "Starting the offline wake listener\u2026"
        }

        val contentIntent = PendingIntent.getActivity(
            context,
            10,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            context,
            11,
            Intent(context, WakeWordService::class.java).setAction(WakeContract.ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .addAction(0, "Stop", stopIntent)
            .build()
    }

    /**
     * Android 14+ can refuse a microphone foreground-service start from
     * BOOT_COMPLETED. Instead of failing silently, ask the user to open the
     * app once so the service can start in the foreground.
     */
    fun postStartPrompt(context: Context, reason: String) {
        ensureChannel(context)
        val contentIntent = PendingIntent.getActivity(
            context,
            12,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_settings_shield)
            .setContentTitle("Jarvis wake service needs a tap")
            .setContentText(reason)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        try {
            NotificationManagerCompat.from(context)
                .notify(PROMPT_NOTIFICATION_ID, notification)
        } catch (t: Throwable) {
            Log.w(TAG, "Unable to post wake start prompt: ${t.message}")
        }
    }
}
