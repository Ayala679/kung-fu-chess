package server.service;

import logging.ActivityLog;
import server.ShardPicker;

/**
 * All the decision logic behind the Game Allocator: which shard a brand-new
 * room should go to. Wraps {@link ShardPicker} (the pure round-robin policy)
 * with logging - kept separate from {@code server.controller.GameAllocatorController}
 * so the NATS request-reply wiring never has to know how the decision itself is
 * made.
 */
public class GameAllocatorService {
    private final ShardPicker picker;
    private final ActivityLog activityLog;

    public GameAllocatorService(ShardPicker picker, ActivityLog activityLog) {
        this.picker = picker;
        this.activityLog = activityLog;
    }

    /** Picks the next shard (round-robin) for a brand-new room. */
    public String assignShard() {
        String shardId = picker.next();
        activityLog.log("assigned new room to " + shardId);
        return shardId;
    }
}
