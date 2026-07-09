package tests;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

// ניתובי הייבוא לקוד המקור
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

    @Test void testSetGetCell() {
        Piece[][] grid = new Piece[8][8];
        Board b = new Board(grid);
        Piece wK = Piece.fromToken("wK");
        b.setCell(0, 0, wK);
        assertEquals(wK, b.getCell(0, 0));
    }

    @Test void testPositionSetGet() {
        Piece[][] grid = new Piece[8][8];
        Board b = new Board(grid);
        Piece bQ = Piece.fromToken("bQ");
        Position p = new Position(3, 4);
        b.setCell(p, bQ);
        assertEquals(bQ, b.getCell(p));
    }

    @Test void testBoundsCheck() {
        Piece[][] grid = new Piece[8][8];
        Board b = new Board(grid);
        assertNull(b.getCell(-1, 0));
        assertNull(b.getCell(0, -1));
        assertNull(b.getCell(8, 0));
    }
}