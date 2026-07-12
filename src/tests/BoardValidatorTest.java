package tests;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import parsing.BoardValidator;

class BoardValidatorTest {
    @Test void testIsValidTokenAcceptsEmptyMarkerAndPieces() {
        assertTrue(BoardValidator.isValidToken("."));
        assertTrue(BoardValidator.isValidToken("wK"));
        assertTrue(BoardValidator.isValidToken("bP"));
    }

    @Test void testIsValidTokenRejectsGarbage() {
        assertFalse(BoardValidator.isValidToken(null));
        assertFalse(BoardValidator.isValidToken(""));
        assertFalse(BoardValidator.isValidToken("xx"));
        assertFalse(BoardValidator.isValidToken("wZ"));
    }

    @Test void testIsValidRejectsNullOrEmptyBoard() {
        assertFalse(BoardValidator.isValid(null));
        assertFalse(BoardValidator.isValid(new String[0][0]));
    }

    @Test void testIsValidAcceptsWellFormedBoard() {
        String[][] board = {
            {"wK", "."},
            {".", "bK"}
        };
        assertTrue(BoardValidator.isValid(board));
    }

    @Test void testIsValidRejectsUnknownToken() {
        String[][] board = {
            {"wK", "??"},
            {".", "bK"}
        };
        assertFalse(BoardValidator.isValid(board));
    }
}
