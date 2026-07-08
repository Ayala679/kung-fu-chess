import java.util.HashMap;
import java.util.Map;

public class PieceMovementRegistry {
    private static final Map<Character, PieceMovement> registry = new HashMap<>();

    static {
        // Register default pieces
        registry.put('K', (board, fr, fc, tr, tc, piece) -> {
            int rd = Math.abs(tr - fr);
            int cd = Math.abs(tc - fc);
            return rd <= 1 && cd <= 1;
        });

        registry.put('R', (board, fr, fc, tr, tc, piece) -> {
            int rd = Math.abs(tr - fr);
            int cd = Math.abs(tc - fc);
            return (rd == 0 || cd == 0) && new MoveValidator(board).isPathClear(fr, fc, tr, tc);
        });

        registry.put('B', (board, fr, fc, tr, tc, piece) -> {
            int rd = Math.abs(tr - fr);
            int cd = Math.abs(tc - fc);
            return (rd == cd) && new MoveValidator(board).isPathClear(fr, fc, tr, tc);
        });

        registry.put('Q', (board, fr, fc, tr, tc, piece) -> {
            int rd = Math.abs(tr - fr);
            int cd = Math.abs(tc - fc);
            return (rd == 0 || cd == 0 || rd == cd) && new MoveValidator(board).isPathClear(fr, fc, tr, tc);
        });

        registry.put('N', (board, fr, fc, tr, tc, piece) -> {
            int rd = Math.abs(tr - fr);
            int cd = Math.abs(tc - fc);
            return (rd == 1 && cd == 2) || (rd == 2 && cd == 1);
        });

        // Pawn default logic
        registry.put('P', (board, fr, fc, tr, tc, piece) -> {
            char color = piece.charAt(0);
            int direction = (color == 'w') ? -1 : 1;
            int rowDiff = tr - fr;
            int colDistance = Math.abs(tc - fc);
            String destination = board[tr][tc];

            // single step
            if (rowDiff == direction && colDistance == 0 && destination.equals(Config.EMPTY)) return true;

            // two steps from starting row (simple heuristic similar to previous)
            int startRow = (color == 'w') ? (board.length - 2) : 1;
            if (rowDiff == 2 * direction && colDistance == 0 && fr == startRow && destination.equals(Config.EMPTY) && new MoveValidator(board).isPathClear(fr, fc, tr, tc)) return true;

            // capture diagonal
            if (rowDiff == direction && colDistance == 1 && !destination.equals(Config.EMPTY) && destination.charAt(0) != color) return true;

            return false;
        });
    }

    public static void register(char type, PieceMovement movement) {
        registry.put(type, movement);
    }

    public static boolean isValid(char type, String[][] board, int fr, int fc, int tr, int tc, String piece) {
        PieceMovement pm = registry.get(type);
        if (pm == null) return false;
        return pm.isValid(board, fr, fc, tr, tc, piece);
    }
}

