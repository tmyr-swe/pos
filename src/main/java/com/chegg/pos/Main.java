package com.chegg.pos;

import com.chegg.pos.app.AppConfig;
import com.chegg.pos.app.AppPaths;
import com.chegg.pos.app.Theme;
import com.chegg.pos.service.AuthService;
import com.chegg.pos.service.LoginResult;
import com.chegg.pos.ui.AppShell;
import com.chegg.pos.ui.UiNavigator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Application entry point. Spring Framework is started without Spring Boot:
 * one AnnotationConfigApplicationContext is the root of the whole process.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        AppPaths.ensureDirectories();
        // Must be set before Logback initialises its file appender.
        System.setProperty("pos.logs", AppPaths.logs().toString());

        Logger log = LoggerFactory.getLogger(Main.class);
        boolean smokeTest = Arrays.asList(args).contains("--smoke-test");
        boolean uiSmokeTest = Arrays.asList(args).contains("--ui-smoke-test");

        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        try {
            context.register(AppConfig.class);
            context.refresh();

            if (smokeTest) {
                runSmokeTest(context);
                context.close();
                return;
            }
            if (uiSmokeTest) {
                runUiSmokeTest(context);
                // Swing timers and the EDT keep the JVM alive; the smoke run is done.
                System.exit(0);
                return;
            }

            context.registerShutdownHook();
            installUncaughtExceptionHandler(log);

            if (GraphicsEnvironment.isHeadless()) {
                log.error("No graphical display is available; start Chegg POS on a desktop session");
                context.close();
                System.exit(1);
                return;
            }

            SwingUtilities.invokeLater(() -> {
                try {
                    Theme.install();
                    context.getBean(UiNavigator.class).showLogin();
                } catch (Throwable error) {
                    log.error("Could not open the sign-in screen", error);
                    showFatalError(error);
                }
            });
        } catch (Throwable error) {
            log.error("Chegg POS failed to start", error);
            try {
                context.close();
            } catch (Exception ignored) {
                // The original failure is the useful one.
            }
            if (!smokeTest && !uiSmokeTest && !GraphicsEnvironment.isHeadless()) {
                showFatalError(error);
            }
            System.exit(1);
        }
    }

    private static void runSmokeTest(AnnotationConfigApplicationContext context) {
        JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);

        int products = count(jdbc, "SELECT COUNT(*) FROM products");
        int categories = count(jdbc, "SELECT COUNT(*) FROM categories");
        int users = count(jdbc, "SELECT COUNT(*) FROM users");
        int roles = count(jdbc, "SELECT COUNT(*) FROM roles");
        int permissions = count(jdbc, "SELECT COUNT(*) FROM permissions");
        int settings = count(jdbc, "SELECT COUNT(*) FROM settings");
        Integer schema = jdbc.queryForObject("SELECT MAX(version) FROM schema_version", Integer.class);

        System.out.printf(
                "SMOKE OK database=%s schema=%s products=%d categories=%d users=%d roles=%d permissions=%d settings=%d%n",
                AppPaths.db(), schema, products, categories, users, roles, permissions, settings);
    }

    /**
     * Constructs every role workspace once and disposes it. This catches
     * layout, theme and bean-wiring failures without a human walking the UI.
     * Requires a display; run it from a desktop session or an X server.
     */
    private static void runUiSmokeTest(AnnotationConfigApplicationContext context) throws Exception {
        if (GraphicsEnvironment.isHeadless()) {
            throw new IllegalStateException("UI smoke test needs a graphical display.");
        }

        AtomicReference<Throwable> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                Theme.install();
                AuthService auth = context.getBean(AuthService.class);
                UiNavigator navigator = context.getBean(UiNavigator.class);

                for (String username : List.of("sysadmin", "storeadmin", "cashier1")) {
                    LoginResult login = auth.login(username, "change-me");
                    if (!login.success()) {
                        throw new IllegalStateException(username + ": " + login.message());
                    }
                    AppShell shell = new AppShell(context, login.user(), navigator);
                    shell.dispose();
                    auth.logout();
                }

                LoginResult kiosk = auth.deviceLogin("kiosk");
                if (!kiosk.success()) {
                    throw new IllegalStateException("kiosk: " + kiosk.message());
                }
                AppShell kioskShell = new AppShell(context, kiosk.user(), navigator);
                kioskShell.dispose();
                auth.logout();

                System.out.println("UI SMOKE OK workspaces=admin,store,cashier,kiosk");
            } catch (Throwable error) {
                failure.set(error);
            }
        });

        if (failure.get() != null) {
            throw new IllegalStateException("UI smoke test failed", failure.get());
        }
    }

    private static int count(JdbcTemplate jdbc, String sql) {
        Integer value = jdbc.queryForObject(sql, Integer.class);
        return value == null ? 0 : value;
    }

    private static void installUncaughtExceptionHandler(Logger log) {
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            log.error("Uncaught exception on thread {}", thread.getName(), error);
            if (!GraphicsEnvironment.isHeadless()) {
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(
                        null,
                        "An unexpected error occurred. The details were written to the log.\n\n"
                                + error.getClass().getSimpleName()
                                + (error.getMessage() == null ? "" : ": " + error.getMessage()),
                        "Chegg POS",
                        JOptionPane.ERROR_MESSAGE));
            }
        });
    }

    private static void showFatalError(Throwable error) {
        JOptionPane.showMessageDialog(
                null,
                "Chegg POS could not start.\n\n"
                        + error.getClass().getSimpleName()
                        + (error.getMessage() == null ? "" : ": " + error.getMessage())
                        + "\n\nSee the log file in the application data folder for details.",
                "Chegg POS",
                JOptionPane.ERROR_MESSAGE);
    }
}
