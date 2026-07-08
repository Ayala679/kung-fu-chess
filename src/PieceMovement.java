public interface PieceMovement {
    boolean isValid(String[][] board, int fromRow, int fromCol, int toRow, int toCol, String piece);
}

