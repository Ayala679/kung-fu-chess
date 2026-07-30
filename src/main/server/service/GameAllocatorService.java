package server.service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

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

    public GameAllocatorService(Connection natsConnection, List<String> shardIds, ShardPicker fallbackPicker, ActivityLog activityLog) {
        this.natsConnection = natsConnection;
        this.shardIds = shardIds;
        this.fallbackPicker = fallbackPicker;
        this.activityLog = activityLog;
    }

    /** Picks the least-loaded shard for a brand-new room, falling back to round-robin if no shard's load could be determined. */
    public String assignShard() {
        String leastLoaded = null;
        int lowestLoad = Integer.MAX_VALUE;

        for (String shardId : shardIds) {
            Integer load = queryLoad(shardId);
            if (load != null && load < lowestLoad) {
                lowestLoad = load;
                leastLoaded = shardId;
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
