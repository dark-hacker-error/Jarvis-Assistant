# Permissions and Security Model

## Runtime permissions

| Permission | Used by | Notes |
| --- | --- | --- |
| `RECORD_AUDIO` | Voice conversation, wake word, voice enrollment | Core permission; the foreground service type includes `microphone`. |
| `POST_NOTIFICATIONS` | Part 1 and Part 2 foreground notifications | Required on Android 13+ for a visible ongoing notification. |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `FOREGROUND_SERVICE_MEDIA_PROJECTION` | `WakeWordService`, `JarvisConversationService` | Declares the foreground services and their active types. |
| `WAKE_LOCK` | Part 1 and Part 2 services | Partial wake lock with a 6-hour timeout; Part 1 renews it every 5 hours while listening. |
| `RECEIVE_BOOT_COMPLETED` | `WakeBootReceiver` | Restarts Part 1 after reboot or app update; if Android blocks the background mic start, a notification asks the user to open Jarvis once. |
| `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION` | Weather card | Used only for the current location; weather is fetched from Open-Meteo. |
| `INTERNET`, `ACCESS_NETWORK_STATE` | Gemini Live, Open-Meteo, YouTube API | Network access. |
| `BLUETOOTH`, `BLUETOOTH_ADMIN` (≤ API 30), `BLUETOOTH_CONNECT` | Headset routing | Routes audio to Bluetooth headsets. |
| `MODIFY_AUDIO_SETTINGS` | Audio routing | Speaker/headset/SCO selection. |
| `READ_CONTACTS` | WhatsApp and phone automation | Resolves contact names to numbers. |
| `CALL_PHONE` | Phone automation | Direct calls when requested. |
| `CAMERA` | Flashlight control | `PhoneControlManager` uses `CameraManager` torch APIs; declared `required="false"` so the app installs on devices without a camera/flash. |
| `VIBRATE` | Feedback | Short vibration cues. |
| `SYSTEM_ALERT_WINDOW` | Floating conversation orb and `TaskProgressOverlay` | Optional overlay permission. The task overlay falls back to an in-app panel; the orb is skipped when permission is missing, but the session continues. Turning off the task overlay does not affect the orb. |
| `WRITE_SETTINGS` | Device settings automation | Protected permission; requires the user to grant it from Settings. |
| `QUERY_ALL_PACKAGES` | App launching | Needed to resolve installed apps on Android 11+. |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Long-running service | Prompt only; the user decides. |

`android.hardware.camera`, `android.hardware.camera.flash`, and
`android.hardware.telephony` are declared with `required="false"` so the app is
installable on tablets, emulators, and devices without telephony.

## Accessibility service

`JarvisAccessibilityService` is exported with the platform permission
`android.permission.BIND_ACCESSIBILITY_SERVICE`. Only the system can bind to it.
Its configuration requests:

- window state and content-change events,
- retrieval of interactive windows and view IDs,
- gesture dispatch,
- screenshot capability.

This is the most powerful permission in the app: it lets Jarvis read screen
content and perform taps, swipes, and text entry. It must be enabled manually in
**Settings → Accessibility → Jarvis**. Users should disable it when automation
is not needed. The service is used for task automation and WhatsApp/YouTube
flows, not for advertising.

## Shizuku system access

Shizuku is an optional, user-installed privileged helper. Jarvis declares the
standard `ShizukuProvider` and requests permission through the Shizuku app at
runtime; there is no Android manifest permission that silently grants this
access. The user must start Shizuku with ADB/wireless debugging or root and
approve Jarvis in Shizuku.

- Privileged commands run in `JarvisShellService`, a Shizuku **UserService** in
  Shizuku's process, not in the normal Jarvis process.
- The AIDL surface has one command method plus Shizuku's reserved `destroy`
  transaction. Commands are capped at 2,000 characters, 60 seconds, and 20,000
  characters of output.
- `ShizukuManager` re-checks permission and binder liveness before every command
  and reports failures instead of crashing the conversation service.
- Shizuku access is never required for ordinary Jarvis voice, chat,
  accessibility automation, WhatsApp, phone, or YouTube flows. If Shizuku is
  unavailable, only tools marked `Shizuku` fail with a user-facing message.
- Revoking Shizuku permission or stopping Shizuku makes privileged MCP tools
  unusable immediately; they are not cached or bypassed.

## MCP tool safety

MCP is an optional extension layer. Built-in servers are off by default; only
enabled tools are included in the Gemini system prompt. Custom MCP servers are
user-supplied endpoints.

- Every built-in tool has a risk label. `CONFIRM` and `DANGEROUS` tools return a
  pending action and never execute on the first model call.
- The pending action is held in `JarvisConversationService` for at most two
  minutes. Only the local spoken yes path calls `McpManager.executeConfirmed()`;
  the model cannot approve its own action.
- A different tool cannot overwrite an existing pending confirmation. An
  unrelated utterance, a stop command, or stopping the conversation clears it.
- Arguments are re-validated and the tool is re-resolved at confirmation time,
  so disabling a server or revoking Shizuku cancels execution.
- Built-in file deletion is restricted to `/sdcard` and
  `/storage/emulated/0`, rejects `..` traversal, refuses the storage root, and
  resolves symlinks before deletion.
- The guarded shell refuses destructive commands targeting system/storage
  roots, generic reboot/shutdown commands (the dedicated power tool is used),
  and direct block-device writes. All shell calls still require explicit
  confirmation.
- Custom MCP endpoints must use HTTPS. Cleartext HTTP is permitted only for
  `localhost`, `127.0.0.1`, and `::1` for local development.
- Custom bearer tokens are stored in `EncryptedSharedPreferences` when
  available. If the Keystore cannot initialise, the UI warns that the token is
  stored in app-private plaintext preferences.
- The custom MCP client refuses HTTP redirects so a server cannot redirect a
  bearer token to a different host, and caps response bodies to 2 MB.
- Custom tool output is treated as untrusted text and truncated before being
  returned to Gemini or shown in the UI. It is never evaluated as a command.

## Screen capture

Screen capture uses `MediaProjection` and requires per-session user consent.
`ScreenCaptureManager` mirrors the display into an `ImageReader`, encodes JPEG
frames, and sends them to Gemini only while screen sharing is active. The
foreground notification reports active capture. Capture stops when the user
stops sharing or the conversation service stops.

## API key storage

- The Gemini API key is stored in `EncryptedSharedPreferences` with an
  AES-256-GCM master key backed by the Android Keystore.
- If encrypted storage cannot be initialised (for example, a broken Keystore on
  some devices), the app keeps a plaintext fallback in standard preferences so
  the assistant remains usable. The UI reports that secure storage is
  unavailable. A plaintext key is less protected; users should remove and
  re-enter it after fixing device security.
- Migration never deletes the only copy of the key. The encrypted copy is
  preferred at read time.

The key is never logged. It is sent only to
`generativelanguage.googleapis.com` as part of the Live API connection.

## Release identity and tamper checks

- `BuildConfig.PINNED_RELEASE_CERT_SHA256` is populated from
  `JARVIS_RELEASE_CERT_SHA256`.
- `SecurityManager.verify()` checks the package name and, in release builds,
  compares the installed signer SHA-256 with the pin. A mismatch shows a tamper
  alert and closes the app.
- Debug builds skip the release signature pin because they use the local debug
  key.
- `AppIdentityVerifier` blocks the conversation service in a release build when
  the pin or verification configuration is missing.
- `verifyReleaseSecurityInputs` fails `packageRelease` unless signing values and
  a 64-hex certificate pin are provided.

## Data at rest and in transit

- Chat history, preferences, and memory live in the app's private storage.
- Room memory is local to the device and is not uploaded by the app itself.
- Live audio, typed messages, screen frames, and tool results are sent to the
  Gemini Live API over TLS (`wss://`).
- Weather coordinates are sent to `api.open-meteo.com` over HTTPS.
- Enabled custom MCP tool calls are sent over HTTPS to the user-configured
  endpoint. Their bearer token and tool arguments leave the device only for that
  endpoint. Localhost HTTP is an explicit development exception.
- There is no separate analytics or advertising SDK in this module.

## Microphone privacy indicator

Android 12+ shows the system microphone indicator (the green dot / privacy
chip) whenever any app is capturing microphone audio. This is a platform
transparency control, not a notification owned by Jarvis:

- `RECORD_AUDIO` app-ops, `SYSTEM_ALERT_WINDOW`, and a foreground-service
  notification do not suppress it.
- `AlwaysOnHotwordDetector` / `SoundTrigger` are privileged system APIs, are not
  present in the public SDK, and still attribute microphone access to the app.
- There is no per-app opt-out. The only global switches
  (`device_config`/`settings` privacy toggles) hide indicators for **every** app
  on the device, so Jarvis deliberately does not change them.

The wake path therefore keeps the indicator visible while it is listening. Part 1
keeps its Sherpa-ONNX spotter and partial wake lock active while the screen is
off so "Jarvis" still works from a locked phone; it pauses only while Part 2 or
an assist session owns the microphone. Active conversations and enrollment are
reported by the same system indicator.

## Operational recommendations

1. Use a dedicated, restricted Gemini API key and rotate it if a device is lost.
2. Keep the release certificate pin configured for every release build.
3. Grant accessibility, screen-capture, and Shizuku access only when needed;
   revoke Shizuku permission when privileged tools are not in use.
4. Keep the app's notification visible; it is the system's indicator that the
   microphone or screen capture is active.
5. Enable only the MCP servers you trust. A custom MCP server can return
   arbitrary tool descriptions and output, which are treated as untrusted data.
6. Do not embed production API keys or MCP bearer tokens in source; enter tokens
   through the encrypted settings UI.
7. Do not embed production API keys in source; `GeminiConstants.RELEASE_API_KEY`
   is intentionally empty and should remain so.
