package server.service;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.nats.client.Connection;

import logging.ActivityLog;
import server.GameAllocatorClient;
import server.MatchQueue;
import server.Protocol;
import server.SeatPairEnvelope;
import server.connection.OutboundConnection;
import server.connection.RemoteOutboundConnection;

/**
 * All the decision logic behind the Matchmaker process: owns {@link
 * MatchQueue} (the ELO-ranged quick-match pairing algorithm) and the
 * {@code connectionId -> RemoteOutboundConnection} cache. Kept separate
 * from {@code server.controller.MatchmakerController} so the NATS subscription/decoding
 * wiring never has to know how a "play"/disconnect request is actually
 * handled. Needs the shared {@code Connection} itself (not just {@link
 * MatchQueue}) because handling these requests has real side effects that
 * go out over NATS: sending {@code WAITING} back to a queued player, asking
 * {@code server.controller.GameAllocatorController} (via {@link GameAllocatorClient})
 * which shard a matched pair's room should go to, and publishing the
 * {@link SeatPairEnvelope} to that shard once found (see "Step 6a" in
 * Server_Design.md).
 */
public class MatchmakerService {
    private final Connection natsConnection;
    private final MatchQueue matchQueue;
    private final Map<String, RemoteOutboundConnection> connections = new ConcurrentHashMap<>();

    public MatchmakerService(Connection natsConnection, ActivityLog activityLog) {
        this.natsConnection = natsConnection;
        this.matchQueue = new MatchQueue(this::onMatchFound, activityLog);
    }

    private RemoteOutboundConnection connectionFor(String connectionId) {
        return connections.computeIfAbsent(connectionId, id -> new RemoteOutboundConnection(id, natsConnection));
    }

    /** "play" - queues or matches, sending WAITING itself if not matched (see MatchQueue's own contract). */
    public void handlePlay(String connectionId, String username, int rating) {
        OutboundConnection connection = connectionFor(connectionId);
        boolean matched = matchQueue.play(connection, username, rating);
        if (!matched) connection.send(Protocol.WAITING);
    }

    private void onMatchFound(OutboundConnection connectionA, String usernameA, int ratingA,
                               OutboundConnection connectionB, String usernameB, int ratingB) {
        String connectionIdA = ((RemoteOutboundConnection) connectionA).getConnectionId();
        String connectionIdB = ((RemoteOutboundConnection) connectionB).getConnectionId();
        String shardId = GameAllocatorClient.assignShard(natsConnection);
        String encoded = new SeatPairEnvelope(connectionIdA, usernameA, ratingA, connectionIdB, usernameB, ratingB).encode();
        natsConnection.publish("shard." + shardId + ".seat_pair", encoded.getBytes(StandardCharsets.UTF_8));
    }

    /** payload is just the connectionId - mirrors GameServerShardService's own handleDisconnect. */
    public void handleDisconnect(String connectionId) {
        RemoteOutboundConnection connection = connections.remove(connectionId);
        if (connection == null) return;
        connection.markClosed();
        matchQueue.cancelQueued(connection);
    }
}
