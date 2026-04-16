package com.repoinspector.runner.ui;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.IdeFrame;
import com.intellij.openapi.wm.WindowManager;
import com.repoinspector.runner.model.ExecutionRequest;
import com.repoinspector.runner.model.ExecutionResult;
import com.repoinspector.runner.model.ParameterDef;
import com.repoinspector.runner.model.SqlLogEntry;
import com.repoinspector.runner.service.RepoExecutionClient;
import com.repoinspector.runner.service.SpringAppUrlResolver;
import com.repoinspector.ui.UITheme;

import javax.swing.*;
import java.awt.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.util.List;

/**
 * Floating popup that executes a repository method against the running Spring Boot
 * application via the RepoBuddy agent.
 *
 * <p>Spring Data JPA generates and executes the real query inside the app's own
 * Spring context — no query simulation or custom SQL generation is done here.
 * The agent captures the executed SQL via Hibernate's {@code StatementInspector}
 * and returns the serialised result.
 *
 * <p>Layout:
 * <pre>
 *  ┌─ NORTH ─────────────────────────────────────────────────────────────────┐
 *  │  UserRepository  →  findById()  (id: Long)      ● Agent Ready           │
 *  └─────────────────────────────────────────────────────────────────────────┘
 *  ┌─ CENTER (JTabbedPane) ──────────────────────────────────────────────────┐
 *  │  [ ⚙ Parameters ]  [ ⛁ SQL Logs ]  [ {} Result ]  [ ✖ Errors ]        │
 *  └─────────────────────────────────────────────────────────────────────────┘
 *  ┌─ SOUTH ─────────────────────────────────────────────────────────────────┐
 *  │  [ Run ▶ ]   ████████████████████ (progress bar, hidden when idle)      │
 *  └─────────────────────────────────────────────────────────────────────────┘
 * </pre>
 */
public class RepoRunnerPopup extends JDialog {

    private static final int POPUP_WIDTH  = 880;
    private static final int POPUP_HEIGHT = 640;

    private static final int TAB_PARAMETERS = 0;
    private static final int TAB_SQL        = 1;
    private static final int TAB_RESULT     = 2;
    private static final int TAB_ERRORS     = 3;

    private final Project project;
    private final String  repositoryClass;
    private final String  methodName;

    // ── Sub-panels ────────────────────────────────────────────────────────────
    private final ParameterFormPanel parameterForm;
    private final SqlLogPanel        sqlPanel;
    private final ResultPanel        resultPanel;
    private final ErrorPanel         errorPanel;
    private final JTabbedPane        tabs;

    // ── Status bar ────────────────────────────────────────────────────────────
    private final JLabel       agentStatusLabel;
    private final JProgressBar progressBar;
    private final JButton      runButton;

    private static Window resolveParentWindow(Project project) {
        IdeFrame ideFrame = WindowManager.getInstance().getIdeFrame(project);
        if (ideFrame == null) return null;
        JComponent comp = ideFrame.getComponent();
        return comp != null ? SwingUtilities.getWindowAncestor(comp) : null;
    }

    public RepoRunnerPopup(Project project, String repositoryClass,
                           String methodName, List<ParameterDef> params) {
        super(resolveParentWindow(project),
                "Repository Runner  \u2014  " + simpleName(repositoryClass) + "." + methodName + "()",
                Dialog.ModalityType.MODELESS);
        this.project         = project;
        this.repositoryClass = repositoryClass;
        this.methodName      = methodName;

        setSize(POPUP_WIDTH, POPUP_HEIGHT);
        setMinimumSize(new Dimension(660, 500));
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        // ── Sub-panels ────────────────────────────────────────────────────────
        parameterForm = new ParameterFormPanel(params);
        sqlPanel      = new SqlLogPanel();
        resultPanel   = new ResultPanel();
        errorPanel    = new ErrorPanel();

        // ── Tabs ──────────────────────────────────────────────────────────────
        tabs = new JTabbedPane();
        tabs.addTab("\u2699 Parameters", new JScrollPane(parameterForm));
        tabs.addTab("\u26C1 SQL Logs",   sqlPanel);
        tabs.addTab("{} Result",         resultPanel);
        tabs.addTab("\u2716 Errors",     errorPanel);

        // ── North: header + agent status ──────────────────────────────────────
        String paramSummary = params.stream()
                .map(p -> p.name() + ": " + p.typeName())
                .reduce((a, b) -> a + ",  " + b)
                .orElse("no params");

        JLabel headerLabel = new JLabel(
                UITheme.popupHeaderHtml(simpleName(repositoryClass), methodName, paramSummary));
        headerLabel.setFont(headerLabel.getFont().deriveFont(14f));
        headerLabel.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));

        agentStatusLabel = new JLabel();
        agentStatusLabel.setFont(agentStatusLabel.getFont().deriveFont(11f));
        agentStatusLabel.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 4));
        setAgentStatusUnknown();

        JPanel headerRow = new JPanel(new BorderLayout(12, 0));
        headerRow.setOpaque(false);
        headerRow.add(headerLabel,      BorderLayout.CENTER);
        headerRow.add(agentStatusLabel, BorderLayout.EAST);

        JPanel north = new JPanel(new BorderLayout());
        north.setBackground(UITheme.HEADER_BG);
        north.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, UITheme.ACCENT.darker()),
                BorderFactory.createEmptyBorder(8, 12, 8, 12)));
        north.add(headerRow, BorderLayout.CENTER);

        // ── South: run button + progress ──────────────────────────────────────
        runButton = UITheme.runButton();
        runButton.addActionListener(e -> runExecution());

        progressBar = new JProgressBar();
        progressBar.setIndeterminate(false);
        progressBar.setVisible(false);
        progressBar.setStringPainted(true);
        progressBar.setString("Executing\u2026");
        progressBar.setForeground(UITheme.SUCCESS);

        JPanel south = new JPanel(new BorderLayout(10, 0));
        south.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, UITheme.ACCENT.darker()),
                BorderFactory.createEmptyBorder(8, 10, 8, 10)));
        south.add(runButton,   BorderLayout.WEST);
        south.add(progressBar, BorderLayout.CENTER);

        // ── Root ──────────────────────────────────────────────────────────────
        JPanel root = new JPanel(new BorderLayout(0, 4));
        root.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
        root.add(north, BorderLayout.NORTH);
        root.add(tabs,  BorderLayout.CENTER);
        root.add(south, BorderLayout.SOUTH);
        setContentPane(root);
    }

    /**
     * Makes the popup visible.
     *
     * <p>If {@code anchor} is non-null (screen coordinates from a gutter icon click),
     * the popup is placed just to the right of the click point, pushed back onto the
     * screen if it would overflow.  If {@code anchor} is null (e.g. opened from the
     * tool-window Run button), the popup is centred on the screen.
     */
    public void display(Point anchor) {
        if (anchor != null) {
            positionNearAnchor(anchor);
        } else {
            setLocationRelativeTo(null);
        }
        setVisible(true);
        toFront();
        // Bind Escape → close so the developer can dismiss with one keystroke.
        getRootPane().registerKeyboardAction(
                e -> dispose(),
                javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        // Check agent connectivity in the background so the popup opens instantly.
        ApplicationManager.getApplication().executeOnPooledThread(this::refreshAgentStatus);
    }

    /**
     * Positions the dialog to the right of {@code anchor}, clamped so it stays
     * fully within the default screen bounds.
     */
    private void positionNearAnchor(Point anchor) {
        java.awt.Dimension screen = java.awt.Toolkit.getDefaultToolkit().getScreenSize();
        int x = anchor.x + 16;
        int y = anchor.y - POPUP_HEIGHT / 4;
        x = Math.max(0, Math.min(x, screen.width  - POPUP_WIDTH));
        y = Math.max(0, Math.min(y, screen.height - POPUP_HEIGHT));
        setLocation(x, y);
    }

    // =========================================================================
    // Execution
    // =========================================================================

    private void runExecution() {
        List<ExecutionRequest.ParameterValue> paramValues = parameterForm.collectValues();
        ExecutionRequest request = new ExecutionRequest(repositoryClass, methodName, paramValues);

        sqlPanel.clear();
        resultPanel.clear();
        errorPanel.clear();
        resetTabTitles();
        runButton.setEnabled(false);
        progressBar.setIndeterminate(true);
        progressBar.setString("Executing " + methodName + "()\u2026");
        progressBar.setVisible(true);
        tabs.setSelectedIndex(TAB_PARAMETERS);

        String baseUrl = SpringAppUrlResolver.resolve(project);

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            if (baseUrl == null || !isPortReachable(baseUrl)) {
                SwingUtilities.invokeLater(this::displayAgentNotRunning);
                return;
            }

            RepoExecutionClient client = new RepoExecutionClient(baseUrl);
            try {
                ExecutionResult result = client.execute(request);
                SwingUtilities.invokeLater(() -> displayResult(result));
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> displayExecutionError(baseUrl, ex));
            }
        });
    }

    // =========================================================================
    // Result display
    // =========================================================================

    private void displayResult(ExecutionResult result) {
        resetLoadingState();

        List<SqlLogEntry> logs = result.sqlLogs() != null ? result.sqlLogs() : List.of();
        sqlPanel.setSqlLogs(logs);
        resultPanel.setResult(result.result(), result.executionTimeMs());
        errorPanel.setError(result.exception());

        tabs.setSelectedIndex(result.isSuccess() ? TAB_RESULT : TAB_ERRORS);
        if (!logs.isEmpty()) {
            boldTabTitle(TAB_SQL);
        }
    }

    private void displayAgentNotRunning() {
        resetLoadingState();
        setAgentStatusOffline();
        String portFile = SpringAppUrlResolver.portFilePath().toString();
        errorPanel.setError(
                "RepoBuddy agent is not running.\n\n"
                + "The agent starts automatically when you launch your Spring Boot app from\n"
                + "IntelliJ \u2014 no configuration needed.\n\n"
                + "Fix:\n"
                + "  \u2022 Start (or restart) your Spring Boot app using the \u25B6 button in IntelliJ.\n"
                + "  \u2022 Look for this line in the app logs:\n"
                + "      [RepoBuddy] Agent listening on http://localhost:<port>/repoinspector/execute\n\n"
                + "If the log line never appears:\n"
                + "  \u2022 Close and reopen this project \u2014 RepoBuddy will re-patch your run\n"
                + "    configuration on the next project open, then restart the app.\n"
                + "  \u2022 Verify Run \u2192 Edit Configurations \u2192 VM Options contains:\n"
                + "      -javaagent:/path/to/repoBuddy-agent.jar\n\n"
                + "Port file expected at: " + portFile);
        tabs.setSelectedIndex(TAB_ERRORS);
    }

    private void displayExecutionError(String baseUrl, Exception ex) {
        resetLoadingState();
        String msg = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();

        if (msg.contains("HTTP 401") || msg.contains("HTTP 403")) {
            errorPanel.setError(
                    "Received an auth error from: " + baseUrl + "\n\n"
                    + "The RepoBuddy agent server never performs authentication \u2014 this 401/403\n"
                    + "means something ELSE is listening on port "
                    + URI.create(baseUrl).getPort() + " (not the RepoBuddy agent).\n\n"
                    + "Fix: ensure the agent is running on a free port.\n"
                    + "Raw error: " + msg);
        } else {
            errorPanel.setError("Execution failed.\n\n" + ex.getClass().getName() + ": " + msg);
        }
        tabs.setSelectedIndex(TAB_ERRORS);
    }

    // =========================================================================
    // Agent status indicator
    // =========================================================================

    /** Checks the port file on the current thread (call from background thread). */
    private void refreshAgentStatus() {
        String url = SpringAppUrlResolver.resolve(project);
        boolean reachable = url != null && isPortReachable(url);
        SwingUtilities.invokeLater(() -> {
            if (reachable) setAgentStatusOnline();
            else           setAgentStatusOffline();
        });
    }

    private void setAgentStatusOnline() {
        agentStatusLabel.setText("\u25CF  Agent Ready");
        agentStatusLabel.setForeground(UITheme.SUCCESS);
    }

    private void setAgentStatusOffline() {
        agentStatusLabel.setText("\u25CF  Agent Offline");
        agentStatusLabel.setForeground(UITheme.DANGER);
    }

    private void setAgentStatusUnknown() {
        agentStatusLabel.setText("\u25CF  Checking\u2026");
        agentStatusLabel.setForeground(UITheme.MUTED);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private void resetLoadingState() {
        progressBar.setIndeterminate(false);
        progressBar.setVisible(false);
        runButton.setEnabled(true);
    }

    /**
     * Makes the tab title bold to draw attention to new content.
     * Idempotent if the title is already wrapped in HTML bold tags.
     */
    private void boldTabTitle(int index) {
        String title = tabs.getTitleAt(index);
        if (!title.startsWith("<html>")) {
            tabs.setTitleAt(index, "<html><b>" + title + "</b></html>");
        }
    }

    /** Strips bold HTML wrappers from all tab titles (called before each new run). */
    private void resetTabTitles() {
        for (int i = 0; i < tabs.getTabCount(); i++) {
            String title = tabs.getTitleAt(i);
            if (title.startsWith("<html><b>") && title.endsWith("</b></html>")) {
                tabs.setTitleAt(i, title.substring("<html><b>".length(),
                        title.length() - "</b></html>".length()));
            }
        }
    }

    /**
     * Returns {@code true} if a TCP connection to the agent port succeeds within
     * 500 ms.  Must be called on a background thread, never on the EDT.
     */
    private static boolean isPortReachable(String baseUrl) {
        try {
            URI uri = URI.create(baseUrl);
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(uri.getHost(), uri.getPort()), 500);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static String simpleName(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot >= 0 ? fqn.substring(dot + 1) : fqn;
    }
}
