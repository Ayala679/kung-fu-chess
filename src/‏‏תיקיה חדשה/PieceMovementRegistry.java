package ruleengine;

import model.Board;
import model.Piece;
import model.Position;
import strategy.MovementStrategy;
import java.util.HashMap;
import java.util.Map;

public class PieceMovementRegistry {
    private static final Map<Piece.Type, MovementStrategy> registry = new HashMap<>();

    static {
        // Register default pieces using the new MovementStrategy interface
        registry.put(Piece.Type.K, (board, from, to, piece) -> {
            int rd = Math.abs(to.getRow() - from.getRow());
            int cd = Math.abs(to.getCol() - from.getCol());
            return rd <= 1 && cd <= 1;
        });

        registry.put(Piece.Type.R, (board, from, to, piece) -> {
            int rd = Math.abs(to.getRow() - from.getRow());
            int cd = Math.abs(to.getCol() - from.getCol());
            return (rd == 0 || cd == 0) && new MoveValidator(board).isPathClear(from, to);
        });

        registry.put(Piece.Type.B, (board, from, to, piece) -> {
            int rd = Math.abs(to.getRow() - from.getRow());
            int cd = Math.abs(to.getCol() - from.getCol());
            return (rd == cd) && new MoveValidator(board).isPathClear(from, to);
        });

        registry.put(Piece.Type.Q, (board, from, to, piece) -> {
            int rd = Math.abs(to.getRow() - from.getRow());
            int cd = Math.abs(to.getCol() - from.getCol());
            return (rd == 0 || cd == 0 || rd == cd) && new MoveValidator(board).isPathClear(from, to);
        });

        registry.put(Piece.Type.N, (board, from, to, piece) -> {
            int rd = Math.abs(to.getRow() - from.getRow());
            int cd = Math.abs(to.getCol() - from.getCol());
            return (rd == 1 && cd == 2) || (rd == 2 && cd == 1);
        });

        // Pawn default logic
        registry.put(Piece.Type.P, (board, from, to, piece) -> {
            if (piece == null) return false;
            Piece.Color color = piece.getColor();
            int direction = (color == Piece.Color.WHITE) ? -1 : 1;
            int rowDiff = to.getRow() - from.getRow();
            int colDistance = Math.abs(to.getCol() - from.getCol());
            Piece destination = board.getCell(to);

            // single step
            if (rowDiff == direction && colDistance == 0 && destination == null) return true;

            // two steps from starting row
            int startRow = (color == Piece.Color.WHITE) ? (board.getHeight() - 2) : 1;
            if (rowDiff == 2 * direction && colDistance == 0 && from.getRow() == startRow && destination == null && new MoveValidator(board).isPathClear(from, to)) return true;

            // capture diagonal
            if (rowDiff == direction && colDistance == 1 && destination != null && destination.getColor() != color) return true;

            return false;
        });
    }

    public static void register(Piece.Type type, MovementStrategy movement) {
        registry.put(type, movement);
    }

    public static boolean isValid(Piece.Type type, Board board, Position from, Position to, Piece piece) {
        MovementStrategy ms = registry.get(type);
        if (ms == null) return false;
        return ms.isValid(board, from, to, piece);
    }
}

