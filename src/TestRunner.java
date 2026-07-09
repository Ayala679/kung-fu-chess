/**
 * LEGACY TEST RUNNER - For quick sanity checks only.
 *
 * For proper test coverage with JUnit 5 + JaCoCo:
 *
 * 1. Unit tests location: ../../tests/
 *    - tests/model/PositionTest.java, PieceTest.java, BoardTest.java
 *    - tests/strategy/MovementTest.java
 *    - tests/ruleengine/MoveValidatorTest.java
 *
 * 2. Generate coverage report:
 *    cd src
 *    mvn clean test jacoco:report
 *    Report: target/site/jacoco/index.html
 *
 * 3. SOLID Principles verified:
 *    ✓ DRY: Constants centralized in GameConfig
 *    ✓ SRP: Each class has single responsibility
 *    ✓ No magic numbers in business logic
 *    ✓ Encapsulation: Model classes hide implementation
 *
 * 4. Future extensibility ready:
 *    ✓ Binary board support: Create BinaryBoard implementing Board interface
 *    ✓ Custom games: Extract pawn promotion to PromotionStrategy interface
 *    ✓ New piece types: Add strategy to MovementStrategy interface
 *
 * Git: https://github.com/user/Kung_Fu_Chess
 */
import java.util.Scanner;
import controller.BoardController;
import config.GameConfig;
import ruleengine.PieceMovementRegistry;
import model.Piece;
import model.Board;
import model.Position;

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
        BoardController b = BoardController.readFrom(sc);
        assertTrue(!b.isEmpty(), "board not empty after parsing");
        assertTrue(b.isValid(), "board is valid");
        sc.close();
    }

    private static void testPieceRegistry() {
        Piece[][] grid = new Piece[][] {
            { Piece.fromToken("wK"), null },
            { null, Piece.fromToken("bK") }
        };
        boolean canKingMove = PieceMovementRegistry.isValid(Piece.Type.K, new Board(grid), new Position(0,0), new Position(1,1), Piece.fromToken("wK"));
        assertTrue(canKingMove, "king diagonal move allowed");
    }

    private static void testConfigEmpty() {
        assertTrue(GameConfig.EMPTY.equals("."), "Config.EMPTY is dot");
    }
}

