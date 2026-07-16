package tests;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import model.Position;
import view.BoardGeometry;

class BoardGeometryTest {

    @Test void testCellSizeMatchesActualBoardImageDimensions() {
        // board.png is 822x828 - not evenly divisible by 8, so cell size is fractional
        assertEquals(102.75, BoardGeometry.cellWidth(822), 1e-9);
        assertEquals(103.5, BoardGeometry.cellHeight(828), 1e-9);
    }

    @Test void testCellSizeAdaptsToTheCurrentlyRenderedPixelSize() {
        // same board, resized/displayed at a different pixel size -> cell size scales with it
        assertEquals(50.0, BoardGeometry.cellWidth(400));
        assertEquals(50.0, BoardGeometry.cellHeight(400));
    }

    @Test void testCellOriginIsTopLeftPixelOfEachCell() {
        assertEquals(0, BoardGeometry.cellX(0, 800));
        assertEquals(0, BoardGeometry.cellY(0, 800));
        assertEquals(400, BoardGeometry.cellX(4, 800));
        assertEquals(400, BoardGeometry.cellY(4, 800));
        assertEquals(700, BoardGeometry.cellX(7, 800));
        assertEquals(700, BoardGeometry.cellY(7, 800));
    }

    @Test void testCellAtMapsAPixelClickToItsCell() {
        Position p = BoardGeometry.cellAt(250, 350, 800, 800);
        assertEquals(3, p.getRow());
        assertEquals(2, p.getCol());
    }

    @Test void testCellAtClampsClicksOutsideTheBoardToTheNearestEdgeCell() {
        Position bottomRightCorner = BoardGeometry.cellAt(799, 799, 800, 800);
        assertEquals(7, bottomRightCorner.getRow());
        assertEquals(7, bottomRightCorner.getCol());

        // exactly on/past the far edge still clamps into bounds, not out-of-range
        Position pastEdge = BoardGeometry.cellAt(800, 800, 800, 800);
        assertEquals(7, pastEdge.getRow());
        assertEquals(7, pastEdge.getCol());

        Position negative = BoardGeometry.cellAt(-5, -5, 800, 800);
        assertEquals(0, negative.getRow());
        assertEquals(0, negative.getCol());
    }
}
