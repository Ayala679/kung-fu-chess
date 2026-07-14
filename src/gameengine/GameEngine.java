package gameengine;

import config.GameConfig;
import model.Board;
import model.GameState;
import model.Piece;
import model.Position;
import ruleengine.RuleEngine;
import view.BoardRenderer;

/**
 * GameEngine: the central gateway (like a Service) for all game actions.
 *
 * It receives move/jump/wait/print requests, validates moves through the
 * RuleEngine, decides move durations and game-over, and delegates everything
 * about timing and pieces-in-transit to RealTimeArbiter.
 *
 * It does NOT parse input and does NOT track click selection - those belong to
 * the parsing layer and the EventEngine respectively.
 */
public class GameEngine {
    private final Board board;
    private final GameState gameState;
    private final RuleEngine ruleEngine;
    private final RealTimeArbiter arbiter;
    private final BoardRenderer renderer;

    public GameEngine(Board board, GameState gameState) {
        this.board = board;
        this.gameState = gameState;
        this.ruleEngine = new RuleEngine(board);
        this.arbiter = new RealTimeArbiter(board, gameState);
        this.renderer = new BoardRenderer(board, arbiter.getActiveMoves(), gameState);
    }

    // ---- queries used by the event layer ----

    public boolean isGameOver() { return gameState.isGameOver(); }
    public int getHeight() { return board.getHeight(); }
    public int getWidth() { return board.getWidth(); }

    public boolean inBounds(int row, int col) {
        return board.inBounds(row, col);
    }

    public Piece pieceAt(int row, int col) { return board.getCell(row, col); }

    public boolean isBusyAt(int row, int col) {
        return arbiter.isBusyAt(row, col);
    }

    /** Is the piece at this cell already committed to a move of its own? */
    public boolean isAlreadyMoving(int row, int col) {
        return arbiter.isAlreadyMoving(row, col);
    }

    /** Bring the board up to date with the current virtual time. */
    public void refreshTime() {
        arbiter.update();
    }

    // ---- board bootstrap checks (used by controller) ----

    public boolean isEmpty() { return board.isEmpty(); }

    public boolean isValid() { return !isEmpty(); }

    // ---- actions (the gateway) ----

    /** Request to move the piece at {@code from} to {@code to}. */
    public void requestMove(Position from, Position to) {
        if (gameState.isGameOver()) return;
        if (arbiter.isAlreadyMoving(from.getRow(), from.getCol())) return;
        if (!ruleEngine.isMoveAllowed(from, to)) return;

        Piece piece = board.getCell(from);
        int maxDistance = Math.max(from.rowDistance(to), from.colDistance(to));
        arbiter.startMove(piece, from, to, piece.moveDuration(maxDistance));
    }

    /** Request to "jump" the piece on a cell in place (Kung-Fu Chess mechanic). */
    public void requestJump(int row, int col) {
        if (gameState.isGameOver()) return;
        if (!inBounds(row, col)) return;

        refreshTime();
        Piece piece = board.getCell(row, col);
        if (piece == null) return;
        if (arbiter.isAlreadyMoving(row, col)) return;

        if (arbiter.isTooLateToJump(row, col, piece)) {
            arbiter.capture(row, col, piece);
        } else {
            arbiter.startJump(piece, new Position(row, col), GameConfig.JUMP_DURATION);
        }
        refreshTime();
    }

    /** Advance virtual time and settle any arrivals. */
    public void advanceTime(long ms) {
        if (gameState.isGameOver()) return;
        gameState.advanceTime(ms);
        refreshTime();
    }

    public void printBoard() {
        refreshTime();
        renderer.printBoard();
    }
}
