package server.auth;

import java.util.Optional;

/**
 * Bearer tokens issued by the REST login/register endpoints (server.HttpApiServer)
 * and consumed by the WebSocket "attach &lt;token&gt;" handshake and by the
 * REST "create room" endpoint - both just need to know who's calling, so one
 * token type is enough. Multi-use (not one-shot) within its TTL, since
 * "attach" and "create room" can happen in either order.
 *
 * {@link InMemorySessionTokenStore} is what every unit test uses (no
 * external service needed); {@link RedisSessionTokenStore} is what the
 * Docker Compose deployment uses (KFC_REDIS_URL set) - a real, shared store
 * means a token survives a server restart instead of being lost with it.
 */
public interface SessionTokenStore {
    /** Identity carried by a token: the authenticated username and their rating at issue time. */
    record Principal(String username, int rating) {}

    String issue(String username, int rating);

    /** Looks up a token without consuming it - absent if unknown or expired. */
    Optional<Principal> validate(String token);
}
