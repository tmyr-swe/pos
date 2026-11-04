package com.chegg.pos.service;

import com.chegg.pos.app.AppProperties;
import com.chegg.pos.model.Shift;
import com.chegg.pos.model.ZReport;
import com.chegg.pos.model.enums.ShiftStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/** Shift open/close lifecycle. One open shift per terminal is enforced by SQLite. */
@Service
public class ShiftService {

    private final JdbcTemplate jdbc;
    private final SessionService sessions;
    private final AuditService audit;
    private final ZReportService zReports;
    private final AppProperties appProperties;

    public ShiftService(JdbcTemplate jdbc,
                        SessionService sessions,
                        AuditService audit,
                        ZReportService zReports,
                        AppProperties appProperties) {
        this.jdbc = jdbc;
        this.sessions = sessions;
        this.audit = audit;
        this.zReports = zReports;
        this.appProperties = appProperties;
    }

    public Shift currentOpenShift() {
        List<Shift> rows = jdbc.query("""
                SELECT s.id, s.terminal_id, s.opened_by, u.full_name AS opened_by_name,
                       s.opened_at, s.opening_cash_cents, s.closed_by, s.closed_at,
                       s.counted_cash_cents, s.expected_cash_cents, s.variance_cents,
                       s.status, s.notes
                FROM shifts s
                JOIN users u ON u.id = s.opened_by
                WHERE s.terminal_id = ? AND s.status = 'OPEN'
                """, (rs, rowNum) -> map(rs), appProperties.resolvedTerminalId());
        return rows.isEmpty() ? null : rows.get(0);
    }

    public Shift requireOpenShift() {
        Shift shift = currentOpenShift();
        if (shift == null) {
            throw new IllegalStateException("Open the till before taking payments.");
        }
        return shift;
    }

    public Shift byId(long id) {
        List<Shift> rows = jdbc.query("""
                SELECT s.id, s.terminal_id, s.opened_by, u.full_name AS opened_by_name,
                       s.opened_at, s.opening_cash_cents, s.closed_by, s.closed_at,
                       s.counted_cash_cents, s.expected_cash_cents, s.variance_cents,
                       s.status, s.notes
                FROM shifts s
                JOIN users u ON u.id = s.opened_by
                WHERE s.id = ?
                """, (rs, rowNum) -> map(rs), id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<Shift> recent(int limit) {
        return jdbc.query("""
                SELECT s.id, s.terminal_id, s.opened_by, u.full_name AS opened_by_name,
                       s.opened_at, s.opening_cash_cents, s.closed_by, s.closed_at,
                       s.counted_cash_cents, s.expected_cash_cents, s.variance_cents,
                       s.status, s.notes
                FROM shifts s
                JOIN users u ON u.id = s.opened_by
                ORDER BY s.opened_at DESC LIMIT ?
                """, (rs, rowNum) -> map(rs), limit);
    }

    @Transactional(rollbackFor = Exception.class)
    public Shift openShift(long openingCashCents, String notes) {
        sessions.require("SHIFT_OPEN");
        long userId = sessions.requireUser();
        if (openingCashCents < 0) {
            throw new IllegalArgumentException("Opening cash cannot be negative.");
        }
        if (currentOpenShift() != null) {
            throw new IllegalStateException("A shift is already open on this terminal.");
        }

        String now = Instant.now().toString();
        jdbc.update("""
                INSERT INTO shifts(terminal_id, opened_by, opened_at, opening_cash_cents, status, notes)
                VALUES (?, ?, ?, ?, 'OPEN', ?)
                """, appProperties.resolvedTerminalId(), userId, now, openingCashCents, trim(notes));

        Long id = jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
        audit.record(userId, id, "SHIFT_OPENED", "SHIFT", id,
                "Opened till with " + com.chegg.pos.util.Money.format(openingCashCents),
                "terminal=" + appProperties.resolvedTerminalId());
        return byId(id == null ? 0 : id);
    }

    @Transactional(rollbackFor = Exception.class)
    public ZReport closeShift(long shiftId, long countedCashCents, String notes) {
        sessions.require("SHIFT_CLOSE");
        long userId = sessions.requireUser();
        Shift shift = byId(shiftId);
        if (shift == null) {
            throw new IllegalArgumentException("Shift not found.");
        }
        if (!shift.isOpen()) {
            throw new IllegalStateException("This shift is already closed.");
        }
        if (shift.openedBy() != userId && !sessions.has("SHIFT_VIEW_ALL")) {
            throw new SecurityException("You can only close your own shift.");
        }
        if (countedCashCents < 0) {
            throw new IllegalArgumentException("Counted cash cannot be negative.");
        }

        ZReport snapshot = zReports.snapshot(shift, countedCashCents);
        String now = Instant.now().toString();
        int updated = jdbc.update("""
                UPDATE shifts
                SET closed_by = ?, closed_at = ?, counted_cash_cents = ?,
                    expected_cash_cents = ?, variance_cents = ?, status = 'CLOSED', notes = ?
                WHERE id = ? AND status = 'OPEN'
                """, userId, now, countedCashCents, snapshot.expectedCashCents(),
                snapshot.varianceCents(), trim(notes), shiftId);

        if (updated == 0) {
            throw new IllegalStateException("This shift was already closed by another action.");
        }

        ZReport saved = zReports.save(snapshot);
        audit.record(userId, shiftId, "SHIFT_CLOSED", "SHIFT", shiftId,
                "Closed shift with " + com.chegg.pos.util.Money.format(countedCashCents),
                "expected=" + snapshot.expectedCashCents() + ", variance=" + snapshot.varianceCents());
        return saved;
    }

    public long expectedCash(Shift shift) {
        return zReports.expectedCash(shift);
    }

    private Shift map(java.sql.ResultSet rs) throws java.sql.SQLException {
        String closedAt = rs.getString("closed_at");
        Long closedBy = rs.getObject("closed_by") == null ? null : rs.getLong("closed_by");
        Long counted = rs.getObject("counted_cash_cents") == null ? null : rs.getLong("counted_cash_cents");
        Long expected = rs.getObject("expected_cash_cents") == null ? null : rs.getLong("expected_cash_cents");
        Long variance = rs.getObject("variance_cents") == null ? null : rs.getLong("variance_cents");
        return new Shift(
                rs.getLong("id"),
                rs.getString("terminal_id"),
                rs.getLong("opened_by"),
                rs.getString("opened_by_name"),
                Instant.parse(rs.getString("opened_at")),
                rs.getLong("opening_cash_cents"),
                closedBy,
                closedAt == null ? null : Instant.parse(closedAt),
                counted,
                expected,
                variance,
                ShiftStatus.valueOf(rs.getString("status")),
                rs.getString("notes")
        );
    }

    private static String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
