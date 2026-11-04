package com.chegg.pos.ui.common;

import javax.swing.JPanel;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.LayoutManager;
import java.awt.RenderingHints;

/** A flat card with the app's 10px corner radius. */
public class RoundedPanel extends JPanel {

    private final Color fillColor;
    private final int radius;

    public RoundedPanel(LayoutManager layout, Color fillColor, int radius) {
        super(layout);
        this.fillColor = fillColor;
        this.radius = radius;
        setOpaque(false);
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(fillColor);
            g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, radius, radius);
        } finally {
            g2.dispose();
        }
        super.paintComponent(g);
    }
}
