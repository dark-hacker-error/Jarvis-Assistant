# Shizuku System Access and MCP Tools

Jarvis 1.8.7 adds two additive integrations:

- **Shizuku system access** for privileged Android operations without rooting the
  phone.
- **MCP Tools** for free built-in device tools and user-added custom MCP
  servers.

Neither feature replaces or weakens the existing Gemini tools, accessibility
automation, WhatsApp/phone/YouTube flows, or settings. If Shizuku is missing or
permission is denied, the rest of Jarvis keeps working normally.

## Shizuku setup

1. Install [Shizuku](https://shizuku.rikka.app/download/) from the official
   source.
2. Start Shizuku with wireless debugging/ADB or root.
3. Open **Jarvis → Settings → Shizuku System Access**.
4. Tap **Request Shizuku Permission** and allow Jarvis in the Shizuku dialog.
5. Tap **Re-check / Bind Privileged Service**.
6. Optionally tap **Test shell** to verify `id` and the Android release.

The settings screen shows whether Shizuku is installed, whether its binder is
running, whether permission is granted, and whether the privileged UserService
is connected. The service reports whether it is running as `ROOT`, `ADB SHELL`
(uid 2000), or another UID.

### How the integration works

- Uses Shizuku API/provider `13.1.5`.
- Uses the current **UserService** API instead of the removed
  `Shizuku.newProcess` API.
- `IJarvisShellService.aidl` exposes `runCommand` and Shizuku's reserved
  `destroy` transaction.
- `JarvisShellService` runs in Shizuku's isolated process, executes only
  `/system/bin/sh`, caps commands at 60 seconds, caps output at 20,000
  characters, and returns JSON.
- `ShizukuManager` owns permission checks, binding, reconnects, timeouts, and
  status text. An explicit timeout avoids hanging the conversation service when
  Shizuku disappears.

Shizuku only provides the transport and privilege. Jarvis still validates every
request and still requires spoken confirmation for sensitive actions.

## Built-in MCPs

Open **Settings → MCP Tools & Servers** to see the free, on-device catalogue.
All built-in MCP servers are **off by default**. Enable only the servers you
want Jarvis to use.

| Server | Tools | Shizuku |
| --- | --- | --- |
| System Control | device status, power, Wi-Fi/Bluetooth/airplane/DND/rotation, volume, brightness, settings panels, guarded shell | partly |
| App Manager | list apps, app info, force-stop, clear data, disable, enable | yes for changes |
| File Manager | list shared-storage files, delete a path, disk usage | yes |
| Network Tools | network status, ping, Wi-Fi status, DNS lookup | partly |
| Device Insights | battery, storage, uptime, hardware information | no |

Risk labels are shown in the MCP screen and in the prompt sent to Gemini:

- `SAFE` — read-only or ordinary app-level action.
- `CONFIRM` — changes device/app state and requires an explicit yes.
- `DANGEROUS` — destructive or power action and requires an explicit yes.

Reboot/shutdown, app-data clearing, disabling apps, file deletion, and the
guarded shell are all confirmation-gated. The shell validator additionally
refuses destructive commands aimed at system/storage roots, generic
reboot/shutdown commands (use the dedicated power tool), and direct writes to
block devices.

## Custom MCP servers

Tap **+ Add** and provide:

- a display name,
- a Streamable HTTP JSON-RPC endpoint (HTTPS required; HTTP is accepted only for
  `localhost`/`127.0.0.1` during development),
- an optional bearer token.

After saving, Jarvis discovers tools with `tools/list`, caches them locally, and
shows the number of discovered tools. Toggle the server on to make those tools
available to Gemini. Use **Refresh tools** after changing the remote server, and
**Remove** to delete the local configuration and cached tool list.

Every custom MCP tool is treated as `CONFIRM`. The client:

- initializes an MCP session and sends `notifications/initialized`,
- supports JSON and Server-Sent Events responses,
- follows paginated `tools/list` results up to five pages,
- sends `Authorization: Bearer …` only when a token is configured,
- refuses redirects so a server cannot bounce a token to another host,
- caps response size,
- reports `isError` tool results as failures.

Bearer tokens are stored in `EncryptedSharedPreferences` when the Android
Keystore is available. The MCP screen warns when it must fall back to
app-private plaintext storage.

## Confirmation flow

1. Gemini calls `execute_mcp_tool` with a tool ID and `arguments_json`.
2. `McpManager` checks that the tool is enabled, validates arguments, and checks
   Shizuku permission when required.
3. Safe tools execute immediately.
4. Confirm/dangerous tools return `requires_confirmation`; Jarvis stores one
   pending action locally and asks the user in the configured language.
5. The user must say an explicit yes/no. The local command parser resolves the
   pending action (`yes`, `haan`, `confirm`, `no`, `nahi`, `cancel`, etc.).
6. Only the local confirmation path calls `McpManager.executeConfirmed()`.
   Arguments are re-validated and the tool is re-resolved before execution.
7. The result is shown in the UI and spoken locally on the confirmation turn.

Safety properties:

- A pending action expires after two minutes.
- A different tool cannot replace an already-pending confirmation.
- An unrelated new utterance clears the pending action.
- Stopping the conversation/task clears the pending action.
- Silent execution mode does not suppress the confirmation prompt.
- The model is told not to retry or claim success while confirmation is
  pending.

## Troubleshooting

| Symptom | Check |
| --- | --- |
| “Shizuku app install nahi hai” | Install Shizuku from the official site. |
| “Shizuku service running nahi hai” | Start Shizuku with wireless debugging/ADB or root. |
| “Shizuku permission required” | Grant permission from the Shizuku screen; if denied permanently, allow Jarvis manually in Shizuku settings. |
| “Privileged shell service abhi ready nahi hai” | Keep Shizuku running, then tap **Re-check / Bind Privileged Service**. |
| MCP says “Shizuku permission is required” | Grant Shizuku permission and rebind; the MCP tool needs privileged access. |
| Custom MCP shows zero tools | Check the URL/token, tap **Refresh tools**, and inspect logcat tag `McpHttpClient`/`McpManager`. |
| Custom MCP returns HTTP 3xx | Use the final endpoint URL directly; Jarvis intentionally does not follow redirects. |

Runtime testing requires a device or emulator with Shizuku installed and a
running Shizuku service. Unit tests and lint cannot validate the remote binder
handshake.
