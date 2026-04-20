package com.repoinspector.ui;

import com.repoinspector.model.EndpointInfo;

import javax.swing.*;
import java.awt.*;

/** Renders {@link EndpointInfo} items in the endpoint combo box with colour-coded HTTP method badges. */
class EndpointComboRenderer extends DefaultListCellRenderer {

    @Override
    public Component getListCellRendererComponent(
            JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {

        super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);

        if (value instanceof EndpointInfo ep) {
            Color methodColor = switch (ep.httpMethod().toUpperCase()) {
                case "GET"    -> UITheme.SUCCESS;
                case "POST"   -> UITheme.ACCENT;
                case "PUT"    -> UITheme.GOLD;
                case "DELETE" -> UITheme.DANGER;
                case "PATCH"  -> UITheme.WARNING;
                default       -> UITheme.MUTED;
            };
            String hex = UITheme.toHex(methodColor);
            setText("<html><font color='" + hex + "'><b>[" + ep.httpMethod() + "]</b></font>"
                    + "&nbsp;" + ep.path() + "</html>");
        }
        return this;
    }
}
