package tests;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

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
        grid[1][0] = Piece.of(Piece.Color.WHITE, Piece.Type.P);
        Board b = new Board(grid);
        MoveValidator v = new MoveValidator(b);
        assertFalse(v.isPathClear(new Position(0, 0), new Position(2, 0)));
    }

    @Test void testPathClearDiagonal() {
        Board b = new Board(new Piece[8][8]);
        MoveValidator v = new MoveValidator(b);
        assertTrue(v.isPathClear(new Position(0, 0), new Position(3, 3)));
    }

    @Test void testGeneralMoveValidRequiresAMoverAtSource() {
        Board b = new Board(new Piece[8][8]);
        MoveValidator v = new MoveValidator(b);
        assertFalse(v.isGeneralMoveValid(new Position(0, 0), new Position(1, 1)));
    }

    @Test void testGeneralMoveValidRejectsFriendlyDestination() {
        Piece[][] grid = new Piece[8][8];
        grid[0][0] = Piece.of(Piece.Color.WHITE, Piece.Type.K);
        grid[1][1] = Piece.of(Piece.Color.WHITE, Piece.Type.P);
        Board b = new Board(grid);
        MoveValidator v = new MoveValidator(b);
        assertFalse(v.isGeneralMoveValid(new Position(0, 0), new Position(1, 1)));
    }

    @Test void testGeneralMoveValidAllowsCapturingEnemy() {
        Piece[][] grid = new Piece[8][8];
        grid[0][0] = Piece.of(Piece.Color.WHITE, Piece.Type.K);
        grid[1][1] = Piece.of(Piece.Color.BLACK, Piece.Type.P);
        Board b = new Board(grid);
        MoveValidator v = new MoveValidator(b);
        assertTrue(v.isGeneralMoveValid(new Position(0, 0), new Position(1, 1)));
    }

    @Test void testGeneralMoveValidRejectsOutOfBoundsSourceOrDestination() {
        Piece[][] grid = new Piece[8][8];
        grid[4][4] = Piece.of(Piece.Color.WHITE, Piece.Type.R);
        Board b = new Board(grid);
        MoveValidator v = new MoveValidator(b);

        assertFalse(v.isGeneralMoveValid(new Position(4, 4), new Position(4, 50)));
        assertFalse(v.isGeneralMoveValid(new Position(-1, 4), new Position(4, 4)));
    }
}
