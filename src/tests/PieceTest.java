package tests;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import model.Piece;

class PieceTest {
    @Test void testOfAndGetters() {
        Piece p = Piece.of(Piece.Color.WHITE, Piece.Type.K);
        assertEquals(Piece.Color.WHITE, p.getColor());
        assertEquals(Piece.Type.K, p.getType());
    }

    @Test void testEqualsAndHashCode() {
        Piece a = Piece.of(Piece.Color.BLACK, Piece.Type.Q);
        Piece b = Piece.of(Piece.Color.BLACK, Piece.Type.Q);
        Piece c = Piece.of(Piece.Color.WHITE, Piece.Type.Q);

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, c);
        assertNotEquals(a, "not a piece");
        assertEquals(a, a);
    }

    @Test void testToString() {
        Piece p = Piece.of(Piece.Color.WHITE, Piece.Type.N);
        assertEquals("WHITE N", p.toString());
    }

    @Test void testPawnPromotesAtFarRow() {
        Piece whitePawn = Piece.of(Piece.Color.WHITE, Piece.Type.P);
        Piece promoted = whitePawn.promotedAt(0, 8);
        assertEquals(Piece.Type.Q, promoted.getType());
        assertEquals(Piece.Color.WHITE, promoted.getColor());

        Piece blackPawn = Piece.of(Piece.Color.BLACK, Piece.Type.P);
        assertEquals(Piece.Type.Q, blackPawn.promotedAt(7, 8).getType());
    }

    @Test void testPawnDoesNotPromoteMidBoard() {
        Piece whitePawn = Piece.of(Piece.Color.WHITE, Piece.Type.P);
        Piece result = whitePawn.promotedAt(4, 8);
        assertEquals(Piece.Type.P, result.getType());
    }

    @Test void testNonPawnNeverPromotes() {
        Piece rook = Piece.of(Piece.Color.WHITE, Piece.Type.R);
        assertEquals(Piece.Type.R, rook.promotedAt(0, 8).getType());
    }
}
