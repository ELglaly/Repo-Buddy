package com.repoinspector.ui;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.ui.components.JBScrollPane;
import com.repoinspector.analysis.CallChainCache;
import com.repoinspector.analysis.EndpointFinder;
import com.repoinspector.model.CallChainNode;
import com.repoinspector.model.EndpointInfo;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * Tool-window panel for the "Call Chain Tracer" tab.
 *
 * <p>Layout:
 * <pre>
 *  ┌─ NORTH ─────────────────────────────────────────────────────────────────┐
 *  │  Endpoint: [combo▼]  [ Trace ]  [ \u21BB Refresh ]  [ \u2398 Copy ]           │
 *  │  &lt;status label&gt;                                                         │
 *  └─────────────────────────────────────────────────────────────────────────┘
 *  ┌─ CENTER ────────────────────────────────────────────────────────────────┐
 *  │  JTree — call-chain result with custom colour renderer                  │
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
        // Select the matching endpoint in the combo if present.
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

        // ── Toolbar buttons ───────────────────────────────────────────────────
        JButton traceButton   = UITheme.button("Trace");
        JButton refreshButton = UITheme.button("\u21BB  Refresh");
        JButton copyButton    = UITheme.button("\u2398  Copy");

        traceButton.setFont(traceButton.getFont().deriveFont(Font.BOLD, 12f));
        traceButton.setForeground(UITheme.SUCCESS);

        traceButton.addActionListener(e   -> runTrace());
        refreshButton.addActionListener(e -> loadEndpoints());
        copyButton.addActionListener(e    -> copyAsText());

        JPanel toolbarRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        toolbarRow.add(new JLabel("Endpoint:"));
        toolbarRow.add(endpointCombo);
        toolbarRow.add(traceButton);
        toolbarRow.add(refreshButton);
        toolbarRow.add(copyButton);

        // ── Status label ──────────────────────────────────────────────────────
        statusLabel = new JLabel("  Select an endpoint and click Trace.");
        statusLabel.setFont(statusLabel.getFont().deriveFont(11f));
        statusLabel.setForeground(UITheme.MUTED);
        statusLabel.setBorder(BorderFactory.createEmptyBorder(0, 8, 4, 8));

        JPanel north = new JPanel(new BorderLayout());
        north.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, UITheme.ACCENT.darker()));
        north.add(toolbarRow,   BorderLayout.NORTH);
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

        DefaultMutableTreeNode root = new DefaultMutableTreeNode(endpoint);

        // depth → most-recent parent node at that depth
        List<DefaultMutableTreeNode> depthStack = new ArrayList<>();
        depthStack.add(root);

        for (CallChainNode node : nodes) {
            if (node.depth() == 0) continue;   // endpoint is the tree root

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
    }

    private void expandAll() {
        for (int i = 0; i < callChainTree.getRowCount(); i++) {
            callChainTree.expandRow(i);
        }
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
    // Combo renderer
    // =========================================================================

    private static final class EndpointComboRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(
                JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (value instanceof EndpointInfo ep) {
                setText(ep.toString());
            }
            return this;
        }
    }
}
