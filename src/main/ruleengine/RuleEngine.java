package ruleengine;

import java.util.Collections;
import java.util.List;

import model.Board;
import model.MovingPiece;
import model.Piece;
import model.Position;

/**
 * RuleEngine: the single entry point for the question "is this move allowed?".
 *
 * It combines the general checks that hold for any piece ({@link MoveValidator})
 * with the piece-type-specific geometry ({@link PieceRules}). It only inspects
 * the board and never changes anything.
 */
public class RuleEngine {
    private final Board board;
    private final MoveValidator validator;

    public RuleEngine(Board board) {
        this.board = board;
        this.validator = new MoveValidator(board);
    }

    public boolean isMoveAllowed(Position from, Position to) {
        return isMoveAllowed(from, to, Collections.emptyList(), 0);
    }

    /**
     * Same as {@link #isMoveAllowed(Position, Position)}, but aware of pieces
     * currently in flight: a cell whose occupant already departed on a move of
     * its own doesn't block this move, even though the board itself only
     * clears that cell on arrival.
     */
    public boolean isMoveAllowed(Position from, Position to, List<MovingPiece> activeMoves, long currentTime) {
        if (!validator.isGeneralMoveValid(from, to, activeMoves, currentTime)) return false;
        Piece piece = board.getCell(from);
        return PieceRules.isValid(piece.getType(), board, from, to, piece, activeMoves, currentTime);
    }
}
