package tests;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

import model.Board;
import model.GameState;
import model.MovingPiece;
import model.Piece;
import model.Position;
import view.BoardRenderer;

class BoardRendererTest {
    private static String capture(Runnable action) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream original = System.out;
        try {
            System.setOut(new PrintStream(out));
            action.run();
        } finally {
            System.setOut(original);
        }
        return out.toString();
    }

    @Test void testPrintsStaticBoardWithPiecesAndEmptyMarkers() {
        Piece[][] grid = new Piece[2][2];
        grid[0][0] = Piece.of(Piece.Color.WHITE, Piece.Type.K);
        Board board = new Board(grid);
        BoardRenderer renderer = new BoardRenderer(board, new ArrayList<>(), new GameState());

        String output = capture(renderer::printBoard);

        assertTrue(output.contains("wK"));
        assertTrue(output.contains("."));
    }

    @Test void testEmptyBoardPrintsNothingAndDoesNotThrow() {
        Board board = new Board(new Piece[0][0]);
        BoardRenderer renderer = new BoardRenderer(board, new ArrayList<>(), new GameState());

        String output = capture(renderer::printBoard);
        assertEquals("", output);
    }

    @Test void testShowsPieceInTransitAtFromAndEmptyAtDestination() {
        Piece[][] grid = new Piece[1][4];
        Piece rook = Piece.of(Piece.Color.WHITE, Piece.Type.R);
        grid[0][0] = rook;
        Board board = new Board(grid);
        GameState state = new GameState();
        List<MovingPiece> active = new ArrayList<>();
        active.add(new MovingPiece(rook, new Position(0, 0), new Position(0, 3), 1000, 0));

        BoardRenderer renderer = new BoardRenderer(board, active, state);
        String output = capture(renderer::printBoard);

        assertTrue(output.contains("wR"));
        // still mid-flight (currentTime 0 < arrival 1000): destination cell must read empty
        String[] cells = output.trim().split("\\s+");
        assertEquals(".", cells[3]);
    }

    @Test void testShowsArrivedPieceAtDestinationAndClearsOrigin() {
        Piece[][] grid = new Piece[1][4];
        Piece rook = Piece.of(Piece.Color.WHITE, Piece.Type.R);
        grid[0][0] = rook;
        Board board = new Board(grid);
        GameState state = new GameState();
        state.advanceTime(1000);
        List<MovingPiece> active = new ArrayList<>();
        active.add(new MovingPiece(rook, new Position(0, 0), new Position(0, 3), 1000, 0));

        BoardRenderer renderer = new BoardRenderer(board, active, state);
        String output = capture(renderer::printBoard);

        String[] cells = output.trim().split("\\s+");
        assertEquals(".", cells[0]);
        assertEquals("wR", cells[3]);
    }

    @Test void testArrivedPawnIsDisplayedAsPromotedQueen() {
        Piece[][] grid = new Piece[1][1];
        Piece pawn = Piece.of(Piece.Color.WHITE, Piece.Type.P);
        Board board = new Board(grid);
        GameState state = new GameState();
        state.advanceTime(1000);
        List<MovingPiece> active = new ArrayList<>();
        active.add(new MovingPiece(pawn, new Position(0, 0), new Position(0, 0), 1000, 0));

        BoardRenderer renderer = new BoardRenderer(board, active, state);
        String output = capture(renderer::printBoard);

        assertTrue(output.contains("wQ"));
    }
}
