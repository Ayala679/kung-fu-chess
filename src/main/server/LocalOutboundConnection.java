package server;

import org.java_websocket.WebSocket;

/** {@link OutboundConnection} wrapping a real {@code WebSocket} - the local/offline topology, where {@code KungFuChessServer} still embeds {@code Lobby}/{@code GameSession} directly. */
public class LocalOutboundConnection implements OutboundConnection {
    private final WebSocket socket;

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
}
