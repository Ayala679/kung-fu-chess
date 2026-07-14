package event;

import gameengine.GameEngine;
import model.Piece;
import model.Position;

/**
 * EventEngine: the input-side front controller.
 *
 * It turns raw cell clicks into game intent (select / cancel / re-select / move
 * request) and forwards ready requests to the GameEngine. It owns the current
 * selection so the engine stays free of UI state.
 *
 * Click rules:
 *   - first click on a piece            -> select it
 *   - click on our own other piece      -> re-select
 *   - click outside board, no selection -> nothing
 *   - click outside board, with select  -> cancel selection
 *   - second click inside the board      -> send a move request
 */
public class EventEngine {
    private final GameEngine engine;
    private Position selection = null;

    public EventEngine(GameEngine engine) {
        this.engine = engine;
    }

    public void handleClick(int row, int col) {
        if (engine.isGameOver()) return;

        if (!engine.inBounds(row, col)) {
            selection = null; // cancel a pending selection; no-op otherwise
            return;
        }

        engine.refreshTime();
        Piece clicked = engine.pieceAt(row, col);

        if (selection == null) {
            // First selection: pick a piece that isn't mid-move.
            if (clicked != null && !engine.isAlreadyMoving(row, col)) {
                selection = new Position(row, col);
            }
            return;
        }

        // Second click.
        Piece selectedPiece = engine.pieceAt(selection.getRow(), selection.getCol());
        if (clicked != null && selectedPiece != null && clicked.getColor() == selectedPiece.getColor()) {
            selection = new Position(row, col); // re-select our own other piece
        } else {
            engine.requestMove(selection, new Position(row, col));
            selection = null;
        }
    }

    public void handleJump(int row, int col) {
        engine.requestJump(row, col);
    }

    public void waitFor(long ms) {
        engine.advanceTime(ms);
    }

    public void print() {
        engine.printBoard();
    }
}
