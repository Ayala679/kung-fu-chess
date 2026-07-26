package event;

import gameengine.GameEngine;
import model.Piece;
import model.Position;

/**
 * The click state machine shared by local play ({@link EventEngine}, one
 * shared selection) and networked play ({@code server.GameSession}, one
 * selection per color) - previously duplicated between the two, which is
 * exactly the kind of place a fix (or a bug) can land in one copy and not
 * the other. Both now delegate here; each caller only owns *where* the
 * current selection is stored.
 *
 * Click rules:
 *   - first click on a piece              -> select it
 *   - second click on that same piece     -> cancel selection
 *   - click on our own other piece        -> re-select
 *   - click outside board, no selection   -> nothing
 *   - click outside board, with selection -> cancel selection
 *   - second click elsewhere inside the board -> send a move request
 */
public final class ClickSelector {
    private ClickSelector() {}

    /** What this click actually did, beyond just the resulting selection - lets a caller that cares (server.GameSession) tell a real move attempt from a mere select/deselect/reselect. */
    public enum Outcome { NO_MOVE_ATTEMPTED, MOVE_ACCEPTED, MOVE_REJECTED }

    /** The next selection to store, plus what this click did (see {@link Outcome}). */
    public record Result(Position selection, Outcome outcome) {}

    /**
     * @param selection     the caller's current selection (null if none)
     * @param requiredColor if non-null, only a piece of this color may ever
     *                      be selected - used to keep a networked player
     *                      from ever selecting (or, via re-select, hopping
     *                      onto) the opponent's pieces. Null means any piece
     *                      may be selected, matching local/offline play
     *                      where one mouse controls both sides.
     * @return the resulting selection plus what this click did (never
     *         mutates the engine except via {@link GameEngine#requestMove}
     *         when a move is sent)
     */
    public static Result handleClick(GameEngine engine, Position selection, int row, int col, Piece.Color requiredColor) {
        if (engine.isGameOver()) return new Result(selection, Outcome.NO_MOVE_ATTEMPTED);
        if (!engine.inBounds(row, col)) return new Result(null, Outcome.NO_MOVE_ATTEMPTED); // cancel a pending selection; no-op otherwise

        engine.refreshTime();
        Piece clicked = engine.pieceAt(row, col);

        if (selection == null) {
            // First selection: pick a piece that isn't mid-move or resting.
            if (clicked != null && (requiredColor == null || clicked.getColor() == requiredColor)
                    && !engine.isAlreadyMoving(row, col) && !engine.isResting(row, col)) {
                return new Result(new Position(row, col), Outcome.NO_MOVE_ATTEMPTED);
            }
            return new Result(null, Outcome.NO_MOVE_ATTEMPTED);
        }

        // Second click.
        Position clickedPos = new Position(row, col);
        if (clickedPos.equals(selection)) {
            return new Result(null, Outcome.NO_MOVE_ATTEMPTED); // clicking the already-selected piece again cancels the selection
        }

        Piece selectedPiece = engine.pieceAt(selection.getRow(), selection.getCol());
        if (clicked != null && selectedPiece != null && clicked.getColor() == selectedPiece.getColor()) {
            return new Result(clickedPos, Outcome.NO_MOVE_ATTEMPTED); // re-select our own other piece
        }

        boolean accepted = engine.requestMove(selection, clickedPos);
        return new Result(null, accepted ? Outcome.MOVE_ACCEPTED : Outcome.MOVE_REJECTED);
    }
}
