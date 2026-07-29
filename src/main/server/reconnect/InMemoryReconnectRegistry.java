package server.reconnect;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** In-process {@link ReconnectRegistry} - what every unit test uses, and the default when KFC_REDIS_URL isn't set. */
public class InMemoryReconnectRegistry implements ReconnectRegistry {
    private final Map<String, String> roomCodeByUsername = new ConcurrentHashMap<>();

    @Override
    public void record(String username, String roomCode) {
        roomCodeByUsername.put(username, roomCode);
    }

    @Override
    public Optional<String> roomCodeFor(String username) {
        return Optional.ofNullable(roomCodeByUsername.get(username));
    }
}
