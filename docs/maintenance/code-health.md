# Code Health

Snapshot of the codebase after the 1.8.7 upgrade, with concrete refactoring
targets. Metrics are from `git ls-files` and static inspection, not estimates.

## Snapshot

| Metric | Value |
| --- | --- |
| Kotlin/Java files | 97 (96 main + 1 test) |
| Main source LOC | 27,587 |
| Test LOC | 77 |
| Test methods | 4 |
| TODO/FIXME/HACK comments | 0 |
| Gradle modules | 1 |
| Gemini tools | 22 |
| Built-in MCP tools | 24 across 5 servers |
| Android activities | 12 |
| Layouts | 22 |
| `strings.xml` entries | 43 |
| Hard-coded UI sentences (approx.) | 1,546 |
| Largest tracked asset | `sherpa-onnx-1.13.8.aar` (48 MB) |
| Debug APK | ≈230 MB |
| Build time (cold, this host) | ≈4 m 45 s |

## Largest files

| File | LOC | Assessment |
| --- | --- | --- |
| `background/JarvisConversationService.kt` | 3,562 | God class. Owns audio, socket, tools, MCP, TTS, overlay, timers, standby, owner voice, typed chat. |
| `automation/accessibility/JarvisAccessibilityService.kt` | 1,972 | Mixes generic UI primitives with WhatsApp/YouTube-specific flows. |
| `vision/VisualTaskOrchestrator.kt` | 1,523 | Mixes generic click/type/scroll with Spotify and Instagram product flows. |
| `network/GeminiLiveWebSocket.kt` | 1,085 | Transport plus a 600-line tool-schema builder. |
| `automation/phone/PhoneControlManager.kt` | 844 | 15 unrelated device capabilities in one class. |
| `vision/UiGroundingEngine.kt` | 594 | Acceptable, but `findTargetElement` is 263 lines. |
| `automation/agent/AgentTaskEngine.kt` | 585 | Global mutable singleton; state is lost on process death. |
| `util/PromptGenerator.kt` | 569 | One 561-line function returning a giant string. |

## Largest functions

| Function | Lines | Why it hurts |
| --- | --- | --- |
| `JarvisConversationService.beginActiveConversation()` | 913 | Session setup, permission checks, audio wiring, socket creation, prompt generation, TTS init, timer start, notification, and owner-voice reset in one body. |
| `JarvisConversationService.handleToolCallExecution()` | 874 | A 22-branch `when` with per-tool business logic inline. |
| `PromptGenerator.generateSystemPrompt()` | 561 | Prompt text is code; impossible to review or A/B test. |
| `VisualTaskOrchestrator.handleClick()` | 521 | Target resolution, safety gates, coordinate mapping, verification. |
| `CommandParser.parse()` | 470 | 29 command types in one function. |
| `GeminiLiveWebSocket.sendSetupMessage()` | 467 | Tool schemas are 600 lines of nested `JSONObject` builders. |
| `VisualTaskOrchestrator.handleType()` | 272 | Same problem as `handleClick`. |
| `UiGroundingEngine.findTargetElement()` | 263 | Scoring, filtering and surface rules intertwined. |

## Structural issues

### S1 — God service

`JarvisConversationService` has 20+ mutable fields and ~60 methods. It is the
single point of failure for voice, tools, MCP, TTS and UI state. Any change
requires understanding all of it.

**Target decomposition**

| New component | Responsibility |
| --- | --- |
| `LiveSessionController` | Socket lifecycle, setup, renewal, reconnect. |
| `AudioPipeline` | Bus, recorder, player, VAD, owner-voice sampling. |
| `ToolDispatcher` | The `when` block, timeouts, cross-app guard. |
| `McpConfirmationCoordinator` | Pending action, yes/no routing, expiry. |
| `TaskOverlayController` | Overlay show/update/hide. |
| `StandbyController` | Inactivity, wake resume, foreground notification. |

### S2 — Global mutable singletons

`AgentTaskEngine`, `AudioCaptureBus`, `SerializedActionExecutor`,
`VisualTaskOrchestrator.getInstance()`, `ScreenCaptureManager.getInstance()`,
`VoskModelManager`, `OwnerVoiceManager`, `JarvisApp.instance`.

Consequences: no isolation in tests, hidden ordering dependencies, state that
survives a "reset" incorrectly, and difficulty running two tasks in parallel.

**Target:** constructor-injected instances owned by `JarvisApp`, with the
singletons kept only as thin facades during migration.

### S3 — Prompt as source code

`PromptGenerator` embeds persona text, language mandates, safety rules and task
policy as Kotlin string literals. There is no versioning, no token budget, no
way to test which rule caused a behaviour.

**Target:** prompt fragments in `assets/prompts/*.md`, assembled by a builder
with explicit sections and a token estimate; snapshot tests for the assembled
prompt.

### S4 — Tool schema duplication

A tool exists in three places: the JSON schema in `GeminiLiveWebSocket`, the
dispatch branch in `JarvisConversationService`, and the automation-tool set used
for guards. Adding a tool is error-prone.

**Target:** one `ToolSpec` model (name, description, schema, risk, handler,
timeout, automation flag) and a registry that generates the setup payload and
the dispatcher.

### S5 — App-specific flows inside generic engines

WhatsApp/YouTube state machines live in `JarvisAccessibilityService`; Spotify and
Instagram flows live in `VisualTaskOrchestrator`. A UI change to one app forces a
release of the whole engine.

**Target:** `automation/flows/<app>/` modules implementing a `Flow` interface,
registered in a flow registry.

### S6 — Duplicated chat adapters

`ChatAdapter`, `JarvisChatAdapter`, `NairaChatAdapter` overlap. Pick one adapter
with a style parameter.

### S7 — No DI, no modules

Everything is constructed in `JarvisApp.onCreate()`. This is workable but makes
feature isolation and testing hard. Hilt or manual constructor injection would
both work; the important part is removing global access.

## Correctness and robustness risks

| # | Risk | Evidence | Impact |
| --- | --- | --- | --- |
| C1 | `open_website` has no required parameter | tool schema: `urls` and `query` both optional | Model can call it with `{}` and get a confusing failure. |
| C2 | Model IDs may not exist | `gemini-3.1-flash-live-preview`, `gemini-3.8-live`, `gemini-3.1-flash-agent` | Session fails with 404; fallback also fails. Verify against the live API before release. |
| C3 | `Log.w`/`Log.e` survive R8 | `proguard-rules.pro` strips only `d`, `v`, `i` | Task goals, package names and exception text can leak in release logs. |
| C4 | Tamper dialog uses Application context | `SecurityManager.showTamperAlert()` | `BadTokenException` is caught → silent `exitProcess(1)`; user sees a crash, not an explanation. |
| C5 | Dead code | `SecurityManager.getApkSignatureSha256()` unused | Confusing duplicate of `AppIdentityVerifier`. |
| C6 | Legacy project name | `settings.gradle.kts`: `rootProject.name = "JARVIS-Part-7"` | Wrong IDE/artifact naming. |
| C7 | Task state is process-local | `AgentTaskEngine` object | Process death mid-task loses the plan; no resume. |
| C8 | No retry/backoff for MCP HTTP | `McpHttpClient` single attempt | Flaky networks surface as tool failures. |
| C9 | Owner voice threshold migration | values `< 0.60` rewritten to `0.70` on read | A user who deliberately chose 0.5 cannot. |
| C10 | `EncryptedSharedPreferences` alpha | `security-crypto:1.1.0-alpha06` | Alpha API; migration risk on Android 15+. |

## Platform and distribution risks

| # | Risk | Detail |
| --- | --- | --- |
| P1 | `targetSdk 34` | Current Play requirements expect a newer target; also misses Android 15/16 behaviour changes. |
| P2 | Accessibility + `QUERY_ALL_PACKAGES` + overlay | Play policy review is likely to require a declaration and may reject the app. Sideload distribution avoids this but limits reach. |
| P3 | `SYSTEM_ALERT_WINDOW` | Special permission; users may not grant it, so the overlay fallback path must stay solid. |
| P4 | 230 MB APK | Vosk + Sherpa models bundled. Play limit is 200 MB for the base APK; this cannot ship on Play as-is. |
| P5 | Vendored AAR without checksum | Supply-chain integrity gap. |
| P6 | No CI | Nothing prevents a broken commit from being pushed. |

## Quick wins (low risk, high value)

| Effort | Change |
| --- | --- |
| 5 min | Rename `rootProject.name` to `JARVIS-Assistant`. |
| 15 min | Make `urls` required in `open_website` (or accept a single `url`). |
| 30 min | Delete `getApkSignatureSha256`; move the tamper dialog to an Activity or a notification. |
| 1 h | Add `-assumenosideeffects` for `Log.w`/`Log.e` payloads, or wrap logging in a `JarvisLog` that is stripped. |
| 2 h | Add ktlint/detekt with a baseline and a `check` task. |
| 2 h | Add a GitHub Actions workflow: `assembleDebug`, `testDebugUnitTest`, `lintDebug`. |
| 1 day | Extract the 22 tool schemas into a `ToolSpec` registry (S4). |
| 1 day | Split `PromptGenerator` into `assets/prompts` fragments with a builder (S3). |

## Prioritised refactor plan

| Phase | Scope | Outcome |
| --- | --- | --- |
| 0 | Quick wins + CI | Guard rails before large edits. |
| 1 | Tool registry (S4) | One place to add a tool; removes ~500 lines of schema code. |
| 2 | Prompt builder (S3) | Reviewable prompts, token budget, snapshot tests. |
| 3 | Service decomposition (S1) | Testable session/audio/tool/MCP components. |
| 4 | Singleton removal (S2) | Constructor injection; unit tests for engines. |
| 5 | Flow modules (S5) | App automations become pluggable. |
| 6 | Data-at-rest encryption + target SDK 36 | Security and platform compliance. |

See [upgrade roadmap](upgrade-roadmap.md) for sequencing and acceptance
criteria.
