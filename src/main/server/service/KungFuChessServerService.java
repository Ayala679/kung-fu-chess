package server.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.java_websocket.WebSocket;

import io.nats.client.Connection;
import io.nats.client.Dispatcher;
import io.nats.client.Nats;

import bus.Bus;
import bus.InMemoryBus;
import bus.NatsBus;
import logging.ActivityLog;
import server.GameSession;
import server.GatewayCommandEnvelope;
import server.Lobby;
import server.MatchQueue;
import server.Protocol;
import server.auth.AuthService;
import server.auth.InMemorySessionTokenStore;
import server.auth.RedisSessionTokenStore;
import server.auth.SessionTokenStore;
import server.auth.UserRepository;
import server.connection.LocalOutboundConnection;
import server.connection.OutboundConnection;
import server.controller.HttpApiServer;
import server.reconnect.InMemoryReconnectRegistry;
import server.reconnect.ReconnectRegistry;
import server.reconnect.RedisReconnectRegistry;
import server.room.LocalRoomCreator;
import server.room.RedisRoomDirectory;
import server.room.RoomDirectory;

/**
 * All the decision logic behind the WS Gateway. Attach handling (bearer-token
 * validation against {@link SessionTokenStore}) is always handled locally -
 * Redis-backed, directly reachable, no reason to bother a Game Server Shard
 * for a pure auth check. Everything else depends on topology (see the
 * constructor):
 *
 * <b>Local/offline</b> (no KFC_REDIS_URL/KFC_NATS_URL - unchanged since
 * before step 5): still embeds {@code Lobby}/{@code GameSession} directly,
 * single process, exactly as before - real {@code WebSocket}s are wrapped
 * in a cached {@link LocalOutboundConnection} at each call into {@code
 * Lobby}.
 *
 * <b>Distributed</b> (both set - the Docker Compose deployment, talking to
 * one or more {@code server.GameServerShardController} processes): this
 * class holds no {@code Lobby}/{@code GameSession}/{@code UserRepository}
 * reference at all. Each connection gets a random {@code connectionId},
 * subscribed to {@code "conn.<connectionId>.out"} (whatever arrives there
 * is forwarded verbatim to the real socket - see {@link
 * RemoteOutboundConnection}, a Shard's half of this) and, since "Step 6a",
 * also to {@code "conn.<connectionId>.shard"} (a shard announcing "this
 * connection is now mine" - see {@link #shardOf}). Every reply (including
 * immediate acks like {@code COMMAND_RESULT}/{@code WAITING}/{@code ERROR})
 * flows back out through the same {@code conn.<connectionId>.out} channel
 * {@code Lobby}/{@code GameSession} already send through, so no NATS
 * request/reply is ever needed here - just routing each post-attach
 * message to the *one* shard that should see it (see {@link
 * #routeDistributedCommand}) - which is this class's one genuinely hard
 * job as of "Step 6a" (multiple shards): a "play" always goes to {@code
 * "matchmaker.play"}; a {@code join_room <code>} is resolved fresh via
 * {@link RoomDirectory} every time (never the cached {@link #shardOf} -
 * a stale entry from a previous, finished game on this same connection
 * must never leak into routing a request for a brand-new room); every
 * other post-attach command (click/jump/rematch) relies on {@link
 * #shardOf}, populated once a shard announces ownership. See
 * Server_Design.md's "Step 5" and "Step 6a".
 */
public class KungFuChessServerService {
    private final boolean distributed;
    private final ActivityLog activityLog;
    private final SessionTokenStore sessionTokenStore;
    private final Map<WebSocket, String> usernames = new ConcurrentHashMap<>();
    private final Map<WebSocket, Integer> ratings = new ConcurrentHashMap<>();

    // Embedded topology only (all null in distributed mode).
    private final Bus bus;
    private final NatsBus natsBus; // non-null only if bus is actually a NatsBus (partially-configured edge case) - so stopAll() knows whether there's a connection to close
    private final AuthService authService;
    private final Lobby lobby;
    private final MatchQueue matchQueue;
    private final HttpApiServer httpApiServer;
    private final Map<WebSocket, OutboundConnection> localConnections = new ConcurrentHashMap<>();

    // Distributed topology only (all null in embedded mode).
    private final Connection gatewayNatsConnection;
    private final Dispatcher gatewayDispatcher;
    private final Map<WebSocket, String> connectionIds = new ConcurrentHashMap<>();
    private final RoomDirectory roomDirectory;
    private final Map<WebSocket, String> shardOf = new ConcurrentHashMap<>();

    /**
     * @param dbUrlOrPath either a bare SQLite file path (legacy/local-dev
     *                    behavior, unchanged) or a full JDBC URL
     *                    (e.g. {@code jdbc:postgresql://...}) - see
     *                    UserRepository's constructor. Only used in the
     *                    embedded topology - the distributed one has no
     *                    database access at all (that's a Game Server
     *                    Shard's job now).
     * @param redisUrl    e.g. {@code redis://redis:6379} - null/absent means
     *                    the in-memory SessionTokenStore (local/offline
     *                    runs, and every unit test).
     * @param natsUrl     e.g. {@code nats://nats:4222} - null/absent means
     *                    the embedded topology (see class Javadoc).
     */
    public KungFuChessServerService(int httpPort, String dbUrlOrPath, String logPath, String redisUrl, String natsUrl) {
        this.activityLog = new ActivityLog(logPath);
        this.sessionTokenStore = redisUrl != null ? new RedisSessionTokenStore(redisUrl) : new InMemorySessionTokenStore();
        this.distributed = redisUrl != null && natsUrl != null;

        if (distributed) {
            this.bus = null;
            this.natsBus = null;
            this.authService = null;
            this.lobby = null;
            this.matchQueue = null;
            this.httpApiServer = null;
            try {
                this.gatewayNatsConnection = Nats.connect(natsUrl);
            } catch (IOException | InterruptedException e) {
                throw new IllegalStateException("Could not connect to NATS at " + natsUrl, e);
            }
            this.gatewayDispatcher = gatewayNatsConnection.createDispatcher();
            this.roomDirectory = new RedisRoomDirectory(redisUrl);
        } else {
            UserRepository userRepository = new UserRepository(dbUrlOrPath);
            this.authService = new AuthService(userRepository, activityLog);

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
            this.matchQueue = new MatchQueue(lobby::seatMatchedPair, activityLog);
            try {
                this.httpApiServer = new HttpApiServer(httpPort, authService, sessionTokenStore, new LocalRoomCreator(lobby), activityLog);
            } catch (IOException e) {
                throw new IllegalStateException("Could not start HTTP API server on port " + httpPort, e);
            }
            this.gatewayNatsConnection = null;
            this.gatewayDispatcher = null;
            this.roomDirectory = null;
        }
    }

    public void handleOpen(WebSocket conn) {
        activityLog.log("connection opened: " + conn.getRemoteSocketAddress());
        if (distributed) {
            String connectionId = UUID.randomUUID().toString();
            connectionIds.put(conn, connectionId);
            gatewayDispatcher.subscribe("conn." + connectionId + ".out", msg -> {
                if (conn.isOpen()) conn.send(new String(msg.getData(), StandardCharsets.UTF_8));
            });
            gatewayDispatcher.subscribe("conn." + connectionId + ".shard", msg ->
                    shardOf.put(conn, new String(msg.getData(), StandardCharsets.UTF_8)));
        }
        // Attach happens on the connection's first text message - see handleMessage.
    }

    // Routes by connection state: not authenticated -> handleAttach; authenticated + in a session -> session.handleCommand; authenticated, no session yet -> handleLobbyCommand.
    public void handleMessage(WebSocket conn, String message) {
        // No per-message entry/exit log here on purpose - this fires on every
        // click/jump during a live game, not just once per connection; the
        // try/catch is still worth it as the one true entry point for every
        // inbound message, regardless of which stage (auth/lobby/game) it's
        // headed for - session.handleCommand has its own inner try/catch too
        // (defense in depth), but auth/lobby dispatch below had none at all.
        try {
            doHandleMessage(conn, message);
        } catch (RuntimeException e) {
            activityLog.log("connection " + conn.getRemoteSocketAddress() + ": handleMessage(\"" + message + "\") failed unexpectedly: " + e);
            conn.send(Protocol.ERROR + "|INTERNAL_ERROR");
        }
    }

    private void doHandleMessage(WebSocket conn, String message) {
        if (!usernames.containsKey(conn)) {
            handleAttach(conn, message);
            return;
        }

        if (distributed) {
            routeDistributedCommand(conn, message);
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

    /**
     * Distributed topology only - decides which of possibly several Game
     * Server Shards should receive this post-attach message (see class
     * Javadoc for the full reasoning): "play" always goes to the
     * Matchmaker; "join_room &lt;code&gt;" is resolved fresh via {@link
     * #roomDirectory} every time (never {@link #shardOf} - a stale shard
     * cached from a previous, now-finished game on this same connection
     * must never be reused for a request to join a brand-new room); every
     * other command (click/jump/rematch) relies entirely on {@link
     * #shardOf}, populated once some shard has announced ownership of this
     * connectionId (see {@link #handleOpen}'s {@code "conn.<id>.shard"}
     * subscription).
     */
    private void routeDistributedCommand(WebSocket conn, String message) {
        String[] parts = message.trim().split("\\s+", 2);
        String verb = parts.length > 0 ? parts[0] : "";
        String envelope = new GatewayCommandEnvelope(connectionIds.get(conn), usernames.get(conn), ratings.get(conn), message).encode();

        if (verb.equals(Protocol.PLAY)) {
            gatewayNatsConnection.publish("matchmaker.play", envelope.getBytes(StandardCharsets.UTF_8));
            return;
        }

        String shardId;
        if (verb.equals(Protocol.JOIN_ROOM)) {
            String code = parts.length > 1 ? parts[1].trim().toUpperCase() : "";
            if (code.isEmpty()) {
                conn.send(Protocol.ERROR + "|expected 'join_room <code>'");
                return;
            }
            Optional<String> resolved = roomDirectory.shardFor(code);
            if (resolved.isEmpty()) {
                conn.send(Protocol.ERROR + "|unknown room code");
                return;
            }
            shardId = resolved.get();
            shardOf.put(conn, shardId);
        } else {
            shardId = shardOf.get(conn);
            if (shardId == null) {
                conn.send(Protocol.ERROR + "|not currently in a game");
                return;
            }
        }

        gatewayNatsConnection.publish("shard." + shardId + ".command", envelope.getBytes(StandardCharsets.UTF_8));
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

    // Dispatches "play" to the local MatchQueue and "join_room <code>" to Lobby (room *creation* is REST-only, see HttpApiServer/Lobby.createRoom) - embedded topology only, see doHandleMessage.
    private void handleLobbyCommand(WebSocket conn, OutboundConnection outbound, String message) {
        String username = usernames.get(conn);
        int rating = ratings.get(conn);
        String[] parts = message.trim().split("\\s+", 2);
        String command = parts.length > 0 ? parts[0] : "";

        if (command.equals(Protocol.PLAY)) {
            boolean matched = matchQueue.play(outbound, username, rating);
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

    public void handleClose(WebSocket conn, String reason) {
        activityLog.log("connection closed: " + conn.getRemoteSocketAddress() + " (" + reason + ")");
        usernames.remove(conn);
        ratings.remove(conn);

        if (distributed) {
            shardOf.remove(conn);
            String connectionId = connectionIds.remove(conn);
            if (connectionId != null) {
                gatewayNatsConnection.publish("gateway.disconnect", connectionId.getBytes(StandardCharsets.UTF_8));
                gatewayDispatcher.unsubscribe("conn." + connectionId + ".out");
                gatewayDispatcher.unsubscribe("conn." + connectionId + ".shard");
            }
        } else {
            OutboundConnection outbound = localConnections.remove(conn);
            if (outbound != null) {
                matchQueue.cancelQueued(outbound);
                lobby.handleDisconnect(outbound);
            }
        }
    }

    public void handleError(WebSocket conn, Exception ex) {
        ex.printStackTrace();
        activityLog.log("WebSocket error on " + (conn == null ? "unknown connection" : conn.getRemoteSocketAddress()) + ": " + ex);
    }

    public void handleStart(int port) {
        if (distributed) {
            System.out.println("Kung Fu Chess WS Gateway listening on port " + port + "; commands relayed to Game Server Shards over NATS");
        } else {
            httpApiServer.start();
            System.out.println("Kung Fu Chess server listening on port " + port
                    + " (WS) and " + httpApiServer.getPort() + " (HTTP)");
        }
    }

    /** Stops the HTTP server and any NATS connection (if any) - the WebSocket server itself is stopped separately by KungFuChessServerController. */
    public void stopAll() throws InterruptedException {
        if (distributed) {
            gatewayNatsConnection.close();
        } else {
            httpApiServer.stop();
            if (natsBus != null) natsBus.close();
        }
    }
}
