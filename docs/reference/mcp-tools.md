# MCP Reference

JARVIS ships a small on-device MCP platform. "MCP" here means the tool model
(servers → tools → JSON arguments → result), not an external cloud account.
Two kinds of servers exist:

| Kind | Source | Transport |
| --- | --- | --- |
| Built-in | `mcp/BuiltInMcpRegistry.kt` | In-process, or `ShizukuManager.runCommand()` |
| Custom | User-added URL + optional bearer token | Streamable HTTP JSON-RPC (`McpHttpClient`) |

## Risk model

| Risk | Execution rule |
| --- | --- |
| `SAFE` | Executes immediately after argument validation. |
| `CONFIRM` | Returns `requires_confirmation`; executes only after a local spoken/typed "yes". |
| `DANGEROUS` | Same gate as `CONFIRM`, plus a more explicit description; irreversible operations. |

The gate lives in `McpManager.executeTool()`. Only
`McpManager.executeConfirmed(pending)` can run a non-`SAFE` tool, and only
`JarvisConversationService` calls it, after `CommandParser` recognises a
confirmation.

Pending actions:

- stored in `JarvisConversationService.pendingMcpAction`,
- expire after 2 minutes,
- cannot be replaced by a different tool call,
- are cleared by "no", "stop", session stop, or expiry,
- re-check the tool's current risk and Shizuku permission at execution time.

## Built-in servers (5 servers, 24 tools)

### `system_control` — System Control

| Tool | Risk | Shizuku | Arguments | Notes |
| --- | --- | --- | --- | --- |
| `system_status` | SAFE | no | — | Model, Android version, battery, uptime, Shizuku status. |
| `power` | DANGEROUS | yes | `action` = `reboot`/`shutdown` | Always requires confirmation. |
| `system_setting` | CONFIRM | yes | `setting`, `value` | Wi-Fi, Bluetooth, airplane mode, DND, rotation. |
| `volume` | SAFE | no | `level` 0–100, `stream` = music/ring/alarm | AudioManager. |
| `brightness` | SAFE | yes | `percent` 1–100 | `settings put system screen_brightness`. |
| `open_panel` | SAFE | no | `panel` = settings/wifi/bluetooth/battery | Intent. |
| `shell` | DANGEROUS | yes | `command`, `timeout_ms` ≤ 60000 | Guarded; see below. |

### `app_manager` — App Manager

| Tool | Risk | Shizuku | Arguments |
| --- | --- | --- | --- |
| `list_apps` | SAFE | no | `filter` = all/third_party |
| `app_info` | SAFE | no | `package` |
| `force_stop` | CONFIRM | yes | `package` |
| `clear_data` | DANGEROUS | yes | `package` |
| `disable` | DANGEROUS | yes | `package` (system apps rejected) |
| `enable` | CONFIRM | yes | `package` |

### `file_manager` — File Manager

| Tool | Risk | Shizuku | Arguments |
| --- | --- | --- | --- |
| `list_files` | CONFIRM | yes | `path` under `/sdcard` or `/storage/emulated/0` |
| `delete_path` | DANGEROUS | yes | `path`; `rm -rf`, irreversible |
| `disk_usage` | SAFE | no | — |

Paths are canonicalised and must stay inside the shared-storage root; symlink
escapes are rejected.

### `network_tools` — Network Tools

| Tool | Risk | Shizuku | Arguments |
| --- | --- | --- | --- |
| `network_status` | SAFE | no | — |
| `ping` | SAFE | yes | `host` (3 pings) |
| `wifi_status` | SAFE | yes | — |
| `dns_lookup` | SAFE | no | `host` |

Hostnames are validated against `^[A-Za-z0-9.:-]+$` and may not start with `-`.

### `device_insights` — Device Insights

| Tool | Risk | Shizuku |
| --- | --- | --- |
| `battery_status` | SAFE | no |
| `storage_status` | SAFE | no |
| `uptime` | SAFE | no |
| `device_info` | SAFE | no |

## Shell guards

`BuiltInMcpExecutor.validateShellCommand()` rejects:

| Pattern | Reason |
| --- | --- |
| `rm -rf /`, `/system`, `/data`, `/vendor`, `/product`, `/odm`, `/sdcard`, `/storage/emulated/0` | Destructive root/storage wipe |
| `reboot`, `shutdown`, `poweroff` | Must use `system_control:power` (which has its own confirmation) |
| `dd ... of=/dev/block/*`, `of=/dev/mmcblk*`, `of=/dev/sda*` | Raw block-device writes |
| Command > 2,000 chars | Bound |

Execution limits in `JarvisShellService`: 60 s timeout, 20,000 chars of output.
The service runs in Shizuku's privileged process (`uid 2000` or `uid 0`), not in
the app process.

## Custom MCP servers

### Adding a server

`McpManager.addCustomServer(name, url, token)` validates:

- name 1–60 chars, no control characters,
- URL must be `https://`, or `http://` only for `localhost`/`127.0.0.1`/`::1`,
- no userinfo (`user:pass@`) in the URL,
- port 1–65535,
- no duplicate URL,
- token must be a single line.

Servers are stored **disabled** by default; the user enables them explicitly.

### Protocol

`McpHttpClient` performs, per operation:

1. `initialize` with client info and protocol version,
2. `notifications/initialized`,
3. `tools/list` (paginated via `cursor`, bounded by `MAX_TOOLS`/`MAX_TOOL_PAGES`),
   or `tools/call` with `{name, arguments}`,
4. response parsing for JSON and SSE (`text/event-stream`).

Redirects are disabled (`followRedirects(false)`, `followSslRedirects(false)`) so
a server cannot bounce the bearer token to another host. Timeouts: 12 s connect,
25 s read/write.

### Risk of custom tools

All custom tools are forced to `McpRisk.CONFIRM`, regardless of what the server
claims. Their descriptions and schemas are untrusted input; the prompt tells the
model to treat server/tool text as data, never as instructions.

## Prompt context

`McpManager.buildPromptContext()` injects only enabled servers/tools:

- max 80 tools and 30,000 characters,
- tool line: `id | risk | args | description(≤300)`,
- custom server URLs are **not** included (they may contain query tokens),
- explicit policy text: never claim success before `success=true`, never bypass
  confirmation with another tool.

## Tool resolution

`execute_mcp_tool` accepts:

1. the exact tool ID (`system_control:volume`),
2. a bare tool name if it is unique across enabled servers,
3. `server.tool` (dots normalised to colons).

Anything else fails with a message listing the requirement to enable the server.

## Adding a built-in tool

1. Add a `tool(...)` entry in `BuiltInMcpRegistry.kt` with risk and parameters.
2. Implement the branch in `BuiltInMcpExecutor.execute()`.
3. Add validation if it takes a path, package, host or command.
4. Update this document and `docs/features/shizuku-and-mcp.md`.
5. Add a test for validation and for the confirmation gate.

There is no dynamic discovery for built-ins: the registry is the single source
of truth for the settings UI, prompt and executor.
