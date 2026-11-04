package com.chegg.pos.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/** Read-only aggregations for dashboards and reports. */
@Service
public class ReportService {

    private static final ZoneId EAT = ZoneId.of("Africa/Nairobi");

    private final JdbcTemplate jdbc;
    private final CatalogService catalog;
    private final InventoryService inventory;
    private final KioskOrderService kioskOrders;
    private final ZReportService zReports;

    public ReportService(JdbcTemplate jdbc, CatalogService catalog,
                         InventoryService inventory, KioskOrderService kioskOrders,
                         ZReportService zReports) {
        this.jdbc = jdbc;
        this.catalog = catalog;
        this.inventory = inventory;
        this.kioskOrders = kioskOrders;
        this.zReports = zReports;
    }

    public Dashboard dashboard() {
        LocalDate today = LocalDate.now(EAT);
        Instant from = today.atStartOfDay(EAT).toInstant();
        Instant to = today.plusDays(1).atStartOfDay(EAT).toInstant();

        Today todayTotals = jdbc.queryForObject("""
                SELECT COALESCE(SUM(total_cents), 0) AS revenue,
                       COUNT(*) AS txn_count,
                       COALESCE(SUM(discount_cents), 0) AS discounts,
                       COALESCE(SUM(rounding_cents), 0) AS rounding
                FROM sales
                WHERE status <> 'VOIDED' AND created_at >= ? AND created_at < ?
                """, (rs, rowNum) -> new Today(
                rs.getLong("revenue"), rs.getInt("txn_count"),
                rs.getLong("discounts"), rs.getLong("rounding")), from.toString(), to.toString());
        if (todayTotals == null) {
            todayTotals = new Today(0, 0, 0, 0);
        }

        Integer openShifts = jdbc.queryForObject(
                "SELECT COUNT(*) FROM shifts WHERE status = 'OPEN'", Integer.class);
        Integer failed = jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_log WHERE action = 'LOGIN_FAILED' AND at >= ?
                """, Integer.class, from.toString());

        return new Dashboard(
                todayTotals.revenue(),
                todayTotals.txnCount(),
                todayTotals.txnCount() == 0 ? 0 : todayTotals.revenue() / todayTotals.txnCount(),
                todayTotals.discounts(),
                todayTotals.rounding(),
                catalog.lowStockCount(),
                openShifts == null ? 0 : openShifts,
                kioskOrders.pendingCount(),
                inventory.stockValueCents(),
                failed == null ? 0 : failed,
                catalog.categories().size());
    }

    public List<TopProduct> topProducts(LocalDate from, LocalDate to, int limit) {
        String sql = """
                SELECT si.product_id, si.name_snapshot AS name,
                       SUM(si.quantity) AS quantity,
                       SUM(si.line_total_cents) AS revenue
                FROM sale_items si
                JOIN sales s ON s.id = si.sale_id
                WHERE s.status <> 'VOIDED'
                """ + dateClause(from, to) + """
                GROUP BY si.product_id, si.name_snapshot
                ORDER BY revenue DESC
                LIMIT ?
                """;
        List<Object> args = dateArgs(from, to);
        args.add(limit);
        return jdbc.query(sql, (rs, rowNum) -> new TopProduct(
                rs.getLong("product_id"), rs.getString("name"),
                rs.getInt("quantity"), rs.getLong("revenue")), args.toArray());
    }

    public List<SalesPoint> salesTrend(int days) {
        LocalDate today = LocalDate.now(EAT);
        Instant from = today.minusDays(Math.max(1, days) - 1L).atStartOfDay(EAT).toInstant();
        List<SalesPoint> points = jdbc.query("""
                SELECT substr(created_at, 1, 10) AS day,
                       COALESCE(SUM(total_cents), 0) AS revenue,
                       COUNT(*) AS txn_count
                FROM sales
                WHERE status <> 'VOIDED' AND created_at >= ?
                GROUP BY day ORDER BY day
                """, (rs, rowNum) -> new SalesPoint(
                LocalDate.parse(rs.getString("day")), rs.getLong("revenue"), rs.getInt("txn_count")),
                from.toString());
        return new ArrayList<>(points);
    }

    public List<ZReportRow> zReports(int limit) {
        return zReports.recent(limit).stream()
                .map(z -> new ZReportRow(z.id(), z.shiftId(), z.generatedAt(),
                        z.netCents(), z.txnCount(), z.refundsCents(), z.varianceCents()))
                .toList();
    }

    public long stockValueCents() {
        return inventory.stockValueCents();
    }

    private static String dateClause(LocalDate from, LocalDate to) {
        StringBuilder clause = new StringBuilder();
        if (from != null) {
            clause.append(" AND substr(s.created_at, 1, 10) >= ?");
        }
        if (to != null) {
            clause.append(" AND substr(s.created_at, 1, 10) <= ?");
        }
        return clause.toString();
    }

    private static List<Object> dateArgs(LocalDate from, LocalDate to) {
        List<Object> args = new ArrayList<>();
        if (from != null) {
            args.add(from.toString());
        }
        if (to != null) {
            args.add(to.toString());
        }
        return args;
    }

    public record Dashboard(long todayRevenueCents, int todayTransactions, long averageTicketCents,
                            long todayDiscountsCents, long todayRoundingCents, int lowStockCount,
                            int openShifts, int kioskQueue, long stockValueCents,
                            int failedLogins, int categoryCount) {
    }

    public record TopProduct(long productId, String name, int quantity, long revenueCents) {
    }

    public record SalesPoint(LocalDate day, long revenueCents, int transactions) {
    }

    public record ZReportRow(long id, long shiftId, Instant generatedAt, long netCents,
                             int transactions, long refundsCents, long varianceCents) {
    }

    private record Today(long revenue, int txnCount, long discounts, long rounding) {
    }
}
