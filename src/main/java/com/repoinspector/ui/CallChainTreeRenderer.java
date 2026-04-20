package com.repoinspector.ui;

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
 * Colors use UITheme tokens to match the design-spec palette in both themes.
 */
public class CallChainTreeRenderer extends DefaultTreeCellRenderer {

    @Override
    public Component getTreeCellRendererComponent(
            JTree tree, Object value, boolean sel,
            boolean expanded, boolean leaf, int row, boolean hasFocus
    ) {
        super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus);

        if (!(value instanceof DefaultMutableTreeNode treeNode)) return this;

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
        setForeground(UITheme.ACCENT);
        setText(endpoint.toString());
        setIcon(null);
    }

    private void renderCallChainNode(CallChainNode node, boolean selected, boolean hasFocus) {
        setIcon(null);

        if (node.isDynamic()) {
            setFont(getFont().deriveFont(Font.ITALIC));
            setForeground(UITheme.MUTED);
            setText(node.displayLabel());
            return;
        }

        if (node.isRepository()) {
            setFont(getFont().deriveFont(Font.BOLD));
            if (node.operationType() == OperationType.READ) {
                setForeground(UITheme.SUCCESS);
            } else if (node.operationType() == OperationType.WRITE) {
                setForeground(UITheme.WARNING);
            } else {
                setForeground(UITheme.INDIGO);
            }
        } else {
            setFont(getFont().deriveFont(Font.PLAIN));
            setForeground(selected
                    ? UIUtil.getTreeSelectionForeground(hasFocus)
                    : UIUtil.getTreeForeground());
        }

        setText(node.displayLabel());

        if (node.isTransactional() && !selected) {
            setOpaque(true);
            setBackground(UITheme.INDIGO_SUB);
        } else {
            setOpaque(false);
        }
    }
}
