package com.repoinspector.ui;

import com.intellij.ui.JBColor;

import javax.swing.*;
import javax.swing.border.Border;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * Central UI palette for all RepoBuddy panels.
 *
 * <p>All colours are {@link JBColor} instances — properly differentiated for IntelliJ
 * Light and Darcula themes.  The palette mirrors the design-spec tokens defined in the
 * reference HTML/CSS spec (ij-light / ij-dark CSS variables).
 */
public final class UITheme {

    private UITheme() {}

    // ── Tool-window backgrounds ───────────────────────────────────────────────
    public static final Color BG         = jb(0xf7, 0xf8, 0xfa,  0x1e, 0x1f, 0x22);
    public static final Color PANEL      = jb(0xff, 0xff, 0xff,  0x2b, 0x2d, 0x30);
    public static final Color PANEL_2    = jb(0xf2, 0xf3, 0xf5,  0x26, 0x28, 0x2b);
    public static final Color TOOLBAR    = jb(0xeb, 0xec, 0xf0,  0x31, 0x34, 0x38);
    public static final Color HEADER_BG  = jb(0xe7, 0xe8, 0xeb,  0x2b, 0x2d, 0x30);

    // ── Borders ───────────────────────────────────────────────────────────────
    public static final Color BORDER     = jb(0xdf, 0xe1, 0xe5,  0x1e, 0x1f, 0x22);
    public static final Color BORDER_SUB = jb(0xeb, 0xec, 0xef,  0x39, 0x3b, 0x40);

    // ── Text ──────────────────────────────────────────────────────────────────
    public static final Color INK        = jb(0x1f, 0x23, 0x29,  0xdf, 0xe1, 0xe5);
    public static final Color INK_2      = jb(0x3c, 0x41, 0x48,  0xc2, 0xc5, 0xca);
    public static final Color INK_DIM    = jb(0x6c, 0x73, 0x7d,  0x9b, 0xa0, 0xa8);

    /** Alias kept for backward compatibility — maps to INK_DIM. */
    public static final Color MUTED = INK_DIM;

    // ── Semantic ──────────────────────────────────────────────────────────────
    public static final Color ACCENT      = jb(0x35, 0x74, 0xf0,  0x54, 0x8a, 0xf7);
    public static final Color ACCENT_SUB  = jb(0xe7, 0xf0, 0xff,  0x23, 0x35, 0x58);
    public static final Color SUCCESS     = jb(0x1f, 0x88, 0x3d,  0x5f, 0xb8, 0x65);
    public static final Color SUCCESS_SUB = jb(0xe6, 0xf4, 0xea,  0x1f, 0x3a, 0x22);
    public static final Color WARNING     = jb(0xb8, 0x7b, 0x00,  0xe3, 0xb3, 0x41);
    public static final Color WARNING_SUB = jb(0xfd, 0xf3, 0xdc,  0x3d, 0x2f, 0x0f);
    public static final Color DANGER      = jb(0xd0, 0x30, 0x30,  0xf3, 0x61, 0x61);
    public static final Color DANGER_SUB  = jb(0xfb, 0xe7, 0xe7,  0x3d, 0x1a, 0x1a);
    public static final Color GOLD        = jb(0xa4, 0x66, 0x00,  0xe8, 0xbf, 0x5a);
    public static final Color GOLD_SUB    = jb(0xfb, 0xef, 0xd6,  0x3a, 0x2e, 0x12);
    public static final Color INDIGO      = jb(0x5b, 0x57, 0xd6,  0x9b, 0x94, 0xf5);
    public static final Color INDIGO_SUB  = jb(0xec, 0xeb, 0xfb,  0x25, 0x23, 0x46);
    public static final Color SELECTION   = jb(0xd7, 0xe5, 0xfb,  0x2e, 0x43, 0x6e);
    public static final Color HOVER       = jb(0xf0, 0xf2, 0xf5,  0x39, 0x3b, 0x40);
    public static final Color FOCUS_RING  = ACCENT;

    // ── Code-panel specifics (terminal-dark in both themes) ───────────────────
    public static final Color SQL_BG      = jb(0x1e, 0x1f, 0x22,  0x1e, 0x1f, 0x22);
    public static final Color RESULT_BG   = jb(0x1c, 0x1e, 0x26,  0x1c, 0x1e, 0x26);
    public static final Color ERROR_BG    = jb(0x1c, 0x0a, 0x0a,  0x1c, 0x0a, 0x0a);
    public static final Color SQL_FG      = jb(0x98, 0xd7, 0x98,  0x98, 0xd7, 0x98);
    public static final Color SQL_KEYWORD = jb(0x56, 0x9c, 0xd6,  0x56, 0x9c, 0xd6);
    public static final Color SQL_DIM     = jb(0x55, 0x6e, 0x55,  0x55, 0x6e, 0x55);
    public static final Color RESULT_FG   = jb(0xc8, 0xdc, 0xff,  0xc8, 0xdc, 0xff);
    public static final Color ERROR_FG    = jb(0xf8, 0x64, 0x64,  0xf8, 0x64, 0x64);

    // ── Panel separator line colours (code panels) ────────────────────────────
    public static final Color SEPARATOR_ERROR  = new JBColor(new Color(80,  30, 30), new Color(100, 40, 40));
    public static final Color SEPARATOR_RESULT = new JBColor(new Color(40,  45, 75), new Color( 50, 55, 90));
    public static final Color SEPARATOR_SQL    = new JBColor(new Color(40,  55, 40), new Color( 50, 70, 50));

    // ── Parameter-type badge fg / bg ──────────────────────────────────────────
    public static final Color BADGE_TEXT       = ACCENT;
    public static final Color BADGE_TEXT_BG    = ACCENT_SUB;
    public static final Color BADGE_NUMBER     = SUCCESS;
    public static final Color BADGE_NUMBER_BG  = SUCCESS_SUB;
    public static final Color BADGE_BOOLEAN    = INDIGO;
    public static final Color BADGE_BOOLEAN_BG = INDIGO_SUB;
    public static final Color BADGE_JSON       = WARNING;
    public static final Color BADGE_JSON_BG    = WARNING_SUB;
    public static final Color BADGE_DECIMAL    = GOLD;
    public static final Color BADGE_DECIMAL_BG = GOLD_SUB;

    // ── Call-count badge colours (kept for compatibility) ─────────────────────
    public static final Color COUNT_ZERO = DANGER_SUB;
    public static final Color COUNT_LOW  = WARNING_SUB;
    public static final Color COUNT_HIGH = SUCCESS_SUB;

    // ── Run-button & header labels ────────────────────────────────────────────
    public static final Color RUN_BG     = SUCCESS;
    public static final Color RUN_FG     = Color.WHITE;
    public static final Color REPO_COLOR  = GOLD;
    public static final Color METHOD_COLOR = ACCENT;
    public static final Color ARROW_COLOR  = MUTED;

    // ── HTTP verb colours ─────────────────────────────────────────────────────
    public static final Color HTTP_GET    = SUCCESS;
    public static final Color HTTP_POST   = ACCENT;
    public static final Color HTTP_PUT    = WARNING;
    public static final Color HTTP_DELETE = DANGER;
    public static final Color HTTP_PATCH  = INDIGO;

    // ── Fonts ─────────────────────────────────────────────────────────────────
    public static final Font MONO    = new Font(Font.MONOSPACED, Font.PLAIN,  13);
    public static final Font MONO_SM = new Font(Font.MONOSPACED, Font.PLAIN,  12);
    public static final Font MONO_XS = new Font(Font.MONOSPACED, Font.PLAIN,  11);
    public static final Font UI      = new Font(Font.SANS_SERIF, Font.PLAIN,  12);
    public static final Font UI_SM   = new Font(Font.SANS_SERIF, Font.PLAIN,  11);
    public static final Font UI_BOLD = new Font(Font.SANS_SERIF, Font.BOLD,   12);

    // =========================================================================
    // Component factories
    // =========================================================================

    /** Standard labeled toolbar button (ij-btn style): 1-px border, hover background. */
    public static JButton button(String text) {
        JButton b = new JButton(text);
        b.setFont(UI);
        b.setFocusPainted(false);
        b.setOpaque(true);
        b.setBackground(PANEL);
        b.setForeground(INK);
        b.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER_SUB, 1),
                BorderFactory.createEmptyBorder(2, 8, 2, 8)));
        addHoverEffect(b, PANEL, HOVER);
        return b;
    }

    /** Toggle-state button. Selected state: accent-sub background + accent foreground. */
    public static JToggleButton toggleButton(String text) {
        Border normalBorder = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER_SUB, 1),
                BorderFactory.createEmptyBorder(2, 8, 2, 8));
        Border activeBorder = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(ACCENT, 1),
                BorderFactory.createEmptyBorder(2, 8, 2, 8));

        JToggleButton b = new JToggleButton(text);
        b.setFont(UI);
        b.setFocusPainted(false);
        b.setOpaque(true);
        b.setBackground(PANEL);
        b.setForeground(INK);
        b.setBorder(normalBorder);

        b.addItemListener(e -> {
            if (b.isSelected()) {
                b.setBackground(ACCENT_SUB);
                b.setForeground(ACCENT);
                b.setBorder(activeBorder);
            } else {
                b.setBackground(PANEL);
                b.setForeground(INK);
                b.setBorder(normalBorder);
            }
        });
        return b;
    }

    /** Small square icon-only button (26 × 26). Pass a unicode glyph as label. */
    public static JButton iconButton(String glyph) {
        JButton b = new JButton(glyph);
        b.setFont(b.getFont().deriveFont(13f));
        b.setPreferredSize(new Dimension(26, 26));
        b.setMinimumSize(new Dimension(26, 26));
        b.setMaximumSize(new Dimension(26, 26));
        b.setFocusPainted(false);
        b.setBorderPainted(false);
        b.setOpaque(true);
        b.setBackground(PANEL);
        b.setForeground(INK_2);
        addHoverEffect(b, PANEL, HOVER);
        return b;
    }

    /** Green "Run ▶" button. */
    public static JButton runButton() {
        JButton b = new JButton("  Run \u25B6  ");
        b.setFont(b.getFont().deriveFont(Font.BOLD, 12f));
        b.setBackground(RUN_BG);
        b.setForeground(RUN_FG);
        b.setOpaque(true);
        b.setBorderPainted(false);
        b.setFocusPainted(false);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return b;
    }

    /** Copy button with standard copy label. */
    public static JButton copyButton(String label) {
        JButton b = button(label);
        b.setFont(b.getFont().deriveFont(11f));
        b.setToolTipText("Copy to clipboard");
        return b;
    }

    /** 1-px vertical toolbar divider. */
    public static JSeparator toolbarDivider() {
        JSeparator sep = new JSeparator(SwingConstants.VERTICAL);
        sep.setPreferredSize(new Dimension(1, 16));
        sep.setMaximumSize(new Dimension(1, 16));
        sep.setForeground(BORDER_SUB);
        return sep;
    }

    /** Read-only monospace text area for code panels. */
    public static JTextArea codeArea(Color bg, Color fg) {
        JTextArea ta = new JTextArea();
        ta.setEditable(false);
        ta.setFont(MONO);
        ta.setBackground(bg);
        ta.setForeground(fg);
        ta.setCaretColor(fg);
        ta.setMargin(new Insets(10, 12, 10, 12));
        ta.setLineWrap(false);
        return ta;
    }

    /** Scroll pane whose viewport matches the text area background. */
    public static JScrollPane darkScrollPane(JTextArea area, Color bg) {
        JScrollPane sp = new JScrollPane(area);
        sp.getViewport().setBackground(bg);
        sp.setBorder(BorderFactory.createEmptyBorder());
        return sp;
    }

    /** Dark-panel header toolbar (used by SqlLogPanel, ResultPanel, ErrorPanel). */
    public static JPanel headerToolbar(Color bg, JLabel label, JButton rightButton, Color borderColor) {
        JPanel bar = new JPanel(new BorderLayout(6, 0));
        bar.setBackground(bg);
        bar.add(label, BorderLayout.CENTER);
        if (rightButton != null) bar.add(rightButton, BorderLayout.EAST);
        bar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, borderColor),
                BorderFactory.createEmptyBorder(5, 10, 5, 8)));
        return bar;
    }

    /** Header label with bold text for code panels. */
    public static JLabel headerLabel(String text, Color fg) {
        JLabel lbl = new JLabel(text);
        lbl.setFont(lbl.getFont().deriveFont(Font.BOLD, 12f));
        lbl.setForeground(fg);
        lbl.setOpaque(false);
        return lbl;
    }

    /** Execution-time colour: green < 100 ms, amber 100–499 ms, red ≥ 500 ms. */
    public static Color execTimeColor(long ms) {
        if (ms < 100) return SUCCESS;
        if (ms < 500) return WARNING;
        return DANGER;
    }

    /**
     * HTML header string: gold Repo · accent method (used in popup title).
     * Uses "." separator instead of the old arrow to match the AFTER design.
     */
    public static String popupHeaderHtml(String repoSimpleName, String methodName, String paramSummary) {
        String repoHex   = toHex(GOLD);
        String methodHex = toHex(ACCENT);
        String dotHex    = toHex(MUTED);
        String paramHex  = toHex(INK_DIM);
        return "<html><font color='" + repoHex + "'><b>" + repoSimpleName + "</b></font>"
                + "<font color='" + dotHex + "'>.</font>"
                + "<font color='" + methodHex + "'><b>" + methodName + "</b></font>"
                + "<font color='" + dotHex + "'>(</font>"
                + "<font color='" + paramHex + "'>" + paramSummary + "</font>"
                + "<font color='" + dotHex + "'>)</font></html>";
    }

    // =========================================================================
    // Custom painting helpers (used by table cell renderers)
    // =========================================================================

    /**
     * Paints a small rounded-rectangle badge centred in the given cell area.
     * Assumes the caller has already cleared the text on the label component.
     */
    public static void paintBadge(Graphics2D g2, String text, Color bg, Color fg,
                                   int cellX, int cellY, int cellW, int cellH) {
        g2 = (Graphics2D) g2.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        g2.setFont(MONO_XS.deriveFont(Font.BOLD));
        FontMetrics fm = g2.getFontMetrics();
        int tw  = fm.stringWidth(text);
        int th  = fm.getHeight();
        int bw  = tw + 12;
        int bh  = th;
        int bx  = cellX + (cellW - bw) / 2;
        int by  = cellY + (cellH - bh) / 2;

        g2.setColor(bg);
        g2.fillRoundRect(bx, by, bw, bh, 4, 4);
        g2.setColor(fg);
        g2.drawString(text, bx + 6, by + fm.getAscent());
        g2.dispose();
    }

    /**
     * Paints the calls-count pill (● N) right-aligned inside the cell.
     * Colours: zero→danger, low (1–2)→warning, high (3+)→success.
     */
    public static void paintCallsPill(Graphics2D g2, int calls,
                                       int cellX, int cellY, int cellW, int cellH) {
        Color bg = calls == 0 ? DANGER_SUB : calls <= 2 ? WARNING_SUB : SUCCESS_SUB;
        Color fg = calls == 0 ? DANGER     : calls <= 2 ? WARNING     : SUCCESS;

        g2 = (Graphics2D) g2.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        g2.setFont(MONO_XS.deriveFont(Font.BOLD));
        FontMetrics fm = g2.getFontMetrics();
        String text  = String.valueOf(calls);
        int dotD     = 6;
        int gap      = 4;
        int textW    = fm.stringWidth(text);
        int pillW    = dotD + gap + textW + 14;
        int pillH    = 18;
        int px       = cellX + cellW - pillW - 8;
        int py       = cellY + (cellH - pillH) / 2;

        g2.setColor(bg);
        g2.fillRoundRect(px, py, pillW, pillH, pillH, pillH);

        int dotX = px + 7;
        int dotY = py + (pillH - dotD) / 2;
        g2.setColor(fg);
        g2.fillOval(dotX, dotY, dotD, dotD);

        g2.drawString(text, dotX + dotD + gap, py + (pillH + fm.getAscent() - fm.getDescent()) / 2);
        g2.dispose();
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /** Adds a hover background effect. {@code normalBg} may be null to skip reset. */
    public static void addHoverEffect(AbstractButton b, Color normalBg, Color hoverBg) {
        b.addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) {
                if (b.isEnabled()) { b.setBackground(hoverBg); b.repaint(); }
            }
            @Override public void mouseExited(MouseEvent e) {
                b.setBackground(normalBg != null ? normalBg : b.getBackground());
                b.repaint();
            }
        });
    }

    /** Compact JBColor factory from two RGB triplets (light, dark). */
    private static JBColor jb(int lr, int lg, int lb, int dr, int dg, int db) {
        return new JBColor(new Color(lr, lg, lb), new Color(dr, dg, db));
    }

    /** Converts a {@link Color} to its lowercase CSS hex string (e.g. {@code #3574f0}). */
    public static String toHex(Color c) {
        return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
    }
}
