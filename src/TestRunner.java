import java.util.Scanner;

public class TestRunner {
    private static int testsPassed = 0;
    private static int testsFailed = 0;

    public static void main(String[] args) {
        try {
            testBoardParsing();
            testPieceRegistry();
            testConfigEmpty();
            System.out.println("Tests passed: " + testsPassed + ", failed: " + testsFailed);
            if (testsFailed > 0) {
                System.exit(1);
            }
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(2);
        }
    }

    private static void assertTrue(boolean cond, String name) {
        if (cond) {
            System.out.println("PASS: " + name);
            testsPassed++;
        } else {
            System.out.println("FAIL: " + name);
            testsFailed++;
        }
    }

    private static void testBoardParsing() {
        String input = "Board:\n" +
                       "wK bK\n" +
                       ". .\n" +
                       "Commands:\n";
        Scanner sc = new Scanner(input);
        Board b = Board.readFrom(sc);
        assertTrue(!b.isEmpty(), "board not empty after parsing");
        assertTrue(b.isValid(), "board is valid");
        sc.close();
    }

    private static void testPieceRegistry() {
        String[][] grid = new String[][] {
            {"wK", Config.EMPTY},
            {Config.EMPTY, "bK"}
        };
        boolean canKingMove = PieceMovementRegistry.isValid('K', grid, 0, 0, 1, 1, "wK");
        assertTrue(canKingMove, "king diagonal move allowed");
    }

    private static void testConfigEmpty() {
        assertTrue(Config.EMPTY.equals("."), "Config.EMPTY is dot");
    }
}

