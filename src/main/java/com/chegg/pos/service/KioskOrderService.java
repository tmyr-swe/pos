package com.chegg.pos.service;

import com.chegg.pos.model.KioskOrder;
import com.chegg.pos.model.enums.KioskOrderStatus;
import com.chegg.pos.service.calc.CartCalculator;
import com.chegg.pos.service.calc.CartItem;
import com.chegg.pos.service.calc.CartSnapshot;
import com.chegg.pos.service.calc.Discount;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/** Kiosk orders are quotes: stock is not reserved until the cashier checks out. */
@Service
public class KioskOrderService {

    private static final ZoneId EAT = ZoneId.of("Africa/Nairobi");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(EAT);

    private final JdbcTemplate jdbc;
    private final SessionService sessions;
    private final AuditService audit;
    private final SettingsService settings;

    public KioskOrderService(JdbcTemplate jdbc,
                             SessionService sessions,
                             AuditService audit,
                             SettingsService settings) {
        this.jdbc = jdbc;
        this.sessions = sessions;
        this.audit = audit;
        this.settings = settings;
    }

    @Transactional(rollbackFor = Exception.class)
    public KioskOrder place(List<PlaceLine> lines) {
        sessions.require("SALE_CREATE");
        long userId = sessions.requireUser();
        if (lines == null || lines.isEmpty()) {
            throw new IllegalArgumentException("Add at least one item.");
        }

        List<CartItem> cartItems = new ArrayList<>();
        for (PlaceLine line : lines) {
            if (line.quantity() <= 0) {
                continue;
            }
            List<CartItem> found = jdbc.query("""
                    SELECT p.id, p.sku, p.name, p.price_cents, p.tax_rate_ppm
                    FROM products p WHERE p.id = ? AND p.active = 1
                    """, (rs, rowNum) -> new CartItem(
                    rs.getLong("id"), rs.getString("sku"), rs.getString("name"),
                    rs.getLong("price_cents"), line.quantity(),
                    rs.getLong("tax_rate_ppm"), Discount.NONE), line.productId());
            if (found.isEmpty()) {
                throw new IllegalArgumentException("A product in the order is no longer available.");
            }
            cartItems.add(found.get(0));
        }
        if (cartItems.isEmpty()) {
            throw new IllegalArgumentException("Add at least one item.");
        }

        CartSnapshot snapshot = CartCalculator.calculate(cartItems, Discount.NONE);
        Instant now = Instant.now();
        int minutes = Math.max(1, settings.getInt("kiosk.orderExpiryMinutes", 15));
        Instant expires = now.plus(minutes, ChronoUnit.MINUTES);
        String code = nextCode(now);

        jdbc.update("""
                INSERT INTO kiosk_orders(code, status, created_at, expires_at,
                    subtotal_cents, total_cents)
                VALUES (?, 'PENDING', ?, ?, ?, ?)
                """, code, now.toString(), expires.toString(),
                snapshot.totals().subtotalCents(), snapshot.totals().totalCents());
        long id = lastInsertId();

        for (var line : snapshot.lines()) {
            jdbc.update("""
                    INSERT INTO kiosk_order_items(order_id, product_id, name_snapshot,
                        unit_price_cents, quantity)
                    VALUES (?, ?, ?, ?, ?)
                    """, id, line.productId(), line.name(), line.unitPriceCents(), line.quantity());
        }

        audit.record(userId, null, "KIOSK_ORDER_PLACED", "KIOSK_ORDER", id,
                "Kiosk order " + code + " placed", "total=" + snapshot.totals().totalCents());
        return byId(id);
    }

    @Transactional(rollbackFor = Exception.class)
    public int expireStale() {
        String now = Instant.now().toString();
        return jdbc.update("""
                UPDATE kiosk_orders SET status = 'EXPIRED'
                WHERE status = 'PENDING' AND expires_at <= ?
                """, now);
    }

    public List<KioskOrder> pending() {
        expireStale();
        return jdbc.query("""
                SELECT id, code, status, created_at, expires_at, completed_sale_id,
                       subtotal_cents, total_cents
                FROM kiosk_orders WHERE status = 'PENDING'
                ORDER BY created_at
                """, (rs, rowNum) -> map(rs));
    }

    public int pendingCount() {
        expireStale();
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM kiosk_orders WHERE status = 'PENDING'", Integer.class);
        return count == null ? 0 : count;
    }

    public KioskOrder byId(long id) {
        List<KioskOrder> rows = jdbc.query("""
                SELECT id, code, status, created_at, expires_at, completed_sale_id,
                       subtotal_cents, total_cents
                FROM kiosk_orders WHERE id = ?
                """, (rs, rowNum) -> map(rs), id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Transactional(rollbackFor = Exception.class)
    public void cancel(long id) {
        sessions.require("KIOSK_ORDER_HANDLE");
        long userId = sessions.requireUser();
        int updated = jdbc.update("""
                UPDATE kiosk_orders SET status = 'CANCELLED'
                WHERE id = ? AND status = 'PENDING'
                """, id);
        if (updated == 0) {
            throw new IllegalStateException("That kiosk order is no longer pending.");
        }
        audit.record(userId, null, "KIOSK_ORDER_CANCELLED", "KIOSK_ORDER", id,
                "Kiosk order cancelled", null);
    }

    private KioskOrder map(java.sql.ResultSet rs) throws java.sql.SQLException {
        long id = rs.getLong("id");
        List<KioskOrder.Item> items = jdbc.query("""
                SELECT id, product_id, name_snapshot, unit_price_cents, quantity
                FROM kiosk_order_items WHERE order_id = ? ORDER BY id
                """, (itemRs, rowNum) -> new KioskOrder.Item(
                itemRs.getLong("id"), itemRs.getLong("product_id"),
                itemRs.getString("name_snapshot"), itemRs.getLong("unit_price_cents"),
                itemRs.getInt("quantity")), id);
        Long saleId = rs.getObject("completed_sale_id") == null
                ? null : rs.getLong("completed_sale_id");
        return new KioskOrder(id, rs.getString("code"),
                KioskOrderStatus.valueOf(rs.getString("status")),
                Instant.parse(rs.getString("created_at")),
                Instant.parse(rs.getString("expires_at")),
                saleId, rs.getLong("subtotal_cents"), rs.getLong("total_cents"), items);
    }

    private String nextCode(Instant now) {
        String day = DAY.format(now);
        Integer seq = jdbc.queryForObject("""
                INSERT INTO daily_counters(day, last_seq) VALUES (?, 1)
                ON CONFLICT(day) DO UPDATE SET last_seq = last_seq + 1
                RETURNING last_seq
                """, Integer.class, "K-" + day);
        int value = seq == null ? 1 : seq;
        return "K-" + String.format("%04d", value);
    }

    private long lastInsertId() {
        Long id = jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
        return id == null ? 0 : id;
    }

    public record PlaceLine(long productId, int quantity) {
    }
}
