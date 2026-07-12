package model;

/**
 * A chess piece: just its color and type. Pure domain data - it knows nothing
 * about how pieces are encoded as text (that lives in parsing.PieceMapper).
 */
public final class Piece {
    public enum Color { WHITE, BLACK }
    public enum Type { K, Q, R, B, N, P }

    private final Color color;
    private final Type type;

    private Piece(Color color, Type type) {
        this.color = color;
        this.type = type;
    }

    /** Build a piece from a color and type. */
    public static Piece of(Color color, Type type) {
        return new Piece(color, type);
    }

    public Color getColor() { return color; }
    public Type getType() { return type; }

    /**
     * Returns the piece this one becomes if it arrives on the given row.
     * A pawn reaching the far row (0 or boardHeight-1) promotes to a queen;
     * every other piece is returned unchanged.
     */
    public Piece promotedAt(int toRow, int boardHeight) {
        if (type == Type.P && (toRow == 0 || toRow == boardHeight - 1)) {
            return new Piece(color, Type.Q);
        }
        return this;
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
