package com.chegg.pos.ui;

import com.chegg.pos.app.AppProperties;
import com.chegg.pos.app.FontLoader;
import com.chegg.pos.app.Theme;
import com.chegg.pos.model.User;
import com.chegg.pos.service.AuthService;
import com.chegg.pos.service.LoginResult;
import com.chegg.pos.ui.common.RoundedPanel;
import com.chegg.pos.ui.common.UiAssets;

import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JSeparator;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.concurrent.ExecutorService;

/** Split wine/cream sign-in screen. All database work happens off the EDT. */
@org.springframework.stereotype.Component
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class LoginFrame extends JFrame {

    private final AuthService authService;
    private final UiNavigator navigator;
    private final ExecutorService executor;
    private final AppProperties appProperties;

    private final JTextField usernameField = new JTextField();
    private final JPasswordField passwordField = new JPasswordField();
    private final JLabel errorLabel = new JLabel(" ");
    private final JButton loginButton = new JButton("Sign in");
    private final JButton kioskButton = new JButton("Start customer kiosk");

    private boolean busy;

    public LoginFrame(AuthService authService,
                      UiNavigator navigator,
                      ExecutorService dbExecutor,
                      AppProperties appProperties) {
        super("Chegg POS - Sign in");
        this.authService = authService;
        this.navigator = navigator;
        this.executor = dbExecutor;
        this.appProperties = appProperties;

        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(980, 620));
        setSize(1080, 700);
        setContentPane(buildContent());
        getRootPane().setDefaultButton(loginButton);
        setLocationRelativeTo(null);

        SwingUtilities.invokeLater(() -> {
            usernameField.requestFocusInWindow();
            showError(" ");
        });
    }

    private JPanel buildContent() {
        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(Theme.SURFACE);
        root.add(buildBrandPanel(), BorderLayout.WEST);
        root.add(buildFormPanel(), BorderLayout.CENTER);
        return root;
    }

    // ------------------------------------------------------------- brand side

    private JPanel buildBrandPanel() {
        JPanel brand = new JPanel(new BorderLayout());
        brand.setBackground(Theme.PRIMARY);
        brand.setPreferredSize(new Dimension(445, 0));
        brand.setBorder(BorderFactory.createEmptyBorder(42, 44, 32, 44));

        JPanel column = new JPanel();
        column.setOpaque(false);
        column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));

        RoundedPanel logoTile = new RoundedPanel(new BorderLayout(), Theme.SURFACE_LOGO, 14);
        logoTile.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));
        logoTile.setMaximumSize(new Dimension(290, 190));
        logoTile.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel logo = new JLabel(UiAssets.logoIcon(262, 166), SwingConstants.CENTER);
        logoTile.add(logo, BorderLayout.CENTER);
        column.add(logoTile);

        column.add(Box.createVerticalStrut(30));

        JLabel title = new JLabel("Chegg POS");
        title.setFont(FontLoader.bold(34f));
        title.setForeground(Theme.ON_PRIMARY);
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        column.add(title);

        JLabel tagline = new JLabel("This project was hard");
        tagline.setFont(FontLoader.regular(14f));
        tagline.setForeground(cream(0.88f));
        tagline.setAlignmentX(Component.LEFT_ALIGNMENT);
        column.add(Box.createVerticalStrut(8));
        column.add(tagline);

        column.add(Box.createVerticalStrut(34));

        JLabel workspacesLabel = new JLabel("FOUR WORKSPACES, ONE TILL");
        workspacesLabel.setFont(FontLoader.semiBold(10.5f));
        workspacesLabel.setForeground(cream(0.65f));
        workspacesLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        column.add(workspacesLabel);
        column.add(Box.createVerticalStrut(12));

        column.add(workspaceRow("System Admin", "Control Room"));
        column.add(workspaceRow("Store Admin", "Operations"));
        column.add(workspaceRow("Cashier", "Terminal"));
        column.add(workspaceRow("Customer", "Self-checkout kiosk"));

        JPanel footer = new JPanel(new BorderLayout());
        footer.setOpaque(false);
        JLabel version = new JLabel("v" + appProperties.version() + "  ·  KES  ·  No VAT");
        version.setFont(FontLoader.regular(11f));
        version.setForeground(cream(0.62f));
        footer.add(version, BorderLayout.WEST);
        brand.add(column, BorderLayout.NORTH);
        brand.add(footer, BorderLayout.SOUTH);
        return brand;
    }

    private JPanel workspaceRow(String role, String workspace) {
        JPanel row = new JPanel(new BorderLayout(10, 0));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));

        JLabel dot = new JLabel("●");
        dot.setFont(FontLoader.regular(9f));
        dot.setForeground(Theme.ACCENT_GOLD);
        dot.setPreferredSize(new Dimension(14, 20));
        row.add(dot, BorderLayout.WEST);

        JLabel label = new JLabel(role + "  —  " + workspace);
        label.setFont(FontLoader.regular(12.5f));
        label.setForeground(cream(0.86f));
        row.add(label, BorderLayout.CENTER);
        return row;
    }

    private static java.awt.Color cream(float alpha) {
        return new java.awt.Color(239, 223, 187, Math.round(alpha * 255));
    }

    // -------------------------------------------------------------- form side

    private JPanel buildFormPanel() {
        JPanel form = new JPanel(new GridBagLayout());
        form.setBackground(Theme.SURFACE);
        form.setBorder(BorderFactory.createEmptyBorder(32, 32, 32, 32));

        RoundedPanel card = new RoundedPanel(new BorderLayout(), Theme.SURFACE_CARD, 16);
        card.setBorder(BorderFactory.createEmptyBorder(30, 34, 28, 34));
        card.setPreferredSize(new Dimension(420, 510));

        JPanel body = new JPanel();
        body.setOpaque(false);
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));

        JLabel heading = new JLabel("Welcome back");
        heading.setFont(FontLoader.semiBold(26f));
        heading.setForeground(Theme.TEXT);
        heading.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel subtitle = new JLabel("Sign in to open your workspace.");
        subtitle.setFont(FontLoader.regular(13f));
        subtitle.setForeground(Theme.TEXT_MUTED);
        subtitle.setAlignmentX(Component.LEFT_ALIGNMENT);

        body.add(heading);
        body.add(Box.createVerticalStrut(6));
        body.add(subtitle);
        body.add(Box.createVerticalStrut(26));

        body.add(fieldLabel("Username"));
        body.add(Box.createVerticalStrut(6));
        configureTextField(usernameField);
        body.add(usernameField);
        body.add(Box.createVerticalStrut(16));

        body.add(fieldLabel("Password"));
        body.add(Box.createVerticalStrut(6));
        configureTextField(passwordField);
        body.add(passwordField);
        body.add(Box.createVerticalStrut(10));

        errorLabel.setFont(FontLoader.regular(12f));
        errorLabel.setForeground(Theme.DANGER);
        errorLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        errorLabel.setPreferredSize(new Dimension(350, 34));
        errorLabel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
        errorLabel.setVerticalAlignment(SwingConstants.TOP);
        body.add(errorLabel);
        body.add(Box.createVerticalStrut(8));

        loginButton.setFont(FontLoader.semiBold(14f));
        loginButton.setBackground(Theme.PRIMARY);
        loginButton.setForeground(Theme.ON_PRIMARY);
        loginButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        loginButton.setAlignmentX(Component.LEFT_ALIGNMENT);
        loginButton.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));
        loginButton.setPreferredSize(new Dimension(350, 44));
        loginButton.addActionListener(e -> submit());
        body.add(loginButton);
        body.add(Box.createVerticalStrut(18));

        JSeparator separator = new JSeparator();
        separator.setForeground(Theme.BORDER);
        separator.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
        separator.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(separator);
        body.add(Box.createVerticalStrut(18));

        kioskButton.setFont(FontLoader.medium(13f));
        kioskButton.setEnabled(true);
        kioskButton.setToolTipText("Start the customer self-checkout kiosk on this terminal.");
        kioskButton.setAlignmentX(Component.LEFT_ALIGNMENT);
        kioskButton.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        kioskButton.setPreferredSize(new Dimension(350, 40));
        kioskButton.addActionListener(e -> startKiosk());
        body.add(kioskButton);

        body.add(Box.createVerticalStrut(18));
        JLabel hint = new JLabel("<html>Staff accounts start with a temporary password "
                + "and are asked to replace it on first sign-in.</html>");
        hint.setFont(FontLoader.regular(10.5f));
        hint.setForeground(Theme.TEXT_MUTED);
        hint.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(hint);

        card.add(body, BorderLayout.CENTER);

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.anchor = GridBagConstraints.CENTER;
        gbc.insets = new Insets(0, 0, 0, 0);
        form.add(card, gbc);
        return form;
    }

    private JLabel fieldLabel(String text) {
        JLabel label = new JLabel(text);
        label.setFont(FontLoader.medium(12f));
        label.setForeground(Theme.TEXT_MUTED);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        return label;
    }

    private void configureTextField(JTextField field) {
        field.setFont(FontLoader.regular(14f));
        field.setPreferredSize(new Dimension(350, 40));
        field.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        field.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.BORDER),
                BorderFactory.createEmptyBorder(4, 10, 4, 10)));
    }

    // ------------------------------------------------------------------ auth

    private void submit() {
        if (busy) {
            return;
        }

        String username = usernameField.getText() == null ? "" : usernameField.getText().trim();
        char[] passwordChars = passwordField.getPassword();
        String password = new String(passwordChars);
        java.util.Arrays.fill(passwordChars, '\0');

        if (username.isEmpty() || password.isEmpty()) {
            showError("Enter your username and password.");
            return;
        }

        setBusy(true);
        showError(" ");

        executor.submit(() -> {
            LoginResult result;
            try {
                result = authService.login(username, password);
            } catch (Exception ex) {
                result = LoginResult.failure("Sign-in failed. Please try again.");
            }
            LoginResult finalResult = result;
            SwingUtilities.invokeLater(() -> handleResult(finalResult));
        });
    }

    private void startKiosk() {
        if (busy) {
            return;
        }
        setBusy(true);
        showError(" ");
        executor.submit(() -> {
            LoginResult result;
            try {
                result = authService.deviceLogin("kiosk");
            } catch (Exception ex) {
                result = LoginResult.failure("The customer kiosk could not start. Please try again.");
            }
            LoginResult finalResult = result;
            SwingUtilities.invokeLater(() -> {
                if (!finalResult.success()) {
                    setBusy(false);
                    showError(finalResult.message());
                    return;
                }
                navigator.showHome(finalResult.user());
            });
        });
    }

    private void handleResult(LoginResult result) {
        if (!result.success()) {
            setBusy(false);
            showError(result.message());
            passwordField.selectAll();
            passwordField.requestFocusInWindow();
            return;
        }

        User user = result.user();
        if (user.mustChangePassword()) {
            ChangePasswordDialog dialog = new ChangePasswordDialog(
                    this, authService, executor, user, () -> navigator.showHome(user));
            dialog.setVisible(true);
            if (!dialog.isCompleted()) {
                setBusy(false);
                showError("A new password is required before you can continue.");
            }
            return;
        }

        navigator.showHome(user);
    }

    private void setBusy(boolean value) {
        busy = value;
        usernameField.setEnabled(!value);
        passwordField.setEnabled(!value);
        kioskButton.setEnabled(!value);
        loginButton.setEnabled(!value);
        loginButton.setText(value ? "Signing in..." : "Sign in");
        setCursor(value
                ? Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR)
                : Cursor.getDefaultCursor());
    }

    private void showError(String message) {
        String text = message == null || message.isBlank() ? " " : message;
        errorLabel.setText("<html><div style='width:340px'>" + escape(text) + "</div></html>");
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
