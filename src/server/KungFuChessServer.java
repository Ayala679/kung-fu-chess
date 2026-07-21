package server;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import bus.Bus;
import logging.ActivityLog;
import net.Protocol;
import server.auth.UserRepository;

/**
 * Single-process Kung Fu Chess server: accepts WebSocket connections,
 * requires a "login"/"register" (username + password, checked against a
 * SQLite-backed UserRepository) as the first message, then - once
 * authenticated - waits for exactly one lobby command ("play", "create_room",
 * or "join_room <code>") before handing the connection off to a GameSession
 * via Lobby. Everything about the game itself - rules, timing, captures,
 * ratings - is delegated entirely to GameSession/GameEngine, the exact same
 * classes the offline client uses.
 */
public class KungFuChessServer extends WebSocketServer {
    public static final int DEFAULT_PORT = 8887;
    private static final String DEFAULT_DB_PATH = "data/kungfuchess.db";
    private static final String DEFAULT_LOG_PATH = "logs/server.log";

    private final Bus bus = new Bus();
    private final UserRepository userRepository;
    private final ActivityLog activityLog;
    private final Lobby lobby;
    private final Map<WebSocket, String> usernames = new ConcurrentHashMap<>();
    private final Map<WebSocket, Integer> ratings = new ConcurrentHashMap<>();

    public KungFuChessServer(int port) {
        this(port, DEFAULT_DB_PATH, DEFAULT_LOG_PATH);
    }

    public KungFuChessServer(int port, String dbPath) {
        this(port, dbPath, DEFAULT_LOG_PATH);
    }

    public KungFuChessServer(int port, String dbPath, String logPath) {
        super(new InetSocketAddress(port));
        this.userRepository = new UserRepository(dbPath);
        this.activityLog = new ActivityLog(logPath);
        this.lobby = new Lobby(bus, userRepository, activityLog);
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        // Login/register happens on the connection's first text message - see onMessage.
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        if (!usernames.containsKey(conn)) {
            handleAuth(conn, message);
            return;
        }

        GameSession session = lobby.sessionOf(conn);
        if (session != null) {
            session.handleCommand(conn, message);
            return;
        }

        handleLobbyCommand(conn, message);
    }

    private void handleAuth(WebSocket conn, String message) {
        String[] parts = message.trim().split("\\s+", 3);
        boolean isLogin = parts.length > 0 && parts[0].equals(Protocol.LOGIN);
        boolean isRegister = parts.length > 0 && parts[0].equals(Protocol.REGISTER);
        if (parts.length < 3 || !(isLogin || isRegister)) {
            conn.send(Protocol.ERROR + " expected 'login <username> <password>' or 'register <username> <password>'");
            conn.close();
            return;
        }

        String username = parts[1];
        String password = parts[2];
        UserRepository.AuthResult auth = isRegister
                ? userRepository.register(username, password)
                : userRepository.authenticate(username, password);

        if (!auth.isSuccess()) {
            activityLog.log("AUTH_FAILED " + username + ": " + auth.getFailureReason());
            conn.send(Protocol.ERROR + " " + auth.getFailureReason());
            conn.close();
            return;
        }

        usernames.put(conn, username);
        ratings.put(conn, auth.getRating());
        activityLog.log(username + " authenticated (rating " + auth.getRating() + ")");
        conn.send(Protocol.AUTH_OK + " " + auth.getRating());
    }

    private void handleLobbyCommand(WebSocket conn, String message) {
        String username = usernames.get(conn);
        int rating = ratings.get(conn);
        String[] parts = message.trim().split("\\s+", 2);
        String command = parts.length > 0 ? parts[0] : "";

        if (command.equals(Protocol.PLAY)) {
            boolean matched = lobby.play(conn, username, rating);
            if (!matched) conn.send(Protocol.WAITING);
            return;
        }

        if (command.equals(Protocol.CREATE_ROOM)) {
            GameSession session = lobby.createRoom(conn, username, rating);
            conn.send(Protocol.ROOM_CREATED + " " + session.getRoomCode());
            // No SEAT/STATE yet - GameSession.join greets White only once Black actually joins.
            return;
        }

        if (command.equals(Protocol.JOIN_ROOM)) {
            if (parts.length < 2 || parts[1].trim().isEmpty()) {
                conn.send(Protocol.ERROR + " expected 'join_room <code>'");
                return;
            }
            String code = parts[1].trim().toUpperCase();
            GameSession session = lobby.joinRoom(code, conn, username, rating);
            if (session == null) {
                conn.send(Protocol.ERROR + " unknown room code");
            }
            return;
        }

        conn.send(Protocol.ERROR + " expected 'play', 'create_room', or 'join_room <code>'");
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        usernames.remove(conn);
        ratings.remove(conn);
        lobby.handleDisconnect(conn);
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        ex.printStackTrace();
    }

    @Override
    public void onStart() {
        System.out.println("Kung Fu Chess server listening on port " + getPort());
    }

    public static void main(String[] args) {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_PORT;
        new KungFuChessServer(port).start();
    }
}
