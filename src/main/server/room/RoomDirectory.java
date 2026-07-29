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
    void record(String roomCode, String shardId);

    Optional<String> shardFor(String roomCode);
}
