package com.repoinspector.integration;

import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.ui.Messages;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** Shared Settings and Tools-menu workflows for installing and configuring the companion CLI. */
public final class RepoBuddyIntegrationUi {
    private RepoBuddyIntegrationUi() {}

    public static @Nullable Project chooseProject(@Nullable Component parent) {
        Project[] projects = Arrays.stream(ProjectManager.getInstance().getOpenProjects())
                .filter(project -> !project.isDisposed() && project.getBasePath() != null).toArray(Project[]::new);
        if (projects.length == 0) {
            Messages.showWarningDialog("Open a project before configuring RepoBuddy for an AI client.",
                    "RepoBuddy Integration");
            return null;
        }
        if (projects.length == 1) return projects[0];
        ProjectChoice[] choices = Arrays.stream(projects).map(ProjectChoice::new).toArray(ProjectChoice[]::new);
        Object selected = JOptionPane.showInputDialog(parent, "Choose the project RepoBuddy should verify:",
                "RepoBuddy Integration", JOptionPane.QUESTION_MESSAGE, null, choices, choices[0]);
        return selected instanceof ProjectChoice choice ? choice.project : null;
    }

    public static void install(@Nullable Project project, @Nullable Runnable finished) {
        RepoBuddyCliManager manager = RepoBuddyCliManager.getInstance();
        ProgressManager.getInstance().run(new Task.Backgroundable(project, manager.installActionText(), true) {
            private String error;
            @Override public void run(@NotNull ProgressIndicator indicator) {
                try { manager.install(); }
                catch (Exception failure) { error = message(failure); }
            }
            @Override public void onSuccess() {
                if (error == null) RepoBuddyIntegrationUi.notify(project, "RepoBuddy CLI installed",
                        manager.installationStatusText(), NotificationType.INFORMATION);
                else Messages.showErrorDialog(error, "RepoBuddy CLI Installation Failed");
                if (finished != null) finished.run();
            }
        });
    }

    public static void configure(@NotNull Project project, @NotNull RepoBuddyCliManager.AiClient client,
                                 @Nullable Runnable finished) {
        configure(project, client, false, finished);
    }

    private static void configure(@NotNull Project project, @NotNull RepoBuddyCliManager.AiClient client,
                                  boolean replace, @Nullable Runnable finished) {
        ProgressManager.getInstance().run(new Task.Backgroundable(project,
                "Configuring " + client.displayName() + " for RepoBuddy", true) {
            private RepoBuddyCliManager.Result result;
            @Override public void run(@NotNull ProgressIndicator indicator) {
                result = RepoBuddyCliManager.getInstance().configure(project, client, replace);
            }
            @Override public void onSuccess() {
                if (result.kind() == RepoBuddyCliManager.ResultKind.SUCCESS) {
                    RepoBuddyIntegrationUi.notify(project, "RepoBuddy is ready", result.message()
                                    + " Restart an already-running client session to load the MCP server.",
                            NotificationType.INFORMATION);
                    if (finished != null) finished.run();
                    return;
                }
                if (result.kind() == RepoBuddyCliManager.ResultKind.CONFLICT && !replace) {
                    int answer = Messages.showYesNoDialog(project,
                            "An MCP server named 'repobuddy' is already registered in " + client.displayName()
                                    + ". Replace that user-level registration?",
                            "Replace RepoBuddy Registration", "Replace", "Cancel", Messages.getQuestionIcon());
                    if (answer == Messages.YES) configure(project, client, true, finished);
                    else if (finished != null) finished.run();
                    return;
                }
                Messages.showErrorDialog(project, result.message(), client.displayName() + " Configuration Failed");
                if (finished != null) finished.run();
            }
        });
    }

    public static void copyConfiguration(@Nullable Project project, RepoBuddyCliManager.ConfigFormat format,
                                         @Nullable Runnable finished) {
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Preparing RepoBuddy MCP configuration", true) {
            private String configuration;
            private String error;
            @Override public void run(@NotNull ProgressIndicator indicator) {
                try { configuration = RepoBuddyCliManager.getInstance().configuration(format); }
                catch (Exception failure) { error = message(failure); }
            }
            @Override public void onSuccess() {
                if (error == null) {
                    CopyPasteManager.getInstance().setContents(new StringSelection(configuration));
                    RepoBuddyIntegrationUi.notify(project, "MCP configuration copied",
                            format == RepoBuddyCliManager.ConfigFormat.CODEX_TOML
                                    ? "Codex TOML was copied to the clipboard."
                                    : "Generic/Claude JSON was copied to the clipboard.",
                            NotificationType.INFORMATION);
                } else Messages.showErrorDialog(error, "Unable to Copy MCP Configuration");
                if (finished != null) finished.run();
            }
        });
    }

    public static void openInstallationFolder(@Nullable Project project) {
        Path directory = RepoBuddyCliManager.getInstance().installationDirectory();
        if (!Files.isDirectory(directory)) {
            Messages.showInfoMessage("Install the RepoBuddy CLI first.", "RepoBuddy CLI");
            return;
        }
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                if (!Desktop.isDesktopSupported()) throw new IllegalStateException("Desktop integration is unavailable");
                Desktop.getDesktop().open(directory.toFile());
            } catch (Exception failure) {
                ApplicationManager.getApplication().invokeLater(() -> Messages.showErrorDialog(
                        message(failure), "Unable to Open RepoBuddy Installation"));
            }
        });
    }

    public static void showPathGuidance() {
        String guidance = RepoBuddyCliManager.getInstance().pathGuidance();
        JTextArea area = new JTextArea(guidance, 7, 58);
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setCaretPosition(0);
        int result = JOptionPane.showConfirmDialog(null, new JScrollPane(area), "RepoBuddy CLI PATH Setup",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.INFORMATION_MESSAGE);
        if (result == JOptionPane.OK_OPTION)
            CopyPasteManager.getInstance().setContents(new StringSelection(guidance));
    }

    private static String message(Throwable error) {
        return error.getMessage() == null || error.getMessage().isBlank()
                ? error.getClass().getSimpleName() : error.getMessage();
    }

    private static void notify(Project project, String title, String content, NotificationType type) {
        NotificationGroupManager.getInstance().getNotificationGroup("RepoBuddy Notifications")
                .createNotification(title, content, type).notify(project);
    }

    private static final class ProjectChoice {
        private final Project project;
        private ProjectChoice(Project project) { this.project = project; }
        @Override public String toString() { return project.getName() + " — " + project.getBasePath(); }
    }
}
