package model;

import config.GameConfig;

/**
 * A chess piece: just its color and type. Pure domain data - it knows nothing
 * about how pieces are encoded as text (that lives in parsing.PieceMapper).
 */
public final class Piece {
    public enum Color { WHITE, BLACK }
    public enum Type { K, Q, R, B, N, P }
    private final Color color;
    private final Type type;
    private final long longRestDuration;
    private final long shortRestDuration;

    private Piece(Color color, Type type, long longRestDuration, long shortRestDuration) {
        this.color = color;
        this.type = type;
        this.longRestDuration = longRestDuration;
        this.shortRestDuration = shortRestDuration;
    }

    /** Build a piece from a color and type, with the default rest durations. */
    public static Piece of(Color color, Type type) {
        return new Piece(color, type, GameConfig.DEFAULT_LONG_REST_DURATION, GameConfig.DEFAULT_SHORT_REST_DURATION);
    }

    public Color getColor() { return color; }
    public Type getType() { return type; }

    /**
     * How long this piece rests (can't be selected or moved) after finishing a
     * slide-move. Lives on the piece itself, not a shared lookup table, so a
     * future piece can be given its own value independent of every other piece
     * of the same type.
     */
    public long getLongRestDuration() { return longRestDuration; }

    /** How long this piece rests after finishing a jump - shorter than a move's rest. */
    public long getShortRestDuration() { return shortRestDuration; }

    /**
     * Returns the piece this one becomes if it arrives on the given row.
     * A pawn reaching the far row (0 or boardHeight-1) promotes to a queen;
     * every other piece is returned unchanged. The rest durations carry over.
     */
    public Piece promotedAt(int toRow, int boardHeight) {
        if (type == Type.P && (toRow == 0 || toRow == boardHeight - 1)) {
            return new Piece(color, Type.Q, longRestDuration, shortRestDuration);
        }
        return this;
    }

    /** Standard material value used for scoring when this piece is captured. */
    public int materialValue() {
        switch (type) {
            case P: return 1;
            case N: case B: return 3;
            case R: return 5;
            case Q: return 9;
            default: return 0; // king has no material value in standard scoring
        }
    }

    /**
     * How long a move of {@code distance} cells takes for this piece type.
     * Centralized here so each type's timing rule has exactly one home, even
     * though today only the knight (a fixed hop) differs from the rest
     * (distance-based).
     */
    public long moveDuration(int distance) {
        if (type == Type.N) {
            return GameConfig.KNIGHT_TOTAL_DURATION;
        }
        return distance * GameConfig.MOVE_DURATION_PER_CELL;
    }

    @Override
    public String toString() { return color + " " + type; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Piece)) return false;
        Piece p = (Piece) o;
        return color == p.color && type == p.type;
    }

    @Override
    public int hashCode() {
        return 31 * color.hashCode() + type.hashCode();
    }
}
