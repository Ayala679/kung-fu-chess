package parsing;

import model.Piece;

/**
 * PieceMapper: the single place that knows the "wK" token encoding, in both
 * directions. Keeping token knowledge here (and out of the model) means a new
 * input/output format only touches this class.
 */
public class PieceMapper {
    /** "wK" -> Piece(WHITE, K); "." or anything invalid -> null. */
    public static Piece parse(String token) {
        if (token == null || token.length() != 2) return null;

        char c = token.charAt(0);
        char t = token.charAt(1);

        Piece.Color color = (c == 'w') ? Piece.Color.WHITE
                          : (c == 'b') ? Piece.Color.BLACK
                          : null;

        Piece.Type type;
        switch (t) {
            case 'K': type = Piece.Type.K; break;
            case 'Q': type = Piece.Type.Q; break;
            case 'R': type = Piece.Type.R; break;
            case 'B': type = Piece.Type.B; break;
            case 'N': type = Piece.Type.N; break;
            case 'P': type = Piece.Type.P; break;
            default:  type = null; break;
        }

        if (color == null || type == null) return null;
        return Piece.of(color, type);
    }

    /** Piece(WHITE, K) -> "wK". */
    public static String format(Piece piece) {
        char c = (piece.getColor() == Piece.Color.WHITE) ? 'w' : 'b';
        return c + piece.getType().name();
    }
}
