package snapshot;

import java.util.List;
import model.MoveLogEntry;
import model.Position;

/**
 * Immutable, render-ready description of the whole board at one instant. Pure
 * data - built by snapshot.SnapshotBuilder from the live model, carries no game
 * logic of its own.
 */
public record GameSnapshot(
        int boardWidth,
        int boardHeight,
        List<PieceSnapshot> pieces,
        Position positionSelected,
        boolean gameOver,
        String winner,
        int whiteScore,
        int blackScore,
        List<MoveLogEntry> whiteMoves,
        List<MoveLogEntry> blackMoves,
        List<Position> legalDestinations,
        String whiteName,
        String blackName
) {
    /** Defensively copies every list field so the "Immutable" claim above holds regardless of what the caller passes in or does with its own reference afterward. */
    public GameSnapshot {
        pieces = List.copyOf(pieces);
        whiteMoves = List.copyOf(whiteMoves);
        blackMoves = List.copyOf(blackMoves);
        legalDestinations = List.copyOf(legalDestinations);
    }
}
