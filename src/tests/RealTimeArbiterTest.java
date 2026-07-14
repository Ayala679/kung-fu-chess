package tests;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import model.Board;
import model.GameState;
import model.Piece;
import model.Position;
import gameengine.RealTimeArbiter;

class RealTimeArbiterTest {
    @Test void testHeadOnCollisionFirstRegisteredMoveWins() {
        // Two enemy slides swapping squares (same distance/duration, requested
        // at the same virtual instant) can't just pass through each other -
        // whichever was registered first survives; the other never arrives.
        Piece[][] grid = new Piece[8][8];
        Piece whiteRook = Piece.of(Piece.Color.WHITE, Piece.Type.R);
        Piece blackRook = Piece.of(Piece.Color.BLACK, Piece.Type.R);
        grid[0][0] = whiteRook;
        grid[0][3] = blackRook;
        Board board = new Board(grid);
        GameState state = new GameState();
        RealTimeArbiter arbiter = new RealTimeArbiter(board, state);

        arbiter.startMove(whiteRook, new Position(0, 0), new Position(0, 3), 3000); // registered first
        arbiter.startMove(blackRook, new Position(0, 3), new Position(0, 0), 3000); // registered second

        state.advanceTime(3000);
        arbiter.update();

        assertEquals(whiteRook, board.getCell(0, 3));
        assertNull(board.getCell(0, 0));
        assertTrue(arbiter.getActiveMoves().isEmpty());
    }

    @Test void testHeadOnCollisionOrderDeterminesTheWinner() {
        // Same setup, registration order reversed - the winner flips too.
        Piece[][] grid = new Piece[8][8];
        Piece whiteRook = Piece.of(Piece.Color.WHITE, Piece.Type.R);
        Piece blackRook = Piece.of(Piece.Color.BLACK, Piece.Type.R);
        grid[0][0] = whiteRook;
        grid[0][3] = blackRook;
        Board board = new Board(grid);
        GameState state = new GameState();
        RealTimeArbiter arbiter = new RealTimeArbiter(board, state);

        arbiter.startMove(blackRook, new Position(0, 3), new Position(0, 0), 3000); // registered first
        arbiter.startMove(whiteRook, new Position(0, 0), new Position(0, 3), 3000); // registered second

        state.advanceTime(3000);
        arbiter.update();

        assertEquals(blackRook, board.getCell(0, 0));
        assertNull(board.getCell(0, 3));
    }

    @Test void testHeadOnCollisionLosingKingEndsTheGame() {
        Piece[][] grid = new Piece[8][8];
        Piece whiteRook = Piece.of(Piece.Color.WHITE, Piece.Type.R);
        Piece blackKing = Piece.of(Piece.Color.BLACK, Piece.Type.K);
        grid[0][0] = whiteRook;
        grid[0][3] = blackKing;
        Board board = new Board(grid);
        GameState state = new GameState();
        RealTimeArbiter arbiter = new RealTimeArbiter(board, state);

        arbiter.startMove(whiteRook, new Position(0, 0), new Position(0, 3), 3000); // registered first, wins
        arbiter.startMove(blackKing, new Position(0, 3), new Position(0, 0), 3000); // registered second, loses

        state.advanceTime(3000);
        arbiter.update();

        assertEquals(whiteRook, board.getCell(0, 3));
        assertTrue(state.isGameOver());
    }

    @Test void testHeadOnCollisionDoesNotAffectAnUnrelatedBystanderMove() {
        Piece[][] grid = new Piece[8][8];
        Piece whiteRook = Piece.of(Piece.Color.WHITE, Piece.Type.R);
        Piece blackRook = Piece.of(Piece.Color.BLACK, Piece.Type.R);
        Piece bystander = Piece.of(Piece.Color.WHITE, Piece.Type.B);
        grid[0][0] = whiteRook;
        grid[0][3] = blackRook;
        grid[7][7] = bystander;
        Board board = new Board(grid);
        GameState state = new GameState();
        RealTimeArbiter arbiter = new RealTimeArbiter(board, state);

        arbiter.startMove(whiteRook, new Position(0, 0), new Position(0, 3), 3000);
        arbiter.startMove(blackRook, new Position(0, 3), new Position(0, 0), 3000);
        arbiter.startMove(bystander, new Position(7, 7), new Position(4, 4), 3000); // unrelated diagonal move

        state.advanceTime(3000);
        arbiter.update();

        assertEquals(whiteRook, board.getCell(0, 3));
        assertEquals(bystander, board.getCell(4, 4));
        assertNull(board.getCell(7, 7));
    }

    @Test void testMidAirCaptureTieGoesToTheDefender() {
        // A jump and an incoming enemy slide with the EXACT same arrival time:
        // the defender (already braced) wins ties, not the attacker.
        Piece[][] grid = new Piece[8][8];
        Piece king = Piece.of(Piece.Color.WHITE, Piece.Type.K);
        Piece rook = Piece.of(Piece.Color.BLACK, Piece.Type.R);
        grid[1][0] = king;
        grid[1][1] = rook;
        Board board = new Board(grid);
        GameState state = new GameState();
        RealTimeArbiter arbiter = new RealTimeArbiter(board, state);

        arbiter.startJump(king, new Position(1, 0), 1000);
        arbiter.startMove(rook, new Position(1, 1), new Position(1, 0), 1000); // same arrival time

        state.advanceTime(1000);
        arbiter.update();

        assertEquals(king, board.getCell(1, 0));
        assertNull(board.getCell(1, 1));
    }

    @Test void testCaptureRemovesThePieceFromTheBoard() {
        Piece[][] grid = new Piece[8][8];
        Piece knight = Piece.of(Piece.Color.BLACK, Piece.Type.N);
        grid[3][3] = knight;
        Board board = new Board(grid);
        RealTimeArbiter arbiter = new RealTimeArbiter(board, new GameState());

        arbiter.capture(3, 3, knight);

        assertNull(board.getCell(3, 3));
    }

    @Test void testCaptureOfKingEndsTheGame() {
        Piece[][] grid = new Piece[8][8];
        Piece king = Piece.of(Piece.Color.WHITE, Piece.Type.K);
        grid[0][0] = king;
        Board board = new Board(grid);
        GameState state = new GameState();
        RealTimeArbiter arbiter = new RealTimeArbiter(board, state);

        arbiter.capture(0, 0, king);

        assertTrue(state.isGameOver());
    }

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

    @Test void testLaterArrivalWinsSharedSquareEvenWhenBatchedInOneUpdate() {
        // Regression: two moves targeting the same square used to be resolved in
        // list-insertion order rather than by arrivalTime when both arrivals fell
        // in the same update() call (e.g. after one large time jump) - so the
        // piece that was requested first could wrongly "win" even though the
        // other one truly arrived later.
        Piece[][] grid = new Piece[8][8];
        Piece whiteRook = Piece.of(Piece.Color.WHITE, Piece.Type.R);
        Piece blackRook = Piece.of(Piece.Color.BLACK, Piece.Type.R);
        grid[4][0] = whiteRook;
        grid[0][4] = blackRook;
        Board board = new Board(grid);
        GameState state = new GameState();
        RealTimeArbiter arbiter = new RealTimeArbiter(board, state);

        // white requested first (arrives at t=4000)
        arbiter.startMove(whiteRook, new Position(4, 0), new Position(4, 4), 4000);
        state.advanceTime(10);
        // black requested second, at t=10 (arrives at t=4010 - genuinely later)
        arbiter.startMove(blackRook, new Position(0, 4), new Position(4, 4), 4000);

        // one big jump resolves both arrivals in the same update() call
        state.advanceTime(100000);
        arbiter.update();

        assertEquals(blackRook, board.getCell(4, 4));
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
