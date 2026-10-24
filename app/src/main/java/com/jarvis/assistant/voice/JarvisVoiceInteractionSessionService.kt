package com.jarvis.assistant.voice

import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

/**
 * Creates the transparent orb session when the system asks the active voice
 * interaction service to show itself.
 *
 * The service is intentionally silent: no foreground service, no notification
 * channel and no startForeground() call exist in this component or its session.
 */
class JarvisVoiceInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        return JarvisVoiceInteractionSession(this)
    }
}
