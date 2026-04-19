package com.repoinspector.ui;

import com.intellij.ui.JBColor;
import com.intellij.util.ui.UIUtil;
import com.repoinspector.model.CallChainNode;
import com.repoinspector.model.EndpointInfo;
import com.repoinspector.model.OperationType;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import java.awt.*;

/**
 * Custom cell renderer for the call-chain JTree.
 *
 * <ul>
 *   <li>Root (endpoint) node — bold, blue (theme-aware)</li>
 *   <li>Repository READ leaf  — bold, green (theme-aware)</li>
 *   <li>Repository WRITE leaf — bold, orange (theme-aware)</li>
 *   <li>Dynamic node          — italic, gray (theme-aware)</li>
 *   <li>@Transactional node   — soft indigo background tint (theme-aware)</li>
 * </ul>
 *
 * All colors use {@link JBColor} so the renderer looks correct in both
 * IntelliJ Light and Dark themes.
 */
public class CallChainTreeRenderer extends DefaultTreeCellRenderer {

    // Light variant / Dark variant
    private static final Color COLOR_ENDPOINT     = new JBColor(new Color(0,   0,  139), new Color(106, 176, 245));
    private static final Color COLOR_READ         = new JBColor(new Color(0,  128,   0), new Color( 80, 200, 120));
    private static final Color COLOR_WRITE        = new JBColor(new Color(200,  80,   0), new Color(255, 140,  60));
    private static final Color COLOR_UNKNOWN_REPO = new JBColor(new Color(100,   0, 150), new Color(190, 130, 240));
    private static final Color COLOR_DYNAMIC      = JBColor.GRAY;
    private static final Color BG_TRANSACTIONAL   = new JBColor(new Color(235, 235, 255), new Color( 40,  40,  80));

    @Override
    public Component getTreeCellRendererComponent(
            JTree tree, Object value, boolean sel,
            boolean expanded, boolean leaf, int row, boolean hasFocus
    ) {
        super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus);

        if (!(value instanceof DefaultMutableTreeNode treeNode)) {
            return this;
        }

        Object userObject = treeNode.getUserObject();

        if (userObject instanceof EndpointInfo endpoint) {
            renderEndpoint(endpoint);
        } else if (userObject instanceof CallChainNode node) {
            renderCallChainNode(node, sel, hasFocus);
        }

        return this;
    }

    private void renderEndpoint(EndpointInfo endpoint) {
        setFont(getFont().deriveFont(Font.BOLD));
        setForeground(COLOR_ENDPOINT);
        setText(endpoint.toString());
        setIcon(null);
    }

    private void renderCallChainNode(CallChainNode node, boolean selected, boolean hasFocus) {
        setIcon(null);

        if (node.isDynamic()) {
            setFont(getFont().deriveFont(Font.ITALIC));
            setForeground(COLOR_DYNAMIC);
            setText(node.displayLabel());
            return;
        }

        if (node.isRepository()) {
            setFont(getFont().deriveFont(Font.BOLD));
            if (node.operationType() == OperationType.READ) {
                setForeground(COLOR_READ);
            } else if (node.operationType() == OperationType.WRITE) {
                setForeground(COLOR_WRITE);
            } else {
                setForeground(COLOR_UNKNOWN_REPO);
            }
        } else {
            setFont(getFont().deriveFont(Font.PLAIN));
            // Use theme-aware foreground — never raw Color.BLACK or Color.WHITE
            setForeground(selected
                    ? UIUtil.getTreeSelectionForeground(hasFocus)
                    : UIUtil.getTreeForeground());
        }

        setText(node.displayLabel());

        if (node.isTransactional() && !selected) {
            setOpaque(true);
            setBackground(BG_TRANSACTIONAL);
        } else {
            setOpaque(false);
        }
    }
}
