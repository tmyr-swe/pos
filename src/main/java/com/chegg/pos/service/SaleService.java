package com.chegg.pos.service;

import com.chegg.pos.model.SaleItemRow;
import com.chegg.pos.model.SaleSummary;
import com.chegg.pos.model.Shift;
import com.chegg.pos.model.enums.SaleChannel;
import com.chegg.pos.model.enums.SaleStatus;
import com.chegg.pos.service.calc.CartLine;
import com.chegg.pos.service.calc.TenderCalculator;
import com.chegg.pos.service.calc.TenderPayment;
import com.chegg.pos.service.calc.TenderState;
import com.chegg.pos.util.Money;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Atomic checkout, voiding and sale history queries. */
@Service
public class SaleService {

    private static final ZoneId EAT = ZoneId.of("Africa/Nairobi");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(EAT);

    private final JdbcTemplate jdbc;
    private final SessionService sessions;
    private final AuditService audit;
    private final ShiftService shifts;

    public SaleService(JdbcTemplate jdbc,
                       SessionService sessions,
                       AuditService audit,
                       ShiftService shifts) {
        this.jdbc = jdbc;
        this.sessions = sessions;
        this.audit = audit;
        this.shifts = shifts;
    }

    @Transactional(rollbackFor = Exception.class)
    public CheckoutResult checkout(CheckoutCommand command) {
        sessions.require("SALE_CREATE");
        long userId = sessions.requireUser();

        if (command.cart() == null || command.cart().isEmpty()) {
            throw new IllegalArgumentException("The cart is empty.");
        }
        Shift shift = shifts.byId(command.shiftId());
        if (shift == null || !shift.isOpen()) {
            throw new IllegalStateException("The shift is not open.");
        }

        long total = command.cart().totals().totalCents();
        List<TenderPayment> normalized = normalizeTenders(command.payments(), total);
        TenderState tender = TenderCalculator.evaluate(total, normalized);
        if (!tender.valid()) {
            throw new IllegalArgumentException(tender.error());
        }

        Instant now = Instant.now();
        String nowText = now.toString();
        if (command.kioskOrderId() != null) {
            Integer pending = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM kiosk_orders
                    WHERE id = ? AND status = 'PENDING' AND expires_at > ?
                    """, Integer.class, command.kioskOrderId(), nowText);
            if (pending == null || pending == 0) {
                throw new IllegalStateException("That kiosk order is no longer available.");
            }
        }

        // Decrement first, in deterministic product order, so a failure rolls
        // back before any sale row exists.
        List<CartLine> ordered = new ArrayList<>(command.cart().lines());
        ordered.sort(Comparator.comparingLong(CartLine::productId));
        for (CartLine line : ordered) {
            int updated = jdbc.update("""
                    UPDATE inventory
                    SET quantity = quantity - ?, updated_at = ?
                    WHERE product_id = ? AND quantity >= ?
                    """, line.quantity(), nowText, line.productId(), line.quantity());
            if (updated == 0) {
                throw new InsufficientStockException(
                        "Not enough stock for " + line.name() + ".");
            }
        }

        String receipt = nextReceiptNumber(now);
        long gross = command.cart().lines().stream().mapToLong(CartLine::grossCents).sum();
        jdbc.update("""
                INSERT INTO sales(receipt_number, shift_id, cashier_id, channel, subtotal_cents,
                                  discount_cents, tax_cents, rounding_cents, total_cents, status,
                                  kiosk_order_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'COMPLETED', ?, ?)
                """, receipt, shift.id(), userId, command.channel() == null
                        ? SaleChannel.COUNTER.name() : command.channel().name(),
                gross, command.cart().totals().discountCents(), command.cart().totals().taxCents(),
                tender.roundingCents(), tender.payableCents(), command.kioskOrderId(), nowText);

        long saleId = lastInsertId();

        int lineNo = 1;
        for (CartLine line : command.cart().lines()) {
            jdbc.update("""
                    INSERT INTO sale_items(sale_id, line_no, product_id, sku_snapshot, name_snapshot,
                        unit_price_cents, quantity, discount_cents, tax_rate_ppm, tax_cents,
                        line_total_cents)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, saleId, lineNo++, line.productId(), line.sku(), line.name(),
                    line.unitPriceCents(), line.quantity(),
                    line.lineDiscountCents() + line.cartDiscountCents(),
                    line.taxRatePpm(), line.taxCents(), line.totalCents());

            jdbc.update("""
                    INSERT INTO stock_movements(product_id, delta, reason, reference_type,
                        reference_id, user_id, shift_id, created_at)
                    VALUES (?, ?, 'SALE', 'SALE', ?, ?, ?, ?)
                    """, line.productId(), -line.quantity(), saleId, userId, shift.id(), nowText);
        }

        boolean cashWritten = false;
        for (TenderPayment payment : normalized) {
            if (payment.method() == com.chegg.pos.model.enums.PaymentMethod.CARD) {
                jdbc.update("""
                        INSERT INTO payments(sale_id, method, amount_cents, tendered_cents,
                            change_cents, reference, created_at)
                        VALUES (?, 'CARD', ?, ?, 0, ?, ?)
                        """, saleId, payment.appliedCents(), payment.tenderedCents(),
                        payment.reference(), nowText);
            } else if (!cashWritten && (tender.roundedCashCents() > 0 || tender.tenderedCashCents() > 0)) {
                cashWritten = true;
                jdbc.update("""
                        INSERT INTO payments(sale_id, method, amount_cents, tendered_cents,
                            change_cents, reference, created_at)
                        VALUES (?, 'CASH', ?, ?, ?, NULL, ?)
                        """, saleId, tender.roundedCashCents(), tender.tenderedCashCents(),
                        tender.changeCents(), nowText);
            }
        }

        if (command.kioskOrderId() != null) {
            int linked = jdbc.update("""
                    UPDATE kiosk_orders
                    SET status = 'COMPLETED', completed_sale_id = ?
                    WHERE id = ? AND status = 'PENDING'
                    """, saleId, command.kioskOrderId());
            if (linked == 0) {
                throw new IllegalStateException("That kiosk order was already handled.");
            }
        }

        audit.record(userId, shift.id(), "SALE_COMPLETED", "SALE", saleId,
                "Sale " + receipt + " completed for " + Money.format(tender.payableCents()),
                "channel=" + command.channel() + ", items=" + command.cart().itemCount()
                        + ", rounding=" + tender.roundingCents());

        return new CheckoutResult(saleId, receipt, tender.payableCents(),
                tender.changeCents(), tender.roundingCents());
    }

    /**
     * Rebuilds tender rows around the cash-rounding rule. Callers supply card
     * amount and cash tendered; the cash applied amount is derived, not trusted.
     */
    private List<TenderPayment> normalizeTenders(List<TenderPayment> input, long total) {
        long cardApplied = 0;
        long cashTendered = 0;
        String cardReference = null;
        for (TenderPayment payment : input) {
            if (payment.method() == com.chegg.pos.model.enums.PaymentMethod.CARD) {
                cardApplied += payment.appliedCents();
                if (cardReference == null) {
                    cardReference = payment.reference();
                }
            } else {
                cashTendered += payment.tenderedCents();
            }
        }
        long roundedCash = TenderCalculator.cashRequired(total, cardApplied);
        List<TenderPayment> result = new ArrayList<>();
        if (cardApplied > 0) {
            result.add(TenderPayment.card(cardApplied, cardReference));
        }
        if (roundedCash > 0 || cashTendered > 0) {
            result.add(TenderPayment.cash(roundedCash, cashTendered));
        }
        return result;
    }

    private String nextReceiptNumber(Instant now) {
        String day = DAY.format(now);
        Integer seq = jdbc.queryForObject("""
                INSERT INTO daily_counters(day, last_seq) VALUES (?, 1)
                ON CONFLICT(day) DO UPDATE SET last_seq = last_seq + 1
                RETURNING last_seq
                """, Integer.class, day);
        int value = seq == null ? 1 : seq;
        return "CHG-" + day + "-" + String.format("%04d", value);
    }

    private long lastInsertId() {
        Long id = jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
        return id == null ? 0 : id;
    }

    @Transactional(rollbackFor = Exception.class)
    public void voidSale(long saleId, String reason) {
        sessions.require("SALE_VOID");
        long userId = sessions.requireUser();
        SaleSummary sale = findById(saleId);
        if (sale == null) {
            throw new IllegalArgumentException("Sale not found.");
        }
        if ("VOIDED".equals(sale.status())) {
            throw new IllegalStateException("This sale is already voided.");
        }
        if ("REFUNDED".equals(sale.status()) || "PARTIALLY_REFUNDED".equals(sale.status())) {
            throw new IllegalStateException("A sale with refunds cannot be voided.");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("A void reason is required.");
        }

        String now = Instant.now().toString();
        int updated = jdbc.update("""
                UPDATE sales SET status = 'VOIDED', voided_at = ?, voided_by = ?, void_reason = ?
                WHERE id = ? AND status = 'COMPLETED'
                """, now, userId, reason.trim(), saleId);
        if (updated == 0) {
            throw new IllegalStateException("This sale can no longer be voided.");
        }

        List<SaleItemRow> items = items(saleId);
        for (SaleItemRow item : items) {
            jdbc.update("UPDATE inventory SET quantity = quantity + ?, updated_at = ? WHERE product_id = ?",
                    item.quantity(), now, item.productId());
            jdbc.update("""
                    INSERT INTO stock_movements(product_id, delta, reason, reference_type,
                        reference_id, user_id, shift_id, created_at)
                    VALUES (?, ?, 'VOID', 'SALE', ?, ?, ?, ?)
                    """, item.productId(), item.quantity(), saleId, userId, sale.shiftId(), now);
        }

        audit.record(userId, sale.shiftId(), "SALE_VOIDED", "SALE", saleId,
                "Voided sale " + sale.receiptNumber(), "reason=" + reason.trim());
    }

    public SaleSummary findById(long saleId) {
        List<SaleSummary> rows = jdbc.query(baseSaleQuery() + " WHERE s.id = ?", this::mapSale, saleId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public SaleSummary findByReceipt(String receipt) {
        List<SaleSummary> rows = jdbc.query(baseSaleQuery() + " WHERE s.receipt_number = ?",
                this::mapSale, receipt);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<SaleSummary> recent(int limit, Long cashierId, boolean allCashiers) {
        if (allCashiers || cashierId == null) {
            return jdbc.query(baseSaleQuery() + " ORDER BY s.created_at DESC LIMIT ?",
                    this::mapSale, limit);
        }
        return jdbc.query(baseSaleQuery() + " WHERE s.cashier_id = ? ORDER BY s.created_at DESC LIMIT ?",
                this::mapSale, cashierId, limit);
    }

    public List<SaleSummary> search(LocalDate from, LocalDate to, String status,
                                    String channel, Long cashierId, boolean allCashiers) {
        StringBuilder sql = new StringBuilder(baseSaleQuery()).append(" WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (from != null) {
            sql.append(" AND substr(s.created_at, 1, 10) >= ?");
            args.add(from.toString());
        }
        if (to != null) {
            sql.append(" AND substr(s.created_at, 1, 10) <= ?");
            args.add(to.toString());
        }
        if (status != null && !status.isBlank() && !"ALL".equals(status)) {
            sql.append(" AND s.status = ?");
            args.add(status);
        }
        if (channel != null && !channel.isBlank() && !"ALL".equals(channel)) {
            sql.append(" AND s.channel = ?");
            args.add(channel);
        }
        if (!allCashiers && cashierId != null) {
            sql.append(" AND s.cashier_id = ?");
            args.add(cashierId);
        }
        sql.append(" ORDER BY s.created_at DESC LIMIT 500");
        return jdbc.query(sql.toString(), this::mapSale, args.toArray());
    }

    public List<SaleItemRow> items(long saleId) {
        return jdbc.query("""
                SELECT si.id, si.line_no, si.product_id, si.sku_snapshot, si.name_snapshot,
                       si.unit_price_cents, si.quantity, si.discount_cents, si.tax_cents,
                       si.line_total_cents,
                       COALESCE((SELECT SUM(ri.quantity) FROM refund_items ri
                                 WHERE ri.sale_item_id = si.id), 0) AS refunded
                FROM sale_items si
                WHERE si.sale_id = ?
                ORDER BY si.line_no
                """, (rs, rowNum) -> new SaleItemRow(
                rs.getLong("id"), rs.getInt("line_no"), rs.getLong("product_id"),
                rs.getString("sku_snapshot"), rs.getString("name_snapshot"),
                rs.getLong("unit_price_cents"), rs.getInt("quantity"),
                rs.getLong("discount_cents"), rs.getLong("tax_cents"),
                rs.getLong("line_total_cents"), rs.getInt("refunded")), saleId);
    }

    private String baseSaleQuery() {
        return """
                SELECT s.id, s.receipt_number, u.full_name AS cashier_name, s.channel,
                       s.subtotal_cents, s.discount_cents, s.tax_cents, s.rounding_cents,
                       s.total_cents, s.status, s.created_at, s.kiosk_order_id, s.shift_id,
                       (SELECT COALESCE(SUM(si.quantity), 0) FROM sale_items si
                        WHERE si.sale_id = s.id) AS item_count,
                       (SELECT GROUP_CONCAT(p.method) FROM payments p
                        WHERE p.sale_id = s.id) AS payment_summary
                FROM sales s
                JOIN users u ON u.id = s.cashier_id
                """;
    }

    private SaleSummary mapSale(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        Long kioskOrderId = rs.getObject("kiosk_order_id") == null
                ? null : rs.getLong("kiosk_order_id");
        return new SaleSummary(
                rs.getLong("id"),
                rs.getString("receipt_number"),
                rs.getString("cashier_name"),
                rs.getString("channel"),
                rs.getLong("subtotal_cents"),
                rs.getLong("discount_cents"),
                rs.getLong("tax_cents"),
                rs.getLong("rounding_cents"),
                rs.getLong("total_cents"),
                rs.getString("status"),
                Instant.parse(rs.getString("created_at")),
                rs.getInt("item_count"),
                rs.getString("payment_summary"),
                kioskOrderId,
                rs.getLong("shift_id")
        );
    }

    public boolean canViewAll() {
        return sessions.has("SALE_VIEW_ALL");
    }

    public boolean canReprintAll() {
        return sessions.has("RECEIPT_REPRINT_ALL");
    }

    public static String statusLabel(String status) {
        return SaleStatus.valueOf(status).name().replace('_', ' ');
    }
}
