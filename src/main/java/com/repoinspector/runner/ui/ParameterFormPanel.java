package com.repoinspector.runner.ui;

import com.repoinspector.runner.model.ExecutionRequest.ParameterValue;
import com.repoinspector.runner.model.ParameterDef;
import com.repoinspector.runner.service.ParameterTypeClassifier;
import com.repoinspector.ui.UITheme;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Dynamically generates input fields for a repository method's parameters.
 *
 * <p>Each parameter row has three columns:
 * <ol>
 *   <li>Name label (bold) + colour-coded type badge</li>
 *   <li>Input widget (text field, checkbox, or JSON text area)</li>
 *   <li>Per-field  🎲  button — generates a type-aware random value for that field only</li>
 * </ol>
 *
 * <p>A <b>🎲 Generate All</b> button above the grid fills every field at once.
 *
 * <p>Type-to-widget mapping:
 * <ul>
 *   <li>{@code BOOLEAN}  → {@link JCheckBox}</li>
 *   <li>{@code NUMBER}   → {@link JTextField} (integer hint)</li>
 *   <li>{@code DECIMAL}  → {@link JTextField} (decimal hint)</li>
 *   <li>{@code TEXT}     → {@link JTextField}</li>
 *   <li>{@code JSON}     → multi-line {@link JTextArea} with monospace font</li>
 * </ul>
 */
class ParameterFormPanel extends JPanel {

    private static final String DICE = "\uD83C\uDFB2";   // 🎲

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

        // ── Generate-All toolbar ──────────────────────────────────────────────
        JButton generateAllButton = UITheme.button(DICE + "  Generate All");
        generateAllButton.setFont(generateAllButton.getFont().deriveFont(Font.BOLD, 11f));
        generateAllButton.setForeground(UITheme.ACCENT);
        generateAllButton.setToolTipText("Fill all fields with type-aware random sample data");
        generateAllButton.addActionListener(e -> fillAllRandom());

        JPanel topBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 4));
        topBar.add(generateAllButton);
        add(topBar, BorderLayout.NORTH);

        // ── Parameter grid ────────────────────────────────────────────────────
        JPanel grid = new JPanel(new GridBagLayout());
        grid.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(UITheme.ACCENT.darker(), 1),
                " Parameters ",
                TitledBorder.LEFT,
                TitledBorder.TOP,
                new Font(Font.SANS_SERIF, Font.BOLD, 11),
                UITheme.ACCENT));

        GridBagConstraints gbc = baseConstraints();

        for (int i = 0; i < params.size(); i++) {
            ParameterDef param = params.get(i);
            final int idx = i;

            // Col 0: label + type badge
            gbc.gridx = 0; gbc.gridy = i;
            gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0;
            grid.add(buildParamLabel(param), gbc);

            // Col 1: input widget
            gbc.gridx = 1;
            gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
            JComponent input = createInput(param);
            inputs.add(input);
            grid.add(input instanceof JTextArea ? scrollWrap((JTextArea) input) : input, gbc);

            // Col 2: per-field 🎲 button
            gbc.gridx = 2;
            gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0;
            JButton diceBtn = buildDiceButton(param, idx);
            grid.add(diceBtn, gbc);
        }

        // Vertical filler — pins the form rows to the top
        gbc.gridx = 0; gbc.gridy = params.size();
        gbc.gridwidth = 3;
        gbc.fill = GridBagConstraints.BOTH; gbc.weighty = 1.0;
        grid.add(new JPanel(), gbc);

        add(grid, BorderLayout.CENTER);
    }

    /** Centered empty-state message for methods with no parameters. */
    private void buildEmptyState() {
        setLayout(new BorderLayout());
        JPanel center = new JPanel(new GridBagLayout());
        JLabel msg = new JLabel(
                "<html><center>"
                + "<b style='font-size:13pt'>No parameters required</b><br><br>"
                + "<font color='" + UITheme.toHex(UITheme.MUTED) + "'>"
                + "Click  <b>Run \u25B6</b>  to execute this method directly."
                + "</font>"
                + "</center></html>");
        msg.setHorizontalAlignment(SwingConstants.CENTER);
        center.add(msg);
        add(center, BorderLayout.CENTER);
    }

    // =========================================================================
    // Per-field dice button
    // =========================================================================

    private JButton buildDiceButton(ParameterDef param, int idx) {
        JButton btn = new JButton(DICE);
        btn.setFont(btn.getFont().deriveFont(14f));
        btn.setMargin(new Insets(2, 6, 2, 6));
        btn.setFocusPainted(false);
        btn.setBorderPainted(true);
        btn.setToolTipText("Generate random " + param.typeName() + " for \"" + param.name() + "\"");
        btn.addActionListener(e -> fillOne(idx));
        return btn;
    }

    // =========================================================================
    // Random fill
    // =========================================================================

    /** Fills every input with a random sample value. */
    private void fillAllRandom() {
        for (int i = 0; i < params.size(); i++) {
            fillOne(i);
        }
    }

    /** Fills the input at position {@code idx} with one random sample value. */
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
    // Label + badge
    // =========================================================================

    private static JPanel buildParamLabel(ParameterDef param) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setOpaque(false);

        JLabel nameLabel = new JLabel(param.name());
        nameLabel.setFont(nameLabel.getFont().deriveFont(Font.BOLD, 12f));
        nameLabel.setAlignmentX(LEFT_ALIGNMENT);

        JLabel typeLabel = new JLabel(badgeText(param));
        typeLabel.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 10));
        typeLabel.setForeground(badgeColor(param));
        typeLabel.setAlignmentX(LEFT_ALIGNMENT);

        panel.add(nameLabel);
        panel.add(Box.createVerticalStrut(2));
        panel.add(typeLabel);
        return panel;
    }

    private static String badgeText(ParameterDef param) {
        return switch (ParameterTypeClassifier.classify(param.typeName())) {
            case BOOLEAN -> "boolean";
            case NUMBER  -> "int / long";
            case DECIMAL -> "float / double";
            case JSON    -> "object (JSON)";
            default      -> param.typeName();
        };
    }

    private static Color badgeColor(ParameterDef param) {
        return switch (ParameterTypeClassifier.classify(param.typeName())) {
            case BOOLEAN -> UITheme.BADGE_BOOLEAN;
            case NUMBER  -> UITheme.BADGE_NUMBER;
            case DECIMAL -> UITheme.BADGE_DECIMAL;
            case JSON    -> UITheme.BADGE_JSON;
            default      -> UITheme.BADGE_TEXT;
        };
    }

    // =========================================================================
    // Input widget factory
    // =========================================================================

    private JComponent createInput(ParameterDef param) {
        return switch (ParameterTypeClassifier.classify(param.typeName())) {
            case BOOLEAN -> {
                JCheckBox cb = new JCheckBox();
                cb.setToolTipText("Boolean: checked = true, unchecked = false");
                yield cb;
            }
            case JSON -> {
                JTextArea ta = new JTextArea(4, 30);
                ta.setFont(UITheme.MONO_SM);
                ta.setToolTipText("JSON value for " + param.typeName());
                ta.setBorder(BorderFactory.createLineBorder(UITheme.BADGE_JSON.darker(), 1));
                yield ta;
            }
            case NUMBER -> {
                JTextField tf = new JTextField(20);
                tf.setToolTipText("Integer value — e.g. 42");
                yield tf;
            }
            case DECIMAL -> {
                JTextField tf = new JTextField(20);
                tf.setToolTipText("Decimal value — e.g. 3.14");
                yield tf;
            }
            default -> new JTextField(20);
        };
    }

    private static JScrollPane scrollWrap(JTextArea ta) {
        return new JScrollPane(ta);
    }

    // =========================================================================
    // Value collection
    // =========================================================================

    /**
     * Collects current values from all input widgets in declaration order.
     *
     * @return list of {@link ParameterValue} ready to send to the agent
     */
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

    // =========================================================================
    // Layout helper
    // =========================================================================

    private static GridBagConstraints baseConstraints() {
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(8, 10, 8, 6);
        gbc.anchor = GridBagConstraints.NORTHWEST;
        return gbc;
    }
}
