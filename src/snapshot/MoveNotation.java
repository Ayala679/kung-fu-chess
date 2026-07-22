package snapshot;

import model.Piece;
import model.Position;

/**
 * Simple move notation for the history panel: piece type letter (omitted for
 * pawns) + destination square in algebraic form, e.g. "Nf3", "e4". No check
 * ("+") or castling ("O-O") - neither check detection nor castling exist in
 * this engine.
 */
public class MoveNotation {

    private MoveNotation() {}

    public static String describe(Piece piece, Position to, int boardHeight) {
        String typeLetter = piece.getType() == Piece.Type.P ? "" : piece.getType().name();
        return typeLetter + square(to, boardHeight);
    }

    /** Algebraic square name for a board position (e.g. "e4") - public so callers outside the move-log UI (e.g. server.GameSession's network events) can reuse the same row/col-to-square formula instead of re-deriving it. */
    public static String square(Position pos, int boardHeight) {
        char file = (char) ('a' + pos.getCol());
        int rank = boardHeight - pos.getRow();
        return "" + file + rank;
    }
}
