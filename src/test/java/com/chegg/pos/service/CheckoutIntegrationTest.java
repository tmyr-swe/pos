package com.chegg.pos.service;

import com.chegg.pos.app.AppConfig;
import com.chegg.pos.model.KioskOrder;
import com.chegg.pos.model.Shift;
import com.chegg.pos.model.ZReport;
import com.chegg.pos.model.enums.KioskOrderStatus;
import com.chegg.pos.model.enums.SaleChannel;
import com.chegg.pos.model.enums.ShiftStatus;
import com.chegg.pos.service.calc.CartCalculator;
import com.chegg.pos.service.calc.CartItem;
import com.chegg.pos.service.calc.CartSnapshot;
import com.chegg.pos.service.calc.Discount;
import com.chegg.pos.service.calc.TenderCalculator;
import com.chegg.pos.service.calc.TenderPayment;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end service test against a throwaway SQLite database: opening a
 * shift, a rolled-back oversell, a cash sale, a Z-report and a kiosk order.
 */
class CheckoutIntegrationTest {

    @TempDir
    static Path home;

    private static AnnotationConfigApplicationContext context;
    private static JdbcTemplate jdbc;
    private static AuthService auth;
    private static CatalogService catalog;
    private static ShiftService shifts;
    private static SaleService sales;
    private static KioskOrderService kioskOrders;
    private static ZReportService zReports;
    private static String previousHome;
    private static String previousLogs;

    @BeforeAll
    static void startContext() {
        previousHome = System.getProperty("pos.home");
        previousLogs = System.getProperty("pos.logs");
        System.setProperty("pos.home", home.toString());
        System.setProperty("pos.logs", home.resolve("logs").toString());

        context = new AnnotationConfigApplicationContext(AppConfig.class);
        jdbc = context.getBean(JdbcTemplate.class);
        auth = context.getBean(AuthService.class);
        catalog = context.getBean(CatalogService.class);
        shifts = context.getBean(ShiftService.class);
        sales = context.getBean(SaleService.class);
        kioskOrders = context.getBean(KioskOrderService.class);
        zReports = context.getBean(ZReportService.class);
    }

    @AfterAll
    static void stopContext() {
        if (context != null) {
            context.close();
        }
        restore("pos.home", previousHome);
        restore("pos.logs", previousLogs);
    }

    private static void restore(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    @Test
    void checkoutRollbackShiftCloseAndKioskCompletion() {
        LoginResult login = auth.login("sysadmin", "change-me");
        assertTrue(login.success(), login.message());
        assertNull(shifts.currentOpenShift());

        Shift shift = shifts.openShift(20_000, "integration test");
        assertNotNull(shift);
        assertEquals(ShiftStatus.OPEN, shift.status());

        CatalogService.ProductRow product = catalog.listProducts(true).stream()
                .max(Comparator.comparingInt(CatalogService.ProductRow::stock))
                .orElseThrow();
        int stockBefore = product.stock();
        assertTrue(stockBefore >= 4, "seed stock should support the scenario");

        // An oversell must roll back without writing a sale or changing stock.
        CartSnapshot oversell = cart(product, stockBefore + 1);
        long oversellCash = TenderCalculator.cashRequired(oversell.totals().totalCents(), 0);
        assertThrows(InsufficientStockException.class, () -> sales.checkout(
                new CheckoutCommand(shift.id(), SaleChannel.COUNTER, null, oversell,
                        List.of(TenderPayment.cash(oversellCash, oversellCash)))));
        assertEquals(0, count("sales"));
        assertEquals(stockBefore, catalog.findProduct(product.id()).stock());

        // A normal cash sale commits sale, lines, payment, movement and audit.
        CartSnapshot cart = cart(product, 2);
        long cash = TenderCalculator.cashRequired(cart.totals().totalCents(), 0);
        CheckoutResult result = sales.checkout(new CheckoutCommand(
                shift.id(), SaleChannel.COUNTER, null, cart,
                List.of(TenderPayment.cash(cash, cash))));

        assertTrue(result.saleId() > 0);
        assertTrue(result.receiptNumber().startsWith("CHG-"), result.receiptNumber());
        assertEquals(stockBefore - 2, catalog.findProduct(product.id()).stock());
        assertEquals(1, count("sales"));
        assertEquals(1, count("sale_items"));
        assertEquals(1, count("payments"));
        assertEquals(1, countWhere("stock_movements", "reason = 'SALE'"));
        assertEquals(1, countWhere("audit_log", "action = 'SALE_COMPLETED'"));

        long expected = shifts.expectedCash(shift);
        ZReport report = shifts.closeShift(shift.id(), expected, "counted");
        assertEquals(0, report.varianceCents());
        assertEquals(ShiftStatus.CLOSED, shifts.byId(shift.id()).status());
        assertNotNull(zReports.findByShift(shift.id()));
        assertEquals(expected, report.expectedCashCents());

        // Kiosk places a quote, then a cashier completes it atomically.
        auth.logout();
        assertTrue(auth.deviceLogin("kiosk").success());
        KioskOrder order = kioskOrders.place(List.of(
                new KioskOrderService.PlaceLine(product.id(), 2)));
        assertNotNull(order);
        assertEquals(KioskOrderStatus.PENDING, order.status());
        assertTrue(order.code().startsWith("K-"), order.code());

        auth.logout();
        assertTrue(auth.login("sysadmin", "change-me").success());
        Shift kioskShift = shifts.openShift(0, "kiosk completion");

        CartSnapshot kioskCart = cart(product, 2);
        long kioskCash = TenderCalculator.cashRequired(kioskCart.totals().totalCents(), 0);
        CheckoutResult kioskSale = sales.checkout(new CheckoutCommand(
                kioskShift.id(), SaleChannel.KIOSK, order.id(), kioskCart,
                List.of(TenderPayment.cash(kioskCash, kioskCash))));

        KioskOrder completed = kioskOrders.byId(order.id());
        assertEquals(KioskOrderStatus.COMPLETED, completed.status());
        assertEquals(kioskSale.saleId(), completed.completedSaleId());
        assertEquals(2, count("sales"));
    }

    private static CartSnapshot cart(CatalogService.ProductRow product, int quantity) {
        return CartCalculator.calculate(List.of(new CartItem(
                product.id(), product.sku(), product.name(), product.priceCents(),
                quantity, product.taxRatePpm(), Discount.NONE)), Discount.NONE);
    }

    private static int count(String table) {
        Integer value = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return value == null ? 0 : value;
    }

    private static int countWhere(String table, String predicate) {
        Integer value = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE " + predicate, Integer.class);
        return value == null ? 0 : value;
    }
}
