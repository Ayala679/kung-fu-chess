package server;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import redis.clients.jedis.Jedis;

/**
 * Redis-backed {@link MatchmakingQueueStore} - what the Docker Compose
 * deployment's Matchmaker uses (KFC_REDIS_URL set), so a waiting player
 * survives a Matchmaker process restart instead of just vanishing. One
 * connection per call, matching RedisReconnectRegistry/UserRepository's own
 * simplicity choice. Deliberately scoped to a single active Matchmaker
 * instance at a time - see Server_Design.md's "Not done yet": nothing here
 * coordinates two Matchmaker processes racing to match the same entry, since
 * only one instance is ever actually deployed today.
 *
 * Order is a Redis LIST of connectionIds ({@code matchqueue:order}); each
 * entry's actual fields live in its own Hash ({@code
 * matchqueue:entry:<connectionId>}) with a TTL slightly longer than the
 * matchmaking timeout - a safety net so an entry left behind by a crashed
 * process (one that never got to remove it itself) self-expires instead of
 * lingering forever. {@link #all()} treats a list id whose hash has already
 * expired as stale and prunes it from the order list on the spot.
 */
public class RedisMatchmakingQueueStore implements MatchmakingQueueStore {
    private static final String ORDER_KEY = "matchqueue:order";
    private static final String ENTRY_KEY_PREFIX = "matchqueue:entry:";
    private static final long DEFAULT_TTL_SECONDS = 90L; // comfortably longer than MatchQueue's default 60s matchmaking timeout

    private final String redisUrl;
    private final long ttlSeconds;

    public RedisMatchmakingQueueStore(String redisUrl) {
        this(redisUrl, DEFAULT_TTL_SECONDS);
    }

    /** Same as the 1-arg constructor, with the entry TTL given explicitly - so it can be kept comfortably above whatever timeout MatchQueue itself is configured with. */
    public RedisMatchmakingQueueStore(String redisUrl, long ttlSeconds) {
        this.redisUrl = redisUrl;
        this.ttlSeconds = ttlSeconds;
    }

    private static String entryKey(String connectionId) {
        return ENTRY_KEY_PREFIX + connectionId;
    }

    @Override
    public void add(Entry entry) {
        try (Jedis jedis = new Jedis(redisUrl)) {
            jedis.rpush(ORDER_KEY, entry.connectionId());
            Map<String, String> fields = new HashMap<>();
            fields.put("username", entry.username());
            fields.put("rating", Integer.toString(entry.rating()));
            fields.put("enqueuedAt", Long.toString(entry.enqueuedAtMillis()));
            String key = entryKey(entry.connectionId());
            jedis.hset(key, fields);
            jedis.expire(key, ttlSeconds);
        }
    }

    @Override
    public void remove(String connectionId) {
        try (Jedis jedis = new Jedis(redisUrl)) {
            jedis.lrem(ORDER_KEY, 0, connectionId);
            jedis.del(entryKey(connectionId));
        }
    }

    @Override
    public List<Entry> all() {
        try (Jedis jedis = new Jedis(redisUrl)) {
            List<String> ids = jedis.lrange(ORDER_KEY, 0, -1);
            List<Entry> result = new ArrayList<>();
            for (String connectionId : ids) {
                Map<String, String> fields = jedis.hgetAll(entryKey(connectionId));
                if (fields.isEmpty()) {
                    jedis.lrem(ORDER_KEY, 0, connectionId); // expired hash, stale list entry - prune it
                    continue;
                }
                result.add(new Entry(connectionId, fields.get("username"),
                        Integer.parseInt(fields.get("rating")), Long.parseLong(fields.get("enqueuedAt"))));
            }
            return result;
        }
    }
}
