package config;

public class GameConfig {
    public static final String EMPTY = ".";
    public static final String TOKEN_PATTERN = "[wb][KQRBNP]";
    public static final String FULL_TOKEN_OR_EMPTY = "\\." + "|[wb][KQRBNP]";

    // Timing constants - base unit is a single-cell move at two-thirds of a
    // second; every other duration below keeps the same ratio to that base
    // it always had (so scaling this one number rescales the whole game).
    public static final long MOVE_DURATION_PER_CELL = 667L; // 2/3 second
    public static final long KNIGHT_TOTAL_DURATION = 2000L; // was 3x the per-cell duration
    // Deliberately shorter than a single-cell move (not "was equal" anymore):
    // a jump can only ever defend if there's still at least JUMP_DURATION of
    // travel time left on the incoming attack when it's requested - if it
    // exactly equaled MOVE_DURATION_PER_CELL, reacting to an adjacent attack
    // (which takes exactly one cell's worth of time) could only ever succeed
    // on a razor's-edge, instant-reaction tie, and any later reaction -
    // however "last second" it visually looked - was mathematically doomed.
    public static final long JUMP_DURATION = 333L;

    // default rest durations after a move (long) or a jump (short) - stored on
    // each Piece instance (model.Piece.getLongRestDuration()/getShortRestDuration()),
    // this is just the value new pieces start with
    public static final long DEFAULT_LONG_REST_DURATION = 667L; // was equal to the per-cell duration
    public static final long DEFAULT_SHORT_REST_DURATION = 267L; // was 0.4x the per-cell duration

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