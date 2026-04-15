package com.repoinspector.ui;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiMethod;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.table.JBTable;
import com.repoinspector.analysis.CallSiteAnalyzer;
import com.repoinspector.model.RepositoryMethodInfo;

import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;

/**
 * Main panel for the "Repository Usage" tab.
 *
 * <p>Shows a sortable, filterable table of all Spring Data repository methods with their
 * call counts.  Cells in the Call Count column are colour-coded:
 * <ul>
 *   <li><b>Red</b>    — never called (count = 0)</li>
 *   <li><b>Amber</b>  — called once or twice</li>
 *   <li><b>Green</b>  — called three or more times</li>
 * </ul>
 *
 * <p>Toolbar controls:
 * <ul>
 *   <li>Live search field — filters by repository name or method name as you type</li>
 *   <li>Show Unused Only toggle — restricts the view to methods with 0 calls</li>
 *   <li>Export CSV — copies the full table to the system clipboard as CSV text</li>
 *   <li>↻ Refresh — re-runs the static call-site analysis</li>
 * </ul>
 * Double-clicking a row navigates to the method declaration in the editor.
 */
public class RepoInspectorPanel extends JPanel {

    private static final String[] COLUMN_NAMES = {"Repository", "Method", "Signature", "Calls"};
    private static final int COL_REPO       = 0;
    private static final int COL_METHOD     = 1;
    private static final int COL_CALL_COUNT = 3;
    private static final int CALL_COUNT_HIGH = 2;   // threshold: > this value → green

    private final Project          project;
    private final DefaultTableModel tableModel;
    private final JBTable          table;
    private final TableRowSorter<DefaultTableModel> rowSorter;
    private final JLabel           statusLabel;
    private final JTextField       searchField;
    private final JToggleButton    unusedOnlyToggle;

    /** Parallel list of PSI references for double-click navigation (model-index aligned). */
    private List<PsiMethod>             methodList  = List.of();
    private List<RepositoryMethodInfo>  lastInfos   = List.of();

    public RepoInspectorPanel(Project project) {
        super(new BorderLayout());
        this.project = project;

        // ── Search field ──────────────────────────────────────────────────────
        searchField = new JTextField(18);
        searchField.setToolTipText("Filter by repository or method name");
        searchField.putClientProperty("JTextField.placeholderText", "Search…");
        searchField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e)  { applyFilter(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e)  { applyFilter(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { applyFilter(); }
        });

        // ── Show Unused Only toggle ───────────────────────────────────────────
        unusedOnlyToggle = new JToggleButton("Show Unused Only");
        unusedOnlyToggle.setFont(unusedOnlyToggle.getFont().deriveFont(11f));
        unusedOnlyToggle.setFocusPainted(false);
        unusedOnlyToggle.setToolTipText("Restrict view to repository methods that are never called");
        unusedOnlyToggle.addItemListener(e -> applyFilter());

        // ── Export CSV button ─────────────────────────────────────────────────
        JButton exportCsvButton = UITheme.button("\u21A7  Export CSV");
        exportCsvButton.setFont(exportCsvButton.getFont().deriveFont(11f));
        exportCsvButton.setToolTipText("Copy the full table to clipboard as CSV");
        exportCsvButton.addActionListener(e -> exportCsv());

        // ── Refresh button ────────────────────────────────────────────────────
        JButton refreshButton = UITheme.button("\u21BB  Refresh");
        refreshButton.setFont(refreshButton.getFont().deriveFont(Font.BOLD, 12f));
        refreshButton.addActionListener(e -> runAnalysis());

        // ── Status label ──────────────────────────────────────────────────────
        statusLabel = new JLabel("  Double-click a row to navigate to the method.");
        statusLabel.setFont(statusLabel.getFont().deriveFont(11f));
        statusLabel.setForeground(UITheme.MUTED);

        // ── Toolbar assembly ──────────────────────────────────────────────────
        JPanel searchBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        searchBar.add(new JLabel("Search:"));
        searchBar.add(searchField);
        searchBar.add(unusedOnlyToggle);
        searchBar.add(exportCsvButton);

        JPanel buttonBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 2));
        buttonBar.add(refreshButton);

        JPanel topRow = new JPanel(new BorderLayout(4, 0));
        topRow.add(searchBar,   BorderLayout.WEST);
        topRow.add(buttonBar,   BorderLayout.EAST);

        JPanel toolbar = new JPanel(new BorderLayout(8, 0));
        toolbar.setBorder(BorderFactory.createEmptyBorder(6, 8, 4, 8));
        toolbar.add(topRow,      BorderLayout.NORTH);
        toolbar.add(statusLabel, BorderLayout.SOUTH);
        add(toolbar, BorderLayout.NORTH);

        // ── Table (CENTER) ────────────────────────────────────────────────────
        tableModel = new DefaultTableModel(COLUMN_NAMES, 0) {
            @Override public boolean isCellEditable(int row, int col) { return false; }
            @Override public Class<?> getColumnClass(int col) {
                return col == COL_CALL_COUNT ? Integer.class : String.class;
            }
        };

        table = new JBTable(tableModel);
        rowSorter = new TableRowSorter<>(tableModel);
        table.setRowSorter(rowSorter);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setRowHeight(24);
        table.getTableHeader().setFont(
                table.getTableHeader().getFont().deriveFont(Font.BOLD));

        table.getColumnModel().getColumn(COL_CALL_COUNT)
                .setCellRenderer(new CallCountCellRenderer());

        table.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) navigateToSelectedMethod();
            }
        });

        add(new JBScrollPane(table), BorderLayout.CENTER);
    }

    // =========================================================================
    // Analysis
    // =========================================================================

    private void runAnalysis() {
        statusLabel.setText("  Analyzing\u2026");
        statusLabel.setForeground(UITheme.ACCENT);

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            CallSiteAnalyzer.AnalysisResult result = CallSiteAnalyzer.analyzeAll(project);
            SwingUtilities.invokeLater(() -> updateTable(result));
        });
    }

    private void updateTable(CallSiteAnalyzer.AnalysisResult result) {
        tableModel.setRowCount(0);
        methodList = result.methods();
        lastInfos  = result.infos();

        for (RepositoryMethodInfo info : lastInfos) {
            tableModel.addRow(new Object[]{
                    info.repositoryName(),
                    info.methodName(),
                    info.methodSignature(),
                    info.callCount()
            });
        }

        applyFilter();
        updateStatusLabel();
    }

    // =========================================================================
    // Filtering
    // =========================================================================

    private void applyFilter() {
        String text   = searchField.getText().trim();
        boolean onlyUnused = unusedOnlyToggle.isSelected();

        if (text.isEmpty() && !onlyUnused) {
            rowSorter.setRowFilter(null);
        } else {
            rowSorter.setRowFilter(RowFilter.andFilter(java.util.Arrays.asList(
                    buildTextFilter(text),
                    buildUnusedFilter(onlyUnused)
            )));
        }
        updateStatusLabel();
    }

    private static RowFilter<DefaultTableModel, Integer> buildTextFilter(String text) {
        if (text.isEmpty()) return RowFilter.regexFilter(".*");   // pass-all
        String escaped = java.util.regex.Pattern.quote(text);
        // match repository (col 0) OR method name (col 1)
        return RowFilter.orFilter(java.util.Arrays.asList(
                RowFilter.regexFilter("(?i)" + escaped, COL_REPO),
                RowFilter.regexFilter("(?i)" + escaped, COL_METHOD)
        ));
    }

    private static RowFilter<DefaultTableModel, Integer> buildUnusedFilter(boolean onlyUnused) {
        if (!onlyUnused) return RowFilter.regexFilter(".*");   // pass-all
        return new RowFilter<>() {
            @Override
            public boolean include(Entry<? extends DefaultTableModel, ? extends Integer> entry) {
                Object val = entry.getValue(COL_CALL_COUNT);
                return val instanceof Integer count && count == 0;
            }
        };
    }

    private void updateStatusLabel() {
        int total    = lastInfos.size();
        long unused  = lastInfos.stream().filter(i -> i.callCount() == 0).count();
        int visible  = table.getRowCount();

        String base = "  " + total + " method" + (total == 1 ? "" : "s")
                + "  \u2014  " + unused + " unused";
        if (visible < total) {
            base += "  \u2014  showing " + visible;
        }
        base += "  \u2014  double-click to navigate";
        statusLabel.setText(base);
        statusLabel.setForeground(UITheme.MUTED);
    }

    // =========================================================================
    // CSV export
    // =========================================================================

    private void exportCsv() {
        if (lastInfos.isEmpty()) {
            statusLabel.setText("  Nothing to export — run an analysis first.");
            statusLabel.setForeground(UITheme.WARNING);
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Repository,Method,Signature,Calls\n");
        for (RepositoryMethodInfo info : lastInfos) {
            sb.append(csvEscape(info.repositoryName())).append(',')
              .append(csvEscape(info.methodName())).append(',')
              .append(csvEscape(info.methodSignature())).append(',')
              .append(info.callCount()).append('\n');
        }

        Toolkit.getDefaultToolkit()
                .getSystemClipboard()
                .setContents(new StringSelection(sb.toString()), null);

        statusLabel.setText("  CSV copied to clipboard (" + lastInfos.size() + " rows).");
        statusLabel.setForeground(UITheme.SUCCESS);
    }

    private static String csvEscape(String value) {
        if (value == null) return "";
        // Wrap in quotes if it contains comma, quote, or newline
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    // =========================================================================
    // Navigation
    // =========================================================================

    private void navigateToSelectedMethod() {
        int viewRow = table.getSelectedRow();
        if (viewRow < 0) return;

        int modelRow = table.convertRowIndexToModel(viewRow);
        if (modelRow < 0 || modelRow >= methodList.size()) return;

        PsiMethod method = methodList.get(modelRow);
        if (method != null && method.isValid()) {
            method.navigate(true);
        }
    }

    // =========================================================================
    // Call-count cell renderer — colour-coded via UITheme
    // =========================================================================

    private static final class CallCountCellRenderer extends DefaultTableCellRenderer {

        @Override
        public Component getTableCellRendererComponent(
                JTable table, Object value, boolean isSelected,
                boolean hasFocus, int row, int column) {

            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            setHorizontalAlignment(SwingConstants.CENTER);
            setFont(getFont().deriveFont(Font.BOLD));

            if (!isSelected && value instanceof Integer count) {
                if (count == 0) {
                    setBackground(UITheme.COUNT_ZERO);
                    setForeground(UITheme.COUNT_ZERO.darker().darker());
                } else if (count <= CALL_COUNT_HIGH) {
                    setBackground(UITheme.COUNT_LOW);
                    setForeground(UITheme.COUNT_LOW.darker().darker());
                } else {
                    setBackground(UITheme.COUNT_HIGH);
                    setForeground(UITheme.COUNT_HIGH.darker().darker());
                }
            }

            return this;
        }
    }
}
