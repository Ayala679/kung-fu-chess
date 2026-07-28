package bus;

import java.util.function.Consumer;

/**
 * Publish/subscribe abstraction. Decouples the game session (which only
 * knows "something happened") from whoever cares about it (network
 * broadcast today; sound/animation triggers are future subscribers on the
 * same topics, once there's something concrete to play).
 *
 * Payloads are plain strings, not arbitrary Java objects - a real
 * cross-process transport (see NatsBus) can only carry bytes/text, so an
 * in-process subscriber and a NATS subscriber see exactly the same shape.
 * {@link InMemoryBus} is what every unit test uses (no external service
 * needed, exactly like a bare SQLite path for UserRepository); {@link
 * NatsBus} is what the Docker Compose deployment uses, selected by
 * KungFuChessServer based on whether KFC_NATS_URL is set.
 */
public interface Bus {
    /** Subscribe to a topic; returns a handle that {@link #unsubscribe} can later remove. */
    Consumer<String> subscribe(String topic, Consumer<String> handler);

    void unsubscribe(String topic, Consumer<String> handler);

    /** Publish {@code payload} to every current subscriber of {@code topic}. */
    void publish(String topic, String payload);
}
