package com.chegg.pos.service;

import com.chegg.pos.model.SaleItemRow;
import com.chegg.pos.model.SaleSummary;
import com.chegg.pos.model.Shift;
import com.chegg.pos.model.enums.PaymentMethod;
import com.chegg.pos.util.Money;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Partial/full refunds with restocking and sale-status transitions. */
@Service
public class RefundService {

    private final JdbcTemplate jdbc;
    private final SessionService sessions;
    private final AuditService audit;
    private final SaleService sales;
    private final ShiftService shifts;

    public RefundService(JdbcTemplate jdbc,
                         SessionService sessions,
                         AuditService audit,
                         SaleService sales,
                         ShiftService shifts) {
        this.jdbc = jdbc;
        this.sessions = sessions;
        this.audit = audit;
        this.sales = sales;
        this.shifts = shifts;
    }

    @Transactional(rollbackFor = Exception.class)
    public long refund(RefundRequest request) {
        sessions.require("REFUND_APPROVE");
        long userId = sessions.requireUser();
        Shift shift = shifts.requireOpenShift();

        SaleSummary sale = sales.findById(request.saleId());
        if (sale == null) {
            throw new IllegalArgumentException("Sale not found.");
        }
        if ("VOIDED".equals(sale.status())) {
            throw new IllegalStateException("A voided sale cannot be refunded.");
        }
        if ("REFUNDED".equals(sale.status())) {
            throw new IllegalStateException("This sale has already been fully refunded.");
        }
        if (request.lines() == null || request.lines().isEmpty()) {
            throw new IllegalArgumentException("Select at least one item to refund.");
        }
        if (request.reason() == null || request.reason().isBlank()) {
            throw new IllegalArgumentException("A refund reason is required.");
        }

        List<SaleItemRow> items = sales.items(sale.id());
        List<ResolvedLine> resolved = new ArrayList<>();
        long amount = 0;
        long tax = 0;

        for (RefundLine requested : request.lines()) {
            if (requested.quantity() <= 0) {
                continue;
            }
            SaleItemRow item = items.stream()
                    .filter(row -> row.id() == requested.saleItemId())
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Sale line not found."));
            if (requested.quantity() > item.remainingQuantity()) {
                throw new IllegalArgumentException("Only " + item.remainingQuantity()
                        + " of " + item.name() + " can still be refunded.");
            }

            long lineAmount = proportion(item.lineTotalCents(), requested.quantity(), item.quantity());
            long lineTax = proportion(item.taxCents(), requested.quantity(), item.quantity());
            amount += lineAmount;
            tax += lineTax;
            resolved.add(new ResolvedLine(item, requested.quantity(), lineAmount));
        }

        if (amount <= 0) {
            throw new IllegalArgumentException("The selected refund is zero.");
        }

        String now = Instant.now().toString();
        jdbc.update("""
                INSERT INTO refunds(sale_id, shift_id, user_id, receipt_number, method,
                    amount_cents, tax_cents, reason, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, sale.id(), shift.id(), userId, sale.receiptNumber(),
                request.method().name(), amount, tax, request.reason().trim(), now);
        long refundId = lastInsertId();

        for (ResolvedLine line : resolved) {
            jdbc.update("""
                    INSERT INTO refund_items(refund_id, sale_item_id, quantity, amount_cents)
                    VALUES (?, ?, ?, ?)
                    """, refundId, line.item().id(), line.quantity(), line.amountCents());

            jdbc.update("UPDATE inventory SET quantity = quantity + ?, updated_at = ? WHERE product_id = ?",
                    line.quantity(), now, line.item().productId());
            jdbc.update("""
                    INSERT INTO stock_movements(product_id, delta, reason, reference_type,
                        reference_id, user_id, shift_id, created_at)
                    VALUES (?, ?, 'REFUND', 'REFUND', ?, ?, ?, ?)
                    """, line.item().productId(), line.quantity(), refundId, userId, shift.id(), now);
        }

        int remainingLines = remainingLineCount(sale.id());
        jdbc.update("UPDATE sales SET status = ? WHERE id = ?",
                remainingLines == 0 ? "REFUNDED" : "PARTIALLY_REFUNDED", sale.id());

        audit.record(userId, shift.id(), "REFUND_ISSUED", "REFUND", refundId,
                "Refund of " + Money.format(amount) + " on " + sale.receiptNumber(),
                "saleId=" + sale.id() + ", method=" + request.method() + ", reason=" + request.reason().trim());

        return refundId;
    }

    public List<SaleItemRow> refundableItems(long saleId) {
        return sales.items(saleId);
    }

    private int remainingLineCount(long saleId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM sale_items si
                WHERE si.sale_id = ?
                  AND si.quantity > COALESCE(
                      (SELECT SUM(ri.quantity) FROM refund_items ri WHERE ri.sale_item_id = si.id), 0)
                """, Integer.class, saleId);
        return count == null ? 0 : count;
    }

    private static long proportion(long amount, int part, int whole) {
        if (whole <= 0) {
            return 0;
        }
        return (amount * part + whole / 2) / whole;
    }

    private long lastInsertId() {
        Long id = jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
        return id == null ? 0 : id;
    }

    public record RefundLine(long saleItemId, int quantity) {
    }

    private record ResolvedLine(SaleItemRow item, int quantity, long amountCents) {
    }

    public record RefundRequest(long saleId, List<RefundLine> lines,
                                PaymentMethod method, String reason) {
    }
}
