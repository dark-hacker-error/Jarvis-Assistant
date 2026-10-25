package com.jarvis.assistant.wake

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Main-process mirror of Part 1's state. Part 1 broadcasts every transition;
 * the UI and the assist service read this flow instead of polling the
 * `:wake` process.
 */
object WakeStatus {

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    fun set(running: Boolean, listening: Boolean = running) {
        _isRunning.value = running
        _isListening.value = listening
    }

    fun refresh(context: Context) {
        val running = WakeContract.isServiceRunning(context)
        _isRunning.value = running
        // Cross-process listening state is not readable synchronously. When
        // the service is running and no Part 2 session is active it is
        // listening; the next state broadcast corrects this if needed.
        _isListening.value = running
    }
}
