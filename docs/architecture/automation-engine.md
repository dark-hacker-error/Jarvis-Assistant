# Automation Engine

The automation engine is the part of JARVIS that turns a spoken goal into taps,
swipes and text on the real device. It is deliberately conservative: the model
proposes, the engine verifies.

## Layers

```
Gemini tool call
   │
   ├── CommandExecutor  ──────────────►  deterministic intents
   │      (calls, volume, torch, memory, YouTube, WhatsApp)
   │
   └── VisualTaskOrchestrator ────────►  screen actions
              │
              ├── AgentTaskEngine          task state, plan, evidence, cancellation
              ├── SerializedActionExecutor  one mutation at a time, stale-action guard
              ├── ActionVerifier           did the expected change happen?
              ├── ScreenSignatureCalculator change / no-progress detection
              ├── UiGroundingEngine        element IDs, labels, bounds, surfaces
              └── JarvisAccessibilityService  gestures + node tree + screenshots
```

## AgentTaskEngine

`object AgentTaskEngine` is the authoritative in-process state for the active
user-requested task.

### States

| State | Meaning |
| --- | --- |
| `IDLE` | No task. |
| `UNDERSTANDING` | Goal parsed and scope classified. |
| `PLANNING` | Plan/steps being produced. |
| `OBSERVING` | Reading the current screen. |
| `RESOLVING_TARGET` | Mapping a label/coordinate to a node. |
| `ACTING` | A UI mutation is in flight. |
| `VERIFYING` | Checking the post-action state. |
| `CONTINUING` | Advancing to the next step automatically. |
| `REPLANNING` | Previous step invalid; plan adjusted. |
| `RECOVERING` | Retry after a failed/rejected action. |
| `WAITING_FOR_UI` | Waiting for content (loading, animation, network). |
| `PAUSED` | User paused; resume requires a fresh observation. |
| `BLOCKED` | Hard stop; no further mutations accepted. |
| `COMPLETED` / `FAILED` / `CANCELLED` | Terminal. |

### Task scope

| Scope | Trigger |
| --- | --- |
| `SINGLE_TARGET` | Default. |
| `ALL_MATCHING` | Bulk language detected by `TaskGoal.fromUserUtterance().isBulkAction` (e.g. "unlike all posts"). |
| `SEARCH_UNTIL_FOUND` | Goal contains search/find words (`search`, `find`, `dhoondh`, `talaash`, …). |

### Context

`Context` carries the plan, step list, processed item IDs/signatures, last
action + result + verified flag, screen signatures, retry/scroll/no-progress
counters, recovery count, completion evidence, and a volatile
`cancellationRequested` flag.

### The mutation gate

Every UI mutation must pass `executeUiMutation(action, operation)`:

1. Refuse if the task is cancelled or `BLOCKED`.
2. Wait while paused (`awaitIfRunnable`).
3. After a pause, force a **fresh** accessibility observation and reject the
   action if the screen changed while paused — the old target may no longer
   exist.
4. Delegate to `SerializedActionExecutor.execute()` with the task's
   `actionGeneration`; actions from a previous task are invalidated.
5. On rejection/failure, increment `consecutiveFailureCount`, set `RECOVERING`,
   and record that the action is unverified.

This is why a second task cannot inherit a stale tap from the first.

### Cancellation

- `requestCancellation()` sets the flag and cancels pending serialized actions.
- `resetForSession()` clears state when the conversation stops/restarts.
- `cancelAllActiveTools()` in the service cancels the coroutine job, the agent
  task and pending actions together.

## SerializedActionExecutor

- A single mutex serialises all accessibility mutations, regardless of which
  coroutine requested them.
- Each task gets a generation number. An action queued by generation *N* is
  dropped if the current generation is newer.
- `cancelAllPendingActions()` invalidates queued work.

## Verification

`ActionVerifier` and `VisualTaskOrchestrator` use several evidence types:

| Evidence | How it is checked |
| --- | --- |
| Expected package foreground | `getActiveRootNode().packageName` |
| Expected text present | Tree search for a normalised snippet |
| Element disappeared | Target node no longer in the tree |
| Screen changed | `ScreenSignatureCalculator` signature differs |
| Text entry | Read back the field content, not just the dispatched gesture |
| Visual hit target | Normalised coordinates are mapped to a node and its label must agree with the requested target |

A dispatched gesture alone is never treated as success. Results carry
`verified=false` with a human-readable reason when evidence is missing.

## Target resolution in `VisualTaskOrchestrator.handleClick()`

Order of preference:

1. **Explicit `element_id`** from a previous `inspect_screen` summary.
2. **Visual coordinates** (`x`,`y` in 0–1000) — only when Screen Share is
   active. The coordinate is mapped to the real display, the node under the
   point is resolved, and its label must agree with `target_name`; otherwise the
   tap is refused.
3. **Label/description matching** across clickable nodes, with scoring and
   ambiguity handling.

Additional gates:

- Sensitive targets (send, pay, delete, call, etc.) return
  `requiresUserConfirmation=true` instead of acting.
- Gmail "Send" is blocked unless the active goal explicitly asks to send email
  and a recipient is present.
- Click debounce of 280 ms prevents machine-gun tapping.
- Toggle semantics are handled separately (`isToggle`) to avoid double-flips.

## `UiGroundingEngine`

Scans the accessibility tree into `UiElementInfo`:

- stable numeric `elementId` per scan,
- primary label (text → content description → hint),
- role/class, clickable/editable/checked flags,
- screen bounds and normalised 0–1000 centre,
- surface detection (`isBrowserSearchScreen`, `isGoogleFlowSurface`,
  overflow menus).

`inspectScreen()` returns a compact textual summary the model can reason about
without receiving an image.

## Accessibility service capabilities

`JarvisAccessibilityService` provides:

| Capability | Methods |
| --- | --- |
| Tree access | `getActiveRootNode()`, `findAccessibilityNodeInfosByText`, recursive collectors |
| Gestures | `clickAt`, `longPressAt`, `scroll`, `pressBack/Home/Recents` |
| Text entry | `findAndInjectEditText`, `injectTextIfEmpty`, read-back verification |
| Screenshots | `takeScreenCapture`, `captureScreenBitmap`, `captureScreenJpeg` |
| App flows | WhatsApp send/call/delete arm+poll state machines, YouTube play/subscribe/fullscreen |
| OTP extraction | `getRecentOtp()` from notification/screen text |
| Change tracking | `UiChange` revision + timestamp for wait loops |

App-specific flows are implemented as **arm → poll → attempt → verify** state
machines because the target UI appears asynchronously after an intent. They are
brittle by nature: a WhatsApp/YouTube redesign changes labels and breaks the
flow. The engine reports failure rather than pretending success.

## Safety rails

| Rail | Location |
| --- | --- |
| One mutation at a time | `SerializedActionExecutor` |
| Stale-task invalidation | generation counter |
| Cross-app action block | `expectedPackageForTask()` in the service |
| Hard stop after repeated failure | `autonomousHardStop` |
| Step ceiling | `MAX_AUTONOMOUS_STEPS` / 400 bulk |
| Per-tool timeout | `automationToolTimeoutMs()` |
| Sensitive-action confirmation | `isSensitiveTarget()` |
| Owner voice gate | `isCurrentTurnOwnerVoiceVerified` before local commands |
| Cancellation by voice | "stop", "cancel task", "bas karo", "ruk jao" |

## Known limits

- Accessibility trees are incomplete in WebViews, games, and custom-drawn UIs;
  those need screen coordinates and an active Screen Share.
- There is no OCR fallback; image-only text is not readable without vision.
- Flows depend on English/Hinglish labels for WhatsApp and YouTube.
- Bulk operations are bounded by time and step count, not by a full item index.
- No replay/record of successful flows; each task re-discovers the UI.
