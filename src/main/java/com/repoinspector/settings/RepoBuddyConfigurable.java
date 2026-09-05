package com.repoinspector.settings;

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;
import com.repoinspector.inspections.scan.RepoBuddyIssueService;
import com.repoinspector.runner.startup.AgentConfigCleaner;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;

/**
 * Settings page (Settings ▸ Tools ▸ RepoBuddy) exposing the panel-only toggle.
 *
 * <p>Applying the change restarts the code-analysis daemon and refreshes the issue service for
 * every open project, so inline underlines and the RepoBuddy indicators update without reopening
 * files.
 */
public final class RepoBuddyConfigurable implements Configurable {

    private JBCheckBox panelOnlyCheckBox;
    private JBCheckBox javaAgentCheckBox;

    @Override
    public @Nls(capitalization = Nls.Capitalization.Title) String getDisplayName() {
        return "RepoBuddy";
    }

    @Override
    public @Nullable JComponent createComponent() {
        panelOnlyCheckBox = new JBCheckBox(
                "Show RepoBuddy issues only in the Issues panel (hide inline warnings)");
        javaAgentCheckBox = new JBCheckBox("Enable RepoBuddy Java agent");

        JBLabel hint = new JBLabel(
                "<html>When enabled, the five RepoBuddy inspections do not add inline underlines or "
                        + "Problems-view entries. Findings appear only in the RepoBuddy <b>Issues</b> tab, "
                        + "the editor banner, and the status-bar counter.<br>"
                        + "When disabled, the inspections also behave as ordinary editor warnings.</html>");
        hint.setForeground(UIManager.getColor("Label.disabledForeground"));
        hint.setBorder(JBUI.Borders.emptyLeft(24));
        JBLabel agentHint = new JBLabel("<html>Injects the RepoBuddy agent when supported Java applications are launched. "
                + "The agent is added at runtime and is not stored in shared run configurations.</html>");
        agentHint.setForeground(UIManager.getColor("Label.disabledForeground"));
        agentHint.setBorder(JBUI.Borders.emptyLeft(24));

        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(JBUI.Borders.empty(10));
        panelOnlyCheckBox.setAlignmentX(Component.LEFT_ALIGNMENT);
        hint.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(panelOnlyCheckBox);
        panel.add(Box.createVerticalStrut(JBUI.scale(6)));
        panel.add(hint);
        panel.add(Box.createVerticalStrut(JBUI.scale(14)));
        javaAgentCheckBox.setAlignmentX(Component.LEFT_ALIGNMENT);
        agentHint.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(javaAgentCheckBox);
        panel.add(Box.createVerticalStrut(JBUI.scale(6)));
        panel.add(agentHint);

        reset();
        return panel;
    }

    @Override
    public boolean isModified() {
        return panelOnlyCheckBox != null && (panelOnlyCheckBox.isSelected() != RepoBuddySettings.getInstance().isPanelOnlyMode()
                || javaAgentCheckBox.isSelected() != RepoBuddySettings.getInstance().isJavaAgentEnabled());
    }

    @Override
    public void apply() {
        if (panelOnlyCheckBox == null) return;
        RepoBuddySettings settings = RepoBuddySettings.getInstance();
        boolean agentWasEnabled = settings.isJavaAgentEnabled();
        settings.setPanelOnlyMode(panelOnlyCheckBox.isSelected());
        settings.setJavaAgentEnabled(javaAgentCheckBox.isSelected());
        for (Project project : ProjectManager.getInstance().getOpenProjects()) {
            if (project.isDisposed()) continue;
            DaemonCodeAnalyzer.getInstance(project).restart();
            RepoBuddyIssueService.getInstance(project).refreshOpenFiles();
            if (agentWasEnabled && !javaAgentCheckBox.isSelected()) AgentConfigCleaner.removeAgentFromConfigurations(project);
        }
    }

    @Override
    public void reset() {
        if (panelOnlyCheckBox != null) {
            panelOnlyCheckBox.setSelected(RepoBuddySettings.getInstance().isPanelOnlyMode());
            javaAgentCheckBox.setSelected(RepoBuddySettings.getInstance().isJavaAgentEnabled());
        }
    }

    @Override
    public void disposeUIResources() {
        panelOnlyCheckBox = null;
        javaAgentCheckBox = null;
    }
}
