package tests;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;

import model.Board;
import model.GameState;
import model.MoveLogEntry;
import model.MovingPiece;
import model.Piece;
import model.Position;
import model.RestingPiece;
import snapshot.GameSnapshot;
import snapshot.PieceSnapshot;
import snapshot.PieceVisualState;
import snapshot.SnapshotBuilder;

class SnapshotBuilderTest {

    private static GameSnapshot build(Board board, List<MovingPiece> activeMoves,
                                       List<RestingPiece> restingPieces, GameState state, Position selection) {
        return SnapshotBuilder.build(board, activeMoves, restingPieces, state, selection,
                0, 0, new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
    }

    @Test void testStaticBoardProducesIdlePieces() {
        Piece[][] grid = new Piece[2][2];
        grid[0][0] = Piece.of(Piece.Color.WHITE, Piece.Type.K);
        Board board = new Board(grid);

        GameSnapshot snapshot = build(board, new ArrayList<>(), new ArrayList<>(), new GameState(), null);

        assertEquals(1, snapshot.pieces().size());
        PieceSnapshot piece = snapshot.pieces().get(0);
        assertEquals(Piece.Type.K, piece.type());
        assertEquals(Piece.Color.WHITE, piece.color());
        assertEquals(0, piece.fromRow());
        assertEquals(0, piece.fromCol());
        assertEquals(0, piece.toRow());
        assertEquals(0, piece.toCol());
        assertEquals(0.0, piece.progress());
        assertEquals(PieceVisualState.IDLE, piece.state());
        assertFalse(snapshot.gameOver());
        assertNull(snapshot.winner());
    }

    @Test void testIdlePiecesStateElapsedAdvancesWithTimeSoTheyKeepAnimating() {
        // Regression: idle pieces used to always report stateElapsedMillis=0,
        // which pins PieceSprites.frame() to frame 1 forever - idle's own
        // config.json loops, so feeding it the current time (rather than
        // needing to track "since when has this piece been idle") is enough
        // to make it cycle correctly.
        Piece[][] grid = new Piece[2][2];
        grid[0][0] = Piece.of(Piece.Color.WHITE, Piece.Type.K);
        Board board = new Board(grid);
        GameState state = new GameState();
        state.advanceTime(3200);

        GameSnapshot snapshot = build(board, new ArrayList<>(), new ArrayList<>(), state, null);

        PieceSnapshot piece = snapshot.pieces().get(0);
        assertEquals(PieceVisualState.IDLE, piece.state());
        assertEquals(3200, piece.stateElapsedMillis());
    }

    @Test void testRestingPieceIsNotHiddenByAFleeingPieceStillClaimingItsOldSquare() {
        // Regression: an attacker that lands on a square a victim is still
        // fleeing FROM (exempt from capture, since the victim already started
        // leaving - see RealTimeArbiter's departingActive) ends up resting on
        // that exact square while the victim's own MovingPiece - still
        // legitimately in flight to its OWN destination - still has that same
        // square as its "from". The old logic unconditionally covered a move's
        // origin cell, hiding whoever had since legitimately landed there.
        Piece[][] grid = new Piece[2][2];
        Piece attacker = Piece.of(Piece.Color.BLACK, Piece.Type.R);
        Piece victim = Piece.of(Piece.Color.WHITE, Piece.Type.P);
        grid[0][1] = attacker; // attacker already landed here, overwriting the board
        Board board = new Board(grid);
        GameState state = new GameState();
        state.advanceTime(500); // mid-flight for the victim, mid-rest for the attacker

        List<MovingPiece> active = new ArrayList<>();
        active.add(new MovingPiece(victim, new Position(0, 1), new Position(1, 1), 1000, 0));

        List<RestingPiece> resting = new ArrayList<>();
        resting.add(new RestingPiece(attacker, new Position(0, 1), 667, false));

        GameSnapshot snapshot = build(board, active, resting, state, null);

        assertEquals(2, snapshot.pieces().size());
        assertTrue(snapshot.pieces().stream()
                .anyMatch(p -> p.color() == Piece.Color.BLACK && p.state() == PieceVisualState.LONG_REST));
        assertTrue(snapshot.pieces().stream()
                .anyMatch(p -> p.color() == Piece.Color.WHITE && p.state() == PieceVisualState.MOVE));
    }

    @Test void testInFlightSlideIsReportedAsMoveWithPartialProgress() {
        Piece[][] grid = new Piece[1][4];
        Piece rook = Piece.of(Piece.Color.WHITE, Piece.Type.R);
        grid[0][0] = rook;
        Board board = new Board(grid);
        GameState state = new GameState();
        state.advanceTime(500); // halfway through a 1000ms move that started at t=0

        List<MovingPiece> active = new ArrayList<>();
        active.add(new MovingPiece(rook, new Position(0, 0), new Position(0, 3), 1000, 0));

        GameSnapshot snapshot = build(board, active, new ArrayList<>(), state, null);

        assertEquals(1, snapshot.pieces().size());
        PieceSnapshot piece = snapshot.pieces().get(0);
        assertEquals(PieceVisualState.MOVE, piece.state());
        assertEquals(0, piece.fromCol());
        assertEquals(3, piece.toCol());
        assertEquals(0.5, piece.progress(), 1e-9);
    }

    @Test void testInPlaceJumpIsReportedAsJump() {
        Piece[][] grid = new Piece[1][1];
        Piece knight = Piece.of(Piece.Color.BLACK, Piece.Type.N);
        grid[0][0] = knight;
        Board board = new Board(grid);
        GameState state = new GameState();

        List<MovingPiece> active = new ArrayList<>();
        active.add(new MovingPiece(knight, new Position(0, 0), new Position(0, 0), 1000, 0));

        GameSnapshot snapshot = build(board, active, new ArrayList<>(), state, null);

        assertEquals(1, snapshot.pieces().size());
        assertEquals(PieceVisualState.JUMP, snapshot.pieces().get(0).state());
    }

    @Test void testArrivedMoveLandsAtDestinationAndCarriesPromotion() {
        Piece[][] grid = new Piece[1][1];
        Piece pawn = Piece.of(Piece.Color.WHITE, Piece.Type.P);
        grid[0][0] = pawn;
        Board board = new Board(grid);
        GameState state = new GameState();
        state.advanceTime(1000);

        List<MovingPiece> active = new ArrayList<>();
        active.add(new MovingPiece(pawn, new Position(0, 0), new Position(0, 0), 1000, 0));

        GameSnapshot snapshot = build(board, active, new ArrayList<>(), state, null);

        assertEquals(1, snapshot.pieces().size());
        PieceSnapshot piece = snapshot.pieces().get(0);
        assertEquals(Piece.Type.Q, piece.type()); // promoted at row 0
        assertEquals(PieceVisualState.IDLE, piece.state());
        assertEquals(1.0, piece.progress(), 1e-9);
    }

    @Test void testSelectionIsCarriedThroughUnchanged() {
        Board board = new Board(new Piece[2][2]);
        Position selection = new Position(1, 1);

        GameSnapshot snapshot = build(board, new ArrayList<>(), new ArrayList<>(), new GameState(), selection);

        assertEquals(selection, snapshot.positionSelected());
    }

    @Test void testGameOverWinnerComesStraightFromGameState() {
        // The winner is whatever RealTimeArbiter recorded on GameState at the
        // moment the game actually ended - not re-derived here by scanning the
        // board for which king is still present.
        Board board = new Board(new Piece[1][1]);
        GameState state = new GameState();
        state.setGameOver(Piece.Color.BLACK);

        GameSnapshot snapshot = build(board, new ArrayList<>(), new ArrayList<>(), state, null);

        assertTrue(snapshot.gameOver());
        assertEquals("BLACK", snapshot.winner());
    }

    @Test void testNotGameOverHasNoWinner() {
        Board board = new Board(new Piece[0][0]);
        GameSnapshot snapshot = build(board, new ArrayList<>(), new ArrayList<>(), new GameState(), null);

        assertFalse(snapshot.gameOver());
        assertNull(snapshot.winner());
    }

    @Test void testRestingPieceAfterMoveIsReportedAsLongRest() {
        Piece[][] grid = new Piece[1][1];
        Piece rook = Piece.of(Piece.Color.WHITE, Piece.Type.R);
        grid[0][0] = rook;
        Board board = new Board(grid);
        GameState state = new GameState();
        long restDuration = rook.getLongRestDuration();
        long halfway = restDuration / 2;
        state.advanceTime(halfway); // halfway into a rest that started at t=0

        List<RestingPiece> resting = new ArrayList<>();
        resting.add(new RestingPiece(rook, new Position(0, 0), restDuration, false));

        GameSnapshot snapshot = build(board, new ArrayList<>(), resting, state, null);

        assertEquals(1, snapshot.pieces().size());
        PieceSnapshot piece = snapshot.pieces().get(0);
        assertEquals(PieceVisualState.LONG_REST, piece.state());
        assertEquals(halfway, piece.stateElapsedMillis());
        assertEquals(halfway / (double) restDuration, piece.progress(), 1e-9); // halfway into the (default) rest
    }

    @Test void testRestingPieceAfterJumpIsReportedAsShortRest() {
        Piece[][] grid = new Piece[1][1];
        Piece knight = Piece.of(Piece.Color.BLACK, Piece.Type.N);
        grid[0][0] = knight;
        Board board = new Board(grid);
        GameState state = new GameState();

        List<RestingPiece> resting = new ArrayList<>();
        resting.add(new RestingPiece(knight, new Position(0, 0), 1000, true));

        GameSnapshot snapshot = build(board, new ArrayList<>(), resting, state, null);

        assertEquals(1, snapshot.pieces().size());
        assertEquals(PieceVisualState.SHORT_REST, snapshot.pieces().get(0).state());
    }

    @Test void testExpiredRestIsNotReported() {
        Piece[][] grid = new Piece[1][1];
        Piece rook = Piece.of(Piece.Color.WHITE, Piece.Type.R);
        grid[0][0] = rook;
        Board board = new Board(grid);
        GameState state = new GameState();
        state.advanceTime(1000); // rest already over

        List<RestingPiece> resting = new ArrayList<>();
        resting.add(new RestingPiece(rook, new Position(0, 0), 1000, false));

        GameSnapshot snapshot = build(board, new ArrayList<>(), resting, state, null);

        assertEquals(1, snapshot.pieces().size());
        assertEquals(PieceVisualState.IDLE, snapshot.pieces().get(0).state());
    }

    @Test void testScoresAndMoveLogsFlowThroughUnchanged() {
        Board board = new Board(new Piece[0][0]);
        List<MoveLogEntry> whiteMoves = List.of(new MoveLogEntry(0, "e4"));
        List<MoveLogEntry> blackMoves = List.of(new MoveLogEntry(10, "e5"), new MoveLogEntry(20, "Nc6"));

        GameSnapshot snapshot = SnapshotBuilder.build(board, new ArrayList<>(), new ArrayList<>(), new GameState(), null,
                3, 7, whiteMoves, blackMoves, new ArrayList<>());

        assertEquals(3, snapshot.whiteScore());
        assertEquals(7, snapshot.blackScore());
        assertEquals(1, snapshot.whiteMoves().size());
        assertEquals("e4", snapshot.whiteMoves().get(0).getNotation());
        assertEquals(2, snapshot.blackMoves().size());
        assertEquals("Nc6", snapshot.blackMoves().get(1).getNotation());
    }
}
