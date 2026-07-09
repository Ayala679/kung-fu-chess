package config;

public class GameConfig {
    public static final String EMPTY = ".";
    public static final String TOKEN_PATTERN = "[wb][KQRBNP]";
    public static final String FULL_TOKEN_OR_EMPTY = "\\." + "|[wb][KQRBNP]";
    // Timing and layout constants centralized to avoid magic numbers
    public static final long NORMAL_MOVE_DURATION = 500L;
    public static final long KNIGHT_MOVE_DURATION = 250L;
    public static final long JUMP_DURATION = 1000L;
    // pixel size of a cell when converting click coordinates to board indices
    public static final int CELL_PIXEL_SIZE = 100;
    // Pawn starting rows (white pawns move "up" from higher index to lower index depending on board orientation)
    public static final int PAWN_START_ROW_WHITE = 6; // typical 8x8 with 0-based rows
    public static final int PAWN_START_ROW_BLACK = 1;
}


