# RepoBuddy

<div align="center">

![Build](https://img.shields.io/badge/build-passing-brightgreen?style=for-the-badge&logo=gradle)
![Version](https://img.shields.io/badge/version-1.0.8-blue?style=for-the-badge)
![IntelliJ](https://img.shields.io/badge/IntelliJ-2023.2%2B-orange?style=for-the-badge&logo=intellij-idea)
![Java](https://img.shields.io/badge/Java-17%2B-red?style=for-the-badge&logo=openjdk)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-2.7%2B-6DB33F?style=for-the-badge&logo=springboot)

**Run any Spring Data JPA repository method directly from the editor — no test, no REST client, no redeploy.**

[Features](#-features) · [Installation](#-installation) · [Usage](#-usage) · [Architecture](#-architecture) · [Contributing](#-contributing)

</div>

---

## The Problem

You're debugging a data-layer issue in a Spring Boot app. To verify a single repository query, you have to:

1. Write a temporary unit test (and mock half the world)
2. Fire up Postman or curl at some proxy endpoint
3. Add a `CommandLineRunner` hack and redeploy
4. Wait. Restart. Repeat.

Every one of those paths is slow, noisy, and indirect.

**RepoBuddy eliminates all of them.**

Click the ⌕|✎ gutter icon next to any Spring Data repository method. Fill in the parameters. See the real query result, the captured SQL, and the execution time — right there in the IDE.

---

## Features

### Run Repository Methods Instantly
- **⌕|✎ Gutter icon** on every Spring Data JPA repository method — one click opens the execution popup
- **Live execution against your running app** — a lightweight Java agent plugs into your Spring Boot process; the real JPA context runs the method (no simulation, no mocking)
- **Execution time** colour-coded green / amber / red (< 100 ms / 100–499 ms / ≥ 500 ms)
- 
<img width="1061" height="287" alt="image" src="https://github.com/user-attachments/assets/fa9eb409-bd61-40e6-8b13-06c8d16d92c4" />

### Full SQL Transparency
- **SQL capture** via Hibernate's `StatementInspector` — every statement triggered by the call is intercepted, ordered, and timestamped
- See the exact queries your method generates — N+1 problems have nowhere to hide
-  
<img width="1128" height="830" alt="Screenshot 2026-04-20 211438" src="https://github.com/user-attachments/assets/41f80680-3aed-4fb3-b19e-de6849edb941" />


### Smart Parameter Input
- **Type-aware parameter form** — input widgets generated per parameter type
- **Supported types:** `String`, `Long`, `Integer`, `Boolean`, `Double`, `Float`, `Short`, `Byte`, `BigDecimal`, `UUID`, `LocalDate`, `LocalDateTime`, `ZonedDateTime`, `Pageable` / `PageRequest` (with sort), `Sort`, `Enum`, `List<String>`, `List<Long>`, and any arbitrary JSON object
- **Pageable / PageRequest** — JSON-driven input with page, size, and sort direction; deserialized into a real `PageRequest` on the agent side (no Jackson abstract-type crash)
- **Enum reflection** — constants are resolved against the app's loaded classes; falls back gracefully to the first constant with a helpful log listing all available values

### Random Data Generation
- **Generate All** button fills every parameter field in one click with type-aware sample data
- **Per-field dice buttons** for randomising individual parameters
- **30+ name-hint rules** — recognises hints like `email`, `phone`, `city`, `status`, `uuid`, and maps them to realistic values automatically

<img width="1132" height="833" alt="Screenshot 2026-04-20 210150" src="https://github.com/user-attachments/assets/444253ff-7c50-4722-acf7-9999da3db34e" />

### Call Chain Tracer
- Pick any Spring MVC endpoint from the combo box and trace every repository method reachable from it
- Results render as an **expandable tree** with double-click navigation to source
- **HTTP method badges** colour-coded (GET = green, POST = blue, PUT = amber, DELETE = red)
- **Summary bar** counts READ / WRITE / `@Transactional` calls and unique entity types touched
- Expand All / Collapse All / Clear Cache controls built in
- **Endpoint search** — type any HTTP verb (`POST`, `GET`) or path fragment (`/users`, `/login`) to filter the endpoint dropdown live as you type; the list opens automatically with matching results
- **Full API paths** — endpoint paths now include the controller-level `@RequestMapping` prefix so you see the complete URL (e.g. `/api/v1/users/{id}` instead of `/{id}`)
- **Clean endpoint display** — the endpoint selector shows only `[VERB] /path` without the controller class name and method signature

<img width="1819" height="907" alt="Screenshot 2026-04-20 210029" src="https://github.com/user-attachments/assets/4f29b4a6-30b8-41cb-8b66-5c682be97902" />
<img width="1824" height="903" alt="Screenshot 2026-04-20 210112" src="https://github.com/user-attachments/assets/2bb68816-07b5-4b89-8498-ed44607b2ffa" />

### Repository Usage Table
- Lists **all repository methods** with call-count badges (red = never called, amber = rarely called, green = frequently called)
- **Live search** filters by repository or method name
- **Show Unused Only** toggle to focus instantly on dead code
- **Current File filter** — open any repository file (e.g. `UserRepository.java`) and click **Current File** to scope the table to that repository's methods instantly; no manual search needed
- **Export to CSV** with one click
- Table auto-populates on panel open — no manual Refresh required on startup
<img width="1837" height="866" alt="Screenshot 2026-04-20 205823" src="https://github.com/user-attachments/assets/3a40bef9-1f30-4ab5-b469-0608183b9440" />


### Code Inspections

RepoBuddy ships five inspections for common Spring Data JPA pitfalls. By default they run in
**panel-only mode**: findings are collected into the dedicated **Issues** tab (see below) and
counted by the editor banner and status-bar indicator, instead of adding inline editor underlines
or **Problems**-view entries. Turn panel-only mode off under **Settings → Tools → RepoBuddy** to
make them behave as ordinary editor inspections (inline underlines + Problems view); each one is
then also configurable under **Settings → Editor → Inspections → RepoBuddy**.

- **Unsafe `@Query`** — flags `@Query` declarations that are likely to misbehave:
  - **Missing `@Param`** — a named bind parameter (e.g. `:name`) with no matching
    `@Param("name")` and no parameter literally named `name`; binding fails at runtime
    unless the project is compiled with `-parameters`. A quick-fix adds the annotation
    when the target parameter is unambiguous.
  - **SpEL injection surface** — the query embeds a SpEL expression (`#{...}`), which can
    interpolate untrusted input (e.g. a dynamic `ORDER BY` in a native query).
  - **String concatenation** — the `@Query` value is built with `+` instead of a single
    literal (flagged by default; disable via the inspection options).

- **Missing pagination** — flags repository methods that return an unbounded collection
  (`List`, `Set`, `Collection`, `Iterable`, `Stream`) with no pagination, which can load
  an entire table into memory. A quick-fix changes the return type to `Page<T>` and adds a
  `Pageable` parameter. Suppressed when the method already takes a `Pageable`/`Sort`,
  returns `Page`/`Slice`, is name-limited (`findFirstBy…`/`findTop10By…`), or its `@Query`
  contains a `LIMIT`. By default only "find all"-style methods are reported.

- **Missing `@Transactional`** — flags methods that write to the database without
  transactional context: a Spring Data `@Modifying` query method that isn't
  `@Transactional`, a method body that calls `persist`/`merge`/`remove`/`flush` or
  `Query.executeUpdate()` outside a transaction, and methods that call repository
  `save`/`delete`/`update`. A quick-fix annotates the method with `@Transactional`. Both
  Spring and Jakarta/`javax` `@Transactional` are recognised; private methods are ignored
  by default.

- **Potential N+1 query** — flags access to a lazy JPA association on a loop variable inside
  a `for-each` loop, which issues a separate `SELECT` per iteration. Fires only when the loop
  variable is an `@Entity` and the property is a clearly lazy association
  (`@OneToMany`/`@ManyToMany`, or `@ManyToOne`/`@OneToOne` with `fetch = LAZY`). Associations
  with a large enough Hibernate `@BatchSize` are ignored (configurable). Report-only — the fix
  (`JOIN FETCH` or `@EntityGraph`) belongs in the loading query.

- **`@Transactional` self-invocation** — flags a call to a `@Transactional` method from another
  method of the same class through `this` (or no qualifier). Spring's proxy-based transaction
  support is bypassed on self-invocation, so the callee's propagation / isolation / rollback
  settings silently do not apply. Report-only — route the call through an injected self-reference
  or move the method to another bean.

#### Issues Panel & Indicators

- **Issues tab** in the RepoBuddy tool window lists every finding across the project — or just the
  open file via the **Current file** toggle — with live search, sortable columns, double-click
  navigation to source, and CSV export.
- **Editor banner** at the top of any file with findings shows `RepoBuddy: N issues in this file`
  with a one-click link to the Issues tab (dismissible per file).
- **Status-bar counter** shows how many RepoBuddy issues are in the current file; click it to open
  the Issues tab.
- All three read from a single shared background scan, so they stay in sync and never re-analyze a
  file three times.

### Zero Configuration
- **Runtime-only `-javaagent` injection** — RepoBuddy adds its agent to the in-memory Java command line immediately before launch, never to shared run-configuration files
- **Safe legacy cleanup** — persisted agent flags from older releases are removed without touching third-party Java agents
- No extra Maven / Gradle dependencies needed in your project
- **Live agent status indicator** — the popup header shows `● Agent Ready` / `● Offline` so you know at a glance whether the agent is reachable before hitting Run

### Theme-Aware UI
- Unified `UITheme` palette built on `JBColor` — looks great in both IntelliJ Light and Dark themes with zero configuration

---

## Installation

### From JetBrains Marketplace

1. Open IntelliJ IDEA
2. Go to **Settings → Plugins → Marketplace**
3. Search for **RepoBuddy**
4. Click **Install** and restart the IDE

### Install and configure the companion CLI

The Marketplace plugin bundles the matching RepoBuddy CLI. Open **Settings | Tools | RepoBuddy**
or **Tools | RepoBuddy Integration**, then choose **Configure Codex** or
**Configure Claude Code**. RepoBuddy installs the CLI into your user application-data directory,
enables its authenticated local integration, registers the read-only MCP server through the
selected client's official CLI, and verifies the result.

Use **Install RepoBuddy CLI** when you only want the command-line tools. Terminal PATH setup is an
optional guided step; RepoBuddy never edits shell profiles or the Windows user environment.
The standalone `repobuddy-cli-<version>.zip` remains available for machines without the plugin.

### Build from Source

**Prerequisites:**
- JDK 17+
- IntelliJ IDEA 2024.1+ (Community or Ultimate)
- Gradle (wrapper included)

```bash
# Clone the repository
git clone https://github.com/elglaly/RepoBuddy.git
cd RepoBuddy

# Build the plugin
./gradlew buildPlugin

# Run in a sandboxed IntelliJ instance (for development)
./gradlew runIde
```

The built plugin `.zip` will appear in `build/distributions/`. Install it via **Settings → Plugins → Install Plugin from Disk**.

---

## Usage

### 1. Open Your Spring Boot Project

RepoBuddy injects its Java agent only into the in-memory Java command line when a supported application is launched. The local agent path is never saved in `.run` or `.idea/runConfigurations` files. Enable or disable this from **Settings | Tools | RepoBuddy | Enable RepoBuddy Java agent**.

### 2. Start Your Application

Run your Spring Boot app from IntelliJ as usual (▶ Run or Debug). The embedded agent binds to a free port and announces itself — you'll see `● Agent Ready` in the RepoBuddy popup once it's up.

### 3. Click the Gutter Icon

Any method in a Spring Data JPA repository interface gets ⌕|✎ a  run marker in the gutter:

```java
public interface UserRepository extends JpaRepository<User, Long> {

    // ⌕|✎ ← click this
    List<User> findByEmailAndStatus(String email, UserStatus status);

    // ⌕|✎ ← or this
    Page<User> findAllByCreatedAtAfter(LocalDate date, Pageable pageable);
}
```

### 4. Fill in Parameters and Run

The parameter form opens automatically. Each field is typed to match the method signature:

```json
// Pageable example — enter JSON directly
{
  "page": 0,
  "size": 10,
  "sort": "createdAt,desc"
}
```

Hit **Generate All** to auto-fill every field with realistic sample data, or use the dice button next to individual fields.

### 5. Inspect the Results

The popup shows:

- **JSON result** — the serialized return value of the method
- **SQL log** — every Hibernate statement generated, in order, with timestamps
- **Execution time** — colour-coded for instant performance feedback

### Trace an Endpoint's Repository Calls

Right-click anywhere inside a Spring MVC controller method → **Trace Repository Calls for This API**. The call chain tree appears in the RepoBuddy tool window at the bottom of the IDE.

---

## Architecture

RepoBuddy keeps the IntelliJ plugin at the Gradle root and separates reusable integration modules.
The MCP adapter is an internal library bundled into the single CLI executable:

```text
repo-buddy-cli -> repo-buddy-mcp -> repo-buddy-ipc -> IntelliJ loopback bridge
       |                |                |                    |
       +----------------+----------> repo-buddy-core          v
                                                    RepoBuddyIssueService
                                                             |
                                                             v
                                                    existing PSI inspections
```

The existing embedded `agent` remains dedicated to repository execution and SQL capture.

### How It Works

```
IntelliJ Plugin                          Spring Boot App (your app)
─────────────────                        ─────────────────────────────────────
1. RepoBuddyJavaProgramPatcher adds       repoBuddy-agent.jar is added to the
   the agent at execution time       ───▶  in-memory JVM command line

2. User clicks ⌕|✎ gutter icon
   → RepoRunnerPopup opens

3. User fills parameters, clicks Run
   → RepoExecutionClient sends HTTP  ───▶ RepoBuddyAgentServer receives request
     POST /execute with JSON body
                                          RepoExecutionService resolves the
                                          repository bean from Spring context,
                                          converts parameters, invokes the method

                                          SqlCapturingInterceptor (Hibernate
                                          StatementInspector) captures all SQL

4. Plugin receives ExecutionResult  ◀─── Agent serializes result + SQL log
   → ResultPanel renders JSON             and returns HTTP response
   → SqlLogPanel renders SQL log
   → Execution time badge updates
```

The agent JAR is embedded inside the plugin JAR at `/agent/repoBuddy-agent.jar` and extracted to the system temp directory at runtime — no external download required.

### RepoBuddy CLI

The installation and IntelliJ configuration flow is described above. Once installed, run:

```text
repobuddy setup
repobuddy check
repobuddy check --changed
repobuddy status
repobuddy doctor
```

`check` requests all RepoBuddy findings. `check --changed` reports only findings introduced by the
current Git changes and fails clearly when Git context is unavailable. Use `--project <directory>`
from outside the project; otherwise RepoBuddy walks upward from the current directory to the nearest
Git, Maven, or Gradle root.

Advanced read-only commands remain available through the same executable: `issues`, `issue`,
`context`, `rules`, `project-info`, and `projects`. JSON is written to stdout with `--format json`;
diagnostics are written to stderr. Exit code `1` means the selected `--fail-on` threshold was met.
Codes `2`–`8` cover command, project, analysis, integration, authentication, lifecycle, and internal
failures.

### AI agent and MCP integration

RepoBuddy exposes a local, read-only MCP server for coding agents. The server does not edit
source code: the agent makes edits in its normal workspace, then asks RepoBuddy to check the
current changes.

#### One-time IntelliJ setup

The **Configure Codex** and **Configure Claude Code** actions install or update the bundled CLI,
enable local CLI/MCP access, and verify registration. The target project must remain open while the
agent uses MCP.

#### Register the server

The MCP server is part of the same CLI distribution and starts with `repobuddy mcp`. MCP clients
normally start this process themselves; do not leave a separate server running in a terminal.

#### Claude Code

Configure Claude Code at user scope through its official CLI (or the matching IntelliJ action):

```text
repobuddy setup claude
```

Use `--dry-run` to inspect the command. RepoBuddy asks before replacing an existing registration
from IntelliJ, or requires `--replace` at the command line. It does not directly edit Claude
configuration files.

#### Codex CLI

Configure Codex through its official CLI (or the matching IntelliJ action):

```text
repobuddy setup codex
```

Use `--dry-run` to inspect the command. RepoBuddy refuses to overwrite an existing `repobuddy`
registration unless `--replace` is supplied. It never edits Codex configuration files directly.

#### Other MCP clients

Run `repobuddy setup generic` to print JSON containing the exact absolute command and arguments for
this installed distribution. Merge the generated `repobuddy` entry into the client's existing MCP
configuration; RepoBuddy does not rewrite that file.

It exposes `repobuddy_list_projects`, `repobuddy_scan_project`, `repobuddy_list_issues`,
`repobuddy_get_issue`, `repobuddy_get_issue_details`, `repobuddy_get_issue_context`,
`repobuddy_get_project_info`, `repobuddy_get_rules`, and `repobuddy_get_rule_details`. Change-aware clients can also call `repobuddy_get_issues` with an explicit
`scope` of `all` or `changed`, and `repobuddy_check_changes`. Use the stable `id` returned by
`repobuddy_list_projects` as `projectId`;
the display name is not the project identity. If the MCP process is started inside a project and
exactly one matching IntelliJ project is open, `projectId` may be omitted.

The recommended AI workflow is:

```text
1. repobuddy_list_projects
2. repobuddy_scan_project → save scanId
3. repobuddy_list_issues with scanId, limit <= 25, and offset 0
4. repobuddy_get_issue for a compact finding summary; call repobuddy_get_issue_details only when explanation or remediation is needed
5. Repeat with nextOffset while hasMore is true
```

For an AI repair loop, call `repobuddy_check_changes`. Stop when `passed` is true. Otherwise use
the returned issues or `repobuddy_get_issues` with `scope: "changed"`, fix only the introduced
findings, and check again. Pre-existing findings are intentionally excluded; do not change them
unless the user explicitly requests an all-issues repair. Preserve the user's intended behavior
rather than reverting legitimate functionality merely to silence an inspection.

You can give the agent this instruction once setup is complete:

```text
After every code change, call repobuddy_check_changes. If passed is false, inspect only the
returned INTRODUCED findings, fix the smallest safe change, and call repobuddy_check_changes again.
Do not modify PRE_EXISTING findings unless I explicitly ask for an all-issues review. Stop when
introducedIssueCount is zero, and preserve the intended behavior of the patch.
```

Issue and rule pages are deliberately bounded so an AI client does not request hundreds of results in one
response. Rule pages accept `limit` and `offset` (1–25) and include `total`, `offset`, `nextOffset`,
`hasMore`, and compact rule summaries. `repobuddy_get_issue` never includes explanation or remediation;
use its detail companion for those bounded fields. Detail and source-context responses expose
`truncated: true` when shortened to stay within the 1 KB MCP response budget.
MCP delegates to the same application service as the CLI and does not execute shell commands or
implement analysis.

### Security, privacy, and troubleshooting

The local endpoint binds only to `127.0.0.1`, requires a random project-bound session token, bounds
requests and responses, and rejects cross-project access. Context can only be requested by a valid
issue ID; canonical real-path checks reject traversal and symlink escapes. No CLI or MCP command edits
source, applies fixes, reads arbitrary paths, or exposes environment variables and credentials.

RepoBuddy itself does not upload source. A connected AI client may send explicitly retrieved findings
or source context to its configured AI provider; review that client's privacy settings.

If a check reports `REPOBUDDY_IDE_NOT_RUNNING`, open the matching project in IntelliJ and enable local
CLI/MCP access. For MCP, call `repobuddy_list_projects` and pass the returned `id` as `projectId`.
`REPOBUDDY_PROJECT_NOT_FOUND` means the selector was not a known project; `REPOBUDDY_PROJECT_AMBIGUOUS`
means an exact project ID is required.

---

## Changelog

### 1.0.8

- Unified CLI: `repobuddy setup`, `repobuddy check`, `repobuddy check --changed`, and `repobuddy mcp`
- Cross-platform CLI release ZIP with no Gradle dependency for end users
- Environment diagnostics and official Codex CLI registration
- Read-only MCP tools for projects, scans, issues, context, rules, and change checks
- Expanded Spring/JPA inspections with project-level findings, production-source filtering, and safer transaction analysis
- Dedicated `repobuddy-mcp` and checkout build launchers removed

### 1.0.7
- Prevented RepoBuddy from persisting machine-specific `-javaagent` paths in shared run configurations
- Added runtime-only Java agent injection for supported launches, with a setting to disable it
- Added cleanup for legacy RepoBuddy agent entries while preserving third-party Java agents and existing VM options
- Prevented duplicate RepoBuddy agent arguments across Windows, macOS, Linux, quoted paths, and paths containing spaces

### 1.0.5
- **Spring Boot 2.7+ support** — agent now auto-configures on Spring Boot 2.7.x, 3.x, and future 4.x releases (adds `spring.factories` alongside the existing `.imports` file)
- **Clean uninstall** — legacy RepoBuddy `-javaagent` flags are defensively stripped from open run configurations when dynamic unload permits it

### 1.0.4
- Fixed plugin description not appearing on JetBrains Marketplace
- Expanded IDE compatibility range: supported from IntelliJ IDEA 2023.2 with no upper build limit

### 1.0.3
- Refined the Repository Usage table with cleaner badges, softer typography, and a more polished visual layout
- Redesigned the Call Chain Tracer tree for a more professional and readable presentation
- Improved the repository runner popup UI with cleaner tabs, cards, and better visual hierarchy
- Updated the Marketplace plugin description and presentation metadata

### 1.0.2
- **Current File filter** in Repository Usage — scope the table to the repository file you have open with one click
- **Endpoint search** in Call Chain Tracer — live filter by HTTP verb or path fragment; dropdown opens automatically as you type
- **Full API paths** — controller-level `@RequestMapping` prefix is now included in all endpoint paths
- **Cleaner endpoint display** — combo box shows only `[VERB] /path`, no controller name or method signature
- Repository Usage table auto-populates on panel open

### 1.0.1
- Enhanced build configuration and random data generation strategies
- Updated settings and plugin files for improved documentation and versioning

### 1.0.0
- Initial release — see [Features](#-features) for the full list

---

## Requirements

| Requirement | Version |
|---|---|
| IntelliJ IDEA (Community or Ultimate) | 2023.2+ |
| Java / JDK | 17+ |
| Spring Boot | 2.7.x, 3.x, 4.x+ |
| Spring Data JPA | on classpath |
| App must be running locally | started from IntelliJ |

---

## Contributing

Contributions are welcome. Here's how to get started:

### Development Setup

```bash
git clone https://github.com/elglaly/RepoBuddy.git
cd RepoBuddy
./gradlew runIde   # launches a sandboxed IntelliJ with the plugin installed
```

Developer verification uses the root Gradle Wrapper:

```bash
./gradlew test
./gradlew buildPlugin
./gradlew :repo-buddy-cli:cliDistZip
./gradlew verifyRelease
```

These are contributor commands. End users install the release ZIP and run `repobuddy`; they do not
invoke Gradle.

### Submitting a Pull Request

1. Fork the repo and create your branch from `main`
2. Make your changes — keep commits focused and atomic
3. Run `./gradlew buildPlugin` and verify the build is green
4. Open a PR with a clear description of what changes and why

### Reporting Issues

Please include:
- IntelliJ IDEA version
- Spring Boot version of the project you're testing against
- Steps to reproduce
- The full stack trace from **Help → Show Log in Explorer** if relevant

---

<div align="center">

Built with care by [Sherif Elglaly](https://elglaly.github.io/Sherif-Elglaly/) · [Plugin Page](https://plugins.jetbrains.com/plugin/31285-repobuddy) · [Report an Issue](https://github.com/elglaly/RepoBuddy/issues)

</div>
