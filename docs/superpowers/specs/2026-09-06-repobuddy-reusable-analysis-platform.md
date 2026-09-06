# RepoBuddy Reusable Analysis Platform Implementation Plan

## Decision

Use an IDE-backed hybrid architecture. The existing inspections depend on IntelliJ PSI, indexes,
`Project`, `VirtualFile`, read actions, and `InspectionEngine`; rebuilding them in a standalone CLI
would create a second analysis engine. Full scans therefore remain in the plugin and are exposed
through an authenticated loopback adapter. Core models, rule metadata, filtering, summaries, JSON
contracts, CLI presentation, and MCP schemas remain IntelliJ-independent.

## Architecture

```text
AI client -> repo-buddy-mcp (stdio) --+
                                       +-> repo-buddy-ipc -> 127.0.0.1 plugin adapter
CLI ----------------------------------+                       |
                                                              v
                                                RepoBuddyApplicationService
                                                              |
                                                              v
                                                RepoBuddyIssueService
                                                              |
                                                              v
                                              existing InspectionEngine rules

All layers consume repo-buddy-core contracts.
```

The Gradle root remains the IntelliJ plugin. `repo-buddy-core`, `repo-buddy-ipc`,
`repo-buddy-cli`, and `repo-buddy-mcp` are separate Java 17 modules. The existing `agent` module
continues to provide runtime repository execution and is not used as MCP or analysis infrastructure.

## Implementation sequence

1. Add portable issue, rule, scan, project, context, pagination, error, and application-service contracts.
2. Attach stable rule IDs and source columns to findings emitted by the existing inspection scanner.
3. Evolve `RepoBuddyIssueService` with joinable project scans while retaining its UI cache role.
4. Normalize IntelliJ findings into portable issues and retain bounded in-memory scan snapshots.
5. Add canonical-path and symlink-safe context lookup for known issue IDs only.
6. Add the opt-in, token-authenticated loopback IPC server with project-bound sessions.
7. Implement CLI commands for scans, issues, individual issues, context, rules, project info, and project selection.
8. Implement six read-only MCP tools over the same application service.
9. Add serialization, filtering, CLI, MCP, scanner-regression, lifecycle, and security tests.
10. Update user and architecture documentation; run all plugin and module verification tasks.

## Security defaults

- Integration disabled by default and bound only to `127.0.0.1` when enabled.
- Random 256-bit project token and project identity required for every request.
- 64 KB request, 1 MB response, 200-issue page, 50-line context, and 32 KB source limits.
- Project-relative issue paths; real-path containment validation prevents traversal and symlink escape.
- No write, quick-fix, shell, arbitrary file, environment, or credential tools.
- Source is not uploaded by RepoBuddy, though connected AI clients may transmit requested context to
  their configured provider.

## Acceptance verification

Run module tests, the full existing test suite, CLI/MCP builds, `build`, `buildPlugin`, project
configuration verification, and Plugin Verifier. Inspect the packaged plugin for the embedded agent,
exercise the CLI against `runIde`, and finish with `git diff --check` and `git status --short`.

Future work is limited to SARIF, CI-specific integrations, file rescans, issue validation, and
explicitly controlled RepoBuddy-owned quick fixes. Generic writes and shell execution remain out of scope.
