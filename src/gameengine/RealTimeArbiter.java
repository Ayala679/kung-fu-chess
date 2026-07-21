package gameengine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import config.GameConfig;
import model.Board;
import model.GameState;
import model.MovingPiece;
import model.Piece;
import model.Position;
import model.RestingPiece;

/**
 * RealTimeArbiter: owns everything about pieces in transit and virtual time.
 *
 * It holds the active moves, advances the simulated clock, decides when a move
 * arrives, and applies the board update atomically on arrival. When a king is
 * captured it reports it back through the shared GameState. {@code
 * jumpDefenses} is the one piece of state that outlives a single {@link
 * #update()} call on purpose - see its own doc.
 *
 * All reading and advancing of virtual time happens here - no other class
 * touches the clock. Tests never sleep; they push time forward via {@link
 * #advance(long)}.
 */
public class RealTimeArbiter {
    private final Board board;
    private final GameState gameState;
    private final List<MovingPiece> activeMoves = new ArrayList<>();
    private final List<RestingPiece> restingPieces = new ArrayList<>();
    private final Map<Position, JumpDefense> jumpDefenses = new HashMap<>();
    private int whiteScore = 0;
    private int blackScore = 0;

    /**
     * Proof that a jump completed at {@code square} at {@code completionTime} -
     * kept independently of {@code activeMoves}/{@code restingPieces} so a
     * jump that genuinely finished in time still defends the square on the
     * attacker's real, later arrival, no matter how many ticks have passed in
     * between (see {@link #isDefendedByATimelyJump} for why checking {@code
     * activeMoves} alone isn't enough). Only counts for a limited grace
     * period afterward, though - {@code piece.getShortRestDuration()} past
     * {@code completionTime} - so a piece that jumped far too early, well
     * before any attacker was even close, doesn't stay invincible forever;
     * see {@link #isDefendedByATimelyJump} for the exact bound. Superseded
     * (removed) even sooner than that the instant that square's occupant
     * does anything else: leaves, jumps again, or is replaced by a
     * capture/arrival - see every {@code jumpDefenses.remove(...)} call site.
     */
    private static final class JumpDefense {
        final Piece piece;
        final long completionTime;

        JumpDefense(Piece piece, long completionTime) {
            this.piece = piece;
            this.completionTime = completionTime;
        }
    }

    public RealTimeArbiter(Board board, GameState gameState) {
        this.board = board;
        this.gameState = gameState;
    }

    /** Live view of the pieces currently in transit (used by the renderer). */
    public List<MovingPiece> getActiveMoves() {
        return activeMoves;
    }

    /** Live view of pieces currently resting after a move/jump (used by the renderer). */
    public List<RestingPiece> getRestingPieces() {
        return restingPieces;
    }

    /** Total material value of the opposing color's pieces this color has captured. */
    public int getScore(Piece.Color color) {
        return color == Piece.Color.WHITE ? whiteScore : blackScore;
    }

    /** Is the piece sitting at this cell still resting after its last move/jump? */
    public boolean isResting(int row, int col) {
        long now = gameState.getCurrentTime();
        Position pos = new Position(row, col);
        for (RestingPiece rp : restingPieces) {
            if (now < rp.getRestUntil() && rp.getPosition().equals(pos)) {
                return true;
            }
        }
        return false;
    }

    /** Advance the virtual clock by {@code ms} and settle anything that arrived. */
    public void advance(long ms) {
        gameState.advanceTime(ms);
        update();
    }

    public void startMove(Piece piece, Position from, Position to, long duration) {
        jumpDefenses.remove(from); // committing to a new errand supersedes any old completed-jump proof for this square
        activeMoves.add(new MovingPiece(piece, from, to, duration, gameState.getCurrentTime()));
    }

    public void startJump(Piece piece, Position pos, long duration) {
        jumpDefenses.remove(pos); // a fresh jump supersedes any earlier one - a new proof lands if/when this one completes
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
        Position pos = new Position(row, col);
        board.setCell(row, col, null);
        clearRestAt(pos);
        jumpDefenses.remove(pos);
        recordCapture(piece);
    }

    /** Credit the opposite color's score with this piece's material value, and end the game if it was a king. */
    private void recordCapture(Piece victim) {
        if (victim.getType() == Piece.Type.K) {
            Piece.Color winner = victim.getColor() == Piece.Color.WHITE ? Piece.Color.BLACK : Piece.Color.WHITE;
            gameState.setGameOver(winner);
        }
        if (victim.getColor() == Piece.Color.WHITE) {
            blackScore += victim.materialValue();
        } else {
            whiteScore += victim.materialValue();
        }
    }

    /**
     * Is it too late to jump out of this cell? A real-time race: if a jump
     * started right now would finish at or before the incoming enemy slide
     * arrives, the dodge succeeds - otherwise it's too late and the piece
     * dies. A tie must side with the jump here, matching the identical tie
     * rule in the mid-air capture resolution below ("ties go to the
     * defender") - otherwise a same-duration race (e.g. any adjacent capture,
     * where a 1-cell slide and JUMP_DURATION are equal) would always kill the
     * defender before its jump could even start, regardless of reaction time.
     */
    public boolean isTooLateToJump(int row, int col, Piece piece) {
        long jumpFinish = gameState.getCurrentTime() + GameConfig.JUMP_DURATION;
        for (MovingPiece active : activeMoves) {
            if (active.isMoving()) {
                Position to = active.getTo();
                if (to.getRow() == row && to.getCol() == col
                        && active.getPiece().getColor() != piece.getColor()
                        && jumpFinish > active.getArrivalTime()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Is {@code square} currently defended, against an attacker of
     * {@code attackerColor} scheduled to arrive at {@code attackerArrivalTime},
     * by an opposite-color in-place jump that finished at or before that
     * arrival? Checks both a jump still genuinely airborne ({@code
     * activeMoves}) and one that already completed within its grace period
     * ({@code jumpDefenses}) - see that field's own doc for why the second
     * check is needed: real play advances time in small (~16ms) increments,
     * and JUMP_DURATION is always shorter than any slide it might be
     * racing, so the jump has almost always already resolved out of {@code
     * activeMoves} by the time a slower attacker's own arrival is even
     * processed. Without the second check, a jump that genuinely finished
     * in time would stop counting the moment its own tick happened to run -
     * which is a real-world certainty, not an edge case, and defeats the
     * entire mechanic outside of tests that jump virtual time forward in
     * one huge step. The grace period itself keeps this from becoming
     * permanent immunity: a jump thrown far too early, long before any
     * attacker was actually close, must not still protect once its own
     * short-rest window has elapsed.
     */
    private boolean isDefendedByATimelyJump(Position square, Piece.Color attackerColor, long attackerArrivalTime) {
        for (MovingPiece jump : activeMoves) {
            if (!jump.isMoving() && jump.getTo().equals(square)
                    && jump.getPiece().getColor() != attackerColor
                    && jump.getArrivalTime() <= attackerArrivalTime) {
                return true;
            }
        }
        JumpDefense defense = jumpDefenses.get(square);
        if (defense == null || defense.piece.getColor() == attackerColor) return false;
        long graceDeadline = defense.completionTime + defense.piece.getShortRestDuration();
        return defense.completionTime <= attackerArrivalTime && attackerArrivalTime <= graceDeadline;
    }

    /**
     * Would starting a move to {@code to} race a same-color knight for that
     * square - either this mover is a knight and someone else already claimed
     * {@code to}, or {@code to} is already claimed by an in-flight knight?
     * Unlike a slide, a knight's L-shaped hop has no natural "stop one cell
     * short" waypoint, so knight-involved same-color races are rejected
     * outright at request time instead of being approximated on arrival.
     * Different-color races are unaffected - those resolve as a normal
     * capture on arrival, which works fine regardless of piece shape.
     */
    public boolean isKnightRaceConflict(Position to, Piece mover) {
        for (MovingPiece mp : activeMoves) {
            if (mp.isMoving() && mp.getTo().equals(to)
                    && mp.getPiece().getColor() == mover.getColor()
                    && (mover.getType() == Piece.Type.N || mp.getPiece().getType() == Piece.Type.N)) {
                return true;
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

        restingPieces.removeIf(rp -> currentTime >= rp.getRestUntil());

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
        // order, so that whichever move genuinely arrives FIRST claims a
        // contested square. Anyone else still heading for the same square -
        // whether it arrives later in this same batch (e.g. after a large time
        // jump) or is still mid-flight and only discovered right now:
        //   - same color as the piece that already claimed it: can't land on or
        //     capture your own piece, so it's redirected to stop one cell short,
        //     continuing at the same pace it was already moving at (not a fresh
        //     move replaying the whole original path from scratch).
        //   - different color: this is just a normal capture of whoever's
        //     there - no special handling, it lands exactly as it would have.
        List<MovingPiece> arrived = new ArrayList<>();
        for (MovingPiece mp : activeMoves) {
            if (currentTime >= mp.getArrivalTime()) {
                arrived.add(mp);
            }
        }
        arrived.sort(Comparator.comparingLong(MovingPiece::getArrivalTime));

        // Cells whose occupant already has an active outgoing move of its own -
        // whether that move arrives later in this same batch, or is still
        // genuinely mid-flight toward a different square entirely. The board
        // only clears an origin on that move's OWN arrival, so it may still
        // show the old occupant sitting there - but it already left before
        // this arrival happened, so it must not be treated as a real capture,
        // no matter how much later its own trip actually finishes.
        Set<Position> departingActive = new HashSet<>();
        for (MovingPiece mp : activeMoves) {
            if (mp.isMoving()) departingActive.add(mp.getFrom());
        }

        Map<Position, Piece> claimed = new HashMap<>();

        for (MovingPiece mp : arrived) {
            if (!mp.isMoving()) {
                addRest(mp.getPiece(), mp.getTo(), currentTime, true); // completed jump - short rest
                jumpDefenses.put(mp.getTo(), new JumpDefense(mp.getPiece(), mp.getArrivalTime()));
                continue;
            }
            Position to = mp.getTo();

            // A defender that jumped in place and finished (or is still mid-
            // jump but scheduled to finish) at or before THIS attacker's own
            // arrival, and hasn't yet fully returned to idle since, defeats
            // the attacker right here instead of being captured - resolved
            // only now, at the attacker's real arrival, not the instant both
            // moves happened to coexist. Ties go to the defender: it was
            // already braced first.
            if (isDefendedByATimelyJump(to, mp.getPiece().getColor(), mp.getArrivalTime())) {
                capture(mp.getFrom().getRow(), mp.getFrom().getCol(), mp.getPiece());
                continue;
            }

            Piece claimant = claimed.get(to);
            boolean sameColorContest = claimant != null && claimant.getColor() == mp.getPiece().getColor();

            boolean pawnBlocked = false;
            if (isPawnStraightAdvance(mp)) {
                if (claimant != null) {
                    pawnBlocked = true; // someone genuinely landed there this same batch
                } else {
                    Piece occupant = board.getCell(to);
                    pawnBlocked = occupant != null && !departingActive.contains(to);
                }
            }

            if (sameColorContest || pawnBlocked) {
                stopShortOfContestedSquare(mp, currentTime);
                continue;
            }

            claimed.put(to, applyArrival(mp, to, currentTime, departingActive.contains(to)));
        }
        activeMoves.removeAll(arrived);

        if (!claimed.isEmpty()) {
            for (int i = activeMoves.size() - 1; i >= 0; i--) {
                MovingPiece mp = activeMoves.get(i);
                if (!mp.isMoving()) continue;

                // Note: mp's own origin can never show up as a claimed square
                // here in a way that matters - mp is itself the active
                // departure from that square, so departingActive above already
                // exempted anyone arriving there from capturing it; mp's trip
                // continues normally to its own destination regardless.

                Piece claimant = claimed.get(mp.getTo());
                if (claimant == null) continue;

                boolean sameColorContest = claimant.getColor() == mp.getPiece().getColor();
                if (sameColorContest || isPawnStraightAdvance(mp)) {
                    activeMoves.remove(i);
                    stopShortOfContestedSquare(mp, currentTime);
                }
                // different color and not a pawn's straight advance: leave it
                // be - it'll capture the claimant normally, through this same
                // arrival logic, once its own (unchanged) arrival time comes.
            }
        }
    }

    /** A pawn moving straight ahead (not a diagonal capture) can never legally capture, regardless of who's there. */
    private static boolean isPawnStraightAdvance(MovingPiece mp) {
        return mp.getPiece().getType() == Piece.Type.P && mp.getFrom().getCol() == mp.getTo().getCol();
    }

    /**
     * Land an arriving move on the board (promotion, capture scoring), returning
     * the piece now sitting there. {@code destinationHasAnActiveDeparture}
     * means the board still shows someone at {@code to} only because their own
     * departure hasn't been applied yet - they already started leaving before
     * this arrival happened (whether their own move resolves in this same
     * batch or only much later), so it's not a real capture.
     *
     * The origin is only cleared if it still holds the piece that's leaving -
     * if a different arrival, processed earlier in this same batch, already
     * landed there, clearing it would wipe out that legitimate arrival instead
     * of the piece that actually departed.
     */
    private Piece applyArrival(MovingPiece mp, Position to, long currentTime, boolean destinationHasAnActiveDeparture) {
        Piece finalPiece = mp.getPiece().promotedAt(to.getRow(), board.getHeight());
        Piece destination = board.getCell(to);
        if (destination != null && !destinationHasAnActiveDeparture) {
            recordCapture(destination);
        }

        board.setCell(to, finalPiece);
        if (board.getCell(mp.getFrom()) == mp.getPiece()) {
            board.setCell(mp.getFrom(), null);
        }
        addRest(finalPiece, to, currentTime, false);
        return finalPiece;
    }

    /**
     * A piece that just finished a move/jump can't act again until its rest
     * duration elapses. Clears any stale entry for this square first - whoever
     * was resting there before (now captured or replaced) is gone, and must not
     * keep blocking whoever occupies the square now. A normal move landing
     * here (fromJump=false) also starts a fresh occupancy episode, so any
     * earlier jump-defense proof for this square is cleared too - the
     * jump-completion branch in {@link #update()} sets a fresh one itself,
     * right after calling this with fromJump=true.
     */
    private void addRest(Piece piece, Position pos, long currentTime, boolean fromJump) {
        clearRestAt(pos);
        if (!fromJump) jumpDefenses.remove(pos);
        long duration = fromJump ? piece.getShortRestDuration() : piece.getLongRestDuration();
        restingPieces.add(new RestingPiece(piece, pos, currentTime + duration, fromJump));
    }

    private void clearRestAt(Position pos) {
        restingPieces.removeIf(rp -> rp.getPosition().equals(pos));
    }

    /**
     * A move that just lost a race for its destination (someone else claimed it
     * first) is redirected to stop one cell short of it, at the SAME pace it was
     * already moving at - same origin and start time, just a shorter (and so
     * proportionally quicker) trip, instead of a fresh move replaying the whole
     * original path. If that shortened trip would already be over by now (the
     * piece had, in effect, already reached the contested square), it lands
     * immediately instead of animating a correction; if it was already adjacent
     * to the contested square to begin with, there's nowhere shorter to go, so
     * it just stays exactly where it is.
     */
    private void stopShortOfContestedSquare(MovingPiece mp, long currentTime) {
        Position from = mp.getFrom();
        Position to = mp.getTo();
        int rowStep = Integer.compare(to.getRow(), from.getRow());
        int colStep = Integer.compare(to.getCol(), from.getCol());
        Position newTo = new Position(to.getRow() - rowStep, to.getCol() - colStep);

        if (newTo.equals(from)) return;

        int newDistance = Math.max(from.rowDistance(newTo), from.colDistance(newTo));
        long newDuration = mp.getPiece().moveDuration(newDistance);
        long startTime = mp.getArrivalTime() - mp.getDuration(); // same start as the original move
        MovingPiece continuation = new MovingPiece(mp.getPiece(), from, newTo, newDuration, startTime);

        if (currentTime >= continuation.getArrivalTime()) {
            Piece finalPiece = mp.getPiece().promotedAt(newTo.getRow(), board.getHeight());
            board.setCell(newTo, finalPiece);
            if (board.getCell(from) == mp.getPiece()) {
                board.setCell(from, null);
            }
            addRest(finalPiece, newTo, currentTime, false);
        } else {
            activeMoves.add(continuation);
        }
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
