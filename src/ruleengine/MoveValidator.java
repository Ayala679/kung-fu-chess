package ruleengine;

import model.Board;
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
        if (!board.inBounds(from) || !board.inBounds(to)) return false;

        Piece mover = board.getCell(from);
        if (mover == null) return false;

        Piece destination = board.getCell(to);
        return destination == null || destination.getColor() != mover.getColor();
    }

    /** Are all cells strictly between 'from' and 'to' empty? */
    public boolean isPathClear(Position from, Position to) {
        int rowStep = Integer.compare(to.getRow(), from.getRow());
        int colStep = Integer.compare(to.getCol(), from.getCol());
        int currentRow = from.getRow() + rowStep;
        int currentCol = from.getCol() + colStep;

        while (currentRow != to.getRow() || currentCol != to.getCol()) {
            if (board.getCell(currentRow, currentCol) != null) return false;
            currentRow += rowStep;
            currentCol += colStep;
        }
        return true;
    }
}
