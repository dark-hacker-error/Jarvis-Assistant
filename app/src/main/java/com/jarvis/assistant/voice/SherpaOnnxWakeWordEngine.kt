package com.jarvis.assistant.voice

import android.content.Context
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.jarvis.assistant.audio.AudioCaptureBus
import com.jarvis.assistant.audio.AudioUtils
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
import java.util.ArrayDeque

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
    private val onWakeWord: () -> Unit,
    /**
     * Part 2 and the system assist session share the in-process
     * [AudioCaptureBus], so they keep the hand-off lease enabled. Part 1 runs
     * in a different process and must release its own AudioRecord before Part 2
     * starts, so it passes false and sends the recent PCM through the intent.
     */
    private val useBusHandoff: Boolean = true,
    /**
     * Part 1 uses the raw microphone so the keyword spotter is not fed through
     * the platform voice-processing chain. Part 2 keeps the communication
     * source because it needs echo cancellation while Jarvis is speaking.
     */
    private val preferRawMic: Boolean = false
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
        private const val DIAGNOSTIC_INTERVAL_MS = 10_000L
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val modelLock = Any()
    private val audioLock = Any()

    // Rolling ~3 s of PCM. The wake callback reads the same audio that
    // produced the keyword, so speaker verification needs no second phrase.
    private val recentLock = Any()
    private val recentChunks = ArrayDeque<ByteArray>()
    private var recentBytes = 0
    private val maxRecentBytes = AudioCaptureBus.SAMPLE_RATE * 2 * 3

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

    // AudioRecord.read() is allowed to return fewer bytes than requested. The
    // old code dropped those partial frames, which on some devices meant the
    // spotter never received a single 10 ms chunk. Carry the remainder across
    // frames so every 320-byte chunk is fed exactly once.
    private var pendingWakeBytes = ByteArray(0)
    private var framesFed = 0L
    private var chunksFed = 0L
    private var peakRms = 0f
    private var lastDiagnosticAt = 0L

    private val busListener = AudioCaptureBus.Listener { frame ->
        if (!active || paused) return@Listener
        feed(frame)
    }

    fun start() {
        active = true
        paused = false
        clearRecent()
        resetFeedState()
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
        clearRecent()
        resetFeedState()
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
        synchronized(audioLock) {
            pendingWakeBytes = ByteArray(0)
        }
    }

    fun stop() {
        active = false
        paused = true
        detachListener()
        synchronized(audioLock) {
            pendingWakeBytes = ByteArray(0)
        }
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
                val loadStartedAt = SystemClock.elapsedRealtime()
                Log.i(TAG, "Loading Sherpa-ONNX keyword spotter")
                val previousSpotter: KeywordSpotter?
                val previousStream: OnlineStream?
                synchronized(modelLock) {
                    previousSpotter = spotter
                    previousStream = stream
                    spotter = null
                    stream = null
                }
                try {
                    previousStream?.release()
                } catch (_: Throwable) {
                }
                if (previousSpotter != null) {
                    try {
                        previousSpotter.release()
                    } catch (_: Throwable) {
                    }
                }
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
                    numTrailingBlanks = 2
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
                Log.i(
                    TAG,
                    "Keyword spotter ready in ${SystemClock.elapsedRealtime() - loadStartedAt} ms; " +
                        "listening for JARVIS"
                )
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
        val source = if (preferRawMic) {
            MediaRecorder.AudioSource.MIC
        } else {
            MediaRecorder.AudioSource.VOICE_COMMUNICATION
        }
        listenerAttached = AudioCaptureBus.register(context, busListener, source)
        if (listenerAttached) {
            Log.i(TAG, "Wake listener attached to shared microphone bus (source=$source)")
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

    /**
     * Returns up to [maxMs] of the most recent 16 kHz PCM16 audio in
     * chronological order. Called by the wake callback to run one-shot speaker
     * verification on the same buffer that produced the keyword.
     */
    fun recentAudio(maxMs: Int = 2_000): ByteArray {
        val maxBytes = (AudioCaptureBus.SAMPLE_RATE * 2 * maxMs / 1000)
            .coerceIn(0, maxRecentBytes)
        synchronized(recentLock) {
            val size = minOf(recentBytes, maxBytes)
            if (size <= 0) return ByteArray(0)
            val out = ByteArray(size)
            var write = size
            val iterator = recentChunks.descendingIterator()
            while (iterator.hasNext() && write > 0) {
                val chunk = iterator.next()
                val take = minOf(chunk.size, write)
                write -= take
                System.arraycopy(chunk, chunk.size - take, out, write, take)
            }
            return out
        }
    }

    private fun appendRecent(pcm: ByteArray) {
        if (pcm.isEmpty()) return
        synchronized(recentLock) {
            recentChunks.addLast(pcm.copyOf())
            recentBytes += pcm.size
            while (recentBytes > maxRecentBytes && recentChunks.isNotEmpty()) {
                recentBytes -= recentChunks.removeFirst().size
            }
        }
    }

    private fun clearRecent() {
        synchronized(recentLock) {
            recentChunks.clear()
            recentBytes = 0
        }
    }

    private fun resetFeedState() {
        synchronized(audioLock) {
            pendingWakeBytes = ByteArray(0)
            framesFed = 0L
            chunksFed = 0L
            peakRms = 0f
            lastDiagnosticAt = 0L
        }
    }

    private fun feed(frame: AudioCaptureBus.Frame) {
        synchronized(audioLock) {
            if (!active || paused) return
            appendRecent(frame.pcm)

            framesFed++
            val rms = AudioUtils.calculateRmsNormalized(frame.pcm, frame.pcm.size)
            if (rms > peakRms) peakRms = rms
            val now = SystemClock.elapsedRealtime()
            if (lastDiagnosticAt == 0L || now - lastDiagnosticAt >= DIAGNOSTIC_INTERVAL_MS) {
                lastDiagnosticAt = now
                Log.i(
                    TAG,
                    "KWS feed: frames=$framesFed chunks=$chunksFed peakRms=$peakRms " +
                        "pending=${pendingWakeBytes.size}B frame=${frame.pcm.size}B " +
                        "modelReady=${spotter != null && stream != null}"
                )
                peakRms = 0f
            }

            val currentSpotter = spotter ?: return
            val currentStream = stream ?: return

            // Carry any partial 10 ms tail into the next microphone frame.
            val combined = if (pendingWakeBytes.isEmpty()) {
                frame.pcm
            } else {
                ByteArray(pendingWakeBytes.size + frame.pcm.size).also { out ->
                    System.arraycopy(pendingWakeBytes, 0, out, 0, pendingWakeBytes.size)
                    System.arraycopy(frame.pcm, 0, out, pendingWakeBytes.size, frame.pcm.size)
                }
            }

            try {
                var offset = 0
                while (offset + AudioCaptureBus.WAKE_CHUNK_SIZE_BYTES <= combined.size) {
                    if (!active || paused) return
                    val bytes = combined.copyOfRange(
                        offset,
                        offset + AudioCaptureBus.WAKE_CHUNK_SIZE_BYTES
                    )
                    offset += AudioCaptureBus.WAKE_CHUNK_SIZE_BYTES
                    chunksFed++

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
                                    SystemClock.elapsedRealtime() + " ms " +
                                    "(frames=$framesFed chunks=$chunksFed)"
                            )
                            currentSpotter.reset(currentStream)
                            pendingWakeBytes = ByteArray(0)
                            triggerWake()
                            return
                        }
                    }
                }
                pendingWakeBytes = if (offset < combined.size) {
                    combined.copyOfRange(offset, combined.size)
                } else {
                    ByteArray(0)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Keyword stream error: ${t.message}", t)
                pendingWakeBytes = ByteArray(0)
                // Recreate both the stream and the spotter. Keeping a broken
                // spotter here used to leave the engine silently deaf.
                val brokenSpotter = synchronized(modelLock) {
                    val old = spotter
                    try {
                        stream?.release()
                    } catch (_: Throwable) {
                    }
                    stream = null
                    spotter = null
                    old
                }
                try {
                    brokenSpotter?.release()
                } catch (_: Throwable) {
                }
                mainHandler.postDelayed(
                    { if (active && !paused) ensureModelThenListen() },
                    MODEL_RETRY_MS
                )
            }
        }
    }

    private fun triggerWake() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastTriggerAt < COOLDOWN_MS) return
        lastTriggerAt = now

        paused = true
        // Part 2 (same process) keeps the microphone alive while the existing
        // conversation service starts. Part 1 releases its own AudioRecord
        // explicitly because Part 2 runs in another process.
        val handoff = if (useBusHandoff) {
            AudioCaptureBus.beginHandoff(HANDOFF_MS)
        } else {
            false
        }
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
