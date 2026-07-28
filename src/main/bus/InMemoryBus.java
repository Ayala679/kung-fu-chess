package bus;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Minimal in-process {@link Bus} - what every unit test uses, and the
 * default when KFC_NATS_URL isn't set (local/offline runs). Publishing is
 * synchronous and on the caller's own thread - there is no queue or async
 * dispatch here, just fan-out to whatever is currently subscribed.
 */
public class InMemoryBus implements Bus {
    private final Map<String, List<Consumer<String>>> subscribers = new ConcurrentHashMap<>();

    @Override
    public Consumer<String> subscribe(String topic, Consumer<String> handler) {
        subscribers.computeIfAbsent(topic, t -> new CopyOnWriteArrayList<>()).add(handler);
        return handler;
    }

    @Override
    public void unsubscribe(String topic, Consumer<String> handler) {
        List<Consumer<String>> handlers = subscribers.get(topic);
        if (handlers != null) handlers.remove(handler);
    }

    @Override
    public void publish(String topic, String payload) {
        List<Consumer<String>> handlers = subscribers.get(topic);
        if (handlers == null) return;
        for (Consumer<String> handler : handlers) {
            handler.accept(payload);
        }
    }
}
