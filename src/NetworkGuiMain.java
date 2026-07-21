import java.awt.Dimension;
import java.awt.Font;
import java.awt.Frame;
import java.net.URI;

import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.SwingConstants;
import javax.swing.Timer;

import net.LobbyDialog;
import net.LoginDialog;
import net.NetworkGameClient;
import view.BoardWindow;
import view.ImgRenderer;

/**
 * Networked entry point: Login/Register alert, then a lobby alert (Quick
 * Play or create/join a Room), then a short 3-2-1 countdown, and only then
 * the same interactive BoardWindow GuiMain uses for offline play - wired to
 * a NetworkGameClient instead of a local EventEngine. Nothing here is part
 * of the board UI itself.
 */
public class NetworkGuiMain {
    private static final String DEFAULT_ADDRESS = "localhost:8887";

    public static void main(String[] args) throws Exception {
        NetworkGameClient client = null;
        String lastAddress = DEFAULT_ADDRESS;

        while (client == null) {
            LoginDialog.Result login = LoginDialog.show(lastAddress);
            if (login == null) {
                return; // user cancelled
            }
            lastAddress = login.serverAddress;

            try {
                NetworkGameClient candidate = new NetworkGameClient(new URI("ws://" + login.serverAddress));
                if (login.mode == LoginDialog.Mode.REGISTER) {
                    candidate.register(login.username, login.password, 10);
                } else {
                    candidate.login(login.username, login.password, 10);
                }
                client = candidate;
            } catch (Exception e) {
                LoginDialog.showError(e.getMessage());
            }
        }

        System.out.println("Authenticated (rating " + client.getRating() + ")");

        while (true) {
            try {
                LobbyDialog.chooseAndWait(client);
                break;
            } catch (IllegalStateException e) {
                if ("cancelled".equals(e.getMessage())) {
                    return;
                }
                LoginDialog.showError(e.getMessage());
                // loop back to the Quick Play / Room choice
            }
        }

        client.awaitFirstSnapshot(10);
        System.out.println("Seated as " + client.getAssignedSeat()
                + (client.getRoomCode() != null ? " (room " + client.getRoomCode() + ")" : ""));

        showCountdown();

        ImgRenderer renderer = new ImgRenderer("resources/board.png");
        new BoardWindow(client, renderer).show(client.getRoomCode());
    }

    /** Purely cosmetic, client-side-only pre-game countdown - no protocol/server involvement. */
    private static void showCountdown() {
        JDialog dialog = new JDialog((Frame) null, "Get ready", true);
        JLabel label = new JLabel("3", SwingConstants.CENTER);
        label.setFont(label.getFont().deriveFont(Font.BOLD, 48f));
        label.setPreferredSize(new Dimension(160, 160));
        dialog.getContentPane().add(label);
        dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
        dialog.pack();
        dialog.setLocationRelativeTo(null);

        int[] remaining = {3};
        Timer timer = new Timer(1000, null);
        timer.addActionListener(e -> {
            remaining[0]--;
            if (remaining[0] <= 0) {
                timer.stop();
                dialog.dispose();
            } else {
                label.setText(String.valueOf(remaining[0]));
            }
        });
        timer.start();
        dialog.setVisible(true); // blocks until dispose()
    }
}
