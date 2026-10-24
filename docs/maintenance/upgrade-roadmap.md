# Upgrade Roadmap

This roadmap turns the findings in [Code Health](code-health.md) into an ordered,
reviewable programme. It is deliberately sequenced: each phase makes the next
one cheaper, and no phase requires a big-bang rewrite.

**Rules for every phase**

- The app must build, pass `testDebugUnitTest` and pass `lintDebug` before merge.
- Behaviour changes are separate commits from refactors.
- A refactor must not change the model-facing tool names, arguments or result
  fields; those are a public contract with the prompt.
- Every phase updates the matching document in the same change.

## Phase 0 — Guard rails (1–2 days)

Nothing structural. This phase exists so later refactors are safe.

| Task | Detail | Acceptance |
| --- | --- | --- |
| Rename the project | `settings.gradle.kts`: `JARVIS-Part-7` → `JARVIS-Assistant` | `./gradlew projects` shows the new name; no path changes. |
| Fix `open_website` schema | Make `urls` required, or replace `urls`/`query` with one required `url` | Empty `{}` call is rejected by the model schema, not by a runtime error. |
| Remove dead code | Delete `SecurityManager.getApkSignatureSha256()` | `grep` returns only `AppIdentityVerifier`. |
| Fix the tamper dialog | Show it from an Activity or a notification; never from the Application context | A simulated signature mismatch displays a dialog instead of `exitProcess` with no UI. |
| CI | GitHub Actions: JDK 21, `assembleDebug`, `testDebugUnitTest`, `lintDebug`; cache Gradle | A pull request cannot merge red. |
| Static analysis | ktlint or detekt with a baseline; do not reformat the world | `check` passes on the baseline. |
| Release log hygiene | Strip or redact `Log.w`/`Log.e` payloads, or route through a `JarvisLog` facade removed by R8 | `strings` on a release APK finds no task goals, package names or exception text. |
| AAR integrity | Record SHA-256 of `sherpa-onnx-1.13.8.aar`; verify it in a Gradle task | A modified AAR fails the build. |

**Why first:** CI and lint catch the mistakes that a 3,500-line service
refactor will otherwise hide.

## Phase 1 — Tool registry (3–5 days)

**Problem:** a tool exists in three places — the JSON schema in
`GeminiLiveWebSocket.sendSetupMessage()`, the `when` branch in
`JarvisConversationService.handleToolCallExecution()`, and the hard-coded
`isAutomationTool` set. Adding one tool means editing three files and remembering
all the guards.

**Design**

```kotlin
data class ToolSpec(
    val name: String,
    val description: String,
    val parameters: JsonSchema,
    val required: List<String>,
    val kind: ToolKind,          // DEVICE, AUTOMATION, MCP, TASK
    val timeoutMs: Long,
    val handler: suspend (ToolCall) -> ToolResult
)
```

- `ToolRegistry` owns `List<ToolSpec>` and produces the Live `setup` payload.
- `ToolDispatcher` resolves `name` to a `ToolSpec`, applies the timeout, the
  cross-app guard and the hard stop, then calls the handler.
- `isAutomationTool` becomes `spec.kind == AUTOMATION`.
- `ToolResult` is a typed object that serialises to the existing JSON fields
  (`success`, `verified`, `message`, `current_screen_elements`, …).

**Migration**

1. Add the registry and `ToolResult` with no behaviour change.
2. Move one low-risk tool (`set_flashlight`) and diff the setup JSON byte for
   byte.
3. Move the rest in groups (device, communication, automation, MCP).
4. Delete the old `when` branches and the hard-coded set.
5. Add a test that every `ToolSpec` has a unique name, a handler, and a schema
   whose required fields exist in `properties`.

**Acceptance**

- `sendSetupMessage()` contains no tool literals.
- Adding a fake tool in a test registers it in the payload and dispatches it.
- Tool names, descriptions and schemas are unchanged for the 22 existing tools.

## Phase 2 — Prompt builder (2–4 days)

**Problem:** `PromptGenerator.generateSystemPrompt()` is a 561-line function of
string concatenation. No section can be reviewed, translated, budgeted or
tested independently.

**Design**

- Move fragments to `app/src/main/assets/prompts/`:
  `persona.md`, `language.md`, `safety.md`, `automation.md`, `mcp.md`,
  `memory.md`, `device-state.md`.
- A `PromptBuilder` assembles sections in a fixed order, substitutes typed
  variables (`{{user_name}}`, `{{date}}`, `{{mcp_context}}`), and returns
  `Prompt(text, estimatedTokens, sections)`.
- Keep the current output as a golden file and diff before/after the migration.
- Add a token estimate (characters / 4 is enough for a guard) and log a warning
  when the prompt exceeds a configurable budget.

**Acceptance**

- The assembled prompt for a fixed preference set is identical to today's
  output (golden test), except for whitespace.
- Each section can be disabled in a debug screen and the prompt shrinks
  accordingly.
- No prompt text remains in `PromptGenerator.kt`.

## Phase 3 — Service decomposition (1–2 weeks)

**Problem:** `JarvisConversationService` is 3,562 lines with a 913-line
`beginActiveConversation()` and an 874-line `handleToolCallExecution()`. It is
the single point of failure for voice, tools, MCP, TTS, overlay and timers.

**Target components** (all constructor-injected, owned by the service)

| Component | Responsibility | Extracted from |
| --- | --- | --- |
| `LiveSessionController` | Connect, setup, renewal, reconnect, model fallback, typed-message flush | socket lifecycle + timers |
| `AudioPipeline` | Bus attach/detach, recorder, player, VAD, owner-voice sampling | audio fields and callbacks |
| `ToolDispatcher` | Registry lookup, timeout, cross-app guard, hard stop, result mapping | `handleToolCallExecution()` |
| `McpConfirmationCoordinator` | Pending action, yes/no routing, expiry, re-validation | MCP branches + `pendingMcpAction` |
| `TaskOverlayController` | Show/update/hide, avatar mode, position | overlay calls |
| `StandbyController` | Inactivity timer, standby enter/exit, wake resume | standby fields |

**Order of extraction** (each is a separate PR)

1. `McpConfirmationCoordinator` — smallest state, clear boundary.
2. `TaskOverlayController` — UI-only, easy to verify.
3. `StandbyController` — timer logic, testable with a fake clock.
4. `AudioPipeline` — highest risk; do it with the device on the desk.
5. `ToolDispatcher` — depends on Phase 1.
6. `LiveSessionController` — last, because everything else touches it.

**Acceptance**

- `JarvisConversationService` is under 1,000 lines and contains no tool-specific
  business logic.
- Each component has a unit test for its state transitions.
- A voice turn, a tool call, a confirmation and a standby cycle are verified on
  a device after each extraction.

## Phase 4 — Remove global singletons (1 week)

**Problem:** `AgentTaskEngine`, `AudioCaptureBus`, `SerializedActionExecutor`,
`VisualTaskOrchestrator.getInstance()`, `ScreenCaptureManager.getInstance()`,
`VoskModelManager`, `OwnerVoiceManager` and `JarvisApp.instance` are global
mutable state.

**Design**

- Construct instances in `JarvisApp` and pass them down through the service and
  the accessibility service.
- Keep `object` facades only while call sites migrate; mark them
  `@Deprecated("Inject X instead")`.
- Give `AgentTaskEngine` an instance API and a `TaskStore` interface so tests can
  create two independent engines.
- Replace `JarvisApp.instance` with an explicit `AppContainer` parameter.

**Acceptance**

- No production code reads `JarvisApp.instance`.
- `AgentTaskEngineTest` creates two engines that do not share state.
- The accessibility service receives its dependencies from the app container.

## Phase 5 — Flow modules (1–2 weeks)

**Problem:** WhatsApp and YouTube state machines live in
`JarvisAccessibilityService`; Spotify and Instagram flows live in
`VisualTaskOrchestrator`. A label change in one app forces a release of the
generic engine.

**Design**

```kotlin
interface AutomationFlow {
    val id: String
    fun matches(goal: TaskGoal): Boolean
    suspend fun execute(ctx: FlowContext): FlowResult
}
```

- `automation/flows/whatsapp`, `.../youtube`, `.../spotify`, `.../instagram`.
- `FlowRegistry` selects a flow; the generic `VisualTaskOrchestrator` remains the
  fallback.
- Each flow declares the packages it may touch, so the cross-app guard becomes
  data rather than a hard-coded check.

**Acceptance**

- `JarvisAccessibilityService` contains only generic UI primitives.
- A flow can be disabled in settings without affecting the others.
- A failed flow returns a typed failure; it never reports unverified success.

## Phase 6 — Data protection and platform (1 week)

| Task | Detail | Acceptance |
| --- | --- | --- |
| Encrypt Room | SQLCipher or an encrypted file layer for `jarvis_memory.db` | A rooted `adb pull` of the DB is unreadable without the key. |
| Encrypt chat history | Move `key_chat_history` out of plaintext prefs, or encrypt the value | Same test. |
| Encrypt owner voice profile | Same treatment; it is biometric-like data | Same test. |
| Replace alpha crypto | Move off `security-crypto:1.1.0-alpha06` to a stable API or a small Keystore wrapper | No alpha dependency in the release graph. |
| `targetSdk 36` | Raise compile/target, handle foreground-service, notification and predictive-back changes | App builds and a full device smoke test passes. |
| APK size | Move Vosk/Sherpa models to a first-run download or an asset pack | Base APK under the distribution limit; wake word still works after download. |
| Package allow-list | User-configurable list of apps automation may touch | A tap on an unlisted app is refused before dispatch. |

**Order:** encryption first (no platform risk), then `targetSdk`, then APK size.

## Phase 7 — Verification and quality (ongoing)

| Area | Action |
| --- | --- |
| Unit tests | `CommandParser` table tests; `McpManager` risk-gate tests; `ToolRegistry` schema tests; `PromptBuilder` golden tests; `AgentTaskEngine` multi-instance tests. |
| Instrumented tests | At least a smoke test that starts the service with a fake socket and asserts state transitions. |
| Device matrix | One Android 8, one Android 12, one Android 15/16 device; WhatsApp and YouTube flows are device-dependent. |
| Telemetry-free diagnostics | A debug-only screen that exports the last N structured events (no audio, no screen content). |
| Performance | Measure cold start, first audio frame latency, tool round-trip and memory while screen sharing. |

## Suggested major-upgrade tracks

If the goal is a visible "major upgrade" rather than pure refactoring, pick one
track and let the phases above support it.

### Track A — Reliability first (recommended)

Phase 0 → 1 → 3 → 7. Outcome: the same feature set, but a tool can be added in
one place, the service is testable, and regressions are caught by CI. Lowest
risk, highest long-term payoff.

### Track B — Automation 2.0

Phase 0 → 5 → 3. Outcome: pluggable per-app flows, a package allow-list, and a
flow recorder/replayer. Highest user-visible impact, highest device-testing cost.

### Track C — Privacy and distribution

Phase 0 → 6 → 7. Outcome: encrypted memory and history, stable crypto,
`targetSdk 36`, smaller APK. Necessary if the app is to be distributed beyond
sideloading.

### Track D — Model and prompt platform

Phase 0 → 2 → 1. Outcome: prompt fragments with a token budget, a single tool
registry, and a debug screen to inspect exactly what the model receives. Best if
behaviour tuning is the priority.

## Risk register

| Risk | Likelihood | Mitigation |
| --- | --- | --- |
| Model IDs are wrong or retired | High | Verify against the live API in Phase 0; keep the fallback and surface a clear error. |
| Tool refactor changes model behaviour | Medium | Golden setup JSON + device smoke test after each group. |
| Service extraction introduces audio races | Medium | Extract audio last; keep the single-owner bus invariant; test on a real device. |
| `targetSdk 36` breaks foreground service or overlay | Medium | Do it in its own PR with a device matrix. |
| APK size work breaks wake word | Medium | Download-then-verify flow with a checksum and an offline fallback message. |
| No device available for automation tests | High | Keep flows behind interfaces with fake trees; record real accessibility dumps as fixtures. |

## Definition of done for the upgrade

1. CI is green on `main` and required for merges.
2. One tool registry generates the setup payload and dispatches calls.
3. The conversation service is under 1,000 lines with extracted components.
4. Prompt text lives in assets and has a golden test.
5. Chat history, memory and the owner voice profile are encrypted at rest.
6. `targetSdk` is current and a device smoke test passes on three API levels.
7. `docs/` matches the code; every phase updated its document.
