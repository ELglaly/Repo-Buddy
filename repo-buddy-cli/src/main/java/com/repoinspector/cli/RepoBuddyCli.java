package com.repoinspector.cli;

import com.repoinspector.core.*;
import com.repoinspector.ipc.RepoBuddyIpcClient;
import com.repoinspector.ipc.SessionDescriptor;
import com.repoinspector.ipc.SessionDiscovery;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ScopeType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

@Command(name = "repobuddy", mixinStandardHelpOptions = true, version = "RepoBuddy 1.0.7",
        description = "RepoBuddy analysis for Spring Data projects",
        subcommands = {RepoBuddyCli.Scan.class, RepoBuddyCli.Issues.class, RepoBuddyCli.Issue.class,
                RepoBuddyCli.Context.class, RepoBuddyCli.Rules.class, RepoBuddyCli.ProjectInfo.class,
                RepoBuddyCli.Projects.class})
public final class RepoBuddyCli implements Callable<Integer> {
    @Option(names = "--format", defaultValue = "human", scope = ScopeType.INHERIT,
            description = "Output format: human or json")
    String format;
    @Option(names = "--project", scope = ScopeType.INHERIT, description = "Open RepoBuddy project ID")
    String projectId;
    @Option(names = "--timeout", defaultValue = "60", scope = ScopeType.INHERIT, description = "Timeout in seconds")
    int timeoutSeconds;
    @Option(names = "--debug", scope = ScopeType.INHERIT, description = "Show diagnostic exception details on stderr")
    boolean debug;

    public static void main(String[] args) { System.exit(new CommandLine(new RepoBuddyCli()).execute(args)); }
    @Override public Integer call() { CommandLine.usage(this, System.out); return 0; }

    RepoBuddyIpcClient client() {
        SessionDescriptor session = SessionDiscovery.select(Path.of("."), projectId);
        return new RepoBuddyIpcClient(session, Duration.ofSeconds(Math.max(1, timeoutSeconds)));
    }

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

    abstract static class Child implements Callable<Integer> {
        @CommandLine.ParentCommand RepoBuddyCli root;
        abstract int execute() throws Exception;
        @Override public Integer call() { return root.run(this::execute); }
    }

    @Command(name = "scan", description = "Analyze the current project")
    static final class Scan extends Child {
        @Option(names = "--severity") Severity severity;
        @Option(names = "--rule") String rule;
        @Option(names = "--fail-on", defaultValue = "any") String failOn;
        @Override int execute() throws Exception {
            RepoBuddyScanResult result = root.await(root.client().scan(new ScanRequest(severity, rule)));
            if (root.json()) root.printJson(result); else printHuman(result);
            return fails(result.summary(), failOn) ? 1 : 0;
        }
        private static boolean fails(RepoBuddyScanSummary summary, String value) {
            if ("none".equalsIgnoreCase(value)) return false;
            if ("any".equalsIgnoreCase(value)) return summary.totalIssues() > 0;
            try {
                Severity threshold = Severity.valueOf(value.toUpperCase(Locale.ROOT));
                return summary.severityCounts().entrySet().stream().anyMatch(entry -> entry.getKey().atLeast(threshold) && entry.getValue() > 0);
            } catch (IllegalArgumentException error) {
                throw new RepoBuddyException(RepoBuddyErrorCode.REPOBUDDY_INVALID_ARGUMENT,
                        "--fail-on must be none, any, HIGH, MEDIUM, LOW, or INFO");
            }
        }
        private static void printHuman(RepoBuddyScanResult result) {
            System.out.println("RepoBuddy Scan\n\nProject: " + result.project().name() + "\nScan: " + result.scanId()
                    + "\nStatus: " + result.status().name().toLowerCase(Locale.ROOT) + "\nDuration: " + result.durationMs()
                    + " ms\nIssues: " + result.summary().totalIssues());
            result.summary().severityCounts().forEach((key, value) -> { if (value > 0) System.out.printf("%-8s %d%n", key, value); });
            if (!result.summary().ruleCounts().isEmpty()) {
                System.out.println("\nTop rules");
                result.summary().ruleCounts().forEach((key, value) -> System.out.printf("%-32s %d%n", key, value));
            }
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
                if (root.projectId != null) throw unavailable;
                value = offlineProjectInfo(Path.of(".").toAbsolutePath().normalize());
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
        Path root = detectRoot(start);
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
                boot, jpa, frameworks, RepoBuddyRules.all().stream().map(RepoBuddyRule::id).toList(), "1.0.7", false);
    }

    private static Path detectRoot(Path start) {
        Path value = start;
        while (value != null) {
            if (Files.exists(value.resolve("pom.xml")) || Files.exists(value.resolve("build.gradle")) || Files.exists(value.resolve("build.gradle.kts"))) return value;
            value = value.getParent();
        }
        throw new RepoBuddyException(RepoBuddyErrorCode.REPOBUDDY_PROJECT_NOT_SUPPORTED, "No Maven or Gradle project was found");
    }
}
