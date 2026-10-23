# Jarvis Background Voice Architecture

## Recommendation

Use `gemini-3.1-flash-live-preview` for the active conversation. Google describes it as an audio-to-audio model optimized for low-latency dialogue and voice-first applications. Its default minimal thinking is the latency-oriented configuration. The app now makes it the default and reduces microphone chunks from 40 ms to 20 ms.

The Live API must not be used as a 24-hour wake-word listener. It would transmit ambient speech, cost tokens, and still has connection/session limits. Instead, Jarvis now uses an Android foreground microphone service in standby and opens Gemini only after its wake phrase detector finds `Jarvis`, `Hey Jarvis`, or `Jarvis wake up`.

## Interaction states

| State | Microphone processing | Gemini connection | Result |
|---|---|---|---|
| Active conversation | 16 kHz PCM streamed in 20 ms chunks | Open | Natural live conversation and barge-in |
| Background standby | Android speech recognition checks only wake phrases | Closed | TV/room speech is not sent to Gemini and cannot trigger a reply |
| Wake detected | Service broadcasts a local event | Reopened | Jarvis is ready for the next spoken request |

After two minutes without detected speech, or after the transcribed command “Jarvis, go to background,” Jarvis says: “Sir, I am going in the background. If you need me, call me.” It then enters wake-only standby.

## Android constraints

On Android 14+, continuing microphone capture outside the UI requires a user-started foreground service with `foregroundServiceType="microphone"`, `FOREGROUND_SERVICE_MICROPHONE`, and `RECORD_AUDIO`. The service must be started while the app is visible; this app does that when the user turns Jarvis on. A persistent notification is required and is intentionally present.

The standby service uses a partial wake lock so it can listen while the display is off. This uses noticeable battery power. It can continue for many hours while the service remains alive, but Android cannot guarantee operation after force-stop, OEM battery killing, revoked microphone permission, or a reboot. A production-grade, system-level “always on” assistant requires the user to select the app as the device VoiceInteractionService/default assistant; Android keeps that selected service bound for hotword use.

The platform recognizer is configured to prefer on-device recognition. Devices without a compatible local recognition model may use their configured recognizer; for a commercial product, replace this detector with a vetted on-device keyword engine and test false accepts/rejects across target phones and languages.

## Sources

1. Google AI for Developers, [Gemini 3.1 Flash Live Preview](https://ai.google.dev/gemini-api/docs/models/gemini-3.1-flash-live-preview) — current model purpose, migration notes, and model identifier.
2. Google AI for Developers, [Live API capabilities](https://ai.google.dev/gemini-api/docs/live-api/capabilities) — native audio, transcription, and thinking-level behavior.
3. Google AI for Developers, [Live API session management](https://ai.google.dev/gemini-api/docs/live-api/session-management) — connection limits, compression, and resumption.
4. Android Developers, [Foreground service types](https://developer.android.com/about/versions/14/changes/fgs-types-required) — microphone foreground-service declaration and permission requirements.
5. Android Developers, [Background foreground-service restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start) — while-in-use microphone constraints.
6. Android Developers, [VoiceInteractionService](https://developer.android.com/reference/android/service/voice/VoiceInteractionService) — default-assistant system binding and hotword lifecycle.
