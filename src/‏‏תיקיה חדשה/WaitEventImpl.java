package event;

public class WaitEventImpl implements GameEvent {
    private final long ms;

    public WaitEventImpl(long ms) {
        this.ms = ms;
    }

    public long getMs() { return ms; }

    @Override
    public void execute(gameengine.GameLogic logic) {
        logic.handleWait(ms);
    }

    @Override
    public String toString() {
        return String.format("WaitEvent(ms=%d)", ms);
    }
}

