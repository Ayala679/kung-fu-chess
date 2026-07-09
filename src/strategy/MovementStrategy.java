package strategy;

import model.Board;
import model.Piece;
import model.Position;

public interface MovementStrategy {
    boolean isValid(Board board, Position from, Position to, Piece piece);
}

