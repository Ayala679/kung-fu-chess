package event;

public class JumpEventImpl implements GameEvent {
    private final int x;
    private final int y;

    public JumpEventImpl(int x, int y) {
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
        logic.handleJumpCell(cellEvent.getRow(), cellEvent.getCol());
    }

    @Override
    public String toString() {
        return String.format("JumpEvent(x=%d, y=%d)", x, y);
    }
}

