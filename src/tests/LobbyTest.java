package tests;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

import bus.Bus;
import logging.ActivityLog;
import server.GameSession;
import server.Lobby;
import server.Seat;
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

    private static Lobby newLobbyWithShortDisconnectGrace(Path tempDir, long disconnectGraceMillis) {
        UserRepository userRepository = new UserRepository(tempDir.resolve("users.db").toString());
        ActivityLog activityLog = new ActivityLog(tempDir.resolve("test.log").toString());
        return new Lobby(new Bus(), userRepository, activityLog, 60_000L, disconnectGraceMillis);
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

        String code = lobby.createRoom("alice");
        GameSession created = lobby.joinRoom(code, alice, "alice", 1200);
        GameSession joined = lobby.joinRoom(code, bob, "bob", 1200);

        assertSame(created, joined);
        assertEquals(Seat.WHITE, created.seatOf(alice));
        assertEquals(Seat.BLACK, created.seatOf(bob));
    }

    @Test void testRoomCreatorGetsNoSeatOrStateUntilAnOpponentJoins(@TempDir Path tempDir) {
        Lobby lobby = newLobby(tempDir);
        FakeWebSocket alice = new FakeWebSocket();

        String code = lobby.createRoom("alice");
        lobby.joinRoom(code, alice, "alice", 1200);

        assertTrue(alice.sentMessages.isEmpty(), "the creator must not see WELCOME/STATE while alone in the room");
    }

    @Test void testBothPlayersAreGreetedTheMomentTheSecondOneJoinsARoom(@TempDir Path tempDir) {
        Lobby lobby = newLobby(tempDir);
        FakeWebSocket alice = new FakeWebSocket();
        FakeWebSocket bob = new FakeWebSocket();

        String code = lobby.createRoom("alice");
        lobby.joinRoom(code, alice, "alice", 1200);
        lobby.joinRoom(code, bob, "bob", 1200);

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

        String code = lobby.createRoom("alice");
        lobby.joinRoom(code, alice, "alice", 1200);
        lobby.joinRoom(code, bob, "bob", 1200);
        lobby.joinRoom(code, carol, "carol", 1200);

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

        String code = lobby.createRoom("alice");
        GameSession session = lobby.joinRoom(code, alice, "alice", 1200);
        lobby.joinRoom(code, bob, "bob", 1200);
        lobby.joinRoom(code, carol, "carol", 1200);

        assertEquals(Seat.VIEWER, session.seatOf(carol));
    }

    @Test void testSessionOfTracksWhichRoomAConnectionJoined(@TempDir Path tempDir) {
        Lobby lobby = newLobby(tempDir);
        FakeWebSocket alice = new FakeWebSocket();

        String code = lobby.createRoom("alice");
        GameSession session = lobby.joinRoom(code, alice, "alice", 1200);

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

        String code = lobby.createRoom("alice");
        GameSession session = lobby.joinRoom(code, alice, "alice", 1200);
        lobby.joinRoom(code, bob, "bob", 1200);
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

    // Regression test for a real reported bug: after both players disconnect
    // from an already-abandoned game and later log back in, tryReconnect
    // silently reconnects them into that dead session (intentional - see its
    // own Javadoc, it's what lets a rematch happen). But without
    // leaveFinishedSessionIfAny, every future "play"/"join_room" from that
    // connection kept being swallowed by the old session as an unrecognized
    // command instead of ever reaching a new game.
    @Test void testLeavingAFinishedSessionFreesTheConnectionToStartANewGame(@TempDir Path tempDir) throws InterruptedException {
        Lobby lobby = newLobbyWithShortDisconnectGrace(tempDir, 100L);
        FakeWebSocket alice = new FakeWebSocket();
        FakeWebSocket bob = new FakeWebSocket();

        String code = lobby.createRoom("alice");
        lobby.joinRoom(code, alice, "alice", 1200);
        lobby.joinRoom(code, bob, "bob", 1200);

        lobby.handleDisconnect(alice);
        Thread.sleep(300); // grace window elapses -> game abandoned, no winner
        lobby.handleDisconnect(bob); // the other side gives up too, exactly like the reported scenario

        FakeWebSocket aliceAgain = new FakeWebSocket();
        assertTrue(lobby.tryReconnect(aliceAgain, "alice"), "reconnecting into the finished game must still work - it's how a rematch happens");
        GameSession finishedSession = lobby.sessionOf(aliceAgain);
        assertNotNull(finishedSession);
        assertTrue(finishedSession.isGameOver());

        // alice doesn't want a rematch - she wants a new game, which must not be swallowed by the dead session
        assertTrue(lobby.leaveFinishedSessionIfAny(aliceAgain));
        assertNull(lobby.sessionOf(aliceAgain), "leaving the finished session must free this connection for normal lobby routing");

        boolean matched = lobby.play(aliceAgain, "alice", 1200);
        assertFalse(matched); // no opponent queued yet - just proves play() ran normally instead of being routed into the old session
    }

    @Test void testLeaveFinishedSessionIfAnyIsANoOpWhileTheGameIsStillInProgress(@TempDir Path tempDir) {
        Lobby lobby = newLobby(tempDir);
        FakeWebSocket alice = new FakeWebSocket();
        FakeWebSocket bob = new FakeWebSocket();

        String code = lobby.createRoom("alice");
        lobby.joinRoom(code, alice, "alice", 1200);
        lobby.joinRoom(code, bob, "bob", 1200);

        assertFalse(lobby.leaveFinishedSessionIfAny(alice));
        assertSame(lobby.sessionOf(alice), lobby.sessionOf(alice), "still attached - the game isn't over, nothing to leave");
        assertNotNull(lobby.sessionOf(alice));
    }

    @Test void testLeaveFinishedSessionIfAnyIsANoOpForAConnectionWithNoSession(@TempDir Path tempDir) {
        Lobby lobby = newLobby(tempDir);
        FakeWebSocket alice = new FakeWebSocket();

        assertFalse(lobby.leaveFinishedSessionIfAny(alice));
    }
}
