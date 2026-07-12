package ruleengine;

import model.Board;
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
        if (!validator.isGeneralMoveValid(from, to)) return false;
        Piece piece = board.getCell(from);
        return PieceRules.isValid(piece.getType(), board, from, to, piece);
    }
}
