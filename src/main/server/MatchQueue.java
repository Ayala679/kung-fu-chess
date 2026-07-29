package server;

import java.util.ArrayList;
import java.util.List;
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
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "matchqueue-timeout");
        t.setDaemon(true);
        return t;
    });

    private final List<Waiting> queue = new ArrayList<>();

    public MatchQueue(MatchFoundListener listener, ActivityLog activityLog) {
        this(listener, activityLog, DEFAULT_TIMEOUT_MILLIS);
    }

    /** Same as the 2-arg constructor, with the "give up waiting" timeout given explicitly (milliseconds) instead of the default 60 seconds - so tests can use a short delay instead of actually waiting a minute. */
    public MatchQueue(MatchFoundListener listener, ActivityLog activityLog, long timeoutMillis) {
        this.listener = listener;
        this.activityLog = activityLog;
        this.timeoutMillis = timeoutMillis;
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
        for (int i = 0; i < queue.size(); i++) {
            Waiting candidate = queue.get(i);
            if (Math.abs(candidate.rating - rating) <= MATCHMAKING_ELO_RANGE) {
                queue.remove(i);
                candidate.timeoutTask.cancel(false);
                activityLog.log("quick-match: " + candidate.username + " vs " + username);
                listener.onMatchFound(candidate.connection, candidate.username, candidate.rating, connection, username, rating);
                return true;
            }
        }
        Waiting waiting = new Waiting(connection, username, rating);
        waiting.timeoutTask = scheduler.schedule(() -> handleTimeout(waiting), timeoutMillis, TimeUnit.MILLISECONDS);
        queue.add(waiting);
        activityLog.log(username + " (" + rating + ") queued for quick-match");
        return false;
    }

    /** No opponent showed up within the timeout - drop the entry and tell the client clearly, instead of leaving them waiting forever. */
    private synchronized void handleTimeout(Waiting waiting) {
        if (!queue.remove(waiting)) return; // already matched (or already cancelled) in the meantime
        activityLog.log(waiting.username + " quick-match timed out after " + timeoutMillis + "ms");
        if (waiting.connection.isOpen()) {
            waiting.connection.send(Protocol.ERROR + "|no opponent found within " + (timeoutMillis / 1000) + " seconds - try again");
        }
    }

    /** Removes a still-queued (not yet matched) connection - safe to call even if it was never queued. */
    public synchronized void cancelQueued(OutboundConnection connection) {
        queue.removeIf(w -> {
            if (w.connection != connection) return false;
            w.timeoutTask.cancel(false);
            return true;
        });
    }

    private static final class Waiting {
        final OutboundConnection connection;
        final String username;
        final int rating;
        ScheduledFuture<?> timeoutTask;

        Waiting(OutboundConnection connection, String username, int rating) {
            this.connection = connection;
            this.username = username;
            this.rating = rating;
        }
    }
}
