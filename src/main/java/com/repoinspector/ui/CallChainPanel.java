package com.repoinspector.ui;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.ui.components.JBScrollPane;
import com.repoinspector.analysis.CallChainCache;
import com.repoinspector.analysis.EndpointFinder;
import com.repoinspector.model.CallChainNode;
import com.repoinspector.model.EndpointInfo;
import com.repoinspector.model.OperationType;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.*;
import java.util.List;

/**
 * Tool-window panel for the "Call Chain Tracer" tab.
 *
 * <p>Layout:
 * <pre>
 *  ┌─ NORTH ─────────────────────────────────────────────────────────────────┐
 *  │  Endpoint: [combo▼]  [Trace]  [↻ Refresh]  [⌘ Copy]                    │
 *  │  [⊞ Expand All]  [⊟ Collapse All]  [✖ Clear Cache]  [Repos Only toggle] │
 *  │  &lt;status label&gt;                                                         │
 *  └─────────────────────────────────────────────────────────────────────────┘
 *  ┌─ CENTER ────────────────────────────────────────────────────────────────┐
 *  │  JTree — call-chain result with custom colour renderer                  │
 *  └─────────────────────────────────────────────────────────────────────────┘
 *  ┌─ SOUTH ─────────────────────────────────────────────────────────────────┐
 *  │  Summary: READ N  WRITE N  @Tx N  Entities: A, B, C                     │
 *  └─────────────────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <p>Text export is delegated to {@link TextExportBuilder} (SRP).
 */
public class CallChainPanel extends JPanel {

    private final Project project;

    private final JComboBox<EndpointInfo> endpointCombo;
    private final JTree                  callChainTree;
    private final DefaultTreeModel       treeModel;
    private final JLabel                 statusLabel;
    private final JLabel                 summaryLabel;
    private final JToggleButton          reposOnlyToggle;

    /** Last result kept for clipboard export. */
    private List<CallChainNode> lastResult   = List.of();
    private EndpointInfo        lastEndpoint = null;

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Programmatically pushes a pre-computed result into the panel (called from
     * {@link com.repoinspector.actions.TraceRepositoryCallsAction}).
     * Must be called on the Event Dispatch Thread.
     */
    public void showResult(EndpointInfo endpoint, List<CallChainNode> nodes) {
        for (int i = 0; i < endpointCombo.getItemCount(); i++) {
            EndpointInfo item = endpointCombo.getItemAt(i);
            if (item.controllerName().equals(endpoint.controllerName())
                    && item.methodSignature().equals(endpoint.methodSignature())) {
                endpointCombo.setSelectedIndex(i);
                break;
            }
        }
        renderResult(endpoint, nodes, true);
    }

    // =========================================================================
    // Construction
    // =========================================================================

    public CallChainPanel(Project project) {
        this.project = project;
        setLayout(new BorderLayout());

        // ── Endpoint combo ────────────────────────────────────────────────────
        endpointCombo = new JComboBox<>();
        endpointCombo.setRenderer(new EndpointComboRenderer());
        endpointCombo.setPrototypeDisplayValue(
                new EndpointInfo("GET", "/placeholder/path", "Controller", "method()", null));

        // ── Primary action buttons ────────────────────────────────────────────
        JButton traceButton   = UITheme.button("Trace");
        JButton refreshButton = UITheme.button("\u21BB  Refresh");
        JButton copyButton    = UITheme.button("\u2398  Copy");

        traceButton.setFont(traceButton.getFont().deriveFont(Font.BOLD, 12f));
        traceButton.setForeground(UITheme.SUCCESS);

        traceButton.addActionListener(e   -> runTrace());
        refreshButton.addActionListener(e -> loadEndpoints());
        copyButton.addActionListener(e    -> copyAsText());

        JPanel primaryRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        primaryRow.add(new JLabel("Endpoint:"));
        primaryRow.add(endpointCombo);
        primaryRow.add(traceButton);
        primaryRow.add(refreshButton);
        primaryRow.add(copyButton);

        // ── Secondary action buttons ──────────────────────────────────────────
        JButton expandAllBtn   = UITheme.button("\u229E  Expand All");
        JButton collapseAllBtn = UITheme.button("\u229F  Collapse All");
        JButton clearCacheBtn  = UITheme.button("\u2715  Clear Cache");

        expandAllBtn.setFont(expandAllBtn.getFont().deriveFont(11f));
        collapseAllBtn.setFont(collapseAllBtn.getFont().deriveFont(11f));
        clearCacheBtn.setFont(clearCacheBtn.getFont().deriveFont(11f));
        clearCacheBtn.setForeground(UITheme.WARNING);

        expandAllBtn.addActionListener(e   -> expandAll());
        collapseAllBtn.addActionListener(e -> collapseAll());
        clearCacheBtn.addActionListener(e  -> clearCache());

        reposOnlyToggle = new JToggleButton("Repos Only");
        reposOnlyToggle.setFont(reposOnlyToggle.getFont().deriveFont(11f));
        reposOnlyToggle.setFocusPainted(false);
        reposOnlyToggle.setToolTipText("Show only repository method nodes in the tree");
        reposOnlyToggle.addItemListener(e -> {
            if (lastEndpoint != null) renderResult(lastEndpoint, lastResult, true);
        });

        JPanel secondaryRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        secondaryRow.add(expandAllBtn);
        secondaryRow.add(collapseAllBtn);
        secondaryRow.add(clearCacheBtn);
        secondaryRow.add(reposOnlyToggle);

        // ── Status label ──────────────────────────────────────────────────────
        statusLabel = new JLabel("  Select an endpoint and click Trace.");
        statusLabel.setFont(statusLabel.getFont().deriveFont(11f));
        statusLabel.setForeground(UITheme.MUTED);
        statusLabel.setBorder(BorderFactory.createEmptyBorder(0, 8, 4, 8));

        JPanel north = new JPanel(new BorderLayout());
        north.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, UITheme.ACCENT.darker()));
        north.add(primaryRow,   BorderLayout.NORTH);
        north.add(secondaryRow, BorderLayout.CENTER);
        north.add(statusLabel,  BorderLayout.SOUTH);
        add(north, BorderLayout.NORTH);

        // ── Tree ──────────────────────────────────────────────────────────────
        DefaultMutableTreeNode root = new DefaultMutableTreeNode("No endpoint selected");
        treeModel     = new DefaultTreeModel(root);
        callChainTree = new JTree(treeModel);
        callChainTree.setCellRenderer(new CallChainTreeRenderer());
        callChainTree.setRootVisible(true);
        callChainTree.setShowsRootHandles(true);
        callChainTree.setRowHeight(22);

        callChainTree.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) navigateToSelected();
            }
        });

        add(new JBScrollPane(callChainTree), BorderLayout.CENTER);

        // ── Summary bar (SOUTH) ───────────────────────────────────────────────
        summaryLabel = new JLabel("  No trace run yet.");
        summaryLabel.setFont(summaryLabel.getFont().deriveFont(11f));
        summaryLabel.setForeground(UITheme.MUTED);
        summaryLabel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, UITheme.ACCENT.darker()),
                BorderFactory.createEmptyBorder(4, 10, 4, 10)));
        add(summaryLabel, BorderLayout.SOUTH);

        SwingUtilities.invokeLater(this::loadEndpoints);
    }

    // =========================================================================
    // Endpoint loading
    // =========================================================================

    private void loadEndpoints() {
        setStatus("Waiting for IDE indexing\u2026", UITheme.MUTED);
        DumbService.getInstance(project).runWhenSmart(() ->
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                List<EndpointInfo> endpoints = EndpointFinder.findAllEndpoints(project);
                SwingUtilities.invokeLater(() -> {
                    endpointCombo.removeAllItems();
                    endpoints.forEach(endpointCombo::addItem);
                    if (endpoints.isEmpty()) {
                        setStatus("No Spring endpoints found in project.", UITheme.WARNING);
                    } else {
                        setStatus(endpoints.size() + " endpoint(s) found — select one and click Trace.", UITheme.MUTED);
                    }
                });
            })
        );
    }

    // =========================================================================
    // Trace
    // =========================================================================

    private void runTrace() {
        EndpointInfo selected = (EndpointInfo) endpointCombo.getSelectedItem();
        if (selected == null) {
            setStatus("No endpoint selected.", UITheme.WARNING);
            return;
        }

        setStatus("Analyzing call chain\u2026", UITheme.ACCENT);
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            List<CallChainNode> nodes = CallChainCache.getOrAnalyze(selected, project);
            SwingUtilities.invokeLater(() -> renderResult(selected, nodes, false));
        });
    }

    // =========================================================================
    // Tree rendering
    // =========================================================================

    private void renderResult(EndpointInfo endpoint, List<CallChainNode> nodes, boolean fromCache) {
        lastResult   = nodes;
        lastEndpoint = endpoint;

        boolean reposOnly = reposOnlyToggle.isSelected();
        List<CallChainNode> visible = reposOnly
                ? nodes.stream().filter(n -> n.depth() == 0 || n.isRepository()).toList()
                : nodes;

        DefaultMutableTreeNode root = new DefaultMutableTreeNode(endpoint);

        List<DefaultMutableTreeNode> depthStack = new ArrayList<>();
        depthStack.add(root);

        for (CallChainNode node : visible) {
            if (node.depth() == 0) continue;

            DefaultMutableTreeNode treeNode = new DefaultMutableTreeNode(node);
            int parentDepth = node.depth() - 1;

            while (depthStack.size() <= node.depth()) {
                depthStack.add(null);
            }

            DefaultMutableTreeNode parent = (parentDepth < depthStack.size())
                    ? depthStack.get(parentDepth)
                    : root;
            if (parent == null) parent = root;

            parent.add(treeNode);
            depthStack.set(node.depth(), treeNode);
        }

        treeModel.setRoot(root);
        expandAll();

        long repoCount = nodes.stream().filter(CallChainNode::isRepository).count();
        String cacheNote = fromCache ? "  (cached)" : "";
        setStatus("Found " + repoCount + " repository call(s) in chain." + cacheNote,
                repoCount > 0 ? UITheme.SUCCESS : UITheme.WARNING);

        updateSummary(nodes);
    }

    // =========================================================================
    // Summary bar
    // =========================================================================

    private void updateSummary(List<CallChainNode> nodes) {
        long reads  = nodes.stream().filter(n -> n.isRepository() && n.operationType() == OperationType.READ).count();
        long writes = nodes.stream().filter(n -> n.isRepository() && n.operationType() == OperationType.WRITE).count();
        long txs    = nodes.stream().filter(CallChainNode::isTransactional).count();

        Set<String> entities = new LinkedHashSet<>();
        nodes.stream()
             .filter(n -> n.isRepository() && !n.entityName().isEmpty())
             .map(CallChainNode::entityName)
             .forEach(entities::add);

        String entityText = entities.isEmpty() ? "none" : String.join(", ", entities);

        String html = "<html>"
                + "<font color='" + UITheme.toHex(UITheme.SUCCESS) + "'><b>READ&nbsp;" + reads + "</b></font>"
                + "&nbsp;&nbsp;"
                + "<font color='" + UITheme.toHex(UITheme.WARNING) + "'><b>WRITE&nbsp;" + writes + "</b></font>"
                + "&nbsp;&nbsp;"
                + "<font color='" + UITheme.toHex(UITheme.ACCENT) + "'><b>@Tx&nbsp;" + txs + "</b></font>"
                + "&nbsp;&nbsp;&nbsp;"
                + "<font color='" + UITheme.toHex(UITheme.MUTED) + "'>Entities: " + entityText + "</font>"
                + "</html>";
        summaryLabel.setText(html);
    }

    // =========================================================================
    // Tree expand / collapse
    // =========================================================================

    private void expandAll() {
        for (int i = 0; i < callChainTree.getRowCount(); i++) {
            callChainTree.expandRow(i);
        }
    }

    private void collapseAll() {
        // Collapse from the bottom up so parent rows are not renumbered mid-loop
        for (int i = callChainTree.getRowCount() - 1; i >= 1; i--) {
            callChainTree.collapseRow(i);
        }
    }

    // =========================================================================
    // Clear cache
    // =========================================================================

    private void clearCache() {
        EndpointInfo selected = (EndpointInfo) endpointCombo.getSelectedItem();
        if (selected == null) {
            setStatus("No endpoint selected — nothing to clear.", UITheme.WARNING);
            return;
        }
        CallChainCache.clear();
        setStatus("Cache cleared. Click Trace to re-analyze.", UITheme.SUCCESS);
    }

    // =========================================================================
    // Navigation
    // =========================================================================

    private void navigateToSelected() {
        TreePath path = callChainTree.getSelectionPath();
        if (path == null) return;

        DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
        Object userObj = node.getUserObject();

        if (userObj instanceof CallChainNode chainNode
                && chainNode.psiMethod() != null
                && !chainNode.isDynamic()) {
            chainNode.psiMethod().navigate(true);
        } else if (userObj instanceof EndpointInfo ep && ep.psiMethod() != null) {
            ep.psiMethod().navigate(true);
        }
    }

    // =========================================================================
    // Clipboard export — delegated to TextExportBuilder (SRP)
    // =========================================================================

    private void copyAsText() {
        if (lastEndpoint == null || lastResult.isEmpty()) {
            setStatus("Nothing to copy \u2014 run a trace first.", UITheme.WARNING);
            return;
        }

        String text = TextExportBuilder.build(lastEndpoint, lastResult);
        Toolkit.getDefaultToolkit()
                .getSystemClipboard()
                .setContents(new StringSelection(text), null);
        setStatus("Copied to clipboard.", UITheme.SUCCESS);
    }

    // =========================================================================
    // Status helper
    // =========================================================================

    private void setStatus(String text, Color color) {
        statusLabel.setText("  " + text);
        statusLabel.setForeground(color);
    }

    // =========================================================================
    // Combo renderer — HTTP method colour badges
    // =========================================================================

    private static final class EndpointComboRenderer extends DefaultListCellRenderer {

        private static final Map<String, Color> HTTP_COLORS = Map.of(
                "GET",    new Color( 80, 200, 120),   // green
                "POST",   new Color( 97, 175, 239),   // blue
                "PUT",    new Color(255, 195,  90),   // amber
                "DELETE", new Color(255,  70,  70),   // red
                "PATCH",  new Color(206, 145, 120)    // orange
        );
        private static final Color HTTP_DEFAULT = new Color(160, 160, 160);

        @Override
        public Component getListCellRendererComponent(
                JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {

            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);

            if (value instanceof EndpointInfo ep) {
                Color methodColor = HTTP_COLORS.getOrDefault(
                        ep.httpMethod().toUpperCase(), HTTP_DEFAULT);
                String hex = String.format("#%02x%02x%02x",
                        methodColor.getRed(), methodColor.getGreen(), methodColor.getBlue());
                setText("<html><font color='" + hex + "'><b>[" + ep.httpMethod() + "]</b></font>"
                        + "&nbsp;" + ep.path()
                        + "&nbsp;<font color='#808080'>[" + ep.controllerName()
                        + "." + ep.methodSignature() + "]</font></html>");
            }
            return this;
        }
    }
}
