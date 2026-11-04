package com.chegg.pos.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

/**
 * Per-OS writable locations. Installed applications must never write beside
 * the binary (Program Files / /usr are read-only for normal users).
 *
 * Override everything with -Dpos.home=/some/path (used by tests and portable runs).
 */
public final class AppPaths {

    private AppPaths() {
    }

    public static Path home() {
        String override = System.getProperty("pos.home");
        if (override != null && !override.isBlank()) {
            return Paths.get(override).toAbsolutePath().normalize();
        }

        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            String localAppData = System.getenv("LOCALAPPDATA");
            if (localAppData != null && !localAppData.isBlank()) {
                return Paths.get(localAppData, "CheggPOS").toAbsolutePath().normalize();
            }
            return Paths.get(System.getProperty("user.home"), "AppData", "Local", "CheggPOS")
                    .toAbsolutePath().normalize();
        }

        String xdgDataHome = System.getenv("XDG_DATA_HOME");
        if (xdgDataHome != null && !xdgDataHome.isBlank()) {
            return Paths.get(xdgDataHome, "chegg-pos").toAbsolutePath().normalize();
        }
        return Paths.get(System.getProperty("user.home"), ".local", "share", "chegg-pos")
                .toAbsolutePath().normalize();
    }

    public static Path db() {
        return home().resolve("pos.db");
    }

    public static Path receipts() {
        return home().resolve("receipts");
    }

    public static Path backups() {
        return home().resolve("backups");
    }

    public static Path logs() {
        String override = System.getProperty("pos.logs");
        if (override != null && !override.isBlank()) {
            return Paths.get(override).toAbsolutePath().normalize();
        }
        return home().resolve("logs");
    }

    /** Creates every directory the application needs. Safe to call repeatedly. */
    public static void ensureDirectories() {
        for (Path dir : new Path[]{home(), receipts(), backups(), logs()}) {
            try {
                Files.createDirectories(dir);
            } catch (IOException e) {
                throw new IllegalStateException("Cannot create application directory: " + dir, e);
            }
        }
    }
}
