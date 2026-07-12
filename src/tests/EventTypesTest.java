package tests;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import model.Board;
import model.GameState;
import model.Piece;
import model.Position;
import gameengine.GameEngine;
import event.CellClickEvent;
import event.ClickEvent;
import event.ClickEventImpl;
import event.EventEngine;
import event.JumpEventImpl;
import event.PrintBoardEventImpl;
import event.WaitEventImpl;

class EventTypesTest {
    @Test void testClickEventGettersAndToString() {
        ClickEvent e = new ClickEvent(10, 20);
        assertEquals(10, e.getX());
        assertEquals(20, e.getY());
        assertEquals("ClickEvent(x=10, y=20)", e.toString());
    }

    @Test void testCellClickEventGettersAndToString() {
        CellClickEvent e = new CellClickEvent(2, 3);
        assertEquals(2, e.getRow());
        assertEquals(3, e.getCol());
        assertEquals("CellClickEvent(row=2, col=3)", e.toString());
    }

    @Test void testClickEventImplExecuteSelectsAPiece() {
        Piece[][] grid = new Piece[8][8];
        Piece rook = Piece.of(Piece.Color.WHITE, Piece.Type.R);
        grid[4][4] = rook;
        GameEngine engine = new GameEngine(new Board(grid), new GameState());
        EventEngine events = new EventEngine(engine);

        // pixel (450,450) with cell size 100 -> board cell (4,4)
        new ClickEventImpl(450, 450).execute(events);
        new ClickEventImpl(0, 450).execute(events); // move to (4,0)
        engine.advanceTime(100000);

        assertEquals(rook, engine.pieceAt(4, 0));
        assertEquals("ClickEvent(x=0, y=450)", new ClickEventImpl(0, 450).toString());
    }

    @Test void testJumpEventImplExecuteDelegatesToHandleJump() {
        Piece[][] grid = new Piece[8][8];
        Piece rook = Piece.of(Piece.Color.WHITE, Piece.Type.R);
        grid[0][0] = rook;
        GameEngine engine = new GameEngine(new Board(grid), new GameState());
        EventEngine events = new EventEngine(engine);

        new JumpEventImpl(0, 0).execute(events); // pixel -> cell (0,0), jump the rook in place

        assertTrue(engine.isBusyAt(0, 0));
        assertEquals("JumpEvent(x=350, y=0)", new JumpEventImpl(350, 0).toString());
    }

    @Test void testPrintBoardEventImplToStringAndExecuteDoesNotThrow() {
        EventEngine events = new EventEngine(new GameEngine(new Board(new Piece[8][8]), new GameState()));
        PrintBoardEventImpl event = new PrintBoardEventImpl();
        assertEquals("PrintBoardEvent", event.toString());
        assertDoesNotThrow(() -> event.execute(events));
    }

    @Test void testWaitEventImplExecuteAdvancesTime() {
        Piece[][] grid = new Piece[8][8];
        Piece rook = Piece.of(Piece.Color.WHITE, Piece.Type.R);
        grid[4][4] = rook;
        GameEngine engine = new GameEngine(new Board(grid), new GameState());
        EventEngine events = new EventEngine(engine);

        engine.requestMove(new Position(4, 4), new Position(4, 0));
        WaitEventImpl wait = new WaitEventImpl(100000);
        assertEquals(100000, wait.getMs());
        assertEquals("WaitEvent(ms=100000)", wait.toString());
        wait.execute(events);

        assertEquals(rook, engine.pieceAt(4, 0));
    }
}
