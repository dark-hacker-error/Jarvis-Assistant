# JARVIS Voice Assistant

An Android voice assistant built with Kotlin, Gemini Live, on-device voice components, accessibility automation, screen capture, and a native Android UI.

## Project status

- Android application module: `app`
- Application ID: `com.jarvis.assistant`
- Minimum Android version: Android 8.0 (API 26)
- Compile and target SDK: 34
- Java/Kotlin target: 17
- Current app version: 1.9.1 (version code 145)

## Getting started

1. Open the project root in Android Studio and allow Gradle to sync.
2. Set the Android SDK path in your local `local.properties` file. The file is machine-specific and is ignored by Git.
3. Launch the app on an Android 8.0+ device or emulator.
4. Add a Gemini API key in the app's Settings and grant the requested runtime permissions.

The Gradle wrapper is included in this project. Use `gradlew.bat` on Windows or `./gradlew` on macOS/Linux.

## Always-on wake word and floating orb

Jarvis uses a two-part runtime so the heavy assistant can sleep while the wake
word stays available:

- **Part 1 (`:wake` process)** is an ultra-light foreground service. It runs a
  free, offline Sherpa-ONNX keyword spotter for "Jarvis" and verifies the owner
  with a WeSpeaker CAM++ embedding on the same 16 kHz buffer. Room, TTS, Gemini,
  MCP, Shizuku, backup and the large Vosk ASR model are never loaded there.
- **Part 2 (main process)** starts only after both the keyword and the owner
  voice match. It attaches a fully transparent bottom-centre orb and reveals it
  only while Jarvis is speaking (or while the user is actively talking), instead
  of opening `MainActivity`. When the conversation ends it hands the microphone
  back to Part 1.

No API key, account or usage limit is involved in wake detection. Enable it in
**Settings → Hands-free** and grant microphone, notification and "Display over
other apps" permissions. Enrolling the owner voice is strongly recommended: the
wake service runs in keyword-only mode until a profile exists, then automatically
requires the owner's voice. The wake service restarts after reboot; some Android
14+ builds block a background microphone start, in which case a notification
asks you to open Jarvis once.

Saved memories (including facts added from the Memory screen) are injected into
the live Gemini session immediately through a silent context update, so Jarvis
can recall them without reconnecting.

## Build commands

```powershell
# Windows: compile and package a debug APK
.\gradlew.bat :app:assembleDebug

# Windows: compile and package a release APK
.\gradlew.bat :app:assembleRelease
```

```bash
# macOS/Linux
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
```

Build outputs are written under `app/build/outputs/` and are generated files, not source files. Release builds enable R8 and resource shrinking. `assembleRelease` intentionally refuses to package unless a dedicated keystore, its SHA-256 certificate pin, and publisher metadata are configured. Never commit keystores or passwords.

Create and protect a release key outside this repository, then supply these values only in the local shell or Gradle user properties:

```powershell
$env:JARVIS_RELEASE_STORE_FILE = 'C:\secure\jarvis-release.jks'
$env:JARVIS_RELEASE_STORE_PASSWORD = '<local secret>'
$env:JARVIS_RELEASE_KEY_ALIAS = 'jarvis-release'
$env:JARVIS_RELEASE_KEY_PASSWORD = '<local secret>'
$env:JARVIS_RELEASE_CERT_SHA256 = '<64 hex characters from keytool -list -v>'
$env:JARVIS_DEVELOPER_NAME = 'Roshan Hacker'
$env:JARVIS_COPYRIGHT_OWNER = 'Roshan Hacker'
.\gradlew.bat :app:assembleRelease
```

The certificate fingerprint must be calculated from the same key used to sign the APK. This project currently has no deployed verification backend or agreed verification API protocol. Local signer pinning and APK signatures do not provide server-side authentication, Play Integrity attestation, or rate limiting; the app identity screen reports remote verification as unavailable until that service is implemented and deployed.

## Repository layout

```text
.
├── app/                         Android application module
│   └── src/main/
│       ├── aidl/                Shizuku UserService interface
│       ├── java/com/jarvis/assistant/
│       │   ├── audio/           Microphone bus, playback, voice biometrics
│       │   ├── automation/      Accessibility, agent, commands, phone, WhatsApp, YouTube
│       │   ├── background/      Conversation foreground service
│       │   ├── data/            Preferences, Room memory, chat repository
│       │   ├── mcp/             Built-in and custom MCP tools
│       │   ├── network/         Gemini Live transport
│       │   ├── security/        Package and signature checks
│       │   ├── system/          Shizuku manager and privileged shell
│       │   ├── ui/              Activities, fragments, overlays
│       │   ├── util/            Prompt builder and shared helpers
│       │   ├── vision/          Screen capture, UI grounding, visual actions
│       │   ├── voice/           Wake engine, orb, VoiceInteraction session
│       │   └── wake/            Part 1 wake service and cross-process contract
│       ├── assets/              Bundled web UI and offline voice models
│       └── res/                 Android layouts, drawables, values, and config
├── docs/
│   ├── architecture/            Overview, module map, runtime flows, automation
│   ├── reference/               Gemini tools, MCP tools, settings and data
│   ├── security/                Permissions and threat model
│   ├── setup/                   Build, signing and troubleshooting
│   ├── maintenance/             Code health, upgrade roadmap, bug-fix log
│   ├── features/                Shizuku and MCP user guide
│   └── diagnostics/             User-provided issue recordings and index
├── gradle/                      Gradle wrapper configuration
├── build.gradle.kts             Root Gradle plugin declarations
├── settings.gradle.kts          Project and repository configuration
└── gradle.properties            Gradle runtime settings
```

## Documentation

Start with the [documentation index](docs/README.md). The most useful entry
points:

- [Architecture overview](docs/architecture/overview.md) — the system in ten minutes
- [Module map](docs/architecture/module-map.md) — which file does what
- [Runtime flows](docs/architecture/runtime-flows.md) — wake word, voice turn, tools, MCP
- [Automation engine](docs/architecture/automation-engine.md) — how screen actions are verified
- [Gemini tools](docs/reference/gemini-tools.md) and [MCP tools](docs/reference/mcp-tools.md)
- [Build and setup](docs/setup/build.md) — JDK, SDK, signing, troubleshooting
- [Threat model](docs/security/threat-model.md) and [permissions](docs/security/permissions-and-security.md)
- [Code health](docs/maintenance/code-health.md) and [upgrade roadmap](docs/maintenance/upgrade-roadmap.md)
- [Shizuku and MCP guide](docs/features/shizuku-and-mcp.md)
- [Voice architecture research](docs/architecture/voice-architecture-research.md) and [voice issue recordings](docs/diagnostics/voice-videos/README.md)

## Configuration and privacy

- Keep API keys, SDK paths, signing keys, and local machine settings out of source control.
- `local.properties` is local-only; start from `local.properties.example` when setting up a new machine.
- Screen sharing and accessibility automation require explicit Android user consent and permissions.
- Bundled Vosk model assets are runtime dependencies; do not remove or rename their directories without updating the model-loading code.
