package com.chegg.pos.service;

import com.chegg.pos.app.AppConfig;
import com.chegg.pos.model.Shift;
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

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Service-layer authorization, independent of which buttons the UI hides. */
class AuthorizationIntegrationTest {

    @TempDir
    static Path home;

    private static AnnotationConfigApplicationContext context;
    private static JdbcTemplate jdbc;
    private static AuthService auth;
    private static CatalogService catalog;
    private static ShiftService shifts;
    private static SaleService sales;
    private static RefundService refunds;
    private static UserService users;
    private static BackupService backups;
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
        users = context.getBean(UserService.class);
        backups = context.getBean(BackupService.class);
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
    void cashierCanSellButCannotVoidOrApproveRefunds() {
        LoginResult login = auth.login("cashier1", "change-me");
        assertTrue(login.success(), login.message());

        Shift shift = shifts.openShift(0, "authorization test");
        CatalogService.ProductRow product = catalog.listProducts(true).stream()
                .max(Comparator.comparingInt(CatalogService.ProductRow::stock))
                .orElseThrow();

        CartSnapshot cart = CartCalculator.calculate(List.of(new CartItem(
                product.id(), product.sku(), product.name(), product.priceCents(),
                1, product.taxRatePpm(), Discount.NONE)), Discount.NONE);
        long cash = TenderCalculator.cashRequired(cart.totals().totalCents(), 0);
        CheckoutResult sale = sales.checkout(new CheckoutCommand(
                shift.id(), SaleChannel.COUNTER, null, cart,
                List.of(TenderPayment.cash(cash, cash))));

        assertThrows(SecurityException.class,
                () -> sales.voidSale(sale.saleId(), "cashier attempt"));
        long lineId = sales.items(sale.saleId()).get(0).id();
        assertThrows(SecurityException.class, () -> refunds.refund(
                new RefundService.RefundRequest(
                        sale.saleId(), List.of(new RefundService.RefundLine(lineId, 1)),
                        PaymentMethod.CASH, "cashier attempt")));

        auth.logout();
    }

    @Test
    void storeAdminCannotTouchSystemAdminsOrRunBackups() {
        LoginResult login = auth.login("storeadmin", "change-me");
        assertTrue(login.success(), login.message());

        Long sysadminId = jdbc.queryForObject(
                "SELECT id FROM users WHERE username = 'sysadmin'", Long.class);
        Long cashierRoleId = jdbc.queryForObject(
                "SELECT id FROM roles WHERE code = 'CASHIER'", Long.class);

        assertThrows(SecurityException.class, () -> users.update(
                sysadminId, "Renamed", cashierRoleId, true));
        assertThrows(SecurityException.class, () -> users.resetPassword(sysadminId, "new-password"));
        assertThrows(SecurityException.class, () -> backups.runBackup());

        auth.logout();
    }
}
