package view;

import java.awt.Color;
import java.awt.Dimension;
import java.util.List;

import config.GameConfig;
import model.MoveLogEntry;
import model.Position;
import snapshot.GameSnapshot;
import snapshot.PieceSnapshot;
import snapshot.PieceVisualState;

/**
 * Draws a GameSnapshot using only Img's own API - read, drawOn, putText - no
 * direct AWT/Graphics2D calls anywhere in this class. The canvas is
 * resources/dashboard.png (bigger than the board itself, with room on the
 * sides/top/bottom for the move tables, rank/file labels and score); the
 * board image is pasted onto it at a fixed offset, and every piece/highlight
 * position is shifted by that same offset. The selection highlight, rest
 * overlay and table borders are themselves tiny image assets, pasted on with
 * drawOn exactly like the board background; piece sprites come from
 * PieceSprites (resources/pieces/...), which picks the right animation frame
 * for the piece's current state.
 */
public class ImgRenderer {
    public static final int BOARD_OFFSET_X = 260;
    public static final int BOARD_OFFSET_Y = 120;

    private static final String DASHBOARD_ASSET = "resources/dashboard.png";
    private static final String HIGHLIGHT_ASSET = "resources/highlight.png";
    private static final String LEGAL_MOVE_ASSET = "resources/legal_move.png";
    private static final String REST_OVERLAY_ASSET = "resources/rest_overlay.png";
    private static final String DIM_OVERLAY_ASSET = "resources/dim_overlay.png";
    private static final String LINE_ASSET = "resources/line.png";

    private static final int TABLE_TOP_Y = 200;
    private static final int ROW_HEIGHT = 30;
    private static final int MOVE_COLUMN_OFFSET = 140;
    private static final int TABLE_WIDTH = 210;

    private final String boardImagePath;
    private final PieceSprites sprites = new PieceSprites();

    public ImgRenderer(String boardImagePath) {
        this.boardImagePath = boardImagePath;
    }

    /** The board image's own pixel size (before the dashboard offset), for click-bounds checks. */
    public int getBoardWidthPx() { return new Img().read(boardImagePath).get().getWidth(); }
    public int getBoardHeightPx() { return new Img().read(boardImagePath).get().getHeight(); }

    public Img render(GameSnapshot snapshot) {
        Img frame = new Img().read(DASHBOARD_ASSET);
        Img board = new Img().read(boardImagePath);
        int boardWidthPx = board.get().getWidth();
        int boardHeightPx = board.get().getHeight();
        board.drawOn(frame, BOARD_OFFSET_X, BOARD_OFFSET_Y);

        drawBoardLabels(frame, boardWidthPx, boardHeightPx);

        if (snapshot.positionSelected() != null) {
            drawSelectionHighlight(frame, snapshot.positionSelected(), boardWidthPx, boardHeightPx);
        }

        for (Position destination : snapshot.legalDestinations()) {
            drawLegalMoveMarker(frame, destination, boardWidthPx, boardHeightPx);
        }

        for (PieceSnapshot piece : snapshot.pieces()) {
            drawPiece(frame, piece, boardWidthPx, boardHeightPx);
        }

        drawScores(frame, snapshot.whiteScore(), snapshot.blackScore(), snapshot.whiteName(), snapshot.blackName());
        drawMoveTable(frame, "Black", snapshot.blackMoves(), 20);
        drawMoveTable(frame, "White", snapshot.whiteMoves(), BOARD_OFFSET_X + boardWidthPx + 20);

        if (snapshot.gameOver()) {
            drawGameOverBanner(frame, snapshot.winner(), boardWidthPx, boardHeightPx);
        }

        return frame;
    }

    /** Rank numbers 1-8 (bottom to top) on the board's left edge, file letters A-H (left to right) above and below. */
    private void drawBoardLabels(Img frame, int boardWidthPx, int boardHeightPx) {
        double cellWidth = BoardGeometry.cellWidth(boardWidthPx);
        double cellHeight = BoardGeometry.cellHeight(boardHeightPx);

        for (int row = 0; row < GameConfig.BOARD_ROWS; row++) {
            int rank = GameConfig.BOARD_ROWS - row; // row 0 (top) -> 8, row 7 (bottom) -> 1
            int y = BOARD_OFFSET_Y + (int) (row * cellHeight + cellHeight / 2) + 6;
            frame.putText(String.valueOf(rank), BOARD_OFFSET_X - 25, y, 1.2f, Color.BLACK, 0);
        }

        for (int col = 0; col < GameConfig.BOARD_COLS; col++) {
            char file = (char) ('A' + col);
            int x = BOARD_OFFSET_X + (int) (col * cellWidth + cellWidth / 2) - 6;
            frame.putText(String.valueOf(file), x, BOARD_OFFSET_Y - 12, 1.2f, Color.BLACK, 0);
            frame.putText(String.valueOf(file), x, BOARD_OFFSET_Y + boardHeightPx + 28, 1.2f, Color.BLACK, 0);
        }
    }

    private void drawPiece(Img frame, PieceSnapshot piece, int boardWidthPx, int boardHeightPx) {
        double x = lerp(BoardGeometry.cellX(piece.fromCol(), boardWidthPx),
                         BoardGeometry.cellX(piece.toCol(), boardWidthPx), piece.progress());
        double y = lerp(BoardGeometry.cellY(piece.fromRow(), boardHeightPx),
                         BoardGeometry.cellY(piece.toRow(), boardHeightPx), piece.progress());

        int cellWidth = (int) Math.round(BoardGeometry.cellWidth(boardWidthPx));
        int cellHeight = (int) Math.round(BoardGeometry.cellHeight(boardHeightPx));
        int drawX = BOARD_OFFSET_X + (int) x;
        int drawY = BOARD_OFFSET_Y + (int) y;

        Img sprite = sprites.frame(piece.type(), piece.color(), piece.state(),
                piece.stateElapsedMillis(), cellWidth, cellHeight);
        sprite.drawOn(frame, drawX, drawY);

        if (piece.state() == PieceVisualState.SHORT_REST || piece.state() == PieceVisualState.LONG_REST) {
            // piece.progress() here is how much of the rest is done (0..1) - see
            // SnapshotBuilder. The overlay drains downward as rest completes, like
            // a water level dropping, anchored to the cell's bottom edge.
            drawRestOverlay(frame, drawX, drawY, cellWidth, cellHeight, piece.progress());
        }
    }

    private void drawRestOverlay(Img frame, int cellX, int cellY, int cellWidth, int cellHeight, double restProgress) {
        int overlayHeight = (int) Math.round(cellHeight * (1.0 - clamp01(restProgress)));
        if (overlayHeight <= 0) return;
        int overlayY = cellY + (cellHeight - overlayHeight);
        new Img().read(REST_OVERLAY_ASSET, new Dimension(cellWidth, overlayHeight), false, null)
                .drawOn(frame, cellX, overlayY);
    }

    private void drawSelectionHighlight(Img frame, Position pos, int boardWidthPx, int boardHeightPx) {
        drawCellOverlay(frame, pos, HIGHLIGHT_ASSET, boardWidthPx, boardHeightPx);
    }

    /** Subtle muted tint on every square the selected piece could legally move to right now. */
    private void drawLegalMoveMarker(Img frame, Position pos, int boardWidthPx, int boardHeightPx) {
        drawCellOverlay(frame, pos, LEGAL_MOVE_ASSET, boardWidthPx, boardHeightPx);
    }

    private void drawCellOverlay(Img frame, Position pos, String asset, int boardWidthPx, int boardHeightPx) {
        int x = BoardGeometry.cellX(pos.getCol(), boardWidthPx);
        int y = BoardGeometry.cellY(pos.getRow(), boardHeightPx);
        int w = Math.min((int) Math.round(BoardGeometry.cellWidth(boardWidthPx)), boardWidthPx - x);
        int h = Math.min((int) Math.round(BoardGeometry.cellHeight(boardHeightPx)), boardHeightPx - y);
        new Img().read(asset, new Dimension(w, h), false, null)
                .drawOn(frame, BOARD_OFFSET_X + x, BOARD_OFFSET_Y + y);
    }

    /** Black's score sits up top (Black's side of the board); White's sits at the bottom (White's side). */
    private void drawScores(Img frame, int whiteScore, int blackScore, String whiteName, String blackName) {
        int canvasWidth = frame.get().getWidth();
        int canvasHeight = frame.get().getHeight();
        float fontSize = 2.6f;
        String blackLabel = blackName == null ? "Black" : "Black (" + blackName + ")";
        String whiteLabel = whiteName == null ? "White" : "White (" + whiteName + ")";
        drawCenteredText(frame, blackLabel + "   Score: " + blackScore, canvasWidth, 50, fontSize);
        drawCenteredText(frame, whiteLabel + "   Score: " + whiteScore, canvasWidth, canvasHeight - 30, fontSize);
    }

    // Img has no text-measurement API, so the rendered width is approximated
    // to center it - same technique as the game-over banner below.
    private void drawCenteredText(Img frame, String text, int canvasWidth, int y, float fontSize) {
        int approxTextWidth = (int) (text.length() * fontSize * 12 * 0.55);
        int x = (canvasWidth - approxTextWidth) / 2;
        frame.putText(text, x, y, fontSize, Color.BLACK, 0);
    }

    private void drawMoveTable(Img frame, String label, List<MoveLogEntry> moves, int panelX) {
        frame.putText(label, panelX, TABLE_TOP_Y - 40, 2.0f, Color.BLACK, 0);
        frame.putText("Time", panelX, TABLE_TOP_Y, 1.4f, Color.BLACK, 0);
        frame.putText("Move", panelX + MOVE_COLUMN_OFFSET, TABLE_TOP_Y, 1.4f, Color.BLACK, 0);

        int maxRows = Math.max(0, (frame.get().getHeight() - TABLE_TOP_Y - 60) / ROW_HEIGHT);
        int start = Math.max(0, moves.size() - maxRows);
        int rowsShown = Math.max(1, moves.size() - start);

        int tableLeft = panelX - 8;
        int tableTop = TABLE_TOP_Y - 28;
        int headerBottom = TABLE_TOP_Y + 10;
        int tableBottom = headerBottom + rowsShown * ROW_HEIGHT;
        int columnSplit = panelX + MOVE_COLUMN_OFFSET - 10;

        drawHorizontalLine(frame, tableLeft, tableTop, TABLE_WIDTH);
        drawHorizontalLine(frame, tableLeft, headerBottom, TABLE_WIDTH);
        drawHorizontalLine(frame, tableLeft, tableBottom, TABLE_WIDTH);
        drawVerticalLine(frame, tableLeft, tableTop, tableBottom - tableTop);
        drawVerticalLine(frame, tableLeft + TABLE_WIDTH, tableTop, tableBottom - tableTop);
        drawVerticalLine(frame, columnSplit, tableTop, tableBottom - tableTop);

        int y = TABLE_TOP_Y + ROW_HEIGHT;
        for (int i = start; i < moves.size(); i++) {
            MoveLogEntry entry = moves.get(i);
            frame.putText(formatTime(entry.getTimestamp()), panelX, y, 1.3f, Color.BLACK, 0);
            frame.putText(entry.getNotation(), panelX + MOVE_COLUMN_OFFSET, y, 1.3f, Color.BLACK, 0);
            y += ROW_HEIGHT;
        }
    }

    private void drawHorizontalLine(Img frame, int x, int y, int width) {
        new Img().read(LINE_ASSET, new Dimension(width, 2), false, null).drawOn(frame, x, y);
    }

    private void drawVerticalLine(Img frame, int x, int y, int height) {
        new Img().read(LINE_ASSET, new Dimension(2, Math.max(1, height)), false, null).drawOn(frame, x, y);
    }

    private static String formatTime(long ms) {
        long minutes = ms / 60000;
        long seconds = (ms % 60000) / 1000;
        long millis = ms % 1000;
        return String.format("%02d:%02d.%03d", minutes, seconds, millis);
    }

    private void drawGameOverBanner(Img frame, String winner, int boardWidthPx, int boardHeightPx) {
        if (winner == null) return;

        // Img has no pixel-filter/blur capability, so this dims the board area
        // instead of a true blur - the closest approximation drawOn alone allows.
        new Img().read(DIM_OVERLAY_ASSET, new Dimension(boardWidthPx, boardHeightPx), false, null)
                .drawOn(frame, BOARD_OFFSET_X, BOARD_OFFSET_Y);

        String message = "Game over, " + capitalize(winner) + " Won";
        int canvasWidth = frame.get().getWidth();
        int canvasHeight = frame.get().getHeight();
        float fontSize = 2.4f;

        // Img has no text-measurement API, so the rendered width is
        // approximated to center it - close enough for this fixed-shape message.
        int approxTextWidth = (int) (message.length() * fontSize * 12 * 0.55);
        int x = (canvasWidth - approxTextWidth) / 2;
        int y = canvasHeight / 2;

        frame.putText(message, x, y, fontSize, new Color(255, 215, 0), 0);
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : s.charAt(0) + s.substring(1).toLowerCase();
    }

    private static double lerp(double from, double to, double progress) {
        return from + (to - from) * progress;
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}
