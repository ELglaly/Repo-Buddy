package com.repoinspector.settings;

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;
import com.repoinspector.inspections.scan.RepoBuddyIssueService;
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

    @Override
    public @Nls(capitalization = Nls.Capitalization.Title) String getDisplayName() {
        return "RepoBuddy";
    }

    @Override
    public @Nullable JComponent createComponent() {
        panelOnlyCheckBox = new JBCheckBox(
                "Show RepoBuddy issues only in the Issues panel (hide inline warnings)");

        JBLabel hint = new JBLabel(
                "<html>When enabled, the five RepoBuddy inspections do not add inline underlines or "
                        + "Problems-view entries. Findings appear only in the RepoBuddy <b>Issues</b> tab, "
                        + "the editor banner, and the status-bar counter.<br>"
                        + "When disabled, the inspections also behave as ordinary editor warnings.</html>");
        hint.setForeground(UIManager.getColor("Label.disabledForeground"));
        hint.setBorder(JBUI.Borders.emptyLeft(24));

        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(JBUI.Borders.empty(10));
        panelOnlyCheckBox.setAlignmentX(Component.LEFT_ALIGNMENT);
        hint.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(panelOnlyCheckBox);
        panel.add(Box.createVerticalStrut(JBUI.scale(6)));
        panel.add(hint);

        reset();
        return panel;
    }

    @Override
    public boolean isModified() {
        return panelOnlyCheckBox != null
                && panelOnlyCheckBox.isSelected() != RepoBuddySettings.getInstance().isPanelOnlyMode();
    }

    @Override
    public void apply() {
        if (panelOnlyCheckBox == null) return;
        RepoBuddySettings.getInstance().setPanelOnlyMode(panelOnlyCheckBox.isSelected());
        for (Project project : ProjectManager.getInstance().getOpenProjects()) {
            if (project.isDisposed()) continue;
            DaemonCodeAnalyzer.getInstance(project).restart();
            RepoBuddyIssueService.getInstance(project).refreshOpenFiles();
        }
    }

    @Override
    public void reset() {
        if (panelOnlyCheckBox != null) {
            panelOnlyCheckBox.setSelected(RepoBuddySettings.getInstance().isPanelOnlyMode());
        }
    }

    @Override
    public void disposeUIResources() {
        panelOnlyCheckBox = null;
    }
}
