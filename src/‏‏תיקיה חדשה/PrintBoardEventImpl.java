package event;

public class PrintBoardEventImpl implements GameEvent {
    @Override
    public void execute(gameengine.GameLogic logic) {
        logic.printBoard();
    }

    @Override
    public String toString() {
        return "PrintBoardEvent";
    }
}

