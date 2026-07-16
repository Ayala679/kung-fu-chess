package view;

import config.GameConfig;
import model.Position;

/**
 * Converts between board cells (row, col) and pixel coordinates for a board
 * currently rendered at boardWidthPx x boardHeightPx. Cell size is recomputed
 * from those dimensions on every call rather than cached, so a window resize
 * just means passing the current width/height - there is no stale state.
 *
 * This is the only place pixel-per-cell math lives; snapshot.SnapshotBuilder
 * works purely in board coordinates (row/col) and never calls this class.
 */
public class BoardGeometry {

    private BoardGeometry() {}

    public static double cellWidth(int boardWidthPx) {
        return boardWidthPx / (double) GameConfig.BOARD_COLS;
    }

    public static double cellHeight(int boardHeightPx) {
        return boardHeightPx / (double) GameConfig.BOARD_ROWS;
    }

    /** Top-left pixel of column col / row row, for placing a piece sprite there. */
    public static int cellX(int col, int boardWidthPx) {
        return (int) Math.round(col * cellWidth(boardWidthPx));
    }

    public static int cellY(int row, int boardHeightPx) {
        return (int) Math.round(row * cellHeight(boardHeightPx));
    }

    /** The cell a pixel click (x, y) falls in, clamped to the board's bounds. */
    public static Position cellAt(int x, int y, int boardWidthPx, int boardHeightPx) {
        int col = clamp((int) (x / cellWidth(boardWidthPx)), 0, GameConfig.BOARD_COLS - 1);
        int row = clamp((int) (y / cellHeight(boardHeightPx)), 0, GameConfig.BOARD_ROWS - 1);
        return new Position(row, col);
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
