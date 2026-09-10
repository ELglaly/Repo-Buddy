package com.repoinspector.settings;

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;
import com.repoinspector.inspections.scan.RepoBuddyIssueService;
import com.repoinspector.integration.RepoBuddyLocalApiServer;
import com.repoinspector.integration.RepoBuddyCliManager;
import com.repoinspector.integration.RepoBuddyIntegrationUi;
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
    private JBCheckBox localIntegrationCheckBox;
    private JBLabel cliStatusLabel;
    private JButton installCliButton;
    private JButton openInstallationButton;

    @Override
    public @Nls(capitalization = Nls.Capitalization.Title) String getDisplayName() {
        return "RepoBuddy";
    }

    @Override
    public @Nullable JComponent createComponent() {
        panelOnlyCheckBox = new JBCheckBox(
                "Show RepoBuddy issues only in the Issues panel (hide inline warnings)");
        javaAgentCheckBox = new JBCheckBox("Enable RepoBuddy Java agent");
        localIntegrationCheckBox = new JBCheckBox("Enable local CLI and MCP access");

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
        JBLabel integrationHint = new JBLabel("<html>Starts an authenticated loopback-only endpoint for local tools. "
                + "Disabled by default; source access remains limited to bounded context for known issues.</html>");
        integrationHint.setForeground(UIManager.getColor("Label.disabledForeground"));
        integrationHint.setBorder(JBUI.Borders.emptyLeft(24));
        RepoBuddyCliManager cliManager = RepoBuddyCliManager.getInstance();
        cliStatusLabel = new JBLabel(cliManager.installationStatusText());
        installCliButton = new JButton(cliManager.installActionText());
        installCliButton.addActionListener(event -> RepoBuddyIntegrationUi.install(null, this::refreshCliStatus));
        JButton configureCodex = new JButton("Configure Codex");
        configureCodex.addActionListener(event -> configureClient(RepoBuddyCliManager.AiClient.CODEX));
        JButton configureClaude = new JButton("Configure Claude Code");
        configureClaude.addActionListener(event -> configureClient(RepoBuddyCliManager.AiClient.CLAUDE_CODE));
        JButton copyConfiguration = new JButton("Copy MCP Configuration");
        copyConfiguration.addActionListener(event -> copyConfiguration());
        openInstallationButton = new JButton("Open Installation Folder");
        openInstallationButton.addActionListener(event -> RepoBuddyIntegrationUi.openInstallationFolder(null));
        JButton pathSetup = new JButton("Terminal PATH Setup…");
        pathSetup.addActionListener(event -> RepoBuddyIntegrationUi.showPathGuidance());

        JPanel cliActions = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0));
        cliActions.add(installCliButton);
        cliActions.add(configureCodex);
        cliActions.add(configureClaude);
        JPanel cliUtilities = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0));
        cliUtilities.add(copyConfiguration);
        cliUtilities.add(openInstallationButton);
        cliUtilities.add(pathSetup);

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
        panel.add(Box.createVerticalStrut(JBUI.scale(14)));
        localIntegrationCheckBox.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(localIntegrationCheckBox);
        panel.add(Box.createVerticalStrut(JBUI.scale(6)));
        panel.add(integrationHint);
        panel.add(Box.createVerticalStrut(JBUI.scale(14)));
        cliStatusLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        cliActions.setAlignmentX(Component.LEFT_ALIGNMENT);
        cliUtilities.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(cliStatusLabel);
        panel.add(Box.createVerticalStrut(JBUI.scale(6)));
        panel.add(cliActions);
        panel.add(Box.createVerticalStrut(JBUI.scale(6)));
        panel.add(cliUtilities);

        reset();
        refreshCliStatus();
        return panel;
    }

    @Override
    public boolean isModified() {
        return panelOnlyCheckBox != null && (panelOnlyCheckBox.isSelected() != RepoBuddySettings.getInstance().isPanelOnlyMode()
                || javaAgentCheckBox.isSelected() != RepoBuddySettings.getInstance().isJavaAgentEnabled()
                || localIntegrationCheckBox.isSelected() != RepoBuddySettings.getInstance().isLocalIntegrationEnabled());
    }

    @Override
    public void apply() {
        if (panelOnlyCheckBox == null) return;
        RepoBuddySettings settings = RepoBuddySettings.getInstance();
        boolean agentWasEnabled = settings.isJavaAgentEnabled();
        boolean integrationWasEnabled = settings.isLocalIntegrationEnabled();
        settings.setPanelOnlyMode(panelOnlyCheckBox.isSelected());
        settings.setJavaAgentEnabled(javaAgentCheckBox.isSelected());
        settings.setLocalIntegrationEnabled(localIntegrationCheckBox.isSelected());
        for (Project project : ProjectManager.getInstance().getOpenProjects()) {
            if (project.isDisposed()) continue;
            DaemonCodeAnalyzer.getInstance(project).restart();
            RepoBuddyIssueService.getInstance(project).refreshOpenFiles();
            if (agentWasEnabled && !javaAgentCheckBox.isSelected()) AgentConfigCleaner.removeAgentFromConfigurations(project);
        }
        if (integrationWasEnabled != localIntegrationCheckBox.isSelected()) {
            RepoBuddyLocalApiServer server = RepoBuddyLocalApiServer.getInstance();
            if (localIntegrationCheckBox.isSelected()) server.enable(); else server.disable();
        }
    }

    @Override
    public void reset() {
        if (panelOnlyCheckBox != null) {
            panelOnlyCheckBox.setSelected(RepoBuddySettings.getInstance().isPanelOnlyMode());
            javaAgentCheckBox.setSelected(RepoBuddySettings.getInstance().isJavaAgentEnabled());
            localIntegrationCheckBox.setSelected(RepoBuddySettings.getInstance().isLocalIntegrationEnabled());
        }
    }

    @Override
    public void disposeUIResources() {
        panelOnlyCheckBox = null;
        javaAgentCheckBox = null;
        localIntegrationCheckBox = null;
        cliStatusLabel = null;
        installCliButton = null;
        openInstallationButton = null;
    }

    private void configureClient(RepoBuddyCliManager.AiClient client) {
        Project project = RepoBuddyIntegrationUi.chooseProject(installCliButton);
        if (project == null) return;
        RepoBuddyIntegrationUi.configure(project, client, () -> {
            if (localIntegrationCheckBox != null)
                localIntegrationCheckBox.setSelected(RepoBuddySettings.getInstance().isLocalIntegrationEnabled());
            refreshCliStatus();
        });
    }

    private void copyConfiguration() {
        Object[] choices = {"Generic / Claude JSON", "Codex TOML"};
        Object selected = JOptionPane.showInputDialog(installCliButton, "Choose a configuration format:",
                "Copy RepoBuddy MCP Configuration", JOptionPane.QUESTION_MESSAGE, null, choices, choices[0]);
        if (selected == null) return;
        RepoBuddyCliManager.ConfigFormat format = selected.equals(choices[1])
                ? RepoBuddyCliManager.ConfigFormat.CODEX_TOML
                : RepoBuddyCliManager.ConfigFormat.GENERIC_JSON;
        RepoBuddyIntegrationUi.copyConfiguration(null, format, this::refreshCliStatus);
    }

    private void refreshCliStatus() {
        if (cliStatusLabel == null) return;
        RepoBuddyCliManager manager = RepoBuddyCliManager.getInstance();
        cliStatusLabel.setText(manager.installationStatusText());
        installCliButton.setText(manager.installActionText());
        installCliButton.setEnabled(!manager.isInstalledCurrent());
        openInstallationButton.setEnabled(java.nio.file.Files.isDirectory(manager.installationDirectory()));
    }
}
