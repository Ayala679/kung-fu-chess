package view;

import java.util.List;
import model.Position;
import config.GameConfig;
import model.GameState;
import model.MovingPiece;
import model.Piece;
import model.Board;

public class BoardRenderer {
    private final Board board;
    private final List<MovingPiece> activeMoves;
    private final GameState gameState;
    private final int boardHeight;
    private final int boardWidth;

    public BoardRenderer(Board board, List<MovingPiece> activeMoves, GameState gameState) {
        this.board = board;
        this.activeMoves = activeMoves;
        this.gameState = gameState;
        this.boardHeight = board.getHeight();
        this.boardWidth = board.getWidth();
    }

    public void printBoard() {
        if (board == null || boardHeight == 0 || boardWidth == 0) {
            return;
        }

        String[][] displayBoard = createDisplayBoard();

        for (int i = 0; i < boardHeight; i++) {
            for (int j = 0; j < boardWidth; j++) {
                System.out.print(displayBoard[i][j]);
                if (j < boardWidth - 1) System.out.print(" ");
            }
            System.out.println();
        }
    }

    private String[][] createDisplayBoard() {
        String[][] display = new String[boardHeight][boardWidth];

        // Copy current board state
        for (int i = 0; i < boardHeight; i++) {
            for (int j = 0; j < boardWidth; j++) {
                Piece p = board.getCell(i, j);
                display[i][j] = (p == null) ? GameConfig.EMPTY : p.toToken();
            }
        }

        // Update with moving pieces
        long currentTime = gameState.getCurrentTime();
        for (MovingPiece mp : activeMoves) {
            if (currentTime < mp.getArrivalTime()) {
                // Piece is still in transit
                Position f = mp.getFrom();
                Position t = mp.getTo();
                display[f.getRow()][f.getCol()] = mp.getPiece().toToken();
                if (mp.isMoving()) {
                    display[t.getRow()][t.getCol()] = GameConfig.EMPTY;
                }
            } else {
                // Piece has arrived
                Piece piece = mp.getPiece();
                Position t = mp.getTo();
                Piece finalPiece = piece;
                if (piece.getType() == Piece.Type.P && (t.getRow() == 0 || t.getRow() == boardHeight - 1)) {
                    finalPiece = Piece.fromToken((piece.getColor() == Piece.Color.WHITE ? "w" : "b") + "Q");
                }
                display[t.getRow()][t.getCol()] = finalPiece.toToken();
                if (mp.isMoving()) {
                    Position f = mp.getFrom();
                    display[f.getRow()][f.getCol()] = GameConfig.EMPTY;
                }
            }
        }

        return display;
    }
}

