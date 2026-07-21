package net;

import java.awt.BorderLayout;
import java.awt.Frame;

import javax.swing.BorderFactory;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

/**
 * The alert shown right after Login/Register succeeds, before any board
 * ever opens: "Quick Play" vs "Room" (Create/Join/Cancel, matching the CTD
 * brief's own description of that dialog). Picking either one is
 * inherently asynchronous - the server may reply WAITING and only assign a
 * Seat once a real opponent shows up - so this shows a small modal "waiting"
 * dialog that {@link NetworkGameClient.LobbyListener} closes once a Seat
 * (or an error) actually arrives.
 */
public final class LobbyDialog {
    private LobbyDialog() {}

    /**
     * Drives the whole "pick a game" flow. Returns once the client has been
     * seated (see {@link NetworkGameClient#getAssignedSeat()}); throws if the
     * user cancels or the server refuses the request.
     */
    public static void chooseAndWait(NetworkGameClient client) {
        String[] options = {"Quick Play", "Room...", "Cancel"};
        int choice = JOptionPane.showOptionDialog(null,
                "Signed in - rating " + client.getRating() + ".\nHow do you want to play?",
                "Kung Fu Chess", JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null, options, options[0]);

        if (choice == 0) {
            runAndWaitForSeat(client, "Looking for an opponent (rating ±100)...", client::requestPlay);
            return;
        }
        if (choice != 1) {
            throw new IllegalStateException("cancelled");
        }

        String[] roomOptions = {"Create", "Join", "Cancel"};
        int roomChoice = JOptionPane.showOptionDialog(null, "Create a new room, or join one by its code?",
                "Kung Fu Chess - Room", JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null, roomOptions, roomOptions[0]);

        if (roomChoice == 0) {
            runAndWaitForSeat(client, "Creating room...", client::createRoom);
        } else if (roomChoice == 1) {
            String code = JOptionPane.showInputDialog(null, "Room code:", "Kung Fu Chess - Join Room", JOptionPane.PLAIN_MESSAGE);
            if (code == null || code.trim().isEmpty()) {
                throw new IllegalStateException("cancelled");
            }
            String trimmedCode = code.trim().toUpperCase();
            runAndWaitForSeat(client, "Joining room " + trimmedCode + "...", () -> client.joinRoom(trimmedCode));
        } else {
            throw new IllegalStateException("cancelled");
        }
    }

    private interface Request {
        void send();
    }

    private static void runAndWaitForSeat(NetworkGameClient client, String initialMessage, Request request) {
        JDialog dialog = new JDialog((Frame) null, "Kung Fu Chess", true);
        JLabel label = new JLabel(initialMessage);
        label.setBorder(BorderFactory.createEmptyBorder(20, 30, 20, 30));
        dialog.getContentPane().add(label, BorderLayout.CENTER);
        dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
        dialog.pack();
        dialog.setLocationRelativeTo(null);

        String[] errorHolder = new String[1];

        client.setLobbyListener(new NetworkGameClient.LobbyListener() {
            @Override public void onWaiting() {
                SwingUtilities.invokeLater(() -> label.setText("Waiting for an opponent..."));
            }

            @Override public void onRoomCreated(String roomCode) {
                SwingUtilities.invokeLater(() -> label.setText("Room " + roomCode + " - waiting for an opponent to join..."));
            }

            @Override public void onSeated(Seat seat) {
                SwingUtilities.invokeLater(dialog::dispose);
            }

            @Override public void onLobbyError(String message) {
                errorHolder[0] = message;
                SwingUtilities.invokeLater(dialog::dispose);
            }
        });

        request.send();
        dialog.setVisible(true); // blocks the calling thread until dispose()

        if (errorHolder[0] != null) {
            throw new IllegalStateException("Refused: " + errorHolder[0]);
        }
    }
}
