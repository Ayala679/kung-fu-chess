package server;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import bus.Bus;
import event.ClickSelector;
import gameengine.GameEngine;
import logging.ActivityLog;
import model.Board;
import model.GameState;
import model.Piece;
import model.Position;
import parsing.BoardMapper;
import server.auth.GameResultRepository;
import server.auth.RatingService;
import server.auth.UserRepository;
import server.connection.OutboundConnection;
import snapshot.GameSnapshot;
import snapshot.MoveNotation;

/**
 * One game: a board + engine, up to two seated players plus any number of
 * read-only spectators, and a real-time ticker that keeps the virtual clock
 * moving and broadcasts each connection its own snapshot. GameEngine here is
 * exactly the same class BoardController/GuiMain already drive locally for
 * offline play - nothing about its logic changes for the networked case.
 *
 * Talks to GameEngine directly rather than through event.EventEngine: that
 * class owns a single shared "selection" field, correct for one local mouse,
 * but wrong for two independent network players sharing one board - White
 * selecting a piece must never show up as Black's selection (or worse, let
 * Black's next click move White's selected piece). So GameSession tracks
 * {@code whiteSelection}/{@code blackSelection} separately, one per color,
 * and drives each through {@link event.ClickSelector} - the same click
 * rules EventEngine itself delegates to, so there is exactly one place that
 * knows what a click means, not two copies that can quietly drift apart.
 * Each connection's outgoing snapshot is built with
 * {@code GameEngine.buildSnapshot(<that seat's own selection, or null for a
 * spectator>)}, so a player only ever sees their own selection highlight and
 * legal-move markers - never the opponent's, and a spectator never sees
 * either (read-only, per the CTD brief).
 *
 * GameEngine/RealTimeArbiter are plain, non-thread-safe classes by design -
 * every other entry point drives them from a single thread. Incoming
 * commands arrive on their own threads (Java-WebSocket's own connection
 * callback threads in the local/offline topology, a NATS message-handler
 * thread in the distributed one - see OutboundConnection), concurrently
 * with this session's ticker/disconnect-timer thread, so every access to
 * {@code engine} (and to the two selection fields) is serialized through
 * {@code lock}. {@code engine} itself is reassigned wholesale on a
 * successful rematch request (see {@link #requestRematch()}) rather than
 * reset in place, which is why it isn't {@code final}.
 *
 * While either seat is vacated by a disconnect (see {@link
 * #isPausedForDisconnect()}), the virtual clock is frozen and both click/jump
 * requests are rejected with {@code ERROR|GAME_PAUSED} - the still-connected
 * side gets no free window to act against an opponent who can't respond. If
 * the grace period elapses without a reconnect, the game ends with no
 * winner and no rating change (see {@link #scheduleAutoAbandon}) - a
 * disconnect is nobody's fault, so nobody is credited with a win or charged
 * with a loss for it.
 */
public class GameSession {
    private static final long TICK_MS = 16;
    private static final long DEFAULT_DISCONNECT_GRACE_MILLIS = 20_000L;

    private static final String ERROR_VIEWER_CANNOT_PLAY = "VIEWER_CANNOT_PLAY";
    private static final String ERROR_NOT_YOUR_PIECE = "NOT_YOUR_PIECE";
    private static final String ERROR_MALFORMED_COMMAND = "MALFORMED_COMMAND";
    private static final String ERROR_ILLEGAL_MOVE = "ILLEGAL_MOVE";
    private static final String ERROR_GAME_PAUSED = "GAME_PAUSED";
    private static final String ERROR_GAME_NOT_OVER = "GAME_NOT_OVER";
    private static final String ERROR_INTERNAL = "INTERNAL_ERROR";

    /** What kind of action a {@link PendingAction} is watching for completion. */
    private enum ActionKind { MOVE, JUMP }

    /**
     * A just-accepted move/jump this session is watching so it can announce
     * {@code EVENT|MOVE_COMPLETED}/{@code MOVE_INTERRUPTED}/{@code
     * JUMP_COMPLETED} once it resolves - see {@link #resolvePendingActions()}.
     * For a JUMP, {@code to} equals {@code from} (matching how
     * RealTimeArbiter itself represents a jump as a zero-distance move).
     */
    private static final class PendingAction {
        final ActionKind kind;
        final Piece.Color color;
        final Position from;
        final Position to;

        PendingAction(ActionKind kind, Piece.Color color, Position from, Position to) {
            this.kind = kind;
            this.color = color;
            this.from = from;
            this.to = to;
        }
    }

    private static final String STANDARD_BOARD =
            "Board:\n" +
            "bR bN bB bK bQ bB bN bR\n" +
            "bP bP bP bP bP bP bP bP\n" +
            ". . . . . . . .\n" +
            ". . . . . . . .\n" +
            ". . . . . . . .\n" +
            ". . . . . . . .\n" +
            "wP wP wP wP wP wP wP wP\n" +
            "wR wN wB wK wQ wB wN wR\n" +
            "Commands:\n";

    private final Object lock = new Object();
    private final Bus bus;
    private final String roomCode;
    private final String snapshotTopic;
    private GameEngine engine;
    private final RatingService ratingService;
    private final GameResultRepository gameResultRepository;
    private final UserRepository userRepository;
    private final ActivityLog activityLog;
    private final long disconnectGraceMillis;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "game-session-" + Integer.toHexString(hashCode()));
        t.setDaemon(true);
        return t;
    });

    private final List<OutboundConnection> viewers = new CopyOnWriteArrayList<>();
    private final List<PendingAction> pendingActions = new ArrayList<>();

    private Position whiteSelection;
    private Position blackSelection;

    private OutboundConnection whiteConnection;
    private OutboundConnection blackConnection;
    private String whiteUsername;
    private String blackUsername;
    private int whiteRating;
    private int blackRating;
    private boolean ratingApplied = false;
    private boolean gameStarted = false;
    private ScheduledFuture<?> whiteAbandonTask;
    private ScheduledFuture<?> blackAbandonTask;

    public GameSession(Bus bus, String roomCode, UserRepository userRepository, ActivityLog activityLog) {
        this(bus, roomCode, userRepository, activityLog, DEFAULT_DISCONNECT_GRACE_MILLIS);
    }

    /**
     * Same as the 4-arg constructor, with the disconnect-&gt;abandon grace
     * period given explicitly instead of the default 20 seconds - so tests
     * can use a short, real (wall-clock) delay instead of actually waiting
     * 20 seconds for {@link #handleDisconnect}'s abandon timer.
     */
    public GameSession(Bus bus, String roomCode, UserRepository userRepository, ActivityLog activityLog,
                        long disconnectGraceMillis) {
        this.bus = bus;
        this.roomCode = roomCode;
        this.snapshotTopic = "room." + roomCode + ".snapshot";
        this.ratingService = new RatingService(userRepository);
        this.gameResultRepository = GameResultRepository.forJdbcUrl(userRepository.getJdbcUrl());
        this.userRepository = userRepository;
        this.activityLog = activityLog;
        this.disconnectGraceMillis = disconnectGraceMillis;
        this.engine = newStandardGameEngine();
    }

    private static GameEngine newStandardGameEngine() {
        Board board = BoardMapper.readBoard(new Scanner(STANDARD_BOARD));
        return new GameEngine(board, new GameState());
    }

    public String getRoomCode() {
        return roomCode;
    }

    /** True once both a White and Black player are seated. */
    public boolean isFull() {
        synchronized (lock) {
            return whiteConnection != null && blackConnection != null;
        }
    }

    /**
     * Seats the first connection White, the second Black, and every one
     * after that as a read-only VIEWER. The White seat gets no greeting
     * (WELCOME/STATE) yet - there's no game to show until an opponent
     * arrives - so its client just keeps showing "waiting for an
     * opponent...". Once Black joins, both are greeted together and the
     * game actually starts; a VIEWER joining an already-running game is
     * greeted immediately.
     *
     * All the mutable-state work (which seat, updating engine player names)
     * happens inside one {@code synchronized(lock)} block, matching every
     * other entry point that touches {@code whiteConnection}/{@code
     * blackConnection}/{@code engine} - greeting/logging (network I/O)
     * happen afterward, outside the lock. If the freshly-seated connection
     * is already closed (one that raced a real disconnect before it ever
     * reached this method - see the distributed topology's connectionId
     * caches), this immediately runs the exact same handling a live
     * disconnect would, instead of silently treating a dead connection as a
     * present opponent forever.
     */
    public Seat join(OutboundConnection connection, String username, int rating) {
        Seat assigned;
        OutboundConnection whiteConn = null;
        synchronized (lock) {
            if (whiteConnection == null) {
                whiteConnection = connection;
                whiteUsername = username;
                whiteRating = rating;
                assigned = Seat.WHITE;
            } else if (blackConnection == null) {
                blackConnection = connection;
                blackUsername = username;
                blackRating = rating;
                engine.setPlayerNames(whiteUsername, blackUsername);
                whiteConn = whiteConnection;
                gameStarted = true;
                assigned = Seat.BLACK;
            } else {
                viewers.add(connection);
                assigned = Seat.VIEWER;
            }
        }

        if (assigned == Seat.WHITE) {
            activityLog.log("room=" + roomCode + " " + username + " seated WHITE - waiting for an opponent");
        } else if (assigned == Seat.BLACK) {
            startTicker();
            activityLog.log("room=" + roomCode + " " + username + " seated BLACK - game starting");
            greet(whiteConn, Seat.WHITE);
            greet(connection, Seat.BLACK);
        } else {
            activityLog.log("room=" + roomCode + " " + username + " joined as VIEWER");
            greet(connection, Seat.VIEWER);
        }

        if (assigned.isPlayer() && !connection.isOpen()) {
            handleDisconnect(connection);
        }
        return assigned;
    }

    // Sends WELCOME + one initial snapshot to a newly-seated connection. Calls buildSnapshotFor/buildNeutralSnapshot.
    private void greet(OutboundConnection connection, Seat seat) {
        send(connection, Protocol.WELCOME + "|role=" + seat);
        GameSnapshot snapshot = seat.isPlayer() ? buildSnapshotFor(seat.toColor()) : buildNeutralSnapshot();
        send(connection, encode(snapshot));
    }

    public Seat seatOf(OutboundConnection connection) {
        synchronized (lock) {
            if (connection == whiteConnection) return Seat.WHITE;
            if (connection == blackConnection) return Seat.BLACK;
        }
        if (viewers.contains(connection)) return Seat.VIEWER;
        return null;
    }

    /**
     * True once the current game has ended (a real win/resignation, or a
     * no-winner abandon) - used by KungFuChessServerService to tell a "play"/
     * "join_room" sent by a connection still mapped to this (finished)
     * session apart from "rematch": the former should leave this session
     * (see handleDisconnect) and fall through to normal lobby handling
     * instead of being swallowed here as an unrecognized command.
     */
    public boolean isGameOver() {
        synchronized (lock) {
            return engine.isGameOver();
        }
    }

    /**
     * True once this room can never do anything more: both seats are
     * currently unoccupied, and either the game is genuinely over or it
     * never actually started (a lone seated player who left before an
     * opponent ever joined). Used by Lobby to retire a room - stop its
     * ticker (see {@link #shutdown()}) and drop it from the room table -
     * instead of leaking one live thread and one map entry per room ever
     * played for the lifetime of the process. Deliberately false while both
     * seats are empty but the game *hasn't* ended yet (e.g. both players
     * disconnected mid-game and the auto-abandon timer hasn't fired) - the
     * room must stay alive so reconnection and the pending abandon still
     * work.
     */
    public boolean isRetirable() {
        synchronized (lock) {
            if (whiteConnection != null || blackConnection != null) return false;
            return !gameStarted || engine.isGameOver();
        }
    }

    /** Stops this room's ticker for good - called once by Lobby exactly when {@link #isRetirable()} turns true. Safe to call even if the ticker never actually started. */
    public void shutdown() {
        scheduler.shutdown();
    }

    private void startTicker() {
        scheduler.scheduleAtFixedRate(this::tick, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS);
    }

    // Runs every TICK_MS on the scheduler thread. Calls engine.advanceTime (skipped while isPausedForDisconnect()), then resolvePendingActions, then broadcastSnapshot.
    private void tick() {
        // This runs on a java.util.concurrent.ScheduledExecutorService via
        // scheduleAtFixedRate - an uncaught exception here would silently
        // cancel all future ticks for this room (the executor just stops
        // rescheduling), freezing the game with no error visible to anyone.
        try {
            synchronized (lock) {
                if (!isPausedForDisconnect()) {
                    engine.advanceTime(TICK_MS);
                }
            }
            resolvePendingActions();
            broadcastSnapshot();
        } catch (RuntimeException e) {
            activityLog.log("room=" + roomCode + " tick() failed unexpectedly: " + e);
        }
    }

    /**
     * Handle one "click row col" / "jump row col" command - parse, then
     * identity, then role, then act, matching the CTD brief's own pipeline
     * (parse -&gt; identity -&gt; role -&gt; validation -&gt; publish). A validly-
     * processed command gets {@code COMMAND_RESULT|SUCCESS} back (regardless
     * of its effect - a mere selection change and a real move both count),
     * a rejected one gets {@code ERROR|<reason>} instead; an unrecognized
     * connection (seat == null) gets neither, since that should never
     * happen given how KungFuChessServerService routes messages.
     */
    public void handleCommand(OutboundConnection connection, String command) {
        // No per-call entry/exit log here on purpose - a fast real-time game
        // can generate a click/jump every few hundred milliseconds, and that
        // volume of log lines would drown out the actually-significant
        // events (join/disconnect/reconnect/rematch/game-over) below. The
        // try/catch is still worth it: it's the outermost boundary that runs
        // per network message, so nothing thrown here should ever crash the
        // connection's read loop or leave the client without any reply.
        try {
            doHandleCommand(connection, command);
        } catch (RuntimeException e) {
            activityLog.log("room=" + roomCode + " handleCommand(\"" + command + "\") failed unexpectedly: " + e);
            send(connection, Protocol.ERROR + "|" + ERROR_INTERNAL);
        }
    }

    private void doHandleCommand(OutboundConnection connection, String command) {
        String[] parts = command.trim().split("\\s+");

        if (parts.length >= 1 && parts[0].equals(Protocol.REMATCH)) {
            handleRematchCommand(connection);
            return;
        }

        Integer row = parts.length >= 2 ? tryParseInt(parts[1]) : null;
        Integer col = parts.length >= 3 ? tryParseInt(parts[2]) : null;
        if (parts.length < 3 || !isKnownVerb(parts[0]) || row == null || col == null) {
            send(connection, Protocol.ERROR + "|" + ERROR_MALFORMED_COMMAND);
            return;
        }

        Seat seat = seatOf(connection);
        if (seat == null) return;
        if (!seat.isPlayer()) {
            send(connection, Protocol.ERROR + "|" + ERROR_VIEWER_CANNOT_PLAY);
            return;
        }
        Piece.Color color = seat.toColor();

        // The disconnect/pause check happens inside handleClick/handleJump
        // themselves, atomically with the actual engine access (same lock,
        // same critical section) - checking it here first and acting on it
        // separately below would leave a window where a disconnect lands in
        // between the check and the action.
        boolean actionTaken;
        if (parts[0].equals("click")) {
            ClickAttempt attempt = handleClick(color, row, col);
            if (attempt.paused) {
                send(connection, Protocol.ERROR + "|" + ERROR_GAME_PAUSED);
                actionTaken = false;
            } else if (attempt.outcome == ClickSelector.Outcome.MOVE_REJECTED) {
                send(connection, Protocol.ERROR + "|" + ERROR_ILLEGAL_MOVE);
                actionTaken = false;
            } else {
                send(connection, Protocol.COMMAND_RESULT + "|SUCCESS");
                if (attempt.outcome == ClickSelector.Outcome.MOVE_ACCEPTED) {
                    broadcastEvent("MOVE_ACCEPTED", color, attempt.from, attempt.to);
                }
                actionTaken = true;
            }
        } else {
            JumpAttempt attempt = handleJump(color, row, col);
            if (attempt.paused) {
                send(connection, Protocol.ERROR + "|" + ERROR_GAME_PAUSED);
                actionTaken = false;
            } else if (!attempt.owns) {
                send(connection, Protocol.ERROR + "|" + ERROR_NOT_YOUR_PIECE);
                actionTaken = false;
            } else {
                send(connection, Protocol.COMMAND_RESULT + "|SUCCESS"); // owning the piece is all that's required - the jump always actually starts, see GameEngine.requestJump
                if (attempt.started) broadcastEvent("JUMP_ACCEPTED", color, attempt.pos, null);
                actionTaken = true;
            }
        }
        if (actionTaken) broadcastSnapshot();
    }

    private static boolean isKnownVerb(String verb) {
        return verb.equals("click") || verb.equals("jump");
    }

    private static Integer tryParseInt(String token) {
        try {
            return Integer.parseInt(token);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * "rematch" - either seated player (not a viewer) may request a fresh
     * board once the current game is over. Rejected while a viewer sent it,
     * while the game isn't actually over yet, or while the other seat is
     * currently vacated by a disconnect (nobody to fairly restart against).
     */
    private void handleRematchCommand(OutboundConnection connection) {
        Seat seat = seatOf(connection);
        if (seat == null) return;
        if (!seat.isPlayer()) {
            send(connection, Protocol.ERROR + "|" + ERROR_VIEWER_CANNOT_PLAY);
            return;
        }
        RematchOutcome outcome = attemptRematch();
        switch (outcome) {
            case PAUSED -> send(connection, Protocol.ERROR + "|" + ERROR_GAME_PAUSED);
            case NOT_OVER -> send(connection, Protocol.ERROR + "|" + ERROR_GAME_NOT_OVER);
            case SUCCESS -> {
                send(connection, Protocol.COMMAND_RESULT + "|SUCCESS");
                activityLog.log("room=" + roomCode + " rematch requested by " + seat);
                broadcastSnapshot();
            }
        }
    }

    private enum RematchOutcome { SUCCESS, PAUSED, NOT_OVER }

    /**
     * Replaces engine with a fresh standard board, if the current game is
     * actually over and neither seat is currently vacated by a disconnect -
     * the paused/game-over check and the engine swap happen inside one lock
     * acquisition each (with the rating re-fetch in between - see below) so
     * a disconnect landing in between can't sneak a rematch through against
     * a defenseless opponent.
     *
     * whiteRating/blackRating are re-read from the database in between the
     * two checks, before actually committing to the rematch: they're a
     * point-in-time cache taken once at {@link #join}, and the previous
     * game's rating change already landed in the database by now (applyRatingChangeIfGameJustEnded
     * runs synchronously inside the very broadcastSnapshot() call that first
     * reported gameOver()) - without this re-fetch, every game after the
     * first one in the same room would score ELO against an increasingly
     * stale baseline and silently overwrite the database with the wrong
     * numbers (see UserRepository.updateRating - an unconditional SET, not
     * an increment). Done outside the lock since it's a blocking DB call,
     * matching applyRatingChangeIfGameJustEnded's own reasoning for keeping
     * slow I/O off this room's lock.
     */
    private RematchOutcome attemptRematch() {
        String whiteUser, blackUser;
        synchronized (lock) {
            if (isPausedForDisconnect()) return RematchOutcome.PAUSED;
            if (!engine.isGameOver()) return RematchOutcome.NOT_OVER;
            whiteUser = whiteUsername;
            blackUser = blackUsername;
        }

        int freshWhiteRating = userRepository.currentRating(whiteUser, whiteRating);
        int freshBlackRating = userRepository.currentRating(blackUser, blackRating);

        synchronized (lock) {
            if (isPausedForDisconnect()) return RematchOutcome.PAUSED;
            if (!engine.isGameOver()) return RematchOutcome.NOT_OVER;
            engine = newStandardGameEngine();
            engine.setPlayerNames(whiteUsername, blackUsername);
            whiteRating = freshWhiteRating;
            blackRating = freshBlackRating;
            whiteSelection = null;
            blackSelection = null;
            pendingActions.clear();
            ratingApplied = false;
            return RematchOutcome.SUCCESS;
        }
    }

    /**
     * True once both seats are filled with real players and one of them is
     * currently disconnected - clicks/jumps/rematch are rejected and the
     * clock is frozen until they reconnect or the abandon timer fires.
     * Always called from within a block already synchronized on {@link
     * #lock} - it reads the same fields every other gate/engine access does.
     */
    private boolean isPausedForDisconnect() {
        return whiteUsername != null && blackUsername != null
                && (whiteConnection == null || blackConnection == null);
    }

    /** Result of one click attempt - see {@link #handleClick}. */
    private static final class ClickAttempt {
        final boolean paused;
        final ClickSelector.Outcome outcome;
        final Position from;
        final Position to;

        ClickAttempt(boolean paused, ClickSelector.Outcome outcome, Position from, Position to) {
            this.paused = paused;
            this.outcome = outcome;
            this.from = from;
            this.to = to;
        }
    }

    /**
     * Same click rules as event.EventEngine, via the shared ClickSelector -
     * just keyed to this one color's selection. The pause check, the click
     * itself, and recording a {@link PendingAction} for an accepted move all
     * happen inside one {@code synchronized(lock)} block, so a disconnect
     * can't land in the middle of processing a click that was already judged
     * to be allowed. {@code EVENT|MOVE_ACCEPTED} is broadcast by the caller,
     * after the lock is released.
     */
    private ClickAttempt handleClick(Piece.Color color, int row, int col) {
        Position to = new Position(row, col);
        synchronized (lock) {
            if (isPausedForDisconnect()) {
                return new ClickAttempt(true, null, null, null);
            }
            Position previousSelection = getSelection(color);
            ClickSelector.Result result = ClickSelector.handleClick(engine, previousSelection, row, col, color);
            setSelection(color, result.selection());
            if (result.outcome() == ClickSelector.Outcome.MOVE_ACCEPTED) {
                pendingActions.add(new PendingAction(ActionKind.MOVE, color, previousSelection, to));
            }
            return new ClickAttempt(false, result.outcome(), previousSelection, to);
        }
    }

    /** Result of one jump attempt - see {@link #handleJump}. */
    private static final class JumpAttempt {
        final boolean paused;
        final boolean owns;
        final boolean started;
        final Position pos;

        JumpAttempt(boolean paused, boolean owns, boolean started, Position pos) {
            this.paused = paused;
            this.owns = owns;
            this.started = started;
            this.pos = pos;
        }
    }

    /**
     * The pause check, ownership check, and the jump request itself all
     * happen inside one {@code synchronized(lock)} block - see {@link
     * #handleClick} for why. {@code owns} is false only if the piece at
     * (row, col) doesn't belong to {@code color} (no command was attempted
     * at all); {@code started} covers both a jump that actually started
     * (watched for {@code JUMP_COMPLETED}) and one that was too late and
     * captured the piece instead - both are valid, non-error outcomes of an
     * owned command.
     */
    private JumpAttempt handleJump(Piece.Color color, int row, int col) {
        Position pos = new Position(row, col);
        synchronized (lock) {
            if (isPausedForDisconnect()) {
                return new JumpAttempt(true, false, false, pos);
            }
            boolean owns = ownsPieceAt(color, row, col);
            boolean started = false;
            if (owns) {
                started = engine.requestJump(row, col);
                if (started) pendingActions.add(new PendingAction(ActionKind.JUMP, color, pos, pos));
            }
            return new JumpAttempt(false, owns, started, pos);
        }
    }

    /**
     * Resolves every watched move/jump by polling existing public
     * GameEngine queries (no new hook into RealTimeArbiter - see CLAUDE.md's
     * note on gameengine.GameEngine.forceResign/abandon being the deliberate
     * exceptions networking makes to that package). An action resolves once
     * its origin square is no longer "departing"
     * ({@code engine.isAlreadyMoving} goes false):
     *   - a JUMP always resolves COMPLETED - once a jump legitimately
     *     starts, RealTimeArbiter has no mechanism left to interrupt it
     *     (it's not a head-on-collision or defended-jump candidate while
     *     {@code isMoving()} is false).
     *   - a MOVE resolves COMPLETED if a same-color piece now sits at
     *     {@code to} (the exact type is deliberately not checked, so a
     *     pawn promoting to a queen still counts), otherwise INTERRUPTED
     *     (redirected short by stopShortOfContestedSquare, or captured
     *     mid-flight).
     * Known, accepted limitation: this is "color at destination", not
     * piece identity - if this exact move is captured mid-flight and an
     * unrelated same-color move happens to land on this move's original
     * destination on the very same tick, this reports COMPLETED instead of
     * INTERRUPTED. Narrow (needs same-tick + same-square coincidence); the
     * alternative is a second exception carved into RealTimeArbiter itself,
     * which is deliberately out of scope.
     */
    private void resolvePendingActions() {
        List<PendingAction> resolved = new ArrayList<>();
        synchronized (lock) {
            Iterator<PendingAction> it = pendingActions.iterator();
            while (it.hasNext()) {
                PendingAction action = it.next();
                if (engine.isAlreadyMoving(action.from.getRow(), action.from.getCol())) continue;
                it.remove();
                resolved.add(action);
            }
        }
        for (PendingAction action : resolved) {
            if (action.kind == ActionKind.JUMP) {
                broadcastEvent("JUMP_COMPLETED", action.color, action.from, null);
            } else {
                Piece atDestination;
                synchronized (lock) {
                    atDestination = engine.pieceAt(action.to.getRow(), action.to.getCol());
                }
                boolean completed = atDestination != null && atDestination.getColor() == action.color;
                broadcastEvent(completed ? "MOVE_COMPLETED" : "MOVE_INTERRUPTED", action.color, action.from, action.to);
            }
        }
    }

    /** Broadcasts one EVENT to White/Black/every viewer (same audience as STATE), and mirrors it onto the bus for future subscribers (see CLAUDE.md's note on sound/animation-trigger topics). */
    private void broadcastEvent(String eventName, Piece.Color color, Position from, Position to) {
        String fromSquare = MoveNotation.square(from, engine.getHeight());
        String message = to == null
                ? Protocol.EVENT + "|" + eventName + "|" + color + "|" + fromSquare
                : Protocol.EVENT + "|" + eventName + "|" + color + "|" + fromSquare + "|" + MoveNotation.square(to, engine.getHeight());
        broadcastAll(message);
        bus.publish("room." + roomCode + ".event", message);
    }

    private void broadcastAll(String message) {
        OutboundConnection white, black;
        synchronized (lock) {
            white = whiteConnection;
            black = blackConnection;
        }
        send(white, message);
        send(black, message);
        for (OutboundConnection viewer : viewers) send(viewer, message);
    }

    private boolean ownsPieceAt(Piece.Color color, int row, int col) {
        Piece piece = engine.pieceAt(row, col);
        return piece != null && piece.getColor() == color;
    }

    private Position getSelection(Piece.Color color) {
        return color == Piece.Color.WHITE ? whiteSelection : blackSelection;
    }

    private void setSelection(Piece.Color color, Position position) {
        if (color == Piece.Color.WHITE) whiteSelection = position; else blackSelection = position;
    }

    /**
     * A connection dropped. A seated player's seat is vacated and, if a real
     * two-player game was in progress, a one-shot auto-abandon timer starts
     * (see {@link #disconnectGraceMillis}) and the other seated player (plus
     * any viewers) is told about it via OPPONENT_DISCONNECTED - cancelled by
     * {@link #reconnect} if the same username comes back in time. Moves are
     * also rejected and the clock frozen for as long as this seat stays
     * vacated - see {@link #isPausedForDisconnect()}. A viewer is just
     * removed from the broadcast list (no reconnection concept for
     * spectators - rejoining the room via a fresh join_room is enough).
     */
    /**
     * Vacates connection's seat because it's voluntarily leaving an already-
     * finished game to start a new one elsewhere (see
     * Lobby.leaveFinishedSessionIfAny / GameServerShardService.checkoutForNewGame) -
     * deliberately NOT the same path as {@link #handleDisconnect}: this game
     * already ended with its real, correct result, so unlike a genuine
     * disconnect this must not schedule an auto-abandon (a stray abandon
     * firing later would be a no-op now anyway - see {@link
     * #scheduleAutoAbandon} - but there's no reason to schedule one at all)
     * or tell the other side/viewers a disconnect just happened, which
     * would be a false alarm for a departure that was never involuntary.
     * Whoever remains can never complete a rematch here afterward (there is
     * nobody left to rematch against), which is correct, not a bug.
     */
    public void leaveAfterGameOver(OutboundConnection connection) {
        synchronized (lock) {
            if (connection == whiteConnection) whiteConnection = null;
            else if (connection == blackConnection) blackConnection = null;
            else viewers.remove(connection);
        }
    }

    public void handleDisconnect(OutboundConnection connection) {
        Piece.Color disconnectedColor = null;
        synchronized (lock) {
            if (connection == whiteConnection) {
                whiteConnection = null;
                whiteAbandonTask = scheduleAutoAbandon(Piece.Color.WHITE);
                if (whiteAbandonTask != null) disconnectedColor = Piece.Color.WHITE;
            } else if (connection == blackConnection) {
                blackConnection = null;
                blackAbandonTask = scheduleAutoAbandon(Piece.Color.BLACK);
                if (blackAbandonTask != null) disconnectedColor = Piece.Color.BLACK;
            } else {
                viewers.remove(connection);
            }
        }
        // Scheduling the abandon task and assigning it to whiteAbandonTask/
        // blackAbandonTask above happen inside the same lock acquisition as
        // nulling out the connection field - a concurrent reconnect() (which
        // also synchronizes on lock) can now never observe the connection as
        // already null while the abandon task field is still unset, which is
        // exactly the window that used to let a just-cancelled abandon fire
        // anyway against an already-reconnected opponent.
        if (disconnectedColor != null) notifyOpponentOfDisconnect(disconnectedColor);
    }

    /** Tells the still-connected side (and any viewers) that {@code disconnectedColor} just dropped, and how long they have before the game is auto-abandoned. */
    private void notifyOpponentOfDisconnect(Piece.Color disconnectedColor) {
        OutboundConnection other;
        synchronized (lock) {
            other = disconnectedColor == Piece.Color.WHITE ? blackConnection : whiteConnection;
        }
        String message = Protocol.OPPONENT_DISCONNECTED + "|" + (disconnectGraceMillis / 1000);
        send(other, message);
        for (OutboundConnection viewer : viewers) send(viewer, message);
    }

    /** Tells the still-connected side (and any viewers) that {@code reconnectedColor} is back. */
    private void notifyOpponentOfReconnect(Piece.Color reconnectedColor) {
        OutboundConnection other;
        synchronized (lock) {
            other = reconnectedColor == Piece.Color.WHITE ? blackConnection : whiteConnection;
        }
        send(other, Protocol.OPPONENT_RECONNECTED);
        for (OutboundConnection viewer : viewers) send(viewer, Protocol.OPPONENT_RECONNECTED);
    }

    /**
     * Restores a previously-seated player who reconnects (identified by
     * username - the same username that was seated before) while their seat
     * is still vacated: cancels the pending auto-resign task and re-greets
     * them exactly like a fresh join (WELCOME + a fresh snapshot). Returns
     * false if {@code username} doesn't match a currently-vacated seat in
     * this session - it was never a player here, or that seat isn't
     * actually empty (e.g. already reconnected, or this is a stale/duplicate
     * attempt) - so the caller can fall back to normal lobby handling.
     * Deliberately still succeeds even once the game has already ended (a
     * disconnect-triggered abandon, most commonly) - reconnecting a player
     * who comes back is exactly what lets them request a rematch instead of
     * being stuck GAME_PAUSED forever; see
     * testEitherSeatedPlayerCanRequestARematchOnceTheGameIsOver. The seat
     * check-and-set happens inside the same lock {@link #handleDisconnect}
     * uses, so the two can never interleave.
     */
    public boolean reconnect(OutboundConnection connection, String username) {
        Seat seat = null;
        synchronized (lock) {
            if (username.equals(whiteUsername) && whiteConnection == null) {
                whiteConnection = connection;
                cancelAbandonTask(Piece.Color.WHITE);
                seat = Seat.WHITE;
            } else if (username.equals(blackUsername) && blackConnection == null) {
                blackConnection = connection;
                cancelAbandonTask(Piece.Color.BLACK);
                seat = Seat.BLACK;
            }
        }
        if (seat == null) return false;
        activityLog.log("room=" + roomCode + " " + username + " reconnected as " + seat);
        greet(connection, seat);
        notifyOpponentOfReconnect(seat.toColor());
        return true;
    }

    // Cancels a pending scheduleAutoAbandon task for one color, if any (called by reconnect).
    private void cancelAbandonTask(Piece.Color color) {
        if (color == Piece.Color.WHITE) {
            if (whiteAbandonTask != null) whiteAbandonTask.cancel(false);
            whiteAbandonTask = null;
        } else {
            if (blackAbandonTask != null) blackAbandonTask.cancel(false);
            blackAbandonTask = null;
        }
    }

    // Schedules a one-shot auto-abandon (game over, no winner) for disconnectedColor after disconnectGraceMillis; calls engine.abandon() when it fires. Cancelled by cancelAbandonTask on reconnect.
    private ScheduledFuture<?> scheduleAutoAbandon(Piece.Color disconnectedColor) {
        if (whiteUsername == null || blackUsername == null) return null; // never became a real 2-player game
        activityLog.log("room=" + roomCode + " " + disconnectedColor + " disconnected - auto-abandon in "
                + disconnectGraceMillis + "ms if not reconnected");
        return scheduler.schedule(() -> {
            try {
                // The game may have already ended for real (a legitimate king
                // capture/resignation) in the disconnectGraceMillis window between
                // this task being scheduled and it actually firing - GameState.abandon()
                // unconditionally overwrites winner with null, so calling it here
                // unconditionally would silently erase a genuine, already-decided
                // result the instant this stale timer goes off. Only ever abandon a
                // game that is still actually in progress.
                boolean abandonedNow;
                synchronized (lock) {
                    abandonedNow = !engine.isGameOver();
                    if (abandonedNow) engine.abandon();
                }
                if (abandonedNow) {
                    activityLog.log("room=" + roomCode + " game abandoned (disconnect timeout, no winner, no rating change)");
                    broadcastSnapshot();
                }
            } catch (RuntimeException e) {
                activityLog.log("room=" + roomCode + " auto-abandon task failed unexpectedly: " + e);
            }
        }, disconnectGraceMillis, TimeUnit.MILLISECONDS);
    }

    // Sends a fresh STATE to White, Black and every viewer; also publishes to bus and calls applyRatingChangeIfGameJustEnded.
    private void broadcastSnapshot() {
        // All three views are built inside one lock acquisition (not one
        // buildSnapshotFor/buildNeutralSnapshot call each, separately locked) so
        // they describe the exact same instant of engine state - otherwise the
        // ticker or a concurrently-handled click/jump could mutate the engine in
        // between two separate lock acquisitions, and White/Black/viewers could
        // each end up seeing a snapshot from a subtly different moment.
        GameSnapshot whiteView, blackView, neutralView;
        synchronized (lock) {
            whiteView = engine.buildSnapshot(getSelection(Piece.Color.WHITE));
            blackView = engine.buildSnapshot(getSelection(Piece.Color.BLACK));
            neutralView = viewers.isEmpty() ? null : engine.buildSnapshot(null);
        }

        String whiteMessage = encode(whiteView);
        OutboundConnection white, black;
        synchronized (lock) {
            white = whiteConnection;
            black = blackConnection;
        }
        send(white, whiteMessage);
        send(black, encode(blackView));

        if (neutralView != null) {
            String neutralMessage = encode(neutralView);
            for (OutboundConnection viewer : viewers) send(viewer, neutralMessage);
        }

        // Published as the already-encoded wire string, not the raw GameSnapshot
        // object - a real cross-process bus (bus.NatsBus) can only carry
        // bytes/text, not an in-JVM Java object, so this is what actually makes
        // the payload meaningfully transmittable once a real subscriber exists.
        bus.publish(snapshotTopic, whiteMessage);
        applyRatingChangeIfGameJustEnded(whiteView);
    }

    /** Builds the snapshot for one color: its own selection/legal-moves only, never the opponent's. */
    private GameSnapshot buildSnapshotFor(Piece.Color color) {
        synchronized (lock) {
            return engine.buildSnapshot(getSelection(color));
        }
    }

    /** Builds a spectator's snapshot: the live board, but never anyone's selection or legal-move markers. */
    private GameSnapshot buildNeutralSnapshot() {
        synchronized (lock) {
            return engine.buildSnapshot(null);
        }
    }

    private static String encode(GameSnapshot snapshot) {
        return Protocol.STATE + "\n" + SnapshotCodec.encode(snapshot);
    }

    /**
     * The first snapshot to report the game as over - with an actual winner -
     * updates both accounts' ratings via {@link RatingService} - guarded by
     * {@code ratingApplied} so a win is only ever scored once, no matter how
     * many more snapshots go out afterward. A game that ended with no winner
     * (abandoned after a disconnect timeout - see {@link #scheduleAutoAbandon})
     * intentionally skips this entirely: nobody's rating changes, since
     * neither side did anything to deserve a loss.
     *
     * {@code broadcastSnapshot()} can run concurrently from two different
     * threads (the ticker and a just-handled click/jump that ends the game
     * on the very same tick) - the check-and-set of {@code ratingApplied}
     * plus reading the two usernames/ratings all happen inside one {@code
     * synchronized(lock)} block so at most one of those concurrent calls
     * ever proceeds to actually apply the rating change/record the game;
     * the slower ELO/DB work itself runs outside the lock.
     */
    private void applyRatingChangeIfGameJustEnded(GameSnapshot snapshot) {
        if (!snapshot.gameOver() || snapshot.winner() == null) return;

        String whiteUser, blackUser;
        int whiteRatingBefore, blackRatingBefore;
        boolean whiteWon;
        synchronized (lock) {
            if (ratingApplied) return;
            if (whiteUsername == null || blackUsername == null) return;
            ratingApplied = true;
            whiteUser = whiteUsername;
            blackUser = blackUsername;
            whiteRatingBefore = whiteRating;
            blackRatingBefore = blackRating;
            whiteWon = "WHITE".equals(snapshot.winner());
        }

        String winnerName = whiteWon ? whiteUser : blackUser;
        String loserName = whiteWon ? blackUser : whiteUser;
        int winnerRating = whiteWon ? whiteRatingBefore : blackRatingBefore;
        int loserRating = whiteWon ? blackRatingBefore : whiteRatingBefore;

        RatingService.Outcome outcome = ratingService.applyGameEnd(winnerName, winnerRating, loserName, loserRating);
        activityLog.log("room=" + roomCode + " GAME_OVER " + winnerName + " (" + winnerRating + " -> " + outcome.newWinnerRating
                + ") beat " + loserName + " (" + loserRating + " -> " + outcome.newLoserRating + ")");

        int whiteRatingAfter = whiteWon ? outcome.newWinnerRating : outcome.newLoserRating;
        int blackRatingAfter = whiteWon ? outcome.newLoserRating : outcome.newWinnerRating;
        gameResultRepository.recordGame(roomCode, whiteUser, blackUser, winnerName,
                whiteRatingBefore, whiteRatingAfter, blackRatingBefore, blackRatingAfter,
                snapshot.whiteMoves(), snapshot.blackMoves());
    }

    private void send(OutboundConnection connection, String message) {
        if (connection != null && connection.isOpen()) {
            connection.send(message);
        }
    }
}
