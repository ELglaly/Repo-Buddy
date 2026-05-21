package com.repoinspector.inspections.scan;

import com.intellij.util.messages.Topic;

/**
 * Message-bus contract fired by {@link RepoBuddyIssueService} whenever its cached findings
 * change. The Issues panel, the editor banner trigger, and the status-bar widget subscribe to
 * this so all of them stay in lock-step with a single underlying scan.
 */
public interface RepoBuddyIssueListener {

    Topic<RepoBuddyIssueListener> TOPIC =
            Topic.create("RepoBuddy issues updated", RepoBuddyIssueListener.class);

    /** Called on the EDT after the issue cache has been refreshed. */
    void issuesUpdated();
}
