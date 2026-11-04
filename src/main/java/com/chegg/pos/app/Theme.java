package com.chegg.pos.app;

import com.formdev.flatlaf.FlatLightLaf;

import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Font;

/** Single source of truth for the Chegg palette and FlatLaf configuration. */
public final class Theme {

    public static final Color PRIMARY = new Color(0x722F37);
    public static final Color PRIMARY_DARK = new Color(0x5A252C);
    public static final Color PRIMARY_LIGHT = new Color(0x8A3B44);

    public static final Color SURFACE = new Color(0xEFDFBB);
    public static final Color SURFACE_CARD = new Color(0xF8F0DD);
    public static final Color SURFACE_MUTED = new Color(0xE4D0A5);
    public static final Color SURFACE_SELECTED = new Color(0xE9C9C3);
    public static final Color SURFACE_LOGO = new Color(0xF2F7FA);

    public static final Color BORDER = new Color(0xD6C29A);
    public static final Color TEXT = new Color(0x2B1B1D);
    public static final Color TEXT_MUTED = new Color(0x5C4A44);
    public static final Color ON_PRIMARY = new Color(0xFFFFFF);

    public static final Color SUCCESS = new Color(0x2F6B3C);
    public static final Color WARNING = new Color(0x8A5A00);
    public static final Color DANGER = new Color(0x9B1C1C);
    public static final Color ACCENT_GOLD = new Color(0xC9A227);

    private Theme() {
    }

    public static void install() {
        FlatLightLaf.setup();

        UIManager.put("defaultFont", FontLoader.regular(13f));

        UIManager.put("Component.arc", 10);
        UIManager.put("Button.arc", 10);
        UIManager.put("TextComponent.arc", 8);
        UIManager.put("Component.focusWidth", 1);
        UIManager.put("Component.focusColor", PRIMARY);
        UIManager.put("Component.innerFocusWidth", 1);
        UIManager.put("ScrollBar.width", 12);

        UIManager.put("Panel.background", SURFACE);
        UIManager.put("Label.foreground", TEXT);
        UIManager.put("Component.borderColor", BORDER);
        UIManager.put("Component.disabledBorderColor", SURFACE_MUTED);

        UIManager.put("Button.background", SURFACE_CARD);
        UIManager.put("Button.foreground", TEXT);
        UIManager.put("Button.hoverBackground", SURFACE_MUTED);
        UIManager.put("Button.pressedBackground", BORDER);
        UIManager.put("Button.default.background", PRIMARY);
        UIManager.put("Button.default.foreground", ON_PRIMARY);
        UIManager.put("Button.default.hoverBackground", PRIMARY_LIGHT);
        UIManager.put("Button.default.pressedBackground", PRIMARY_DARK);
        UIManager.put("Button.default.focusColor", PRIMARY_DARK);

        UIManager.put("TextField.background", SURFACE_CARD);
        UIManager.put("TextField.foreground", TEXT);
        UIManager.put("TextField.focusedBackground", Color.WHITE);
        UIManager.put("TextField.focusColor", PRIMARY);
        UIManager.put("PasswordField.background", SURFACE_CARD);
        UIManager.put("PasswordField.foreground", TEXT);

        UIManager.put("Table.background", SURFACE_CARD);
        UIManager.put("Table.foreground", TEXT);
        UIManager.put("Table.alternateRowColor", SURFACE_MUTED);
        UIManager.put("Table.selectionBackground", SURFACE_SELECTED);
        UIManager.put("Table.selectionForeground", TEXT);
        UIManager.put("Table.gridColor", BORDER);
        UIManager.put("TableHeader.background", PRIMARY);
        UIManager.put("TableHeader.foreground", ON_PRIMARY);
        UIManager.put("TableHeader.separatorColor", PRIMARY_DARK);
        UIManager.put("TableHeader.height", 38);
        UIManager.put("Table.rowHeight", 40);

        UIManager.put("ScrollPane.background", SURFACE);
        UIManager.put("Viewport.background", SURFACE_CARD);

        UIManager.put("OptionPane.background", SURFACE);
        UIManager.put("OptionPane.messageForeground", TEXT);
        UIManager.put("OptionPane.messageFont", FontLoader.regular(13f));
        UIManager.put("OptionPane.buttonFont", FontLoader.medium(13f));

        UIManager.put("ToolTip.background", PRIMARY_DARK);
        UIManager.put("ToolTip.foreground", ON_PRIMARY);
    }

    public static Font font(float size) {
        return FontLoader.regular(size);
    }

    public static Font boldFont(float size) {
        return FontLoader.bold(size);
    }
}
