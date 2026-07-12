package event;

public class PrintBoardEventImpl implements GameEvent {
    @Override
    public void execute(EventEngine eventEngine) {
        eventEngine.print();
    }

    @Override
    public String toString() {
        return "PrintBoardEvent";
    }
}

