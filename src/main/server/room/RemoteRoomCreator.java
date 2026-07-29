package server.room;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import io.nats.client.Connection;
import io.nats.client.Message;

import server.GameAllocatorClient;

/**
 * {@link RoomCreator} for the distributed topology - the API Gateway
 * (server.service.ApiGateway) is a separate process from the ones that own {@code
 * Lobby} (the Game Server Shards), so it can't call {@code
 * Lobby.createRoom} directly. First asks {@code server.controller.GameAllocatorController}
 * (via {@link GameAllocatorClient}) which shard should get this new room,
 * then sends a real NATS request to that shard's own {@code
 * "shard.<shardId>.create_room"} subject (see server.controller.GameServerShardController
 * for the other end) and blocks for the room code back.
 */
public class RemoteRoomCreator implements RoomCreator {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final Connection connection;

    public RemoteRoomCreator(Connection connection) {
        this.connection = connection;
    }

    /** The subject a specific shard's own {@code GameServerShardController} listens on. */
    public static String subjectFor(String shardId) {
        return "shard." + shardId + ".create_room";
    }

    @Override
    public String createRoom(String username) {
        String shardId = GameAllocatorClient.assignShard(connection);
        Message reply;
        try {
            reply = connection.request(subjectFor(shardId), username.getBytes(StandardCharsets.UTF_8), TIMEOUT);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RoomCreationException("Interrupted while waiting for the Game Server Shard to create a room", e);
        }
        if (reply == null) {
            throw new RoomCreationException("Game Server Shard [" + shardId + "] did not respond within " + TIMEOUT);
        }
        return new String(reply.getData(), StandardCharsets.UTF_8);
    }
}
