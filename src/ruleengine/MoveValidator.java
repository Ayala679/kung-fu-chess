package ruleengine;

import java.util.Collections;
import java.util.List;

import model.Board;
import model.MovingPiece;
import model.Piece;
import model.Position;

/**
 * MoveValidator: general movement checks that hold for ANY piece, regardless of
 * its type — there is a piece to move, it does not land on a friendly piece,
 * and (when needed) the path between squares is clear.
 *
 * Piece-type-specific geometry lives in {@link PieceRules}; token/format
 * validation lives in the parsing layer. This class only inspects the board and
 * depends on nothing but the model.
 */
public class MoveValidator {
    private final Board board;

    public MoveValidator(Board board) {
        this.board = board;
    }

    /**
     * General legality, independent of piece type:
     *   (1) 'from' and 'to' are both on the board;
     *   (2) there is a piece at 'from';
     *   (3) 'to' is not occupied by a piece of the same color.
     */
    public boolean isGeneralMoveValid(Position from, Position to) {
        return isGeneralMoveValid(from, to, Collections.emptyList(), 0);
    }

    /**
     * Same as {@link #isGeneralMoveValid(Position, Position)}, but a cell whose
     * occupant already has an active outgoing move (real-time: it's mid-flight
     * away, even though the board itself isn't cleared until arrival) is treated
     * as empty rather than friendly/enemy-occupied.
     */
    public boolean isGeneralMoveValid(Position from, Position to, List<MovingPiece> activeMoves, long currentTime) {
        if (!board.inBounds(from) || !board.inBounds(to)) return false;

        Piece mover = board.getCell(from);
        if (mover == null) return false;

        Piece destination = effectivePieceAt(to, activeMoves, currentTime);
        return destination == null || destination.getColor() != mover.getColor();
    }

    /** Are all cells strictly between 'from' and 'to' empty? */
    public boolean isPathClear(Position from, Position to) {
        return isPathClear(from, to, Collections.emptyList(), 0);
    }

    /**
     * Same as {@link #isPathClear(Position, Position)}, but a cell whose
     * occupant already has an active outgoing move away from it doesn't block
     * the path - see {@link #isGeneralMoveValid(Position, Position, List, long)}.
     */
    public boolean isPathClear(Position from, Position to, List<MovingPiece> activeMoves, long currentTime) {
        int rowStep = Integer.compare(to.getRow(), from.getRow());
        int colStep = Integer.compare(to.getCol(), from.getCol());
        int currentRow = from.getRow() + rowStep;
        int currentCol = from.getCol() + colStep;

        while (currentRow != to.getRow() || currentCol != to.getCol()) {
            if (effectivePieceAt(new Position(currentRow, currentCol), activeMoves, currentTime) != null) return false;
            currentRow += rowStep;
            currentCol += colStep;
        }
        return true;
    }

    /**
     * What's really at this cell right now: null if empty, or if the piece
     * sitting there (per the static board) has already departed on an active
     * move of its own and hasn't arrived yet.
     */
    private Piece effectivePieceAt(Position pos, List<MovingPiece> activeMoves, long currentTime) {
        Piece piece = board.getCell(pos);
        if (piece == null) return null;

        for (MovingPiece mp : activeMoves) {
            if (mp.isMoving() && currentTime < mp.getArrivalTime() && mp.getFrom().equals(pos)) {
                return null;
            }
        }
        return piece;
    }
}
