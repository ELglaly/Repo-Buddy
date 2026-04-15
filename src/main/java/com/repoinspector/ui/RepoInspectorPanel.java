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
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;

/**
 * Main panel for the "Repository Usage" tab.
 *
 * <p>Shows a sortable table of all Spring Data repository methods with their
 * call counts.  Cells in the Call Count column are colour-coded:
 * <ul>
 *   <li><b>Red</b>    — never called (count = 0)</li>
 *   <li><b>Amber</b>  — called once or twice</li>
 *   <li><b>Green</b>  — called three or more times</li>
 * </ul>
 * Double-clicking a row navigates to the method declaration in the editor.
 */
public class RepoInspectorPanel extends JPanel {

    private static final String[] COLUMN_NAMES = {"Repository", "Method", "Signature", "Calls"};
    private static final int COL_CALL_COUNT   = 3;
    private static final int CALL_COUNT_HIGH  = 2;   // threshold: > this value → green

    private final Project          project;
    private final DefaultTableModel tableModel;
    private final JBTable          table;
    private final JLabel           statusLabel;

    /** Parallel list of PSI references for double-click navigation (model-index aligned). */
    private List<PsiMethod> methodList = List.of();

    public RepoInspectorPanel(Project project) {
        super(new BorderLayout());
        this.project = project;

        // ── Toolbar (NORTH) ───────────────────────────────────────────────────
        JButton refreshButton = UITheme.button("\u21BB  Refresh Analysis");
        refreshButton.setFont(refreshButton.getFont().deriveFont(Font.BOLD, 12f));
        refreshButton.addActionListener(e -> runAnalysis());

        statusLabel = new JLabel("  Double-click a row to navigate to the method.");
        statusLabel.setFont(statusLabel.getFont().deriveFont(11f));
        statusLabel.setForeground(UITheme.MUTED);

        JPanel toolbar = new JPanel(new BorderLayout(8, 0));
        toolbar.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        toolbar.add(refreshButton, BorderLayout.WEST);
        toolbar.add(statusLabel,   BorderLayout.CENTER);
        add(toolbar, BorderLayout.NORTH);

        // ── Table (CENTER) ────────────────────────────────────────────────────
        tableModel = new DefaultTableModel(COLUMN_NAMES, 0) {
            @Override public boolean isCellEditable(int row, int col) { return false; }
            @Override public Class<?> getColumnClass(int col) {
                // Integer class enables numeric sort on the Calls column.
                return col == COL_CALL_COUNT ? Integer.class : String.class;
            }
        };

        table = new JBTable(tableModel);
        table.setAutoCreateRowSorter(true);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setRowHeight(24);
        table.getTableHeader().setFont(
                table.getTableHeader().getFont().deriveFont(Font.BOLD));

        // Colour-coded Calls column
        table.getColumnModel().getColumn(COL_CALL_COUNT)
                .setCellRenderer(new CallCountCellRenderer());

        // Double-click → navigate
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

        for (RepositoryMethodInfo info : result.infos()) {
            tableModel.addRow(new Object[]{
                    info.repositoryName(),
                    info.methodName(),
                    info.methodSignature(),
                    info.callCount()
            });
        }

        int total = result.infos().size();
        long unused = result.infos().stream().filter(i -> i.callCount() == 0).count();
        statusLabel.setText("  " + total + " method" + (total == 1 ? "" : "s") + "  \u2014  "
                + unused + " unused  \u2014  double-click to navigate");
        statusLabel.setForeground(UITheme.MUTED);
    }

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
