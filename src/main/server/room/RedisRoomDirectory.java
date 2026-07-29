package server.room;

import java.util.Optional;

import redis.clients.jedis.Jedis;

/**
 * Redis-backed {@link RoomDirectory} - what the Docker Compose deployment
 * uses (KFC_REDIS_URL set). One connection per call, matching
 * RedisReconnectRegistry/RedisSessionTokenStore/UserRepository's own
 * simplicity choice. Room codes are already constrained to {@code
 * [A-Z0-9]{6}} (see Lobby.newRoomCode) and shard ids are plain configured
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
    public void record(String roomCode, String shardId) {
        try (Jedis jedis = new Jedis(redisUrl)) {
            jedis.setex(KEY_PREFIX + roomCode, ttlSeconds, shardId);
        }
    }

    @Override
    public Optional<String> shardFor(String roomCode) {
        try (Jedis jedis = new Jedis(redisUrl)) {
            return Optional.ofNullable(jedis.get(KEY_PREFIX + roomCode));
        }
    }
}
