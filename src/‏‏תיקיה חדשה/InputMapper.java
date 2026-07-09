package event;

public class InputMapper {
    private static final int CELL_SIZE = 100; // pixels per cell

    public static CellClickEvent mapPixelToCell(ClickEvent pixelEvent) {
        int row = pixelEvent.getY() / CELL_SIZE;
        int col = pixelEvent.getX() / CELL_SIZE;
        return new CellClickEvent(row, col);
    }
}

