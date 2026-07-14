package tests;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import model.Board;
import model.GameState;
import model.Piece;
import model.Position;
import gameengine.GameEngine;

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

        engine.requestMove(new Position(0, 0), new Position(0, 5)); // 5 cells -> 5000ms
        engine.advanceTime(1);                                      // rook underway 1ms
        engine.requestJump(0, 5);                                   // knight reacts, 999ms of slack
        engine.advanceTime(100000);

        assertEquals(knight, engine.pieceAt(0, 5));
        assertNull(engine.pieceAt(0, 0));
    }

    @Test void testReactiveJumpTooCloseToArrivalDies() {
        Piece[][] grid = new Piece[8][8];
        Piece rook = Piece.of(Piece.Color.WHITE, Piece.Type.R);
        Piece knight = Piece.of(Piece.Color.BLACK, Piece.Type.N);
        grid[0][0] = rook;
        grid[0][7] = knight;
        GameEngine engine = engineWith(grid);

        engine.requestMove(new Position(0, 0), new Position(0, 7)); // 7 cells -> 7000ms
        engine.advanceTime(6500);                                    // only 500ms left, jump needs 1000ms
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
}
