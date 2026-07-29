package server.reconnect;

import java.util.Optional;

/**
 * Pure "username -&gt; room code" lookup used by {@link Lobby#tryReconnect}
 * to find which room a disconnected player was last seated in - split out
 * of what used to be a {@code Map<String, GameSession>} so this half (plain
 * data, no live connections or engine state) can move to Redis while the
 * actual {@code GameSession} objects stay in-process (see Server_Design.md
 * for why they have to, at least until there's more than one Game Server
 * Shard process).
 *
 * {@link InMemoryReconnectRegistry} is what every unit test uses; {@link
 * RedisReconnectRegistry} is what the Docker Compose deployment uses
 * (KFC_REDIS_URL set).
 */
public interface ReconnectRegistry {
    void record(String username, String roomCode);

    Optional<String> roomCodeFor(String username);
}
