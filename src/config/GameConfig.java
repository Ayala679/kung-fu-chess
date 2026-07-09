package config;

public class GameConfig {
    public static final String EMPTY = ".";
    public static final String TOKEN_PATTERN = "[wb][KQRBNP]";
    public static final String FULL_TOKEN_OR_EMPTY = "\\." + "|[wb][KQRBNP]";

    // Timing constants reverted to the original logic
    public static final long MOVE_DURATION_PER_CELL = 1000L;
    public static final long KNIGHT_TOTAL_DURATION = 3000L;
    public static final long JUMP_DURATION = 1000L;

    // pixel size of a cell when converting click coordinates to board indices
    public static final int CELL_PIXEL_SIZE = 100;

    // Pawn starting rows
    public static final int PAWN_START_ROW_WHITE = 6;
    public static final int PAWN_START_ROW_BLACK = 1;
}