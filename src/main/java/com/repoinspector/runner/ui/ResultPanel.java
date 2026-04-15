package com.repoinspector.runner.ui;

import com.repoinspector.ui.UITheme;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;

/**
 * Displays the JSON-serialized return value of the executed repository method.
 *
 * <p>The header bar shows execution time colour-coded by speed:
 * <ul>
 *   <li><b>Green</b> — &lt; 100 ms (fast)</li>
 *   <li><b>Amber</b> — 100 – 499 ms (moderate)</li>
 *   <li><b>Red</b>   — ≥ 500 ms (slow)</li>
 * </ul>
 *
 * <p>Layout:
 * <pre>
 *  ┌─ NORTH (header bar) ─────────────────────────────────────────────┐
 *  │  { } Result  —  ⏱ 42 ms                          ⎘ Copy JSON │
 *  ├─ CENTER (scrollable text area) ──────────────────────────────────┤
 *  │  JSON content in monospace blue-on-dark                          │
 *  └──────────────────────────────────────────────────────────────────┘
 * </pre>
 */
class ResultPanel extends JPanel {

    private static final Color HEADER_BG     = UITheme.RESULT_BG.darker();
    private static final Color SEPARATOR_CLR = new Color(40, 45, 75);

    private final JTextArea textArea;
    private final JLabel    execTimeLabel;

    ResultPanel() {
        setLayout(new BorderLayout(0, 0));
        setBackground(UITheme.RESULT_BG);

        // ── Header ────────────────────────────────────────────────────────────
        execTimeLabel = UITheme.headerLabel("{ } Result", UITheme.RESULT_FG);

        JButton copyButton = UITheme.copyButton("\u2398 Copy JSON");
        copyButton.setBackground(HEADER_BG);

        JPanel toolbar = UITheme.headerToolbar(HEADER_BG, execTimeLabel, copyButton, SEPARATOR_CLR);
        add(toolbar, BorderLayout.NORTH);

        // ── Text area ─────────────────────────────────────────────────────────
        textArea = UITheme.codeArea(UITheme.RESULT_BG, UITheme.RESULT_FG);
        copyButton.addActionListener(e -> copyToClipboard(textArea.getText()));

        add(UITheme.darkScrollPane(textArea, UITheme.RESULT_BG), BorderLayout.CENTER);
    }

    /**
     * Displays the JSON result.  Execution time is shown in the header with a
     * colour that reflects query speed (green / amber / red).
     */
    void setResult(String json, long execTimeMs) {
        execTimeLabel.setText("{ } Result  \u2014  \u23F1 " + execTimeMs + " ms");
        execTimeLabel.setForeground(UITheme.execTimeColor(execTimeMs));

        textArea.setText(json != null ? json : "null");
        textArea.setCaretPosition(0);
    }

    void clear() {
        execTimeLabel.setText("{ } Result");
        execTimeLabel.setForeground(UITheme.RESULT_FG);
        textArea.setText("");
    }

    private void copyToClipboard(String text) {
        Toolkit.getDefaultToolkit()
                .getSystemClipboard()
                .setContents(new StringSelection(text), null);
    }
}
