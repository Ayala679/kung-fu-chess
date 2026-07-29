package server.main;

import java.util.Arrays;
import java.util.List;

import logging.ActivityLog;
import server.ShardPicker;
import server.controller.GameAllocatorController;
import server.service.GameAllocatorService;

/** Entry point for the Game Allocator process - reads env, wires GameAllocatorService/GameAllocatorController, starts. No logic of its own. */
public class GameAllocatorMain {
    private static final String DEFAULT_LOG_PATH = "logs/game-allocator.log";

    public static void main(String[] args) {
        String natsUrl = System.getenv("KFC_NATS_URL");
        String logPath = System.getenv().getOrDefault("KFC_LOG_PATH", DEFAULT_LOG_PATH);
        String shardIdsEnv = System.getenv("KFC_SHARD_IDS");
        if (shardIdsEnv == null || shardIdsEnv.isBlank()) {
            throw new IllegalArgumentException("KFC_SHARD_IDS is required (comma-separated list of shard ids)");
        }
        List<String> shardIds = Arrays.stream(shardIdsEnv.split(",")).map(String::trim).toList();

        GameAllocatorService service = new GameAllocatorService(new ShardPicker(shardIds), new ActivityLog(logPath));
        GameAllocatorController controller = new GameAllocatorController(natsUrl, service);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                controller.stop();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        System.out.println("Kung Fu Chess Game Allocator ready, round-robin over " + shardIds);
    }
}
