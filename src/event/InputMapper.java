package event;

import config.GameConfig;

public class InputMapper {
    public static CellClickEvent mapPixelToCell(ClickEvent pixelEvent) {
        int row = pixelEvent.getY() / GameConfig.CELL_PIXEL_SIZE;
        int col = pixelEvent.getX() / GameConfig.CELL_PIXEL_SIZE;
        return new CellClickEvent(row, col);
    }
}
