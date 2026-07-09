package parsing;

/**
 * Validates board structure and tokens.
 */
public class BoardValidator {
    private static final String TOKEN_PATTERN = "\\.|[wb][KQRBNP]";

    public static boolean isValid(String[][] board) {
        if (board == null || board.length == 0) {
            return false;
        }

        for (String[] row : board) {
            for (String token : row) {
                if (!token.matches(TOKEN_PATTERN)) {
                    System.out.println("ERROR UNKNOWN_TOKEN");
                    return false;
                }
            }
        }

        return true;
    }

    public static void printBoard(String[][] board) {
        if (board == null || board.length == 0) {
            return;
        }

        for (int row = 0; row < board.length; row++) {
            for (int col = 0; col < board[row].length; col++) {
                System.out.print(board[row][col]);
                if (col < board[row].length - 1) {
                    System.out.print(" ");
                }
            }
            System.out.println();
        }
    }
}