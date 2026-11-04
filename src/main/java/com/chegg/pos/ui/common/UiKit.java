package com.chegg.pos.ui.common;

import com.chegg.pos.app.FontLoader;
import com.chegg.pos.app.Theme;
import com.chegg.pos.util.Money;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableColumnModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.LayoutManager;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Small shared UI vocabulary so every workspace feels like the same product. */
public final class UiKit {

    public static final ZoneId EAT = ZoneId.of("Africa/Nairobi");
    public static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("dd MMM HH:mm").withZone(EAT);
    public static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("dd MMM yyyy").withZone(EAT);

    private UiKit() {
    }

    public static JPanel panel(LayoutManager layout) {
        JPanel panel = new JPanel(layout);
        panel.setOpaque(false);
        return panel;
    }

    public static RoundedPanel card(LayoutManager layout) {
        RoundedPanel card = new RoundedPanel(layout, Theme.SURFACE_CARD, 14);
        card.setBorder(BorderFactory.createEmptyBorder(16, 18, 16, 18));
        return card;
    }

    public static JLabel label(String text, float size, Color color, boolean bold) {
        JLabel label = new JLabel(text);
        label.setFont(bold ? FontLoader.semiBold(size) : FontLoader.regular(size));
        label.setForeground(color);
        return label;
    }

    public static JLabel title(String text) {
        return label(text, 24f, Theme.TEXT, true);
    }

    public static JLabel section(String text) {
        return label(text, 17f, Theme.TEXT, true);
    }

    public static JLabel caption(String text) {
        return label(text, 11f, Theme.TEXT_MUTED, false);
    }

    public static JLabel badge(String text, Color color) {
        JLabel label = label(text, 10f, Color.WHITE, true);
        label.setOpaque(true);
        label.setBackground(color);
        label.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
        return label;
    }

    public static JButton primary(String text) {
        JButton button = new JButton(text);
        styleButton(button, Theme.PRIMARY, Theme.ON_PRIMARY);
        return button;
    }

    public static JButton secondary(String text) {
        JButton button = new JButton(text);
        styleButton(button, Theme.SURFACE_CARD, Theme.TEXT);
        button.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.BORDER),
                BorderFactory.createEmptyBorder(6, 14, 6, 14)));
        return button;
    }

    public static JButton danger(String text) {
        JButton button = new JButton(text);
        styleButton(button, Theme.DANGER, Color.WHITE);
        return button;
    }

    private static void styleButton(JButton button, Color background, Color foreground) {
        button.setFont(FontLoader.medium(13f));
        button.setBackground(background);
        button.setForeground(foreground);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.setFocusPainted(false);
        button.setBorder(BorderFactory.createEmptyBorder(8, 16, 8, 16));
    }

    public static JTextField field(String placeholder) {
        JTextField field = new JTextField();
        field.setFont(FontLoader.regular(13f));
        field.putClientProperty("JTextField.placeholderText", placeholder);
        field.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.BORDER),
                BorderFactory.createEmptyBorder(5, 9, 5, 9)));
        return field;
    }

    public static JScrollPane scroll(Component component) {
        JScrollPane scroll = new JScrollPane(component);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setBackground(Theme.SURFACE_CARD);
        return scroll;
    }

    public static JTable table(javax.swing.table.TableModel model) {
        JTable table = new JTable(model) {
            @Override
            public boolean getScrollableTracksViewportWidth() {
                return getPreferredSize().width < getParent().getWidth();
            }
        };
        table.setFont(FontLoader.regular(12.5f));
        table.setRowHeight(36);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setShowVerticalLines(false);
        table.setShowHorizontalLines(false);
        table.setGridColor(Theme.BORDER);
        table.setIntercellSpacing(new Dimension(0, 1));
        table.setFillsViewportHeight(true);
        table.setBackground(Theme.SURFACE_CARD);
        table.setForeground(Theme.TEXT);
        table.getTableHeader().setFont(FontLoader.semiBold(10.5f));
        table.getTableHeader().setBackground(Theme.PRIMARY);
        table.getTableHeader().setForeground(Theme.ON_PRIMARY);
        table.getTableHeader().setReorderingAllowed(false);
        table.getTableHeader().setPreferredSize(new Dimension(0, 34));
        return table;
    }

    public static TableCellRenderer moneyRenderer() {
        return new MoneyRenderer(Theme.TEXT);
    }

    public static TableCellRenderer moneyRenderer(Color color) {
        return new MoneyRenderer(color);
    }

    public static TableCellRenderer rightRenderer() {
        DefaultTableCellRenderer renderer = new DefaultTableCellRenderer();
        renderer.setHorizontalAlignment(SwingConstants.RIGHT);
        return renderer;
    }

    public static void columns(JTable table, int... widths) {
        TableColumnModel columns = table.getColumnModel();
        for (int i = 0; i < widths.length && i < columns.getColumnCount(); i++) {
            columns.getColumn(i).setPreferredWidth(widths[i]);
        }
    }

    public static JPanel actions(Component... components) {
        JPanel panel = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 8, 0));
        panel.setOpaque(false);
        for (Component component : components) {
            panel.add(component);
        }
        return panel;
    }

    public static JPanel header(String title, String subtitle, Component right) {
        JPanel panel = new JPanel(new BorderLayout(16, 0));
        panel.setOpaque(false);
        JPanel text = new JPanel();
        text.setOpaque(false);
        text.setLayout(new javax.swing.BoxLayout(text, javax.swing.BoxLayout.Y_AXIS));
        text.add(title(title));
        if (subtitle != null) {
            text.add(javax.swing.Box.createVerticalStrut(3));
            text.add(caption(subtitle));
        }
        panel.add(text, BorderLayout.WEST);
        if (right != null) {
            JPanel wrap = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 0, 0));
            wrap.setOpaque(false);
            wrap.add(right);
            panel.add(wrap, BorderLayout.EAST);
        }
        return panel;
    }

    public static void error(Component parent, String message) {
        JOptionPane.showMessageDialog(parent, message, "Chegg POS", JOptionPane.ERROR_MESSAGE);
    }

    public static void info(Component parent, String message) {
        JOptionPane.showMessageDialog(parent, message, "Chegg POS", JOptionPane.INFORMATION_MESSAGE);
    }

    public static boolean confirm(Component parent, String message) {
        return JOptionPane.showConfirmDialog(parent, message, "Please confirm",
                JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE) == JOptionPane.YES_OPTION;
    }

    public static String input(Component parent, String message, String initial) {
        return JOptionPane.showInputDialog(parent, message, initial);
    }

    /**
     * Runs a service call on the database executor and marshals the result to
     * the EDT. Failures are shown in a dialog unless the caller handles them.
     */
    public static <T> void async(Component parent, ExecutorService executor,
                                 Supplier<T> work, Consumer<T> onSuccess) {
        async(parent, executor, work, onSuccess, error -> {
            String message = error.getMessage() == null
                    ? "Something went wrong. Please try again." : error.getMessage();
            error(parent, message);
        });
    }

    public static <T> void async(Component parent, ExecutorService executor,
                                 Supplier<T> work, Consumer<T> onSuccess,
                                 Consumer<Throwable> onFailure) {
        executor.submit(() -> {
            try {
                T value = work.get();
                SwingUtilities.invokeLater(() -> onSuccess.accept(value));
            } catch (Throwable error) {
                SwingUtilities.invokeLater(() -> onFailure.accept(error));
            }
        });
    }

    public static String time(Instant instant) {
        return instant == null ? "" : DATE_TIME.format(instant);
    }

    public static String date(Instant instant) {
        return instant == null ? "" : DATE.format(instant);
    }

    public static JLabel kpi(String label, Color color) {
        JLabel value = label("-", 26f, color, true);
        value.setName(label);
        return value;
    }

    public static RoundedPanel kpiCard(String label, JLabel value) {
        RoundedPanel card = new RoundedPanel(new BorderLayout(), Theme.SURFACE_CARD, 12);
        card.setBorder(BorderFactory.createEmptyBorder(12, 16, 12, 16));
        card.add(label(label, 10f, Theme.TEXT_MUTED, true), BorderLayout.NORTH);
        card.add(value, BorderLayout.CENTER);
        return card;
    }

    public static void setFontRecursive(Component component, Font font) {
        component.setFont(font);
        if (component instanceof java.awt.Container container) {
            for (Component child : container.getComponents()) {
                setFontRecursive(child, font);
            }
        }
    }

    private static final class MoneyRenderer extends DefaultTableCellRenderer {
        private final Color normal;

        private MoneyRenderer(Color normal) {
            this.normal = normal;
            setHorizontalAlignment(SwingConstants.RIGHT);
        }

        @Override
        protected void setValue(Object value) {
            setText(value instanceof Long cents ? Money.format(cents) : "");
            setForeground(normal);
        }
    }
}
