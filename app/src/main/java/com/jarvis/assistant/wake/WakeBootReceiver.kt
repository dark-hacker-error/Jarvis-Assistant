package com.jarvis.assistant.wake

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.util.PermissionHelper

/**
 * Restarts Part 1 after a reboot or an app update when the user has kept the
 * ultra-light wake service enabled.
 *
 * Android 14+ may reject a microphone foreground-service start from
 * BOOT_COMPLETED. In that case a silent prompt notification asks the user to
 * open Jarvis once; the app then starts the service from the foreground.
 */
class WakeBootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "WakeBootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED &&
            action != "android.intent.action.QUICKBOOT_POWERON" &&
            action != "android.intent.action.HTC_QUICKBOOT_POWERON"
        ) {
            return
        }

        val app = context.applicationContext as? JarvisApp ?: return
        if (!app.preferences.isWakeServiceEnabled) {
            Log.i(TAG, "Wake service disabled; boot start skipped")
            return
        }
        if (!PermissionHelper.hasRecordAudioPermission(context)) {
            Log.i(TAG, "RECORD_AUDIO missing; boot start skipped")
            return
        }

        val started = WakeContract.start(context)
        Log.i(TAG, "Boot wake start requested: success=$started action=$action")
        if (!started) {
            WakeNotifications.postStartPrompt(
                context,
                "Tap to restart the offline wake word after your device restarted."
            )
        }
    }
}
