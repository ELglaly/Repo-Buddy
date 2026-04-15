package com.repoinspector.ui;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiMethod;
import com.intellij.ui.JBColor;
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
 * Main panel for the "Repo Inspector" tool window.
 * Shows a sortable table of all Spring repository methods and their call counts,
 * with color-coded cells and double-click navigation to source.
 */
public class RepoInspectorPanel extends JPanel {

    private static final String[] COLUMN_NAMES = {"Repository", "Method", "Signature", "Call Count"};
    private static final int CALL_COUNT_COLUMN = 3;
    private static final int CALL_COUNT_WARNING_THRESHOLD = 2;

    private final Project project;
    private final DefaultTableModel tableModel;
    private final JBTable table;

    // Parallel list of PsiMethod references for double-click navigation
    private List<PsiMethod> methodList = List.of();

    public RepoInspectorPanel(Project project) {
        super(new BorderLayout());
        this.project = project;

        // --- Toolbar (NORTH) ---
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
        JButton refreshButton = new JButton("Refresh Analysis");
        refreshButton.addActionListener(e -> runAnalysis());
        toolbar.add(refreshButton);
        add(toolbar, BorderLayout.NORTH);

        // --- Table (CENTER) ---
        tableModel = new DefaultTableModel(COLUMN_NAMES, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }

            @Override
            public Class<?> getColumnClass(int columnIndex) {
                // Returning Integer for the call count column enables numeric sort
                return columnIndex == CALL_COUNT_COLUMN ? Integer.class : String.class;
            }
        };

        table = new JBTable(tableModel);
        table.setAutoCreateRowSorter(true);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        // Color-coded renderer for the Call Count column
        table.getColumnModel().getColumn(CALL_COUNT_COLUMN)
                .setCellRenderer(new CallCountCellRenderer());

        // Double-click to navigate to method declaration
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    navigateToSelectedMethod();
                }
            }
        });

        add(new JBScrollPane(table), BorderLayout.CENTER);
    }

    /**
     * Runs the analysis on a background thread and updates the table on the EDT.
     */
    private void runAnalysis() {
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            CallSiteAnalyzer.AnalysisResult result = CallSiteAnalyzer.analyzeAll(project);
            SwingUtilities.invokeLater(() -> updateTable(result));
        });
    }

    /**
     * Populates the table with the analysis results. Must be called on the EDT.
     */
    private void updateTable(CallSiteAnalyzer.AnalysisResult result) {
        tableModel.setRowCount(0);
        methodList = result.methods();

        for (RepositoryMethodInfo info : result.infos()) {
            tableModel.addRow(new Object[]{
                    info.repositoryName(),
                    info.methodName(),
                    info.methodSignature(),
                    info.callCount()   // stored as Integer for numeric sort
            });
        }
    }

    /**
     * Navigates to the PsiMethod corresponding to the currently selected table row.
     */
    private void navigateToSelectedMethod() {
        int viewRow = table.getSelectedRow();
        if (viewRow < 0) {
            return;
        }
        // Convert view index to model index (accounts for active sort order)
        int modelRow = table.convertRowIndexToModel(viewRow);
        if (modelRow < 0 || modelRow >= methodList.size()) {
            return;
        }

        PsiMethod method = methodList.get(modelRow);
        if (method != null && method.isValid()) {
            method.navigate(true);
        }
    }

    // -------------------------------------------------------------------------
    // Cell renderer for color-coded Call Count column
    // -------------------------------------------------------------------------

    private static class CallCountCellRenderer extends DefaultTableCellRenderer {

        private static final Color COLOR_RED    = new JBColor(new Color(255, 180, 180), new Color(120, 40, 40));
        private static final Color COLOR_YELLOW = new JBColor(new Color(255, 255, 180), new Color(100, 90, 20));
        private static final Color COLOR_GREEN  = new JBColor(new Color(190, 255, 190), new Color(30, 90, 30));

        @Override
        public Component getTableCellRendererComponent(
                JTable table, Object value, boolean isSelected,
                boolean hasFocus, int row, int column) {

            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            setHorizontalAlignment(SwingConstants.CENTER);

            if (!isSelected && value instanceof Integer count) {
                if (count == 0) {
                    setBackground(COLOR_RED);
                } else if (count <= CALL_COUNT_WARNING_THRESHOLD) {
                    setBackground(COLOR_YELLOW);
                } else {
                    setBackground(COLOR_GREEN);
                }
            }

            return this;
        }
    }
}
