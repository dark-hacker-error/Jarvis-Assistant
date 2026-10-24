# JARVIS Assistant — Documentation

This directory is the technical documentation for the JARVIS Android voice
assistant (`com.jarvis.assistant`). It describes the code that exists in
`app/src/main/java/com/jarvis/assistant`, not an aspirational design.

**Source of truth:** the Kotlin sources and `AndroidManifest.xml`. If a document
and the code disagree, the code wins and the document must be fixed.

## Start here

| If you want to… | Read |
| --- | --- |
| Understand the system in 10 minutes | [Architecture overview](architecture/overview.md) |
| Find which file does what | [Module and package map](architecture/module-map.md) |
| Trace a voice turn, tool call or wake word | [Runtime flows](architecture/runtime-flows.md) |
| Understand screen automation | [Automation engine](architecture/automation-engine.md) |
| Know every model-facing tool | [Gemini tool reference](reference/gemini-tools.md) |
| Know every MCP tool and its risk level | [MCP reference](reference/mcp-tools.md) |
| Change settings, storage or data models | [Settings and data](reference/settings-and-data.md) |
| Build, sign or debug a build | [Build and setup](setup/build.md) |
| Review permissions and trust boundaries | [Permissions](security/permissions-and-security.md), [Threat model](security/threat-model.md) |
| Plan or prioritise refactoring | [Code health](maintenance/code-health.md), [Upgrade roadmap](maintenance/upgrade-roadmap.md) |
| See what was fixed and when | [Bug-fix log](maintenance/bugfix-log.md) |

## Reading paths

**New contributor**

1. [Overview](architecture/overview.md)
2. [Module map](architecture/module-map.md)
3. [Runtime flows](architecture/runtime-flows.md)
4. [Gemini tools](reference/gemini-tools.md)
5. [Build and setup](setup/build.md)

**Release engineer**

1. [Build and setup](setup/build.md) — signing, R8, aarch64 build-tools note
2. [Settings and data](reference/settings-and-data.md) — what is encrypted and what is not
3. [Permissions](security/permissions-and-security.md)
4. [Threat model](security/threat-model.md)

**Security reviewer**

1. [Threat model](security/threat-model.md)
2. [Permissions](security/permissions-and-security.md)
3. [MCP reference](reference/mcp-tools.md) — confirmation gate and shell guards
4. [Automation engine](architecture/automation-engine.md) — what can touch the UI

## Documentation conventions

- File references are written as `path/to/File.kt` and, where useful,
  `File.kt:123`.
- "Tool" means a Gemini Live function declaration unless prefixed with "MCP".
- Risk levels (`SAFE`, `CONFIRM`, `DANGEROUS`) come from
  `mcp/McpModels.kt` and are enforced by `McpManager`.
- Diagrams use Mermaid and render on GitHub.

## Maintaining these docs

When you change any of the following, update the matching document in the same
commit:

| Change | Document |
| --- | --- |
| Add/remove a Gemini tool | [Gemini tool reference](reference/gemini-tools.md) |
| Add/remove an MCP tool or change its risk | [MCP reference](reference/mcp-tools.md) |
| Add a settings key or screen | [Settings and data](reference/settings-and-data.md) |
| Change the tool/automation pipeline | [Runtime flows](architecture/runtime-flows.md), [Automation engine](architecture/automation-engine.md) |
| Change permissions or exported components | [Permissions](security/permissions-and-security.md), [Threat model](security/threat-model.md) |
| Bump SDK, Gradle or signing | [Build and setup](setup/build.md) |
