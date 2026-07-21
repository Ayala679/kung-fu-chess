package net;

import java.awt.GridLayout;

import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;

/**
 * The "alert before the design window opens" the CTD brief's Home screen
 * calls for: a small modal dialog collecting a server address, username and
 * password, with Login/Register buttons - shown once, before
 * view.BoardWindow itself ever opens. Deliberately not part of the board UI.
 */
public final class LoginDialog {
    public enum Mode { LOGIN, REGISTER }

    public static final class Result {
        public final Mode mode;
        public final String serverAddress;
        public final String username;
        public final String password;

        Result(Mode mode, String serverAddress, String username, String password) {
            this.mode = mode;
            this.serverAddress = serverAddress;
            this.username = username;
            this.password = password;
        }
    }

    private LoginDialog() {}

    /** Shows the dialog; returns null if the user cancelled or closed it. */
    public static Result show(String defaultServerAddress) {
        JTextField addressField = new JTextField(defaultServerAddress);
        JTextField usernameField = new JTextField();
        JPasswordField passwordField = new JPasswordField();

        JPanel panel = new JPanel(new GridLayout(0, 1, 4, 4));
        panel.add(new JLabel("Server address:"));
        panel.add(addressField);
        panel.add(new JLabel("Username:"));
        panel.add(usernameField);
        panel.add(new JLabel("Password:"));
        panel.add(passwordField);

        String[] options = {"Login", "Register", "Cancel"};
        int choice = JOptionPane.showOptionDialog(null, panel, "Kung Fu Chess - Sign in",
                JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null, options, options[0]);

        if (choice != 0 && choice != 1) return null; // Cancel, or the dialog was closed

        String address = addressField.getText().trim();
        String resolvedAddress = address.isEmpty() ? defaultServerAddress : address;
        String username = usernameField.getText().trim();
        String password = new String(passwordField.getPassword());

        if (username.isEmpty() || password.isEmpty()) {
            showError("Username and password are both required.");
            return show(resolvedAddress); // try again
        }

        Mode mode = choice == 1 ? Mode.REGISTER : Mode.LOGIN;
        return new Result(mode, resolvedAddress, username, password);
    }

    public static void showError(String message) {
        JOptionPane.showMessageDialog(null, message, "Kung Fu Chess", JOptionPane.ERROR_MESSAGE);
    }
}
