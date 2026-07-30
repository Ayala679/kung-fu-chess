package server.room;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** In-process {@link RoomDirectory} - what every unit test uses, and the default when KFC_REDIS_URL isn't set. */
public class InMemoryRoomDirectory implements RoomDirectory {
    private final Map<String, String> shardIdByRoomCode = new ConcurrentHashMap<>();

    @Override
    public boolean recordIfAbsent(String roomCode, String shardId) {
        return shardIdByRoomCode.putIfAbsent(roomCode, shardId) == null;
    }

    @Override
    public Optional<String> shardFor(String roomCode) {
        return Optional.ofNullable(shardIdByRoomCode.get(roomCode));
    }
}
