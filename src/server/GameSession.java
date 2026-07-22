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

import org.java_websocket.WebSocket;

import bus.Bus;
import event.ClickSelector;
import gameengine.GameEngine;
import logging.ActivityLog;
import model.Board;
import model.GameState;
import model.Piece;
import model.Position;
import net.Protocol;
import net.Seat;
import net.SnapshotCodec;
import parsing.BoardMapper;
import server.auth.EloCalculator;
import server.auth.UserRepository;
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
 * every other entry point drives them from a single thread. Java-WebSocket
 * dispatches connection callbacks on its own threads, concurrently with this
 * session's ticker/disconnect-timer thread, so every access to {@code
 * engine} (and to the two selection fields) is serialized through
 * {@code lock}.
 */
public class GameSession {
    private static final long TICK_MS = 16;
    private static final long DEFAULT_DISCONNECT_RESIGN_MILLIS = 20_000L;

    private static final String ERROR_VIEWER_CANNOT_PLAY = "VIEWER_CANNOT_PLAY";
    private static final String ERROR_NOT_YOUR_PIECE = "NOT_YOUR_PIECE";
    private static final String ERROR_MALFORMED_COMMAND = "MALFORMED_COMMAND";
    private static final String ERROR_ILLEGAL_MOVE = "ILLEGAL_MOVE";

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
    private final GameEngine engine;
    private final UserRepository userRepository;
    private final ActivityLog activityLog;
    private final long disconnectResignDelayMillis;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "game-session-" + Integer.toHexString(hashCode()));
        t.setDaemon(true);
        return t;
    });

    private final List<WebSocket> viewers = new CopyOnWriteArrayList<>();
    private final List<PendingAction> pendingActions = new ArrayList<>();

    private Position whiteSelection;
    private Position blackSelection;

    private WebSocket whiteConnection;
    private WebSocket blackConnection;
    private String whiteUsername;
    private String blackUsername;
    private int whiteRating;
    private int blackRating;
    private boolean ratingApplied = false;
    private ScheduledFuture<?> whiteResignTask;
    private ScheduledFuture<?> blackResignTask;

    public GameSession(Bus bus, String roomCode, UserRepository userRepository, ActivityLog activityLog) {
        this(bus, roomCode, userRepository, activityLog, DEFAULT_DISCONNECT_RESIGN_MILLIS);
    }

    /**
     * Same as the 4-arg constructor, with the disconnect-&gt;forfeit grace
     * period given explicitly instead of the default 20 seconds - so tests
     * can use a short, real (wall-clock) delay instead of actually waiting
     * 20 seconds for {@link #handleDisconnect}'s forfeit timer.
     */
    public GameSession(Bus bus, String roomCode, UserRepository userRepository, ActivityLog activityLog,
                        long disconnectResignDelayMillis) {
        this.bus = bus;
        this.roomCode = roomCode;
        this.snapshotTopic = "room." + roomCode + ".snapshot";
        this.userRepository = userRepository;
        this.activityLog = activityLog;
        this.disconnectResignDelayMillis = disconnectResignDelayMillis;
        Board board = BoardMapper.readBoard(new Scanner(STANDARD_BOARD));
        GameState gameState = new GameState();
        this.engine = new GameEngine(board, gameState);
    }

    public String getRoomCode() {
        return roomCode;
    }

    /** True once both a White and Black player are seated. */
    public synchronized boolean isFull() {
        return whiteConnection != null && blackConnection != null;
    }

    /**
     * Seats the first connection White, the second Black, and every one
     * after that as a read-only VIEWER. The White seat gets no greeting
     * (SEAT/STATE) yet - there's no game to show until an opponent
     * arrives - so its client just keeps showing "waiting for an
     * opponent...". Once Black joins, both are greeted together and the
     * game actually starts; a VIEWER joining an already-running game is
     * greeted immediately.
     */
    public synchronized Seat join(WebSocket connection, String username, int rating) {
        if (whiteConnection == null) {
            whiteConnection = connection;
            whiteUsername = username;
            whiteRating = rating;
            activityLog.log("room=" + roomCode + " " + username + " seated WHITE - waiting for an opponent");
            return Seat.WHITE;
        }
        if (blackConnection == null) {
            blackConnection = connection;
            blackUsername = username;
            blackRating = rating;
            engine.setPlayerNames(whiteUsername, blackUsername);
            startTicker();
            activityLog.log("room=" + roomCode + " " + username + " seated BLACK - game starting");
            greet(whiteConnection, Seat.WHITE);
            greet(blackConnection, Seat.BLACK);
            return Seat.BLACK;
        }
        viewers.add(connection);
        activityLog.log("room=" + roomCode + " " + username + " joined as VIEWER");
        greet(connection, Seat.VIEWER);
        return Seat.VIEWER;
    }

    private void greet(WebSocket connection, Seat seat) {
        send(connection, Protocol.WELCOME + "|role=" + seat);
        GameSnapshot snapshot = seat.isPlayer() ? buildSnapshotFor(seat.toColor()) : buildNeutralSnapshot();
        send(connection, encode(snapshot));
    }

    public Seat seatOf(WebSocket connection) {
        if (connection == whiteConnection) return Seat.WHITE;
        if (connection == blackConnection) return Seat.BLACK;
        if (viewers.contains(connection)) return Seat.VIEWER;
        return null;
    }

    private void startTicker() {
        scheduler.scheduleAtFixedRate(this::tick, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS);
    }

    private void tick() {
        synchronized (lock) {
            engine.advanceTime(TICK_MS);
        }
        resolvePendingActions();
        broadcastSnapshot();
    }

    /**
     * Handle one "click row col" / "jump row col" command - parse, then
     * identity, then role, then act, matching the CTD brief's own pipeline
     * (parse -&gt; identity -&gt; role -&gt; validation -&gt; publish). A validly-
     * processed command gets {@code COMMAND_RESULT|SUCCESS} back (regardless
     * of its effect - a mere selection change and a real move both count),
     * a rejected one gets {@code ERROR|<reason>} instead; an unrecognized
     * connection (seat == null) gets neither, since that should never
     * happen given how KungFuChessServer routes messages.
     */
    public void handleCommand(WebSocket connection, String command) {
        String[] parts = command.trim().split("\\s+");
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

        boolean actionTaken;
        if (parts[0].equals("click")) {
            ClickSelector.Outcome outcome = handleClick(color, row, col);
            if (outcome == ClickSelector.Outcome.MOVE_REJECTED) {
                send(connection, Protocol.ERROR + "|" + ERROR_ILLEGAL_MOVE);
                actionTaken = false;
            } else {
                send(connection, Protocol.COMMAND_RESULT + "|SUCCESS");
                actionTaken = true;
            }
        } else {
            boolean owns = handleJump(color, row, col);
            if (!owns) {
                send(connection, Protocol.ERROR + "|" + ERROR_NOT_YOUR_PIECE);
                actionTaken = false;
            } else {
                send(connection, Protocol.COMMAND_RESULT + "|SUCCESS"); // both "jump started" and "too late, captured" are valid, non-error outcomes
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
     * Same click rules as event.EventEngine, via the shared ClickSelector -
     * just keyed to this one color's selection. When the click resolved to
     * an actually-accepted move, records a {@link PendingAction} to watch
     * and immediately announces {@code EVENT|MOVE_ACCEPTED}.
     */
    private ClickSelector.Outcome handleClick(Piece.Color color, int row, int col) {
        Position previousSelection = getSelection(color);
        Position to = new Position(row, col);
        ClickSelector.Result result;
        synchronized (lock) {
            result = ClickSelector.handleClick(engine, previousSelection, row, col, color);
            setSelection(color, result.selection());
            if (result.outcome() == ClickSelector.Outcome.MOVE_ACCEPTED) {
                pendingActions.add(new PendingAction(ActionKind.MOVE, color, previousSelection, to));
            }
        }
        if (result.outcome() == ClickSelector.Outcome.MOVE_ACCEPTED) {
            broadcastEvent("MOVE_ACCEPTED", color, previousSelection, to);
        }
        return result.outcome();
    }

    /**
     * @return false if the piece at (row, col) doesn't belong to {@code color} (no command was attempted at all).
     * True covers both a jump that actually started (watched for {@code JUMP_COMPLETED}) and one that was too
     * late and captured the piece instead - both are valid, non-error outcomes of an owned command.
     */
    private boolean handleJump(Piece.Color color, int row, int col) {
        boolean owns;
        boolean started = false;
        Position pos = new Position(row, col);
        synchronized (lock) {
            owns = ownsPieceAt(color, row, col);
            if (owns) {
                started = engine.requestJump(row, col);
                if (started) pendingActions.add(new PendingAction(ActionKind.JUMP, color, pos, pos));
            }
        }
        if (started) broadcastEvent("JUMP_ACCEPTED", color, pos, null);
        return owns;
    }

    /**
     * Resolves every watched move/jump by polling existing public
     * GameEngine queries (no new hook into RealTimeArbiter - see CLAUDE.md's
     * note on gameengine.GameEngine.forceResign being the one deliberate
     * exception networking made to that package). An action resolves once
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
        send(whiteConnection, message);
        send(blackConnection, message);
        for (WebSocket viewer : viewers) send(viewer, message);
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
     * two-player game was in progress, a one-shot forfeit timer starts
     * (see {@link #disconnectResignDelayMillis}) - cancelled by
     * {@link #reconnect} if the same username comes back in time. A viewer
     * is just removed from the broadcast list (no reconnection concept for
     * spectators - rejoining the room via a fresh join_room is enough).
     */
    public void handleDisconnect(WebSocket connection) {
        if (connection == whiteConnection) {
            whiteConnection = null;
            whiteResignTask = scheduleForcedResign(Piece.Color.WHITE);
        } else if (connection == blackConnection) {
            blackConnection = null;
            blackResignTask = scheduleForcedResign(Piece.Color.BLACK);
        } else {
            viewers.remove(connection);
        }
    }

    /**
     * Restores a previously-seated player who reconnects (identified by
     * username - the same username that was seated before) while their seat
     * is still vacated: cancels the pending auto-resign task and re-greets
     * them exactly like a fresh join (SEAT + a fresh snapshot). Returns
     * false if {@code username} doesn't match a currently-vacated seat in
     * this session - it was never a player here, or that seat isn't
     * actually empty (e.g. already reconnected, or this is a stale/duplicate
     * attempt) - so the caller can fall back to normal lobby handling.
     */
    public synchronized boolean reconnect(WebSocket connection, String username) {
        if (username.equals(whiteUsername) && whiteConnection == null) {
            whiteConnection = connection;
            cancelResignTask(Piece.Color.WHITE);
            activityLog.log("room=" + roomCode + " " + username + " reconnected as WHITE");
            greet(connection, Seat.WHITE);
            return true;
        }
        if (username.equals(blackUsername) && blackConnection == null) {
            blackConnection = connection;
            cancelResignTask(Piece.Color.BLACK);
            activityLog.log("room=" + roomCode + " " + username + " reconnected as BLACK");
            greet(connection, Seat.BLACK);
            return true;
        }
        return false;
    }

    private void cancelResignTask(Piece.Color color) {
        if (color == Piece.Color.WHITE) {
            if (whiteResignTask != null) whiteResignTask.cancel(false);
            whiteResignTask = null;
        } else {
            if (blackResignTask != null) blackResignTask.cancel(false);
            blackResignTask = null;
        }
    }

    private ScheduledFuture<?> scheduleForcedResign(Piece.Color disconnectedColor) {
        if (whiteUsername == null || blackUsername == null) return null; // never became a real 2-player game
        activityLog.log("room=" + roomCode + " " + disconnectedColor + " disconnected - auto-resign in "
                + disconnectResignDelayMillis + "ms if not reconnected");
        return scheduler.schedule(() -> {
            synchronized (lock) {
                engine.forceResign(disconnectedColor);
            }
            activityLog.log("room=" + roomCode + " " + disconnectedColor + " auto-resigned (disconnect timeout)");
            broadcastSnapshot();
        }, disconnectResignDelayMillis, TimeUnit.MILLISECONDS);
    }

    private void broadcastSnapshot() {
        GameSnapshot whiteView = buildSnapshotFor(Piece.Color.WHITE);
        GameSnapshot blackView = buildSnapshotFor(Piece.Color.BLACK);
        send(whiteConnection, encode(whiteView));
        send(blackConnection, encode(blackView));

        if (!viewers.isEmpty()) {
            String neutralMessage = encode(buildNeutralSnapshot());
            for (WebSocket viewer : viewers) send(viewer, neutralMessage);
        }

        bus.publish(snapshotTopic, whiteView);
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
     * The first snapshot to report the game as over updates both accounts'
     * ratings via a standard ELO exchange (see server.auth.EloCalculator) and
     * persists them (see server.auth.UserRepository) - guarded by
     * {@code ratingApplied} so a win is only ever scored once, no matter how
     * many more snapshots go out afterward (including one from the
     * disconnect auto-resign timer).
     */
    private void applyRatingChangeIfGameJustEnded(GameSnapshot snapshot) {
        if (!snapshot.gameOver() || ratingApplied) return;
        if (whiteUsername == null || blackUsername == null) return;
        ratingApplied = true;

        boolean whiteWon = "WHITE".equals(snapshot.winner());
        String winnerName = whiteWon ? whiteUsername : blackUsername;
        String loserName = whiteWon ? blackUsername : whiteUsername;
        int winnerRating = whiteWon ? whiteRating : blackRating;
        int loserRating = whiteWon ? blackRating : whiteRating;

        int newWinnerRating = EloCalculator.winnerNewRating(winnerRating, loserRating);
        int newLoserRating = EloCalculator.loserNewRating(loserRating, winnerRating);
        userRepository.updateRating(winnerName, newWinnerRating);
        userRepository.updateRating(loserName, newLoserRating);
        activityLog.log("room=" + roomCode + " GAME_OVER " + winnerName + " (" + winnerRating + " -> " + newWinnerRating
                + ") beat " + loserName + " (" + loserRating + " -> " + newLoserRating + ")");
    }

    private void send(WebSocket connection, String message) {
        if (connection != null && connection.isOpen()) {
            connection.send(message);
        }
    }
}
