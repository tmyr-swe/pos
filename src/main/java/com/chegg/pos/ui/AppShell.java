package com.chegg.pos.ui;

import com.chegg.pos.app.Theme;
import com.chegg.pos.model.User;
import com.chegg.pos.service.AuthService;
import com.chegg.pos.ui.workspace.admin.AdminWorkspace;
import com.chegg.pos.ui.workspace.cashier.CashierWorkspace;
import com.chegg.pos.ui.workspace.kiosk.KioskWorkspace;

import org.springframework.context.ApplicationContext;

import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.WindowConstants;
import java.awt.Dimension;
import java.awt.GraphicsEnvironment;

/**
 * The single window that hosts one of the four role-specific workspaces.
 * Navigation by role is the only routing in the application.
 */
public class AppShell extends JFrame {

    public AppShell(ApplicationContext context, User user, UiNavigator navigator) {
        super("Chegg POS - " + user.fullName());
        boolean kiosk = "CUSTOMER".equals(user.roleCode());

        Runnable signOut = () -> {
            context.getBean(AuthService.class).logout();
            navigator.showLogin();
        };

        JComponent workspace;
        if (kiosk) {
            workspace = new KioskWorkspace(context, user, this, signOut).root();
        } else if ("CASHIER".equals(user.roleCode())) {
            workspace = new CashierWorkspace(context, user, this, signOut).root();
        } else {
            workspace = new AdminWorkspace(context, user, this, signOut).root();
        }

        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        setContentPane(workspace);
        getContentPane().setBackground(Theme.SURFACE);

        if (kiosk) {
            setUndecorated(true);
            setSize(GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getMaximumWindowBounds().getSize());
            setLocation(0, 0);
            setExtendedState(MAXIMIZED_BOTH);
        } else {
            setMinimumSize(new Dimension(1180, 760));
            setSize("CASHIER".equals(user.roleCode())
                    ? new Dimension(1440, 900) : new Dimension(1360, 860));
            setLocationRelativeTo(null);
        }
    }
}
