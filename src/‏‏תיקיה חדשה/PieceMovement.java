package ruleengine;

 import model.Board;
import model.Piece;
import model.Position;

public interface PieceMovement {
    boolean isValid(Board board, Position from, Position to, Piece piece);
}

