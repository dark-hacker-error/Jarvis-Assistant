# Jarvis Bug-Fix TODO (working checklist)

Status legend: `[ ]` pending, `[~]` in progress, `[x]` done, `[!]` verified via build

## Batch 1 — Chat & Gemini Live
- [x] 1. ChatFragment service-start race: typed message is queued, not dropped
- [x] 2. ChatRepository: additive `beginTurn` / `completeLastTurn` so the reply attaches to the typed turn
- [x] 3. Service `sendTextMessage`: queues until LIVE; no blank/duplicate turns
- [x] 4. GeminiLiveWebSocket `sendClientContent`: returns success; service retries after reconnect
- [x] 5. Gemini error classification: HTTP 400 is no longer reported as an invalid key
- [x] 6. Model fallback on model-related HTTP 400/404 errors
- [x] 7. Reconnect: exponential backoff (0.5s → 30s); canceled by `stopTimers()`

## Batch 2 — Audio, location, preferences
- [x] 8. AudioRecorder: AEC / NoiseSuppressor / AGC reattached after auto-recovery
- [x] 9. AudioPlayer.release: resets playback state and notifies listeners
- [x] 10. LocationWeatherHelper: cancellable timeout + listener cleanup
- [x] 11. OwnerVoiceManager: divides by valid vector count only
- [x] 12. AppPreferences: plaintext fallback when encrypted storage is unavailable/fails on read; key is never deleted
- [x] 13. HeadsetAudioRouter: explicit receiver export flag on Android 13+

## Batch 3 — UI, automation, security, power
- [x] 14. TaskProgressOverlay: 88dp initial window + `applyMode()` at init
- [x] 15. WhatsAppAssistant: disambiguation never guesses; remembers whether the request was message/call/delete and supports cancel
- [x] 16. WhatsAppAssistant: Business package used for calls/delete/launch
- [x] 17. ScreenCaptureManager: bitmap recycle race fixed; unused projection stopped; late frames discarded after stop
- [x] 18. JarvisConversationService: wake lock renewed before the 6-hour timeout
- [x] 19. SecurityManager: signature pin now uses the configured release fingerprint

## Batch 4 — Warnings & lint
- [x] 20. HomeFragment always-false condition removed
- [x] 21. VisualTaskOrchestrator always-true null check replaced with safe call
- [x] 22. themes.xml API 27-only attributes removed (ThemeManager already applies them at runtime)
- [x] 23. Manifest `uses-feature required="false"` for camera/telephony
- [x] 24. Full `:app:assembleDebug` + unit tests + lint pass (4/4 tests; 0 lint errors)
- [x] 25. Final docs (architecture, modules, setup, permissions, security, bug/fix log)
