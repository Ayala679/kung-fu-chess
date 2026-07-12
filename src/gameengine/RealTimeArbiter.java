package gameengine;

import java.util.ArrayList;
import java.util.List;

import model.Board;
import model.GameState;
import model.MovingPiece;
import model.Piece;
import model.Position;

/**
 * RealTimeArbiter: owns everything about pieces in transit and virtual time.
 *
 * It holds the active moves, advances the simulated clock, decides when a move
 * arrives, and applies the board update atomically on arrival. When a king is
 * captured it reports it back through the shared GameState.
 *
 * All reading and advancing of virtual time happens here - no other class
 * touches the clock. Tests never sleep; they push time forward via {@link
 * #advance(long)}.
 */
public class RealTimeArbiter {
    private final Board board;
    private final GameState gameState;
    private final List<MovingPiece> activeMoves = new ArrayList<>();

    public RealTimeArbiter(Board board, GameState gameState) {
        this.board = board;
        this.gameState = gameState;
    }

    /** Live view of the pieces currently in transit (used by the renderer). */
    public List<MovingPiece> getActiveMoves() {
        return activeMoves;
    }

    /** Advance the virtual clock by {@code ms} and settle anything that arrived. */
    public void advance(long ms) {
        gameState.advanceTime(ms);
        update();
    }

    public void startMove(Piece piece, Position from, Position to, long duration) {
        activeMoves.add(new MovingPiece(piece, from, to, duration, gameState.getCurrentTime()));
    }

    public void startJump(Piece piece, Position pos, long duration) {
        activeMoves.add(new MovingPiece(piece, pos, pos, duration, gameState.getCurrentTime()));
    }

    /** Is a piece currently entering or leaving this cell (move not yet arrived)? */
    public boolean isBusyAt(int row, int col) {
        long now = gameState.getCurrentTime();
        Position pos = new Position(row, col);
        for (MovingPiece mp : activeMoves) {
            if (now < mp.getArrivalTime()) {
                if (mp.getFrom().equals(pos) || mp.getTo().equals(pos)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Is an enemy already sliding into this cell, so a jump here would be too late? */
    public boolean isTooLateToJump(int row, int col, Piece piece) {
        long now = gameState.getCurrentTime();
        for (MovingPiece active : activeMoves) {
            if (active.isMoving()) {
                Position to = active.getTo();
                if (to.getRow() == row && to.getCol() == col) {
                    long enemyStart = active.getArrivalTime() - active.getDuration();
                    if (enemyStart <= now && active.getPiece().getColor() != piece.getColor()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Settle the world at the current virtual time: resolve mid-air captures,
     * then apply arrivals to the board atomically.
     */
    public void update() {
        long currentTime = gameState.getCurrentTime();

        // Mid-air captures: a jumping (in-place) piece that landed first eats an
        // enemy that is still sliding toward the same cell.
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
                    board.setCell(move.getFrom(), null);
                    if (move.getPiece().getType() == Piece.Type.K) {
                        gameState.setGameOver();
                    }
                    activeMoves.remove(i);
                }
            }
        }

        // Arrivals: move each finished piece onto the board.
        for (int i = activeMoves.size() - 1; i >= 0; i--) {
            MovingPiece mp = activeMoves.get(i);
            if (currentTime >= mp.getArrivalTime()) {
                if (mp.isMoving()) {
                    Position to = mp.getTo();
                    Piece finalPiece = mp.getPiece().promotedAt(to.getRow(), board.getHeight());

                    Piece destination = board.getCell(to);
                    if (destination != null && destination.getType() == Piece.Type.K) {
                        gameState.setGameOver();
                    }

                    board.setCell(to, finalPiece);
                    board.setCell(mp.getFrom(), null);
                }
                activeMoves.remove(i);
            }
        }
    }
}
