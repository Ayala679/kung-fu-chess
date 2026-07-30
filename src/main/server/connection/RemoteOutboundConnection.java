package server.connection;

import java.nio.charset.StandardCharsets;

import io.nats.client.Connection;

/**
 * {@link OutboundConnection} for the distributed topology - the Game
 * Server Shard never holds a real {@code WebSocket} (only the WS Gateway
 * process that accepted the TCP connection can), so {@code send} just
 * publishes to that one connection's own NATS subject; the Gateway is
 * already subscribed to it (see KungFuChessServerService) and relays verbatim to
 * the real socket. {@code isOpen} reflects the last disconnect
 * notification the Shard received for this connectionId (see
 * GameServerShardController) - not a live query, since the Shard has no direct way
 * to ask the Gateway's socket state.
 */
public class RemoteOutboundConnection implements OutboundConnection {
    private final String connectionId;
    private final Connection natsConnection;
    private volatile boolean open = true;

    public RemoteOutboundConnection(String connectionId, Connection natsConnection) {
        this.connectionId = connectionId;
        this.natsConnection = natsConnection;
    }

    public String getConnectionId() {
        return connectionId;
    }

    @Override
    public String id() {
        return connectionId;
    }

    @Override
    public void send(String message) {
        if (!open) return;
        natsConnection.publish("conn." + connectionId + ".out", message.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    /** Called by GameServerShardController once it receives a "gateway.disconnect" notification for this connectionId. */
    public void markClosed() {
        open = false;
    }
}
