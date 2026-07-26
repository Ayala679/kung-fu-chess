package snapshot;

import model.Piece;

/**
 * Immutable, render-ready description of one piece. Board-relative (row/col),
 * not pixels: pixel math is view.BoardGeometry's job, computed fresh from
 * whatever size the board is currently displayed at.
 *
 * fromRow/fromCol/toRow/toCol + progress describe where the piece is along its
 * current move (fromRow==toRow && fromCol==toCol, progress 0.0, when idle) - the
 * same data already carried by model.MovingPiece, just reshaped for rendering.
 */
public record PieceSnapshot(
        String id,
        Piece.Type type,
        Piece.Color color,
        int fromRow,
        int fromCol,
        int toRow,
        int toCol,
        double progress,
        PieceVisualState state,
        long stateElapsedMillis
) {
}
