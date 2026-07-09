package strategy;

import model.Board;
import model.Piece;
import model.Position;

/**
 * Minimal pawn movement rules (forward 1). This is a placeholder for a richer implementation.
 */
public class PawnMovement implements MovementStrategy {
    @Override
    public boolean isValid(Board board, Position from, Position to, Piece piece) {
        if (piece == null || piece.getType() != Piece.Type.P) return false;
        int dir = piece.getColor() == Piece.Color.WHITE ? -1 : 1;
        int expectedRow = from.getRow() + dir;
        return to.getCol() == from.getCol() && to.getRow() == expectedRow && board.getCell(to) == null;
    }
}

