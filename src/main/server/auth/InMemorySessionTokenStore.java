package server.auth;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-process {@link SessionTokenStore} - what every unit test uses, and the
 * default when KFC_REDIS_URL isn't set (local/offline runs). No background
 * sweep thread - expired entries are pruned lazily on issue(), matching the
 * project's other lightweight, no-framework helpers (logging.ActivityLog).
 */
public class InMemorySessionTokenStore implements SessionTokenStore {
    private static final long DEFAULT_TTL_MILLIS = 10 * 60 * 1000L;

    private final long ttlMillis;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Entry> tokens = new ConcurrentHashMap<>();

    public InMemorySessionTokenStore() {
        this(DEFAULT_TTL_MILLIS);
    }

    public InMemorySessionTokenStore(long ttlMillis) {
        this.ttlMillis = ttlMillis;
    }

    @Override
    public String issue(String username, int rating) {
        prune();
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        tokens.put(token, new Entry(new Principal(username, rating), System.currentTimeMillis() + ttlMillis));
        return token;
    }

    @Override
    public Optional<Principal> validate(String token) {
        Entry entry = tokens.get(token);
        if (entry == null || entry.expiresAt < System.currentTimeMillis()) {
            return Optional.empty();
        }
        return Optional.of(entry.principal);
    }

    private void prune() {
        long now = System.currentTimeMillis();
        tokens.values().removeIf(e -> e.expiresAt < now);
    }

    private static final class Entry {
        final Principal principal;
        final long expiresAt;

        Entry(Principal principal, long expiresAt) {
            this.principal = principal;
            this.expiresAt = expiresAt;
        }
    }
}
