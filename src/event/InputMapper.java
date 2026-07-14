package event;

import config.GameConfig;

public class InputMapper {
    public static CellClickEvent mapPixelToCell(ClickEvent pixelEvent) {
        int row = pixelEvent.getY() / GameConfig.CELL_PIXEL_SIZE;
        int col = pixelEvent.getX() / GameConfig.CELL_PIXEL_SIZE;
        return new CellClickEvent(row, col);
    }

    public static CellClickEvent mapPixelToCell(int x, int y) {
        return mapPixelToCell(new ClickEvent(x, y));
    }
}
