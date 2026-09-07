# RepoBuddy launcher migration

RepoBuddy has one public executable, `repobuddy`. Gradle remains developer tooling.

## Audited launchers

| File | Role | Decision |
| --- | --- | --- |
| `gradlew`, `gradlew.bat` | Root developer build wrapper | Keep |
| `gradle/wrapper/*` | Canonical wrapper runtime and configuration | Keep |
| Root `repobuddy` and `repobuddy.bat` | Checkout launchers coupled to `build/libs` | Replaced by distribution templates |
| Root `repobuddy-mcp` and `repobuddy-mcp.bat` | Duplicate MCP launchers | Replaced by `repobuddy mcp` |
| `.run/Run IDE with Plugin.run.xml` | Developer `runIde` configuration | Keep |
| `.github/java-upgrade/hooks/scripts/*` | Unrelated local development hooks | Keep isolated |

No duplicate Gradle wrapper, `.cmd` launcher, misspelled RepoBuddy launcher, or committed generated
launcher was found. Generated `build`, `bin`, `.intellijPlatform`, and VS Code dependency outputs are
not release inputs.

## Distribution flow

`repo-buddy-cli` bundles the internal MCP adapter, core contracts, and IPC client into
`repobuddy.jar`. `cliDistZip` packages that JAR with thin Windows and Unix launchers. `buildPlugin`
continues to package the IntelliJ plugin and embedded Java agent. `assembleRelease` creates both
versioned ZIPs and SHA-256 checksums; `verifyRelease` exercises the packaged CLI rather than a class
directory or checkout launcher.
