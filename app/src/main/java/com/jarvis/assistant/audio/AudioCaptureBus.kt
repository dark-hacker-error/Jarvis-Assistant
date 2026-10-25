package com.jarvis.assistant.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.jarvis.assistant.util.PermissionHelper
import java.util.ArrayDeque
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.max

/**
 * Single-owner microphone bus shared by the wake-word spotter and the existing
 * conversation recorder.
 *
 * Why this exists:
 *  - The old design stopped the Vosk microphone, started the conversation
 *    service, and then created a second AudioRecord. That is both a hand-off
 *    gap and a race between two microphone owners.
 *  - Here one AudioRecord is opened once. A wake-word listener and the
 *    conversation recorder can attach/detach independently.
 *  - On a wake trigger the bus enters a short hand-off lease. It keeps reading
 *    while the conversation service starts, buffers up to 5 seconds of recent
 *    PCM, and drains that pre-roll into the conversation recorder as soon as it
 *    attaches. The microphone session itself is never closed during that gap.
 *
 * The bus deliberately uses the same 16 kHz mono PCM16 / 20 ms chunk format and
 * the same VOICE_COMMUNICATION source + platform AEC/NS/AGC as the old
 * AudioRecorder, so the existing Gemini and VAD code sees exactly the same
 * stream shape.
 */
object AudioCaptureBus {

    private const val TAG = "AudioCaptureBus"

    const val SAMPLE_RATE = 16_000
    const val CHUNK_SIZE_BYTES = 640          // 20 ms of 16-bit mono audio
    const val WAKE_CHUNK_SIZE_BYTES = 320     // 10 ms, fed to Sherpa-ONNX

    private const val MAX_RECENT_FRAMES = 100 // 2 seconds of pre-roll
    private const val MAX_HANDOFF_FRAMES = 250 // hard upper bound: 5 seconds
    private const val HANDOFF_TIMEOUT_MS = 3_500L
    private const val READ_ERROR_LIMIT = 10

    data class Frame(
        val pcm: ByteArray,
        val amplitude: Float,
        val frequency: AudioFrequencyData
    )

    fun interface Listener {
        fun onFrame(frame: Frame)
    }

    private val lock = Any()
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val recentFrames = ArrayDeque<Frame>(MAX_RECENT_FRAMES)
    private val handoffFrames = ArrayDeque<Frame>(MAX_HANDOFF_FRAMES)

    /**
     * Part 1 runs in `:wake`; its in-memory hand-off buffer cannot cross the
     * process boundary. It sends the recent PCM in the start intent instead and
     * Part 2 seeds it here before the conversation recorder attaches.
     */
    private var externalPreroll: ByteArray? = null

    private var appContext: Context? = null
    private var audioRecord: AudioRecord? = null
    private var worker: Thread? = null
    private var generation = 0
    private var recording = false
    private var handoffActive = false
    private var handoffUntil = 0L
    private var currentSource = MediaRecorder.AudioSource.VOICE_COMMUNICATION
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var automaticGainControl: AutomaticGainControl? = null

    /**
     * Attach a listener. Starts the single AudioRecord if this is the first one.
     *
     * [source] is honoured only for the first listener in this process. Part 1
     * can request the raw [MediaRecorder.AudioSource.MIC] while Part 2 keeps
     * [MediaRecorder.AudioSource.VOICE_COMMUNICATION] for echo cancellation.
     */
    fun register(
        context: Context,
        listener: Listener,
        source: Int = MediaRecorder.AudioSource.VOICE_COMMUNICATION
    ): Boolean {
        val app = context.applicationContext
        synchronized(lock) {
            appContext = app
            if (!listeners.contains(listener)) {
                listeners.add(listener)
            }
            if (recording) return true
            currentSource = source
            val started = startLocked(app)
            if (!started) {
                listeners.remove(listener)
            }
            return started
        }
    }

    /** Detach a listener. The bus stops when no listener and no hand-off lease remains. */
    fun unregister(listener: Listener) {
        synchronized(lock) {
            listeners.remove(listener)
            if (listeners.isEmpty() && !handoffActive) {
                stopLocked()
            }
        }
    }

    /**
     * Keep the microphone open after a wake detection while the conversation
     * service starts. The recent 2 seconds are copied into the hand-off buffer
     * and new frames keep accumulating for up to [HANDOFF_TIMEOUT_MS].
     *
     * Call this on the wake audio thread immediately before releasing the
     * spotter listener.
     */
    fun beginHandoff(durationMs: Long = HANDOFF_TIMEOUT_MS): Boolean {
        val context = appContext ?: return false
        synchronized(lock) {
            handoffActive = true
            handoffUntil = SystemClock.elapsedRealtime() + durationMs.coerceAtLeast(500L)
            handoffFrames.clear()
            for (frame in recentFrames) {
                handoffFrames.addLast(frame)
            }
            if (!recording) {
                return startLocked(context)
            }
            return true
        }
    }

    /**
     * Seeds pre-roll captured by another process. The next [drainHandoff]
     * (called by the conversation recorder) forwards it before live frames.
     */
    fun seedExternalPreroll(pcm: ByteArray) {
        synchronized(lock) {
            externalPreroll = pcm.copyOf()
        }
    }

    /**
     * Returns and clears the PCM captured during the hand-off window. The
     * existing conversation recorder calls this exactly once when it starts.
     */
    fun drainHandoff(): List<Frame> {
        synchronized(lock) {
            val result = mutableListOf<Frame>()

            val external = externalPreroll
            externalPreroll = null
            if (external != null && external.isNotEmpty()) {
                var offset = 0
                while (offset + CHUNK_SIZE_BYTES <= external.size) {
                    val pcm = external.copyOfRange(offset, offset + CHUNK_SIZE_BYTES)
                    val frequency = AudioUtils.analyzeFrequency(pcm, pcm.size, SAMPLE_RATE)
                    result.add(Frame(pcm, frequency.amplitude, frequency))
                    offset += CHUNK_SIZE_BYTES
                }
            }

            if (handoffFrames.isNotEmpty()) {
                result.addAll(handoffFrames)
                handoffFrames.clear()
            }
            handoffActive = false
            return result
        }
    }

    fun endHandoff() {
        synchronized(lock) {
            handoffActive = false
            handoffFrames.clear()
            externalPreroll = null
            if (listeners.isEmpty()) stopLocked()
        }
    }

    /**
     * Part 1 calls this after a verified wake, immediately before starting
     * Part 2 in the main process. It clears every listener and closes the
     * AudioRecord so the two processes never own the microphone at once.
     */
    fun shutdownForHandoff() {
        synchronized(lock) {
            listeners.clear()
            handoffActive = false
            handoffFrames.clear()
            externalPreroll = null
            stopLocked()
        }
    }

    fun isRunning(): Boolean = synchronized(lock) { recording }

    /**
     * Open the AudioRecord. Must be called while holding [lock].
     */
    @SuppressLint("MissingPermission")
    private fun startLocked(context: Context): Boolean {
        if (recording) return true
        if (!PermissionHelper.hasRecordAudioPermission(context)) {
            Log.w(TAG, "RECORD_AUDIO not granted; microphone bus not started")
            return false
        }

        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(TAG, "Invalid AudioRecord min buffer size")
            return false
        }
        val bufferSize = max(minBufferSize * 4, 32_000)

        val record = try {
            AudioRecord(
                currentSource,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Unable to create AudioRecord: ${t.message}", t)
            return false
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialize")
            record.release()
            return false
        }

        return try {
            attachAudioEffects(record, currentSource)
            record.startRecording()
            audioRecord = record
            recording = true
            generation++
            val thisGeneration = generation

            val thread = Thread(
                { recordLoop(thisGeneration, record) },
                "JarvisAudioCaptureBus"
            ).apply {
                isDaemon = true
                start()
            }
            worker = thread
            Log.i(TAG, "Microphone bus started (generation=$thisGeneration)")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Unable to start AudioRecord: ${t.message}", t)
            releaseAudioEffectsLocked()
            try {
                record.release()
            } catch (_: Throwable) {
            }
            audioRecord = null
            recording = false
            false
        }
    }

    private fun stopLocked() {
        if (!recording && audioRecord == null) {
            handoffActive = false
            handoffFrames.clear()
            return
        }
        recording = false
        handoffActive = false
        handoffFrames.clear()
        generation++

        val record = audioRecord
        audioRecord = null
        releaseAudioEffectsLocked()
        try {
            if (record != null) {
                if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    record.stop()
                }
                record.release()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Error closing AudioRecord: ${t.message}")
        }
        worker = null
        Log.i(TAG, "Microphone bus stopped")
    }

    private fun recordLoop(thisGeneration: Int, initialRecord: AudioRecord) {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        } catch (_: Throwable) {
        }

        var record = initialRecord
        var consecutiveErrors = 0
        var framesRead = 0L
        var partialReads = 0L
        var peakAmplitude = 0f
        var lastDiagnosticAt = 0L
        val buffer = ByteArray(CHUNK_SIZE_BYTES)

        while (true) {
            val shouldStop = synchronized(lock) {
                when {
                    thisGeneration != generation || !recording -> true
                    listeners.isEmpty() && !handoffActive -> true
                    else -> {
                        expireHandoffLocked()
                        false
                    }
                }
            }
            if (shouldStop) break

            val readBytes = try {
                record.read(buffer, 0, buffer.size)
            } catch (t: Throwable) {
                Log.w(TAG, "AudioRecord read exception: ${t.message}")
                -1
            }

            if (readBytes > 0) {
                consecutiveErrors = 0
                framesRead++
                if (readBytes != buffer.size) partialReads++
                val pcm = buffer.copyOf(readBytes)
                val frequency = try {
                    AudioUtils.analyzeFrequency(pcm, readBytes, SAMPLE_RATE)
                } catch (t: Throwable) {
                    Log.w(TAG, "Frame analysis failed (${readBytes} bytes): ${t.message}")
                    AudioFrequencyData()
                }
                if (frequency.amplitude > peakAmplitude) peakAmplitude = frequency.amplitude

                val now = SystemClock.elapsedRealtime()
                if (framesRead == 1L) {
                    Log.i(
                        TAG,
                        "First microphone frame: ${readBytes}B source=$currentSource " +
                            "amplitude=${frequency.amplitude}"
                    )
                }
                if (lastDiagnosticAt == 0L || now - lastDiagnosticAt >= 10_000L) {
                    lastDiagnosticAt = now
                    Log.i(
                        TAG,
                        "Mic bus alive: frames=$framesRead partial=$partialReads " +
                            "peakAmp=$peakAmplitude source=$currentSource " +
                            "listeners=${listeners.size} handoff=$handoffActive"
                    )
                    peakAmplitude = 0f
                }

                try {
                    dispatch(Frame(pcm, frequency.amplitude, frequency))
                } catch (t: Throwable) {
                    Log.w(TAG, "Frame dispatch failed: ${t.message}")
                }
                continue
            }

            if (readBytes < 0) {
                consecutiveErrors++
                Log.w(TAG, "AudioRecord read error $readBytes ($consecutiveErrors/$READ_ERROR_LIMIT)")
                sleepQuietly(40L)
                if (consecutiveErrors >= READ_ERROR_LIMIT &&
                    thisGeneration == generation &&
                    recording
                ) {
                    record = recoverLocked(thisGeneration, record) ?: break
                    consecutiveErrors = 0
                }
            } else {
                sleepQuietly(5L)
            }
        }

        // A recovery or stop may already have released this instance.
        synchronized(lock) {
            if (thisGeneration != generation && audioRecord !== record) {
                try {
                    record.release()
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun dispatch(frame: Frame) {
        val snapshot: List<Listener>
        synchronized(lock) {
            expireHandoffLocked()
            recentFrames.addLast(frame)
            while (recentFrames.size > MAX_RECENT_FRAMES) {
                recentFrames.removeFirst()
            }
            if (handoffActive) {
                handoffFrames.addLast(frame)
                while (handoffFrames.size > MAX_HANDOFF_FRAMES) {
                    handoffFrames.removeFirst()
                }
            }
            snapshot = listeners.toList()
        }
        for (listener in snapshot) {
            try {
                listener.onFrame(frame)
            } catch (t: Throwable) {
                Log.w(TAG, "Audio listener failed: ${t.message}")
            }
        }
    }

    private fun expireHandoffLocked() {
        if (handoffActive && SystemClock.elapsedRealtime() >= handoffUntil) {
            handoffActive = false
            handoffFrames.clear()
        }
    }

    @SuppressLint("MissingPermission")
    private fun recoverLocked(thisGeneration: Int, oldRecord: AudioRecord): AudioRecord? {
        if (thisGeneration != generation || !recording) return null
        Log.w(TAG, "Recovering microphone bus after repeated read errors")
        releaseAudioEffectsLocked()
        try {
            oldRecord.stop()
        } catch (_: Throwable) {
        }
        try {
            oldRecord.release()
        } catch (_: Throwable) {
        }
        audioRecord = null

        val context = appContext ?: return null
        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
            return null
        }
        return try {
            val newRecord = AudioRecord(
                currentSource,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                max(minBufferSize * 4, 32_000)
            )
            if (newRecord.state != AudioRecord.STATE_INITIALIZED) {
                newRecord.release()
                return null
            }
            attachAudioEffects(newRecord, currentSource)
            newRecord.startRecording()
            audioRecord = newRecord
            Log.i(TAG, "Microphone bus recovered")
            newRecord
        } catch (t: Throwable) {
            Log.e(TAG, "Microphone recovery failed: ${t.message}", t)
            null
        }
    }

    private fun attachAudioEffects(record: AudioRecord, source: Int) {
        // Raw MIC is used by the wake spotter. Platform AEC/NS/AGC can distort
        // or gate the keyword, so leave the raw stream untouched for that path.
        if (source != MediaRecorder.AudioSource.VOICE_COMMUNICATION) {
            Log.i(TAG, "Raw microphone source=$source; platform audio effects skipped")
            return
        }
        val sessionId = record.audioSessionId
        if (AcousticEchoCanceler.isAvailable()) {
            try {
                echoCanceler = AcousticEchoCanceler.create(sessionId)?.apply { enabled = true }
            } catch (t: Throwable) {
                Log.w(TAG, "AEC unavailable: ${t.message}")
            }
        }
        if (NoiseSuppressor.isAvailable()) {
            try {
                noiseSuppressor = NoiseSuppressor.create(sessionId)?.apply { enabled = true }
            } catch (t: Throwable) {
                Log.w(TAG, "NoiseSuppressor unavailable: ${t.message}")
            }
        }
        if (AutomaticGainControl.isAvailable()) {
            try {
                automaticGainControl = AutomaticGainControl.create(sessionId)?.apply { enabled = true }
            } catch (t: Throwable) {
                Log.w(TAG, "AGC unavailable: ${t.message}")
            }
        }
    }

    private fun releaseAudioEffectsLocked() {
        try {
            echoCanceler?.release()
            noiseSuppressor?.release()
            automaticGainControl?.release()
        } catch (_: Throwable) {
        } finally {
            echoCanceler = null
            noiseSuppressor = null
            automaticGainControl = null
        }
    }

    private fun sleepQuietly(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
