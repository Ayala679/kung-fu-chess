package server;

import java.util.List;
import java.util.Scanner;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
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
    private static final long DISCONNECT_RESIGN_SECONDS = 20;

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
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "game-session-" + Integer.toHexString(hashCode()));
        t.setDaemon(true);
        return t;
    });

    private final List<WebSocket> viewers = new CopyOnWriteArrayList<>();

    private Position whiteSelection;
    private Position blackSelection;

    private WebSocket whiteConnection;
    private WebSocket blackConnection;
    private String whiteUsername;
    private String blackUsername;
    private int whiteRating;
    private int blackRating;
    private boolean ratingApplied = false;

    public GameSession(Bus bus, String roomCode, UserRepository userRepository, ActivityLog activityLog) {
        this.bus = bus;
        this.roomCode = roomCode;
        this.snapshotTopic = "room." + roomCode + ".snapshot";
        this.userRepository = userRepository;
        this.activityLog = activityLog;
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
        send(connection, Protocol.SEAT + " " + seat);
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
        broadcastSnapshot();
    }

    /** Handle one "click row col" / "jump row col" command from a seated player - a viewer's command is a no-op. */
    public void handleCommand(WebSocket connection, String command) {
        Seat seat = seatOf(connection);
        if (seat == null || !seat.isPlayer()) return;
        Piece.Color color = seat.toColor();

        String[] parts = command.trim().split("\\s+");
        if (parts.length < 3) return;
        int row, col;
        try {
            row = Integer.parseInt(parts[1]);
            col = Integer.parseInt(parts[2]);
        } catch (NumberFormatException e) {
            return;
        }

        switch (parts[0]) {
            case "click": handleClick(color, row, col); break;
            case "jump": handleJump(color, row, col); break;
            default: return;
        }
        broadcastSnapshot();
    }

    /** Same click rules as event.EventEngine, via the shared ClickSelector - just keyed to this one color's selection. */
    private void handleClick(Piece.Color color, int row, int col) {
        synchronized (lock) {
            Position newSelection = ClickSelector.handleClick(engine, getSelection(color), row, col, color);
            setSelection(color, newSelection);
        }
    }

    private void handleJump(Piece.Color color, int row, int col) {
        synchronized (lock) {
            if (!ownsPieceAt(color, row, col)) return;
            engine.requestJump(row, col);
        }
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
     * two-player game was in progress, a one-shot 20s forfeit timer starts -
     * no reconnection support, matching this stage's scope. A viewer is just
     * removed from the broadcast list.
     */
    public void handleDisconnect(WebSocket connection) {
        if (connection == whiteConnection) {
            whiteConnection = null;
            scheduleForcedResign(Piece.Color.WHITE);
        } else if (connection == blackConnection) {
            blackConnection = null;
            scheduleForcedResign(Piece.Color.BLACK);
        } else {
            viewers.remove(connection);
        }
    }

    private void scheduleForcedResign(Piece.Color disconnectedColor) {
        if (whiteUsername == null || blackUsername == null) return; // never became a real 2-player game
        activityLog.log("room=" + roomCode + " " + disconnectedColor + " disconnected - auto-resign in "
                + DISCONNECT_RESIGN_SECONDS + "s if not resolved");
        scheduler.schedule(() -> {
            synchronized (lock) {
                engine.forceResign(disconnectedColor);
            }
            activityLog.log("room=" + roomCode + " " + disconnectedColor + " auto-resigned (disconnect timeout)");
            broadcastSnapshot();
        }, DISCONNECT_RESIGN_SECONDS, TimeUnit.SECONDS);
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
