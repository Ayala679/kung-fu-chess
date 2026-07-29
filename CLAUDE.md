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

Fetch runtime dependencies once (Java-WebSocket + its slf4j-api dependency;
sqlite-jdbc + postgresql, both JDBC drivers - `UserRepository` picks
whichever one matches the `jdbc:` URL scheme it's given at runtime; jedis +
commons-pool2, and jnats - the Redis and NATS clients, both selected the
same way via `KFC_REDIS_URL`/`KFC_NATS_URL` - see "Networking" below):
```powershell
powershell -File tools/fetch-libs.ps1
```

Build (from repo root - the classpath is needed even for offline-only work,
since `sources.txt` compiles the whole project, networking code included,
in one `javac` invocation):
```bash
cd src/main
javac -encoding UTF-8 -cp "../../lib/Java-WebSocket-1.5.6.jar;../../lib/slf4j-api-2.0.13.jar;../../lib/sqlite-jdbc-3.46.1.3.jar;../../lib/postgresql-42.7.4.jar;../../lib/jedis-5.1.3.jar;../../lib/commons-pool2-2.12.0.jar;../../lib/jnats-2.17.2.jar" -d ../../out @sources.txt
cd ../..
```
`sources.txt` (in `src/main/`) is the authoritative file list for `javac`;
production code lives under `src/main/` and tests live separately under
`src/tests/` - `sources.txt` only ever lists the former. Add new non-test
classes to it when creating files. Image/text assets (`board.png`,
`dashboard.png`, per-piece sprite sheets, the demo board fixture) live in a
separate top-level `resources/` folder at the repo root (not under `src/`),
loaded at runtime via relative paths, so `java` invocations below must run
from the repo root (their working directory) for those relative paths to
resolve.

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

Run the networked server (WebSocket on port 8887, REST API on port 8888,
both by default - see "Networking" below; accounts in
`data/kungfuchess.db`, activity log at `logs/server.log`) and connect to it
with the networked GUI client (its own log at `logs/client-<username>.log`)
- the client shows a Login/Register alert, then a Quick Play/Room alert,
before anything else opens; see README.md for the full walkthrough:
```bash
java -cp "out;lib/Java-WebSocket-1.5.6.jar;lib/slf4j-api-2.0.13.jar;lib/sqlite-jdbc-3.46.1.3.jar;lib/postgresql-42.7.4.jar;lib/jedis-5.1.3.jar;lib/commons-pool2-2.12.0.jar;lib/jnats-2.17.2.jar" server.main.KungFuChessServerMain
java -cp "out;lib/Java-WebSocket-1.5.6.jar;lib/slf4j-api-2.0.13.jar;lib/sqlite-jdbc-3.46.1.3.jar;lib/postgresql-42.7.4.jar;lib/jedis-5.1.3.jar;lib/commons-pool2-2.12.0.jar;lib/jnats-2.17.2.jar" NetworkGuiMain
```

Or run the server containerized, backed by PostgreSQL instead of SQLite,
via Docker Compose (see `Server_Design.md` for what this does and doesn't
cover):
```bash
docker compose up --build
```
The client (`NetworkGuiMain`, a Swing GUI) is never containerized - run it
locally as above, pointed at `localhost:8887`.

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
    capture does via `GameState.setGameOver`; `abandon` - ends the game with
    **no** winner via the new `GameState.abandon()`, used when a disconnect
    grace window elapses with nobody to fairly credit with a win - these two
    are the deliberate touches the networking layer makes to this package,
    see "Networking" below). It asks
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
      afterward is what captures it. `GameEngine.requestJump` never rejects
      a jump just because it looks "too early" against some enemy slide
      already in flight - it always starts and runs its full course; a jump
      thrown hopelessly early simply lands and rests normally, becoming an
      ordinary, capturable occupant well before that attack arrives, which
      leaves real room for a *second*, better-timed jump before the
      attacker actually gets there (see `GameEngineTest.testEarlyJumpLeavesRoomForAWellTimedSecondJumpThatSurvives`
      - an earlier version of this method special-cased "too early" into an
      immediate, unrecoverable capture at request time via a since-removed
      `isTooLateToJump` - a real reported bug: it denied any second chance,
      even with plenty of real time left to react again; don't reintroduce
      that shortcut). Whether a jump actually dodges is instead resolved
      only for real, at arrival time, by `isProtectedByAnInProgressJump` -
      since real time advances in small increments, a jump legitimately
      still in the air a moment ago may have already landed by the tick
      that matters; ties (the jump lands at the *same* instant the attacker
      arrives) go to the defender. `update()` resolves every
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
  dodge). A key listener also binds `R` to `GameClient.requestRematch()`
  once `snapshot().gameOver()` - a no-op offline, a real `"rematch"` command
  networked. On every `refresh()`, if `GameClient.millisUntilOpponentAutoAbandon()`
  is positive (only ever true for `NetworkGameClient`), `ImgRenderer.drawDisconnectCountdown`
  is called as an extra post-processing step on top of the normal `render(...)`
  frame - kept as a *separate* call rather than a `render(...)` parameter so
  `GameSnapshot` itself never has to carry a purely-networking concept.
  `ImgRenderer` composites onto `resources/dashboard.png` (board +
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
`server.main.KungFuChessServerMain`) - kept deliberately separate so `Main` stays a
single `public static void main` for graders/tools that auto-detect the
entry point.

## Networking (bus/, client/, server/, logging/)

Added on top of the layers above with **two deliberate exceptions**
(`GameEngine.forceResign`/`abandon`, above) - a networked game is driven by the exact
same `GameEngine` a local session uses; only what sits *around* it differs.

**Every one of the five server processes is split three ways, organized
into matching folders/packages** - `server/main/` (`server.main`, one
`XxxMain` per process: own `main`, reads env vars/args, wires the other
two, starts - no logic of its own), `server/controller/`
(`server.controller`, the process's endpoints only - NATS subscriptions/
WebSocket callbacks/HTTP routes, decodes each request to its raw fields,
delegates everything else), and `server/service/` (`server.service`, all
the decision logic, talking to the deeper layers - `Lobby`/`MatchQueue`/
`ShardPicker`/`GameSession`/etc. - that aren't specific to any one process,
so weren't part of this split at all). Mirrors the `Main`/`BoardController` split the local/offline
game already used - `Main` stays a single, tiny `public static void main`,
all real logic lives one layer down. Some of this already existed in
spirit before the rename - `MatchQueue`/`ShardPicker` were always the pure
"service" logic behind `Matchmaker`/`GameAllocator` - this made it uniform
and explicit across all five processes: `KungFuChessServerMain`/
`Controller`/`Service` (WS Gateway), `GameServerShardMain`/`Controller`/
`Service` (a Game Server Shard - also absorbed the old standalone
`RoomCreationResponder` as a `Service` method), `MatchmakerMain`/
`Controller`/`Service`, `GameAllocatorMain`/`Controller`/`Service`, and
`ApiGatewayMain` (the one process that didn't need new Controller/Service
classes written - `server.controller.HttpApiServer` already *was* the
Controller, and `server.service.ApiGateway` already *was* the
wiring/composition layer other processes' `XxxService` plays - both just
moved into the matching package on this pass, unchanged otherwise;
`server.auth.AuthService`, renamed from `AuthController` since it never
touched HTTP/WebSocket at all, stays in `server.auth` - a separate,
pre-existing subpackage this reorganization didn't touch). The
class-by-class descriptions below still describe the
*behavior* accurately - just read `KungFuChessServer`/`GameServerShard`/
`Matchmaker`/`GameAllocator` as shorthand for "the process," now spread
across that process's own `Main`/`Controller`/`Service` trio.

**The classes shared across more than one process were further split by
family** - `server/connection/` (`OutboundConnection`/
`LocalOutboundConnection`/`RemoteOutboundConnection`), `server/room/`
(`RoomCreator`/`LocalRoomCreator`/`RemoteRoomCreator`/
`RoomCreationException`/`RoomDirectory`/`InMemoryRoomDirectory`/
`RedisRoomDirectory`), and `server/reconnect/` (`ReconnectRegistry`/
`InMemoryReconnectRegistry`/`RedisReconnectRegistry`) - each grouping the
existing dual-mode families (interface + `InMemoryXxx` + `RedisXxx`) that
were already sitting next to each other. `Lobby`, `GameSession`,
`Protocol`, `Seat`, `SnapshotCodec`, `GatewayCommandEnvelope`,
`SeatPairEnvelope`, `MatchQueue`, `ShardPicker`, and `GameAllocatorClient`
stayed directly in `server/` - none of them share a tight enough family
resemblance with anything else to justify a folder of their own.

- **event/GameClient** - the interface `view.BoardWindow` actually depends
  on (`handleClick`/`handleJump`/`waitFor`/`snapshot`, plus two `default`
  methods only `client.NetworkGameClient` overrides for real -
  `millisUntilOpponentAutoAbandon()` and `requestRematch()` - offline play
  keeps the default no-op/zero implementation, since neither concept exists
  without a network). `EventEngine`
  implements it for local play; `client.NetworkGameClient` implements it for
  networked play. `BoardWindow` itself has no idea which one it has.
- **Wire protocol** (`Protocol`/`Seat`/`SnapshotCodec`, all in `server/`
  alongside the rest of the server implementation - genuinely shared between
  client and server, not exclusively server-side logic, but the project has
  no separate client/server module to put "shared" code in, and they aren't
  client-only either, so they live with `server/`'s other classes; the
  actual client-only code is `client/`, below) - compiled as regular source
  alongside everything else (still one `javac` invocation, no build-tool
  split).
  - `Protocol` - the message prefixes specific to the network layer.
    Login/register/room-creation are non-realtime and go over the REST API
    (`server.HttpApiServer`, below) rather than the WebSocket - see
    "REST API / scalable-server design" further down for the full picture.
    On the WebSocket, client→server commands (`attach <token>`, `play`,
    `join_room <code>`, `click row col`/`jump row col`, `rematch`) stay
    space-delimited (`rematch` takes no arguments - it's just the bare
    verb). Every server→client *tagged* reply with a payload is
    pipe-delimited (`TAG|value`, matching the CTD 26 brief's own wire
    examples): `AUTH_OK|rating` (the WS attach reply; the REST login/register
    reply reuses the same tag with an extra field, `AUTH_OK|token|rating`),
    `ROOM_CREATED|code` (REST-only now, the `POST /api/rooms` reply),
    `WELCOME|role=WHITE` (greeting on being seated - the CTD brief's own
    name for this; `Seat` the Java enum is unchanged, only the wire text
    was renamed from the old `SEAT WHITE`), `ERROR|reason` (in-game
    rejections use a `SCREAMING_SNAKE_CASE` code - `NOT_YOUR_PIECE`,
    `VIEWER_CANNOT_PLAY`, `MALFORMED_COMMAND`, `ILLEGAL_MOVE`, `GAME_PAUSED`,
    `GAME_NOT_OVER` - matching the brief's style; auth/room/matchmaking-
    timeout reasons stay free text), `OPPONENT_DISCONNECTED|graceSeconds`
    (sent once, the instant a seated player drops, to the still-connected
    side and any viewers) and `OPPONENT_RECONNECTED` (no payload, same
    audience, sent once they're back). `WAITING` (no payload) and
    `STATE\n<block>` (a different multi-line shape, encoded by
    `SnapshotCodec`) are untouched. Board commands aren't parsed by
    `event.EventMapper` - `EventMapper`'s
    `click x y` is pixel-based (see `event.InputMapper`) for the
    stdin/console protocol, a different concern from the already-resolved
    board cell coordinates `BoardWindow`/`GameClient` deal in; `click row
    col`/`jump row col`/`rematch` are parsed directly by `server.GameSession`.
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
    `RealTimeArbiter` - consistent with `forceResign`/`abandon` being the
    only deliberate exceptions networking makes to `gameengine/`. Two documented,
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
  - This required two small, additive changes elsewhere:
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
- **client/** - the code that only ever runs on the client, kept out of
  `server/` on purpose so nothing there is mistaken for server-side logic.
  - `NetworkGameClient` (a `WebSocketClient`) - `register()`/`login()` block
    (via a `CountDownLatch`) until `AUTH_OK` or `ERROR`. What happens next
    (quick-match vs. room) is inherently asynchronous - the server may reply
    `WAITING` and only send a `WELCOME` once a real opponent shows up - so
    `requestPlay()`/`createRoom()`/`joinRoom(code)` are fire-and-forget and
    a `LobbyListener` callback (`onWaiting`/`onRoomCreated`/`onSeated`/
    `onLobbyError`) reports what happens; `awaitFirstSnapshot(...)` blocks
    for the first `STATE` once seated. Every incoming `STATE` is decoded and
    cached (a `volatile` field, read from Swing's event thread, written from
    the WS thread - same pattern `BoardWindow.currentFrame` already uses).
    `waitFor()` is a no-op: the server is the only real clock now.
    `OPPONENT_DISCONNECTED|<seconds>` stores a `volatile` deadline
    (`System.currentTimeMillis() + seconds*1000`); `millisUntilOpponentAutoAbandon()`
    just subtracts "now" from it each time `BoardWindow` asks, so the
    countdown is purely a function of elapsed wall-clock time - no per-tick
    server push needed to keep it moving. `OPPONENT_RECONNECTED` resets the
    deadline back to 0. `requestRematch()` sends `"rematch"`.
  - `LoginDialog` - the sign-in alert (`JOptionPane` + a small form) shown
    by `NetworkGuiMain` first: server address, username, password,
    Login/Register/Cancel.
  - `LobbyDialog` - shown right after sign-in succeeds: Quick Play vs. Room
    (Create/Join/Cancel, matching the CTD brief's own described dialog).
    Implements `NetworkGameClient.LobbyListener` to drive a small modal
    "waiting..." `JDialog` that closes itself once a `Seat` (or an error)
    arrives - blocking the caller until then, same pattern `LoginDialog`
    already established. Neither dialog is folded into the board UI itself.
- **server/** - `KungFuChessServer` (a `WebSocketServer`) is the WS
  Gateway: its first message must be `attach <token>` (a bearer token
  minted by `HttpApiServer`'s REST login/register - see below), then it
  accepts `play`/`join_room <code>`/`click`/`jump`/`rematch` - room
  *creation* is REST-only. Attach (token validation) is always handled
  locally - `SessionTokenStore` is directly reachable regardless of
  topology - but what happens with everything else depends on which
  topology the constructor infers from `KFC_REDIS_URL`/`KFC_NATS_URL`
  (same signal steps 3/4 already established):
  - **Local/offline** (either unset): unchanged since before step 5 -
    embeds `Lobby`/`GameSession`/`MatchQueue` directly and calls them as
    plain Java methods (`MatchQueue`'s `MatchFoundListener` is wired
    straight to `lobby::seatMatchedPair`, in-process, no NATS), wrapping
    each real `WebSocket` in a cached `LocalOutboundConnection` (see
    `OutboundConnection` below).
  - **Distributed** (both set - the Docker Compose deployment): becomes a
    relay with **no** `Lobby`/`GameSession`/`UserRepository` reference at
    all - since "Step 6a", possibly routing to *more than one* Game Server
    Shard (see `routeDistributedCommand`, below), not just one fixed
    subject - see "REST API / scalable-server design" below and
    `Server_Design.md`'s "Step 5" for the full picture of why and how.
  - **Routing to the right shard** ("Step 6a", `routeDistributedCommand`):
    with more than one Game Server Shard, the Gateway must pick exactly one
    to receive each post-attach message - the wrong one either drops it
    silently or, worse, several shards all reply to the same client at
    once. A `"play"` always goes to `"matchmaker.play"` (unaffected by
    sharding - `Matchmaker` asks the Game Allocator itself once matched).
    A `"join_room <code>"` is resolved **fresh, every time**, via
    `RoomDirectory.shardFor(code)` (never a cached shard - a stale entry
    from a previous, already-finished game on this same connection must
    never leak into routing a request for a brand-new room); an unknown
    code gets a clean `ERROR|unknown room code` straight from the Gateway,
    no shard round trip at all. Every other command (click/jump/rematch)
    relies entirely on a local `Map<WebSocket, String> shardOf` cache,
    populated by subscribing to `"conn.<connectionId>.shard"` (alongside
    the pre-existing `"conn.<connectionId>.out"`, same lifecycle) - a
    shard publishes its own id there the instant it claims a connectionId
    (`join_room`/`seatMatchedPair`/`tryReconnect` all succeeding, see
    `GameServerShard` below).
  - `OutboundConnection` (`void send(String)`/`boolean isOpen()`) is what
    lets `Lobby`/`GameSession` stay completely unaware of which topology
    they're running in - it replaced `org.java_websocket.WebSocket` as
    their field/parameter type everywhere, with zero other logic change.
    `LocalOutboundConnection` wraps a real `WebSocket` (local topology).
    `RemoteOutboundConnection` wraps a `connectionId` and the Shard's NATS
    connection - `send` publishes to `"conn.<connectionId>.out"`, which
    the Gateway is already subscribed to per connection and relays
    verbatim to the real socket; `isOpen` reflects the last disconnect
    notification received for that `connectionId` (see `GameServerShard`
    below), not a live query. `tests.FakeWebSocket` also implements this
    interface directly (its `send`/`isOpen` already matched), which is why
    `LobbyTest`/`GameSessionTest` needed no changes for this split.
  - `HttpApiServer` - the REST half of the protocol, built on the JDK's own
    `com.sun.net.httpserver` (no new web-framework dependency): `POST
    /api/login`/`POST /api/register` (form-encoded `username`/`password`,
    reuses `AuthController.handleAuth` unchanged, replies
    `AUTH_OK|token|rating`/`ERROR|reason`) and `POST /api/rooms`
    (form-encoded `token`, replies `ROOM_CREATED|code` or, if room creation
    fails - see `RoomCreator` below - `503`/`ERROR|room service
    unavailable`). Runs on its own port (`8888` by default, `8887 + 1`) in
    one of two places depending on topology: **embedded** inside
    `KungFuChessServer` for local/offline runs (`KFC_REDIS_URL`/
    `KFC_NATS_URL` both unset - unchanged since before step 4), or as its
    own standalone process, **`server.ApiGateway`** (own `main`, its own
    `UserRepository`/`AuthController`/`RedisSessionTokenStore` - all
    already genuinely shared/multi-process-safe stores, nothing new needed
    for those), in the Docker Compose deployment. Room creation is the one
    thing that can't just be a local Java call once the API Gateway is a
    separate process from `Lobby` - `RoomCreator` (an interface -
    `LocalRoomCreator` wraps a direct `Lobby` reference, embedded case only;
    `RemoteRoomCreator` first asks `server.GameAllocator` (below) which
    shard the new room should go to, then sends a real, blocking NATS
    request to that shard's own `"shard.<shardId>.create_room"` subject and
    waits for the room code back) abstracts over that. The other side of
    that request is `RoomCreationResponder`, living in `GameServerShard`
    (a Game Server Shard process - see below) alongside `Lobby` itself,
    subscribing on the same NATS connection the process already opened for
    `Bus` (`NatsBus.rawConnection()`). See "REST API / scalable-server
    design" below and `Server_Design.md`'s "Step 4"/"Step 5"/"Step 6a" for
    the full picture.
    `SessionTokenStore` (an interface - `InMemorySessionTokenStore` for
    local/offline runs and every unit test, `RedisSessionTokenStore` when
    `KFC_REDIS_URL` is set, see "REST API / scalable-server design" below)
    issues/validates the bearer token these endpoints hand out - multi-use
    within a TTL (not one-shot), since a token authenticates both the WS
    `attach` handshake and a `POST /api/rooms` call, in either order. See
    `Server_Design.md` for why this split exists and what it deliberately
    does *not* do yet.
  - `Lobby` - the "tournament manager" (opens rooms, routes connections to
    the right `GameSession`) - a room map (6-char generated code →
    `GameSession`, still always in-process - see "REST API / scalable-server
    design" below for why), a `connection → GameSession` map (for
    disconnect lookups, also inherently in-process - a live connection
    identity, whichever `OutboundConnection` implementation it is, can
    never be shared state), and a `ReconnectRegistry` (`username → roomCode`
    - `InMemoryReconnectRegistry` or `RedisReconnectRegistry`, same
    selection as `SessionTokenStore` above) for reconnect, below.
    `createRoom(username)` (called from `HttpApiServer`, not the WebSocket)
    just mints a code and registers an empty `GameSession` - there's no
    live connection yet to seat, so seating always happens the normal way,
    via `joinRoom` once someone's WebSocket sends `join_room <code>`
    (whoever arrives first becomes White - this deliberately doesn't
    special-case "the creator"). `seatMatchedPair(...)` does the same for
    two players a `MatchQueue` (below) just paired - mints a fresh room and
    seats both sides at once; ELO-ranged pairing itself doesn't live here
    any more (see `MatchQueue`/`Matchmaker` below and Server_Design.md's
    "Step 5"). As of "Step 6a" (`Server_Design.md`), a `Lobby` also
    optionally knows its own `shardId` and a `RoomDirectory` (`roomCode →
    shardId`, `InMemoryRoomDirectory`/`RedisRoomDirectory` - same dual-mode
    pattern as `ReconnectRegistry`) - both null/unused outside the
    multi-shard distributed topology (the shorter, pre-existing
    constructors default to that, so every call site that only ever knew
    one implicit shard needed zero changes) - `createRoom`/
    `seatMatchedPair` record `roomDirectory.record(code, shardId)` right
    after minting a code, so the WS Gateway can later resolve which shard
    to route a `join_room <code>` to.
  - `MatchQueue` - the reference design's own **Matchmaker** component, in
    its purest, directly-unit-tested form (topology-agnostic, built on
    `OutboundConnection` same as `Lobby`/`GameSession` - see
    `tests.MatchmakerTest`): the actual ELO-ranged quick-match queue,
    extracted out of what used to be `Lobby.play`/`cancelQueued` so it can
    become a genuinely separate process (see `Matchmaker` below) rather
    than logic bundled inside the Game Server Shard - per the original
    brief's own wording, Matchmaker "pairs players for quick-match" and
    nothing else; room creation/joining stays a Shard/`HttpApiServer`
    concern (there's no Game Allocator yet - see the roadmap below).
    `play(connection, username, rating)` scans the queue for anyone within
    ±100 ELO; if found, both are handed to a `MatchFoundListener` callback
    (in practice, always `Lobby.seatMatchedPair`, called directly in the
    embedded topology or reached over NATS in the distributed one) instead
    of `MatchQueue` creating a `GameSession` itself. An unmatched player is
    removed from the queue and sent an explicit `ERROR` after a
    configurable timeout (default 60s, matching the CTD brief's "waits up
    to a minute") rather than queuing forever - each `Waiting` entry owns
    its own scheduled timeout task, cancelled the moment it's matched or
    explicitly cancelled (`cancelQueued`).
  - `GameServerShard` - **one of possibly several** Game Server Shard
    processes in the distributed topology, each its own identity,
    `KFC_SHARD_ID` (own `main`; requires `KFC_REDIS_URL`/`KFC_NATS_URL`/
    `KFC_SHARD_ID`, no local fallback, same stance as `ApiGateway`): owns
    `UserRepository`, `Lobby` (constructed with this shard's own `shardId`
    and a `RedisRoomDirectory`), and `RoomCreationResponder`. Never sees a
    real `WebSocket` - every command arrives as a `GatewayCommandEnvelope`
    on this shard's own subject, `"shard.<shardId>.command"` (a plain
    record: `connectionId|username|rating|rawCommand`, decoded fresh per
    message, not a client-facing `Protocol.java` concept - see "Step 6a"
    below for why this is per-shard now, not the single global
    `"gateway.command"` step 5 originally used), resolved to a cached
    `RemoteOutboundConnection` per `connectionId` (the *same* instance
    across multiple messages, so `Lobby`'s connection-keyed maps keep
    working by reference) and dispatched with the same routing logic
    `KungFuChessServer`'s local topology already has - except "play",
    which isn't this Shard's job at all any more: the exact envelope it
    already has is just relayed onward unchanged to `"matchmaker.play"`
    for the standalone `Matchmaker` process to handle ("this isn't mine to
    handle, forward it", the same pattern `HttpApiServer`'s room creation
    already uses via `RoomCreator`) - the stuck-in-a-finished-session check
    (`isGameOver`/`leaveFinishedSessionIfAny`) still runs first either way,
    unaffected. `"gateway.reconnect"` (`connectionId|username`) and
    `"gateway.disconnect"` (bare `connectionId`) stay *shared*, broadcast
    to every shard (unlike `"shard.<shardId>.command"`) - safe, since a
    non-owning shard's `Lobby.tryReconnect`/`handleDisconnect` simply finds
    nothing local and no-ops (`ReconnectRegistry` is Redis-shared, but
    `Lobby.rooms` isn't, so only the true owner ever matches). This shard's
    own `"shard.<shardId>.seat_pair"` carries a `SeatPairEnvelope` from
    `Matchmaker` once it pairs two players for a room the Game Allocator
    assigned to *this* shard specifically - decoded, resolved to two
    (possibly newly-cached) `RemoteOutboundConnection`s, and handed to
    `Lobby.seatMatchedPair`. After a connectionId is newly claimed -
    `joinRoom`/`seatMatchedPair`/`tryReconnect` all succeeding - this shard
    publishes `"conn.<connectionId>.shard"` = its own `shardId`, so the WS
    Gateway learns which shard now owns that connection (see
    `KungFuChessServer` below).
  - `server.GameAllocator` - standalone (own `main`; requires only
    `KFC_NATS_URL` and `KFC_SHARD_IDS`, a comma-separated static list - no
    Postgres/Redis at all) - the reference design's own Game Allocator
    component, added in "Step 6a". Wraps a pure, directly-unit-tested
    `ShardPicker` (`String next()`, round-robin `AtomicInteger` index over
    the configured shard list - see `tests.ShardPickerTest`; static
    round-robin was chosen over a load-aware policy for simplicity, see
    its own Javadoc) behind one NATS request-reply subject,
    `"allocator.assign"` (empty request, shardId reply), mirroring
    `RoomCreationResponder`'s own request-reply shape. `RemoteRoomCreator`
    and `Matchmaker` both call it (via the small shared
    `GameAllocatorClient.assignShard` helper) whenever a brand-new room
    needs a shard; `join_room <code>` for an *existing* room never touches
    the Allocator at all - it's resolved via `RoomDirectory` instead (see
    `Lobby` above and `KungFuChessServer` below).
  - `Matchmaker` - the Matchmaker, standalone (own `main`; requires only
    `KFC_NATS_URL` - no Postgres/Redis at all, since `MatchQueue` is pure
    in-memory pairing logic and reconnect/rating lookups are someone
    else's job). Owns one `MatchQueue`, its own NATS connection, and its
    own `connectionId → RemoteOutboundConnection` cache (mirroring
    `GameServerShard`'s). Subscribes to `"matchmaker.play"` (decodes the
    `GatewayCommandEnvelope` `GameServerShard` forwarded, calls
    `MatchQueue.play(...)`, sends `WAITING` itself if not matched - same
    "caller sends WAITING, not the queue itself" contract `Lobby.play` used
    to document) and, independently, to the very same `"gateway.disconnect"`
    subject every `GameServerShard` already subscribes to (plain NATS
    pub/sub naturally supports more than one subscriber per subject, so
    the WS Gateway never has to know or care that multiple services now
    care about disconnects) to call `MatchQueue.cancelQueued`. On a match,
    asks `GameAllocator` which shard the new room should go to, then
    publishes a `SeatPairEnvelope` to that shard's own
    `"shard.<shardId>.seat_pair"` for it to seat. The embedded/local
    topology owns its own in-process `MatchQueue` the same way (see
    `KungFuChessServer` below) - this standalone class only exists for the
    distributed one.
  - **Reconnect**: `Lobby.tryReconnect(connection, username)` runs right
    after every successful WS `attach`, *before* waiting for a lobby
    command - called directly by `KungFuChessServer` in the local topology,
    or via a fire-and-forget `"gateway.reconnect"` publish (handled by
    `GameServerShard`, above) in the distributed one; today's return value
    is ignored by the caller either way. If `username` was seated
    (White/Black - never a spectator) in a session it was since
    disconnected from, `GameSession.reconnect(...)` restores that exact
    seat (cancelling the pending auto-abandon task via `cancelAbandonTask`)
    and re-greets the connection (`WELCOME` + a fresh `STATE`) - silently,
    with no new protocol message of its own to the reconnecting player, but
    the *other* seated player (and any viewers) get an explicit
    `OPPONENT_RECONNECTED` so a client-side "opponent disconnected"
    countdown (below) knows to clear itself. `client.LobbyDialog.chooseAndWait` checks
    `client.getAssignedSeat() != null` at the very top and returns
    immediately if so, so a reconnected player is never asked to pick
    Quick Play/Room again for a game they're already back in.
  - `GameSession.join(...)` does its own greeting (`WELCOME` + that
    connection's snapshot) rather than leaving it to the caller - and, for
    the first (White) seat, deliberately **doesn't** greet at all: with no
    opponent yet there's nothing to show, so that client just keeps
    whatever "waiting for an opponent..." UI it already had up
    (`LobbyDialog`'s modal dialog stays open, since no `WELCOME` ever arrives
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
    already-two-player game, schedules a **one-shot** auto-abandon task
    (default 20s, configurable via a constructor param so tests don't need
    to wait for real) via `scheduleAutoAbandon` that calls `engine.abandon()`
    - ends the game with **no winner** (see `GameState.abandon()`/
    `GameEngine.abandon` above), since a disconnect is nobody's fault and
    neither side should be credited with a win or charged with a loss for
    it; `abandon()` is idempotent so it's harmless if the game already ended
    some other way in the meantime. The still-connected side (and any
    viewers) are also sent an explicit `OPPONENT_DISCONNECTED|<graceSeconds>`
    the instant the disconnect happens, so a client can show a **visible
    countdown** (this used to be explicitly countdown-less; that changed
    once auto-abandon stopped declaring a winner, since a silent forfeit
    and a silent no-fault stoppage read very differently to the player
    still connected). If the same username reconnects first (see `Lobby`'s
    reconnect above), `GameSession.reconnect` cancels that pending
    `ScheduledFuture` via `cancelAbandonTask` and restores the seat instead -
    the abandon never fires.
  - **Paused while disconnected**: `isPausedForDisconnect()` is true
    whenever both seats have real usernames but one connection is currently
    null. While true, `tick()` skips `engine.advanceTime(...)` entirely (the
    virtual clock freezes - nothing in flight can arrive, nothing rests
    through), and `handleCommand` rejects every `click`/`jump`/`rematch`
    with `ERROR|GAME_PAUSED` - the still-connected side gets no free window
    to act against a defenseless opponent who can't respond.
  - **Rematch**: either seated player (not a viewer) may send `"rematch"`
    once `engine.isGameOver()` and neither seat is currently vacated by a
    disconnect. `GameSession.requestRematch()` simply replaces the `engine`
    field wholesale with a fresh `GameEngine` over a brand-new standard
    board/`GameState` (which is why `engine` isn't `final` - see the class
    Javadoc) rather than adding any in-place reset to `GameEngine` itself -
    `gameengine/` stays untouched by this feature entirely. Also clears
    both selections, the pending-action watch-list, and `ratingApplied`, so
    the next game is scored independently of the one before it. Rejected
    with `ERROR|GAME_NOT_OVER` if the game is still in progress, or
    `ERROR|GAME_PAUSED` if a seat is currently vacated (nothing to fairly
    restart against).
  - **Concurrency**: `GameEngine`/`RealTimeArbiter` are plain,
    non-thread-safe classes by design (every other entry point drives them
    from one thread). Because Java-WebSocket dispatches connection
    callbacks on their own threads concurrently with the ticker/disconnect-
    timer thread, `GameSession` serializes **every** access to `engine`
    (and to the two selection fields) through one `synchronized` block.
    Don't add a new path into `engine` from `GameSession` without going
    through that same lock.
  - The first snapshot to report `gameOver()` **with an actual winner**
    (from a real king capture or a forced resignation - both flow through
    the same `GameState.setGameOver`) triggers a one-time ELO update
    (`applyRatingChangeIfGameJustEnded`, guarded by a `ratingApplied` flag)
    via the new `server.auth.RatingService` (wraps `EloCalculator` +
    `UserRepository.updateRating` as one small unit, the same way
    `AuthCommandParser`/`AuthController` were split out of the auth flow).
    A game that ends via `abandon()` - no winner - deliberately skips this
    check entirely: nobody's rating changes when nobody did anything wrong.
  - **server/auth/** - `AuthCommandParser` is the one place that knows the
    raw `"login/register <username> <password>"` wire text shape (mirrors
    `parsing.BoardMapper`/`PieceMapper` being kept separate from the engine
    that consumes their output) - `parse(message)` returns a `ParsedCommand`
    (mode + username + password) or `null` if malformed; it has no
    dependency on `UserRepository` or `AuthController`. `AuthController` is
    the controller for the login/register step: calls the parser, then
    calls into `UserRepository` (the service that owns account
    logic/persistence) to carry it out, returning an `Outcome` (malformed,
    or a `UserRepository.AuthResult`) that its one caller, `HttpApiServer`,
    translates into an HTTP reply (`AUTH_OK|token|rating`/`ERROR|reason`) -
    kept separate so this orchestration/logging isn't mixed into HTTP
    request handling, and `UserRepository` never has to see raw wire text at
    all. `UserRepository` (`sqlite-jdbc` locally/in tests, `postgresql`
    when deployed via Docker Compose - see "REST API / scalable-server
    design" below - picked by the `jdbc:` URL scheme it's constructed with;
    `CREATE TABLE IF NOT EXISTS` on construction, one connection per call -
    no pooling, there are at most a couple of players per process/shard)
    owns accounts: `register`/`authenticate` (returning an `AuthResult`
    with either a rating or a failure reason) and `updateRating`. `PasswordHasher` is
    salted SHA-256 - no bcrypt/argon2 dependency, adequate for this
    project's scope; a password is never stored or compared in the clear.
    `EloCalculator` is a pure, stateless standard ELO formula (K=32, no
    draws - a king capture or a resignation always has a winner; an
    abandoned/no-winner game, above, simply never reaches `EloCalculator`
    at all rather than being modeled as a draw). `RatingService` is the
    thin orchestration layer around it described above - computes both new
    ratings and persists them via `UserRepository.updateRating` as one call.
- **bus/Bus** - a pub/sub interface (`subscribe(topic, handler)` /
  `publish(topic, String payload)` - string payloads only, not arbitrary
  Java objects, since a real cross-process transport can only carry
  bytes/text). `InMemoryBus` (synchronous, same-JVM fan-out - what every
  unit test uses, and the default when `KFC_NATS_URL` isn't set) and
  `NatsBus` (a real NATS connection - `KFC_NATS_URL` set, the Docker
  Compose deployment) both implement it. `GameSession` publishes each
  broadcast's White-side snapshot (already encoded to its wire-text form,
  via `SnapshotCodec`) on `"room.<code>.snapshot"`, and each move/jump
  event on `"room.<code>.event"`. Sound and start/end-animation triggers
  (mentioned in the original CTD 26 brief) are meant to subscribe to future
  topics on this same bus once there are concrete assets/specs for them -
  none exist yet, so none are wired up; `NatsBus` genuinely working (not
  just `InMemoryBus`) is what makes a real, different-process subscriber
  possible once one exists.
- **logging/ActivityLog** - a tiny append-only timestamped text logger
  (`synchronized log(String)`, opens/appends the file fresh each call - not
  a real logging framework). One instance server-side (`logs/server.log`,
  shared across all of `KungFuChessServer`/`Lobby`/every `GameSession`);
  each `NetworkGameClient` creates its own (`logs/client-<username>.log`,
  lazily once the username is known) and logs what it sends/receives.

### REST API / scalable-server design

A course-staff reference design (API Gateway/REST, WS Gateway, Matchmaker,
Game Allocator, Game Server Shards, Observability; NATS/Redis, PostgreSQL,
Docker/Kubernetes) asked for a step toward a scalable server, being built
up in a small number of independently-committable steps - `Server_Design.md`
is the full writeup, including the current roadmap and exactly what's
deliberately not done yet (Observability beyond `ActivityLog`, Kubernetes
manifests). What *is* done so far: the API Gateway (`HttpApiServer`,
login/register/room-creation), the WS Gateway (`KungFuChessServer`), the
Matchmaker (`Matchmaker`, owning `MatchQueue` - the ELO-ranged quick-match
pairing algorithm), the Game Allocator (`GameAllocator`, owning
`ShardPicker` - static round-robin over a configured shard list), and
**two** Game Server Shard instances (`GameServerShard`, each owning its
own `Lobby`/every `GameSession` it seats) are six genuinely separate
process types in the Docker Compose deployment now, none holding a direct
Java reference into any of the others - see the `server/` bullets above
for exactly how each process boundary is crossed (`RoomCreator`/NATS
request-reply for room creation; `OutboundConnection`/
`GatewayCommandEnvelope`/fire-and-forget NATS publishes for the WS Gateway
↔ Game Server Shard boundary, now routed to a specific shard's own
`"shard.<id>.command"` subject rather than one shared one - see
`routeDistributedCommand`; a `GatewayCommandEnvelope` forwarded on
`"matchmaker.play"` and a `SeatPairEnvelope` published back on a specific
shard's `"shard.<id>.seat_pair"` for the Game Server Shard ↔ Matchmaker
boundary; `"allocator.assign"` NATS request-reply, via the shared
`GameAllocatorClient` helper, for whichever process needs to know which
shard a *brand-new* room should go to - per the original brief's own
wording, "Matchmaker" means quick-match pairing specifically, "Game
Allocator" means deciding which shard runs *every* room (matched or
manually created) - see `Server_Design.md`'s "Step 6a" for the full
routing design, including `RoomDirectory` (`roomCode → shardId`) and how
the WS Gateway learns which shard owns an already-established connection).
`UserRepository` now speaks either SQLite (local dev/tests, zero external
services) or PostgreSQL (the Docker Compose deployment);
`SessionTokenStore`/`ReconnectRegistry`/`RoomDirectory` (see above) now
speak either in-memory (same conditions) or Redis; `bus.Bus` now speaks
either in-memory or NATS. `Dockerfile` + `docker-compose.yml` run the
whole thing (Postgres, Redis, NATS, and six server-process containers -
`ws-gateway`, `api-gateway`, `matchmaker`, `game-allocator`,
`game-server-shard-1`, `game-server-shard-2`) as one command - `docker
compose up --build` (see "Commands" above). The Swing client is never
containerized - it's a desktop GUI, run locally against the exposed ports
(the WS Gateway's 8887 and the API Gateway's 8888 - it has no way to know,
or need to know, how many containers those two ports actually span, or how
many shards sit behind them).

This is phases 1-3 of a staged brief: pub/sub bus + WebSocket server + 2
players; SQLite-backed accounts + ELO rating; ELO-ranged quick-match,
rooms with spectators, disconnect pause/auto-abandon with rematch, and
activity logging. There
is no remaining known phase beyond this as of this writing - if the brief
gains new slides, treat them as a new stage rather than assuming scope
that isn't in the code.
