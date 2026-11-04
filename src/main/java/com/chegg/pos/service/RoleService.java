package com.chegg.pos.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Permission matrix editing. The System Admin role is locked. */
@Service
public class RoleService {

    private final JdbcTemplate jdbc;
    private final SessionService sessions;
    private final AuditService audit;

    public RoleService(JdbcTemplate jdbc, SessionService sessions, AuditService audit) {
        this.jdbc = jdbc;
        this.sessions = sessions;
        this.audit = audit;
    }

    public List<RoleView> roles() {
        return jdbc.query("""
                SELECT r.id, r.code, r.name, r.locked, r.max_discount_ppm,
                       (SELECT COUNT(*) FROM role_permissions rp WHERE rp.role_id = r.id) AS permission_count
                FROM roles r ORDER BY r.id
                """, (rs, rowNum) -> new RoleView(
                rs.getLong("id"), rs.getString("code"), rs.getString("name"),
                rs.getInt("locked") == 1, rs.getLong("max_discount_ppm"),
                rs.getInt("permission_count")));
    }

    public List<PermissionRow> permissions() {
        return jdbc.query("""
                SELECT id, code, area, label FROM permissions ORDER BY area, code
                """, (rs, rowNum) -> new PermissionRow(
                rs.getLong("id"), rs.getString("code"), rs.getString("area"), rs.getString("label")));
    }

    public Set<String> rolePermissions(long roleId) {
        return new LinkedHashSet<>(jdbc.queryForList("""
                SELECT p.code FROM permissions p
                JOIN role_permissions rp ON rp.permission_id = p.id
                WHERE rp.role_id = ?
                """, String.class, roleId));
    }

    @Transactional(rollbackFor = Exception.class)
    public void update(long roleId, Set<String> permissionCodes, long maxDiscountPpm) {
        sessions.require("ROLE_CONFIGURE");
        long actor = sessions.requireUser();
        List<RoleView> roles = jdbc.query("""
                SELECT r.id, r.code, r.name, r.locked, r.max_discount_ppm,
                       (SELECT COUNT(*) FROM role_permissions rp WHERE rp.role_id = r.id) AS permission_count
                FROM roles r WHERE r.id = ?
                """, (rs, rowNum) -> new RoleView(
                rs.getLong("id"), rs.getString("code"), rs.getString("name"),
                rs.getInt("locked") == 1, rs.getLong("max_discount_ppm"),
                rs.getInt("permission_count")), roleId);
        if (roles.isEmpty()) {
            throw new IllegalArgumentException("Role not found.");
        }
        RoleView role = roles.get(0);
        if (role.locked()) {
            throw new IllegalStateException("The System Admin role is locked to all permissions.");
        }
        if (maxDiscountPpm < 0 || maxDiscountPpm > 1_000_000) {
            throw new IllegalArgumentException("Maximum discount must be between 0% and 100%.");
        }

        Set<String> before = rolePermissions(roleId);
        Set<String> after = permissionCodes == null ? Set.of() : new LinkedHashSet<>(permissionCodes);
        jdbc.update("DELETE FROM role_permissions WHERE role_id = ?", roleId);
        for (String code : after) {
            Integer permissionId = jdbc.queryForObject(
                    "SELECT id FROM permissions WHERE code = ?", Integer.class, code);
            if (permissionId != null) {
                jdbc.update("INSERT INTO role_permissions(role_id, permission_id) VALUES (?, ?)",
                        roleId, permissionId);
            }
        }
        jdbc.update("UPDATE roles SET max_discount_ppm = ? WHERE id = ?", maxDiscountPpm, roleId);

        audit.record(actor, null, "ROLE_UPDATED", "ROLE", roleId,
                "Updated role " + role.code(),
                "permissions " + before.size() + " -> " + after.size()
                        + ", maxDiscount " + role.maxDiscountPpm() + " -> " + maxDiscountPpm
                        + ", added=" + minus(after, before) + ", removed=" + minus(before, after));
    }

    private static Set<String> minus(Set<String> a, Set<String> b) {
        Set<String> result = new LinkedHashSet<>(a);
        result.removeAll(b);
        return result;
    }

    public record RoleView(long id, String code, String name, boolean locked,
                           long maxDiscountPpm, int permissionCount) {
    }

    public record PermissionRow(long id, String code, String area, String label) {
    }
}
