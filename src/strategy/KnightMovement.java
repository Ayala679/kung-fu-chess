package strategy;

import model.Board;
import model.Piece;
import model.Position;

public class KnightMovement implements MovementStrategy {
    @Override
    public boolean isValid(Board board, Position from, Position to, Piece piece) {
        if (piece == null || piece.getType() != Piece.Type.N) return false;
        int dr = Math.abs(from.getRow() - to.getRow());
        int dc = Math.abs(from.getCol() - to.getCol());
        return (dr == 2 && dc == 1) || (dr == 1 && dc == 2);
    }
}

