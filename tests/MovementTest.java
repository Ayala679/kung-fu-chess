package tests;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

// ניתובי הייבוא לקוד המקור
import model.Board;
import model.Piece;
import model.Position;
import ruleengine.PieceMovementRegistry;

class MovementTest {
    @Test void testKnightMovement() {
        Piece[][] grid = new Piece[8][8];
        Piece wN = Piece.fromToken("wN");
        Board b = new Board(grid);

        assertTrue(PieceMovementRegistry.isValid(Piece.Type.N, b, new Position(0, 0), new Position(1, 2), wN));
        assertTrue(PieceMovementRegistry.isValid(Piece.Type.N, b, new Position(0, 0), new Position(2, 1), wN));
        assertFalse(PieceMovementRegistry.isValid(Piece.Type.N, b, new Position(0, 0), new Position(1, 1), wN));
    }

    @Test void testKingMovement() {
        Piece[][] grid = new Piece[8][8];
        Piece wK = Piece.fromToken("wK");
        Board b = new Board(grid);

        assertTrue(PieceMovementRegistry.isValid(Piece.Type.K, b, new Position(4, 4), new Position(4, 5), wK));
        assertTrue(PieceMovementRegistry.isValid(Piece.Type.K, b, new Position(4, 4), new Position(5, 5), wK));
        assertFalse(PieceMovementRegistry.isValid(Piece.Type.K, b, new Position(4, 4), new Position(6, 4), wK));
    }

    @Test void testPawnMovement() {
        Piece[][] grid = new Piece[8][8];
        Piece wP = Piece.fromToken("wP");
        Board b = new Board(grid);

        assertTrue(PieceMovementRegistry.isValid(Piece.Type.P, b, new Position(6, 0), new Position(5, 0), wP));
        assertFalse(PieceMovementRegistry.isValid(Piece.Type.P, b, new Position(6, 0), new Position(4, 0), wP));
    }
}