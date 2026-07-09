package ruleengine;

import model.Board;
import model.Position;
import model.Piece;
import config.GameConfig;

public class MoveValidator {
    private final Board board;

    public MoveValidator(Board board) {
        this.board = board;
    }

    public boolean isValidMove(Piece piece, Position from, Position to) {
        if (piece == null) return false;
        return PieceMovementRegistry.isValid(piece.getType(), board, from, to, piece);
    }

    public boolean isPathClear(Position from, Position to) {
        int rowStep = Integer.compare(to.getRow(), from.getRow());
        int colStep = Integer.compare(to.getCol(), from.getCol());
        int currentRow = from.getRow() + rowStep;
        int currentCol = from.getCol() + colStep;

        while (currentRow != to.getRow() || currentCol != to.getCol()) {
            Piece p = board.getCell(currentRow, currentCol);
            if (p != null) return false;
            currentRow += rowStep;
            currentCol += colStep;
        }
        return true;
    }

    public boolean isValidToken(String token) {
        return token == null || token.equals(GameConfig.EMPTY) || token.matches(GameConfig.TOKEN_PATTERN);
    }
}

