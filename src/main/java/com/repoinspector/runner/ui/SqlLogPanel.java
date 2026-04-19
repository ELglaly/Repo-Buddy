package com.repoinspector.runner.ui;

import com.repoinspector.constants.SqlKeywords;
import com.repoinspector.runner.model.SqlLogEntry;
import com.repoinspector.ui.UITheme;

import javax.swing.*;
import javax.swing.text.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Displays SQL statements captured by the Hibernate agent during method execution.
 *
 * <p>Uses a terminal-style dark background with syntax highlighting:
 * <ul>
 *   <li>Comment lines ({@code -- [N] timestamp}) — dim grey</li>
 *   <li>SQL keywords (SELECT, FROM, WHERE, …) — cornflower blue, bold</li>
 *   <li>Regular SQL tokens — green ({@link UITheme#SQL_FG})</li>
 * </ul>
 *
 * <p>Layout:
 * <pre>
 *  ┌─ NORTH (header bar) ──────────────────────────────────────────────┐
 *  │  ⛁  3 SQL statements captured                       ⎘ Copy SQL │
 *  ├─ CENTER (scrollable JTextPane) ───────────────────────────────────┤
 *  │  -- [1]  14:32:01.123                                             │
 *  │  SELECT u.id FROM users u WHERE u.id = ?                         │
 *  └───────────────────────────────────────────────────────────────────┘
 * </pre>
 */
class SqlLogPanel extends JPanel {

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

    private static final Color HEADER_BG     = UITheme.SQL_BG.darker();
    private static final Color SEPARATOR_CLR = UITheme.SEPARATOR_SQL;


    private final JTextPane textPane;
    private final JLabel    countLabel;

    // Reusable named styles — initialised once in the constructor.
    private final Style commentStyle;
    private final Style keywordStyle;
    private final Style regularStyle;

    SqlLogPanel() {
        setLayout(new BorderLayout(0, 0));
        setBackground(UITheme.SQL_BG);

        // ── Header ────────────────────────────────────────────────────────────
        countLabel = UITheme.headerLabel("\u26C1  No queries captured", UITheme.SQL_DIM);

        JButton copyButton = UITheme.copyButton("\u2398 Copy SQL");
        copyButton.setBackground(HEADER_BG);

        JPanel toolbar = UITheme.headerToolbar(HEADER_BG, countLabel, copyButton, SEPARATOR_CLR);
        add(toolbar, BorderLayout.NORTH);

        // ── Text pane with syntax-highlighting styles ──────────────────────────
        textPane = new JTextPane();
        textPane.setEditable(false);
        textPane.setBackground(UITheme.SQL_BG);
        textPane.setCaretColor(UITheme.SQL_FG);
        textPane.setFont(UITheme.MONO_SM);

        StyleContext sc = StyleContext.getDefaultStyleContext();
        Style base = sc.getStyle(StyleContext.DEFAULT_STYLE);

        regularStyle = textPane.addStyle("regular", base);
        StyleConstants.setForeground(regularStyle, UITheme.SQL_FG);
        StyleConstants.setFontFamily(regularStyle, Font.MONOSPACED);
        StyleConstants.setFontSize(regularStyle,   12);

        commentStyle = textPane.addStyle("comment", base);
        StyleConstants.setForeground(commentStyle, UITheme.SQL_DIM);
        StyleConstants.setFontFamily(commentStyle, Font.MONOSPACED);
        StyleConstants.setFontSize(commentStyle,   12);

        keywordStyle = textPane.addStyle("keyword", base);
        StyleConstants.setForeground(keywordStyle, UITheme.SQL_KEYWORD);
        StyleConstants.setFontFamily(keywordStyle, Font.MONOSPACED);
        StyleConstants.setFontSize(keywordStyle,   12);
        StyleConstants.setBold(keywordStyle,       true);

        JScrollPane sp = new JScrollPane(textPane);
        sp.getViewport().setBackground(UITheme.SQL_BG);
        sp.setBorder(BorderFactory.createEmptyBorder());
        add(sp, BorderLayout.CENTER);

        copyButton.addActionListener(e -> copyToClipboard(textPane.getText()));
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /** Renders the captured SQL log entries.  Clears previous content first. */
    void setSqlLogs(List<SqlLogEntry> logs) {
        if (logs.isEmpty()) {
            countLabel.setText("\u26C1  No SQL captured  (Hibernate agent may not be active)");
            countLabel.setForeground(UITheme.SQL_DIM);
            clearDocument();
            return;
        }

        String plural = logs.size() == 1 ? "statement" : "statements";
        countLabel.setText("\u26C1  " + logs.size() + " SQL " + plural + " captured");
        countLabel.setForeground(UITheme.SQL_FG);

        StyledDocument doc = textPane.getStyledDocument();
        clearDocument();

        for (int i = 0; i < logs.size(); i++) {
            SqlLogEntry entry = logs.get(i);
            appendComment(doc, "-- [" + (i + 1) + "]  "
                    + TIME_FMT.format(Instant.ofEpochMilli(entry.capturedAt())) + "\n");
            appendHighlightedSql(doc, entry.sql());
            appendRegular(doc, "\n\n");
        }

        textPane.setCaretPosition(0);
    }

    void clear() {
        countLabel.setText("\u26C1  No queries captured");
        countLabel.setForeground(UITheme.SQL_DIM);
        clearDocument();
    }

    // =========================================================================
    // Syntax highlighting helpers
    // =========================================================================

    /**
     * Appends a single SQL statement with keyword highlighting.
     *
     * <p>Strategy: split the line into whitespace-delimited tokens.  Each token
     * that matches a SQL keyword is rendered bold-blue; all others are plain green.
     * Whitespace between tokens is preserved as-is.
     */
    private void appendHighlightedSql(StyledDocument doc, String sql) {
        if (sql == null) return;

        // Walk through the SQL character-by-character collecting tokens and spaces.
        int start = 0;
        int len   = sql.length();

        while (start < len) {
            // Collect leading whitespace
            int wsEnd = start;
            while (wsEnd < len && Character.isWhitespace(sql.charAt(wsEnd))) wsEnd++;
            if (wsEnd > start) appendRegular(doc, sql.substring(start, wsEnd));
            start = wsEnd;
            if (start >= len) break;

            // Collect next token (non-whitespace run)
            int tokEnd = start;
            while (tokEnd < len && !Character.isWhitespace(sql.charAt(tokEnd))) tokEnd++;
            String token = sql.substring(start, tokEnd);

            // Strip trailing punctuation for keyword check (e.g. "WHERE," → "WHERE")
            String bare = token.replaceAll("[^A-Za-z_]", "");
            if (!bare.isEmpty() && SqlKeywords.ALL.contains(bare.toUpperCase())) {
                appendStyled(doc, token, keywordStyle);
            } else {
                appendRegular(doc, token);
            }

            start = tokEnd;
        }
    }

    private void appendComment(StyledDocument doc, String text) {
        appendStyled(doc, text, commentStyle);
    }

    private void appendRegular(StyledDocument doc, String text) {
        appendStyled(doc, text, regularStyle);
    }

    private static void appendStyled(StyledDocument doc, String text, Style style) {
        try {
            doc.insertString(doc.getLength(), text, style);
        } catch (BadLocationException ignored) {
            // Cannot happen — we always insert at the end.
        }
    }

    private void clearDocument() {
        StyledDocument doc = textPane.getStyledDocument();
        try {
            doc.remove(0, doc.getLength());
        } catch (BadLocationException ignored) {}
    }

    // =========================================================================

    private static void copyToClipboard(String text) {
        Toolkit.getDefaultToolkit()
                .getSystemClipboard()
                .setContents(new StringSelection(text), null);
    }
}
