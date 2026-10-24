# Project documentation

- [Architecture overview](architecture/overview.md): runtime components, voice and typed-chat flows, automation, vision, and resilience.
- [Module and package map](architecture/module-map.md): one-module layout, key classes, manifest components, resources.
- [Build and setup](setup/build.md): JDK/SDK requirements, debug/release builds, tests, lint, signing properties.
- [Permissions and security](security/permissions-and-security.md): runtime permissions, accessibility, screen capture, API-key storage, release pinning.
- [Shizuku and MCP tools](features/shizuku-and-mcp.md): privileged system access, free built-in MCPs, custom HTTP MCPs, confirmation flow, troubleshooting.
- [Bug-fix log](maintenance/bugfix-log.md): all fixes from the 2026-10-23 pass with file references and verification.
- [Bug-fix checklist](bugfix-todo.md): working checklist for the same pass.
- [Voice architecture research](architecture/voice-architecture-research.md): background service behavior, interaction states, and Android platform constraints.
- [Voice issue recordings](diagnostics/voice-videos/README.md): user-provided recordings associated with reported voice and automation issues.

Keep durable project guidance here. Generated APKs, Gradle caches, and local machine configuration belong outside source documentation and are ignored by Git.
