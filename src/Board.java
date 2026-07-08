import java.util.ArrayList;
import java.util.Scanner;

public class Board {
    private String[][] grid;
    private int height;
    private int width;
    private ArrayList<MovingPiece> activeMoves;
    private GameState gameState;
    private MoveValidator validator;
    private BoardRenderer renderer;
    private int[] selection = {-1, -1};

    private static final long KNIGHT_DURATION = 3000;
    private static final long NORMAL_MOVE_DURATION = 1000;

    public Board(String[][] grid, GameState gameState) {
        this.grid = grid;
        this.height = grid.length;
        this.width = grid.length > 0 ? grid[0].length : 0;
        this.gameState = gameState;
        this.activeMoves = new ArrayList<>();
        this.validator = new MoveValidator(grid);
        this.renderer = new BoardRenderer(grid, activeMoves, gameState);
    }

    public static Board readFrom(Scanner sc) {
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
            return new Board(new String[0][0], new GameState());
        }

        String[][] grid = parseBoard(boardRows);
        return new Board(grid, new GameState());
    }

    private static String[][] parseBoard(ArrayList<String> boardRows) {
        String[] firstRow = boardRows.get(0).split("\\s+");
        int cols = firstRow.length;
        String[][] grid = new String[boardRows.size()][cols];

        for (int i = 0; i < boardRows.size(); i++) {
            String[] tokens = boardRows.get(i).split("\\s+");
            for (int j = 0; j < tokens.length; j++) {
                grid[i][j] = tokens[j];
            }
        }

        return grid;
    }

    public boolean isEmpty() {
        return height == 0 || width == 0;
    }

    public boolean isValid() {
        if (isEmpty()) return false;

        for (int i = 0; i < height; i++) {
            for (int j = 0; j < width; j++) {
                String token = grid[i][j];
                if (token == null || !validator.isValidToken(token)) {
                    System.out.println("ERROR");
                    return false;
                }
            }
        }
        return true;
    }

    public void handleClick(int x, int y) {
        if (gameState.isGameOver()) return;

        int col = x / 100;
        int row = y / 100;

        if (row < 0 || row >= height || col < 0 || col >= width) return;

        String clickedCell = grid[row][col];

        if (selection[0] == -1) {
            // First selection
            boolean isMoving = isMovingAt(row, col);
            if (!clickedCell.equals(Config.EMPTY) && !isMoving) {
                selection[0] = row;
                selection[1] = col;
            }
        } else {
            // Second selection
            String selectedPiece = grid[selection[0]][selection[1]];
            if (!clickedCell.equals(Config.EMPTY) && clickedCell.charAt(0) == selectedPiece.charAt(0)) {
                selection[0] = row;
                selection[1] = col;
            } else {
                attemptMove(selectedPiece, selection[0], selection[1], row, col);
                selection[0] = -1;
                selection[1] = -1;
            }
        }
    }

    public void handleJump(int x, int y) {
        if (gameState.isGameOver()) return;

        int col = x / 100;
        int row = y / 100;

        if (row < 0 || row >= height || col < 0 || col >= width) return;

        String piece = grid[row][col];
        if (!piece.equals(Config.EMPTY)) {
            boolean alreadyMoving = isMovingAt(row, col);
            if (!alreadyMoving) {
                boolean jumpTooLate = checkIfTooLate(row, col, piece);
                if (jumpTooLate) {
                            grid[row][col] = Config.EMPTY;
                    if (piece.charAt(1) == 'K') {
                        gameState.setGameOver();
                    }
                } else {
                    createJumpMove(piece, row, col);
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

    private void attemptMove(String piece, int fromRow, int fromCol, int toRow, int toCol) {
        if (!validator.isValidMove(piece, fromRow, fromCol, toRow, toCol)) {
            return;
        }

        String destination = grid[toRow][toCol];
        if (destination.equals(Config.EMPTY) || destination.charAt(0) != piece.charAt(0)) {
            char pieceType = piece.charAt(1);
            long duration = (pieceType == 'N') ? KNIGHT_DURATION : NORMAL_MOVE_DURATION;
            createMove(piece, fromRow, fromCol, toRow, toCol, duration);
        }
    }

    private void createMove(String piece, int fromRow, int fromCol, int toRow, int toCol, long duration) {
        MovingPiece mp = new MovingPiece(piece, fromRow, fromCol, toRow, toCol, duration, gameState.getCurrentTime());
        activeMoves.add(mp);
    }

    private void createJumpMove(String piece, int row, int col) {
        MovingPiece mp = new MovingPiece(piece, row, col, row, col, 1000, gameState.getCurrentTime());
        activeMoves.add(mp);
    }

    private boolean isMovingAt(int row, int col) {
        for (MovingPiece mp : activeMoves) {
            if (mp.getFromRow() == row && mp.getFromCol() == col && gameState.getCurrentTime() < mp.getArrivalTime()) {
                return true;
            }
        }
        return false;
    }

    private boolean checkIfTooLate(int row, int col, String piece) {
        for (MovingPiece active : activeMoves) {
            if (active.isMoving() && active.getToRow() == row && active.getToCol() == col) {
                long enemyStart = active.getArrivalTime() - active.getDuration();
                if (enemyStart < gameState.getCurrentTime() && active.getPiece().charAt(0) != piece.charAt(0)) {
                    return true;
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
                    if (!jump.isMoving() && jump.getToRow() == move.getToRow() && jump.getToCol() == move.getToCol()) {
                        long jumpStart = jump.getArrivalTime() - jump.getDuration();
                        if (jumpStart <= enemyStart && jump.getPiece().charAt(0) != move.getPiece().charAt(0)) {
                            eatenByAirborne = true;
                            break;
                        }
                    }
                }

                if (eatenByAirborne) {
                    grid[move.getFromRow()][move.getFromCol()] = Config.EMPTY;
                    if (move.getPiece().charAt(1) == 'K') {
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
                    String finalPiece = mp.getPiece();
                    if (finalPiece.charAt(1) == 'P' && (mp.getToRow() == 0 || mp.getToRow() == height - 1)) {
                        finalPiece = finalPiece.charAt(0) + "Q";
                    }

                    String destination = grid[mp.getToRow()][mp.getToCol()];
                    if (!destination.equals(Config.EMPTY) && destination.charAt(1) == 'K') {
                        gameState.setGameOver();
                    }

                    grid[mp.getToRow()][mp.getToCol()] = finalPiece;
                    grid[mp.getFromRow()][mp.getFromCol()] = Config.EMPTY;
                }
                activeMoves.remove(i);
            }
        }
    }
}

