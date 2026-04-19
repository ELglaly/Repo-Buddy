package com.repoinspector.ui;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.ui.components.JBScrollPane;
import com.repoinspector.analysis.api.CallChainService;
import com.repoinspector.analysis.api.EndpointAnalysisService;
import com.repoinspector.model.CallChainNode;
import com.repoinspector.model.EndpointInfo;
import com.repoinspector.model.OperationType;
import com.repoinspector.runner.service.api.ParameterExtractionService;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

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
 * <p>Long-lived fields ({@link #lastResult}, {@link #lastEndpoint}) store
 * PSI-stripped copies so live {@code PsiMethod} references are not held across
 * read actions.  Navigation re-resolves PSI lazily inside a fresh read action.
 */
public class CallChainPanel extends JPanel {

    private final Project project;

    private final JComboBox<EndpointInfo> endpointCombo;
    private final JTextField             endpointSearchField;
    private final JTree                  callChainTree;
    private final DefaultTreeModel       treeModel;
    private final JLabel                 statusLabel;
    private final JLabel                 summaryLabel;
    private final JToggleButton          reposOnlyToggle;

    /** Full unfiltered endpoint list — source of truth for {@link #filterEndpoints()}. */
    private List<EndpointInfo> allEndpoints = List.of();

    /** PSI-stripped — safe to hold across read actions. */
    private List<CallChainNode> lastResult   = List.of();
    @Nullable private EndpointInfo lastEndpoint = null;

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Programmatically pushes a pre-computed result into the panel.
     * Must be called on the Event Dispatch Thread.
     */
    public void showResult(@NotNull EndpointInfo endpoint, @NotNull List<CallChainNode> nodes) {
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

    public CallChainPanel(@NotNull Project project) {
        this.project = project;
        setLayout(new BorderLayout());

        endpointCombo = new JComboBox<>();
        endpointCombo.setRenderer(new EndpointComboRenderer());
        endpointCombo.setPrototypeDisplayValue(
                new EndpointInfo("GET", "/placeholder/path", "Controller", "method()", null, null));

        endpointSearchField = new JTextField(16);
        endpointSearchField.putClientProperty("JTextField.placeholderText", "Filter endpoints\u2026");
        endpointSearchField.setToolTipText("Filter by HTTP verb (e.g. POST) or path (e.g. /users)");
        endpointSearchField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e)  { filterEndpoints(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e)  { filterEndpoints(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { filterEndpoints(); }
        });

        JButton traceButton   = UITheme.button("Trace");
        JButton refreshButton = UITheme.button("\u21BB  Refresh");
        JButton copyButton    = UITheme.button("\u2398  Copy");

        traceButton.setFont(traceButton.getFont().deriveFont(Font.BOLD, 12f));
        traceButton.setForeground(UITheme.SUCCESS);

        traceButton.addActionListener(e   -> runTrace());
        refreshButton.addActionListener(e -> loadEndpoints());
        copyButton.addActionListener(e    -> copyAsText());

        JPanel primaryRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        primaryRow.add(new JLabel("Filter:"));
        primaryRow.add(endpointSearchField);
        primaryRow.add(new JLabel("Endpoint:"));
        primaryRow.add(endpointCombo);
        primaryRow.add(traceButton);
        primaryRow.add(refreshButton);
        primaryRow.add(copyButton);

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
    // Endpoint filtering
    // =========================================================================

    private void filterEndpoints() {
        String query = endpointSearchField.getText().trim().toLowerCase(java.util.Locale.ROOT);
        EndpointInfo prev = (EndpointInfo) endpointCombo.getSelectedItem();

        endpointCombo.removeAllItems();
        allEndpoints.stream()
                .filter(ep -> query.isEmpty()
                        || ep.httpMethod().toLowerCase(java.util.Locale.ROOT).contains(query)
                        || ep.path().toLowerCase(java.util.Locale.ROOT).contains(query))
                .forEach(endpointCombo::addItem);

        if (prev != null) {
            for (int i = 0; i < endpointCombo.getItemCount(); i++) {
                EndpointInfo item = endpointCombo.getItemAt(i);
                if (item.controllerName().equals(prev.controllerName())
                        && item.methodSignature().equals(prev.methodSignature())) {
                    endpointCombo.setSelectedIndex(i);
                    break;
                }
            }
        }

        if (!allEndpoints.isEmpty()) {
            int shown = endpointCombo.getItemCount();
            setStatus(shown + " of " + allEndpoints.size() + " endpoint(s) — select one and click Trace.",
                    UITheme.MUTED);
        }

        if (!query.isEmpty() && endpointCombo.getItemCount() > 0) {
            SwingUtilities.invokeLater(endpointCombo::showPopup);
        } else if (query.isEmpty()) {
            endpointCombo.hidePopup();
        }
    }

    // =========================================================================
    // Endpoint loading
    // =========================================================================

    private void loadEndpoints() {
        setStatus("Waiting for IDE indexing\u2026", UITheme.MUTED);
        DumbService.getInstance(project).runWhenSmart(() ->
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                List<EndpointInfo> endpoints =
                        project.getService(EndpointAnalysisService.class).findAllEndpoints();
                SwingUtilities.invokeLater(() -> {
                    allEndpoints = List.copyOf(endpoints);
                    filterEndpoints();
                    if (endpoints.isEmpty()) {
                        setStatus("No Spring endpoints found in project.", UITheme.WARNING);
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
            List<CallChainNode> nodes =
                    project.getService(CallChainService.class).getOrAnalyze(selected);
            SwingUtilities.invokeLater(() -> renderResult(selected, nodes, false));
        });
    }

    // =========================================================================
    // Tree rendering
    // =========================================================================

    private void renderResult(EndpointInfo endpoint, List<CallChainNode> nodes, boolean fromCache) {
        // Strip PSI before storing — live PsiMethod must not be held across read actions.
        lastResult   = nodes.stream().map(CallChainNode::withoutPsi).toList();
        lastEndpoint = endpoint.withoutPsi();

        boolean reposOnly = reposOnlyToggle.isSelected();
        List<CallChainNode> visible = reposOnly
                ? nodes.stream().filter(n -> n.depth() == 0 || n.isRepository()).toList()
                : nodes;

        treeModel.setRoot(buildTree(endpoint, visible));
        expandAll();

        long repoCount = nodes.stream().filter(CallChainNode::isRepository).count();
        String cacheNote = fromCache ? "  (cached)" : "";
        setStatus("Found " + repoCount + " repository call(s) in chain." + cacheNote,
                repoCount > 0 ? UITheme.SUCCESS : UITheme.WARNING);

        updateSummary(nodes);
    }

    private static DefaultMutableTreeNode buildTree(EndpointInfo endpoint, List<CallChainNode> visible) {
        DefaultMutableTreeNode root = new DefaultMutableTreeNode(endpoint);
        // Use a map keyed by depth so filtered nodes don't leave null gaps.
        Map<Integer, DefaultMutableTreeNode> byDepth = new HashMap<>();
        byDepth.put(0, root);

        for (CallChainNode node : visible) {
            if (node.depth() == 0) continue;
            DefaultMutableTreeNode treeNode = new DefaultMutableTreeNode(node);
            DefaultMutableTreeNode parent   = byDepth.getOrDefault(node.depth() - 1, root);
            parent.add(treeNode);
            byDepth.put(node.depth(), treeNode);
        }
        return root;
    }

    // =========================================================================
    // Summary bar
    // =========================================================================

    private void updateSummary(List<CallChainNode> nodes) {
        long reads  = nodes.stream()
                .filter(n -> n.isRepository() && n.operationType() == OperationType.READ).count();
        long writes = nodes.stream()
                .filter(n -> n.isRepository() && n.operationType() == OperationType.WRITE).count();
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
        project.getService(CallChainService.class).clearCache();
        setStatus("Cache cleared. Click Trace to re-analyze.", UITheme.SUCCESS);
    }

    // =========================================================================
    // Navigation — resolves PSI lazily if the node was loaded from the cache
    // =========================================================================

    private void navigateToSelected() {
        TreePath path = callChainTree.getSelectionPath();
        if (path == null) return;

        DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
        Object userObj = node.getUserObject();

        if (userObj instanceof CallChainNode chainNode && !chainNode.isDynamic()) {
            if (chainNode.psiMethod() != null && chainNode.psiMethod().isValid()) {
                chainNode.psiMethod().navigate(true);
            } else if (chainNode.qualifiedClassName() != null) {
                resolveAndNavigate(chainNode.qualifiedClassName(), chainNode.methodSignature());
            }
        } else if (userObj instanceof EndpointInfo ep) {
            if (ep.psiMethod() != null && ep.psiMethod().isValid()) {
                ep.psiMethod().navigate(true);
            } else if (ep.qualifiedControllerName() != null) {
                resolveAndNavigate(ep.qualifiedControllerName(), ep.methodSignature());
            }
        }
    }

    private void resolveAndNavigate(@NotNull String qualifiedClassName, @NotNull String signature) {
        ApplicationManager.getApplication().executeOnPooledThread(() ->
            ApplicationManager.getApplication().runReadAction(() -> {
                JavaPsiFacade facade = JavaPsiFacade.getInstance(project);
                PsiClass cls = facade.findClass(qualifiedClassName,
                        GlobalSearchScope.projectScope(project));
                if (cls == null) return;
                ParameterExtractionService svc = ApplicationManager.getApplication()
                        .getService(ParameterExtractionService.class);
                for (PsiMethod m : cls.getMethods()) {
                    if (svc.buildSignature(m).equals(signature)) {
                        SwingUtilities.invokeLater(() -> m.navigate(true));
                        return;
                    }
                }
            })
        );
    }

    // =========================================================================
    // Clipboard export
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
}
