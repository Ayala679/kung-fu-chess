package config;

public class GameConfig {
    public static final String EMPTY = ".";
    public static final String TOKEN_PATTERN = "[wb][KQRBNP]";
    public static final String FULL_TOKEN_OR_EMPTY = "\\." + "|[wb][KQRBNP]";

    // Timing constants - base unit is a single-cell move, one full second
    // (slowed down from the original 2/3 second - the previous pace was too
    // fast to comfortably react to). Most other durations below keep the
    // same ratio to this base they always had, so scaling this one number
    // rescales the whole game - DEFAULT_SHORT_REST_DURATION is the one
    // deliberate exception, see its own comment.
    public static final long MOVE_DURATION_PER_CELL = 1000L; // 1 second
    public static final long KNIGHT_TOTAL_DURATION = 3000L; // 3x the per-cell duration
    // Deliberately shorter than a single-cell move (not "was equal" anymore):
    // a jump can only ever defend if there's still at least JUMP_DURATION of
    // travel time left on the incoming attack when it's requested - if it
    // exactly equaled MOVE_DURATION_PER_CELL, reacting to an adjacent attack
    // (which takes exactly one cell's worth of time) could only ever succeed
    // on a razor's-edge, instant-reaction tie, and any later reaction -
    // however "last second" it visually looked - was mathematically doomed.
    public static final long JUMP_DURATION = 500L;

    // default rest durations after a move (long) or a jump (short) - stored on
    // each Piece instance (model.Piece.getLongRestDuration()/getShortRestDuration()),
    // this is just the value new pieces start with
    public static final long DEFAULT_LONG_REST_DURATION = 1000L; // equal to the per-cell duration
    // Deliberately NOT scaled at the same 0.4x ratio the previous pace used
    // (that would be ~400ms): gameengine.RealTimeArbiter's jump-defense grace
    // period is JUMP_DURATION + this value, and that combined window has to
    // comfortably cover the single fastest possible attack (one cell,
    // MOVE_DURATION_PER_CELL) even for an instantly-reacting defender, or a
    // dodge against the most common capture shape in the game could never
    // mathematically succeed. 500 (jump) + 600 (this) = 1100ms, a 100ms
    // cushion over the 1000ms adjacent-attack case - don't shrink this
    // below JUMP_DURATION's shortfall against MOVE_DURATION_PER_CELL without
    // re-checking that math.
    public static final long DEFAULT_SHORT_REST_DURATION = 600L;

    // pixel size of a cell when converting click coordinates to board indices
    public static final int CELL_PIXEL_SIZE = 100;

    // Pawn starting rows
    public static final int PAWN_START_ROW_WHITE = 6;
    public static final int PAWN_START_ROW_BLACK = 1;

    // board dimensions in cells, used by view.BoardGeometry to convert a rendered
    // board's pixel size into per-cell pixels
    public static final int BOARD_ROWS = 8;
    public static final int BOARD_COLS = 8;
}
