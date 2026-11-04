package com.chegg.pos.service;

import com.chegg.pos.util.Money;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/** Catalog queries and product/category maintenance. */
@Service
public class CatalogService {

    private final JdbcTemplate jdbc;
    private final SessionService sessions;
    private final AuditService audit;

    public CatalogService(JdbcTemplate jdbc, SessionService sessions, AuditService audit) {
        this.jdbc = jdbc;
        this.sessions = sessions;
        this.audit = audit;
    }

    public Overview overview() {
        return new Overview(listProducts(true), categoryCount(), lowStockCount());
    }

    public List<ProductRow> listProducts(boolean activeOnly) {
        String sql = """
                SELECT p.id, p.sku, p.name, c.name AS category_name, c.id AS category_id,
                       p.price_cents, p.cost_cents, p.tax_rate_ppm, p.active,
                       i.quantity, i.reorder_level
                FROM products p
                JOIN categories c ON c.id = p.category_id
                JOIN inventory i ON i.product_id = p.id
                """ + (activeOnly ? " WHERE p.active = 1" : "")
                + " ORDER BY c.sort_order, p.name";
        return jdbc.query(sql, (rs, rowNum) -> new ProductRow(
                rs.getLong("id"),
                rs.getString("sku"),
                rs.getString("name"),
                rs.getString("category_name"),
                rs.getLong("price_cents"),
                rs.getInt("quantity"),
                rs.getInt("reorder_level"),
                rs.getLong("category_id"),
                rs.getLong("cost_cents"),
                rs.getLong("tax_rate_ppm"),
                rs.getInt("active") == 1
        ));
    }

    public List<ProductRow> listProducts() {
        return listProducts(true);
    }

    public List<ProductRow> lowStockProducts() {
        return jdbc.query("""
                SELECT p.id, p.sku, p.name, c.name AS category_name, c.id AS category_id,
                       p.price_cents, p.cost_cents, p.tax_rate_ppm, p.active,
                       i.quantity, i.reorder_level
                FROM products p
                JOIN categories c ON c.id = p.category_id
                JOIN inventory i ON i.product_id = p.id
                WHERE p.active = 1 AND i.quantity <= i.reorder_level
                ORDER BY (i.quantity - i.reorder_level), p.name
                """, (rs, rowNum) -> new ProductRow(
                rs.getLong("id"), rs.getString("sku"), rs.getString("name"),
                rs.getString("category_name"), rs.getLong("price_cents"),
                rs.getInt("quantity"), rs.getInt("reorder_level"),
                rs.getLong("category_id"), rs.getLong("cost_cents"),
                rs.getLong("tax_rate_ppm"), rs.getInt("active") == 1));
    }

    public List<CategoryRow> categories() {
        return jdbc.query("""
                SELECT c.id, c.name, c.color_hex, c.sort_order,
                       (SELECT COUNT(*) FROM products p WHERE p.category_id = c.id AND p.active = 1) AS product_count
                FROM categories c ORDER BY c.sort_order, c.name
                """, (rs, rowNum) -> new CategoryRow(
                rs.getLong("id"), rs.getString("name"), rs.getString("color_hex"),
                rs.getInt("sort_order"), rs.getInt("product_count")));
    }

    public int categoryCount() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM categories", Integer.class);
        return count == null ? 0 : count;
    }

    public int lowStockCount() {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM inventory i
                JOIN products p ON p.id = i.product_id
                WHERE p.active = 1 AND i.quantity <= i.reorder_level
                """, Integer.class);
        return count == null ? 0 : count;
    }

    @Transactional(rollbackFor = Exception.class)
    public long saveProduct(ProductInput input) {
        String now = Instant.now().toString();
        long userId = sessions.requireUser();
        if (input.id() == null) {
            sessions.require("PRODUCT_EDIT");
            if (input.sku() == null || input.sku().isBlank()
                    || input.name() == null || input.name().isBlank()) {
                throw new IllegalArgumentException("SKU and product name are required.");
            }
            if (input.priceCents() < 0 || input.costCents() < 0 || input.taxRatePpm() < 0) {
                throw new IllegalArgumentException("Prices and tax rate cannot be negative.");
            }
            jdbc.update("""
                    INSERT INTO products(sku, name, category_id, price_cents, cost_cents,
                        tax_rate_ppm, active, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, input.sku().trim().toUpperCase(), input.name().trim(), input.categoryId(),
                    input.priceCents(), input.costCents(), input.taxRatePpm(),
                    input.active() ? 1 : 0, now, now);
            long id = lastInsertId();
            jdbc.update("""
                    INSERT INTO inventory(product_id, quantity, reorder_level, updated_at)
                    VALUES (?, ?, ?, ?)
                    """, id, Math.max(0, input.initialStock()), Math.max(0, input.reorderLevel()), now);
            audit.record(userId, null, "PRODUCT_CHANGED", "PRODUCT", id,
                    "Created product " + input.name(), "sku=" + input.sku()
                            + ", price=" + Money.format(input.priceCents()));
            return id;
        }

        sessions.require("PRODUCT_EDIT");
        ProductRow before = findProduct(input.id());
        if (before == null) {
            throw new IllegalArgumentException("Product not found.");
        }
        jdbc.update("""
                UPDATE products SET sku = ?, name = ?, category_id = ?, price_cents = ?,
                    cost_cents = ?, tax_rate_ppm = ?, active = ?, updated_at = ?
                WHERE id = ?
                """, input.sku().trim().toUpperCase(), input.name().trim(), input.categoryId(),
                input.priceCents(), input.costCents(), input.taxRatePpm(),
                input.active() ? 1 : 0, now, input.id());
        jdbc.update("""
                UPDATE inventory SET reorder_level = ?, updated_at = ? WHERE product_id = ?
                """, Math.max(0, input.reorderLevel()), now, input.id());
        audit.record(userId, null, "PRODUCT_CHANGED", "PRODUCT", input.id(),
                "Updated product " + input.name(),
                before.name() + " -> " + input.name() + ", "
                        + Money.format(before.priceCents()) + " -> " + Money.format(input.priceCents()));
        return input.id();
    }

    @Transactional(rollbackFor = Exception.class)
    public void archiveProduct(long productId) {
        sessions.require("PRODUCT_ARCHIVE");
        long userId = sessions.requireUser();
        ProductRow product = findProduct(productId);
        if (product == null) {
            throw new IllegalArgumentException("Product not found.");
        }
        jdbc.update("UPDATE products SET active = 0, updated_at = ? WHERE id = ?",
                Instant.now().toString(), productId);
        audit.record(userId, null, "PRODUCT_CHANGED", "PRODUCT", productId,
                "Archived product " + product.name(), "sku=" + product.sku());
    }

    public ProductRow findProduct(long id) {
        List<ProductRow> rows = jdbc.query("""
                SELECT p.id, p.sku, p.name, c.name AS category_name, c.id AS category_id,
                       p.price_cents, p.cost_cents, p.tax_rate_ppm, p.active,
                       i.quantity, i.reorder_level
                FROM products p
                JOIN categories c ON c.id = p.category_id
                JOIN inventory i ON i.product_id = p.id
                WHERE p.id = ?
                """, (rs, rowNum) -> new ProductRow(
                rs.getLong("id"), rs.getString("sku"), rs.getString("name"),
                rs.getString("category_name"), rs.getLong("price_cents"),
                rs.getInt("quantity"), rs.getInt("reorder_level"),
                rs.getLong("category_id"), rs.getLong("cost_cents"),
                rs.getLong("tax_rate_ppm"), rs.getInt("active") == 1), id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Transactional(rollbackFor = Exception.class)
    public long saveCategory(String name, String colorHex, int sortOrder) {
        sessions.require("PRODUCT_EDIT");
        long userId = sessions.requireUser();
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Category name is required.");
        }
        jdbc.update("INSERT INTO categories(name, color_hex, sort_order) VALUES (?, ?, ?)",
                name.trim(), colorHex == null || colorHex.isBlank() ? "#722F37" : colorHex, sortOrder);
        long id = lastInsertId();
        audit.record(userId, null, "CATEGORY_CHANGED", "CATEGORY", id,
                "Created category " + name, null);
        return id;
    }

    private long lastInsertId() {
        Long id = jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
        return id == null ? 0 : id;
    }

    public record ProductRow(
            long id,
            String sku,
            String name,
            String category,
            long priceCents,
            int stock,
            int reorderLevel,
            long categoryId,
            long costCents,
            long taxRatePpm,
            boolean active
    ) {
        public boolean lowStock() {
            return stock <= reorderLevel;
        }
    }

    public record CategoryRow(long id, String name, String colorHex, int sortOrder, int productCount) {
    }

    public record ProductInput(
            Long id,
            String sku,
            String name,
            long categoryId,
            long priceCents,
            long costCents,
            long taxRatePpm,
            boolean active,
            int initialStock,
            int reorderLevel
    ) {
    }

    public record Overview(List<ProductRow> products, int categoryCount, int lowStockCount) {
    }
}
