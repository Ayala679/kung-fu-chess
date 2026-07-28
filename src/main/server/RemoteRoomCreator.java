package server;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import io.nats.client.Connection;
import io.nats.client.Message;

/**
 * {@link RoomCreator} for the distributed topology - the API Gateway
 * (server.ApiGateway) is a separate process from the one that owns
 * {@code Lobby}/the Game Server Shard, so it can't call {@code
 * Lobby.createRoom} directly. Sends a real NATS request (see
 * server.RoomCreationResponder for the other end) and blocks for the room
 * code back.
 */
public class RemoteRoomCreator implements RoomCreator {
    public static final String SUBJECT = "lobby.create_room";
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final Connection connection;

    public RemoteRoomCreator(Connection connection) {
        this.connection = connection;
    }

    @Override
    public String createRoom(String username) {
        Message reply;
        try {
            reply = connection.request(SUBJECT, username.getBytes(StandardCharsets.UTF_8), TIMEOUT);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RoomCreationException("Interrupted while waiting for the Game Server Shard to create a room", e);
        }
        if (reply == null) {
            throw new RoomCreationException("Game Server Shard did not respond within " + TIMEOUT);
        }
        return new String(reply.getData(), StandardCharsets.UTF_8);
    }
}
