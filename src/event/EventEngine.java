package event;

import gameengine.GameEngine;
import model.Position;
import snapshot.GameSnapshot;

/**
 * EventEngine: the input-side front controller.
 *
 * It turns raw cell clicks into game intent (select / cancel / re-select / move
 * request) and forwards ready requests to the GameEngine. It owns the current
 * selection so the engine stays free of UI state - the actual click rules live
 * in {@link ClickSelector} (shared with server.GameSession's per-color version).
 */
public class EventEngine implements GameClient {
    private final GameEngine engine;
    private Position selection = null;

    public EventEngine(GameEngine engine) {
        this.engine = engine;
    }

    public void handleClick(int row, int col) {
        selection = ClickSelector.handleClick(engine, selection, row, col, null).selection();
    }

    public void handleJump(int row, int col) {
        engine.requestJump(row, col);
    }

    public void waitFor(long ms) {
        engine.advanceTime(ms);
    }

    public void print() {
        engine.printBoard();
    }

    /** Render-ready snapshot of the current board, including the pending selection. */
    public GameSnapshot snapshot() {
        return engine.buildSnapshot(selection);
    }
}
