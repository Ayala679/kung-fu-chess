package tests;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import model.MoveLogEntry;
import model.Piece;
import model.Position;
import net.SnapshotCodec;
import snapshot.GameSnapshot;
import snapshot.PieceSnapshot;
import snapshot.PieceVisualState;

class SnapshotCodecTest {

    @Test void testRoundTripPreservesEmptySnapshot() {
        GameSnapshot original = new GameSnapshot(8, 8, Collections.emptyList(), null, false, null,
                0, 0, Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), null, null);

        GameSnapshot decoded = SnapshotCodec.decode(SnapshotCodec.encode(original));

        assertEquals(original.boardWidth(), decoded.boardWidth());
        assertEquals(original.boardHeight(), decoded.boardHeight());
        assertFalse(decoded.gameOver());
        assertNull(decoded.winner());
        assertNull(decoded.positionSelected());
        assertTrue(decoded.pieces().isEmpty());
        assertTrue(decoded.legalDestinations().isEmpty());
        assertTrue(decoded.whiteMoves().isEmpty());
        assertTrue(decoded.blackMoves().isEmpty());
    }

    @Test void testRoundTripPreservesSelectionAndLegalDestinations() {
        List<Position> legal = Arrays.asList(new Position(4, 4), new Position(5, 5));
        GameSnapshot original = new GameSnapshot(8, 8, Collections.emptyList(), new Position(6, 4), false, null,
                0, 0, Collections.emptyList(), Collections.emptyList(), legal, null, null);

        GameSnapshot decoded = SnapshotCodec.decode(SnapshotCodec.encode(original));

        assertEquals(new Position(6, 4), decoded.positionSelected());
        assertEquals(legal, decoded.legalDestinations());
    }

    @Test void testRoundTripPreservesGameOverAndWinner() {
        GameSnapshot original = new GameSnapshot(8, 8, Collections.emptyList(), null, true, "WHITE",
                9, 4, Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), null, null);

        GameSnapshot decoded = SnapshotCodec.decode(SnapshotCodec.encode(original));

        assertTrue(decoded.gameOver());
        assertEquals("WHITE", decoded.winner());
        assertEquals(9, decoded.whiteScore());
        assertEquals(4, decoded.blackScore());
    }

    @Test void testRoundTripPreservesMoveLogEntries() {
        List<MoveLogEntry> whiteMoves = Arrays.asList(new MoveLogEntry(0, "e4"), new MoveLogEntry(900, "Nf3"));
        GameSnapshot original = new GameSnapshot(8, 8, Collections.emptyList(), null, false, null,
                0, 0, whiteMoves, Collections.emptyList(), Collections.emptyList(), null, null);

        GameSnapshot decoded = SnapshotCodec.decode(SnapshotCodec.encode(original));

        assertEquals(2, decoded.whiteMoves().size());
        assertEquals(0L, decoded.whiteMoves().get(0).getTimestamp());
        assertEquals("e4", decoded.whiteMoves().get(0).getNotation());
        assertEquals(900L, decoded.whiteMoves().get(1).getTimestamp());
        assertEquals("Nf3", decoded.whiteMoves().get(1).getNotation());
    }

    @Test void testRoundTripPreservesPlayerNames() {
        GameSnapshot original = new GameSnapshot(8, 8, Collections.emptyList(), null, false, null,
                0, 0, Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), "alice", "bob");

        GameSnapshot decoded = SnapshotCodec.decode(SnapshotCodec.encode(original));

        assertEquals("alice", decoded.whiteName());
        assertEquals("bob", decoded.blackName());
    }

    @Test void testRoundTripLeavesPlayerNamesNullWhenThereAreNone() {
        GameSnapshot original = new GameSnapshot(8, 8, Collections.emptyList(), null, false, null,
                0, 0, Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), null, null);

        GameSnapshot decoded = SnapshotCodec.decode(SnapshotCodec.encode(original));

        assertNull(decoded.whiteName());
        assertNull(decoded.blackName());
    }

    @Test void testRoundTripPreservesPieceAnimationState() {
        PieceSnapshot piece = new PieceSnapshot("id", Piece.Type.N, Piece.Color.BLACK,
                1, 2, 3, 4, 0.75, PieceVisualState.MOVE, 250);
        GameSnapshot original = new GameSnapshot(8, 8, Collections.singletonList(piece), null, false, null,
                0, 0, Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), null, null);

        GameSnapshot decoded = SnapshotCodec.decode(SnapshotCodec.encode(original));

        assertEquals(1, decoded.pieces().size());
        PieceSnapshot decodedPiece = decoded.pieces().get(0);
        assertEquals(Piece.Type.N, decodedPiece.type());
        assertEquals(Piece.Color.BLACK, decodedPiece.color());
        assertEquals(1, decodedPiece.fromRow());
        assertEquals(2, decodedPiece.fromCol());
        assertEquals(3, decodedPiece.toRow());
        assertEquals(4, decodedPiece.toCol());
        assertEquals(0.75, decodedPiece.progress(), 0.0001);
        assertEquals(PieceVisualState.MOVE, decodedPiece.state());
        assertEquals(250L, decodedPiece.stateElapsedMillis());
    }
}
