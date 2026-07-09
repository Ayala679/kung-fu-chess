package event;

public class ClickEventImpl implements GameEvent {
    private final int x;
    private final int y;

    public ClickEventImpl(int x, int y) {
        this.x = x;
        this.y = y;
    }

    public int getX() { return x; }
    public int getY() { return y; }

    @Override
    public void execute(gameengine.GameLogic logic) {
        CellClickEvent cellEvent = InputMapper.mapPixelToCell(
            new ClickEvent(x, y)
        );
        logic.handleClickCell(cellEvent.getRow(), cellEvent.getCol());
    }

    @Override
    public String toString() {
        return String.format("ClickEvent(x=%d, y=%d)", x, y);
    }
}

