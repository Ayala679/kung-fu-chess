package tests;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

// ניתובי הייבוא לקוד המקור
import model.Board;
import model.Piece;
import model.Position;
import ruleengine.MoveValidator;

class MoveValidatorTest {
    @Test void testPathClear() {
        Piece[][] grid = new Piece[3][3];
        Board b = new Board(grid);
        MoveValidator v = new MoveValidator(b);
        assertTrue(v.isPathClear(new Position(0, 0), new Position(2, 0)));
    }

    @Test void testPathBlocked() {
        Piece[][] grid = new Piece[3][3];
        grid[1][0] = Piece.fromToken("wP");
        Board b = new Board(grid);
        MoveValidator v = new MoveValidator(b);
        assertFalse(v.isPathClear(new Position(0, 0), new Position(2, 0)));
    }

    @Test void testValidToken() {
        Piece[][] grid = new Piece[3][3];
        Board b = new Board(grid);
        MoveValidator v = new MoveValidator(b);
        assertTrue(v.isValidToken(null));
        assertTrue(v.isValidToken("wK"));
    }

    @Test void testKingMove() {
        Piece[][] grid = new Piece[8][8];
        Piece wK = Piece.fromToken("wK");
        grid[4][4] = wK;
        Board b = new Board(grid);
        MoveValidator v = new MoveValidator(b);
        assertTrue(v.isValidMove(wK, new Position(4, 4), new Position(4, 5)));
    }
}