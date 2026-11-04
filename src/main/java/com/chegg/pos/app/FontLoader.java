package com.chegg.pos.app;

import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Loads the bundled JetBrains Mono TTF files. TTF (not the CFF-flavoured OTF)
 * is used because PDFBox cannot embed CFF outlines.
 */
public final class FontLoader {

    private static final String FAMILY = "JetBrains Mono";
    private static final Set<String> REGISTERED = new HashSet<>();
    private static final Map<String, Font> BASE = new HashMap<>();

    private FontLoader() {
    }

    public static Font regular(float size) {
        return load("Regular", Font.PLAIN, size);
    }

    public static Font medium(float size) {
        return load("Medium", Font.PLAIN, size);
    }

    public static Font semiBold(float size) {
        return load("SemiBold", Font.PLAIN, size);
    }

    public static Font bold(float size) {
        return load("Bold", Font.BOLD, size);
    }

    public static Font load(String weight, int style, float size) {
        Font base = base(weight);
        return base.deriveFont(style, size);
    }

    private static synchronized Font base(String weight) {
        Font cached = BASE.get(weight);
        if (cached != null) {
            return cached;
        }

        String resource = "/fonts/JetBrainsMono-" + weight + ".ttf";
        try (InputStream in = FontLoader.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Missing font resource " + resource);
            }
            Font font = Font.createFont(Font.TRUETYPE_FONT, in);
            if (REGISTERED.add(weight)) {
                GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(font);
            }
            BASE.put(weight, font);
            return font;
        } catch (Exception e) {
            Font fallback = new Font(Font.MONOSPACED, Font.PLAIN, 13);
            BASE.put(weight, fallback);
            return fallback;
        }
    }

    public static String family() {
        return FAMILY;
    }
}
