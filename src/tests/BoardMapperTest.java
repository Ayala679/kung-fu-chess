package tests;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.Scanner;
import model.Board;
import model.Piece;
import parsing.BoardMapper;

class BoardMapperTest {
    @Test void testReadsValidBoardIntoPieces() {
        String input = "Board:\nwK bK\n. .\nCommands:\n";
        Board board = BoardMapper.readBoard(new Scanner(input));

        assertFalse(board.isEmpty());
        assertEquals(Piece.Color.WHITE, board.getCell(0, 0).getColor());
        assertEquals(Piece.Type.K, board.getCell(0, 0).getType());
        assertEquals(Piece.Color.BLACK, board.getCell(0, 1).getColor());
        assertNull(board.getCell(1, 0));
    }

    @Test void testInvalidTokenYieldsEmptyBoard() {
        String input = "Board:\nwK ??\nCommands:\n";
        Board board = BoardMapper.readBoard(new Scanner(input));
        assertTrue(board.isEmpty());
    }

    @Test void testMismatchedWidthYieldsEmptyBoard() {
        String input = "Board:\nwK bK\n. . .\nCommands:\n";
        Board board = BoardMapper.readBoard(new Scanner(input));
        assertTrue(board.isEmpty());
    }

    @Test void testEmptyInputYieldsEmptyBoard() {
        Board board = BoardMapper.readBoard(new Scanner(""));
        assertTrue(board.isEmpty());
    }
}
