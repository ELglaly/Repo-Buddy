package com.repoinspector.runner.ui;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.IdeFrame;
import com.intellij.openapi.wm.WindowManager;
import com.repoinspector.model.OperationType;
import com.repoinspector.runner.model.ExecutionRequest;
import com.repoinspector.runner.model.ExecutionResult;
import com.repoinspector.runner.model.ParameterDef;
import com.repoinspector.runner.model.SqlLogEntry;
import com.repoinspector.runner.service.RepoExecutionClient;
import com.repoinspector.runner.service.SpringAppUrlResolver;
import com.repoinspector.ui.UITheme;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.util.List;

/**
 * Floating popup that executes a repository method against the running Spring Boot
 * application via the RepoBuddy agent.
 *
 * <p>Layout:
 * <pre>
 *  ┌─ NORTH: titlebar ───────────────────────────────────────────────────────────┐
 *  │  ▶  OrderRepository.findAllByCustomerIdAndStatus   📌 🕘 ✖                  │
 *  ├─ hint bar ──────────────────────────────────────────────────────────────────┤
 *  │  [READ]  4 parameters                                                        │
 *  └─────────────────────────────────────────────────────────────────────────────┘
 *  ┌─ CENTER (JTabbedPane) ──────────────────────────────────────────────────────┐
 *  │  [ ⚙ Parameters ]  [ ⛁ SQL Logs ]  [ {} Result ]  [ ✖ Errors ]            │
 *  └─────────────────────────────────────────────────────────────────────────────┘
 *  ┌─ SOUTH ─────────────────────────────────────────────────────────────────────┐
 *  │  ● Connected · localhost:8080          [ Cancel ]  [ ▶ Run  Ctrl+↵ ]        │
 *  └─────────────────────────────────────────────────────────────────────────────┘
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

    private final ParameterFormPanel parameterForm;
    private final SqlLogPanel        sqlPanel;
    private final ResultPanel        resultPanel;
    private final ErrorPanel         errorPanel;
    private final JTabbedPane        tabs;

    private final JLabel       agentStatusLabel;
    private final JProgressBar progressBar;
    private final JButton      runButton;
    private final JButton      cancelButton;

    private static Window resolveParentWindow(Project project) {
        IdeFrame ideFrame = WindowManager.getInstance().getIdeFrame(project);
        if (ideFrame == null) return null;
        JComponent comp = ideFrame.getComponent();
        return comp != null ? SwingUtilities.getWindowAncestor(comp) : null;
    }

    public RepoRunnerPopup(Project project, String repositoryClass,
                           String methodName, List<ParameterDef> params) {
        super(resolveParentWindow(project),
                simpleName(repositoryClass) + "." + methodName + "()",
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

        // ── Titlebar ──────────────────────────────────────────────────────────
        JLabel titleLabel = new JLabel();
        titleLabel.setFont(UITheme.MONO_XS.deriveFont(Font.BOLD, 13f));
        String repoHex   = UITheme.toHex(UITheme.GOLD);
        String methodHex = UITheme.toHex(UITheme.ACCENT);
        titleLabel.setText("<html>"
                + "<font color='" + repoHex + "'>" + simpleName(repositoryClass) + "</font>"
                + "<font color='" + UITheme.toHex(UITheme.MUTED) + "'>.</font>"
                + "<font color='" + methodHex + "'>" + methodName + "</font>"
                + "</html>");

        JButton pinBtn     = UITheme.iconButton("\uD83D\uDCCC");
        JButton historyBtn = UITheme.iconButton("\uD83D\uDD58");
        JButton closeBtn   = UITheme.iconButton("\u2716");
        pinBtn.setToolTipText("Pin");
        historyBtn.setToolTipText("History");
        closeBtn.setToolTipText("Close");
        closeBtn.setForeground(UITheme.DANGER);
        closeBtn.addActionListener(e -> dispose());

        JPanel titlebar = new JPanel(new BorderLayout(8, 0));
        titlebar.setBackground(UITheme.HEADER_BG);
        titlebar.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 8));

        JLabel playIcon = new JLabel("\u25B6");
        playIcon.setFont(UITheme.UI.deriveFont(12f));
        playIcon.setForeground(UITheme.ACCENT);
        playIcon.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 8));

        JPanel titleLeft = new JPanel(new BorderLayout());
        titleLeft.setOpaque(false);
        titleLeft.add(playIcon,   BorderLayout.WEST);
        titleLeft.add(titleLabel, BorderLayout.CENTER);

        JPanel titleRight = new JPanel();
        titleRight.setLayout(new BoxLayout(titleRight, BoxLayout.X_AXIS));
        titleRight.setOpaque(false);
        titleRight.add(pinBtn);
        titleRight.add(historyBtn);
        titleRight.add(closeBtn);

        titlebar.add(titleLeft,  BorderLayout.CENTER);
        titlebar.add(titleRight, BorderLayout.EAST);

        // ── Hint bar ──────────────────────────────────────────────────────────
        OperationType op = OperationType.fromMethodName(methodName);
        JLabel opBadge = buildOpBadge(op);

        JLabel paramCount = new JLabel(params.size() + " parameter" + (params.size() == 1 ? "" : "s"));
        paramCount.setFont(UITheme.UI_SM);
        paramCount.setForeground(UITheme.MUTED);

        JLabel hintSep = new JLabel(" \u00B7 ");
        hintSep.setFont(UITheme.UI_SM);
        hintSep.setForeground(UITheme.BORDER_SUB);

        JPanel hintBar = new JPanel();
        hintBar.setLayout(new BoxLayout(hintBar, BoxLayout.X_AXIS));
        hintBar.setBackground(UITheme.HEADER_BG);
        hintBar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 1, 0, UITheme.BORDER_SUB),
                BorderFactory.createEmptyBorder(4, 12, 4, 12)));
        hintBar.add(opBadge);
        hintBar.add(hintSep);
        hintBar.add(paramCount);

        JPanel north = new JPanel(new BorderLayout());
        north.add(titlebar, BorderLayout.NORTH);
        north.add(hintBar,  BorderLayout.SOUTH);

        // ── South: agent status + cancel + run ────────────────────────────────
        agentStatusLabel = new JLabel();
        agentStatusLabel.setFont(UITheme.UI_SM);
        setAgentStatusUnknown();

        JPanel agentPanel = new JPanel();
        agentPanel.setLayout(new BoxLayout(agentPanel, BoxLayout.X_AXIS));
        agentPanel.setOpaque(false);
        JLabel connDot = new JLabel("\u25CF ");
        connDot.setFont(UITheme.UI_SM);
        connDot.setForeground(UITheme.SUCCESS);
        agentPanel.add(agentStatusLabel);

        cancelButton = UITheme.button("Cancel");
        cancelButton.addActionListener(e -> dispose());

        runButton = UITheme.runButton();
        runButton.addActionListener(e -> runExecution());

        progressBar = new JProgressBar();
        progressBar.setIndeterminate(false);
        progressBar.setVisible(false);
        progressBar.setStringPainted(true);
        progressBar.setString("Executing\u2026");
        progressBar.setForeground(UITheme.SUCCESS);
        progressBar.setMaximumSize(new Dimension(200, 16));

        JPanel south = new JPanel(new BorderLayout(10, 0));
        south.setBackground(UITheme.TOOLBAR);
        south.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, UITheme.BORDER_SUB),
                BorderFactory.createEmptyBorder(8, 12, 8, 12)));

        JPanel southRight = new JPanel();
        southRight.setLayout(new BoxLayout(southRight, BoxLayout.X_AXIS));
        southRight.setOpaque(false);
        southRight.add(progressBar);
        southRight.add(Box.createHorizontalStrut(8));
        southRight.add(cancelButton);
        southRight.add(Box.createHorizontalStrut(4));
        southRight.add(runButton);

        south.add(agentStatusLabel, BorderLayout.WEST);
        south.add(southRight,       BorderLayout.EAST);

        // ── Root ──────────────────────────────────────────────────────────────
        JPanel root = new JPanel(new BorderLayout(0, 0));
        root.add(north, BorderLayout.NORTH);
        root.add(tabs,  BorderLayout.CENTER);
        root.add(south, BorderLayout.SOUTH);
        setContentPane(root);
    }

    public void display(Point anchor) {
        if (anchor != null) positionNearAnchor(anchor);
        else setLocationRelativeTo(null);
        setVisible(true);
        toFront();

        // Escape → close
        getRootPane().registerKeyboardAction(
                e -> dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);

        // Ctrl+Enter → run
        int mask = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        getRootPane().registerKeyboardAction(
                e -> { if (runButton.isEnabled()) runExecution(); },
                KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, mask),
                JComponent.WHEN_IN_FOCUSED_WINDOW);

        ApplicationManager.getApplication().executeOnPooledThread(this::refreshAgentStatus);
    }

    private void positionNearAnchor(Point anchor) {
        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
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
        if (!logs.isEmpty()) boldTabTitle(TAB_SQL);
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
    // Agent status indicator (in south bar)
    // =========================================================================

    private void refreshAgentStatus() {
        String url = SpringAppUrlResolver.resolve(project);
        boolean reachable = url != null && isPortReachable(url);
        SwingUtilities.invokeLater(() -> {
            if (reachable) setAgentStatusOnline();
            else           setAgentStatusOffline();
        });
    }

    private void setAgentStatusOnline() {
        String url = SpringAppUrlResolver.resolve(project);
        String host = url != null ? URI.create(url).getHost() + ":" + URI.create(url).getPort() : "localhost";
        agentStatusLabel.setText("\u25CF  Connected \u00B7 " + host);
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

    private JLabel buildOpBadge(OperationType op) {
        String text; Color fg, bg;
        if (op == OperationType.READ) {
            text = "READ";  fg = UITheme.SUCCESS; bg = UITheme.SUCCESS_SUB;
        } else if (op == OperationType.WRITE) {
            text = "WRITE"; fg = UITheme.WARNING; bg = UITheme.WARNING_SUB;
        } else {
            text = "?";     fg = UITheme.MUTED;   bg = UITheme.BORDER_SUB;
        }
        JLabel badge = new JLabel(text) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(getBackground());
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 4, 4);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        badge.setFont(UITheme.UI_SM.deriveFont(Font.BOLD));
        badge.setForeground(fg);
        badge.setBackground(bg);
        badge.setOpaque(false);
        badge.setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 8));
        return badge;
    }

    private void resetLoadingState() {
        progressBar.setIndeterminate(false);
        progressBar.setVisible(false);
        runButton.setEnabled(true);
    }

    private void boldTabTitle(int index) {
        String title = tabs.getTitleAt(index);
        if (!title.startsWith("<html>"))
            tabs.setTitleAt(index, "<html><b>" + title + "</b></html>");
    }

    private void resetTabTitles() {
        for (int i = 0; i < tabs.getTabCount(); i++) {
            String title = tabs.getTitleAt(i);
            if (title.startsWith("<html><b>") && title.endsWith("</b></html>"))
                tabs.setTitleAt(i, title.substring(9, title.length() - 11));
        }
    }

    private static boolean isPortReachable(String baseUrl) {
        try {
            URI uri = URI.create(baseUrl);
            try (Socket s = new Socket()) { s.connect(new InetSocketAddress(uri.getHost(), uri.getPort()), 500); }
            return true;
        } catch (Exception e) { return false; }
    }

    private static String simpleName(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot >= 0 ? fqn.substring(dot + 1) : fqn;
    }
}
