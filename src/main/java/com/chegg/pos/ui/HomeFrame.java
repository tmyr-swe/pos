package com.chegg.pos.ui;

import com.chegg.pos.app.FontLoader;
import com.chegg.pos.app.Theme;
import com.chegg.pos.model.User;
import com.chegg.pos.service.CatalogService;
import com.chegg.pos.service.CatalogService.Overview;
import com.chegg.pos.service.CatalogService.ProductRow;
import com.chegg.pos.ui.common.RoundedPanel;
import com.chegg.pos.ui.common.UiAssets;
import com.chegg.pos.util.Money;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.RowFilter;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.Image;
import java.util.concurrent.ExecutorService;
import java.util.regex.Pattern;

/**
 * Phase 0 landing screen. It proves the signed-in identity, role routing and
 * live catalog data. Role-specific workspaces replace it in later phases.
 */
@org.springframework.stereotype.Component
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class HomeFrame extends JFrame {

    private static final Logger log = LoggerFactory.getLogger(HomeFrame.class);

    private final UiNavigator navigator;
    private final CatalogService catalogService;
    private final ExecutorService executor;

    private final DefaultTableModel tableModel = new DefaultTableModel(
            new Object[]{"SKU", "Product", "Category", "Price", "Stock"}, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            return switch (columnIndex) {
                case 3 -> Long.class;
                case 4 -> Integer.class;
                default -> String.class;
            };
        }
    };

    private final JTable productTable = new JTable(tableModel);
    private final TableRowSorter<DefaultTableModel> sorter = new TableRowSorter<>(tableModel);
    private final JTextField searchField = new JTextField();
    private final JLabel catalogCountValue = kpiValue();
    private final JLabel categoryCountValue = kpiValue();
    private final JLabel lowStockValue = kpiValue(Theme.WARNING);
    private final JLabel statusLabel = new JLabel("Loading catalog...");

    private User user;

    public HomeFrame(UiNavigator navigator, CatalogService catalogService, ExecutorService dbExecutor) {
        super("Chegg POS");
        this.navigator = navigator;
        this.catalogService = catalogService;
        this.executor = dbExecutor;
    }

    public void init(User user) {
        this.user = user;
        setTitle("Chegg POS - " + workspaceName());
        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(1080, 680));
        setSize(1280, 800);
        setContentPane(buildContent());
        setLocationRelativeTo(null);

        Image icon = UiAssets.logoImage();
        if (icon != null) {
            setIconImage(icon);
        }

        loadCatalog();
    }

    private JPanel buildContent() {
        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(Theme.SURFACE);
        root.add(buildTopBar(), BorderLayout.NORTH);
        root.add(buildBody(), BorderLayout.CENTER);
        return root;
    }

    // --------------------------------------------------------------- top bar

    private JPanel buildTopBar() {
        JPanel bar = new JPanel(new BorderLayout(16, 0));
        bar.setBackground(Theme.PRIMARY);
        bar.setBorder(BorderFactory.createEmptyBorder(10, 18, 10, 18));

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
        left.setOpaque(false);

        RoundedPanel logoTile = new RoundedPanel(new BorderLayout(), Theme.SURFACE_CARD, 10);
        logoTile.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        logoTile.setPreferredSize(new Dimension(74, 46));
        logoTile.add(new JLabel(UiAssets.logoIcon(64, 38), SwingConstants.CENTER),
                BorderLayout.CENTER);
        left.add(logoTile);

        JPanel titles = new JPanel();
        titles.setOpaque(false);
        titles.setLayout(new BoxLayout(titles, BoxLayout.Y_AXIS));

        JLabel appTitle = new JLabel("Chegg POS");
        appTitle.setFont(FontLoader.semiBold(17f));
        appTitle.setForeground(Theme.ON_PRIMARY);

        JLabel workspace = new JLabel(workspaceName() + "  ·  " + user.roleName());
        workspace.setFont(FontLoader.regular(11.5f));
        workspace.setForeground(new Color(0xEF, 0xDF, 0xBB, 205));

        titles.add(appTitle);
        titles.add(workspace);
        left.add(titles);
        bar.add(left, BorderLayout.WEST);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 12, 0));
        right.setOpaque(false);

        JLabel userLabel = new JLabel(user.fullName());
        userLabel.setFont(FontLoader.medium(12.5f));
        userLabel.setForeground(Theme.ON_PRIMARY);
        right.add(userLabel);

        JButton signOut = new JButton("Sign out");
        signOut.setFont(FontLoader.medium(12f));
        signOut.setBackground(Theme.PRIMARY_LIGHT);
        signOut.setForeground(Theme.ON_PRIMARY);
        signOut.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        signOut.addActionListener(e -> signOut());
        right.add(signOut);

        bar.add(right, BorderLayout.EAST);
        return bar;
    }

    // ------------------------------------------------------------------ body

    private JPanel buildBody() {
        JPanel body = new JPanel(new BorderLayout(0, 20));
        body.setBackground(Theme.SURFACE);
        body.setBorder(BorderFactory.createEmptyBorder(26, 34, 26, 34));
        body.add(buildHeader(), BorderLayout.NORTH);

        RoundedPanel tableCard = new RoundedPanel(new BorderLayout(), Theme.SURFACE_CARD, 14);
        tableCard.setBorder(BorderFactory.createEmptyBorder(16, 18, 14, 18));
        tableCard.add(buildTableHeader(), BorderLayout.NORTH);
        tableCard.add(buildTable(), BorderLayout.CENTER);
        tableCard.add(buildTableFooter(), BorderLayout.SOUTH);

        body.add(tableCard, BorderLayout.CENTER);
        return body;
    }

    private JPanel buildHeader() {
        JPanel header = new JPanel();
        header.setOpaque(false);
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));

        JLabel greeting = new JLabel("Habari, " + user.firstName() + ".");
        greeting.setFont(FontLoader.semiBold(28f));
        greeting.setForeground(Theme.TEXT);
        greeting.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel subtitle = new JLabel("You are signed in to the " + workspaceName()
                + " workspace as " + user.roleName() + ".");
        subtitle.setFont(FontLoader.regular(13f));
        subtitle.setForeground(Theme.TEXT_MUTED);
        subtitle.setAlignmentX(Component.LEFT_ALIGNMENT);

        header.add(greeting);
        header.add(Box.createVerticalStrut(4));
        header.add(subtitle);
        header.add(Box.createVerticalStrut(16));

        RoundedPanel notice = new RoundedPanel(new BorderLayout(), Theme.SURFACE_MUTED, 10);
        notice.setBorder(BorderFactory.createEmptyBorder(10, 14, 10, 14));
        JLabel noticeText = new JLabel("<html><b>Phase 0 checkpoint.</b> Sign-in, database bootstrap, "
                + "forced password change and live catalog data are working. The full "
                + workspaceName() + " workspace is the next build phase.</html>");
        noticeText.setFont(FontLoader.regular(12f));
        noticeText.setForeground(Theme.TEXT_MUTED);
        notice.add(noticeText, BorderLayout.CENTER);
        notice.setAlignmentX(Component.LEFT_ALIGNMENT);
        notice.setMaximumSize(new Dimension(Integer.MAX_VALUE, 52));
        header.add(notice);
        header.add(Box.createVerticalStrut(18));

        JPanel kpis = new JPanel(new GridLayout(1, 3, 16, 0));
        kpis.setOpaque(false);
        kpis.add(kpiCard("ACTIVE PRODUCTS", catalogCountValue));
        kpis.add(kpiCard("CATEGORIES", categoryCountValue));
        kpis.add(kpiCard("LOW STOCK", lowStockValue));
        kpis.setAlignmentX(Component.LEFT_ALIGNMENT);
        kpis.setMaximumSize(new Dimension(Integer.MAX_VALUE, 88));
        kpis.setPreferredSize(new Dimension(900, 88));
        header.add(kpis);
        return header;
    }

    private RoundedPanel kpiCard(String label, JLabel value) {
        RoundedPanel card = new RoundedPanel(new BorderLayout(), Theme.SURFACE_CARD, 12);
        card.setBorder(BorderFactory.createEmptyBorder(14, 18, 14, 18));

        JLabel caption = new JLabel(label);
        caption.setFont(FontLoader.semiBold(10f));
        caption.setForeground(Theme.TEXT_MUTED);
        card.add(caption, BorderLayout.NORTH);

        card.add(value, BorderLayout.CENTER);
        return card;
    }

    private static JLabel kpiValue() {
        return kpiValue(Theme.TEXT);
    }

    private static JLabel kpiValue(Color color) {
        JLabel label = new JLabel("-");
        label.setFont(FontLoader.semiBold(28f));
        label.setForeground(color);
        return label;
    }

    private JPanel buildTableHeader() {
        JPanel header = new JPanel(new BorderLayout(12, 0));
        header.setOpaque(false);
        header.setBorder(BorderFactory.createEmptyBorder(0, 2, 12, 2));

        JLabel title = new JLabel("Catalog preview");
        title.setFont(FontLoader.semiBold(17f));
        title.setForeground(Theme.TEXT);
        header.add(title, BorderLayout.WEST);

        searchField.setFont(FontLoader.regular(12.5f));
        searchField.setPreferredSize(new Dimension(310, 34));
        searchField.putClientProperty("JTextField.placeholderText",
                "Search product, SKU or category");
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                applyFilter();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                applyFilter();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                applyFilter();
            }
        });

        JPanel searchWrap = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        searchWrap.setOpaque(false);
        searchWrap.add(searchField);
        header.add(searchWrap, BorderLayout.EAST);
        return header;
    }

    private JScrollPane buildTable() {
        productTable.setRowSorter(sorter);
        productTable.setRowHeight(38);
        productTable.setFont(FontLoader.regular(12.5f));
        productTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        productTable.setShowVerticalLines(false);
        productTable.setShowHorizontalLines(false);
        productTable.setFillsViewportHeight(true);
        productTable.setBackground(Theme.SURFACE_CARD);
        productTable.setForeground(Theme.TEXT);
        productTable.setGridColor(Theme.BORDER);
        productTable.getTableHeader().setFont(FontLoader.semiBold(11f));
        productTable.getTableHeader().setBackground(Theme.PRIMARY);
        productTable.getTableHeader().setForeground(Theme.ON_PRIMARY);
        productTable.getTableHeader().setReorderingAllowed(false);
        productTable.setIntercellSpacing(new Dimension(0, 1));

        productTable.getColumnModel().getColumn(0).setPreferredWidth(110);
        productTable.getColumnModel().getColumn(1).setPreferredWidth(340);
        productTable.getColumnModel().getColumn(2).setPreferredWidth(150);
        productTable.getColumnModel().getColumn(3).setPreferredWidth(130);
        productTable.getColumnModel().getColumn(4).setPreferredWidth(80);

        productTable.getColumnModel().getColumn(3).setCellRenderer(new MoneyRenderer());
        productTable.getColumnModel().getColumn(4).setCellRenderer(new StockRenderer());

        JScrollPane scroll = new JScrollPane(productTable);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setBackground(Theme.SURFACE_CARD);
        return scroll;
    }

    private JPanel buildTableFooter() {
        JPanel footer = new JPanel(new BorderLayout());
        footer.setOpaque(false);
        footer.setBorder(BorderFactory.createEmptyBorder(10, 2, 0, 2));

        statusLabel.setFont(FontLoader.regular(11.5f));
        statusLabel.setForeground(Theme.TEXT_MUTED);
        footer.add(statusLabel, BorderLayout.WEST);

        JLabel hint = new JLabel("Prices in Kenyan shillings · No VAT");
        hint.setFont(FontLoader.regular(11f));
        hint.setForeground(Theme.TEXT_MUTED);
        footer.add(hint, BorderLayout.EAST);
        return footer;
    }

    // ------------------------------------------------------------------ data

    private void loadCatalog() {
        executor.submit(() -> {
            try {
                Overview overview = catalogService.overview();
                SwingUtilities.invokeLater(() -> applyOverview(overview));
            } catch (Exception ex) {
                log.error("Could not load catalog overview", ex);
                SwingUtilities.invokeLater(() -> {
                    statusLabel.setText("Could not load the catalog. Check the logs for details.");
                    statusLabel.setForeground(Theme.DANGER);
                });
            }
        });
    }

    private void applyOverview(Overview overview) {
        tableModel.setRowCount(0);
        for (ProductRow product : overview.products()) {
            tableModel.addRow(new Object[]{
                    product.sku(),
                    product.name(),
                    product.category(),
                    product.priceCents(),
                    product.stock()
            });
        }

        catalogCountValue.setText(Integer.toString(overview.products().size()));
        categoryCountValue.setText(Integer.toString(overview.categoryCount()));
        lowStockValue.setText(Integer.toString(overview.lowStockCount()));
        statusLabel.setForeground(Theme.TEXT_MUTED);
        statusLabel.setText(overview.products().size() + " products  ·  "
                + overview.categoryCount() + " categories  ·  "
                + overview.lowStockCount() + " at or below reorder level");
    }

    private void applyFilter() {
        String text = searchField.getText() == null ? "" : searchField.getText().trim();
        if (text.isEmpty()) {
            sorter.setRowFilter(null);
        } else {
            sorter.setRowFilter(RowFilter.regexFilter("(?i)" + Pattern.quote(text)));
        }
    }

    // ---------------------------------------------------------------- actions

    private void signOut() {
        int choice = JOptionPane.showConfirmDialog(this,
                "Sign out of the " + workspaceName() + " workspace?",
                "Sign out",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.QUESTION_MESSAGE);
        if (choice == JOptionPane.YES_OPTION) {
            navigator.showLogin();
        }
    }

    private String workspaceName() {
        if (user == null) {
            return "Workspace";
        }
        return switch (user.roleCode()) {
            case "SYSTEM_ADMIN" -> "Control Room";
            case "STORE_ADMIN" -> "Operations";
            case "CASHIER" -> "Terminal";
            case "CUSTOMER" -> "Customer Kiosk";
            default -> "Workspace";
        };
    }

    // -------------------------------------------------------------- renderers

    private static final class MoneyRenderer extends DefaultTableCellRenderer {
        private MoneyRenderer() {
            setHorizontalAlignment(SwingConstants.RIGHT);
        }

        @Override
        protected void setValue(Object value) {
            setText(value instanceof Long cents ? Money.format(cents) : "");
        }
    }

    private static final class StockRenderer extends DefaultTableCellRenderer {
        private StockRenderer() {
            setHorizontalAlignment(SwingConstants.RIGHT);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                                                       boolean isSelected, boolean hasFocus,
                                                       int row, int column) {
            Component component = super.getTableCellRendererComponent(
                    table, value, isSelected, hasFocus, row, column);
            if (!isSelected && value instanceof Integer quantity) {
                setForeground(quantity <= 0 ? Theme.DANGER : Theme.TEXT);
            }
            return component;
        }
    }
}
