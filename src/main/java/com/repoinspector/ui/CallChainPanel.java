package com.repoinspector.ui;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import com.repoinspector.analysis.api.CallChainService;
import com.repoinspector.analysis.api.EndpointAnalysisService;
import com.repoinspector.model.CallChainNode;
import com.repoinspector.model.EndpointInfo;
import com.repoinspector.model.OperationType;
import com.repoinspector.runner.service.api.ParameterExtractionService;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.Timer;
import javax.swing.event.AncestorEvent;
import javax.swing.event.AncestorListener;
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
 * Tool-window panel for the "Call Chain Tracer" tab — premium dashboard design.
 */
public class CallChainPanel extends JPanel implements Disposable {

    // ── Palette re-used from RepoInspectorPanel ───────────────────────────────
    private static final JBColor INDIGO      = RepoInspectorPanel.INDIGO;
    private static final JBColor INDIGO_PALE = RepoInspectorPanel.INDIGO_PALE;
    private static final JBColor TEAL        = RepoInspectorPanel.TEAL;
    private static final JBColor TEAL_PALE   = RepoInspectorPanel.TEAL_PALE;
    private static final JBColor AMBER       = RepoInspectorPanel.AMBER;
    private static final JBColor AMBER_PALE  = RepoInspectorPanel.AMBER_PALE;
    private static final JBColor VIOLET      = new JBColor(new Color(0x7C3AED), new Color(0x8B5CF6));
    private static final JBColor VIOLET_PALE = new JBColor(new Color(0xF5F3FF), new Color(0x2E1065));
    private static final JBColor ROSE        = RepoInspectorPanel.ROSE;
    private static final JBColor ROSE_PALE   = RepoInspectorPanel.ROSE_PALE;

    private static final Font FONT_TITLE = new Font(Font.SANS_SERIF, Font.BOLD,  18);
    private static final Font FONT_HERO  = new Font(Font.SANS_SERIF, Font.BOLD,  24);

    // ── State ─────────────────────────────────────────────────────────────────
    private final Project project;

    private final JComboBox<EndpointInfo> endpointCombo;
    private final JTextField              endpointSearchField;
    private final JTree                   callChainTree;
    private final DefaultTreeModel        treeModel;
    private final JLabel                  statusLabel;
    private final JPanel                  summaryBar;
    private final JToggleButton           reposOnlyToggle;

    private AnimatedNumber endpointCounter;
    private AnimatedNumber repoCounter;
    private AnimatedNumber txCounter;
    private PulsingDot     liveDot;

    private List<EndpointInfo>  allEndpoints = List.of();
    private List<CallChainNode> lastResult   = List.of();
    @Nullable private EndpointInfo lastEndpoint = null;

    private final List<Timer> managedTimers = new ArrayList<>();

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
    // Constructor
    // =========================================================================

    public CallChainPanel(@NotNull Project project) {
        this.project = project;
        setLayout(new BorderLayout());

        // ── Endpoint search + combo ───────────────────────────────────────────
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

        // ── Trace button ──────────────────────────────────────────────────────
        JButton traceButton = new JButton("Trace") {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setPaint(new GradientPaint(0, 0, INDIGO, getWidth(), getHeight(),
                        new JBColor(new Color(0x7C3AED), new Color(0x8B5CF6))));
                g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, JBUI.scale(6), JBUI.scale(6));
                g2.dispose();
                super.paintComponent(g);
            }
        };
        traceButton.setFont(UITheme.UI.deriveFont(Font.BOLD, 12f));
        traceButton.setForeground(Color.WHITE);
        traceButton.setOpaque(false);
        traceButton.setContentAreaFilled(false);
        traceButton.setBorderPainted(false);
        traceButton.setFocusPainted(false);
        traceButton.setBorder(JBUI.Borders.empty(4, 12));
        traceButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        JButton refreshBtn = UITheme.iconButton("\u21BB");
        JButton copyBtn    = UITheme.iconButton("\u2398");
        refreshBtn.setToolTipText("Refresh endpoints");
        copyBtn.setToolTipText("Copy as text");

        traceButton.addActionListener(e -> runTrace());
        refreshBtn.addActionListener(e -> loadEndpoints());
        copyBtn.addActionListener(e    -> copyAsText());

        // ── Search wrapper ────────────────────────────────────────────────────
        JPanel searchWrapper = new JPanel(new BorderLayout(JBUI.scale(4), 0));
        searchWrapper.setOpaque(false);
        searchWrapper.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(UITheme.BORDER_SUB, 1),
                JBUI.Borders.empty(2, 6)));
        JLabel filterIcon = new JLabel("\u25BC");
        filterIcon.setFont(UITheme.UI_SM);
        filterIcon.setForeground(UITheme.MUTED);
        searchWrapper.add(filterIcon,           BorderLayout.WEST);
        searchWrapper.add(endpointSearchField,  BorderLayout.CENTER);

        // ── Primary toolbar row ───────────────────────────────────────────────
        JPanel primaryRow = new JPanel();
        primaryRow.setLayout(new BoxLayout(primaryRow, BoxLayout.X_AXIS));
        primaryRow.setOpaque(false);
        primaryRow.setBorder(JBUI.Borders.empty(5, 8, 4, 8));
        primaryRow.add(searchWrapper);
        primaryRow.add(Box.createHorizontalStrut(JBUI.scale(6)));
        primaryRow.add(endpointCombo);
        primaryRow.add(Box.createHorizontalStrut(JBUI.scale(6)));
        primaryRow.add(traceButton);
        primaryRow.add(Box.createHorizontalGlue());
        primaryRow.add(refreshBtn);
        primaryRow.add(Box.createHorizontalStrut(JBUI.scale(2)));
        primaryRow.add(copyBtn);

        // ── Secondary toolbar row ─────────────────────────────────────────────
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
        secondaryRow.setBorder(JBUI.Borders.empty(2, 8, 4, 8));
        secondaryRow.add(expandCollapse);
        secondaryRow.add(Box.createHorizontalStrut(JBUI.scale(6)));
        secondaryRow.add(UITheme.toolbarDivider());
        secondaryRow.add(Box.createHorizontalStrut(JBUI.scale(6)));
        secondaryRow.add(reposOnlyToggle);
        secondaryRow.add(Box.createHorizontalGlue());
        secondaryRow.add(clearCacheBtn);

        // ── Status label ──────────────────────────────────────────────────────
        statusLabel = new JLabel("  Select an endpoint and click Trace.");
        statusLabel.setFont(UITheme.UI_SM);
        statusLabel.setForeground(UITheme.MUTED);
        statusLabel.setBorder(JBUI.Borders.empty(0, 8, 4, 8));

        JPanel toolbarPanel = new JPanel(new BorderLayout());
        toolbarPanel.setBackground(UITheme.TOOLBAR);
        toolbarPanel.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, UITheme.BORDER_SUB));
        toolbarPanel.add(primaryRow,   BorderLayout.NORTH);
        toolbarPanel.add(secondaryRow, BorderLayout.CENTER);
        toolbarPanel.add(statusLabel,  BorderLayout.SOUTH);

        JPanel north = new JPanel(new BorderLayout());
        north.setOpaque(false);
        north.add(buildHeader(),  BorderLayout.NORTH);
        north.add(toolbarPanel,   BorderLayout.SOUTH);
        add(north, BorderLayout.NORTH);

        // ── Tree ──────────────────────────────────────────────────────────────
        DefaultMutableTreeNode root = new DefaultMutableTreeNode("No endpoint selected");
        treeModel     = new DefaultTreeModel(root);
        callChainTree = new JTree(treeModel);
        callChainTree.setCellRenderer(new CallChainTreeRenderer());
        callChainTree.setRootVisible(true);
        callChainTree.setShowsRootHandles(true);
        callChainTree.setRowHeight(0);
        callChainTree.setBackground(UITheme.PANEL);
        callChainTree.setBorder(JBUI.Borders.empty(8));
        callChainTree.putClientProperty("JTree.lineStyle", "None");
        callChainTree.setToggleClickCount(1);
        callChainTree.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) navigateToSelected();
            }
        });

        JBScrollPane treeScrollPane = new JBScrollPane(callChainTree);
        treeScrollPane.setBorder(BorderFactory.createEmptyBorder());
        treeScrollPane.getViewport().setBackground(UITheme.PANEL);
        add(treeScrollPane, BorderLayout.CENTER);

        // ── Summary bar ───────────────────────────────────────────────────────
        summaryBar = new JPanel();
        summaryBar.setLayout(new BoxLayout(summaryBar, BoxLayout.X_AXIS));
        summaryBar.setBackground(UITheme.TOOLBAR);
        summaryBar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, UITheme.BORDER_SUB),
                JBUI.Borders.empty(4, 10)));
        rebuildSummaryBar(0, 0, 0, List.of());
        add(summaryBar, BorderLayout.SOUTH);

        addAncestorListener(new AncestorListener() {
            boolean fired = false;
            @Override public void ancestorAdded(AncestorEvent e) {
                if (!fired) { fired = true; startEntranceAnimations(); }
            }
            @Override public void ancestorRemoved(AncestorEvent e) {}
            @Override public void ancestorMoved(AncestorEvent e) {}
        });

        SwingUtilities.invokeLater(this::loadEndpoints);
    }

    // =========================================================================
    // Header
    // =========================================================================

    private JPanel buildHeader() {
        GradientHeader header = new GradientHeader();
        header.setLayout(new BorderLayout(JBUI.scale(12), 0));
        header.setBorder(JBUI.Borders.empty(12, 14, 12, 14));

        JLabel icon  = new JLabel("\uD83D\uDD17");
        icon.setFont(icon.getFont().deriveFont(20f));
        JLabel title = new JLabel("Call Chain Tracer");
        title.setFont(FONT_TITLE);
        title.setForeground(new JBColor(new Color(0x1E1B4B), new Color(0xE0E7FF)));
        JLabel sub = new JLabel("Endpoint \u2192 Repository analysis");
        sub.setFont(UITheme.UI_SM);
        sub.setForeground(new JBColor(new Color(0x6366F1), new Color(0x818CF8)));
        JPanel titleCol = boxColumn(title, sub);

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0));
        left.setOpaque(false);
        left.add(icon);
        left.add(titleCol);

        endpointCounter = new AnimatedNumber(0);
        repoCounter     = new AnimatedNumber(0);
        txCounter       = new AnimatedNumber(0);
        JPanel stats = new JPanel(new FlowLayout(FlowLayout.CENTER, JBUI.scale(8), 0));
        stats.setOpaque(false);
        stats.add(miniStatCard(endpointCounter, "Endpoints", INDIGO, INDIGO_PALE));
        stats.add(miniStatCard(repoCounter,     "Repos Hit", TEAL,   TEAL_PALE));
        stats.add(miniStatCard(txCounter,       "@Tx Nodes", VIOLET, VIOLET_PALE));

        liveDot = new PulsingDot();
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        right.setOpaque(false);
        right.add(liveDot);

        header.add(left,  BorderLayout.WEST);
        header.add(stats, BorderLayout.CENTER);
        header.add(right, BorderLayout.EAST);
        return header;
    }

    private JPanel miniStatCard(AnimatedNumber counter, String label, JBColor accent, JBColor bg) {
        JPanel card = new JPanel(new BorderLayout(0, JBUI.scale(2))) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(bg);
                g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, JBUI.scale(10), JBUI.scale(10));
                g2.dispose();
                super.paintComponent(g);
            }
        };
        card.setOpaque(false);
        card.setBorder(JBUI.Borders.empty(8, 14, 8, 14));
        card.setPreferredSize(new Dimension(JBUI.scale(88), JBUI.scale(58)));
        counter.setFont(FONT_HERO.deriveFont(Font.BOLD, JBUI.scaleFontSize(22f)));
        counter.setForeground(accent);
        counter.setHorizontalAlignment(SwingConstants.CENTER);
        JLabel lbl = new JLabel(label);
        lbl.setFont(UITheme.UI_SM);
        lbl.setForeground(UITheme.MUTED);
        lbl.setHorizontalAlignment(SwingConstants.CENTER);
        card.add(counter, BorderLayout.CENTER);
        card.add(lbl,     BorderLayout.SOUTH);
        return card;
    }

    // =========================================================================
    // Entrance animation
    // =========================================================================

    private void startEntranceAnimations() {
        timedOnce(150, () -> liveDot.start());
        timedOnce(250, () -> endpointCounter.animateTo(allEndpoints.size()));
    }

    private void timedOnce(int ms, Runnable action) {
        Timer t = new Timer(ms, e -> action.run());
        t.setRepeats(false);
        t.start();
        managedTimers.add(t);
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
                    endpointCounter.animateTo(allEndpoints.size());
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

        setStatus("Analyzing call chain\u2026", INDIGO);
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

        long repos = nodes.stream().filter(CallChainNode::isRepository).count();
        long txs   = nodes.stream().filter(CallChainNode::isTransactional).count();
        String cacheNote = fromCache ? " (cached)" : "";
        setStatus("Found " + repos + " repository call(s) in chain." + cacheNote,
                repos > 0 ? UITheme.SUCCESS : UITheme.WARNING);

        repoCounter.animateTo((int) repos);
        txCounter.animateTo((int) txs);
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
        long reads   = nodes.stream().filter(n -> n.isRepository() && n.operationType() == OperationType.READ).count();
        long writes  = nodes.stream().filter(n -> n.isRepository() && n.operationType() == OperationType.WRITE).count();
        long txs     = nodes.stream().filter(CallChainNode::isTransactional).count();
        Set<String> entities = new LinkedHashSet<>();
        nodes.stream()
             .filter(n -> n.isRepository() && !n.entityName().isEmpty())
             .map(CallChainNode::entityName)
             .forEach(entities::add);
        rebuildSummaryBar(reads, writes, txs, entities);
    }

    private void rebuildSummaryBar(long reads, long writes, long txs, java.util.Collection<String> entities) {
        summaryBar.removeAll();
        summaryBar.add(statPill(reads  + " READ",  TEAL,   TEAL_PALE));
        summaryBar.add(Box.createHorizontalStrut(JBUI.scale(6)));
        summaryBar.add(statPill(writes + " WRITE", AMBER,  AMBER_PALE));
        summaryBar.add(Box.createHorizontalStrut(JBUI.scale(6)));
        summaryBar.add(statPill(txs    + " @Tx",   VIOLET, VIOLET_PALE));
        summaryBar.add(Box.createHorizontalGlue());

        if (!entities.isEmpty()) {
            JLabel lbl = new JLabel("Entities");
            lbl.setFont(UITheme.UI_SM);
            lbl.setForeground(UITheme.MUTED);
            summaryBar.add(lbl);
            summaryBar.add(Box.createHorizontalStrut(JBUI.scale(4)));
            for (String entity : entities) {
                summaryBar.add(entityPill(entity));
                summaryBar.add(Box.createHorizontalStrut(JBUI.scale(3)));
            }
        }
        summaryBar.revalidate();
        summaryBar.repaint();
    }

    private static JLabel statPill(String text, JBColor fg, JBColor bg) {
        JLabel label = new JLabel(text) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(getBackground());
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), JBUI.scale(6), JBUI.scale(6));
                g2.dispose();
                super.paintComponent(g);
            }
        };
        label.setFont(UITheme.UI_SM.deriveFont(Font.BOLD));
        label.setForeground(fg);
        label.setBackground(bg);
        label.setOpaque(false);
        label.setBorder(JBUI.Borders.empty(2, 8));
        return label;
    }

    private static JLabel entityPill(String text) {
        JLabel label = new JLabel(text) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(getBackground());
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), JBUI.scale(10), JBUI.scale(10));
                g2.dispose();
                super.paintComponent(g);
            }
        };
        label.setFont(UITheme.MONO_XS.deriveFont(10f));
        label.setForeground(INDIGO);
        label.setBackground(INDIGO_PALE);
        label.setOpaque(false);
        label.setBorder(JBUI.Borders.empty(2, 8));
        return label;
    }

    // =========================================================================
    // Tree expand / collapse / clear cache / navigate
    // =========================================================================

    private void expandAll() {
        for (int i = 0; i < callChainTree.getRowCount(); i++) callChainTree.expandRow(i);
    }

    private void collapseAll() {
        for (int i = callChainTree.getRowCount() - 1; i >= 1; i--) callChainTree.collapseRow(i);
    }

    private void clearCache() {
        EndpointInfo selected = (EndpointInfo) endpointCombo.getSelectedItem();
        if (selected == null) { setStatus("No endpoint selected \u2014 nothing to clear.", UITheme.WARNING); return; }
        project.getService(CallChainService.class).clearCache();
        setStatus("Cache cleared. Click Trace to re-analyze.", UITheme.SUCCESS);
    }

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
    // Helpers
    // =========================================================================

    private void setStatus(String text, Color color) {
        statusLabel.setText("  " + text);
        statusLabel.setForeground(color);
    }

    private static JPanel boxColumn(JComponent... items) {
        JPanel p = new JPanel();
        p.setOpaque(false);
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        for (JComponent c : items) p.add(c);
        return p;
    }

    @Override
    public void dispose() {
        managedTimers.forEach(Timer::stop);
        managedTimers.clear();
        if (liveDot != null) liveDot.stop();
    }

    // =========================================================================
    // Inner: GradientHeader
    // =========================================================================

    private static final class GradientHeader extends JPanel {
        GradientHeader() { setOpaque(false); }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color c1 = new JBColor(new Color(0xEEF2FF), new Color(0x1E1B4B));
            Color c2 = new JBColor(new Color(0xF5F3FF), new Color(0x1C1333));
            g2.setPaint(new GradientPaint(0, 0, c1, getWidth(), getHeight(), c2));
            g2.fillRect(0, 0, getWidth(), getHeight());
            g2.setColor(new JBColor(new Color(0xC7D2FE), new Color(0x312E81)));
            g2.drawLine(0, getHeight() - 1, getWidth(), getHeight() - 1);
            g2.dispose();
            super.paintComponent(g);
        }
    }

    // =========================================================================
    // Inner: AnimatedNumber
    // =========================================================================

    private static final class AnimatedNumber extends JLabel {
        private int   current = 0;
        private Timer countTimer;

        AnimatedNumber(int initial) { super(String.valueOf(initial)); }

        void animateTo(int target) {
            if (countTimer != null) countTimer.stop();
            int   start     = current;
            long  startTime = System.currentTimeMillis();
            long  duration  = 800L;
            countTimer = new Timer(16, e -> {
                double t     = Math.min(1.0, (System.currentTimeMillis() - startTime) / (double) duration);
                double eased = 1.0 - Math.pow(1.0 - t, 4);
                current = (int) Math.round(start + (target - start) * eased);
                setText(String.valueOf(current));
                if (t >= 1.0) { countTimer.stop(); current = target; setText(String.valueOf(target)); }
            });
            countTimer.start();
        }
    }

    // =========================================================================
    // Inner: PulsingDot — live indicator ring
    // =========================================================================

    private static final class PulsingDot extends JComponent {
        private Timer pulseTimer;
        private double phase = 0.0;

        PulsingDot() {
            setOpaque(false);
            setPreferredSize(new Dimension(JBUI.scale(44), JBUI.scale(44)));
            setToolTipText("Analysis engine ready");
        }

        void start() {
            if (pulseTimer != null && pulseTimer.isRunning()) return;
            pulseTimer = new Timer(50, e -> { phase += 0.12; repaint(); });
            pulseTimer.start();
        }

        void stop() { if (pulseTimer != null) pulseTimer.stop(); }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int cx = getWidth()  / 2;
            int cy = getHeight() / 2;
            int r  = JBUI.scale(5);
            double pulse = Math.sin(phase);
            int outerR = r + (int)(r * 0.8 * (pulse * 0.5 + 0.5));
            int alpha  = (int)(80 * (1.0 - (pulse * 0.5 + 0.5)));
            g2.setColor(new JBColor(
                    new Color(TEAL.getRed(), TEAL.getGreen(), TEAL.getBlue(), alpha),
                    new Color(TEAL.getRed(), TEAL.getGreen(), TEAL.getBlue(), alpha)));
            g2.fillOval(cx - outerR, cy - outerR, outerR * 2, outerR * 2);
            g2.setColor(TEAL);
            g2.fillOval(cx - r, cy - r, r * 2, r * 2);
            g2.dispose();
        }
    }
}
