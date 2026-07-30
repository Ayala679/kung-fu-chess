package server.room;

import java.util.Optional;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.params.SetParams;

/**
 * Redis-backed {@link RoomDirectory} - what the Docker Compose deployment
 * uses (KFC_REDIS_URL set). One connection per call, matching
 * RedisReconnectRegistry/RedisSessionTokenStore/UserRepository's own
 * simplicity choice. Room codes are already constrained to {@code
 * [A-Z0-9]{6}} (see Lobby.reserveRoomCode) and shard ids are plain configured
 * strings, so no delimiter/encoding concerns.
 */
public class RedisRoomDirectory implements RoomDirectory {
    private static final long DEFAULT_TTL_SECONDS = 24 * 60 * 60L;
    private static final String KEY_PREFIX = "room-shard:";

    private final String redisUrl;
    private final long ttlSeconds;

    public RedisRoomDirectory(String redisUrl) {
        this(redisUrl, DEFAULT_TTL_SECONDS);
    }

    public RedisRoomDirectory(String redisUrl, long ttlSeconds) {
        this.redisUrl = redisUrl;
        this.ttlSeconds = ttlSeconds;
    }

    @Override
    public boolean recordIfAbsent(String roomCode, String shardId) {
        try (Jedis jedis = new Jedis(redisUrl)) {
            // SET ... NX EX is a single atomic Redis command - "set only if this key
            // doesn't already exist" - so two shards racing to claim the same
            // (extremely unlikely, but never actually prevented before this) room
            // code can never both succeed, unlike a separate GET-then-SET.
            String result = jedis.set(KEY_PREFIX + roomCode, shardId, SetParams.setParams().nx().ex(ttlSeconds));
            return "OK".equals(result);
        }
    }

    @Override
    public Optional<String> shardFor(String roomCode) {
        try (Jedis jedis = new Jedis(redisUrl)) {
            return Optional.ofNullable(jedis.get(KEY_PREFIX + roomCode));
        }
    }
}
