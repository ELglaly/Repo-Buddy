package com.repoinspector.ui;

import com.intellij.ui.JBColor;
import com.repoinspector.model.EndpointInfo;

import javax.swing.*;
import java.awt.*;
import java.util.Map;

/** Renders {@link EndpointInfo} items in the endpoint combo box with colour-coded HTTP method badges. */
class EndpointComboRenderer extends DefaultListCellRenderer {

    private static final Map<String, Color> HTTP_COLORS = Map.of(
            "GET",    new JBColor(new Color( 80, 200, 120), new Color( 80, 200, 120)),
            "POST",   new JBColor(new Color( 97, 175, 239), new Color( 97, 175, 239)),
            "PUT",    new JBColor(new Color(255, 195,  90), new Color(255, 195,  90)),
            "DELETE", new JBColor(new Color(255,  70,  70), new Color(255,  70,  70)),
            "PATCH",  new JBColor(new Color(206, 145, 120), new Color(206, 145, 120))
    );
    private static final Color HTTP_DEFAULT =
            new JBColor(new Color(160, 160, 160), new Color(120, 120, 120));

    @Override
    public Component getListCellRendererComponent(
            JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {

        super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);

        if (value instanceof EndpointInfo ep) {
            Color methodColor = HTTP_COLORS.getOrDefault(
                    ep.httpMethod().toUpperCase(), HTTP_DEFAULT);
            String hex = String.format("#%02x%02x%02x",
                    methodColor.getRed(), methodColor.getGreen(), methodColor.getBlue());
            setText("<html><font color='" + hex + "'><b>[" + ep.httpMethod() + "]</b></font>"
                    + "&nbsp;" + ep.path() + "</html>");
        }
        return this;
    }
}
