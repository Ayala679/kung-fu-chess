package tests;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import model.Board;
import model.MovingPiece;
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

    @Test void testPathClearTreatsADepartedOriginAsEmpty() {
        // The board only clears a cell on arrival, so a piece mid-flight away
        // from (1,1) still shows up there in board.getCell() - but it has
        // already left, so it must not block another piece's path through it.
        Piece[][] grid = new Piece[3][3];
        Piece mover = Piece.of(Piece.Color.WHITE, Piece.Type.B);
        grid[1][1] = mover;
        Board b = new Board(grid);
        MoveValidator v = new MoveValidator(b);

        MovingPiece inFlight = new MovingPiece(mover, new Position(1, 1), new Position(2, 2), 1000, 0);
        List<MovingPiece> active = List.of(inFlight);

        assertFalse(v.isPathClear(new Position(1, 0), new Position(1, 2))); // old behavior, no active-move awareness
        assertTrue(v.isPathClear(new Position(1, 0), new Position(1, 2), active, 500));
    }

    @Test void testPathClearStillBlockedByAPieceJumpingInPlace() {
        // A jump (from == to) never departs its cell - it's still physically
        // there defending, so it must keep blocking the path.
        Piece[][] grid = new Piece[3][3];
        Piece jumper = Piece.of(Piece.Color.WHITE, Piece.Type.B);
        grid[1][1] = jumper;
        Board b = new Board(grid);
        MoveValidator v = new MoveValidator(b);

        MovingPiece jump = new MovingPiece(jumper, new Position(1, 1), new Position(1, 1), 1000, 0);
        List<MovingPiece> active = List.of(jump);

        assertFalse(v.isPathClear(new Position(1, 0), new Position(1, 2), active, 500));
    }

    @Test void testPathClearIgnoresAMoveThatHasAlreadyArrived() {
        Piece[][] grid = new Piece[3][3];
        Piece mover = Piece.of(Piece.Color.WHITE, Piece.Type.B);
        grid[1][1] = mover;
        Board b = new Board(grid);
        MoveValidator v = new MoveValidator(b);

        MovingPiece arrived = new MovingPiece(mover, new Position(1, 1), new Position(2, 2), 1000, 0);
        List<MovingPiece> active = List.of(arrived);

        // currentTime (1000) is at the move's arrival time - no longer "departing"
        assertFalse(v.isPathClear(new Position(1, 0), new Position(1, 2), active, 1000));
    }
}
