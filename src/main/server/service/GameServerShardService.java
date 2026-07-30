package server.service;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import io.nats.client.Connection;

import server.GameSession;
import server.GatewayCommandEnvelope;
import server.Lobby;
import server.Protocol;
import server.SeatPairEnvelope;
import server.connection.OutboundConnection;
import server.connection.RemoteOutboundConnection;

/**
 * All the decision logic behind one Game Server Shard: owns {@link Lobby}
 * (and, through it, every {@code GameSession}/{@code GameEngine}) and the
 * {@code connectionId -> RemoteOutboundConnection} cache. Kept separate
 * from {@link GameServerShardController} so the NATS subscription/decoding
 * wiring never has to know how a command/seat_pair/reconnect/disconnect/
 * create_room request is actually handled. Needs the shared {@code
 * Connection} itself (not just {@link Lobby}) because handling a command
 * has real side effects that go out over NATS: forwarding "play" to the
 * Matchmaker, and announcing shard ownership of a connectionId back to the
 * WS Gateway (see "Step 6a" in Server_Design.md).
 */
public class GameServerShardService {
    // "gateway.disconnect" is a broadcast every shard receives for every
    // connection in the whole cluster (see this class's own Javadoc), not
    // just ones this shard actually owns - so handleDisconnect (below) can't
    // simply forget an unrecognized connectionId the way it briefly used to,
    // without reintroducing the "ghost seat" race described there. Evicting
    // a closed, cluster-wide-irrelevant entry after a short delay instead of
    // either extreme (forget immediately / keep forever) bounds this cache's
    // size without reopening that race - a connectionId is never reused
    // (KungFuChessServerService.handleOpen mints a fresh random one per
    // connection), so nothing legitimate can ever need this exact entry back
    // once it's old enough to evict.
    private static final long CLOSED_CONNECTION_RETENTION_MILLIS = 60_000L;

    private final Connection natsConnection;
    private final Lobby lobby;
    private final String shardId;
    private final Map<String, RemoteOutboundConnection> connections = new ConcurrentHashMap<>();
    private final ScheduledExecutorService cleanupScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "shard-connection-cleanup");
        t.setDaemon(true);
        return t;
    });

    public GameServerShardService(Connection natsConnection, Lobby lobby, String shardId) {
        this.natsConnection = natsConnection;
        this.lobby = lobby;
        this.shardId = shardId;
    }

    private RemoteOutboundConnection connectionFor(String connectionId) {
        return connections.computeIfAbsent(connectionId, id -> new RemoteOutboundConnection(id, natsConnection));
    }

    // Tells the WS Gateway this connectionId now belongs to this shard - see KungFuChessServerService's "conn.<id>.shard" subscription.
    private void announceOwnership(String connectionId) {
        natsConnection.publish("conn." + connectionId + ".shard", shardId.getBytes(StandardCharsets.UTF_8));
    }

    // Same routing decision as KungFuChessServerService's local-topology dispatch, keyed by connectionId instead of a live connection.
    public void handleCommand(GatewayCommandEnvelope envelope) {
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
        handleLobbyCommand(connection, envelope);
    }

    private static boolean isLobbyCommand(String message) {
        String verb = message.trim().split("\\s+", 2)[0];
        return verb.equals(Protocol.PLAY) || verb.equals(Protocol.JOIN_ROOM);
    }

    // "play" isn't handled here at all - it's not this Shard's job any more (see server.MatchmakerService) - the exact envelope this Shard already has is just relayed onward unchanged.
    private void handleLobbyCommand(OutboundConnection connection, GatewayCommandEnvelope envelope) {
        String[] parts = envelope.rawCommand().trim().split("\\s+", 2);
        String command = parts.length > 0 ? parts[0] : "";

        if (command.equals(Protocol.PLAY)) {
            natsConnection.publish("matchmaker.play", envelope.encode().getBytes(StandardCharsets.UTF_8));
            return;
        }

        if (command.equals(Protocol.JOIN_ROOM)) {
            if (parts.length < 2 || parts[1].trim().isEmpty()) {
                connection.send(Protocol.ERROR + "|expected 'join_room <code>'");
                return;
            }
            String code = parts[1].trim().toUpperCase();
            GameSession session = lobby.joinRoom(code, connection, envelope.username(), envelope.rating());
            if (session == null) {
                connection.send(Protocol.ERROR + "|unknown room code");
            } else {
                announceOwnership(envelope.connectionId());
            }
            return;
        }

        connection.send(Protocol.ERROR + "|expected 'play' or 'join_room <code>'");
    }

    // From server.MatchmakerService once it pairs two players - resolve/cache both connections (may be the first time this Shard has seen either connectionId) and seat them.
    public void handleSeatPair(SeatPairEnvelope envelope) {
        OutboundConnection connectionA = connectionFor(envelope.connectionIdA());
        OutboundConnection connectionB = connectionFor(envelope.connectionIdB());
        lobby.seatMatchedPair(connectionA, envelope.usernameA(), envelope.ratingA(),
                connectionB, envelope.usernameB(), envelope.ratingB());
        announceOwnership(envelope.connectionIdA());
        announceOwnership(envelope.connectionIdB());
    }

    // Broadcast to every shard (see GameServerShardController's class Javadoc: safe, harmless no-op on any shard that doesn't own this username's room); fire-and-forget from KungFuChessServerService.handleAttach, today's tryReconnect return value already ignored by that same caller.
    public void handleReconnect(String connectionId, String username) {
        if (lobby.tryReconnect(connectionFor(connectionId), username)) {
            announceOwnership(connectionId);
        }
    }

    /**
     * Mirrors KungFuChessServerService's own handleDisconnect for the local
     * topology. Deliberately does NOT remove connectionId from {@link
     * #connections} the way it briefly used to - "gateway.disconnect" is a
     * broadcast every shard receives (see this class's own Javadoc), so this
     * can easily be the very first time this specific shard has ever heard
     * of connectionId (e.g. it disconnected in the narrow window between
     * being matched by the Matchmaker and this shard actually processing the
     * resulting seat_pair). Forgetting the id entirely in that case used to
     * mean a *later* seat_pair/join_room would call connectionFor(id) and
     * create a brand-new RemoteOutboundConnection defaulting to open=true -
     * a "ghost" seat GameSession would treat as a live, connected opponent
     * forever, since the one real disconnect event for that id had already
     * come and gone unrecorded. Keeping (and marking closed) the cached
     * connection instead means any later connectionFor(id) call finds this
     * same, already-closed instance - see GameSession.join's own isOpen()
     * check, which now reacts to that immediately. The entry is evicted
     * again after {@link #CLOSED_CONNECTION_RETENTION_MILLIS} so this cache
     * doesn't grow forever across every disconnect in the whole cluster.
     */
    public void handleDisconnect(String connectionId) {
        RemoteOutboundConnection connection = connectionFor(connectionId);
        connection.markClosed();
        lobby.handleDisconnect(connection);
        cleanupScheduler.schedule(() -> connections.remove(connectionId, connection),
                CLOSED_CONNECTION_RETENTION_MILLIS, TimeUnit.MILLISECONDS);
    }

    /** REST "create room", relayed here already routed to this specific shard by GameAllocatorClient - see RemoteRoomCreator. */
    public String createRoom(String username) {
        return lobby.createRoom(username);
    }

    /** Answers this shard's own "shard.&lt;id&gt;.load" query (see GameServerShardController) - how many in-progress games it's currently hosting, so GameAllocatorService can pick the least-loaded shard instead of pure round-robin. */
    public int activeGameCount() {
        return lobby.activeGameCount();
    }

    /**
     * Answers this shard's own "shard.&lt;id&gt;.checkout" query (see
     * GameServerShardController) - asked by KungFuChessServerService before
     * it lets a connection that's currently associated with this shard
     * (KungFuChessServerService's own {@code shardOf} cache) send a fresh
     * "play"/"join_room", so this shard gets a say instead of the Gateway
     * silently re-routing the connection to a brand-new game while it's
     * still actively seated here - the exact guard the embedded topology
     * gets for free from {@code lobby.sessionOf(...)} being checked
     * in-process before "play"/"join_room" are ever dispatched.
     *
     * @return true if connectionId has no session on this shard at all, or
     * only a finished one (which is left as a side effect, exactly like
     * {@link #handleCommand} already does for an in-band lobby command
     * against a finished session) - false if it's still actively seated in
     * a game here, in which case the Gateway must refuse the request rather
     * than let the connection start a second, simultaneous game elsewhere.
     */
    public boolean checkoutForNewGame(String connectionId) {
        OutboundConnection connection = connectionFor(connectionId);
        GameSession session = lobby.sessionOf(connection);
        if (session == null) return true;
        if (!session.isGameOver()) return false;
        lobby.leaveFinishedSessionIfAny(connection);
        return true;
    }
}
