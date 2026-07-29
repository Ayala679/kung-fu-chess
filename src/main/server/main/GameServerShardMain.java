package server.main;

import java.io.IOException;

import io.nats.client.Connection;

import bus.NatsBus;
import logging.ActivityLog;
import server.Lobby;
import server.auth.UserRepository;
import server.controller.GameServerShardController;
import server.reconnect.ReconnectRegistry;
import server.reconnect.RedisReconnectRegistry;
import server.room.RedisRoomDirectory;
import server.room.RoomDirectory;
import server.service.GameServerShardService;

/** Entry point for a Game Server Shard process - reads env, wires GameServerShardService/GameServerShardController, starts. No logic of its own. */
public class GameServerShardMain {
    private static final String DEFAULT_DB_PATH = "data/kungfuchess.db";
    private static final String DEFAULT_LOG_PATH = "logs/game-server-shard.log";
    private static final long DEFAULT_DISCONNECT_GRACE_MILLIS = 20_000L;

    public static void main(String[] args) {
        String dbUrlOrPath = System.getenv().getOrDefault("KFC_DB_URL", DEFAULT_DB_PATH);
        String logPath = System.getenv().getOrDefault("KFC_LOG_PATH", DEFAULT_LOG_PATH);
        String redisUrl = System.getenv("KFC_REDIS_URL");
        String natsUrl = System.getenv("KFC_NATS_URL");
        String shardId = System.getenv("KFC_SHARD_ID");
        if (redisUrl == null) throw new IllegalArgumentException("KFC_REDIS_URL is required - server.GameServerShardMain only runs in the distributed topology");
        if (natsUrl == null) throw new IllegalArgumentException("KFC_NATS_URL is required - server.GameServerShardMain only runs in the distributed topology");
        if (shardId == null || shardId.isBlank()) throw new IllegalArgumentException("KFC_SHARD_ID is required - server.GameServerShardMain only runs in the distributed topology");

        UserRepository userRepository = new UserRepository(dbUrlOrPath);
        ActivityLog activityLog = new ActivityLog(logPath);
        NatsBus bus;
        try {
            bus = new NatsBus(natsUrl);
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException("Could not connect to NATS at " + natsUrl, e);
        }
        Connection natsConnection = bus.rawConnection();
        ReconnectRegistry reconnectRegistry = new RedisReconnectRegistry(redisUrl);
        RoomDirectory roomDirectory = new RedisRoomDirectory(redisUrl);
        Lobby lobby = new Lobby(bus, userRepository, activityLog, reconnectRegistry, DEFAULT_DISCONNECT_GRACE_MILLIS, shardId, roomDirectory);

        GameServerShardService service = new GameServerShardService(natsConnection, lobby, shardId);
        GameServerShardController controller = new GameServerShardController(natsConnection, service, shardId, activityLog);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            controller.stop();
            try {
                bus.close();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        System.out.println("Kung Fu Chess Game Server Shard [" + shardId + "] ready, listening on NATS");
    }
}
