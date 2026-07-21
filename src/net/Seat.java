package net;

import model.Piece;

/**
 * What a connection was assigned inside a game: an active color, or a
 * read-only spectator. Distinct from model.Piece.Color because a viewer
 * has no color at all.
 */
public enum Seat {
    WHITE, BLACK, VIEWER;

    public boolean isPlayer() {
        return this == WHITE || this == BLACK;
    }

    /** Only valid for WHITE/BLACK - a VIEWER has no board color. */
    public Piece.Color toColor() {
        switch (this) {
            case WHITE: return Piece.Color.WHITE;
            case BLACK: return Piece.Color.BLACK;
            default: throw new IllegalStateException("VIEWER has no color");
        }
    }

    public static Seat fromColor(Piece.Color color) {
        return color == Piece.Color.WHITE ? WHITE : BLACK;
    }
}
