package com.repoinspector.runner.ui;

import com.repoinspector.runner.model.ExecutionRequest.ParameterValue;
import com.repoinspector.runner.model.ParameterDef;
import com.repoinspector.ui.UITheme;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Dynamically generates input fields for a repository method's parameters.
 *
 * <p>Each parameter is rendered as a two-column row:
 * <ul>
 *   <li><b>Left</b>: parameter name (bold) + a colour-coded type badge underneath</li>
 *   <li><b>Right</b>: the appropriate input widget for the type</li>
 * </ul>
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

    private final List<ParameterDef> params;
    private final List<JComponent>   inputs = new ArrayList<>();

    ParameterFormPanel(List<ParameterDef> params) {
        this.params = params;
        build();
    }

    // -------------------------------------------------------------------------

    private void build() {
        if (params.isEmpty()) {
            buildEmptyState();
            return;
        }

        setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(UITheme.ACCENT.darker(), 1),
                " Parameters ",
                TitledBorder.LEFT,
                TitledBorder.TOP,
                new Font(Font.SANS_SERIF, Font.BOLD, 11),
                UITheme.ACCENT));

        setLayout(new GridBagLayout());
        GridBagConstraints gbc = baseConstraints();

        for (int i = 0; i < params.size(); i++) {
            ParameterDef param = params.get(i);

            // Left: name + type badge
            gbc.gridx = 0; gbc.gridy = i;
            gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0;
            add(buildParamLabel(param), gbc);

            // Right: input widget
            gbc.gridx = 1;
            gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
            JComponent input = createInput(param);
            inputs.add(input);
            add(input instanceof JTextArea ? new JScrollPane(input) : input, gbc);
        }

        // Vertical filler so form sticks to the top
        gbc.gridx = 0; gbc.gridy = params.size();
        gbc.gridwidth = 2;
        gbc.fill = GridBagConstraints.BOTH; gbc.weighty = 1.0;
        add(new JPanel(), gbc);
    }

    /** Centered message panel shown when the method has no parameters. */
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

    /**
     * Builds the left-column label panel: bold parameter name on the first line,
     * colour-coded type badge on the second.
     */
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
        return switch (param.fieldType()) {
            case BOOLEAN -> "boolean";
            case NUMBER  -> "int / long";
            case DECIMAL -> "float / double";
            case JSON    -> "object (JSON)";
            default      -> param.typeName();
        };
    }

    private static Color badgeColor(ParameterDef param) {
        return switch (param.fieldType()) {
            case BOOLEAN -> UITheme.BADGE_BOOLEAN;
            case NUMBER  -> UITheme.BADGE_NUMBER;
            case DECIMAL -> UITheme.BADGE_DECIMAL;
            case JSON    -> UITheme.BADGE_JSON;
            default      -> UITheme.BADGE_TEXT;
        };
    }

    private JComponent createInput(ParameterDef param) {
        return switch (param.fieldType()) {
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

    private static GridBagConstraints baseConstraints() {
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(8, 10, 8, 10);
        gbc.anchor = GridBagConstraints.NORTHWEST;
        return gbc;
    }
}
