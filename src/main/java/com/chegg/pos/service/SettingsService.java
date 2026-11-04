package com.chegg.pos.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Typed settings backed by the {@code settings} table. */
@Service
public class SettingsService {

    private final JdbcTemplate jdbc;
    private final SessionService sessions;
    private final AuditService audit;

    public SettingsService(JdbcTemplate jdbc, SessionService sessions, AuditService audit) {
        this.jdbc = jdbc;
        this.sessions = sessions;
        this.audit = audit;
    }

    public String get(String key) {
        List<String> values = jdbc.queryForList(
                "SELECT value FROM settings WHERE key = ?", String.class, key);
        return values.isEmpty() ? null : values.get(0);
    }

    public String get(String key, String fallback) {
        String value = get(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    public boolean getBoolean(String key, boolean fallback) {
        String value = get(key);
        return value == null ? fallback : Boolean.parseBoolean(value.trim());
    }

    public int getInt(String key, int fallback) {
        String value = get(key);
        try {
            return value == null ? fallback : Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public Map<String, String> all() {
        Map<String, String> result = new LinkedHashMap<>();
        jdbc.query("SELECT key, value FROM settings ORDER BY key",
                rs -> {
                    result.put(rs.getString("key"), rs.getString("value"));
                });
        return result;
    }

    public String storeName() {
        return get("store.name", "Chegg");
    }

    public String symbol() {
        return get("currency.symbol", "KSh");
    }

    @Transactional(rollbackFor = Exception.class)
    public void save(Map<String, String> values) {
        sessions.require("SETTINGS_EDIT");
        long userId = sessions.requireUser();
        Map<String, String> before = all();

        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()) {
                continue;
            }
            jdbc.update("""
                    INSERT INTO settings(key, value) VALUES (?, ?)
                    ON CONFLICT(key) DO UPDATE SET value = excluded.value
                    """, entry.getKey().trim(), entry.getValue() == null ? "" : entry.getValue());
        }

        StringBuilder diff = new StringBuilder();
        values.forEach((key, value) -> diff.append(key)
                .append(": ")
                .append(before.getOrDefault(key, ""))
                .append(" -> ")
                .append(value)
                .append("; "));
        audit.record(userId, null, "SETTINGS_CHANGED", "SETTINGS", null,
                "Settings updated", diff.toString());
    }
}
