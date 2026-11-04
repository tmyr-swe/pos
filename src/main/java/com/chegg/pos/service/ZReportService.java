package com.chegg.pos.service;

import com.chegg.pos.app.AppProperties;
import com.chegg.pos.model.Shift;
import com.chegg.pos.model.ZReport;
import com.chegg.pos.util.Money;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** Aggregates a shift into an immutable Z-report snapshot and renders its text. */
@Service
public class ZReportService {

    private static final DateTimeFormatter STAMP = DateTimeFormatter
            .ofPattern("dd MMM yyyy HH:mm")
            .withZone(ZoneId.of("Africa/Nairobi"));

    private static final RowMapper<ZReport> ROW_MAPPER = (rs, rowNum) -> new ZReport(
            rs.getLong("id"), rs.getLong("shift_id"),
            Instant.parse(rs.getString("generated_at")),
            rs.getLong("gross_cents"), rs.getLong("discounts_cents"),
            rs.getLong("net_cents"), rs.getLong("tax_cents"),
            rs.getLong("cash_cents"), rs.getLong("card_cents"),
            rs.getLong("rounding_cents"), rs.getLong("refunds_cents"),
            rs.getInt("refund_count"), rs.getInt("void_count"),
            rs.getInt("txn_count"), rs.getLong("opening_cash_cents"),
            rs.getLong("expected_cash_cents"), rs.getLong("counted_cash_cents"),
            rs.getLong("variance_cents"), rs.getString("report_text"));

    private final JdbcTemplate jdbc;
    private final SettingsService settings;
    private final AppProperties appProperties;

    public ZReportService(JdbcTemplate jdbc, SettingsService settings, AppProperties appProperties) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.appProperties = appProperties;
    }

    public ZReport snapshot(Shift shift, long countedCashCents) {
        long opening = shift.openingCashCents();

        Totals totals = jdbc.queryForObject("""
                SELECT COALESCE(SUM(subtotal_cents), 0) AS gross,
                       COALESCE(SUM(discount_cents), 0) AS discounts,
                       COALESCE(SUM(tax_cents), 0) AS tax,
                       COALESCE(SUM(rounding_cents), 0) AS rounding,
                       COUNT(*) AS txn
                FROM sales
                WHERE shift_id = ? AND status <> 'VOIDED'
                """, (rs, rowNum) -> new Totals(
                rs.getLong("gross"),
                rs.getLong("discounts"),
                rs.getLong("tax"),
                rs.getLong("rounding"),
                rs.getInt("txn")), shift.id());

        if (totals == null) {
            totals = new Totals(0, 0, 0, 0, 0);
        }

        long cash = paymentTotal(shift.id(), "CASH");
        long card = paymentTotal(shift.id(), "CARD");
        long cashRefunds = refundTotal(shift.id(), "CASH");
        long refunds = refundTotal(shift.id(), null);
        int refundCount = count("SELECT COUNT(*) FROM refunds WHERE shift_id = ?", shift.id());
        int voidCount = count("SELECT COUNT(*) FROM sales WHERE shift_id = ? AND status = 'VOIDED'", shift.id());

        long net = totals.gross - totals.discounts;
        long expected = opening + cash - cashRefunds;
        long variance = countedCashCents - expected;

        ZReport report = new ZReport(0, shift.id(), Instant.now(),
                totals.gross, totals.discounts, net, totals.tax,
                cash, card, totals.rounding, refunds, refundCount, voidCount, totals.txn,
                opening, expected, countedCashCents, variance, "");

        return new ZReport(report.id(), report.shiftId(), report.generatedAt(),
                report.grossCents(), report.discountsCents(), report.netCents(),
                report.taxCents(), report.cashCents(), report.cardCents(),
                report.roundingCents(), report.refundsCents(), report.refundCount(),
                report.voidCount(), report.txnCount(), report.openingCashCents(),
                report.expectedCashCents(), report.countedCashCents(),
                report.varianceCents(), render(shift, report));
    }

    public ZReport save(ZReport report) {
        jdbc.update("""
                INSERT INTO z_reports(shift_id, generated_at, gross_cents, discounts_cents,
                    net_cents, tax_cents, cash_cents, card_cents, rounding_cents,
                    refunds_cents, refund_count, void_count, txn_count,
                    opening_cash_cents, expected_cash_cents, counted_cash_cents,
                    variance_cents, report_text)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                report.shiftId(), report.generatedAt().toString(), report.grossCents(),
                report.discountsCents(), report.netCents(), report.taxCents(),
                report.cashCents(), report.cardCents(), report.roundingCents(),
                report.refundsCents(), report.refundCount(), report.voidCount(),
                report.txnCount(), report.openingCashCents(), report.expectedCashCents(),
                report.countedCashCents(), report.varianceCents(), report.reportText());

        Long id = jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
        return new ZReport(id == null ? 0 : id, report.shiftId(), report.generatedAt(),
                report.grossCents(), report.discountsCents(), report.netCents(),
                report.taxCents(), report.cashCents(), report.cardCents(),
                report.roundingCents(), report.refundsCents(), report.refundCount(),
                report.voidCount(), report.txnCount(), report.openingCashCents(),
                report.expectedCashCents(), report.countedCashCents(),
                report.varianceCents(), report.reportText());
    }

    public ZReport findByShift(long shiftId) {
        List<ZReport> reports = jdbc.query("""
                SELECT id, shift_id, generated_at, gross_cents, discounts_cents, net_cents,
                       tax_cents, cash_cents, card_cents, rounding_cents, refunds_cents,
                       refund_count, void_count, txn_count, opening_cash_cents,
                       expected_cash_cents, counted_cash_cents, variance_cents, report_text
                FROM z_reports WHERE shift_id = ?
                """, ROW_MAPPER, shiftId);
        return reports.isEmpty() ? null : reports.get(0);
    }

    public ZReport findById(long id) {
        List<ZReport> reports = jdbc.query("""
                SELECT id, shift_id, generated_at, gross_cents, discounts_cents, net_cents,
                       tax_cents, cash_cents, card_cents, rounding_cents, refunds_cents,
                       refund_count, void_count, txn_count, opening_cash_cents,
                       expected_cash_cents, counted_cash_cents, variance_cents, report_text
                FROM z_reports WHERE id = ?
                """, ROW_MAPPER, id);
        return reports.isEmpty() ? null : reports.get(0);
    }

    public List<ZReport> recent(int limit) {
        return jdbc.query("""
                SELECT id, shift_id, generated_at, gross_cents, discounts_cents, net_cents,
                       tax_cents, cash_cents, card_cents, rounding_cents, refunds_cents,
                       refund_count, void_count, txn_count, opening_cash_cents,
                       expected_cash_cents, counted_cash_cents, variance_cents, report_text
                FROM z_reports ORDER BY generated_at DESC LIMIT ?
                """, ROW_MAPPER, limit);
    }

    public long expectedCash(Shift shift) {
        return shift.openingCashCents()
                + paymentTotal(shift.id(), "CASH")
                - refundTotal(shift.id(), "CASH");
    }

    private long paymentTotal(long shiftId, String method) {
        Long value = jdbc.queryForObject("""
                SELECT COALESCE(SUM(p.amount_cents), 0)
                FROM payments p
                JOIN sales s ON s.id = p.sale_id
                WHERE s.shift_id = ? AND s.status <> 'VOIDED' AND p.method = ?
                """, Long.class, shiftId, method);
        return value == null ? 0 : value;
    }

    private long refundTotal(long shiftId, String method) {
        Long value = method == null
                ? jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount_cents), 0) FROM refunds WHERE shift_id = ?",
                Long.class, shiftId)
                : jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount_cents), 0) FROM refunds WHERE shift_id = ? AND method = ?",
                Long.class, shiftId, method);
        return value == null ? 0 : value;
    }

    private int count(String sql, long shiftId) {
        Integer value = jdbc.queryForObject(sql, Integer.class, shiftId);
        return value == null ? 0 : value;
    }

    private String render(Shift shift, ZReport r) {
        String line = "-".repeat(42);
        String store = settings.storeName();
        String terminal = appProperties.resolvedTerminalId();
        StringBuilder text = new StringBuilder();
        text.append(store).append(" - Z REPORT\n");
        text.append("Terminal: ").append(terminal).append('\n');
        text.append("Shift #").append(shift.id()).append('\n');
        text.append("Opened: ").append(STAMP.format(shift.openedAt())).append('\n');
        text.append("Closed: ").append(STAMP.format(Instant.now())).append('\n');
        text.append(line).append('\n');
        text.append(String.format("%-28s %12s%n", "Gross sales", Money.formatPlain(r.grossCents())));
        text.append(String.format("%-28s %12s%n", "Discounts", Money.formatPlain(r.discountsCents())));
        text.append(String.format("%-28s %12s%n", "Net sales", Money.formatPlain(r.netCents())));
        text.append(String.format("%-28s %12s%n", "Tax", Money.formatPlain(r.taxCents())));
        text.append(String.format("%-28s %12s%n", "Cash payments", Money.formatPlain(r.cashCents())));
        text.append(String.format("%-28s %12s%n", "Card payments", Money.formatPlain(r.cardCents())));
        text.append(String.format("%-28s %12s%n", "Cash rounding", Money.formatPlain(r.roundingCents())));
        text.append(String.format("%-28s %12s%n", "Refunds (" + r.refundCount() + ")",
                Money.formatPlain(r.refundsCents())));
        text.append(String.format("%-28s %12d%n", "Transactions", r.txnCount()));
        text.append(String.format("%-28s %12d%n", "Voids", r.voidCount()));
        text.append(line).append('\n');
        text.append(String.format("%-28s %12s%n", "Opening cash", Money.formatPlain(r.openingCashCents())));
        text.append(String.format("%-28s %12s%n", "Expected cash", Money.formatPlain(r.expectedCashCents())));
        text.append(String.format("%-28s %12s%n", "Counted cash", Money.formatPlain(r.countedCashCents())));
        text.append(String.format("%-28s %12s%n", "Variance", Money.formatPlain(r.varianceCents())));
        text.append(line).append('\n');
        text.append("Average ticket: ").append(Money.format(r.averageTicketCents())).append('\n');
        text.append("Generated by Chegg POS\n");
        return text.toString();
    }

    private record Totals(long gross, long discounts, long tax, long rounding, int txn) {
    }
}
