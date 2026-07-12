package tests;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.Scanner;
import parsing.BoardParser;

class BoardParserTest {
    @Test void testReadsBoardUntilCommandsMarker() {
        String input = "Board:\nwK bK\n. .\nCommands:\nprint board\n";
        String[][] board = BoardParser.readBoard(new Scanner(input));

        assertEquals(2, board.length);
        assertArrayEquals(new String[]{"wK", "bK"}, board[0]);
        assertArrayEquals(new String[]{".", "."}, board[1]);
    }

    @Test void testSkipsBlankLines() {
        String input = "Board:\n\nwK bK\n\nCommands:\n";
        String[][] board = BoardParser.readBoard(new Scanner(input));

        assertEquals(1, board.length);
        assertArrayEquals(new String[]{"wK", "bK"}, board[0]);
    }

    @Test void testReadsToEndOfInputWhenNoCommandsMarker() {
        String input = "Board:\nwK bK\n. .\n";
        String[][] board = BoardParser.readBoard(new Scanner(input));

        assertEquals(2, board.length);
    }

    @Test void testEmptyInputReturnsEmptyMatrix() {
        String[][] board = BoardParser.readBoard(new Scanner(""));
        assertEquals(0, board.length);
    }

    @Test void testMismatchedRowWidthReturnsEmptyMatrix() {
        String input = "Board:\nwK bK\n. . .\nCommands:\n";
        String[][] board = BoardParser.readBoard(new Scanner(input));
        assertEquals(0, board.length);
    }

    @Test void testMismatchedRowWidthStillConsumesThroughCommandsMarker() {
        // Regression: readBoard used to return the moment it spotted a width
        // mismatch, leaving "Commands:" itself unread in the Scanner. Whoever
        // reads the remaining lines next (Main's command loop) would then see
        // "Commands:" as if it were the first command line.
        String input = "Board:\nwK bK\n. . .\nCommands:\nprint board\n";
        Scanner scanner = new Scanner(input);
        BoardParser.readBoard(scanner);

        assertTrue(scanner.hasNextLine());
        assertEquals("print board", scanner.nextLine());
    }

    @Test void testMismatchedRowWidthWithNoCommandsLeftDoesNotLeaveMarkerBehind() {
        String input = "Board:\nwK bK\n. . .\nCommands:\n";
        Scanner scanner = new Scanner(input);
        BoardParser.readBoard(scanner);

        assertFalse(scanner.hasNextLine());
    }

    @Test void testLinesBeforeBoardMarkerAreIgnored() {
        String input = "some noise\nBoard:\nwK bK\nCommands:\n";
        String[][] board = BoardParser.readBoard(new Scanner(input));
        assertEquals(1, board.length);
        assertArrayEquals(new String[]{"wK", "bK"}, board[0]);
    }
}
