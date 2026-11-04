package com.chegg.pos.service;

import com.chegg.pos.model.AuditEntry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Append-only audit trail. Business events join the caller's transaction;
 * security events commit independently so a failed login is never rolled back.
 */
@Service
public class AuditService {

    private final JdbcTemplate jdbc;

    public AuditService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void record(Long userId,
                       Long shiftId,
                       String action,
                       String entityType,
                       Long entityId,
                       String summary,
                       String details) {
        insert(userId, shiftId, action, entityType, entityId, summary, details);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void recordSecurityEvent(Long userId,
                                    String action,
                                    String summary,
                                    String details) {
        insert(userId, null, action, "USER", userId, summary, details);
    }

    public List<AuditEntry> recent(int limit, String actionFilter) {
        String sql = """
                SELECT a.id, a.at, a.user_id, u.full_name AS user_name, a.shift_id,
                       a.action, a.entity_type, a.entity_id, a.summary, a.details
                FROM audit_log a
                LEFT JOIN users u ON u.id = a.user_id
                """;
        if (actionFilter == null || actionFilter.isBlank() || "ALL".equals(actionFilter)) {
            return jdbc.query(sql + " ORDER BY a.at DESC, a.id DESC LIMIT ?", this::map, limit);
        }
        return jdbc.query(sql + " WHERE a.action = ? ORDER BY a.at DESC, a.id DESC LIMIT ?",
                this::map, actionFilter, limit);
    }

    public List<String> actions() {
        return jdbc.queryForList("SELECT DISTINCT action FROM audit_log ORDER BY action", String.class);
    }

    private AuditEntry map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new AuditEntry(
                rs.getLong("id"),
                Instant.parse(rs.getString("at")),
                rs.getObject("user_id") == null ? null : rs.getLong("user_id"),
                rs.getString("user_name"),
                rs.getObject("shift_id") == null ? null : rs.getLong("shift_id"),
                rs.getString("action"),
                rs.getString("entity_type"),
                rs.getObject("entity_id") == null ? null : rs.getLong("entity_id"),
                rs.getString("summary"),
                rs.getString("details"));
    }

    private void insert(Long userId,
                        Long shiftId,
                        String action,
                        String entityType,
                        Long entityId,
                        String summary,
                        String details) {
        jdbc.update("""
                        INSERT INTO audit_log(at, user_id, shift_id, action, entity_type, entity_id, summary, details)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                Instant.now().toString(), userId, shiftId, action, entityType, entityId, summary, details);
    }
}
