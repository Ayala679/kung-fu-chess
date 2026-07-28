package server;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import bus.Bus;
import bus.InMemoryBus;
import bus.NatsBus;
import logging.ActivityLog;
import server.auth.AuthController;
import server.auth.InMemorySessionTokenStore;
import server.auth.RedisSessionTokenStore;
import server.auth.SessionTokenStore;
import server.auth.UserRepository;

/**
 * Single-process Kung Fu Chess server: accepts WebSocket connections whose
 * first message must be "attach <token>" (a bearer token minted by the REST
 * login/register endpoints - see HttpApiServer - checked against a
 * UserRepository backed by SQLite locally or PostgreSQL when deployed via
 * Docker Compose), then - once authenticated - waits for exactly one lobby
 * command ("play" or "join_room <code>"; room *creation* is REST-only now,
 * see Lobby.createRoom) before handing the connection off to a GameSession
 * via Lobby. Everything about the game itself - rules, timing, captures,
 * ratings - is delegated entirely to GameSession/GameEngine, the exact same
 * classes the offline client uses.
 */
public class KungFuChessServer extends WebSocketServer {
    public static final int DEFAULT_PORT = 8887;
    public static final int DEFAULT_HTTP_PORT = 8888;
    private static final String DEFAULT_DB_PATH = "data/kungfuchess.db";
    private static final String DEFAULT_LOG_PATH = "logs/server.log";

    private final Bus bus;
    private final NatsBus natsBus; // non-null only if bus is actually a NatsBus - so stopAll() knows whether there's a connection to close
    private final ActivityLog activityLog;
    private final AuthController authController;
    private final SessionTokenStore sessionTokenStore;
    private final Lobby lobby;
    private final HttpApiServer httpApiServer;
    private final Map<WebSocket, String> usernames = new ConcurrentHashMap<>();
    private final Map<WebSocket, Integer> ratings = new ConcurrentHashMap<>();

    public KungFuChessServer(int port) {
        this(port, DEFAULT_HTTP_PORT, DEFAULT_DB_PATH, DEFAULT_LOG_PATH, null, null);
    }

    public KungFuChessServer(int port, String dbUrlOrPath) {
        this(port, DEFAULT_HTTP_PORT, dbUrlOrPath, DEFAULT_LOG_PATH, null, null);
    }

    public KungFuChessServer(int port, String dbUrlOrPath, String logPath) {
        this(port, DEFAULT_HTTP_PORT, dbUrlOrPath, logPath, null, null);
    }

    /**
     * @param dbUrlOrPath either a bare SQLite file path (legacy/local-dev
     *                    behavior, unchanged) or a full JDBC URL
     *                    (e.g. {@code jdbc:postgresql://...}) - see
     *                    UserRepository's constructor.
     * @param redisUrl    e.g. {@code redis://redis:6379} - null/absent means
     *                    the in-memory SessionTokenStore/ReconnectRegistry
     *                    (local/offline runs, and every unit test); see
     *                    Server_Design.md for why this is a deliberate,
     *                    still-single-process-safe scope, not a full
     *                    Redis migration.
     * @param natsUrl     e.g. {@code nats://nats:4222} - null/absent means
     *                    the in-memory Bus (same reasoning as redisUrl).
     */
    public KungFuChessServer(int port, int httpPort, String dbUrlOrPath, String logPath, String redisUrl, String natsUrl) {
        super(new InetSocketAddress(port));
        UserRepository userRepository = new UserRepository(dbUrlOrPath);
        this.activityLog = new ActivityLog(logPath);
        this.authController = new AuthController(userRepository, activityLog);

        if (natsUrl != null) {
            try {
                this.natsBus = new NatsBus(natsUrl);
            } catch (IOException | InterruptedException e) {
                throw new IllegalStateException("Could not connect to NATS at " + natsUrl, e);
            }
            this.bus = natsBus;
        } else {
            this.natsBus = null;
            this.bus = new InMemoryBus();
        }

        this.sessionTokenStore = redisUrl != null ? new RedisSessionTokenStore(redisUrl) : new InMemorySessionTokenStore();
        ReconnectRegistry reconnectRegistry = redisUrl != null ? new RedisReconnectRegistry(redisUrl) : new InMemoryReconnectRegistry();

        this.lobby = new Lobby(bus, userRepository, activityLog, reconnectRegistry);
        try {
            this.httpApiServer = new HttpApiServer(httpPort, authController, sessionTokenStore, lobby, activityLog);
        } catch (IOException e) {
            throw new IllegalStateException("Could not start HTTP API server on port " + httpPort, e);
        }
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        // Attach happens on the connection's first text message - see onMessage.
        activityLog.log("connection opened: " + conn.getRemoteSocketAddress());
    }

    // Routes by connection state: not authenticated -> handleAuth; authenticated + in a session -> session.handleCommand; authenticated, no session yet -> handleLobbyCommand.
    @Override
    public void onMessage(WebSocket conn, String message) {
        // No per-message entry/exit log here on purpose - this fires on every
        // click/jump during a live game, not just once per connection; the
        // try/catch is still worth it as the one true entry point for every
        // inbound message, regardless of which stage (auth/lobby/game) it's
        // headed for - session.handleCommand has its own inner try/catch too
        // (defense in depth), but auth/lobby dispatch below had none at all.
        try {
            doOnMessage(conn, message);
        } catch (RuntimeException e) {
            activityLog.log("connection " + conn.getRemoteSocketAddress() + ": onMessage(\"" + message + "\") failed unexpectedly: " + e);
            conn.send(Protocol.ERROR + "|INTERNAL_ERROR");
        }
    }

    private void doOnMessage(WebSocket conn, String message) {
        if (!usernames.containsKey(conn)) {
            handleAttach(conn, message);
            return;
        }

        GameSession session = lobby.sessionOf(conn);
        if (session != null) {
            // "rematch"/click/jump still belong to this session (rematch is
            // exactly how a finished game gets replayed) - but "play"/
            // "join_room" mean this connection wants a genuinely different
            // game, which this old, already-over session can't offer (its
            // handleCommand only knows rematch/click/jump - anything else
            // would otherwise come back as a confusing MALFORMED_COMMAND).
            // Leave it first so the normal lobby flow below can actually
            // seat them somewhere new.
            if (session.isGameOver() && isLobbyCommand(message)) {
                lobby.leaveFinishedSessionIfAny(conn);
            } else {
                session.handleCommand(conn, message);
                return;
            }
        }

        handleLobbyCommand(conn, message);
    }

    private static boolean isLobbyCommand(String message) {
        String verb = message.trim().split("\\s+", 2)[0];
        return verb.equals(Protocol.PLAY) || verb.equals(Protocol.JOIN_ROOM);
    }

    // Parses "attach <token>" (token minted by the REST login/register endpoints, see HttpApiServer), replies AUTH_OK/ERROR, then calls lobby.tryReconnect on success.
    private void handleAttach(WebSocket conn, String message) {
        String[] parts = message.trim().split("\\s+", 2);
        if (parts.length < 2 || !parts[0].equals(Protocol.ATTACH)) {
            conn.send(Protocol.ERROR + "|expected 'attach <token>'");
            conn.close();
            return;
        }

        Optional<SessionTokenStore.Principal> principal = sessionTokenStore.validate(parts[1].trim());
        if (principal.isEmpty()) {
            conn.send(Protocol.ERROR + "|invalid or expired token");
            conn.close();
            return;
        }

        String username = principal.get().username();
        usernames.put(conn, username);
        ratings.put(conn, principal.get().rating());
        conn.send(Protocol.AUTH_OK + "|" + principal.get().rating());

        // If this username was seated in a game it got disconnected from,
        // silently resume it instead of making them pick play/join again -
        // see Lobby.tryReconnect / GameSession.reconnect.
        lobby.tryReconnect(conn, username);
    }

    // Dispatches "play"/"join_room <code>" to the matching Lobby method (room *creation* is REST-only, see HttpApiServer/Lobby.createRoom).
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

        if (command.equals(Protocol.JOIN_ROOM)) {
            if (parts.length < 2 || parts[1].trim().isEmpty()) {
                conn.send(Protocol.ERROR + "|expected 'join_room <code>'");
                return;
            }
            String code = parts[1].trim().toUpperCase();
            GameSession session = lobby.joinRoom(code, conn, username, rating);
            if (session == null) {
                conn.send(Protocol.ERROR + "|unknown room code");
            }
            return;
        }

        conn.send(Protocol.ERROR + "|expected 'play' or 'join_room <code>'");
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        activityLog.log("connection closed: " + conn.getRemoteSocketAddress() + " (" + reason + ")");
        usernames.remove(conn);
        ratings.remove(conn);
        lobby.handleDisconnect(conn);
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        ex.printStackTrace();
        activityLog.log("WebSocket error on " + (conn == null ? "unknown connection" : conn.getRemoteSocketAddress()) + ": " + ex);
    }

    @Override
    public void onStart() {
        httpApiServer.start();
        System.out.println("Kung Fu Chess server listening on port " + getPort()
                + " (WS) and " + httpApiServer.getPort() + " (HTTP)");
    }

    /** Stops the WebSocket/HTTP servers and the NATS connection (if any) - used by main()'s shutdown hook so Docker's SIGTERM (docker compose down) shuts down cleanly instead of being killed. */
    public void stopAll() throws InterruptedException {
        httpApiServer.stop();
        if (natsBus != null) natsBus.close();
        stop();
    }

    public static void main(String[] args) {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : envInt("KFC_WS_PORT", DEFAULT_PORT);
        int httpPort = envInt("KFC_HTTP_PORT", DEFAULT_HTTP_PORT);
        String dbUrlOrPath = System.getenv().getOrDefault("KFC_DB_URL", DEFAULT_DB_PATH);
        String logPath = System.getenv().getOrDefault("KFC_LOG_PATH", DEFAULT_LOG_PATH);
        String redisUrl = System.getenv("KFC_REDIS_URL");
        String natsUrl = System.getenv("KFC_NATS_URL");

        KungFuChessServer server = new KungFuChessServer(port, httpPort, dbUrlOrPath, logPath, redisUrl, natsUrl);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                server.stopAll();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        server.start();
    }

    private static int envInt(String name, int defaultValue) {
        String value = System.getenv(name);
        return value != null ? Integer.parseInt(value) : defaultValue;
    }
}
