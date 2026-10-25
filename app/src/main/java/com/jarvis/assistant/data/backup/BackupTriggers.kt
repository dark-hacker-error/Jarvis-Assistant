package com.jarvis.assistant.data.backup

/**
 * Lightweight indirection so data-layer classes can request a persistent
 * backup without depending on the Android application container. The
 * coordinator installs the handler during startup; calls before that are
 * harmless and simply ignored.
 */
object BackupTriggers {

    @Volatile
    private var handler: ((String) -> Unit)? = null

    fun install(listener: (String) -> Unit) {
        handler = listener
    }

    fun uninstall() {
        handler = null
    }

    fun request(reason: String) {
        val current = handler ?: return
        try {
            current(reason)
        } catch (_: Exception) {
            // A backup must never break the feature that changed the data.
        }
    }
}
