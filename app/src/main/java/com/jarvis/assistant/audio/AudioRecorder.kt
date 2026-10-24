package com.jarvis.assistant.audio

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Compatibility facade for the existing conversation pipeline.
 *
 * The microphone itself now belongs to [AudioCaptureBus]. This class only
 * subscribes to the bus, applies the mute flag, and drains the wake-word
 * hand-off pre-roll before live frames are forwarded. As a result the
 * conversation service no longer opens a second AudioRecord while the wake
 * engine is releasing its own.
 */
class AudioRecorder(
    private val context: Context,
    private val onAudioChunk: (chunk: ByteArray, amplitude: Float, frequencyData: AudioFrequencyData) -> Unit
) {
    companion object {
        private const val TAG = "AudioRecorder"
        const val SAMPLE_RATE = AudioCaptureBus.SAMPLE_RATE
        const val CHUNK_SIZE_BYTES = AudioCaptureBus.CHUNK_SIZE_BYTES
    }

    private val isRecording = AtomicBoolean(false)
    private val handoffGate = AtomicBoolean(false)
    private val gateLock = Any()
    private val parkedLiveFrames = ConcurrentLinkedQueue<AudioCaptureBus.Frame>()
    val isMuted = AtomicBoolean(false)

    private val busListener = AudioCaptureBus.Listener { frame ->
        synchronized(gateLock) {
            if (!isRecording.get()) return@Listener
            if (handoffGate.get()) {
                // The bus can deliver a live frame while the hand-off pre-roll is
                // still being forwarded. Park it so audio stays in chronological
                // order instead of jumping ahead of the wake tail.
                parkedLiveFrames.offer(frame)
                if (parkedLiveFrames.size > 250) {
                    parkedLiveFrames.poll()
                }
                return@Listener
            }
        }
        deliver(frame)
    }

    fun start(scope: CoroutineScope): Boolean {
        if (isRecording.get()) return true

        isRecording.set(true)
        handoffGate.set(true)

        if (!AudioCaptureBus.register(context, busListener)) {
            Log.e(TAG, "Unable to attach to AudioCaptureBus")
            isRecording.set(false)
            handoffGate.set(false)
            return false
        }

        val preRoll = AudioCaptureBus.drainHandoff()
        if (preRoll.isEmpty()) {
            // A frame can race registration even when there is no pre-roll.
            // Close the gate off the main thread so the caller never blocks.
            scope.launch(Dispatchers.Default) {
                finishHandoff()
            }
            return true
        }

        scope.launch(Dispatchers.Default) {
            try {
                for (frame in preRoll) {
                    if (!isRecording.get()) break
                    deliver(frame)
                }
            } finally {
                // Keep the gate closed until parked live frames are drained;
                // otherwise a newer frame can overtake the wake tail.
                finishHandoff()
            }
        }
        return true
    }

    /**
     * Must be called while holding [gateLock]. Drains every frame that was
     * parked during the pre-roll, then opens the gate. A listener that arrives
     * while this is running blocks on [gateLock]; it will either be parked
     * before the drain or delivered directly after the gate opens, never in
     * the middle of the pre-roll.
     */
    private fun finishHandoff() {
        synchronized(gateLock) {
            while (isRecording.get()) {
                val frame = parkedLiveFrames.poll() ?: break
                deliver(frame)
            }
            handoffGate.set(false)
        }
    }

    private fun deliver(frame: AudioCaptureBus.Frame) {
        if (isMuted.get()) {
            onAudioChunk(ByteArray(0), 0f, AudioFrequencyData())
        } else {
            onAudioChunk(frame.pcm, frame.amplitude, frame.frequency)
        }
    }

    fun stop() {
        if (!isRecording.getAndSet(false)) return
        AudioCaptureBus.unregister(busListener)
        handoffGate.set(false)
        parkedLiveFrames.clear()
    }

    fun setMuted(muted: Boolean) {
        isMuted.set(muted)
    }
}
