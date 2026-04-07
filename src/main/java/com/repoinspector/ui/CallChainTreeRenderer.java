package com.repoinspector.ui;

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
 *   <li>Root (endpoint) node — bold, dark blue</li>
 *   <li>Repository READ leaf  — bold, green foreground</li>
 *   <li>Repository WRITE leaf — bold, orange foreground</li>
 *   <li>Dynamic node          — italic, gray foreground</li>
 *   <li>@Transactional node   — slightly indigo background tint</li>
 * </ul>
 */
public class CallChainTreeRenderer extends DefaultTreeCellRenderer {

    private static final Color COLOR_ENDPOINT    = new Color(0, 0, 139);   // dark blue
    private static final Color COLOR_READ        = new Color(0, 128, 0);   // green
    private static final Color COLOR_WRITE       = new Color(200, 80, 0);  // orange
    private static final Color COLOR_UNKNOWN_REPO= new Color(100, 0, 150); // purple
    private static final Color COLOR_DYNAMIC     = Color.GRAY;
    private static final Color BG_TRANSACTIONAL  = new Color(235, 235, 255); // soft indigo

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
            renderCallChainNode(node, sel);
        }

        return this;
    }

    private void renderEndpoint(EndpointInfo endpoint) {
        Font bold = getFont().deriveFont(Font.BOLD);
        setFont(bold);
        setForeground(COLOR_ENDPOINT);
        setText(endpoint.toString());
        setIcon(null);
    }

    private void renderCallChainNode(CallChainNode node, boolean selected) {
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
            setForeground(selected ? Color.WHITE : Color.BLACK);
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
