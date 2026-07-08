public class MoveValidator {
    private String[][] board;
    private int boardHeight;
    private int boardWidth;

    public MoveValidator(String[][] board) {
        this.board = board;
        this.boardHeight = board.length;
        this.boardWidth = board.length > 0 ? board[0].length : 0;
    }

    public boolean isValidMove(String piece, int fromRow, int fromCol, int toRow, int toCol) {
        // Delegate to registry so new piece types can be registered later
        if (piece == null || piece.length() < 2) return false;
        char pieceType = piece.charAt(1);
        return PieceMovementRegistry.isValid(pieceType, board, fromRow, fromCol, toRow, toCol, piece);
    }

    private boolean isValidPawnMove(int fromRow, int fromCol, int toRow, int toCol, char color) {
        int direction = (color == 'w') ? -1 : 1;
        int rowDiff = toRow - fromRow;
        int colDistance = Math.abs(toCol - fromCol);
        String destination = board[toRow][toCol];

        // Single step forward
        if (rowDiff == direction && colDistance == 0 && destination.equals(".")) {
            return true;
        }

        // Two steps from starting row
        int startRow = (color == 'w') ? (boardHeight - 2) : 1;
        if (rowDiff == 2 * direction && colDistance == 0 && fromRow == startRow && destination.equals(".") && isPathClear(fromRow, fromCol, toRow, toCol)) {
            return true;
        }

        // Diagonal capture
        if (rowDiff == direction && colDistance == 1 && !destination.equals(".") && destination.charAt(0) != color) {
            return true;
        }

        return false;
    }

    public boolean isPathClear(int fromRow, int fromCol, int toRow, int toCol) {
        int rowStep = Integer.compare(toRow, fromRow);
        int colStep = Integer.compare(toCol, fromCol);
        int currentRow = fromRow + rowStep;
        int currentCol = fromCol + colStep;

        while (currentRow != toRow || currentCol != toCol) {
            if (!board[currentRow][currentCol].equals(Config.EMPTY)) {
                return false;
            }
            currentRow += rowStep;
            currentCol += colStep;
        }
        return true;
    }

    public boolean isValidToken(String token) {
        return token.equals(Config.EMPTY) || token.matches(Config.TOKEN_PATTERN);
    }
}
