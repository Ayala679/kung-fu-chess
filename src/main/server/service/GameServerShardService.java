package server.service;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
    private final Connection natsConnection;
    private final Lobby lobby;
    private final String shardId;
    private final Map<String, RemoteOutboundConnection> connections = new ConcurrentHashMap<>();

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

    // Mirrors KungFuChessServerService's own handleDisconnect for the local topology.
    public void handleDisconnect(String connectionId) {
        RemoteOutboundConnection connection = connections.remove(connectionId);
        if (connection == null) return;
        connection.markClosed();
        lobby.handleDisconnect(connection);
    }

    /** REST "create room", relayed here already routed to this specific shard by GameAllocatorClient - see RemoteRoomCreator. */
    public String createRoom(String username) {
        return lobby.createRoom(username);
    }

    /** Answers this shard's own "shard.&lt;id&gt;.load" query (see GameServerShardController) - how many in-progress games it's currently hosting, so GameAllocatorService can pick the least-loaded shard instead of pure round-robin. */
    public int activeGameCount() {
        return lobby.activeGameCount();
    }
}
