package gameengine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import config.GameConfig;
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

    /**
     * Is the piece sitting at this cell already mid-move on its own (sliding
     * away, or already mid-jump)? Unlike {@link #isBusyAt}, this ignores other
     * pieces merely heading toward this cell - that case is a jump/defense
     * question for {@link #isTooLateToJump}, not a "already has a command" one.
     */
    public boolean isAlreadyMoving(int row, int col) {
        long now = gameState.getCurrentTime();
        Position pos = new Position(row, col);
        for (MovingPiece mp : activeMoves) {
            if (now < mp.getArrivalTime() && mp.getFrom().equals(pos)) {
                return true;
            }
        }
        return false;
    }

    /** Remove the piece sitting at (row, col) - e.g. a jump that came too late to escape a slide. */
    public void capture(int row, int col, Piece piece) {
        board.setCell(row, col, null);
        if (piece.getType() == Piece.Type.K) {
            gameState.setGameOver();
        }
    }

    /**
     * Is it too late to jump out of this cell? A real-time race: if a jump
     * started right now would finish before the incoming enemy slide arrives,
     * the dodge succeeds - otherwise it's too late and the piece dies.
     */
    public boolean isTooLateToJump(int row, int col, Piece piece) {
        long jumpFinish = gameState.getCurrentTime() + GameConfig.JUMP_DURATION;
        for (MovingPiece active : activeMoves) {
            if (active.isMoving()) {
                Position to = active.getTo();
                if (to.getRow() == row && to.getCol() == col
                        && active.getPiece().getColor() != piece.getColor()
                        && jumpFinish >= active.getArrivalTime()) {
                    return true;
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

        // Mid-air captures: a jumping (in-place) piece that finishes its dodge
        // at or before an incoming enemy slide arrives eats that slide - a
        // real-time race decided by who finishes first, not who started first.
        // Ties go to the defender (the jump): it was already braced first.
        for (int i = activeMoves.size() - 1; i >= 0; i--) {
            MovingPiece move = activeMoves.get(i);
            if (move.isMoving()) {
                boolean eatenByAirborne = false;

                for (MovingPiece jump : activeMoves) {
                    if (!jump.isMoving()) {
                        Position jt = jump.getTo();
                        Position mt = move.getTo();
                        if (jt.getRow() == mt.getRow() && jt.getCol() == mt.getCol()
                                && jump.getArrivalTime() <= move.getArrivalTime()
                                && jump.getPiece().getColor() != move.getPiece().getColor()) {
                            eatenByAirborne = true;
                            break;
                        }
                    }
                }

                if (eatenByAirborne) {
                    capture(move.getFrom().getRow(), move.getFrom().getCol(), move.getPiece());
                    activeMoves.remove(i);
                }
            }
        }

        // Head-on collisions: two different-colored slides swapping squares
        // (each one's destination is the other's origin) can't just pass
        // through each other - only the higher-priority one survives (earlier
        // arrival; if tied, earlier start; if still tied, whichever was
        // requested first). The loser is captured before ever arriving.
        List<MovingPiece> collisionLosers = new ArrayList<>();
        for (int i = 0; i < activeMoves.size(); i++) {
            MovingPiece a = activeMoves.get(i);
            if (!a.isMoving() || collisionLosers.contains(a)) continue;

            for (int j = i + 1; j < activeMoves.size(); j++) {
                MovingPiece b = activeMoves.get(j);
                if (!b.isMoving() || collisionLosers.contains(b)) continue;
                if (a.getPiece().getColor() == b.getPiece().getColor()) continue;
                if (!a.getTo().equals(b.getFrom()) || !b.getTo().equals(a.getFrom())) continue;

                collisionLosers.add(hasPriority(a, i, b, j) ? b : a);
            }
        }
        for (MovingPiece loser : collisionLosers) {
            capture(loser.getFrom().getRow(), loser.getFrom().getCol(), loser.getPiece());
        }
        activeMoves.removeAll(collisionLosers);

        // Arrivals: move each finished piece onto the board, in arrival-time
        // order. This matters when two or more moves land on the same square in
        // the same update() call (e.g. after a large time jump) - applying them
        // oldest-arrival-first last guarantees the move that truly arrived last
        // is the one left standing, not whichever happened to be requested last.
        List<MovingPiece> arrived = new ArrayList<>();
        for (MovingPiece mp : activeMoves) {
            if (currentTime >= mp.getArrivalTime()) {
                arrived.add(mp);
            }
        }
        arrived.sort(Comparator.comparingLong(MovingPiece::getArrivalTime));

        for (MovingPiece mp : arrived) {
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
        }
        activeMoves.removeAll(arrived);
    }

    /** Does {@code x} win a head-on collision against {@code y}? */
    private boolean hasPriority(MovingPiece x, int xIndex, MovingPiece y, int yIndex) {
        if (x.getArrivalTime() != y.getArrivalTime()) {
            return x.getArrivalTime() < y.getArrivalTime();
        }
        long xStart = x.getArrivalTime() - x.getDuration();
        long yStart = y.getArrivalTime() - y.getDuration();
        if (xStart != yStart) {
            return xStart < yStart;
        }
        return xIndex < yIndex;
    }
}
