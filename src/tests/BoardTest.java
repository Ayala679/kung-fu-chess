package tests;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import model.Board;
import model.Piece;
import model.Position;

class BoardTest {
    @Test void testBoardCreation() {
        Piece[][] grid = new Piece[8][8];
        Board b = new Board(grid);
        assertEquals(8, b.getHeight());
        assertEquals(8, b.getWidth());
    }

    @Test void testEmptyBoard() {
        Board b = new Board(new Piece[0][0]);
        assertTrue(b.isEmpty());
        assertEquals(0, b.getHeight());
        assertEquals(0, b.getWidth());
    }

    @Test void testNonEmptyBoardIsNotEmpty() {
        Board b = new Board(new Piece[8][8]);
        assertFalse(b.isEmpty());
    }

    @Test void testSetGetCell() {
        Piece[][] grid = new Piece[8][8];
        Board b = new Board(grid);
        Piece wK = Piece.of(Piece.Color.WHITE, Piece.Type.K);
        b.setCell(0, 0, wK);
        assertEquals(wK, b.getCell(0, 0));
    }

    @Test void testPositionSetGet() {
        Piece[][] grid = new Piece[8][8];
        Board b = new Board(grid);
        Piece bQ = Piece.of(Piece.Color.BLACK, Piece.Type.Q);
        Position p = new Position(3, 4);
        b.setCell(p, bQ);
        assertEquals(bQ, b.getCell(p));
    }

    @Test void testGetCellBoundsCheck() {
        Board b = new Board(new Piece[8][8]);
        assertNull(b.getCell(-1, 0));
        assertNull(b.getCell(0, -1));
        assertNull(b.getCell(8, 0));
        assertNull(b.getCell(0, 8));
    }

    @Test void testSetCellOutOfBoundsIsIgnored() {
        Board b = new Board(new Piece[8][8]);
        Piece wK = Piece.of(Piece.Color.WHITE, Piece.Type.K);
        b.setCell(-1, 0, wK);
        b.setCell(0, 8, wK);
        // no exception, and nothing was written since both writes were out of bounds
        assertNull(b.getCell(0, 0));
    }

    @Test void testInBounds() {
        Board b = new Board(new Piece[8][8]);
        assertTrue(b.inBounds(0, 0));
        assertTrue(b.inBounds(7, 7));
        assertTrue(b.inBounds(new Position(3, 3)));
        assertFalse(b.inBounds(-1, 0));
        assertFalse(b.inBounds(8, 0));
        assertFalse(b.inBounds(new Position(0, 8)));
    }
}
