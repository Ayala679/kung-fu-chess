# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A Java implementation of **Kung Fu Chess**: a real-time chess variant with no
turns. Both sides can move at any moment, every move takes real time to
travel across the board (not an instant hop), and a piece can **jump** in
place to dodge an incoming capture in a genuine timing race. See
[README.md](README.md) for the full feature/rules writeup.

There is no Maven/Gradle - the project is built and tested with plain
`javac`/`java` invocations and on-demand-downloaded jars (test tooling into
`tools/`, runtime dependencies into `lib/` - both gitignored, both fetched
by a `tools/*.ps1` script on first use).

## Commands

Fetch runtime dependencies once (Java-WebSocket + its slf4j-api dependency,
and sqlite-jdbc - used by the `server`/`net` packages):
```powershell
powershell -File tools/fetch-libs.ps1
```

Build (from repo root - the classpath is needed even for offline-only work,
since `sources.txt` compiles the whole project, networking code included,
in one `javac` invocation):
```bash
cd src
javac -encoding UTF-8 -cp "../lib/Java-WebSocket-1.5.6.jar;../lib/slf4j-api-2.0.13.jar;../lib/sqlite-jdbc-3.46.1.3.jar" -d ../out @sources.txt
cd ..
```
`sources.txt` (in `src/`) is the authoritative file list for `javac`; it
excludes `src/tests/`. Add new non-test classes to it when creating files.

Run the graphical version (opens an interactive Swing window; mouse-driven,
right-click to jump):
```bash
java -cp out GuiMain < resources/demo_board_8x8.txt
```

Run the headless/console version (reads a board + a fixed command list from
stdin, prints the board after each command - this is what the grading/test
fixtures under `src/tests/test_*.txt` drive):
```bash
java -cp out Main < input.txt
```

Run the networked server (port 8887 by default, accounts in
`data/kungfuchess.db`, activity log at `logs/server.log`) and connect to it
with the networked GUI client (its own log at `logs/client-<username>.log`)
- the client shows a Login/Register alert, then a Quick Play/Room alert,
before anything else opens; see README.md for the full walkthrough:
```bash
java -cp "out;lib/Java-WebSocket-1.5.6.jar;lib/slf4j-api-2.0.13.jar;lib/sqlite-jdbc-3.46.1.3.jar" server.KungFuChessServer
java -cp "out;lib/Java-WebSocket-1.5.6.jar;lib/slf4j-api-2.0.13.jar;lib/sqlite-jdbc-3.46.1.3.jar" NetworkGuiMain
```

Run the full test suite + JaCoCo coverage report in one step (downloads
JUnit console launcher + JaCoCo into `tools/` on first run, not committed):
```powershell
powershell -File tools/run-tests.ps1
```
Report opens at `out/coverage-html/index.html`. Test classes live flat in
`src/tests/` (no subpackage), compiled to `out/test-classes` against
`out/classes` + the JUnit jar.

Run a single test class directly (after `tools/run-tests.ps1` has been run
at least once, so the jars exist in `tools/`):
```powershell
java -cp "out/classes;out/test-classes;tools/junit-console.jar" org.junit.platform.console.ConsoleLauncher --select-class=tests.RealTimeArbiterTest --details=tree
```

## Architecture

Strict layering, one responsibility per class; the command/event flow is:

```
stdin ─▶ BoardController ─▶ EventDispatcher ─▶ EventEngine ─▶ GameEngine
                                                                 │
                                        RuleEngine (MoveValidator + PieceRules)
                                                                 │
                                                          RealTimeArbiter ─▶ Board
                                                                 │
                                              BoardRenderer / ImgRenderer ─▶ output
```

- **model/** - pure data, no logic (`Board`, `Piece`, `Position`,
  `MovingPiece`, `GameState`). `Piece` carries its own rest durations and
  `moveDuration()`/`materialValue()`/`promotedAt()` - not a shared lookup
  table, so a future piece type can override any of these independently.
- **parsing/** - text ⇄ `Board`. `PieceMapper` is the *only* place that knows
  the `"wK"`-style token format; nothing else should encode/decode tokens.
- **ruleengine/** - `PieceRules.isValid(...)` is a single `switch` on
  `Piece.Type` for movement geometry (adding a piece = adding one `case`);
  `MoveValidator` handles the type-agnostic checks (occupancy, path
  clearing). Neither one mutates anything or knows about time/scheduling.
- **gameengine/** - the real engine:
  - `GameEngine` is the single gateway for every action (`requestMove`,
    `requestJump`, `advanceTime`, `printBoard`, `buildSnapshot`,
    `forceResign` - a resignation, ending the game exactly like a king
    capture does via `GameState.setGameOver`; the one deliberate touch the
    networking layer made to this package, used for the disconnect
    auto-resign timeout - see "Networking" below). It asks
    `RuleEngine` for legality, then hands timing/scheduling to
    `RealTimeArbiter`. It also owns move history (per-color `MoveLogEntry`
    lists) and `legalDestinationsFrom` (used to highlight legal moves in the
    GUI - runs the exact same gates as `requestMove` without starting one).
  - `RealTimeArbiter` owns **all** time-based state: pieces in transit,
    resting pieces, capture scoring, and the virtual clock. This is where
    the "Kung Fu" mechanics actually live - jump-vs-slide dodge races,
    head-on collisions between opposite-color slides, same-color/knight
    square-contention (redirect to stop one cell short via
    `stopShortOfContestedSquare`), and atomic arrival application in
    `update()`. Read this class in full before touching any timing/capture/
    jump behavior - the ordering of collision resolution vs. arrival vs.
    contention inside `update()` is load-bearing and each branch has a
    comment explaining a specific edge case it exists for.
    - **Jump-vs-slide dodge race**: a jump only defends if it's still
      genuinely airborne - not yet landed - at the exact moment the
      incoming enemy slide arrives; landing back down *onto* the attacker
      afterward is what captures it (`isTooLateToJump` gates this at
      request time - too late means the jump would already be over before
      the slide gets there, so it's rejected outright with an immediate
      capture instead of animating a pointless jump; `isProtectedByAnInProgressJump`
      re-checks it at the attacker's actual arrival, since real time
      advances in small increments and a jump legitimately still in the
      air a moment ago may have already landed by the tick that matters -
      ties go to the defender in both places). `update()` resolves every
      arrival in one single, strictly chronological pass (ties: slides
      before jump landings, so a jump landing at the *same* instant an
      attacker arrives can still catch it) - an attacker landing on a
      still-airborne defender's square doesn't capture anyone yet, it just
      occupies the square (the defender "isn't really there"); the jump's
      own landing (`resolveJumpLanding`) then simply reads real board state
      to see who's standing there and captures accordingly. No cross-tick
      bookkeeping is needed for any of this - real, persistent board state
      already carries the answer, which is why the mechanic works
      identically whether both arrivals fall in one `update()` call or
      across many separate real-time ticks.
  - Tests never sleep: time is pushed forward explicitly (`advanceTime` /
    `advance`), which is what makes the real-time logic deterministically
    testable.
- **event/** - input side. `EventEngine` owns *where* the current selection
  is stored (one field) so `GameEngine` stays free of any notion of UI
  selection; the actual click rules (select / re-select / cancel / move-
  request - see `ClickSelector`'s class comment) live in
  `event.ClickSelector`, a pure function of (engine, current selection,
  click, optional required color) → next selection. `server.GameSession`
  calls the same function (once per color) instead of re-implementing the
  rules - see "Networking" below for why that used to be a real duplicate
  and isn't anymore. `EventDispatcher` + `EventMapper` turn a command string (`click x y`,
  `jump x y`, `wait ms`, `print board`) into a `GameEvent`.
- **snapshot/** - immutable, render-ready description of "the board right
  now" (`GameSnapshot`, built by `SnapshotBuilder` from the live model:
  pieces + animation state, scores, move history, selection, legal-move
  highlights). The record's compact constructor `List.copyOf`s every list
  field (`pieces`, `whiteMoves`, `blackMoves`, `legalDestinations`), so the
  "immutable" guarantee holds regardless of what a caller does with its own
  reference afterward - `SnapshotBuilder` no longer needs to wrap anything
  itself. Both `BoardRenderer` (text) and `ImgRenderer` (graphical) render
  from this, not from the model directly.
- **view/** - `BoardWindow` is the actual interactive Swing window: a
  `JPanel` repainting the latest `ImgRenderer` frame, a `Timer` feeding real
  elapsed ms into `EventEngine.waitFor` (so animation runs on its own
  without needing typed `wait` commands), and a mouse handler that
  left-clicks (`handleClick`) or right-clicks (`handleJump`, in-place
  dodge). `ImgRenderer` composites onto `resources/dashboard.png` (board +
  move tables + score) using only `Img`'s own tiny API (`read`/`drawOn`/
  `putText`) - no direct AWT calls outside `Img`/`BoardWindow`.
- **controller/** - `BoardController.readFrom(Scanner)` wires the whole
  chain for a session; `executeCommand(String)` is the one entry point both
  `Main` (console) and tests drive commands through.
- **config/GameConfig** - every constant (durations, cell pixel size, token
  regexes, board dimensions). `MOVE_DURATION_PER_CELL` is the base unit;
  other durations keep a fixed ratio to it (see comments in the file before
  changing any of them - several are intentionally *not* equal to what
  you'd guess, e.g. `JUMP_DURATION` is deliberately shorter than a one-cell
  move so a dodge can ever mathematically succeed).

Three entry points: `Main` (console/Scanner, headless, what tests drive),
`GuiMain` (opens the graphical `BoardWindow` against a local engine), and
`NetworkGuiMain` (opens the same `BoardWindow` against a remote
`server.KungFuChessServer`) - kept deliberately separate so `Main` stays a
single `public static void main` for graders/tools that auto-detect the
entry point.

## Networking (bus/, net/, server/, logging/)

Added on top of the layers above with **one deliberate exception**
(`GameEngine.forceResign`, above) - a networked game is driven by the exact
same `GameEngine` a local session uses; only what sits *around* it differs.

- **event/GameClient** - the interface `view.BoardWindow` actually depends
  on (`handleClick`/`handleJump`/`waitFor`/`snapshot`). `EventEngine`
  implements it for local play; `net.NetworkGameClient` implements it for
  networked play. `BoardWindow` itself has no idea which one it has.
- **net/** - the wire protocol, compiled as regular source alongside
  everything else (no separate client/server module - this project has no
  build tool to make that split meaningful).
  - `Protocol` - the message prefixes specific to the network layer.
    Client→server commands (`login`/`register`, `play`/`create_room`/
    `join_room <code>`, `click row col`/`jump row col`) stay space-
    delimited. Every server→client *tagged* reply with a payload is
    pipe-delimited (`TAG|value`, matching the CTD 26 brief's own wire
    examples): `AUTH_OK|rating`, `ROOM_CREATED|code`,
    `WELCOME|role=WHITE` (greeting on being seated - the CTD brief's own
    name for this; `Seat` the Java enum is unchanged, only the wire text
    was renamed from the old `SEAT WHITE`), `ERROR|reason` (in-game
    rejections use a `SCREAMING_SNAKE_CASE` code - `NOT_YOUR_PIECE`,
    `VIEWER_CANNOT_PLAY`, `MALFORMED_COMMAND`, `ILLEGAL_MOVE` - matching
    the brief exactly; auth/room/matchmaking-timeout reasons stay free
    text). `WAITING` (no payload) and `STATE\n<block>` (a different
    multi-line shape, encoded by `SnapshotCodec`) are untouched. Board
    commands aren't parsed by `event.EventMapper` - `EventMapper`'s
    `click x y` is pixel-based (see `event.InputMapper`) for the
    stdin/console protocol, a different concern from the already-resolved
    board cell coordinates `BoardWindow`/`GameClient` deal in; `click row
    col`/`jump row col` are parsed directly by `server.GameSession`.
  - **Command vs. Event** (the CTD brief's own distinction): a `click`/
    `jump` is a *Command* - rejectable, and always answered with either
    `COMMAND_RESULT|SUCCESS` (parsed, regardless of its effect - a mere
    selection change and a real move both count) or `ERROR|<reason>`.
    An *Event* is a fact that already happened, broadcast to White/Black/
    every viewer (same audience as `STATE`, not just the sender):
    `EVENT|MOVE_ACCEPTED|color|fromSquare|toSquare` the instant a move
    starts, then later either `EVENT|MOVE_COMPLETED|...` (it genuinely
    landed) or `EVENT|MOVE_INTERRUPTED|...` (redirected short by
    `RealTimeArbiter.stopShortOfContestedSquare`, or captured mid-flight);
    `EVENT|JUMP_ACCEPTED|color|square` / `EVENT|JUMP_COMPLETED|...`
    similarly (a jump, once accepted, can never be interrupted - see
    `RealTimeArbiter`: it isn't a collision/defended-jump candidate while
    `isMoving()` is false, so there's no `JUMP_INTERRUPTED`). `GameSession`
    derives all of this by **polling existing public `GameEngine` queries**
    every tick (a `PendingAction` watch-list, resolved in
    `resolvePendingActions()`) rather than adding a new hook into
    `RealTimeArbiter` - consistent with `forceResign` being the *one*
    deliberate exception networking made to `gameengine/`. Two documented,
    accepted limitations of this polling approach (see the method's own
    Javadoc): it reads "same color at the destination square", not piece
    identity, so an unrelated same-color move landing on a captured move's
    exact original destination on the exact same tick is misreported as
    `COMPLETED` rather than `INTERRUPTED` (narrow - needs same-tick +
    same-square coincidence); and a move fully blocked one cell out
    produces `MOVE_ACCEPTED` immediately followed by `MOVE_INTERRUPTED`,
    often within the same tick (semantically correct, not a bug).
    `EVENT`s are *additional* to continuous `STATE` snapshots, not a
    replacement - `STATE`'s per-tick `progress`/`PieceVisualState` fields
    remain the only source for animation, since `view.ImgRenderer` has
    zero client-side elapsed-time tracking of its own and duplicating
    `RealTimeArbiter`'s timing math on the client was judged a regression,
    not an improvement, against this project's single-source-of-truth
    principle.
  - This required two small, additive changes below `net/`:
    `GameEngine.requestMove`/`requestJump` now return `boolean` (true iff
    the action actually started - `void` before), and
    `event.ClickSelector.handleClick` now returns a `Result(Position
    selection, Outcome outcome)` instead of a bare `Position` (`Outcome` is
    `NO_MOVE_ATTEMPTED`/`MOVE_ACCEPTED`/`MOVE_REJECTED`) so a caller that
    cares (`GameSession`) can tell a real move attempt from a mere select/
    deselect/reselect - `EventEngine`'s local-play call site just reads
    `.selection()` and ignores the rest, unchanged behavior.
  - `Seat` - `WHITE`/`BLACK`/`VIEWER`. Replaces raw `Piece.Color` as "what a
    connection was assigned" wherever a spectator is possible, since a
    viewer has no color (`Seat.toColor()` throws for `VIEWER` - callers
    check `isPlayer()` first).
  - `SnapshotCodec` - `GameSnapshot` ⇄ a plain-text block (one header line -
    including each side's display name, `-` when there isn't one, e.g.
    offline play - a legal-destinations line, two move-log lines, one line
    per `PieceSnapshot`). Plain text rather than JSON, matching the
    project's existing token/command conventions and avoiding another
    dependency. Pure functions - see `tests/SnapshotCodecTest.java` for the
    expected round-trip shape before changing the format.
  - `NetworkGameClient` (a `WebSocketClient`) - `register()`/`login()` block
    (via a `CountDownLatch`) until `AUTH_OK` or `ERROR`. What happens next
    (quick-match vs. room) is inherently asynchronous - the server may reply
    `WAITING` and only send a `SEAT` once a real opponent shows up - so
    `requestPlay()`/`createRoom()`/`joinRoom(code)` are fire-and-forget and
    a `LobbyListener` callback (`onWaiting`/`onRoomCreated`/`onSeated`/
    `onLobbyError`) reports what happens; `awaitFirstSnapshot(...)` blocks
    for the first `STATE` once seated. Every incoming `STATE` is decoded and
    cached (a `volatile` field, read from Swing's event thread, written from
    the WS thread - same pattern `BoardWindow.currentFrame` already uses).
    `waitFor()` is a no-op: the server is the only real clock now.
  - `LoginDialog` - the sign-in alert (`JOptionPane` + a small form) shown
    by `NetworkGuiMain` first: server address, username, password,
    Login/Register/Cancel.
  - `LobbyDialog` - shown right after sign-in succeeds: Quick Play vs. Room
    (Create/Join/Cancel, matching the CTD brief's own described dialog).
    Implements `NetworkGameClient.LobbyListener` to drive a small modal
    "waiting..." `JDialog` that closes itself once a `Seat` (or an error)
    arrives - blocking the caller until then, same pattern `LoginDialog`
    already established. Neither dialog is folded into the board UI itself.
- **server/** - `KungFuChessServer` (a `WebSocketServer`) is now a thin
  transport/routing layer: it requires `login`/`register` first, then waits
  for exactly one lobby command (`play`/`create_room`/`join_room <code>`)
  and delegates everything about *which* game a connection ends up in to
  `Lobby`. `onClose` calls `lobby.handleDisconnect(conn)`.
  - `Lobby` - the "tournament manager" (opens rooms, matches players by
    ELO, routes connections to the right `GameSession`) - a room map
    (6-char generated code → `GameSession`), a `connection → GameSession`
    map (for `onClose` lookups), a `username → GameSession` map (for
    reconnect, below), and a matchmaking queue. `play(...)` scans the queue
    for anyone within ±100 ELO; if found, both are seated into a fresh
    session immediately. An unmatched player is removed from the queue and
    sent an explicit `ERROR` after a configurable timeout (default 60s,
    matching the CTD brief's "waits up to a minute") rather than queuing
    forever - each `Waiting` entry owns its own scheduled timeout task,
    cancelled the moment it's matched or explicitly cancelled
    (`cancelQueued`).
  - **Reconnect**: `Lobby.tryReconnect(connection, username)` is called by
    `KungFuChessServer` right after every successful login/register,
    *before* waiting for a lobby command. If `username` was seated
    (White/Black - never a spectator) in a session it was since
    disconnected from, `GameSession.reconnect(...)` restores that exact
    seat (cancelling the pending auto-resign task) and re-greets the
    connection (`SEAT` + a fresh `STATE`) - silently, with no new protocol
    message of its own. `net.LobbyDialog.chooseAndWait` checks
    `client.getAssignedSeat() != null` at the very top and returns
    immediately if so, so a reconnected player is never asked to pick
    Quick Play/Room again for a game they're already back in.
  - `GameSession.join(...)` does its own greeting (`SEAT` + that
    connection's snapshot) rather than leaving it to the caller - and, for
    the first (White) seat, deliberately **doesn't** greet at all: with no
    opponent yet there's nothing to show, so that client just keeps
    whatever "waiting for an opponent..." UI it already had up
    (`LobbyDialog`'s modal dialog stays open, since no `SEAT` ever arrives
    to close it). The moment the second (Black) seat fills, `join(...)`
    greets **both** connections at once and calls
    `engine.setPlayerNames(whiteUsername, blackUsername)` so the snapshot -
    and so `ImgRenderer`'s score line - carries real names from then on. A
    spectator joining an already-full room is greeted immediately, same as
    always.
  - `GameSession` talks to `GameEngine` **directly**, not through
    `event.EventEngine` - that class owns a single shared "selection" field,
    correct for one local mouse but wrong for two independent network
    players sharing one board (White's selection must never leak to Black,
    and Black's next click must never be able to move White's selected
    piece). So `GameSession` keeps `whiteSelection`/`blackSelection`
    separately and drives each one through `event.ClickSelector` (color
    passed in as the required owner) instead of re-implementing the click
    rules a second time - an earlier version of this file *did* duplicate
    them, which is exactly how "click your own piece again to deselect it"
    almost shipped working in one path and not the other; don't reintroduce
    that copy if this code changes again. Each connection's outgoing
    snapshot is built with `GameEngine.buildSnapshot(<that seat's own
    selection, or null for a spectator>)` - a player only ever sees their
    own selection highlight and legal-move markers; a `VIEWER` (tracked in a
    `CopyOnWriteArrayList`, unlimited) sees the live board but never any
    selection. `handleCommand` follows the CTD brief's own pipeline - parse
    → identity → role → validation → publish (see the Command vs. Event
    section above for the exact wire format and every rejection reason,
    including the network-specific `VIEWER_CANNOT_PLAY`/`NOT_YOUR_PIECE`
    and the general `MALFORMED_COMMAND`/`ILLEGAL_MOVE`). A snapshot is
    only broadcast once an action was actually taken, not after a rejected
    one.
  - A `ScheduledExecutorService` field (shared by the tick loop and the
    disconnect timer below) ticks every 16ms once both seats are filled
    (`engine.advanceTime(16)` - the same call `BoardWindow`'s local Swing
    `Timer` makes for offline play), broadcasting a personalized snapshot to
    White, Black, and every viewer after each tick and each accepted
    command.
  - **Disconnect handling**: `handleDisconnect(connection)` vacates that
    seat and, if it was a real seated player (not a spectator) in an
    already-two-player game, schedules a **one-shot** forfeit task (default
    20s, configurable via a constructor param so tests don't need to wait
    for real) that calls `engine.forceResign(thatColor)`; `forceResign` is
    idempotent so it's harmless if the game already ended some other way in
    the meantime. If the same username reconnects first (see `Lobby`'s
    reconnect above), `GameSession.reconnect` cancels that pending
    `ScheduledFuture` and restores the seat instead - the forfeit never
    fires. There is deliberately **no visible countdown** anywhere
    (explicit user instruction) - purely functional.
  - **Concurrency**: `GameEngine`/`RealTimeArbiter` are plain,
    non-thread-safe classes by design (every other entry point drives them
    from one thread). Because Java-WebSocket dispatches connection
    callbacks on their own threads concurrently with the ticker/disconnect-
    timer thread, `GameSession` serializes **every** access to `engine`
    (and to the two selection fields) through one `synchronized` block.
    Don't add a new path into `engine` from `GameSession` without going
    through that same lock.
  - The first snapshot to report `gameOver()` (from a real king capture
    *or* a forced resignation - both flow through the same
    `GameState.setGameOver`) triggers a one-time ELO update
    (`applyRatingChangeIfGameJustEnded`, guarded by a `ratingApplied` flag)
    via `server.auth.EloCalculator`, persisted through
    `UserRepository.updateRating`.
  - **server/auth/** - `AuthCommandParser` is the one place that knows the
    raw `"login/register <username> <password>"` wire text shape (mirrors
    `parsing.BoardMapper`/`PieceMapper` being kept separate from the engine
    that consumes their output) - `parse(message)` returns a `ParsedCommand`
    (mode + username + password) or `null` if malformed; it has no
    dependency on `UserRepository` or `AuthController`. `AuthController` is
    the controller for the login/register step: calls the parser, then
    calls into `UserRepository` (the service that owns account
    logic/persistence) to carry it out, returning an `Outcome` (malformed,
    or a `UserRepository.AuthResult`) for `KungFuChessServer.handleAuth` to
    translate into a wire reply (`AUTH_OK`/`ERROR` + closing the
    connection) - kept separate so this orchestration/logging isn't mixed
    into WebSocket connection bookkeeping, and `UserRepository` never has to
    see raw wire text at all. `UserRepository` (SQLite via `sqlite-jdbc`,
    `CREATE TABLE IF NOT EXISTS` on construction, one connection per call -
    no pooling, there are at most two players per game) owns accounts:
    `register`/`authenticate` (returning an `AuthResult` with either a
    rating or a failure reason) and `updateRating`. `PasswordHasher` is
    salted SHA-256 - no bcrypt/argon2 dependency, adequate for this
    project's scope; a password is never stored or compared in the clear.
    `EloCalculator` is a pure, stateless standard ELO formula (K=32, no
    draws - this engine's games always end in a king capture or a
    resignation, never a tie).
- **bus/Bus** - a generic, synchronous in-process pub/sub
  (`subscribe(topic, handler)` / `publish(topic, payload)`).
  `GameSession` publishes each broadcast's White-side `GameSnapshot` on
  `"room.<code>.snapshot"`. Sound and start/end-animation triggers
  (mentioned in the original CTD 26 brief) are meant to subscribe to future
  topics on this same bus once there are concrete assets/specs for them -
  none exist yet, so none are wired up.
- **logging/ActivityLog** - a tiny append-only timestamped text logger
  (`synchronized log(String)`, opens/appends the file fresh each call - not
  a real logging framework). One instance server-side (`logs/server.log`,
  shared across all of `KungFuChessServer`/`Lobby`/every `GameSession`);
  each `NetworkGameClient` creates its own (`logs/client-<username>.log`,
  lazily once the username is known) and logs what it sends/receives.

This is phases 1-3 of a staged brief: pub/sub bus + WebSocket server + 2
players; SQLite-backed accounts + ELO rating; ELO-ranged quick-match,
rooms with spectators, disconnect auto-resign, and activity logging. There
is no remaining known phase beyond this as of this writing - if the brief
gains new slides, treat them as a new stage rather than assuming scope
that isn't in the code.
