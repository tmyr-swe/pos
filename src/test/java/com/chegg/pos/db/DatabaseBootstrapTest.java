package com.chegg.pos.db;

import com.chegg.pos.app.AppConfig;
import com.chegg.pos.model.User;
import com.chegg.pos.service.AuthService;
import com.chegg.pos.service.LoginResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Boots the real Spring context against a throwaway SQLite database and proves
 * the first-run schema, seed data and authentication behaviour.
 */
class DatabaseBootstrapTest {

    @TempDir
    static Path home;

    private static AnnotationConfigApplicationContext context;
    private static JdbcTemplate jdbc;
    private static AuthService authService;
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
        authService = context.getBean(AuthService.class);
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
    void migratesAndSeedsAFreshDatabase() {
        assertEquals(1, jdbc.queryForObject(
                "SELECT MAX(version) FROM schema_version", Integer.class));
        assertEquals(36, count("products"));
        assertEquals(6, count("categories"));
        assertEquals(4, count("roles"));
        assertEquals(32, count("permissions"));
        assertEquals(4, count("users"));
        assertEquals(36, jdbc.queryForObject("SELECT COUNT(*) FROM inventory", Integer.class));
    }

    @Test
    void seedsSettingsForKenyanShillingsAndNoVat() {
        assertEquals("KES", setting("currency.code"));
        assertEquals("KSh", setting("currency.symbol"));
        assertEquals("en-KE", setting("currency.locale"));
        assertEquals("false", setting("tax.enabled"));
        assertEquals("0", setting("tax.defaultRatePpm"));
        assertEquals("NEAREST_1", setting("cash.rounding"));
        assertNotNull(setting("terminal.id"));
        assertFalse(setting("terminal.id").isBlank());
    }

    @Test
    void defaultPermissionMatrixMatchesThePlan() {
        assertEquals(32, grantsFor("SYSTEM_ADMIN"));
        assertEquals(30, grantsFor("STORE_ADMIN"));
        assertEquals(11, grantsFor("CASHIER"));
        assertEquals(3, grantsFor("CUSTOMER"));
    }

    @Test
    void seededSystemAdminCanSignIn() {
        LoginResult result = authService.login("sysadmin", "change-me");

        assertTrue(result.success(), result.message());
        assertNotNull(result.user());
        assertEquals("SYSTEM_ADMIN", result.user().roleCode());
        assertTrue(result.user().mustChangePassword());

        assertNotNull(jdbc.queryForObject(
                "SELECT last_login_at FROM users WHERE username = 'sysadmin'", String.class));
        assertTrue(countWhere("audit_log", "action = 'LOGIN_SUCCESS'") >= 1);
    }

    @Test
    void fiveFailedAttemptsLockTheAccount() {
        LoginResult lastFailure = null;
        for (int attempt = 1; attempt <= 5; attempt++) {
            lastFailure = authService.login("cashier1", "definitely-wrong");
            assertFalse(lastFailure.success());
        }

        assertNotNull(lastFailure);
        assertTrue(lastFailure.message().contains("locked"), lastFailure.message());
        assertTrue(countWhere("audit_log", "action = 'LOGIN_FAILED'") >= 5);

        LoginResult whileLocked = authService.login("cashier1", "change-me");
        assertFalse(whileLocked.success());
        assertTrue(whileLocked.message().contains("locked"), whileLocked.message());
    }

    @Test
    void changingTheForcedPasswordUnlocksNormalSignIn() {
        LoginResult first = authService.login("storeadmin", "change-me");
        assertTrue(first.success(), first.message());
        assertTrue(first.user().mustChangePassword());

        User user = first.user();
        authService.changePassword(user.id(), "Store-Admin-2026");

        LoginResult second = authService.login("storeadmin", "Store-Admin-2026");
        assertTrue(second.success(), second.message());
        assertFalse(second.user().mustChangePassword());
        assertEquals(1, countWhere("audit_log", "action = 'PASSWORD_CHANGED'"));
    }

    @Test
    void productsArePricedInPositiveIntegerCents() {
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM products WHERE price_cents <= 0", Integer.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM products WHERE price_cents % 100 <> 0", Integer.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM products WHERE tax_rate_ppm <> 0", Integer.class));
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

    private static int grantsFor(String roleCode) {
        Integer value = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM role_permissions rp
                JOIN roles r ON r.id = rp.role_id
                WHERE r.code = ?
                """, Integer.class, roleCode);
        return value == null ? 0 : value;
    }

    private static String setting(String key) {
        return jdbc.queryForObject("SELECT value FROM settings WHERE key = ?", String.class, key);
    }
}
