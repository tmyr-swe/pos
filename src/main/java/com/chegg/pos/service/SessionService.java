package com.chegg.pos.service;

import com.chegg.pos.model.User;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Active identity for the single-terminal process. Permissions are loaded from
 * the database at sign-in and checked by services, not just by hidden buttons.
 */
@Service
public class SessionService {

    private final JdbcTemplate jdbc;

    private volatile User user;
    private volatile Set<String> permissions = Set.of();

    public SessionService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void start(User user) {
        if (user == null) {
            throw new IllegalArgumentException("User is required.");
        }
        this.permissions = Set.copyOf(loadPermissions(user.roleCode()));
        this.user = user;
    }

    public void stop() {
        user = null;
        permissions = Set.of();
    }

    public User user() {
        return user;
    }

    public long requireUser() {
        User current = user;
        if (current == null) {
            throw new SecurityException("Please sign in to continue.");
        }
        return current.id();
    }

    public String roleCode() {
        User current = user;
        return current == null ? null : current.roleCode();
    }

    public boolean has(String permission) {
        return permissions.contains(permission);
    }

    public void require(String permission) {
        requireUser();
        if (!permissions.contains(permission)) {
            throw new SecurityException("You do not have permission to do that ("
                    + permission + ").");
        }
    }

    public Set<String> permissions() {
        return Set.copyOf(permissions);
    }

    public List<String> loadPermissions(String roleCode) {
        return jdbc.queryForList("""
                SELECT p.code
                FROM permissions p
                JOIN role_permissions rp ON rp.permission_id = p.id
                JOIN roles r ON r.id = rp.role_id
                WHERE r.code = ?
                ORDER BY p.area, p.code
                """, String.class, roleCode);
    }

    public Set<String> permissionsForRole(long roleId) {
        return new LinkedHashSet<>(jdbc.queryForList("""
                SELECT p.code
                FROM permissions p
                JOIN role_permissions rp ON rp.permission_id = p.id
                WHERE rp.role_id = ?
                ORDER BY p.area, p.code
                """, String.class, roleId));
    }

    /** Used by role editing to show a role's current matrix. */
    public Set<String> allPermissionCodes() {
        return new HashSet<>(jdbc.queryForList("SELECT code FROM permissions", String.class));
    }
}
