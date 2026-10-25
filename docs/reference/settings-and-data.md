# Settings, Preferences and Data

## Screens

| Screen | Activity | What it configures |
| --- | --- | --- |
| Home | `MainActivity` + `HomeFragment` | Orb, session control, status cards, weather, task fallback panel, dock navigation. |
| Settings hub | `SettingsActivity` + `SettingsFragment` | Entry point to every section; version/identity. |
| API connection | `ApiConnectionActivity` | Gemini API key, model selection, custom endpoint. |
| Persona | `PersonaActivity` | Personality mode, user name, language, voice. |
| Hands-free | `HandsFreeActivity` | Wake word, standby behaviour, owner voice. |
| Privacy shield | `PrivacyShieldActivity` | Owner voice verification, silent execution, screen-share awareness. |
| Overlay avatar | `OverlayAvatarActivity` | Task overlay avatar/mode/edge/position. |
| MCP tools | `McpSettingsActivity` | Built-in server toggles, custom servers, tool refresh. |
| Shizuku | `ShizukuSettingsActivity` | Shizuku install/permission/binding status. |
| Memory | `MemoryActivity` | Browse and delete stored facts. |
| Voice enrollment | `VoiceEnrollmentActivity` | Record owner voice samples. |
| Permissions | `PermissionActivity` | Runtime permission onboarding. |
| Theme | `ThemeSelectionBottomSheet` | App theme, accent, bottom-nav style. |

## Preference keys

All keys live in `AppPreferences` (`SharedPreferences` file `jarvis_prefs`),
except the Gemini API key, which is stored in `EncryptedSharedPreferences`
(`jarvis_secure_api_prefs`, AES-256-GCM master key) when the Android Keystore is
available.

| Key | Type | Default | Purpose |
| --- | --- | --- | --- |
| `key_api_key` | String | `""` | Gemini API key (encrypted store; guarded plaintext fallback). |
| `key_model` | String | `models/gemini-3.1-flash-live-preview` | Live model; validated against `SUPPORTED_MODELS`. |
| `key_voice` | String | `Puck` | Prebuilt voice name. |
| `key_personality` | String | `Jarvis Pro` | Persona mode. Legacy `TechGPT X Mode` is migrated to `Roshan Hacker Mode`. |
| `key_user_name` | String | `""` | Preferred form of address. |
| `key_language` | String | `Auto` | Spoken-language mandate. |
| `key_mic_muted` | Boolean | `false` | Mic mute state. |
| `key_silent_execution_mode` | Boolean | `false` | Suppress spoken confirmations during automation. |
| `key_chat_history` | String (JSON) | `[]` | Persisted turns, capped at 100. |
| `key_favorite_song` | String | `Tadipaar` | Used by the deterministic music path. |
| `key_favorite_app` | String | `""` | Preferred app for ambiguous "open" requests. |
| `key_song_counts` | String (JSON) | `{}` | Play-count map. |
| `key_user_birthday` | String | `""` | Recall command. |
| `key_user_location` | String | `""` | Prompt context for local questions. |
| `key_user_age` | String | `""` | Prompt context. |
| `key_daily_routine` | String | `""` | Recall command / prompt context. |
| `key_owner_voice_enabled` | Boolean | `false` | Enforce owner voice verification. |
| `key_owner_voice_profile` | String (JSON) | — | Master embedding. |
| `key_owner_voice_samples` | String (JSON) | — | Enrolled sample vectors. |
| `key_owner_voice_threshold` | Float | `0.70` | Similarity threshold; values < 0.60 are auto-migrated to 0.70. |
| `key_owner_voice_sample_count` | Int | `0` | Enrollment progress. |
| `key_task_overlay_enabled` | Boolean | `true` | Floating task HUD. |
| `key_task_overlay_avatar` | String | `orb` | Avatar asset. |
| `key_task_overlay_mode` | String | `pill` | `pill` or `avatar`. |
| `key_task_overlay_edge` | String | `right` | Docked edge. |
| `key_task_overlay_free_position` | Boolean | `false` | Free drag vs edge dock. |
| `key_task_overlay_x`, `_y` | Int | `0` | Saved position. |
| `key_app_theme` | String | `dark` | Theme. |
| `key_bottom_nav_style` | String | `dock` | Navigation style. |
| `key_custom_theme_color` | String | `#00E5FF` | Accent. |
| `key_orb_animation` | String | `pulse` | Orb animation. |
| `key_orb_speed` | Float | `1.0` | Animation speed. |
| `key_orb_glow` | Float | `1.0` | Glow strength. |
| `key_orb_scale` | Float | `1.0` | Orb size. |
| `key_orb_hue` | Float | `180` | Hue rotation. |
| `key_custom_endpoint` | String | `""` | Optional Live endpoint override. |

`YouTubePreferences` stores the optional Data API key separately.

## Encrypted storage

- `MasterKey` uses `AES256_GCM` in the Android Keystore.
- `EncryptedSharedPreferences` uses AES-256-SIV for keys and AES-256-GCM for
  values.
- If the Keystore cannot initialise (some emulators/older devices), the app logs
  the failure and falls back to the plaintext preference file. The UI exposes
  `canStoreApiKeySecurely` so the user can see the difference.
- The key is never written to logs, chat history or the system prompt.

## Room memory

Database: `jarvis_memory.db` (`MemoryDatabase`).

`MemoryEntity` fields:

| Column | Purpose |
| --- | --- |
| `id` | Auto primary key. |
| `category` | `personal`, `preferences`, `routines`, `contacts`, `notes`. |
| `key` | First 40 chars of the fact. |
| `value` | Full fact text. |
| `rawText` | Original cleaned sentence. |
| `createdAt`, `updatedAt` | Timestamps. |

`MemoryManager`:

- keeps a 30-turn short-term window preloaded from chat history,
- appends pruned turns to an 800-char rolling summary,
- extracts preferences from conversation (`learnPreferencesFromConversation`),
- `rememberFact`, `forgetFact`, `getWhatDoYouRemember` produce spoken replies
  and also write a chat turn.

## Chat history

`ChatRepository`:

- loads from prefs at construction,
- `addTurn` de-duplicates consecutive identical turns,
- `beginTurn` creates a user bubble without a placeholder reply,
- `completeLastTurn` attaches the reply,
- cap: 100 turns (oldest dropped).

## Uninstall-surviving backup

`BackupCoordinator` writes one encrypted snapshot to
`Downloads/Jarvis/jarvis-backup.json` through MediaStore. App-private storage is
deleted on uninstall, but a MediaStore file in Downloads is not, so the snapshot
survives a reinstall. It contains long-term memories, chat history, personal
preferences, both API keys and the custom MCP configuration.

- Encryption: AES-256-GCM with a key derived from the app signing certificate;
  only an app signed with the same key can decrypt it.
- Automatic restore runs on first launch only when local data is empty.
- An explicit **Restore** merges missing records and never overwrites newer
  local values.
- Writes are debounced after relevant changes and also run when the app goes to
  the background; a periodic safety backup runs while the app is open.
- The snapshot can be deleted from Settings.

`android:allowBackup="false"` disables Android's own cloud/adb backup; the
Jarvis snapshot above is a separate, opt-in mechanism. There is no cloud sync.

## Data retention summary

| Data | Where | Cleared by |
| --- | --- | --- |
| Chat history | SharedPreferences JSON | Chat history sheet → clear, or app data wipe |
| Long-term memory | Room | Memory screen delete, `forget` command, app data wipe |
| Owner voice profile | SharedPreferences JSON | Privacy shield → clear profile |
| API keys | EncryptedSharedPreferences | Settings → clear key |
| Custom MCP servers/tokens | EncryptedSharedPreferences | MCP settings → remove server |
| Wake word model | APK assets | Reinstall |
| Uninstall-surviving snapshot | Encrypted JSON in `Downloads/Jarvis` | Settings → delete backup |
| Screen frames | Memory only (latest bitmap) | Stop sharing / process death |
