package com.jarvis.assistant.audio

import android.util.Log
import com.jarvis.assistant.data.preferences.AppPreferences
import org.json.JSONObject
import org.vosk.Recognizer
import kotlin.math.sqrt

data class VoiceMatchResult(
    val isMatch: Boolean,
    val distance: Float,
    val similarityPercent: Int,
    val threshold: Float,
    val hasExtractedVector: Boolean = true
)

object OwnerVoiceManager {
    private const val TAG = "OwnerVoiceManager"

    // Vosk/Kaldi 128-d x-vector cosine distance thresholds.
    // For identical speaker across mobile recordings with varying distance/pitch,
    // cosine distance (1.0 - cosSim) ranges between 0.40 and 0.68.
    // Completely different speakers typically measure 0.85 to 1.30+.
    const val DEFAULT_THRESHOLD = 0.70f   // Balanced: reliable owner match, 100% blocks strangers
    const val STRICT_THRESHOLD = 0.62f    // Strict: high security
    const val RELAXED_THRESHOLD = 0.78f   // Relaxed: tolerant of noisy environments

    // WeSpeaker CAM++ cosine-distance thresholds. These are intentionally
    // tighter than the legacy Vosk x-vector values because the embedding
    // space is different and better separated.
    const val SHERPA_STRICT_THRESHOLD = 0.32f
    const val SHERPA_DEFAULT_THRESHOLD = 0.42f
    const val SHERPA_RELAXED_THRESHOLD = 0.52f

    /**
     * Calculates the cosine distance between two speaker embedding vectors:
     * distance = 1.0 - (dot(v1, v2) / (norm(v1) * norm(v2)))
     * Range is [0.0, 2.0]. Lower values indicate identical vocal characteristics.
     */
    fun calculateCosineDistance(v1: FloatArray, v2: FloatArray): Float {
        if (v1.isEmpty() || v2.isEmpty() || v1.size != v2.size) return 1.0f

        var dot = 0.0
        var norm1 = 0.0
        var norm2 = 0.0

        for (i in v1.indices) {
            val a = v1[i].toDouble()
            val b = v2[i].toDouble()
            dot += a * b
            norm1 += a * a
            norm2 += b * b
        }

        if (norm1 <= 0.0 || norm2 <= 0.0) return 1.0f

        val cosSim = (dot / (sqrt(norm1) * sqrt(norm2))).coerceIn(-1.0, 1.0)
        return (1.0 - cosSim).toFloat().coerceIn(0.0f, 2.0f)
    }

    /**
     * Converts a cosine distance to a human-friendly similarity percentage [0..100].
     */
    fun distanceToSimilarityPercent(distance: Float): Int {
        val sim = (1.0f - (distance / 1.35f)) * 100f
        return sim.toInt().coerceIn(0, 100)
    }

    /**
     * Computes the centroid (normalized average) of multiple speaker sample embeddings.
     */
    fun averageVectors(vectors: List<FloatArray>): FloatArray? {
        if (vectors.isEmpty()) return null
        val dim = vectors[0].size
        val avg = FloatArray(dim)
        var validCount = 0

        for (vec in vectors) {
            if (vec.size != dim) continue
            validCount++
            for (i in 0 until dim) {
                avg[i] += vec[i]
            }
        }

        // Divide only by vectors that were actually accumulated. Dividing by the
        // full list size shrinks the centroid whenever a mismatched sample exists.
        if (validCount == 0) return null
        val count = validCount.toFloat()
        for (i in 0 until dim) {
            avg[i] /= count
        }

        // Normalize the averaged vector
        var norm = 0.0
        for (v in avg) {
            norm += v.toDouble() * v.toDouble()
        }
        val sqrtNorm = sqrt(norm).toFloat()
        if (sqrtNorm > 0f) {
            for (i in 0 until dim) {
                avg[i] /= sqrtNorm
            }
        }

        return avg
    }

    /**
     * Extracts a speaker embedding vector (x-vector) from a 16kHz mono 16-bit PCM audio buffer.
     */
    @Synchronized
    fun extractSpeakerVector(pcmAudio: ByteArray): FloatArray? {
        val model = VoskModelManager.model
        val spkModel = VoskModelManager.speakerModel

        if (model == null || spkModel == null) {
            Log.w(TAG, "Cannot extract speaker vector: Vosk model or speaker model is not ready")
            return null
        }
        if (pcmAudio.size < 3200) { // Require at least 100ms of audio (16000 * 2 * 0.1)
            Log.w(TAG, "Audio buffer too short for speaker extraction (${pcmAudio.size} bytes)")
            return null
        }

        var rec: Recognizer? = null
        return try {
            rec = Recognizer(model, 16000.0f, spkModel)

            // Feed audio in clean 4096-byte blocks so Kaldi's acoustic and x-vector pipeline processes all frames
            var offset = 0
            val chunkSize = 4096
            while (offset < pcmAudio.size) {
                val len = minOf(chunkSize, pcmAudio.size - offset)
                rec.acceptWaveForm(pcmAudio.copyOfRange(offset, offset + len), len)
                offset += len
            }

            // Feed 400ms of trailing silence padding so Kaldi's nnet3 context window completes pooling on short commands
            val silencePadding = ByteArray(12800)
            rec.acceptWaveForm(silencePadding, silencePadding.size)

            val jsonStr = rec.finalResult
            if (jsonStr.isNullOrBlank()) return null

            val json = JSONObject(jsonStr)
            if (json.has("spk")) {
                val arr = json.getJSONArray("spk")
                val vector = FloatArray(arr.length()) { i -> arr.getDouble(i).toFloat() }
                if (vector.isNotEmpty()) vector else null
            } else {
                Log.d(TAG, "No 'spk' field found in Vosk final result: $jsonStr")
                null
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error extracting speaker vector from PCM: ${e.message}", e)
            null
        } finally {
            try {
                rec?.close()
            } catch (_: Exception) {}
        }
    }

    /**
     * Verifies an extracted speaker vector against the enrolled owner profile stored in preferences.
     * Uses multi-sample nearest-neighbor + centroid matching:
     * Compares against both the averaged centroid AND each individual enrolled sample vector.
     */
    fun verifySpeakerVector(currentVector: FloatArray, prefs: AppPreferences): VoiceMatchResult {
        val masterProfile = prefs.getOwnerVoiceProfile()
        val sampleProfiles = prefs.getOwnerVoiceSamples()
        val threshold = prefs.ownerVoiceThreshold

        if ((masterProfile == null || masterProfile.isEmpty()) && sampleProfiles.isEmpty()) {
            // No profile enrolled yet; allow all speakers by default
            return VoiceMatchResult(
                isMatch = true,
                distance = 0.0f,
                similarityPercent = 100,
                threshold = threshold
            )
        }

        var minDistance = 2.0f

        // Check against master centroid
        if (masterProfile != null && masterProfile.isNotEmpty()) {
            val d = calculateCosineDistance(currentVector, masterProfile)
            if (d < minDistance) minDistance = d
        }

        // Check against each enrolled sample vector for optimal match across tones/cadences
        for (sample in sampleProfiles) {
            val d = calculateCosineDistance(currentVector, sample)
            if (d < minDistance) minDistance = d
        }

        val isMatch = minDistance <= threshold
        val similarityPercent = distanceToSimilarityPercent(minDistance)

        return VoiceMatchResult(
            isMatch = isMatch,
            distance = minDistance,
            similarityPercent = similarityPercent,
            threshold = threshold
        )
    }

    // ------------------------------------------------------------------
    // One-shot Sherpa/WeSpeaker path (wake service + upgraded enrollment)
    // ------------------------------------------------------------------

    fun extractSherpaVector(context: android.content.Context, pcmAudio: ByteArray): FloatArray? =
        SherpaSpeakerVerifier.extractEmbedding(context, pcmAudio)

    fun verifySherpaVector(
        currentVector: FloatArray,
        prefs: AppPreferences
    ): VoiceMatchResult {
        val masterProfile = prefs.getOwnerVoiceSherpaProfile()
        val sampleProfiles = prefs.getOwnerVoiceSherpaSamples()
        val threshold = prefs.ownerVoiceSherpaThreshold

        if ((masterProfile == null || masterProfile.isEmpty()) && sampleProfiles.isEmpty()) {
            return VoiceMatchResult(
                isMatch = true,
                distance = 0.0f,
                similarityPercent = 100,
                threshold = threshold
            )
        }

        var minDistance = 2.0f
        if (masterProfile != null && masterProfile.isNotEmpty()) {
            val distance = SherpaSpeakerVerifier.cosineDistance(currentVector, masterProfile)
            if (distance < minDistance) minDistance = distance
        }
        for (sample in sampleProfiles) {
            val distance = SherpaSpeakerVerifier.cosineDistance(currentVector, sample)
            if (distance < minDistance) minDistance = distance
        }

        return VoiceMatchResult(
            isMatch = minDistance <= threshold,
            distance = minDistance,
            similarityPercent = SherpaSpeakerVerifier.similarityPercent(minDistance),
            threshold = threshold
        )
    }

    fun verifySherpaAudio(
        context: android.content.Context,
        pcmAudio: ByteArray,
        prefs: AppPreferences
    ): VoiceMatchResult {
        if (!prefs.isOwnerVoiceEnabled || !prefs.hasSherpaVoiceProfile()) {
            return VoiceMatchResult(
                isMatch = true,
                distance = 0.0f,
                similarityPercent = 100,
                threshold = prefs.ownerVoiceSherpaThreshold
            )
        }

        val vector = extractSherpaVector(context, pcmAudio)
        if (vector == null) {
            return VoiceMatchResult(
                isMatch = false,
                distance = 1.0f,
                similarityPercent = 0,
                threshold = prefs.ownerVoiceSherpaThreshold,
                hasExtractedVector = false
            )
        }
        return verifySherpaVector(vector, prefs)
    }

    /**
     * Preferred verifier for both the wake service and the conversation
     * service. New enrollments use the Sherpa embedding; older installations
     * keep the Vosk x-vector path until they re-enroll once.
     */
    fun verifyBestAvailableAudio(
        context: android.content.Context,
        pcmAudio: ByteArray,
        prefs: AppPreferences
    ): VoiceMatchResult {
        if (!prefs.isOwnerVoiceEnabled || !prefs.hasAnyOwnerVoiceProfile()) {
            return VoiceMatchResult(
                isMatch = true,
                distance = 0.0f,
                similarityPercent = 100,
                threshold = prefs.ownerVoiceThreshold
            )
        }

        if (prefs.hasSherpaVoiceProfile()) {
            val sherpa = verifySherpaAudio(context, pcmAudio, prefs)
            // If the new model is unavailable on this device, fall back to the
            // legacy profile instead of locking the owner out.
            if (sherpa.hasExtractedVector || !prefs.hasOwnerVoiceProfile()) return sherpa
        }
        return verifySpeakerAudio(pcmAudio, prefs)
    }

    /**
     * Verifies raw PCM audio against the enrolled owner profile.
     */
    fun verifySpeakerAudio(pcmAudio: ByteArray, prefs: AppPreferences): VoiceMatchResult {
        if (!prefs.isOwnerVoiceEnabled || !prefs.hasOwnerVoiceProfile()) {
            return VoiceMatchResult(
                isMatch = true,
                distance = 0.0f,
                similarityPercent = 100,
                threshold = prefs.ownerVoiceThreshold
            )
        }

        val vector = extractSpeakerVector(pcmAudio)
        if (vector == null) {
            Log.w(TAG, "Could not extract speaker vector from audio; insufficient voiced frames")
            return VoiceMatchResult(
                isMatch = false,
                distance = 1.0f,
                similarityPercent = 0,
                threshold = prefs.ownerVoiceThreshold,
                hasExtractedVector = false
            )
        }

        return verifySpeakerVector(vector, prefs)
    }
}
