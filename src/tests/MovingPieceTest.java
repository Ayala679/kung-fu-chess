package tests;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import model.MovingPiece;
import model.Piece;
import model.Position;

class MovingPieceTest {
    @Test void testGettersAndArrivalTimeComputation() {
        Piece rook = Piece.of(Piece.Color.WHITE, Piece.Type.R);
        Position from = new Position(0, 0);
        Position to = new Position(0, 3);
        MovingPiece mp = new MovingPiece(rook, from, to, 1000, 500);

        assertEquals(rook, mp.getPiece());
        assertEquals(from, mp.getFrom());
        assertEquals(to, mp.getTo());
        assertEquals(1000, mp.getDuration());
        assertEquals(1500, mp.getArrivalTime());
    }

    @Test void testIsMovingWhenFromAndToDiffer() {
        Piece rook = Piece.of(Piece.Color.WHITE, Piece.Type.R);
        MovingPiece mp = new MovingPiece(rook, new Position(0, 0), new Position(0, 3), 1000, 0);
        assertTrue(mp.isMoving());
    }

    @Test void testIsNotMovingWhenFromEqualsTo() {
        Piece knight = Piece.of(Piece.Color.BLACK, Piece.Type.N);
        Position pos = new Position(2, 2);
        MovingPiece jump = new MovingPiece(knight, pos, pos, 500, 0);
        assertFalse(jump.isMoving());
    }

    @Test void testHasArrived() {
        Piece rook = Piece.of(Piece.Color.WHITE, Piece.Type.R);
        MovingPiece mp = new MovingPiece(rook, new Position(0, 0), new Position(0, 3), 1000, 0);

        assertFalse(mp.hasArrived(999));
        assertTrue(mp.hasArrived(1000));
        assertTrue(mp.hasArrived(1500));
    }

    @Test void testSetArrivalTime() {
        Piece rook = Piece.of(Piece.Color.WHITE, Piece.Type.R);
        MovingPiece mp = new MovingPiece(rook, new Position(0, 0), new Position(0, 3), 1000, 0);
        mp.setArrivalTime(2000);
        assertEquals(2000, mp.getArrivalTime());
    }
}
