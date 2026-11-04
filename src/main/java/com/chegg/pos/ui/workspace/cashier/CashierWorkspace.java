package com.chegg.pos.ui.workspace.cashier;

import com.chegg.pos.app.FontLoader;
import com.chegg.pos.app.Theme;
import com.chegg.pos.model.KioskOrder;
import com.chegg.pos.model.SaleItemRow;
import com.chegg.pos.model.SaleSummary;
import com.chegg.pos.model.Shift;
import com.chegg.pos.model.User;
import com.chegg.pos.model.ZReport;
import com.chegg.pos.model.enums.PaymentMethod;
import com.chegg.pos.model.enums.SaleChannel;
import com.chegg.pos.service.CartService;
import com.chegg.pos.service.CatalogService;
import com.chegg.pos.service.CheckoutCommand;
import com.chegg.pos.service.CheckoutResult;
import com.chegg.pos.service.KioskOrderService;
import com.chegg.pos.service.ReceiptService;
import com.chegg.pos.service.SaleService;
import com.chegg.pos.service.SessionService;
import com.chegg.pos.service.SettingsService;
import com.chegg.pos.service.ShiftService;
import com.chegg.pos.service.ZReportService;
import com.chegg.pos.service.calc.CartLine;
import com.chegg.pos.service.calc.CartSnapshot;
import com.chegg.pos.service.calc.Discount;
import com.chegg.pos.service.calc.DiscountType;
import com.chegg.pos.service.calc.TenderCalculator;
import com.chegg.pos.service.calc.TenderPayment;
import com.chegg.pos.service.calc.TenderState;
import com.chegg.pos.ui.common.RoundedPanel;
import com.chegg.pos.ui.common.UiAssets;
import com.chegg.pos.ui.common.UiKit;
import com.chegg.pos.util.Money;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.RowFilter;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.regex.Pattern;

/** The one-screen cashier terminal: catalog, cart, tender, shift and kiosk queue. */
public class CashierWorkspace {

    private static final DateTimeFormatter CLOCK = DateTimeFormatter
            .ofPattern("HH:mm").withZone(ZoneId.of("Africa/Nairobi"));

    private final CatalogService catalog;
    private final CartService cart;
    private final SaleService sales;
    private final ShiftService shifts;
    private final KioskOrderService kioskOrders;
    private final ReceiptService receipts;
    private final ZReportService zReports;
    private final SettingsService settings;
    private final SessionService sessions;
    private final ExecutorService executor;
    private final User user;
    private final JFrame owner;
    private final Runnable signOut;

    private final JPanel root = new JPanel(new BorderLayout());
    private final JLabel shiftPill = new JLabel("Shift closed");
    private final JLabel clockLabel = UiKit.label("", 12f, Theme.ON_PRIMARY, true);
    private final JButton queueButton = UiKit.secondary("Kiosk Orders (0)");
    private final JTextField search = UiKit.field("Scan or type a product name / SKU");
    private final JComboBox<String> categoryBox = new JComboBox<>();
    private final DefaultTableModel productModel = hiddenIdModel(
            new String[]{"ID", "Product", "Category", "Price", "Stock"});
    private final JTable productTable;
    private final TableRowSorter<DefaultTableModel> productSorter;
    private final DefaultTableModel cartModel = hiddenIdModel(
            new String[]{"ID", "Item", "Qty", "Unit", "Line total"});
    private final JTable cartTable;
    private final JLabel subtotalValue = totalValue();
    private final JLabel discountValue = totalValue();
    private final JLabel taxValue = totalValue();
    private final JLabel totalValue = grandTotal();
    private final JLabel statusLabel = UiKit.caption(" ");

    private Shift shift;
    private CartSnapshot snapshot = CartSnapshot.empty();
    private boolean refreshingProducts;
    private Timer clockTimer;
    private Timer queueTimer;

    public CashierWorkspace(org.springframework.context.ApplicationContext context,
                            User user, JFrame owner, Runnable signOut) {
        this.catalog = context.getBean(CatalogService.class);
        this.cart = context.getBean(CartService.class);
        this.sales = context.getBean(SaleService.class);
        this.shifts = context.getBean(ShiftService.class);
        this.kioskOrders = context.getBean(KioskOrderService.class);
        this.receipts = context.getBean(ReceiptService.class);
        this.zReports = context.getBean(ZReportService.class);
        this.settings = context.getBean(SettingsService.class);
        this.sessions = context.getBean(SessionService.class);
        this.executor = context.getBean(ExecutorService.class);
        this.user = user;
        this.owner = owner;
        this.signOut = signOut;

        this.productTable = UiKit.table(productModel);
        this.productSorter = new TableRowSorter<>(productModel);
        this.productTable.setRowSorter(productSorter);
        this.cartTable = UiKit.table(cartModel);

        root.setBackground(Theme.SURFACE);
        root.add(buildTopBar(), BorderLayout.NORTH);
        root.add(buildBody(), BorderLayout.CENTER);

        cart.addListener(this::renderCart);
        installKeys();
        loadShift(false);
        loadProducts();
        updateClock();
        clockTimer = new Timer(15_000, e -> {
            updateClock();
            refreshQueueCount();
        });
        clockTimer.start();
        queueTimer = new Timer(2_000, e -> refreshQueueCount());
        queueTimer.start();
        owner.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosed(java.awt.event.WindowEvent e) {
                stopTimers();
            }
        });
        refreshQueueCount();
    }

    private void stopTimers() {
        if (clockTimer != null) {
            clockTimer.stop();
        }
        if (queueTimer != null) {
            queueTimer.stop();
        }
    }

    public JPanel root() {
        return root;
    }

    // -------------------------------------------------------------------- top bar

    private JPanel buildTopBar() {
        JPanel bar = new JPanel(new BorderLayout(14, 0));
        bar.setBackground(Theme.PRIMARY);
        bar.setBorder(BorderFactory.createEmptyBorder(8, 14, 8, 14));

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        left.setOpaque(false);
        RoundedPanel logoTile = new RoundedPanel(new BorderLayout(), Theme.SURFACE_LOGO, 8);
        logoTile.setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 6));
        logoTile.setPreferredSize(new Dimension(66, 40));
        logoTile.add(new JLabel(UiAssets.logoIcon(56, 32), JLabel.CENTER), BorderLayout.CENTER);
        left.add(logoTile);
        left.add(UiKit.label("Terminal", 16f, Theme.ON_PRIMARY, true));

        shiftPill.setOpaque(true);
        shiftPill.setFont(FontLoader.medium(11f));
        shiftPill.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));
        shiftPill.setBackground(Theme.SURFACE_MUTED);
        shiftPill.setForeground(Theme.TEXT);
        shiftPill.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        shiftPill.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                showMyShift();
            }
        });
        left.add(shiftPill);

        JLabel cashier = UiKit.label(user.fullName(), 12f, Theme.ON_PRIMARY, true);
        left.add(cashier);
        bar.add(left, BorderLayout.WEST);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        right.setOpaque(false);
        queueButton.setFont(FontLoader.medium(12f));
        queueButton.addActionListener(e -> showKioskQueue());
        JButton myShift = UiKit.secondary("My shift");
        myShift.addActionListener(e -> showMyShift());
        JButton mySales = UiKit.secondary("My sales");
        mySales.addActionListener(e -> showMySales());
        JButton signOutButton = UiKit.secondary("Sign out");
        signOutButton.addActionListener(e -> {
            if (UiKit.confirm(owner, "Sign out of the terminal?")) {
                signOut.run();
            }
        });
        right.add(clockLabel);
        right.add(queueButton);
        right.add(myShift);
        right.add(mySales);
        right.add(signOutButton);
        bar.add(right, BorderLayout.EAST);
        return bar;
    }

    // ----------------------------------------------------------------------- body

    private JPanel buildBody() {
        JPanel body = new JPanel(new BorderLayout(14, 0));
        body.setBackground(Theme.SURFACE);
        body.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));
        body.add(buildCatalog(), BorderLayout.CENTER);
        body.add(buildCart(), BorderLayout.EAST);
        return body;
    }

    private JPanel buildCatalog() {
        JPanel panel = new JPanel(new BorderLayout(0, 10));
        panel.setOpaque(false);

        JPanel bar = new JPanel(new BorderLayout(10, 0));
        bar.setOpaque(false);
        search.setPreferredSize(new Dimension(360, 38));
        search.setFont(FontLoader.regular(14f));
        search.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                filterProducts();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                filterProducts();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                filterProducts();
            }
        });
        search.addActionListener(e -> addSelectedProduct());
        bar.add(search, BorderLayout.WEST);

        categoryBox.setFont(FontLoader.regular(12f));
        categoryBox.addActionListener(e -> filterProducts());
        JPanel categoryWrap = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        categoryWrap.setOpaque(false);
        categoryWrap.add(categoryBox);
        bar.add(categoryWrap, BorderLayout.CENTER);

        JButton add = UiKit.primary("Add to cart");
        add.addActionListener(e -> addSelectedProduct());
        bar.add(add, BorderLayout.EAST);
        panel.add(bar, BorderLayout.NORTH);

        productTable.removeColumn(productTable.getColumnModel().getColumn(0));
        UiKit.columns(productTable, 0, 300, 130, 100, 60);
        productTable.getColumnModel().getColumn(2).setCellRenderer(UiKit.moneyRenderer());
        productTable.getColumnModel().getColumn(3).setCellRenderer(stockRenderer());
        productTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        productTable.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) {
                    addSelectedProduct();
                }
            }
        });
        productTable.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "add");
        productTable.getActionMap().put("add", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                addSelectedProduct();
            }
        });
        panel.add(UiKit.scroll(productTable), BorderLayout.CENTER);

        JPanel footer = new JPanel(new BorderLayout());
        footer.setOpaque(false);
        statusLabel.setFont(FontLoader.regular(11f));
        footer.add(statusLabel, BorderLayout.WEST);
        footer.add(UiKit.caption("Double-click a product or press Enter · F4 tender · F9 kiosk queue · Esc clear"),
                BorderLayout.EAST);
        panel.add(footer, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel buildCart() {
        RoundedPanel panel = UiKit.card(new BorderLayout(0, 10));
        panel.setPreferredSize(new Dimension(440, 0));

        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.add(UiKit.section("Current sale"), BorderLayout.WEST);
        JButton clear = UiKit.secondary("Clear");
        clear.addActionListener(e -> {
            if (!cart.isEmpty() && UiKit.confirm(owner, "Clear the current sale?")) {
                cart.clear();
                focusSearch();
            }
        });
        header.add(clear, BorderLayout.EAST);
        panel.add(header, BorderLayout.NORTH);

        cartTable.removeColumn(cartTable.getColumnModel().getColumn(0));
        UiKit.columns(cartTable, 0, 210, 50, 90, 100);
        cartTable.getColumnModel().getColumn(2).setCellRenderer(UiKit.moneyRenderer());
        cartTable.getColumnModel().getColumn(3).setCellRenderer(UiKit.moneyRenderer());
        cartTable.getSelectionModel().addListSelectionListener(e -> { });
        panel.add(UiKit.scroll(cartTable), BorderLayout.CENTER);

        JPanel bottom = new JPanel(new BorderLayout(0, 10));
        bottom.setOpaque(false);

        JPanel lineButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        lineButtons.setOpaque(false);
        JButton plus = UiKit.secondary("+");
        JButton minus = UiKit.secondary("−");
        JButton remove = UiKit.secondary("Remove");
        JButton lineDiscount = UiKit.secondary("Line discount");
        plus.addActionListener(e -> changeQuantity(1));
        minus.addActionListener(e -> changeQuantity(-1));
        remove.addActionListener(e -> {
            long id = selectedId(cartTable, cartModel);
            if (id > 0) {
                cart.remove(id);
                focusSearch();
            }
        });
        lineDiscount.addActionListener(e -> lineDiscount());
        lineButtons.add(plus);
        lineButtons.add(minus);
        lineButtons.add(remove);
        if (sessions.has("SALE_DISCOUNT")) {
            lineButtons.add(lineDiscount);
        }
        bottom.add(lineButtons, BorderLayout.NORTH);

        JPanel totals = new JPanel(new GridBagLayout());
        totals.setOpaque(false);
        int row = 0;
        row = totalRow(totals, row, "Subtotal", subtotalValue, false);
        row = totalRow(totals, row, "Discounts", discountValue, false);
        row = totalRow(totals, row, "Tax", taxValue, false);
        totalRow(totals, row, "TOTAL", totalValue, true);
        bottom.add(totals, BorderLayout.CENTER);

        JPanel actions = new JPanel(new GridLayout(1, 2, 8, 0));
        actions.setOpaque(false);
        JButton discount = UiKit.secondary("Cart discount");
        discount.setEnabled(sessions.has("SALE_DISCOUNT"));
        discount.addActionListener(e -> cartDiscount());
        JButton tender = UiKit.primary("TENDER  (F4)");
        tender.setFont(FontLoader.semiBold(15f));
        tender.setPreferredSize(new Dimension(0, 54));
        tender.addActionListener(e -> tenders());
        actions.add(discount);
        actions.add(tender);
        bottom.add(actions, BorderLayout.SOUTH);

        panel.add(bottom, BorderLayout.SOUTH);
        return panel;
    }

    private int totalRow(JPanel panel, int row, String label, JLabel value, boolean grand) {
        GridBagConstraints left = new GridBagConstraints();
        left.gridx = 0;
        left.gridy = row;
        left.anchor = GridBagConstraints.WEST;
        left.weightx = 1;
        left.insets = new Insets(1, 2, 1, 8);
        panel.add(UiKit.label(label, grand ? 14f : 12f, grand ? Theme.TEXT : Theme.TEXT_MUTED, grand), left);

        GridBagConstraints right = new GridBagConstraints();
        right.gridx = 1;
        right.gridy = row;
        right.anchor = GridBagConstraints.EAST;
        right.insets = new Insets(1, 8, 1, 2);
        panel.add(value, right);
        return row + 1;
    }

    private static JLabel totalValue() {
        return UiKit.label("KSh 0.00", 13f, Theme.TEXT, false);
    }

    private static JLabel grandTotal() {
        JLabel label = UiKit.label("KSh 0.00", 32f, Theme.PRIMARY, true);
        return label;
    }

    // ----------------------------------------------------------------------- data

    private void loadProducts() {
        if (refreshingProducts) {
            return;
        }
        refreshingProducts = true;
        UiKit.async(owner, executor, () -> {
            List<CatalogService.ProductRow> products = catalog.listProducts(true);
            List<CatalogService.CategoryRow> categories = catalog.categories();
            return new Object[]{products, categories};
        }, data -> {
            refreshingProducts = false;
            @SuppressWarnings("unchecked")
            List<CatalogService.ProductRow> products = (List<CatalogService.ProductRow>) data[0];
            @SuppressWarnings("unchecked")
            List<CatalogService.CategoryRow> categories = (List<CatalogService.CategoryRow>) data[1];
            productModel.setRowCount(0);
            for (CatalogService.ProductRow product : products) {
                productModel.addRow(new Object[]{product.id(), product.name(), product.category(),
                        product.priceCents(), product.stock()});
            }
            String selected = (String) categoryBox.getSelectedItem();
            categoryBox.removeAllItems();
            categoryBox.addItem("All categories");
            for (CatalogService.CategoryRow category : categories) {
                categoryBox.addItem(category.name());
            }
            if (selected != null) {
                categoryBox.setSelectedItem(selected);
            }
            statusLabel.setText(products.size() + " products  ·  prices in KES  ·  no VAT");
            filterProducts();
        }, error -> {
            refreshingProducts = false;
            UiKit.error(owner, "Could not load products: " + error.getMessage());
        });
    }

    private void filterProducts() {
        String text = search.getText() == null ? "" : search.getText().trim();
        String category = (String) categoryBox.getSelectedItem();
        List<RowFilter<Object, Object>> filters = new ArrayList<>();
        if (!text.isEmpty()) {
            filters.add(RowFilter.regexFilter("(?i)" + Pattern.quote(text)));
        }
        if (category != null && !category.equals("All categories")) {
            filters.add(RowFilter.regexFilter("^" + Pattern.quote(category) + "$", 2));
        }
        productSorter.setRowFilter(filters.isEmpty() ? null : RowFilter.andFilter(filters));
        if (productTable.getRowCount() > 0 && productTable.getSelectedRow() < 0) {
            productTable.setRowSelectionInterval(0, 0);
        }
    }

    private void loadShift(boolean promptIfClosed) {
        UiKit.async(owner, executor, shifts::currentOpenShift, current -> {
            shift = current;
            if (current == null) {
                shiftPill.setText("Shift closed · open till");
                shiftPill.setBackground(Theme.SURFACE_MUTED);
                shiftPill.setForeground(Theme.TEXT);
                if (promptIfClosed) {
                    promptOpenShift();
                }
            } else {
                shiftPill.setText("Shift #" + current.id() + " open · "
                        + UiKit.date(current.openedAt()));
                shiftPill.setBackground(Theme.SUCCESS);
                shiftPill.setForeground(Color.WHITE);
            }
        });
    }

    private void renderCart(CartSnapshot snapshot) {
        this.snapshot = snapshot;
        long selected = selectedId(cartTable, cartModel);
        cartModel.setRowCount(0);
        for (CartLine line : snapshot.lines()) {
            cartModel.addRow(new Object[]{line.productId(), line.name(), line.quantity(),
                    line.unitPriceCents(), line.totalCents()});
        }
        if (selected > 0) {
            for (int row = 0; row < cartModel.getRowCount(); row++) {
                if (((Number) cartModel.getValueAt(row, 0)).longValue() == selected) {
                    cartTable.setRowSelectionInterval(row, row);
                    break;
                }
            }
        }
        subtotalValue.setText(Money.format(snapshot.totals().subtotalCents()));
        discountValue.setText("-" + Money.format(snapshot.totals().discountCents()));
        taxValue.setText(Money.format(snapshot.totals().taxCents()));
        totalValue.setText(Money.format(snapshot.totals().totalCents()));
        if (snapshot.isEmpty()) {
            statusLabel.setText("Cart empty  ·  scan or select a product");
        }
    }

    private void addSelectedProduct() {
        if (shift == null) {
            promptOpenShift();
            return;
        }
        int viewRow = productTable.getSelectedRow();
        if (viewRow < 0) {
            if (productModel.getRowCount() > 0) {
                productTable.setRowSelectionInterval(0, 0);
            }
            return;
        }
        int row = productTable.convertRowIndexToModel(viewRow);
        long id = ((Number) productModel.getValueAt(row, 0)).longValue();
        UiKit.async(owner, executor, () -> catalog.findProduct(id), product -> {
            if (product == null || !product.active()) {
                return;
            }
            try {
                cart.add(product);
                if (product.stock() <= 0) {
                    statusLabel.setText("Warning: " + product.name() + " is out of stock.");
                } else {
                    statusLabel.setText(product.name() + " added. Stock on hand: " + product.stock());
                }
                focusSearch();
            } catch (IllegalArgumentException ex) {
                UiKit.error(owner, ex.getMessage());
            }
        });
    }

    private void changeQuantity(int delta) {
        long id = selectedId(cartTable, cartModel);
        if (id <= 0) {
            return;
        }
        for (CartLine line : snapshot.lines()) {
            if (line.productId() == id) {
                cart.setQuantity(id, line.quantity() + delta);
                break;
            }
        }
    }

    private void lineDiscount() {
        long id = selectedId(cartTable, cartModel);
        if (id <= 0) {
            return;
        }
        Discount discount = discountDialog("Line discount");
        if (discount == null) {
            return;
        }
        try {
            cart.applyLineDiscount(id, discount);
        } catch (RuntimeException ex) {
            UiKit.error(owner, ex.getMessage());
        }
    }

    private void cartDiscount() {
        Discount discount = discountDialog("Cart discount");
        if (discount == null) {
            return;
        }
        try {
            cart.applyCartDiscount(discount);
        } catch (RuntimeException ex) {
            UiKit.error(owner, ex.getMessage());
        }
    }

    private Discount discountDialog(String title) {
        JComboBox<String> type = new JComboBox<>(new String[]{"Percent %", "Fixed KSh", "No discount"});
        JTextField value = UiKit.field("Value");
        JPanel form = new JPanel(new GridLayout(0, 2, 8, 8));
        form.add(new JLabel("Type"));
        form.add(type);
        form.add(new JLabel("Value"));
        form.add(value);
        int choice = JOptionPane.showConfirmDialog(owner, form, title,
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (choice != JOptionPane.OK_OPTION) {
            return null;
        }
        try {
            return switch (type.getSelectedIndex()) {
                case 0 -> Discount.percentFromInput(value.getText());
                case 1 -> Discount.fixedCents(Money.parse(value.getText()));
                default -> Discount.NONE;
            };
        } catch (RuntimeException ex) {
            UiKit.error(owner, ex.getMessage());
            return null;
        }
    }

    // --------------------------------------------------------------------- tender

    private void tenders() {
        if (cart.isEmpty()) {
            return;
        }
        if (shift == null) {
            promptOpenShift();
            return;
        }
        TenderResult result = new TenderDialog().showDialog();
        if (result == null) {
            return;
        }
        CheckoutCommand command = new CheckoutCommand(shift.id(),
                cart.kioskOrderId() == null ? SaleChannel.COUNTER : SaleChannel.KIOSK,
                cart.kioskOrderId(), snapshot, result.payments());

        UiKit.async(owner, executor, () -> {
            CheckoutResult checkout = sales.checkout(command);
            java.nio.file.Path pdf = null;
            String pdfError = null;
            try {
                pdf = receipts.generateReceipt(checkout.saleId());
            } catch (Exception e) {
                pdfError = e.getMessage();
            }
            return new SaleOutcome(checkout, pdf, pdfError);
        }, outcome -> {
            String message = "Sale complete\n\nReceipt " + outcome.checkout().receiptNumber()
                    + "\nTotal " + Money.format(outcome.checkout().totalCents());
            if (outcome.checkout().changeCents() > 0) {
                message += "\nChange due " + Money.format(outcome.checkout().changeCents());
            }
            if (outcome.pdf() != null) {
                message += "\n\nReceipt PDF saved:\n" + outcome.pdf();
            } else if (outcome.pdfError() != null) {
                message += "\n\nReceipt PDF was not generated: " + outcome.pdfError();
            }
            UiKit.info(owner, message);
            cart.clear();
            snapshot = CartSnapshot.empty();
            renderCart(snapshot);
            loadProducts();
            loadShift(false);
            refreshQueueCount();
            focusSearch();
            if (outcome.pdf() != null && settings.getBoolean("receipt.autoPrint", false)) {
                UiKit.async(owner, executor, () -> {
                    try {
                        receipts.print(outcome.pdf());
                    } catch (Exception ex) {
                        throw new RuntimeException(ex);
                    }
                    return outcome.pdf();
                }, path -> { }, error -> UiKit.error(owner, "Printing failed: " + error.getMessage()));
            }
        });
    }

    private final class TenderDialog extends JDialog {
        private final JTextField cardField = UiKit.field("0.00");
        private final JTextField cashField = UiKit.field("0.00");
        private final JLabel payableLabel = UiKit.label("", 14f, Theme.TEXT, true);
        private final JLabel changeLabel = UiKit.label("KSh 0.00", 28f, Theme.SUCCESS, true);
        private final JLabel errorLabel = UiKit.label(" ", 11f, Theme.DANGER, false);
        private TenderResult result;

        private TenderDialog() {
            super(owner, "Tender", true);
            setContentPane(build());
            setSize(560, 480);
            setResizable(false);
            setLocationRelativeTo(owner);
        }

        private JPanel build() {
            JPanel surface = new JPanel(new BorderLayout());
            surface.setBackground(Theme.SURFACE);
            surface.setBorder(BorderFactory.createEmptyBorder(18, 22, 18, 22));

            JPanel body = new JPanel();
            body.setOpaque(false);
            body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));

            JLabel total = UiKit.label(Money.format(snapshot.totals().totalCents()), 34f, Theme.PRIMARY, true);
            total.setAlignmentX(Component.LEFT_ALIGNMENT);
            body.add(UiKit.caption("AMOUNT DUE"));
            body.add(total);
            body.add(Box.createVerticalStrut(10));
            payableLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
            body.add(payableLabel);
            body.add(Box.createVerticalStrut(14));

            body.add(fieldRow("Card amount", cardField));
            body.add(Box.createVerticalStrut(8));
            body.add(fieldRow("Cash tendered", cashField));
            body.add(Box.createVerticalStrut(10));

            JPanel chips = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            chips.setOpaque(false);
            for (String chip : new String[]{"Exact", "50", "100", "200", "500", "1,000"}) {
                JButton button = UiKit.secondary(chip);
                button.addActionListener(e -> applyChip(chip));
                chips.add(button);
            }
            chips.setAlignmentX(Component.LEFT_ALIGNMENT);
            body.add(chips);
            body.add(Box.createVerticalStrut(16));
            body.add(UiKit.caption("CHANGE DUE"));
            changeLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
            body.add(changeLabel);
            errorLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
            body.add(Box.createVerticalStrut(6));
            body.add(errorLabel);

            cardField.getDocument().addDocumentListener(simpleListener(this::recalculate));
            cashField.getDocument().addDocumentListener(simpleListener(this::recalculate));

            JPanel actions = new JPanel(new GridLayout(1, 2, 10, 0));
            actions.setOpaque(false);
            JButton cancel = UiKit.secondary("Cancel");
            cancel.addActionListener(e -> dispose());
            JButton confirm = UiKit.primary("Complete sale");
            confirm.addActionListener(e -> confirm());
            actions.add(cancel);
            actions.add(confirm);
            actions.setAlignmentX(Component.LEFT_ALIGNMENT);
            actions.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));
            body.add(Box.createVerticalStrut(14));
            body.add(actions);

            surface.add(body, BorderLayout.CENTER);
            getRootPane().setDefaultButton(confirm);
            SwingUtilities.invokeLater(() -> {
                cashField.setText(Money.formatPlain(TenderCalculator.cashRequired(
                        snapshot.totals().totalCents(), 0)));
                cashField.selectAll();
                cashField.requestFocusInWindow();
            });
            return surface;
        }

        private JPanel fieldRow(String label, JTextField field) {
            JPanel row = new JPanel(new BorderLayout(10, 0));
            row.setOpaque(false);
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 42));
            row.setAlignmentX(Component.LEFT_ALIGNMENT);
            row.add(UiKit.label(label, 12f, Theme.TEXT_MUTED, false), BorderLayout.WEST);
            field.setFont(FontLoader.regular(15f));
            field.setPreferredSize(new Dimension(220, 36));
            row.add(field, BorderLayout.CENTER);
            return row;
        }

        private void applyChip(String chip) {
            long card = parseField(cardField);
            long cashRequired = TenderCalculator.cashRequired(snapshot.totals().totalCents(), card);
            if ("Exact".equals(chip)) {
                cashField.setText(Money.formatPlain(cashRequired));
            } else {
                long amount = Money.parse(chip.replace(",", "")) * 100;
                long current = parseField(cashField);
                cashField.setText(Money.formatPlain(current + amount));
            }
            recalculate();
        }

        private void recalculate() {
            long total = snapshot.totals().totalCents();
            long card = Math.max(0, parseField(cardField));
            long cashRequired = TenderCalculator.cashRequired(total, card);
            TenderState state = TenderCalculator.evaluate(total, tendersList(card, parseField(cashField)));
            payableLabel.setText("Cash " + Money.format(cashRequired)
                    + (state.roundingCents() != 0
                    ? "  ·  rounding " + Money.format(state.roundingCents()) : "")
                    + "  ·  payable " + Money.format(state.payableCents()));
            if (state.valid()) {
                changeLabel.setText(Money.format(state.changeCents()));
                changeLabel.setForeground(Theme.SUCCESS);
                errorLabel.setText(" ");
            } else {
                changeLabel.setText(Money.format(0));
                changeLabel.setForeground(Theme.TEXT_MUTED);
                errorLabel.setText(state.error());
            }
        }

        private void confirm() {
            long total = snapshot.totals().totalCents();
            long card = Math.max(0, parseField(cardField));
            long cash = Math.max(0, parseField(cashField));
            List<TenderPayment> payments = tendersList(card, cash);
            TenderState state = TenderCalculator.evaluate(total, payments);
            if (!state.valid()) {
                errorLabel.setText(state.error());
                return;
            }
            result = new TenderResult(payments, state);
            dispose();
        }

        private List<TenderPayment> tendersList(long card, long cashTendered) {
            List<TenderPayment> payments = new ArrayList<>();
            if (card > 0) {
                payments.add(TenderPayment.card(card, null));
            }
            long cashRequired = TenderCalculator.cashRequired(snapshot.totals().totalCents(), card);
            if (cashRequired > 0 || cashTendered > 0) {
                payments.add(TenderPayment.cash(cashRequired, cashTendered));
            }
            return payments;
        }

        private TenderResult showDialog() {
            recalculate();
            setVisible(true);
            return result;
        }

        private long parseField(JTextField field) {
            try {
                return Money.parse(field.getText());
            } catch (Exception e) {
                return -1;
            }
        }
    }

    // ------------------------------------------------------------------- shift etc

    private void promptOpenShift() {
        if (shift != null) {
            return;
        }
        JTextField opening = UiKit.field("Opening cash, e.g. 2,000.00");
        JTextField note = UiKit.field("Note (optional)");
        JPanel form = new JPanel(new GridLayout(0, 2, 8, 8));
        form.add(new JLabel("Opening cash"));
        form.add(opening);
        form.add(new JLabel("Note"));
        form.add(note);
        int choice = JOptionPane.showConfirmDialog(owner, form, "Open till",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (choice != JOptionPane.OK_OPTION) {
            return;
        }
        try {
            long cents = Money.parse(opening.getText());
            UiKit.async(owner, executor, () -> shifts.openShift(cents, note.getText()), opened -> {
                shift = opened;
                shiftPill.setText("Shift #" + opened.id() + " open");
                shiftPill.setBackground(Theme.SUCCESS);
                shiftPill.setForeground(Color.WHITE);
                UiKit.info(owner, "Till opened with " + Money.format(cents) + ".");
                focusSearch();
            });
        } catch (Exception ex) {
            UiKit.error(owner, ex.getMessage());
        }
    }

    private void showMyShift() {
        UiKit.async(owner, executor, () -> {
            Shift current = shifts.currentOpenShift();
            ZReport preview = current == null ? null : zReports.snapshot(current, 0);
            return new Object[]{current, preview};
        }, data -> {
            Shift current = (Shift) data[0];
            ZReport preview = (ZReport) data[1];
            if (current == null) {
                if (UiKit.confirm(owner, "No shift is open. Open the till now?")) {
                    promptOpenShift();
                }
                return;
            }
            JTextArea area = new JTextArea(preview.reportText(), 22, 58);
            area.setFont(FontLoader.regular(12f));
            area.setEditable(false);
            JButton close = UiKit.danger("Close shift");
            JButton print = UiKit.secondary("Preview PDF");
            JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
            actions.add(print);
            actions.add(close);
            JPanel panel = new JPanel(new BorderLayout(0, 8));
            panel.add(new JScrollPane(area), BorderLayout.CENTER);
            panel.add(actions, BorderLayout.SOUTH);
            JDialog dialog = new JDialog(owner, "My shift", true);
            dialog.setContentPane(panel);
            dialog.setSize(680, 580);
            dialog.setLocationRelativeTo(owner);
            close.addActionListener(e -> {
                dialog.dispose();
                closeMyShift();
            });
            print.addActionListener(e -> UiKit.async(owner, executor, () -> {
                try {
                    return receipts.generateZReport(preview);
                } catch (java.io.IOException ex) {
                    throw new java.io.UncheckedIOException(ex);
                }
            }, path -> UiKit.info(owner, "Preview saved to:\n" + path)));
            dialog.setVisible(true);
        });
    }

    private void closeMyShift() {
        String counted = UiKit.input(owner, "Counted cash in the drawer:", "");
        if (counted == null) {
            return;
        }
        String note = UiKit.input(owner, "Closing note (optional):", "");
        try {
            long cents = Money.parse(counted);
            UiKit.async(owner, executor, () -> {
                ZReport report = shifts.closeShift(shift.id(), cents, note);
                java.nio.file.Path pdf = null;
                try {
                    pdf = receipts.generateZReport(report);
                } catch (Exception ignored) {
                    // The shift is already closed; PDF failure must not undo it.
                }
                return new Object[]{report, pdf};
            }, data -> {
                ZReport report = (ZReport) data[0];
                java.nio.file.Path pdf = (java.nio.file.Path) data[1];
                String message = "Shift closed.\n\nExpected " + Money.format(report.expectedCashCents())
                        + "\nCounted " + Money.format(report.countedCashCents())
                        + "\nVariance " + Money.format(report.varianceCents());
                if (pdf != null) {
                    message += "\n\nZ-report saved:\n" + pdf;
                }
                UiKit.info(owner, message);
                shift = null;
                loadShift(false);
            });
        } catch (Exception ex) {
            UiKit.error(owner, ex.getMessage());
        }
    }

    private void showMySales() {
        UiKit.async(owner, executor, () -> sales.recent(200, user.id(), false), rows -> {
            DefaultTableModel model = hiddenIdModel(new String[]{"ID", "Receipt", "Time", "Items", "Total", "Status"});
            for (SaleSummary sale : rows) {
                model.addRow(new Object[]{sale.id(), sale.receiptNumber(), UiKit.time(sale.createdAt()),
                        sale.itemCount(), sale.totalCents(), sale.status()});
            }
            JTable table = UiKit.table(model);
            table.removeColumn(table.getColumnModel().getColumn(0));
            UiKit.columns(table, 0, 150, 120, 60, 110, 120);
            table.getColumnModel().getColumn(3).setCellRenderer(UiKit.moneyRenderer());
            JButton reprint = UiKit.primary("Reprint receipt");
            JButton close = UiKit.secondary("Close");
            JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
            actions.add(reprint);
            actions.add(close);
            JPanel panel = new JPanel(new BorderLayout(0, 8));
            panel.add(new JScrollPane(table), BorderLayout.CENTER);
            panel.add(actions, BorderLayout.SOUTH);
            JDialog dialog = new JDialog(owner, "My sales", true);
            dialog.setContentPane(panel);
            dialog.setSize(720, 520);
            dialog.setLocationRelativeTo(owner);
            close.addActionListener(e -> dialog.dispose());
            reprint.addActionListener(e -> {
                long id = selectedId(table, model);
                if (id > 0) {
                    UiKit.async(owner, executor, () -> {
                        try {
                            return receipts.generateReceipt(id);
                        } catch (java.io.IOException ex) {
                            throw new java.io.UncheckedIOException(ex);
                        }
                    }, path -> UiKit.info(owner, "Receipt saved:\n" + path));
                }
            });
            dialog.setVisible(true);
        });
    }

    private void showKioskQueue() {
        UiKit.async(owner, executor, kioskOrders::pending, orders -> {
            DefaultTableModel model = hiddenIdModel(
                    new String[]{"ID", "Code", "Created", "Items", "Total", "Expires"});
            for (KioskOrder order : orders) {
                int items = order.items().stream().mapToInt(KioskOrder.Item::quantity).sum();
                model.addRow(new Object[]{order.id(), order.code(), UiKit.time(order.createdAt()),
                        items, order.totalCents(), UiKit.time(order.expiresAt())});
            }
            JTable table = UiKit.table(model);
            table.removeColumn(table.getColumnModel().getColumn(0));
            UiKit.columns(table, 0, 90, 110, 60, 110, 110);
            table.getColumnModel().getColumn(3).setCellRenderer(UiKit.moneyRenderer());
            JButton load = UiKit.primary("Load into cart");
            JButton cancel = UiKit.danger("Cancel order");
            JButton close = UiKit.secondary("Close");
            JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
            actions.add(load);
            actions.add(cancel);
            actions.add(close);
            JPanel panel = new JPanel(new BorderLayout(0, 8));
            panel.add(new JScrollPane(table), BorderLayout.CENTER);
            JLabel hint = UiKit.caption("Stock is not reserved until payment is completed.");
            panel.add(hint, BorderLayout.NORTH);
            panel.add(actions, BorderLayout.SOUTH);
            JDialog dialog = new JDialog(owner, "Kiosk queue", true);
            dialog.setContentPane(panel);
            dialog.setSize(720, 520);
            dialog.setLocationRelativeTo(owner);
            close.addActionListener(e -> dialog.dispose());
            cancel.addActionListener(e -> {
                long id = selectedId(table, model);
                if (id > 0 && UiKit.confirm(owner, "Cancel this kiosk order?")) {
                    UiKit.async(owner, executor, () -> {
                        kioskOrders.cancel(id);
                        return id;
                    }, ignored -> {
                        dialog.dispose();
                        showKioskQueue();
                        refreshQueueCount();
                    });
                }
            });
            load.addActionListener(e -> {
                long id = selectedId(table, model);
                if (id <= 0) {
                    return;
                }
                KioskOrder order = orders.stream().filter(o -> o.id() == id).findFirst().orElse(null);
                if (order != null) {
                    cart.clear();
                    List<CartLine> lines = new ArrayList<>();
                    for (KioskOrder.Item item : order.items()) {
                        long gross = item.unitPriceCents() * item.quantity();
                        lines.add(new CartLine(item.productId(), "", item.name(),
                                item.unitPriceCents(), item.quantity(), gross, 0, 0,
                                gross, 0, 0, gross));
                    }
                    cart.addLines(lines);
                    cart.setKioskOrderId(order.id());
                    dialog.dispose();
                    statusLabel.setText("Kiosk order " + order.code() + " loaded. Tender to complete.");
                    focusSearch();
                }
            });
            dialog.setVisible(true);
        });
    }

    private void refreshQueueCount() {
        UiKit.async(owner, executor, kioskOrders::pendingCount, count -> {
            queueButton.setText("Kiosk Orders (" + count + ")");
            queueButton.setBackground(count > 0 ? Theme.ACCENT_GOLD : Theme.SURFACE_CARD);
        }, error -> { });
    }

    // -------------------------------------------------------------------- plumbing

    private void installKeys() {
        root.getInputMap(JPanel.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke("F4"), "tender");
        root.getActionMap().put("tender", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                tenders();
            }
        });
        root.getInputMap(JPanel.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke("F9"), "queue");
        root.getActionMap().put("queue", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                showKioskQueue();
            }
        });
        root.getInputMap(JPanel.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke("ESCAPE"), "clearCart");
        root.getActionMap().put("clearCart", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (!cart.isEmpty()) {
                    cart.clear();
                }
                focusSearch();
            }
        });
    }

    private void focusSearch() {
        SwingUtilities.invokeLater(() -> {
            search.requestFocusInWindow();
            search.selectAll();
        });
    }

    private void updateClock() {
        clockLabel.setText(CLOCK.format(Instant.now()));
    }

    private static DocumentListener simpleListener(Runnable action) {
        return new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                action.run();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                action.run();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                action.run();
            }
        };
    }

    private static DefaultTableModel hiddenIdModel(String[] columns) {
        return new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
    }

    private static long selectedId(JTable table, DefaultTableModel model) {
        int viewRow = table.getSelectedRow();
        if (viewRow < 0) {
            return -1;
        }
        Object id = model.getValueAt(table.convertRowIndexToModel(viewRow), 0);
        return id instanceof Number number ? number.longValue() : -1;
    }

    private static javax.swing.table.TableCellRenderer stockRenderer() {
        return new javax.swing.table.DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value,
                                                           boolean selected, boolean focus,
                                                           int row, int column) {
                Component component = super.getTableCellRendererComponent(
                        table, value, selected, focus, row, column);
                setHorizontalAlignment(JLabel.RIGHT);
                if (!selected && value instanceof Integer stock) {
                    setForeground(stock <= 0 ? Theme.DANGER : stock < 5 ? Theme.WARNING : Theme.TEXT);
                }
                return component;
            }
        };
    }

    private record TenderResult(List<TenderPayment> payments, TenderState state) {
    }

    private record SaleOutcome(CheckoutResult checkout, java.nio.file.Path pdf, String pdfError) {
    }
}
