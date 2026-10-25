package com.jarvis.assistant.wake

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.audio.AudioCaptureBus
import com.jarvis.assistant.audio.OwnerVoiceManager
import com.jarvis.assistant.audio.SherpaSpeakerVerifier
import com.jarvis.assistant.audio.VoiceMatchResult
import com.jarvis.assistant.audio.VoskModelManager
import com.jarvis.assistant.util.PermissionHelper
import com.jarvis.assistant.voice.SherpaOnnxWakeWordEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Part 1: ultra-light, always-on wake service.
 *
 * Runs in the isolated `:wake` process declared in AndroidManifest.xml. The
 * application object deliberately skips Room, TTS, Gemini, MCP, Shizuku,
 * backup and the 68 MB Vosk ASR model in this process, so the resident cost
 * is the keyword spotter plus the small speaker model.
 *
 * Flow:
 *
 *   mic -> Sherpa keyword spotter -> "Jarvis"
 *       -> WeSpeaker CAM++ embedding of the same 16 kHz buffer
 *       -> owner match?  no  -> resume listening, ignore the speaker
 *                        yes -> release the mic, start Part 2 with pre-roll
 *
 * Part 2 never opens MainActivity. It shows the floating orb and signals
 * [ACTION_RESUME_AFTER_SESSION] when the conversation ends; this service then
 * reattaches the keyword spotter.
 */
class WakeWordService : Service() {

    companion object {
        private const val TAG = "WakeWordService"

        private const val WAKE_COOLDOWN_MS = 1_200L
        private const val VERIFY_AUDIO_MS = 2_400
        private const val SESSION_POLL_MS = 15_000L
        private const val SESSION_MISS_LIMIT = 2
        private const val RESUME_DELAY_MS = 700L
        private const val WAKE_LOCK_TIMEOUT_MS = 6 * 60 * 60 * 1000L
        private const val WAKE_LOCK_RENEW_MS = 5 * 60 * 60 * 1000L
        private const val SPEAKER_IDLE_RELEASE_MS = 90_000L
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var wakeEngine: SherpaOnnxWakeWordEngine? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wakeLockJob: Job? = null
    private var sessionWatchdogJob: Job? = null
    private var speakerReleaseJob: Job? = null

    @Volatile
    private var listening = false

    @Volatile
    private var sessionActive = false

    @Volatile
    private var lastWakeAt = 0L

    private val verifying = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        WakeNotifications.ensureChannel(this)
        acquireWakeLock()
        WakeContract.notifyState(this, running = true, listening = false)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!ensureForeground()) {
            return START_NOT_STICKY
        }

        when (intent?.action) {
            WakeContract.ACTION_STOP -> {
                Log.i(TAG, "Stop requested")
                stopEverything()
                return START_NOT_STICKY
            }

            WakeContract.ACTION_PAUSE_FOR_SESSION -> {
                Log.i(TAG, "Pausing for an active Part 2 session")
                pauseForSession()
                return START_STICKY
            }

            WakeContract.ACTION_RESUME_AFTER_SESSION -> {
                Log.i(TAG, "Part 2 session ended; resuming wake listening")
                resumeAfterSession()
                return START_STICKY
            }

            WakeContract.ACTION_START, null -> {
                startListening()
                return START_STICKY
            }

            else -> {
                startListening()
                return START_STICKY
            }
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.i(TAG, "Task removed; wake service stays alive")
        acquireWakeLock()
        if (!sessionActive && !listening) startListening()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.i(TAG, "Wake service destroyed")
        sessionWatchdogJob?.cancel()
        sessionWatchdogJob = null
        speakerReleaseJob?.cancel()
        speakerReleaseJob = null
        listening = false
        sessionActive = false
        try {
            wakeEngine?.stop()
        } catch (t: Throwable) {
            Log.w(TAG, "Error stopping wake engine: ${t.message}")
        }
        wakeEngine = null
        AudioCaptureBus.endHandoff()
        AudioCaptureBus.shutdownForHandoff()
        SherpaSpeakerVerifier.release()
        releaseWakeLock()
        WakeContract.notifyState(this, running = false, listening = false)
        serviceScope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // Listening
    // ------------------------------------------------------------------

    private fun startListening() {
        if (listening || sessionActive) return

        val prefs = (application as JarvisApp).preferences
        if (!prefs.isWakeServiceEnabled) {
            Log.i(TAG, "Wake service disabled in preferences; stopping")
            stopEverything()
            return
        }
        if (!PermissionHelper.hasRecordAudioPermission(this)) {
            Log.w(TAG, "RECORD_AUDIO missing; wake service cannot listen")
            updateNotification()
            WakeNotifications.postStartPrompt(
                this,
                "Microphone permission is missing. Open Jarvis and allow Microphone."
            )
            stopSelf()
            return
        }

        // Legacy owners who have not re-enrolled yet still need the Vosk
        // x-vector model. Load only the 15 MB speaker model, never the 68 MB
        // ASR model, and only when it is actually required.
        if (prefs.hasOwnerVoiceProfile() && !prefs.hasSherpaVoiceProfile()) {
            VoskModelManager.initSpeakerOnly(applicationContext, serviceScope)
        }

        if (wakeEngine == null) {
            wakeEngine = SherpaOnnxWakeWordEngine(
                context = applicationContext,
                scope = serviceScope,
                onWakeWord = { onWakeCandidate() },
                useBusHandoff = false
            )
        }

        listening = true
        wakeEngine?.start()
        updateNotification()
        WakeContract.notifyState(this, running = true, listening = true)
        Log.i(TAG, "Wake listener active (Sherpa KWS + WeSpeaker owner gate)")
    }

    private fun onWakeCandidate() {
        if (!listening || sessionActive) return
        if (isPhoneCallActive()) {
            Log.i(TAG, "Wake ignored during a phone call")
            wakeEngine?.resume()
            return
        }

        val now = SystemClock.elapsedRealtime()
        if (now - lastWakeAt < WAKE_COOLDOWN_MS) {
            wakeEngine?.resume()
            return
        }
        if (!verifying.compareAndSet(false, true)) {
            wakeEngine?.resume()
            return
        }
        lastWakeAt = now

        val audio = wakeEngine?.recentAudio(VERIFY_AUDIO_MS) ?: ByteArray(0)
        if (audio.size < 16_000) {
            Log.w(TAG, "Wake buffer too short for speaker verification (${audio.size} bytes)")
            verifying.set(false)
            wakeEngine?.resume()
            return
        }

        serviceScope.launch {
            val result = try {
                withContext(Dispatchers.Default) { verifyOwner(audio) }
            } catch (t: Throwable) {
                Log.e(TAG, "Owner verification failed: ${t.message}", t)
                null
            }
            verifying.set(false)

            if (!listening || sessionActive) return@launch

            if (result?.isMatch == true) {
                Log.i(
                    TAG,
                    "Owner verified (distance=${result.distance}, threshold=${result.threshold}, " +
                        "similarity=${result.similarityPercent}%); waking Part 2"
                )
                scheduleSpeakerRelease()
                launchPartTwo(audio)
            } else {
                Log.i(
                    TAG,
                    "Wake ignored: speaker mismatch " +
                        "(distance=${result?.distance}, threshold=${result?.threshold})"
                )
                scheduleSpeakerRelease()
                wakeEngine?.resume()
            }
        }
    }

    /**
     * One-shot owner verification on the exact buffer that produced the
     * keyword. New enrollments use WeSpeaker CAM++; legacy profiles fall back
     * to the Vosk x-vector model so existing users are not locked out.
     */
    private suspend fun verifyOwner(audio: ByteArray): VoiceMatchResult {
        val prefs = (application as JarvisApp).preferences

        if (!prefs.isOwnerVoiceEnabled || !prefs.hasAnyOwnerVoiceProfile()) {
            return VoiceMatchResult(
                isMatch = true,
                distance = 0f,
                similarityPercent = 100,
                threshold = prefs.ownerVoiceThreshold
            )
        }

        if (prefs.hasSherpaVoiceProfile()) {
            val sherpa = OwnerVoiceManager.verifySherpaAudio(applicationContext, audio, prefs)
            if (sherpa.hasExtractedVector || !prefs.hasOwnerVoiceProfile()) return sherpa
            Log.w(TAG, "WeSpeaker extraction unavailable; falling back to the Vosk profile")
        }

        if (prefs.hasOwnerVoiceProfile()) {
            if (!ensureLegacySpeakerModel()) {
                Log.e(TAG, "Legacy speaker model unavailable; owner cannot be verified")
                return VoiceMatchResult(
                    isMatch = false,
                    distance = 1f,
                    similarityPercent = 0,
                    threshold = prefs.ownerVoiceThreshold,
                    hasExtractedVector = false
                )
            }
            return OwnerVoiceManager.verifySpeakerAudio(audio, prefs)
        }

        return VoiceMatchResult(
            isMatch = false,
            distance = 1f,
            similarityPercent = 0,
            threshold = prefs.ownerVoiceThreshold,
            hasExtractedVector = false
        )
    }

    private suspend fun ensureLegacySpeakerModel(): Boolean {
        if (VoskModelManager.speakerModel != null) return true
        VoskModelManager.initSpeakerOnly(applicationContext, serviceScope)
        val deadline = SystemClock.elapsedRealtime() + 8_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            if (VoskModelManager.speakerModel != null) return true
            delay(100L)
        }
        return VoskModelManager.speakerModel != null
    }

    // ------------------------------------------------------------------
    // Part 2 hand-off
    // ------------------------------------------------------------------

    private fun launchPartTwo(preroll: ByteArray) {
        sessionActive = true
        listening = false
        wakeEngine?.pause()
        AudioCaptureBus.endHandoff()
        // The `:wake` process must release its AudioRecord before Part 2 opens
        // its own recorder in the main process.
        AudioCaptureBus.shutdownForHandoff()
        updateNotification()
        WakeContract.notifyState(this, running = true, listening = false)
        vibrateBriefly()

        val intent = Intent()
            .setClassName(packageName, WakeContract.CONVERSATION_SERVICE_CLASS)
            .setAction(WakeContract.ACTION_START_CONVERSATION)
            .putExtra(WakeContract.EXTRA_WAKE_TRIGGERED, true)
            .putExtra(WakeContract.EXTRA_WAKE_PREROLL, preroll)

        val started = try {
            ContextCompat.startForegroundService(this, intent)
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Unable to start Part 2: ${t.message}", t)
            false
        }

        if (!started) {
            sessionActive = false
            WakeNotifications.postStartPrompt(
                this,
                "Tap to open Jarvis and start the conversation service."
            )
            resumeAfterSession()
            return
        }

        startSessionWatchdog()
    }

    private fun startSessionWatchdog() {
        sessionWatchdogJob?.cancel()
        sessionWatchdogJob = serviceScope.launch(Dispatchers.Default) {
            var misses = 0
            while (isActive && sessionActive) {
                delay(SESSION_POLL_MS)
                if (!sessionActive) break
                val running = WakeContract.isConversationServiceRunning(applicationContext)
                if (running) {
                    misses = 0
                } else {
                    misses++
                    Log.w(TAG, "Part 2 not running (miss $misses/$SESSION_MISS_LIMIT)")
                    if (misses >= SESSION_MISS_LIMIT) {
                        withContext(Dispatchers.Main) { resumeAfterSession() }
                        break
                    }
                }
            }
        }
    }

    private fun pauseForSession() {
        sessionActive = true
        listening = false
        wakeEngine?.pause()
        AudioCaptureBus.endHandoff()
        AudioCaptureBus.shutdownForHandoff()
        updateNotification()
        WakeContract.notifyState(this, running = true, listening = false)
        startSessionWatchdog()
    }

    private fun resumeAfterSession() {
        sessionActive = false
        sessionWatchdogJob?.cancel()
        sessionWatchdogJob = null
        serviceScope.launch {
            // Give Part 2 a moment to release its AudioRecord before this
            // process reopens the microphone.
            delay(RESUME_DELAY_MS)
            if (!sessionActive && !listening) {
                startListening()
            }
        }
    }

    // ------------------------------------------------------------------
    // Lifecycle helpers
    // ------------------------------------------------------------------

    private fun ensureForeground(): Boolean {
        val notification = WakeNotifications.buildServiceNotification(
            this,
            listening = listening,
            sessionActive = sessionActive
        )
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    WakeNotifications.SERVICE_NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(WakeNotifications.SERVICE_NOTIFICATION_ID, notification)
            }
            return true
        } catch (t: Throwable) {
            Log.e(TAG, "startForeground failed: ${t.message}", t)
            // Android 14+ may block a microphone FGS start from BOOT_COMPLETED.
            WakeNotifications.postStartPrompt(
                this,
                "Android blocked the background microphone start. Open Jarvis once to resume wake word."
            )
            stopSelf()
            return false
        }
    }

    private fun updateNotification() {
        try {
            val notification = WakeNotifications.buildServiceNotification(
                this,
                listening = listening,
                sessionActive = sessionActive
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE)
                as? android.app.NotificationManager ?: return
            manager.notify(WakeNotifications.SERVICE_NOTIFICATION_ID, notification)
        } catch (t: Throwable) {
            Log.w(TAG, "Unable to update wake notification: ${t.message}")
        }
    }

    private fun stopEverything() {
        sessionActive = false
        listening = false
        sessionWatchdogJob?.cancel()
        sessionWatchdogJob = null
        speakerReleaseJob?.cancel()
        speakerReleaseJob = null
        try {
            wakeEngine?.stop()
        } catch (_: Throwable) {
        }
        wakeEngine = null
        AudioCaptureBus.endHandoff()
        AudioCaptureBus.shutdownForHandoff()
        SherpaSpeakerVerifier.release()
        releaseWakeLock()
        WakeContract.notifyState(this, running = false, listening = false)
        try {
            (application as JarvisApp).preferences.isWakeServiceRunning = false
        } catch (_: Throwable) {
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun scheduleSpeakerRelease() {
        speakerReleaseJob?.cancel()
        speakerReleaseJob = serviceScope.launch(Dispatchers.Default) {
            delay(SPEAKER_IDLE_RELEASE_MS)
            SherpaSpeakerVerifier.release()
            Log.i(TAG, "Released WeSpeaker model to lower idle memory")
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:JarvisWake")
            .also {
                it.setReferenceCounted(false)
                it.acquire(WAKE_LOCK_TIMEOUT_MS)
            }
        wakeLockJob?.cancel()
        wakeLockJob = serviceScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(WAKE_LOCK_RENEW_MS)
                val lock = wakeLock ?: break
                if (!lock.isHeld) break
                try {
                    lock.acquire(WAKE_LOCK_TIMEOUT_MS)
                    Log.i(TAG, "Renewed wake-service wake lock")
                } catch (t: Throwable) {
                    Log.w(TAG, "Unable to renew wake lock: ${t.message}")
                    break
                }
            }
        }
    }

    private fun releaseWakeLock() {
        wakeLockJob?.cancel()
        wakeLockJob = null
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    private fun isPhoneCallActive(): Boolean {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (audioManager?.mode == AudioManager.MODE_IN_CALL) return true
        return try {
            val telephony = getSystemService(Context.TELEPHONY_SERVICE)
                as? android.telephony.TelephonyManager
            telephony != null &&
                telephony.callState != android.telephony.TelephonyManager.CALL_STATE_IDLE
        } catch (_: Throwable) {
            false
        }
    }

    private fun vibrateBriefly() {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                    ?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(
                    VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(40)
            }
        } catch (_: Throwable) {
        }
    }
}
