package client;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import event.GameClient;
import logging.ActivityLog;
import server.Protocol;
import server.Seat;
import server.SnapshotCodec;
import snapshot.GameSnapshot;

/**
 * Client-side counterpart to server.KungFuChessServerService/HttpApiServer/Lobby/
 * GameSession. Two stages: authenticate, then pick a game (quick-match
 * "play", or create/join a room) - the latter is inherently asynchronous
 * (the server may reply WAITING and only send a WELCOME once an opponent
 * actually arrives), so it's callback-driven via {@link LobbyListener}
 * rather than a single blocking call. Once seated, this turns
 * view.BoardWindow's clicks/jumps into text frames and exposes whatever
 * GameSnapshot the server most recently pushed. waitFor() is a no-op: the
 * server is the only real clock.
 *
 * Login/register/room-creation are non-realtime and go over REST
 * (server.HttpApiServer) rather than this WebSocket - see authenticate()/
 * createRoom(). The HTTP API's port is the WebSocket port + 1 by
 * convention (8888 alongside the default WS port 8887); there's no separate
 * server-address field in the login UI to carry a second port, so this is
 * derived rather than configured - see Server_Design.md.
 */
public class NetworkGameClient extends WebSocketClient implements GameClient {
    /** Callbacks for what happens after authentication, while picking a game - always delivered off the Swing thread. */
    public interface LobbyListener {
        void onWaiting();
        void onRoomCreated(String roomCode);
        void onSeated(Seat seat);
        void onLobbyError(String message);
    }

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final CountDownLatch authComplete = new CountDownLatch(1);
    private final CountDownLatch firstSnapshot = new CountDownLatch(1);
    private volatile int rating;
    private volatile String sessionToken;
    private volatile String serverError;
    private volatile Seat assignedSeat;
    private volatile String roomCode;
    private volatile GameSnapshot latestSnapshot;
    private volatile LobbyListener lobbyListener;
    private volatile ActivityLog activityLog;
    /** Epoch millis when the opponent will be auto-abandoned, or 0 if they aren't currently disconnected. */
    private volatile long opponentAbandonDeadline;

    public NetworkGameClient(URI serverUri) {
        super(serverUri);
    }

    /** Registers a new account (REST) and attaches this connection to it (WS). Blocks until AUTH_OK or a refusal. */
    public void register(String username, String password, long timeoutSeconds) throws Exception {
        authenticate(Protocol.REGISTER, username, password, timeoutSeconds);
    }

    /** Signs into an existing account (REST) and attaches this connection to it (WS). Blocks until AUTH_OK or a refusal. */
    public void login(String username, String password, long timeoutSeconds) throws Exception {
        authenticate(Protocol.LOGIN, username, password, timeoutSeconds);
    }

    private void authenticate(String mode, String username, String password, long timeoutSeconds) throws Exception {
        activityLog = new ActivityLog("logs/client-" + username + ".log");

        activityLog.log("HTTP POST /api/" + mode + " for " + username);
        HttpResponse<String> response = postForm("/api/" + mode, "username=" + urlEncode(username) + "&password=" + urlEncode(password), timeoutSeconds);
        String body = response.body().trim();
        if (!body.startsWith(Protocol.AUTH_OK + "|")) {
            String reason = body.startsWith(Protocol.ERROR + "|") ? body.substring(Protocol.ERROR.length() + 1) : body;
            activityLog.log("REST auth refused: " + reason);
            throw new IllegalStateException("Refused: " + reason);
        }
        String[] parts = body.substring(Protocol.AUTH_OK.length() + 1).split("\\|", 2);
        sessionToken = parts[0];
        rating = Integer.parseInt(parts[1].trim());

        activityLog.log("connecting to " + getURI());
        if (!connectBlocking(timeoutSeconds, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Could not connect to " + getURI());
        }
        activityLog.log("attach " + username);
        send(Protocol.ATTACH + " " + sessionToken);
        if (!authComplete.await(timeoutSeconds, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Server did not respond to authentication in time");
        }
        if (serverError != null) {
            throw new IllegalStateException("Refused: " + serverError);
        }
    }

    /** Set before requestPlay()/createRoom()/joinRoom() - notified of WAITING/ROOM_CREATED/WELCOME/errors. */
    public void setLobbyListener(LobbyListener listener) {
        this.lobbyListener = listener;
    }

    public void requestPlay() {
        activityLog.log("play (quick-match)");
        send(Protocol.PLAY);
    }

    /** REST-creates a fresh room, reports the code to the LobbyListener directly, then joins it over the WebSocket (seated White, being first to arrive). */
    public void createRoom() {
        try {
            activityLog.log("HTTP POST /api/rooms");
            HttpResponse<String> response = postForm("/api/rooms", "token=" + urlEncode(sessionToken), 10);
            String body = response.body().trim();
            if (!body.startsWith(Protocol.ROOM_CREATED + "|")) {
                String reason = body.startsWith(Protocol.ERROR + "|") ? body.substring(Protocol.ERROR.length() + 1) : body;
                activityLog.log("create_room refused: " + reason);
                if (lobbyListener != null) lobbyListener.onLobbyError(reason);
                return;
            }
            roomCode = body.substring(Protocol.ROOM_CREATED.length() + 1).trim();
            activityLog.log("room created: " + roomCode);
            if (lobbyListener != null) lobbyListener.onRoomCreated(roomCode);
            joinRoom(roomCode);
        } catch (Exception e) {
            activityLog.log("create_room failed: " + e);
            if (lobbyListener != null) lobbyListener.onLobbyError(e.getMessage());
        }
    }

    private HttpResponse<String> postForm(String path, String formBody, long timeoutSeconds) throws Exception {
        URI wsUri = getURI();
        URI httpUri = new URI("http", null, wsUri.getHost(), wsUri.getPort() + 1, path, null, null);
        HttpRequest request = HttpRequest.newBuilder(httpUri)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(formBody))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
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

    /** The room code, once known - set by the REST createRoom() response, or whatever was passed to joinRoom(). */
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
    public long millisUntilOpponentAutoAbandon() {
        long deadline = opponentAbandonDeadline;
        return deadline <= 0 ? 0 : deadline - System.currentTimeMillis();
    }

    @Override
    public void requestRematch() {
        send(Protocol.REMATCH);
    }

    @Override
    public void onOpen(ServerHandshake handshakedata) {
        // "attach <token>" is sent explicitly once the socket is open - see authenticate()
    }

    @Override
    public void onMessage(String message) {
        if (message.startsWith(Protocol.AUTH_OK + "|")) {
            rating = Integer.parseInt(message.substring(Protocol.AUTH_OK.length() + 1).trim());
            activityLog.log("attached, rating=" + rating);
            authComplete.countDown();
        } else if (message.equals(Protocol.WAITING)) {
            activityLog.log("waiting for an opponent");
            if (lobbyListener != null) lobbyListener.onWaiting();
        } else if (message.startsWith(Protocol.WELCOME + "|role=")) {
            assignedSeat = Seat.valueOf(message.substring((Protocol.WELCOME + "|role=").length()).trim());
            activityLog.log("seated as " + assignedSeat);
            if (lobbyListener != null) lobbyListener.onSeated(assignedSeat);
        } else if (message.startsWith(Protocol.COMMAND_RESULT + "|")) {
            activityLog.log("command result: " + message.substring(Protocol.COMMAND_RESULT.length() + 1).trim());
        } else if (message.startsWith(Protocol.EVENT + "|")) {
            activityLog.log("event: " + message.substring(Protocol.EVENT.length() + 1).trim());
        } else if (message.startsWith(Protocol.ERROR)) {
            serverError = message.startsWith(Protocol.ERROR + "|")
                    ? message.substring(Protocol.ERROR.length() + 1)
                    : message.substring(Protocol.ERROR.length()).trim();
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
        } else if (message.startsWith(Protocol.OPPONENT_DISCONNECTED + "|")) {
            long graceSeconds = Long.parseLong(message.substring((Protocol.OPPONENT_DISCONNECTED + "|").length()).trim());
            opponentAbandonDeadline = System.currentTimeMillis() + graceSeconds * 1000;
            activityLog.log("opponent disconnected, auto-abandon in " + graceSeconds + "s if not reconnected");
        } else if (message.equals(Protocol.OPPONENT_RECONNECTED)) {
            opponentAbandonDeadline = 0;
            activityLog.log("opponent reconnected");
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
