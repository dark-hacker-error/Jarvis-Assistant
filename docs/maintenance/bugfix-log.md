# Bug-Fix Log — 2026-10-23

Additive fix pass on `main` (base commit `94e5327`). No features were removed.
All changes were verified with `:app:assembleDebug`, `:app:testDebugUnitTest`,
and `:app:lintDebug`.

## Chat and Gemini Live

1. **Typed messages dropped while the service starts**
   `JarvisConversationService.sendTextMessage()` previously returned when no
   service instance existed. Messages are now queued in `pendingTypedText`,
   shown immediately through `ChatRepository.beginTurn()`, and flushed on the
   next `LIVE` transition.
   Files: `background/JarvisConversationService.kt`,
   `data/repository/ChatRepository.kt`.

2. **Assistant replies created duplicate turns**
   Typed messages were stored with a placeholder response, then the real reply
   was added as a new “Spoken user query” turn. `ChatRepository` gained
   `beginTurn()`, `completeLastTurn()`, and `completeLatestPendingTurn()`;
   `finalizeTurn()` now attaches replies to the pending typed bubble and falls
   back to `addTurn()` only when no bubble matches.
   Files: `data/repository/ChatRepository.kt`,
   `background/JarvisConversationService.kt`.

3. **`sendClientContent()` silently discarded text**
   The method now validates readiness, returns `Boolean`, and leaves failed
   messages in the service queue for retry after reconnect.
   File: `network/GeminiLiveWebSocket.kt`.

4. **Every HTTP 400 was reported as an invalid API key**
   Classification now trusts explicit key markers or 401/403. Generic 400
   responses surface the real server reason as `BAD_REQUEST`; model failures are
   detected separately.
   File: `network/GeminiLiveWebSocket.kt`.

5. **No recovery from model-specific failures**
   Model-related HTTP 400/404 responses and error payloads trigger a one-time
   fallback to `models/gemini-3.8-live`; if that also fails, a fatal
   `MODEL_UNAVAILABLE` error is shown.
   File: `network/GeminiLiveWebSocket.kt`.

6. **Reconnect loop had a fixed 500 ms delay**
   Reconnects now back off exponentially from 500 ms to 30 s and reset after a
   successful setup. `stopTimers()` cancels queued reconnect work on explicit
   stop.
   File: `network/GeminiLiveWebSocket.kt`.

## Audio, location, and preferences

7. **Audio effects lost after recorder auto-recovery**
   AEC, NoiseSuppressor, and AGC are released from the old audio session and
   reattached to the recovered `AudioRecord`. Shared helpers keep initial start
   and recovery consistent.
   File: `audio/AudioRecorder.kt`.

8. **Playback state stuck after `AudioPlayer.release()`**
   Release now clears `isPlayingState`, marks the turn complete, and notifies
   playback-stopped/amplitude listeners so the service cannot remain in
   `SPEAKING`.
   File: `audio/AudioPlayer.kt`.

9. **Location timeout leaked a listener**
   `suspendCoroutine` was replaced with `suspendCancellableCoroutine`; the
   location listener is removed on success, error, and timeout.
   File: `util/LocationWeatherHelper.kt`.

10. **Owner voice centroid was diluted by invalid vectors**
    `averageVectors()` divides by the number of accumulated vectors and returns
    `null` when none are valid.
    File: `audio/OwnerVoiceManager.kt`.

11. **API key became inaccessible when encrypted storage failed**
    The getter falls back to legacy plaintext storage, including when an
    already-initialised encrypted store throws on read after Keystore key
    invalidation; the setter writes to plaintext only when secure storage is
    unavailable; migration no longer deletes the only key copy.
    File: `data/preferences/AppPreferences.kt`.

12. **Headset receiver registration crashed on Android 13+**
    Registration uses `ContextCompat.registerReceiver(..., RECEIVER_EXPORTED)`
    for the protected system broadcasts.
    File: `audio/HeadsetAudioRouter.kt`.

## UI, automation, security, and power

13. **Floating task overlay blocked touches**
    The initial window is now 88×88 dp (avatar mode) instead of the expanded
    236 dp pill, and `applyMode()` runs during initialisation.
    File: `ui/agent/TaskProgressOverlay.kt`.

14. **WhatsApp disambiguation could message the wrong contact or change the action**
    An unclear answer now re-asks the question. Name matching and numeric/ordinal
    choices are supported; the first candidate is never chosen automatically.
    The pending action (message, call, or delete) is remembered, so choosing a
    contact for a call/delete no longer creates a message draft. “Cancel” now
    exits the picker instead of being treated as a contact name. A new
    message/call/delete request also clears an older unsent draft, preventing an
    ambiguous new contact from accidentally sending the previous draft.
    Files: `automation/whatsapp/WhatsAppAssistant.kt`,
    `automation/command/CommandParser.kt`.

15. **WhatsApp Business users could not call/delete/launch**
    Calls, message deletion, and launch intents now use the installed package
    (`com.whatsapp` or `com.whatsapp.w4b`).
    File: `automation/whatsapp/WhatsAppAssistant.kt`.

16. **Screen-capture bitmap recycle race and projection leak**
    JPEG compression now happens under `bitmapLock`; an unused second
    `MediaProjection` is stopped instead of left open. A frame decoded while
    `stopCapture()` is running is discarded instead of being re-published after
    sharing ends.
    File: `vision/ScreenCaptureManager.kt`.

17. **Wake lock expired after six hours**
    The wake lock is non-reference-counted and renewed every five hours while
    the service owns it. Renewal is canceled on release.
    File: `background/JarvisConversationService.kt`.

18. **Signature check was a dead placeholder**
    `SecurityManager.checkSignature()` now verifies the installed signer against
    `BuildConfig.PINNED_RELEASE_CERT_SHA256` in release builds and is called by
    `verify()`. Debug builds are exempt.
    File: `security/SecurityManager.kt`.

## Warnings and lint

19. **Always-false/always-true conditions**
    `HomeFragment.renderTaskFallback()` handles the null snapshot explicitly;
    `VisualTaskOrchestrator` uses a safe-call comparison.
    Files: `ui/home/HomeFragment.kt`, `vision/VisualTaskOrchestrator.kt`.

20. **API 27-only theme attributes failed lint**
    `android:windowLightNavigationBar` items were removed from
    `values/themes.xml`. `ThemeManager.applyTheme()` already sets navigation-bar
    appearance at runtime with `WindowInsetsControllerCompat`, so behavior is
    unchanged on every supported API.
    Files: `res/values/themes.xml`, `util/ThemeManager.kt`.

21. **ChromeOS `uses-feature` lint errors**
    Camera and telephony features are declared with `required="false"`.
    File: `AndroidManifest.xml`.

## Verification

| Check | Result |
| --- | --- |
| `./gradlew :app:assembleDebug` | `BUILD SUCCESSFUL` |
| `./gradlew :app:testDebugUnitTest` | 4/4 `AgentTaskEngineTest` tests pass |
| `./gradlew :app:lintDebug` | `BUILD SUCCESSFUL`, 0 errors |
| Debug APK | `app/build/outputs/apk/debug/app-debug.apk` |

Working checklist: [bugfix-todo.md](../bugfix-todo.md).
