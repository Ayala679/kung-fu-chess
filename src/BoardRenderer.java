import java.util.ArrayList;

public class BoardRenderer {
    private String[][] board;
    private ArrayList<MovingPiece> activeMoves;
    private GameState gameState;
    private int boardHeight;
    private int boardWidth;

    public BoardRenderer(String[][] board, ArrayList<MovingPiece> activeMoves, GameState gameState) {
        this.board = board;
        this.activeMoves = activeMoves;
        this.gameState = gameState;
        this.boardHeight = board.length;
        this.boardWidth = board.length > 0 ? board[0].length : 0;
    }

    public void printBoard() {
        if (board == null || boardHeight == 0 || boardWidth == 0) {
            return;
        }

        String[][] displayBoard = createDisplayBoard();

        for (int i = 0; i < boardHeight; i++) {
            for (int j = 0; j < boardWidth; j++) {
                System.out.print(displayBoard[i][j]);
                if (j < boardWidth - 1) {
                    System.out.print(" ");
                }
            }
            System.out.println();
        }
    }

    private String[][] createDisplayBoard() {
        String[][] display = new String[boardHeight][boardWidth];

        // Copy current board state
        for (int i = 0; i < boardHeight; i++) {
            for (int j = 0; j < boardWidth; j++) {
                display[i][j] = board[i][j];
            }
        }

        // Update with moving pieces
        long currentTime = gameState.getCurrentTime();
        for (MovingPiece mp : activeMoves) {
                if (currentTime < mp.getArrivalTime()) {
                // Piece is still in transit
                display[mp.getFromRow()][mp.getFromCol()] = mp.getPiece();
                if (mp.isMoving()) {
                    display[mp.getToRow()][mp.getToCol()] = Config.EMPTY;
                }
            } else {
                // Piece has arrived
                String finalPiece = mp.getPiece();
                if (mp.getPiece().charAt(1) == 'P' && (mp.getToRow() == 0 || mp.getToRow() == boardHeight - 1)) {
                    finalPiece = mp.getPiece().charAt(0) + "Q";
                }
                display[mp.getToRow()][mp.getToCol()] = finalPiece;
                if (mp.isMoving()) {
                    display[mp.getFromRow()][mp.getFromCol()] = Config.EMPTY;
                }
            }
        }

        return display;
    }
}
