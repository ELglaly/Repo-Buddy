package com.repoinspector.runner.ui;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
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
    private static final Gson  PRETTY_GSON   = new GsonBuilder().setPrettyPrinting().create();

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
     * Displays the JSON result with pretty-printing and an item count for arrays.
     * Execution time is shown in the header colour-coded by speed (green/amber/red).
     */
    void setResult(String json, long execTimeMs) {
        String prettyJson = prettyPrint(json);
        String countNote  = itemCountNote(json);

        execTimeLabel.setText("{ } Result  \u2014  \u23F1 " + execTimeMs + " ms" + countNote);
        execTimeLabel.setForeground(UITheme.execTimeColor(execTimeMs));

        textArea.setText(prettyJson);
        textArea.setCaretPosition(0);
    }

    // -------------------------------------------------------------------------

    /** Re-formats a JSON string with 2-space indentation.  Returns the original on failure. */
    private static String prettyPrint(String json) {
        if (json == null) return "null";
        try {
            JsonElement element = JsonParser.parseString(json);
            return PRETTY_GSON.toJson(element);
        } catch (Exception ignored) {
            return json;
        }
    }

    /**
     * Returns an empty string for non-array JSON, or {@code "  [N items]"} for arrays
     * so the developer sees the count without scrolling.
     */
    private static String itemCountNote(String json) {
        if (json == null) return "";
        try {
            JsonElement element = JsonParser.parseString(json);
            if (element.isJsonArray()) {
                int size = element.getAsJsonArray().size();
                return "  \u2014  " + size + (size == 1 ? " item" : " items");
            }
            return "";
        } catch (Exception ignored) {
            return "";
        }
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
