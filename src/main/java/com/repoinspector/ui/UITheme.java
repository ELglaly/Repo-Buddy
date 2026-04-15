package com.repoinspector.ui;

import com.intellij.ui.JBColor;

import javax.swing.*;
import javax.swing.border.Border;
import java.awt.*;

/**
 * Central UI palette for all RepoBuddy panels — the single source of truth for
 * colours, fonts, and component factories (DRY / OCP).
 *
 * <p>All colours are {@link JBColor} instances so the plugin looks correct in
 * both IntelliJ's Light and Dark themes.  Consuming panels must <em>not</em>
 * declare their own colour constants; they must reference this class instead.
 *
 * <p>Factory methods (e.g. {@link #codeArea}, {@link #runButton}) apply the
 * standard styling so panel code stays declarative and change-resistant (SRP).
 */
public final class UITheme {

    private UITheme() {}

    // ── Panel backgrounds ─────────────────────────────────────────────────────
    public static final Color SQL_BG    = jb(22,  24,  26,  22,  24,  26);
    public static final Color RESULT_BG = jb(18,  20,  38,  18,  20,  38);
    public static final Color ERROR_BG  = jb(28,  10,  10,  28,  10,  10);
    public static final Color HEADER_BG = jb(36,  39,  46,  43,  45,  48);

    // ── Code-area foregrounds ─────────────────────────────────────────────────
    public static final Color SQL_FG      = jb(152, 215, 152, 152, 215, 152);
    public static final Color SQL_KEYWORD = jb(86,  156, 214, 86,  156, 214);
    public static final Color SQL_DIM     = jb(85,  110, 85,  85,  110, 85);
    public static final Color RESULT_FG   = jb(200, 220, 255, 200, 220, 255);
    public static final Color ERROR_FG    = jb(248, 100, 100, 248, 100, 100);

    // ── Semantic ──────────────────────────────────────────────────────────────
    public static final Color SUCCESS = jb(80,  200, 120, 80,  200, 120);
    public static final Color WARNING = jb(255, 195,  90, 255, 195,  90);
    public static final Color DANGER  = jb(255,  70,  70, 255,  70,  70);
    public static final Color ACCENT  = jb(97,  175, 239, 97,  175, 239);
    public static final Color MUTED   = jb(120, 130, 145, 120, 130, 145);

    // ── Method-header label colours ───────────────────────────────────────────
    /** Repository class name label (gold / orange). */
    public static final Color REPO_COLOR   = jb(232, 191,  90, 232, 191,  90);
    /** Method name label (cornflower blue). */
    public static final Color METHOD_COLOR = jb(106, 176, 245, 106, 176, 245);
    /** Arrow / separator in the header. */
    public static final Color ARROW_COLOR  = jb(128, 138, 150, 128, 138, 150);

    // ── Call-count badge colours ──────────────────────────────────────────────
    public static final Color COUNT_ZERO = new JBColor(new Color(255, 180, 180), new Color(120,  40,  40));
    public static final Color COUNT_LOW  = new JBColor(new Color(255, 255, 180), new Color(100,  90,  20));
    public static final Color COUNT_HIGH = new JBColor(new Color(190, 255, 190), new Color( 30,  90,  30));

    // ── Parameter-type badge colours ──────────────────────────────────────────
    public static final Color BADGE_TEXT    = jb(181, 206, 168, 181, 206, 168);
    public static final Color BADGE_NUMBER  = jb(184, 215, 163, 184, 215, 163);
    public static final Color BADGE_BOOLEAN = jb(86,  156, 214, 86,  156, 214);
    public static final Color BADGE_JSON    = jb(206, 145, 120, 206, 145, 120);
    public static final Color BADGE_DECIMAL = jb(220, 180, 100, 220, 180, 100);

    // ── Run-button ────────────────────────────────────────────────────────────
    public static final Color RUN_BG = jb(0,  148, 78,  0,  128, 68);
    public static final Color RUN_FG = Color.WHITE;

    // ── Fonts ─────────────────────────────────────────────────────────────────
    public static final Font MONO    = new Font(Font.MONOSPACED, Font.PLAIN,  13);
    public static final Font MONO_SM = new Font(Font.MONOSPACED, Font.PLAIN,  12);

    // =========================================================================
    // Component factories
    // =========================================================================

    /**
     * Creates a standard, focus-ring-free button.
     * Use this for all non-run buttons so styling stays consistent.
     */
    public static JButton button(String text) {
        JButton b = new JButton(text);
        b.setFocusPainted(false);
        return b;
    }

    /** Creates the green "Run ▶" button used in the popup south bar. */
    public static JButton runButton() {
        JButton b = new JButton("  Run \u25B6  ");
        b.setFont(b.getFont().deriveFont(Font.BOLD, 13f));
        b.setBackground(RUN_BG);
        b.setForeground(RUN_FG);
        b.setOpaque(true);
        b.setBorderPainted(false);
        b.setFocusPainted(false);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return b;
    }

    /** Creates a clipboard-copy button with standard label. */
    public static JButton copyButton(String label) {
        JButton b = button(label);
        b.setFont(b.getFont().deriveFont(11f));
        b.setToolTipText("Copy to clipboard");
        return b;
    }

    /**
     * Creates a read-only monospace text area styled for the given dark background.
     * Margin and line-wrap are pre-configured for code content.
     */
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

    /**
     * Wraps a text area in a scroll pane whose viewport background matches the
     * text area background (prevents white flash during resize).
     */
    public static JScrollPane darkScrollPane(JTextArea area, Color bg) {
        JScrollPane sp = new JScrollPane(area);
        sp.getViewport().setBackground(bg);
        sp.setBorder(BorderFactory.createEmptyBorder());
        return sp;
    }

    /**
     * Creates a dark panel header toolbar with a label on the left and an
     * optional button on the right, separated from the content below by a
     * 1-pixel matte border.
     *
     * @param bg          background for the toolbar (typically the panel bg darkened)
     * @param label       the pre-configured left label
     * @param rightButton optional right-side button (may be null)
     * @param borderColor colour of the bottom separator line
     */
    public static JPanel headerToolbar(Color bg, JLabel label, JButton rightButton, Color borderColor) {
        JPanel bar = new JPanel(new BorderLayout(6, 0));
        bar.setBackground(bg);
        bar.add(label, BorderLayout.CENTER);
        if (rightButton != null) {
            bar.add(rightButton, BorderLayout.EAST);
        }
        bar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, borderColor),
                BorderFactory.createEmptyBorder(5, 10, 5, 8)));
        return bar;
    }

    /**
     * Returns a colour for an execution-time value:
     * <ul>
     *   <li>&lt; 100 ms → {@link #SUCCESS} (green)</li>
     *   <li>100 – 499 ms → {@link #WARNING} (amber)</li>
     *   <li>≥ 500 ms → {@link #DANGER} (red)</li>
     * </ul>
     */
    public static Color execTimeColor(long ms) {
        if (ms < 100) return SUCCESS;
        if (ms < 500) return WARNING;
        return DANGER;
    }

    /**
     * Creates a header label pre-configured with bold text, the given colour,
     * and a transparent background so it inherits the parent bar's colour.
     */
    public static JLabel headerLabel(String text, Color fg) {
        JLabel lbl = new JLabel(text);
        lbl.setFont(lbl.getFont().deriveFont(Font.BOLD, 12f));
        lbl.setForeground(fg);
        lbl.setOpaque(false);
        return lbl;
    }

    /**
     * Builds an HTML-formatted popup-header string showing the repository class
     * and method name in their semantic colours, suitable for a {@link JLabel}.
     *
     * @param repoSimpleName   simple class name of the repository
     * @param methodName       method name
     * @param paramSummary     formatted parameter list, e.g. {@code "id: Long, name: String"}
     */
    public static String popupHeaderHtml(String repoSimpleName, String methodName, String paramSummary) {
        String repoHex   = toHex(REPO_COLOR);
        String methodHex = toHex(METHOD_COLOR);
        String arrowHex  = toHex(ARROW_COLOR);
        String paramHex  = toHex(MUTED);
        return "<html>"
                + "<font color='" + repoHex   + "'><b>" + repoSimpleName + "</b></font>"
                + "<font color='" + arrowHex  + "'>  \u2192  </font>"
                + "<font color='" + methodHex + "'><b>" + methodName + "</b></font>"
                + "<font color='" + arrowHex  + "'>(</font>"
                + "<font color='" + paramHex  + "'>" + paramSummary + "</font>"
                + "<font color='" + arrowHex  + "'>)</font>"
                + "</html>";
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /** Compact factory for {@link JBColor} from light/dark RGB triplets. */
    private static JBColor jb(int lr, int lg, int lb, int dr, int dg, int db) {
        return new JBColor(new Color(lr, lg, lb), new Color(dr, dg, db));
    }

    /** Converts a {@link Color} to its lowercase CSS hex string, e.g. {@code #6ab0f5}. */
    public static String toHex(Color c) {
        return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
    }
}