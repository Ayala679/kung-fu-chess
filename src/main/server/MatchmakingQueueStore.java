package server;

import java.util.List;

/**
 * The durable, serializable half of {@link MatchQueue}'s waiting list - just
 * the fields that can actually survive leaving this JVM (a connectionId, not
 * the live {@code OutboundConnection} itself, which a Redis-backed store can
 * never hold - see {@code server.connection.OutboundConnection#id()}).
 * {@link MatchQueue} keeps the live connection objects and timeout tasks
 * local always; only this half is pluggable, the same InMemoryXxx/RedisXxx
 * split already used by {@code server.reconnect.ReconnectRegistry}/{@code
 * server.room.RoomDirectory}.
 */
public interface MatchmakingQueueStore {
    /** One waiting player. Order matters - {@link #all()} returns these oldest-enqueued-first, matching MatchQueue's own scan order. */
    record Entry(String connectionId, String username, int rating, long enqueuedAtMillis) {}

    void add(Entry entry);

    void remove(String connectionId);

    /** Every currently-waiting entry, oldest first. */
    List<Entry> all();
}
