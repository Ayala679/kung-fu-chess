package server;

/**
 * Message prefixes for the client<->server protocol - both the WebSocket
 * text protocol and the small REST API (server.HttpApiServer) sitting next
 * to it. Board commands ("click row col" / "jump row col") aren't listed
 * here - they're parsed directly by server.GameSession, which already knows
 * the format.
 *
 * login/register/room-creation are non-realtime and go over REST now (see
 * HttpApiServer) - LOGIN/REGISTER below stay as the internal mode markers
 * reused when building the string passed into AuthController.handleAuth,
 * and AUTH_OK/ROOM_CREATED/ERROR are reused verbatim as HTTP response
 * bodies. Everything actually live (matchmaking wait, gameplay, state)
 * stays on the WebSocket: the connection's first message is now
 * "attach &lt;token&gt;" (a token minted by the REST login/register call)
 * instead of raw login/register text.
 *
 * Client -> server commands stay space-delimited (unchanged from before).
 * Every server -> client tagged message with a payload is pipe-delimited
 * ("TAG|value" or "TAG|key=value"), matching the CTD 26 brief's own wire
 * examples - except WAITING (no payload) and STATE (a multi-line block,
 * not a simple tag, encoded separately by SnapshotCodec).
 */
public final class Protocol {
    private Protocol() {}

    /** Internal auth mode marker (REST path suffix / AuthController.handleAuth prefix), not a WS verb any more. */
    public static final String LOGIN = "login";

    /** Internal auth mode marker (REST path suffix / AuthController.handleAuth prefix), not a WS verb any more. */
    public static final String REGISTER = "register";

    /** WS client -> server, first message on a new connection: {@code "attach <token>"} (token from the REST login/register call). */
    public static final String ATTACH = "attach";

    /** Reply to REST login/register, and to the WS attach handshake: {@code "AUTH_OK|<rating>"} (REST also appends the token: {@code "AUTH_OK|<token>|<rating>"}). */
    public static final String AUTH_OK = "AUTH_OK";

    /** Client -> server, sent once authenticated: join the ELO-ranged matchmaking queue. */
    public static final String PLAY = "play";

    /** REST client -> server, {@code POST /api/rooms} body {@code token=<token>}: mints a fresh room code, no seat yet. */
    public static final String CREATE_ROOM = "create_room";

    /** Client -> server, sent once authenticated: {@code "join_room <code>"} - seated White if the room is still empty, Black if one seat is taken, or a VIEWER if already full. */
    public static final String JOIN_ROOM = "join_room";

    /** Client -> server, sent by either seated player once the game is over: requests a fresh rematch in the same room. */
    public static final String REMATCH = "rematch";

    /** Server -> client, still waiting for an opponent (quick-match or an empty room seat). No payload. */
    public static final String WAITING = "WAITING";

    /** REST response body for {@code POST /api/rooms}: {@code "ROOM_CREATED|<code>"}. */
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

    /** Server -> client (to the still-connected side and any viewers), a seated player just disconnected: {@code "OPPONENT_DISCONNECTED|<graceSeconds>"}. */
    public static final String OPPONENT_DISCONNECTED = "OPPONENT_DISCONNECTED";

    /** Server -> client (to the still-connected side and any viewers), a previously-disconnected player reconnected in time. No payload. */
    public static final String OPPONENT_RECONNECTED = "OPPONENT_RECONNECTED";
}
