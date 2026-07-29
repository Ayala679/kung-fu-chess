package server.controller;

import java.net.InetSocketAddress;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import server.service.KungFuChessServerService;

/**
 * The WS Gateway's endpoints. Necessarily {@code extends WebSocketServer} -
 * a WebSocket connection's lifecycle callbacks are the one true entry point
 * for this process, forced by the library, so this class can't be freed of
 * that inheritance the way the NATS-based process controllers are free of
 * any particular base class. Holds no decision logic of its own beyond that
 * - every callback is a single delegation to {@link KungFuChessServerService}.
 */
public class KungFuChessServerController extends WebSocketServer {
    private final KungFuChessServerService service;

    public KungFuChessServerController(int port, KungFuChessServerService service) {
        super(new InetSocketAddress(port));
        this.service = service;
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        service.handleOpen(conn);
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        service.handleMessage(conn, message);
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        service.handleClose(conn, reason);
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        service.handleError(conn, ex);
    }

    @Override
    public void onStart() {
        service.handleStart(getPort());
    }

    /** Stops the WebSocket server itself plus everything KungFuChessServerService owns - used by KungFuChessServerMain's shutdown hook so Docker's SIGTERM (docker compose down) shuts down cleanly instead of being killed. */
    public void stopAll() throws InterruptedException {
        service.stopAll();
        stop();
    }
}
