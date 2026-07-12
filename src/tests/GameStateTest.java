package tests;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import model.GameState;

class GameStateTest {
    @Test void testStartsAtTimeZeroAndNotOver() {
        GameState state = new GameState();
        assertEquals(0, state.getCurrentTime());
        assertFalse(state.isGameOver());
    }

    @Test void testAdvanceTimeAccumulates() {
        GameState state = new GameState();
        state.advanceTime(500);
        state.advanceTime(250);
        assertEquals(750, state.getCurrentTime());
    }

    @Test void testSetGameOverIsSticky() {
        GameState state = new GameState();
        state.setGameOver();
        assertTrue(state.isGameOver());
        state.advanceTime(100);
        assertTrue(state.isGameOver());
    }
}
