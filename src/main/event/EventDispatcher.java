package event;

public class EventDispatcher {
    private final EventEngine eventEngine;

    public EventDispatcher(EventEngine eventEngine) {
        this.eventEngine = eventEngine;
    }

    /**
     * Dispatches a GameEvent to be executed against the EventEngine.
     */
    public void dispatch(GameEvent event) {
        if (event == null) {
            System.err.println("Unknown command");
            return;
        }

        try {
            event.execute(eventEngine);
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
