package net;

import java.net.URI;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import event.GameClient;
import logging.ActivityLog;
import snapshot.GameSnapshot;

/**
 * Client-side counterpart to server.KungFuChessServer/Lobby/GameSession.
 * Two stages: authenticate (login/register), then pick a game (quick-match
 * "play", or create/join a room) - the latter is inherently asynchronous
 * (the server may reply WAITING and only send a SEAT once an opponent
 * actually arrives), so it's callback-driven via {@link LobbyListener}
 * rather than a single blocking call. Once seated, this turns
 * view.BoardWindow's clicks/jumps into text frames and exposes whatever
 * GameSnapshot the server most recently pushed. waitFor() is a no-op: the
 * server is the only real clock.
 */
public class NetworkGameClient extends WebSocketClient implements GameClient {
    /** Callbacks for what happens after authentication, while picking a game - always delivered off the Swing thread. */
    public interface LobbyListener {
        void onWaiting();
        void onRoomCreated(String roomCode);
        void onSeated(Seat seat);
        void onLobbyError(String message);
    }

    private final CountDownLatch authComplete = new CountDownLatch(1);
    private final CountDownLatch firstSnapshot = new CountDownLatch(1);
    private volatile int rating;
    private volatile String serverError;
    private volatile Seat assignedSeat;
    private volatile String roomCode;
    private volatile GameSnapshot latestSnapshot;
    private volatile LobbyListener lobbyListener;
    private volatile ActivityLog activityLog;

    public NetworkGameClient(URI serverUri) {
        super(serverUri);
    }

    /** Connects and creates a new account. Blocks until AUTH_OK or a refusal. */
    public void register(String username, String password, long timeoutSeconds) throws Exception {
        authenticate(Protocol.REGISTER, username, password, timeoutSeconds);
    }

    /** Connects and signs into an existing account. Blocks until AUTH_OK or a refusal. */
    public void login(String username, String password, long timeoutSeconds) throws Exception {
        authenticate(Protocol.LOGIN, username, password, timeoutSeconds);
    }

    private void authenticate(String mode, String username, String password, long timeoutSeconds) throws Exception {
        activityLog = new ActivityLog("logs/client-" + username + ".log");
        activityLog.log("connecting to " + getURI());
        if (!connectBlocking(timeoutSeconds, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Could not connect to " + getURI());
        }
        activityLog.log(mode + " " + username);
        send(mode + " " + username + " " + password);
        if (!authComplete.await(timeoutSeconds, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Server did not respond to authentication in time");
        }
        if (serverError != null) {
            throw new IllegalStateException("Refused: " + serverError);
        }
    }

    /** Set before requestPlay()/createRoom()/joinRoom() - notified of WAITING/ROOM_CREATED/SEAT/errors. */
    public void setLobbyListener(LobbyListener listener) {
        this.lobbyListener = listener;
    }

    public void requestPlay() {
        activityLog.log("play (quick-match)");
        send(Protocol.PLAY);
    }

    public void createRoom() {
        activityLog.log("create_room");
        send(Protocol.CREATE_ROOM);
    }

    public void joinRoom(String roomCode) {
        activityLog.log("join_room " + roomCode);
        send(Protocol.JOIN_ROOM + " " + roomCode);
    }

    /** Blocks until the first board snapshot arrives - call after {@link LobbyListener#onSeated}. */
    public void awaitFirstSnapshot(long timeoutSeconds) throws InterruptedException {
        if (!firstSnapshot.await(timeoutSeconds, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Server did not send an initial board state");
        }
    }

    public int getRating() {
        return rating;
    }

    public Seat getAssignedSeat() {
        return assignedSeat;
    }

    /** The room code, once known - set by ROOM_CREATED (creator) or whatever was passed to joinRoom(). */
    public String getRoomCode() {
        return roomCode;
    }

    @Override
    public void handleClick(int row, int col) {
        send("click " + row + " " + col);
    }

    @Override
    public void handleJump(int row, int col) {
        send("jump " + row + " " + col);
    }

    @Override
    public void waitFor(long ms) {
        // no-op: the server owns the only real clock in networked play.
    }

    @Override
    public GameSnapshot snapshot() {
        return latestSnapshot;
    }

    @Override
    public void onOpen(ServerHandshake handshakedata) {
        // login/register is sent explicitly once the socket is open - see authenticate()
    }

    @Override
    public void onMessage(String message) {
        if (message.startsWith(Protocol.AUTH_OK + " ")) {
            rating = Integer.parseInt(message.substring(Protocol.AUTH_OK.length() + 1).trim());
            activityLog.log("authenticated, rating=" + rating);
            authComplete.countDown();
        } else if (message.equals(Protocol.WAITING)) {
            activityLog.log("waiting for an opponent");
            if (lobbyListener != null) lobbyListener.onWaiting();
        } else if (message.startsWith(Protocol.ROOM_CREATED + " ")) {
            roomCode = message.substring(Protocol.ROOM_CREATED.length() + 1).trim();
            activityLog.log("room created: " + roomCode);
            if (lobbyListener != null) lobbyListener.onRoomCreated(roomCode);
        } else if (message.startsWith(Protocol.SEAT + " ")) {
            assignedSeat = Seat.valueOf(message.substring(Protocol.SEAT.length() + 1).trim());
            activityLog.log("seated as " + assignedSeat);
            if (lobbyListener != null) lobbyListener.onSeated(assignedSeat);
        } else if (message.startsWith(Protocol.ERROR)) {
            serverError = message.substring(Protocol.ERROR.length()).trim();
            activityLog.log("ERROR from server: " + serverError);
            // Whichever stage is currently being awaited must not hang forever.
            authComplete.countDown();
            firstSnapshot.countDown();
            if (lobbyListener != null) lobbyListener.onLobbyError(serverError);
        } else if (message.startsWith(Protocol.STATE)) {
            String block = message.substring(Protocol.STATE.length());
            if (block.startsWith("\n")) block = block.substring(1);
            latestSnapshot = SnapshotCodec.decode(block);
            firstSnapshot.countDown();
        }
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        if (activityLog != null) activityLog.log("disconnected: " + reason);
        System.err.println("Disconnected from server: " + reason);
    }

    @Override
    public void onError(Exception ex) {
        ex.printStackTrace();
    }
}
