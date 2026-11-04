package com.chegg.pos.db;

import com.chegg.pos.app.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Seeds roles, permissions, users, categories, the sample catalog and settings on a fresh DB. */
@Component
@DependsOn("schemaManager")
public class Seeder implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(Seeder.class);

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final TransactionTemplate transactionTemplate;
    private final AppProperties appProperties;

    public Seeder(JdbcTemplate jdbc,
                  PasswordEncoder passwordEncoder,
                  TransactionTemplate transactionTemplate,
                  AppProperties appProperties) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.transactionTemplate = transactionTemplate;
        this.appProperties = appProperties;
    }

    @Override
    public void afterPropertiesSet() {
        Integer roles = jdbc.queryForObject("SELECT COUNT(*) FROM roles", Integer.class);
        if (roles != null && roles > 0) {
            ensureDefaultSettings();
            log.debug("Database already seeded; skipping");
            return;
        }
        log.info("Seeding database");
        transactionTemplate.executeWithoutResult(status -> seedAll());
        ensureDefaultSettings();
    }

    /** Adds settings introduced after the first release without touching user values. */
    private void ensureDefaultSettings() {
        Map<String, String> defaults = new LinkedHashMap<>();
        defaults.put("store.motto", "This project was hard");
        defaults.put("kiosk.staffPin", "2468");
        defaults.forEach((key, value) -> jdbc.update("""
                INSERT INTO settings(key, value) VALUES (?, ?)
                ON CONFLICT(key) DO NOTHING
                """, key, value));
    }

    private void seedAll() {
        String now = Instant.now().toString();

        // ------------------------------------------------------------ roles
        insertRole("SYSTEM_ADMIN", "System Administrator", true, 1_000_000);
        insertRole("STORE_ADMIN", "Store Administrator", false, 300_000);
        insertRole("CASHIER", "Cashier", false, 100_000);
        insertRole("CUSTOMER", "Customer Kiosk", false, 0);

        // ------------------------------------------------------ permissions
        List<PermissionDef> permissions = List.of(
                // sales
                new PermissionDef("SALE_CREATE", "Sales", "Create sales"),
                new PermissionDef("SALE_DISCOUNT", "Sales", "Apply discounts"),
                new PermissionDef("SALE_VOID", "Sales", "Void a sale"),
                new PermissionDef("SALE_VIEW_OWN", "Sales", "View own sales"),
                new PermissionDef("SALE_VIEW_ALL", "Sales", "View all sales"),
                new PermissionDef("RECEIPT_REPRINT_OWN", "Sales", "Reprint own receipts"),
                new PermissionDef("RECEIPT_REPRINT_ALL", "Sales", "Reprint any receipt"),
                new PermissionDef("KIOSK_ORDER_HANDLE", "Sales", "Handle kiosk orders"),
                // returns
                new PermissionDef("REFUND_CREATE", "Returns", "Request a refund"),
                new PermissionDef("REFUND_APPROVE", "Returns", "Approve refunds"),
                // catalog
                new PermissionDef("PRODUCT_VIEW", "Catalog", "View products"),
                new PermissionDef("PRODUCT_EDIT", "Catalog", "Create/edit products"),
                new PermissionDef("PRODUCT_ARCHIVE", "Catalog", "Archive products"),
                // inventory
                new PermissionDef("INVENTORY_VIEW", "Inventory", "View stock"),
                new PermissionDef("INVENTORY_ADJUST", "Inventory", "Adjust stock"),
                new PermissionDef("STOCK_RECEIVE", "Inventory", "Receive stock"),
                // reports
                new PermissionDef("REPORT_VIEW", "Reports", "View reports"),
                new PermissionDef("REPORT_EXPORT", "Reports", "Export reports"),
                new PermissionDef("ZREPORT_VIEW_OWN", "Reports", "View own Z-report"),
                new PermissionDef("ZREPORT_VIEW_ALL", "Reports", "View all Z-reports"),
                new PermissionDef("ZREPORT_CLOSE", "Reports", "Close a shift"),
                new PermissionDef("AUDIT_VIEW", "Reports", "View audit log"),
                // shifts
                new PermissionDef("SHIFT_OPEN", "Shifts", "Open a shift"),
                new PermissionDef("SHIFT_CLOSE", "Shifts", "Close own shift"),
                new PermissionDef("SHIFT_VIEW_ALL", "Shifts", "View all shifts"),
                // people
                new PermissionDef("USER_VIEW", "People", "View users"),
                new PermissionDef("USER_MANAGE", "People", "Manage users"),
                new PermissionDef("ROLE_CONFIGURE", "People", "Configure roles"),
                // system
                new PermissionDef("SETTINGS_VIEW", "System", "View settings"),
                new PermissionDef("SETTINGS_EDIT", "System", "Edit settings"),
                new PermissionDef("BACKUP_RUN", "System", "Run backups"),
                new PermissionDef("KIOSK_MODE", "System", "Use customer kiosk")
        );

        for (PermissionDef p : permissions) {
            jdbc.update("INSERT INTO permissions(code, area, label) VALUES (?, ?, ?)",
                    p.code(), p.area(), p.label());
        }

        Set<String> allCodes = new LinkedHashSet<>();
        permissions.forEach(p -> allCodes.add(p.code()));

        grant("SYSTEM_ADMIN", allCodes);

        Set<String> storeAdmin = new LinkedHashSet<>(allCodes);
        storeAdmin.remove("ROLE_CONFIGURE");
        storeAdmin.remove("BACKUP_RUN");
        grant("STORE_ADMIN", storeAdmin);

        grant("CASHIER", Set.of(
                "SALE_CREATE", "SALE_DISCOUNT", "SALE_VIEW_OWN",
                "RECEIPT_REPRINT_OWN", "KIOSK_ORDER_HANDLE", "REFUND_CREATE",
                "PRODUCT_VIEW", "INVENTORY_VIEW", "ZREPORT_VIEW_OWN",
                "SHIFT_OPEN", "SHIFT_CLOSE"
        ));

        grant("CUSTOMER", Set.of("PRODUCT_VIEW", "SALE_CREATE", "KIOSK_MODE"));

        // ------------------------------------------------------------ users
        insertUser("sysadmin", "System Administrator", "SYSTEM_ADMIN",
                passwordEncoder.encode("change-me"), true, now);
        insertUser("storeadmin", "Store Administrator", "STORE_ADMIN",
                passwordEncoder.encode("change-me"), true, now);
        insertUser("cashier1", "Cashier One", "CASHIER",
                passwordEncoder.encode("change-me"), true, now);
        insertUser("kiosk", "Self Checkout Kiosk", "CUSTOMER", null, false, now);

        // ------------------------------------------------------- categories
        Map<String, Long> categoryIds = new LinkedHashMap<>();
        categoryIds.put("Beverages", insertCategory("Beverages", "#722F37", 1));
        categoryIds.put("Snacks", insertCategory("Snacks", "#C9A227", 2));
        categoryIds.put("Groceries", insertCategory("Groceries", "#2F6B3C", 3));
        categoryIds.put("Household", insertCategory("Household", "#2F5D7C", 4));
        categoryIds.put("Personal Care", insertCategory("Personal Care", "#8A3B44", 5));
        categoryIds.put("Stationery", insertCategory("Stationery", "#5C4A44", 6));

        // --------------------------------------------------------- products
        int productCount = seedProducts(categoryIds, now);

        // --------------------------------------------------------- settings
        Map<String, String> settings = new LinkedHashMap<>();
        settings.put("currency.code", "KES");
        settings.put("currency.symbol", "KSh");
        settings.put("currency.locale", "en-KE");
        settings.put("tax.enabled", "false");
        settings.put("tax.defaultRatePpm", "0");
        settings.put("cash.rounding", "NEAREST_1");
        settings.put("store.name", "Chegg");
        settings.put("store.motto", "This project was hard");
        settings.put("store.pin", "");
        settings.put("store.address", "");
        settings.put("store.phone", "");
        settings.put("receipt.pageSize", "A5");
        settings.put("receipt.autoPrint", "false");
        settings.put("kiosk.idleResetSeconds", "60");
        settings.put("kiosk.orderExpiryMinutes", "15");
        settings.put("kiosk.staffPin", "2468");
        settings.put("session.idleLockMinutes", "5");
        settings.put("backup.retentionDays", "14");
        settings.put("terminal.id", appProperties.resolvedTerminalId());
        settings.forEach((key, value) ->
                jdbc.update("INSERT INTO settings(key, value) VALUES (?, ?)", key, value));

        jdbc.update("""
                INSERT INTO audit_log(at, user_id, shift_id, action, entity_type, entity_id, summary, details)
                VALUES (?, NULL, NULL, 'SYSTEM_SEEDED', 'DATABASE', NULL, ?, ?)
                """, now, "Initial data seeded",
                "roles=4, permissions=" + permissions.size() + ", products=" + productCount);

        log.info("Seed complete: 4 roles, {} permissions, 4 users, {} products",
                permissions.size(), productCount);
    }

    // ------------------------------------------------------------- helpers

    private void insertRole(String code, String name, boolean locked, long maxDiscountPpm) {
        jdbc.update("INSERT INTO roles(code, name, locked, max_discount_ppm) VALUES (?, ?, ?, ?)",
                code, name, locked ? 1 : 0, maxDiscountPpm);
    }

    private void grant(String roleCode, Set<String> permissionCodes) {
        Long roleId = jdbc.queryForObject("SELECT id FROM roles WHERE code = ?", Long.class, roleCode);
        for (String code : permissionCodes) {
            Long permissionId = jdbc.queryForObject(
                    "SELECT id FROM permissions WHERE code = ?", Long.class, code);
            jdbc.update("INSERT INTO role_permissions(role_id, permission_id) VALUES (?, ?)",
                    roleId, permissionId);
        }
    }

    private void insertUser(String username, String fullName, String roleCode,
                            String passwordHash, boolean mustChange, String now) {
        Long roleId = jdbc.queryForObject("SELECT id FROM roles WHERE code = ?", Long.class, roleCode);
        jdbc.update("""
                        INSERT INTO users(username, password_hash, full_name, role_id, active,
                                          must_change_password, failed_attempts, created_at, updated_at)
                        VALUES (?, ?, ?, ?, 1, ?, 0, ?, ?)
                        """,
                username, passwordHash, fullName, roleId, mustChange ? 1 : 0, now, now);
    }

    private long insertCategory(String name, String colorHex, int sortOrder) {
        jdbc.update("INSERT INTO categories(name, color_hex, sort_order) VALUES (?, ?, ?)",
                name, colorHex, sortOrder);
        Long id = jdbc.queryForObject("SELECT id FROM categories WHERE name = ?", Long.class, name);
        return id == null ? 0 : id;
    }

    private int seedProducts(Map<String, Long> categoryIds, String now) {
        int count = 0;
        InputStream in = getClass().getResourceAsStream("/seed/products.csv");
        if (in == null) {
            log.warn("Seed catalog not found at /seed/products.csv");
            return 0;
        }

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            boolean header = true;
            while ((line = reader.readLine()) != null) {
                if (header) {
                    header = false;
                    continue;
                }
                if (line.isBlank()) {
                    continue;
                }
                String[] parts = line.split(",", -1);
                if (parts.length < 6) {
                    log.warn("Skipping malformed seed row: {}", line);
                    continue;
                }
                String sku = parts[0].trim();
                String name = parts[1].trim();
                String category = parts[2].trim();
                long priceCents = Long.parseLong(parts[3].trim());
                int stock = Integer.parseInt(parts[4].trim());
                int reorder = Integer.parseInt(parts[5].trim());

                Long categoryId = categoryIds.get(category);
                if (categoryId == null) {
                    log.warn("Unknown category '{}' for product {}", category, sku);
                    continue;
                }

                jdbc.update("""
                                INSERT INTO products(sku, name, category_id, price_cents, cost_cents,
                                                     tax_rate_ppm, active, created_at, updated_at)
                                VALUES (?, ?, ?, ?, 0, 0, 1, ?, ?)
                                """,
                        sku, name, categoryId, priceCents, now, now);

                Long productId = jdbc.queryForObject(
                        "SELECT id FROM products WHERE sku = ?", Long.class, sku);
                jdbc.update("""
                                INSERT INTO inventory(product_id, quantity, reorder_level, updated_at)
                                VALUES (?, ?, ?, ?)
                                """,
                        productId, stock, reorder, now);
                count++;
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to seed product catalog", e);
        }
        return count;
    }

    private record PermissionDef(String code, String area, String label) {
    }
}
