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
import net.Protocol;
import server.auth.UserRepository;

/**
 * The "tournament manager": opens rooms, matches waiting players by ELO, and
 * hands each WebSocket connection off to the right GameSession. Everything
 * about actually playing a game - including greeting a connection once it's
 * actually seated (SEAT + board snapshot) - is delegated entirely to
 * GameSession/GameEngine; this class only ever decides WHICH session a
 * connection belongs to.
 */
public class Lobby {
    private static final int MATCHMAKING_ELO_RANGE = 100;
    private static final String ROOM_CODE_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final int ROOM_CODE_LENGTH = 6;
    private static final long DEFAULT_MATCHMAKING_TIMEOUT_MILLIS = 60_000L;

    private final Bus bus;
    private final UserRepository userRepository;
    private final ActivityLog activityLog;
    private final long matchmakingTimeoutMillis;
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
        this(bus, userRepository, activityLog, DEFAULT_MATCHMAKING_TIMEOUT_MILLIS);
    }

    /**
     * Same as the 3-arg constructor, with the "give up waiting" timeout for
     * {@link #play} given explicitly (in milliseconds) instead of the
     * default 60 seconds - so tests can use a short delay instead of
     * actually waiting a minute.
     */
    public Lobby(Bus bus, UserRepository userRepository, ActivityLog activityLog, long matchmakingTimeoutMillis) {
        this.bus = bus;
        this.userRepository = userRepository;
        this.activityLog = activityLog;
        this.matchmakingTimeoutMillis = matchmakingTimeoutMillis;
    }

    public GameSession sessionOf(WebSocket connection) {
        return sessionByConnection.get(connection);
    }

    /** "create_room": a fresh room, this connection seated White. */
    public GameSession createRoom(WebSocket connection, String username, int rating) {
        String code = newRoomCode();
        GameSession session = new GameSession(bus, code, userRepository, activityLog);
        rooms.put(code, session);
        session.join(connection, username, rating);
        sessionByConnection.put(connection, session);
        sessionByUsername.put(username, session);
        activityLog.log("room=" + code + " created by " + username);
        return session;
    }

    /** "join_room <code>" - null if the code doesn't exist (caller sends an ERROR). */
    public GameSession joinRoom(String code, WebSocket connection, String username, int rating) {
        GameSession session = rooms.get(code);
        if (session == null) return null;
        session.join(connection, username, rating);
        sessionByConnection.put(connection, session);
        sessionByUsername.put(username, session);
        return session;
    }

    /**
     * Called right after a successful login/register, before any lobby
     * command: if {@code username} was seated (White/Black, never a
     * spectator - see GameSession.reconnect) in a session it has since been
     * disconnected from, restores that seat instead of making them start
     * over. Returns false (silently - the caller proceeds to the normal
     * play/create_room/join_room flow) if there's nothing to reconnect to.
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
     * "play": pairs with any currently-waiting player within +-100 ELO and
     * returns true (GameSession.join itself greets both sides - SEAT +
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
                GameSession session = new GameSession(bus, code, userRepository, activityLog);
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
