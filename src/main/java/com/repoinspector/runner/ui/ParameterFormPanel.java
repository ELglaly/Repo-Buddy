package com.repoinspector.runner.ui;

import com.repoinspector.runner.model.ExecutionRequest.ParameterValue;
import com.repoinspector.runner.model.ParameterDef;
import com.repoinspector.runner.service.ParameterTypeClassifier;
import com.repoinspector.ui.UITheme;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Dynamically generates input fields for a repository method's parameters.
 *
 * <p>Each parameter is rendered as a vertical "param-row" card:
 * <ol>
 *   <li>Label row: bold name + colour-coded type badge + hint text</li>
 *   <li>Full-width input widget below</li>
 *   <li>Per-field "rand" button at right of label row</li>
 * </ol>
 *
 * <p>Type-to-widget mapping:
 * <ul>
 *   <li>{@code BOOLEAN}  → {@link JCheckBox}</li>
 *   <li>{@code JSON}     → multi-line {@link JTextArea}</li>
 *   <li>{@code NUMBER}   → {@link JTextField}</li>
 *   <li>{@code DECIMAL}  → {@link JTextField}</li>
 *   <li>{@code TEXT}     → {@link JTextField}</li>
 * </ul>
 */
class ParameterFormPanel extends JPanel {

    private final List<ParameterDef> params;
    private final List<JComponent>   inputs = new ArrayList<>();

    ParameterFormPanel(List<ParameterDef> params) {
        this.params = params;
        build();
    }

    // =========================================================================
    // Build
    // =========================================================================

    private void build() {
        if (params.isEmpty()) {
            buildEmptyState();
            return;
        }

        setLayout(new BorderLayout(0, 0));
        setBackground(UITheme.PANEL);

        // ── Generate-All toolbar ──────────────────────────────────────────────
        JButton generateAllButton = UITheme.button("rand all");
        generateAllButton.setFont(UITheme.UI_SM.deriveFont(Font.BOLD));
        generateAllButton.setForeground(UITheme.ACCENT);
        generateAllButton.setToolTipText("Fill all fields with type-aware random sample data");
        generateAllButton.addActionListener(e -> fillAllRandom());

        JPanel topBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 4));
        topBar.setBackground(UITheme.TOOLBAR);
        topBar.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, UITheme.BORDER_SUB));
        topBar.add(generateAllButton);
        add(topBar, BorderLayout.NORTH);

        // ── Parameter rows ────────────────────────────────────────────────────
        JPanel rows = new JPanel();
        rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));
        rows.setBackground(UITheme.PANEL);
        rows.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));

        for (int i = 0; i < params.size(); i++) {
            ParameterDef param = params.get(i);
            final int idx = i;

            JComponent input = createInput(param);
            inputs.add(input);

            // Label row: name + type badge + hint + spacer + rand button
            JLabel nameLabel = new JLabel(param.name());
            nameLabel.setFont(UITheme.UI.deriveFont(Font.BOLD, 12f));
            nameLabel.setForeground(UITheme.INK);

            JLabel typeBadge = buildTypeBadge(param);
            JLabel hintLabel = buildHintLabel(param);

            JButton randBtn = UITheme.iconButton("rand");
            randBtn.setFont(UITheme.UI_SM);
            randBtn.setForeground(UITheme.MUTED);
            randBtn.setToolTipText("Generate random " + param.typeName());
            randBtn.addActionListener(e -> fillOne(idx));

            JPanel labelRow = new JPanel();
            labelRow.setLayout(new BoxLayout(labelRow, BoxLayout.X_AXIS));
            labelRow.setOpaque(false);
            labelRow.add(nameLabel);
            labelRow.add(Box.createHorizontalStrut(6));
            labelRow.add(typeBadge);
            labelRow.add(Box.createHorizontalStrut(6));
            labelRow.add(hintLabel);
            labelRow.add(Box.createHorizontalGlue());
            labelRow.add(randBtn);

            // Input wrapper
            JComponent inputWidget = input instanceof JTextArea
                    ? new JScrollPane(input) : input;
            if (input instanceof JTextArea) {
                inputWidget.setPreferredSize(new Dimension(Integer.MAX_VALUE, 80));
                inputWidget.setMaximumSize(new Dimension(Integer.MAX_VALUE, 100));
            }
            if (input instanceof JTextField tf) {
                tf.setFont(UITheme.MONO_XS.deriveFont(12f));
                tf.setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(UITheme.BORDER_SUB, 1),
                        BorderFactory.createEmptyBorder(4, 8, 4, 8)));
                tf.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
            }

            // Param-row card
            JPanel row = new JPanel(new BorderLayout(0, 4));
            row.setOpaque(false);
            row.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 0, 1, 0, UITheme.BORDER_SUB),
                    BorderFactory.createEmptyBorder(10, 14, 10, 14)));
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
            row.add(labelRow,     BorderLayout.NORTH);
            row.add(inputWidget,  BorderLayout.CENTER);

            rows.add(row);
        }

        // Vertical filler
        JPanel filler = new JPanel();
        filler.setOpaque(false);
        rows.add(filler);

        add(rows, BorderLayout.CENTER);
    }

    private void buildEmptyState() {
        setLayout(new BorderLayout());
        JPanel center = new JPanel(new GridBagLayout());
        center.setBackground(UITheme.PANEL);
        JLabel msg = new JLabel(
                "<html><center>"
                + "<b style='font-size:13pt'>No parameters required</b><br><br>"
                + "<font color='" + UITheme.toHex(UITheme.MUTED) + "'>"
                + "Click  <b>Run \u25B6</b>  to execute this method directly."
                + "</font></center></html>");
        msg.setHorizontalAlignment(SwingConstants.CENTER);
        center.add(msg);
        add(center, BorderLayout.CENTER);
    }

    // =========================================================================
    // Badge + hint builders
    // =========================================================================

    private static JLabel buildTypeBadge(ParameterDef param) {
        ParameterDef.FieldType kind = ParameterTypeClassifier.classify(param.typeName());
        String text; Color fg, bg;
        switch (kind) {
            case NUMBER  -> { text = "Long";   fg = UITheme.BADGE_NUMBER;  bg = UITheme.BADGE_NUMBER_BG; }
            case DECIMAL -> { text = "Double"; fg = UITheme.BADGE_DECIMAL; bg = UITheme.BADGE_DECIMAL_BG; }
            case BOOLEAN -> { text = "Boolean";fg = UITheme.BADGE_BOOLEAN; bg = UITheme.BADGE_BOOLEAN_BG; }
            case JSON    -> { text = "JSON";   fg = UITheme.BADGE_JSON;    bg = UITheme.BADGE_JSON_BG; }
            default      -> { text = param.typeName().contains("<")
                                ? param.typeName().substring(0, param.typeName().indexOf('<'))
                                : param.typeName();
                              fg = UITheme.BADGE_TEXT; bg = UITheme.BADGE_TEXT_BG; }
        }

        JLabel badge = new JLabel(text) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(getBackground());
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 6, 6);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        badge.setFont(UITheme.MONO_XS.deriveFont(10f));
        badge.setForeground(fg);
        badge.setBackground(bg);
        badge.setOpaque(false);
        badge.setBorder(BorderFactory.createEmptyBorder(1, 6, 1, 6));
        return badge;
    }

    private static JLabel buildHintLabel(ParameterDef param) {
        ParameterDef.FieldType kind = ParameterTypeClassifier.classify(param.typeName());
        String hint = switch (kind) {
            case NUMBER  -> "required \u00B7 numeric";
            case DECIMAL -> "required \u00B7 decimal";
            case BOOLEAN -> "true or false";
            case JSON    -> "JSON object";
            default -> param.typeName().toLowerCase().contains("instant")
                    || param.typeName().toLowerCase().contains("date") ? "ISO-8601" : "required";
        };
        JLabel label = new JLabel(hint);
        label.setFont(UITheme.UI_SM);
        label.setForeground(UITheme.MUTED);
        return label;
    }

    // =========================================================================
    // Random fill
    // =========================================================================

    private void fillAllRandom() {
        for (int i = 0; i < params.size(); i++) fillOne(i);
    }

    private void fillOne(int idx) {
        String value = RandomDataGenerator.generate(params.get(idx));
        applyValue(inputs.get(idx), value);
    }

    private static void applyValue(JComponent input, String value) {
        if (input instanceof JCheckBox cb) {
            cb.setSelected(Boolean.parseBoolean(value));
        } else if (input instanceof JTextArea ta) {
            ta.setText(value);
            ta.setCaretPosition(0);
        } else if (input instanceof JTextField tf) {
            tf.setText(value);
        }
    }

    // =========================================================================
    // Input widget factory
    // =========================================================================

    private JComponent createInput(ParameterDef param) {
        ParameterDef.FieldType kind = ParameterTypeClassifier.classify(param.typeName());
        return switch (kind) {
            case BOOLEAN -> {
                JCheckBox cb = new JCheckBox();
                cb.setOpaque(false);
                cb.setToolTipText("Boolean: checked = true, unchecked = false");
                yield cb;
            }
            case JSON -> {
                JTextArea ta = new JTextArea(4, 30);
                ta.setFont(UITheme.MONO_XS.deriveFont(11f));
                ta.setBackground(UITheme.PANEL);
                ta.setForeground(UITheme.INK);
                ta.setCaretColor(UITheme.INK);
                ta.setToolTipText("JSON value for " + param.typeName());
                ta.setBorder(BorderFactory.createLineBorder(UITheme.BORDER_SUB, 1));
                yield ta;
            }
            case NUMBER -> {
                JTextField tf = new JTextField(20);
                tf.setToolTipText("Integer value \u2014 e.g. 42");
                yield tf;
            }
            case DECIMAL -> {
                JTextField tf = new JTextField(20);
                tf.setToolTipText("Decimal value \u2014 e.g. 3.14");
                yield tf;
            }
            default -> new JTextField(20);
        };
    }

    // =========================================================================
    // Value collection
    // =========================================================================

    List<ParameterValue> collectValues() {
        List<ParameterValue> values = new ArrayList<>();
        for (int i = 0; i < params.size(); i++) {
            values.add(new ParameterValue(params.get(i).typeName(), extractRawValue(inputs.get(i))));
        }
        return values;
    }

    private static String extractRawValue(JComponent comp) {
        if (comp instanceof JCheckBox cb)  return String.valueOf(cb.isSelected());
        if (comp instanceof JTextArea  ta) return ta.getText().trim();
        if (comp instanceof JTextField tf) return tf.getText().trim();
        return "";
    }
}
