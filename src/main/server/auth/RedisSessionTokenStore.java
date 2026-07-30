package server.auth;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;

import redis.clients.jedis.Jedis;

/**
 * Redis-backed {@link SessionTokenStore} - what the Docker Compose
 * deployment uses (KFC_REDIS_URL set). One connection per call rather than
 * a pool, matching UserRepository's own "at most a couple of players per
 * process" simplicity choice. Each token is a Redis Hash ({@code username}/
 * {@code rating} fields) rather than one delimited string value, so a
 * username can never collide with whatever delimiter a simpler encoding
 * would need. Redis's own TTL (via EXPIRE) replaces the in-memory store's
 * lazy prune-on-issue.
 */
public class RedisSessionTokenStore implements SessionTokenStore {
    private static final long DEFAULT_TTL_SECONDS = 10 * 60L;
    private static final String KEY_PREFIX = "token:";

    private final String redisUrl;
    private final long ttlSeconds;
    private final SecureRandom random = new SecureRandom();

    public RedisSessionTokenStore(String redisUrl) {
        this(redisUrl, DEFAULT_TTL_SECONDS);
    }

    public RedisSessionTokenStore(String redisUrl, long ttlSeconds) {
        this.redisUrl = redisUrl;
        this.ttlSeconds = ttlSeconds;
    }

    @Override
    public String issue(String username, int rating) {
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        try (Jedis jedis = new Jedis(redisUrl)) {
            String key = KEY_PREFIX + token;
            jedis.hset(key, Map.of("username", username, "rating", String.valueOf(rating)));
            jedis.expire(key, ttlSeconds);
        }
        return token;
    }

    @Override
    public Optional<Principal> validate(String token) {
        try (Jedis jedis = new Jedis(redisUrl)) {
            Map<String, String> fields = jedis.hgetAll(KEY_PREFIX + token);
            String username = fields.get("username");
            String rating = fields.get("rating");
            if (username == null || rating == null) return Optional.empty();
            return Optional.of(new Principal(username, Integer.parseInt(rating)));
        }
    }

    @Override
    public void revoke(String token) {
        try (Jedis jedis = new Jedis(redisUrl)) {
            jedis.del(KEY_PREFIX + token);
        }
    }
}
