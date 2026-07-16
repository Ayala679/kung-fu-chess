package tests;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import model.Piece;
import model.Position;
import snapshot.MoveNotation;

class MoveNotationTest {
    @Test void testPawnMoveHasNoLetterPrefix() {
        Piece pawn = Piece.of(Piece.Color.WHITE, Piece.Type.P);
        assertEquals("e4", MoveNotation.describe(pawn, new Position(4, 4), 8));
    }

    @Test void testKnightMoveHasLetterPrefix() {
        Piece knight = Piece.of(Piece.Color.WHITE, Piece.Type.N);
        assertEquals("Nf3", MoveNotation.describe(knight, new Position(5, 5), 8));
    }

    @Test void testEachPieceTypeUsesItsOwnLetter() {
        assertEquals("Ra1", MoveNotation.describe(Piece.of(Piece.Color.WHITE, Piece.Type.R), new Position(7, 0), 8));
        assertEquals("Bc1", MoveNotation.describe(Piece.of(Piece.Color.WHITE, Piece.Type.B), new Position(7, 2), 8));
        assertEquals("Qd1", MoveNotation.describe(Piece.of(Piece.Color.WHITE, Piece.Type.Q), new Position(7, 3), 8));
        assertEquals("Ke1", MoveNotation.describe(Piece.of(Piece.Color.WHITE, Piece.Type.K), new Position(7, 4), 8));
    }

    @Test void testRankIsInvertedForWhitesPerspective() {
        // row 7 (last row, White's own back rank) reads as rank 1
        assertEquals("a1", MoveNotation.describe(Piece.of(Piece.Color.WHITE, Piece.Type.P), new Position(7, 0), 8));
        // row 0 (top row, Black's back rank) reads as rank 8
        assertEquals("a8", MoveNotation.describe(Piece.of(Piece.Color.WHITE, Piece.Type.P), new Position(0, 0), 8));
    }
}
