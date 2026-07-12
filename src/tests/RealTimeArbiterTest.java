package tests;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import model.Board;
import model.GameState;
import model.Piece;
import model.Position;
import gameengine.RealTimeArbiter;

class RealTimeArbiterTest {
    @Test void testStartMoveMarksBothSquaresBusyUntilArrival() {
        Piece[][] grid = new Piece[8][8];
        Piece rook = Piece.of(Piece.Color.WHITE, Piece.Type.R);
        grid[0][0] = rook;
        Board board = new Board(grid);
        GameState state = new GameState();
        RealTimeArbiter arbiter = new RealTimeArbiter(board, state);

        arbiter.startMove(rook, new Position(0, 0), new Position(0, 3), 1000);

        assertTrue(arbiter.isBusyAt(0, 0));
        assertTrue(arbiter.isBusyAt(0, 3));
        assertFalse(arbiter.isBusyAt(5, 5));
    }

    @Test void testMoveArrivesAndUpdatesBoardAfterDuration() {
        Piece[][] grid = new Piece[8][8];
        Piece rook = Piece.of(Piece.Color.WHITE, Piece.Type.R);
        grid[0][0] = rook;
        Board board = new Board(grid);
        GameState state = new GameState();
        RealTimeArbiter arbiter = new RealTimeArbiter(board, state);

        arbiter.startMove(rook, new Position(0, 0), new Position(0, 3), 1000);
        arbiter.advance(1000);

        assertNull(board.getCell(0, 0));
        assertEquals(rook, board.getCell(0, 3));
        assertFalse(arbiter.isBusyAt(0, 3));
        assertTrue(arbiter.getActiveMoves().isEmpty());
    }

    @Test void testMoveDoesNotArriveBeforeDurationElapses() {
        Piece[][] grid = new Piece[8][8];
        Piece rook = Piece.of(Piece.Color.WHITE, Piece.Type.R);
        grid[0][0] = rook;
        Board board = new Board(grid);
        GameState state = new GameState();
        RealTimeArbiter arbiter = new RealTimeArbiter(board, state);

        arbiter.startMove(rook, new Position(0, 0), new Position(0, 3), 1000);
        arbiter.advance(500);

        assertEquals(rook, board.getCell(0, 0));
        assertNull(board.getCell(0, 3));
        assertFalse(arbiter.getActiveMoves().isEmpty());
    }

    @Test void testPawnPromotesOnArrival() {
        Piece[][] grid = new Piece[8][8];
        Piece pawn = Piece.of(Piece.Color.WHITE, Piece.Type.P);
        grid[1][0] = pawn;
        Board board = new Board(grid);
        GameState state = new GameState();
        RealTimeArbiter arbiter = new RealTimeArbiter(board, state);

        arbiter.startMove(pawn, new Position(1, 0), new Position(0, 0), 1000);
        arbiter.advance(1000);

        assertEquals(Piece.Type.Q, board.getCell(0, 0).getType());
    }

    @Test void testArrivingOnEnemyKingEndsGame() {
        Piece[][] grid = new Piece[8][8];
        Piece rook = Piece.of(Piece.Color.WHITE, Piece.Type.R);
        Piece king = Piece.of(Piece.Color.BLACK, Piece.Type.K);
        grid[0][0] = rook;
        grid[0][3] = king;
        Board board = new Board(grid);
        GameState state = new GameState();
        RealTimeArbiter arbiter = new RealTimeArbiter(board, state);

        arbiter.startMove(rook, new Position(0, 0), new Position(0, 3), 1000);
        arbiter.advance(1000);

        assertTrue(state.isGameOver());
    }

    @Test void testJumpInPlaceDefendsSquareAgainstIncomingSlide() {
        Piece[][] grid = new Piece[8][8];
        Piece rook = Piece.of(Piece.Color.WHITE, Piece.Type.R);
        Piece knight = Piece.of(Piece.Color.BLACK, Piece.Type.N);
        grid[0][0] = rook;
        grid[0][3] = knight;
        Board board = new Board(grid);
        GameState state = new GameState();
        RealTimeArbiter arbiter = new RealTimeArbiter(board, state);

        // the knight is already jumping in place on the target square when the
        // rook's slide toward that same square starts
        arbiter.startJump(knight, new Position(0, 3), 500);
        arbiter.startMove(rook, new Position(0, 0), new Position(0, 3), 1000);
        arbiter.update();

        assertNull(board.getCell(0, 0));
        assertTrue(arbiter.getActiveMoves().stream().noneMatch(mp -> mp.getPiece() == rook));
    }

    @Test void testMidAirCaptureOfKingEndsGame() {
        Piece[][] grid = new Piece[8][8];
        Piece king = Piece.of(Piece.Color.WHITE, Piece.Type.K);
        Piece knight = Piece.of(Piece.Color.BLACK, Piece.Type.N);
        grid[0][0] = king;
        grid[0][3] = knight;
        Board board = new Board(grid);
        GameState state = new GameState();
        RealTimeArbiter arbiter = new RealTimeArbiter(board, state);

        arbiter.startJump(knight, new Position(0, 3), 500);
        arbiter.startMove(king, new Position(0, 0), new Position(0, 3), 1000);
        arbiter.update();

        assertTrue(state.isGameOver());
    }

    @Test void testIsTooLateToJumpWhenEnemySlideAlreadyUnderway() {
        Piece[][] grid = new Piece[8][8];
        Piece rook = Piece.of(Piece.Color.WHITE, Piece.Type.R);
        Piece knight = Piece.of(Piece.Color.BLACK, Piece.Type.N);
        grid[0][0] = rook;
        grid[0][3] = knight;
        Board board = new Board(grid);
        GameState state = new GameState();
        RealTimeArbiter arbiter = new RealTimeArbiter(board, state);

        arbiter.startMove(rook, new Position(0, 0), new Position(0, 3), 1000);

        assertTrue(arbiter.isTooLateToJump(0, 3, knight));
    }

    @Test void testIsTooLateToJumpFalseWhenIncomingPieceIsFriendly() {
        Piece[][] grid = new Piece[8][8];
        Piece rookA = Piece.of(Piece.Color.WHITE, Piece.Type.R);
        Piece rookB = Piece.of(Piece.Color.WHITE, Piece.Type.R);
        grid[0][0] = rookA;
        grid[0][3] = rookB;
        Board board = new Board(grid);
        GameState state = new GameState();
        RealTimeArbiter arbiter = new RealTimeArbiter(board, state);

        arbiter.startMove(rookA, new Position(0, 0), new Position(0, 3), 1000);

        // same color as the incoming slide - not "too late", just a friendly square
        assertFalse(arbiter.isTooLateToJump(0, 3, rookB));
    }

    @Test void testIsTooLateToJumpFalseWhenNoIncomingEnemy() {
        Board board = new Board(new Piece[8][8]);
        GameState state = new GameState();
        RealTimeArbiter arbiter = new RealTimeArbiter(board, state);
        Piece knight = Piece.of(Piece.Color.BLACK, Piece.Type.N);

        assertFalse(arbiter.isTooLateToJump(0, 3, knight));
    }
}
