package server.reconnect;

import java.util.Optional;

import redis.clients.jedis.Jedis;

/**
 * Redis-backed {@link ReconnectRegistry} - what the Docker Compose
 * deployment uses (KFC_REDIS_URL set). One connection per call, matching
 * RedisSessionTokenStore/UserRepository's own simplicity choice. Room codes
 * are already constrained to {@code [A-Z0-9]{6}} (see Lobby.reserveRoomCode),
 * so a plain string value is safe - no delimiter/encoding concerns.
 */
public class RedisReconnectRegistry implements ReconnectRegistry {
    private static final long DEFAULT_TTL_SECONDS = 24 * 60 * 60L;
    private static final String KEY_PREFIX = "reconnect:";

    private final String redisUrl;
    private final long ttlSeconds;

    public RedisReconnectRegistry(String redisUrl) {
        this(redisUrl, DEFAULT_TTL_SECONDS);
    }

    public RedisReconnectRegistry(String redisUrl, long ttlSeconds) {
        this.redisUrl = redisUrl;
        this.ttlSeconds = ttlSeconds;
    }

    @Override
    public void record(String username, String roomCode) {
        try (Jedis jedis = new Jedis(redisUrl)) {
            jedis.setex(KEY_PREFIX + username, ttlSeconds, roomCode);
        }
    }

    @Override
    public Optional<String> roomCodeFor(String username) {
        try (Jedis jedis = new Jedis(redisUrl)) {
            return Optional.ofNullable(jedis.get(KEY_PREFIX + username));
        }
    }
}
