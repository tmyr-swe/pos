package com.chegg.pos.service;

import com.chegg.pos.model.User;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * BCrypt authentication with a 5-failure / 15-minute lockout.
 * The kiosk account has no password because it runs in device mode.
 */
@Service
public class AuthService {

    static final int MAX_FAILED_ATTEMPTS = 5;
    static final Duration LOCK_DURATION = Duration.ofMinutes(15);
    static final int MIN_PASSWORD_LENGTH = 8;

    private static final String FIND_USER = """
            SELECT u.id, u.username, u.password_hash, u.full_name, u.active,
                   u.must_change_password, u.failed_attempts, u.locked_until,
                   r.code AS role_code, r.name AS role_name
            FROM users u
            JOIN roles r ON r.id = u.role_id
            WHERE u.username = ?
            """;

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;
    private final SessionService sessions;

    public AuthService(JdbcTemplate jdbc,
                       PasswordEncoder passwordEncoder,
                       AuditService auditService,
                       SessionService sessions) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
        this.sessions = sessions;
    }

    public LoginResult login(String username, String rawPassword) {
        String name = username == null ? "" : username.trim();
        if (name.isEmpty() || rawPassword == null || rawPassword.isEmpty()) {
            return LoginResult.failure("Enter your username and password.");
        }

        UserRow row = findUser(name);
        if (row == null) {
            auditService.recordSecurityEvent(null, "LOGIN_FAILED",
                    "Unknown username attempted sign-in", "username=" + name);
            return LoginResult.failure("Invalid username or password.");
        }

        Instant now = Instant.now();
        if (row.lockedUntil() != null && row.lockedUntil().isAfter(now)) {
            long minutes = Math.max(1, Duration.between(now, row.lockedUntil()).toMinutes() + 1);
            auditService.recordSecurityEvent(row.id(), "LOGIN_FAILED",
                    "Sign-in attempted while account locked", "username=" + row.username());
            return LoginResult.failure("Account locked. Try again in about "
                    + minutes + (minutes == 1 ? " minute." : " minutes."));
        }

        if (!row.active()) {
            auditService.recordSecurityEvent(row.id(), "LOGIN_FAILED",
                    "Inactive account attempted sign-in", "username=" + row.username());
            return LoginResult.failure("This account is inactive. Ask an administrator for help.");
        }

        if (row.passwordHash() == null) {
            auditService.recordSecurityEvent(row.id(), "LOGIN_FAILED",
                    "Device-mode account attempted password sign-in", "username=" + row.username());
            return LoginResult.failure("This account runs in device mode and cannot sign in here.");
        }

        if (!passwordEncoder.matches(rawPassword, row.passwordHash())) {
            return registerFailure(row, now);
        }

        String nowText = now.toString();
        jdbc.update("""
                        UPDATE users
                        SET failed_attempts = 0, locked_until = NULL,
                            last_login_at = ?, updated_at = ?
                        WHERE id = ?
                        """,
                nowText, nowText, row.id());

        User user = new User(row.id(), row.username(), row.fullName(),
                row.roleCode(), row.roleName(), row.mustChangePassword());

        auditService.record(row.id(), null, "LOGIN_SUCCESS", "USER", row.id(),
                "Signed in as " + row.username(), "role=" + row.roleCode());

        sessions.start(user);
        return LoginResult.success(user);
    }

    /**
     * Device-mode sign-in for the customer kiosk. The kiosk account has no
     * password and only CUSTOMER accounts may be opened this way.
     */
    public LoginResult deviceLogin(String username) {
        UserRow row = findUser(username);
        if (row == null || !row.active() || row.passwordHash() != null
                || !"CUSTOMER".equals(row.roleCode())) {
            auditService.recordSecurityEvent(row == null ? null : row.id(), "LOGIN_FAILED",
                    "Device-mode sign-in rejected", "username=" + username);
            return LoginResult.failure("The customer kiosk is not available.");
        }

        User user = new User(row.id(), row.username(), row.fullName(),
                row.roleCode(), row.roleName(), false);
        sessions.start(user);
        auditService.record(row.id(), null, "LOGIN_SUCCESS", "USER", row.id(),
                "Customer kiosk started", "role=" + row.roleCode() + ", device=true");
        return LoginResult.success(user);
    }

    public void logout() {
        User current = sessions.user();
        if (current != null) {
            auditService.recordSecurityEvent(current.id(), "LOGOUT",
                    "Signed out", "username=" + current.username());
        }
        sessions.stop();
    }

    /**
     * Replaces the password for an account. Used for the forced first-login
     * change and, later, ordinary self-service password changes.
     */
    public void changePassword(long userId, String newPassword) {
        if (newPassword == null || newPassword.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException(
                    "Password must be at least " + MIN_PASSWORD_LENGTH + " characters.");
        }

        String hash = passwordEncoder.encode(newPassword);
        String now = Instant.now().toString();
        int updated = jdbc.update("""
                        UPDATE users
                        SET password_hash = ?, must_change_password = 0,
                            failed_attempts = 0, locked_until = NULL, updated_at = ?
                        WHERE id = ? AND active = 1
                        """,
                hash, now, userId);

        if (updated == 0) {
            throw new IllegalStateException("This account is no longer available.");
        }

        auditService.recordSecurityEvent(userId, "PASSWORD_CHANGED",
                "Password changed", "userId=" + userId);
    }

    private LoginResult registerFailure(UserRow row, Instant now) {
        int attempts = row.failedAttempts() + 1;

        if (attempts >= MAX_FAILED_ATTEMPTS) {
            String lockedUntil = now.plus(LOCK_DURATION).toString();
            jdbc.update("""
                            UPDATE users
                            SET failed_attempts = 0, locked_until = ?, updated_at = ?
                            WHERE id = ?
                            """,
                    lockedUntil, now.toString(), row.id());

            auditService.recordSecurityEvent(row.id(), "LOGIN_FAILED",
                    "Account locked after repeated failed sign-ins",
                    "username=" + row.username() + ", attempts=" + attempts);

            return LoginResult.failure(
                    "Too many failed attempts. This account is locked for 15 minutes.");
        }

        jdbc.update("""
                        UPDATE users
                        SET failed_attempts = ?, locked_until = NULL, updated_at = ?
                        WHERE id = ?
                        """,
                attempts, now.toString(), row.id());

        auditService.recordSecurityEvent(row.id(), "LOGIN_FAILED",
                "Failed sign-in attempt", "username=" + row.username() + ", attempt=" + attempts);

        int remaining = MAX_FAILED_ATTEMPTS - attempts;
        return LoginResult.failure("Invalid username or password. "
                + remaining + (remaining == 1 ? " attempt" : " attempts") + " left.");
    }

    private UserRow findUser(String username) {
        List<UserRow> rows = jdbc.query(FIND_USER, (rs, rowNum) -> {
            String lockedUntil = rs.getString("locked_until");
            return new UserRow(
                    rs.getLong("id"),
                    rs.getString("username"),
                    rs.getString("password_hash"),
                    rs.getString("full_name"),
                    rs.getInt("active") == 1,
                    rs.getInt("must_change_password") == 1,
                    rs.getInt("failed_attempts"),
                    lockedUntil == null ? null : Instant.parse(lockedUntil),
                    rs.getString("role_code"),
                    rs.getString("role_name")
            );
        }, username);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private record UserRow(
            long id,
            String username,
            String passwordHash,
            String fullName,
            boolean active,
            boolean mustChangePassword,
            int failedAttempts,
            Instant lockedUntil,
            String roleCode,
            String roleName
    ) {
    }
}
