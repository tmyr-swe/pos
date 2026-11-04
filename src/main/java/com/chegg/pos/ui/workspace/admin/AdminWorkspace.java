package com.chegg.pos.ui.workspace.admin;

import com.chegg.pos.app.AppPaths;
import com.chegg.pos.app.FontLoader;
import com.chegg.pos.app.Theme;
import com.chegg.pos.model.AuditEntry;
import com.chegg.pos.model.KioskOrder;
import com.chegg.pos.model.SaleItemRow;
import com.chegg.pos.model.SaleSummary;
import com.chegg.pos.model.User;
import com.chegg.pos.service.AuditService;
import com.chegg.pos.service.BackupService;
import com.chegg.pos.service.CatalogService;
import com.chegg.pos.service.InventoryService;
import com.chegg.pos.service.KioskOrderService;
import com.chegg.pos.service.ReceiptService;
import com.chegg.pos.service.RefundService;
import com.chegg.pos.service.ReportService;
import com.chegg.pos.service.RoleService;
import com.chegg.pos.service.SaleService;
import com.chegg.pos.service.SessionService;
import com.chegg.pos.service.SettingsService;
import com.chegg.pos.service.ShiftService;
import com.chegg.pos.service.UserService;
import com.chegg.pos.service.ZReportService;
import com.chegg.pos.model.ZReport;
import com.chegg.pos.model.enums.PaymentMethod;
import com.chegg.pos.ui.common.RoundedPanel;
import com.chegg.pos.ui.common.UiAssets;
import com.chegg.pos.ui.common.UiKit;
import com.chegg.pos.util.Money;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Window;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;

/**
 * "Control Room" for System Admin and "Operations" for Store Admin. The nav is
 * built from the same permission set the services enforce.
 */
public class AdminWorkspace {

    private final CatalogService catalog;
    private final InventoryService inventory;
    private final ReportService reports;
    private final SaleService sales;
    private final AuditService audit;
    private final UserService users;
    private final RoleService roles;
    private final SettingsService settings;
    private final SessionService sessions;
    private final ShiftService shifts;
    private final KioskOrderService kioskOrders;
    private final RefundService refunds;
    private final ReceiptService receipts;
    private final ZReportService zReports;
    private final BackupService backups;
    private final ExecutorService executor;
    private final User user;
    private final JFrame owner;
    private final Runnable signOut;

    private final JPanel root = new JPanel(new BorderLayout());
    private final JPanel content = new JPanel(new CardLayout());
    private final Map<String, JComponent> built = new LinkedHashMap<>();
    private final List<JButton> navButtons = new ArrayList<>();
    private String current = "";

    public AdminWorkspace(org.springframework.context.ApplicationContext context,
                          User user, JFrame owner, Runnable signOut) {
        this.catalog = context.getBean(CatalogService.class);
        this.inventory = context.getBean(InventoryService.class);
        this.reports = context.getBean(ReportService.class);
        this.sales = context.getBean(SaleService.class);
        this.audit = context.getBean(AuditService.class);
        this.users = context.getBean(UserService.class);
        this.roles = context.getBean(RoleService.class);
        this.settings = context.getBean(SettingsService.class);
        this.sessions = context.getBean(SessionService.class);
        this.shifts = context.getBean(ShiftService.class);
        this.kioskOrders = context.getBean(KioskOrderService.class);
        this.refunds = context.getBean(RefundService.class);
        this.receipts = context.getBean(ReceiptService.class);
        this.zReports = context.getBean(ZReportService.class);
        this.backups = context.getBean(BackupService.class);
        this.executor = context.getBean(ExecutorService.class);
        this.user = user;
        this.owner = owner;
        this.signOut = signOut;

        root.setBackground(Theme.SURFACE);
        root.add(buildRail(), BorderLayout.WEST);
        content.setOpaque(false);
        content.setBorder(BorderFactory.createEmptyBorder(22, 26, 22, 26));
        root.add(content, BorderLayout.CENTER);

        if (sessions.has("SALE_VIEW_OWN") || sessions.has("SALE_VIEW_ALL")) {
            show("dashboard");
        } else {
            show(firstKey());
        }
    }

    public JPanel root() {
        return root;
    }

    private JPanel buildRail() {
        JPanel rail = new JPanel(new BorderLayout());
        rail.setBackground(Theme.PRIMARY);
        rail.setPreferredSize(new Dimension(212, 0));
        rail.setBorder(BorderFactory.createEmptyBorder(16, 14, 16, 14));

        JPanel top = new JPanel();
        top.setOpaque(false);
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));

        RoundedPanel logoTile = new RoundedPanel(new BorderLayout(), Theme.SURFACE_LOGO, 10);
        logoTile.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        logoTile.setMaximumSize(new Dimension(182, 50));
        logoTile.add(new JLabel(UiAssets.logoIcon(164, 40), JLabel.CENTER), BorderLayout.CENTER);
        top.add(logoTile);
        top.add(Box.createVerticalStrut(12));

        JLabel app = UiKit.label("Chegg POS", 16f, Theme.ON_PRIMARY, true);
        app.setAlignmentX(Component.LEFT_ALIGNMENT);
        top.add(app);
        JLabel workspace = UiKit.label(isSystemAdmin() ? "Control Room" : "Operations",
                10.5f, new Color(0xEF, 0xDF, 0xBB, 190), false);
        workspace.setAlignmentX(Component.LEFT_ALIGNMENT);
        top.add(workspace);
        top.add(Box.createVerticalStrut(20));

        addNav(top, "dashboard", "Dashboard");
        if (sessions.has("SALE_VIEW_ALL") || sessions.has("SALE_VIEW_OWN")) {
            addNav(top, "sales", "Sales");
        }
        if (sessions.has("PRODUCT_VIEW")) {
            addNav(top, "products", "Products");
        }
        if (sessions.has("INVENTORY_VIEW")) {
            addNav(top, "inventory", "Inventory");
        }
        if (sessions.has("REPORT_VIEW") || sessions.has("ZREPORT_VIEW_ALL")) {
            addNav(top, "reports", "Reports");
        }
        if (sessions.has("AUDIT_VIEW")) {
            addNav(top, "audit", "Audit");
        }
        if (sessions.has("USER_VIEW")) {
            addNav(top, "users", "Users");
        }
        if (sessions.has("ROLE_CONFIGURE")) {
            addNav(top, "roles", "Roles");
        }
        if (sessions.has("SETTINGS_VIEW")) {
            addNav(top, "system", "System");
        }

        rail.add(top, BorderLayout.NORTH);

        JPanel bottom = new JPanel();
        bottom.setOpaque(false);
        bottom.setLayout(new BoxLayout(bottom, BoxLayout.Y_AXIS));
        JLabel name = UiKit.label(user.fullName(), 12f, Theme.ON_PRIMARY, true);
        name.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel role = UiKit.label(user.roleName(), 10.5f, new Color(0xEF, 0xDF, 0xBB, 180), false);
        role.setAlignmentX(Component.LEFT_ALIGNMENT);
        JButton signOutButton = UiKit.secondary("Sign out");
        signOutButton.setAlignmentX(Component.LEFT_ALIGNMENT);
        signOutButton.addActionListener(e -> {
            if (UiKit.confirm(owner, "Sign out of " + user.fullName() + "'s workspace?")) {
                signOut.run();
            }
        });
        bottom.add(name);
        bottom.add(role);
        bottom.add(Box.createVerticalStrut(10));
        bottom.add(signOutButton);
        rail.add(bottom, BorderLayout.SOUTH);
        return rail;
    }

    private void addNav(JPanel rail, String key, String label) {
        JButton button = new JButton(label);
        button.setFont(FontLoader.medium(13f));
        button.setForeground(new Color(0xEF, 0xDF, 0xBB));
        button.setBackground(Theme.PRIMARY);
        button.setHorizontalAlignment(JButton.LEFT);
        button.setBorder(BorderFactory.createEmptyBorder(10, 14, 10, 14));
        button.setMaximumSize(new Dimension(Integer.MAX_VALUE, 42));
        button.setAlignmentX(Component.LEFT_ALIGNMENT);
        button.setFocusPainted(false);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.addActionListener(e -> show(key));
        navButtons.add(button);
        rail.add(button);
        rail.add(Box.createVerticalStrut(2));
    }

    private String firstKey() {
        if (sessions.has("PRODUCT_VIEW")) {
            return "products";
        }
        if (sessions.has("INVENTORY_VIEW")) {
            return "inventory";
        }
        return "dashboard";
    }

    private void show(String key) {
        current = key;
        if (!built.containsKey(key)) {
            JComponent panel = switch (key) {
                case "sales" -> salesPanel();
                case "products" -> productsPanel();
                case "inventory" -> inventoryPanel();
                case "reports" -> reportsPanel();
                case "audit" -> auditPanel();
                case "users" -> usersPanel();
                case "roles" -> rolesPanel();
                case "system" -> systemPanel();
                default -> dashboardPanel();
            };
            built.put(key, panel);
            content.add(panel, key);
        }
        ((CardLayout) content.getLayout()).show(content, key);
        highlight(key);
    }

    private void highlight(String key) {
        String[] keys = {"dashboard", "sales", "products", "inventory", "reports", "audit", "users", "roles", "system"};
        int index = 0;
        for (int i = 0; i < keys.length; i++) {
            if (keys[i].equals(key)) {
                index = i;
            }
        }
        // Buttons were added only when permitted, so match by text label.
        Map<String, String> labels = Map.of("dashboard", "Dashboard", "sales", "Sales",
                "products", "Products", "inventory", "Inventory", "reports", "Reports",
                "audit", "Audit", "users", "Users", "roles", "Roles", "system", "System");
        for (JButton button : navButtons) {
            boolean selected = button.getText().equals(labels.get(key));
            button.setBackground(selected ? Theme.PRIMARY_LIGHT : Theme.PRIMARY);
            button.setFont(selected ? FontLoader.semiBold(13f) : FontLoader.medium(13f));
        }
    }

    private boolean isSystemAdmin() {
        return "SYSTEM_ADMIN".equals(user.roleCode());
    }

    // ------------------------------------------------------------------ dashboard

    private JComponent dashboardPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 16));
        panel.setOpaque(false);
        JButton refresh = UiKit.secondary("Refresh");
        panel.add(UiKit.header("Dashboard", "Live picture of the store", refresh),
                BorderLayout.NORTH);

        JPanel body = new JPanel(new BorderLayout(0, 16));
        body.setOpaque(false);

        JPanel kpis = new JPanel(new GridLayout(2, 4, 12, 12));
        kpis.setOpaque(false);
        JLabel revenue = UiKit.kpi("revenue", Theme.SUCCESS);
        JLabel transactions = UiKit.kpi("transactions", Theme.TEXT);
        JLabel average = UiKit.kpi("average", Theme.TEXT);
        JLabel lowStock = UiKit.kpi("lowStock", Theme.WARNING);
        JLabel openShifts = UiKit.kpi("openShifts", Theme.TEXT);
        JLabel queue = UiKit.kpi("queue", Theme.PRIMARY_LIGHT);
        JLabel stockValue = UiKit.kpi("stockValue", Theme.TEXT);
        JLabel failed = UiKit.kpi("failed", Theme.DANGER);
        kpis.add(UiKit.kpiCard("TODAY'S REVENUE", revenue));
        kpis.add(UiKit.kpiCard("TRANSACTIONS", transactions));
        kpis.add(UiKit.kpiCard("AVERAGE TICKET", average));
        kpis.add(UiKit.kpiCard("LOW STOCK", lowStock));
        kpis.add(UiKit.kpiCard("OPEN SHIFTS", openShifts));
        kpis.add(UiKit.kpiCard("KIOSK QUEUE", queue));
        kpis.add(UiKit.kpiCard("STOCK VALUE", stockValue));
        kpis.add(UiKit.kpiCard("FAILED LOGINS TODAY", failed));
        body.add(kpis, BorderLayout.NORTH);

        DefaultTableModel saleModel = model(new String[]{"Receipt", "Time", "Cashier", "Total", "Status"});
        JTable saleTable = UiKit.table(saleModel);
        UiKit.columns(saleTable, 150, 110, 160, 120, 130);
        saleTable.getColumnModel().getColumn(3).setCellRenderer(UiKit.moneyRenderer());

        DefaultTableModel stockModel = model(new String[]{"Product", "Stock", "Reorder"});
        JTable stockTable = UiKit.table(stockModel);
        UiKit.columns(stockTable, 240, 70, 70);

        JPanel tables = new JPanel(new GridLayout(1, 2, 14, 0));
        tables.setOpaque(false);
        tables.add(listCard("Recent sales", saleTable));
        tables.add(listCard("Low stock", stockTable));
        body.add(tables, BorderLayout.CENTER);
        panel.add(body, BorderLayout.CENTER);

        Runnable load = () -> UiKit.async(owner, executor, reports::dashboard, dashboard -> {
            revenue.setText(Money.format(dashboard.todayRevenueCents()));
            transactions.setText(Integer.toString(dashboard.todayTransactions()));
            average.setText(Money.format(dashboard.averageTicketCents()));
            lowStock.setText(Integer.toString(dashboard.lowStockCount()));
            openShifts.setText(Integer.toString(dashboard.openShifts()));
            queue.setText(Integer.toString(dashboard.kioskQueue()));
            stockValue.setText(Money.format(dashboard.stockValueCents()));
            failed.setText(Integer.toString(dashboard.failedLogins()));
        });

        Runnable loadSales = () -> UiKit.async(owner, executor,
                () -> sales.recent(8, user.id(), sales.canViewAll()), rows -> {
                    saleModel.setRowCount(0);
                    for (SaleSummary sale : rows) {
                        saleModel.addRow(new Object[]{sale.receiptNumber(), UiKit.time(sale.createdAt()),
                                sale.cashierName(), sale.totalCents(), sale.status()});
                    }
                });

        Runnable loadStock = () -> UiKit.async(owner, executor, catalog::lowStockProducts, rows -> {
            stockModel.setRowCount(0);
            for (CatalogService.ProductRow product : rows) {
                stockModel.addRow(new Object[]{product.name(), product.stock(), product.reorderLevel()});
            }
        });

        Runnable all = () -> {
            load.run();
            loadSales.run();
            loadStock.run();
        };
        refresh.addActionListener(e -> all.run());
        all.run();
        panel.putClientProperty("refresh", all);
        return panel;
    }

    private JComponent listCard(String title, JTable table) {
        RoundedPanel card = UiKit.card(new BorderLayout(0, 10));
        card.add(UiKit.section(title), BorderLayout.NORTH);
        card.add(UiKit.scroll(table), BorderLayout.CENTER);
        return card;
    }

    private JButton refreshButton(Runnable action) {
        JButton button = UiKit.secondary("Refresh");
        button.addActionListener(e -> action.run());
        return button;
    }

    // ---------------------------------------------------------------------- sales

    private JComponent salesPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 14));
        panel.setOpaque(false);

        JTextField fromField = UiKit.field("From yyyy-MM-dd");
        JTextField toField = UiKit.field("To yyyy-MM-dd");
        fromField.setPreferredSize(new Dimension(130, 32));
        toField.setPreferredSize(new Dimension(130, 32));
        JComboBox<String> statusBox = new JComboBox<>(new String[]{"ALL", "COMPLETED", "PARTIALLY_REFUNDED", "REFUNDED", "VOIDED"});
        JComboBox<String> channelBox = new JComboBox<>(new String[]{"ALL", "COUNTER", "KIOSK"});
        JButton search = UiKit.primary("Search");
        JButton refund = UiKit.secondary("Refund");
        JButton voidSale = UiKit.danger("Void");
        JButton reprint = UiKit.secondary("Reprint");
        JButton details = UiKit.secondary("Details");
        JButton refresh = UiKit.secondary("Refresh");

        JPanel filters = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        filters.setOpaque(false);
        filters.add(fromField);
        filters.add(toField);
        filters.add(statusBox);
        filters.add(channelBox);
        filters.add(search);
        filters.add(refresh);
        filters.add(details);
        filters.add(reprint);
        if (sessions.has("REFUND_APPROVE")) {
            filters.add(refund);
        }
        if (sessions.has("SALE_VOID")) {
            filters.add(voidSale);
        }

        JPanel top = new JPanel();
        top.setOpaque(false);
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.add(UiKit.header("Sales", "Every transaction on this terminal", null));
        top.add(Box.createVerticalStrut(12));
        top.add(filters);
        panel.add(top, BorderLayout.NORTH);

        DefaultTableModel model = model(new String[]{"ID", "Receipt", "Time", "Cashier",
                "Channel", "Items", "Total", "Status", "Payment", "Shift"});
        JTable table = UiKit.table(model);
        table.removeColumn(table.getColumnModel().getColumn(0));
        UiKit.columns(table, 0, 140, 105, 140, 80, 45, 110, 120, 80, 55);
        table.getColumnModel().getColumn(5).setCellRenderer(UiKit.moneyRenderer());
        panel.add(UiKit.scroll(table), BorderLayout.CENTER);

        Runnable load = () -> {
            LocalDate from = parseDate(fromField.getText());
            LocalDate to = parseDate(toField.getText());
            String status = (String) statusBox.getSelectedItem();
            String channel = (String) channelBox.getSelectedItem();
            UiKit.async(owner, executor,
                    () -> sales.search(from, to, status, channel, user.id(), sales.canViewAll()),
                    rows -> {
                        model.setRowCount(0);
                        for (SaleSummary sale : rows) {
                            model.addRow(new Object[]{sale.id(), sale.receiptNumber(),
                                    UiKit.time(sale.createdAt()), sale.cashierName(), sale.channel(),
                                    sale.itemCount(), sale.totalCents(), sale.status(),
                                    sale.paymentSummary() == null ? "" : sale.paymentSummary(),
                                    sale.shiftId()});
                        }
                    });
        };
        search.addActionListener(e -> load.run());
        refresh.addActionListener(e -> load.run());

        Runnable selectedAction = null;
        details.addActionListener(e -> {
            long id = selectedId(table, model);
            if (id > 0) {
                showSaleDetails(id);
            }
        });
        reprint.addActionListener(e -> reprintSale(selectedId(table, model)));
        if (sessions.has("REFUND_APPROVE")) {
            refund.addActionListener(e -> refundSale(selectedId(table, model), load));
        }
        if (sessions.has("SALE_VOID")) {
            voidSale.addActionListener(e -> voidSale(selectedId(table, model), load));
        }
        table.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) {
                    long id = selectedId(table, model);
                    if (id > 0) {
                        showSaleDetails(id);
                    }
                }
            }
        });

        load.run();
        panel.putClientProperty("refresh", load);
        return panel;
    }

    private void showSaleDetails(long saleId) {
        UiKit.async(owner, executor, () -> new Object[]{
                sales.findById(saleId), sales.items(saleId)}, data -> {
            SaleSummary sale = (SaleSummary) data[0];
            if (sale == null) {
                return;
            }
            @SuppressWarnings("unchecked")
            List<SaleItemRow> items = (List<SaleItemRow>) data[1];
            StringBuilder text = new StringBuilder();
            text.append("Receipt: ").append(sale.receiptNumber()).append('\n');
            text.append("Cashier: ").append(sale.cashierName()).append('\n');
            text.append("Time:    ").append(UiKit.time(sale.createdAt())).append('\n');
            text.append("Channel: ").append(sale.channel()).append('\n');
            text.append("Status:  ").append(sale.status()).append("\n\n");
            for (SaleItemRow item : items) {
                text.append(String.format("%-32s %3d x %10s = %10s%n",
                        item.name(), item.quantity(), Money.formatPlain(item.unitPriceCents()),
                        Money.formatPlain(item.lineTotalCents())));
            }
            text.append("\nSubtotal ").append(Money.format(sale.subtotalCents())).append('\n');
            text.append("Discount ").append(Money.format(sale.discountCents())).append('\n');
            text.append("Rounding ").append(Money.format(sale.roundingCents())).append('\n');
            text.append("TOTAL    ").append(Money.format(sale.totalCents())).append('\n');
            JTextArea area = new JTextArea(text.toString(), 18, 58);
            area.setFont(FontLoader.regular(12f));
            area.setEditable(false);
            JOptionPane.showMessageDialog(owner, new JScrollPane(area),
                    "Sale " + sale.receiptNumber(), JOptionPane.PLAIN_MESSAGE);
        });
    }

    private void reprintSale(long saleId) {
        if (saleId <= 0) {
            return;
        }
        UiKit.async(owner, executor, () -> {
            try {
                return receipts.generateReceipt(saleId);
            } catch (java.io.IOException ex) {
                throw new java.io.UncheckedIOException(ex);
            }
        }, path -> {
            if (UiKit.confirm(owner, "Receipt saved to:\n" + path + "\n\nSend it to the printer now?")) {
                UiKit.async(owner, executor, () -> {
                    try {
                        receipts.print(path);
                    } catch (Exception ex) {
                        throw new RuntimeException(ex);
                    }
                    return path;
                }, printed -> { }, error -> UiKit.error(owner,
                        "Could not print: " + error.getMessage()));
            }
        });
    }

    private void refundSale(long saleId, Runnable after) {
        if (saleId <= 0) {
            return;
        }
        UiKit.async(owner, executor, () -> {
            SaleSummary sale = sales.findById(saleId);
            List<SaleItemRow> items = refunds.refundableItems(saleId);
            return new Object[]{sale, items};
        }, data -> {
            @SuppressWarnings("unchecked")
            SaleSummary sale = (SaleSummary) data[0];
            @SuppressWarnings("unchecked")
            List<SaleItemRow> items = (List<SaleItemRow>) data[1];
            if (sale == null || items == null) {
                return;
            }
            RefundService.RefundRequest request = refundDialog(sale, items);
            if (request == null) {
                return;
            }
            UiKit.async(owner, executor, () -> refunds.refund(request), id -> {
                UiKit.info(owner, "Refund recorded on shift. Stock has been returned.");
                after.run();
            });
        });
    }

    private RefundService.RefundRequest refundDialog(SaleSummary sale, List<SaleItemRow> items) {
        DefaultTableModel model = new DefaultTableModel(
                new Object[]{"Item", "Sold", "Already refunded", "Refund now"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return column == 3;
            }

            @Override
            public Class<?> getColumnClass(int columnIndex) {
                return columnIndex == 3 ? Integer.class : String.class;
            }
        };
        for (SaleItemRow item : items) {
            model.addRow(new Object[]{item.name(), item.quantity(), item.refundedQuantity(),
                    item.remainingQuantity() > 0 ? 0 : 0});
        }
        JTable table = UiKit.table(model);
        UiKit.columns(table, 260, 60, 110, 80);
        JComboBox<PaymentMethod> method = new JComboBox<>(PaymentMethod.values());
        JTextField reason = UiKit.field("Reason for refund");
        JPanel form = new JPanel(new BorderLayout(0, 8));
        form.add(new JScrollPane(table), BorderLayout.CENTER);
        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        bottom.add(new JLabel("Refund to"));
        bottom.add(method);
        bottom.add(reason);
        form.add(bottom, BorderLayout.SOUTH);
        form.setPreferredSize(new Dimension(560, 340));
        int choice = JOptionPane.showConfirmDialog(owner, form,
                "Refund " + sale.receiptNumber(), JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (choice != JOptionPane.OK_OPTION) {
            return null;
        }
        List<RefundService.RefundLine> lines = new ArrayList<>();
        for (int i = 0; i < model.getRowCount(); i++) {
            Object value = model.getValueAt(i, 3);
            int quantity;
            try {
                quantity = value == null ? 0 : Integer.parseInt(value.toString().trim());
            } catch (NumberFormatException e) {
                quantity = 0;
            }
            if (quantity > 0) {
                lines.add(new RefundService.RefundLine(items.get(i).id(), quantity));
            }
        }
        if (lines.isEmpty()) {
            UiKit.error(owner, "Enter at least one refund quantity.");
            return null;
        }
        if (reason.getText() == null || reason.getText().isBlank()) {
            UiKit.error(owner, "A refund reason is required.");
            return null;
        }
        return new RefundService.RefundRequest(sale.id(), lines,
                (PaymentMethod) method.getSelectedItem(), reason.getText().trim());
    }

    private void voidSale(long saleId, Runnable after) {
        if (saleId <= 0) {
            return;
        }
        String reason = UiKit.input(owner, "Reason for voiding this sale:", "");
        if (reason == null || reason.isBlank()) {
            return;
        }
        if (!UiKit.confirm(owner, "Void this sale and return its items to stock?")) {
            return;
        }
        UiKit.async(owner, executor, () -> {
            sales.voidSale(saleId, reason);
            return saleId;
        }, id -> {
            UiKit.info(owner, "Sale voided.");
            after.run();
        });
    }

    // ------------------------------------------------------------------- products

    private JComponent productsPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 14));
        panel.setOpaque(false);

        JTextField search = UiKit.field("Search product or SKU");
        search.setPreferredSize(new Dimension(260, 32));
        JButton add = UiKit.primary("Add product");
        JButton edit = UiKit.secondary("Edit");
        JButton archive = UiKit.secondary("Archive");
        JButton refresh = UiKit.secondary("Refresh");

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        actions.setOpaque(false);
        actions.add(search);
        if (sessions.has("PRODUCT_EDIT")) {
            actions.add(add);
            actions.add(edit);
        }
        if (sessions.has("PRODUCT_ARCHIVE")) {
            actions.add(archive);
        }
        actions.add(refresh);

        JPanel top = new JPanel();
        top.setOpaque(false);
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.add(UiKit.header("Products", "Catalog, prices and reorder points", null));
        top.add(Box.createVerticalStrut(12));
        top.add(actions);
        panel.add(top, BorderLayout.NORTH);

        DefaultTableModel model = model(new String[]{"ID", "SKU", "Product", "Category",
                "Price", "Cost", "Stock", "Reorder", "Active"});
        JTable table = UiKit.table(model);
        table.removeColumn(table.getColumnModel().getColumn(0));
        UiKit.columns(table, 0, 100, 260, 130, 100, 100, 70, 70, 60);
        table.getColumnModel().getColumn(4).setCellRenderer(UiKit.moneyRenderer());
        table.getColumnModel().getColumn(5).setCellRenderer(UiKit.moneyRenderer());
        panel.add(UiKit.scroll(table), BorderLayout.CENTER);

        Runnable load = () -> UiKit.async(owner, executor, () -> catalog.listProducts(false), rows -> {
            model.setRowCount(0);
            String filter = search.getText() == null ? "" : search.getText().trim().toLowerCase();
            for (CatalogService.ProductRow product : rows) {
                if (!filter.isEmpty()
                        && !product.name().toLowerCase().contains(filter)
                        && !product.sku().toLowerCase().contains(filter)) {
                    continue;
                }
                model.addRow(new Object[]{product.id(), product.sku(), product.name(),
                        product.category(), product.priceCents(), product.costCents(),
                        product.stock(), product.reorderLevel(), product.active() ? "Yes" : "No"});
            }
        });
        search.addActionListener(e -> load.run());
        refresh.addActionListener(e -> load.run());
        add.addActionListener(e -> editProduct(null, load));
        edit.addActionListener(e -> {
            long id = selectedId(table, model);
            if (id > 0) {
                UiKit.async(owner, executor, () -> catalog.findProduct(id),
                        product -> editProduct(product, load));
            }
        });
        archive.addActionListener(e -> {
            long id = selectedId(table, model);
            if (id <= 0) {
                return;
            }
            if (UiKit.confirm(owner, "Archive this product? It will disappear from the till.")) {
                UiKit.async(owner, executor, () -> {
                    catalog.archiveProduct(id);
                    return id;
                }, ignored -> load.run());
            }
        });
        table.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2 && sessions.has("PRODUCT_EDIT")) {
                    long id = selectedId(table, model);
                    if (id > 0) {
                        UiKit.async(owner, executor, () -> catalog.findProduct(id),
                                product -> editProduct(product, load));
                    }
                }
            }
        });

        load.run();
        panel.putClientProperty("refresh", load);
        return panel;
    }

    private void editProduct(CatalogService.ProductRow existing, Runnable after) {
        UiKit.async(owner, executor, catalog::categories, categories -> {
            if (categories.isEmpty()) {
                UiKit.error(owner, "Create a category before adding products.");
                return;
            }
            editProductDialog(existing, categories, after);
        });
    }

    private void editProductDialog(CatalogService.ProductRow existing,
                                   List<CatalogService.CategoryRow> categories, Runnable after) {
        JTextField sku = UiKit.field("SKU");
        JTextField name = UiKit.field("Product name");
        JTextField price = UiKit.field("Price, e.g. 150.00");
        JTextField cost = UiKit.field("Cost, e.g. 100.00");
        JTextField stock = UiKit.field("Opening stock");
        JTextField reorder = UiKit.field("Reorder level");
        JComboBox<CatalogService.CategoryRow> category = new JComboBox<>(categories.toArray(new CatalogService.CategoryRow[0]));
        category.setRenderer((list, value, index, selected, focus) -> {
            JLabel label = new JLabel(value == null ? "" : value.name());
            label.setOpaque(true);
            label.setBackground(selected ? Theme.SURFACE_SELECTED : Theme.SURFACE_CARD);
            return label;
        });
        JCheckBox active = new JCheckBox("Active", true);

        if (existing != null) {
            sku.setText(existing.sku());
            name.setText(existing.name());
            price.setText(Money.formatPlain(existing.priceCents()));
            cost.setText(Money.formatPlain(existing.costCents()));
            reorder.setText(Integer.toString(existing.reorderLevel()));
            active.setSelected(existing.active());
            for (int i = 0; i < category.getItemCount(); i++) {
                if (category.getItemAt(i).id() == existing.categoryId()) {
                    category.setSelectedIndex(i);
                }
            }
        }

        JPanel form = formPanel();
        addRow(form, 0, "SKU", sku);
        addRow(form, 1, "Name", name);
        addRow(form, 2, "Category", category);
        addRow(form, 3, "Price (KSh)", price);
        addRow(form, 4, "Cost (KSh)", cost);
        if (existing == null) {
            addRow(form, 5, "Opening stock", stock);
            addRow(form, 6, "Reorder level", reorder);
        } else {
            addRow(form, 5, "Reorder level", reorder);
            addRow(form, 6, "Status", active);
        }

        int choice = JOptionPane.showConfirmDialog(owner, form,
                existing == null ? "Add product" : "Edit product",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (choice != JOptionPane.OK_OPTION) {
            return;
        }
        try {
            long priceCents = Money.parse(price.getText());
            long costCents = cost.getText() == null || cost.getText().isBlank() ? 0 : Money.parse(cost.getText());
            int opening = existing == null ? parseInt(stock.getText(), 0) : 0;
            int reorderLevel = parseInt(reorder.getText(), 0);
            CatalogService.CategoryRow selected = (CatalogService.CategoryRow) category.getSelectedItem();
            CatalogService.ProductInput input = new CatalogService.ProductInput(
                    existing == null ? null : existing.id(), sku.getText(), name.getText(),
                    selected == null ? categories.get(0).id() : selected.id(),
                    priceCents, costCents, 0, existing == null || active.isSelected(),
                    opening, reorderLevel);
            UiKit.async(owner, executor, () -> catalog.saveProduct(input), id -> {
                UiKit.info(owner, existing == null ? "Product added." : "Product updated.");
                after.run();
            });
        } catch (Exception ex) {
            UiKit.error(owner, ex.getMessage());
        }
    }

    // ------------------------------------------------------------------ inventory

    private JComponent inventoryPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 14));
        panel.setOpaque(false);

        JButton receive = UiKit.primary("Receive stock");
        JButton adjust = UiKit.secondary("Adjust");
        JButton reorder = UiKit.secondary("Set reorder level");
        JButton history = UiKit.secondary("Movement history");
        JButton refresh = UiKit.secondary("Refresh");
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        actions.setOpaque(false);
        actions.add(receive);
        actions.add(adjust);
        actions.add(reorder);
        actions.add(history);
        actions.add(refresh);

        JPanel top = new JPanel();
        top.setOpaque(false);
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.add(UiKit.header("Inventory", "Stock on hand, receiving and adjustments", null));
        top.add(Box.createVerticalStrut(12));
        top.add(actions);
        panel.add(top, BorderLayout.NORTH);

        DefaultTableModel model = model(new String[]{"ID", "SKU", "Product", "Stock", "Reorder", "Status"});
        JTable table = UiKit.table(model);
        table.removeColumn(table.getColumnModel().getColumn(0));
        UiKit.columns(table, 0, 100, 300, 80, 80, 120);
        panel.add(UiKit.scroll(table), BorderLayout.CENTER);

        Runnable load = () -> UiKit.async(owner, executor, () -> catalog.listProducts(false), rows -> {
            model.setRowCount(0);
            for (CatalogService.ProductRow product : rows) {
                model.addRow(new Object[]{product.id(), product.sku(), product.name(),
                        product.stock(), product.reorderLevel(),
                        product.lowStock() ? "LOW" : "OK"});
            }
        });
        refresh.addActionListener(e -> load.run());
        receive.addActionListener(e -> adjustStock(selectedId(table, model), true, load));
        adjust.addActionListener(e -> adjustStock(selectedId(table, model), false, load));
        reorder.addActionListener(e -> {
            long id = selectedId(table, model);
            if (id <= 0) {
                return;
            }
            String value = UiKit.input(owner, "Reorder level (units):", "10");
            if (value == null) {
                return;
            }
            try {
                int level = Integer.parseInt(value.trim());
                UiKit.async(owner, executor, () -> {
                    inventory.setReorderLevel(id, level);
                    return id;
                }, ignored -> load.run());
            } catch (NumberFormatException ex) {
                UiKit.error(owner, "Enter a whole number.");
            }
        });
        history.addActionListener(e -> showMovementHistory(selectedId(table, model)));
        load.run();
        panel.putClientProperty("refresh", load);
        return panel;
    }

    private void adjustStock(long productId, boolean receiveStock, Runnable after) {
        if (productId <= 0) {
            return;
        }
        String label = receiveStock ? "Units received:" : "Adjustment (+/- units):";
        String value = UiKit.input(owner, label, receiveStock ? "10" : "0");
        if (value == null || value.isBlank()) {
            return;
        }
        try {
            int delta = Integer.parseInt(value.trim());
            if (!receiveStock && delta == 0) {
                return;
            }
            if (receiveStock && delta <= 0) {
                UiKit.error(owner, "Received quantity must be positive.");
                return;
            }
            String note = UiKit.input(owner, "Reference or note (optional):", "");
            UiKit.async(owner, executor, () -> {
                inventory.adjust(productId, Math.abs(delta) * (receiveStock ? 1 : (delta < 0 ? -1 : 1)),
                        receiveStock ? com.chegg.pos.model.enums.StockReason.RECEIVE
                                : com.chegg.pos.model.enums.StockReason.ADJUST, note);
                return productId;
            }, ignored -> {
                UiKit.info(owner, "Stock updated.");
                after.run();
            });
        } catch (NumberFormatException ex) {
            UiKit.error(owner, "Enter a whole number of units.");
        }
    }

    private void showMovementHistory(long productId) {
        UiKit.async(owner, executor, () -> productId <= 0
                ? inventory.movements(200) : inventory.movementsForProduct(productId, 200), rows -> {
            DefaultTableModel model = model(new String[]{"When", "Product", "Change", "Reason", "User"});
            for (com.chegg.pos.model.StockMovement move : rows) {
                model.addRow(new Object[]{UiKit.time(move.createdAt()), move.productName(),
                        (move.delta() > 0 ? "+" : "") + move.delta(), move.reason(),
                        move.userName() == null ? "" : move.userName()});
            }
            JTable table = UiKit.table(model);
            UiKit.columns(table, 110, 260, 70, 90, 140);
            JDialog dialog = new JDialog(owner, "Stock movements", true);
            dialog.setContentPane(new JScrollPane(table));
            dialog.setSize(820, 520);
            dialog.setLocationRelativeTo(owner);
            dialog.setVisible(true);
        });
    }

    // -------------------------------------------------------------------- reports

    private JComponent reportsPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 14));
        panel.setOpaque(false);

        JTextField from = UiKit.field("From yyyy-MM-dd");
        JTextField to = UiKit.field("To yyyy-MM-dd");
        JButton loadButton = UiKit.primary("Run report");
        JButton export = UiKit.secondary("Export CSV");
        JButton zPdf = UiKit.secondary("Z-report PDF");
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        actions.setOpaque(false);
        actions.add(from);
        actions.add(to);
        actions.add(loadButton);
        actions.add(export);
        actions.add(zPdf);

        JPanel top = new JPanel();
        top.setOpaque(false);
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.add(UiKit.header("Reports", "Sales performance, stock valuation and Z-reports", null));
        top.add(Box.createVerticalStrut(12));
        top.add(actions);
        panel.add(top, BorderLayout.NORTH);

        DefaultTableModel topModel = model(new String[]{"Product", "Units", "Revenue"});
        JTable topTable = UiKit.table(topModel);
        UiKit.columns(topTable, 280, 70, 120);
        topTable.getColumnModel().getColumn(2).setCellRenderer(UiKit.moneyRenderer());

        DefaultTableModel trendModel = model(new String[]{"Day", "Revenue", "Transactions"});
        JTable trendTable = UiKit.table(trendModel);
        UiKit.columns(trendTable, 120, 120, 100);
        trendTable.getColumnModel().getColumn(1).setCellRenderer(UiKit.moneyRenderer());

        DefaultTableModel zModel = model(new String[]{"ID", "Shift", "Generated", "Net", "Txns", "Refunds", "Variance"});
        JTable zTable = UiKit.table(zModel);
        zTable.removeColumn(zTable.getColumnModel().getColumn(0));
        UiKit.columns(zTable, 0, 55, 110, 110, 50, 100, 100);
        zTable.getColumnModel().getColumn(2).setCellRenderer(UiKit.moneyRenderer());
        zTable.getColumnModel().getColumn(4).setCellRenderer(UiKit.moneyRenderer());
        zTable.getColumnModel().getColumn(5).setCellRenderer(UiKit.moneyRenderer());

        JPanel left = new JPanel(new GridLayout(2, 1, 0, 12));
        left.setOpaque(false);
        left.add(listCard("Top products", topTable));
        left.add(listCard("Last 7 days", trendTable));
        JPanel split = new JPanel(new GridLayout(1, 2, 14, 0));
        split.setOpaque(false);
        split.add(left);
        split.add(listCard("Z-reports", zTable));
        panel.add(split, BorderLayout.CENTER);

        Runnable load = () -> {
            LocalDate fromDate = parseDate(from.getText());
            LocalDate toDate = parseDate(to.getText());
            UiKit.async(owner, executor, () -> reports.topProducts(fromDate, toDate, 25), rows -> {
                topModel.setRowCount(0);
                for (ReportService.TopProduct row : rows) {
                    topModel.addRow(new Object[]{row.name(), row.quantity(), row.revenueCents()});
                }
            });
            UiKit.async(owner, executor, () -> reports.salesTrend(7), rows -> {
                trendModel.setRowCount(0);
                for (ReportService.SalesPoint row : rows) {
                    trendModel.addRow(new Object[]{row.day().format(DateTimeFormatter.ofPattern("dd MMM")),
                            row.revenueCents(), row.transactions()});
                }
            });
            UiKit.async(owner, executor, () -> reports.zReports(50), rows -> {
                zModel.setRowCount(0);
                for (ReportService.ZReportRow row : rows) {
                    zModel.addRow(new Object[]{row.id(), row.shiftId(), UiKit.time(row.generatedAt()),
                            row.netCents(), row.transactions(), row.refundsCents(), row.varianceCents()});
                }
            });
        };
        loadButton.addActionListener(e -> load.run());
        export.addActionListener(e -> exportCsv(topModel));
        zPdf.addActionListener(e -> {
            long id = selectedId(zTable, zModel);
            if (id <= 0) {
                return;
            }
            UiKit.async(owner, executor, () -> {
                ZReport report = zReports.findById(id);
                if (report == null) {
                    throw new IllegalArgumentException("Z-report not found.");
                }
                try {
                    return receipts.generateZReport(report);
                } catch (java.io.IOException ex) {
                    throw new java.io.UncheckedIOException(ex);
                }
            }, path -> UiKit.info(owner, "Z-report PDF saved to:\n" + path));
        });

        load.run();
        panel.putClientProperty("refresh", load);
        return panel;
    }

    private void exportCsv(DefaultTableModel model) {
        StringBuilder csv = new StringBuilder();
        for (int column = 0; column < model.getColumnCount(); column++) {
            if (column > 0) {
                csv.append(',');
            }
            csv.append(escapeCsv(model.getColumnName(column)));
        }
        csv.append('\n');
        for (int row = 0; row < model.getRowCount(); row++) {
            for (int column = 0; column < model.getColumnCount(); column++) {
                if (column > 0) {
                    csv.append(',');
                }
                csv.append(escapeCsv(String.valueOf(model.getValueAt(row, column))));
            }
            csv.append('\n');
        }
        try {
            Path dir = AppPaths.home().resolve("exports");
            java.nio.file.Files.createDirectories(dir);
            Path file = dir.resolve("report-" + java.time.LocalDateTime.now()
                    .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".csv");
            java.nio.file.Files.writeString(file, csv.toString());
            UiKit.info(owner, "CSV saved to:\n" + file);
        } catch (Exception e) {
            UiKit.error(owner, "Could not save CSV: " + e.getMessage());
        }
    }

    private static String escapeCsv(String value) {
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    // ---------------------------------------------------------------------- audit

    private JComponent auditPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 14));
        panel.setOpaque(false);

        JComboBox<String> filter = new JComboBox<>();
        filter.addItem("ALL");
        JButton refresh = UiKit.secondary("Refresh");
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        actions.setOpaque(false);
        actions.add(new JLabel("Action"));
        actions.add(filter);
        actions.add(refresh);

        JPanel top = new JPanel();
        top.setOpaque(false);
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.add(UiKit.header("Audit", "Append-only trail of every change", null));
        top.add(Box.createVerticalStrut(12));
        top.add(actions);
        panel.add(top, BorderLayout.NORTH);

        DefaultTableModel model = model(new String[]{"When", "User", "Action", "Summary", "Details"});
        JTable table = UiKit.table(model);
        UiKit.columns(table, 110, 150, 150, 300, 340);
        panel.add(UiKit.scroll(table), BorderLayout.CENTER);

        Runnable load = () -> UiKit.async(owner, executor,
                () -> audit.recent(500, (String) filter.getSelectedItem()), rows -> {
                    model.setRowCount(0);
                    for (AuditEntry entry : rows) {
                        model.addRow(new Object[]{UiKit.time(entry.at()),
                                entry.userName() == null ? "system" : entry.userName(),
                                entry.action(), entry.summary(), entry.details()});
                    }
                });
        filter.addActionListener(e -> load.run());
        refresh.addActionListener(e -> load.run());
        UiKit.async(owner, executor, audit::actions, actionsList -> {
            for (String action : actionsList) {
                filter.addItem(action);
            }
        });
        load.run();
        panel.putClientProperty("refresh", load);
        return panel;
    }

    // ---------------------------------------------------------------------- users

    private JComponent usersPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 14));
        panel.setOpaque(false);

        JButton add = UiKit.primary("Add user");
        JButton edit = UiKit.secondary("Edit");
        JButton reset = UiKit.secondary("Reset password");
        JButton refresh = UiKit.secondary("Refresh");
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        actions.setOpaque(false);
        actions.add(add);
        actions.add(edit);
        actions.add(reset);
        actions.add(refresh);

        JPanel top = new JPanel();
        top.setOpaque(false);
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.add(UiKit.header("Users", "Staff accounts and role assignment", null));
        top.add(Box.createVerticalStrut(12));
        top.add(actions);
        panel.add(top, BorderLayout.NORTH);

        DefaultTableModel model = model(new String[]{"ID", "Username", "Name", "Role",
                "Active", "Last sign-in", "Must change"});
        JTable table = UiKit.table(model);
        table.removeColumn(table.getColumnModel().getColumn(0));
        UiKit.columns(table, 0, 120, 220, 160, 70, 150, 90);
        panel.add(UiKit.scroll(table), BorderLayout.CENTER);

        Runnable load = () -> UiKit.async(owner, executor, users::users, rows -> {
            model.setRowCount(0);
            for (UserService.UserRow row : rows) {
                model.addRow(new Object[]{row.id(), row.username(), row.fullName(),
                        row.roleName(), row.active() ? "Yes" : "No",
                        row.lastLoginAt() == null ? "never" : row.lastLoginAt(),
                        row.mustChangePassword() ? "Yes" : "No"});
            }
        });
        refresh.addActionListener(e -> load.run());
        add.addActionListener(e -> userDialog(null, load));
        edit.addActionListener(e -> {
            long id = selectedId(table, model);
            if (id > 0) {
                UiKit.async(owner, executor, () -> users.find(id), row -> userDialog(row, load));
            }
        });
        reset.addActionListener(e -> {
            long id = selectedId(table, model);
            if (id <= 0) {
                return;
            }
            String password = UiKit.input(owner, "New temporary password (min 8 chars):", "");
            if (password == null || password.length() < 8) {
                return;
            }
            UiKit.async(owner, executor, () -> {
                users.resetPassword(id, password);
                return id;
            }, ignored -> UiKit.info(owner, "Password reset. The user must change it at next sign-in."));
        });
        load.run();
        panel.putClientProperty("refresh", load);
        return panel;
    }

    private void userDialog(UserService.UserRow existing, Runnable after) {
        UiKit.async(owner, executor, users::roles,
                roleRows -> userDialog(existing, roleRows, after));
    }

    private void userDialog(UserService.UserRow existing,
                            List<UserService.RoleRow> roleRows, Runnable after) {
        JTextField username = UiKit.field("Username");
        JTextField fullName = UiKit.field("Full name");
        JTextField password = UiKit.field("Temporary password");
        JComboBox<UserService.RoleRow> role = new JComboBox<>(roleRows.toArray(new UserService.RoleRow[0]));
        role.setRenderer((list, value, index, selected, focus) -> {
            JLabel label = new JLabel(value == null ? "" : value.name());
            label.setOpaque(true);
            label.setBackground(selected ? Theme.SURFACE_SELECTED : Theme.SURFACE_CARD);
            return label;
        });
        JCheckBox active = new JCheckBox("Active", true);
        if (existing != null) {
            username.setText(existing.username());
            username.setEnabled(false);
            fullName.setText(existing.fullName());
            active.setSelected(existing.active());
            for (int i = 0; i < role.getItemCount(); i++) {
                if (role.getItemAt(i).id() == existing.roleId()) {
                    role.setSelectedIndex(i);
                }
            }
        }
        JPanel form = formPanel();
        addRow(form, 0, "Username", username);
        addRow(form, 1, "Full name", fullName);
        addRow(form, 2, "Role", role);
        if (existing == null) {
            addRow(form, 3, "Temporary password", password);
        } else {
            addRow(form, 3, "Status", active);
        }
        int choice = JOptionPane.showConfirmDialog(owner, form,
                existing == null ? "Add user" : "Edit user",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (choice != JOptionPane.OK_OPTION) {
            return;
        }
        UserService.RoleRow selectedRole = (UserService.RoleRow) role.getSelectedItem();
        if (selectedRole == null) {
            UiKit.error(owner, "Choose a role.");
            return;
        }
        if (existing == null) {
            UiKit.async(owner, executor,
                    () -> users.create(username.getText(), fullName.getText(),
                            selectedRole.id(), password.getText()),
                    id -> {
                        UiKit.info(owner, "User created.");
                        after.run();
                    });
        } else {
            UiKit.async(owner, executor, () -> {
                users.update(existing.id(), fullName.getText(), selectedRole.id(), active.isSelected());
                return existing.id();
            }, id -> {
                UiKit.info(owner, "User updated.");
                after.run();
            });
        }
    }

    // ---------------------------------------------------------------------- roles

    private JComponent rolesPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 14));
        panel.setOpaque(false);

        JComboBox<RoleService.RoleView> roleBox = new JComboBox<>();
        roleBox.setRenderer((list, value, index, selected, focus) -> {
            JLabel label = new JLabel(value == null ? "" : value.name());
            label.setOpaque(true);
            label.setBackground(selected ? Theme.SURFACE_SELECTED : Theme.SURFACE_CARD);
            return label;
        });
        JTextField maxDiscount = UiKit.field("Max discount %");
        maxDiscount.setPreferredSize(new Dimension(130, 32));
        JButton save = UiKit.primary("Save permissions");
        JLabel status = UiKit.caption(" ");

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        controls.setOpaque(false);
        controls.add(roleBox);
        controls.add(new JLabel("Max discount %"));
        controls.add(maxDiscount);
        controls.add(save);
        controls.add(status);

        JPanel top = new JPanel();
        top.setOpaque(false);
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.add(UiKit.header("Roles & permissions", "Changes take effect at next sign-in", null));
        top.add(Box.createVerticalStrut(12));
        top.add(controls);
        panel.add(top, BorderLayout.NORTH);

        JPanel matrix = new JPanel();
        matrix.setOpaque(false);
        matrix.setLayout(new BoxLayout(matrix, BoxLayout.Y_AXIS));
        JScrollPane scroll = UiKit.scroll(matrix);
        panel.add(scroll, BorderLayout.CENTER);

        Map<Long, Map<String, JCheckBox>> boxes = new LinkedHashMap<>();
        Map<Long, Set<String>> selected = new LinkedHashMap<>();
        List<RoleService.PermissionRow> loadedPermissions = new ArrayList<>();
        Runnable[] reload = new Runnable[1];

        reload[0] = () -> UiKit.async(owner, executor, () -> {
            List<RoleService.RoleView> roleRows = roles.roles();
            List<RoleService.PermissionRow> permissionRows = roles.permissions();
            Map<Long, Set<String>> current = new LinkedHashMap<>();
            for (RoleService.RoleView role : roleRows) {
                current.put(role.id(), roles.rolePermissions(role.id()));
            }
            return new Object[]{roleRows, permissionRows, current};
        }, data -> {
            @SuppressWarnings("unchecked")
            List<RoleService.RoleView> roleRows = (List<RoleService.RoleView>) data[0];
            @SuppressWarnings("unchecked")
            List<RoleService.PermissionRow> permissionRows = (List<RoleService.PermissionRow>) data[1];
            @SuppressWarnings("unchecked")
            Map<Long, Set<String>> current = (Map<Long, Set<String>>) data[2];
            roleBox.removeAllItems();
            boxes.clear();
            selected.clear();
            selected.putAll(current);
            loadedPermissions.clear();
            loadedPermissions.addAll(permissionRows);
            for (RoleService.RoleView role : roleRows) {
                roleBox.addItem(role);
            }
            matrix.removeAll();
            String area = "";
            JPanel areaPanel = null;
            for (RoleService.PermissionRow permission : permissionRows) {
                if (!permission.area().equals(area)) {
                    area = permission.area();
                    areaPanel = new JPanel(new GridLayout(0, 2, 8, 2));
                    areaPanel.setOpaque(false);
                    areaPanel.setBorder(BorderFactory.createTitledBorder(area));
                    matrix.add(areaPanel);
                    matrix.add(Box.createVerticalStrut(6));
                }
                JCheckBox check = new JCheckBox(permission.label() + "  (" + permission.code() + ")");
                check.setOpaque(false);
                check.setFont(FontLoader.regular(11.5f));
                areaPanel.add(check);
                for (RoleService.RoleView role : roleRows) {
                    boxes.computeIfAbsent(role.id(), key -> new LinkedHashMap<>())
                            .put(permission.code(), check);
                }
            }
            // Each permission has one checkbox per role; swap visibility when a role is chosen.
            Runnable apply = () -> {
                RoleService.RoleView role = (RoleService.RoleView) roleBox.getSelectedItem();
                if (role == null) {
                    return;
                }
                Set<String> granted = selected.getOrDefault(role.id(), Set.of());
                for (RoleService.PermissionRow permission : permissionRows) {
                    JCheckBox check = findCheck(matrix, permission.code());
                    if (check != null) {
                        check.setSelected(granted.contains(permission.code()));
                        check.setEnabled(!role.locked());
                    }
                }
                maxDiscount.setText(Money.formatPercent(role.maxDiscountPpm()).replace("%", ""));
                maxDiscount.setEnabled(!role.locked());
                save.setEnabled(!role.locked());
                status.setText(role.locked() ? "System Admin is locked to all permissions." : " ");
            };
            roleBox.addActionListener(e -> apply.run());
            apply.run();
            status.setText(" ");
        });

        save.addActionListener(e -> {
            RoleService.RoleView role = (RoleService.RoleView) roleBox.getSelectedItem();
            if (role == null) {
                return;
            }
            Set<String> chosen = new LinkedHashSet<>();
            for (RoleService.PermissionRow permission : loadedPermissions) {
                JCheckBox check = findCheck(matrix, permission.code());
                if (check != null && check.isSelected()) {
                    chosen.add(permission.code());
                }
            }
            long ppm;
            try {
                ppm = new java.math.BigDecimal(maxDiscount.getText().trim().replace("%", ""))
                        .movePointRight(4).longValueExact();
            } catch (Exception ex) {
                UiKit.error(owner, "Enter a discount percentage, e.g. 10.");
                return;
            }
            UiKit.async(owner, executor, () -> {
                roles.update(role.id(), chosen, ppm);
                return role.id();
            }, id -> {
                UiKit.info(owner, "Permissions saved. Staff see the change at next sign-in.");
                reload[0].run();
            });
        });

        reload[0].run();
        panel.putClientProperty("refresh", reload[0]);
        return panel;
    }

    private JCheckBox findCheck(JPanel matrix, String code) {
        return findCheckRecursive(matrix, code);
    }

    private JCheckBox findCheckRecursive(java.awt.Container container, String code) {
        for (Component component : container.getComponents()) {
            if (component instanceof JCheckBox check && check.getText().endsWith("(" + code + ")")) {
                return check;
            }
            if (component instanceof java.awt.Container child) {
                JCheckBox found = findCheckRecursive(child, code);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    // --------------------------------------------------------------------- system

    private JComponent systemPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 14));
        panel.setOpaque(false);

        JButton save = UiKit.primary("Save settings");
        JButton reload = UiKit.secondary("Reload");
        JButton backup = UiKit.secondary("Create backup");
        JLabel status = UiKit.caption("Database: " + AppPaths.db());
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        actions.setOpaque(false);
        actions.add(save);
        actions.add(reload);
        if (sessions.has("BACKUP_RUN")) {
            actions.add(backup);
        }
        actions.add(status);

        JPanel top = new JPanel();
        top.setOpaque(false);
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.add(UiKit.header("System", "Store details, receipt and kiosk settings", null));
        top.add(Box.createVerticalStrut(12));
        top.add(actions);
        panel.add(top, BorderLayout.NORTH);

        DefaultTableModel model = new DefaultTableModel(new String[]{"Key", "Value"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return column == 1;
            }
        };
        JTable table = UiKit.table(model);
        UiKit.columns(table, 240, 420);
        panel.add(UiKit.scroll(table), BorderLayout.CENTER);

        Runnable load = () -> UiKit.async(owner, executor, settings::all, values -> {
            model.setRowCount(0);
            values.forEach((key, value) -> model.addRow(new Object[]{key, value}));
        });
        reload.addActionListener(e -> load.run());
        save.addActionListener(e -> {
            Map<String, String> values = new LinkedHashMap<>();
            for (int row = 0; row < model.getRowCount(); row++) {
                values.put(String.valueOf(model.getValueAt(row, 0)),
                        String.valueOf(model.getValueAt(row, 1)));
            }
            UiKit.async(owner, executor, () -> {
                settings.save(values);
                return values.size();
            }, count -> {
                UiKit.info(owner, "Settings saved. Some changes apply at next sign-in.");
                load.run();
            });
        });
        backup.addActionListener(e -> UiKit.async(owner, executor, () -> {
            try {
                return backups.runBackup();
            } catch (java.io.IOException ex) {
                throw new java.io.UncheckedIOException(ex);
            }
        }, path -> UiKit.info(owner, "Backup created:\n" + path)));
        load.run();
        panel.putClientProperty("refresh", load);
        return panel;
    }

    // -------------------------------------------------------------------- helpers

    private static DefaultTableModel model(String[] columns) {
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
        int row = table.convertRowIndexToModel(viewRow);
        Object id = model.getValueAt(row, 0);
        if (id instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(id));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static LocalDate parseDate(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(text.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static int parseInt(String text, int fallback) {
        try {
            return text == null || text.isBlank() ? fallback : Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static JPanel formPanel() {
        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        return form;
    }

    private static void addRow(JPanel form, int row, String label, JComponent field) {
        GridBagConstraints left = new GridBagConstraints();
        left.gridx = 0;
        left.gridy = row;
        left.anchor = GridBagConstraints.WEST;
        left.insets = new Insets(4, 4, 4, 12);
        JLabel caption = UiKit.label(label, 12f, Theme.TEXT_MUTED, false);
        form.add(caption, left);

        GridBagConstraints right = new GridBagConstraints();
        right.gridx = 1;
        right.gridy = row;
        right.weightx = 1;
        right.fill = GridBagConstraints.HORIZONTAL;
        right.insets = new Insets(4, 4, 4, 4);
        if (field instanceof JTextField text) {
            text.setPreferredSize(new Dimension(280, 32));
        }
        form.add(field, right);
    }
}
