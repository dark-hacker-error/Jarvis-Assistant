# Module and Package Map

The project is one Gradle module, `:app`. Kotlin sources live under
`app/src/main/java/com/jarvis/assistant`.

| Package | Key files | Notes |
| --- | --- | --- |
| root | `JarvisApp.kt` | Application container. Builds shared singletons: `AppPreferences`, `ChatRepository`, `MemoryManager`, `YouTubeAutomationManager`, `WhatsAppAssistant`, `PhoneControlManager`, `CommandExecutor`, contact resolver. Registers activity theme callbacks. |
| `audio` | `AudioRecorder`, `AudioPlayer`, `AudioUtils`, `HeadsetAudioRouter`, `OwnerVoiceManager`, `VoskModelManager`, `JarvisSoundEffects` | Capture/playback, AEC/NS/AGC, amplitude/frequency analysis, headset routing, owner voice embedding, Vosk model install, UI sounds. |
| `automation.accessibility` | `JarvisAccessibilityService` | Declared with `BIND_ACCESSIBILITY_SERVICE`; exposes screen tree, gestures, text entry, app launch, and WhatsApp UI helpers. |
| `automation.agent` | `AgentTaskEngine`, `AgentModels`, `ActionVerifier`, `ScreenSignatureCalculator`, `SerializedActionExecutor` | Multi-step task state machine, step evidence, screen stability/signature checks, serialized UI actions. |
| `automation.command` | `CommandExecutor`, `CommandParser`, `JarvisCommand` | Deterministic command fallback and dispatch. |
| `automation.contacts` | `ContactResolver`, `ContactModel` | Contact lookup and ambiguity results. |
| `automation.phone` | `PhoneControlManager`, `DomainNormalizer` | Calls, SMS, alarms, apps, URLs/web search, device settings. |
| `automation.whatsapp` | `WhatsAppAssistant` | Contact disambiguation, draft/send, WhatsApp calls, message deletion, Business package support. |
| `automation.youtube` | `YouTubeAutomationManager`, `YouTubeController`, `YouTubeApiClient` | Search/playback automation and optional API access. |
| `background` | `JarvisConversationService` | Foreground service (`microphone|mediaPlayback|mediaProjection`), session lifecycle, audio/WebSocket wiring, typed-chat queue, wake lock, notification, overlay ownership. |
| `data.memory` | `MemoryDatabase`, `MemoryDao`, `MemoryEntity`, `MemoryRepository`, `MemoryManager` | Room database and long/short-term memory composition. |
| `data.model` | `ChatTurn`, `ConversationState`, `GeminiLiveModels` | Chat turn, UI conversation states, Gemini model IDs and endpoint constants. |
| `data.preferences` | `AppPreferences`, `YouTubePreferences` | Settings, encrypted API key, chat history, theme/persona/model/voice prefs. |
| `data.repository` | `ChatRepository` | `StateFlow<List<ChatTurn>>`; add, begin, complete, clear history. |
| `mcp` | `McpManager`, `McpModels`, `McpPreferences`, `BuiltInMcpRegistry`, `BuiltInMcpExecutor`, `McpHttpClient` | Built-in/custom MCP catalogue, risk/confirmation gate, encrypted custom-server storage, HTTP JSON-RPC client, enabled-tool prompt context. |
| `system` | `ShizukuManager`, `JarvisShellService`, `IJarvisShellService` (AIDL) | Shizuku permission/binding, privileged UserService shell, status and timeouts. |
| `network` | `GeminiLiveWebSocket` | Live API transport, setup/tool schemas, audio/video send, reconnect and model fallback. |
| `security` | `SecurityManager`, `AppIdentityVerifier`, `DeveloperInfo` | Package-name check, release signature pin, release identity constants. |
| `ui.home` | `MainActivity`, `HomeFragment`, `MainViewModel`, `UpdatesFragment` | Navigation dock, orb/mic control, status cards, permissions, update screen. |
| `ui.chat` | `ChatFragment`, `JarvisChatAdapter`, `ChatAdapter`, `NairaChatAdapter`, `ChatHistoryBottomSheet` | Typed chat and history UI. |
| `ui.settings` | `SettingsActivity`, `SettingsFragment`, `ApiConnectionActivity`, `PersonaActivity`, `OverlayAvatarActivity`, `PrivacyShieldActivity`, `HandsFreeActivity`, `ThemeSelectionBottomSheet`, `McpSettingsActivity`, `ShizukuSettingsActivity` | Configuration screens, including MCP toggles/custom servers and Shizuku permission/binding. |
| `ui.agent` | `TaskProgressOverlay` | Floating task HUD (avatar/pill modes, draggable, click-through handling). |
| `ui.memory` | `MemoryActivity`, `MemoryAdapter` | Memory inspection/editing. |
| `ui.permission` | `PermissionActivity` | Runtime permission onboarding. |
| `ui.voice` | `VoiceEnrollmentActivity` | Owner voice enrollment. |
| `ui.orb` | `OrbHelper` | Orb animation/state helpers. |
| `ui.widget` | `CurvedDockLineView` | Custom navigation decoration. |
| `util` | `PermissionHelper`, `ThemeManager`, `LocationWeatherHelper`, `PromptGenerator`, `ActivityLauncherHelper`, `AppLauncher`, `ContactCaller`, `WhatsAppMessenger`, `BuiltInChromeEngine` | Cross-cutting helpers; `ThemeManager` applies theme colors and system-bar appearance at runtime. |
| `vision` | `ScreenCaptureManager`, `VisualTaskOrchestrator`, `UiGroundingEngine` | MediaProjection capture, JPEG encoding, UI element grounding and visual wait loops. |

## Manifest components

- `JarvisApp` (application)
- `MainActivity` (launcher)
- `SettingsActivity`, `MemoryActivity`, `VoiceEnrollmentActivity`,
  `ApiConnectionActivity`, `PersonaActivity`, `PermissionActivity`,
  `OverlayAvatarActivity`, `PrivacyShieldActivity`, `HandsFreeActivity`,
  `McpSettingsActivity`, `ShizukuSettingsActivity`
- `JarvisConversationService` (foreground service, not exported)
- `JarvisAccessibilityService` (accessibility service, guarded by
  `BIND_ACCESSIBILITY_SERVICE`)
- `ShizukuProvider` (`rikka.shizuku.ShizukuProvider`, authority
  `${applicationId}.shizuku`, guarded by Shizuku's API permission)
- `JarvisShellService` (Shizuku UserService, loaded in Shizuku's process; not an
  exported Android service)

## Resource layout

- `res/layout` — ViewBinding layouts for activities, fragments, chat items, overlays.
- `res/values` — strings, colors, themes, dimensions.
- `res/drawable`, `res/mipmap-*` — icons, orb art, launcher assets.
- `assets` — Vosk model metadata and bundled web/UI assets.

## Build configuration

`app/build.gradle.kts` defines compile/target SDK 34, min SDK 26, Java/Kotlin 17,
ViewBinding, BuildConfig, kapt, AIDL, R8 for release, and release signing
properties. Build Tools **36.0.0** is pinned because the installed 34.0.0
package contains x86-64 host binaries while this build host is aarch64.
Shizuku dependencies are `dev.rikka.shizuku:api:13.1.5` and
`dev.rikka.shizuku:provider:13.1.5`. Version: `1.8.7` (`versionCode 143`).
