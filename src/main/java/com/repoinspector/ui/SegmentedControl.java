package com.repoinspector.ui;

import javax.swing.*;
import javax.swing.border.Border;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * A segmented button group that shares a single outer border with dividers between
 * segments — matches the {@code .seg} / {@code .seg__btn} CSS pattern from the design spec.
 *
 * <p>Usage:
 * <pre>
 *   SegmentedControl seg = new SegmentedControl("\u229E  Expand", "\u229F  Collapse");
 *   seg.getButton(0).addActionListener(e -> expandAll());
 *   seg.getButton(1).addActionListener(e -> collapseAll());
 *   toolbar.add(seg);
 * </pre>
 */
public final class SegmentedControl extends JPanel {

    private final List<JButton> buttons = new ArrayList<>();

    public SegmentedControl(String... labels) {
        setLayout(new BoxLayout(this, BoxLayout.X_AXIS));
        setOpaque(true);
        setBackground(UITheme.PANEL);
        setBorder(BorderFactory.createLineBorder(UITheme.BORDER_SUB, 1));
        setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
        setPreferredSize(new Dimension(0, 26));   // width resolved by layout

        for (int i = 0; i < labels.length; i++) {
            JButton btn = createSegmentButton(labels[i], i < labels.length - 1);
            buttons.add(btn);
            add(btn);
        }
    }

    /** Returns the button at {@code index} so callers can add {@code ActionListener}s. */
    public JButton getButton(int index) {
        return buttons.get(index);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private static JButton createSegmentButton(String label, boolean hasRightDivider) {
        JButton b = new JButton(label) {
            private boolean hovered;
            {
                addMouseListener(new MouseAdapter() {
                    @Override public void mouseEntered(MouseEvent e) { hovered = true;  repaint(); }
                    @Override public void mouseExited(MouseEvent e)  { hovered = false; repaint(); }
                });
            }
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setColor(hovered ? UITheme.HOVER : UITheme.PANEL);
                g2.fillRect(0, 0, getWidth(), getHeight());
                g2.dispose();
                super.paintComponent(g);
            }
        };

        b.setFont(UITheme.UI);
        b.setOpaque(false);
        b.setContentAreaFilled(false);
        b.setFocusPainted(false);
        b.setBorderPainted(true);
        b.setForeground(UITheme.INK_DIM);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        Border inner   = BorderFactory.createEmptyBorder(0, 10, 0, 10);
        Border divider = hasRightDivider
                ? BorderFactory.createMatteBorder(0, 0, 0, 1, UITheme.BORDER_SUB)
                : BorderFactory.createEmptyBorder();
        b.setBorder(BorderFactory.createCompoundBorder(divider, inner));
        b.setPreferredSize(new Dimension(b.getPreferredSize().width, 26));

        return b;
    }
}
