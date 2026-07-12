package tests;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import model.Piece;
import parsing.PieceMapper;

class PieceMapperTest {
    @Test void testParseAllTokens() {
        String[] tokens = {"wK", "wQ", "wR", "wB", "wN", "wP", "bK", "bQ", "bR", "bB", "bN", "bP"};
        for (String t : tokens) {
            Piece p = PieceMapper.parse(t);
            assertNotNull(p, "expected a piece for token " + t);
            assertEquals(t, PieceMapper.format(p));
        }
    }

    @Test void testParseWhiteAndBlackColors() {
        assertEquals(Piece.Color.WHITE, PieceMapper.parse("wK").getColor());
        assertEquals(Piece.Color.BLACK, PieceMapper.parse("bK").getColor());
    }

    @Test void testParseNullReturnsNull() {
        assertNull(PieceMapper.parse(null));
    }

    @Test void testParseWrongLengthReturnsNull() {
        assertNull(PieceMapper.parse("w"));
        assertNull(PieceMapper.parse("wKK"));
        assertNull(PieceMapper.parse(""));
    }

    @Test void testParseUnknownColorReturnsNull() {
        assertNull(PieceMapper.parse("xK"));
    }

    @Test void testParseUnknownTypeReturnsNull() {
        assertNull(PieceMapper.parse("wX"));
    }

    @Test void testFormatRoundTrip() {
        Piece p = Piece.of(Piece.Color.BLACK, Piece.Type.N);
        assertEquals("bN", PieceMapper.format(p));
    }
}
