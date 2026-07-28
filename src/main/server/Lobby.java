package server;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.java_websocket.WebSocket;

import bus.Bus;
import logging.ActivityLog;
import server.auth.UserRepository;

/**
 * The "tournament manager": opens rooms, matches waiting players by ELO, and
 * hands each WebSocket connection off to the right GameSession. Everything
 * about actually playing a game - including greeting a connection once it's
 * actually seated (WELCOME + board snapshot) - is delegated entirely to
 * GameSession/GameEngine; this class only ever decides WHICH session a
 * connection belongs to.
 */
public class Lobby {
    private static final int MATCHMAKING_ELO_RANGE = 100;
    private static final String ROOM_CODE_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final int ROOM_CODE_LENGTH = 6;
    private static final long DEFAULT_MATCHMAKING_TIMEOUT_MILLIS = 60_000L;
    private static final long DEFAULT_DISCONNECT_GRACE_MILLIS = 20_000L;

    private final Bus bus;
    private final UserRepository userRepository;
    private final ActivityLog activityLog;
    private final long matchmakingTimeoutMillis;
    private final long disconnectGraceMillis;
    private final SecureRandom random = new SecureRandom();
    private final ScheduledExecutorService matchmakingScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "lobby-matchmaking-timeout");
        t.setDaemon(true);
        return t;
    });

    private final Map<String, GameSession> rooms = new ConcurrentHashMap<>();
    private final Map<WebSocket, GameSession> sessionByConnection = new ConcurrentHashMap<>();
    private final Map<String, GameSession> sessionByUsername = new ConcurrentHashMap<>();
    private final List<Waiting> matchmakingQueue = new ArrayList<>();

    public Lobby(Bus bus, UserRepository userRepository, ActivityLog activityLog) {
        this(bus, userRepository, activityLog, DEFAULT_MATCHMAKING_TIMEOUT_MILLIS, DEFAULT_DISCONNECT_GRACE_MILLIS);
    }

    /**
     * Same as the 3-arg constructor, with the "give up waiting" timeout for
     * {@link #play} given explicitly (in milliseconds) instead of the
     * default 60 seconds - so tests can use a short delay instead of
     * actually waiting a minute.
     */
    public Lobby(Bus bus, UserRepository userRepository, ActivityLog activityLog, long matchmakingTimeoutMillis) {
        this(bus, userRepository, activityLog, matchmakingTimeoutMillis, DEFAULT_DISCONNECT_GRACE_MILLIS);
    }

    /**
     * Same as the 4-arg constructor, with the disconnect-&gt;abandon grace
     * period every GameSession this Lobby creates is given (see
     * GameSession's own same-named constructor param) also given explicitly
     * instead of the default 20 seconds - so tests can exercise the
     * abandon/leaveFinishedSessionIfAny flow without actually waiting.
     */
    public Lobby(Bus bus, UserRepository userRepository, ActivityLog activityLog,
                 long matchmakingTimeoutMillis, long disconnectGraceMillis) {
        this.bus = bus;
        this.userRepository = userRepository;
        this.activityLog = activityLog;
        this.matchmakingTimeoutMillis = matchmakingTimeoutMillis;
        this.disconnectGraceMillis = disconnectGraceMillis;
    }

    public GameSession sessionOf(WebSocket connection) {
        return sessionByConnection.get(connection);
    }

    /**
     * REST "create room" (server.HttpApiServer): mints a fresh room code and
     * registers an empty GameSession - no seat yet, since a REST call has no
     * live connection to seat. The creator (and anyone they share the code
     * with) actually gets seated the normal way, via joinRoom once their
     * WebSocket sends "join_room &lt;code&gt;" - whoever arrives first there
     * becomes White, so this deliberately doesn't special-case "the creator".
     */
    public String createRoom(String username) {
        String code = newRoomCode();
        GameSession session = new GameSession(bus, code, userRepository, activityLog, disconnectGraceMillis);
        rooms.put(code, session);
        activityLog.log("room=" + code + " reserved by " + username);
        return code;
    }

    /** "join_room <code>" - null if the code doesn't exist (caller sends an ERROR). */
    public GameSession joinRoom(String code, WebSocket connection, String username, int rating) {
        GameSession session = rooms.get(code);
        if (session == null) return null;
        session.join(connection, username, rating);
        sessionByConnection.put(connection, session);
        sessionByUsername.put(username, session);
        activityLog.log("room=" + code + " joined by " + username);
        return session;
    }

    /**
     * Called right after a successful login/attach, before any lobby
     * command: if {@code username} was seated (White/Black, never a
     * spectator - see GameSession.reconnect) in a session it has since been
     * disconnected from, restores that seat instead of making them start
     * over. Returns false (silently - the caller proceeds to the normal
     * play/join_room flow) if there's nothing to reconnect to. Deliberately
     * still reconnects even into an already-finished game (most commonly a
     * disconnect-triggered abandon) - see GameSession.reconnect - so a
     * player who comes back can request a rematch instead of finding
     * nothing there; see leaveFinishedSessionIfAny for the other half of
     * this: what happens once such a player instead asks for a genuinely
     * new game.
     */
    public boolean tryReconnect(WebSocket connection, String username) {
        GameSession session = sessionByUsername.get(username);
        if (session == null) return false;
        boolean reconnected = session.reconnect(connection, username);
        if (reconnected) {
            sessionByConnection.put(connection, session);
            activityLog.log("room=" + session.getRoomCode() + " " + username + " reconnected");
        }
        return reconnected;
    }

    /**
     * Called by KungFuChessServer when a connection sends "play" or
     * "join_room &lt;code&gt;" while still mapped (via tryReconnect, above)
     * to a session whose game has already ended - vacates that connection's
     * seat exactly like a real disconnect (GameSession.handleDisconnect)
     * and forgets the connection→session mapping, so the caller can then
     * fall through to normal play/join_room handling and actually reach a
     * new room, instead of every future command from this connection being
     * swallowed as an unrecognized command by the old (finished, rematch-
     * only) session. A no-op (returns false) if this connection isn't
     * currently attached to a finished session - e.g. an active game, or no
     * session at all - so the caller knows whether it actually needs to
     * detach anything.
     */
    public boolean leaveFinishedSessionIfAny(WebSocket connection) {
        GameSession session = sessionByConnection.get(connection);
        if (session == null || !session.isGameOver()) return false;
        session.handleDisconnect(connection);
        sessionByConnection.remove(connection);
        activityLog.log("room=" + session.getRoomCode() + " left (game already over) to start a new game");
        return true;
    }

    /**
     * "play": pairs with any currently-waiting player within +-100 ELO and
     * returns true (GameSession.join itself greets both sides - WELCOME +
     * initial snapshot - the moment the second one joins). If no one
     * waiting is close enough, this connection joins the queue and false is
     * returned (caller sends WAITING); if no opponent arrives within
     * {@link #matchmakingTimeoutMillis}, it's removed from the queue and
     * sent an explicit ERROR instead of waiting forever.
     */
    public synchronized boolean play(WebSocket connection, String username, int rating) {
        for (int i = 0; i < matchmakingQueue.size(); i++) {
            Waiting candidate = matchmakingQueue.get(i);
            if (Math.abs(candidate.rating - rating) <= MATCHMAKING_ELO_RANGE) {
                matchmakingQueue.remove(i);
                candidate.timeoutTask.cancel(false);

                String code = newRoomCode();
                GameSession session = new GameSession(bus, code, userRepository, activityLog, disconnectGraceMillis);
                rooms.put(code, session);

                session.join(candidate.connection, candidate.username, candidate.rating);
                session.join(connection, username, rating);
                sessionByConnection.put(candidate.connection, session);
                sessionByConnection.put(connection, session);
                sessionByUsername.put(candidate.username, session);
                sessionByUsername.put(username, session);

                activityLog.log("room=" + code + " quick-match: " + candidate.username + " vs " + username);
                return true;
            }
        }
        Waiting waiting = new Waiting(connection, username, rating);
        waiting.timeoutTask = matchmakingScheduler.schedule(() -> handleMatchmakingTimeout(waiting),
                matchmakingTimeoutMillis, TimeUnit.MILLISECONDS);
        matchmakingQueue.add(waiting);
        activityLog.log(username + " (" + rating + ") queued for quick-match");
        return false;
    }

    /** No opponent showed up within the timeout - drop the entry and tell the client clearly, instead of leaving them waiting forever. */
    private synchronized void handleMatchmakingTimeout(Waiting waiting) {
        if (!matchmakingQueue.remove(waiting)) return; // already matched (or already cancelled) in the meantime
        activityLog.log(waiting.username + " quick-match timed out after " + matchmakingTimeoutMillis + "ms");
        if (waiting.connection.isOpen()) {
            waiting.connection.send(Protocol.ERROR + "|no opponent found within "
                    + (matchmakingTimeoutMillis / 1000) + " seconds - try again");
        }
    }

    /** Removes a still-queued (not yet matched) connection - safe to call even if it was never queued. */
    public synchronized void cancelQueued(WebSocket connection) {
        matchmakingQueue.removeIf(w -> {
            if (w.connection != connection) return false;
            w.timeoutTask.cancel(false);
            return true;
        });
    }

    /** A connection dropped: forget it, and let its GameSession (if any) handle the forfeit/spectator-removal. */
    public void handleDisconnect(WebSocket connection) {
        cancelQueued(connection);
        GameSession session = sessionByConnection.remove(connection);
        if (session != null) session.handleDisconnect(connection);
    }

    // Generates a random unused 6-char room code (retries on collision).
    private String newRoomCode() {
        String code;
        do {
            StringBuilder sb = new StringBuilder(ROOM_CODE_LENGTH);
            for (int i = 0; i < ROOM_CODE_LENGTH; i++) {
                sb.append(ROOM_CODE_CHARS.charAt(random.nextInt(ROOM_CODE_CHARS.length())));
            }
            code = sb.toString();
        } while (rooms.containsKey(code));
        return code;
    }

    private static final class Waiting {
        final WebSocket connection;
        final String username;
        final int rating;
        ScheduledFuture<?> timeoutTask;

        Waiting(WebSocket connection, String username, int rating) {
            this.connection = connection;
            this.username = username;
            this.rating = rating;
        }
    }
}
