package server.service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.nats.client.Connection;
import io.nats.client.Message;

import logging.ActivityLog;
import server.ShardPicker;
import server.controller.GameServerShardController;

/**
 * All the decision logic behind the Game Allocator: which shard a brand-new
 * room should go to. Load-aware (see Server_Design.md's "Not done yet"):
 * asks every configured shard's own {@code "shard.&lt;id&gt;.load"} (see
 * {@link GameServerShardController#loadSubject}) how many in-progress games
 * it's currently hosting, and picks the least-loaded one. A shard that
 * doesn't answer within {@link #LOAD_QUERY_TIMEOUT} is treated as
 * unreachable, not crashed on - simply excluded from consideration for this
 * assignment. If literally none of them answer (every shard down, or this
 * whole load-query mechanism unavailable), falls back to {@link ShardPicker}
 * (the pure round-robin policy this class used to always use) so a room can
 * still be assigned - kept, not deleted, specifically to remain that safety
 * net. Kept separate from {@code server.controller.GameAllocatorController}
 * so the NATS request-reply wiring never has to know how the decision itself
 * is made.
 */
public class GameAllocatorService {
    private static final Duration LOAD_QUERY_TIMEOUT = Duration.ofMillis(300);

    private final Connection natsConnection;
    private final List<String> shardIds;
    private final ShardPicker fallbackPicker;
    private final ActivityLog activityLog;
    // Only used to run the blocking per-shard NATS requests concurrently
    // (see assignShard) - not for anything else, so a small fixed pool is
    // plenty even with many shards configured.
    private final ExecutorService loadQueryExecutor = Executors.newCachedThreadPool();

    public GameAllocatorService(Connection natsConnection, List<String> shardIds, ShardPicker fallbackPicker, ActivityLog activityLog) {
        this.natsConnection = natsConnection;
        this.shardIds = shardIds;
        this.fallbackPicker = fallbackPicker;
        this.activityLog = activityLog;
    }

    /**
     * Picks the least-loaded shard for a brand-new room, falling back to
     * round-robin if no shard's load could be determined. Every shard is
     * queried concurrently (not one after another) - each query is its own
     * blocking NATS request bounded by {@link #LOAD_QUERY_TIMEOUT}, so
     * querying N shards sequentially could take up to N * 300ms if some of
     * them are unresponsive; done concurrently, the whole call is bounded by
     * a single LOAD_QUERY_TIMEOUT regardless of how many shards are
     * configured.
     */
    public String assignShard() {
        Map<String, CompletableFuture<Integer>> loads = new ConcurrentHashMap<>();
        for (String shardId : shardIds) {
            loads.put(shardId, CompletableFuture.supplyAsync(() -> queryLoad(shardId), loadQueryExecutor));
        }

        String leastLoaded = null;
        int lowestLoad = Integer.MAX_VALUE;
        for (Map.Entry<String, CompletableFuture<Integer>> entry : loads.entrySet()) {
            Integer load = entry.getValue().join();
            if (load != null && load < lowestLoad) {
                lowestLoad = load;
                leastLoaded = entry.getKey();
            }
        }

        String shardId = leastLoaded != null ? leastLoaded : fallbackPicker.next();
        activityLog.log("assigned new room to " + shardId
                + (leastLoaded != null ? " (load " + lowestLoad + ")" : " (round-robin fallback - no shard load responses)"));
        return shardId;
    }

    // null if the shard didn't answer within LOAD_QUERY_TIMEOUT - "unreachable for this assignment", not a hard error.
    private Integer queryLoad(String shardId) {
        try {
            Message reply = natsConnection.request(GameServerShardController.loadSubject(shardId), new byte[0], LOAD_QUERY_TIMEOUT);
            if (reply == null) return null;
            return Integer.parseInt(new String(reply.getData(), StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
