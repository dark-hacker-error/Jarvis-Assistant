package com.jarvis.assistant.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class AudioRecorder(
    private val onAudioChunk: (chunk: ByteArray, amplitude: Float, frequencyData: AudioFrequencyData) -> Unit
) {
    companion object {
        private const val TAG = "AudioRecorder"
        const val SAMPLE_RATE = 16000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        // Ultra-low latency 20ms chunk: 16000 samples/sec * 0.02s * 2 bytes/sample = 640 bytes
        const val CHUNK_SIZE_BYTES = 640
    }

    private var audioRecord: AudioRecord? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var automaticGainControl: AutomaticGainControl? = null
    private var recordingJob: Job? = null
    private val isRecording = AtomicBoolean(false)
    val isMuted = AtomicBoolean(false)

    @SuppressLint("MissingPermission")
    fun start(scope: CoroutineScope): Boolean {
        if (isRecording.get()) return true

        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(TAG, "Unable to get valid min buffer size")
            return false
        }

        val bufferSize = maxOf(minBufferSize * 4, 32000)

        return try {
            val record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord initialization failed")
                record.release()
                return false
            }

            val sessionId = record.audioSessionId
            if (AcousticEchoCanceler.isAvailable()) {
                try {
                    echoCanceler = AcousticEchoCanceler.create(sessionId)?.apply {
                        enabled = true
                    }
                    Log.i(TAG, "Hardware AcousticEchoCanceler enabled on session $sessionId")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to enable AcousticEchoCanceler: ${e.message}")
                }
            }

            if (NoiseSuppressor.isAvailable()) {
                try {
                    noiseSuppressor = NoiseSuppressor.create(sessionId)?.apply {
                        enabled = true
                    }
                    Log.i(TAG, "Hardware NoiseSuppressor enabled on session $sessionId")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to enable NoiseSuppressor: ${e.message}")
                }
            }

            if (AutomaticGainControl.isAvailable()) {
                try {
                    automaticGainControl = AutomaticGainControl.create(sessionId)?.apply {
                        enabled = true
                    }
                    Log.i(TAG, "Hardware AutomaticGainControl enabled on session $sessionId")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to enable AutomaticGainControl: ${e.message}")
                }
            }

            record.startRecording()
            audioRecord = record
            isRecording.set(true)

            recordingJob = scope.launch(Dispatchers.IO) {
                try {
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
                } catch (_: Exception) {}
                val buffer = ByteArray(CHUNK_SIZE_BYTES)
                var currentRecord: AudioRecord? = record
                while (isActive && isRecording.get()) {
                    var consecutiveErrors = 0
                    while (isActive && isRecording.get() && currentRecord != null) {
                        try {
                            val readBytes = currentRecord.read(buffer, 0, buffer.size)
                            if (readBytes > 0) {
                                consecutiveErrors = 0
                                val freq = AudioUtils.analyzeFrequency(buffer, readBytes, SAMPLE_RATE)
                                if (!isMuted.get()) {
                                    val chunkCopy = buffer.copyOf(readBytes)
                                    onAudioChunk(chunkCopy, freq.amplitude, freq)
                                } else {
                                    onAudioChunk(ByteArray(0), 0f, AudioFrequencyData())
                                }
                            } else if (readBytes < 0) {
                                consecutiveErrors++
                                Log.w(TAG, "AudioRecord read error code: $readBytes (error count: $consecutiveErrors)")
                                delay(40)
                                if (consecutiveErrors > 10 && isRecording.get()) {
                                    Log.e(TAG, "Consecutive AudioRecord read errors exceeded limit. Attempting auto-recovery...")
                                    break
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Exception during AudioRecord read loop: ${e.message}", e)
                            delay(50)
                        }
                    }

                    // Auto-recovery if broken but still meant to be recording:
                    if (isActive && isRecording.get()) {
                        delay(400)
                        try {
                            currentRecord?.release()
                        } catch (_: Exception) {}
                        try {
                            val newRecord = AudioRecord(
                                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                                SAMPLE_RATE,
                                CHANNEL_CONFIG,
                                AUDIO_FORMAT,
                                bufferSize
                            )
                            if (newRecord.state == AudioRecord.STATE_INITIALIZED) {
                                newRecord.startRecording()
                                currentRecord = newRecord
                                audioRecord = newRecord
                                Log.i(TAG, "AudioRecord successfully auto-recovered and restarted!")
                            } else {
                                newRecord.release()
                                delay(1000)
                            }
                        } catch (reInitEx: Exception) {
                            Log.e(TAG, "AudioRecord auto-recovery failed: ${reInitEx.message}")
                            delay(1000)
                        }
                    }
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error starting AudioRecord: ${e.message}", e)
            false
        }
    }

    fun stop() {
        isRecording.set(false)
        recordingJob?.cancel()
        recordingJob = null

        try {
            echoCanceler?.release()
            noiseSuppressor?.release()
            automaticGainControl?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing audio effects: ${e.message}")
        } finally {
            echoCanceler = null
            noiseSuppressor = null
            automaticGainControl = null
        }

        try {
            audioRecord?.let {
                if (it.state == AudioRecord.STATE_INITIALIZED) {
                    it.stop()
                }
                it.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping AudioRecord: ${e.message}")
        } finally {
            audioRecord = null
        }
    }

    fun setMuted(muted: Boolean) {
        isMuted.set(muted)
    }
}
