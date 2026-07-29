package server;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.nats.client.Connection;

import bus.NatsBus;
import logging.ActivityLog;
import server.auth.UserRepository;

/**
 * The Game Server Shard, standalone: owns {@code Lobby} (and, through it,
 * every {@code GameSession}/{@code GameEngine}) - the authoritative half of
 * the reference design's WS-Gateway/Game-Server-Shard split (see
 * Server_Design.md's "Step 5"). Only makes sense in the distributed
 * (Docker Compose) topology - like {@code ApiGateway}, it requires
 * KFC_REDIS_URL/KFC_NATS_URL, no local fallback.
 *
 * Never sees a real {@code WebSocket} - every command arrives from a
 * separate {@code KungFuChessServer} (WS Gateway) process as a plain NATS
 * message carrying a {@code connectionId} (see {@link GatewayCommandEnvelope}),
 * and every reply/broadcast goes back out through a {@link
 * RemoteOutboundConnection} for that same connectionId, published on
 * {@code "conn.<connectionId>.out"} - the Gateway is already subscribed to
 * it and relays verbatim to the real socket. This class's own dispatch
 * logic ({@link #handleCommand}) is deliberately the same shape as
 * {@code KungFuChessServer}'s local-topology {@code doOnMessage}/
 * {@code handleLobbyCommand} - same routing decision, just keyed by
 * connectionId instead of a live connection reference.
 */
public class GameServerShard {
    private static final String DEFAULT_DB_PATH = "data/kungfuchess.db";
    private static final String DEFAULT_LOG_PATH = "logs/game-server-shard.log";

    private final NatsBus bus;
    private final Connection natsConnection;
    private final Lobby lobby;
    private final RoomCreationResponder roomCreationResponder;
    private final ActivityLog activityLog;
    private final Map<String, RemoteOutboundConnection> connections = new ConcurrentHashMap<>();

    public GameServerShard(String dbUrlOrPath, String logPath, String redisUrl, String natsUrl) {
        if (redisUrl == null) throw new IllegalArgumentException("KFC_REDIS_URL is required - server.GameServerShard only runs in the distributed topology");
        if (natsUrl == null) throw new IllegalArgumentException("KFC_NATS_URL is required - server.GameServerShard only runs in the distributed topology");

        UserRepository userRepository = new UserRepository(dbUrlOrPath);
        this.activityLog = new ActivityLog(logPath);
        try {
            this.bus = new NatsBus(natsUrl);
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException("Could not connect to NATS at " + natsUrl, e);
        }
        this.natsConnection = bus.rawConnection();
        ReconnectRegistry reconnectRegistry = new RedisReconnectRegistry(redisUrl);
        this.lobby = new Lobby(bus, userRepository, activityLog, reconnectRegistry);
        this.roomCreationResponder = new RoomCreationResponder(natsConnection, lobby);

        var dispatcher = natsConnection.createDispatcher();
        dispatcher.subscribe("gateway.command", msg -> handleCommand(new String(msg.getData(), StandardCharsets.UTF_8)));
        dispatcher.subscribe("gateway.reconnect", msg -> handleReconnect(new String(msg.getData(), StandardCharsets.UTF_8)));
        dispatcher.subscribe("gateway.disconnect", msg -> handleDisconnect(new String(msg.getData(), StandardCharsets.UTF_8)));
    }

    private RemoteOutboundConnection connectionFor(String connectionId) {
        return connections.computeIfAbsent(connectionId, id -> new RemoteOutboundConnection(id, natsConnection));
    }

    // Same routing decision as KungFuChessServer.doOnMessage/handleLobbyCommand in the local topology, keyed by connectionId instead of a live connection.
    private void handleCommand(String encoded) {
        try {
            GatewayCommandEnvelope envelope = GatewayCommandEnvelope.decode(encoded);
            OutboundConnection connection = connectionFor(envelope.connectionId());

            GameSession session = lobby.sessionOf(connection);
            if (session != null) {
                if (session.isGameOver() && isLobbyCommand(envelope.rawCommand())) {
                    lobby.leaveFinishedSessionIfAny(connection);
                } else {
                    session.handleCommand(connection, envelope.rawCommand());
                    return;
                }
            }
            handleLobbyCommand(connection, envelope.username(), envelope.rating(), envelope.rawCommand());
        } catch (RuntimeException e) {
            activityLog.log("handleCommand(\"" + encoded + "\") failed unexpectedly: " + e);
        }
    }

    private static boolean isLobbyCommand(String message) {
        String verb = message.trim().split("\\s+", 2)[0];
        return verb.equals(Protocol.PLAY) || verb.equals(Protocol.JOIN_ROOM);
    }

    private void handleLobbyCommand(OutboundConnection connection, String username, int rating, String message) {
        String[] parts = message.trim().split("\\s+", 2);
        String command = parts.length > 0 ? parts[0] : "";

        if (command.equals(Protocol.PLAY)) {
            boolean matched = lobby.play(connection, username, rating);
            if (!matched) connection.send(Protocol.WAITING);
            return;
        }

        if (command.equals(Protocol.JOIN_ROOM)) {
            if (parts.length < 2 || parts[1].trim().isEmpty()) {
                connection.send(Protocol.ERROR + "|expected 'join_room <code>'");
                return;
            }
            String code = parts[1].trim().toUpperCase();
            GameSession session = lobby.joinRoom(code, connection, username, rating);
            if (session == null) {
                connection.send(Protocol.ERROR + "|unknown room code");
            }
            return;
        }

        connection.send(Protocol.ERROR + "|expected 'play' or 'join_room <code>'");
    }

    // "connectionId|username" - fire-and-forget from KungFuChessServer.handleAttach; today's tryReconnect return value is already ignored by that same caller.
    private void handleReconnect(String encoded) {
        try {
            String[] parts = encoded.split("\\|", 2);
            lobby.tryReconnect(connectionFor(parts[0]), parts[1]);
        } catch (RuntimeException e) {
            activityLog.log("handleReconnect(\"" + encoded + "\") failed unexpectedly: " + e);
        }
    }

    // payload is just the connectionId - mirrors KungFuChessServer.onClose -> lobby.handleDisconnect(conn) in the local topology.
    private void handleDisconnect(String connectionId) {
        try {
            RemoteOutboundConnection connection = connections.remove(connectionId);
            if (connection == null) return;
            connection.markClosed();
            lobby.handleDisconnect(connection);
        } catch (RuntimeException e) {
            activityLog.log("handleDisconnect(\"" + connectionId + "\") failed unexpectedly: " + e);
        }
    }

    public void stop() throws InterruptedException {
        roomCreationResponder.close();
        bus.close();
    }

    public static void main(String[] args) {
        String dbUrlOrPath = System.getenv().getOrDefault("KFC_DB_URL", DEFAULT_DB_PATH);
        String logPath = System.getenv().getOrDefault("KFC_LOG_PATH", DEFAULT_LOG_PATH);
        String redisUrl = System.getenv("KFC_REDIS_URL");
        String natsUrl = System.getenv("KFC_NATS_URL");

        GameServerShard shard = new GameServerShard(dbUrlOrPath, logPath, redisUrl, natsUrl);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                shard.stop();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        System.out.println("Kung Fu Chess Game Server Shard ready, listening on NATS");
    }
}
