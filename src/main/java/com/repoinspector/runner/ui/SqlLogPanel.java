package com.repoinspector.runner.ui;

import com.repoinspector.runner.model.SqlLogEntry;
import com.repoinspector.ui.UITheme;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Displays SQL statements captured by the Hibernate agent during method execution.
 *
 * <p>Uses a terminal-style dark background to visually distinguish SQL content
 * from other panels.  The header bar shows a live count badge.
 *
 * <p>Layout:
 * <pre>
 *  ┌─ NORTH (header bar) ──────────────────────────────────────────────┐
 *  │  ⛁  3 SQL statements captured                       ⎘ Copy SQL │
 *  ├─ CENTER (scrollable text area) ───────────────────────────────────┤
 *  │  -- [1]  14:32:01.123                                             │
 *  │  SELECT * FROM users WHERE id = ?                                 │
 *  └───────────────────────────────────────────────────────────────────┘
 * </pre>
 */
class SqlLogPanel extends JPanel {

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

    private static final Color HEADER_BG     = UITheme.SQL_BG.darker();
    private static final Color SEPARATOR_CLR = new Color(40, 55, 40);

    private final JTextArea textArea;
    private final JLabel    countLabel;

    SqlLogPanel() {
        setLayout(new BorderLayout(0, 0));
        setBackground(UITheme.SQL_BG);

        // ── Header ────────────────────────────────────────────────────────────
        countLabel = UITheme.headerLabel("\u26C1  No queries captured", UITheme.SQL_DIM);

        JButton copyButton = UITheme.copyButton("\u2398 Copy SQL");
        copyButton.setBackground(HEADER_BG);

        JPanel toolbar = UITheme.headerToolbar(HEADER_BG, countLabel, copyButton, SEPARATOR_CLR);
        add(toolbar, BorderLayout.NORTH);

        // ── Text area ─────────────────────────────────────────────────────────
        textArea = UITheme.codeArea(UITheme.SQL_BG, UITheme.SQL_FG);
        copyButton.addActionListener(e -> copyToClipboard(textArea.getText()));

        add(UITheme.darkScrollPane(textArea, UITheme.SQL_BG), BorderLayout.CENTER);
    }

    /** Renders the captured SQL log entries.  Clears previous content first. */
    void setSqlLogs(List<SqlLogEntry> logs) {
        if (logs.isEmpty()) {
            countLabel.setText("\u26C1  No SQL captured  (Hibernate agent may not be active)");
            countLabel.setForeground(UITheme.SQL_DIM);
            textArea.setText("");
            return;
        }

        String plural = logs.size() == 1 ? "statement" : "statements";
        countLabel.setText("\u26C1  " + logs.size() + " SQL " + plural + " captured");
        countLabel.setForeground(UITheme.SQL_FG);

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < logs.size(); i++) {
            SqlLogEntry entry = logs.get(i);
            sb.append("-- [").append(i + 1).append("]  ")
              .append(TIME_FMT.format(Instant.ofEpochMilli(entry.capturedAt())))
              .append('\n')
              .append(entry.sql())
              .append("\n\n");
        }

        textArea.setText(sb.toString());
        textArea.setCaretPosition(0);
    }

    void clear() {
        countLabel.setText("\u26C1  No queries captured");
        countLabel.setForeground(UITheme.SQL_DIM);
        textArea.setText("");
    }

    private void copyToClipboard(String text) {
        Toolkit.getDefaultToolkit()
                .getSystemClipboard()
                .setContents(new StringSelection(text), null);
    }
}
