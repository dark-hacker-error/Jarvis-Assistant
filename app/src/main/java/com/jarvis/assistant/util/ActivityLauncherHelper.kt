package com.jarvis.assistant.util

import android.app.ActivityOptions
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Universal helper to safely launch activities from background services
 * (e.g. JarvisConversationService) across Android 10, 11, 12, 13, 14, 15 without
 * getting blocked by Android's Background Activity Launch (BAL) restrictions.
 */
object ActivityLauncherHelper {

    private const val TAG = "ActivityLauncherHelper"
    private const val LAUNCHER_CHANNEL_ID = "jarvis_activity_launcher"
    private const val LAUNCHER_NOTIFICATION_ID = 8899

    fun startActivitySafely(context: Context, intent: Intent): Boolean {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

        // 1. If JarvisAccessibilityService is running, use it directly.
        // Android explicitly exempts AccessibilityService from BAL restrictions.
        val a11y = com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService.instance
        if (a11y != null) {
            try {
                a11y.startActivity(intent)
                Log.d(TAG, "Launched activity via AccessibilityService: ${intent.component ?: intent.`package` ?: intent.action}")
                return true
            } catch (e: Exception) {
                Log.w(TAG, "AccessibilityService.startActivity failed: ${e.message}")
            }
        }

        // 2. If SYSTEM_ALERT_WINDOW (overlay permission) is granted:
        // Android officially allows background activity starts when overlay permission is granted.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)) {
            try {
                context.startActivity(intent)
                Log.d(TAG, "Launched activity via Overlay exemption: ${intent.component ?: intent.`package` ?: intent.action}")
                return true
            } catch (e: Exception) {
                Log.w(TAG, "Overlay-exempt context.startActivity failed: ${e.message}")
            }
        }

        // 3. PendingIntent approach with background activity start allowance (Android 14+ / API 34)
        try {
            val requestCode = (System.currentTimeMillis() % 10000).toInt()
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val pendingIntent = PendingIntent.getActivity(context, requestCode, intent, flags)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val options = ActivityOptions.makeBasic().apply {
                    setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                }
                pendingIntent.send(context, 0, null, null, null, null, options.toBundle())
            } else {
                pendingIntent.send()
            }
            Log.d(TAG, "Launched activity via PendingIntent: ${intent.component ?: intent.`package` ?: intent.action}")
            return true
        } catch (e: Exception) {
            Log.w(TAG, "PendingIntent.send failed: ${e.message}")
        }

        // 4. High-Priority FullScreenIntent Notification (guaranteed foreground trigger on Android 10+)
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            if (nm != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val channel = NotificationChannel(
                        LAUNCHER_CHANNEL_ID,
                        "Jarvis Action Launcher",
                        NotificationManager.IMPORTANCE_HIGH
                    ).apply {
                        setSound(null, null)
                        enableVibration(false)
                        setShowBadge(false)
                    }
                    nm.createNotificationChannel(channel)
                }

                val reqCode = (System.currentTimeMillis() % 10000).toInt()
                val pi = PendingIntent.getActivity(
                    context,
                    reqCode,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                val notif = NotificationCompat.Builder(context, LAUNCHER_CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                    .setContentTitle("Opening App")
                    .setContentText("Jarvis executing action...")
                    .setPriority(NotificationCompat.PRIORITY_MAX)
                    .setCategory(NotificationCompat.CATEGORY_ALARM)
                    .setFullScreenIntent(pi, true)
                    .setAutoCancel(true)
                    .setTimeoutAfter(3000)
                    .build()

                nm.notify(LAUNCHER_NOTIFICATION_ID, notif)
                Handler(Looper.getMainLooper()).postDelayed({
                    try { nm.cancel(LAUNCHER_NOTIFICATION_ID) } catch (_: Exception) {}
                }, 1500)
                Log.d(TAG, "Triggered activity launch via FullScreenIntent notification")
                return true
            }
        } catch (e: Exception) {
            Log.w(TAG, "FullScreenIntent notification launch failed: ${e.message}")
        }

        // 5. Fallback to direct context.startActivity
        return try {
            context.startActivity(intent)
            Log.d(TAG, "Launched activity via context.startActivity fallback: ${intent.component ?: intent.`package` ?: intent.action}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "context.startActivity failed: ${e.message}", e)
            false
        }
    }
}
