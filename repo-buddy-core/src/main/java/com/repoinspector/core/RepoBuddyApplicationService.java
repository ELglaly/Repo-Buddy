package com.repoinspector.core;
import java.util.List;
import java.util.concurrent.CompletionStage;
public interface RepoBuddyApplicationService {
    CompletionStage<RepoBuddyScanResult> scan(ScanRequest request);
    CompletionStage<IssuePage> listIssues(IssueQuery query);
    CompletionStage<RepoBuddyIssue> getIssue(String issueId);
    CompletionStage<RepoBuddyIssueContext> getIssueContext(String issueId, int contextLines);
    CompletionStage<List<RepoBuddyRule>> getRules(RuleQuery query);
    CompletionStage<RepoBuddyProjectInfo> getProjectInfo();
    CompletionStage<ScopedIssuePage> getIssues(ScopedIssueRequest request);
    CompletionStage<ChangeCheckResult> checkChanges(Severity severity, String ruleId);
}
