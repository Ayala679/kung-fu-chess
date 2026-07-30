package server.main;

import java.util.Arrays;
import java.util.List;

import io.nats.client.Connection;
import io.nats.client.Nats;

import logging.ActivityLog;
import server.ShardPicker;
import server.controller.GameAllocatorController;
import server.service.GameAllocatorService;

/** Entry point for the Game Allocator process - reads env, wires GameAllocatorService/GameAllocatorController, starts. No logic of its own. */
public class GameAllocatorMain {
    private static final String DEFAULT_LOG_PATH = "logs/game-allocator.log";

    public static void main(String[] args) {
        String natsUrl = System.getenv("KFC_NATS_URL");
        if (natsUrl == null) throw new IllegalArgumentException("KFC_NATS_URL is required - server.GameAllocatorMain only runs in the distributed topology");
        String logPath = System.getenv().getOrDefault("KFC_LOG_PATH", DEFAULT_LOG_PATH);
        String shardIdsEnv = System.getenv("KFC_SHARD_IDS");
        if (shardIdsEnv == null || shardIdsEnv.isBlank()) {
            throw new IllegalArgumentException("KFC_SHARD_IDS is required (comma-separated list of shard ids)");
        }
        List<String> shardIds = Arrays.stream(shardIdsEnv.split(",")).map(String::trim).toList();

        Connection natsConnection;
        try {
            natsConnection = Nats.connect(natsUrl);
        } catch (Exception e) {
            throw new IllegalStateException("Could not connect to NATS at " + natsUrl, e);
        }

        ActivityLog activityLog = new ActivityLog(logPath);
        GameAllocatorService service = new GameAllocatorService(natsConnection, shardIds, new ShardPicker(shardIds), activityLog);
        GameAllocatorController controller = new GameAllocatorController(natsConnection, service, activityLog);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            controller.stop();
            try {
                natsConnection.close();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        System.out.println("Kung Fu Chess Game Allocator ready, load-aware over " + shardIds);
    }
}
