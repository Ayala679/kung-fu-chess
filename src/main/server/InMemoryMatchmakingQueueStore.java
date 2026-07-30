package server;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** In-process {@link MatchmakingQueueStore} - what every unit test uses, and the default when KFC_REDIS_URL isn't set. A LinkedHashMap preserves insertion order, matching the oldest-enqueued-first scan MatchQueue.play relies on. */
public class InMemoryMatchmakingQueueStore implements MatchmakingQueueStore {
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    @Override
    public synchronized void add(Entry entry) {
        entries.put(entry.connectionId(), entry);
    }

    @Override
    public synchronized void remove(String connectionId) {
        entries.remove(connectionId);
    }

    @Override
    public synchronized List<Entry> all() {
        return List.copyOf(entries.values());
    }
}
