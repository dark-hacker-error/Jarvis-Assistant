package com.jarvis.assistant.audio

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Offline speaker-embedding extractor used by the one-shot wake verifier.
 *
 * The model is a compact WeSpeaker CAM++ network (English VoxCeleb). It is
 * deliberately independent from the legacy Vosk x-vector path:
 *
 *  - the wake service only needs this model + the keyword spotter, not the
 *    full 68 MB Vosk ASR model;
 *  - the same 16 kHz PCM buffer that produced the keyword is reused, so the
 *    user never has to speak a second verification phrase;
 *  - embeddings are L2-normalised before comparison, which makes the cosine
 *    distance stable across device microphones and recording levels.
 *
 * The extractor is created lazily and kept resident for the lifetime of the
 * process. A failed load is cached so a missing/corrupt asset does not retry
 * on every microphone frame.
 */
object SherpaSpeakerVerifier {

    private const val TAG = "SherpaSpeakerVerifier"
    private const val MODEL_PATH = "models/speaker/wespeaker_en_voxceleb_CAMpp.onnx"
    private const val SAMPLE_RATE = 16_000
    private const val MIN_AUDIO_BYTES = 16_000 * 2 * 4 / 10 // 400 ms

    private val lock = Any()

    @Volatile
    private var extractor: SpeakerEmbeddingExtractor? = null

    @Volatile
    private var loadFailed = false

    /** True once the model is loaded and usable in this process. */
    fun isReady(context: Context): Boolean = ensureExtractor(context) != null

    /**
     * Loads the model ahead of the first wake so verification starts at
     * embedding inference instead of a 28 MB ONNX initialisation.
     */
    fun preload(context: Context) {
        ensureExtractor(context)
    }

    /**
     * Frees the 28 MB speaker model. The wake service calls this after an idle
     * period so a phone that has not heard "Jarvis" for a while only keeps the
     * keyword spotter resident. The next verification transparently reloads
     * the model.
     */
    fun release() {
        synchronized(lock) {
            try {
                extractor?.release()
            } catch (_: Throwable) {
            }
            extractor = null
            loadFailed = false
        }
    }

    /**
     * Extracts a normalised speaker embedding from 16 kHz mono PCM16 audio.
     * Returns null when the model is unavailable, the clip is too short, or
     * the model cannot produce a finite embedding.
     */
    fun extractEmbedding(context: Context, pcm16: ByteArray): FloatArray? {
        if (pcm16.size < MIN_AUDIO_BYTES) {
            Log.d(TAG, "Clip too short for speaker embedding (${pcm16.size} bytes)")
            return null
        }

        val model = ensureExtractor(context) ?: return null

        synchronized(lock) {
            var stream: com.k2fsa.sherpa.onnx.OnlineStream? = null
            return try {
                val voiced = trimSilence(pcm16)
                val samples = FloatArray(voiced.size / 2)
                var sampleIndex = 0
                var byteIndex = 0
                while (byteIndex + 1 < voiced.size) {
                    val low = voiced[byteIndex].toInt() and 0xFF
                    val high = voiced[byteIndex + 1].toInt()
                    val value = (high shl 8) or low
                    samples[sampleIndex++] = value.toFloat() / 32768f
                    byteIndex += 2
                }

                stream = model.createStream()
                stream.acceptWaveform(samples, SAMPLE_RATE)
                stream.inputFinished()
                if (!model.isReady(stream)) {
                    Log.w(TAG, "Speaker model is not ready for this clip")
                    return null
                }

                val embedding = model.compute(stream)
                normalise(embedding)
            } catch (t: Throwable) {
                Log.e(TAG, "Speaker embedding failed: ${t.message}", t)
                null
            } finally {
                try {
                    stream?.release()
                } catch (_: Throwable) {
                }
            }
        }
    }

    /** Cosine distance in [0, 2]; lower means the same speaker. */
    fun cosineDistance(first: FloatArray, second: FloatArray): Float {
        if (first.isEmpty() || second.isEmpty() || first.size != second.size) return 1f

        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in first.indices) {
            val a = first[i].toDouble()
            val b = second[i].toDouble()
            dot += a * b
            normA += a * a
            normB += b * b
        }
        if (normA <= 0.0 || normB <= 0.0) return 1f
        val similarity = (dot / (sqrt(normA) * sqrt(normB))).coerceIn(-1.0, 1.0)
        return (1.0 - similarity).toFloat().coerceIn(0f, 2f)
    }

    fun similarityPercent(distance: Float): Int {
        val similarity = (1f - (distance / 1.35f)) * 100f
        return similarity.toInt().coerceIn(0, 100)
    }

    private fun ensureExtractor(context: Context): SpeakerEmbeddingExtractor? {
        extractor?.let { return it }
        if (loadFailed) return null

        synchronized(lock) {
            extractor?.let { return it }
            if (loadFailed) return null
            return try {
                Log.i(TAG, "Loading speaker embedding model: $MODEL_PATH")
                val config = SpeakerEmbeddingExtractorConfig(
                    model = MODEL_PATH,
                    numThreads = 1,
                    debug = false,
                    provider = "cpu"
                )
                val created = SpeakerEmbeddingExtractor(context.applicationContext.assets, config)
                if (created.dim() <= 0) {
                    created.release()
                    loadFailed = true
                    Log.e(TAG, "Speaker model reported an invalid embedding dimension")
                    null
                } else {
                    extractor = created
                    Log.i(TAG, "Speaker model ready (dim=${created.dim()})")
                    created
                }
            } catch (t: Throwable) {
                loadFailed = true
                Log.e(TAG, "Unable to load speaker model: ${t.message}", t)
                null
            }
        }
    }

    /**
     * Drops leading/trailing near-silence so the embedding is computed on the
     * spoken phrase instead of the surrounding room noise. Keeps a small guard
     * band on both ends because WeSpeaker expects some context.
     */
    private fun trimSilence(pcm16: ByteArray): ByteArray {
        val sampleCount = pcm16.size / 2
        if (sampleCount < 1_600) return pcm16

        val samples = FloatArray(sampleCount)
        var peak = 0f
        var index = 0
        var byteIndex = 0
        while (byteIndex + 1 < pcm16.size) {
            val low = pcm16[byteIndex].toInt() and 0xFF
            val high = pcm16[byteIndex + 1].toInt()
            val value = ((high shl 8) or low).toFloat() / 32768f
            samples[index++] = value
            if (abs(value) > peak) peak = abs(value)
            byteIndex += 2
        }
        if (peak <= 0.01f) return pcm16

        val gate = (peak * 0.12f).coerceAtLeast(0.01f)
        var start = 0
        while (start < sampleCount && abs(samples[start]) < gate) start++
        var end = sampleCount - 1
        while (end > start && abs(samples[end]) < gate) end--
        if (end <= start) return pcm16

        val guard = (SAMPLE_RATE * 15 / 100) // 150 ms of context
        start = (start - guard).coerceAtLeast(0)
        end = (end + guard).coerceAtMost(sampleCount - 1)

        val out = ByteArray((end - start + 1) * 2)
        System.arraycopy(pcm16, start * 2, out, 0, out.size)
        return out
    }

    private fun normalise(vector: FloatArray): FloatArray? {
        if (vector.isEmpty()) return null
        var norm = 0.0
        for (value in vector) {
            if (!value.isFinite()) return null
            norm += value.toDouble() * value.toDouble()
        }
        val length = sqrt(norm).toFloat()
        if (length <= 0f) return null
        for (i in vector.indices) vector[i] /= length
        return vector
    }
}
