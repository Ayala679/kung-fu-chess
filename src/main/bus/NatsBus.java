package bus;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import io.nats.client.Connection;
import io.nats.client.Dispatcher;
import io.nats.client.Nats;
import io.nats.client.Subscription;

/**
 * A real, cross-process {@link Bus} backed by NATS - what the Docker
 * Compose deployment uses (KFC_NATS_URL set). One shared {@link Dispatcher}
 * per connection; {@link #subscribe} tracks the {@link Subscription} it
 * creates per handler so {@link #unsubscribe} can remove exactly that one,
 * matching {@link InMemoryBus}'s per-handler semantics.
 */
public class NatsBus implements Bus {
    private final Connection connection;
    private final Dispatcher dispatcher;
    private final Map<Consumer<String>, Subscription> subscriptions = new ConcurrentHashMap<>();

    public NatsBus(String url) throws IOException, InterruptedException {
        this.connection = Nats.connect(url);
        this.dispatcher = connection.createDispatcher();
    }

    @Override
    public Consumer<String> subscribe(String topic, Consumer<String> handler) {
        Subscription subscription = dispatcher.subscribe(topic, msg -> handler.accept(new String(msg.getData(), StandardCharsets.UTF_8)));
        subscriptions.put(handler, subscription);
        return handler;
    }

    @Override
    public void unsubscribe(String topic, Consumer<String> handler) {
        Subscription subscription = subscriptions.remove(handler);
        if (subscription != null) dispatcher.unsubscribe(subscription);
    }

    @Override
    public void publish(String topic, String payload) {
        connection.publish(topic, payload.getBytes(StandardCharsets.UTF_8));
    }

    public void close() throws InterruptedException {
        connection.close();
    }

    /** The underlying NATS connection this Bus already opened - reused by server.GameServerShardController for its own request-reply subscription instead of opening a second, redundant connection. */
    public Connection rawConnection() {
        return connection;
    }
}
