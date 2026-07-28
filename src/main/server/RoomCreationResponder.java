package server;

import java.nio.charset.StandardCharsets;

import io.nats.client.Connection;
import io.nats.client.Dispatcher;

/**
 * The other end of {@link RemoteRoomCreator}: runs in the process that
 * actually owns {@link Lobby} (the Game Server Shard - see
 * KungFuChessServer), listening on {@link RemoteRoomCreator#SUBJECT} for
 * room-creation requests from a separate API Gateway process and replying
 * with the new room code. Only started in the distributed topology - see
 * KungFuChessServer's constructor.
 */
public class RoomCreationResponder {
    private final Dispatcher dispatcher;

    public RoomCreationResponder(Connection connection, Lobby lobby) {
        this.dispatcher = connection.createDispatcher(msg -> {
            String username = new String(msg.getData(), StandardCharsets.UTF_8);
            String code = lobby.createRoom(username);
            connection.publish(msg.getReplyTo(), code.getBytes(StandardCharsets.UTF_8));
        });
        dispatcher.subscribe(RemoteRoomCreator.SUBJECT);
    }

    public void close() {
        dispatcher.unsubscribe(RemoteRoomCreator.SUBJECT);
    }
}
