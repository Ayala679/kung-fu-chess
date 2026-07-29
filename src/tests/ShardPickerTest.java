package tests;

import java.util.List;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import server.ShardPicker;

class ShardPickerTest {

    @Test void testSingleShardAlwaysReturnsItself() {
        ShardPicker picker = new ShardPicker(List.of("shard-1"));
        assertEquals("shard-1", picker.next());
        assertEquals("shard-1", picker.next());
    }

    @Test void testCyclesThroughShardsInOrder() {
        ShardPicker picker = new ShardPicker(List.of("shard-1", "shard-2"));
        assertEquals("shard-1", picker.next());
        assertEquals("shard-2", picker.next());
        assertEquals("shard-1", picker.next());
        assertEquals("shard-2", picker.next());
    }

    @Test void testWrapsAroundAfterFullCycle() {
        ShardPicker picker = new ShardPicker(List.of("a", "b", "c"));
        for (int i = 0; i < 3; i++) picker.next();
        assertEquals("a", picker.next());
    }

    @Test void testRejectsEmptyShardList() {
        assertThrows(IllegalArgumentException.class, () -> new ShardPicker(List.of()));
    }
}
