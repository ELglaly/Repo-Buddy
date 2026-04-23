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
 * <p>Each parameter is rendered as a vertical card:
 * <ol>
 *   <li>Label row: bold name, type badge, hint text, and a per-field generate button</li>
 *   <li>Full-width input widget below</li>
 * </ol>
 *
 * <p>Type-to-widget mapping:
 * <ul>
 *   <li>{@code BOOLEAN} -> {@link JCheckBox}</li>
 *   <li>{@code JSON} -> multi-line {@link JTextArea}</li>
 *   <li>{@code NUMBER} -> compact {@link JTextArea}</li>
 *   <li>{@code DECIMAL} -> compact {@link JTextArea}</li>
 *   <li>{@code TEXT} -> compact {@link JTextArea}</li>
 * </ul>
 */
class ParameterFormPanel extends JPanel {

    private final List<ParameterDef> params;
    private final List<JComponent> inputs = new ArrayList<>();

    ParameterFormPanel(List<ParameterDef> params) {
        this.params = params;
        build();
    }

    private void build() {
        if (params.isEmpty()) {
            buildEmptyState();
            return;
        }

        setLayout(new BorderLayout());
        setBackground(UITheme.PANEL);
        add(buildTopBar(), BorderLayout.NORTH);
        add(buildRowsPanel(), BorderLayout.CENTER);
    }

    private JComponent buildTopBar() {
        JButton generateAllButton = buildGenerateAllButton();
        generateAllButton.setToolTipText("Fill all fields with type-aware random sample data");
        generateAllButton.addActionListener(e -> fillAllRandom());

        JLabel sectionTitle = new JLabel("Input Parameters");
        sectionTitle.setFont(UITheme.UI_BOLD.deriveFont(13f));
        sectionTitle.setForeground(UITheme.INK);

        JLabel sectionHint = new JLabel("Provide values or generate sample data for a quick execution pass.");
        sectionHint.setFont(UITheme.UI_SM);
        sectionHint.setForeground(UITheme.MUTED);

        JPanel topLeft = new JPanel();
        topLeft.setOpaque(false);
        topLeft.setLayout(new BoxLayout(topLeft, BoxLayout.Y_AXIS));
        topLeft.add(sectionTitle);
        topLeft.add(Box.createVerticalStrut(2));
        topLeft.add(sectionHint);

        JPanel topBar = new JPanel(new BorderLayout());
        topBar.setBackground(UITheme.TOOLBAR);
        topBar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, UITheme.BORDER_SUB),
                BorderFactory.createEmptyBorder(8, 12, 8, 12)));
        topBar.add(topLeft, BorderLayout.WEST);
        topBar.add(generateAllButton, BorderLayout.EAST);
        return topBar;
    }

    private JComponent buildRowsPanel() {
        JPanel rows = new JPanel();
        rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));
        rows.setBackground(UITheme.PANEL);
        rows.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));

        for (int i = 0; i < params.size(); i++) {
            rows.add(buildParamRow(params.get(i), i));
            rows.add(Box.createVerticalStrut(8));
        }

        JPanel filler = new JPanel();
        filler.setOpaque(false);
        rows.add(filler);
        return rows;
    }

    private JComponent buildParamRow(ParameterDef param, int idx) {
        JComponent input = createInput(param);
        inputs.add(input);

        JPanel row = new JPanel(new BorderLayout(0, 8)) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(UITheme.PANEL_2);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 16, 16);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        row.setOpaque(false);
        row.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(UITheme.BORDER_SUB, 1),
                        BorderFactory.createEmptyBorder()),
                BorderFactory.createEmptyBorder(12, 14, 12, 14)));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        row.add(buildLabelRow(param, idx), BorderLayout.NORTH);
        row.add(wrapInput(param, input), BorderLayout.CENTER);
        return row;
    }

    private JComponent buildLabelRow(ParameterDef param, int idx) {
        JLabel nameLabel = new JLabel(param.name());
        nameLabel.setFont(UITheme.UI.deriveFont(Font.BOLD, 12f));
        nameLabel.setForeground(UITheme.INK);

        JLabel typeBadge = buildTypeBadge(param);
        JLabel hintLabel = buildHintLabel(param);

        JButton generateOneButton = buildGenerateOneButton();
        generateOneButton.setToolTipText("Generate random " + param.typeName());
        generateOneButton.addActionListener(e -> fillOne(idx));

        JPanel labelRow = new JPanel();
        labelRow.setLayout(new BoxLayout(labelRow, BoxLayout.X_AXIS));
        labelRow.setOpaque(false);
        labelRow.add(nameLabel);
        labelRow.add(Box.createHorizontalStrut(6));
        labelRow.add(typeBadge);
        labelRow.add(Box.createHorizontalStrut(6));
        labelRow.add(hintLabel);
        labelRow.add(Box.createHorizontalGlue());
        labelRow.add(generateOneButton);
        return labelRow;
    }

    private JComponent wrapInput(ParameterDef param, JComponent input) {
        if (input instanceof JTextArea textArea) {
            configureTextArea(param, textArea);
            JScrollPane scrollPane = new JScrollPane(textArea);
            scrollPane.setBorder(BorderFactory.createEmptyBorder());
            scrollPane.getViewport().setBackground(UITheme.PANEL);

            boolean json = ParameterTypeClassifier.classify(param.typeName()) == ParameterDef.FieldType.JSON;
            scrollPane.setPreferredSize(new Dimension(Integer.MAX_VALUE, json ? 92 : 44));
            scrollPane.setMaximumSize(new Dimension(Integer.MAX_VALUE, json ? 110 : 52));
            return scrollPane;
        }

        if (input instanceof JTextField tf) {
            tf.setFont(UITheme.UI.deriveFont(12f));
            tf.setBackground(UITheme.PANEL);
            tf.setForeground(UITheme.INK);
            tf.setCaretColor(UITheme.INK);
            tf.setHorizontalAlignment(SwingConstants.LEFT);
            tf.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(UITheme.BORDER_SUB, 1),
                    BorderFactory.createEmptyBorder(6, 10, 6, 10)));
            tf.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        }

        return input;
    }

    private void configureTextArea(ParameterDef param, JTextArea textArea) {
        boolean json = ParameterTypeClassifier.classify(param.typeName()) == ParameterDef.FieldType.JSON;
        textArea.setLineWrap(false);
        textArea.setWrapStyleWord(false);
        textArea.setRows(json ? 4 : 2);
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

    private static JLabel buildTypeBadge(ParameterDef param) {
        ParameterDef.FieldType kind = ParameterTypeClassifier.classify(param.typeName());
        String text;
        Color fg;
        Color bg;
        switch (kind) {
            case NUMBER -> {
                text = "Long";
                fg = UITheme.BADGE_NUMBER;
                bg = UITheme.BADGE_NUMBER_BG;
            }
            case DECIMAL -> {
                text = "Double";
                fg = UITheme.BADGE_DECIMAL;
                bg = UITheme.BADGE_DECIMAL_BG;
            }
            case BOOLEAN -> {
                text = "Boolean";
                fg = UITheme.BADGE_BOOLEAN;
                bg = UITheme.BADGE_BOOLEAN_BG;
            }
            case JSON -> {
                text = "JSON";
                fg = UITheme.BADGE_JSON;
                bg = UITheme.BADGE_JSON_BG;
            }
            default -> {
                text = param.typeName().contains("<")
                        ? param.typeName().substring(0, param.typeName().indexOf('<'))
                        : param.typeName();
                fg = UITheme.BADGE_TEXT;
                bg = UITheme.BADGE_TEXT_BG;
            }
        }

        JLabel badge = new JLabel(text) {
            @Override
            protected void paintComponent(Graphics g) {
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
            case NUMBER -> "required \u00b7 numeric";
            case DECIMAL -> "required \u00b7 decimal";
            case BOOLEAN -> "true or false";
            case JSON -> "JSON object";
            default -> param.typeName().toLowerCase().contains("instant")
                    || param.typeName().toLowerCase().contains("date") ? "ISO-8601" : "required";
        };
        JLabel label = new JLabel(hint);
        label.setFont(UITheme.UI_SM);
        label.setForeground(UITheme.MUTED);
        return label;
    }

    private void fillAllRandom() {
        for (int i = 0; i < params.size(); i++) {
            fillOne(i);
        }
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
                ta.setFont(UITheme.UI.deriveFont(12f));
                ta.setBackground(UITheme.PANEL);
                ta.setForeground(UITheme.INK);
                ta.setCaretColor(UITheme.INK);
                ta.setAlignmentX(Component.LEFT_ALIGNMENT);
                ta.setToolTipText("JSON value for " + param.typeName());
                ta.setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(UITheme.BORDER_SUB, 1),
                        BorderFactory.createEmptyBorder(8, 10, 8, 10)));
                yield ta;
            }
            case NUMBER -> {
                JTextArea ta = createSingleValueArea();
                ta.setToolTipText("Integer value - e.g. 42");
                yield ta;
            }
            case DECIMAL -> {
                JTextArea ta = createSingleValueArea();
                ta.setToolTipText("Decimal value - e.g. 3.14");
                yield ta;
            }
            default -> createSingleValueArea();
        };
    }

    private static JTextArea createSingleValueArea() {
        JTextArea ta = new JTextArea(2, 20);
        ta.setFont(UITheme.UI.deriveFont(12f));
        ta.setBackground(UITheme.PANEL);
        ta.setForeground(UITheme.INK);
        ta.setCaretColor(UITheme.INK);
        ta.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(UITheme.BORDER_SUB, 1),
                BorderFactory.createEmptyBorder(8, 10, 8, 10)));
        return ta;
    }

    private static JButton buildGenerateAllButton() {
        JButton button = new JButton("Generate All") {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setPaint(new GradientPaint(0, 0, UITheme.ACCENT, getWidth(), 0, UITheme.INDIGO));
                g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 10, 10);
                g2.setColor(new Color(255, 255, 255, 36));
                g2.fillRoundRect(1, 1, getWidth() - 3, Math.max(8, getHeight() / 2), 10, 10);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        button.setFont(UITheme.UI_BOLD.deriveFont(12f));
        button.setForeground(Color.WHITE);
        button.setOpaque(false);
        button.setContentAreaFilled(false);
        button.setBorderPainted(false);
        button.setFocusPainted(false);
        button.setBorder(BorderFactory.createEmptyBorder(8, 16, 8, 16));
        button.setMargin(new Insets(8, 16, 8, 16));
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return button;
    }

    private static JButton buildGenerateOneButton() {
        JButton button = new JButton("Generate") {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(UITheme.ACCENT_SUB);
                g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 9, 9);
                g2.setColor(UITheme.ACCENT);
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 9, 9);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        button.setFont(UITheme.UI_SM.deriveFont(Font.BOLD));
        button.setForeground(UITheme.ACCENT);
        button.setOpaque(false);
        button.setContentAreaFilled(false);
        button.setBorderPainted(false);
        button.setFocusPainted(false);
        button.setBorder(BorderFactory.createEmptyBorder(6, 12, 6, 12));
        button.setMargin(new Insets(6, 12, 6, 12));
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return button;
    }

    List<ParameterValue> collectValues() {
        List<ParameterValue> values = new ArrayList<>();
        for (int i = 0; i < params.size(); i++) {
            values.add(new ParameterValue(params.get(i).typeName(), extractRawValue(inputs.get(i))));
        }
        return values;
    }

    private static String extractRawValue(JComponent comp) {
        if (comp instanceof JCheckBox cb) return String.valueOf(cb.isSelected());
        if (comp instanceof JTextArea ta) return ta.getText().trim();
        if (comp instanceof JTextField tf) return tf.getText().trim();
        return "";
    }
}
