RepoBuddy CLI
=============

RepoBuddy 1.0.8 provides read-only project checks and MCP access for the matching IntelliJ plugin.

When installed by the RepoBuddy IntelliJ plugin, this distribution lives in your stable user
application-data directory. Open your Spring project in IntelliJ IDEA, enable local CLI and MCP
access (the Configure actions do this automatically), and run:

  repobuddy setup
  repobuddy setup codex
  repobuddy setup claude
  repobuddy check --changed
  repobuddy mcp

Adding bin to PATH is optional and is never performed silently. RepoBuddy requires Java 17 or
newer. The CLI never invokes Gradle.
