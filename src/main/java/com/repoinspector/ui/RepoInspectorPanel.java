package com.repoinspector.ui;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiMethod;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import com.repoinspector.analysis.api.RepositoryAnalysisService;
import com.repoinspector.model.OperationType;
import com.repoinspector.model.RepositoryMethodInfo;
import com.repoinspector.runner.model.ParameterDef;
import com.repoinspector.runner.service.api.ParameterExtractionService;
import com.repoinspector.runner.ui.RepoRunnerPopup;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.event.AncestorEvent;
import javax.swing.event.AncestorListener;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.*;
import java.awt.geom.GeneralPath;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Main panel for the "Repository Usage" tab — premium dashboard design.
 */
public class RepoInspectorPanel extends JPanel implements Disposable {

    // ── Column indices ────────────────────────────────────────────────────────
    private static final String[] COLUMN_NAMES = {"Repository", "Method", "Signature", "Op", "Calls"};
    private static final int COL_REPO       = 0;
    private static final int COL_METHOD     = 1;
    private static final int COL_SIG        = 2;
    private static final int COL_OP         = 3;
    private static final int COL_CALL_COUNT = 4;

    // ── Accent palette (JBColor: light / dark) ────────────────────────────────
    static final JBColor INDIGO      = new JBColor(new Color(0x4F46E5), new Color(0x6366F1));
    static final JBColor INDIGO_PALE = new JBColor(new Color(0xEEF2FF), new Color(0x1E1B4B));
    static final JBColor TEAL        = new JBColor(new Color(0x0D9488), new Color(0x14B8A6));
    static final JBColor TEAL_PALE   = new JBColor(new Color(0xF0FDFA), new Color(0x042F2E));
    static final JBColor AMBER       = new JBColor(new Color(0xD97706), new Color(0xF59E0B));
    static final JBColor AMBER_PALE  = new JBColor(new Color(0xFFFBEB), new Color(0x2D1B00));
    static final JBColor ROSE        = new JBColor(new Color(0xE11D48), new Color(0xFB7185));
    static final JBColor ROSE_PALE   = new JBColor(new Color(0xFFF1F2), new Color(0x3D0018));

    // ── Font tiers ────────────────────────────────────────────────────────────
    private static final Font FONT_TITLE   = new Font(Font.SANS_SERIF, Font.BOLD,  18);
    private static final Font FONT_LABEL   = new Font(Font.SANS_SERIF, Font.PLAIN, 12);
    private static final Font FONT_MONO    = new Font(Font.MONOSPACED, Font.PLAIN, 12);
    private static final Font FONT_HERO    = new Font(Font.SANS_SERIF, Font.BOLD,  24);

    // ── State ─────────────────────────────────────────────────────────────────
    private final Project                           project;
    private final DefaultTableModel                 tableModel;
    private final JBTable                           table;
    private final TableRowSorter<DefaultTableModel> rowSorter;
    private final JTextField                        searchField;
    private final JToggleButton                     unusedOnlyToggle;
    private final JToggleButton                     currentFileToggle;
    private final JButton                           runButton;
    private final JLabel                            totalLabel;
    private final JLabel                            unusedLabel;
    private final JLabel                            visibleLabel;

    private HealthScoreRing     healthRing;
    private AnimatedNumber      totalCounter;
    private AnimatedNumber      unusedCounter;
    private AnimatedNumber      repoCounter;
    private CommitSparkline     sparkline;

    private List<PsiMethod>            methodList = List.of();
    private List<RepositoryMethodInfo> lastInfos  = List.of();
    @Nullable private String currentFileClassName  = null;

    private final List<Timer> managedTimers = new ArrayList<>();

    // =========================================================================
    // Constructor
    // =========================================================================

    public RepoInspectorPanel(Project project) {
        super(new BorderLayout());
        this.project = project;

        // Search field
        searchField = new JTextField(22);
        searchField.putClientProperty("JTextField.placeholderText", "Search repository or method\u2026");
        searchField.setFont(FONT_LABEL);
        searchField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e)  { applyFilter(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e)  { applyFilter(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { applyFilter(); }
        });
        bindKey(KeyEvent.VK_F, "focusSearch", () -> searchField.requestFocusInWindow());

        // Toggle buttons
        unusedOnlyToggle = UITheme.toggleButton("Unused only");
        unusedOnlyToggle.addItemListener(e -> applyFilter());
        currentFileToggle = UITheme.toggleButton("Current file");
        currentFileToggle.addItemListener(e -> {
            if (currentFileToggle.isSelected()) loadCurrentFileFilter();
            else { currentFileClassName = null; applyFilter(); }
        });

        // Run button
        runButton = UITheme.runButton();
        runButton.setEnabled(false);
        runButton.setToolTipText("Run selected method (Ctrl+Enter)");
        runButton.addActionListener(e -> openRunnerForSelected());
        bindKey(KeyEvent.VK_ENTER, "runSelected", () -> { if (runButton.isEnabled()) openRunnerForSelected(); });

        // Table
        tableModel = new DefaultTableModel(COLUMN_NAMES, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
            @Override public Class<?> getColumnClass(int c) {
                return c == COL_CALL_COUNT ? Integer.class : c == COL_OP ? OperationType.class : String.class;
            }
        };
        table = new JBTable(tableModel);
        rowSorter = new TableRowSorter<>(tableModel);
        table.setRowSorter(rowSorter);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setRowHeight(JBUI.scale(30));
        table.setShowGrid(false);
        table.setIntercellSpacing(new Dimension(0, 0));
        table.getTableHeader().setFont(FONT_LABEL.deriveFont(Font.BOLD));
        table.getTableHeader().setBackground(UITheme.TOOLBAR);
        table.getTableHeader().setForeground(UITheme.MUTED);
        table.setFont(FONT_LABEL);
        table.getColumnModel().getColumn(COL_REPO).setPreferredWidth(JBUI.scale(180));
        table.getColumnModel().getColumn(COL_METHOD).setPreferredWidth(JBUI.scale(180));
        table.getColumnModel().getColumn(COL_SIG).setPreferredWidth(JBUI.scale(280));
        table.getColumnModel().getColumn(COL_OP).setPreferredWidth(JBUI.scale(70));
        table.getColumnModel().getColumn(COL_CALL_COUNT).setPreferredWidth(JBUI.scale(80));
        table.getColumnModel().getColumn(COL_REPO).setCellRenderer(new RepoCellRenderer());
        table.getColumnModel().getColumn(COL_METHOD).setCellRenderer(new MethodCellRenderer());
        table.getColumnModel().getColumn(COL_SIG).setCellRenderer(new SigCellRenderer());
        table.getColumnModel().getColumn(COL_OP).setCellRenderer(new OpBadgeCellRenderer());
        table.getColumnModel().getColumn(COL_CALL_COUNT).setCellRenderer(new CallsBadgeCellRenderer());
        table.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) { if (e.getClickCount() == 2) navigateToSelected(); }
        });
        table.getSelectionModel().addListSelectionListener(
                e -> { if (!e.getValueIsAdjusting()) runButton.setEnabled(table.getSelectedRow() >= 0); });

        // Status bar labels
        totalLabel   = styledLabel("0 methods", UITheme.UI_SM, null);
        unusedLabel  = styledLabel("0 unused",  UITheme.UI_SM, UITheme.DANGER);
        visibleLabel = styledLabel("",           UITheme.UI_SM, UITheme.MUTED);

        // Layout
        add(buildHeader(),    BorderLayout.NORTH);
        add(buildCenter(),    BorderLayout.CENTER);
        add(buildStatusBar(), BorderLayout.SOUTH);

        // Staggered entrance on first show
        addAncestorListener(new AncestorListener() {
            boolean fired = false;
            @Override public void ancestorAdded(AncestorEvent e) { if (!fired) { fired = true; triggerEntrance(); } }
            @Override public void ancestorRemoved(AncestorEvent e) {}
            @Override public void ancestorMoved(AncestorEvent e) {}
        });

        SwingUtilities.invokeLater(this::runAnalysis);
    }

    // =========================================================================
    // Layout builders
    // =========================================================================

    private JPanel buildHeader() {
        GradientHeader header = new GradientHeader();
        header.setLayout(new BorderLayout(JBUI.scale(12), 0));
        header.setBorder(JBUI.Borders.empty(12, 14, 12, 14));

        // Left: icon + title
        JLabel icon  = new JLabel("\uD83D\uDCCA");
        icon.setFont(icon.getFont().deriveFont(20f));
        JLabel title = new JLabel("Repository Usage");
        title.setFont(FONT_TITLE);
        title.setForeground(new JBColor(new Color(0x1E1B4B), new Color(0xE0E7FF)));
        JLabel sub = new JLabel("Spring Data JPA analysis");
        sub.setFont(UITheme.UI_SM);
        sub.setForeground(new JBColor(new Color(0x6366F1), new Color(0x818CF8)));
        JPanel titleCol = boxColumn(title, sub);

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0));
        left.setOpaque(false);
        left.add(icon);
        left.add(titleCol);

        // Center: stat mini-cards
        totalCounter  = new AnimatedNumber(0);
        unusedCounter = new AnimatedNumber(0);
        repoCounter   = new AnimatedNumber(0);
        JPanel stats = new JPanel(new FlowLayout(FlowLayout.CENTER, JBUI.scale(8), 0));
        stats.setOpaque(false);
        stats.add(miniStatCard(totalCounter,  "Methods", INDIGO, INDIGO_PALE));
        stats.add(miniStatCard(unusedCounter, "Unused",  ROSE,   ROSE_PALE));
        stats.add(miniStatCard(repoCounter,   "Repos",   TEAL,   TEAL_PALE));

        // Right: health ring
        healthRing = new HealthScoreRing(100);
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        right.setOpaque(false);
        right.add(healthRing);

        header.add(left,  BorderLayout.WEST);
        header.add(stats, BorderLayout.CENTER);
        header.add(right, BorderLayout.EAST);
        return header;
    }

    private JPanel miniStatCard(AnimatedNumber counter, String label, JBColor accent, JBColor bg) {
        RoundedCard card = new RoundedCard(bg, JBUI.scale(10));
        card.setLayout(new BorderLayout(0, JBUI.scale(2)));
        card.setBorder(JBUI.Borders.empty(8, 14, 8, 14));
        card.setPreferredSize(new Dimension(JBUI.scale(88), JBUI.scale(58)));
        counter.setFont(FONT_HERO.deriveFont(Font.BOLD, JBUI.scaleFontSize(22f)));
        counter.setForeground(accent);
        counter.setHorizontalAlignment(SwingConstants.CENTER);
        JLabel lbl = styledLabel(label, UITheme.UI_SM, UITheme.MUTED);
        lbl.setHorizontalAlignment(SwingConstants.CENTER);
        card.add(counter, BorderLayout.CENTER);
        card.add(lbl,     BorderLayout.SOUTH);
        return card;
    }

    private JPanel buildCenter() {
        // Search wrapper
        JPanel searchWrap = new JPanel(new BorderLayout(JBUI.scale(4), 0));
        searchWrap.setOpaque(false);
        searchWrap.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(UITheme.BORDER_SUB, 1),
                JBUI.Borders.empty(2, 6)));
        JLabel searchIcon = new JLabel("\uD83D\uDD0D");
        searchIcon.setFont(UITheme.UI_SM);
        searchIcon.setForeground(UITheme.MUTED);
        JLabel kbdHint = new JLabel("Ctrl+F");
        kbdHint.setFont(UITheme.UI_SM);
        kbdHint.setForeground(UITheme.MUTED);
        kbdHint.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(UITheme.BORDER_SUB, 1), JBUI.Borders.empty(0, 4)));
        searchWrap.add(searchIcon,  BorderLayout.WEST);
        searchWrap.add(searchField, BorderLayout.CENTER);
        searchWrap.add(kbdHint,     BorderLayout.EAST);

        // Action buttons
        JButton exportBtn  = UITheme.iconButton("\u21A7");
        JButton refreshBtn = UITheme.iconButton("\u21BB");
        exportBtn.setToolTipText("Export CSV");
        refreshBtn.setToolTipText("Refresh analysis");
        exportBtn.addActionListener(e -> exportCsv());
        refreshBtn.addActionListener(e -> runAnalysis());

        // Toolbar
        JPanel toolbar = new JPanel();
        toolbar.setLayout(new BoxLayout(toolbar, BoxLayout.X_AXIS));
        toolbar.setBackground(UITheme.TOOLBAR);
        toolbar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, UITheme.BORDER_SUB),
                JBUI.Borders.empty(5, 8)));
        toolbar.add(searchWrap);
        toolbar.add(Box.createHorizontalStrut(JBUI.scale(6)));
        toolbar.add(UITheme.toolbarDivider());
        toolbar.add(Box.createHorizontalStrut(JBUI.scale(6)));
        toolbar.add(unusedOnlyToggle);
        toolbar.add(Box.createHorizontalStrut(JBUI.scale(4)));
        toolbar.add(currentFileToggle);
        toolbar.add(Box.createHorizontalGlue());
        toolbar.add(exportBtn);
        toolbar.add(Box.createHorizontalStrut(JBUI.scale(2)));
        toolbar.add(refreshBtn);
        toolbar.add(Box.createHorizontalStrut(JBUI.scale(4)));
        toolbar.add(UITheme.toolbarDivider());
        toolbar.add(Box.createHorizontalStrut(JBUI.scale(6)));
        toolbar.add(runButton);

        // Sparkline strip
        sparkline = new CommitSparkline();
        sparkline.setPreferredSize(new Dimension(0, JBUI.scale(52)));

        JPanel top = new JPanel(new BorderLayout());
        top.add(toolbar,  BorderLayout.NORTH);
        top.add(sparkline, BorderLayout.CENTER);

        // Table scroll
        JBScrollPane scroll = new JBScrollPane(table);
        scroll.setBorder(BorderFactory.createEmptyBorder());

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, top, scroll);
        split.setDividerSize(JBUI.scale(1));
        split.setBorder(null);
        split.setResizeWeight(0.0);
        split.setDividerLocation(JBUI.scale(86));

        JPanel center = new JPanel(new BorderLayout());
        center.add(split, BorderLayout.CENTER);
        return center;
    }

    private JPanel buildStatusBar() {
        JLabel hint = styledLabel("Double-click to navigate \u00B7 \u21B5 Run", UITheme.UI_SM, UITheme.MUTED);
        JLabel dot  = styledLabel(" \u00B7 ", UITheme.UI_SM, UITheme.MUTED);

        JPanel left = new JPanel();
        left.setLayout(new BoxLayout(left, BoxLayout.X_AXIS));
        left.setOpaque(false);
        left.add(totalLabel);
        left.add(dot);
        left.add(unusedLabel);
        left.add(visibleLabel);

        JPanel bar = new JPanel(new BorderLayout());
        bar.setBackground(UITheme.TOOLBAR);
        bar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, UITheme.BORDER_SUB),
                JBUI.Borders.empty(4, 10)));
        bar.add(left, BorderLayout.WEST);
        bar.add(hint, BorderLayout.EAST);
        return bar;
    }

    // =========================================================================
    // Entrance animation
    // =========================================================================

    private void triggerEntrance() {
        timedOnce(100, () -> healthRing.startAnimation());
        timedOnce(200, () -> animateCounters());
        timedOnce(300, () -> { if (sparkline != null) sparkline.startAnimation(); });
    }

    private void animateCounters() {
        if (lastInfos.isEmpty()) return;
        totalCounter.animateTo(lastInfos.size());
        unusedCounter.animateTo((int) lastInfos.stream().filter(i -> i.callCount() == 0).count());
        long repos = lastInfos.stream().map(RepositoryMethodInfo::repositoryName).distinct().count();
        repoCounter.animateTo((int) repos);
    }

    private void timedOnce(int delayMs, Runnable action) {
        Timer t = new Timer(delayMs, e -> action.run());
        t.setRepeats(false);
        t.start();
        managedTimers.add(t);
    }

    // =========================================================================
    // Analysis
    // =========================================================================

    private void runAnalysis() {
        DumbService.getInstance(project).runWhenSmart(() ->
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                RepositoryAnalysisService.AnalysisResult result =
                        project.getService(RepositoryAnalysisService.class).analyzeAll();
                SwingUtilities.invokeLater(() -> updateTable(result));
            })
        );
    }

    private void updateTable(RepositoryAnalysisService.AnalysisResult result) {
        tableModel.setRowCount(0);
        methodList = result.methods();
        lastInfos  = result.infos();
        currentFileClassName = null;

        for (RepositoryMethodInfo info : lastInfos) {
            tableModel.addRow(new Object[]{
                    info.repositoryName(), info.methodName(),
                    info.methodSignature(), info.operationType(), info.callCount()
            });
        }

        sparkline.setData(lastInfos.stream().mapToInt(RepositoryMethodInfo::callCount).toArray());
        animateCounters();

        int health = lastInfos.isEmpty() ? 100
                : (int)(100.0 * lastInfos.stream().filter(i -> i.callCount() > 0).count() / lastInfos.size());
        healthRing.setScore(health);

        if (currentFileToggle.isSelected()) loadCurrentFileFilter();
        else applyFilter();
    }

    // =========================================================================
    // Filtering
    // =========================================================================

    private void applyFilter() {
        String  text    = searchField.getText().trim();
        boolean unused  = unusedOnlyToggle.isSelected();
        boolean curFile = currentFileToggle.isSelected() && currentFileClassName != null;

        if (text.isEmpty() && !unused && !curFile) {
            rowSorter.setRowFilter(null);
        } else {
            List<RowFilter<DefaultTableModel, Integer>> filters = new ArrayList<>();
            if (!text.isEmpty()) {
                String esc = java.util.regex.Pattern.quote(text);
                filters.add(RowFilter.orFilter(Arrays.asList(
                        RowFilter.regexFilter("(?i)" + esc, COL_REPO),
                        RowFilter.regexFilter("(?i)" + esc, COL_METHOD))));
            }
            if (unused) filters.add(new RowFilter<>() {
                @Override public boolean include(Entry<? extends DefaultTableModel, ? extends Integer> e) {
                    Object v = e.getValue(COL_CALL_COUNT);
                    return v instanceof Integer c && c == 0;
                }
            });
            if (curFile) {
                String cls = currentFileClassName;
                filters.add(new RowFilter<>() {
                    @Override public boolean include(Entry<? extends DefaultTableModel, ? extends Integer> e) {
                        Object v = e.getValue(COL_REPO);
                        return cls.equalsIgnoreCase(v != null ? v.toString() : "");
                    }
                });
            }
            rowSorter.setRowFilter(RowFilter.andFilter(filters));
        }
        updateStatusBar();
    }

    private void loadCurrentFileFilter() {
        com.intellij.openapi.editor.Editor editor =
                FileEditorManager.getInstance(project).getSelectedTextEditor();
        if (editor == null) { currentFileToggle.setSelected(false); return; }
        VirtualFile vf = FileDocumentManager.getInstance().getFile(editor.getDocument());
        if (vf == null)     { currentFileToggle.setSelected(false); return; }
        currentFileClassName = vf.getNameWithoutExtension();
        applyFilter();
    }

    private void updateStatusBar() {
        int  total   = lastInfos.size();
        long unused  = lastInfos.stream().filter(i -> i.callCount() == 0).count();
        int  visible = table.getRowCount();
        totalLabel.setText("<html><b>" + total   + "</b> method" + (total == 1 ? "" : "s") + "</html>");
        unusedLabel.setText("<html><b>" + unused  + "</b> unused</html>");
        visibleLabel.setVisible(visible < total);
        if (visible < total) visibleLabel.setText(" \u00B7 showing <b>" + visible + "</b>");
    }

    // =========================================================================
    // Actions
    // =========================================================================

    private void exportCsv() {
        if (lastInfos.isEmpty()) return;
        StringBuilder sb = new StringBuilder("Repository,Method,Signature,Op,Calls\n");
        for (RepositoryMethodInfo i : lastInfos) {
            sb.append(esc(i.repositoryName())).append(',')
              .append(esc(i.methodName())).append(',')
              .append(esc(i.methodSignature())).append(',')
              .append(i.operationType().name()).append(',')
              .append(i.callCount()).append('\n');
        }
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(sb.toString()), null);
    }

    private static String esc(String v) {
        if (v == null) return "";
        return (v.contains(",") || v.contains("\"") || v.contains("\n"))
                ? "\"" + v.replace("\"", "\"\"") + "\"" : v;
    }

    private void openRunnerForSelected() {
        int vr = table.getSelectedRow();
        if (vr < 0) return;
        int mr = table.convertRowIndexToModel(vr);
        if (mr < 0 || mr >= methodList.size()) return;
        PsiMethod method = methodList.get(mr);
        if (method == null || !method.isValid()) return;

        ApplicationManager.getApplication().runReadAction(() -> {
            PsiClass cls = method.getContainingClass();
            String fqn   = (cls != null && cls.getQualifiedName() != null) ? cls.getQualifiedName() : "";
            List<ParameterDef> params = ApplicationManager.getApplication()
                    .getService(ParameterExtractionService.class).extract(method);
            SwingUtilities.invokeLater(() -> new RepoRunnerPopup(project, fqn, method.getName(), params).display(null));
        });
    }

    private void navigateToSelected() {
        int vr = table.getSelectedRow();
        if (vr < 0) return;
        int mr = table.convertRowIndexToModel(vr);
        if (mr < 0 || mr >= methodList.size()) return;
        PsiMethod m = methodList.get(mr);
        if (m != null && m.isValid()) m.navigate(true);
    }

    // =========================================================================
    // Disposable
    // =========================================================================

    @Override
    public void dispose() {
        managedTimers.forEach(Timer::stop);
        managedTimers.clear();
        if (healthRing != null) healthRing.dispose();
        if (sparkline  != null) sparkline.dispose();
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private void bindKey(int vk, String name, Runnable action) {
        KeyStroke ks = KeyStroke.getKeyStroke(vk, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx());
        getInputMap(WHEN_IN_FOCUSED_WINDOW).put(ks, name);
        getActionMap().put(name, new AbstractAction() {
            public void actionPerformed(ActionEvent e) { action.run(); }
        });
    }

    private static JLabel styledLabel(String text, Font font, @Nullable Color fg) {
        JLabel l = new JLabel(text);
        l.setFont(font);
        if (fg != null) l.setForeground(fg);
        return l;
    }

    private static JPanel boxColumn(JComponent... items) {
        JPanel p = new JPanel();
        p.setOpaque(false);
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        for (JComponent c : items) p.add(c);
        return p;
    }

    // =========================================================================
    // Inner: GradientHeader
    // =========================================================================

    private static final class GradientHeader extends JPanel {
        GradientHeader() { setOpaque(false); }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color c1 = new JBColor(new Color(0xEEF2FF), new Color(0x1E1B4B));
            Color c2 = new JBColor(new Color(0xF5F3FF), new Color(0x1C1333));
            g2.setPaint(new GradientPaint(0, 0, c1, getWidth(), getHeight(), c2));
            g2.fillRect(0, 0, getWidth(), getHeight());
            g2.setColor(new JBColor(new Color(0xC7D2FE), new Color(0x312E81)));
            g2.drawLine(0, getHeight() - 1, getWidth(), getHeight() - 1);
            g2.dispose();
            super.paintComponent(g);
        }
    }

    // =========================================================================
    // Inner: RoundedCard — card surface with hover-lift shadow
    // =========================================================================

    private static final class RoundedCard extends JPanel {
        private final Color bg;
        private final int   arc;
        private int         shadowOffset = 0;

        RoundedCard(Color bg, int arc) {
            this.bg  = bg;
            this.arc = arc;
            setOpaque(false);
            addMouseListener(new MouseAdapter() {
                Timer t;
                @Override public void mouseEntered(MouseEvent e) { animate(true);  }
                @Override public void mouseExited(MouseEvent e)  { animate(false); }
                void animate(boolean in) {
                    if (t != null) t.stop();
                    t = new Timer(16, ev -> {
                        shadowOffset = Math.max(0, Math.min(4, shadowOffset + (in ? 1 : -1)));
                        repaint();
                        if (shadowOffset == (in ? 4 : 0)) t.stop();
                    });
                    t.start();
                }
            });
        }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            for (int i = shadowOffset; i > 0; i--) {
                g2.setColor(new JBColor(new Color(0, 0, 0, 15 * i), new Color(0, 0, 0, 15 * i)));
                g2.fillRoundRect(i, i, getWidth() - i, getHeight() - i, arc, arc);
            }
            g2.setColor(bg);
            g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, arc, arc);
            g2.dispose();
            super.paintComponent(g);
        }
    }

    // =========================================================================
    // Inner: HealthScoreRing — animated arc, color shifts with score
    // =========================================================================

    private static final class HealthScoreRing extends JPanel {
        private int   score;
        private float animAngle = 0f;
        private Timer animTimer;

        HealthScoreRing(int score) {
            this.score = score;
            setOpaque(false);
            setPreferredSize(new Dimension(JBUI.scale(62), JBUI.scale(62)));
            setToolTipText("Health score: " + score + "%");
        }

        void startAnimation() { animateTo(score * 3.6f); }

        void setScore(int newScore) {
            score = newScore;
            setToolTipText("Health score: " + score + "%");
            animateTo(score * 3.6f);
        }

        private void animateTo(float target) {
            animAngle = 0f;
            if (animTimer != null) animTimer.stop();
            float step = target / 30f;
            animTimer = new Timer(16, e -> {
                animAngle = Math.min(animAngle + step, target);
                repaint();
                if (animAngle >= target) animTimer.stop();
            });
            animTimer.start();
        }

        void dispose() { if (animTimer != null) animTimer.stop(); }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int pad  = JBUI.scale(6);
            int size = Math.min(getWidth(), getHeight()) - pad * 2;
            int x    = (getWidth()  - size) / 2;
            int y    = (getHeight() - size) / 2;
            float sw = JBUI.scaleFontSize(5.5f);
            g2.setStroke(new BasicStroke(sw, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.setColor(UITheme.BORDER_SUB);
            g2.drawArc(x, y, size, size, 90, -360);
            Color arcColor = score >= 80 ? TEAL : score >= 50 ? AMBER : ROSE;
            g2.setColor(arcColor);
            g2.drawArc(x, y, size, size, 90, -(int) animAngle);
            g2.setFont(UITheme.UI_SM.deriveFont(Font.BOLD, JBUI.scaleFontSize(11f)));
            g2.setColor(arcColor);
            String txt = score + "%";
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(txt, x + (size - fm.stringWidth(txt)) / 2, y + (size + fm.getAscent()) / 2 - JBUI.scale(2));
            g2.dispose();
        }
    }

    // =========================================================================
    // Inner: AnimatedNumber — JLabel that counts up with easeOutQuart
    // =========================================================================

    private static final class AnimatedNumber extends JLabel {
        private int   current = 0;
        private Timer countTimer;

        AnimatedNumber(int initial) { super(String.valueOf(initial)); }

        void animateTo(int target) {
            if (countTimer != null) countTimer.stop();
            int   start     = current;
            long  startTime = System.currentTimeMillis();
            long  duration  = 800L;
            countTimer = new Timer(16, e -> {
                double t = Math.min(1.0, (System.currentTimeMillis() - startTime) / (double) duration);
                double eased = 1.0 - Math.pow(1.0 - t, 4);
                current = (int) Math.round(start + (target - start) * eased);
                setText(String.valueOf(current));
                if (t >= 1.0) { countTimer.stop(); current = target; setText(String.valueOf(target)); }
            });
            countTimer.start();
        }
    }

    // =========================================================================
    // Inner: CommitSparkline — animated area chart of call-count distribution
    // =========================================================================

    private static final class CommitSparkline extends JPanel {
        private int[] data      = new int[0];
        private float clipFrac  = 1f;
        private Timer drawTimer;

        CommitSparkline() {
            setOpaque(false);
            setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, UITheme.BORDER_SUB));
        }

        void setData(int[] newData) {
            data     = newData.clone();
            clipFrac = 0f;
            startAnimation();
        }

        void startAnimation() {
            if (drawTimer != null) drawTimer.stop();
            clipFrac  = 0f;
            drawTimer = new Timer(16, e -> {
                clipFrac = Math.min(1f, clipFrac + 0.04f);
                repaint();
                if (clipFrac >= 1f) drawTimer.stop();
            });
            drawTimer.start();
        }

        void dispose() { if (drawTimer != null) drawTimer.stop(); }

        @Override protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (data.length < 2) return;
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int w = getWidth(), h = getHeight();
            int px = JBUI.scale(12), py = JBUI.scale(8);
            int maxVal = Arrays.stream(data).max().orElse(1);
            if (maxVal == 0) maxVal = 1;
            float step = (float)(w - px * 2) / (data.length - 1);

            // Clip to animated region
            g2.clipRect(0, 0, (int)(w * clipFrac), h);

            // Point coords
            float[] xs = new float[data.length];
            float[] ys = new float[data.length];
            for (int i = 0; i < data.length; i++) {
                xs[i] = px + i * step;
                ys[i] = py + (1f - (float) data[i] / maxVal) * (h - py * 2);
            }

            // Fill area
            GeneralPath fill = new GeneralPath();
            fill.moveTo(xs[0], h - py);
            for (int i = 0; i < data.length; i++) fill.lineTo(xs[i], ys[i]);
            fill.lineTo(xs[data.length - 1], h - py);
            fill.closePath();
            Color indigoAlpha35 = new JBColor(
                    new Color(INDIGO.getRed(), INDIGO.getGreen(), INDIGO.getBlue(), 35),
                    new Color(INDIGO.getRed(), INDIGO.getGreen(), INDIGO.getBlue(), 35));
            g2.setColor(indigoAlpha35);
            g2.fill(fill);

            // Stroke line
            GeneralPath line = new GeneralPath();
            line.moveTo(xs[0], ys[0]);
            for (int i = 1; i < data.length; i++) line.lineTo(xs[i], ys[i]);
            g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.setColor(INDIGO);
            g2.draw(line);

            // Glow dots
            Color indigoAlpha50 = new JBColor(
                    new Color(INDIGO.getRed(), INDIGO.getGreen(), INDIGO.getBlue(), 50),
                    new Color(INDIGO.getRed(), INDIGO.getGreen(), INDIGO.getBlue(), 50));
            int r = JBUI.scale(3);
            for (int i = 0; i < data.length; i++) {
                g2.setColor(indigoAlpha50);
                g2.fillOval((int)(xs[i] - r * 2), (int)(ys[i] - r * 2), r * 4, r * 4);
                g2.setColor(INDIGO);
                g2.fillOval((int)(xs[i] - r), (int)(ys[i] - r), r * 2, r * 2);
            }
            g2.dispose();
        }
    }

    // =========================================================================
    // Cell renderers
    // =========================================================================

    private static final class RepoCellRenderer extends DefaultTableCellRenderer {
        @Override public Component getTableCellRendererComponent(
                JTable t, Object v, boolean sel, boolean focus, int r, int c) {
            super.getTableCellRendererComponent(t, v, sel, focus, r, c);
            setFont(FONT_MONO.deriveFont(Font.BOLD));
            if (!sel) setForeground(UITheme.GOLD);
            setBorder(JBUI.Borders.empty(0, 8, 0, 4));
            return this;
        }
    }

    private static final class MethodCellRenderer extends DefaultTableCellRenderer {
        @Override public Component getTableCellRendererComponent(
                JTable t, Object v, boolean sel, boolean focus, int r, int c) {
            super.getTableCellRendererComponent(t, v, sel, focus, r, c);
            setFont(FONT_MONO);
            if (!sel) setForeground(INDIGO);
            setBorder(JBUI.Borders.empty(0, 4));
            return this;
        }
    }

    private static final class SigCellRenderer extends DefaultTableCellRenderer {
        @Override public Component getTableCellRendererComponent(
                JTable t, Object v, boolean sel, boolean focus, int r, int c) {
            super.getTableCellRendererComponent(t, v, sel, focus, r, c);
            setFont(FONT_MONO.deriveFont(11f));
            if (!sel) setForeground(UITheme.INK_DIM);
            setBorder(JBUI.Borders.empty(0, 4));
            return this;
        }
    }

    private static final class OpBadgeCellRenderer extends DefaultTableCellRenderer {
        @Override public Component getTableCellRendererComponent(
                JTable t, Object v, boolean sel, boolean focus, int r, int c) {
            super.getTableCellRendererComponent(t, v, sel, focus, r, c);
            setText("");
            setHorizontalAlignment(CENTER);
            return new OpBadge(v instanceof OperationType op ? op : OperationType.UNKNOWN, sel);
        }
    }

    private static final class OpBadge extends JComponent {
        private final OperationType op;
        private final boolean       sel;
        OpBadge(OperationType op, boolean sel) { this.op = op; this.sel = sel; setOpaque(false); }

        @Override protected void paintComponent(Graphics g) {
            if (sel) return;
            String label;
            Color  bg;
            Color  fg;
            if (op == OperationType.READ)       { label = "READ";  bg = TEAL_PALE;         fg = TEAL;         }
            else if (op == OperationType.WRITE) { label = "WRITE"; bg = AMBER_PALE;        fg = AMBER;        }
            else                                { label = "?";     bg = UITheme.BORDER_SUB; fg = UITheme.MUTED; }

            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setFont(UITheme.UI_SM.deriveFont(Font.BOLD));
            FontMetrics fm = g2.getFontMetrics();
            int tw = fm.stringWidth(label);
            int pw = tw + JBUI.scale(10);
            int ph = JBUI.scale(16);
            int x  = (getWidth()  - pw) / 2;
            int y  = (getHeight() - ph) / 2;
            g2.setColor(bg); g2.fillRoundRect(x, y, pw, ph, JBUI.scale(4), JBUI.scale(4));
            g2.setColor(fg); g2.drawString(label, x + JBUI.scale(5), y + ph - JBUI.scale(4));
            g2.dispose();
        }
    }

    private static final class CallsBadgeCellRenderer extends DefaultTableCellRenderer {
        @Override public Component getTableCellRendererComponent(
                JTable t, Object v, boolean sel, boolean focus, int r, int c) {
            super.getTableCellRendererComponent(t, v, sel, focus, r, c);
            setText(""); setHorizontalAlignment(RIGHT);
            return new CallsDot(v instanceof Integer i ? i : 0, sel);
        }
    }

    private static final class CallsDot extends JComponent {
        private final int     count;
        private final boolean sel;
        CallsDot(int count, boolean sel) { this.count = count; this.sel = sel; setOpaque(false); }

        @Override protected void paintComponent(Graphics g) {
            if (sel) return;
            Color dot;
            if      (count == 0) dot = ROSE;
            else if (count <= 2) dot = AMBER;
            else                 dot = TEAL;
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            String text = String.valueOf(count);
            g2.setFont(UITheme.UI_SM);
            FontMetrics fm = g2.getFontMetrics();
            int ds  = JBUI.scale(6);
            int gap = JBUI.scale(4);
            int tw  = fm.stringWidth(text);
            int x   = getWidth() - ds - gap - tw - JBUI.scale(8);
            int cy  = getHeight() / 2;
            g2.setColor(dot);
            g2.fillOval(x, cy - ds / 2, ds, ds);
            g2.setColor(UITheme.INK_DIM);
            g2.drawString(text, x + ds + gap, cy + fm.getAscent() / 2 - 1);
            g2.dispose();
        }
    }
}
