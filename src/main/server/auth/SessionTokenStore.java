package server.auth;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bearer tokens issued by the REST login/register endpoints (server.HttpApiServer)
 * and consumed by the WebSocket "attach &lt;token&gt;" handshake and by the
 * REST "create room" endpoint - both just need to know who's calling, so one
 * token type is enough. Multi-use (not one-shot) within its TTL, since
 * "attach" and "create room" can happen in either order. No background
 * sweep thread - expired entries are pruned lazily on issue(), matching the
 * project's other lightweight, no-framework helpers (logging.ActivityLog).
 */
public class SessionTokenStore {
    private static final long DEFAULT_TTL_MILLIS = 10 * 60 * 1000L;

    private final long ttlMillis;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Entry> tokens = new ConcurrentHashMap<>();

    public SessionTokenStore() {
        this(DEFAULT_TTL_MILLIS);
    }

    public SessionTokenStore(long ttlMillis) {
        this.ttlMillis = ttlMillis;
    }

    /** Identity carried by a token: the authenticated username and their rating at issue time. */
    public record Principal(String username, int rating) {}

    public String issue(String username, int rating) {
        prune();
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        tokens.put(token, new Entry(new Principal(username, rating), System.currentTimeMillis() + ttlMillis));
        return token;
    }

    /** Looks up a token without consuming it - absent if unknown or expired. */
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
