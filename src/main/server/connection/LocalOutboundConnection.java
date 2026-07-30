package server.connection;

import org.java_websocket.WebSocket;

/** {@link OutboundConnection} wrapping a real {@code WebSocket} - the local/offline topology, where {@code KungFuChessServerService} still embeds {@code Lobby}/{@code GameSession} directly. */
public class LocalOutboundConnection implements OutboundConnection {
    private final WebSocket socket;
    private final String id = java.util.UUID.randomUUID().toString();

    public LocalOutboundConnection(WebSocket socket) {
        this.socket = socket;
    }

    @Override
    public void send(String message) {
        if (socket.isOpen()) socket.send(message);
    }

    @Override
    public boolean isOpen() {
        return socket.isOpen();
    }

    // Synthetic - the local/embedded topology never persists this anywhere (see OutboundConnection.id()'s Javadoc), only needs a stable in-process identity.
    @Override
    public String id() {
        return id;
    }
}
