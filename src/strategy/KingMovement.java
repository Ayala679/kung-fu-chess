package strategy;

import model.Board;
import model.Piece;
import model.Position;

public class KingMovement implements MovementStrategy {
    @Override
    public boolean isValid(Board board, Position from, Position to, Piece piece) {
        if (piece == null || piece.getType() != Piece.Type.K) return false;
        int dr = Math.abs(from.getRow() - to.getRow());
        int dc = Math.abs(from.getCol() - to.getCol());
        return dr <= 1 && dc <= 1;
    }
}

