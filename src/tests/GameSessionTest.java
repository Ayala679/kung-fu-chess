package tests;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

import bus.Bus;
import logging.ActivityLog;
import net.Seat;
import net.SnapshotCodec;
import server.GameSession;
import server.auth.UserRepository;
import snapshot.GameSnapshot;

class GameSessionTest {

    private static GameSession newSession(Path tempDir, long disconnectResignDelayMillis) {
        Bus bus = new Bus();
        UserRepository userRepository = new UserRepository(tempDir.resolve("users.db").toString());
        ActivityLog activityLog = new ActivityLog(tempDir.resolve("test.log").toString());
        return new GameSession(bus, "ROOM1", userRepository, activityLog, disconnectResignDelayMillis);
    }

    private static GameSnapshot lastStateOf(FakeWebSocket socket) {
        String message = socket.sentMessages.stream()
                .filter(m -> m.startsWith("STATE"))
                .reduce((first, second) -> second)
                .orElseThrow(() -> new AssertionError("no STATE message was ever sent"));
        return SnapshotCodec.decode(message.substring("STATE".length()).replaceFirst("^\n", ""));
    }

    @Test void testViewerCommandsAreRejectedWithAnExplicitError(@TempDir Path tempDir) {
        GameSession session = newSession(tempDir, 20_000L);
        FakeWebSocket alice = new FakeWebSocket();
        FakeWebSocket bob = new FakeWebSocket();
        FakeWebSocket carol = new FakeWebSocket();
        session.join(alice, "alice", 1200);
        session.join(bob, "bob", 1200);
        session.join(carol, "carol", 1200);
        carol.sentMessages.clear();

        session.handleCommand(carol, "click 6 4");

        assertTrue(carol.sentMessages.contains("ERROR|VIEWER_CANNOT_PLAY"));
    }

    @Test void testJumpingAPieceYouDoNotOwnIsRejectedWithAnExplicitError(@TempDir Path tempDir) {
        GameSession session = newSession(tempDir, 20_000L);
        FakeWebSocket alice = new FakeWebSocket();
        FakeWebSocket bob = new FakeWebSocket();
        session.join(alice, "alice", 1200); // WHITE
        session.join(bob, "bob", 1200);     // BLACK
        alice.sentMessages.clear();

        session.handleCommand(alice, "jump 1 4"); // a black pawn's starting square

        assertTrue(alice.sentMessages.contains("ERROR|NOT_YOUR_PIECE"));
    }

    @Test void testJumpingAnEmptySquareIsAlsoRejectedAsNotYourPiece(@TempDir Path tempDir) {
        GameSession session = newSession(tempDir, 20_000L);
        FakeWebSocket alice = new FakeWebSocket();
        FakeWebSocket bob = new FakeWebSocket();
        session.join(alice, "alice", 1200);
        session.join(bob, "bob", 1200);
        alice.sentMessages.clear();

        session.handleCommand(alice, "jump 4 4"); // empty square in the middle of the board

        assertTrue(alice.sentMessages.contains("ERROR|NOT_YOUR_PIECE"));
    }

    @Test void testAMalformedCommandIsRejectedWithAnExplicitError(@TempDir Path tempDir) {
        GameSession session = newSession(tempDir, 20_000L);
        FakeWebSocket alice = new FakeWebSocket();
        FakeWebSocket bob = new FakeWebSocket();
        session.join(alice, "alice", 1200);
        session.join(bob, "bob", 1200);
        alice.sentMessages.clear();

        session.handleCommand(alice, "teleport 1 4");
        session.handleCommand(alice, "click notanumber 4");

        assertEquals(2, alice.sentMessages.stream().filter(m -> m.equals("ERROR|MALFORMED_COMMAND")).count());
    }

    @Test void testOwningYourOwnPieceIsAcceptedAndProducesNoError(@TempDir Path tempDir) {
        GameSession session = newSession(tempDir, 20_000L);
        FakeWebSocket alice = new FakeWebSocket();
        FakeWebSocket bob = new FakeWebSocket();
        session.join(alice, "alice", 1200);
        session.join(bob, "bob", 1200);
        alice.sentMessages.clear();

        session.handleCommand(alice, "click 6 4"); // alice's own pawn

        assertTrue(alice.sentMessages.stream().noneMatch(m -> m.startsWith("ERROR")));
    }

    @Test void testAValidClickReceivesCommandResultSuccess(@TempDir Path tempDir) {
        GameSession session = newSession(tempDir, 20_000L);
        FakeWebSocket alice = new FakeWebSocket();
        FakeWebSocket bob = new FakeWebSocket();
        session.join(alice, "alice", 1200);
        session.join(bob, "bob", 1200);
        alice.sentMessages.clear();

        session.handleCommand(alice, "click 6 4"); // just a selection, no move

        assertTrue(alice.sentMessages.contains("COMMAND_RESULT|SUCCESS"));
    }

    @Test void testAnIllegalMoveIsRejectedWithErrorIllegalMove(@TempDir Path tempDir) {
        GameSession session = newSession(tempDir, 20_000L);
        FakeWebSocket alice = new FakeWebSocket();
        FakeWebSocket bob = new FakeWebSocket();
        session.join(alice, "alice", 1200);
        session.join(bob, "bob", 1200);
        alice.sentMessages.clear();

        session.handleCommand(alice, "click 6 4"); // select alice's pawn (e2)
        alice.sentMessages.clear();
        session.handleCommand(alice, "click 5 5"); // pawns can't move diagonally without a capture there

        assertTrue(alice.sentMessages.contains("ERROR|ILLEGAL_MOVE"));
        assertTrue(alice.sentMessages.stream().noneMatch(m -> m.equals("COMMAND_RESULT|SUCCESS")));
    }

    @Test void testAnAcceptedMoveEmitsMoveAcceptedThenMoveCompletedEvents(@TempDir Path tempDir) throws InterruptedException {
        GameSession session = newSession(tempDir, 20_000L);
        FakeWebSocket alice = new FakeWebSocket();
        FakeWebSocket bob = new FakeWebSocket();
        session.join(alice, "alice", 1200);
        session.join(bob, "bob", 1200);
        alice.sentMessages.clear();

        session.handleCommand(alice, "click 6 4"); // select e2 pawn
        session.handleCommand(alice, "click 5 4"); // move to e3 - a 1-cell move, 1000ms

        assertTrue(alice.sentMessages.contains("EVENT|MOVE_ACCEPTED|WHITE|e2|e3"));
        assertTrue(alice.sentMessages.stream().noneMatch(m -> m.startsWith("EVENT|MOVE_COMPLETED")),
                "the move can't have completed yet - no time has passed");

        Thread.sleep(1200); // past the 1000ms move duration - the session's own real-time ticker resolves it

        assertTrue(alice.sentMessages.contains("EVENT|MOVE_COMPLETED|WHITE|e2|e3"));
    }

    @Test void testAnAcceptedJumpEmitsJumpAcceptedThenJumpCompletedEvents(@TempDir Path tempDir) throws InterruptedException {
        GameSession session = newSession(tempDir, 20_000L);
        FakeWebSocket alice = new FakeWebSocket();
        FakeWebSocket bob = new FakeWebSocket();
        session.join(alice, "alice", 1200);
        session.join(bob, "bob", 1200);
        alice.sentMessages.clear();

        session.handleCommand(alice, "jump 6 4"); // e2 pawn jumps in place, JUMP_DURATION (700ms)

        assertTrue(alice.sentMessages.contains("EVENT|JUMP_ACCEPTED|WHITE|e2"));

        Thread.sleep(900); // past the 700ms jump duration

        assertTrue(alice.sentMessages.contains("EVENT|JUMP_COMPLETED|WHITE|e2"));
    }

    @Test void testReconnectingBeforeTheGraceWindowElapsesRestoresTheSeatAndCancelsTheForfeit(@TempDir Path tempDir) throws InterruptedException {
        GameSession session = newSession(tempDir, 300L);
        FakeWebSocket alice = new FakeWebSocket();
        FakeWebSocket bob = new FakeWebSocket();
        session.join(alice, "alice", 1200);
        session.join(bob, "bob", 1200);

        session.handleDisconnect(alice);
        FakeWebSocket aliceAgain = new FakeWebSocket();
        boolean reconnected = session.reconnect(aliceAgain, "alice");
        Thread.sleep(600); // longer than the 300ms grace window, so the (cancelled) forfeit would have fired by now

        assertTrue(reconnected);
        assertEquals(Seat.WHITE, session.seatOf(aliceAgain));
        assertTrue(aliceAgain.sentMessages.stream().anyMatch(m -> m.equals("WELCOME|role=WHITE")));
        assertFalse(lastStateOf(bob).gameOver(), "the game must not have been auto-resigned once alice reconnected in time");
    }

    @Test void testFailingToReconnectWithinTheGraceWindowForfeitsTheGame(@TempDir Path tempDir) throws InterruptedException {
        GameSession session = newSession(tempDir, 200L);
        FakeWebSocket alice = new FakeWebSocket();
        FakeWebSocket bob = new FakeWebSocket();
        session.join(alice, "alice", 1200);
        session.join(bob, "bob", 1200);

        session.handleDisconnect(alice);
        Thread.sleep(500);

        GameSnapshot finalState = lastStateOf(bob);
        assertTrue(finalState.gameOver(), "the game should be auto-resigned once the grace window elapses without a reconnect");
        assertEquals("BLACK", finalState.winner(), "bob (still connected) should win by forfeit");
    }

    @Test void testReconnectFailsForAUsernameThatWasNeverSeatedHere(@TempDir Path tempDir) {
        GameSession session = newSession(tempDir, 20_000L);
        FakeWebSocket alice = new FakeWebSocket();
        FakeWebSocket bob = new FakeWebSocket();
        session.join(alice, "alice", 1200);
        session.join(bob, "bob", 1200);

        assertFalse(session.reconnect(new FakeWebSocket(), "nobody"));
    }

    @Test void testReconnectFailsIfTheSeatIsNotActuallyVacated(@TempDir Path tempDir) {
        GameSession session = newSession(tempDir, 20_000L);
        FakeWebSocket alice = new FakeWebSocket();
        FakeWebSocket bob = new FakeWebSocket();
        session.join(alice, "alice", 1200);
        session.join(bob, "bob", 1200);

        assertFalse(session.reconnect(new FakeWebSocket(), "alice")); // alice never disconnected
    }
}
