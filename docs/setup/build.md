# Build and Setup

## Prerequisites

| Tool | Version used by this project |
| --- | --- |
| JDK | 21 (Gradle runs on JDK 21; sources target Java 17) |
| Android SDK | Platform 34, Build Tools 36.0.0 (pinned) |
| Gradle | Wrapper 8.9 |
| Android Gradle Plugin | 8.7.2 |
| Kotlin | 2.0.20 (kapt falls back to language level 1.9) |

Set the SDK location in an untracked `local.properties`:

```properties
sdk.dir=/path/to/Android/Sdk
```

On this machine the working values are:

```bash
export JAVA_HOME=/opt/jdk-21.0.12.1+1
export PATH="$JAVA_HOME/bin:$PATH"
# local.properties already points at /opt/sdk
```

## Debug build

```bash
cd /root/jarvis
./gradlew :app:assembleDebug --no-daemon
```

Output: `app/build/outputs/apk/debug/app-debug.apk`.

## Unit tests

```bash
./gradlew :app:testDebugUnitTest --no-daemon
```

Current suite: `AgentTaskEngineTest` (4 tests, all passing). Reports:
`app/build/test-results/testDebugUnitTest/`.

## Lint

```bash
./gradlew :app:lintDebug --no-daemon
```

HTML report: `app/build/reports/lint-results-debug.html`.
The project disables only `NullSafeMutableLiveData` because that AndroidX
Lifecycle detector crashes under Kotlin 2.0 UAST.

## Release build and identity pinning

Release packaging requires four signing values and a certificate fingerprint.
Provide them as Gradle properties or environment variables:

| Property | Purpose |
| --- | --- |
| `JARVIS_RELEASE_STORE_FILE` | Path to the release keystore. |
| `JARVIS_RELEASE_STORE_PASSWORD` | Keystore password. |
| `JARVIS_RELEASE_KEY_ALIAS` | Release key alias. |
| `JARVIS_RELEASE_KEY_PASSWORD` | Key password. |
| `JARVIS_RELEASE_CERT_SHA256` | 64-hex-character SHA-256 of the signing certificate. |
| `JARVIS_DEVELOPER_NAME` | Publisher name embedded in BuildConfig (default `Tech GPTx`). |
| `JARVIS_COPYRIGHT_OWNER` | Copyright owner embedded in BuildConfig. |
| `JARVIS_VERIFICATION_ENDPOINT` | Optional remote verification endpoint embedded in BuildConfig. |

`verifyReleaseSecurityInputs` runs before `packageRelease` and fails the build
when signing or the certificate pin is missing. `AppIdentityVerifier` also
refuses to start the conversation service in a release build without a valid
pin. Debug builds are intentionally exempt from the release pin because they use
the local debug key.

```bash
./gradlew :app:assembleRelease --no-daemon
```

## Common build notes

- `kapt` prints a warning that Kotlin language version 2.0 is unsupported; it
  falls back to 1.9 and the build still succeeds.
- `aapt2FromMavenOverride` is experimental; the warning is expected in this
  environment.
- Build Tools **36.0.0** is pinned in `app/build.gradle.kts`. The SDK's
  Build Tools 34.0.0 package on this aarch64 host contains x86-64 `aidl` and
  `aapt2` binaries, which fail with exec-format errors. Install 36.0.0 or set
  `buildToolsVersion` to an aarch64-compatible package. The AIDL interface uses
  explicit transaction IDs (`runCommand = 1`, Shizuku `destroy = 16777114`).
- If the SDK is missing, create `local.properties`; never commit it.
- If permissions or the API key are missing at runtime, the conversation service
  reports a user-facing error instead of crashing. Configure the key in
  **Settings → API Connection**.

## Useful verification sequence

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest --no-daemon
./gradlew :app:lintDebug --no-daemon
```

All three tasks are expected to finish with `BUILD SUCCESSFUL`. The Shizuku
binder handshake and custom MCP network calls require a real device/emulator
with Shizuku running and cannot be covered by JVM unit tests; see
[Shizuku and MCP tools](../features/shizuku-and-mcp.md#troubleshooting).
