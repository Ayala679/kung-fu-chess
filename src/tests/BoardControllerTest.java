package tests;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.Scanner;
import controller.BoardController;

class BoardControllerTest {
    /** Mirrors Main.main()'s own driving loop, for end-to-end regression tests. */
    private static String runAsMainWould(String input) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        try {
            System.setOut(new PrintStream(out));
            System.setErr(new PrintStream(err));

            Scanner sc = new Scanner(input);
            BoardController controller = BoardController.readFrom(sc);
            while (sc.hasNextLine()) {
                String line = sc.nextLine().trim();
                if (line.isEmpty()) continue;
                controller.executeCommand(line);
            }
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
        return out.toString() + err.toString();
    }
    @Test void testReadFromParsesAValidBoard() {
        String input = "Board:\nwK bK\n. .\nCommands:\n";
        BoardController controller = BoardController.readFrom(new Scanner(input));

        assertFalse(controller.isEmpty());
        assertTrue(controller.isValid());
    }

    @Test void testReadFromWithNoBoardYieldsEmptyController() {
        BoardController controller = BoardController.readFrom(new Scanner(""));
        assertTrue(controller.isEmpty());
        assertFalse(controller.isValid());
    }

    @Test void testExecuteCommandRunsAFullClickToMoveSequence() {
        String input = "Board:\nwR . . .\n. . . .\n. . . .\n. . . .\nCommands:\n";
        BoardController controller = BoardController.readFrom(new Scanner(input));

        // cell size is 100px: (0,0) then (0,300) -> board (0,0) -> (0,3)
        assertDoesNotThrow(() -> {
            controller.executeCommand("click 0 0");
            controller.executeCommand("click 300 0");
            controller.executeCommand("wait 100000");
            controller.executeCommand("print board");
        });
    }

    @Test void testExecuteCommandIgnoresUnknownCommand() {
        BoardController controller = BoardController.readFrom(new Scanner("Board:\nwK\nCommands:\n"));
        assertDoesNotThrow(() -> controller.executeCommand("nonsense"));
    }

    @Test void testRowWidthMismatchProducesOnlyTheErrorLineWithNoCommandsFollowing() {
        // Regression: a width-mismatched board used to leave the "Commands:"
        // marker unread, so Main's loop would then treat "Commands:" itself as
        // a stray unknown command.
        String input = "Board:\nwK . .\n. bK\nCommands:\n";
        String output = runAsMainWould(input);

        assertEquals("ERROR ROW_WIDTH_MISMATCH", output.trim());
    }

    @Test void testRowWidthMismatchProducesOnlyTheErrorLineWithCommandsFollowing() {
        String input = "Board:\nwK . .\n. bK\nCommands:\nprint board\n";
        String output = runAsMainWould(input);

        assertEquals("ERROR ROW_WIDTH_MISMATCH", output.trim());
    }
}
