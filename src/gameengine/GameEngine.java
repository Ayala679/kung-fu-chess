package gameengine;

import java.util.ArrayList;
import java.util.List;

import config.GameConfig;
import model.Board;
import model.GameState;
import model.MoveLogEntry;
import model.Piece;
import model.Position;
import ruleengine.RuleEngine;
import view.BoardRenderer;
import snapshot.GameSnapshot;
import snapshot.MoveNotation;
import snapshot.SnapshotBuilder;

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
    private final List<MoveLogEntry> whiteMoves = new ArrayList<>();
    private final List<MoveLogEntry> blackMoves = new ArrayList<>();
    private String whiteName;
    private String blackName;

    public GameEngine(Board board, GameState gameState) {
        this.board = board;
        this.gameState = gameState;
        this.ruleEngine = new RuleEngine(board);
        this.arbiter = new RealTimeArbiter(board, gameState);
        this.renderer = new BoardRenderer(board, arbiter.getActiveMoves(), gameState);
    }

    /** Display names for the snapshot (e.g. "White (alice)") - null by default, as in offline play. */
    public void setPlayerNames(String whiteName, String blackName) {
        this.whiteName = whiteName;
        this.blackName = blackName;
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

    /** Is the piece at this cell still resting after its last move/jump? */
    public boolean isResting(int row, int col) {
        return arbiter.isResting(row, col);
    }

    /** Bring the board up to date with the current virtual time. */
    public void refreshTime() {
        arbiter.update();
    }

    // ---- board bootstrap checks (used by controller) ----

    public boolean isEmpty() { return board.isEmpty(); }

    public boolean isValid() { return !isEmpty(); }

    // ---- actions (the gateway) ----

    /**
     * Temporary diagnostic switch (off by default, so it never affects tests
     * that capture console output) - GuiMain turns this on so requestMove
     * rejections are visible while manually reproducing a reported bug.
     */
    public static boolean DEBUG_LOGGING = false;

    private static void debugLog(String message) {
        if (DEBUG_LOGGING) System.err.println(message);
    }

    /** Request to move the piece at {@code from} to {@code to}. */
    public void requestMove(Position from, Position to) {
        if (gameState.isGameOver()) { debugLog("[requestMove] " + from + "->" + to + " REJECTED: game over"); return; }
        if (arbiter.isAlreadyMoving(from.getRow(), from.getCol())) {
            debugLog("[requestMove] " + from + "->" + to + " REJECTED: " + from + " is already moving");
            return;
        }
        if (arbiter.isResting(from.getRow(), from.getCol())) {
            debugLog("[requestMove] " + from + "->" + to + " REJECTED: " + from + " is resting");
            return;
        }

        Piece piece = board.getCell(from);
        if (piece == null) {
            debugLog("[requestMove] " + from + "->" + to + " REJECTED: no piece at " + from);
            return;
        }
        if (arbiter.isKnightRaceConflict(to, piece)) {
            debugLog("[requestMove] " + from + "->" + to + " REJECTED: knight race conflict at " + to);
            return;
        }
        if (!ruleEngine.isMoveAllowed(from, to, arbiter.getActiveMoves(), gameState.getCurrentTime())) {
            debugLog("[requestMove] " + from + "->" + to + " REJECTED: rule engine disallows it");
            return;
        }
        debugLog("[requestMove] " + from + "->" + to + " ACCEPTED (" + piece + ")");

        String notation = MoveNotation.describe(piece, to, board.getHeight());
        List<MoveLogEntry> log = piece.getColor() == Piece.Color.WHITE ? whiteMoves : blackMoves;
        log.add(new MoveLogEntry(gameState.getCurrentTime(), notation));

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
        if (arbiter.isResting(row, col)) return;

        if (arbiter.isTooLateToJump(row, col, piece)) {
            arbiter.capture(row, col, piece);
        } else {
            arbiter.startJump(piece, new Position(row, col), GameConfig.JUMP_DURATION);
        }
        refreshTime();
    }

    /**
     * Ends the game immediately in {@code resigningColor}'s favor of the
     * opponent - a resignation, exactly like a king capture ends the game
     * except there's no piece taken. Idempotent: does nothing once the game
     * is already over. Generic on purpose - this class doesn't need to know
     * *why* a color resigned (e.g. a network disconnect timeout), only who.
     */
    public void forceResign(Piece.Color resigningColor) {
        if (gameState.isGameOver()) return;
        Piece.Color winner = resigningColor == Piece.Color.WHITE ? Piece.Color.BLACK : Piece.Color.WHITE;
        gameState.setGameOver(winner);
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

    /** Render-ready snapshot of the current board, for the graphical view. */
    public GameSnapshot buildSnapshot(Position selected) {
        refreshTime();
        return SnapshotBuilder.build(board, arbiter.getActiveMoves(), arbiter.getRestingPieces(), gameState, selected,
                arbiter.getScore(Piece.Color.WHITE), arbiter.getScore(Piece.Color.BLACK),
                whiteMoves, blackMoves, legalDestinationsFrom(selected), whiteName, blackName);
    }

    /**
     * Every square the selected piece could actually move to right now - the
     * same gates {@link #requestMove} itself checks (game-over, already
     * moving/resting, knight race conflicts, rule-engine legality), just
     * without ever starting a move. Used so the view can show the player
     * where a selected piece is allowed to go.
     */
    private List<Position> legalDestinationsFrom(Position selected) {
        List<Position> destinations = new ArrayList<>();
        if (selected == null || gameState.isGameOver()) return destinations;
        if (arbiter.isAlreadyMoving(selected.getRow(), selected.getCol())) return destinations;
        if (arbiter.isResting(selected.getRow(), selected.getCol())) return destinations;

        Piece piece = board.getCell(selected);
        if (piece == null) return destinations;

        for (int row = 0; row < board.getHeight(); row++) {
            for (int col = 0; col < board.getWidth(); col++) {
                Position candidate = new Position(row, col);
                if (candidate.equals(selected)) continue;
                if (arbiter.isKnightRaceConflict(candidate, piece)) continue;
                if (ruleEngine.isMoveAllowed(selected, candidate, arbiter.getActiveMoves(), gameState.getCurrentTime())) {
                    destinations.add(candidate);
                }
            }
        }
        return destinations;
    }
}
