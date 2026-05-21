package com.repoinspector.ui;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import com.repoinspector.inspections.scan.RepoBuddyIssueListener;
import com.repoinspector.inspections.scan.RepoBuddyIssueService;
import com.repoinspector.inspections.scan.RepoBuddyInspectionScanner.Finding;

import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Arrays;
import java.util.List;

/**
 * "Issues" tab — runs the RepoBuddy inspections (via the shared {@link RepoBuddyIssueService}) and
 * shows their findings in a sortable, searchable table. Styled to match the Repository Usage and
 * Call Chain Tracer tabs: gradient header with mini stat cards, per-inspection colour badges, and a
 * coloured summary bar — all using the shared {@link RepoInspectorPanel} palette.
 */
public class RepoIssuesPanel extends JPanel implements Disposable {

    private static final String[] COLUMNS = {"Inspection", "File", "Line", "Message"};
    private static final int COL_INSPECTION = 0;
    private static final int COL_FILE       = 1;
    private static final int COL_LINE       = 2;
    private static final int COL_MESSAGE    = 3;

    private static final Font FONT_TITLE = new Font(Font.SANS_SERIF, Font.BOLD, 18);
    private static final Font FONT_HERO  = new Font(Font.SANS_SERIF, Font.BOLD, 22);

    // ── Shared palette (re-used from RepoInspectorPanel, same as CallChainPanel) ──
    private static final JBColor INDIGO      = RepoInspectorPanel.INDIGO;
    private static final JBColor INDIGO_PALE = RepoInspectorPanel.INDIGO_PALE;
    private static final JBColor TEAL        = RepoInspectorPanel.TEAL;
    private static final JBColor TEAL_PALE   = RepoInspectorPanel.TEAL_PALE;
    private static final JBColor AMBER       = RepoInspectorPanel.AMBER;
    private static final JBColor AMBER_PALE  = RepoInspectorPanel.AMBER_PALE;
    private static final JBColor ROSE        = RepoInspectorPanel.ROSE;
    private static final JBColor ROSE_PALE   = RepoInspectorPanel.ROSE_PALE;
    private static final JBColor VIOLET      = new JBColor(new Color(0x7C3AED), new Color(0x8B5CF6));
    private static final JBColor VIOLET_PALE = new JBColor(new Color(0xF5F3FF), new Color(0x2E1065));

    /** Fixed display order for the summary pills. */
    private static final List<String> INSPECTION_ORDER = List.of(
            "Unsafe @Query", "Missing pagination", "Missing @Transactional",
            "N+1 query", "@Transactional self-invocation");

    private final Project project;

    private final DefaultTableModel tableModel;
    private final JBTable table;
    private final TableRowSorter<DefaultTableModel> rowSorter;
    private final JTextField searchField;
    private final JToggleButton currentFileToggle;
    private final JLabel statusLabel;

    private final JLabel totalValue = heroLabel(INDIGO);
    private final JLabel filesValue = heroLabel(TEAL);
    private final JPanel summaryPills = new JPanel();

    private List<Finding> findings = List.of();

    public RepoIssuesPanel(Project project) {
        super(new BorderLayout());
        this.project = project;

        searchField = new JTextField(22);
        searchField.putClientProperty("JTextField.placeholderText", "Search inspection, file or message…");
        searchField.setFont(UITheme.UI);
        searchField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e)  { applyFilter(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e)  { applyFilter(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { applyFilter(); }
        });

        currentFileToggle = UITheme.toggleButton("Current file");
        currentFileToggle.setToolTipText("Scan only the file open in the editor");
        currentFileToggle.addItemListener(e -> runScan());

        JButton refreshBtn = UITheme.iconButton("↻");
        refreshBtn.setToolTipText("Re-run RepoBuddy inspections");
        refreshBtn.addActionListener(e -> runScan());

        JButton exportBtn = UITheme.iconButton("↧");
        exportBtn.setToolTipText("Copy findings as CSV");
        exportBtn.addActionListener(e -> exportCsv());

        tableModel = new DefaultTableModel(COLUMNS, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
            @Override public Class<?> getColumnClass(int c) {
                return c == COL_LINE ? Integer.class : String.class;
            }
        };
        table = new JBTable(tableModel);
        rowSorter = new TableRowSorter<>(tableModel);
        table.setRowSorter(rowSorter);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setRowHeight(JBUI.scale(30));
        table.setShowGrid(false);
        table.setIntercellSpacing(new Dimension(0, 0));
        table.setBackground(UITheme.PANEL);
        table.setFont(UITheme.UI);
        table.getTableHeader().setFont(UITheme.UI.deriveFont(Font.BOLD));
        table.getTableHeader().setBackground(UITheme.TOOLBAR);
        table.getTableHeader().setForeground(UITheme.MUTED);
        table.getColumnModel().getColumn(COL_INSPECTION).setPreferredWidth(JBUI.scale(190));
        table.getColumnModel().getColumn(COL_FILE).setPreferredWidth(JBUI.scale(160));
        table.getColumnModel().getColumn(COL_LINE).setPreferredWidth(JBUI.scale(52));
        table.getColumnModel().getColumn(COL_MESSAGE).setPreferredWidth(JBUI.scale(520));
        table.getColumnModel().getColumn(COL_INSPECTION).setCellRenderer(new InspectionBadgeRenderer());
        table.getColumnModel().getColumn(COL_FILE).setCellRenderer(new FileBadgeRenderer());
        table.getColumnModel().getColumn(COL_LINE).setCellRenderer(new LinePillRenderer());
        table.getColumnModel().getColumn(COL_MESSAGE).setCellRenderer(new MessageRenderer());
        table.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) { if (e.getClickCount() == 2) navigateToSelected(); }
        });

        statusLabel = new JLabel("Ready");
        statusLabel.setFont(UITheme.UI_SM);
        statusLabel.setForeground(UITheme.MUTED);

        add(buildHeader(), BorderLayout.NORTH);
        add(buildCenter(refreshBtn, exportBtn), BorderLayout.CENTER);
        add(buildSummaryBar(), BorderLayout.SOUTH);

        project.getMessageBus().connect(this)
                .subscribe(RepoBuddyIssueListener.TOPIC, (RepoBuddyIssueListener) this::reloadFromService);

        SwingUtilities.invokeLater(this::runScan);
    }

    // =========================================================================
    // Layout builders
    // =========================================================================

    private JComponent buildHeader() {
        GradientHeader header = new GradientHeader();
        header.setLayout(new BorderLayout(JBUI.scale(12), 0));
        header.setBorder(JBUI.Borders.empty(12, 14, 12, 14));

        JLabel icon = new JLabel("⚠");
        icon.setFont(icon.getFont().deriveFont(20f));
        JLabel title = new JLabel("Issues");
        title.setFont(FONT_TITLE);
        title.setForeground(new JBColor(new Color(0x1E1B4B), new Color(0xE0E7FF)));
        JLabel sub = new JLabel("RepoBuddy inspection findings");
        sub.setFont(UITheme.UI_SM);
        sub.setForeground(new JBColor(new Color(0x6366F1), new Color(0x818CF8)));

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0));
        left.setOpaque(false);
        left.add(icon);
        left.add(boxColumn(title, sub));

        JPanel stats = new JPanel(new FlowLayout(FlowLayout.CENTER, JBUI.scale(8), 0));
        stats.setOpaque(false);
        stats.add(statCard(totalValue, "Issues", INDIGO, INDIGO_PALE));
        stats.add(statCard(filesValue, "Files",  TEAL,   TEAL_PALE));

        header.add(left,  BorderLayout.WEST);
        header.add(stats, BorderLayout.CENTER);
        return header;
    }

    private JPanel buildCenter(JButton refreshBtn, JButton exportBtn) {
        JPanel searchWrap = new JPanel(new BorderLayout(JBUI.scale(4), 0));
        searchWrap.setOpaque(false);
        searchWrap.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(UITheme.BORDER_SUB, 1),
                JBUI.Borders.empty(2, 6)));
        JLabel searchIcon = new JLabel("🔍");
        searchIcon.setFont(UITheme.UI_SM);
        searchIcon.setForeground(UITheme.MUTED);
        searchWrap.add(searchIcon,  BorderLayout.WEST);
        searchWrap.add(searchField, BorderLayout.CENTER);

        JPanel toolbar = new JPanel();
        toolbar.setLayout(new BoxLayout(toolbar, BoxLayout.X_AXIS));
        toolbar.setBackground(UITheme.TOOLBAR);
        toolbar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, UITheme.BORDER_SUB),
                JBUI.Borders.empty(5, 8)));
        toolbar.add(searchWrap);
        toolbar.add(Box.createHorizontalStrut(JBUI.scale(6)));
        toolbar.add(UITheme.toolbarDivider());
        toolbar.add(Box.createHorizontalStrut(JBUI.scale(6)));
        toolbar.add(currentFileToggle);
        toolbar.add(Box.createHorizontalGlue());
        toolbar.add(exportBtn);
        toolbar.add(Box.createHorizontalStrut(JBUI.scale(2)));
        toolbar.add(refreshBtn);

        JBScrollPane scroll = new JBScrollPane(table);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setBackground(UITheme.PANEL);

        JPanel center = new JPanel(new BorderLayout());
        center.add(toolbar, BorderLayout.NORTH);
        center.add(scroll,  BorderLayout.CENTER);
        return center;
    }

    private JPanel buildSummaryBar() {
        summaryPills.setLayout(new BoxLayout(summaryPills, BoxLayout.X_AXIS));
        summaryPills.setOpaque(false);

        JPanel bar = new JPanel(new BorderLayout());
        bar.setBackground(UITheme.TOOLBAR);
        bar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, UITheme.BORDER_SUB),
                JBUI.Borders.empty(4, 10)));
        bar.add(summaryPills, BorderLayout.WEST);
        bar.add(statusLabel,  BorderLayout.EAST);
        rebuildSummary();
        return bar;
    }

    // =========================================================================
    // Scan
    // =========================================================================

    /** Triggers an async refresh in the shared service; the table reloads via the topic callback. */
    private void runScan() {
        statusLabel.setText("Scanning…");
        RepoBuddyIssueService service = RepoBuddyIssueService.getInstance(project);
        if (currentFileToggle.isSelected()) {
            VirtualFile currentFile = service.selectedFile();
            if (currentFile == null) {
                findings = List.of();
                updateTable();
                statusLabel.setText("No file open in the editor");
                return;
            }
            service.refreshFile(currentFile);
        } else {
            service.refreshProject();
        }
    }

    /** Reloads the table from the shared cache (runs on the EDT when findings change). */
    private void reloadFromService() {
        RepoBuddyIssueService service = RepoBuddyIssueService.getInstance(project);
        findings = currentFileToggle.isSelected()
                ? service.findingsForFile(service.selectedFile())
                : service.findings();
        updateTable();
    }

    private void updateTable() {
        tableModel.setRowCount(0);
        for (Finding f : findings) {
            tableModel.addRow(new Object[]{f.inspection(), f.fileName(), f.line(), f.message()});
        }
        applyFilter();
        rebuildSummary();
        updateStatus();
    }

    // =========================================================================
    // Filter / status / summary
    // =========================================================================

    private void applyFilter() {
        String text = searchField.getText().trim();
        if (text.isEmpty()) {
            rowSorter.setRowFilter(null);
        } else {
            String esc = java.util.regex.Pattern.quote(text);
            rowSorter.setRowFilter(RowFilter.orFilter(Arrays.asList(
                    RowFilter.regexFilter("(?i)" + esc, COL_INSPECTION),
                    RowFilter.regexFilter("(?i)" + esc, COL_FILE),
                    RowFilter.regexFilter("(?i)" + esc, COL_MESSAGE))));
        }
        updateStatus();
    }

    private void updateStatus() {
        int total = findings.size();
        int visible = table.getRowCount();
        long files = findings.stream().map(Finding::filePath).distinct().count();
        totalValue.setText(String.valueOf(total));
        filesValue.setText(String.valueOf(files));

        String scope = currentFileToggle.isSelected() ? "current file" : "project";
        String base = total + (total == 1 ? " issue" : " issues") + " · " + scope;
        statusLabel.setText(visible < total ? base + " · showing " + visible : base);
    }

    private void rebuildSummary() {
        summaryPills.removeAll();
        int total = findings.size();
        summaryPills.add(statPill((total == 1 ? "1 issue" : total + " issues"), UITheme.INK, UITheme.PANEL_2));
        for (String inspection : INSPECTION_ORDER) {
            long count = findings.stream().filter(f -> f.inspection().equals(inspection)).count();
            if (count == 0) continue;
            Color[] colors = colorsFor(inspection);
            summaryPills.add(Box.createHorizontalStrut(JBUI.scale(6)));
            JComponent pill = statPill(shortLabel(inspection) + "  " + count, colors[0], colors[1]);
            pill.setToolTipText(inspection + ": " + count);
            summaryPills.add(pill);
        }
        summaryPills.revalidate();
        summaryPills.repaint();
    }

    // =========================================================================
    // Actions
    // =========================================================================

    private void navigateToSelected() {
        int viewRow = table.getSelectedRow();
        if (viewRow < 0) return;
        int modelRow = table.convertRowIndexToModel(viewRow);
        if (modelRow < 0 || modelRow >= findings.size()) return;
        Finding f = findings.get(modelRow);
        if (f.file() != null && f.file().isValid()) {
            new OpenFileDescriptor(project, f.file(), f.offset()).navigate(true);
        }
    }

    private void exportCsv() {
        if (findings.isEmpty()) return;
        StringBuilder sb = new StringBuilder("Inspection,File,Line,Message\n");
        for (Finding f : findings) {
            sb.append(esc(f.inspection())).append(',')
              .append(esc(f.fileName())).append(',')
              .append(f.line()).append(',')
              .append(esc(f.message())).append('\n');
        }
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(sb.toString()), null);
        statusLabel.setText("Copied " + findings.size() + " rows to clipboard");
    }

    private static String esc(String v) {
        if (v == null) return "";
        return (v.contains(",") || v.contains("\"") || v.contains("\n"))
                ? "\"" + v.replace("\"", "\"\"") + "\"" : v;
    }

    @Override
    public void dispose() {
        findings = List.of();
    }

    // =========================================================================
    // Colour mapping
    // =========================================================================

    /** {@code [foreground, paleBackground]} for an inspection label. */
    private static Color[] colorsFor(String inspection) {
        return switch (inspection) {
            case "Unsafe @Query"                   -> new Color[]{ROSE,   ROSE_PALE};
            case "Missing pagination"              -> new Color[]{AMBER,  AMBER_PALE};
            case "Missing @Transactional"          -> new Color[]{VIOLET, VIOLET_PALE};
            case "N+1 query"                       -> new Color[]{INDIGO, INDIGO_PALE};
            case "@Transactional self-invocation"  -> new Color[]{TEAL,   TEAL_PALE};
            default                                -> new Color[]{UITheme.INK_DIM, UITheme.PANEL_2};
        };
    }

    private static String shortLabel(String inspection) {
        return switch (inspection) {
            case "Unsafe @Query"                   -> "@Query";
            case "Missing pagination"              -> "Pagination";
            case "Missing @Transactional"          -> "@Tx";
            case "N+1 query"                       -> "N+1";
            case "@Transactional self-invocation"  -> "Self-inv";
            default                                -> inspection;
        };
    }

    // =========================================================================
    // Small UI helpers
    // =========================================================================

    private static JLabel heroLabel(JBColor accent) {
        JLabel l = new JLabel("0");
        l.setFont(FONT_HERO.deriveFont(Font.BOLD, JBUI.scaleFontSize(22f)));
        l.setForeground(accent);
        l.setHorizontalAlignment(SwingConstants.CENTER);
        return l;
    }

    private static JPanel statCard(JLabel value, String label, JBColor accent, JBColor bg) {
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
        card.setBorder(JBUI.Borders.empty(6, 16));
        card.setPreferredSize(new Dimension(JBUI.scale(90), JBUI.scale(54)));
        JLabel lbl = new JLabel(label);
        lbl.setFont(UITheme.UI_SM);
        lbl.setForeground(UITheme.MUTED);
        lbl.setHorizontalAlignment(SwingConstants.CENTER);
        card.add(value, BorderLayout.CENTER);
        card.add(lbl,   BorderLayout.SOUTH);
        return card;
    }

    private static JComponent statPill(String text, Color fg, Color bg) {
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

    private static JPanel boxColumn(JComponent... items) {
        JPanel p = new JPanel();
        p.setOpaque(false);
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        for (JComponent c : items) p.add(c);
        return p;
    }

    private static String clip(FontMetrics fm, String value, int maxWidth) {
        if (value == null || value.isEmpty()) return "";
        if (fm.stringWidth(value) <= maxWidth) return value;
        String ellipsis = "…";
        int allowed = Math.max(0, maxWidth - fm.stringWidth(ellipsis));
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            if (fm.stringWidth(out.toString() + value.charAt(i)) > allowed) break;
            out.append(value.charAt(i));
        }
        return out.append(ellipsis).toString();
    }

    // =========================================================================
    // Header gradient (matches RepoInspectorPanel / CallChainPanel)
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
    // Cell renderers
    // =========================================================================

    /** Inspection name as a colour-coded rounded badge, one colour per inspection type. */
    private static final class InspectionBadgeRenderer extends DefaultTableCellRenderer {
        @Override public Component getTableCellRendererComponent(
                JTable t, Object v, boolean sel, boolean focus, int r, int c) {
            super.getTableCellRendererComponent(t, v, sel, focus, r, c);
            setText("");
            return new BadgeCell(v == null ? "" : String.valueOf(v), sel, true);
        }
    }

    /** File name as a muted rounded badge. */
    private static final class FileBadgeRenderer extends DefaultTableCellRenderer {
        @Override public Component getTableCellRendererComponent(
                JTable t, Object v, boolean sel, boolean focus, int r, int c) {
            super.getTableCellRendererComponent(t, v, sel, focus, r, c);
            setText("");
            return new BadgeCell(v == null ? "" : String.valueOf(v), sel, false);
        }
    }

    private static final class BadgeCell extends JComponent {
        private final String  text;
        private final boolean sel;
        private final boolean colored;

        BadgeCell(String text, boolean sel, boolean colored) {
            this.text = text;
            this.sel = sel;
            this.colored = colored;
            setOpaque(false);
        }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            Color[] colors = colored ? colorsFor(text) : new Color[]{UITheme.INK_DIM, UITheme.PANEL_2};
            Color bg = sel ? UITheme.SELECTION : colors[1];
            Color fg = sel ? Color.WHITE       : colors[0];

            g2.setFont(colored ? UITheme.UI_BOLD.deriveFont(11f) : UITheme.UI_SM.deriveFont(11f));
            FontMetrics fm = g2.getFontMetrics();
            int leftPad = JBUI.scale(10);
            String display = clip(fm, text, Math.max(JBUI.scale(40), getWidth() - leftPad * 2 - JBUI.scale(6)));
            int h = JBUI.scale(20);
            int w = Math.min(getWidth() - JBUI.scale(8), fm.stringWidth(display) + leftPad * 2);
            int x = JBUI.scale(4);
            int y = (getHeight() - h) / 2;

            g2.setColor(bg);
            g2.fillRoundRect(x, y, w, h, JBUI.scale(14), JBUI.scale(14));
            g2.setColor(fg);
            g2.drawString(display, x + leftPad, y + (h + fm.getAscent() - fm.getDescent()) / 2);
            g2.dispose();
        }
    }

    /** Line number as a small centred muted pill. */
    private static final class LinePillRenderer extends DefaultTableCellRenderer {
        @Override public Component getTableCellRendererComponent(
                JTable t, Object v, boolean sel, boolean focus, int r, int c) {
            super.getTableCellRendererComponent(t, v, sel, focus, r, c);
            setText("");
            return new LinePill(v == null ? "" : String.valueOf(v), sel);
        }
    }

    private static final class LinePill extends JComponent {
        private final String  text;
        private final boolean sel;
        LinePill(String text, boolean sel) { this.text = text; this.sel = sel; setOpaque(false); }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setFont(UITheme.MONO_XS.deriveFont(Font.BOLD));
            FontMetrics fm = g2.getFontMetrics();
            int pw = fm.stringWidth(text) + JBUI.scale(12);
            int ph = JBUI.scale(18);
            int x = (getWidth() - pw) / 2;
            int y = (getHeight() - ph) / 2;
            g2.setColor(sel ? UITheme.SELECTION : UITheme.BORDER_SUB);
            g2.fillRoundRect(x, y, pw, ph, ph, ph);
            g2.setColor(sel ? Color.WHITE : UITheme.MUTED);
            g2.drawString(text, x + JBUI.scale(6), y + (ph + fm.getAscent() - fm.getDescent()) / 2);
            g2.dispose();
        }
    }

    /** Message as plain text with padding; selection-aware colours. */
    private static final class MessageRenderer extends DefaultTableCellRenderer {
        @Override public Component getTableCellRendererComponent(
                JTable t, Object v, boolean sel, boolean focus, int r, int c) {
            super.getTableCellRendererComponent(t, v, sel, focus, r, c);
            setFont(UITheme.UI);
            setBorder(JBUI.Borders.empty(0, 8));
            setOpaque(true);
            setBackground(sel ? UITheme.SELECTION : UITheme.PANEL);
            setForeground(sel ? Color.WHITE : UITheme.INK_2);
            return this;
        }
    }
}
