package view;

import java.util.List;
import model.Position;
import config.GameConfig;
import model.GameState;
import model.MovingPiece;
import model.Piece;
import model.Board;
import parsing.PieceMapper;

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
                display[i][j] = (p == null) ? GameConfig.EMPTY : PieceMapper.format(p);
            }
        }

        // Update with moving pieces
        long currentTime = gameState.getCurrentTime();
        for (MovingPiece mp : activeMoves) {
            if (currentTime < mp.getArrivalTime()) {
                // Piece is still in transit
                Position f = mp.getFrom();
                Position t = mp.getTo();
                display[f.getRow()][f.getCol()] = PieceMapper.format(mp.getPiece());
                if (mp.isMoving()) {
                    display[t.getRow()][t.getCol()] = GameConfig.EMPTY;
                }
            } else {
                // Piece has arrived
                Piece piece = mp.getPiece();
                Position t = mp.getTo();
                Piece finalPiece = piece.promotedAt(t.getRow(), boardHeight);
                display[t.getRow()][t.getCol()] = PieceMapper.format(finalPiece);
                if (mp.isMoving()) {
                    Position f = mp.getFrom();
                    display[f.getRow()][f.getCol()] = GameConfig.EMPTY;
                }
            }
        }

        return display;
    }
}

