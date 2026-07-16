package tests;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import model.Board;
import model.GameState;
import model.MoveLogEntry;
import model.Piece;
import model.Position;
import gameengine.GameEngine;
import snapshot.GameSnapshot;

class GameEngineTest {
    private static GameEngine engineWith(Piece[][] grid) {
        return new GameEngine(new Board(grid), new GameState());
    }

    @Test void testBoardBootstrapChecks() {
        GameEngine empty = engineWith(new Piece[0][0]);
        assertTrue(empty.isEmpty());
        assertFalse(empty.isValid());

        GameEngine nonEmpty = engineWith(new Piece[8][8]);
        assertFalse(nonEmpty.isEmpty());
        assertTrue(nonEmpty.isValid());
    }

    @Test void testInBoundsDelegatesToBoard() {
        GameEngine engine = engineWith(new Piece[8][8]);
        assertTrue(engine.inBounds(0, 0));
        assertTrue(engine.inBounds(7, 7));
        assertFalse(engine.inBounds(8, 0));
        assertFalse(engine.inBounds(-1, 0));
        assertEquals(8, engine.getHeight());
        assertEquals(8, engine.getWidth());
    }

    @Test void testRequestMoveAppliesAfterDurationElapses() {
        Piece[][] grid = new Piece[8][8];
        Piece rook = Piece.of(Piece.Color.WHITE, Piece.Type.R);
        grid[4][4] = rook;
        GameEngine engine = engineWith(grid);

        engine.requestMove(new Position(4, 4), new Position(4, 0));
        engine.advanceTime(100000);

        assertNull(engine.pieceAt(4, 4));
        assertEquals(rook, engine.pieceAt(4, 0));
    }

    @Test void testRequestMoveUsesFlatKnightDurationRegardlessOfDistance() {
        Piece[][] grid = new Piece[8][8];
        Piece knight = Piece.of(Piece.Color.WHITE, Piece.Type.N);
        grid[7][1] = knight;
        GameEngine engine = engineWith(grid);

        engine.requestMove(new Position(7, 1), new Position(5, 2));
        engine.advanceTime(100000);

        assertNull(engine.pieceAt(7, 1));
        assertEquals(knight, engine.pieceAt(5, 2));
    }

    @Test void testRequestMoveRejectsIllegalGeometry() {
        Piece[][] grid = new Piece[8][8];
        grid[4][4] = Piece.of(Piece.Color.WHITE, Piece.Type.R);
        GameEngine engine = engineWith(grid);

        engine.requestMove(new Position(4, 4), new Position(5, 5));
        engine.advanceTime(100000);

        assertNotNull(engine.pieceAt(4, 4));
        assertNull(engine.pieceAt(5, 5));
    }

    @Test void testRequestMoveRejectsOutOfBoundsDestination() {
        Piece[][] grid = new Piece[8][8];
        Piece rook = Piece.of(Piece.Color.WHITE, Piece.Type.R);
        grid[4][4] = rook;
        GameEngine engine = engineWith(grid);

        engine.requestMove(new Position(4, 4), new Position(4, 50));
        engine.advanceTime(100000);

        assertEquals(rook, engine.pieceAt(4, 4));
    }

    @Test void testRequestMoveIgnoredWhileSourceAlreadyMoving() {
        Piece[][] grid = new Piece[8][8];
        Piece rook = Piece.of(Piece.Color.WHITE, Piece.Type.R);
        grid[6][4] = rook;
        GameEngine engine = engineWith(grid);

        engine.requestMove(new Position(6, 4), new Position(4, 4));
        // same source is already mid-flight; this second request must be ignored
        engine.requestMove(new Position(6, 4), new Position(0, 4));
        engine.advanceTime(100000);

        assertEquals(rook, engine.pieceAt(4, 4));
        assertNull(engine.pieceAt(0, 4));
    }

    @Test void testThreatenedPieceCanFleeToASafeSquare() {
        // Regression: requestMove used to check arbiter.isBusyAt(from), which
        // also matches any incoming enemy slide targeting "from" - blocking a
        // threatened piece from ever moving away, not just from being redirected.
        Piece[][] grid = new Piece[8][8];
        Piece attacker = Piece.of(Piece.Color.WHITE, Piece.Type.R);
        Piece victim = Piece.of(Piece.Color.BLACK, Piece.Type.R);
        grid[0][0] = attacker;
        grid[0][5] = victim;
        GameEngine engine = engineWith(grid);

        engine.requestMove(new Position(0, 0), new Position(0, 5)); // attacker heads toward the victim
        engine.advanceTime(1);
        engine.requestMove(new Position(0, 5), new Position(3, 5)); // victim flees down its own column
        engine.advanceTime(100000);

        // the victim escaped safely; the attacker's own (independent) slide still
        // completes and simply lands on the now-empty square it was already headed to
        assertEquals(victim, engine.pieceAt(3, 5));
        assertEquals(attacker, engine.pieceAt(0, 5));
    }

    @Test void testRequestMoveIgnoredWhenGameOver() {
        Piece[][] grid = new Piece[8][8];
        Piece rook = Piece.of(Piece.Color.WHITE, Piece.Type.R);
        Piece enemyKing = Piece.of(Piece.Color.BLACK, Piece.Type.K);
        grid[4][4] = rook;
        grid[4][0] = enemyKing;
        GameEngine engine = engineWith(grid);

        engine.requestMove(new Position(4, 4), new Position(4, 0));
        engine.advanceTime(100000);
        assertTrue(engine.isGameOver());

        // game is over: a further move request must be a no-op
        engine.requestMove(new Position(4, 0), new Position(0, 0));
        engine.advanceTime(100000);
        assertEquals(rook, engine.pieceAt(4, 0));
    }

    @Test void testReactiveJumpWithEnoughTimeLeftSucceeds() {
        // Real-time race: a reactive jump (started after the enemy) still wins
        // if it FINISHES before the enemy's slide arrives.
        Piece[][] grid = new Piece[8][8];
        Piece rook = Piece.of(Piece.Color.WHITE, Piece.Type.R);
        Piece knight = Piece.of(Piece.Color.BLACK, Piece.Type.N);
        grid[0][0] = rook;
        grid[0][5] = knight;
        GameEngine engine = engineWith(grid);

        engine.requestMove(new Position(0, 0), new Position(0, 5)); // 5 cells -> 3335ms
        engine.advanceTime(1);                                      // rook underway 1ms
        engine.requestJump(0, 5);                                   // knight reacts, plenty of slack
        engine.advanceTime(100000);

        assertEquals(knight, engine.pieceAt(0, 5));
        assertNull(engine.pieceAt(0, 0));
    }

    @Test void testReactiveJumpAgainstAnAdjacentAttackerCanStillSucceed() {
        // An adjacent (1-cell) attack takes MOVE_DURATION_PER_CELL (667ms);
        // JUMP_DURATION (333ms) is deliberately shorter, so even a reactive
        // jump against the single most common capture shape in the game has
        // real room to succeed - not just an exact-tie instant reaction.
        Piece[][] grid = new Piece[8][8];
        Piece rook = Piece.of(Piece.Color.WHITE, Piece.Type.R);
        Piece knight = Piece.of(Piece.Color.BLACK, Piece.Type.N);
        grid[0][0] = rook;
        grid[0][1] = knight; // adjacent - 1 cell -> 667ms slide, comfortably more than JUMP_DURATION
        GameEngine engine = engineWith(grid);

        engine.requestMove(new Position(0, 0), new Position(0, 1)); // rook attacks
        engine.requestJump(0, 1);                                   // knight reacts instantly
        engine.advanceTime(100000);

        assertEquals(knight, engine.pieceAt(0, 1)); // knight survives...
        assertNull(engine.pieceAt(0, 0));            // ...and the attacking rook is gone
    }

    @Test void testReactiveJumpTooCloseToArrivalDies() {
        Piece[][] grid = new Piece[8][8];
        Piece rook = Piece.of(Piece.Color.WHITE, Piece.Type.R);
        Piece knight = Piece.of(Piece.Color.BLACK, Piece.Type.N);
        grid[0][0] = rook;
        grid[0][7] = knight;
        GameEngine engine = engineWith(grid);

        engine.requestMove(new Position(0, 0), new Position(0, 7)); // 7 cells -> 4669ms
        engine.advanceTime(4400);                                    // only 269ms left, jump needs 667ms
        engine.requestJump(0, 7);
        engine.advanceTime(100000);

        // too late to dodge: the rook arrives and takes the square: the knight dies
        assertEquals(rook, engine.pieceAt(0, 7));
    }

    @Test void testPreemptiveJumpDefeatsALaterEnemySlide() {
        Piece[][] grid = new Piece[8][8];
        Piece rook = Piece.of(Piece.Color.WHITE, Piece.Type.R);
        Piece knight = Piece.of(Piece.Color.BLACK, Piece.Type.N);
        grid[0][0] = rook;
        grid[0][3] = knight;
        GameEngine engine = engineWith(grid);

        engine.requestJump(0, 3); // knight defends first
        engine.advanceTime(10);
        engine.requestMove(new Position(0, 0), new Position(0, 3)); // rook attacks after
        engine.advanceTime(100000);

        assertEquals(knight, engine.pieceAt(0, 3));
        assertNull(engine.pieceAt(0, 0));
    }

    @Test void testRequestJumpOutOfBoundsIsIgnored() {
        GameEngine engine = engineWith(new Piece[8][8]);
        engine.requestJump(50, 50); // must not throw
        assertFalse(engine.isBusyAt(50, 50));
    }

    @Test void testRequestJumpOnEmptyCellIsIgnored() {
        GameEngine engine = engineWith(new Piece[8][8]);
        engine.requestJump(3, 3);
        assertNull(engine.pieceAt(3, 3));
    }

    @Test void testIsBusyAtWhileMoveInFlight() {
        Piece[][] grid = new Piece[8][8];
        grid[4][4] = Piece.of(Piece.Color.WHITE, Piece.Type.R);
        GameEngine engine = engineWith(grid);

        engine.requestMove(new Position(4, 4), new Position(4, 0));
        assertTrue(engine.isBusyAt(4, 4));
        assertTrue(engine.isBusyAt(4, 0));
    }

    @Test void testMoveIsNotBlockedByAPieceThatHasAlreadyDepartedItsOrigin() {
        // Regression: the board only clears a cell on arrival, so a still
        // in-flight piece's own origin square (already vacated visually) used
        // to wrongly block any other piece's path/destination through it.
        Piece[][] grid = new Piece[8][8];
        Piece bishop = Piece.of(Piece.Color.WHITE, Piece.Type.B);
        Piece rook = Piece.of(Piece.Color.BLACK, Piece.Type.R);
        grid[4][4] = bishop;
        grid[4][0] = rook;
        GameEngine engine = engineWith(grid);

        engine.requestMove(new Position(4, 4), new Position(7, 7)); // bishop heads off diagonally, still mid-flight
        engine.requestMove(new Position(4, 0), new Position(4, 7)); // rook slides through (4,4), the bishop's vacated origin
        engine.advanceTime(100000);

        assertEquals(bishop, engine.pieceAt(7, 7));
        assertEquals(rook, engine.pieceAt(4, 7));
    }

    @Test void testKnightRequestIsRejectedWhenAFriendlyPieceAlreadyClaimedTheSquare() {
        Piece[][] grid = new Piece[8][8];
        Piece rook = Piece.of(Piece.Color.WHITE, Piece.Type.R);
        Piece knight = Piece.of(Piece.Color.WHITE, Piece.Type.N);
        grid[4][0] = rook;
        grid[6][5] = knight; // (6,5) -> (4,4) is a legal knight hop
        GameEngine engine = engineWith(grid);

        engine.requestMove(new Position(4, 0), new Position(4, 4)); // rook claims (4,4) first
        engine.requestMove(new Position(6, 5), new Position(4, 4)); // knight's request must be rejected outright

        assertFalse(engine.isBusyAt(6, 5));
        assertEquals(knight, engine.pieceAt(6, 5)); // never left

        engine.advanceTime(100000);
        assertEquals(rook, engine.pieceAt(4, 4));
        assertEquals(knight, engine.pieceAt(6, 5));
    }

    @Test void testMoveToASquareAKnightAlreadyClaimedIsRejected() {
        Piece[][] grid = new Piece[8][8];
        Piece knight = Piece.of(Piece.Color.WHITE, Piece.Type.N);
        Piece rook = Piece.of(Piece.Color.WHITE, Piece.Type.R);
        grid[6][5] = knight;
        grid[4][0] = rook;
        GameEngine engine = engineWith(grid);

        engine.requestMove(new Position(6, 5), new Position(4, 4)); // knight claims (4,4) first
        engine.requestMove(new Position(4, 0), new Position(4, 4)); // rook's request must be rejected outright

        assertFalse(engine.isBusyAt(4, 0));
        assertEquals(rook, engine.pieceAt(4, 0)); // never left

        engine.advanceTime(100000);
        assertEquals(knight, engine.pieceAt(4, 4));
        assertEquals(rook, engine.pieceAt(4, 0));
    }

    @Test void testPieceCannotMoveAgainWhileResting() {
        Piece[][] grid = new Piece[8][8];
        Piece rook = Piece.of(Piece.Color.WHITE, Piece.Type.R);
        grid[4][4] = rook;
        GameEngine engine = engineWith(grid);

        engine.requestMove(new Position(4, 4), new Position(4, 3)); // 1 cell -> 667ms
        engine.advanceTime(1000); // arrives, enters rest (default 1000ms)

        engine.requestMove(new Position(4, 3), new Position(4, 2)); // rejected - still resting
        engine.advanceTime(1);

        assertEquals(rook, engine.pieceAt(4, 3));
        assertNull(engine.pieceAt(4, 2));
    }

    @Test void testPieceCanMoveAgainOnceRestElapses() {
        Piece[][] grid = new Piece[8][8];
        Piece rook = Piece.of(Piece.Color.WHITE, Piece.Type.R);
        grid[4][4] = rook;
        GameEngine engine = engineWith(grid);

        engine.requestMove(new Position(4, 4), new Position(4, 3)); // 1 cell -> 667ms
        engine.advanceTime(1000); // arrives, rest starts
        engine.advanceTime(1000); // default rest duration elapses

        engine.requestMove(new Position(4, 3), new Position(4, 2));
        engine.advanceTime(1000);

        assertEquals(rook, engine.pieceAt(4, 2));
    }

    @Test void testRestingPieceCannotJumpEither() {
        Piece[][] grid = new Piece[8][8];
        Piece rook = Piece.of(Piece.Color.WHITE, Piece.Type.R);
        grid[4][4] = rook;
        GameEngine engine = engineWith(grid);

        engine.requestMove(new Position(4, 4), new Position(4, 3)); // 1 cell -> 667ms
        engine.advanceTime(1000); // arrives, enters rest

        engine.requestJump(4, 3); // rejected - still resting
        assertFalse(engine.isBusyAt(4, 3));
    }

    @Test void testMoveLogAndScoreFlowThroughTheSnapshot() {
        Piece[][] grid = new Piece[8][8];
        Piece whitePawn = Piece.of(Piece.Color.WHITE, Piece.Type.P);
        Piece blackPawn = Piece.of(Piece.Color.BLACK, Piece.Type.P);
        grid[6][4] = whitePawn; // e2
        grid[1][3] = blackPawn; // d7
        GameEngine engine = engineWith(grid);

        engine.requestMove(new Position(6, 4), new Position(4, 4)); // e2-e4
        engine.requestMove(new Position(1, 3), new Position(3, 3)); // d7-d5

        GameSnapshot snapshot = engine.buildSnapshot(null);

        assertEquals(1, snapshot.whiteMoves().size());
        assertEquals("e4", snapshot.whiteMoves().get(0).getNotation());
        assertEquals(1, snapshot.blackMoves().size());
        assertEquals("d5", snapshot.blackMoves().get(0).getNotation());
    }

    @Test void testScoreIncreasesInTheSnapshotAfterACapture() {
        Piece[][] grid = new Piece[8][8];
        Piece whiteRook = Piece.of(Piece.Color.WHITE, Piece.Type.R);
        Piece blackPawn = Piece.of(Piece.Color.BLACK, Piece.Type.P);
        grid[0][0] = whiteRook;
        grid[0][3] = blackPawn;
        GameEngine engine = engineWith(grid);

        engine.requestMove(new Position(0, 0), new Position(0, 3));
        engine.advanceTime(3000);

        GameSnapshot snapshot = engine.buildSnapshot(null);

        assertEquals(1, snapshot.whiteScore());
        assertEquals(0, snapshot.blackScore());
    }

    @Test void testPrintBoardDoesNotThrowAndProducesOutput() {
        Piece[][] grid = new Piece[8][8];
        grid[0][0] = Piece.of(Piece.Color.WHITE, Piece.Type.K);
        GameEngine engine = engineWith(grid);

        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream original = System.out;
        try {
            System.setOut(new PrintStream(captured));
            engine.printBoard();
        } finally {
            System.setOut(original);
        }

        assertTrue(captured.toString().contains("wK"));
    }

    @Test void testSecondPieceCanAlsoHeadToAContestedEmptySquare() {
        Piece[][] grid = new Piece[8][8];
        Piece rookA = Piece.of(Piece.Color.WHITE, Piece.Type.R);
        Piece rookB = Piece.of(Piece.Color.WHITE, Piece.Type.R);
        grid[4][0] = rookA;
        grid[0][4] = rookB;
        GameEngine engine = engineWith(grid);

        engine.requestMove(new Position(4, 0), new Position(4, 4)); // rookA heads there first
        engine.requestMove(new Position(0, 4), new Position(4, 4)); // rookB tries too, while rookA is still in flight

        assertTrue(engine.isBusyAt(0, 4)); // rookB's move actually started
    }

    @Test void testThreatenedPieceCanFleeEvenBeforeTheAttackerArrives() {
        Piece[][] grid = new Piece[8][8];
        Piece attacker = Piece.of(Piece.Color.WHITE, Piece.Type.R);
        Piece defender = Piece.of(Piece.Color.BLACK, Piece.Type.R);
        grid[0][0] = attacker;
        grid[0][5] = defender;
        GameEngine engine = engineWith(grid);

        engine.requestMove(new Position(0, 0), new Position(0, 5)); // attacker still far from arriving
        engine.requestMove(new Position(0, 5), new Position(3, 5)); // defender flees immediately

        assertTrue(engine.isBusyAt(3, 5));
    }

    @Test void testLegalDestinationsEmptyWhenNothingIsSelected() {
        Piece[][] grid = new Piece[8][8];
        grid[4][4] = Piece.of(Piece.Color.WHITE, Piece.Type.R);
        GameEngine engine = engineWith(grid);

        assertTrue(engine.buildSnapshot(null).legalDestinations().isEmpty());
    }

    @Test void testLegalDestinationsForARookOnAnEmptyBoard() {
        Piece[][] grid = new Piece[8][8];
        grid[4][4] = Piece.of(Piece.Color.WHITE, Piece.Type.R);
        GameEngine engine = engineWith(grid);

        GameSnapshot snapshot = engine.buildSnapshot(new Position(4, 4));

        assertEquals(14, snapshot.legalDestinations().size()); // full rank + full file, minus its own square
        assertTrue(snapshot.legalDestinations().contains(new Position(0, 4)));
        assertTrue(snapshot.legalDestinations().contains(new Position(4, 7)));
        assertFalse(snapshot.legalDestinations().contains(new Position(4, 4))); // never its own square
    }

    @Test void testLegalDestinationsStopAtAFriendlyBlockerAndDoNotIncludeIt() {
        Piece[][] grid = new Piece[8][8];
        grid[4][4] = Piece.of(Piece.Color.WHITE, Piece.Type.R);
        grid[4][6] = Piece.of(Piece.Color.WHITE, Piece.Type.P); // blocks the rank two squares away
        GameEngine engine = engineWith(grid);

        GameSnapshot snapshot = engine.buildSnapshot(new Position(4, 4));

        assertTrue(snapshot.legalDestinations().contains(new Position(4, 5))); // up to the blocker
        assertFalse(snapshot.legalDestinations().contains(new Position(4, 6))); // the blocker's own square
        assertFalse(snapshot.legalDestinations().contains(new Position(4, 7))); // past the blocker
    }

    @Test void testLegalDestinationsIncludeAnEnemyOccupiedSquareAsACapture() {
        Piece[][] grid = new Piece[8][8];
        grid[4][4] = Piece.of(Piece.Color.WHITE, Piece.Type.R);
        grid[4][6] = Piece.of(Piece.Color.BLACK, Piece.Type.P);
        GameEngine engine = engineWith(grid);

        GameSnapshot snapshot = engine.buildSnapshot(new Position(4, 4));

        assertTrue(snapshot.legalDestinations().contains(new Position(4, 6))); // capturable
        assertFalse(snapshot.legalDestinations().contains(new Position(4, 7))); // still blocked beyond it
    }

    @Test void testLegalDestinationsEmptyForAPieceThatIsCurrentlyMoving() {
        Piece[][] grid = new Piece[8][8];
        Piece rook = Piece.of(Piece.Color.WHITE, Piece.Type.R);
        grid[4][4] = rook;
        GameEngine engine = engineWith(grid);

        engine.requestMove(new Position(4, 4), new Position(4, 0));
        GameSnapshot snapshot = engine.buildSnapshot(new Position(4, 4));

        assertTrue(snapshot.legalDestinations().isEmpty());
    }

    @Test void testLegalDestinationsEmptyForAPieceThatIsResting() {
        Piece[][] grid = new Piece[8][8];
        Piece rook = Piece.of(Piece.Color.WHITE, Piece.Type.R);
        grid[4][4] = rook;
        GameEngine engine = engineWith(grid);

        engine.requestMove(new Position(4, 4), new Position(4, 3)); // 1 cell -> 667ms
        engine.advanceTime(667); // arrives, enters rest

        GameSnapshot snapshot = engine.buildSnapshot(new Position(4, 3));

        assertTrue(snapshot.legalDestinations().isEmpty());
    }
}
