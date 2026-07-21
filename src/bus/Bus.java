package bus;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Minimal in-process publish/subscribe bus. Decouples the game session
 * (which only knows "something happened") from whoever cares about it
 * (network broadcast today; sound/animation triggers are future
 * subscribers on the same topics, once there's something concrete to play).
 *
 * Publishing is synchronous and on the caller's own thread - there is no
 * queue or async dispatch here, just fan-out to whatever is currently
 * subscribed.
 */
public class Bus {
    private final Map<String, List<Consumer<Object>>> subscribers = new ConcurrentHashMap<>();

    /** Subscribe to a topic; returns a handle that {@link #unsubscribe} can later remove. */
    public Consumer<Object> subscribe(String topic, Consumer<Object> handler) {
        subscribers.computeIfAbsent(topic, t -> new CopyOnWriteArrayList<>()).add(handler);
        return handler;
    }

    public void unsubscribe(String topic, Consumer<Object> handler) {
        List<Consumer<Object>> handlers = subscribers.get(topic);
        if (handlers != null) handlers.remove(handler);
    }

    /** Publish {@code payload} to every current subscriber of {@code topic}. */
    public void publish(String topic, Object payload) {
        List<Consumer<Object>> handlers = subscribers.get(topic);
        if (handlers == null) return;
        for (Consumer<Object> handler : handlers) {
            handler.accept(payload);
        }
    }
}
