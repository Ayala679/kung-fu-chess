package tests;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import event.ClickSelector;
import gameengine.GameEngine;
import model.Board;
import model.GameState;
import model.Piece;
import model.Position;

class ClickSelectorTest {
    private static GameEngine engineWith(Piece[][] grid) {
        return new GameEngine(new Board(grid), new GameState());
    }

    @Test void testFirstClickOnAPieceSelectsIt() {
        Piece[][] grid = new Piece[8][8];
        grid[4][4] = Piece.of(Piece.Color.WHITE, Piece.Type.R);
        GameEngine engine = engineWith(grid);

        ClickSelector.Result result = ClickSelector.handleClick(engine, null, 4, 4, null);

        assertEquals(new Position(4, 4), result.selection());
        assertEquals(ClickSelector.Outcome.NO_MOVE_ATTEMPTED, result.outcome());
    }

    @Test void testFirstClickOnAnEmptyCellSelectsNothing() {
        GameEngine engine = engineWith(new Piece[8][8]);

        ClickSelector.Result result = ClickSelector.handleClick(engine, null, 4, 4, null);

        assertNull(result.selection());
    }

    @Test void testSecondClickOnTheSameSelectedPieceCancelsTheSelection() {
        Piece[][] grid = new Piece[8][8];
        grid[4][4] = Piece.of(Piece.Color.WHITE, Piece.Type.R);
        GameEngine engine = engineWith(grid);

        ClickSelector.Result result = ClickSelector.handleClick(engine, new Position(4, 4), 4, 4, null);

        assertNull(result.selection());
    }

    @Test void testSecondClickOnAnotherOwnPieceReselects() {
        Piece[][] grid = new Piece[8][8];
        grid[4][4] = Piece.of(Piece.Color.WHITE, Piece.Type.R);
        grid[4][0] = Piece.of(Piece.Color.WHITE, Piece.Type.R);
        GameEngine engine = engineWith(grid);

        ClickSelector.Result result = ClickSelector.handleClick(engine, new Position(4, 4), 4, 0, null);

        assertEquals(new Position(4, 0), result.selection());
        assertEquals(ClickSelector.Outcome.NO_MOVE_ATTEMPTED, result.outcome());
    }

    @Test void testSecondClickElsewhereRequestsAMoveAndClearsTheSelection() {
        Piece[][] grid = new Piece[8][8];
        Piece rook = Piece.of(Piece.Color.WHITE, Piece.Type.R);
        grid[4][4] = rook;
        GameEngine engine = engineWith(grid);

        ClickSelector.Result result = ClickSelector.handleClick(engine, new Position(4, 4), 4, 0, null);
        engine.advanceTime(100000);

        assertNull(result.selection());
        assertEquals(ClickSelector.Outcome.MOVE_ACCEPTED, result.outcome());
        assertEquals(rook, engine.pieceAt(4, 0));
    }

    @Test void testOutOfBoundsClickCancelsAnyPendingSelection() {
        GameEngine engine = engineWith(new Piece[8][8]);

        ClickSelector.Result result = ClickSelector.handleClick(engine, new Position(4, 4), -1, -1, null);

        assertNull(result.selection());
    }

    @Test void testGameOverLeavesTheSelectionUntouched() {
        Piece[][] grid = new Piece[8][8];
        Piece rook = Piece.of(Piece.Color.WHITE, Piece.Type.R);
        Piece enemyKing = Piece.of(Piece.Color.BLACK, Piece.Type.K);
        grid[4][4] = rook;
        grid[4][0] = enemyKing;
        GameEngine engine = engineWith(grid);
        engine.requestMove(new Position(4, 4), new Position(4, 0));
        engine.advanceTime(100000);
        assertTrue(engine.isGameOver());

        ClickSelector.Result result = ClickSelector.handleClick(engine, new Position(1, 1), 2, 2, null);

        assertEquals(new Position(1, 1), result.selection()); // unchanged - clicks are ignored once the game is over
        assertEquals(ClickSelector.Outcome.NO_MOVE_ATTEMPTED, result.outcome());
    }

    @Test void testRequiredColorBlocksSelectingAnOpponentPiece() {
        Piece[][] grid = new Piece[8][8];
        grid[4][4] = Piece.of(Piece.Color.BLACK, Piece.Type.R);
        GameEngine engine = engineWith(grid);

        ClickSelector.Result result = ClickSelector.handleClick(engine, null, 4, 4, Piece.Color.WHITE);

        assertNull(result.selection());
    }

    @Test void testRequiredColorAllowsSelectingYourOwnPiece() {
        Piece[][] grid = new Piece[8][8];
        grid[4][4] = Piece.of(Piece.Color.WHITE, Piece.Type.R);
        GameEngine engine = engineWith(grid);

        ClickSelector.Result result = ClickSelector.handleClick(engine, null, 4, 4, Piece.Color.WHITE);

        assertEquals(new Position(4, 4), result.selection());
    }

    @Test void testRequiredColorDoesNotBlockCapturingAnOpponentPieceOnTheSecondClick() {
        // The color restriction only gates WHICH piece you may ever select -
        // moving your own selected piece onto an enemy square is still a
        // normal capture, exactly as event.EventEngine allows.
        Piece[][] grid = new Piece[8][8];
        Piece whiteRook = Piece.of(Piece.Color.WHITE, Piece.Type.R);
        Piece blackPawn = Piece.of(Piece.Color.BLACK, Piece.Type.P);
        grid[4][4] = whiteRook;
        grid[4][0] = blackPawn;
        GameEngine engine = engineWith(grid);

        ClickSelector.Result result = ClickSelector.handleClick(engine, new Position(4, 4), 4, 0, Piece.Color.WHITE);
        engine.advanceTime(100000);

        assertNull(result.selection());
        assertEquals(ClickSelector.Outcome.MOVE_ACCEPTED, result.outcome());
        assertEquals(whiteRook, engine.pieceAt(4, 0));
    }

    @Test void testAnIllegalMoveAttemptIsReportedAsRejected() {
        Piece[][] grid = new Piece[8][8];
        grid[4][4] = Piece.of(Piece.Color.WHITE, Piece.Type.R);
        GameEngine engine = engineWith(grid);

        // A rook cannot move diagonally - the click still "requests" a move
        // (it's not a reselect, since (5,5) is empty), but the engine rejects it.
        ClickSelector.Result result = ClickSelector.handleClick(engine, new Position(4, 4), 5, 5, null);

        assertNull(result.selection());
        assertEquals(ClickSelector.Outcome.MOVE_REJECTED, result.outcome());
    }
}
