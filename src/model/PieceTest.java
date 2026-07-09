package model;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class PieceTest {
    @Test void testFromToken() {
        Piece p = Piece.fromToken("wK");
        assertEquals(Piece.Color.WHITE, p.getColor());
        assertEquals(Piece.Type.K, p.getType());
    }

    @Test void testToToken() {
        Piece p = Piece.fromToken("bQ");
        assertEquals("bQ", p.toToken());
    }

    @Test void testNull() {
        assertNull(Piece.fromToken(null));
        assertNull(Piece.fromToken("xx"));
    }

    @Test void testAllPieces() {
        String[] tokens = {"wK", "wQ", "wR", "wB", "wN", "wP", "bK", "bQ", "bR", "bB", "bN", "bP"};
        for (String t : tokens) {
            assertNotNull(Piece.fromToken(t));
            assertEquals(t, Piece.fromToken(t).toToken());
        }
    }
}

