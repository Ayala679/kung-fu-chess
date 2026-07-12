package parsing;

import config.GameConfig;

/**
 * Validates board structure and tokens. This is where "is this received text a
 * valid token?" is answered - it is a parsing/format concern, not a movement rule.
 */
public class BoardValidator {
    public static boolean isValid(String[][] board) {
        if (board == null || board.length == 0) {
            return false;
        }

        for (String[] row : board) {
            for (String token : row) {
                if (!isValidToken(token)) {
                    System.out.println("ERROR UNKNOWN_TOKEN");
                    return false;
                }
            }
        }

        return true;
    }

    /** Is this a valid cell token - a piece like "wK" or the empty marker "."? */
    public static boolean isValidToken(String token) {
        return token != null && token.matches(GameConfig.FULL_TOKEN_OR_EMPTY);
    }
}
