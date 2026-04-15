package com.repoinspector.runner.ui;

import com.repoinspector.ui.UITheme;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;

/**
 * Displays the exception stack trace returned by the agent on method failure,
 * or a connection-error message when the agent is unreachable.
 *
 * <p>Layout:
 * <pre>
 *  ┌─ NORTH (header bar) ────────────────────────────────────────────┐
 *  │  ✖  &lt;first-line of error&gt;                          ⎘ Copy │
 *  ├─ CENTER (scrollable text area) ────────────────────────────────┤
 *  │  full stack trace / message in monospace red-on-dark           │
 *  └────────────────────────────────────────────────────────────────┘
 * </pre>
 */
class ErrorPanel extends JPanel {

    private static final Color HEADER_BG     = UITheme.ERROR_BG.darker();
    private static final Color SEPARATOR_CLR = new Color(80, 30, 30);

    private final JTextArea textArea;
    private final JLabel    titleLabel;

    ErrorPanel() {
        setLayout(new BorderLayout(0, 0));
        setBackground(UITheme.ERROR_BG);

        // ── Header ────────────────────────────────────────────────────────────
        titleLabel = UITheme.headerLabel("\u2716  No error", UITheme.MUTED);

        JButton copyButton = UITheme.copyButton("\u2398 Copy");
        copyButton.setBackground(HEADER_BG);

        JPanel toolbar = UITheme.headerToolbar(HEADER_BG, titleLabel, copyButton, SEPARATOR_CLR);
        add(toolbar, BorderLayout.NORTH);

        // ── Text area ─────────────────────────────────────────────────────────
        textArea = UITheme.codeArea(UITheme.ERROR_BG, UITheme.ERROR_FG);
        copyButton.addActionListener(e -> copyToClipboard(textArea.getText()));

        add(UITheme.darkScrollPane(textArea, UITheme.ERROR_BG), BorderLayout.CENTER);
    }

    /**
     * Displays an exception stack trace or connection-error message.
     * The header bar shows the first line of the text for quick scanning.
     */
    void setError(String text) {
        if (text == null || text.isBlank()) {
            titleLabel.setText("\u2716  No error");
            titleLabel.setForeground(UITheme.MUTED);
            textArea.setText("");
            return;
        }

        String firstLine = text.lines().findFirst().orElse("Error");
        String truncated = firstLine.length() > 74 ? firstLine.substring(0, 74) + "\u2026" : firstLine;
        titleLabel.setText("\u2716  " + truncated);
        titleLabel.setForeground(UITheme.ERROR_FG);

        textArea.setText(text);
        textArea.setCaretPosition(0);
    }

    void clear() {
        titleLabel.setText("\u2716  No error");
        titleLabel.setForeground(UITheme.MUTED);
        textArea.setText("");
    }

    private void copyToClipboard(String text) {
        Toolkit.getDefaultToolkit()
                .getSystemClipboard()
                .setContents(new StringSelection(text), null);
    }
}