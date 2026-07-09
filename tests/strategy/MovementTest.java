package strategy;

import model.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class MovementTest {
    @Test void testKnightMovement() {
        Piece[][] grid = new Piece[8][8];
        Board b = new Board(grid);
        MovementStrategy knight = new KnightMovement();
        Piece wN = Piece.fromToken("wN");
        assertTrue(knight.isValid(b, new Position(0, 0), new Position(1, 2), wN));
        assertTrue(knight.isValid(b, new Position(0, 0), new Position(2, 1), wN));
        assertFalse(knight.isValid(b, new Position(0, 0), new Position(1, 1), wN));
    }

    @Test void testKingMovement() {
        Piece[][] grid = new Piece[8][8];
        Board b = new Board(grid);
        MovementStrategy king = new KingMovement();
        Piece wK = Piece.fromToken("wK");
        assertTrue(king.isValid(b, new Position(4, 4), new Position(4, 5), wK));
        assertTrue(king.isValid(b, new Position(4, 4), new Position(5, 5), wK));
        assertFalse(king.isValid(b, new Position(4, 4), new Position(6, 4), wK));
    }

    @Test void testPawnMovement() {
        Piece[][] grid = new Piece[8][8];
        Board b = new Board(grid);
        MovementStrategy pawn = new PawnMovement();
        Piece wP = Piece.fromToken("wP");
        assertTrue(pawn.isValid(b, new Position(6, 0), new Position(5, 0), wP));
        assertFalse(pawn.isValid(b, new Position(6, 0), new Position(4, 0), wP));
    }
}

