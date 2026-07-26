package event;

public class ClickEvent {
    private final int x;
    private final int y;

    public ClickEvent(int x, int y) {
        this.x = x;
        this.y = y;
    }

    public int getX() { return x; }
    public int getY() { return y; }

    @Override
    public String toString() {
        return String.format("ClickEvent(x=%d, y=%d)", x, y);
    }
}

