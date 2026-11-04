package com.chegg.pos.ui.common;

import javax.imageio.ImageIO;
import javax.swing.ImageIcon;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;

/** Loads the supplied Chegg logo without recolouring or removing its background. */
public final class UiAssets {

    private static Image logo;

    private UiAssets() {
    }

    public static synchronized Image logoImage() {
        if (logo == null) {
            try (InputStream in = UiAssets.class.getResourceAsStream("/branding/logo.jpg")) {
                if (in != null) {
                    logo = ImageIO.read(in);
                }
            } catch (IOException ignored) {
                // A missing logo must never stop the till from opening.
            }
        }
        return logo;
    }

    /** Scales the logo to fit inside the given box, preserving its aspect ratio. */
    public static ImageIcon logoIcon(int maxWidth, int maxHeight) {
        Image source = logoImage();
        if (source == null) {
            return new ImageIcon();
        }

        int sourceWidth = source.getWidth(null);
        int sourceHeight = source.getHeight(null);
        if (sourceWidth <= 0 || sourceHeight <= 0) {
            return new ImageIcon(source);
        }

        double scale = Math.min((double) maxWidth / sourceWidth, (double) maxHeight / sourceHeight);
        int width = Math.max(1, (int) Math.round(sourceWidth * scale));
        int height = Math.max(1, (int) Math.round(sourceHeight * scale));

        BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = scaled.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g.drawImage(source, 0, 0, width, height, null);
        } finally {
            g.dispose();
        }
        return new ImageIcon(scaled);
    }
}
