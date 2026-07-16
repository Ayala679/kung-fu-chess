package model;

/**
 * A piece that just finished a move or jump and can't act again until
 * restUntil. fromJump distinguishes which rest sprite applies: a jump leads to
 * a short rest, a slide-move leads to a long rest.
 */
public class RestingPiece {
    private final Piece piece;
    private final Position position;
    private final long restUntil;
    private final boolean fromJump;

    public RestingPiece(Piece piece, Position position, long restUntil, boolean fromJump) {
        this.piece = piece;
        this.position = position;
        this.restUntil = restUntil;
        this.fromJump = fromJump;
    }

    public Piece getPiece() { return piece; }
    public Position getPosition() { return position; }
    public long getRestUntil() { return restUntil; }
    public boolean isFromJump() { return fromJump; }
}
