package tests;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

import bus.Bus;
import logging.ActivityLog;
import net.Seat;
import server.GameSession;
import server.Lobby;
import server.auth.UserRepository;

class LobbyTest {

    private static Lobby newLobby(Path tempDir) {
        UserRepository userRepository = new UserRepository(tempDir.resolve("users.db").toString());
        ActivityLog activityLog = new ActivityLog(tempDir.resolve("test.log").toString());
        return new Lobby(new Bus(), userRepository, activityLog);
    }

    private static Lobby newLobbyWithShortMatchmakingTimeout(Path tempDir, long timeoutSeconds) {
        UserRepository userRepository = new UserRepository(tempDir.resolve("users.db").toString());
        ActivityLog activityLog = new ActivityLog(tempDir.resolve("test.log").toString());
        return new Lobby(new Bus(), userRepository, activityLog, timeoutSeconds);
    }

    @Test void testPlayQueuesWhenNoOneIsWaiting(@TempDir Path tempDir) {
        Lobby lobby = newLobby(tempDir);
        FakeWebSocket alice = new FakeWebSocket();

        boolean matched = lobby.play(alice, "alice", 1200);

        assertFalse(matched);
        assertTrue(alice.sentMessages.isEmpty()); // caller (KungFuChessServer) sends WAITING, not Lobby itself
    }

    @Test void testPlayMatchesTwoPlayersWithinEloRange(@TempDir Path tempDir) {
        Lobby lobby = newLobby(tempDir);
        FakeWebSocket alice = new FakeWebSocket();
        FakeWebSocket bob = new FakeWebSocket();

        assertFalse(lobby.play(alice, "alice", 1200));
        boolean matched = lobby.play(bob, "bob", 1250); // within +-100

        assertTrue(matched);
        assertTrue(alice.sentMessages.stream().anyMatch(m -> m.startsWith("WELCOME")));
        assertTrue(bob.sentMessages.stream().anyMatch(m -> m.startsWith("WELCOME")));
        assertTrue(alice.sentMessages.stream().anyMatch(m -> m.startsWith("STATE")));
        assertTrue(bob.sentMessages.stream().anyMatch(m -> m.startsWith("STATE")));
    }

    @Test void testPlayDoesNotMatchPlayersOutsideEloRange(@TempDir Path tempDir) {
        Lobby lobby = newLobby(tempDir);
        FakeWebSocket alice = new FakeWebSocket();
        FakeWebSocket carol = new FakeWebSocket();

        assertFalse(lobby.play(alice, "alice", 1200));
        boolean matched = lobby.play(carol, "carol", 1350); // 150 apart - outside +-100

        assertFalse(matched);
        assertTrue(carol.sentMessages.isEmpty());
    }

    @Test void testAThirdQueuedPlayerCanStillMatchAnEarlierOneInRange(@TempDir Path tempDir) {
        Lobby lobby = newLobby(tempDir);
        FakeWebSocket alice = new FakeWebSocket();
        FakeWebSocket carol = new FakeWebSocket();
        FakeWebSocket dave = new FakeWebSocket();

        assertFalse(lobby.play(alice, "alice", 1200));
        assertFalse(lobby.play(carol, "carol", 1350)); // queued too, out of range of alice
        boolean matched = lobby.play(dave, "dave", 1310); // within range of carol (1350), not alice (1200, 110 apart)

        assertTrue(matched);
        assertTrue(carol.sentMessages.stream().anyMatch(m -> m.startsWith("WELCOME")));
        assertTrue(dave.sentMessages.stream().anyMatch(m -> m.startsWith("WELCOME")));
        assertTrue(alice.sentMessages.isEmpty()); // still waiting
    }

    @Test void testCreateRoomThenJoinRoomSeatsWhiteAndBlack(@TempDir Path tempDir) {
        Lobby lobby = newLobby(tempDir);
        FakeWebSocket alice = new FakeWebSocket();
        FakeWebSocket bob = new FakeWebSocket();

        GameSession created = lobby.createRoom(alice, "alice", 1200);
        GameSession joined = lobby.joinRoom(created.getRoomCode(), bob, "bob", 1200);

        assertSame(created, joined);
        assertEquals(Seat.WHITE, created.seatOf(alice));
        assertEquals(Seat.BLACK, created.seatOf(bob));
    }

    @Test void testRoomCreatorGetsNoSeatOrStateUntilAnOpponentJoins(@TempDir Path tempDir) {
        Lobby lobby = newLobby(tempDir);
        FakeWebSocket alice = new FakeWebSocket();

        lobby.createRoom(alice, "alice", 1200);

        assertTrue(alice.sentMessages.isEmpty(), "the creator must not see WELCOME/STATE while alone in the room");
    }

    @Test void testBothPlayersAreGreetedTheMomentTheSecondOneJoinsARoom(@TempDir Path tempDir) {
        Lobby lobby = newLobby(tempDir);
        FakeWebSocket alice = new FakeWebSocket();
        FakeWebSocket bob = new FakeWebSocket();

        GameSession session = lobby.createRoom(alice, "alice", 1200);
        lobby.joinRoom(session.getRoomCode(), bob, "bob", 1200);

        assertTrue(alice.sentMessages.stream().anyMatch(m -> m.startsWith("WELCOME")));
        assertTrue(alice.sentMessages.stream().anyMatch(m -> m.startsWith("STATE")));
        assertTrue(bob.sentMessages.stream().anyMatch(m -> m.startsWith("WELCOME")));
        assertTrue(bob.sentMessages.stream().anyMatch(m -> m.startsWith("STATE")));
    }

    @Test void testASpectatorJoiningAFullRoomIsGreetedImmediately(@TempDir Path tempDir) {
        Lobby lobby = newLobby(tempDir);
        FakeWebSocket alice = new FakeWebSocket();
        FakeWebSocket bob = new FakeWebSocket();
        FakeWebSocket carol = new FakeWebSocket();

        GameSession session = lobby.createRoom(alice, "alice", 1200);
        lobby.joinRoom(session.getRoomCode(), bob, "bob", 1200);
        lobby.joinRoom(session.getRoomCode(), carol, "carol", 1200);

        assertTrue(carol.sentMessages.stream().anyMatch(m -> m.equals("WELCOME|role=VIEWER")));
        assertTrue(carol.sentMessages.stream().anyMatch(m -> m.startsWith("STATE")));
    }

    @Test void testJoinRoomWithUnknownCodeReturnsNull(@TempDir Path tempDir) {
        Lobby lobby = newLobby(tempDir);
        FakeWebSocket bob = new FakeWebSocket();

        assertNull(lobby.joinRoom("NOSUCH", bob, "bob", 1200));
    }

    @Test void testAThirdPlayerJoiningARoomBecomesAViewer(@TempDir Path tempDir) {
        Lobby lobby = newLobby(tempDir);
        FakeWebSocket alice = new FakeWebSocket();
        FakeWebSocket bob = new FakeWebSocket();
        FakeWebSocket carol = new FakeWebSocket();

        GameSession session = lobby.createRoom(alice, "alice", 1200);
        lobby.joinRoom(session.getRoomCode(), bob, "bob", 1200);
        lobby.joinRoom(session.getRoomCode(), carol, "carol", 1200);

        assertEquals(Seat.VIEWER, session.seatOf(carol));
    }

    @Test void testSessionOfTracksWhichRoomAConnectionJoined(@TempDir Path tempDir) {
        Lobby lobby = newLobby(tempDir);
        FakeWebSocket alice = new FakeWebSocket();

        GameSession session = lobby.createRoom(alice, "alice", 1200);

        assertSame(session, lobby.sessionOf(alice));
    }

    @Test void testCancelQueuedRemovesAWaitingPlayerSoTheyAreNotMatchedLater(@TempDir Path tempDir) {
        Lobby lobby = newLobby(tempDir);
        FakeWebSocket alice = new FakeWebSocket();
        FakeWebSocket bob = new FakeWebSocket();

        assertFalse(lobby.play(alice, "alice", 1200));
        lobby.cancelQueued(alice);
        boolean matched = lobby.play(bob, "bob", 1200);

        assertFalse(matched); // alice was removed, so bob just queues instead of matching a stale entry
    }

    @Test void testPlayTimesOutAndTellsTheClientIfNoOpponentArrivesInTime(@TempDir Path tempDir) throws InterruptedException {
        Lobby lobby = newLobbyWithShortMatchmakingTimeout(tempDir, 100L);
        FakeWebSocket alice = new FakeWebSocket();

        assertFalse(lobby.play(alice, "alice", 1200));
        Thread.sleep(300);

        assertTrue(alice.sentMessages.stream().anyMatch(m -> m.startsWith("ERROR")));
    }

    @Test void testPlayDoesNotTimeOutOnceAlreadyMatched(@TempDir Path tempDir) throws InterruptedException {
        Lobby lobby = newLobbyWithShortMatchmakingTimeout(tempDir, 100L);
        FakeWebSocket alice = new FakeWebSocket();
        FakeWebSocket bob = new FakeWebSocket();

        assertFalse(lobby.play(alice, "alice", 1200));
        assertTrue(lobby.play(bob, "bob", 1200));
        alice.sentMessages.clear(); // drop the WELCOME/STATE greeting so only a stray timeout ERROR would show up below
        Thread.sleep(300); // the game's own ticker keeps sending fresh STATE messages in the meantime - that's expected

        assertFalse(alice.sentMessages.stream().anyMatch(m -> m.startsWith("ERROR")),
                "a matched player must not also receive a stale timeout error");
    }

    @Test void testTryReconnectRestoresADisconnectedPlayerToTheSameRoom(@TempDir Path tempDir) {
        Lobby lobby = newLobby(tempDir);
        FakeWebSocket alice = new FakeWebSocket();
        FakeWebSocket bob = new FakeWebSocket();

        GameSession session = lobby.createRoom(alice, "alice", 1200);
        lobby.joinRoom(session.getRoomCode(), bob, "bob", 1200);
        lobby.handleDisconnect(alice);

        FakeWebSocket aliceAgain = new FakeWebSocket();
        boolean reconnected = lobby.tryReconnect(aliceAgain, "alice");

        assertTrue(reconnected);
        assertSame(session, lobby.sessionOf(aliceAgain));
        assertEquals(Seat.WHITE, session.seatOf(aliceAgain));
    }

    @Test void testTryReconnectFailsForAUsernameWithNoDisconnectedSeat(@TempDir Path tempDir) {
        Lobby lobby = newLobby(tempDir);
        FakeWebSocket alice = new FakeWebSocket();

        assertFalse(lobby.tryReconnect(alice, "nobody"));
    }
}
