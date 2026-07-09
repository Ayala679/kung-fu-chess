package tests;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

// ניתובי הייבוא לקוד המקור
import model.Position;

class PositionTest {
    @Test void testPositionCreation() {
        Position p = new Position(3, 4);
        assertEquals(3, p.getRow());
        assertEquals(4, p.getCol());
    }

    @Test void testRowDistance() {
        Position p1 = new Position(1, 1);
        Position p2 = new Position(4, 5);
        assertEquals(3, p1.rowDistance(p2));
    }

    @Test void testColDistance() {
        Position p1 = new Position(1, 1);
        Position p2 = new Position(4, 6);
        assertEquals(5, p1.colDistance(p2));
    }

    @Test void testEquality() {
        Position p1 = new Position(2, 2);
        Position p2 = new Position(2, 2);
        assertEquals(p1, p2);
    }
}