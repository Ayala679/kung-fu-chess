package tests;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import model.Board;
import model.GameState;
import model.Piece;
import gameengine.GameEngine;
import event.EventEngine;

class EventEngineTest {
    private static GameEngine engineWith(Piece[][] grid) {
        return new GameEngine(new Board(grid), new GameState());
    }

    @Test void testTwoClicksSelectThenMoveAPiece() {
        Piece[][] grid = new Piece[8][8];
        Piece rook = Piece.of(Piece.Color.WHITE, Piece.Type.R);
        grid[4][4] = rook;
        GameEngine engine = engineWith(grid);
        EventEngine events = new EventEngine(engine);

        events.handleClick(4, 4); // select the rook
        events.handleClick(4, 0); // request the move
        engine.advanceTime(100000);

        assertNull(engine.pieceAt(4, 4));
        assertEquals(rook, engine.pieceAt(4, 0));
    }

    @Test void testFirstClickOnEmptyCellSelectsNothing() {
        GameEngine engine = engineWith(new Piece[8][8]);
        EventEngine events = new EventEngine(engine);

        events.handleClick(4, 4); // nothing there, no selection made
        events.handleClick(4, 0); // treated as a fresh first click, still nothing there
        engine.advanceTime(1000);

        assertNull(engine.pieceAt(4, 0));
    }

    @Test void testClickingOwnOtherPieceReselectsInsteadOfMoving() {
        Piece[][] grid = new Piece[8][8];
        Piece rookA = Piece.of(Piece.Color.WHITE, Piece.Type.R);
        Piece rookB = Piece.of(Piece.Color.WHITE, Piece.Type.R);
        grid[4][4] = rookA;
        grid[4][0] = rookB;
        GameEngine engine = engineWith(grid);
        EventEngine events = new EventEngine(engine);

        events.handleClick(4, 4); // select rookA
        events.handleClick(4, 0); // re-select rookB (same color) instead of moving rookA there
        events.handleClick(0, 0); // now move rookB
        engine.advanceTime(100000);

        assertEquals(rookA, engine.pieceAt(4, 4)); // untouched
        assertNull(engine.pieceAt(4, 0));
        assertEquals(rookB, engine.pieceAt(0, 0));
    }

    @Test void testOutOfBoundsClickCancelsPendingSelection() {
        Piece[][] grid = new Piece[8][8];
        Piece rook = Piece.of(Piece.Color.WHITE, Piece.Type.R);
        grid[4][4] = rook;
        GameEngine engine = engineWith(grid);
        EventEngine events = new EventEngine(engine);

        events.handleClick(4, 4);   // select
        events.handleClick(-1, -1); // cancels the selection
        events.handleClick(4, 0);   // treated as a fresh first click (empty cell, no-op)
        engine.advanceTime(100000);

        assertEquals(rook, engine.pieceAt(4, 4)); // never moved
    }

    @Test void testBusyPieceCannotBeSelected() {
        Piece[][] grid = new Piece[8][8];
        Piece rook = Piece.of(Piece.Color.WHITE, Piece.Type.R);
        grid[4][4] = rook;
        GameEngine engine = engineWith(grid);
        EventEngine events = new EventEngine(engine);

        engine.requestMove(new model.Position(4, 4), new model.Position(4, 0)); // now mid-flight
        events.handleClick(4, 4); // busy - must not be selectable
        events.handleClick(0, 0); // if it had wrongly been selected, this would request a move
        engine.advanceTime(100000);

        assertEquals(rook, engine.pieceAt(4, 0)); // arrived at the original destination only
        assertNull(engine.pieceAt(0, 0));
    }

    @Test void testClickIsIgnoredAfterGameOver() {
        Piece[][] grid = new Piece[8][8];
        Piece rook = Piece.of(Piece.Color.WHITE, Piece.Type.R);
        Piece enemyKing = Piece.of(Piece.Color.BLACK, Piece.Type.K);
        grid[4][4] = rook;
        grid[4][0] = enemyKing;
        GameEngine engine = engineWith(grid);
        EventEngine events = new EventEngine(engine);

        engine.requestMove(new model.Position(4, 4), new model.Position(4, 0));
        engine.advanceTime(100000);
        assertTrue(engine.isGameOver());

        events.handleClick(4, 0);
        events.handleClick(0, 0);
        engine.advanceTime(100000);

        assertEquals(rook, engine.pieceAt(4, 0)); // still there - clicks were ignored
    }

    @Test void testHandleJumpDelegatesToEngine() {
        Piece[][] grid = new Piece[8][8];
        Piece rook = Piece.of(Piece.Color.WHITE, Piece.Type.R);
        grid[0][0] = rook;
        GameEngine engine = engineWith(grid);
        EventEngine events = new EventEngine(engine);

        events.handleJump(0, 0); // jumping the rook in place must start a jump move
        assertTrue(engine.isBusyAt(0, 0));
    }

    @Test void testWaitForDelegatesToEngine() {
        Piece[][] grid = new Piece[8][8];
        Piece rook = Piece.of(Piece.Color.WHITE, Piece.Type.R);
        grid[4][4] = rook;
        GameEngine engine = engineWith(grid);
        EventEngine events = new EventEngine(engine);

        engine.requestMove(new model.Position(4, 4), new model.Position(4, 0));
        events.waitFor(100000);

        assertEquals(rook, engine.pieceAt(4, 0));
    }

    @Test void testPrintDelegatesToEngine() {
        Piece[][] grid = new Piece[8][8];
        grid[0][0] = Piece.of(Piece.Color.WHITE, Piece.Type.K);
        GameEngine engine = engineWith(grid);
        EventEngine events = new EventEngine(engine);

        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream original = System.out;
        try {
            System.setOut(new PrintStream(captured));
            events.print();
        } finally {
            System.setOut(original);
        }

        assertTrue(captured.toString().contains("wK"));
    }
}
