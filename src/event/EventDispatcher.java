package event;

import gameengine.GameLogic;

public class EventDispatcher {
    private GameLogic logic;

    public EventDispatcher(GameLogic logic) {
        this.logic = logic;
    }

    /**
     * Dispatches a GameEvent to be executed against the game logic.
     */
    public void dispatch(GameEvent event) {
        if (event == null) {
            System.err.println("Unknown command");
            return;
        }

        try {
            event.execute(logic);
        } catch (Exception e) {
            System.err.println("Error executing event: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Parse a command line and dispatch as event.
     */
    public void dispatchFromCommand(String line) {
        GameEvent event = EventMapper.mapCommand(line);
        dispatch(event);
    }
}

