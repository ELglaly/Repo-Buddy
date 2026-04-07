package com.repoinspector.ui;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.ui.components.JBScrollPane;
import com.repoinspector.analysis.CallChainAnalyzer;
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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Tool-window panel for the "Call Chain Tracer" tab.
 *
 * <p>Layout:
 * <pre>
 *  ┌─ NORTH (toolbar) ──────────────────────────────────────────┐
 *  │  [endpoint combo▼]  [Trace]  [Refresh Endpoints]  [Copy as Text]  │
 *  └────────────────────────────────────────────────────────────┘
 *  ┌─ CENTER ───────────────────────────────────────────────────┐
 *  │  JTree (call chain result)                                 │
 *  └────────────────────────────────────────────────────────────┘
 * </pre>
 */
public class CallChainPanel extends JPanel {

    private final Project project;

    private final JComboBox<EndpointInfo> endpointCombo;
    private final JTree callChainTree;
    private final DefaultTreeModel treeModel;
    private final JLabel statusLabel;

    /** Flat list of CallChainNode results from the last analysis — kept for export. */
    private List<CallChainNode> lastResult = List.of();
    private EndpointInfo lastEndpoint = null;

    /**
     * Programmatically push a pre-computed result into the panel (called from
     * {@link com.repoinspector.actions.TraceRepositoryCallsAction}).
     * Must be called on the Event Dispatch Thread.
     */
    public void showResult(EndpointInfo endpoint, List<CallChainNode> nodes) {
        // Select the matching endpoint in the combo if present
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

    public CallChainPanel(Project project) {
        this.project = project;
        setLayout(new BorderLayout());

        // --- toolbar ---
        endpointCombo = new JComboBox<>();
        endpointCombo.setRenderer(new EndpointComboRenderer());
        endpointCombo.setPrototypeDisplayValue(
                new EndpointInfo("GET", "/placeholder/path", "Controller", "method()", null));

        JButton traceButton = new JButton("Trace");
        JButton refreshButton = new JButton("Refresh Endpoints");
        JButton copyButton = new JButton("Copy as Text");
        statusLabel = new JLabel("Select an endpoint and click Trace.");

        traceButton.addActionListener(e -> runTrace());
        refreshButton.addActionListener(e -> loadEndpoints());
        copyButton.addActionListener(e -> copyAsText());

        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        toolbar.add(new JLabel("Endpoint:"));
        toolbar.add(endpointCombo);
        toolbar.add(traceButton);
        toolbar.add(refreshButton);
        toolbar.add(copyButton);

        JPanel northPanel = new JPanel(new BorderLayout());
        northPanel.add(toolbar, BorderLayout.NORTH);
        northPanel.add(statusLabel, BorderLayout.SOUTH);
        add(northPanel, BorderLayout.NORTH);

        // --- tree ---
        DefaultMutableTreeNode root = new DefaultMutableTreeNode("No endpoint selected");
        treeModel = new DefaultTreeModel(root);
        callChainTree = new JTree(treeModel);
        callChainTree.setCellRenderer(new CallChainTreeRenderer());
        callChainTree.setRootVisible(true);
        callChainTree.setShowsRootHandles(true);

        callChainTree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    navigateToSelected();
                }
            }
        });

        add(new JBScrollPane(callChainTree), BorderLayout.CENTER);

        // Load endpoints after UI is visible
        SwingUtilities.invokeLater(this::loadEndpoints);
    }

    // -------------------------------------------------------------------------
    // Endpoint loading
    // -------------------------------------------------------------------------

    private void loadEndpoints() {
        statusLabel.setText("Scanning for endpoints...");
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            List<EndpointInfo> endpoints = EndpointFinder.findAllEndpoints(project);
            SwingUtilities.invokeLater(() -> {
                endpointCombo.removeAllItems();
                for (EndpointInfo ep : endpoints) {
                    endpointCombo.addItem(ep);
                }
                if (endpoints.isEmpty()) {
                    statusLabel.setText("No Spring endpoints found in project.");
                } else {
                    statusLabel.setText(endpoints.size() + " endpoint(s) found. Select one and click Trace.");
                }
            });
        });
    }

    // -------------------------------------------------------------------------
    // Trace action
    // -------------------------------------------------------------------------

    private void runTrace() {
        EndpointInfo selected = (EndpointInfo) endpointCombo.getSelectedItem();
        if (selected == null) {
            statusLabel.setText("No endpoint selected.");
            return;
        }

        // Check cache first
        List<CallChainNode> cached = CallChainCache.getOrNull(selected, project);
        if (cached != null) {
            renderResult(selected, cached, true);
            return;
        }

        statusLabel.setText("Analyzing call chain...");
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            List<CallChainNode> nodes = CallChainAnalyzer.analyze(selected, project);
            CallChainCache.put(selected, project, nodes);
            SwingUtilities.invokeLater(() -> renderResult(selected, nodes, false));
        });
    }

    // -------------------------------------------------------------------------
    // Tree rendering
    // -------------------------------------------------------------------------

    private void renderResult(EndpointInfo endpoint, List<CallChainNode> nodes, boolean fromCache) {
        lastResult = nodes;
        lastEndpoint = endpoint;

        DefaultMutableTreeNode root = new DefaultMutableTreeNode(endpoint);

        // depth → most recent parent node at that depth
        List<DefaultMutableTreeNode> depthStack = new ArrayList<>();
        depthStack.add(root); // depth 0 parent is root

        for (CallChainNode node : nodes) {
            // Skip the endpoint node itself (depth 0) since it's the tree root
            if (node.depth() == 0) {
                continue;
            }

            DefaultMutableTreeNode treeNode = new DefaultMutableTreeNode(node);
            int parentDepth = node.depth() - 1;

            // Ensure depthStack has a slot for this depth
            while (depthStack.size() <= node.depth()) {
                depthStack.add(null);
            }

            DefaultMutableTreeNode parent = parentDepth < depthStack.size()
                    ? depthStack.get(parentDepth)
                    : root;
            if (parent == null) {
                parent = root;
            }

            parent.add(treeNode);
            depthStack.set(node.depth(), treeNode);
        }

        treeModel.setRoot(root);
        expandAll();

        long repoCount = nodes.stream().filter(CallChainNode::isRepository).count();
        String cacheNote = fromCache ? " (cached)" : "";
        statusLabel.setText("Found " + repoCount + " repository call(s) in chain." + cacheNote);
    }

    private void expandAll() {
        for (int i = 0; i < callChainTree.getRowCount(); i++) {
            callChainTree.expandRow(i);
        }
    }

    // -------------------------------------------------------------------------
    // Navigation
    // -------------------------------------------------------------------------

    private void navigateToSelected() {
        TreePath path = callChainTree.getSelectionPath();
        if (path == null) {
            return;
        }
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

    // -------------------------------------------------------------------------
    // Copy as text
    // -------------------------------------------------------------------------

    private void copyAsText() {
        if (lastEndpoint == null || lastResult.isEmpty()) {
            statusLabel.setText("Nothing to copy — run a trace first.");
            return;
        }

        String text = buildTextExport(lastEndpoint, lastResult);
        Toolkit.getDefaultToolkit()
                .getSystemClipboard()
                .setContents(new StringSelection(text), null);
        statusLabel.setText("Copied to clipboard.");
    }

    private static String buildTextExport(EndpointInfo endpoint, List<CallChainNode> nodes) {
        String separator = "─".repeat(60);
        StringBuilder sb = new StringBuilder();

        sb.append("API Endpoint: ").append(endpoint.httpMethod()).append(' ').append(endpoint.path())
                .append("  →  ").append(endpoint.controllerName()).append('.').append(endpoint.methodSignature())
                .append('\n');
        sb.append("Generated:    ")
                .append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
                .append('\n');
        sb.append(separator).append('\n');

        for (CallChainNode node : nodes) {
            if (node.depth() == 0) continue; // skip endpoint row itself
            sb.append("  ".repeat(node.depth())).append(node.displayLabel())
                    .append("  [depth=").append(node.depth()).append(']')
                    .append('\n');
        }

        // Summary section
        List<CallChainNode> repoNodes = nodes.stream()
                .filter(CallChainNode::isRepository)
                .toList();

        if (!repoNodes.isEmpty()) {
            sb.append(separator).append('\n');
            sb.append("Repository Methods Summary:\n");
            for (CallChainNode repo : repoNodes) {
                sb.append("  - ").append(repo.className()).append('.').append(repo.methodSignature())
                        .append("  →  ").append(repo.operationType().name());
                if (!repo.entityName().isEmpty()) {
                    sb.append("  (").append(repo.entityName()).append(')');
                }
                sb.append('\n');
            }
        }

        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Combo renderer
    // -------------------------------------------------------------------------

    private static class EndpointComboRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(
                JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus
        ) {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (value instanceof EndpointInfo ep) {
                setText(ep.toString());
            }
            return this;
        }
    }
}
