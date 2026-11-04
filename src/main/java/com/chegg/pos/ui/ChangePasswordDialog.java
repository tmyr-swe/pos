package com.chegg.pos.ui;

import com.chegg.pos.app.FontLoader;
import com.chegg.pos.app.Theme;
import com.chegg.pos.model.User;
import com.chegg.pos.service.AuthService;
import com.chegg.pos.ui.common.RoundedPanel;
import com.chegg.pos.ui.common.UiAssets;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.concurrent.ExecutorService;

/** Forced first-login password change. The seeded accounts all start with change-me. */
public class ChangePasswordDialog extends JDialog {

    private final AuthService authService;
    private final ExecutorService executor;
    private final User user;
    private final Runnable onSuccess;

    private final JPasswordField newPasswordField = new JPasswordField();
    private final JPasswordField confirmPasswordField = new JPasswordField();
    private final JLabel errorLabel = new JLabel(" ");
    private final JButton saveButton = new JButton("Save new password");
    private final JButton cancelButton = new JButton("Cancel");

    private volatile boolean completed;

    public ChangePasswordDialog(Frame owner,
                                AuthService authService,
                                ExecutorService executor,
                                User user,
                                Runnable onSuccess) {
        super(owner, "Choose a new password", true);
        this.authService = authService;
        this.executor = executor;
        this.user = user;
        this.onSuccess = onSuccess;

        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setContentPane(buildContent());
        setSize(460, 360);
        setResizable(false);
        setLocationRelativeTo(owner);
        getRootPane().setDefaultButton(saveButton);
        SwingUtilities.invokeLater(newPasswordField::requestFocusInWindow);
    }

    public boolean isCompleted() {
        return completed;
    }

    private JPanel buildContent() {
        JPanel surface = new JPanel(new BorderLayout());
        surface.setBackground(Theme.SURFACE);
        surface.setBorder(BorderFactory.createEmptyBorder(24, 24, 24, 24));

        RoundedPanel card = new RoundedPanel(new BorderLayout(), Theme.SURFACE_CARD, 12);
        card.setBorder(BorderFactory.createEmptyBorder(24, 26, 24, 26));

        JPanel body = new JPanel();
        body.setOpaque(false);
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));

        JLabel heading = new JLabel("Set a new password");
        heading.setFont(FontLoader.semiBold(22f));
        heading.setForeground(Theme.TEXT);
        heading.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(heading);

        JLabel subtitle = new JLabel("<html>For security, the temporary password must be replaced before "
                + user.firstName() + " can use the till.</html>");
        subtitle.setFont(FontLoader.regular(12.5f));
        subtitle.setForeground(Theme.TEXT_MUTED);
        subtitle.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(Box.createVerticalStrut(6));
        body.add(subtitle);
        body.add(Box.createVerticalStrut(18));

        body.add(fieldLabel("New password (at least 8 characters)"));
        body.add(Box.createVerticalStrut(6));
        configurePasswordField(newPasswordField);
        newPasswordField.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(newPasswordField);
        body.add(Box.createVerticalStrut(12));

        body.add(fieldLabel("Confirm new password"));
        body.add(Box.createVerticalStrut(6));
        configurePasswordField(confirmPasswordField);
        confirmPasswordField.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(confirmPasswordField);
        body.add(Box.createVerticalStrut(8));

        errorLabel.setFont(FontLoader.regular(12f));
        errorLabel.setForeground(Theme.DANGER);
        errorLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(errorLabel);
        body.add(Box.createVerticalStrut(12));

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.setOpaque(false);
        actions.setAlignmentX(Component.LEFT_ALIGNMENT);

        cancelButton.setFont(FontLoader.medium(13f));
        cancelButton.addActionListener(e -> dispose());

        saveButton.setFont(FontLoader.semiBold(13f));
        saveButton.setBackground(Theme.PRIMARY);
        saveButton.setForeground(Theme.ON_PRIMARY);
        saveButton.setPreferredSize(new Dimension(190, 38));
        saveButton.addActionListener(e -> save());

        actions.add(cancelButton);
        actions.add(saveButton);
        body.add(actions);

        card.add(body, BorderLayout.CENTER);
        surface.add(card, BorderLayout.CENTER);
        return surface;
    }

    private void configurePasswordField(JPasswordField field) {
        field.setFont(FontLoader.regular(14f));
        field.setPreferredSize(new Dimension(360, 38));
        field.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));
        field.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.BORDER),
                BorderFactory.createEmptyBorder(4, 10, 4, 10)));
    }

    private JLabel fieldLabel(String text) {
        JLabel label = new JLabel(text);
        label.setFont(FontLoader.medium(12f));
        label.setForeground(Theme.TEXT_MUTED);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        return label;
    }

    private void save() {
        char[] first = newPasswordField.getPassword();
        char[] second = confirmPasswordField.getPassword();
        String password = new String(first);
        String confirmation = new String(second);

        if (password.length() < 8) {
            showError("Use at least 8 characters.");
            return;
        }
        if (!password.equals(confirmation)) {
            showError("The two passwords do not match.");
            return;
        }

        setBusy(true);
        errorLabel.setText(" ");

        executor.submit(() -> {
            try {
                authService.changePassword(user.id(), password);
                SwingUtilities.invokeLater(() -> {
                    completed = true;
                    dispose();
                    onSuccess.run();
                });
            } catch (Exception ex) {
                String message = ex.getMessage() == null ? "Could not save the password." : ex.getMessage();
                SwingUtilities.invokeLater(() -> {
                    setBusy(false);
                    showError(message);
                });
            }
        });
    }

    private void setBusy(boolean busy) {
        newPasswordField.setEnabled(!busy);
        confirmPasswordField.setEnabled(!busy);
        cancelButton.setEnabled(!busy);
        saveButton.setEnabled(!busy);
        saveButton.setText(busy ? "Saving..." : "Save new password");
    }

    private void showError(String message) {
        errorLabel.setText("<html>" + message + "</html>");
    }
}
