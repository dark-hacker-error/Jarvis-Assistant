package com.jarvis.assistant.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.jarvis.assistant.audio.AudioCaptureBus
import com.jarvis.assistant.util.PermissionHelper
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Streaming neural keyword spotter for the wake path.
 *
 * The old wake implementation used a Vosk recognizer and released its
 * microphone before the conversation service started. This implementation:
 *
 *  - uses the bundled Sherpa-ONNX int8 zipformer keyword spotter;
 *  - consumes 10 ms (160 sample) slices from the single shared
 *    [AudioCaptureBus] microphone;
 *  - posts the wake callback on the main looper so the floating orb can be
 *    attached without a background-thread WindowManager crash; and
 *  - calls [AudioCaptureBus.beginHandoff] before detaching, so the conversation
 *    service receives the recent audio instead of losing the command onset.
 *
 * Latency note: the bundled model is a streaming chunk-16 zipformer. Feeding
 * 10 ms slices removes our own buffering delay, but the model's algorithmic
 * chunk size still determines the best-case detection latency (roughly 160 ms,
 * not a guaranteed sub-100 ms result). A smaller model, a smaller chunk size, or
 * a dedicated DSP hotword path would be required for a hard sub-100 ms target.
 */
class SherpaOnnxWakeWordEngine(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onWakeWord: () -> Unit
) {
    companion object {
        private const val TAG = "SherpaWakeWord"
        private const val MODEL_DIR = "models/sherpa-kws"
        private const val ENCODER = "$MODEL_DIR/encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
        private const val DECODER = "$MODEL_DIR/decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
        private const val JOINER = "$MODEL_DIR/joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
        private const val TOKENS = "$MODEL_DIR/tokens.txt"
        private const val KEYWORDS = "$MODEL_DIR/keywords.txt"
        private const val COOLDOWN_MS = 1_500L
        private const val HANDOFF_MS = 3_500L
        private const val MODEL_RETRY_MS = 2_000L
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val modelLock = Any()
    private val audioLock = Any()

    @Volatile
    private var active = false

    @Volatile
    private var paused = true

    @Volatile
    private var modelLoading = false

    @Volatile
    private var spotter: KeywordSpotter? = null
    private var stream: OnlineStream? = null
    private var listenerAttached = false
    private var lastTriggerAt = 0L

    private val busListener = AudioCaptureBus.Listener { frame ->
        if (!active || paused) return@Listener
        feed(frame)
    }

    fun start() {
        active = true
        paused = false
        AudioCaptureBus.endHandoff()
        ensureModelThenListen()
    }

    /**
     * Resume after a conversation/session ends. The model is intentionally kept
     * resident so resume does not pay the ONNX initialization cost again.
     */
    fun resume() {
        active = true
        paused = false
        AudioCaptureBus.endHandoff()
        val currentSpotter = spotter
        val currentStream = stream
        if (currentSpotter != null && currentStream != null) {
            synchronized(audioLock) {
                try {
                    currentSpotter.reset(currentStream)
                } catch (t: Throwable) {
                    Log.w(TAG, "Unable to reset keyword stream: ${t.message}")
                }
            }
            attachListener()
        } else {
            ensureModelThenListen()
        }
    }

    /**
     * Stop consuming microphone frames but keep the model loaded. Used as soon
     * as a wake word is accepted and the hand-off lease is active.
     */
    fun pause() {
        paused = true
        detachListener()
    }

    fun stop() {
        active = false
        paused = true
        detachListener()
        val oldSpotter: KeywordSpotter?
        val oldStream: OnlineStream?
        synchronized(modelLock) {
            oldSpotter = spotter
            oldStream = stream
            spotter = null
            stream = null
        }
        scope.launch(Dispatchers.Default) {
            synchronized(audioLock) {
                try {
                    oldStream?.release()
                    oldSpotter?.release()
                } catch (t: Throwable) {
                    Log.w(TAG, "Unable to release keyword spotter: ${t.message}")
                }
            }
        }
    }

    private fun ensureModelThenListen() {
        if (!active || paused) return
        if (spotter != null && stream != null) {
            attachListener()
            return
        }
        synchronized(modelLock) {
            if (modelLoading) return
            modelLoading = true
        }

        scope.launch(Dispatchers.Default) {
            try {
                Log.i(TAG, "Loading Sherpa-ONNX keyword spotter")
                val config = KeywordSpotterConfig(
                    featConfig = FeatureConfig(sampleRate = 16_000, featureDim = 80),
                    modelConfig = OnlineModelConfig(
                        transducer = OnlineTransducerModelConfig(
                            encoder = ENCODER,
                            decoder = DECODER,
                            joiner = JOINER
                        ),
                        tokens = TOKENS,
                        numThreads = 1,
                        debug = false,
                        provider = "cpu",
                        modelType = "zipformer2"
                    ),
                    maxActivePaths = 4,
                    keywordsFile = KEYWORDS,
                    keywordsScore = 1.5f,
                    keywordsThreshold = 0.25f,
                    numTrailingBlanks = 1
                )
                val newSpotter = KeywordSpotter(context.assets, config)
                val newStream = newSpotter.createStream("")

                synchronized(modelLock) {
                    if (!active) {
                        newStream.release()
                        newSpotter.release()
                        modelLoading = false
                        return@launch
                    }
                    spotter = newSpotter
                    stream = newStream
                    modelLoading = false
                }
                Log.i(TAG, "Keyword spotter ready; listening for JARVIS")
                if (!paused) attachListener()
            } catch (t: Throwable) {
                synchronized(modelLock) {
                    modelLoading = false
                }
                Log.e(TAG, "Keyword spotter initialization failed: ${t.message}", t)
                if (active && !paused) {
                    mainHandler.postDelayed(
                        { ensureModelThenListen() },
                        MODEL_RETRY_MS
                    )
                }
            }
        }
    }

    private fun attachListener() {
        if (!active || paused || listenerAttached) return
        if (!PermissionHelper.hasRecordAudioPermission(context)) {
            Log.w(TAG, "RECORD_AUDIO permission not granted; wake listener not attached")
            return
        }
        listenerAttached = AudioCaptureBus.register(context, busListener)
        if (listenerAttached) {
            Log.i(TAG, "Wake listener attached to shared microphone bus")
        } else {
            Log.w(TAG, "Unable to attach wake listener; retrying")
            if (active && !paused) {
                mainHandler.postDelayed({ attachListener() }, MODEL_RETRY_MS)
            }
        }
    }

    private fun detachListener() {
        if (!listenerAttached) return
        listenerAttached = false
        AudioCaptureBus.unregister(busListener)
    }

    private fun feed(frame: AudioCaptureBus.Frame) {
        synchronized(audioLock) {
            if (!active || paused) return
            val currentSpotter = spotter ?: return
            val currentStream = stream ?: return

            try {
                var offset = 0
                while (offset + AudioCaptureBus.WAKE_CHUNK_SIZE_BYTES <= frame.pcm.size) {
                    if (!active || paused) return
                    val bytes = frame.pcm.copyOfRange(
                        offset,
                        offset + AudioCaptureBus.WAKE_CHUNK_SIZE_BYTES
                    )
                    offset += AudioCaptureBus.WAKE_CHUNK_SIZE_BYTES

                    val samples = FloatArray(bytes.size / 2)
                    var sampleIndex = 0
                    var byteIndex = 0
                    while (byteIndex + 1 < bytes.size) {
                        val low = bytes[byteIndex].toInt() and 0xFF
                        val high = bytes[byteIndex + 1].toInt()
                        val value = (high shl 8) or low
                        samples[sampleIndex++] = value.toFloat() / 32768f
                        byteIndex += 2
                    }

                    currentStream.acceptWaveform(samples, AudioCaptureBus.SAMPLE_RATE)
                    var decodeGuard = 0
                    while (currentSpotter.isReady(currentStream) && decodeGuard++ < 8) {
                        currentSpotter.decode(currentStream)
                        val result = currentSpotter.getResult(currentStream)
                        if (result.keyword.isNotBlank()) {
                            Log.i(
                                TAG,
                                "Wake keyword '${result.keyword}' detected at " +
                                    SystemClock.elapsedRealtime() + " ms"
                            )
                            currentSpotter.reset(currentStream)
                            triggerWake()
                            return
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Keyword stream error: ${t.message}", t)
                // Recreate the stream on the next resume/start rather than
                // leaving the engine in a permanently broken state.
                synchronized(modelLock) {
                    stream?.release()
                    stream = null
                }
            }
        }
    }

    private fun triggerWake() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastTriggerAt < COOLDOWN_MS) return
        lastTriggerAt = now

        paused = true
        // Keep the microphone alive while the existing conversation service
        // starts and attaches its own AudioRecorder to the same bus.
        val handoff = AudioCaptureBus.beginHandoff(HANDOFF_MS)
        detachListener()
        Log.i(TAG, "Wake accepted; microphone hand-off active=$handoff")

        mainHandler.post {
            if (!active) return@post
            try {
                onWakeWord()
            } catch (t: Throwable) {
                Log.e(TAG, "Wake callback failed: ${t.message}", t)
            }
        }
    }
}
