package tests;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import model.Board;
import model.GameState;
import model.Piece;
import model.Position;
import gameengine.GameEngine;
import event.EventDispatcher;
import event.EventEngine;
import event.GameEvent;

class EventDispatcherTest {
    @Test void testDispatchNullEventDoesNotThrow() {
        EventEngine events = new EventEngine(new GameEngine(new Board(new Piece[8][8]), new GameState()));
        EventDispatcher dispatcher = new EventDispatcher(events);
        assertDoesNotThrow(() -> dispatcher.dispatch(null));
    }

    @Test void testDispatchCatchesExceptionsFromEventExecution() {
        EventEngine events = new EventEngine(new GameEngine(new Board(new Piece[8][8]), new GameState()));
        EventDispatcher dispatcher = new EventDispatcher(events);
        GameEvent broken = eventEngine -> { throw new RuntimeException("boom"); };

        assertDoesNotThrow(() -> dispatcher.dispatch(broken));
    }

    @Test void testDispatchFromCommandRunsAMappedCommand() {
        Piece[][] grid = new Piece[8][8];
        Piece rook = Piece.of(Piece.Color.WHITE, Piece.Type.R);
        grid[4][4] = rook;
        GameEngine engine = new GameEngine(new Board(grid), new GameState());
        EventDispatcher dispatcher = new EventDispatcher(new EventEngine(engine));

        engine.requestMove(new Position(4, 4), new Position(4, 0));
        dispatcher.dispatchFromCommand("wait 100000"); // "wait" command should advance real time

        assertEquals(rook, engine.pieceAt(4, 0));
    }

    @Test void testDispatchFromCommandWithUnknownCommandDoesNotThrow() {
        EventEngine events = new EventEngine(new GameEngine(new Board(new Piece[8][8]), new GameState()));
        EventDispatcher dispatcher = new EventDispatcher(events);
        assertDoesNotThrow(() -> dispatcher.dispatchFromCommand("nonsense 1 2 3"));
    }
}
