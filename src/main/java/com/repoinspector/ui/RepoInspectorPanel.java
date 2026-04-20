package com.repoinspector.ui;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiMethod;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.table.JBTable;
import com.repoinspector.analysis.api.RepositoryAnalysisService;
import com.repoinspector.model.OperationType;
import com.repoinspector.model.RepositoryMethodInfo;
import com.repoinspector.runner.model.ParameterDef;
import com.repoinspector.runner.service.api.ParameterExtractionService;
import com.repoinspector.runner.ui.RepoRunnerPopup;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Main panel for the "Repository Usage" tab.
 * Redesigned to match the AfterTable reference design.
 */
public class RepoInspectorPanel extends JPanel {

    private static final String[] COLUMN_NAMES  = {"Repository", "Method", "Signature", "Op", "Calls"};
    private static final int COL_REPO       = 0;
    private static final int COL_METHOD     = 1;
    private static final int COL_SIG        = 2;
    private static final int COL_OP         = 3;
    private static final int COL_CALL_COUNT = 4;

    private final Project          project;
    private final DefaultTableModel tableModel;
    private final JBTable          table;
    private final TableRowSorter<DefaultTableModel> rowSorter;
    private final JTextField       searchField;
    private final JToggleButton    unusedOnlyToggle;
    private final JToggleButton    currentFileToggle;
    private final JButton          runButton;

    // Status bar components
    private final JLabel totalLabel;
    private final JLabel unusedLabel;
    private final JLabel visibleLabel;

    private List<PsiMethod>            methodList = List.of();
    private List<RepositoryMethodInfo> lastInfos  = List.of();

    @Nullable private String currentFileClassName = null;

    public RepoInspectorPanel(Project project) {
        super(new BorderLayout());
        this.project = project;

        // ── Search field ──────────────────────────────────────────────────────
        searchField = new JTextField(22);
        searchField.putClientProperty("JTextField.placeholderText", "Search repository or method\u2026");
        searchField.setFont(UITheme.UI);
        searchField.setToolTipText("Ctrl+F");
        searchField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e)  { applyFilter(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e)  { applyFilter(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { applyFilter(); }
        });

        // Ctrl+F focuses search
        KeyStroke ctrlF = KeyStroke.getKeyStroke(KeyEvent.VK_F, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx());
        getInputMap(WHEN_IN_FOCUSED_WINDOW).put(ctrlF, "focusSearch");
        getActionMap().put("focusSearch", new javax.swing.AbstractAction() {
            public void actionPerformed(java.awt.event.ActionEvent e) { searchField.requestFocusInWindow(); }
        });

        // ── Toggle buttons ────────────────────────────────────────────────────
        unusedOnlyToggle = UITheme.toggleButton("Unused only");
        unusedOnlyToggle.setToolTipText("Show methods with zero call sites");
        unusedOnlyToggle.addItemListener(e -> applyFilter());

        currentFileToggle = UITheme.toggleButton("Current file");
        currentFileToggle.setToolTipText("Show only methods from the open editor's class");
        currentFileToggle.addItemListener(e -> {
            if (currentFileToggle.isSelected()) loadCurrentFileFilter();
            else { currentFileClassName = null; applyFilter(); }
        });

        // ── Icon buttons ──────────────────────────────────────────────────────
        JButton exportBtn   = UITheme.iconButton("\u21A7");
        JButton refreshBtn  = UITheme.iconButton("\u21BB");
        exportBtn.setToolTipText("Export CSV");
        refreshBtn.setToolTipText("Refresh analysis");
        exportBtn.addActionListener(e -> exportCsv());
        refreshBtn.addActionListener(e -> runAnalysis());

        // ── Run button ────────────────────────────────────────────────────────
        runButton = UITheme.runButton();
        runButton.setEnabled(false);
        runButton.setToolTipText("Run selected method (Ctrl+Enter)");
        runButton.addActionListener(e -> openRunnerPopupForSelected());

        // Ctrl+Enter runs
        KeyStroke ctrlEnter = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER,
                Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx());
        getInputMap(WHEN_IN_FOCUSED_WINDOW).put(ctrlEnter, "runSelected");
        getActionMap().put("runSelected", new javax.swing.AbstractAction() {
            public void actionPerformed(java.awt.event.ActionEvent e) {
                if (runButton.isEnabled()) openRunnerPopupForSelected();
            }
        });

        // ── Search wrapper panel ──────────────────────────────────────────────
        JPanel searchWrapper = new JPanel(new BorderLayout(4, 0));
        searchWrapper.setOpaque(false);
        searchWrapper.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(UITheme.BORDER_SUB, 1),
                BorderFactory.createEmptyBorder(2, 6, 2, 6)));
        JLabel searchIcon = new JLabel("\uD83D\uDD0D");
        searchIcon.setFont(UITheme.UI_SM);
        searchIcon.setForeground(UITheme.MUTED);
        searchWrapper.add(searchIcon, BorderLayout.WEST);
        searchWrapper.add(searchField, BorderLayout.CENTER);
        JLabel searchKbd = new JLabel("Ctrl+F");
        searchKbd.setFont(UITheme.UI_SM);
        searchKbd.setForeground(UITheme.MUTED);
        searchKbd.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(UITheme.BORDER_SUB, 1),
                BorderFactory.createEmptyBorder(0, 4, 0, 4)));
        searchWrapper.add(searchKbd, BorderLayout.EAST);

        // ── Toolbar assembly ──────────────────────────────────────────────────
        JPanel toolbar = new JPanel();
        toolbar.setLayout(new BoxLayout(toolbar, BoxLayout.X_AXIS));
        toolbar.setBackground(UITheme.TOOLBAR);
        toolbar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, UITheme.BORDER_SUB),
                BorderFactory.createEmptyBorder(5, 8, 5, 8)));

        toolbar.add(searchWrapper);
        toolbar.add(Box.createHorizontalStrut(6));
        toolbar.add(UITheme.toolbarDivider());
        toolbar.add(Box.createHorizontalStrut(6));
        toolbar.add(unusedOnlyToggle);
        toolbar.add(Box.createHorizontalStrut(4));
        toolbar.add(currentFileToggle);
        toolbar.add(Box.createHorizontalGlue());
        toolbar.add(exportBtn);
        toolbar.add(Box.createHorizontalStrut(2));
        toolbar.add(refreshBtn);
        toolbar.add(Box.createHorizontalStrut(4));
        toolbar.add(UITheme.toolbarDivider());
        toolbar.add(Box.createHorizontalStrut(6));
        toolbar.add(runButton);

        add(toolbar, BorderLayout.NORTH);

        // ── Table ─────────────────────────────────────────────────────────────
        tableModel = new DefaultTableModel(COLUMN_NAMES, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
            @Override public Class<?> getColumnClass(int c) {
                if (c == COL_CALL_COUNT) return Integer.class;
                if (c == COL_OP)         return OperationType.class;
                return String.class;
            }
        };

        table = new JBTable(tableModel);
        rowSorter = new TableRowSorter<>(tableModel);
        table.setRowSorter(rowSorter);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setRowHeight(28);
        table.setShowGrid(false);
        table.setIntercellSpacing(new Dimension(0, 0));
        table.getTableHeader().setFont(UITheme.UI_SM.deriveFont(Font.BOLD));
        table.getTableHeader().setBackground(UITheme.TOOLBAR);
        table.getTableHeader().setForeground(UITheme.MUTED);
        table.setFont(UITheme.UI);

        // Column widths
        table.getColumnModel().getColumn(COL_REPO).setPreferredWidth(200);
        table.getColumnModel().getColumn(COL_METHOD).setPreferredWidth(200);
        table.getColumnModel().getColumn(COL_SIG).setPreferredWidth(300);
        table.getColumnModel().getColumn(COL_OP).setPreferredWidth(70);
        table.getColumnModel().getColumn(COL_CALL_COUNT).setPreferredWidth(80);

        // Cell renderers
        table.getColumnModel().getColumn(COL_REPO).setCellRenderer(new RepoCellRenderer());
        table.getColumnModel().getColumn(COL_METHOD).setCellRenderer(new MethodCellRenderer());
        table.getColumnModel().getColumn(COL_SIG).setCellRenderer(new SigCellRenderer());
        table.getColumnModel().getColumn(COL_OP).setCellRenderer(new OpBadgeCellRenderer());
        table.getColumnModel().getColumn(COL_CALL_COUNT).setCellRenderer(new CallsBadgeCellRenderer());

        table.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) navigateToSelectedMethod();
            }
        });
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) runButton.setEnabled(table.getSelectedRow() >= 0);
        });

        add(new JBScrollPane(table), BorderLayout.CENTER);

        // ── Status bar ────────────────────────────────────────────────────────
        totalLabel   = new JLabel("0 methods");
        unusedLabel  = new JLabel("0 unused");
        visibleLabel = new JLabel();

        totalLabel.setFont(UITheme.UI_SM);
        unusedLabel.setFont(UITheme.UI_SM);
        visibleLabel.setFont(UITheme.UI_SM);
        unusedLabel.setForeground(UITheme.DANGER);
        visibleLabel.setForeground(UITheme.MUTED);

        JLabel hintLabel = new JLabel("Double-click a row to navigate \u00B7 \u21B5 Run");
        hintLabel.setFont(UITheme.UI_SM);
        hintLabel.setForeground(UITheme.MUTED);

        JLabel sep1 = new JLabel(" \u00B7 ");
        JLabel sep2 = new JLabel(" \u00B7 ");
        sep1.setFont(UITheme.UI_SM);
        sep2.setFont(UITheme.UI_SM);
        sep1.setForeground(UITheme.MUTED);
        sep2.setForeground(UITheme.MUTED);

        JPanel statusLeft = new JPanel();
        statusLeft.setLayout(new BoxLayout(statusLeft, BoxLayout.X_AXIS));
        statusLeft.setOpaque(false);
        statusLeft.add(totalLabel);
        statusLeft.add(sep1);
        statusLeft.add(unusedLabel);

        JPanel statusBar = new JPanel(new BorderLayout());
        statusBar.setBackground(UITheme.TOOLBAR);
        statusBar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, UITheme.BORDER_SUB),
                BorderFactory.createEmptyBorder(4, 10, 4, 10)));
        statusBar.add(statusLeft, BorderLayout.WEST);
        statusBar.add(hintLabel,  BorderLayout.EAST);

        add(statusBar, BorderLayout.SOUTH);

        SwingUtilities.invokeLater(this::runAnalysis);
    }

    // =========================================================================
    // Analysis
    // =========================================================================

    private void runAnalysis() {
        DumbService.getInstance(project).runWhenSmart(() -> {
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                RepositoryAnalysisService.AnalysisResult result =
                        project.getService(RepositoryAnalysisService.class).analyzeAll();
                SwingUtilities.invokeLater(() -> updateTable(result));
            });
        });
    }

    private void updateTable(RepositoryAnalysisService.AnalysisResult result) {
        tableModel.setRowCount(0);
        methodList = result.methods();
        lastInfos  = result.infos();
        currentFileClassName = null;

        for (RepositoryMethodInfo info : lastInfos) {
            tableModel.addRow(new Object[]{
                    info.repositoryName(),
                    info.methodName(),
                    info.methodSignature(),
                    info.operationType(),
                    info.callCount()
            });
        }

        if (currentFileToggle.isSelected()) loadCurrentFileFilter();
        else applyFilter();
    }

    // =========================================================================
    // Filtering
    // =========================================================================

    private void applyFilter() {
        String  text             = searchField.getText().trim();
        boolean onlyUnused       = unusedOnlyToggle.isSelected();
        boolean currentFileActive = currentFileToggle.isSelected() && currentFileClassName != null;

        if (text.isEmpty() && !onlyUnused && !currentFileActive) {
            rowSorter.setRowFilter(null);
        } else {
            List<RowFilter<DefaultTableModel, Integer>> filters = new ArrayList<>();
            filters.add(buildTextFilter(text));
            filters.add(buildUnusedFilter(onlyUnused));
            if (currentFileActive) filters.add(buildCurrentFileFilter(currentFileClassName));
            rowSorter.setRowFilter(RowFilter.andFilter(filters));
        }
        updateStatusBar();
    }

    private void loadCurrentFileFilter() {
        com.intellij.openapi.editor.Editor editor =
                FileEditorManager.getInstance(project).getSelectedTextEditor();
        if (editor == null) {
            currentFileToggle.setSelected(false);
            return;
        }
        VirtualFile vf = FileDocumentManager.getInstance().getFile(editor.getDocument());
        if (vf == null) { currentFileToggle.setSelected(false); return; }
        currentFileClassName = vf.getNameWithoutExtension();
        applyFilter();
    }

    private static RowFilter<DefaultTableModel, Integer> buildCurrentFileFilter(@NotNull String cls) {
        return new RowFilter<>() {
            @Override public boolean include(Entry<? extends DefaultTableModel, ? extends Integer> e) {
                Object v = e.getValue(COL_REPO);
                return cls.equalsIgnoreCase(v != null ? v.toString() : "");
            }
        };
    }

    private static RowFilter<DefaultTableModel, Integer> buildTextFilter(String text) {
        if (text.isEmpty()) return RowFilter.regexFilter(".*");
        String esc = java.util.regex.Pattern.quote(text);
        return RowFilter.orFilter(Arrays.asList(
                RowFilter.regexFilter("(?i)" + esc, COL_REPO),
                RowFilter.regexFilter("(?i)" + esc, COL_METHOD)));
    }

    private static RowFilter<DefaultTableModel, Integer> buildUnusedFilter(boolean onlyUnused) {
        if (!onlyUnused) return RowFilter.regexFilter(".*");
        return new RowFilter<>() {
            @Override public boolean include(Entry<? extends DefaultTableModel, ? extends Integer> e) {
                Object v = e.getValue(COL_CALL_COUNT);
                return v instanceof Integer count && count == 0;
            }
        };
    }

    private void updateStatusBar() {
        int total   = lastInfos.size();
        long unused = lastInfos.stream().filter(i -> i.callCount() == 0).count();
        int visible = table.getRowCount();

        totalLabel.setText("<html><b>" + total + "</b> method" + (total == 1 ? "" : "s") + "</html>");
        unusedLabel.setText("<html><b>" + unused + "</b> unused</html>");

        if (visible < total) {
            visibleLabel.setText(" \u00B7 showing <b>" + visible + "</b>");
            visibleLabel.setVisible(true);
        } else {
            visibleLabel.setVisible(false);
        }
    }

    // =========================================================================
    // CSV export
    // =========================================================================

    private void exportCsv() {
        if (lastInfos.isEmpty()) return;
        StringBuilder sb = new StringBuilder("Repository,Method,Signature,Op,Calls\n");
        for (RepositoryMethodInfo info : lastInfos) {
            sb.append(csvEscape(info.repositoryName())).append(',')
              .append(csvEscape(info.methodName())).append(',')
              .append(csvEscape(info.methodSignature())).append(',')
              .append(info.operationType().name()).append(',')
              .append(info.callCount()).append('\n');
        }
        Toolkit.getDefaultToolkit().getSystemClipboard()
               .setContents(new StringSelection(sb.toString()), null);
    }

    private static String csvEscape(String v) {
        if (v == null) return "";
        if (v.contains(",") || v.contains("\"") || v.contains("\n"))
            return "\"" + v.replace("\"", "\"\"") + "\"";
        return v;
    }

    // =========================================================================
    // Run
    // =========================================================================

    private void openRunnerPopupForSelected() {
        int viewRow = table.getSelectedRow();
        if (viewRow < 0) return;
        int modelRow = table.convertRowIndexToModel(viewRow);
        if (modelRow < 0 || modelRow >= methodList.size()) return;
        PsiMethod method = methodList.get(modelRow);
        if (method == null || !method.isValid()) return;

        ApplicationManager.getApplication().runReadAction(() -> {
            PsiClass cls  = method.getContainingClass();
            String fqn    = (cls != null && cls.getQualifiedName() != null) ? cls.getQualifiedName() : "";
            String name   = method.getName();
            List<ParameterDef> params = ApplicationManager.getApplication()
                    .getService(ParameterExtractionService.class).extract(method);
            SwingUtilities.invokeLater(() -> new RepoRunnerPopup(project, fqn, name, params).display(null));
        });
    }

    // =========================================================================
    // Navigation
    // =========================================================================

    private void navigateToSelectedMethod() {
        int viewRow = table.getSelectedRow();
        if (viewRow < 0) return;
        int modelRow = table.convertRowIndexToModel(viewRow);
        if (modelRow < 0 || modelRow >= methodList.size()) return;
        PsiMethod m = methodList.get(modelRow);
        if (m != null && m.isValid()) m.navigate(true);
    }

    // =========================================================================
    // Cell renderers
    // =========================================================================

    private static final class RepoCellRenderer extends DefaultTableCellRenderer {
        @Override public Component getTableCellRendererComponent(
                JTable t, Object v, boolean sel, boolean focus, int r, int c) {
            super.getTableCellRendererComponent(t, v, sel, focus, r, c);
            setFont(UITheme.MONO_XS.deriveFont(Font.BOLD, 12f));
            if (!sel) setForeground(UITheme.GOLD);
            setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 4));
            return this;
        }
    }

    private static final class MethodCellRenderer extends DefaultTableCellRenderer {
        @Override public Component getTableCellRendererComponent(
                JTable t, Object v, boolean sel, boolean focus, int r, int c) {
            super.getTableCellRendererComponent(t, v, sel, focus, r, c);
            setFont(UITheme.MONO_XS.deriveFont(12f));
            if (!sel) setForeground(UITheme.ACCENT);
            setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 4));
            return this;
        }
    }

    private static final class SigCellRenderer extends DefaultTableCellRenderer {
        @Override public Component getTableCellRendererComponent(
                JTable t, Object v, boolean sel, boolean focus, int r, int c) {
            super.getTableCellRendererComponent(t, v, sel, focus, r, c);
            setFont(UITheme.MONO_XS.deriveFont(11f));
            if (!sel) setForeground(UITheme.INK_DIM);
            setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 4));
            return this;
        }
    }

    private static final class OpBadgeCellRenderer extends DefaultTableCellRenderer {
        @Override public Component getTableCellRendererComponent(
                JTable t, Object v, boolean sel, boolean focus, int r, int c) {
            super.getTableCellRendererComponent(t, v, sel, focus, r, c);
            setText("");
            setHorizontalAlignment(CENTER);
            return new BadgePainter(v instanceof OperationType op ? op : OperationType.UNKNOWN, sel);
        }
    }

    private static final class BadgePainter extends JComponent {
        private final OperationType op;
        private final boolean selected;
        BadgePainter(OperationType op, boolean selected) {
            this.op = op; this.selected = selected;
            setOpaque(false);
        }
        @Override protected void paintComponent(Graphics g) {
            if (selected) return;
            String label;
            Color  bg, fg;
            if (op == OperationType.READ) {
                label = "READ";  bg = UITheme.SUCCESS_SUB; fg = UITheme.SUCCESS;
            } else if (op == OperationType.WRITE) {
                label = "WRITE"; bg = UITheme.WARNING_SUB; fg = UITheme.WARNING;
            } else {
                label = "?";     bg = UITheme.BORDER_SUB;  fg = UITheme.MUTED;
            }
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            FontMetrics fm = g2.getFontMetrics(UITheme.UI_SM);
            int tw = fm.stringWidth(label);
            int pw = tw + 10, ph = 16;
            int x  = (getWidth()  - pw) / 2;
            int y  = (getHeight() - ph) / 2;
            g2.setColor(bg);
            g2.fillRoundRect(x, y, pw, ph, 4, 4);
            g2.setColor(fg);
            g2.setFont(UITheme.UI_SM.deriveFont(Font.BOLD));
            g2.drawString(label, x + 5, y + ph - 4);
            g2.dispose();
        }
    }

    private static final class CallsBadgeCellRenderer extends DefaultTableCellRenderer {
        @Override public Component getTableCellRendererComponent(
                JTable t, Object v, boolean sel, boolean focus, int r, int c) {
            super.getTableCellRendererComponent(t, v, sel, focus, r, c);
            int count = v instanceof Integer i ? i : 0;
            setText("");
            setHorizontalAlignment(RIGHT);
            return new CallsPainter(count, sel);
        }
    }

    private static final class CallsPainter extends JComponent {
        private final int count;
        private final boolean selected;
        CallsPainter(int count, boolean selected) {
            this.count = count; this.selected = selected;
            setOpaque(false);
        }
        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color dotColor;
            if (selected) { g2.dispose(); return; }
            if (count == 0)     dotColor = UITheme.DANGER;
            else if (count <= 2) dotColor = UITheme.WARNING;
            else                 dotColor = UITheme.SUCCESS;

            String text = String.valueOf(count);
            g2.setFont(UITheme.UI_SM);
            FontMetrics fm = g2.getFontMetrics();
            int tw = fm.stringWidth(text);
            int dotSize = 6;
            int gap = 4;
            int totalW = dotSize + gap + tw;
            int x  = getWidth()  - totalW - 8;
            int cy = getHeight() / 2;

            g2.setColor(dotColor);
            g2.fillOval(x, cy - dotSize / 2, dotSize, dotSize);
            g2.setColor(UITheme.INK_DIM);
            g2.drawString(text, x + dotSize + gap, cy + fm.getAscent() / 2 - 1);
            g2.dispose();
        }
    }
}
