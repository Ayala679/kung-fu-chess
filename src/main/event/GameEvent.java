package event;

/**
 * A command turned into an executable action against the EventEngine.
 */
public interface GameEvent {
    void execute(EventEngine eventEngine);
}
