package net;

/**
 * Message prefixes for the client<->server text protocol. Board commands
 * ("click row col" / "jump row col") aren't listed here - they're parsed
 * directly by server.GameSession, which already knows the format.
 *
 * Client -> server commands stay space-delimited (unchanged from before).
 * Every server -> client tagged message with a payload is pipe-delimited
 * ("TAG|value" or "TAG|key=value"), matching the CTD 26 brief's own wire
 * examples - except WAITING (no payload) and STATE (a multi-line block,
 * not a simple tag, encoded separately by net.SnapshotCodec).
 */
public final class Protocol {
    private Protocol() {}

    /** Client -> server, first message on a new connection: {@code "login <username> <password>"}. */
    public static final String LOGIN = "login";

    /** Client -> server, first message for a new account: {@code "register <username> <password>"}. */
    public static final String REGISTER = "register";

    /** Server -> client, sent right after a successful login/register: {@code "AUTH_OK|<rating>"}. */
    public static final String AUTH_OK = "AUTH_OK";

    /** Client -> server, sent once authenticated: join the ELO-ranged matchmaking queue. */
    public static final String PLAY = "play";

    /** Client -> server, sent once authenticated: create a new room, seated as White. */
    public static final String CREATE_ROOM = "create_room";

    /** Client -> server, sent once authenticated: {@code "join_room <code>"} - seated Black, or a VIEWER if already full. */
    public static final String JOIN_ROOM = "join_room";

    /** Server -> client, still waiting for an opponent (quick-match or an empty room seat). No payload. */
    public static final String WAITING = "WAITING";

    /** Server -> client, reply to create_room: {@code "ROOM_CREATED|<code>"}. */
    public static final String ROOM_CREATED = "ROOM_CREATED";

    /** Server -> client, sent once seated in a game: {@code "WELCOME|role=WHITE"} / {@code "WELCOME|role=BLACK"} / {@code "WELCOME|role=VIEWER"}. */
    public static final String WELCOME = "WELCOME";

    /** Server -> client, a request was refused: {@code "ERROR|<reason>"}. In-game rejections use a SCREAMING_SNAKE_CASE code (e.g. NOT_YOUR_PIECE); auth/room/matchmaking reasons stay free text. */
    public static final String ERROR = "ERROR";

    /** Server -> client, a click/jump command passed validation: {@code "COMMAND_RESULT|SUCCESS"} - regardless of whether it caused a move/jump, a mere selection change, or a too-late jump capture. */
    public static final String COMMAND_RESULT = "COMMAND_RESULT";

    /**
     * Server -> client (broadcast to White/Black/every viewer, same audience
     * as STATE), a fact that already happened - distinct from COMMAND_RESULT,
     * which only ever tells the sender their own command was accepted:
     * {@code "EVENT|MOVE_ACCEPTED|color|fromSquare|toSquare"},
     * {@code "EVENT|MOVE_COMPLETED|..."}, {@code "EVENT|MOVE_INTERRUPTED|..."}
     * (redirected or captured mid-flight - see server.GameSession),
     * {@code "EVENT|JUMP_ACCEPTED|color|square"}, {@code "EVENT|JUMP_COMPLETED|..."}.
     */
    public static final String EVENT = "EVENT";

    /** Server -> client, one full board snapshot: {@code "STATE\n" + SnapshotCodec.encode(...)}. */
    public static final String STATE = "STATE";
}
