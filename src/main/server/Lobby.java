package server;

import java.security.SecureRandom;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import bus.Bus;
import logging.ActivityLog;
import server.auth.UserRepository;
import server.connection.OutboundConnection;
import server.reconnect.ReconnectRegistry;
import server.room.InMemoryRoomDirectory;
import server.room.RoomDirectory;

/**
 * The "tournament manager": opens rooms and hands each connection off to the
 * right GameSession. Everything about actually playing a game - including
 * greeting a connection once it's actually seated (WELCOME + board
 * snapshot) - is delegated entirely to GameSession/GameEngine; this class
 * only ever decides WHICH session a connection belongs to. Quick-match
 * pairing itself lives in the separate {@link MatchQueue}/{@code
 * server.MatchmakerController} - see {@link #seatMatchedPair} for the hand-off point.
 */
public class Lobby {
    private static final String ROOM_CODE_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final int ROOM_CODE_LENGTH = 6;
    private static final long DEFAULT_DISCONNECT_GRACE_MILLIS = 20_000L;

    private final Bus bus;
    private final UserRepository userRepository;
    private final ActivityLog activityLog;
    private final ReconnectRegistry reconnectRegistry;
    private final long disconnectGraceMillis;
    private final String shardId;
    private final RoomDirectory roomDirectory;
    private final SecureRandom random = new SecureRandom();

    private final Map<String, GameSession> rooms = new ConcurrentHashMap<>();
    private final Map<OutboundConnection, GameSession> sessionByConnection = new ConcurrentHashMap<>();

    public Lobby(Bus bus, UserRepository userRepository, ActivityLog activityLog, ReconnectRegistry reconnectRegistry) {
        this(bus, userRepository, activityLog, reconnectRegistry, DEFAULT_DISCONNECT_GRACE_MILLIS);
    }

    /**
     * Same as the 4-arg constructor, with the disconnect-&gt;abandon grace
     * period every GameSession this Lobby creates is given (see
     * GameSession's own same-named constructor param) given explicitly
     * instead of the default 20 seconds - so tests can exercise the
     * abandon/leaveFinishedSessionIfAny flow without actually waiting.
     */
    public Lobby(Bus bus, UserRepository userRepository, ActivityLog activityLog, ReconnectRegistry reconnectRegistry,
                 long disconnectGraceMillis) {
        this(bus, userRepository, activityLog, reconnectRegistry, disconnectGraceMillis, null, new InMemoryRoomDirectory());
    }

    /**
     * Same as the 5-arg constructor, with this Lobby's own shard identity
     * and the shared {@link RoomDirectory} given explicitly - used only by
     * the distributed topology (see server.GameServerShardController), where more
     * than one Game Server Shard process exists and every newly-minted room
     * code needs to be recorded (see {@link #createRoom}/{@link
     * #seatMatchedPair}) so the WS Gateway can later route a {@code
     * join_room} to the right one. {@code shardId} is null (and {@code
     * roomDirectory} an unused {@link InMemoryRoomDirectory}) in every other
     * case - local/offline runs and every unit test - where there's only
     * ever one implicit shard and nothing to route between.
     */
    public Lobby(Bus bus, UserRepository userRepository, ActivityLog activityLog, ReconnectRegistry reconnectRegistry,
                 long disconnectGraceMillis, String shardId, RoomDirectory roomDirectory) {
        this.bus = bus;
        this.userRepository = userRepository;
        this.activityLog = activityLog;
        this.reconnectRegistry = reconnectRegistry;
        this.disconnectGraceMillis = disconnectGraceMillis;
        this.shardId = shardId;
        this.roomDirectory = roomDirectory;
    }

    public GameSession sessionOf(OutboundConnection connection) {
        return sessionByConnection.get(connection);
    }

    /** How many rooms this Lobby is currently hosting a genuinely in-progress game for (excludes finished ones) - the load signal GameAllocatorService queries via GameServerShardService/Controller's "shard.&lt;id&gt;.load" (see Server_Design.md's "Not done yet"). */
    public int activeGameCount() {
        int count = 0;
        for (GameSession session : rooms.values()) {
            if (!session.isGameOver()) count++;
        }
        return count;
    }

    /** This Lobby's own shard identity (see the 7-arg constructor) - null outside the distributed, multi-shard topology. */
    public String getShardId() {
        return shardId;
    }

    // Records this shard as the owner of a freshly-minted room code (see RoomDirectory) - a no-op when there's only one implicit shard (shardId == null).
    private void recordShardOwnership(String code) {
        if (shardId != null) roomDirectory.record(code, shardId);
    }

    /**
     * REST "create room" (server.HttpApiServer): mints a fresh room code and
     * registers an empty GameSession - no seat yet, since a REST call has no
     * live connection to seat. The creator (and anyone they share the code
     * with) actually gets seated the normal way, via joinRoom once their
     * client sends "join_room &lt;code&gt;" - whoever arrives first there
     * becomes White, so this deliberately doesn't special-case "the creator".
     */
    public String createRoom(String username) {
        String code = newRoomCode();
        GameSession session = new GameSession(bus, code, userRepository, activityLog, disconnectGraceMillis);
        rooms.put(code, session);
        recordShardOwnership(code);
        activityLog.log("room=" + code + " reserved by " + username);
        return code;
    }

    /** "join_room <code>" - null if the code doesn't exist (caller sends an ERROR). */
    public GameSession joinRoom(String code, OutboundConnection connection, String username, int rating) {
        GameSession session = rooms.get(code);
        if (session == null) return null;
        session.join(connection, username, rating);
        sessionByConnection.put(connection, session);
        reconnectRegistry.record(username, code);
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
    public boolean tryReconnect(OutboundConnection connection, String username) {
        Optional<String> roomCode = reconnectRegistry.roomCodeFor(username);
        if (roomCode.isEmpty()) return false;
        GameSession session = rooms.get(roomCode.get());
        if (session == null) return false; // this process no longer knows the room (e.g. never actually loaded it) - nothing to reconnect to
        boolean reconnected = session.reconnect(connection, username);
        if (reconnected) {
            sessionByConnection.put(connection, session);
            activityLog.log("room=" + session.getRoomCode() + " " + username + " reconnected");
        }
        return reconnected;
    }

    /**
     * Called by KungFuChessServerService when a connection sends "play" or
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
    public boolean leaveFinishedSessionIfAny(OutboundConnection connection) {
        GameSession session = sessionByConnection.get(connection);
        if (session == null || !session.isGameOver()) return false;
        session.handleDisconnect(connection);
        sessionByConnection.remove(connection);
        activityLog.log("room=" + session.getRoomCode() + " left (game already over) to start a new game");
        return true;
    }

    /**
     * Seats two players {@link MatchQueue} just paired into a fresh room
     * (GameSession.join itself greets both sides - WELCOME + initial
     * snapshot - the moment the second one joins). This is exactly what
     * {@code Lobby.play}'s old "matched" branch used to do inline, before
     * quick-match pairing moved out into the separate {@link MatchQueue}/
     * {@code server.MatchmakerController} - called directly, in-process, by the
     * embedded topology's local MatchQueue listener, or by {@code
     * server.GameServerShardController} once it receives a {@link SeatPairEnvelope}
     * over NATS from the standalone Matchmaker process.
     */
    public void seatMatchedPair(OutboundConnection connectionA, String usernameA, int ratingA,
                                 OutboundConnection connectionB, String usernameB, int ratingB) {
        String code = newRoomCode();
        GameSession session = new GameSession(bus, code, userRepository, activityLog, disconnectGraceMillis);
        rooms.put(code, session);
        recordShardOwnership(code);

        session.join(connectionA, usernameA, ratingA);
        session.join(connectionB, usernameB, ratingB);
        sessionByConnection.put(connectionA, session);
        sessionByConnection.put(connectionB, session);
        reconnectRegistry.record(usernameA, code);
        reconnectRegistry.record(usernameB, code);

        activityLog.log("room=" + code + " quick-match: " + usernameA + " vs " + usernameB);
    }

    /** A connection dropped: forget it, and let its GameSession (if any) handle the forfeit/spectator-removal. Matchmaking-queue membership (if any) is a separate concern - see MatchQueue.cancelQueued, called independently by whoever owns that connection's MatchQueue. */
    public void handleDisconnect(OutboundConnection connection) {
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
}
