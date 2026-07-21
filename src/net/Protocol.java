package net;

/**
 * Message prefixes for the client<->server text protocol. Board commands
 * ("click row col" / "jump row col") aren't listed here - they're parsed
 * directly by server.GameSession, which already knows the format.
 */
public final class Protocol {
    private Protocol() {}

    /** Client -> server, first message on a new connection: {@code "login <username> <password>"}. */
    public static final String LOGIN = "login";

    /** Client -> server, first message for a new account: {@code "register <username> <password>"}. */
    public static final String REGISTER = "register";

    /** Server -> client, sent right after a successful login/register: {@code "AUTH_OK <rating>"}. */
    public static final String AUTH_OK = "AUTH_OK";

    /** Client -> server, sent once authenticated: join the ELO-ranged matchmaking queue. */
    public static final String PLAY = "play";

    /** Client -> server, sent once authenticated: create a new room, seated as White. */
    public static final String CREATE_ROOM = "create_room";

    /** Client -> server, sent once authenticated: {@code "join_room <code>"} - seated Black, or a VIEWER if already full. */
    public static final String JOIN_ROOM = "join_room";

    /** Server -> client, still waiting for an opponent (quick-match or an empty room seat). */
    public static final String WAITING = "WAITING";

    /** Server -> client, reply to create_room: {@code "ROOM_CREATED <code>"}. */
    public static final String ROOM_CREATED = "ROOM_CREATED";

    /** Server -> client, sent once seated in a game: {@code "SEAT WHITE"} / {@code "SEAT BLACK"} / {@code "SEAT VIEWER"}. */
    public static final String SEAT = "SEAT";

    /** Server -> client, a request was refused (bad credentials, username taken, unknown room code): {@code "ERROR <reason>"}. */
    public static final String ERROR = "ERROR";

    /** Server -> client, one full board snapshot: {@code "STATE\n" + SnapshotCodec.encode(...)}. */
    public static final String STATE = "STATE";
}
