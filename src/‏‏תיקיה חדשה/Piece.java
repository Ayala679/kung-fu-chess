package model;

public final class Piece {
    public enum Color { WHITE, BLACK }
    public enum Type { K, Q, R, B, N, P }

    private final Color color;
    private final Type type;

    private Piece(Color color, Type type) {
        this.color = color;
        this.type = type;
    }

    public Color getColor() { return color; }
    public Type getType() { return type; }

    public static Piece fromToken(String token) {
        if (token == null || token.length() != 2) return null;
        char c = token.charAt(0);
        char t = token.charAt(1);
        Color color = (c == 'w') ? Color.WHITE : (c == 'b') ? Color.BLACK : null;
        Type type = null;
        switch (t) {
            case 'K': type = Type.K; break;
            case 'Q': type = Type.Q; break;
            case 'R': type = Type.R; break;
            case 'B': type = Type.B; break;
            case 'N': type = Type.N; break;
            case 'P': type = Type.P; break;
            default: type = null; break;
        }
        if (color == null || type == null) return null;
        return new Piece(color, type);
    }

    public String toToken() {
        char c = (color == Color.WHITE) ? 'w' : 'b';
        return c + type.name();
    }

    @Override
    public String toString() { return toToken(); }

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

