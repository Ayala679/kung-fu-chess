package gameengine;

import java.util.ArrayList;
import java.util.Scanner;
import config.GameConfig;
import model.GameState;
import model.MovingPiece;
import model.Board;
import model.Piece;
import model.Position;
import ruleengine.MoveValidator;
import view.BoardRenderer;

/**
 * GameLogic: Contains all game logic.
 * Uses model.Board which is a pure data model.
 */
public class GameLogic {
    private Board board;
    private ArrayList<MovingPiece> activeMoves;
    private GameState gameState;
    private MoveValidator validator;
    private BoardRenderer renderer;
    private Position selection = null;

    public GameLogic(Board board, GameState gameState) {
        this.board = board;
        this.gameState = gameState;
        this.activeMoves = new ArrayList<>();
        this.validator = new MoveValidator(board);
        this.renderer = new BoardRenderer(board, activeMoves, gameState);
    }

    public static GameLogic readFrom(Scanner sc) {
        ArrayList<String> boardRows = new ArrayList<>();
        boolean boardStarted = false;

        while (sc.hasNextLine()) {
            String line = sc.nextLine().trim();
            if (line.isEmpty()) continue;

            if (line.equals("Board:")) {
                boardStarted = true;
                continue;
            }
            if (line.equals("Commands:")) {
                break;
            }

            if (boardStarted) {
                boardRows.add(line);
            }
        }

        if (boardRows.isEmpty()) {
            return new GameLogic(new Board(new Piece[0][0]), new GameState());
        }

        Piece[][] grid = parseBoard(boardRows);
        return new GameLogic(new Board(grid), new GameState());
    }

    private static Piece[][] parseBoard(ArrayList<String> boardRows) {
        String[] firstRow = boardRows.get(0).split("\\s+");
        int cols = firstRow.length;
        Piece[][] grid = new Piece[boardRows.size()][cols];

        for (int i = 0; i < boardRows.size(); i++) {
            String[] tokens = boardRows.get(i).split("\\s+");
            for (int j = 0; j < tokens.length; j++) {
                grid[i][j] = Piece.fromToken(tokens[j]);
            }
        }

        return grid;
    }

    public boolean isEmpty() {
        return board.isEmpty();
    }

    public boolean isValid() {
        if (isEmpty()) return false;

        for (int i = 0; i < board.getHeight(); i++) {
            for (int j = 0; j < board.getWidth(); j++) {
                Piece token = board.getCell(i, j);
                if (token == null && !validator.isValidToken(null)) {
                    System.out.println("ERROR");
                    return false;
                }
            }
        }
        return true;
    }

    public void handleClickCell(int row, int col) {
        if (gameState.isGameOver()) return;
        if (row < 0 || row >= board.getHeight() || col < 0 || col >= board.getWidth()) return;

        updateBoardTime();
        Piece clickedPiece = board.getCell(row, col);

        if (selection == null) {
            // First selection
            boolean isMoving = isMovingAt(row, col);
            if (clickedPiece != null && !isMoving) {
                selection = new Position(row, col);
            }
        } else {
            // Second selection
            Piece selectedPiece = board.getCell(selection.getRow(), selection.getCol());
            if (clickedPiece != null && clickedPiece.getColor() == selectedPiece.getColor()) {
                selection = new Position(row, col);
            } else {
                attemptMove(selectedPiece, selection, new Position(row, col));
                selection = null;
            }
        }
    }

    public void handleJumpCell(int row, int col) {
        if (gameState.isGameOver()) return;
        if (row < 0 || row >= board.getHeight() || col < 0 || col >= board.getWidth()) return;

        updateBoardTime();
        Piece piece = board.getCell(row, col);
        if (piece != null) {
            boolean alreadyMoving = isMovingAt(row, col);
            if (!alreadyMoving) {
                boolean jumpTooLate = checkIfTooLate(row, col, piece);
                if (jumpTooLate) {
                    board.setCell(row, col, null);
                    if (piece.getType() == Piece.Type.K) {
                        gameState.setGameOver();
                    }
                } else {
                    createJumpMove(piece, new Position(row, col));
                }
                updateBoardTime();
            }
        }
    }

    public void handleWait(long ms) {
        if (gameState.isGameOver()) return;

        gameState.advanceTime(ms);
        updateBoardTime();
    }

    public void printBoard() {
        updateBoardTime();
        renderer.printBoard();
    }

    private void attemptMove(Piece piece, Position from, Position to) {
        if (piece == null) return;
        if (!validator.isValidMove(piece, from, to)) {
            return;
        }

        Piece destination = board.getCell(to);
        if (destination == null || destination.getColor() != piece.getColor()) {
            int maxDistance = Math.max(from.rowDistance(to), from.colDistance(to));
            long duration = (piece.getType() == Piece.Type.N) ? GameConfig.KNIGHT_TOTAL_DURATION : (maxDistance * GameConfig.MOVE_DURATION_PER_CELL);
            createMove(piece, from, to, duration);
        }
    }

    private void createMove(Piece piece, Position from, Position to, long duration) {
        MovingPiece mp = new MovingPiece(piece, from, to, duration, gameState.getCurrentTime());
        activeMoves.add(mp);
    }

    private void createJumpMove(Piece piece, Position pos) {
        MovingPiece mp = new MovingPiece(piece, pos, pos, GameConfig.JUMP_DURATION, gameState.getCurrentTime());
        activeMoves.add(mp);
    }

    private boolean isMovingAt(int row, int col) {
        Position pos = new Position(row, col);
        for (MovingPiece mp : activeMoves) {
            if (gameState.getCurrentTime() < mp.getArrivalTime()) {
                if (mp.getFrom().equals(pos) || mp.getTo().equals(pos)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean checkIfTooLate(int row, int col, Piece piece) {
        for (MovingPiece active : activeMoves) {
            if (active.isMoving()) {
                Position to = active.getTo();
                if (to.getRow() == row && to.getCol() == col) {
                    long enemyStart = active.getArrivalTime() - active.getDuration();
                    if (enemyStart <= gameState.getCurrentTime() && active.getPiece().getColor() != piece.getColor()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private void updateBoardTime() {
        long currentTime = gameState.getCurrentTime();

        // Check air captures
        for (int i = activeMoves.size() - 1; i >= 0; i--) {
            MovingPiece move = activeMoves.get(i);
            if (move.isMoving()) {
                long enemyStart = move.getArrivalTime() - move.getDuration();
                boolean eatenByAirborne = false;

                for (MovingPiece jump : activeMoves) {
                    if (!jump.isMoving()) {
                        Position jt = jump.getTo();
                        Position mt = move.getTo();
                        if (jt.getRow() == mt.getRow() && jt.getCol() == mt.getCol()) {
                            long jumpStart = jump.getArrivalTime() - jump.getDuration();
                            if (jumpStart <= enemyStart && jump.getPiece().getColor() != move.getPiece().getColor()) {
                                eatenByAirborne = true;
                                break;
                            }
                        }
                    }
                }

                if (eatenByAirborne) {
                    Position fr = move.getFrom();
                    board.setCell(fr, null);
                    if (move.getPiece().getType() == Piece.Type.K) {
                        gameState.setGameOver();
                    }
                    activeMoves.remove(i);
                }
            }
        }

        // Check move arrivals and update board
        for (int i = activeMoves.size() - 1; i >= 0; i--) {
            MovingPiece mp = activeMoves.get(i);
            if (currentTime >= mp.getArrivalTime()) {
                if (mp.isMoving()) {
                    Piece finalPiece = mp.getPiece();
                    Position to = mp.getTo();
                    if (finalPiece.getType() == Piece.Type.P && (to.getRow() == 0 || to.getRow() == board.getHeight() - 1)) {
                        // promote to Queen
                        finalPiece = Piece.fromToken((finalPiece.getColor() == Piece.Color.WHITE ? "w" : "b") + "Q");
                    }

                    Piece destination = board.getCell(to);
                    if (destination != null && destination.getType() == Piece.Type.K) {
                        gameState.setGameOver();
                    }

                    board.setCell(to, finalPiece);
                    Position fr = mp.getFrom();
                    board.setCell(fr, null);
                }
                activeMoves.remove(i);
            }
        }
    }
}