package tests;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import config.GameConfig;
import model.Board;
import model.GameState;
import model.Piece;
import model.Position;
import gameengine.RealTimeArbiter;

class RealTimeArbiterTest {
    @Test void testHeadOnCollisionFirstRegisteredMoveWins() {
        // Two enemy slides swapping squares (same distance/duration, requested
        // at the same virtual instant) can't just pass through each other -
        // whichever was registered first survives; the other never arrives.
        Piece[][] grid = new Piece[8][8];
        Piece whiteRook = Piece.of(Piece.Color.WHITE, Piece.Type.R);
        Piece blackRook = Piece.of(Piece.Color.BLACK, Piece.Type.R);
        grid[0][0] = whiteRook;
        grid[0][3] = blackRook;
        Board board = new Board(grid);
        GameState state = new GameState();
        RealTimeArbiter arbiter = new RealTimeArbiter(board, state);

        arbiter.startMove(whiteRook, new Position(0, 0), new Position(0, 3), 3000); // registered first
        arbiter.startMove(blackRook, new Position(0, 3), new Position(0, 0), 3000); // registered second

        state.advanceTime(3000);
        arbiter.update();

        assertEquals(whiteRook, board.getCell(0, 3));
        assertNull(board.getCell(0, 0));
        assertTrue(arbiter.getActiveMoves().isEmpty());
    }

    @Test void testHeadOnCollisionOrderDeterminesTheWinner() {
        // Same setup, registration order reversed - the winner flips too.
        Piece[][] grid = new Piece[8][8];
        Piece whiteRook = Piece.of(Piece.Color.WHITE, Piece.Type.R);
        Piece blackRook = Piece.of(Piece.Color.BLACK, Piece.Type.R);
        grid[0][0] = whiteRook;
        grid[0][3] = blackRook;
        Board board = new Board(grid);
        GameState state = new GameState();
        RealTimeArbiter arbiter = new RealTimeArbiter(board, state);

        arbiter.startMove(blackRook, new Position(0, 3), new Position(0, 0), 3000); // registered first
        arbiter.startMove(whiteRook, new Position(0, 0), new Position(0, 3), 3000); // registered second

        state.advanceTime(3000);
        arbiter.update();

        assertEquals(blackRook, board.getCell(0, 0));
        assertNull(board.getCell(0, 3));
    }

    @Test void testHeadOnCollisionLosingKingEndsTheGame() {
        Piece[][] grid = new Piece[8][8];
        Piece whiteRook = Piece.of(Piece.Color.WHITE, Piece.Type.R);
        Piece blackKing = Piece.of(Piece.Color.BLACK, Piece.Type.K);
        grid[0][0] = whiteRook;
        grid[0][3] = blackKing;
        Board board = new Board(grid);
        GameState state = new GameState();
        RealTimeArbiter arbiter = new RealTimeArbiter(board, state);

        arbiter.startMove(whiteRook, new Position(0, 0), new Position(0, 3), 3000); // registered first, wins
        arbiter.startMove(blackKing, new Position(0, 3), new Position(0, 0), 3000); // registered second, loses

        state.advanceTime(3000);
        arbiter.update();

        assertEquals(whiteRook, board.getCell(0, 3));
        assertTrue(state.isGameOver());
    }

    @Test void testHeadOnCollisionDoesNotAffectAnUnrelatedBystanderMove() {
        Piece[][] grid = new Piece[8][8];
        Piece whiteRook = Piece.of(Piece.Color.WHITE, Piece.Type.R);
        Piece blackRook = Piece.of(Piece.Color.BLACK, Piece.Type.R);
        Piece bystander = Piece.of(Piece.Color.WHITE, Piece.Type.B);
        grid[0][0] = whiteRook;
        grid[0][3] = blackRook;
        grid[7][7] = bystander;
        Board board = new Board(grid);
        GameState state = new GameState();
        RealTimeArbiter arbiter = new RealTimeArbiter(board, state);

        arbiter.startMove(whiteRook, new Position(0, 0), new Position(0, 3), 3000);
        arbiter.startMove(blackRook, new Position(0, 3), new Position(0, 0), 3000);
        arbiter.startMove(bystander, new Position(7, 7), new Position(4, 4), 3000); // unrelated diagonal move

        state.advanceTime(3000);
        arbiter.update();

        assertEquals(whiteRook, board.getCell(0, 3));
        assertEquals(bystander, board.getCell(4, 4));
        assertNull(board.getCell(7, 7));
    }

    @Test void testMidAirCaptureTieGoesToTheDefender() {
        // A jump and an incoming enemy slide with the EXACT same arrival time:
        // the defender (already braced) wins ties, not the attacker.
        Piece[][] grid = new Piece[8][8];
        Piece king = Piece.of(Piece.Color.WHITE, Piece.Type.K);
        Piece rook = Piece.of(Piece.Color.BLACK, Piece.Type.R);
        grid[1][0] = king;
        grid[1][1] = rook;
        Board board = new Board(grid);
        GameState state = new GameState();
        RealTimeArbiter arbiter = new RealTimeArbiter(board, state);

        arbiter.startJump(king, new Position(1, 0), 1000);
        arbiter.startMove(rook, new Position(1, 1), new Position(1, 0), 1000); // same arrival time

        state.advanceTime(1000);
        arbiter.update();

        assertEquals(king, board.getCell(1, 0));
        assertNull(board.getCell(1, 1));
    }

    @Test void testCaptureRemovesThePieceFromTheBoard() {
        Piece[][] grid = new Piece[8][8];
        Piece knight = Piece.of(Piece.Color.BLACK, Piece.Type.N);
        grid[3][3] = knight;
        Board board = new Board(grid);
        RealTimeArbiter arbiter = new RealTimeArbiter(board, new GameState());

        arbiter.capture(3, 3, knight);

        assertNull(board.getCell(3, 3));
    }

    @Test void testCaptureOfKingEndsTheGame() {
        Piece[][] grid = new Piece[8][8];
        Piece king = Piece.of(Piece.Color.WHITE, Piece.Type.K);
        grid[0][0] = king;
        Board board = new Board(grid);
        GameState state = new GameState();
        RealTimeArbiter arbiter = new RealTimeArbiter(board, state);

        arbiter.capture(0, 0, king);

        assertTrue(state.isGameOver());
    }

    @Test void testStartMoveMarksBothSquaresBusyUntilArrival() {
        Piece[][] grid = new Piece[8][8];
        Piece rook = Piece.of(Piece.Color.WHITE, Piece.Type.R);
        grid[0][0] = rook;
        Board board = new Board(grid);
        GameState state = new GameState();
        RealTimeArbiter arbiter = new RealTimeArbiter(board, state);

        arbiter.startMove(rook, new Position(0, 0), new Position(0, 3), 1000);

        assertTrue(arbiter.isBusyAt(0, 0));
        assertTrue(arbiter.isBusyAt(0, 3));
        assertFalse(arbiter.isBusyAt(5, 5));
    }

    @Test void testMoveArrivesAndUpdatesBoardAfterDuration() {
        Piece[][] grid = new Piece[8][8];
        Piece rook = Piece.of(Piece.Color.WHITE, Piece.Type.R);
        grid[0][0] = rook;
        Board board = new Board(grid);
        GameState state = new GameState();
        RealTimeArbiter arbiter = new RealTimeArbiter(board, state);

        arbiter.startMove(rook, new Position(0, 0), new Position(0, 3), 1000);
        arbiter.advance(1000);

        assertNull(board.getCell(0, 0));
        assertEquals(rook, board.getCell(0, 3));
        assertFalse(arbiter.isBusyAt(0, 3));
        assertTrue(arbiter.getActiveMoves().isEmpty());
    }

    @Test void testFirstArrivalWinsSharedSquareEvenWhenBatchedInOneUpdate() {
        // Two FRIENDLY moves targeting the same square (a piece can't land on or
        // capture its own color), both arrivals falling in the same update() call
        // (e.g. after one large time jump): whichever genuinely arrives FIRST
        // claims the square. The other lands one cell short of it immediately,
        // instead of overwriting the winner or replaying its whole original path
        // as a second, slower move.
        Piece[][] grid = new Piece[8][8];
        Piece rookA = Piece.of(Piece.Color.WHITE, Piece.Type.R);
        Piece rookB = Piece.of(Piece.Color.WHITE, Piece.Type.R);
        grid[4][0] = rookA;
        grid[0][4] = rookB;
        Board board = new Board(grid);
        GameState state = new GameState();
        RealTimeArbiter arbiter = new RealTimeArbiter(board, state);

        // rookA requested first (arrives at t=4000)
        arbiter.startMove(rookA, new Position(4, 0), new Position(4, 4), 4000);
        state.advanceTime(10);
        // rookB requested second, at t=10 (arrives at t=4010 - genuinely later)
        arbiter.startMove(rookB, new Position(0, 4), new Position(4, 4), 4000);

        // one big jump resolves both arrivals in the same update() call
        state.advanceTime(100000);
        arbiter.update();

        assertEquals(rookA, board.getCell(4, 4));
        assertEquals(rookB, board.getCell(3, 4)); // lands short immediately, no second move
        assertNull(board.getCell(0, 4));
        assertTrue(arbiter.getActiveMoves().isEmpty());
    }

    @Test void testStillInFlightMoveKeepsMovingTowardTheShortenedSquareAtTheSamePace() {
        // rookB's own arrival is still far off when rookA (same color) claims the
        // square. It must be redirected right away (not only once rookB itself
        // "discovers" the conflict at its original arrival time), but it should
        // keep sliding smoothly toward the shortened square, not snap there instantly.
        Piece[][] grid = new Piece[8][8];
        Piece rookA = Piece.of(Piece.Color.WHITE, Piece.Type.R);
        Piece rookB = Piece.of(Piece.Color.WHITE, Piece.Type.R);
        grid[4][3] = rookA; // one cell from (4,4) - arrives quickly
        grid[0][4] = rookB; // four cells from (4,4) - still mid-flight when rookA lands
        Board board = new Board(grid);
        GameState state = new GameState();
        RealTimeArbiter arbiter = new RealTimeArbiter(board, state);

        arbiter.startMove(rookA, new Position(4, 3), new Position(4, 4), 1000);
        arbiter.startMove(rookB, new Position(0, 4), new Position(4, 4), 4000);

        state.advanceTime(1000);
        arbiter.update(); // rookA claims (4,4); rookB is redirected but keeps moving, toward (3,4) now

        assertEquals(rookA, board.getCell(4, 4));
        // rookB hasn't arrived anywhere yet - it's still mid-flight, just retargeted
        assertEquals(rookB, board.getCell(0, 4));
        assertTrue(arbiter.getActiveMoves().stream()
                .anyMatch(mp -> mp.getPiece() == rookB && mp.getTo().equals(new Position(3, 4))));

        state.advanceTime(100000);
        arbiter.update(); // let the shortened move actually finish

        assertEquals(rookB, board.getCell(3, 4));
        assertNull(board.getCell(0, 4));
        assertTrue(arbiter.getActiveMoves().isEmpty());
    }

    @Test void testAdjacentLoserOfARaceStaysPutInstead() {
        // rookB is already one cell from the contested square - there's nowhere
        // shorter to go, so it just stays where it is instead of moving at all.
        Piece[][] grid = new Piece[8][8];
        Piece rookA = Piece.of(Piece.Color.WHITE, Piece.Type.R);
        Piece rookB = Piece.of(Piece.Color.WHITE, Piece.Type.R);
        grid[4][0] = rookA;
        grid[4][3] = rookB;
        Board board = new Board(grid);
        GameState state = new GameState();
        RealTimeArbiter arbiter = new RealTimeArbiter(board, state);

        arbiter.startMove(rookA, new Position(4, 0), new Position(4, 4), 500); // arrives first
        arbiter.startMove(rookB, new Position(4, 3), new Position(4, 4), 2000); // still mid-flight when rookA lands

        state.advanceTime(500);
        arbiter.update();

        assertEquals(rookA, board.getCell(4, 4));
        assertEquals(rookB, board.getCell(4, 3));
        assertTrue(arbiter.getActiveMoves().isEmpty());
    }

    @Test void testDifferentColorRacersResultInACaptureNotAStopShort() {
        // Same setup as the first-arrival-wins test, but the two racers are
        // enemies: the later arrival just captures the earlier one normally,
        // like any other move onto an enemy-occupied square - it doesn't stop short.
        Piece[][] grid = new Piece[8][8];
        Piece whiteRook = Piece.of(Piece.Color.WHITE, Piece.Type.R);
        Piece blackRook = Piece.of(Piece.Color.BLACK, Piece.Type.R);
        grid[4][0] = whiteRook;
        grid[0][4] = blackRook;
        Board board = new Board(grid);
        GameState state = new GameState();
        RealTimeArbiter arbiter = new RealTimeArbiter(board, state);

        arbiter.startMove(whiteRook, new Position(4, 0), new Position(4, 4), 4000);
        state.advanceTime(10);
        arbiter.startMove(blackRook, new Position(0, 4), new Position(4, 4), 4000); // arrives later, captures

        state.advanceTime(100000);
        arbiter.update();

        assertEquals(blackRook, board.getCell(4, 4));
        assertTrue(arbiter.getActiveMoves().isEmpty());
    }

    @Test void testStillInFlightDifferentColorRacerIsLeftAloneAndCapturesOnItsOwnArrival() {
        Piece[][] grid = new Piece[8][8];
        Piece whiteRook = Piece.of(Piece.Color.WHITE, Piece.Type.R);
        Piece blackRook = Piece.of(Piece.Color.BLACK, Piece.Type.R);
        grid[4][3] = whiteRook; // arrives quickly
        grid[0][4] = blackRook; // still mid-flight when white lands
        Board board = new Board(grid);
        GameState state = new GameState();
        RealTimeArbiter arbiter = new RealTimeArbiter(board, state);

        arbiter.startMove(whiteRook, new Position(4, 3), new Position(4, 4), 1000);
        arbiter.startMove(blackRook, new Position(0, 4), new Position(4, 4), 4000);

        state.advanceTime(1000);
        arbiter.update(); // white claims (4,4); black is left alone, still heading straight for (4,4)

        assertEquals(whiteRook, board.getCell(4, 4));
        assertTrue(arbiter.getActiveMoves().stream()
                .anyMatch(mp -> mp.getPiece() == blackRook && mp.getTo().equals(new Position(4, 4))));

        state.advanceTime(100000);
        arbiter.update(); // black arrives and captures white, same as any normal capture

        assertEquals(blackRook, board.getCell(4, 4));
        assertTrue(arbiter.getActiveMoves().isEmpty());
    }

    @Test void testMoveDoesNotArriveBeforeDurationElapses() {
        Piece[][] grid = new Piece[8][8];
        Piece rook = Piece.of(Piece.Color.WHITE, Piece.Type.R);
        grid[0][0] = rook;
        Board board = new Board(grid);
        GameState state = new GameState();
        RealTimeArbiter arbiter = new RealTimeArbiter(board, state);

        arbiter.startMove(rook, new Position(0, 0), new Position(0, 3), 1000);
        arbiter.advance(500);

        assertEquals(rook, board.getCell(0, 0));
        assertNull(board.getCell(0, 3));
        assertFalse(arbiter.getActiveMoves().isEmpty());
    }

    @Test void testPawnPromotesOnArrival() {
        Piece[][] grid = new Piece[8][8];
        Piece pawn = Piece.of(Piece.Color.WHITE, Piece.Type.P);
        grid[1][0] = pawn;
        Board board = new Board(grid);
        GameState state = new GameState();
        RealTimeArbiter arbiter = new RealTimeArbiter(board, state);

        arbiter.startMove(pawn, new Position(1, 0), new Position(0, 0), 1000);
        arbiter.advance(1000);

        assertEquals(Piece.Type.Q, board.getCell(0, 0).getType());
    }

    @Test void testArrivingOnEnemyKingEndsGame() {
        Piece[][] grid = new Piece[8][8];
        Piece rook = Piece.of(Piece.Color.WHITE, Piece.Type.R);
        Piece king = Piece.of(Piece.Color.BLACK, Piece.Type.K);
        grid[0][0] = rook;
        grid[0][3] = king;
        Board board = new Board(grid);
        GameState state = new GameState();
        RealTimeArbiter arbiter = new RealTimeArbiter(board, state);

        arbiter.startMove(rook, new Position(0, 0), new Position(0, 3), 1000);
        arbiter.advance(1000);

        assertTrue(state.isGameOver());
    }

    @Test void testJumpInPlaceDefendsSquareAgainstIncomingSlide() {
        Piece[][] grid = new Piece[8][8];
        Piece rook = Piece.of(Piece.Color.WHITE, Piece.Type.R);
        Piece knight = Piece.of(Piece.Color.BLACK, Piece.Type.N);
        grid[0][0] = rook;
        grid[0][3] = knight;
        Board board = new Board(grid);
        GameState state = new GameState();
        RealTimeArbiter arbiter = new RealTimeArbiter(board, state);

        // the knight is already jumping in place on the target square when the
        // rook's slide toward that same square starts; resolution only
        // happens once the rook's own arrival time is actually reached
        arbiter.startJump(knight, new Position(0, 3), 500);
        arbiter.startMove(rook, new Position(0, 0), new Position(0, 3), 1000);
        state.advanceTime(1000);
        arbiter.update();

        assertNull(board.getCell(0, 0));
        assertTrue(arbiter.getActiveMoves().stream().noneMatch(mp -> mp.getPiece() == rook));
    }

    @Test void testAttackersMoveIsNotDestroyedEarlyEvenWhenAJumpDefenseWillEventuallyResolveAgainstIt() {
        // Regression: the old design resolved a jump-vs-slide race the instant
        // both moves coexisted in activeMoves, regardless of the slide's own
        // arrival time - destroying a slow attacker's move the moment a
        // defender jumped, even while the attacker was still far away and its
        // own arrival was seconds off. Resolution must wait for the
        // attacker's actual arrival instead.
        Piece[][] grid = new Piece[8][8];
        Piece rook = Piece.of(Piece.Color.BLACK, Piece.Type.R);
        Piece knight = Piece.of(Piece.Color.WHITE, Piece.Type.N);
        grid[0][0] = rook;
        grid[0][3] = knight;
        Board board = new Board(grid);
        GameState state = new GameState();
        RealTimeArbiter arbiter = new RealTimeArbiter(board, state);

        arbiter.startJump(knight, new Position(0, 3), 500); // defender jumps right away, completes at t=500
        arbiter.startMove(rook, new Position(0, 0), new Position(0, 3), 2000); // attacker still far off, arrives t=2000

        state.advanceTime(900); // the jump has already resolved (landed, and finished its own rest) by now
        arbiter.update();

        assertTrue(arbiter.getActiveMoves().stream().anyMatch(mp -> mp.getPiece() == rook)); // still on its way, not destroyed early
        assertEquals(knight, board.getCell(0, 3)); // knight still safely there for now

        state.advanceTime(1100); // now at t=2000, the rook's own arrival
        arbiter.update();

        // The knight's jump landed (and was removed from activeMoves) back
        // at t=500, long before the rook's own arrival at t=2000 - jumping
        // this early doesn't grant any protection against an attack that's
        // still seconds away, so the rook wins the race after all.
        assertEquals(rook, board.getCell(0, 3));
        assertNull(board.getCell(0, 0));
    }

    @Test void testJumpDefenseDoesNotOutlastTheJumpItself() {
        // A jump that lands well before an attacker arrives doesn't protect
        // "into the rest that follows it" - the whole grace-period-into-rest
        // idea is gone. Success now depends only on whether the jump is
        // still genuinely airborne (not yet landed) at the attacker's own
        // arrival - see isProtectedByAnInProgressJump.
        Piece[][] grid = new Piece[8][8];
        Piece rook = Piece.of(Piece.Color.BLACK, Piece.Type.R);
        Piece knight = Piece.of(Piece.Color.WHITE, Piece.Type.N);
        grid[0][0] = rook;
        grid[0][3] = knight;
        Board board = new Board(grid);
        GameState state = new GameState();
        RealTimeArbiter arbiter = new RealTimeArbiter(board, state);

        arbiter.startJump(knight, new Position(0, 3), 500); // jump finishes at t=500
        arbiter.startMove(rook, new Position(0, 0), new Position(0, 3), 700); // arrives at t=700

        state.advanceTime(500);
        arbiter.update(); // the jump lands and starts its own short-rest cooldown, in an earlier tick

        state.advanceTime(200); // now t=700 - knight is merely resting (not jumping) when the rook arrives
        arbiter.update();

        assertEquals(rook, board.getCell(0, 3)); // landed at t=500, long done by t=700 - an ordinary, undefended capture
        assertNull(board.getCell(0, 0));
    }

    @Test void testStillAirborneJumpDefendsAcrossSeparateTicksToo() {
        // The mirror image of the previous test: if the jump is still
        // genuinely airborne (not yet landed) when the attacker's own
        // arrival is processed - even in a separate, later update() call,
        // not the same batch - it still defends. No cross-tick bookkeeping
        // is needed for this: the jump's MovingPiece entry simply hasn't
        // been resolved yet, so it's still sitting right there in
        // activeMoves when the attacker's tick comes around.
        Piece[][] grid = new Piece[8][8];
        Piece rook = Piece.of(Piece.Color.BLACK, Piece.Type.R);
        Piece knight = Piece.of(Piece.Color.WHITE, Piece.Type.N);
        grid[0][0] = rook;
        grid[0][3] = knight;
        Board board = new Board(grid);
        GameState state = new GameState();
        RealTimeArbiter arbiter = new RealTimeArbiter(board, state);

        arbiter.startMove(rook, new Position(0, 0), new Position(0, 3), 700); // attacker arrives at t=700
        arbiter.startJump(knight, new Position(0, 3), 1000); // defender's jump finishes later, at t=1000 - still airborne at t=700

        state.advanceTime(700);
        arbiter.update(); // rook "arrives" but the knight isn't really there to capture - it's still mid-jump

        assertEquals(rook, board.getCell(0, 3)); // occupies the square normally for now - nobody captured yet
        assertTrue(arbiter.getActiveMoves().stream().anyMatch(mp -> mp.getPiece() == knight)); // the jump is still in progress

        state.advanceTime(300); // now t=1000 - the jump lands, in a separate, later update() call
        arbiter.update();

        assertEquals(knight, board.getCell(0, 3)); // the knight lands on the rook that moved in and captures it
        assertNull(board.getCell(0, 0));
    }

    @Test void testJumpProtectionEndsTheMomentTheDefenderMovesAwayInstead() {
        // If the piece moves away (a normal slide, not a jump) instead of
        // staying to face the incoming attacker, there's no jump in
        // progress at all - the attacker simply captures whatever's left
        // behind, exactly as it would for any other piece.
        Piece[][] grid = new Piece[8][8];
        Piece rook = Piece.of(Piece.Color.BLACK, Piece.Type.R);
        Piece knight = Piece.of(Piece.Color.WHITE, Piece.Type.N);
        Piece filler = Piece.of(Piece.Color.WHITE, Piece.Type.P);
        grid[0][0] = rook;
        grid[0][3] = knight;
        Board board = new Board(grid);
        GameState state = new GameState();
        RealTimeArbiter arbiter = new RealTimeArbiter(board, state);

        arbiter.startMove(knight, new Position(0, 3), new Position(1, 3), 1000); // knight flees instead of jumping
        state.advanceTime(1000);
        arbiter.update();

        board.setCell(0, 3, filler); // some unrelated pawn now happens to sit where the knight used to be
        arbiter.startMove(rook, new Position(0, 0), new Position(0, 3), 2000);
        state.advanceTime(2000);
        arbiter.update();

        assertEquals(knight, board.getCell(1, 3)); // the knight itself was never attacked - still safe where it moved to
        assertEquals(rook, board.getCell(0, 3)); // the filler pawn left behind is captured normally
    }

    @Test void testMidAirCaptureOfKingEndsGame() {
        Piece[][] grid = new Piece[8][8];
        Piece king = Piece.of(Piece.Color.WHITE, Piece.Type.K);
        Piece knight = Piece.of(Piece.Color.BLACK, Piece.Type.N);
        grid[0][0] = king;
        grid[0][3] = knight;
        Board board = new Board(grid);
        GameState state = new GameState();
        RealTimeArbiter arbiter = new RealTimeArbiter(board, state);

        arbiter.startMove(king, new Position(0, 0), new Position(0, 3), 1000); // king attacks, arrives t=1000
        arbiter.startJump(knight, new Position(0, 3), 1500); // knight's jump is still airborne at t=1000

        state.advanceTime(1000);
        arbiter.update(); // king "arrives" but the knight isn't really there yet - occupies the square for now

        assertFalse(state.isGameOver());

        state.advanceTime(500); // now t=1500 - the jump lands on the king that moved in
        arbiter.update();

        assertTrue(state.isGameOver());
    }

    @Test void testIsTooLateToJumpWhenReactingTooEarly() {
        // Reacting when there's still much more than JUMP_DURATION left on
        // the incoming attack means the jump would land back down long
        // before the attack arrives - too early to matter, so it's "too
        // late" in the sense that jumping wouldn't help at all.
        Piece[][] grid = new Piece[8][8];
        Piece rook = Piece.of(Piece.Color.WHITE, Piece.Type.R);
        Piece knight = Piece.of(Piece.Color.BLACK, Piece.Type.N);
        grid[0][0] = rook;
        grid[0][3] = knight;
        Board board = new Board(grid);
        GameState state = new GameState();
        RealTimeArbiter arbiter = new RealTimeArbiter(board, state);

        arbiter.startMove(rook, new Position(0, 0), new Position(0, 3), 1000);
        state.advanceTime(100); // 900ms left - well more than JUMP_DURATION (700ms), reacting way too early

        assertTrue(arbiter.isTooLateToJump(0, 3, knight));
    }

    @Test void testIsTooLateToJumpIsFalseOnAnExactTie() {
        // Regression: a jump that would finish at EXACTLY the same instant the
        // enemy slide arrives must not be "too late" - ties go to the
        // defender, same as the mid-air capture resolution in update().
        Piece[][] grid = new Piece[8][8];
        Piece rook = Piece.of(Piece.Color.WHITE, Piece.Type.R);
        Piece knight = Piece.of(Piece.Color.BLACK, Piece.Type.N);
        grid[0][0] = rook;
        grid[0][3] = knight;
        Board board = new Board(grid);
        GameState state = new GameState();
        RealTimeArbiter arbiter = new RealTimeArbiter(board, state);

        arbiter.startMove(rook, new Position(0, 0), new Position(0, 3), GameConfig.JUMP_DURATION);

        assertFalse(arbiter.isTooLateToJump(0, 3, knight));
    }

    @Test void testIsTooLateToJumpIsFalseWhenReactingWithLittleTimeLeft() {
        // The mirror image of the "too early" test above: reacting once the
        // attack has JUMP_DURATION or less left on its own clock means the
        // jump will still be airborne when it arrives - not too late.
        Piece[][] grid = new Piece[8][8];
        Piece rook = Piece.of(Piece.Color.WHITE, Piece.Type.R);
        Piece knight = Piece.of(Piece.Color.BLACK, Piece.Type.N);
        grid[0][0] = rook;
        grid[0][3] = knight;
        Board board = new Board(grid);
        GameState state = new GameState();
        RealTimeArbiter arbiter = new RealTimeArbiter(board, state);

        arbiter.startMove(rook, new Position(0, 0), new Position(0, 3), 1000);
        state.advanceTime(400); // 600ms left - within JUMP_DURATION (700ms)

        assertFalse(arbiter.isTooLateToJump(0, 3, knight));
    }

    @Test void testIsTooLateToJumpFalseWhenIncomingPieceIsFriendly() {
        Piece[][] grid = new Piece[8][8];
        Piece rookA = Piece.of(Piece.Color.WHITE, Piece.Type.R);
        Piece rookB = Piece.of(Piece.Color.WHITE, Piece.Type.R);
        grid[0][0] = rookA;
        grid[0][3] = rookB;
        Board board = new Board(grid);
        GameState state = new GameState();
        RealTimeArbiter arbiter = new RealTimeArbiter(board, state);

        arbiter.startMove(rookA, new Position(0, 0), new Position(0, 3), 1000);

        // same color as the incoming slide - not "too late", just a friendly square
        assertFalse(arbiter.isTooLateToJump(0, 3, rookB));
    }

    @Test void testIsTooLateToJumpFalseWhenNoIncomingEnemy() {
        Board board = new Board(new Piece[8][8]);
        GameState state = new GameState();
        RealTimeArbiter arbiter = new RealTimeArbiter(board, state);
        Piece knight = Piece.of(Piece.Color.BLACK, Piece.Type.N);

        assertFalse(arbiter.isTooLateToJump(0, 3, knight));
    }

    @Test void testKnightRaceConflictWhenTheKnightWouldBeTheSecondRequest() {
        Piece[][] grid = new Piece[8][8];
        Piece rook = Piece.of(Piece.Color.WHITE, Piece.Type.R);
        Piece knight = Piece.of(Piece.Color.WHITE, Piece.Type.N);
        grid[4][0] = rook;
        Board board = new Board(grid);
        RealTimeArbiter arbiter = new RealTimeArbiter(board, new GameState());

        arbiter.startMove(rook, new Position(4, 0), new Position(4, 4), 4000); // already heading to (4,4)

        assertTrue(arbiter.isKnightRaceConflict(new Position(4, 4), knight));
    }

    @Test void testKnightRaceConflictWhenTheKnightAlreadyClaimedTheSquare() {
        Piece[][] grid = new Piece[8][8];
        Piece knight = Piece.of(Piece.Color.WHITE, Piece.Type.N);
        Piece rook = Piece.of(Piece.Color.WHITE, Piece.Type.R);
        grid[2][3] = knight;
        Board board = new Board(grid);
        RealTimeArbiter arbiter = new RealTimeArbiter(board, new GameState());

        arbiter.startMove(knight, new Position(2, 3), new Position(4, 4), 3000); // already heading to (4,4)

        assertTrue(arbiter.isKnightRaceConflict(new Position(4, 4), rook));
    }

    @Test void testNoKnightRaceConflictAcrossDifferentColors() {
        // A knight vs. an enemy racing for the same square resolves as a normal
        // capture on arrival - no geometry problem, so it isn't blocked here.
        Piece[][] grid = new Piece[8][8];
        Piece whiteRook = Piece.of(Piece.Color.WHITE, Piece.Type.R);
        Piece blackKnight = Piece.of(Piece.Color.BLACK, Piece.Type.N);
        grid[4][0] = whiteRook;
        Board board = new Board(grid);
        RealTimeArbiter arbiter = new RealTimeArbiter(board, new GameState());

        arbiter.startMove(whiteRook, new Position(4, 0), new Position(4, 4), 4000);

        assertFalse(arbiter.isKnightRaceConflict(new Position(4, 4), blackKnight));
    }

    @Test void testNoKnightRaceConflictWhenNeitherPieceIsAKnight() {
        // Two sliding pieces racing for the same square still use the ordinary
        // "stop one cell short" resolution - not blocked outright.
        Piece[][] grid = new Piece[8][8];
        Piece rookA = Piece.of(Piece.Color.WHITE, Piece.Type.R);
        Piece rookB = Piece.of(Piece.Color.WHITE, Piece.Type.R);
        grid[4][0] = rookA;
        Board board = new Board(grid);
        RealTimeArbiter arbiter = new RealTimeArbiter(board, new GameState());

        arbiter.startMove(rookA, new Position(4, 0), new Position(4, 4), 4000);

        assertFalse(arbiter.isKnightRaceConflict(new Position(4, 4), rookB));
    }

    @Test void testMoveArrivalStartsLongRest() {
        Piece[][] grid = new Piece[8][8];
        Piece rook = Piece.of(Piece.Color.WHITE, Piece.Type.R);
        grid[0][0] = rook;
        Board board = new Board(grid);
        GameState state = new GameState();
        RealTimeArbiter arbiter = new RealTimeArbiter(board, state);

        arbiter.startMove(rook, new Position(0, 0), new Position(0, 3), 1000);
        arbiter.advance(1000); // arrives

        assertTrue(arbiter.isResting(0, 3));
        assertEquals(1, arbiter.getRestingPieces().size());
        assertFalse(arbiter.getRestingPieces().get(0).isFromJump());
    }

    @Test void testJumpCompletionStartsShortRest() {
        Piece[][] grid = new Piece[8][8];
        Piece knight = Piece.of(Piece.Color.BLACK, Piece.Type.N);
        grid[2][2] = knight;
        Board board = new Board(grid);
        GameState state = new GameState();
        RealTimeArbiter arbiter = new RealTimeArbiter(board, state);

        arbiter.startJump(knight, new Position(2, 2), 500);
        arbiter.advance(500); // jump completes without being captured

        assertTrue(arbiter.isResting(2, 2));
        assertEquals(1, arbiter.getRestingPieces().size());
        assertTrue(arbiter.getRestingPieces().get(0).isFromJump());
    }

    @Test void testRestExpiresAfterItsDuration() {
        Piece[][] grid = new Piece[8][8];
        Piece rook = Piece.of(Piece.Color.WHITE, Piece.Type.R);
        grid[0][0] = rook;
        Board board = new Board(grid);
        GameState state = new GameState();
        RealTimeArbiter arbiter = new RealTimeArbiter(board, state);

        arbiter.startMove(rook, new Position(0, 0), new Position(0, 3), 1000);
        arbiter.advance(1000); // arrives, default rest duration is 1000ms
        assertTrue(arbiter.isResting(0, 3));

        arbiter.advance(1000); // rest elapses
        assertFalse(arbiter.isResting(0, 3));
    }

    @Test void testScoreIncreasesOnDirectCapture() {
        Piece[][] grid = new Piece[8][8];
        Piece bishop = Piece.of(Piece.Color.BLACK, Piece.Type.B);
        grid[3][3] = bishop;
        Board board = new Board(grid);
        RealTimeArbiter arbiter = new RealTimeArbiter(board, new GameState());

        arbiter.capture(3, 3, bishop);

        assertEquals(3, arbiter.getScore(Piece.Color.WHITE));
        assertEquals(0, arbiter.getScore(Piece.Color.BLACK));
    }

    @Test void testKingCaptureAddsNoScore() {
        Piece[][] grid = new Piece[8][8];
        Piece king = Piece.of(Piece.Color.BLACK, Piece.Type.K);
        grid[3][3] = king;
        Board board = new Board(grid);
        RealTimeArbiter arbiter = new RealTimeArbiter(board, new GameState());

        arbiter.capture(3, 3, king);

        assertEquals(0, arbiter.getScore(Piece.Color.WHITE));
    }

    @Test void testScoreIncreasesWhenAnArrivalCapturesAnEnemy() {
        Piece[][] grid = new Piece[8][8];
        Piece whiteRook = Piece.of(Piece.Color.WHITE, Piece.Type.R);
        Piece blackPawn = Piece.of(Piece.Color.BLACK, Piece.Type.P);
        grid[0][0] = whiteRook;
        grid[0][3] = blackPawn;
        Board board = new Board(grid);
        GameState state = new GameState();
        RealTimeArbiter arbiter = new RealTimeArbiter(board, state);

        arbiter.startMove(whiteRook, new Position(0, 0), new Position(0, 3), 1000);
        arbiter.advance(1000);

        assertEquals(whiteRook, board.getCell(0, 3));
        assertEquals(1, arbiter.getScore(Piece.Color.WHITE));
    }

    @Test void testScoreIncreasesOnMidAirCapture() {
        Piece[][] grid = new Piece[8][8];
        Piece rook = Piece.of(Piece.Color.WHITE, Piece.Type.R);
        Piece knight = Piece.of(Piece.Color.BLACK, Piece.Type.N);
        grid[0][0] = rook;
        grid[0][3] = knight;
        Board board = new Board(grid);
        GameState state = new GameState();
        RealTimeArbiter arbiter = new RealTimeArbiter(board, state);

        arbiter.startMove(rook, new Position(0, 0), new Position(0, 3), 1000); // rook attacks, arrives t=1000
        arbiter.startJump(knight, new Position(0, 3), 1500); // knight's jump is still airborne at t=1000

        state.advanceTime(1000);
        arbiter.update(); // rook occupies the square for now - the knight isn't really there to capture yet

        state.advanceTime(500); // now t=1500 - the knight lands on the rook that moved in
        arbiter.update();

        assertEquals(5, arbiter.getScore(Piece.Color.BLACK)); // rook's material value
    }

    @Test void testScoreIncreasesOnHeadOnCollisionCapture() {
        Piece[][] grid = new Piece[8][8];
        Piece whiteRook = Piece.of(Piece.Color.WHITE, Piece.Type.R);
        Piece blackQueen = Piece.of(Piece.Color.BLACK, Piece.Type.Q);
        grid[0][0] = whiteRook;
        grid[0][3] = blackQueen;
        Board board = new Board(grid);
        GameState state = new GameState();
        RealTimeArbiter arbiter = new RealTimeArbiter(board, state);

        arbiter.startMove(whiteRook, new Position(0, 0), new Position(0, 3), 3000); // registered first, wins
        arbiter.startMove(blackQueen, new Position(0, 3), new Position(0, 0), 3000); // registered second, loses

        state.advanceTime(3000);
        arbiter.update();

        assertEquals(whiteRook, board.getCell(0, 3));
        assertEquals(9, arbiter.getScore(Piece.Color.WHITE)); // captured queen
    }

    @Test void testCapturingARestingPieceClearsItsStaleRestEntry() {
        // Regression: a captured piece's rest record used to linger (only
        // pruned once its own original timer ran out), so it could keep
        // blocking whoever replaced it on that square.
        Piece[][] grid = new Piece[8][8];
        Piece whiteRook = Piece.of(Piece.Color.WHITE, Piece.Type.R);
        Piece blackRook = Piece.of(Piece.Color.BLACK, Piece.Type.R);
        grid[0][0] = whiteRook;
        grid[5][3] = blackRook;
        Board board = new Board(grid);
        GameState state = new GameState();
        RealTimeArbiter arbiter = new RealTimeArbiter(board, state);

        arbiter.startMove(whiteRook, new Position(0, 0), new Position(0, 3), 3000);
        arbiter.advance(3000); // whiteRook arrives and starts resting at (0,3)
        assertTrue(arbiter.isResting(0, 3));

        arbiter.startMove(blackRook, new Position(5, 3), new Position(0, 3), 500);
        arbiter.advance(500); // blackRook captures whiteRook well before whiteRook's rest would have expired

        assertEquals(1, arbiter.getRestingPieces().size());
        assertEquals(blackRook, arbiter.getRestingPieces().get(0).getPiece());
        assertEquals(blackRook, board.getCell(0, 3));
    }

    @Test void testDirectCaptureClearsAnyRestingEntryAtThatSquare() {
        Piece[][] grid = new Piece[8][8];
        Piece rook = Piece.of(Piece.Color.WHITE, Piece.Type.R);
        grid[4][0] = rook;
        Board board = new Board(grid);
        GameState state = new GameState();
        RealTimeArbiter arbiter = new RealTimeArbiter(board, state);

        arbiter.startMove(rook, new Position(4, 0), new Position(4, 3), 1000);
        arbiter.advance(1000); // rook arrives at (4,3), starts resting
        assertTrue(arbiter.isResting(4, 3));

        arbiter.capture(4, 3, rook); // e.g. a mid-air/collision capture removing it

        assertTrue(arbiter.getRestingPieces().isEmpty());
        assertFalse(arbiter.isResting(4, 3));
    }

    @Test void testAttackerLandsSafelyWhenVictimEscapesInTheSameUpdateBatch() {
        // Regression: white arrives at (0,5) and black's escape from (0,5) both
        // resolve in the same update() call, with white processed first (same
        // arrival time, registered first). White used to see black still
        // sitting there (board not mutated yet), "capture" it, and land - then
        // black's OWN arrival cleared (0,5) as ITS origin, wiping out white
        // entirely, as if white itself had been captured.
        Piece[][] grid = new Piece[8][8];
        Piece whiteRook = Piece.of(Piece.Color.WHITE, Piece.Type.R);
        Piece blackRook = Piece.of(Piece.Color.BLACK, Piece.Type.R);
        grid[0][0] = whiteRook;
        grid[0][5] = blackRook;
        Board board = new Board(grid);
        GameState state = new GameState();
        RealTimeArbiter arbiter = new RealTimeArbiter(board, state);

        arbiter.startMove(whiteRook, new Position(0, 0), new Position(0, 5), 2000); // arrives at t=2000
        state.advanceTime(500);
        arbiter.startMove(blackRook, new Position(0, 5), new Position(3, 5), 1500); // flees, also arrives at t=2000

        state.advanceTime(1500); // t=2000 - both arrive in this same update() call
        arbiter.update();

        assertEquals(whiteRook, board.getCell(0, 5)); // white landed safely, not erased
        assertEquals(blackRook, board.getCell(3, 5)); // black escaped safely
        assertNull(board.getCell(0, 0));
        assertEquals(0, arbiter.getScore(Piece.Color.WHITE)); // no real capture happened
    }

    @Test void testFleeingPieceIsNeverCapturedEvenIfItHasNotArrivedAtItsOwnDestinationYet() {
        // A piece that already started fleeing before the enemy arrives must
        // never be captured at its old square - even if its own escape hasn't
        // itself landed yet at the moment the enemy gets there. White still
        // physically lands on the vacated square (its own slide has to finish
        // somewhere), but no capture is credited, and black's escape completes
        // normally afterward instead of being erased.
        Piece[][] grid = new Piece[8][8];
        Piece whiteBishop = Piece.of(Piece.Color.WHITE, Piece.Type.B);
        Piece blackPawn = Piece.of(Piece.Color.BLACK, Piece.Type.P);
        grid[4][0] = whiteBishop;
        grid[0][4] = blackPawn; // S
        Board board = new Board(grid);
        GameState state = new GameState();
        RealTimeArbiter arbiter = new RealTimeArbiter(board, state);

        arbiter.startMove(whiteBishop, new Position(4, 0), new Position(0, 4), 4000); // arrives at t=4000
        state.advanceTime(3200);
        arbiter.startMove(blackPawn, new Position(0, 4), new Position(1, 4), 1000); // flees, arrives at t=4200

        state.advanceTime(800); // t=4000: white arrives, black still mid-flight (hadn't escaped yet)
        arbiter.update();

        assertEquals(whiteBishop, board.getCell(0, 4)); // white's own slide still has to land somewhere
        assertEquals(0, arbiter.getScore(Piece.Color.WHITE)); // but no capture - black had already started fleeing
        assertTrue(arbiter.getActiveMoves().stream().anyMatch(mp -> mp.getPiece() == blackPawn)); // black's escape is still on track

        state.advanceTime(300); // past t=4200: black's own escape now arrives
        arbiter.update();

        assertEquals(whiteBishop, board.getCell(0, 4)); // still safely there
        assertEquals(blackPawn, board.getCell(1, 4)); // black's escape completed safely
        assertEquals(0, arbiter.getScore(Piece.Color.WHITE)); // still no capture
    }

    @Test void testPawnCannotCaptureViaStraightAdvanceEvenIfAnEnemyArrivesJustInTime() {
        // Regression: a pawn can only capture diagonally. If it advances
        // straight ahead and an enemy (or a friendly piece) beats it to that
        // square, the pawn must stop one square short - not "capture" it,
        // which pawns can never legally do moving straight.
        Piece[][] grid = new Piece[8][8];
        Piece whitePawn = Piece.of(Piece.Color.WHITE, Piece.Type.P);
        Piece blackPawn = Piece.of(Piece.Color.BLACK, Piece.Type.P);
        grid[6][4] = whitePawn;
        grid[3][4] = blackPawn;
        Board board = new Board(grid);
        GameState state = new GameState();
        RealTimeArbiter arbiter = new RealTimeArbiter(board, state);

        arbiter.startMove(whitePawn, new Position(6, 4), new Position(4, 4), 2000); // 2-square advance
        arbiter.startMove(blackPawn, new Position(3, 4), new Position(4, 4), 500); // 1-square advance, arrives first

        arbiter.advance(500); // black arrives at (4,4) first
        assertEquals(blackPawn, board.getCell(4, 4));

        arbiter.advance(1500); // white would also reach (4,4) now, but must stop short instead

        assertEquals(blackPawn, board.getCell(4, 4)); // black still safely there - not captured
        assertEquals(whitePawn, board.getCell(5, 4)); // white stopped one square short
        assertNull(board.getCell(6, 4));
        assertEquals(0, arbiter.getScore(Piece.Color.WHITE)); // no illegal capture credited
    }

    @Test void testPawnDiagonalCaptureStillWorksNormallyInARace() {
        // Sanity check: the straight-advance restriction must not affect a
        // pawn's legitimate diagonal capture.
        Piece[][] grid = new Piece[8][8];
        Piece whitePawn = Piece.of(Piece.Color.WHITE, Piece.Type.P);
        Piece blackPawn = Piece.of(Piece.Color.BLACK, Piece.Type.P);
        grid[6][4] = whitePawn;
        grid[5][5] = blackPawn;
        Board board = new Board(grid);
        GameState state = new GameState();
        RealTimeArbiter arbiter = new RealTimeArbiter(board, state);

        arbiter.startMove(whitePawn, new Position(6, 4), new Position(5, 5), 1000); // diagonal capture
        arbiter.advance(1000);

        assertEquals(whitePawn, board.getCell(5, 5));
        assertEquals(1, arbiter.getScore(Piece.Color.WHITE));
    }
}
