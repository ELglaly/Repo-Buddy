# RepoBuddy reusable analysis architecture

RepoBuddy analysis remains IntelliJ-backed because its inspections execute over Java PSI through
IntelliJ's `InspectionEngine`. The CLI and MCP server do not contain inspection implementations.

## Boundaries

- `repo-buddy-core` owns stable DTOs, rule metadata, filtering, summaries, IDs, JSON, and the
  `RepoBuddyApplicationService` interface. It has no IntelliJ dependencies.
- The root plugin owns PSI traversal, inspection execution, project/index lifecycle, finding
  normalization, scan snapshots, and bounded source context.
- `repo-buddy-ipc` owns the versioned local protocol, session discovery, and application-service client.
- `repo-buddy-cli` owns argument parsing, human/JSON rendering, and process exit codes.
- `repo-buddy-mcp` owns MCP schemas and request/error adaptation only.

## Finding lifecycle

`RepoBuddyInspectionScanner` runs the five existing inspections and produces an internal finding with
navigation data. `IntelliJRepoBuddyApplicationService` maps it to a portable `RepoBuddyIssue` with a
rule ID, severity, project-relative path, location, explanation, recommendation, and opaque stable ID.
`RepoBuddyIssueService` remains the shared project cache used by the Issues panel, editor banner, and
status widget. Concurrent full scan requests join one future instead of starting duplicate scans.

## Local isolation

The integration is opt-in. It listens on an ephemeral `127.0.0.1` port and advertises project sessions
through a per-user runtime/cache directory. Each project receives an independent random token. The
server checks both token and project ID, invalidates access when a project closes, bounds messages, and
never binds publicly.

MCP clients discover active projects through `repobuddy_list_projects` and use the returned stable ID
as `projectId`. Unique display names and canonical roots remain compatibility selectors, but unknown
selectors return `REPOBUDDY_PROJECT_NOT_FOUND` and ambiguous selectors return
`REPOBUDDY_PROJECT_AMBIGUOUS`; they are not reported as an IDE outage. Issue retrieval is paginated
to bounded MCP pages with `scanId`, `offset`, `nextOffset`, and `hasMore` continuation metadata.

Context retrieval accepts only a finding ID from the latest selected scan. The plugin resolves the
stored relative path against the canonical project root, follows the candidate to its real path, rejects
escapes, and returns at most 50 surrounding lines and 32 KB.

## Capability differences

`rules` and basic build-file-based `project-info` can run without IntelliJ. Full scans, stored issue
queries, and source context require the matching project to be open in IntelliJ with local integration
enabled. RepoBuddy reports that limitation explicitly and never substitutes a partial standalone scan.
