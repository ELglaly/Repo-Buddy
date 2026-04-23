package com.repoinspector.ui;

import com.intellij.util.ui.JBUI;
import com.repoinspector.model.CallChainNode;
import com.repoinspector.model.EndpointInfo;
import com.repoinspector.model.OperationType;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreeCellRenderer;
import java.awt.*;

/**
 * Custom card-style renderer for the call-chain tree.
 */
public class CallChainTreeRenderer extends JPanel implements TreeCellRenderer {

    private final JLabel titleLabel = new JLabel();
    private final JLabel subtitleLabel = new JLabel();
    private final JPanel badgeRow = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0));

    private Color rowBg = UITheme.PANEL_2;
    private Color accent = UITheme.ACCENT;
    private boolean selected;

    public CallChainTreeRenderer() {
        setOpaque(false);
        setLayout(new BorderLayout());
        setBorder(JBUI.Borders.empty(3, 0));

        titleLabel.setFont(UITheme.UI_BOLD);
        subtitleLabel.setFont(UITheme.UI_SM);
        badgeRow.setOpaque(false);

        JPanel textPanel = new JPanel();
        textPanel.setOpaque(false);
        textPanel.setLayout(new BoxLayout(textPanel, BoxLayout.Y_AXIS));
        textPanel.add(titleLabel);
        textPanel.add(Box.createVerticalStrut(JBUI.scale(2)));
        textPanel.add(subtitleLabel);
        textPanel.add(Box.createVerticalStrut(JBUI.scale(4)));
        textPanel.add(badgeRow);

        add(textPanel, BorderLayout.CENTER);
    }

    @Override
    public Component getTreeCellRendererComponent(
            JTree tree, Object value, boolean sel, boolean expanded, boolean leaf, int row, boolean hasFocus
    ) {
        selected = sel;
        badgeRow.removeAll();

        if (!(value instanceof DefaultMutableTreeNode treeNode)) {
            configureFallback(String.valueOf(value));
            return this;
        }

        Object userObject = treeNode.getUserObject();
        if (userObject instanceof EndpointInfo endpoint) {
            configureEndpoint(endpoint);
        } else if (userObject instanceof CallChainNode node) {
            configureNode(node);
        } else {
            configureFallback(String.valueOf(userObject));
        }

        return this;
    }

    private void configureEndpoint(EndpointInfo endpoint) {
        accent = methodColor(endpoint.httpMethod());
        rowBg = selected ? UITheme.SELECTION : UITheme.ACCENT_SUB;

        titleLabel.setText(endpoint.httpMethod() + "  " + endpoint.path());
        titleLabel.setForeground(selected ? Color.WHITE : accent);

        subtitleLabel.setText(endpoint.controllerName() + "." + endpoint.methodSignature());
        subtitleLabel.setForeground(selected ? Color.WHITE : UITheme.INK_2);

        badgeRow.add(createBadge("ENTRYPOINT", selected ? Color.WHITE : accent, selected ? tinted(accent, 70) : UITheme.PANEL));
        badgeRow.add(createBadge(endpoint.controllerName(), selected ? Color.WHITE : UITheme.INK, selected ? tinted(UITheme.INK_2, 55) : UITheme.PANEL));
    }

    private void configureNode(CallChainNode node) {
        if (node.isDynamic()) {
            accent = UITheme.MUTED;
            rowBg = selected ? UITheme.SELECTION : UITheme.PANEL_2;
            titleLabel.setText(node.className() + "." + node.methodSignature());
            titleLabel.setForeground(selected ? Color.WHITE : UITheme.INK_2);
            subtitleLabel.setText("Dynamic bean resolution. Static tracing stops here.");
            subtitleLabel.setForeground(selected ? Color.WHITE : UITheme.MUTED);
            badgeRow.add(createBadge("DYNAMIC", selected ? Color.WHITE : UITheme.MUTED, selected ? tinted(UITheme.MUTED, 55) : UITheme.BORDER_SUB));
            addDepthBadge(node.depth());
            return;
        }

        if (node.isRepository()) {
            accent = operationColor(node.operationType());
            rowBg = selected ? UITheme.SELECTION : operationBg(node.operationType());
            titleLabel.setText(node.className() + "." + node.methodSignature());
            titleLabel.setForeground(selected ? Color.WHITE : accent);
            subtitleLabel.setText(repositorySubtitle(node));
            subtitleLabel.setForeground(selected ? Color.WHITE : UITheme.INK_2);

            badgeRow.add(createBadge("REPOSITORY", selected ? Color.WHITE : accent, selected ? tinted(accent, 70) : UITheme.PANEL));
            badgeRow.add(createBadge(node.operationType().name(), selected ? Color.WHITE : accent, selected ? tinted(accent, 55) : UITheme.PANEL));
            if (!node.entityName().isEmpty()) {
                badgeRow.add(createBadge(node.entityName(), selected ? Color.WHITE : UITheme.INK, selected ? tinted(UITheme.INK_2, 55) : UITheme.PANEL));
            }
        } else {
            accent = UITheme.ACCENT;
            rowBg = selected ? UITheme.SELECTION : UITheme.PANEL_2;
            titleLabel.setText(node.className() + "." + node.methodSignature());
            titleLabel.setForeground(selected ? Color.WHITE : UITheme.INK);
            subtitleLabel.setText("Application call");
            subtitleLabel.setForeground(selected ? Color.WHITE : UITheme.MUTED);
            badgeRow.add(createBadge("METHOD", selected ? Color.WHITE : UITheme.ACCENT, selected ? tinted(UITheme.ACCENT, 70) : UITheme.ACCENT_SUB));
        }

        if (node.isTransactional()) {
            badgeRow.add(createBadge("@Transactional", selected ? Color.WHITE : UITheme.INDIGO, selected ? tinted(UITheme.INDIGO, 70) : UITheme.INDIGO_SUB));
        }
        addDepthBadge(node.depth());
    }

    private void configureFallback(String text) {
        accent = UITheme.ACCENT;
        rowBg = selected ? UITheme.SELECTION : UITheme.PANEL_2;
        titleLabel.setText(text);
        titleLabel.setForeground(selected ? Color.WHITE : UITheme.INK);
        subtitleLabel.setText("");
    }

    private void addDepthBadge(int depth) {
        badgeRow.add(createBadge("Depth " + depth, selected ? Color.WHITE : UITheme.MUTED,
                selected ? tinted(UITheme.MUTED, 55) : UITheme.BORDER_SUB));
    }

    private static String repositorySubtitle(CallChainNode node) {
        if (!node.entityName().isEmpty()) {
            return "Repository access for entity " + node.entityName();
        }
        return "Repository access";
    }

    private static Color operationColor(OperationType type) {
        if (type == OperationType.READ) return UITheme.SUCCESS;
        if (type == OperationType.WRITE) return UITheme.WARNING;
        return UITheme.INDIGO;
    }

    private static Color operationBg(OperationType type) {
        if (type == OperationType.READ) return UITheme.SUCCESS_SUB;
        if (type == OperationType.WRITE) return UITheme.WARNING_SUB;
        return UITheme.INDIGO_SUB;
    }

    private static Color methodColor(String httpMethod) {
        return switch (httpMethod) {
            case "GET" -> UITheme.HTTP_GET;
            case "POST" -> UITheme.HTTP_POST;
            case "PUT" -> UITheme.HTTP_PUT;
            case "DELETE" -> UITheme.HTTP_DELETE;
            case "PATCH" -> UITheme.HTTP_PATCH;
            default -> UITheme.ACCENT;
        };
    }

    private static Color tinted(Color base, int alpha) {
        return new Color(base.getRed(), base.getGreen(), base.getBlue(), alpha);
    }

    private static JComponent createBadge(String text, Color fg, Color bg) {
        return new BadgeChip(text, fg, bg);
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        int stripW = JBUI.scale(4);
        int arc = JBUI.scale(14);
        int x = JBUI.scale(4);
        int y = 0;
        int w = Math.max(0, getWidth() - JBUI.scale(8));
        int h = Math.max(0, getHeight() - 1);

        g2.setColor(rowBg);
        g2.fillRoundRect(x, y, w, h, arc, arc);
        g2.setColor(selected ? Color.WHITE : accent);
        g2.fillRoundRect(x, y, stripW, h, stripW, stripW);
        g2.dispose();
    }

    private static final class BadgeChip extends JComponent {
        private final String text;
        private final Color fg;
        private final Color bg;

        BadgeChip(String text, Color fg, Color bg) {
            this.text = text;
            this.fg = fg;
            this.bg = bg;
            setOpaque(false);
            setFont(UITheme.UI_SM.deriveFont(Font.BOLD));
        }

        @Override
        public Dimension getPreferredSize() {
            FontMetrics fm = getFontMetrics(getFont());
            return new Dimension(fm.stringWidth(text) + JBUI.scale(14), JBUI.scale(18));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            FontMetrics fm = g2.getFontMetrics(getFont());
            int h = getHeight();
            g2.setColor(bg);
            g2.fillRoundRect(0, 0, getWidth(), h, h, h);
            g2.setColor(fg);
            g2.setFont(getFont());
            g2.drawString(text, JBUI.scale(7), (h + fm.getAscent() - fm.getDescent()) / 2);
            g2.dispose();
        }
    }
}
