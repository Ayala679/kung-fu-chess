package server.connection;

/**
 * Everything {@code Lobby}/{@code GameSession} actually need from "a
 * connection" - addressing identity plus a way to send it a message. Lets
 * both classes stay entirely unaware of whether they're running embedded
 * in the same process as the WebSocket (local/offline - {@link
 * LocalOutboundConnection}) or in a separate Game Server Shard process,
 * reached only over NATS ({@link RemoteOutboundConnection}) - see
 * Server_Design.md's "Step 5".
 */
public interface OutboundConnection {
    void send(String message);

    boolean isOpen();

    /** A stable identity for this connection, unique within its process - see {@link server.MatchmakingQueueStore} for the one caller that needs it (a Redis-backed queue can only persist this id, never the live connection itself). */
    String id();
}
