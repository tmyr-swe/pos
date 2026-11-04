package com.chegg.pos.ui.workspace.kiosk;

import com.chegg.pos.app.FontLoader;
import com.chegg.pos.app.Theme;
import com.chegg.pos.model.KioskOrder;
import com.chegg.pos.model.User;
import com.chegg.pos.service.CatalogService;
import com.chegg.pos.service.KioskOrderService;
import com.chegg.pos.service.SettingsService;
import com.chegg.pos.ui.common.RoundedPanel;
import com.chegg.pos.ui.common.UiAssets;
import com.chegg.pos.ui.common.UiKit;
import com.chegg.pos.util.Money;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPasswordField;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.table.DefaultTableModel;
import java.awt.AWTEvent;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Toolkit;
import java.awt.event.AWTEventListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * Touch-first guided self-checkout: Welcome - Browse - Review - Confirmation.
 * Orders are quotes; stock is only reserved when the cashier takes payment.
 */
public class KioskWorkspace {

    private static final String STEP_WELCOME = "welcome";
    private static final String STEP_BROWSE = "browse";
    private static final String STEP_REVIEW = "review";
    private static final String STEP_CONFIRM = "confirm";
    private static final String ALL = "All products";

    private final CatalogService catalog;
    private final KioskOrderService kioskOrders;
    private final SettingsService settings;
    private final ExecutorService executor;
    private final JFrame owner;
    private final Runnable signOut;

    private final JPanel root = new JPanel(new BorderLayout());
    private final CardLayout cards = new CardLayout();
    private final JPanel body = new JPanel(cards);
    private final JLabel progress = UiKit.label("", 12f, Theme.TEXT_MUTED, true);
    private final JLabel storeTitle = UiKit.label("Chegg", 20f, Theme.ON_PRIMARY, true);
    private final JLabel mottoLabel = UiKit.label("This project was hard", 12f,
            new Color(0xEF, 0xDF, 0xBB, 190), false);
    private final JLabel welcomeMottoLabel = UiKit.label("This project was hard", 20f,
            Theme.TEXT_MUTED, false);

    private final JPanel chips = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
    private final JPanel grid = new JPanel(new GridLayout(0, 4, 14, 14));
    private final JLabel cartSummary = UiKit.label("Your order is empty", 16f, Theme.TEXT, true);
    private final JLabel cartTotal = UiKit.label(Money.format(0), 30f, Theme.PRIMARY, true);
    private final JButton reviewButton = UiKit.primary("Review order  →");

    private final DefaultTableModel reviewModel = new DefaultTableModel(
            new Object[]{"Item", "Qty", "Price", "Line total"}, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };
    private final JLabel reviewTotal = UiKit.label(Money.format(0), 34f, Theme.PRIMARY, true);
    private final JLabel confirmCode = UiKit.label("", 44f, Theme.PRIMARY, true);
    private final JLabel confirmTotal = UiKit.label("", 24f, Theme.TEXT, true);

    private final List<CatalogService.ProductRow> products = new ArrayList<>();
    private final Map<Long, Integer> quantities = new LinkedHashMap<>();
    private final Map<Long, JLabel> quantityLabels = new LinkedHashMap<>();
    private final Map<Long, JButton> minusButtons = new LinkedHashMap<>();

    private volatile int idleResetSeconds = 60;
    private long lastActivity = System.currentTimeMillis();
    private String activeCategory = ALL;
    private String currentStep = STEP_WELCOME;
    private AWTEventListener activityListener;
    private Timer idleTimer;

    public KioskWorkspace(org.springframework.context.ApplicationContext context,
                          User user, JFrame owner, Runnable signOut) {
        this.catalog = context.getBean(CatalogService.class);
        this.kioskOrders = context.getBean(KioskOrderService.class);
        this.settings = context.getBean(SettingsService.class);
        this.executor = context.getBean(ExecutorService.class);
        this.owner = owner;
        this.signOut = signOut;

        root.setBackground(Theme.SURFACE);
        root.add(buildTopBar(), BorderLayout.NORTH);
        body.setOpaque(false);
        body.setBorder(BorderFactory.createEmptyBorder(16, 22, 22, 22));
        body.add(buildWelcome(), STEP_WELCOME);
        body.add(buildBrowse(), STEP_BROWSE);
        body.add(buildReview(), STEP_REVIEW);
        body.add(buildConfirmation(), STEP_CONFIRM);
        root.add(body, BorderLayout.CENTER);

        loadCatalog();
        loadSettings();

        activityListener = event -> lastActivity = System.currentTimeMillis();
        Toolkit.getDefaultToolkit().addAWTEventListener(activityListener,
                AWTEvent.MOUSE_EVENT_MASK | AWTEvent.MOUSE_MOTION_EVENT_MASK
                        | AWTEvent.KEY_EVENT_MASK);
        owner.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                stopTimers();
            }
        });

        idleTimer = new Timer(1000, e -> checkIdle());
        idleTimer.start();
        showStep(STEP_WELCOME);
    }

    public JPanel root() {
        return root;
    }

    // -------------------------------------------------------------------- chrome

    private JPanel buildTopBar() {
        JPanel bar = new JPanel(new BorderLayout(16, 0));
        bar.setBackground(Theme.PRIMARY);
        bar.setBorder(BorderFactory.createEmptyBorder(10, 20, 10, 20));

        JPanel brand = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
        brand.setOpaque(false);
        RoundedPanel tile = new RoundedPanel(new BorderLayout(), Theme.SURFACE_LOGO, 10);
        tile.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        tile.setPreferredSize(new Dimension(84, 50));
        tile.add(new JLabel(UiAssets.logoIcon(70, 40), SwingConstants.CENTER), BorderLayout.CENTER);
        brand.add(tile);
        JPanel names = new JPanel();
        names.setOpaque(false);
        names.setLayout(new BoxLayout(names, BoxLayout.Y_AXIS));
        names.add(storeTitle);
        mottoLabel.setForeground(new Color(0xEF, 0xDF, 0xBB, 195));
        names.add(mottoLabel);
        brand.add(names);
        bar.add(brand, BorderLayout.WEST);

        JPanel middle = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
        middle.setOpaque(false);
        progress.setFont(FontLoader.medium(12.5f));
        progress.setForeground(new Color(0xEF, 0xDF, 0xBB, 210));
        middle.add(progress);
        bar.add(middle, BorderLayout.CENTER);

        JButton staff = UiKit.secondary("Staff · hold 3s");
        staff.setToolTipText("Hold for three seconds to return to the staff sign-in screen.");
        Timer hold = new Timer(3000, e -> promptStaffPin());
        hold.setRepeats(false);
        staff.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                lastActivity = System.currentTimeMillis();
                hold.restart();
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                hold.stop();
            }

            @Override
            public void mouseExited(MouseEvent e) {
                hold.stop();
            }
        });
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        right.setOpaque(false);
        right.add(staff);
        bar.add(right, BorderLayout.EAST);
        return bar;
    }

    // -------------------------------------------------------------------- steps

    private JComponent buildWelcome() {
        JPanel panel = new JPanel(new java.awt.GridBagLayout());
        panel.setOpaque(false);

        JPanel column = new JPanel();
        column.setOpaque(false);
        column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));

        RoundedPanel logoCard = new RoundedPanel(new BorderLayout(), Theme.SURFACE_LOGO, 24);
        logoCard.setBorder(BorderFactory.createEmptyBorder(22, 26, 22, 26));
        logoCard.setAlignmentX(Component.CENTER_ALIGNMENT);
        logoCard.setMaximumSize(new Dimension(520, 300));
        logoCard.add(new JLabel(UiAssets.logoIcon(440, 250), SwingConstants.CENTER),
                BorderLayout.CENTER);
        column.add(logoCard);
        column.add(Box.createVerticalStrut(28));

        JLabel welcome = UiKit.label("Welcome to Chegg", 44f, Theme.TEXT, true);
        welcome.setAlignmentX(Component.CENTER_ALIGNMENT);
        column.add(welcome);
        column.add(Box.createVerticalStrut(8));

        welcomeMottoLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        column.add(welcomeMottoLabel);
        column.add(Box.createVerticalStrut(30));

        JButton start = UiKit.primary("Touch to start  →");
        start.setFont(FontLoader.semiBold(24f));
        start.setAlignmentX(Component.CENTER_ALIGNMENT);
        start.setMaximumSize(new Dimension(420, 84));
        start.setPreferredSize(new Dimension(420, 84));
        start.addActionListener(e -> {
            lastActivity = System.currentTimeMillis();
            showStep(STEP_BROWSE);
        });
        column.add(start);
        column.add(Box.createVerticalStrut(18));
        JLabel hint = UiKit.caption("Shop at your own pace. Pay at the counter when you are done.");
        hint.setFont(FontLoader.regular(14f));
        hint.setAlignmentX(Component.CENTER_ALIGNMENT);
        column.add(hint);

        panel.add(column);
        return panel;
    }

    private JComponent buildBrowse() {
        JPanel panel = new JPanel(new BorderLayout(18, 12));
        panel.setOpaque(false);

        chips.setOpaque(false);
        JScrollPane chipScroll = UiKit.scroll(chips);
        chipScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        chipScroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_NEVER);
        chipScroll.setPreferredSize(new Dimension(0, 54));
        panel.add(chipScroll, BorderLayout.NORTH);

        grid.setOpaque(false);
        JPanel gridWrap = new JPanel(new BorderLayout());
        gridWrap.setOpaque(false);
        gridWrap.add(grid, BorderLayout.NORTH);
        JScrollPane scroll = UiKit.scroll(gridWrap);
        scroll.getVerticalScrollBar().setUnitIncrement(28);
        panel.add(scroll, BorderLayout.CENTER);

        RoundedPanel summary = UiKit.card(new BorderLayout(0, 14));
        summary.setPreferredSize(new Dimension(340, 0));
        JLabel heading = UiKit.section("Your order");
        summary.add(heading, BorderLayout.NORTH);

        JPanel summaryBody = new JPanel();
        summaryBody.setOpaque(false);
        summaryBody.setLayout(new BoxLayout(summaryBody, BoxLayout.Y_AXIS));
        cartSummary.setAlignmentX(Component.LEFT_ALIGNMENT);
        summaryBody.add(cartSummary);
        summaryBody.add(Box.createVerticalStrut(10));
        JLabel totalLabel = UiKit.caption("TOTAL");
        totalLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        summaryBody.add(totalLabel);
        cartTotal.setAlignmentX(Component.LEFT_ALIGNMENT);
        summaryBody.add(cartTotal);
        summaryBody.add(Box.createVerticalStrut(22));
        reviewButton.setFont(FontLoader.semiBold(18f));
        reviewButton.setMaximumSize(new Dimension(Integer.MAX_VALUE, 68));
        reviewButton.setPreferredSize(new Dimension(300, 68));
        reviewButton.setAlignmentX(Component.LEFT_ALIGNMENT);
        reviewButton.addActionListener(e -> openReview());
        summaryBody.add(reviewButton);
        summaryBody.add(Box.createVerticalStrut(12));
        JLabel note = UiKit.caption("Prices include no VAT. Stock is confirmed at the counter.");
        note.setAlignmentX(Component.LEFT_ALIGNMENT);
        summaryBody.add(note);
        summary.add(summaryBody, BorderLayout.CENTER);

        JButton back = UiKit.secondary("← Back to welcome");
        back.addActionListener(e -> resetToWelcome());
        summary.add(back, BorderLayout.SOUTH);
        panel.add(summary, BorderLayout.EAST);
        return panel;
    }

    private JComponent buildReview() {
        JPanel panel = new JPanel(new BorderLayout(0, 14));
        panel.setOpaque(false);

        JPanel top = new JPanel(new BorderLayout());
        top.setOpaque(false);
        JLabel heading = UiKit.label("Review your order", 30f, Theme.TEXT, true);
        top.add(heading, BorderLayout.WEST);
        JButton back = UiKit.secondary("← Add more items");
        back.addActionListener(e -> {
            lastActivity = System.currentTimeMillis();
            showStep(STEP_BROWSE);
        });
        top.add(back, BorderLayout.EAST);
        panel.add(top, BorderLayout.NORTH);

        JTable table = UiKit.table(reviewModel);
        table.setRowHeight(46);
        table.setFont(FontLoader.regular(16f));
        UiKit.columns(table, 420, 90, 150, 170);
        table.getColumnModel().getColumn(2).setCellRenderer(UiKit.moneyRenderer());
        table.getColumnModel().getColumn(3).setCellRenderer(UiKit.moneyRenderer());
        panel.add(UiKit.scroll(table), BorderLayout.CENTER);

        RoundedPanel footer = UiKit.card(new BorderLayout(16, 0));
        JPanel totals = new JPanel();
        totals.setOpaque(false);
        totals.setLayout(new BoxLayout(totals, BoxLayout.Y_AXIS));
        totals.add(UiKit.caption("TOTAL TO PAY AT THE COUNTER"));
        totals.add(confirmTotalLabel());
        footer.add(totals, BorderLayout.WEST);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        actions.setOpaque(false);
        JButton place = UiKit.primary("Place order  →");
        place.setFont(FontLoader.semiBold(20f));
        place.setPreferredSize(new Dimension(280, 66));
        place.addActionListener(e -> placeOrder());
        actions.add(place);
        footer.add(actions, BorderLayout.EAST);
        panel.add(footer, BorderLayout.SOUTH);
        return panel;
    }

    private JLabel confirmTotalLabel() {
        JLabel total = UiKit.label("", 34f, Theme.PRIMARY, true);
        // Keep the review total in sync with the same field the confirmation uses.
        reviewTotal.setName("reviewTotal");
        total.setName("reviewFooterTotal");
        reviewFooter = total;
        return total;
    }

    private JLabel reviewFooter;

    private JComponent buildConfirmation() {
        JPanel panel = new JPanel(new java.awt.GridBagLayout());
        panel.setOpaque(false);

        JPanel column = new JPanel();
        column.setOpaque(false);
        column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));

        JLabel tick = UiKit.label("✓", 84f, Theme.SUCCESS, true);
        tick.setAlignmentX(Component.CENTER_ALIGNMENT);
        column.add(tick);
        column.add(Box.createVerticalStrut(8));

        JLabel heading = UiKit.label("Order placed", 40f, Theme.TEXT, true);
        heading.setAlignmentX(Component.CENTER_ALIGNMENT);
        column.add(heading);
        column.add(Box.createVerticalStrut(18));

        JLabel codeCaption = UiKit.caption("YOUR ORDER NUMBER");
        codeCaption.setFont(FontLoader.medium(13f));
        codeCaption.setAlignmentX(Component.CENTER_ALIGNMENT);
        column.add(codeCaption);
        confirmCode.setAlignmentX(Component.CENTER_ALIGNMENT);
        column.add(confirmCode);
        column.add(Box.createVerticalStrut(12));
        confirmTotal.setAlignmentX(Component.CENTER_ALIGNMENT);
        column.add(confirmTotal);
        column.add(Box.createVerticalStrut(8));

        JLabel pay = UiKit.label("Please pay at the counter.", 22f, Theme.TEXT_MUTED, false);
        pay.setAlignmentX(Component.CENTER_ALIGNMENT);
        column.add(pay);
        column.add(Box.createVerticalStrut(30));

        JButton start = UiKit.primary("Start a new order");
        start.setFont(FontLoader.semiBold(22f));
        start.setAlignmentX(Component.CENTER_ALIGNMENT);
        start.setMaximumSize(new Dimension(420, 78));
        start.setPreferredSize(new Dimension(420, 78));
        start.addActionListener(e -> resetToWelcome());
        column.add(start);

        panel.add(column);
        return panel;
    }

    // -------------------------------------------------------------------- data

    private void loadCatalog() {
        UiKit.async(owner, executor, () -> {
            List<CatalogService.ProductRow> rows = catalog.listProducts(true);
            List<CatalogService.CategoryRow> categories = catalog.categories();
            return new Object[]{rows, categories};
        }, data -> {
            @SuppressWarnings("unchecked")
            List<CatalogService.ProductRow> rows = (List<CatalogService.ProductRow>) data[0];
            @SuppressWarnings("unchecked")
            List<CatalogService.CategoryRow> categories =
                    (List<CatalogService.CategoryRow>) data[1];
            products.clear();
            products.addAll(rows);
            renderChips(categories);
            rebuildGrid();
            updateSummary();
        }, error -> UiKit.error(owner, "Could not load the catalog: " + error.getMessage()));
    }

    private void loadSettings() {
        UiKit.async(owner, executor, () -> {
            int idle = settings.getInt("kiosk.idleResetSeconds", 60);
            String store = settings.storeName();
            String motto = settings.get("store.motto", "This project was hard");
            return new Object[]{idle, store, motto};
        }, data -> {
            idleResetSeconds = Math.max(15, (Integer) data[0]);
            storeTitle.setText((String) data[1]);
            mottoLabel.setText((String) data[2]);
            welcomeMottoLabel.setText((String) data[2]);
        }, error -> { });
    }

    private void renderChips(List<CatalogService.CategoryRow> categories) {
        chips.removeAll();
        chips.add(chip(ALL));
        for (CatalogService.CategoryRow category : categories) {
            chips.add(chip(category.name()));
        }
        chips.revalidate();
        chips.repaint();
    }

    private JButton chip(String name) {
        JButton button = name.equals(activeCategory) ? UiKit.primary(name) : UiKit.secondary(name);
        button.setFont(FontLoader.medium(14f));
        button.addActionListener(e -> {
            lastActivity = System.currentTimeMillis();
            activeCategory = name;
            for (Component component : chips.getComponents()) {
                if (component instanceof JButton other) {
                    boolean selected = other.getText().equals(name);
                    other.setBackground(selected ? Theme.PRIMARY : Theme.SURFACE_CARD);
                    other.setForeground(selected ? Theme.ON_PRIMARY : Theme.TEXT);
                    other.setFont(selected ? FontLoader.semiBold(14f) : FontLoader.medium(14f));
                }
            }
            rebuildGrid();
        });
        return button;
    }

    private void rebuildGrid() {
        grid.removeAll();
        quantityLabels.clear();
        minusButtons.clear();
        for (CatalogService.ProductRow product : products) {
            if (ALL.equals(activeCategory) || activeCategory.equals(product.category())) {
                grid.add(productCard(product));
            }
        }
        grid.revalidate();
        grid.repaint();
    }

    private JComponent productCard(CatalogService.ProductRow product) {
        RoundedPanel card = new RoundedPanel(new BorderLayout(0, 8), Theme.SURFACE_CARD, 18);
        card.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));
        card.setPreferredSize(new Dimension(250, 210));

        JPanel text = new JPanel();
        text.setOpaque(false);
        text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
        JLabel name = UiKit.label("<html><div style='width:205px'>"
                + escape(product.name()) + "</div></html>", 16f, Theme.TEXT, true);
        name.setAlignmentX(Component.LEFT_ALIGNMENT);
        text.add(name);
        text.add(Box.createVerticalStrut(4));
        JLabel category = UiKit.caption(product.category());
        category.setAlignmentX(Component.LEFT_ALIGNMENT);
        text.add(category);
        card.add(text, BorderLayout.NORTH);

        JLabel price = UiKit.label(Money.format(product.priceCents()), 24f, Theme.PRIMARY, true);
        price.setVerticalAlignment(SwingConstants.BOTTOM);
        card.add(price, BorderLayout.CENTER);

        int quantity = quantities.getOrDefault(product.id(), 0);
        JPanel controls = new JPanel(new BorderLayout(8, 0));
        controls.setOpaque(false);

        JLabel inCart = UiKit.label(quantity == 0 ? "" : "× " + quantity,
                15f, Theme.SUCCESS, true);
        quantityLabels.put(product.id(), inCart);

        JButton minus = UiKit.secondary("−");
        minus.setFont(FontLoader.semiBold(20f));
        minus.setPreferredSize(new Dimension(52, 46));
        minus.setEnabled(quantity > 0);
        minus.addActionListener(e -> removeOne(product));
        minusButtons.put(product.id(), minus);

        JButton add = UiKit.primary("+ Add");
        add.setFont(FontLoader.semiBold(15f));
        add.setPreferredSize(new Dimension(112, 46));
        add.addActionListener(e -> addOne(product));

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        right.setOpaque(false);
        right.add(inCart);
        right.add(minus);
        right.add(add);
        controls.add(right, BorderLayout.EAST);
        card.add(controls, BorderLayout.SOUTH);
        return card;
    }

    private void addOne(CatalogService.ProductRow product) {
        lastActivity = System.currentTimeMillis();
        int quantity = quantities.getOrDefault(product.id(), 0);
        if (product.stock() > 0 && quantity >= product.stock()) {
            UiKit.info(owner, "Only " + product.stock() + " of " + product.name()
                    + " are available.");
            return;
        }
        quantities.put(product.id(), quantity + 1);
        refreshCard(product.id());
        updateSummary();
    }

    private void removeOne(CatalogService.ProductRow product) {
        lastActivity = System.currentTimeMillis();
        int quantity = quantities.getOrDefault(product.id(), 0);
        if (quantity <= 1) {
            quantities.remove(product.id());
        } else {
            quantities.put(product.id(), quantity - 1);
        }
        refreshCard(product.id());
        updateSummary();
    }

    private void refreshCard(long productId) {
        int quantity = quantities.getOrDefault(productId, 0);
        JLabel label = quantityLabels.get(productId);
        if (label != null) {
            label.setText(quantity == 0 ? "" : "× " + quantity);
        }
        JButton minus = minusButtons.get(productId);
        if (minus != null) {
            minus.setEnabled(quantity > 0);
        }
    }

    private void refreshAllCards() {
        for (Long id : new ArrayList<>(quantityLabels.keySet())) {
            refreshCard(id);
        }
    }

    private long previewTotalCents() {
        long total = 0;
        for (Map.Entry<Long, Integer> entry : quantities.entrySet()) {
            for (CatalogService.ProductRow product : products) {
                if (product.id() == entry.getKey()) {
                    total += product.priceCents() * entry.getValue();
                    break;
                }
            }
        }
        return total;
    }

    private int itemCount() {
        return quantities.values().stream().mapToInt(Integer::intValue).sum();
    }

    private void updateSummary() {
        int count = itemCount();
        cartSummary.setText(count == 0 ? "Your order is empty"
                : count + (count == 1 ? " item" : " items") + " in your order");
        cartTotal.setText(Money.format(previewTotalCents()));
        reviewButton.setEnabled(count > 0);
    }

    // -------------------------------------------------------------------- review

    private void openReview() {
        lastActivity = System.currentTimeMillis();
        if (quantities.isEmpty()) {
            UiKit.info(owner, "Add at least one item before reviewing.");
            return;
        }
        reviewModel.setRowCount(0);
        for (Map.Entry<Long, Integer> entry : quantities.entrySet()) {
            CatalogService.ProductRow product = product(entry.getKey());
            if (product == null) {
                continue;
            }
            long line = product.priceCents() * entry.getValue();
            reviewModel.addRow(new Object[]{product.name(), entry.getValue(),
                    product.priceCents(), line});
        }
        String total = Money.format(previewTotalCents());
        reviewTotal.setText(total);
        if (reviewFooter != null) {
            reviewFooter.setText(total);
        }
        showStep(STEP_REVIEW);
    }

    private void placeOrder() {
        lastActivity = System.currentTimeMillis();
        if (quantities.isEmpty()) {
            showStep(STEP_BROWSE);
            return;
        }
        List<KioskOrderService.PlaceLine> lines = new ArrayList<>();
        for (Map.Entry<Long, Integer> entry : quantities.entrySet()) {
            lines.add(new KioskOrderService.PlaceLine(entry.getKey(), entry.getValue()));
        }
        reviewButton.setEnabled(false);
        UiKit.async(owner, executor, () -> kioskOrders.place(lines), order -> {
            reviewButton.setEnabled(true);
            confirmCode.setText(order.code());
            confirmTotal.setText(Money.format(order.totalCents()));
            quantities.clear();
            refreshAllCards();
            updateSummary();
            showStep(STEP_CONFIRM);
            lastActivity = System.currentTimeMillis();
        }, error -> {
            reviewButton.setEnabled(true);
            UiKit.error(owner, "Could not place the order: " + error.getMessage());
        });
    }

    private CatalogService.ProductRow product(long id) {
        return products.stream().filter(row -> row.id() == id).findFirst().orElse(null);
    }

    // ------------------------------------------------------------- idle & staff

    private void checkIdle() {
        if (STEP_WELCOME.equals(currentStep)) {
            return;
        }
        long idleMillis = System.currentTimeMillis() - lastActivity;
        if (idleMillis >= idleResetSeconds * 1000L) {
            resetToWelcome();
        }
    }

    private void resetToWelcome() {
        quantities.clear();
        refreshAllCards();
        updateSummary();
        reviewModel.setRowCount(0);
        showStep(STEP_WELCOME);
        lastActivity = System.currentTimeMillis();
    }

    private void showStep(String step) {
        currentStep = step;
        cards.show(body, step);
        progress.setText(switch (step) {
            case STEP_BROWSE -> "1  Browse    2  Review    3  Confirmation";
            case STEP_REVIEW -> "1  Browse    2  Review    3  Confirmation";
            case STEP_CONFIRM -> "1  Browse    2  Review    3  Confirmation";
            default -> "Welcome";
        });
        int index = switch (step) {
            case STEP_BROWSE -> 1;
            case STEP_REVIEW -> 2;
            case STEP_CONFIRM -> 3;
            default -> 0;
        };
        progress.setText(index == 0 ? "Welcome"
                : "Step " + index + " of 3   ·   "
                        + (index == 1 ? "Browse" : index == 2 ? "Review" : "Pay at counter"));
        lastActivity = System.currentTimeMillis();
    }

    private void promptStaffPin() {
        lastActivity = System.currentTimeMillis();
        JPasswordField pin = new JPasswordField();
        pin.setFont(FontLoader.regular(20f));
        pin.setPreferredSize(new Dimension(220, 42));
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.add(new JLabel("Enter the staff PIN to leave the kiosk:"), BorderLayout.NORTH);
        panel.add(pin, BorderLayout.CENTER);
        int choice = javax.swing.JOptionPane.showConfirmDialog(owner, panel,
                "Staff exit", javax.swing.JOptionPane.OK_CANCEL_OPTION,
                javax.swing.JOptionPane.PLAIN_MESSAGE);
        if (choice != javax.swing.JOptionPane.OK_OPTION) {
            return;
        }
        String entered = new String(pin.getPassword());
        javax.swing.JOptionPane pane = null;
        UiKit.async(owner, executor, () -> settings.get("kiosk.staffPin", "2468"),
                stored -> {
                    if (entered != null && entered.equals(stored)) {
                        stopTimers();
                        signOut.run();
                    } else {
                        UiKit.error(owner, "That staff PIN is not correct.");
                    }
                });
    }

    private void stopTimers() {
        if (idleTimer != null) {
            idleTimer.stop();
        }
        if (activityListener != null) {
            Toolkit.getDefaultToolkit().removeAWTEventListener(activityListener);
            activityListener = null;
        }
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("&", "&amp;")
                .replace("<", "&lt;").replace(">", "&gt;");
    }
}
