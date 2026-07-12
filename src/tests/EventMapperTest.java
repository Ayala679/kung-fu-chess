package tests;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import event.CellClickEvent;
import event.ClickEventImpl;
import event.EventMapper;
import event.GameEvent;
import event.InputMapper;
import event.JumpEventImpl;
import event.PrintBoardEventImpl;
import event.WaitEventImpl;

class EventMapperTest {
    @Test void testMapsClickCommand() {
        GameEvent event = EventMapper.mapCommand("click 150 250");
        assertInstanceOf(ClickEventImpl.class, event);
        assertEquals(150, ((ClickEventImpl) event).getX());
        assertEquals(250, ((ClickEventImpl) event).getY());
    }

    @Test void testMapsJumpCommand() {
        GameEvent event = EventMapper.mapCommand("jump 50 50");
        assertInstanceOf(JumpEventImpl.class, event);
    }

    @Test void testMapsWaitCommand() {
        GameEvent event = EventMapper.mapCommand("wait 1500");
        assertInstanceOf(WaitEventImpl.class, event);
        assertEquals(1500, ((WaitEventImpl) event).getMs());
    }

    @Test void testMapsPrintBoardCommand() {
        GameEvent event = EventMapper.mapCommand("print board");
        assertInstanceOf(PrintBoardEventImpl.class, event);
    }

    @Test void testUnknownCommandReturnsNull() {
        assertNull(EventMapper.mapCommand("fly away"));
    }

    @Test void testPrintWithoutBoardReturnsNull() {
        assertNull(EventMapper.mapCommand("print something"));
    }

    @Test void testTruncatedCommandsReturnNull() {
        assertNull(EventMapper.mapCommand("click 1"));
        assertNull(EventMapper.mapCommand("jump 1"));
        assertNull(EventMapper.mapCommand("wait"));
        assertNull(EventMapper.mapCommand("print"));
    }

    @Test void testInputMapperConvertsPixelsToCells() {
        CellClickEvent cell = InputMapper.mapPixelToCell(new event.ClickEvent(250, 350));
        assertEquals(3, cell.getRow());
        assertEquals(2, cell.getCol());
    }
}
