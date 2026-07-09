package ruleengine;

import model.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class MoveValidatorTest {
    @Test void testPathClear() {
        Piece[][] grid = new Piece[8][8];
        Board b = new Board(grid);
        MoveValidator v = new MoveValidator(b);
        assertTrue(v.isPathClear(new Position(0, 0), new Position(2, 0)));
    }

    @Test void testPathBlocked() {
        Piece[][] grid = new Piece[8][8];
        grid[1][0] = Piece.fromToken("wP");
        Board b = new Board(grid);
        MoveValidator v = new MoveValidator(b);
        assertFalse(v.isPathClear(new Position(0, 0), new Position(2, 0)));
    }

    @Test void testValidToken() {
        Piece[][] grid = new Piece[8][8];
        Board b = new Board(grid);
        MoveValidator v = new MoveValidator(b);
        assertTrue(v.isValidToken(null));
        assertTrue(v.isValidToken("wK"));
    }

    @Test void testKingMove() {
        Piece[][] grid = new Piece[8][8];
        Board b = new Board(grid);
        MoveValidator v = new MoveValidator(b);
        Piece wK = Piece.fromToken("wK");
        assertTrue(v.isValidMove(wK, new Position(4, 4), new Position(4, 5)));
    }
}

