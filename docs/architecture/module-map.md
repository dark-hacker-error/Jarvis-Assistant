# Module and Package Map

The project has exactly one Gradle module, `:app`. All Kotlin sources live under
`app/src/main/java/com/jarvis/assistant`. There is one AIDL file under
`app/src/main/aidl`.

```
app/src/main/java/com/jarvis/assistant/
├── JarvisApp.kt                     Application container
├── JarvisVoiceInteractionService.kt Android VoiceInteractionService entry point
├── audio/                           Microphone bus, recorder, player, voice biometrics
├── automation/
│   ├── accessibility/               AccessibilityService + app-specific UI flows
│   ├── agent/                       Task state machine, verification, serialized actions
│   ├── command/                     Deterministic command parser/executor
│   ├── contacts/                    Contact lookup and ambiguity model
│   ├── phone/                       Calls, SMS, volume, brightness, torch, app launch
│   ├── whatsapp/                    WhatsApp messaging and calling
│   └── youtube/                     YouTube search/playback automation
├── background/                      Foreground conversation service
├── data/
│   ├── memory/                      Room memory database
│   ├── model/                       Chat turn, conversation state, Gemini constants
│   ├── preferences/                 SharedPreferences + encrypted API key
│   └── repository/                  Chat history repository
├── mcp/                             Built-in and custom MCP tool platform
├── network/                         Gemini Live WebSocket transport
├── security/                        Package/signature checks, developer identity
├── system/                          Shizuku manager + privileged shell UserService
├── ui/                              Activities, fragments, adapters, overlay
├── util/                            Cross-cutting helpers and prompt builder
├── vision/                          MediaProjection capture, UI grounding, UI actions
└── voice/                           Wake word, orb, VoiceInteraction session
```

## Package reference

### Root

| File | Purpose |
| --- | --- |
| `JarvisApp.kt` | `Application`. Creates `AppPreferences`, `ChatRepository`, `MemoryDatabase/Repository/Manager`, `YouTubeAutomationManager`, `ContactResolver`, `PhoneControlManager`, `WhatsAppAssistant`, `ShizukuManager`, `McpManager`, `CommandExecutor`. Initialises `VoskModelManager`, registers activity lifecycle callbacks for theming and foreground tracking. Exposes `moveAppToBack()` and `isAppForeground`. |
| `JarvisVoiceInteractionService.kt` | System `VoiceInteractionService`. Owns the wake engine and floating orb, starts the conversation service on wake, and exposes device/assistant metadata. |

### `audio`

| File | Purpose |
| --- | --- |
| `AudioCaptureBus.kt` | Singleton microphone owner. 16 kHz mono PCM16, 20 ms frames, `VOICE_COMMUNICATION` + AEC/NS/AGC. Listener registration, 2 s rolling buffer, 3.5 s wake hand-off lease. |
| `AudioRecorder.kt` | Conversation-side adapter over the bus. Emits 640-byte chunks, amplitude and frequency data, mute support. |
| `AudioPlayer.kt` | `AudioTrack` queue for 24 kHz PCM from Gemini, amplitude callback, flush/interrupt, turn-complete callback. |
| `AudioUtils.kt` | PCM helpers and amplitude/frequency analysis. |
| `HeadsetAudioRouter.kt` | Bluetooth/wired headset routing, SCO handling. |
| `OwnerVoiceManager.kt` | Speaker embedding extraction and verification against the enrolled owner profile (Vosk speaker model assets). |
| `VoskModelManager.kt` | Installs/validates the bundled Vosk models used for speaker biometrics. |
| `JarvisSoundEffects.kt` | Short UI sounds (wake, error, confirmation). |

### `automation.accessibility`

| File | Purpose |
| --- | --- |
| `JarvisAccessibilityService.kt` | The UI automation surface: node tree traversal, element lookup by text/description/bounds, click/long-press/swipe/scroll, text injection, back/home/recents, screenshots, OTP extraction, and arm/poll state machines for WhatsApp send/call/delete and YouTube play/subscribe/fullscreen. Also hosts `UiChange` revision tracking. |

### `automation.agent`

| File | Purpose |
| --- | --- |
| `AgentTaskEngine.kt` | Authoritative task state (`IDLE`…`CANCELLED`), plan generation, step evidence, observation, pause/resume, cancellation, and `executeUiMutation()` — the single gate every UI mutation must pass. |
| `AgentModels.kt` | `TaskPlanStep`, `TaskStepStatus`, `TaskGoal`, `AgentTaskSnapshot`, bulk-action classification. |
| `ActionVerifier.kt` | Post-action checks: expected screen/package/text present, element gone, etc. |
| `ScreenSignatureCalculator.kt` | Stable signature of the visible UI used to detect change/no-progress. |
| `SerializedActionExecutor.kt` | Serialises UI actions across coroutines, invalidates stale actions after a new task starts. |

### `automation.command`

| File | Purpose |
| --- | --- |
| `CommandParser.kt` | Keyword/regex parser for deterministic commands in English/Hinglish (play, open, message, call, volume, brightness, torch, remember, forget, confirm/deny, etc.). |
| `CommandExecutor.kt` | Executes a `JarvisCommand` through the phone/YouTube/WhatsApp/memory managers. |
| `JarvisCommand.kt` | Sealed command model (29 commands). |

### `automation.contacts` / `automation.phone`

| File | Purpose |
| --- | --- |
| `ContactResolver.kt`, `ContactModel.kt` | Contact lookup with ambiguity results (exact, partial, multiple matches). |
| `PhoneControlManager.kt` | Calls, SMS, alarms, media volume, brightness, torch, app launch, URL opening, web search, settings panels. |
| `DomainNormalizer.kt` | Normalises spoken domains/URLs (`github dot com` → `github.com`) and detects web-platform targets. |

### `automation.whatsapp` / `automation.youtube`

| File | Purpose |
| --- | --- |
| `WhatsAppAssistant.kt` | Resolves contacts, opens the correct chat, types and sends, places voice/video calls, deletes messages, handles dual-app (Business) packages. |
| `YouTubeAutomationManager.kt` | High-level YouTube intents (search, play, subscribe, fullscreen) using accessibility and/or API. |
| `YouTubeController.kt` | UI-level controls (skip ad, like, subscribe, fullscreen, first result). |
| `YouTubeApiClient.kt` | Optional Data API v3 client for search when a key is configured. |

### `background`

| File | Purpose |
| --- | --- |
| `JarvisConversationService.kt` | Foreground service (`microphone|mediaPlayback|mediaProjection`). Session lifecycle, wake lock + Wi-Fi lock, notification, audio wiring, tool dispatch, MCP confirmation, TTS, inactivity timer, standby, task overlay, typed-message queue. Companion exposes all UI state and control functions. |

### `data`

| File | Purpose |
| --- | --- |
| `memory/MemoryDatabase.kt` | Room database (`jarvis_memory.db`). |
| `memory/MemoryEntity.kt` | Fact entity with category, key, value, timestamps. |
| `memory/MemoryDao.kt` | Queries: all, by category, keyword search, delete by keyword. |
| `memory/MemoryRepository.kt` | DAO wrapper. |
| `memory/MemoryManager.kt` | Long-term facts, 30-turn short-term window, preference extraction, remember/forget/recall responses. |
| `model/ChatTurn.kt` | `userTranscript`, `jarvisResponse`, timestamp. |
| `model/ConversationState.kt` | `IDLE`, `LISTENING`, `THINKING`, `SPEAKING`, `ERROR`. |
| `model/GeminiLiveModels.kt` | Model IDs, voices, languages, personalities, WebSocket base URL, Gson payload models. |
| `preferences/AppPreferences.kt` | All settings; encrypted API key; chat history JSON; voice profile; theme/orb config. |
| `preferences/YouTubePreferences.kt` | YouTube API key and defaults. |
| `repository/ChatRepository.kt` | In-memory `StateFlow<List<ChatTurn>>` backed by prefs, capped at 100 turns. |

### `mcp`

| File | Purpose |
| --- | --- |
| `McpModels.kt` | `McpServer`, `McpTool`, `McpParameter`, `McpRisk`, `McpCustomServer`, `McpPendingAction`, `McpExecutionResult`. |
| `BuiltInMcpRegistry.kt` | Declarative catalogue: 5 servers, 24 tools. |
| `BuiltInMcpExecutor.kt` | In-process/Shizuku execution with argument validation and shell guards. |
| `McpManager.kt` | Enablement, risk gate, confirmation, prompt context, custom-server CRUD, tool resolution. |
| `McpPreferences.kt` | Encrypted storage for custom server URLs/tokens; cached tool lists. |
| `McpHttpClient.kt` | Streamable-HTTP JSON-RPC: `initialize`, `notifications/initialized`, `tools/list`, `tools/call`; SSE parsing; redirects disabled. |

### `network`

| File | Purpose |
| --- | --- |
| `GeminiLiveWebSocket.kt` | Connection, setup payload (system instruction, generation config, 22 function declarations), audio/video send, server event parsing, tool responses, transcripts, interruption, renewal, reconnect, model fallback. |

### `security`

| File | Purpose |
| --- | --- |
| `SecurityManager.kt` | Package-name check and release certificate pin; tamper dialog. |
| `AppIdentityVerifier.kt` | Reads current signer SHA-256. |
| `DeveloperInfo.kt` | Publisher identity and links shown in the UI. |

### `system`

| File | Purpose |
| --- | --- |
| `ShizukuManager.kt` | Shizuku availability, permission request, UserService bind/unbind, `runCommand` with timeout and output caps. |
| `JarvisShellService.kt` | The UserService implementation; executes shell commands in Shizuku's privileged process. |
| `aidl/IJarvisShellService.aidl` | `runCommand(command, timeoutMs)` plus reserved `destroy`. |

### `ui`

| Package | Files | Purpose |
| --- | --- | --- |
| `ui.home` | `MainActivity`, `HomeFragment`, `MainViewModel` | Launcher, orb, status cards, dock navigation, permission prompts, session control. |
| `ui.chat` | `ChatFragment`, `JarvisChatAdapter`, `NairaChatAdapter`, `ChatAdapter`, `ChatHistoryBottomSheet` | Typed chat, streaming captions, history. |
| `ui.settings` | `SettingsActivity`, `SettingsFragment`, `ApiConnectionActivity`, `PersonaActivity`, `OverlayAvatarActivity`, `PrivacyShieldActivity`, `HandsFreeActivity`, `McpSettingsActivity`, `ShizukuSettingsActivity`, `ThemeSelectionBottomSheet` | All configuration screens. |
| `ui.agent` | `TaskProgressOverlay` | Floating task HUD (avatar/pill), draggable, click-through. |
| `ui.memory` | `MemoryActivity`, `MemoryAdapter` | Inspect/delete stored facts. |
| `ui.voice` | `VoiceEnrollmentActivity` | Owner voice enrollment. |
| `ui.permission` | `PermissionActivity` | Runtime permission onboarding. |
| `ui.orb` | `OrbHelper` | Orb animation/state helper. |

### `util`

| File | Purpose |
| --- | --- |
| `PromptGenerator.kt` | Builds the full system instruction from persona, language, memory, device state and MCP context. |
| `PermissionHelper.kt` | Permission checks and request lists. |
| `ThemeManager.kt` | Applies theme colors and system-bar appearance. |
| `LocationWeatherHelper.kt` | Location + Open-Meteo weather for the home card. |
| `AppLauncher.kt` | Resolves app names to packages, handles dual/cloned apps, launches. |
| `ActivityLauncherHelper.kt` | Intent-based launch helpers with fallbacks. |
| `ContactCaller.kt` | Contact → phone call intent. |
| `WhatsAppMessenger.kt` | `wa.me` / `whatsapp://` intent fallback. |
| `BuiltInChromeEngine.kt` | Headless web fetch/search used by `builtin_chrome_search`. |
| `SocialLinks.kt` | Developer/social links shown in settings. |

### `vision`

| File | Purpose |
| --- | --- |
| `ScreenCaptureManager.kt` | MediaProjection + VirtualDisplay + ImageReader; downscales to ≤768 px, encodes JPEG, throttles to ~1 fps, keeps the latest bitmap for verification snapshots. |
| `UiGroundingEngine.kt` | Scans the accessibility tree into `UiElementInfo` with stable IDs, bounds, roles, and normalised coordinates; detects surfaces (browser, Google Flow). |
| `VisualTaskOrchestrator.kt` | Action engine: `handleClick`, `handleType`, `handleScroll`, `handleKey`, `inspectScreen`, `waitForUiState`, plus specialised Spotify search and Instagram bulk-unlike flows. |

### `voice`

| File | Purpose |
| --- | --- |
| `SherpaOnnxWakeWordEngine.kt` | Streaming KWS, 10 ms slices, cooldown, wake hand-off. |
| `JarvisVoiceInteractionSession.kt` | `VoiceInteractionSession` implementation: shows the orb, routes the command, dismisses. |
| `JarvisVoiceInteractionSessionService.kt` | Creates the session. |
| `JarvisFloatingOrbController.kt`, `JarvisOrbTouchOverlay.kt`, `JarvisOrbView.kt` | Floating orb window, touch handling, animation. |
| `JarvisAgentBridge.kt` | Bridge between the voice-interaction session and the conversation service. |
| `KernelSuRuntimeHelper.kt` | Optional KernelSU/root detection used only to report capability, never to escalate silently. |

## Manifest components

| Component | Exported | Guard |
| --- | --- | --- |
| `JarvisApp` | — | Application class |
| `MainActivity` | yes | Launcher intent filter |
| `SettingsActivity`, `MemoryActivity`, `VoiceEnrollmentActivity`, `ApiConnectionActivity`, `PersonaActivity`, `PermissionActivity`, `OverlayAvatarActivity`, `PrivacyShieldActivity`, `HandsFreeActivity`, `McpSettingsActivity`, `ShizukuSettingsActivity` | no | Internal navigation only |
| `JarvisConversationService` | no | Foreground service, `microphone|mediaPlayback|mediaProjection` |
| `JarvisAccessibilityService` | yes | `BIND_ACCESSIBILITY_SERVICE` (system-only bind) |
| `JarvisVoiceInteractionService` | yes | `BIND_VOICE_INTERACTION` |
| `JarvisVoiceInteractionSessionService` | yes | `BIND_VOICE_INTERACTION` |
| `ShizukuProvider` | yes | Shizuku API permission + `INTERACT_ACROSS_USERS_FULL` |
| `JarvisShellService` | — | Shizuku UserService, not a normal Android service |

## Resources and assets

| Path | Contents |
| --- | --- |
| `res/layout` | 22 layouts: activities, fragments, chat items, MCP server row, memory row, overlays, theme sheet. |
| `res/values` | `strings.xml` (43 strings), colors, themes, dimens. |
| `res/xml` | `accessibility_service_config.xml`, `voice_interaction_service.xml`, `network_security_config.xml`. |
| `assets/model-en-us` | Vosk English acoustic model (speaker biometrics / legacy recognition). |
| `assets/model-spk` | Vosk speaker model (`final.ext.raw`, `mean.vec`, `transform.mat`). |
| `assets/models/sherpa-kws` | Sherpa-ONNX int8 zipformer encoder/decoder/joiner, tokens, keywords. |
| `assets/*.html` | Orb, HUD, ghost avatar and overlay toys rendered in WebView. |
| `assets/three.min.js` | Three.js for the WebGL orb. |

## Build configuration

- One module, `:app`; `rootProject.name = "JARVIS-Part-7"` (legacy name, see
  [code health](../maintenance/code-health.md)).
- `compileSdk = 34`, `targetSdk = 34`, `minSdk = 26`.
- `buildToolsVersion = "36.0.0"` pinned because the 34.0.0 package on this
  aarch64 host ships x86-64 host binaries.
- Java/Kotlin target 17; ViewBinding, BuildConfig, AIDL enabled.
- Release: R8 + resource shrinking, custom signing config, `verifyReleaseSecurityInputs`
  task that fails `packageRelease` without keystore and certificate pin.
- Dependencies: AndroidX (core, appcompat, material, constraintlayout, lifecycle,
  activity, recyclerview, webkit, room, security-crypto), OkHttp, Gson,
  kotlinx-coroutines, JNA, Vosk, vendored Sherpa-ONNX AAR, Shizuku API/provider.
- Version `1.8.7` (`versionCode 143`).

See [build and setup](../setup/build.md) for commands and signing.
