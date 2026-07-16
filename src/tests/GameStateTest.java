package tests;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import model.GameState;
import model.Piece;

class GameStateTest {
    @Test void testStartsAtTimeZeroAndNotOver() {
        GameState state = new GameState();
        assertEquals(0, state.getCurrentTime());
        assertFalse(state.isGameOver());
        assertNull(state.getWinner());
    }

    @Test void testAdvanceTimeAccumulates() {
        GameState state = new GameState();
        state.advanceTime(500);
        state.advanceTime(250);
        assertEquals(750, state.getCurrentTime());
    }

    @Test void testSetGameOverRecordsTheWinnerAndIsSticky() {
        GameState state = new GameState();
        state.setGameOver(Piece.Color.WHITE);
        assertTrue(state.isGameOver());
        assertEquals(Piece.Color.WHITE, state.getWinner());
        state.advanceTime(100);
        assertTrue(state.isGameOver());
        assertEquals(Piece.Color.WHITE, state.getWinner());
    }
}
