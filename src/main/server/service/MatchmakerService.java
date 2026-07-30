package server.service;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

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
    private static final long DEFAULT_TIMEOUT_MILLIS = 60_000L;
    // See GameServerShardService's own CLOSED_CONNECTION_RETENTION_MILLIS for why this
    // is neither 0 (forget immediately) nor infinite (unbounded growth) - same reasoning
    // applies here: "gateway.disconnect" is a cluster-wide broadcast this service also
    // subscribes to, for connectionIds it may never have queued at all.
    private static final long CLOSED_CONNECTION_RETENTION_MILLIS = 60_000L;

    private final Connection natsConnection;
    private final MatchQueue matchQueue;
    private final Map<String, RemoteOutboundConnection> connections = new ConcurrentHashMap<>();
    private final ScheduledExecutorService cleanupScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "matchmaker-connection-cleanup");
        t.setDaemon(true);
        return t;
    });

    public MatchmakerService(Connection natsConnection, ActivityLog activityLog) {
        this(natsConnection, activityLog, null);
    }

    /**
     * Same as the 2-arg constructor, with {@code redisUrl} given explicitly:
     * {@code null} keeps the queue in-memory (lost on restart - fine for
     * local/offline runs and every unit test), a real URL backs it with
     * {@link server.RedisMatchmakingQueueStore} instead (the Docker Compose
     * deployment) so a waiting player survives this process restarting - see
     * MatchQueue's own class Javadoc for exactly what that does and doesn't
     * cover.
     */
    public MatchmakerService(Connection natsConnection, ActivityLog activityLog, String redisUrl) {
        this.natsConnection = natsConnection;
        server.MatchmakingQueueStore store = redisUrl == null
                ? new server.InMemoryMatchmakingQueueStore()
                : new server.RedisMatchmakingQueueStore(redisUrl);
        this.matchQueue = new MatchQueue(this::onMatchFound, activityLog, DEFAULT_TIMEOUT_MILLIS, store);
    }

    private RemoteOutboundConnection connectionFor(String connectionId) {
        return connections.computeIfAbsent(connectionId, id -> new RemoteOutboundConnection(id, natsConnection));
    }

    /**
     * "play" - queues or matches, sending WAITING itself if not matched (see
     * MatchQueue's own contract). If this connectionId was already marked
     * closed (a disconnect that raced ahead of this very "play" message -
     * see {@link #handleDisconnect}), it's refused outright instead of being
     * queued/matched: a dead connection queued here would otherwise go on to
     * be matched with a real player and seated as a permanently unresponsive
     * "ghost" opponent on whichever Game Server Shard the match lands on.
     */
    public void handlePlay(String connectionId, String username, int rating) {
        OutboundConnection connection = connectionFor(connectionId);
        if (!connection.isOpen()) return;
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

    /**
     * payload is just the connectionId - mirrors GameServerShardService's
     * own handleDisconnect, including keeping (not forgetting) the cached,
     * now-closed connection so a "play" that was already in flight for this
     * same connectionId (see {@link #handlePlay}) reliably sees it as closed
     * instead of racing a fresh, wrongly-open instance - evicted again after
     * {@link #CLOSED_CONNECTION_RETENTION_MILLIS} so this cache doesn't grow
     * forever across every disconnect in the whole cluster.
     */
    public void handleDisconnect(String connectionId) {
        RemoteOutboundConnection connection = connectionFor(connectionId);
        connection.markClosed();
        matchQueue.cancelQueued(connection);
        cleanupScheduler.schedule(() -> connections.remove(connectionId, connection),
                CLOSED_CONNECTION_RETENTION_MILLIS, TimeUnit.MILLISECONDS);
    }
}
