package snapshot;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import model.Board;
import model.GameState;
import model.MoveLogEntry;
import model.MovingPiece;
import model.Piece;
import model.Position;
import model.RestingPiece;

/**
 * Builds a render-ready GameSnapshot from the live model. Mirrors the same
 * board-walk + activeMoves-overlay that view.BoardRenderer.createDisplayBoard()
 * uses for the console grid, just emitting PieceSnapshots instead of strings.
 * Pure read - no mutation, no pixel math (that's view.BoardGeometry's job).
 */
public class SnapshotBuilder {

    private SnapshotBuilder() {}

    public static GameSnapshot build(Board board, List<MovingPiece> activeMoves,
                                      List<RestingPiece> restingPieces,
                                      GameState gameState, Position selection,
                                      int whiteScore, int blackScore,
                                      List<MoveLogEntry> whiteMoves, List<MoveLogEntry> blackMoves,
                                      List<Position> legalDestinations) {
        return build(board, activeMoves, restingPieces, gameState, selection, whiteScore, blackScore,
                whiteMoves, blackMoves, legalDestinations, null, null);
    }

    /** Same as above, with each side's display name (null when there isn't one - e.g. offline play). */
    public static GameSnapshot build(Board board, List<MovingPiece> activeMoves,
                                      List<RestingPiece> restingPieces,
                                      GameState gameState, Position selection,
                                      int whiteScore, int blackScore,
                                      List<MoveLogEntry> whiteMoves, List<MoveLogEntry> blackMoves,
                                      List<Position> legalDestinations,
                                      String whiteName, String blackName) {
        int height = board.getHeight();
        int width = board.getWidth();
        long currentTime = gameState.getCurrentTime();

        List<PieceSnapshot> pieces = new ArrayList<>();
        Set<Long> coveredCells = new HashSet<>();

        for (MovingPiece mp : activeMoves) {
            Position from = mp.getFrom();
            Position to = mp.getTo();
            long startTime = mp.getArrivalTime() - mp.getDuration();
            long elapsed = currentTime - startTime;

            if (currentTime < mp.getArrivalTime()) {
                double progress = mp.getDuration() == 0 ? 1.0 : clamp01(elapsed / (double) mp.getDuration());
                PieceVisualState state = mp.isMoving() ? PieceVisualState.MOVE : PieceVisualState.JUMP;
                pieces.add(new PieceSnapshot(
                        pieceId(mp.getPiece(), from.getRow(), from.getCol()),
                        mp.getPiece().getType(), mp.getPiece().getColor(),
                        from.getRow(), from.getCol(), to.getRow(), to.getCol(),
                        progress, state, elapsed));
                // Only claim the origin cell if the board still shows this same
                // piece sitting there - an enemy that's exempt from capturing a
                // fleeing piece (see RealTimeArbiter's departingActive) can still
                // land on that very square while this move is still in flight,
                // and that new occupant must not be hidden by this stale claim.
                if (board.getCell(from) == mp.getPiece()) {
                    coveredCells.add(cellKey(from.getRow(), from.getCol(), width));
                }
            } else {
                Piece finalPiece = mp.getPiece().promotedAt(to.getRow(), height);
                pieces.add(new PieceSnapshot(
                        pieceId(finalPiece, to.getRow(), to.getCol()),
                        finalPiece.getType(), finalPiece.getColor(),
                        to.getRow(), to.getCol(), to.getRow(), to.getCol(),
                        1.0, PieceVisualState.IDLE, elapsed));
                coveredCells.add(cellKey(to.getRow(), to.getCol(), width));
                if (mp.isMoving() && board.getCell(from) == mp.getPiece()) {
                    coveredCells.add(cellKey(from.getRow(), from.getCol(), width));
                }
            }
        }

        for (RestingPiece rp : restingPieces) {
            if (currentTime >= rp.getRestUntil()) continue;
            Position pos = rp.getPosition();
            if (coveredCells.contains(cellKey(pos.getRow(), pos.getCol(), width))) continue;

            Piece piece = rp.getPiece();
            long restDuration = rp.isFromJump() ? piece.getShortRestDuration() : piece.getLongRestDuration();
            long restStart = rp.getRestUntil() - restDuration;
            long elapsed = currentTime - restStart;
            // progress here means "how much of the rest is done" (0 at the start,
            // 1 once it's over) - reused instead of a new field, since a resting
            // piece never moves position so the MOVE/JUMP lerp meaning doesn't apply
            double restProgress = restDuration == 0 ? 1.0 : clamp01(elapsed / (double) restDuration);
            PieceVisualState state = rp.isFromJump() ? PieceVisualState.SHORT_REST : PieceVisualState.LONG_REST;
            pieces.add(new PieceSnapshot(
                    pieceId(piece, pos.getRow(), pos.getCol()),
                    piece.getType(), piece.getColor(),
                    pos.getRow(), pos.getCol(), pos.getRow(), pos.getCol(),
                    restProgress, state, elapsed));
            coveredCells.add(cellKey(pos.getRow(), pos.getCol(), width));
        }

        for (int row = 0; row < height; row++) {
            for (int col = 0; col < width; col++) {
                if (coveredCells.contains(cellKey(row, col, width))) continue;
                Piece piece = board.getCell(row, col);
                if (piece == null) continue;
                pieces.add(new PieceSnapshot(
                        pieceId(piece, row, col),
                        piece.getType(), piece.getColor(),
                        row, col, row, col,
                        0.0, PieceVisualState.IDLE, currentTime));
            }
        }

        Piece.Color winnerColor = gameState.getWinner();
        String winner = winnerColor == null ? null : winnerColor.name();

        return new GameSnapshot(width, height, pieces, selection, gameState.isGameOver(), winner,
                whiteScore, blackScore,
                whiteMoves, blackMoves, legalDestinations, whiteName, blackName);
    }

    private static String pieceId(Piece piece, int row, int col) {
        return piece.getColor() + "_" + piece.getType() + "_" + row + "_" + col;
    }

    private static long cellKey(int row, int col, int width) {
        return (long) row * width + col;
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}
