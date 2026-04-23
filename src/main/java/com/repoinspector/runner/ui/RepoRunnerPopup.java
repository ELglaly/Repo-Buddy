package com.repoinspector.runner.ui;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.IdeFrame;
import com.intellij.openapi.wm.WindowManager;
import com.intellij.ui.JBColor;
import com.intellij.util.ui.JBUI;
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
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.KeyEvent;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.util.List;
import java.util.ArrayList;

/**
 * Floating popup that executes a repository method against the running Spring Boot
 * application via the RepoBuddy agent — premium gradient design.
 */
public class RepoRunnerPopup extends JDialog {

    private static final int POPUP_WIDTH  = 900;
    private static final int POPUP_HEIGHT = 660;

    private static final int TAB_PARAMETERS = 0;
    private static final int TAB_SQL        = 1;
    private static final int TAB_RESULT     = 2;
    private static final int TAB_ERRORS     = 3;

    // Accent palette
    private static final JBColor INDIGO  = new JBColor(new Color(0x4F46E5), new Color(0x6366F1));
    private static final JBColor VIOLET  = new JBColor(new Color(0x7C3AED), new Color(0x8B5CF6));
    private static final JBColor TEAL    = new JBColor(new Color(0x0D9488), new Color(0x14B8A6));
    private static final JBColor TEAL_PALE   = new JBColor(new Color(0xF0FDFA), new Color(0x042F2E));
    private static final JBColor AMBER   = new JBColor(new Color(0xD97706), new Color(0xF59E0B));
    private static final JBColor AMBER_PALE  = new JBColor(new Color(0xFFFBEB), new Color(0x2D1B00));
    private static final JBColor ROSE    = new JBColor(new Color(0xE11D48), new Color(0xFB7185));

    private final Project project;
    private final String  repositoryClass;
    private final String  methodName;

    private final ParameterFormPanel parameterForm;
    private final SqlLogPanel        sqlPanel;
    private final ResultPanel        resultPanel;
    private final ErrorPanel         errorPanel;
    private final JTabbedPane        tabs;

    private final JLabel         agentStatusLabel;
    private final JProgressBar   progressBar;
    private final JButton        runButton;
    private final JButton        cancelButton;
    private final PulsingConnDot connDot;
    private final List<Timer>    managedTimers = new ArrayList<>();
    private Point                dragOffset;

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

        setUndecorated(true);
        setSize(POPUP_WIDTH, POPUP_HEIGHT);
        setMinimumSize(new Dimension(680, 520));
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        // Sub-panels
        parameterForm = new ParameterFormPanel(params);
        sqlPanel      = new SqlLogPanel();
        resultPanel   = new ResultPanel();
        errorPanel    = new ErrorPanel();

        // Tabs
        tabs = new JTabbedPane();
        tabs.setFont(UITheme.UI.deriveFont(Font.PLAIN, 12f));
        tabs.setBackground(UITheme.PANEL);
        tabs.setForeground(UITheme.INK);
        tabs.setBorder(JBUI.Borders.empty(10, 10, 0, 10));

        JScrollPane parameterScroll = new JScrollPane(parameterForm);
        parameterScroll.setBorder(BorderFactory.createEmptyBorder());
        parameterScroll.getViewport().setBackground(UITheme.PANEL);

        tabs.addTab("\u2699 Parameters", wrapTabContent(parameterScroll));
        tabs.addTab("\u26C1 SQL Logs",   wrapTabContent(sqlPanel));
        tabs.addTab("{} Result",         wrapTabContent(resultPanel));
        tabs.addTab("\u2716 Errors",     wrapTabContent(errorPanel));
        installTabDecorations();

        // Agent status dot
        connDot          = new PulsingConnDot();
        agentStatusLabel = new JLabel();
        agentStatusLabel.setFont(UITheme.UI_SM);
        setAgentStatusUnknown();

        // Buttons
        cancelButton = UITheme.button("Cancel");
        cancelButton.addActionListener(e -> dispose());

        runButton = buildRunButton();
        runButton.addActionListener(e -> runExecution());

        progressBar = new JProgressBar();
        progressBar.setIndeterminate(false);
        progressBar.setVisible(false);
        progressBar.setStringPainted(true);
        progressBar.setString("Executing\u2026");
        progressBar.setForeground(UITheme.SUCCESS);
        progressBar.setMaximumSize(new Dimension(JBUI.scale(200), JBUI.scale(16)));

        // Assemble root
        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(UITheme.BG);
        root.setBorder(BorderFactory.createLineBorder(UITheme.BORDER_SUB, 1));
        root.add(buildTitleBar(params), BorderLayout.NORTH);
        root.add(tabs,                  BorderLayout.CENTER);
        root.add(buildSouthBar(),       BorderLayout.SOUTH);
        setContentPane(root);
    }

    // =========================================================================
    // Layout
    // =========================================================================

    private JPanel buildTitleBar(List<ParameterDef> params) {
        // Gradient titlebar
        JPanel titlebar = new JPanel(new BorderLayout(JBUI.scale(8), 0)) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setPaint(new GradientPaint(0, 0,
                        new JBColor(new Color(0xEEF2FF), new Color(0x1A1A2E)),
                        getWidth(), 0,
                        new JBColor(new Color(0xF5F3FF), new Color(0x16213E))));
                g2.fillRect(0, 0, getWidth(), getHeight());
                g2.dispose();
                super.paintComponent(g);
            }
        };
        titlebar.setOpaque(false);
        titlebar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0,
                        new JBColor(new Color(0xC7D2FE), new Color(0x312E81))),
                JBUI.Borders.empty(10, 14, 10, 10)));

        // Play icon
        JLabel playIcon = new JLabel("\u25B6");
        playIcon.setFont(UITheme.UI.deriveFont(12f));
        playIcon.setForeground(INDIGO);
        playIcon.setBorder(JBUI.Borders.empty(0, 0, 0, 8));

        // Title
        JLabel titleLabel = new JLabel(UITheme.popupHeaderHtml(
                simpleName(repositoryClass),
                methodName,
                params.size() + " arg" + (params.size() == 1 ? "" : "s")));
        titleLabel.setFont(UITheme.UI_BOLD.deriveFont(14f));

        JLabel subLabel = new JLabel("Execute repository method against the live Spring application");
        subLabel.setFont(UITheme.UI_SM);
        subLabel.setForeground(UITheme.MUTED);

        JPanel titleLeft = new JPanel(new BorderLayout(JBUI.scale(8), 0));
        titleLeft.setOpaque(false);
        titleLeft.add(playIcon,   BorderLayout.WEST);
        titleLeft.add(titleLabel, BorderLayout.CENTER);

        // Hint row (op badge + param count)
        OperationType op    = OperationType.fromMethodName(methodName);
        JLabel opBadge      = buildOpBadge(op);
        JLabel paramCount   = new JLabel(params.size() + " parameter" + (params.size() == 1 ? "" : "s"));
        paramCount.setFont(UITheme.UI_SM);
        paramCount.setForeground(UITheme.MUTED);
        JLabel dot = new JLabel("  \u00B7  ");
        dot.setFont(UITheme.UI_SM);
        dot.setForeground(UITheme.MUTED);

        JPanel hintRow = new JPanel();
        hintRow.setOpaque(false);
        hintRow.setLayout(new BoxLayout(hintRow, BoxLayout.X_AXIS));
        hintRow.add(opBadge);
        hintRow.add(dot);
        hintRow.add(paramCount);

        JPanel titleCenter = new JPanel(new BorderLayout(0, JBUI.scale(4)));
        titleCenter.setOpaque(false);
        titleCenter.add(titleLeft, BorderLayout.NORTH);
        titleCenter.add(subLabel,  BorderLayout.CENTER);
        titleCenter.add(hintRow,   BorderLayout.SOUTH);

        // Window buttons
        JButton closeBtn = buildWindowControl("\u2715", ROSE, UITheme.DANGER_SUB);
        closeBtn.setToolTipText("Close");
        closeBtn.addActionListener(e -> dispose());

        JPanel titleRight = new JPanel(new FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), 0));
        titleRight.setOpaque(false);
        titleRight.add(closeBtn);

        JPanel leftWrap = new JPanel(new BorderLayout());
        leftWrap.setOpaque(false);
        leftWrap.add(titleCenter, BorderLayout.WEST);

        JPanel rightWrap = new JPanel(new BorderLayout());
        rightWrap.setOpaque(false);
        rightWrap.add(titleRight, BorderLayout.NORTH);

        titlebar.add(leftWrap,    BorderLayout.CENTER);
        titlebar.add(rightWrap,   BorderLayout.EAST);
        installWindowDragging(titlebar);
        return titlebar;
    }

    private JPanel buildSouthBar() {
        JPanel connRow = new JPanel();
        connRow.setLayout(new BoxLayout(connRow, BoxLayout.X_AXIS));
        connRow.setOpaque(false);
        connRow.add(connDot);
        connRow.add(Box.createHorizontalStrut(JBUI.scale(6)));
        connRow.add(agentStatusLabel);

        JPanel southRight = new JPanel();
        southRight.setLayout(new BoxLayout(southRight, BoxLayout.X_AXIS));
        southRight.setOpaque(false);
        southRight.add(progressBar);
        southRight.add(Box.createHorizontalStrut(JBUI.scale(8)));
        southRight.add(cancelButton);
        southRight.add(Box.createHorizontalStrut(JBUI.scale(4)));
        southRight.add(runButton);

        JPanel south = new JPanel(new BorderLayout(JBUI.scale(10), 0));
        south.setBackground(UITheme.TOOLBAR);
        south.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, UITheme.BORDER_SUB),
                JBUI.Borders.empty(8, 12)));
        south.add(connRow,    BorderLayout.WEST);
        south.add(southRight, BorderLayout.EAST);
        return south;
    }

    private JComponent wrapTabContent(JComponent content) {
        JPanel shell = new JPanel(new BorderLayout());
        shell.setOpaque(false);
        shell.setBorder(JBUI.Borders.empty(0, 0, 10, 0));

        JPanel card = new JPanel(new BorderLayout());
        card.setBackground(UITheme.PANEL);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(UITheme.BORDER_SUB, 1),
                JBUI.Borders.empty()));
        card.add(content, BorderLayout.CENTER);

        shell.add(card, BorderLayout.CENTER);
        return shell;
    }

    private void installTabDecorations() {
        for (int i = 0; i < tabs.getTabCount(); i++) {
            tabs.setTabComponentAt(i, createTabChip(tabs.getTitleAt(i), i == 0));
        }
        tabs.addChangeListener(e -> refreshTabDecorations());
    }

    private void refreshTabDecorations() {
        for (int i = 0; i < tabs.getTabCount(); i++) {
            tabs.setTabComponentAt(i, createTabChip(stripHtmlBold(tabs.getTitleAt(i)), i == tabs.getSelectedIndex()));
        }
    }

    private JComponent createTabChip(String text, boolean active) {
        JLabel label = new JLabel(text);
        label.setFont(active ? UITheme.UI_BOLD.deriveFont(12f) : UITheme.UI.deriveFont(12f));
        label.setForeground(active ? UITheme.ACCENT : UITheme.INK_DIM);

        JPanel chip = new JPanel(new FlowLayout(FlowLayout.CENTER, JBUI.scale(10), JBUI.scale(6))) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                Color bg = active ? UITheme.ACCENT_SUB : UITheme.PANEL_2;
                Color border = active ? UITheme.ACCENT : UITheme.BORDER_SUB;
                g2.setColor(bg);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), JBUI.scale(14), JBUI.scale(14));
                g2.setColor(border);
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, JBUI.scale(14), JBUI.scale(14));
                g2.dispose();
                super.paintComponent(g);
            }
        };
        chip.setOpaque(false);
        chip.add(label);
        return chip;
    }

    private static String stripHtmlBold(String title) {
        if (title.startsWith("<html><b>") && title.endsWith("</b></html>")) {
            return title.substring(9, title.length() - 11);
        }
        return title;
    }

    private JButton buildWindowControl(String glyph, Color fg, Color hoverBg) {
        JButton button = new JButton(glyph);
        button.setFont(UITheme.UI_BOLD.deriveFont(13f));
        button.setForeground(fg);
        button.setPreferredSize(new Dimension(JBUI.scale(28), JBUI.scale(24)));
        button.setMinimumSize(new Dimension(JBUI.scale(28), JBUI.scale(24)));
        button.setMaximumSize(new Dimension(JBUI.scale(28), JBUI.scale(24)));
        button.setFocusPainted(false);
        button.setBorderPainted(false);
        button.setOpaque(false);
        button.setContentAreaFilled(false);
        button.setBackground(new Color(0, 0, 0, 0));
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        UITheme.addHoverEffect(button, new Color(0, 0, 0, 0), hoverBg);
        return button;
    }

    private void installWindowDragging(JComponent handle) {
        MouseAdapter dragHandler = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                dragOffset = e.getPoint();
            }

            @Override public void mouseDragged(MouseEvent e) {
                if (dragOffset == null) return;
                Point screen = e.getLocationOnScreen();
                setLocation(screen.x - dragOffset.x, screen.y - dragOffset.y);
            }
        };
        handle.addMouseListener(dragHandler);
        handle.addMouseMotionListener(dragHandler);
    }

    private JButton buildRunButton() {
        JButton btn = new JButton("Run") {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                Color c1 = isEnabled() ? INDIGO : UITheme.BORDER_SUB;
                Color c2 = isEnabled() ? VIOLET : UITheme.BORDER_SUB;
                g2.setPaint(new GradientPaint(0, 0, c1, getWidth(), 0, c2));
                g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, JBUI.scale(10), JBUI.scale(10));

                if (isEnabled()) {
                    g2.setColor(new Color(255, 255, 255, 32));
                    g2.fillRoundRect(1, 1, getWidth() - 3, Math.max(8, getHeight() / 2), JBUI.scale(10), JBUI.scale(10));
                }

                g2.dispose();
                super.paintComponent(g);
            }
        };
        btn.setFont(UITheme.UI_BOLD.deriveFont(13f));
        btn.setForeground(Color.WHITE);
        btn.setOpaque(false);
        btn.setContentAreaFilled(false);
        btn.setBorderPainted(false);
        btn.setFocusPainted(false);
        btn.setBorder(JBUI.Borders.empty(8, 18));
        btn.setPreferredSize(new Dimension(JBUI.scale(96), JBUI.scale(34)));
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.setText("Run  \u25B6");
        return btn;
    }

    private JLabel buildOpBadge(OperationType op) {
        String text;
        Color  fg;
        Color  bg;
        if (op == OperationType.READ)       { text = "READ";  fg = TEAL;  bg = TEAL_PALE;  }
        else if (op == OperationType.WRITE) { text = "WRITE"; fg = AMBER; bg = AMBER_PALE; }
        else                                { text = "?";     fg = UITheme.MUTED; bg = UITheme.BORDER_SUB; }

        JLabel badge = new JLabel(text) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(getBackground());
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), JBUI.scale(4), JBUI.scale(4));
                g2.dispose();
                super.paintComponent(g);
            }
        };
        badge.setFont(UITheme.UI_SM.deriveFont(Font.BOLD));
        badge.setForeground(fg);
        badge.setBackground(bg);
        badge.setOpaque(false);
        badge.setBorder(JBUI.Borders.empty(2, 8));
        return badge;
    }

    // =========================================================================
    // Display
    // =========================================================================

    public void display(Point anchor) {
        if (anchor != null) positionNearAnchor(anchor);
        else setLocationRelativeTo(null);
        setVisible(true);
        toFront();

        getRootPane().registerKeyboardAction(
                e -> dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);

        int mask = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        getRootPane().registerKeyboardAction(
                e -> { if (runButton.isEnabled()) runExecution(); },
                KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, mask),
                JComponent.WHEN_IN_FOCUSED_WINDOW);

        connDot.start();
        ApplicationManager.getApplication().executeOnPooledThread(this::refreshAgentStatus);
    }

    @Override
    public void dispose() {
        connDot.stop();
        managedTimers.forEach(Timer::stop);
        managedTimers.clear();
        super.dispose();
    }

    private void positionNearAnchor(Point anchor) {
        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        int x = Math.max(0, Math.min(anchor.x + 16, screen.width  - POPUP_WIDTH));
        int y = Math.max(0, Math.min(anchor.y - POPUP_HEIGHT / 4, screen.height - POPUP_HEIGHT));
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
                    + "means something ELSE is listening on that port (not the RepoBuddy agent).\n\n"
                    + "Fix: ensure the agent is running on a free port.\n"
                    + "Raw error: " + msg);
        } else {
            errorPanel.setError("Execution failed.\n\n" + ex.getClass().getName() + ": " + msg);
        }
        tabs.setSelectedIndex(TAB_ERRORS);
    }

    // =========================================================================
    // Agent status
    // =========================================================================

    private void refreshAgentStatus() {
        String  url       = SpringAppUrlResolver.resolve(project);
        boolean reachable = url != null && isPortReachable(url);
        SwingUtilities.invokeLater(() -> {
            if (reachable) setAgentStatusOnline();
            else           setAgentStatusOffline();
        });
    }

    private void setAgentStatusOnline() {
        String url  = SpringAppUrlResolver.resolve(project);
        String host = url != null
                ? URI.create(url).getHost() + ":" + URI.create(url).getPort()
                : "localhost";
        agentStatusLabel.setText("Connected \u00B7 " + host);
        agentStatusLabel.setForeground(UITheme.SUCCESS);
        connDot.setOnline(true);
    }

    private void setAgentStatusOffline() {
        agentStatusLabel.setText("Agent Offline");
        agentStatusLabel.setForeground(UITheme.DANGER);
        connDot.setOnline(false);
    }

    private void setAgentStatusUnknown() {
        agentStatusLabel.setText("Checking\u2026");
        agentStatusLabel.setForeground(UITheme.MUTED);
        connDot.setOnline(null);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private void resetLoadingState() {
        progressBar.setIndeterminate(false);
        progressBar.setVisible(false);
        runButton.setEnabled(true);
    }

    private void boldTabTitle(int index) {
        String title = tabs.getTitleAt(index);
        if (!title.startsWith("<html>"))
            tabs.setTitleAt(index, "<html><b>" + title + "</b></html>");
        refreshTabDecorations();
    }

    private void resetTabTitles() {
        for (int i = 0; i < tabs.getTabCount(); i++) {
            String title = tabs.getTitleAt(i);
            if (title.startsWith("<html><b>") && title.endsWith("</b></html>"))
                tabs.setTitleAt(i, title.substring(9, title.length() - 11));
        }
        refreshTabDecorations();
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

    // =========================================================================
    // Inner: PulsingConnDot
    // =========================================================================

    private static final class PulsingConnDot extends JComponent {
        private Boolean online = null;
        private double  phase  = 0.0;
        private Timer   timer;

        PulsingConnDot() {
            setOpaque(false);
            setPreferredSize(new Dimension(JBUI.scale(12), JBUI.scale(12)));
        }

        void start() {
            if (timer != null && timer.isRunning()) return;
            timer = new Timer(50, e -> { phase += 0.12; repaint(); });
            timer.start();
        }

        void stop() {
            if (timer == null) return;
            timer.stop();
            timer = null;
        }

        void setOnline(Boolean status) {
            this.online = status;
            repaint();
        }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int cx = getWidth()  / 2;
            int cy = getHeight() / 2;
            int r  = JBUI.scale(4);

            JBColor dotColor;
            if (Boolean.TRUE.equals(online))  dotColor = new JBColor(new Color(0x0D9488), new Color(0x14B8A6));
            else if (Boolean.FALSE.equals(online)) dotColor = new JBColor(new Color(0xE11D48), new Color(0xFB7185));
            else                              dotColor = new JBColor(new Color(0x9CA3AF), new Color(0x6B7280));

            if (Boolean.TRUE.equals(online)) {
                double pulse  = Math.sin(phase);
                int    outerR = r + (int)(r * 0.7 * (pulse * 0.5 + 0.5));
                int    alpha  = (int)(70 * (1.0 - (pulse * 0.5 + 0.5)));
                g2.setColor(new JBColor(
                        new Color(dotColor.getRed(), dotColor.getGreen(), dotColor.getBlue(), alpha),
                        new Color(dotColor.getRed(), dotColor.getGreen(), dotColor.getBlue(), alpha)));
                g2.fillOval(cx - outerR, cy - outerR, outerR * 2, outerR * 2);
            }
            g2.setColor(dotColor);
            g2.fillOval(cx - r, cy - r, r * 2, r * 2);
            g2.dispose();
        }
    }
}
