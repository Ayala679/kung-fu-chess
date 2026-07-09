package event;

public class CellClickEvent {
    private final int row;
    private final int col;

    public CellClickEvent(int row, int col) {
        this.row = row;
        this.col = col;
    }

    public int getRow() { return row; }
    public int getCol() { return col; }

    @Override
    public String toString() {
        return String.format("CellClickEvent(row=%d, col=%d)", row, col);
    }
}

