package com.repoinspector.cli;

import com.google.gson.JsonObject;
import com.repoinspector.core.*;
import com.repoinspector.ipc.RepoBuddyIpcClient;
import com.repoinspector.ipc.SessionDescriptor;
import com.repoinspector.ipc.SessionDiscovery;
import com.repoinspector.mcp.RepoBuddyMcpServer;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ScopeType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

@Command(name = "repobuddy", mixinStandardHelpOptions = true, versionProvider = RepoBuddyCli.VersionProvider.class,
        description = "RepoBuddy analysis for Spring Data projects",
        subcommands = {RepoBuddyCli.Setup.class, RepoBuddyCli.Mcp.class, RepoBuddyCli.Check.class,
                RepoBuddyCli.Status.class, RepoBuddyCli.Doctor.class, RepoBuddyCli.Version.class,
                RepoBuddyCli.Issues.class, RepoBuddyCli.Issue.class,
                RepoBuddyCli.Context.class, RepoBuddyCli.Rules.class, RepoBuddyCli.ProjectInfo.class,
                RepoBuddyCli.Projects.class, CommandLine.HelpCommand.class})
public final class RepoBuddyCli implements Callable<Integer> {
    @Option(names = "--format", defaultValue = "human", scope = ScopeType.INHERIT,
            description = "Output format: human or json")
    String format;
    @Option(names = "--project", scope = ScopeType.INHERIT, description = "Project directory")
    Path project;
    @Option(names = "--timeout", defaultValue = "60", scope = ScopeType.INHERIT, description = "Timeout in seconds")
    int timeoutSeconds;
    @Option(names = "--debug", scope = ScopeType.INHERIT, description = "Show diagnostic exception details on stderr")
    boolean debug;

    public static void main(String[] args) { System.exit(new CommandLine(new RepoBuddyCli()).execute(args)); }
    @Override public Integer call() { CommandLine.usage(this, System.out); return 0; }

    RepoBuddyIpcClient client() {
        Path root = ProjectLocator.resolve(project, Path.of("."));
        SessionDescriptor session = SessionDiscovery.select(root, root.toString());
        return new RepoBuddyIpcClient(session, Duration.ofSeconds(Math.max(1, timeoutSeconds)));
    }

    Path projectRoot() { return ProjectLocator.resolve(project, Path.of(".")); }

    boolean json() {
        if (!"human".equalsIgnoreCase(format) && !"json".equalsIgnoreCase(format))
            throw new RepoBuddyException(RepoBuddyErrorCode.REPOBUDDY_INVALID_ARGUMENT, "--format must be human or json");
        return "json".equalsIgnoreCase(format);
    }

    void printJson(Object value) { System.out.println(RepoBuddyJson.gson(true).toJson(value)); }

    int run(Action action) {
        try { return action.run(); }
        catch (Throwable error) {
            Throwable cause = unwrap(error);
            RepoBuddyException failure = cause instanceof RepoBuddyException value ? value
                    : cause instanceof IllegalArgumentException
                    ? new RepoBuddyException(RepoBuddyErrorCode.REPOBUDDY_INVALID_ARGUMENT, cause.getMessage(), cause)
                    : new RepoBuddyException(RepoBuddyErrorCode.REPOBUDDY_INTERNAL_ERROR, "RepoBuddy command failed", cause);
            if ("json".equalsIgnoreCase(format)) System.out.println(RepoBuddyJson.gson(false).toJson(RepoBuddyError.of(failure)));
            else System.err.println(failure.code() + ": " + failure.getMessage());
            if (debug) cause.printStackTrace(System.err);
            return exitCode(failure.code());
        }
    }

    <T> T await(java.util.concurrent.CompletionStage<T> stage) throws Exception {
        return stage.toCompletableFuture().get(Math.max(1, timeoutSeconds), TimeUnit.SECONDS);
    }

    private static Throwable unwrap(Throwable error) {
        Throwable value = error;
        while ((value instanceof java.util.concurrent.ExecutionException || value instanceof CompletionException)
                && value.getCause() != null) value = value.getCause();
        return value;
    }

    private static int exitCode(RepoBuddyErrorCode code) {
        return switch (code) {
            case REPOBUDDY_INVALID_ARGUMENT -> 2;
            case REPOBUDDY_PROJECT_NOT_FOUND, REPOBUDDY_PROJECT_AMBIGUOUS, REPOBUDDY_PROJECT_NOT_SUPPORTED -> 3;
            case REPOBUDDY_SCAN_FAILED, REPOBUDDY_SCAN_TIMEOUT, REPOBUDDY_PROJECT_INDEXING -> 4;
            case REPOBUDDY_IDE_NOT_RUNNING -> 5;
            case REPOBUDDY_AUTHENTICATION_FAILED, REPOBUDDY_PROTOCOL_MISMATCH -> 6;
            case REPOBUDDY_PROJECT_DISPOSED -> 7;
            default -> 8;
        };
    }

    @FunctionalInterface interface Action { int run() throws Exception; }

    public static final class VersionProvider implements CommandLine.IVersionProvider {
        @Override public String[] getVersion() { return new String[] { "RepoBuddy " + RepoBuddyVersion.current() }; }
    }

    abstract static class Child implements Callable<Integer> {
        @CommandLine.ParentCommand RepoBuddyCli root;
        abstract int execute() throws Exception;
        @Override public Integer call() { return root.run(this::execute); }
    }

    @Command(name = "setup", description = "Validate RepoBuddy and optionally configure an AI client")
    static final class Setup extends Child {
        @Parameters(index = "0", arity = "0..1", description = "Client: codex, claude, or generic") String client;
        @Option(names = "--dry-run", description = "Print client configuration without changing it") boolean dryRun;
        @Option(names = "--replace", description = "Replace an existing RepoBuddy client registration") boolean replace;

        @Override int execute() throws Exception {
            EnvironmentReport report = EnvironmentReport.inspect(root, true);
            if (root.json()) root.printJson(report.asMap()); else report.print("RepoBuddy Setup");
            if (client == null) return report.ready() ? 0 : 1;
            return switch (client.toLowerCase(Locale.ROOT)) {
                case "codex" -> configureCodex(report);
                case "claude" -> printClaude(report);
                case "generic" -> printGeneric(report);
                default -> throw new RepoBuddyException(RepoBuddyErrorCode.REPOBUDDY_INVALID_ARGUMENT,
                        "Client must be codex, claude, or generic");
            };
        }

        private int configureCodex(EnvironmentReport report) {
            if (!report.ready()) return 1;
            Path codex = ProcessSupport.findExecutable("codex");
            if (codex == null) throw new RepoBuddyException(RepoBuddyErrorCode.REPOBUDDY_INVALID_ARGUMENT,
                    "Codex CLI was not found on PATH");
            List<String> server = serverCommand();
            List<String> add = new ArrayList<>(List.of(codex.toString(), "mcp", "add", "repobuddy", "--"));
            add.addAll(server);
            System.out.println("\nSuggested command:\n" + display(add));
            if (dryRun) return 0;
            ProcessSupport.Result existing = ProcessSupport.run(root.projectRoot(),
                    List.of(codex.toString(), "mcp", "get", "repobuddy"), root.timeoutSeconds);
            if (existing.succeeded() && !replace) throw new RepoBuddyException(
                    RepoBuddyErrorCode.REPOBUDDY_INVALID_ARGUMENT,
                    "A Codex MCP server named repobuddy already exists; use --replace to replace it");
            if (existing.succeeded()) {
                ProcessSupport.Result removed = ProcessSupport.run(root.projectRoot(),
                        List.of(codex.toString(), "mcp", "remove", "repobuddy"), root.timeoutSeconds);
                if (!removed.succeeded()) throw commandFailure("Unable to remove existing Codex registration", removed);
            }
            ProcessSupport.Result added = ProcessSupport.run(root.projectRoot(), add, root.timeoutSeconds);
            if (!added.succeeded()) throw commandFailure("Unable to configure Codex", added);
            ProcessSupport.Result verified = ProcessSupport.run(root.projectRoot(),
                    List.of(codex.toString(), "mcp", "get", "repobuddy"), root.timeoutSeconds);
            if (!verified.succeeded()) throw commandFailure("Codex registration could not be verified", verified);
            System.out.println("Codex MCP registration is ready.");
            return 0;
        }

        private int printClaude(EnvironmentReport report) {
            if (!report.ready()) return 1;
            List<String> command = new ArrayList<>(List.of("claude", "mcp", "add", "--scope", "user",
                    "--transport", "stdio", "repobuddy", "--"));
            command.addAll(serverCommand());
            System.out.println("\nClaude configuration (copy and run):\n" + display(command));
            return 0;
        }

        private int printGeneric(EnvironmentReport report) {
            if (!report.ready()) return 1;
            List<String> command = serverCommand();
            JsonObject server = new JsonObject();
            server.addProperty("command", command.get(0));
            com.google.gson.JsonArray args = new com.google.gson.JsonArray();
            command.subList(1, command.size()).forEach(args::add);
            server.add("args", args);
            JsonObject servers = new JsonObject(); servers.add("repobuddy", server);
            JsonObject result = new JsonObject(); result.add("mcpServers", servers);
            System.out.println("\nGeneric MCP configuration:\n" + RepoBuddyJson.gson(true).toJson(result));
            return 0;
        }

        private static List<String> serverCommand() {
            Path java = ProcessSupport.javaExecutable();
            Path jar = InstallationLayout.runningArtifact();
            if (java == null) throw new RepoBuddyException(RepoBuddyErrorCode.REPOBUDDY_INVALID_ARGUMENT,
                    "Java 17 or newer was not found");
            if (!InstallationLayout.isPackagedJar()) throw new RepoBuddyException(
                    RepoBuddyErrorCode.REPOBUDDY_INVALID_ARGUMENT,
                    "Client setup must be run from the packaged RepoBuddy CLI distribution");
            return List.of(java.toString(), "-jar", jar.toString(), "mcp");
        }

        private static String display(List<String> values) {
            return values.stream().map(value -> value.contains(" ") ? '"' + value + '"' : value)
                    .collect(java.util.stream.Collectors.joining(" "));
        }

        private static RepoBuddyException commandFailure(String message, ProcessSupport.Result result) {
            String detail = result.output().isBlank() ? "exit code " + result.exitCode() : result.output();
            return new RepoBuddyException(RepoBuddyErrorCode.REPOBUDDY_INTERNAL_ERROR, message + ": " + detail);
        }
    }

    @Command(name = "mcp", description = "Start the local read-only MCP server over stdio")
    static final class Mcp extends Child {
        @Override int execute() throws Exception { new RepoBuddyMcpServer().run(); return 0; }
    }

    @Command(name = "status", description = "Show RepoBuddy project and integration status")
    static final class Status extends Child {
        @Override int execute() {
            EnvironmentReport report = EnvironmentReport.inspect(root, true);
            if (root.json()) root.printJson(report.asMap()); else report.print("RepoBuddy Status");
            return 0;
        }
    }

    @Command(name = "doctor", description = "Diagnose RepoBuddy installation and integration problems")
    static final class Doctor extends Child {
        @Override int execute() {
            EnvironmentReport report = EnvironmentReport.inspect(root, true);
            if (root.json()) root.printJson(report.asMap()); else report.print("RepoBuddy Doctor");
            return report.ready() ? 0 : 1;
        }
    }

    @Command(name = "version", description = "Print the RepoBuddy version")
    static final class Version extends Child {
        @Override int execute() { System.out.println("RepoBuddy " + RepoBuddyVersion.current()); return 0; }
    }

    private record CheckRow(String name, String status, String detail, boolean required) {}

    private record EnvironmentReport(List<CheckRow> checks) {
        static EnvironmentReport inspect(RepoBuddyCli root, boolean includeTools) {
            List<CheckRow> checks = new ArrayList<>();
            int feature = Runtime.version().feature();
            checks.add(new CheckRow("Java", feature >= 17 ? "PASS" : "FAIL",
                    System.getProperty("java.version") + " (requires 17+)", true));
            checks.add(new CheckRow("Distribution", InstallationLayout.isPackagedJar() ? "PASS" : "FAIL",
                    String.valueOf(InstallationLayout.runningArtifact()), true));
            Path projectRoot = null;
            try {
                projectRoot = root.projectRoot();
                checks.add(new CheckRow("Project", "PASS", projectRoot.toString(), true));
            } catch (RuntimeException error) {
                checks.add(new CheckRow("Project", "FAIL", error.getMessage(), true));
            }
            Path git = ProcessSupport.findExecutable("git");
            checks.add(new CheckRow("Git", git == null ? "WARN" : "PASS",
                    git == null ? "not found; change-aware checks are unavailable" : git.toString(), false));
            Path sessionDirectory = SessionDiscovery.directory();
            Path permissionTarget = sessionDirectory;
            while (permissionTarget != null && !Files.exists(permissionTarget)) permissionTarget = permissionTarget.getParent();
            boolean writable = permissionTarget != null && Files.isWritable(permissionTarget);
            checks.add(new CheckRow("Session storage", writable ? "PASS" : "FAIL",
                    sessionDirectory.toString(), true));
            if (projectRoot != null) {
                boolean repository = ProjectLocator.isGitRepository(projectRoot);
                checks.add(new CheckRow("Git repository", repository ? "PASS" : "WARN",
                        repository ? projectRoot.toString() : "not detected; --changed is unavailable", false));
                try {
                    SessionDescriptor session = SessionDiscovery.select(projectRoot, projectRoot.toString());
                    checks.add(new CheckRow("IntelliJ integration", "PASS",
                            session.projectName() + " (PID " + session.pid() + ")", true));
                    try {
                        RepoBuddyProjectInfo info = root.await(new RepoBuddyIpcClient(session,
                                Duration.ofSeconds(Math.max(1, root.timeoutSeconds))).getProjectInfo());
                        checks.add(new CheckRow("IPC", "PASS", "protocol compatible; plugin " + info.repoBuddyVersion(), true));
                    } catch (Exception error) {
                        checks.add(new CheckRow("IPC", "FAIL", "session exists but cannot be reached", true));
                    }
                } catch (RuntimeException error) {
                    checks.add(new CheckRow("IntelliJ integration", "FAIL",
                            error.getMessage() + "; open the project and enable local CLI and MCP access", true));
                }
                if (git != null && ProjectLocator.isGitRepository(projectRoot)) {
                    ProcessSupport.Result branch = ProcessSupport.run(projectRoot,
                            List.of(git.toString(), "branch", "--show-current"), 5);
                    checks.add(new CheckRow("Branch", branch.succeeded() ? "PASS" : "WARN",
                            branch.output().isBlank() ? "detached or unavailable" : branch.output(), false));
                    ProcessSupport.Result changes = ProcessSupport.run(projectRoot,
                            List.of(git.toString(), "status", "--porcelain"), 5);
                    long count = changes.output().isBlank() ? 0 : changes.output().lines().count();
                    checks.add(new CheckRow("Changed files", changes.succeeded() ? "PASS" : "WARN",
                            changes.succeeded() ? Long.toString(count) : changes.output(), false));
                }
            }
            if (includeTools) {
                Path codex = ProcessSupport.findExecutable("codex");
                Path claude = ProcessSupport.findExecutable("claude");
                checks.add(new CheckRow("Codex", codex == null ? "INFO" : "PASS", codex == null ? "not detected" : codex.toString(), false));
                checks.add(new CheckRow("Claude", claude == null ? "INFO" : "PASS", claude == null ? "not detected" : claude.toString(), false));
                if (codex != null) {
                    ProcessSupport.Result registration = ProcessSupport.run(projectRoot,
                            List.of(codex.toString(), "mcp", "get", "repobuddy"), 10);
                    checks.add(new CheckRow("Codex MCP", registration.succeeded() ? "PASS" : "INFO",
                            registration.succeeded() ? "repobuddy is configured" : "not configured", false));
                }
            }
            return new EnvironmentReport(List.copyOf(checks));
        }

        boolean ready() { return checks.stream().noneMatch(check -> check.required() && "FAIL".equals(check.status())); }
        Map<String, Object> asMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("schemaVersion", "1"); result.put("version", RepoBuddyVersion.current());
            result.put("ready", ready()); result.put("checks", checks); return result;
        }
        void print(String title) {
            System.out.println(title + "\n");
            for (CheckRow check : checks) System.out.printf("%-22s %-5s %s%n", check.name(), check.status(), check.detail());
            System.out.println("\n" + (ready() ? "RepoBuddy is ready." : "RepoBuddy needs attention."));
        }
    }

    @Command(name = "check", description = "Analyze the current project")
    static final class Check extends Child {
        @Option(names = "--changed", description = "Report issues introduced by current Git changes") boolean changed;
        @Option(names = "--severity") Severity severity;
        @Option(names = "--rule") String rule;
        @Option(names = "--fail-on", defaultValue = "any") String failOn;
        @Override int execute() throws Exception {
            if (changed) {
                Path rootPath = root.projectRoot();
                if (!ProjectLocator.isGitRepository(rootPath)) throw new RepoBuddyException(
                        RepoBuddyErrorCode.REPOBUDDY_PROJECT_NOT_SUPPORTED,
                        "--changed requires a Git repository: " + rootPath);
                ChangeCheckResult result = root.await(root.client().checkChanges(severity, rule));
                if (root.json()) root.printJson(result); else printChanged(result);
                return fails(result.issues(), failOn) ? 1 : 0;
            }
            RepoBuddyScanResult result = root.await(root.client().scan(new ScanRequest(severity, rule)));
            if (root.json()) root.printJson(result); else printHuman(result);
            return fails(result.summary(), failOn) ? 1 : 0;
        }
        private static boolean fails(List<RepoBuddyIssue> issues, String value) {
            if ("none".equalsIgnoreCase(value)) return false;
            if ("any".equalsIgnoreCase(value)) return !issues.isEmpty();
            try {
                Severity threshold = Severity.valueOf(value.toUpperCase(Locale.ROOT));
                return issues.stream().anyMatch(issue -> issue.severity().atLeast(threshold));
            } catch (IllegalArgumentException error) {
                throw invalidFailOn();
            }
        }
        private static boolean fails(RepoBuddyScanSummary summary, String value) {
            if ("none".equalsIgnoreCase(value)) return false;
            if ("any".equalsIgnoreCase(value)) return summary.totalIssues() > 0;
            try {
                Severity threshold = Severity.valueOf(value.toUpperCase(Locale.ROOT));
                return summary.severityCounts().entrySet().stream().anyMatch(entry -> entry.getKey().atLeast(threshold) && entry.getValue() > 0);
            } catch (IllegalArgumentException error) {
                throw invalidFailOn();
            }
        }
        private static RepoBuddyException invalidFailOn() { return new RepoBuddyException(
                RepoBuddyErrorCode.REPOBUDDY_INVALID_ARGUMENT,
                "--fail-on must be none, any, HIGH, MEDIUM, LOW, or INFO"); }
        private static void printHuman(RepoBuddyScanResult result) {
            System.out.println("RepoBuddy Check\n\nProject: " + result.project().name() + "\nScan: " + result.scanId()
                    + "\nStatus: " + result.status().name().toLowerCase(Locale.ROOT) + "\nDuration: " + result.durationMs()
                    + " ms\nIssues: " + result.summary().totalIssues());
            result.summary().severityCounts().forEach((key, value) -> { if (value > 0) System.out.printf("%-8s %d%n", key, value); });
            if (!result.summary().ruleCounts().isEmpty()) {
                System.out.println("\nTop rules");
                result.summary().ruleCounts().forEach((key, value) -> System.out.printf("%-32s %d%n", key, value));
            }
        }
        private static void printChanged(ChangeCheckResult result) {
            System.out.println("RepoBuddy Changed Check\n\nAvailability: " + result.availability()
                    + "\nBaseline: " + result.baseline() + "\nIntroduced: " + result.introducedIssueCount()
                    + "\nPre-existing: " + result.preExistingIssueCount() + "\nResolved: " + result.resolvedIssueCount());
            if (result.message() != null && !result.message().isBlank()) System.out.println("Message: " + result.message());
            for (RepoBuddyIssue issue : result.issues())
                System.out.printf("%-24s %-6s %s:%s  %s%n", issue.id(), issue.severity(), issue.path(), issue.line(), issue.message());
        }
    }

    @Command(name = "issues", description = "List normalized findings")
    static final class Issues extends Child {
        @Option(names = "--severity") Severity severity;
        @Option(names = "--minimum-severity") Severity minimum;
        @Option(names = "--rule") String rule;
        @Option(names = "--file") String file;
        @Option(names = "--limit", defaultValue = "50") int limit;
        @Option(names = "--offset", defaultValue = "0") int offset;
        @Option(names = "--scan-id") String scanId;
        @Override int execute() throws Exception {
            IssueQuery query = new IssueQuery(severity, minimum, rule, file, limit, offset, scanId);
            IssuePage page = root.await(root.client().listIssues(query));
            if (root.json()) root.printJson(page); else {
                System.out.println("RepoBuddy Issues (" + page.total() + ")");
                for (RepoBuddyIssue issue : page.issues()) System.out.printf("%-24s %-6s %s:%s  %s%n", issue.id(), issue.severity(), issue.path(), issue.line(), issue.message());
            }
            return 0;
        }
    }

    @Command(name = "issue", description = "Retrieve one finding")
    static final class Issue extends Child {
        @Parameters(index = "0") String id;
        @Override int execute() throws Exception {
            RepoBuddyIssue issue = root.await(root.client().getIssue(id));
            if (root.json()) root.printJson(issue); else System.out.println(issue.id() + "  " + issue.severity() + "\n" + issue.path() + ":" + issue.line() + ":" + issue.column() + "\n\n" + issue.message() + "\n\n" + issue.explanation() + "\n\nSuggested fix: " + issue.suggestedFix());
            return 0;
        }
    }

    @Command(name = "context", description = "Retrieve bounded source context for a finding")
    static final class Context extends Child {
        @Parameters(index = "0") String id;
        @Option(names = "--lines", defaultValue = "10") int lines;
        @Override int execute() throws Exception {
            RepoBuddyIssueContext value = root.await(root.client().getIssueContext(id, lines));
            if (root.json()) root.printJson(value); else System.out.print(value.context().code());
            return 0;
        }
    }

    @Command(name = "rules", description = "List RepoBuddy rules")
    static final class Rules extends Child {
        @Option(names = "--enabled") boolean enabled;
        @Option(names = "--rule") String rule;
        @Override int execute() throws Exception {
            List<RepoBuddyRule> values;
            try { values = root.await(root.client().getRules(new RuleQuery(enabled ? Boolean.TRUE : null, rule))); }
            catch (Throwable unavailable) {
                values = RepoBuddyRules.all().stream().filter(value -> !enabled || value.enabled())
                        .filter(value -> rule == null || value.id().equalsIgnoreCase(rule)).toList();
            }
            if (root.json()) root.printJson(values); else values.forEach(value -> System.out.printf("%-32s %-6s %s%n", value.id(), value.defaultSeverity(), value.name()));
            return 0;
        }
    }

    @Command(name = "project-info", description = "Describe the current project")
    static final class ProjectInfo extends Child {
        @Override int execute() throws Exception {
            RepoBuddyProjectInfo value;
            try { value = root.await(root.client().getProjectInfo()); }
            catch (Exception unavailable) {
                value = offlineProjectInfo(root.projectRoot());
            }
            if (root.json()) root.printJson(value); else System.out.println("Project: " + value.name() + "\nRoot: " + value.root() + "\nBuild: " + value.buildSystem() + "\nSpring Boot: " + value.springBoot() + "\nSpring Data JPA: " + value.springDataJpa() + "\nIDE analysis available: " + value.analysisAvailable());
            return 0;
        }
    }

    @Command(name = "projects", description = "List open RepoBuddy-enabled IntelliJ projects")
    static final class Projects extends Child {
        @Override int execute() {
            List<SessionDescriptor> values = SessionDiscovery.readAll().stream()
                    .filter(value -> ProcessHandle.of(value.pid()).map(ProcessHandle::isAlive).orElse(false)).toList();
            if (root.json()) root.printJson(values.stream().map(value -> new Project(value.projectId(), value.projectName(), value.projectRoot())).toList());
            else values.forEach(value -> System.out.printf("%-28s %s  %s%n", value.projectId(), value.projectName(), value.projectRoot()));
            return 0;
        }
        record Project(String id, String name, String root) {}
    }

    private static RepoBuddyProjectInfo offlineProjectInfo(Path start) {
        Path root = ProjectLocator.resolve(start, Path.of("."));
        String build = Files.exists(root.resolve("pom.xml")) ? "MAVEN"
                : Files.exists(root.resolve("build.gradle")) || Files.exists(root.resolve("build.gradle.kts")) ? "GRADLE" : "UNKNOWN";
        String content = "";
        for (String name : List.of("pom.xml", "build.gradle", "build.gradle.kts")) try { content += Files.readString(root.resolve(name)); } catch (Exception ignored) { }
        String lower = content.toLowerCase(Locale.ROOT);
        boolean boot = lower.contains("spring-boot");
        boolean jpa = lower.contains("spring-data-jpa") || lower.contains("starter-data-jpa");
        List<String> frameworks = new java.util.ArrayList<>();
        if (boot) frameworks.add("Spring Boot");
        if (jpa) frameworks.add("Spring Data JPA");
        return new RepoBuddyProjectInfo("1", null, root.getFileName().toString(), root.toString(), build, null,
                boot, jpa, frameworks, RepoBuddyRules.all().stream().map(RepoBuddyRule::id).toList(), RepoBuddyVersion.current(), false);
    }
}
