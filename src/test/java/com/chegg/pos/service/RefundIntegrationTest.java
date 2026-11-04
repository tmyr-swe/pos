package com.chegg.pos.service;

import com.chegg.pos.app.AppConfig;
import com.chegg.pos.model.SaleItemRow;
import com.chegg.pos.model.SaleSummary;
import com.chegg.pos.model.Shift;
import com.chegg.pos.model.ZReport;
import com.chegg.pos.model.enums.PaymentMethod;
import com.chegg.pos.model.enums.SaleChannel;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Refunds restock inventory, transition the sale through
 * PARTIALLY_REFUNDED to REFUNDED, roll back cleanly when over-refunded and
 * reconcile in the shift Z-report.
 */
class RefundIntegrationTest {

    @TempDir
    static Path home;

    private static AnnotationConfigApplicationContext context;
    private static JdbcTemplate jdbc;
    private static AuthService auth;
    private static CatalogService catalog;
    private static ShiftService shifts;
    private static SaleService sales;
    private static RefundService refunds;
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
        refunds = context.getBean(RefundService.class);
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
    void partialRefundRestockOverRefundRollbackAndZReport() {
        LoginResult login = auth.login("sysadmin", "change-me");
        assertTrue(login.success(), login.message());

        Shift shift = shifts.openShift(0, "refund integration test");
        CatalogService.ProductRow product = catalog.listProducts(true).stream()
                .max(Comparator.comparingInt(CatalogService.ProductRow::stock))
                .orElseThrow();
        assertTrue(product.stock() >= 3, "seed stock should support the scenario");

        CartSnapshot cart = cart(product, 3);
        long cash = TenderCalculator.cashRequired(cart.totals().totalCents(), 0);
        CheckoutResult sale = sales.checkout(new CheckoutCommand(
                shift.id(), SaleChannel.COUNTER, null, cart,
                List.of(TenderPayment.cash(cash, cash))));

        int stockAfterSale = catalog.findProduct(product.id()).stock();
        SaleItemRow line = sales.items(sale.saleId()).get(0);

        long refundId = refunds.refund(new RefundService.RefundRequest(
                sale.saleId(), List.of(new RefundService.RefundLine(line.id(), 1)),
                PaymentMethod.CASH, "damaged item"));
        assertTrue(refundId > 0);
        assertEquals(stockAfterSale + 1, catalog.findProduct(product.id()).stock());
        assertEquals("PARTIALLY_REFUNDED", sales.findById(sale.saleId()).status());
        assertEquals(1, count("refunds"));
        assertEquals(1, countWhere("stock_movements", "reason = 'REFUND'"));
        assertEquals(1, countWhere("audit_log", "action = 'REFUND_ISSUED'"));

        // Requesting more than remains must fail and leave no partial refund behind.
        int stockBeforeFailed = catalog.findProduct(product.id()).stock();
        assertThrows(IllegalArgumentException.class, () -> refunds.refund(
                new RefundService.RefundRequest(
                        sale.saleId(), List.of(new RefundService.RefundLine(line.id(), 3)),
                        PaymentMethod.CASH, "too many")));
        assertEquals(1, count("refunds"));
        assertEquals(stockBeforeFailed, catalog.findProduct(product.id()).stock());
        assertEquals("PARTIALLY_REFUNDED", sales.findById(sale.saleId()).status());

        // Refunding the remaining quantity closes the sale.
        refunds.refund(new RefundService.RefundRequest(
                sale.saleId(), List.of(new RefundService.RefundLine(line.id(), 2)),
                PaymentMethod.CASH, "remainder"));
        SaleSummary refunded = sales.findById(sale.saleId());
        assertEquals("REFUNDED", refunded.status());
        assertEquals(product.stock(), catalog.findProduct(product.id()).stock());
        assertEquals(2, count("refunds"));

        assertThrows(IllegalStateException.class, () -> refunds.refund(
                new RefundService.RefundRequest(
                        sale.saleId(), List.of(new RefundService.RefundLine(line.id(), 1)),
                        PaymentMethod.CASH, "already refunded")));

        long expected = shifts.expectedCash(shifts.byId(shift.id()));
        ZReport report = shifts.closeShift(shift.id(), expected, "counted");
        assertEquals(0, report.varianceCents());
        assertEquals(2, report.refundCount());
        assertTrue(report.refundsCents() > 0);
    }

    @Test
    void voidingASaleRestocksAndExcludesItFromTheZReport() {
        LoginResult login = auth.login("sysadmin", "change-me");
        assertTrue(login.success(), login.message());
        // The previous test may have closed the terminal's only shift.
        Shift shift = shifts.openShift(0, "void integration test");
        CatalogService.ProductRow product = catalog.listProducts(true).stream()
                .max(Comparator.comparingInt(CatalogService.ProductRow::stock))
                .orElseThrow();
        int stockBefore = product.stock();

        CartSnapshot cart = cart(product, 1);
        long cash = TenderCalculator.cashRequired(cart.totals().totalCents(), 0);
        CheckoutResult sale = sales.checkout(new CheckoutCommand(
                shift.id(), SaleChannel.COUNTER, null, cart,
                List.of(TenderPayment.cash(cash, cash))));
        assertEquals(stockBefore - 1, catalog.findProduct(product.id()).stock());

        sales.voidSale(sale.saleId(), "wrong item scanned");
        assertEquals(stockBefore, catalog.findProduct(product.id()).stock());
        assertEquals("VOIDED", sales.findById(sale.saleId()).status());
        assertEquals(1, countWhere("stock_movements", "reason = 'VOID'"));
        assertEquals(1, countWhere("audit_log", "action = 'SALE_VOIDED'"));

        assertThrows(IllegalStateException.class,
                () -> sales.voidSale(sale.saleId(), "again"));

        long expected = shifts.expectedCash(shifts.byId(shift.id()));
        ZReport report = shifts.closeShift(shift.id(), expected, "counted");
        assertEquals(0, report.varianceCents());
        assertEquals(0, report.txnCount());
        assertEquals(1, report.voidCount());
        assertEquals(0, report.cashCents());
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
