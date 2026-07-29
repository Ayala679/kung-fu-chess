package server;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The Game Allocator's actual policy: static round-robin over a fixed list
 * of shard ids. Pure and stateless besides its own index, so it's directly
 * unit-tested in isolation (see tests.ShardPickerTest) - the same "pure
 * logic class + thin NATS-process wrapper" split already used for
 * MatchQueue/Matchmaker.
 */
public class ShardPicker {
    private final List<String> shardIds;
    private final AtomicInteger index = new AtomicInteger(0);

    public ShardPicker(List<String> shardIds) {
        if (shardIds.isEmpty()) throw new IllegalArgumentException("shardIds must not be empty");
        this.shardIds = List.copyOf(shardIds);
    }

    /** Returns the next shard id in round-robin order. */
    public String next() {
        int i = Math.floorMod(index.getAndIncrement(), shardIds.size());
        return shardIds.get(i);
    }
}
