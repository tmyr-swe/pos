package com.chegg.pos.ui;

import com.chegg.pos.model.User;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import javax.swing.JFrame;

/**
 * Owns the single active window. A new window is made visible before the old
 * one is disposed so the desktop never flashes an empty screen.
 */
@Component
public class UiNavigator {

    private final ApplicationContext context;
    private JFrame current;

    public UiNavigator(ApplicationContext context) {
        this.context = context;
    }

    public void showLogin() {
        LoginFrame frame = context.getBean(LoginFrame.class);
        swap(frame);
    }

    /** Routes each role to its workspace inside the shared application shell. */
    public void showHome(User user) {
        swap(new AppShell(context, user, this));
    }

    private void swap(JFrame frame) {
        JFrame previous = current;
        current = frame;
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
        if (previous != null && previous != frame) {
            previous.dispose();
        }
    }
}
