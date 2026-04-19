# RepoBuddy

<div align="center">

![Build](https://img.shields.io/badge/build-passing-brightgreen?style=for-the-badge&logo=gradle)
![Version](https://img.shields.io/badge/version-3.0--SNAPSHOT-blue?style=for-the-badge)
![License](https://img.shields.io/badge/license-MIT-green?style=for-the-badge)
![IntelliJ](https://img.shields.io/badge/IntelliJ-2024.1%2B-orange?style=for-the-badge&logo=intellij-idea)
![Java](https://img.shields.io/badge/Java-17%2B-red?style=for-the-badge&logo=openjdk)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.x-6DB33F?style=for-the-badge&logo=springboot)

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

Click the ▶ gutter icon next to any Spring Data repository method. Fill in the parameters. See the real query result, the captured SQL, and the execution time — right there in the IDE.

---

## Features

### Run Repository Methods Instantly
- **▶ Gutter icon** on every Spring Data JPA repository method — one click opens the execution popup
- **Live execution against your running app** — a lightweight Java agent plugs into your Spring Boot process; the real JPA context runs the method (no simulation, no mocking)
- **Execution time** colour-coded green / amber / red (< 100 ms / 100–499 ms / ≥ 500 ms)
- 
<img width="1061" height="287" alt="image" src="https://github.com/user-attachments/assets/fa9eb409-bd61-40e6-8b13-06c8d16d92c4" />

### Full SQL Transparency
- **SQL capture** via Hibernate's `StatementInspector` — every statement triggered by the call is intercepted, ordered, and timestamped
- See the exact queries your method generates — N+1 problems have nowhere to hide
-  
<img width="1103" height="828" alt="Screenshot 2026-04-16 092400" src="https://github.com/user-attachments/assets/b0dcbb77-07d9-4b4c-babe-1a256e5262a2" />


### Smart Parameter Input
- **Type-aware parameter form** — input widgets generated per parameter type
- **Supported types:** `String`, `Long`, `Integer`, `Boolean`, `Double`, `Float`, `Short`, `Byte`, `BigDecimal`, `UUID`, `LocalDate`, `LocalDateTime`, `ZonedDateTime`, `Pageable` / `PageRequest` (with sort), `Sort`, `Enum`, `List<String>`, `List<Long>`, and any arbitrary JSON object
- **Pageable / PageRequest** — JSON-driven input with page, size, and sort direction; deserialized into a real `PageRequest` on the agent side (no Jackson abstract-type crash)
- **Enum reflection** — constants are resolved against the app's loaded classes; falls back gracefully to the first constant with a helpful log listing all available values

### Random Data Generation
- **Generate All** button fills every parameter field in one click with type-aware sample data
- **Per-field dice buttons** for randomising individual parameters
- **30+ name-hint rules** — recognises hints like `email`, `phone`, `city`, `status`, `uuid`, and maps them to realistic values automatically

<img width="1645" height="667" alt="Screenshot 2026-04-16 092235" src="https://github.com/user-attachments/assets/58114092-75f2-4e27-9b3b-2320987d6e71" />

### Call Chain Tracer
- Pick any Spring MVC endpoint from the combo box and trace every repository method reachable from it
- Results render as an **expandable tree** with double-click navigation to source
- **HTTP method badges** colour-coded (GET = green, POST = blue, PUT = amber, DELETE = red)
- **Summary bar** counts READ / WRITE / `@Transactional` calls and unique entity types touched
- Expand All / Collapse All / Clear Cache controls built in

   <img width="1640" height="605" alt="Screenshot 2026-04-16 092434" src="https://github.com/user-attachments/assets/c8eac4ab-f88c-4f9f-b4ce-431fee92c621" />

  <img width="1633" height="626" alt="Screenshot 2026-04-16 092552" src="https://github.com/user-attachments/assets/d2f45bda-2504-4c65-a040-a7ce8cef0b85" />

### Repository Usage Table
- Lists **all repository methods** with call-count badges (red = never called, amber = rarely called, green = frequently called)
- **Live search** filters by repository or method name
- **Show Unused Only** toggle to focus instantly on dead code
- **Export to CSV** with one click
<img width="1645" height="667" alt="Screenshot 2026-04-16 092235" src="https://github.com/user-attachments/assets/34bb1f53-c629-4976-8f9b-03c8942e7ac4" />


### Zero Configuration
- **Automatic `-javaagent` injection** into your run configurations on project open — implemented via IntelliJ's `ProjectActivity` API (compatible with 2024.1+)
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

RepoBuddy detects Spring Boot run configurations automatically on project open and patches them with the `-javaagent` flag. No manual setup required.

### 2. Start Your Application

Run your Spring Boot app from IntelliJ as usual (▶ Run or Debug). The embedded agent binds to a free port and announces itself — you'll see `● Agent Ready` in the RepoBuddy popup once it's up.

### 3. Click the Gutter Icon

Any method in a Spring Data JPA repository interface gets a ▶ run marker in the gutter:

```java
public interface UserRepository extends JpaRepository<User, Long> {

    // ▶ ← click this
    List<User> findByEmailAndStatus(String email, UserStatus status);

    // ▶ ← or this
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

RepoBuddy is a two-module Gradle project:

```
RepoBuddy/
├── src/                            # IntelliJ plugin module
│   └── main/java/com/repoinspector/
│       ├── actions/                # IDE action (right-click menu)
│       ├── analysis/               # Static analysis services
│       │   ├── api/                # Service interfaces
│       │   ├── impl/               # Default implementations
│       │   ├── CallChainAnalyzer   # Endpoint → repository traversal
│       │   ├── CallChainCache      # Result caching
│       │   ├── CallSiteAnalyzer    # Call site resolution
│       │   ├── EndpointFinder      # Spring MVC endpoint discovery
│       │   ├── RepositoryFinder    # Spring Data repo discovery
│       │   └── RepositoryOperationClassifier
│       ├── constants/              # Spring annotation & SQL keyword constants
│       ├── gutter/                 # Gutter icon marker provider
│       ├── model/                  # Domain models (CallChainNode, EndpointInfo, …)
│       ├── runner/                 # Execution subsystem
│       │   ├── model/              # Request/response DTOs
│       │   ├── service/            # Parameter extraction, Spring URL resolution
│       │   ├── startup/            # AgentRunConfigPatcher (ProjectActivity)
│       │   └── ui/                 # Execution popup panels
│       └── ui/                     # Tool window panels (RepoInspector, CallChain)
│
└── agent/                          # Embedded Java agent module
    └── src/main/java/com/repoinspector/agent/
        ├── AgentPremain            # Java agent entry point (Premain-Class)
        ├── config/                 # Spring auto-configuration for agent beans
        ├── dto/                    # Shared DTOs (ExecutionRequest, ExecutionResult)
        ├── server/                 # HTTP server (receives execution requests from plugin)
        ├── service/                # Repository method invocation, parameter conversion
        └── sql/                    # Hibernate StatementInspector + SQL log store
```

### How It Works

```
IntelliJ Plugin                          Spring Boot App (your app)
─────────────────                        ─────────────────────────────────────
1. AgentRunConfigPatcher patches          repoBuddy-agent.jar is added as
   your Run Config on project open  ───▶  -javaagent at JVM startup

2. User clicks ▶ gutter icon
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

---

## Requirements

| Requirement | Version |
|---|---|
| IntelliJ IDEA (Community or Ultimate) | 2024.1+ |
| Java / JDK | 17+ |
| Spring Boot | 3.x |
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

## License

MIT License — see [LICENSE](LICENSE) for details.

---

<div align="center">

Built with care by [Sherif Elglaly](https://elglaly.github.io/Sherif-Elglaly/) · [Plugin Page](https://plugins.jetbrains.com) · [Report an Issue](https://github.com/elglaly/RepoBuddy/issues)

</div>
