package server;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import logging.ActivityLog;
import server.connection.OutboundConnection;

/**
 * The Matchmaker's actual algorithm: an ELO-ranged quick-match queue,
 * topology-agnostic (built on {@link OutboundConnection}, same as {@link
 * Lobby}/{@link GameSession}) so it's directly unit-testable and reusable
 * from both the embedded topology ({@code KungFuChessServerService} owns one
 * in-process) and the distributed one ({@code server.MatchmakerController}, a
 * standalone process, owns one wired to NATS). Deliberately knows nothing
 * about rooms/sessions/GameEngine - on a match it only calls back into a
 * {@link MatchFoundListener}, which is the caller's job to fulfil (in
 * practice, always {@code Lobby.seatMatchedPair}, whether called directly
 * in-process or reached over NATS via {@code server.MatchmakerController}/{@code
 * server.GameServerShardController}).
 *
 * The waiting list itself is split in two: the durable, serializable fields
 * (connectionId/username/rating/enqueue time) live in a pluggable {@link
 * MatchmakingQueueStore} (in-memory by default, Redis when the distributed
 * topology configures one - see the 4-arg constructor), while the live
 * {@link OutboundConnection} objects and this instance's own {@link
 * ScheduledFuture} timeout tasks always stay local to this JVM - neither can
 * be serialized, and (see Server_Design.md's "Not done yet") only a single
 * Matchmaker instance is ever active at a time, so nothing needs to hand
 * either off to another process. If {@link #play} finds a store entry whose
 * connectionId this instance never registered (most commonly: a Redis-backed
 * entry left behind by a previous run of this same process, before a
 * restart), it's treated as stale and pruned from the store on the spot
 * rather than ever being matched against.
 */
public class MatchQueue {
    private static final int MATCHMAKING_ELO_RANGE = 100;
    private static final long DEFAULT_TIMEOUT_MILLIS = 60_000L;

    /** Called once two players are paired - never called with the same connection on both sides. */
    public interface MatchFoundListener {
        void onMatchFound(OutboundConnection connectionA, String usernameA, int ratingA,
                           OutboundConnection connectionB, String usernameB, int ratingB);
    }

    private final MatchFoundListener listener;
    private final ActivityLog activityLog;
    private final long timeoutMillis;
    private final MatchmakingQueueStore store;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "matchqueue-timeout");
        t.setDaemon(true);
        return t;
    });

    private final Map<String, OutboundConnection> liveConnections = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> timeoutTasks = new ConcurrentHashMap<>();

    public MatchQueue(MatchFoundListener listener, ActivityLog activityLog) {
        this(listener, activityLog, DEFAULT_TIMEOUT_MILLIS);
    }

    /** Same as the 2-arg constructor, with the "give up waiting" timeout given explicitly (milliseconds) instead of the default 60 seconds - so tests can use a short delay instead of actually waiting a minute. */
    public MatchQueue(MatchFoundListener listener, ActivityLog activityLog, long timeoutMillis) {
        this(listener, activityLog, timeoutMillis, new InMemoryMatchmakingQueueStore());
    }

    /** Same as the 3-arg constructor, with the waiting-list store given explicitly - see the class Javadoc for why only this half (never the live connections/timeout tasks) is ever pluggable. */
    public MatchQueue(MatchFoundListener listener, ActivityLog activityLog, long timeoutMillis, MatchmakingQueueStore store) {
        this.listener = listener;
        this.activityLog = activityLog;
        this.timeoutMillis = timeoutMillis;
        this.store = store;
    }

    /**
     * "play": pairs with any currently-waiting player within +-100 ELO and
     * returns true ({@link MatchFoundListener#onMatchFound} is called
     * synchronously before this returns). If no one waiting is close
     * enough, this connection joins the queue and false is returned
     * (caller sends WAITING); if no opponent arrives within {@link
     * #timeoutMillis}, it's removed from the queue and sent an explicit
     * ERROR instead of waiting forever.
     */
    public synchronized boolean play(OutboundConnection connection, String username, int rating) {
        String id = connection.id();
        liveConnections.put(id, connection);

        for (MatchmakingQueueStore.Entry candidate : store.all()) {
            OutboundConnection candidateConnection = liveConnections.get(candidate.connectionId());
            if (candidateConnection == null) {
                // Stale - this instance never registered that connectionId locally (e.g. a Redis entry surviving a restart). Self-heal and keep scanning.
                store.remove(candidate.connectionId());
                cancelTimeoutTask(candidate.connectionId());
                continue;
            }
            if (Math.abs(candidate.rating() - rating) <= MATCHMAKING_ELO_RANGE) {
                store.remove(candidate.connectionId());
                cancelTimeoutTask(candidate.connectionId());
                liveConnections.remove(candidate.connectionId());
                liveConnections.remove(id);
                activityLog.log("quick-match: " + candidate.username() + " vs " + username);
                listener.onMatchFound(candidateConnection, candidate.username(), candidate.rating(), connection, username, rating);
                return true;
            }
        }

        store.add(new MatchmakingQueueStore.Entry(id, username, rating, System.currentTimeMillis()));
        ScheduledFuture<?> task = scheduler.schedule(() -> handleTimeout(id), timeoutMillis, TimeUnit.MILLISECONDS);
        timeoutTasks.put(id, task);
        activityLog.log(username + " (" + rating + ") queued for quick-match");
        return false;
    }

    /** No opponent showed up within the timeout - drop the entry and tell the client clearly, instead of leaving them waiting forever. */
    private synchronized void handleTimeout(String connectionId) {
        if (timeoutTasks.remove(connectionId) == null) return; // already matched (or already cancelled) in the meantime
        store.remove(connectionId);
        OutboundConnection connection = liveConnections.remove(connectionId);
        activityLog.log(connectionId + " quick-match timed out after " + timeoutMillis + "ms");
        if (connection != null && connection.isOpen()) {
            connection.send(Protocol.ERROR + "|no opponent found within " + (timeoutMillis / 1000) + " seconds - try again");
        }
    }

    /** Removes a still-queued (not yet matched) connection - safe to call even if it was never queued. */
    public synchronized void cancelQueued(OutboundConnection connection) {
        String id = connection.id();
        store.remove(id);
        cancelTimeoutTask(id);
        liveConnections.remove(id);
    }

    private void cancelTimeoutTask(String connectionId) {
        ScheduledFuture<?> task = timeoutTasks.remove(connectionId);
        if (task != null) task.cancel(false);
    }
}
