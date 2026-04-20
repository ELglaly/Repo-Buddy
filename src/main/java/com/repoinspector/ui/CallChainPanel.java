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
 * Redesigned to match the AfterTree reference design.
 */
public class CallChainPanel extends JPanel {

    private final Project project;

    private final JComboBox<EndpointInfo> endpointCombo;
    private final JTextField             endpointSearchField;
    private final JTree                  callChainTree;
    private final DefaultTreeModel       treeModel;
    private final JLabel                 statusLabel;
    private final JPanel                 summaryBar;
    private final JToggleButton          reposOnlyToggle;

    private List<EndpointInfo> allEndpoints = List.of();
    private List<CallChainNode> lastResult  = List.of();
    @Nullable private EndpointInfo lastEndpoint = null;

    // =========================================================================
    // Public API
    // =========================================================================

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

        // ── Endpoint controls ─────────────────────────────────────────────────
        endpointCombo = new JComboBox<>();
        endpointCombo.setRenderer(new EndpointComboRenderer());
        endpointCombo.setFont(UITheme.UI);
        endpointCombo.setPrototypeDisplayValue(
                new EndpointInfo("GET", "/placeholder/path", "Controller", "method()", null, null));

        endpointSearchField = new JTextField(16);
        endpointSearchField.putClientProperty("JTextField.placeholderText", "Filter endpoints\u2026");
        endpointSearchField.setFont(UITheme.UI);
        endpointSearchField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e)  { filterEndpoints(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e)  { filterEndpoints(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { filterEndpoints(); }
        });

        // ── Primary toolbar buttons ───────────────────────────────────────────
        JButton traceButton   = UITheme.button("Trace");
        traceButton.setFont(UITheme.UI.deriveFont(Font.BOLD, 12f));
        traceButton.setBackground(UITheme.ACCENT);
        traceButton.setForeground(Color.WHITE);
        traceButton.setOpaque(true);

        JButton refreshBtn = UITheme.iconButton("\u21BB");
        JButton copyBtn    = UITheme.iconButton("\u2398");
        refreshBtn.setToolTipText("Refresh endpoints");
        copyBtn.setToolTipText("Copy as text");

        traceButton.addActionListener(e -> runTrace());
        refreshBtn.addActionListener(e -> loadEndpoints());
        copyBtn.addActionListener(e    -> copyAsText());

        // ── Primary toolbar row ───────────────────────────────────────────────
        JPanel searchWrapper = new JPanel(new BorderLayout(4, 0));
        searchWrapper.setOpaque(false);
        searchWrapper.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(UITheme.BORDER_SUB, 1),
                BorderFactory.createEmptyBorder(2, 6, 2, 6)));
        JLabel filterIcon = new JLabel("\u25BC");
        filterIcon.setFont(UITheme.UI_SM);
        filterIcon.setForeground(UITheme.MUTED);
        searchWrapper.add(filterIcon, BorderLayout.WEST);
        searchWrapper.add(endpointSearchField, BorderLayout.CENTER);

        JPanel primaryRow = new JPanel();
        primaryRow.setLayout(new BoxLayout(primaryRow, BoxLayout.X_AXIS));
        primaryRow.setOpaque(false);
        primaryRow.setBorder(BorderFactory.createEmptyBorder(5, 8, 4, 8));
        primaryRow.add(searchWrapper);
        primaryRow.add(Box.createHorizontalStrut(6));
        primaryRow.add(endpointCombo);
        primaryRow.add(Box.createHorizontalStrut(6));
        primaryRow.add(traceButton);
        primaryRow.add(Box.createHorizontalGlue());
        primaryRow.add(refreshBtn);
        primaryRow.add(Box.createHorizontalStrut(2));
        primaryRow.add(copyBtn);

        // ── Secondary (dense) toolbar row ─────────────────────────────────────
        SegmentedControl expandCollapse = new SegmentedControl("\u229E  Expand", "\u229F  Collapse");
        expandCollapse.getButton(0).addActionListener(e -> expandAll());
        expandCollapse.getButton(1).addActionListener(e -> collapseAll());

        reposOnlyToggle = UITheme.toggleButton("\uD83D\uDDB4 Repos only");
        reposOnlyToggle.setToolTipText("Show only repository call nodes");
        reposOnlyToggle.addItemListener(e -> {
            if (lastEndpoint != null) renderResult(lastEndpoint, lastResult, true);
        });

        JButton clearCacheBtn = UITheme.button("\u2715  Clear cache");
        clearCacheBtn.setFont(UITheme.UI_SM);
        clearCacheBtn.setForeground(UITheme.WARNING);
        clearCacheBtn.setToolTipText("Clear analysis cache");
        clearCacheBtn.addActionListener(e -> clearCache());

        JPanel secondaryRow = new JPanel();
        secondaryRow.setLayout(new BoxLayout(secondaryRow, BoxLayout.X_AXIS));
        secondaryRow.setOpaque(false);
        secondaryRow.setBorder(BorderFactory.createEmptyBorder(2, 8, 4, 8));
        secondaryRow.add(expandCollapse);
        secondaryRow.add(Box.createHorizontalStrut(6));
        secondaryRow.add(UITheme.toolbarDivider());
        secondaryRow.add(Box.createHorizontalStrut(6));
        secondaryRow.add(reposOnlyToggle);
        secondaryRow.add(Box.createHorizontalGlue());
        secondaryRow.add(clearCacheBtn);

        // ── Status label ──────────────────────────────────────────────────────
        statusLabel = new JLabel("  Select an endpoint and click Trace.");
        statusLabel.setFont(UITheme.UI_SM);
        statusLabel.setForeground(UITheme.MUTED);
        statusLabel.setBorder(BorderFactory.createEmptyBorder(0, 8, 4, 8));

        JPanel north = new JPanel(new BorderLayout());
        north.setBackground(UITheme.TOOLBAR);
        north.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, UITheme.BORDER_SUB));
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
        callChainTree.setBackground(UITheme.PANEL);
        callChainTree.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) navigateToSelected();
            }
        });

        add(new JBScrollPane(callChainTree), BorderLayout.CENTER);

        // ── Summary bar ───────────────────────────────────────────────────────
        summaryBar = new JPanel();
        summaryBar.setLayout(new BoxLayout(summaryBar, BoxLayout.X_AXIS));
        summaryBar.setBackground(UITheme.TOOLBAR);
        summaryBar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, UITheme.BORDER_SUB),
                BorderFactory.createEmptyBorder(4, 10, 4, 10)));
        summaryBar.add(buildStatPill("0 READ",  UITheme.SUCCESS, UITheme.SUCCESS_SUB));
        summaryBar.add(Box.createHorizontalStrut(6));
        summaryBar.add(buildStatPill("0 WRITE", UITheme.WARNING, UITheme.WARNING_SUB));
        summaryBar.add(Box.createHorizontalStrut(6));
        summaryBar.add(buildStatPill("0 @Tx",   UITheme.INDIGO,  UITheme.INDIGO_SUB));
        summaryBar.add(Box.createHorizontalGlue());
        add(summaryBar, BorderLayout.SOUTH);

        SwingUtilities.invokeLater(this::loadEndpoints);
    }

    // =========================================================================
    // Endpoint filtering
    // =========================================================================

    private void filterEndpoints() {
        String query = endpointSearchField.getText().trim().toLowerCase(Locale.ROOT);
        EndpointInfo prev = (EndpointInfo) endpointCombo.getSelectedItem();

        endpointCombo.removeAllItems();
        allEndpoints.stream()
                .filter(ep -> query.isEmpty()
                        || ep.httpMethod().toLowerCase(Locale.ROOT).contains(query)
                        || ep.path().toLowerCase(Locale.ROOT).contains(query))
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
            setStatus(endpointCombo.getItemCount() + " of " + allEndpoints.size()
                    + " endpoint(s) \u2014 select one and click Trace.", UITheme.MUTED);
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
                    if (endpoints.isEmpty()) setStatus("No Spring endpoints found.", UITheme.WARNING);
                });
            })
        );
    }

    // =========================================================================
    // Trace
    // =========================================================================

    private void runTrace() {
        EndpointInfo selected = (EndpointInfo) endpointCombo.getSelectedItem();
        if (selected == null) { setStatus("No endpoint selected.", UITheme.WARNING); return; }

        setStatus("Analyzing call chain\u2026", UITheme.ACCENT);
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            List<CallChainNode> nodes = project.getService(CallChainService.class).getOrAnalyze(selected);
            SwingUtilities.invokeLater(() -> renderResult(selected, nodes, false));
        });
    }

    // =========================================================================
    // Tree rendering
    // =========================================================================

    private void renderResult(EndpointInfo endpoint, List<CallChainNode> nodes, boolean fromCache) {
        lastResult   = nodes.stream().map(CallChainNode::withoutPsi).toList();
        lastEndpoint = endpoint.withoutPsi();

        boolean reposOnly = reposOnlyToggle.isSelected();
        List<CallChainNode> visible = reposOnly
                ? nodes.stream().filter(n -> n.depth() == 0 || n.isRepository()).toList()
                : nodes;

        treeModel.setRoot(buildTree(endpoint, visible));
        expandAll();

        long repoCount = nodes.stream().filter(CallChainNode::isRepository).count();
        String cacheNote = fromCache ? " (cached)" : "";
        setStatus("Found " + repoCount + " repository call(s) in chain." + cacheNote,
                repoCount > 0 ? UITheme.SUCCESS : UITheme.WARNING);

        updateSummaryBar(nodes);
    }

    private static DefaultMutableTreeNode buildTree(EndpointInfo endpoint, List<CallChainNode> visible) {
        DefaultMutableTreeNode root = new DefaultMutableTreeNode(endpoint);
        Map<Integer, DefaultMutableTreeNode> byDepth = new HashMap<>();
        byDepth.put(0, root);
        for (CallChainNode node : visible) {
            if (node.depth() == 0) continue;
            DefaultMutableTreeNode treeNode = new DefaultMutableTreeNode(node);
            byDepth.getOrDefault(node.depth() - 1, root).add(treeNode);
            byDepth.put(node.depth(), treeNode);
        }
        return root;
    }

    // =========================================================================
    // Summary bar
    // =========================================================================

    private void updateSummaryBar(List<CallChainNode> nodes) {
        long reads  = nodes.stream().filter(n -> n.isRepository() && n.operationType() == OperationType.READ).count();
        long writes = nodes.stream().filter(n -> n.isRepository() && n.operationType() == OperationType.WRITE).count();
        long txs    = nodes.stream().filter(CallChainNode::isTransactional).count();

        Set<String> entities = new LinkedHashSet<>();
        nodes.stream()
             .filter(n -> n.isRepository() && !n.entityName().isEmpty())
             .map(CallChainNode::entityName)
             .forEach(entities::add);

        summaryBar.removeAll();
        summaryBar.add(buildStatPill(reads  + " READ",  UITheme.SUCCESS, UITheme.SUCCESS_SUB));
        summaryBar.add(Box.createHorizontalStrut(6));
        summaryBar.add(buildStatPill(writes + " WRITE", UITheme.WARNING, UITheme.WARNING_SUB));
        summaryBar.add(Box.createHorizontalStrut(6));
        summaryBar.add(buildStatPill(txs    + " @Tx",   UITheme.INDIGO,  UITheme.INDIGO_SUB));
        summaryBar.add(Box.createHorizontalGlue());

        if (!entities.isEmpty()) {
            JLabel entitiesLabel = new JLabel("Entities");
            entitiesLabel.setFont(UITheme.UI_SM);
            entitiesLabel.setForeground(UITheme.MUTED);
            summaryBar.add(entitiesLabel);
            summaryBar.add(Box.createHorizontalStrut(4));
            for (String entity : entities) {
                summaryBar.add(buildEntityPill(entity));
                summaryBar.add(Box.createHorizontalStrut(3));
            }
        }

        summaryBar.revalidate();
        summaryBar.repaint();
    }

    private static JLabel buildStatPill(String text, Color fg, Color bg) {
        JLabel label = new JLabel(text) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(getBackground());
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 6, 6);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        label.setFont(UITheme.UI_SM.deriveFont(Font.BOLD));
        label.setForeground(fg);
        label.setBackground(bg);
        label.setOpaque(false);
        label.setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 8));
        return label;
    }

    private static JLabel buildEntityPill(String text) {
        JLabel label = new JLabel(text) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(getBackground());
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        label.setFont(UITheme.MONO_XS.deriveFont(10f));
        label.setForeground(UITheme.ACCENT);
        label.setBackground(UITheme.ACCENT_SUB);
        label.setOpaque(false);
        label.setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 8));
        return label;
    }

    // =========================================================================
    // Tree expand / collapse
    // =========================================================================

    private void expandAll() {
        for (int i = 0; i < callChainTree.getRowCount(); i++) callChainTree.expandRow(i);
    }

    private void collapseAll() {
        for (int i = callChainTree.getRowCount() - 1; i >= 1; i--) callChainTree.collapseRow(i);
    }

    // =========================================================================
    // Clear cache
    // =========================================================================

    private void clearCache() {
        EndpointInfo selected = (EndpointInfo) endpointCombo.getSelectedItem();
        if (selected == null) { setStatus("No endpoint selected \u2014 nothing to clear.", UITheme.WARNING); return; }
        project.getService(CallChainService.class).clearCache();
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
                PsiClass cls = facade.findClass(qualifiedClassName, GlobalSearchScope.projectScope(project));
                if (cls == null) return;
                ParameterExtractionService svc =
                        ApplicationManager.getApplication().getService(ParameterExtractionService.class);
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
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
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
