package com.chegg.pos.service;

import com.chegg.pos.app.AppPaths;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** Copies the SQLite database to the backups folder and applies retention. */
@Service
public class BackupService {

    private static final DateTimeFormatter STAMP = DateTimeFormatter
            .ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneId.of("Africa/Nairobi"));

    private final JdbcTemplate jdbc;
    private final SessionService sessions;
    private final AuditService audit;
    private final SettingsService settings;

    public BackupService(JdbcTemplate jdbc, SessionService sessions,
                         AuditService audit, SettingsService settings) {
        this.jdbc = jdbc;
        this.sessions = sessions;
        this.audit = audit;
        this.settings = settings;
    }

    public Path runBackup() throws IOException {
        sessions.require("BACKUP_RUN");
        long userId = sessions.requireUser();
        AppPaths.ensureDirectories();

        jdbc.execute("PRAGMA wal_checkpoint(FULL)");
        Path target = AppPaths.backups().resolve("chegg-pos-" + STAMP.format(Instant.now()) + ".db");
        Files.copy(AppPaths.db(), target);

        int retainedDays = Math.max(1, settings.getInt("backup.retentionDays", 14));
        Instant cutoff = Instant.now().minusSeconds(retainedDays * 86_400L);
        try (Stream<Path> files = Files.list(AppPaths.backups())) {
            List<Path> old = files.filter(path -> path.getFileName().toString().endsWith(".db"))
                    .filter(path -> {
                        try {
                            return Files.getLastModifiedTime(path).toInstant().isBefore(cutoff);
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .sorted(Comparator.naturalOrder())
                    .toList();
            for (Path path : old) {
                Files.deleteIfExists(path);
            }
        }

        audit.record(userId, null, "BACKUP_RUN", "SYSTEM", null,
                "Database backup created", target.toString());
        return target;
    }

    public Path latestBackup() {
        try (Stream<Path> files = Files.list(AppPaths.backups())) {
            return files.filter(path -> path.getFileName().toString().endsWith(".db"))
                    .max(Comparator.comparing(path -> path.getFileName().toString()))
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }
}
