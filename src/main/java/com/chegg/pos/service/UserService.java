package com.chegg.pos.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/** Staff accounts. The last active System Admin is protected. */
@Service
public class UserService {

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final SessionService sessions;
    private final AuditService audit;

    public UserService(JdbcTemplate jdbc, PasswordEncoder passwordEncoder,
                       SessionService sessions, AuditService audit) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.sessions = sessions;
        this.audit = audit;
    }

    public List<UserRow> users() {
        return jdbc.query("""
                SELECT u.id, u.username, u.full_name, u.role_id, r.code AS role_code,
                       r.name AS role_name, u.active, u.must_change_password,
                       u.failed_attempts, u.locked_until, u.last_login_at
                FROM users u JOIN roles r ON r.id = u.role_id
                ORDER BY u.active DESC, r.name, u.username
                """, (rs, rowNum) -> map(rs));
    }

    public UserRow find(long id) {
        List<UserRow> rows = jdbc.query("""
                SELECT u.id, u.username, u.full_name, u.role_id, r.code AS role_code,
                       r.name AS role_name, u.active, u.must_change_password,
                       u.failed_attempts, u.locked_until, u.last_login_at
                FROM users u JOIN roles r ON r.id = u.role_id
                WHERE u.id = ?
                """, (rs, rowNum) -> map(rs), id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<RoleRow> roles() {
        return jdbc.query("""
                SELECT id, code, name, locked, max_discount_ppm FROM roles ORDER BY id
                """, (rs, rowNum) -> new RoleRow(
                rs.getLong("id"), rs.getString("code"), rs.getString("name"),
                rs.getInt("locked") == 1, rs.getLong("max_discount_ppm")));
    }

    @Transactional(rollbackFor = Exception.class)
    public long create(String username, String fullName, long roleId, String password) {
        sessions.require("USER_MANAGE");
        long actor = sessions.requireUser();
        if (username == null || username.isBlank() || fullName == null || fullName.isBlank()) {
            throw new IllegalArgumentException("Username and full name are required.");
        }
        if (password == null || password.length() < 8) {
            throw new IllegalArgumentException("Password must be at least 8 characters.");
        }
        RoleRow role = role(roleId);
        if (role == null) {
            throw new IllegalArgumentException("Choose a role.");
        }
        if ("SYSTEM_ADMIN".equals(role.code()) && !"SYSTEM_ADMIN".equals(sessions.roleCode())) {
            throw new SecurityException("Only a System Admin can create another System Admin.");
        }
        Integer existing = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE username = ?", Integer.class, username.trim());
        if (existing != null && existing > 0) {
            throw new IllegalArgumentException("That username is already taken.");
        }

        String now = Instant.now().toString();
        jdbc.update("""
                INSERT INTO users(username, password_hash, full_name, role_id, active,
                    must_change_password, failed_attempts, created_at, updated_at)
                VALUES (?, ?, ?, ?, 1, 1, 0, ?, ?)
                """, username.trim(), passwordEncoder.encode(password), fullName.trim(), roleId, now, now);
        long id = lastInsertId();
        audit.record(actor, null, "USER_CREATED", "USER", id,
                "Created user " + username.trim(), "role=" + role.code());
        return id;
    }

    @Transactional(rollbackFor = Exception.class)
    public void update(long id, String fullName, long roleId, boolean active) {
        sessions.require("USER_MANAGE");
        long actor = sessions.requireUser();
        UserRow before = find(id);
        if (before == null) {
            throw new IllegalArgumentException("User not found.");
        }
        RoleRow role = role(roleId);
        if (role == null) {
            throw new IllegalArgumentException("Choose a role.");
        }
        if ("SYSTEM_ADMIN".equals(role.code()) && !"SYSTEM_ADMIN".equals(sessions.roleCode())) {
            throw new SecurityException("Only a System Admin can assign the System Admin role.");
        }
        if ("SYSTEM_ADMIN".equals(before.roleCode()) && !"SYSTEM_ADMIN".equals(sessions.roleCode())) {
            throw new SecurityException("Only a System Admin can change a System Admin account.");
        }
        if (id == actor && !active) {
            throw new IllegalStateException("You cannot deactivate your own account.");
        }
        boolean removingLastAdmin = "SYSTEM_ADMIN".equals(before.roleCode())
                && (!active || !"SYSTEM_ADMIN".equals(role.code()));
        if (removingLastAdmin && activeSystemAdmins() <= 1) {
            throw new IllegalStateException("The last active System Admin cannot be changed.");
        }

        jdbc.update("""
                UPDATE users SET full_name = ?, role_id = ?, active = ?, updated_at = ?
                WHERE id = ?
                """, fullName == null ? before.fullName() : fullName.trim(), roleId,
                active ? 1 : 0, Instant.now().toString(), id);

        audit.record(actor, null, "USER_UPDATED", "USER", id,
                "Updated user " + before.username(),
                "role=" + before.roleCode() + " -> " + role.code()
                        + ", active=" + before.active() + " -> " + active);
        if (!active && before.active()) {
            audit.record(actor, null, "USER_DEACTIVATED", "USER", id,
                    "Deactivated user " + before.username(), null);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void resetPassword(long id, String password) {
        sessions.require("USER_MANAGE");
        long actor = sessions.requireUser();
        UserRow user = find(id);
        if (user == null) {
            throw new IllegalArgumentException("User not found.");
        }
        if ("SYSTEM_ADMIN".equals(user.roleCode()) && !"SYSTEM_ADMIN".equals(sessions.roleCode())) {
            throw new SecurityException("Only a System Admin can reset a System Admin password.");
        }
        if (password == null || password.length() < 8) {
            throw new IllegalArgumentException("Password must be at least 8 characters.");
        }
        jdbc.update("""
                UPDATE users SET password_hash = ?, must_change_password = 1,
                    failed_attempts = 0, locked_until = NULL, updated_at = ?
                WHERE id = ?
                """, passwordEncoder.encode(password), Instant.now().toString(), id);
        audit.record(actor, null, "USER_UPDATED", "USER", id,
                "Reset password for " + user.username(), null);
    }

    private int activeSystemAdmins() {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM users u JOIN roles r ON r.id = u.role_id
                WHERE r.code = 'SYSTEM_ADMIN' AND u.active = 1
                """, Integer.class);
        return count == null ? 0 : count;
    }

    private RoleRow role(long id) {
        List<RoleRow> rows = jdbc.query("""
                SELECT id, code, name, locked, max_discount_ppm FROM roles WHERE id = ?
                """, (rs, rowNum) -> new RoleRow(
                rs.getLong("id"), rs.getString("code"), rs.getString("name"),
                rs.getInt("locked") == 1, rs.getLong("max_discount_ppm")), id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private UserRow map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new UserRow(
                rs.getLong("id"), rs.getString("username"), rs.getString("full_name"),
                rs.getLong("role_id"), rs.getString("role_code"), rs.getString("role_name"),
                rs.getInt("active") == 1, rs.getInt("must_change_password") == 1,
                rs.getInt("failed_attempts"), rs.getString("locked_until"),
                rs.getString("last_login_at"));
    }

    private long lastInsertId() {
        Long id = jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
        return id == null ? 0 : id;
    }

    public record UserRow(long id, String username, String fullName, long roleId,
                          String roleCode, String roleName, boolean active,
                          boolean mustChangePassword, int failedAttempts,
                          String lockedUntil, String lastLoginAt) {
    }

    public record RoleRow(long id, String code, String name, boolean locked, long maxDiscountPpm) {
    }
}
