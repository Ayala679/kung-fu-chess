package snapshot;

/**
 * IDLE/MOVE/JUMP come from an active MovingPiece; SHORT_REST (after a jump) and
 * LONG_REST (after a slide-move) come from an active RestingPiece - see
 * gameengine.RealTimeArbiter's rest tracking.
 */
public enum PieceVisualState {
    IDLE,
    MOVE,
    JUMP,
    SHORT_REST,
    LONG_REST
}
