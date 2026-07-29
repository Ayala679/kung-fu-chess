package server;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import io.nats.client.Connection;
import io.nats.client.Dispatcher;
import io.nats.client.Nats;

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
 * The WS Gateway. Attach handling (bearer-token validation against {@link
 * SessionTokenStore}) is always handled locally - Redis-backed, directly
 * reachable, no reason to bother the Game Server Shard for a pure auth
 * check. Everything else depends on topology (see the constructor):
 *
 * <b>Local/offline</b> (no KFC_REDIS_URL/KFC_NATS_URL - unchanged since
 * before step 5): still embeds {@code Lobby}/{@code GameSession} directly,
 * single process, exactly as before - real {@code WebSocket}s are wrapped
 * in a cached {@link LocalOutboundConnection} at each call into {@code
 * Lobby}.
 *
 * <b>Distributed</b> (both set - the Docker Compose deployment, talking to
 * a separate {@code server.GameServerShard} process): this class becomes a
 * dumb relay with no {@code Lobby}/{@code GameSession}/{@code
 * UserRepository} reference at all. Each connection gets a random {@code
 * connectionId}, subscribed to {@code "conn.<connectionId>.out"} (whatever
 * arrives there is forwarded verbatim to the real socket - see {@link
 * RemoteOutboundConnection}, the Shard's half of this); every post-attach
 * WS message is fire-and-forget published to {@code "gateway.command"} as
 * a {@link GatewayCommandEnvelope} - no request/reply needed, since every
 * reply (including immediate acks like {@code COMMAND_RESULT}/{@code
 * WAITING}/{@code ERROR}) already flows back out through the same {@code
 * conn.<connectionId>.out} channel today's {@code Lobby}/{@code
 * GameSession} already send through. See Server_Design.md's "Step 5".
 */
public class KungFuChessServer extends WebSocketServer {
    public static final int DEFAULT_PORT = 8887;
    public static final int DEFAULT_HTTP_PORT = 8888;
    private static final String DEFAULT_DB_PATH = "data/kungfuchess.db";
    private static final String DEFAULT_LOG_PATH = "logs/server.log";

    private final boolean distributed;
    private final ActivityLog activityLog;
    private final SessionTokenStore sessionTokenStore;
    private final Map<WebSocket, String> usernames = new ConcurrentHashMap<>();
    private final Map<WebSocket, Integer> ratings = new ConcurrentHashMap<>();

    // Embedded topology only (all null in distributed mode).
    private final Bus bus;
    private final NatsBus natsBus; // non-null only if bus is actually a NatsBus (partially-configured edge case) - so stopAll() knows whether there's a connection to close
    private final AuthController authController;
    private final Lobby lobby;
    private final HttpApiServer httpApiServer;
    private final Map<WebSocket, OutboundConnection> localConnections = new ConcurrentHashMap<>();

    // Distributed topology only (all null in embedded mode).
    private final Connection gatewayNatsConnection;
    private final Dispatcher gatewayDispatcher;
    private final Map<WebSocket, String> connectionIds = new ConcurrentHashMap<>();

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
     *                    UserRepository's constructor. Only used in the
     *                    embedded topology - the distributed one has no
     *                    database access at all (that's the Game Server
     *                    Shard's job now).
     * @param redisUrl    e.g. {@code redis://redis:6379} - null/absent means
     *                    the in-memory SessionTokenStore (local/offline
     *                    runs, and every unit test).
     * @param natsUrl     e.g. {@code nats://nats:4222} - null/absent means
     *                    the embedded topology (see class Javadoc).
     */
    public KungFuChessServer(int port, int httpPort, String dbUrlOrPath, String logPath, String redisUrl, String natsUrl) {
        super(new InetSocketAddress(port));
        this.activityLog = new ActivityLog(logPath);
        this.sessionTokenStore = redisUrl != null ? new RedisSessionTokenStore(redisUrl) : new InMemorySessionTokenStore();
        this.distributed = redisUrl != null && natsUrl != null;

        if (distributed) {
            this.bus = null;
            this.natsBus = null;
            this.authController = null;
            this.lobby = null;
            this.httpApiServer = null;
            try {
                this.gatewayNatsConnection = Nats.connect(natsUrl);
            } catch (IOException | InterruptedException e) {
                throw new IllegalStateException("Could not connect to NATS at " + natsUrl, e);
            }
            this.gatewayDispatcher = gatewayNatsConnection.createDispatcher();
        } else {
            UserRepository userRepository = new UserRepository(dbUrlOrPath);
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

            ReconnectRegistry reconnectRegistry = redisUrl != null ? new RedisReconnectRegistry(redisUrl) : new InMemoryReconnectRegistry();
            this.lobby = new Lobby(bus, userRepository, activityLog, reconnectRegistry);
            try {
                this.httpApiServer = new HttpApiServer(httpPort, authController, sessionTokenStore, new LocalRoomCreator(lobby), activityLog);
            } catch (IOException e) {
                throw new IllegalStateException("Could not start HTTP API server on port " + httpPort, e);
            }
            this.gatewayNatsConnection = null;
            this.gatewayDispatcher = null;
        }
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        activityLog.log("connection opened: " + conn.getRemoteSocketAddress());
        if (distributed) {
            String connectionId = UUID.randomUUID().toString();
            connectionIds.put(conn, connectionId);
            gatewayDispatcher.subscribe("conn." + connectionId + ".out", msg -> {
                if (conn.isOpen()) conn.send(new String(msg.getData(), StandardCharsets.UTF_8));
            });
        }
        // Attach happens on the connection's first text message - see onMessage.
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

        if (distributed) {
            String envelope = new GatewayCommandEnvelope(connectionIds.get(conn), usernames.get(conn), ratings.get(conn), message).encode();
            gatewayNatsConnection.publish("gateway.command", envelope.getBytes(StandardCharsets.UTF_8));
            return;
        }

        OutboundConnection outbound = localConnections.computeIfAbsent(conn, LocalOutboundConnection::new);
        GameSession session = lobby.sessionOf(outbound);
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
                lobby.leaveFinishedSessionIfAny(outbound);
            } else {
                session.handleCommand(outbound, message);
                return;
            }
        }

        handleLobbyCommand(conn, outbound, message);
    }

    private static boolean isLobbyCommand(String message) {
        String verb = message.trim().split("\\s+", 2)[0];
        return verb.equals(Protocol.PLAY) || verb.equals(Protocol.JOIN_ROOM);
    }

    // Parses "attach <token>" (token minted by the REST login/register endpoints, see HttpApiServer/ApiGateway), replies AUTH_OK/ERROR. Always handled locally (SessionTokenStore is directly reachable in both topologies) - only what happens on success (a reconnect attempt) differs by topology.
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
        // see Lobby.tryReconnect / GameSession.reconnect. Fire-and-forget in
        // the distributed topology - today's return value is already
        // ignored here too.
        if (distributed) {
            String envelope = connectionIds.get(conn) + "|" + username;
            gatewayNatsConnection.publish("gateway.reconnect", envelope.getBytes(StandardCharsets.UTF_8));
        } else {
            lobby.tryReconnect(localConnections.computeIfAbsent(conn, LocalOutboundConnection::new), username);
        }
    }

    // Dispatches "play"/"join_room <code>" to the matching Lobby method (room *creation* is REST-only, see HttpApiServer/Lobby.createRoom) - embedded topology only, see doOnMessage.
    private void handleLobbyCommand(WebSocket conn, OutboundConnection outbound, String message) {
        String username = usernames.get(conn);
        int rating = ratings.get(conn);
        String[] parts = message.trim().split("\\s+", 2);
        String command = parts.length > 0 ? parts[0] : "";

        if (command.equals(Protocol.PLAY)) {
            boolean matched = lobby.play(outbound, username, rating);
            if (!matched) conn.send(Protocol.WAITING);
            return;
        }

        if (command.equals(Protocol.JOIN_ROOM)) {
            if (parts.length < 2 || parts[1].trim().isEmpty()) {
                conn.send(Protocol.ERROR + "|expected 'join_room <code>'");
                return;
            }
            String code = parts[1].trim().toUpperCase();
            GameSession session = lobby.joinRoom(code, outbound, username, rating);
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

        if (distributed) {
            String connectionId = connectionIds.remove(conn);
            if (connectionId != null) {
                gatewayNatsConnection.publish("gateway.disconnect", connectionId.getBytes(StandardCharsets.UTF_8));
                gatewayDispatcher.unsubscribe("conn." + connectionId + ".out");
            }
        } else {
            OutboundConnection outbound = localConnections.remove(conn);
            if (outbound != null) lobby.handleDisconnect(outbound);
        }
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        ex.printStackTrace();
        activityLog.log("WebSocket error on " + (conn == null ? "unknown connection" : conn.getRemoteSocketAddress()) + ": " + ex);
    }

    @Override
    public void onStart() {
        if (distributed) {
            System.out.println("Kung Fu Chess WS Gateway listening on port " + getPort() + "; commands relayed to server.GameServerShard over NATS");
        } else {
            httpApiServer.start();
            System.out.println("Kung Fu Chess server listening on port " + getPort()
                    + " (WS) and " + httpApiServer.getPort() + " (HTTP)");
        }
    }

    /** Stops the WebSocket/HTTP servers and the NATS connection (if any) - used by main()'s shutdown hook so Docker's SIGTERM (docker compose down) shuts down cleanly instead of being killed. */
    public void stopAll() throws InterruptedException {
        if (distributed) {
            gatewayNatsConnection.close();
        } else {
            httpApiServer.stop();
            if (natsBus != null) natsBus.close();
        }
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
