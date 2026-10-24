# Gemini Tool Reference

All 22 functions are declared in
`network/GeminiLiveWebSocket.kt` → `sendSetupMessage()` and dispatched in
`background/JarvisConversationService.kt` → `handleToolCallExecution()`.

The model receives the declarations in the Live `setup` payload; there is no
separate tool registry. Adding a tool requires editing **both** files, plus
[this document](../README.md#maintaining-these-docs).

## Result contract

Every tool returns a JSON object to `sendToolResponse()`. The conventions the
model is instructed to honour:

| Field | Meaning |
| --- | --- |
| `success` | The action was actually performed. |
| `verified` | The post-action state confirmed the intended change. |
| `message` | Human/model-readable outcome, including honest failure reasons. |
| `current_screen_elements` | Optional fresh screen summary for the next step. |
| `requires_confirmation` | MCP only; nothing was executed. |
| `requires_permission` | MCP only; Shizuku permission is missing. |
| `autonomous_status` | `STOPPED` when a hard stop refused the action. |

## Tools

### App and web

| # | Tool | Required | Optional | Behaviour |
| --- | --- | --- | --- | --- |
| 1 | `open_app` | `app_name` | `app_number` (1–2) | Resolves installed apps, handles dual/cloned instances. Web-platform names (Google Flow, Runway, …) are short-circuited to a URL in Chrome. Anti-reload guard suppresses reopening the same URL within 20 s. |
| 2 | `open_website` | — | `urls` (string[]), `query` (string) | Opens one or more URLs as Chrome tabs. ⚠️ No required field: the model can call it empty. See [code health](../maintenance/code-health.md). |
| 3 | `search_in_chrome` | `query` | — | Opens Chrome and searches, or opens a URL. |
| 4 | `search_and_play_youtube` | `query` | — | Searches YouTube and immediately opens/plays the first result. |
| 5 | `search_youtube` | `query` | — | Search results screen only, no auto-play. |
| 6 | `youtube_accessibility_action` | `action` | — | `skip_ad`, `like`, `subscribe`, `open_channel`, `fullscreen`, `click_first`. |
| 7 | `media_playback_control` | `action` | — | `play`, `pause`, `next`, `previous`, `stop` via `MediaController`/audio manager. |
| 13 | `builtin_chrome_search` | `query` | — | Headless web research through `BuiltInChromeEngine`; returns text, no visible browser. |
| 14 | `go_to_background` | — | — | Minimises the task and enters wake-word standby. |

### Communication

| # | Tool | Required | Optional | Behaviour |
| --- | --- | --- | --- | --- |
| 8 | `send_whatsapp_message` | `recipient_name`, `message` | `app_number`, `confirmed` | Resolves the contact, opens the chat, types and sends through accessibility arm/poll. Result distinguishes "queued/opened" from verified send. |
| 9 | `whatsapp_call` | `recipient_name`, `call_type` | `app_number`, `confirmed` | Voice or video call; `call_type` is `voice`/`video`. |
| 10 | `call_contact` | `contact_name` | — | Standard telephony call via `ContactResolver` + `CALL_PHONE`. |

### Device

| # | Tool | Required | Optional | Behaviour |
| --- | --- | --- | --- | --- |
| 11 | `set_volume` | `action` | `percentage` | `increase`, `decrease`, `set` (0–100). |
| 12 | `set_brightness` | `action` | `percentage` | `increase`, `decrease`, `set` (0–100). |
| 15 | `set_flashlight` | `enabled` (bool) | — | Torch on/off through `CameraManager`. |
| 19 | `press_key` | `key` | — | `back`, `home`, `recents`. |

### Screen automation

| # | Tool | Required | Optional | Behaviour |
| --- | --- | --- | --- | --- |
| 16 | `click_ui_element` | `target_name` | `x`, `y`, `element_id`, `is_toggle`, `description` | Resolves by element ID, then label, then normalised coordinates (only with Screen Share). Verifies the hit target. |
| 17 | `type_text` | `text` | `x`, `y`, `target_input_name`, `submit` | Grounds the field, types, reads back the value. Clipboard paste alone is not proof. |
| 18 | `scroll_screen` | `direction` | `distance` | `up`/`down`/`left`/`right`; `small`/`medium`/`large` (default `small`, zero fling). |
| 21 | `inspect_screen` | — | — | Returns foreground package, title and accessible elements. Text only, not an image. |
| 20 | `task_completed` | `summary` | — | Signals the goal is visibly confirmed. The prompt forbids calling it right after typing into a search box. |

### Extensions

| # | Tool | Required | Optional | Behaviour |
| --- | --- | --- | --- | --- |
| 22 | `execute_mcp_tool` | `tool` (exact ID) | `arguments_json` (JSON string) | Runs an enabled MCP tool. Non-`SAFE` tools return `requires_confirmation` and never execute in this call. |

## Dispatch notes

- The tool name is matched in a `when` block. An unknown name falls through to a
  generic failure; there is no reflection or dynamic registration.
- `isAutomationTool` is a hard-coded set:
  `open_app`, `open_website`, `search_in_chrome`, `click_ui_element`,
  `type_text`, `scroll_screen`, `press_key`.
  New automation tools must be added there or they skip the autonomous-task
  bookkeeping and the cross-app guard.
- Tool calls are serialised by `toolExecutionMutex`; parallel calls queue.
- `task_completed` is the only tool that ends the autonomous loop early; the
  step ceiling ends it otherwise.
- The service also runs a deterministic fallback (`CommandParser`) when the model
  answers without calling a tool, so common intents still work.

## Prompt budget

The declarations are static JSON (~600 lines of builder code). If the model's
context becomes a concern, the cheapest win is to split rarely used tools into a
second tool set negotiated at setup; the Live API does not support dynamic tool
updates mid-session.
