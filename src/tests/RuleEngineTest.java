package tests;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import model.Board;
import model.Piece;
import model.Position;
import ruleengine.RuleEngine;

class RuleEngineTest {
    @Test void testAllowsALegalRookMove() {
        Piece[][] grid = new Piece[8][8];
        grid[4][4] = Piece.of(Piece.Color.WHITE, Piece.Type.R);
        Board board = new Board(grid);
        RuleEngine rules = new RuleEngine(board);

        assertTrue(rules.isMoveAllowed(new Position(4, 4), new Position(4, 0)));
    }

    @Test void testRejectsMoveWithNoPieceAtSource() {
        RuleEngine rules = new RuleEngine(new Board(new Piece[8][8]));
        assertFalse(rules.isMoveAllowed(new Position(0, 0), new Position(1, 1)));
    }

    @Test void testRejectsMoveThatBreaksPieceGeometry() {
        Piece[][] grid = new Piece[8][8];
        grid[4][4] = Piece.of(Piece.Color.WHITE, Piece.Type.R);
        Board board = new Board(grid);
        RuleEngine rules = new RuleEngine(board);

        assertFalse(rules.isMoveAllowed(new Position(4, 4), new Position(5, 5)));
    }

    @Test void testRejectsOutOfBoundsDestination() {
        Piece[][] grid = new Piece[8][8];
        grid[4][4] = Piece.of(Piece.Color.WHITE, Piece.Type.R);
        Board board = new Board(grid);
        RuleEngine rules = new RuleEngine(board);

        assertFalse(rules.isMoveAllowed(new Position(4, 4), new Position(4, 50)));
    }

    @Test void testRejectsBlockedPath() {
        Piece[][] grid = new Piece[8][8];
        grid[4][4] = Piece.of(Piece.Color.WHITE, Piece.Type.R);
        grid[4][2] = Piece.of(Piece.Color.BLACK, Piece.Type.P);
        Board board = new Board(grid);
        RuleEngine rules = new RuleEngine(board);

        assertFalse(rules.isMoveAllowed(new Position(4, 4), new Position(4, 0)));
    }
}
