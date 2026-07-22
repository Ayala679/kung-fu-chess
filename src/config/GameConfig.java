package config;

public class GameConfig {
    public static final String EMPTY = ".";
    public static final String TOKEN_PATTERN = "[wb][KQRBNP]";
    public static final String FULL_TOKEN_OR_EMPTY = "\\." + "|[wb][KQRBNP]";

    // Timing constants - base unit is a single-cell move, one full second
    // (slowed down from the original 2/3 second - the previous pace was too
    // fast to comfortably react to). Most other durations below keep the
    // same ratio to this base they always had, so scaling this one number
    // rescales the whole game.
    public static final long MOVE_DURATION_PER_CELL = 1000L; // 1 second
    public static final long KNIGHT_TOTAL_DURATION = 3000L; // 3x the per-cell duration
    // A successful dodge requires the jump to still be genuinely airborne -
    // not yet landed - at the moment the incoming attack actually arrives
    // (see gameengine.RealTimeArbiter.isTooLateToJump/isProtectedByAnInProgressJump);
    // landing back down onto the attacker afterward is what captures it.
    // Deliberately shorter than a single-cell move (the fastest possible
    // attack, MOVE_DURATION_PER_CELL): reacting the instant an adjacent
    // attack starts (with the full 1000ms still on the clock) must still
    // fail - jumping "too early" just means landing back down long before
    // the attack arrives, an ordinary, undefended sitting duck by then. Only
    // reacting once the incoming attack has at most JUMP_DURATION left on
    // its own clock succeeds - widen this value to make that reaction
    // window more forgiving; keep it below MOVE_DURATION_PER_CELL so the
    // most common (adjacent) attack still requires *some* real reaction
    // rather than succeeding no matter when you jump.
    public static final long JUMP_DURATION = 700L;

    // default rest durations after a move (long) or a jump (short) - stored on
    // each Piece instance (model.Piece.getLongRestDuration()/getShortRestDuration()),
    // this is just the value new pieces start with
    public static final long DEFAULT_LONG_REST_DURATION = 1000L; // equal to the per-cell duration
    // Purely a cosmetic/balance choice now (how long a piece is briefly
    // vulnerable again right after a jump) - not coupled to JUMP_DURATION or
    // to any dodge-timing math, since the dodge itself now resolves entirely
    // at the jump's own landing (see JUMP_DURATION's comment above), not
    // during any post-landing window.
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
