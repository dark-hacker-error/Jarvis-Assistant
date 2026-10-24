# Build and Setup

## Prerequisites

| Tool | Version used by this project |
| --- | --- |
| JDK | 21 to run Gradle; sources and bytecode target Java 17 |
| Android SDK | Platform 34 (platform 36 also installed), Build Tools **36.0.0** (pinned) |
| Gradle | Wrapper 8.9 |
| Android Gradle Plugin | 8.7.2 |
| Kotlin | 2.0.20 (kapt falls back to language level 1.9) |

Set the SDK location in an untracked `local.properties`:

```properties
sdk.dir=/path/to/Android/Sdk
```

On the reference aarch64 host:

```bash
export JAVA_HOME=/opt/jdk-21.0.12.1+1
export PATH="$JAVA_HOME/bin:$PATH"
# local.properties already points at /opt/sdk
```

> The default `java` on this host is 11. Gradle 8.9 requires 17+, so always set
> `JAVA_HOME` explicitly or the build fails at configuration time.

## Debug build

```bash
cd /root/jarvis
./gradlew :app:assembleDebug --no-daemon
```

Output: `app/build/outputs/apk/debug/app-debug.apk` (≈230 MB because the Vosk
and Sherpa model assets are bundled uncompressed).

Install:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Unit tests

```bash
./gradlew :app:testDebugUnitTest --no-daemon
```

Current suite: `app/src/test/java/com/jarvis/assistant/automation/agent/AgentTaskEngineTest.kt`
(4 tests). Reports: `app/build/test-results/testDebugUnitTest/`.

There are no instrumented tests. Anything that needs AccessibilityService,
Shizuku, MediaProjection or a real microphone must be verified manually on a
device.

## Lint

```bash
./gradlew :app:lintDebug --no-daemon
```

HTML report: `app/build/reports/lint-results-debug.html`.

The project disables only `NullSafeMutableLiveData` because that AndroidX
Lifecycle 2.8.6 detector crashes under the Kotlin 2.0 UAST. Every other check is
active.

## Release build and identity pinning

Release packaging requires four signing values and a certificate fingerprint.
Provide them as Gradle properties (`-P`) or environment variables:

| Property | Purpose |
| --- | --- |
| `JARVIS_RELEASE_STORE_FILE` | Path to the release keystore. |
| `JARVIS_RELEASE_STORE_PASSWORD` | Keystore password. |
| `JARVIS_RELEASE_KEY_ALIAS` | Release key alias. |
| `JARVIS_RELEASE_KEY_PASSWORD` | Key password. |
| `JARVIS_RELEASE_CERT_SHA256` | 64-hex-character SHA-256 of the signing certificate (colons allowed). |
| `JARVIS_DEVELOPER_NAME` | Publisher name embedded in BuildConfig (default `Roshan Hacker`). |
| `JARVIS_COPYRIGHT_OWNER` | Copyright owner embedded in BuildConfig (default `Roshan Hacker`). |
| `JARVIS_VERIFICATION_ENDPOINT` | Optional remote verification endpoint embedded in BuildConfig. |

`verifyReleaseSecurityInputs` runs before `packageRelease` and fails the build
when signing, the certificate pin, or the publisher identity is missing.

```bash
export JARVIS_RELEASE_STORE_FILE=/secure/jarvis-release.jks
export JARVIS_RELEASE_STORE_PASSWORD='…'
export JARVIS_RELEASE_KEY_ALIAS=jarvis-release
export JARVIS_RELEASE_KEY_PASSWORD='…'
export JARVIS_RELEASE_CERT_SHA256='AA:BB:…'   # or 64 hex chars
export JARVIS_DEVELOPER_NAME='Roshan Hacker'
export JARVIS_COPYRIGHT_OWNER='Roshan Hacker'

./gradlew :app:assembleRelease --no-daemon
```

At runtime, `SecurityManager.verify()` runs in `JarvisApp.onCreate()`:

- package name must be `com.jarvis.assistant`,
- release builds must be signed with the pinned certificate,
- debug builds are exempt (local debug key),
- a mismatch shows a non-cancellable tamper dialog and exits.

Compute the fingerprint from the signing certificate:

```bash
keytool -list -v -keystore /secure/jarvis-release.jks -alias jarvis-release \
  | grep 'SHA256:'
```

## Common build notes

- **Build Tools 36.0.0 is pinned.** The 34.0.0 package on this aarch64 host ships
  x86-64 `aidl`/`aapt2` binaries and fails with exec-format errors. Install
  36.0.0 or point `buildToolsVersion` at an aarch64-compatible package.
- **AIDL transaction IDs are explicit.** `IJarvisShellService` uses
  `runCommand = 1`; Shizuku reserves `destroy = 16777114`. Do not reorder.
- `kapt` warns that Kotlin 2.0 is unsupported and falls back to 1.9; the build
  succeeds.
- `aapt2FromMavenOverride` is experimental; its warning is expected.
- Never commit `local.properties`, `*.jks`, `*.keystore` or signing passwords.
  `.gitignore` already excludes them.
- `app/build/` is ~780 MB after a full build; it is ignored.

## Useful verification sequence

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest --no-daemon
./gradlew :app:lintDebug --no-daemon
```

All tasks should finish with `BUILD SUCCESSFUL`. The Shizuku binder handshake,
custom MCP network calls, wake word and screen capture require a real
device/emulator and cannot be covered by JVM tests. See
[Shizuku and MCP tools](../features/shizuku-and-mcp.md#troubleshooting).

## Troubleshooting

| Symptom | Cause / fix |
| --- | --- |
| `Unsupported class file major version` | Gradle running on JDK 11. Set `JAVA_HOME` to JDK 21. |
| `aidl: Exec format error` | Build Tools 34.0.0 on aarch64. Use 36.0.0. |
| `SDK location not found` | Create `local.properties` with `sdk.dir`. |
| Release build fails at `verifyReleaseSecurityInputs` | Missing keystore values or certificate pin. |
| App exits immediately after install | Signature/package check failed (release build with wrong key). |
| Live session reports `API_KEY_MISSING` | Set the key in **Settings → API Connection**. |
| Live session reports `PERMISSIONS_MISSING` | Grant microphone and notifications. |
| Wake word never triggers | `RECORD_AUDIO` denied, or the KWS model failed to load (check logcat tag `SherpaWakeWord`). |
| Automation taps nothing | Accessibility service not enabled, or the foreground app does not match the task. |
| MCP tool says Shizuku required | Shizuku not running or permission not granted. |
