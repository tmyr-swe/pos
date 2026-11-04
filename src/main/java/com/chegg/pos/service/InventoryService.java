package com.chegg.pos.service;

import com.chegg.pos.model.Shift;
import com.chegg.pos.model.StockMovement;
import com.chegg.pos.model.enums.StockReason;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/** Stock adjustments and receipts. Every change writes a movement row. */
@Service
public class InventoryService {

    private final JdbcTemplate jdbc;
    private final SessionService sessions;
    private final AuditService audit;
    private final ShiftService shifts;

    public InventoryService(JdbcTemplate jdbc, SessionService sessions,
                            AuditService audit, ShiftService shifts) {
        this.jdbc = jdbc;
        this.sessions = sessions;
        this.audit = audit;
        this.shifts = shifts;
    }

    @Transactional(rollbackFor = Exception.class)
    public void adjust(long productId, int delta, StockReason reason, String note) {
        if (reason == StockReason.RECEIVE) {
            sessions.require("STOCK_RECEIVE");
        } else {
            sessions.require("INVENTORY_ADJUST");
        }
        long userId = sessions.requireUser();
        if (delta == 0) {
            throw new IllegalArgumentException("Enter a quantity to add or remove.");
        }
        if (reason != StockReason.RECEIVE && reason != StockReason.ADJUST) {
            throw new IllegalArgumentException("Only manual movements are allowed here.");
        }

        String now = Instant.now().toString();
        int updated;
        if (delta < 0) {
            updated = jdbc.update("""
                    UPDATE inventory SET quantity = quantity + ?, updated_at = ?
                    WHERE product_id = ? AND quantity >= ?
                    """, delta, now, productId, -delta);
        } else {
            updated = jdbc.update("""
                    UPDATE inventory SET quantity = quantity + ?, updated_at = ?
                    WHERE product_id = ?
                    """, delta, now, productId);
        }
        if (updated == 0) {
            throw new IllegalStateException("Not enough stock for that adjustment.");
        }

        Shift shift = shifts.currentOpenShift();
        jdbc.update("""
                INSERT INTO stock_movements(product_id, delta, reason, reference_type,
                    reference_id, user_id, shift_id, created_at)
                VALUES (?, ?, ?, 'MANUAL', NULL, ?, ?, ?)
                """, productId, delta, reason.name(), userId,
                shift == null ? null : shift.id(), now);

        audit.record(userId, shift == null ? null : shift.id(), "STOCK_ADJUSTED", "PRODUCT", productId,
                (reason == StockReason.RECEIVE ? "Received " : "Adjusted ") + Math.abs(delta) + " units",
                "delta=" + delta + (note == null || note.isBlank() ? "" : ", note=" + note.trim()));
    }

    @Transactional(rollbackFor = Exception.class)
    public void setReorderLevel(long productId, int reorderLevel) {
        sessions.require("INVENTORY_ADJUST");
        long userId = sessions.requireUser();
        if (reorderLevel < 0) {
            throw new IllegalArgumentException("Reorder level cannot be negative.");
        }
        jdbc.update("UPDATE inventory SET reorder_level = ?, updated_at = ? WHERE product_id = ?",
                reorderLevel, Instant.now().toString(), productId);
        audit.record(userId, null, "STOCK_ADJUSTED", "PRODUCT", productId,
                "Reorder level set to " + reorderLevel, null);
    }

    public List<StockMovement> movements(int limit) {
        return jdbc.query("""
                SELECT m.id, m.product_id, p.name AS product_name, m.delta, m.reason,
                       m.reference_type, m.reference_id, u.full_name AS user_name, m.created_at
                FROM stock_movements m
                JOIN products p ON p.id = m.product_id
                LEFT JOIN users u ON u.id = m.user_id
                ORDER BY m.created_at DESC, m.id DESC
                LIMIT ?
                """, (rs, rowNum) -> new StockMovement(
                rs.getLong("id"), rs.getLong("product_id"), rs.getString("product_name"),
                rs.getInt("delta"), rs.getString("reason"), rs.getString("reference_type"),
                rs.getObject("reference_id") == null ? null : rs.getLong("reference_id"),
                rs.getString("user_name"), Instant.parse(rs.getString("created_at"))), limit);
    }

    public List<StockMovement> movementsForProduct(long productId, int limit) {
        return jdbc.query("""
                SELECT m.id, m.product_id, p.name AS product_name, m.delta, m.reason,
                       m.reference_type, m.reference_id, u.full_name AS user_name, m.created_at
                FROM stock_movements m
                JOIN products p ON p.id = m.product_id
                LEFT JOIN users u ON u.id = m.user_id
                WHERE m.product_id = ?
                ORDER BY m.created_at DESC, m.id DESC LIMIT ?
                """, (rs, rowNum) -> new StockMovement(
                rs.getLong("id"), rs.getLong("product_id"), rs.getString("product_name"),
                rs.getInt("delta"), rs.getString("reason"), rs.getString("reference_type"),
                rs.getObject("reference_id") == null ? null : rs.getLong("reference_id"),
                rs.getString("user_name"), Instant.parse(rs.getString("created_at"))), productId, limit);
    }

    public long stockValueCents() {
        Long value = jdbc.queryForObject("""
                SELECT COALESCE(SUM(i.quantity * p.cost_cents), 0)
                FROM inventory i JOIN products p ON p.id = i.product_id
                WHERE p.active = 1
                """, Long.class);
        return value == null ? 0 : value;
    }
}
