import java.util.Scanner;
import java.util.ArrayList;

/**
 * Parses board fixture from standard input.
 * Input format:
 *   Board:
 *   . wP bP .
 *   . . . .
 *   Commands:
 */
public class BoardParser {
    private static final String BOARD_MARKER = "Board:";
    private static final String COMMANDS_MARKER = "Commands:";
    private static final String WHITESPACE = "\\s+";

    public static String[][] readBoard(Scanner input) {
        ArrayList<String> rows = new ArrayList<>();
        int expectedWidth = -1;
        boolean parsingBoard = false;

        while (input.hasNextLine()) {
            String line = input.nextLine().trim();

            if (line.isEmpty()) {
                continue;
            }

            if (line.equals(BOARD_MARKER)) {
                parsingBoard = true;
                continue;
            }

            if (line.equals(COMMANDS_MARKER)) {
                break;
            }

            if (parsingBoard) {
                String[] tokens = line.split(WHITESPACE);

                if (expectedWidth == -1) {
                    expectedWidth = tokens.length;
                } else if (tokens.length != expectedWidth) {
                    System.out.println("ERROR ROW_WIDTH_MISMATCH");
                    return new String[0][0];
                }

                rows.add(line);
            }
        }

        return buildMatrix(rows, expectedWidth);
    }

    private static String[][] buildMatrix(ArrayList<String> rows, int width) {
        if (rows.isEmpty() || width <= 0) {
            return new String[0][0];
        }

        String[][] matrix = new String[rows.size()][width];
        for (int i = 0; i < rows.size(); i++) {
            matrix[i] = rows.get(i).split(WHITESPACE);
        }
        return matrix;
    }
}

