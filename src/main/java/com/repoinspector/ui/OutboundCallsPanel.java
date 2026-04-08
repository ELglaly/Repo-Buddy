package com.repoinspector.ui;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.ui.components.JBScrollPane;
import com.repoinspector.analysis.EndpointFinder;
import com.repoinspector.analysis.OutboundApiCallFinder;
import com.repoinspector.analysis.OutboundCallCache;
import com.repoinspector.model.EndpointInfo;
import com.repoinspector.model.OutboundApiCall;

import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;

/**
 * Tool-window panel for the "Outbound API Calls" tab.
 *
 * <p>Layout:
 * <pre>
 *  ┌─ NORTH (toolbar) ──────────────────────────────────────────────────┐
 *  │  [Scan All]  Filter by endpoint: [combo▼]  [Scan Endpoint]         │
 *  │  [Copy as Text]   status label                                      │
 *  └────────────────────────────────────────────────────────────────────┘
 *  ┌─ CENTER ────────────────────────────────────────────────────────────┐
 *  │  JTable: Caller | Method | HTTP | Resolved URL | Confidence | Type  │
 *  └────────────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <p>Double-clicking a row navigates to the calling method in the editor.
 */
public class OutboundCallsPanel extends JPanel {

    private static final String[] COLUMN_NAMES =
            {"Caller Class", "Caller Method", "HTTP", "Resolved URL", "Confidence", "Client"};

    private final Project project;
    private final JComboBox<EndpointItem> endpointCombo;
    private final JTable table;
    private final DefaultTableModel tableModel;
    private final JLabel statusLabel;

    /** Retained for Copy-as-Text and double-click navigation. */
    private List<OutboundApiCall> lastResult = List.of();

    public OutboundCallsPanel(Project project) {
        this.project = project;
        setLayout(new BorderLayout());

        // ── toolbar ──────────────────────────────────────────────────────────
        JButton scanAllButton = new JButton("Scan All");
        JButton scanEndpointButton = new JButton("Scan Endpoint");
        JButton copyButton = new JButton("Copy as Text");
        statusLabel = new JLabel("Click 'Scan All' to detect outbound HTTP calls.");

        endpointCombo = new JComboBox<>();
        endpointCombo.setPrototypeDisplayValue(new EndpointItem(
                new EndpointInfo("GET", "/placeholder/path", "Controller", "method()", null)));
        endpointCombo.setRenderer(new EndpointComboRenderer());

        scanAllButton.addActionListener(e -> runScanAll());
        scanEndpointButton.addActionListener(e -> runScanEndpoint());
        copyButton.addActionListener(e -> copyAsText());

        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        toolbar.add(scanAllButton);
        toolbar.add(new JSeparator(SwingConstants.VERTICAL));
        toolbar.add(new JLabel("Filter by endpoint:"));
        toolbar.add(endpointCombo);
        toolbar.add(scanEndpointButton);
        toolbar.add(new JSeparator(SwingConstants.VERTICAL));
        toolbar.add(copyButton);

        JPanel northPanel = new JPanel(new BorderLayout());
        northPanel.add(toolbar, BorderLayout.NORTH);
        northPanel.add(statusLabel, BorderLayout.SOUTH);
        add(northPanel, BorderLayout.NORTH);

        // ── table ─────────────────────────────────────────────────────────────
        tableModel = new DefaultTableModel(COLUMN_NAMES, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        table = new JTable(tableModel);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
        table.getTableHeader().setReorderingAllowed(false);

        // Column widths
        table.getColumnModel().getColumn(0).setPreferredWidth(120); // Caller Class
        table.getColumnModel().getColumn(1).setPreferredWidth(160); // Caller Method
        table.getColumnModel().getColumn(2).setPreferredWidth(55);  // HTTP
        table.getColumnModel().getColumn(3).setPreferredWidth(320); // URL
        table.getColumnModel().getColumn(4).setPreferredWidth(80);  // Confidence
        table.getColumnModel().getColumn(5).setPreferredWidth(110); // Client

        // Color-code confidence column
        table.getColumnModel().getColumn(4).setCellRenderer(new ConfidenceCellRenderer());
        // Color-code HTTP method column
        table.getColumnModel().getColumn(2).setCellRenderer(new HttpMethodCellRenderer());

        // Double-click to navigate
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    navigateToSelected();
                }
            }
        });

        add(new JBScrollPane(table), BorderLayout.CENTER);

        // Populate endpoint combo in background
        SwingUtilities.invokeLater(this::loadEndpoints);
    }

    // -------------------------------------------------------------------------
    // Endpoint loading
    // -------------------------------------------------------------------------

    private void loadEndpoints() {
        DumbService.getInstance(project).runWhenSmart(() ->
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                List<EndpointInfo> endpoints = EndpointFinder.findAllEndpoints(project);
                SwingUtilities.invokeLater(() -> {
                    endpointCombo.removeAllItems();
                    for (EndpointInfo ep : endpoints) {
                        endpointCombo.addItem(new EndpointItem(ep));
                    }
                });
            }));
    }

    // -------------------------------------------------------------------------
    // Scan actions
    // -------------------------------------------------------------------------

    private void runScanAll() {
        statusLabel.setText("Scanning project for outbound HTTP calls...");
        tableModel.setRowCount(0);

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            List<OutboundApiCall> cached = OutboundCallCache.getOrNull(project);
            if (cached == null) {
                cached = OutboundApiCallFinder.findAll(project);
                OutboundCallCache.put(project, cached);
            }
            final List<OutboundApiCall> results = cached;
            SwingUtilities.invokeLater(() -> renderResults(results, "project-wide scan"));
        });
    }

    private void runScanEndpoint() {
        EndpointItem selected = (EndpointItem) endpointCombo.getSelectedItem();
        if (selected == null) {
            statusLabel.setText("No endpoint selected — load endpoints first.");
            return;
        }

        EndpointInfo ep = selected.endpoint();
        statusLabel.setText("Scanning outbound calls reachable from " + ep.httpMethod()
                + " " + ep.path() + "...");
        tableModel.setRowCount(0);

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            List<OutboundApiCall> results = OutboundApiCallFinder.findReachableFrom(ep, project);
            SwingUtilities.invokeLater(() ->
                    renderResults(results, ep.httpMethod() + " " + ep.path()));
        });
    }

    // -------------------------------------------------------------------------
    // Table rendering
    // -------------------------------------------------------------------------

    private void renderResults(List<OutboundApiCall> results, String scope) {
        lastResult = results;
        tableModel.setRowCount(0);

        for (OutboundApiCall call : results) {
            tableModel.addRow(new Object[]{
                    call.callerClass(),
                    call.callerMethod(),
                    call.httpMethod(),
                    call.resolvedUrl(),
                    call.confidence().name(),
                    call.clientType().name().replace('_', ' ')
            });
        }

        if (results.isEmpty()) {
            statusLabel.setText("No outbound HTTP calls detected in " + scope + ".");
        } else {
            statusLabel.setText(results.size() + " outbound call(s) found in " + scope
                    + ". Double-click to navigate.");
        }
    }

    // -------------------------------------------------------------------------
    // Navigation
    // -------------------------------------------------------------------------

    private void navigateToSelected() {
        int row = table.getSelectedRow();
        if (row < 0 || row >= lastResult.size()) return;
        OutboundApiCall call = lastResult.get(row);
        if (call.psiMethod() != null) {
            call.psiMethod().navigate(true);
        }
    }

    // -------------------------------------------------------------------------
    // Copy as text
    // -------------------------------------------------------------------------

    private void copyAsText() {
        if (lastResult.isEmpty()) {
            statusLabel.setText("Nothing to copy — run a scan first.");
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%-25s %-30s %-7s %-60s %-8s %s%n",
                "Caller Class", "Caller Method", "HTTP", "Resolved URL", "Conf.", "Client"));
        sb.append("─".repeat(145)).append('\n');

        for (OutboundApiCall c : lastResult) {
            sb.append(String.format("%-25s %-30s %-7s %-60s %-8s %s%n",
                    truncate(c.callerClass(), 24),
                    truncate(c.callerMethod(), 29),
                    c.httpMethod(),
                    truncate(c.resolvedUrl(), 59),
                    c.confidence().name(),
                    c.clientType().name().replace('_', ' ')));
        }

        Toolkit.getDefaultToolkit()
                .getSystemClipboard()
                .setContents(new StringSelection(sb.toString()), null);
        statusLabel.setText("Copied " + lastResult.size() + " row(s) to clipboard.");
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    // -------------------------------------------------------------------------
    // Cell renderers
    // -------------------------------------------------------------------------

    private static final class ConfidenceCellRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(
                JTable t, Object value, boolean isSelected, boolean hasFocus, int row, int col) {
            super.getTableCellRendererComponent(t, value, isSelected, hasFocus, row, col);
            if (!isSelected) {
                String v = value != null ? value.toString() : "";
                setForeground(switch (v) {
                    case "HIGH"   -> new Color(0, 140, 0);
                    case "MEDIUM" -> new Color(180, 110, 0);
                    case "LOW"    -> new Color(180, 0, 0);
                    default       -> getForeground();
                });
            }
            return this;
        }
    }

    private static final class HttpMethodCellRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(
                JTable t, Object value, boolean isSelected, boolean hasFocus, int row, int col) {
            super.getTableCellRendererComponent(t, value, isSelected, hasFocus, row, col);
            if (!isSelected) {
                String v = value != null ? value.toString() : "";
                setForeground(switch (v) {
                    case "GET"    -> new Color(0, 100, 180);
                    case "POST"   -> new Color(0, 140, 0);
                    case "PUT"    -> new Color(160, 90, 0);
                    case "PATCH"  -> new Color(130, 0, 130);
                    case "DELETE" -> new Color(180, 0, 0);
                    default       -> getForeground();
                });
            }
            return this;
        }
    }

    // -------------------------------------------------------------------------
    // Combo item wrapper
    // -------------------------------------------------------------------------

    private record EndpointItem(EndpointInfo endpoint) {
        @Override
        public String toString() {
            return endpoint.httpMethod() + "  " + endpoint.path()
                    + "  →  " + endpoint.controllerName() + "." + endpoint.methodSignature();
        }
    }

    private static final class EndpointComboRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(
                JList<?> list, Object value, int index, boolean isSelected, boolean hasFocus) {
            super.getListCellRendererComponent(list, value, index, isSelected, hasFocus);
            if (value instanceof EndpointItem item) {
                setText(item.toString());
            }
            return this;
        }
    }
}
