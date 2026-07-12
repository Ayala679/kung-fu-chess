package tests;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import model.Board;
import model.Piece;
import model.Position;
import ruleengine.PieceRules;

class MovementTest {
    @Test void testKnightMovement() {
        Piece wN = Piece.of(Piece.Color.WHITE, Piece.Type.N);
        Board b = new Board(new Piece[8][8]);

        assertTrue(PieceRules.isValid(Piece.Type.N, b, new Position(0, 0), new Position(1, 2), wN));
        assertTrue(PieceRules.isValid(Piece.Type.N, b, new Position(0, 0), new Position(2, 1), wN));
        assertFalse(PieceRules.isValid(Piece.Type.N, b, new Position(0, 0), new Position(1, 1), wN));
    }

    @Test void testKingMovement() {
        Piece wK = Piece.of(Piece.Color.WHITE, Piece.Type.K);
        Board b = new Board(new Piece[8][8]);

        assertTrue(PieceRules.isValid(Piece.Type.K, b, new Position(4, 4), new Position(4, 5), wK));
        assertTrue(PieceRules.isValid(Piece.Type.K, b, new Position(4, 4), new Position(5, 5), wK));
        assertFalse(PieceRules.isValid(Piece.Type.K, b, new Position(4, 4), new Position(6, 4), wK));
    }

    @Test void testRookMovement() {
        Piece wR = Piece.of(Piece.Color.WHITE, Piece.Type.R);
        Board b = new Board(new Piece[8][8]);

        assertTrue(PieceRules.isValid(Piece.Type.R, b, new Position(0, 0), new Position(0, 7), wR));
        assertTrue(PieceRules.isValid(Piece.Type.R, b, new Position(0, 0), new Position(7, 0), wR));
        assertFalse(PieceRules.isValid(Piece.Type.R, b, new Position(0, 0), new Position(1, 1), wR));
    }

    @Test void testRookBlockedPath() {
        Piece[][] grid = new Piece[8][8];
        Piece wR = Piece.of(Piece.Color.WHITE, Piece.Type.R);
        grid[0][3] = Piece.of(Piece.Color.BLACK, Piece.Type.P);
        Board b = new Board(grid);

        assertFalse(PieceRules.isValid(Piece.Type.R, b, new Position(0, 0), new Position(0, 7), wR));
    }

    @Test void testBishopMovement() {
        Piece wB = Piece.of(Piece.Color.WHITE, Piece.Type.B);
        Board b = new Board(new Piece[8][8]);

        assertTrue(PieceRules.isValid(Piece.Type.B, b, new Position(0, 0), new Position(3, 3), wB));
        assertFalse(PieceRules.isValid(Piece.Type.B, b, new Position(0, 0), new Position(3, 4), wB));
    }

    @Test void testQueenMovement() {
        Piece wQ = Piece.of(Piece.Color.WHITE, Piece.Type.Q);
        Board b = new Board(new Piece[8][8]);

        assertTrue(PieceRules.isValid(Piece.Type.Q, b, new Position(3, 3), new Position(3, 7), wQ));
        assertTrue(PieceRules.isValid(Piece.Type.Q, b, new Position(3, 3), new Position(6, 6), wQ));
        assertFalse(PieceRules.isValid(Piece.Type.Q, b, new Position(3, 3), new Position(5, 6), wQ));
    }

    @Test void testPawnSingleStep() {
        Piece wP = Piece.of(Piece.Color.WHITE, Piece.Type.P);
        Board b = new Board(new Piece[8][8]);

        assertTrue(PieceRules.isValid(Piece.Type.P, b, new Position(6, 0), new Position(5, 0), wP));
        assertFalse(PieceRules.isValid(Piece.Type.P, b, new Position(6, 0), new Position(6, 1), wP)); // sideways
    }

    @Test void testPawnTwoStepsFromStartRow() {
        Piece wP = Piece.of(Piece.Color.WHITE, Piece.Type.P);
        Board b = new Board(new Piece[8][8]);

        assertTrue(PieceRules.isValid(Piece.Type.P, b, new Position(6, 0), new Position(4, 0), wP));
    }

    @Test void testPawnCannotTwoStepOutsideStartRow() {
        Piece wP = Piece.of(Piece.Color.WHITE, Piece.Type.P);
        Board b = new Board(new Piece[8][8]);

        assertFalse(PieceRules.isValid(Piece.Type.P, b, new Position(5, 0), new Position(3, 0), wP));
    }

    @Test void testPawnCannotAdvanceIntoOccupiedCell() {
        Piece[][] grid = new Piece[8][8];
        Piece wP = Piece.of(Piece.Color.WHITE, Piece.Type.P);
        grid[5][0] = Piece.of(Piece.Color.BLACK, Piece.Type.P);
        Board b = new Board(grid);

        assertFalse(PieceRules.isValid(Piece.Type.P, b, new Position(6, 0), new Position(5, 0), wP));
    }

    @Test void testPawnDiagonalCapture() {
        Piece[][] grid = new Piece[8][8];
        Piece wP = Piece.of(Piece.Color.WHITE, Piece.Type.P);
        grid[5][1] = Piece.of(Piece.Color.BLACK, Piece.Type.P);
        Board b = new Board(grid);

        assertTrue(PieceRules.isValid(Piece.Type.P, b, new Position(6, 0), new Position(5, 1), wP));
    }

    @Test void testPawnCannotCaptureDiagonallyWithoutEnemyThere() {
        Piece wP = Piece.of(Piece.Color.WHITE, Piece.Type.P);
        Board b = new Board(new Piece[8][8]);

        assertFalse(PieceRules.isValid(Piece.Type.P, b, new Position(6, 0), new Position(5, 1), wP));
    }

    @Test void testBlackPawnMovesDownward() {
        Piece bP = Piece.of(Piece.Color.BLACK, Piece.Type.P);
        Board b = new Board(new Piece[8][8]);

        assertTrue(PieceRules.isValid(Piece.Type.P, b, new Position(1, 0), new Position(2, 0), bP));
        assertTrue(PieceRules.isValid(Piece.Type.P, b, new Position(1, 0), new Position(3, 0), bP));
    }
}
