package server.room;

import java.util.Optional;

/**
 * "Which shard owns this room?" - {@code roomCode -> shardId}. Written by
 * whichever shard's {@code Lobby} actually mints a room code ({@code
 * Lobby.createRoom}/{@code seatMatchedPair}), read by the WS Gateway
 * ({@code KungFuChessServerService}) to route a {@code join_room <code>} command
 * to the one shard that actually owns it. Same dual-mode shape as {@link
 * ReconnectRegistry} - {@link InMemoryRoomDirectory} for local/offline runs
 * and every unit test, {@link RedisRoomDirectory} for the distributed
 * (Docker Compose) topology.
 */
public interface RoomDirectory {
    /**
     * Atomically claims roomCode for shardId - returns false if roomCode is
     * already claimed (by this shard or any other) instead of silently
     * overwriting whoever claimed it first, so a caller minting a brand-new
     * code (see Lobby.reserveRoomCode) can detect a genuine cluster-wide
     * collision and simply try a different code.
     */
    boolean recordIfAbsent(String roomCode, String shardId);

    Optional<String> shardFor(String roomCode);
}
