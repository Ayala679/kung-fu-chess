package tests;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

import bus.InMemoryBus;
import logging.ActivityLog;
import server.Lobby;
import server.MatchQueue;
import server.auth.UserRepository;
import server.reconnect.InMemoryReconnectRegistry;

/**
 * Tests server.MatchQueue - the ELO-ranged quick-match pairing algorithm
 * extracted out of server.Lobby (see Server_Design.md's "Step 5"). Wires
 * its MatchFoundListener directly to a real, in-memory Lobby.seatMatchedPair
 * (exactly how the embedded topology's KungFuChessServerService wires it in
 * production) so the same observable behavior LobbyTest used to assert on
 * "play" still holds here.
 */
class MatchmakerTest {

    private static MatchQueue newMatchQueue(Path tempDir) {
        UserRepository userRepository = new UserRepository(tempDir.resolve("users.db").toString());
        ActivityLog activityLog = new ActivityLog(tempDir.resolve("test.log").toString());
        Lobby lobby = new Lobby(new InMemoryBus(), userRepository, activityLog, new InMemoryReconnectRegistry());
        return new MatchQueue(lobby::seatMatchedPair, activityLog);
    }

    private static MatchQueue newMatchQueueWithShortTimeout(Path tempDir, long timeoutMillis) {
        UserRepository userRepository = new UserRepository(tempDir.resolve("users.db").toString());
        ActivityLog activityLog = new ActivityLog(tempDir.resolve("test.log").toString());
        Lobby lobby = new Lobby(new InMemoryBus(), userRepository, activityLog, new InMemoryReconnectRegistry());
        return new MatchQueue(lobby::seatMatchedPair, activityLog, timeoutMillis);
    }

    @Test void testPlayQueuesWhenNoOneIsWaiting(@TempDir Path tempDir) {
        MatchQueue matchQueue = newMatchQueue(tempDir);
        FakeWebSocket alice = new FakeWebSocket();

        boolean matched = matchQueue.play(alice, "alice", 1200);

        assertFalse(matched);
        assertTrue(alice.sentMessages.isEmpty()); // caller sends WAITING, not MatchQueue itself
    }

    @Test void testPlayMatchesTwoPlayersWithinEloRange(@TempDir Path tempDir) {
        MatchQueue matchQueue = newMatchQueue(tempDir);
        FakeWebSocket alice = new FakeWebSocket();
        FakeWebSocket bob = new FakeWebSocket();

        assertFalse(matchQueue.play(alice, "alice", 1200));
        boolean matched = matchQueue.play(bob, "bob", 1250); // within +-100

        assertTrue(matched);
        assertTrue(alice.sentMessages.stream().anyMatch(m -> m.startsWith("WELCOME")));
        assertTrue(bob.sentMessages.stream().anyMatch(m -> m.startsWith("WELCOME")));
        assertTrue(alice.sentMessages.stream().anyMatch(m -> m.startsWith("STATE")));
        assertTrue(bob.sentMessages.stream().anyMatch(m -> m.startsWith("STATE")));
    }

    @Test void testPlayDoesNotMatchPlayersOutsideEloRange(@TempDir Path tempDir) {
        MatchQueue matchQueue = newMatchQueue(tempDir);
        FakeWebSocket alice = new FakeWebSocket();
        FakeWebSocket carol = new FakeWebSocket();

        assertFalse(matchQueue.play(alice, "alice", 1200));
        boolean matched = matchQueue.play(carol, "carol", 1350); // 150 apart - outside +-100

        assertFalse(matched);
        assertTrue(carol.sentMessages.isEmpty());
    }

    @Test void testAThirdQueuedPlayerCanStillMatchAnEarlierOneInRange(@TempDir Path tempDir) {
        MatchQueue matchQueue = newMatchQueue(tempDir);
        FakeWebSocket alice = new FakeWebSocket();
        FakeWebSocket carol = new FakeWebSocket();
        FakeWebSocket dave = new FakeWebSocket();

        assertFalse(matchQueue.play(alice, "alice", 1200));
        assertFalse(matchQueue.play(carol, "carol", 1350)); // queued too, out of range of alice
        boolean matched = matchQueue.play(dave, "dave", 1310); // within range of carol (1350), not alice (1200, 110 apart)

        assertTrue(matched);
        assertTrue(carol.sentMessages.stream().anyMatch(m -> m.startsWith("WELCOME")));
        assertTrue(dave.sentMessages.stream().anyMatch(m -> m.startsWith("WELCOME")));
        assertTrue(alice.sentMessages.isEmpty()); // still waiting
    }

    @Test void testCancelQueuedRemovesAWaitingPlayerSoTheyAreNotMatchedLater(@TempDir Path tempDir) {
        MatchQueue matchQueue = newMatchQueue(tempDir);
        FakeWebSocket alice = new FakeWebSocket();
        FakeWebSocket bob = new FakeWebSocket();

        assertFalse(matchQueue.play(alice, "alice", 1200));
        matchQueue.cancelQueued(alice);
        boolean matched = matchQueue.play(bob, "bob", 1200);

        assertFalse(matched); // alice was removed, so bob just queues instead of matching a stale entry
    }

    @Test void testPlayTimesOutAndTellsTheClientIfNoOpponentArrivesInTime(@TempDir Path tempDir) throws InterruptedException {
        MatchQueue matchQueue = newMatchQueueWithShortTimeout(tempDir, 100L);
        FakeWebSocket alice = new FakeWebSocket();

        assertFalse(matchQueue.play(alice, "alice", 1200));
        Thread.sleep(300);

        assertTrue(alice.sentMessages.stream().anyMatch(m -> m.startsWith("ERROR")));
    }

    @Test void testPlayDoesNotTimeOutOnceAlreadyMatched(@TempDir Path tempDir) throws InterruptedException {
        MatchQueue matchQueue = newMatchQueueWithShortTimeout(tempDir, 100L);
        FakeWebSocket alice = new FakeWebSocket();
        FakeWebSocket bob = new FakeWebSocket();

        assertFalse(matchQueue.play(alice, "alice", 1200));
        assertTrue(matchQueue.play(bob, "bob", 1200));
        alice.sentMessages.clear(); // drop the WELCOME/STATE greeting so only a stray timeout ERROR would show up below
        Thread.sleep(300); // the game's own ticker keeps sending fresh STATE messages in the meantime - that's expected

        assertFalse(alice.sentMessages.stream().anyMatch(m -> m.startsWith("ERROR")),
                "a matched player must not also receive a stale timeout error");
    }
}
