# Server Design

This documents where the Kung Fu Chess server stands against the course
staff's reference design for a scalable server (API Gateway/REST, WS
Gateway, Matchmaker, Game Allocator, Game Server Shards, Observability;
NATS/Redis, PostgreSQL, Docker/Kubernetes) - what maps to what today, the
scope decisions behind each step, and what's deliberately not done yet. See
`CLAUDE.md`'s "Networking" section for the full class-level writeup this
summarizes.

## Roadmap

Being built in a small number of independently-committable steps, each
kept fully working end to end before moving on:

1. ✅ **Basic networked server** - WebSocket server, SQLite accounts, ELO,
   matchmaking, rooms with spectators, disconnect/reconnect, rematch.
2. ✅ **REST API + PostgreSQL + Docker Compose** - login/register/room
   creation moved to REST; PostgreSQL for the deployed path.
3. ✅ **Redis (shared state) + NATS (internal bus)** - still one process;
   laid the groundwork step 4 actually needed.
4. ✅ **API Gateway split** into its own process.
5. ✅ **WebSocket Gateway split** from the Game Server Shard, refined so
   the Matchmaker is also its own process (not just logic bundled inside
   the Shard) - see "Step 5 refinement" below.
6a. ✅ **Game Allocator + multiple Game Server Shards** - see "Step 6a"
    below.
6b. ⬜ **Observability.**
6c. ⬜ **Kubernetes/K3s manifests.**
6d. ✅ **Main/Controller/Service split** across all five server processes -
    see "Step 6d" below.

## Component mapping

| Reference design | This project, today |
|---|---|
| API Gateway (REST: login, rooms, history) | `server.HttpApiServer` - `POST /api/login`, `POST /api/register`, `POST /api/rooms`. Runs as its own process, entry point `server.ApiGatewayMain` (own `main`, wires `server.ApiGateway`), in the Docker Compose deployment (see "Step 4" below) - separate from the WS Gateway/Game Server Shard, talking to it only over NATS request-reply. Still embeds in the same process for local/offline runs (no Redis/NATS configured). |
| WS Gateway (live connections, state updates) | `server.KungFuChessServerController`/`KungFuChessServerService` (entry point `server.KungFuChessServerMain`), now a genuinely separate process/container (`ws-gateway`) from a Game Server Shard in the Docker Compose deployment - see "Step 5" below. First message is still `attach <token>`; once attached, every command (`play`/`join_room <code>`/`click`/`jump`/`rematch`) is relayed to the right Shard over NATS rather than handled in-process. Still embeds `Lobby`/`GameSession` directly for local/offline runs (no Redis/NATS configured), unchanged. |
| Matchmaker | `server.MatchmakerController`/`MatchmakerService` (entry point `server.MatchmakerMain`) - its own process (needs only `KFC_NATS_URL`), owning `server.MatchQueue` (the ELO-ranged, ±100, quick-match pairing queue). Reached by a Game Server Shard forwarding "play" commands over NATS (`"matchmaker.play"`); on a match, asks the Game Allocator for a shard and publishes to that shard's own `"shard.<id>.seat_pair"` for it to seat via `Lobby.seatMatchedPair`. See "Step 5 refinement"/"Step 6a"/"Step 6d" below. |
| Game Allocator (decides which shard runs a room) | `server.GameAllocatorController`/`GameAllocatorService` (entry point `server.GameAllocatorMain`) - its own process (needs only `KFC_NATS_URL` + `KFC_SHARD_IDS`). Load-aware: queries each configured shard's `"shard.<id>.load"` (current in-progress game count) and picks the least-loaded, falling back to `server.ShardPicker`'s static round-robin if none respond in time. Answers `"allocator.assign"` NATS requests with the chosen shard id. See "Step 6a" below. |
| Game Server Shards (authoritative GameEngine, many instances) | `server.GameSession` + `gameengine.GameEngine` - exactly the same engine the offline client uses, inside `server.GameServerShardController`/`GameServerShardService` (entry point `server.GameServerShardMain`). **Two** instances now run in the Docker Compose deployment (`game-server-shard-1`/`-2`, each its own `KFC_SHARD_ID`), each with its own `Lobby`/rooms - see "Step 6a" below for how the WS Gateway/Matchmaker/API Gateway route to the right one. |
| Observability (logs, metrics, health checks, load tests) | `logging.ActivityLog` only (append-only text log, server-side + one per client). No metrics, no health-check endpoint, no load tests. |
| NATS / Redis (internal pub-sub between services) | `bus.Bus` - an interface now. `InMemoryBus` (local/offline runs, every unit test) or `NatsBus` (a real NATS connection, `KFC_NATS_URL` set - the Docker Compose deployment). Genuinely in active cross-process use as of step 5 - see below - not just a ready-but-unused abstraction any more. |
| Redis (sessions, active rooms, reconnect, matchmaking queue, room→shard routing) | **Partly done** - see "Step 3" below for exactly what moved to Redis and what's deliberately still in-process (`Lobby.rooms`/`sessionByConnection` - a live connection identity can never be shared state). `RoomDirectory` (`roomCode → shardId`, "Step 6a") and `MatchmakingQueueStore` (the Matchmaker's own waiting list, "Not done yet" below) are the newest Redis-backed stores, in the same dual-mode shape as `ReconnectRegistry`. |
| PostgreSQL (users, games, results, move history) | `server.auth.UserRepository` speaks PostgreSQL when deployed via Docker Compose (see below) - `users` table (accounts + rating). `server.auth.GameResultRepository`, sharing the same database, adds `games` (one row per finished game: room, both usernames, winner, ratings before/after) and `moves` (every move from both sides' logs) - see "Not done yet" below. |
| Docker Compose (small runnable version) | `Dockerfile` + `docker-compose.yml` at the repo root - `docker compose up --build` runs 9 containers: `postgres`, `redis`, `nats`, `ws-gateway`, `game-server-shard-1`, `game-server-shard-2`, `game-allocator`, `matchmaker`, `api-gateway`. |
| Kubernetes / K3s | Not started. |

## Step 3: Redis (shared state) + NATS (internal bus)

**The constraint that shaped this step**: a `WebSocket` connection object is
fundamentally process-local - it can never be serialized into Redis or
handed to another process; only the process that accepted that TCP
connection can ever write to it. So not everything in `Lobby` could
honestly move to Redis yet, and forcing it would have been busywork with no
real payoff before there's a second process to actually share the data
with:

- **Moved to Redis** (pure data, no connection objects, real value even
  today - e.g. survives a server restart): `SessionTokenStore`
  (`InMemorySessionTokenStore`/`RedisSessionTokenStore`, a Redis Hash per
  token - `username`/`rating` fields plus a native Redis `EXPIRE`, replacing
  the old lazy-prune-on-issue approach) and the reconnect lookup, extracted
  into a new `ReconnectRegistry` interface
  (`InMemoryReconnectRegistry`/`RedisReconnectRegistry`, `username →
  roomCode` - a plain string, not a `GameSession` object reference, with a
  24h TTL). Selected the same way `UserRepository` already picks SQLite vs.
  PostgreSQL: `KFC_REDIS_URL` set → Redis; absent → in-memory (every unit
  test, and any local/offline run).
- **Stayed in-process**: `Lobby.rooms` (`roomCode → GameSession` - the
  `GameSession` objects themselves only ever live in one JVM at this
  stage anyway), `Lobby.sessionByConnection` (`OutboundConnection →
  GameSession` - inherently local to whichever process holds the real
  connection identity, this will *never* move to Redis in any future step
  either), and the matchmaking queue (at this stage still `Lobby.play`'s
  own field; moved into `MatchQueue`'s own process in the step 5
  refinement below, but still in-memory there too - it stores a connection
  reference alongside username/rating, and moving just the data half to
  Redis now would add complexity with zero payoff until step 6 actually
  gives a second Game Server Shard process something to read it from -
  revisit then).

`bus.Bus` became an interface the same way `SessionTokenStore` did.
`InMemoryBus` is today's exact old behavior, renamed; `NatsBus` wraps a
real `io.nats.client.Connection` (`KFC_NATS_URL` set). This forced one real
fix: `Bus.publish` used to take an arbitrary `Object` payload, and
`GameSession.broadcastSnapshot` published a raw `GameSnapshot` Java object
- meaningless to actually put on a NATS subject (a cross-process transport
can only carry bytes/text). It now reuses the already-`SnapshotCodec`
-encoded wire string instead, so `Bus` is a genuinely uniform pub/sub
abstraction. There's still no real subscriber anywhere (same as before -
sound/animation triggers are still hypothetical), so this step is
necessarily unverified by anything actually *consuming* a NATS message
yet - that's what step 4/5 will finally exercise for real.

## Step 4: API Gateway split into its own process

The first *real* process split - not just "an abstraction that could
support it," an actual second container. `HttpApiServer` moves out of
`KungFuChessServer`'s JVM into `server.ApiGateway`, its own class with its
own `main`.

`/api/login`/`/api/register` needed **zero cross-process work** -
`AuthController`/`UserRepository` (Postgres) and `SessionTokenStore`
(Redis, since step 3) already work identically from any process.
`POST /api/rooms` was the one hard problem: `Lobby.createRoom` returns a
real `GameSession` reference internally, and a `GameSession` (like a
`WebSocket`, see step 3) can only ever live in the one process that owns
the Game Server Shard.

**Solution: a genuine NATS request-reply**, not a workaround - this is
exactly the API-Gateway-↔-NATS-↔-Game-Server-Shard arrow the reference
diagram already draws. New `server.RoomCreator` interface:
`LocalRoomCreator` (wraps a direct `Lobby` reference - still used whenever
`HttpApiServer` stays embedded) and `RemoteRoomCreator` (does a real
blocking `Connection.request("lobby.create_room", usernameBytes,
Duration.ofSeconds(5))` and reads the room code back from the reply,
throwing a `RoomCreationException` on a timeout - which `HttpApiServer`
turns into `503`/`ERROR|room service unavailable` rather than crashing).
The other end, `server.RoomCreationResponder`, runs inside
`KungFuChessServer`, subscribed on the same NATS connection the process
already opened for `Bus` (`NatsBus.rawConnection()` - no second,
redundant connection).

**Topology is inferred, not a new config knob** - reusing exactly the
signal step 3 already established: if `KFC_REDIS_URL` **and**
`KFC_NATS_URL` are both set, `KungFuChessServer` treats that as "this is a
real distributed deployment" and does **not** embed `HttpApiServer` at all
- it starts `RoomCreationResponder` instead, and a separate `ApiGateway`
process (started independently - `docker-compose.yml`'s new `api-gateway`
service, same image as `server`, just a different `command:` override) is
expected to be the one actually serving REST. If either is unset (the
untouched local/offline path - bare `java -cp ... server.KungFuChessServer`,
still documented in `CLAUDE.md` exactly as before), `HttpApiServer` stays
embedded with a `LocalRoomCreator`, unchanged behavior. `ApiGateway` itself
has no such fallback - it requires both env vars, since it only ever makes
sense in the distributed topology.

From `client.NetworkGameClient`'s point of view, **nothing changed** - same
host, same derived HTTP port (`wsPort + 1`), same wire protocol. It has no
way to know (or need to know) that its REST calls now land in a different
container than its WebSocket does - which is exactly the point of a clean
split.

## Step 5: WebSocket Gateway split from the Game Server Shard

The step this document itself already flagged as "the biggest remaining
step" - `server.KungFuChessServer` was both the WS Gateway *and* the Game
Server Shard in one process; this splits them for real, matching the
reference diagram's WS Gateway ↔ NATS ↔ Game Server Shards arrows.

**The key decision: a type substitution, not a rewrite of `GameSession`.**
`GameSession`/`Lobby` addressed White, Black, and every viewer directly as
`org.java_websocket.WebSocket`, calling `.send()`/`.isOpen()` on it for
*every* outbound message (`WELCOME`, `STATE`, `EVENT`, `ERROR`,
`COMMAND_RESULT`, `OPPONENT_DISCONNECTED`/`RECONNECTED`) - already the
entire outbound mechanism. Once the Shard is a separate process it can
never hold a real `WebSocket` (same constraint as `GameSession` itself
in step 3/4). Rather than redesigning that addressing model around NATS
broadcast topics, a small interface -

```java
interface OutboundConnection { void send(String message); boolean isOpen(); }
```

- replaces `WebSocket` as `GameSession`/`Lobby`'s field/parameter type,
**with no other logic change at all**. `LocalOutboundConnection` wraps a
real `WebSocket` (the still-supported embedded/local topology);
`RemoteOutboundConnection` wraps a `connectionId` and publishes to
`"conn.<connectionId>.out"` - the Gateway is already subscribed to it per
connection and relays verbatim to the real socket.

This is what kept the step's risk contained despite its size: the existing
test double, `tests.FakeWebSocket`, already had matching `send`/`isOpen`
methods, so making it also `implements OutboundConnection` meant
`LobbyTest`/`GameSessionTest` needed **no other changes** - every existing
test keeps exercising the exact same `Lobby`/`GameSession` logic, just
through the new interface.

**The Gateway needed no request-reply at all - just fire-and-forget.**
Since every outbound reply (including immediate acks like
`COMMAND_RESULT|SUCCESS`/`WAITING`/`ERROR|unknown room code`) already
flows back out through `OutboundConnection.send()`, the Gateway doesn't
need to correlate a request with its response the way `RemoteRoomCreator`
(step 4) does - it just publishes and moves on:

- WS open → generate a `connectionId`, subscribe to its own
  `"conn.<connectionId>.out"`, relay whatever arrives there to the socket.
- WS message, pre-attach → handled 100% locally (`SessionTokenStore` is
  already Redis-backed and directly reachable); on success, fire-and-forget
  publish `"gateway.reconnect"` (today's `Lobby.tryReconnect` return value
  was already ignored by its caller, confirmed before making this
  fire-and-forget).
- WS message, post-attach → fire-and-forget publish `"gateway.command"`
  with a `GatewayCommandEnvelope` (`connectionId|username|rating|rawCommand`)
  - `server.GameServerShard` decides routing using the *same* logic
  `KungFuChessServer.doOnMessage`/`handleLobbyCommand` already had, just
  moved onto the Shard and keyed by `connectionId`.
- WS close → fire-and-forget publish `"gateway.disconnect"`
  (`connectionId`), then unsubscribe locally.

The Gateway ends up with **no** `Lobby`/`GameSession`/`UserRepository`
reference at all in the distributed topology - genuinely just a relay.
`server.GameServerShard` (new class + `main`) is the real Game Server
Shard from now on: owns `UserRepository`, `Lobby`, and (moved here from
`KungFuChessServer`) `RoomCreationResponder`; caches one
`RemoteOutboundConnection` per `connectionId` (map lookups like
`sessionByConnection.get(connection)` need the *same* instance handed back
across multiple messages, exactly like the Gateway already relies on real
`WebSocket` reference identity today).

**Topology inferred the same way as steps 3/4** - `KFC_REDIS_URL` and
`KFC_NATS_URL` both set → `KungFuChessServer` is the pure relay described
above; either unset → unchanged local/offline embedding.
`docker-compose.yml`'s `server` service is renamed `ws-gateway` (matching
`api-gateway`'s naming now that both are thin relays), and a new
`game-server-shard` service runs `server.GameServerShard` - no published
port, only reachable over NATS/Redis/Postgres, exactly like a real
internal service.

### Step 5 refinement: the Matchmaker becomes its own process too

The first pass above left quick-match pairing (`Lobby.play`) bundled
inside the Game Server Shard - functionally fine, but not what the
reference diagram means by "Matchmaker": a component that "pairs players
for quick-match" specifically, distinct from the Shard that actually runs
games. Room creation/joining correctly stays Shard-adjacent (there's no
Game Allocator yet to decide *which* shard a room belongs on - see the
roadmap), so only the ELO-ranged pairing algorithm itself needed to move.

**The extraction**: the queue/timeout/pairing logic (`Lobby.play`,
`cancelQueued`, the timeout scheduler, the `Waiting` entry) moved
verbatim into a new, topology-agnostic `server.MatchQueue` (same
`OutboundConnection`-based design as `Lobby`/`GameSession`, so it's
directly unit-tested in isolation - see `tests.MatchmakerTest`). Instead
of building a `GameSession` itself on a match, it calls a
`MatchFoundListener` callback; `Lobby` gained one new method,
`seatMatchedPair(...)`, which is exactly what `play()`'s old "matched"
branch used to do inline, now reachable by anyone.

**The Gateway needed zero changes.** It already fire-and-forget publishes
every post-attach command to `"gateway.command"` unchanged. The only
routing change is inside `GameServerShard`: its `"play"` branch, instead
of calling a (now-removed) `Lobby.play`, just republishes the exact
envelope it already has onward to `"matchmaker.play"` - the same
"this isn't mine to handle, forward it" pattern `HttpApiServer` already
uses for room creation via `RoomCreator`. The stuck-in-a-finished-session
check (`isGameOver`/`leaveFinishedSessionIfAny`) still runs first,
unaffected - so that earlier bug fix isn't at risk of regressing.

The new `server.Matchmaker` process (own `main`, needs only
`KFC_NATS_URL` - no Postgres/Redis at all, since `MatchQueue` is pure
in-memory) owns one `MatchQueue`, wired to publish a `SeatPairEnvelope`
(`connectionIdA|userA|ratingA|connectionIdB|userB|ratingB`) to
`"shard.seat_pair"` on a match - `GameServerShard` subscribes and calls
`Lobby.seatMatchedPair`. It also subscribes, independently, to the very
same `"gateway.disconnect"` subject `GameServerShard` already listens on
(plain NATS pub/sub naturally supports more than one subscriber per
subject), so it can drop a disconnected player from its own queue without
the Gateway ever needing to know two different services now care about
disconnects.

The embedded/local topology gets its own in-process `MatchQueue` too
(constructed by `KungFuChessServer` alongside its local `Lobby`, listener
wired straight to `lobby::seatMatchedPair` - no NATS involved), so
behavior there is unchanged. `docker-compose.yml` gained a `matchmaker`
service - `depends_on: nats` only, no published port, no
Postgres/Redis env vars at all.

## Step 6a: Game Allocator + multiple Game Server Shards

Per the original brief, two components were still essentially missing:
**Game Allocator** and **multiple Game Server Shards** (there was only
ever one shard, so nothing to allocate between). This step implements
both together, since a Game Allocator is meaningless without a second
shard to allocate to.

**The real problem this step solves is routing, not just running a second
container.** With multiple shards, each owning a disjoint set of rooms in
its own local `Lobby`, the WS Gateway must route every post-attach command
to the *one* shard that actually owns that connection's game - the wrong
shard (or a broadcast to all of them) either drops the command silently
or, worse, causes several shards to reply to the same client at once.

**Allocation policy: static round-robin**, deliberately simpler than a
load-aware policy (which would need shards to report their own game
counts somewhere - real, but out of scope for this step; a natural future
extension once Observability exists). `server.ShardPicker` is a pure,
directly-unit-tested `AtomicInteger` round-robin over a fixed shard list
(`tests.ShardPickerTest`) - the same "pure logic class + thin NATS-process
wrapper" split already used for `MatchQueue`/`Matchmaker`. `server.GameAllocator`
wraps it behind one NATS request-reply subject, `"allocator.assign"`
(empty request, shardId reply), mirroring `RoomCreationResponder`'s own
shape exactly. Needs only `KFC_NATS_URL` + `KFC_SHARD_IDS` (comma-separated) -
no Postgres/Redis at all, since round-robin over a static list needs no
persistent state.

**Every `GameServerShard` gets its own identity, `KFC_SHARD_ID`**, and:
- Subscribes to `"shard.<id>.command"` instead of the old global
  `"gateway.command"`.
- Subscribes to `"shard.<id>.create_room"` instead of one fixed
  `RoomCreationResponder` subject.
- Subscribes to `"shard.<id>.seat_pair"` instead of one fixed
  `"shard.seat_pair"`.
- Still subscribes to the *shared* `"gateway.reconnect"`/
  `"gateway.disconnect"` subjects, unchanged - broadcasting these to every
  shard turns out to already be safe: a non-owning shard's
  `Lobby.tryReconnect`/`handleDisconnect` simply finds nothing local and
  no-ops, since `ReconnectRegistry` is Redis-shared but `Lobby.rooms` is
  not, so only the true owner ever matches. (Regular gameplay commands
  can't use this same "broadcast + silent no-op" trick - a non-owning
  shard's fallback for an unrecognized click/jump is to reply with an
  `ERROR`, which would reach the client from every non-owning shard at
  once. That's why those specifically need precise per-shard routing,
  below, while reconnect/disconnect don't.)
- After a connectionId is newly claimed - `joinRoom`/`seatMatchedPair`/
  `tryReconnect` all succeeding - publishes a tiny NATS notification,
  `"conn.<connectionId>.shard"` = its own shardId, so the Gateway learns
  (or updates) which shard now owns that connection.

**`Lobby` gained an optional `shardId` + `RoomDirectory`** (`roomCode →
shardId`, same `InMemoryRoomDirectory`/`RedisRoomDirectory` dual-mode
shape as `ReconnectRegistry`), threaded through a new fullest constructor
overload - the existing shorter constructors delegate to it with
`shardId=null`/`new InMemoryRoomDirectory()`, so **every existing
`new Lobby(...)` call site (4 of them, including every test) needed zero
changes**. `createRoom`/`seatMatchedPair` call
`roomDirectory.record(code, shardId)` right after minting a code.

**WS Gateway routing** (`KungFuChessServer.routeDistributedCommand`):
- `"play"` → unchanged, still `"matchmaker.play"` (Matchmaker asks the
  Allocator itself once matched - the Gateway needs no shard knowledge for
  this path at all).
- `"join_room <code>"` → resolved **fresh, every time**, via
  `RoomDirectory.shardFor(code)` - deliberately never the cached shard
  below, because a stale entry from a previous, now-finished game on this
  same connection must never leak into routing a request for a brand-new
  room. An unknown code gets a clean `ERROR|unknown room code` straight
  from the Gateway - no shard round trip needed at all.
- Every other command (click/jump/rematch) relies entirely on a local
  `Map<WebSocket, String> shardOf` cache, populated by subscribing to
  `"conn.<connectionId>.shard"` (added alongside the pre-existing
  `"conn.<connectionId>.out"` subscription, same lifecycle - see `onOpen`/
  `onClose`).

**REST room creation** (`RemoteRoomCreator`): first asks the Allocator for
a shardId, then sends the room-creation request to that shard's specific
`"shard.<id>.create_room"` subject instead of one fixed subject.
`RoomCreationResponder`'s constructor now takes its owning shard's id.

**`GameSession`/`GameEngine` needed zero changes** - each session already
fully self-ticks via its own `ScheduledExecutorService`, entirely
self-contained once a shard owns it. Sharding is purely a routing concern
resolved before a command ever reaches a session.

`docker-compose.yml`: `game-server-shard` (one service) became two
explicit services, `game-server-shard-1`/`game-server-shard-2`, each its
own `KFC_SHARD_ID` - plain Compose has no per-replica env var templating
(that needs Swarm), so two named services was simpler than fighting
`--scale` for this. A new `game-allocator` service needs only
`KFC_NATS_URL` + `KFC_SHARD_IDS` - no Postgres/Redis, no published port.

**Verified live**: round-robin allocation confirmed by grepping each
shard's own log for room-creation entries (alternates shard-1/shard-2/
shard-1/... across both REST-created and quick-matched rooms); full
gameplay (click + jump) through rooms landing on either shard, proving
`shardOf`-cache routing correctly keeps directing follow-up commands to
whichever shard actually owns the session; `join_room` with a bogus code
returns a clean `ERROR` with no shard round trip; disconnect/reconnect
verified across shards (exercises the still-shared `gateway.reconnect`
broadcast path); local/offline mode (no Docker) confirmed unaffected.

## What changed in step 2 (REST + PostgreSQL)

1. **Login/register/room-creation moved off the WebSocket, onto REST.**
   `HttpApiServer` (built on the JDK's own `com.sun.net.httpserver`, no new
   web-framework dependency) exposes `POST /api/login`, `POST /api/register`
   (form-encoded `username`/`password`, replies `AUTH_OK|token|rating` or
   `ERROR|reason`) and `POST /api/rooms` (form-encoded `token`, replies
   `ROOM_CREATED|code`). The WebSocket's first message becomes
   `attach <token>` instead of raw login/register text.

   **Matchmaking (`play`) and joining a room (`join_room <code>`)
   deliberately stay on the WebSocket, not REST.** Both are asynchronous by
   nature - `play` may wait up to 60 seconds for an opponent, and actually
   seating a player only makes sense once there's a live connection to push
   `WELCOME`/`STATE` down. In the reference design this asynchronous
   notification is exactly what the Matchmaker → NATS → WS Gateway path is
   for; collapsed into one process, the already-open, already-authenticated
   WebSocket is that same path, just without the extra hop. Forcing this
   through REST would mean the client polling a queue-status endpoint for
   no real benefit.

   The bearer token is **multi-use within a TTL** (10 minutes), not
   one-shot: it authenticates both the WS `attach` handshake and a
   `POST /api/rooms` call, in either order, since the client's login and
   later "create a room" action can happen with an arbitrary gap between
   them.

2. **PostgreSQL for the deployed/Docker path, SQLite kept for local dev and
   unit tests.** `UserRepository`'s constructor now accepts either a bare
   SQLite file path (legacy behavior, unchanged - still what
   `tools/run-tests.ps1` and every existing test use, so running the test
   suite needs **zero external services**) or a full JDBC URL (what
   `docker-compose.yml` passes: `jdbc:postgresql://postgres:5432/...`). The
   schema itself (`CREATE TABLE IF NOT EXISTS users (username TEXT PRIMARY
   KEY, salt TEXT NOT NULL, password_hash TEXT NOT NULL, rating INTEGER NOT
   NULL)`, plain parameterized `INSERT`/`SELECT`/`UPDATE`) is already
   ANSI-portable - no per-dialect branching was needed. Both JDBC drivers
   (`sqlite-jdbc`, `postgresql`) sit on the classpath together;
   `DriverManager` picks the right one from the URL scheme at runtime.

   This is a deliberate compromise, not an oversight: a hard PostgreSQL
   dependency for every unit test run would mean contributors/graders need
   a database standing up just to run `javac`/`tools/run-tests.ps1`, which
   this project has never required.

3. **Docker Compose** (`Dockerfile` + `docker-compose.yml`, repo root) runs
   the server containerized against a real `postgres:16` container instead
   of a SQLite file - the actual point of swapping databases, since a
   per-container SQLite file wouldn't outlive `docker compose down` and
   can't be shared if this ever runs as more than one container. The
   `Dockerfile` mirrors `tools/fetch-libs.ps1`'s exact jar URLs/versions via
   `curl` (there's no cross-platform script runner in the build image to
   share that file directly with) - **a known, accepted duplication**: if a
   dependency version changes, both files need updating by hand. The Swing
   client (`NetworkGuiMain`) is **not** containerized - it's a desktop GUI;
   it runs locally against the container's exposed ports (8887 WS, 8888
   HTTP).

## Step 6d: Main/Controller/Service split across every server process

Before this step, each of the five server processes (`KungFuChessServer`,
`GameServerShard`, `Matchmaker`, `GameAllocator`, `ApiGateway`) mixed three
concerns into one class: the JVM entry point (`main`, reading env vars),
the endpoint layer (NATS subscriptions / WebSocket callbacks / HTTP
routes), and the actual decision logic. This didn't match the pattern the
local/offline game already used - `Main.java` is a tiny entry point that
immediately hands off to `controller.BoardController`, which owns all the
real logic.

**The split, applied uniformly**: `XxxMain` (own `main`, reads env/args,
wires the other two, starts - no logic of its own), `XxxController` (the
endpoints only - decodes each request to its raw fields, delegates
everything else), `XxxService` (all the decision logic, talking to the
deeper layers - `Lobby`, `MatchQueue`, `ShardPicker` - that this step never
touched). Concretely: `KungFuChessServerMain`/`Controller`/`Service`,
`GameServerShardMain`/`Controller`/`Service` (also absorbed the old
standalone `RoomCreationResponder` class as a `GameServerShardService`
method, `createRoom(username)`, for full consistency),
`MatchmakerMain`/`Controller`/`Service`, and
`GameAllocatorMain`/`Controller`/`Service`. Note this had already happened
in spirit for the pure-logic classes underneath two of these -
`MatchQueue`/`ShardPicker` were always the "service" behind
`Matchmaker`/`GameAllocator` - this step made the naming and the split
explicit and uniform across the whole `server/` package, not just those
two.

`ApiGateway` is the one process that doesn't get a full three-way split:
`HttpApiServer` already *was* its Controller (HTTP route handlers, no
business logic), and `server.auth.AuthController` already *was* a Service
in every way but name - it never touched HTTP or WebSocket at all, just
orchestrated `AuthCommandParser` + `UserRepository` - so it was renamed to
`server.auth.AuthService` rather than restructured. `ApiGateway` itself
keeps its existing role, constructing `HttpApiServer` and its dependencies -
the same "wiring" role `XxxService` plays in the other four processes, just
under its original name since nothing about its actual code changed.

**Follow-up**: once split, the flat `server/` package (~36 files) felt
cluttered - a second pass reorganized every `XxxMain`/`XxxController`/
`XxxService` into matching folders/packages, `server/main/`
(`server.main`), `server/controller/` (`server.controller` - also gained
`HttpApiServer`, since it already played that role), and `server/service/`
(`server.service` - also gained `ApiGateway`, for the same reason) -
purely a physical reorganization, no behavior change. `server/auth/` was
untouched (a separate, pre-existing subpackage this reorganization doesn't
overlap with).

**A third pass** split the ~23 classes that stayed in the `server`
package root even further, along the lines of the dual-mode families
already established (interface + `InMemoryXxx` + `RedisXxx`) that were
already sitting right next to each other: `server/connection/`
(`OutboundConnection`, `LocalOutboundConnection`, `RemoteOutboundConnection`),
`server/room/` (`RoomCreator`, `LocalRoomCreator`, `RemoteRoomCreator`,
`RoomCreationException`, `RoomDirectory`, `InMemoryRoomDirectory`,
`RedisRoomDirectory`), and `server/reconnect/` (`ReconnectRegistry`,
`InMemoryReconnectRegistry`, `RedisReconnectRegistry`). What's left
directly in `server/` (`Lobby`, `GameSession`, `Protocol`, `Seat`,
`SnapshotCodec`, `GatewayCommandEnvelope`, `SeatPairEnvelope`,
`MatchQueue`, `ShardPicker`, `GameAllocatorClient`) doesn't share this kind
of tight family resemblance with anything else, so it stayed put rather
than being forced into an artificial grouping.

**Verified**: full test suite unaffected (315 tests, same count before and
after - none of the five split process classes were ever constructed
directly by a test, only from their own `main`, confirmed by grep before
starting); `docker-compose.yml`/`Dockerfile` entrypoints updated to the new
`*Main` classes; full Docker Compose smoke test (REST room creation +
gameplay, quick-match + disconnect/reconnect, unknown room code) and the
untouched local/offline path (`server.KungFuChessServerMain`, no Docker)
both re-verified end to end, matching every prior step's verification
rigor.

## All six Gateway/Matchmaker/Allocator/Shard roles are now separate processes

`api-gateway`, `ws-gateway`, `matchmaker`, `game-allocator`, and two
`game-server-shard-*` instances are six genuinely independent process
types (nine containers total with `postgres`/`redis`/`nats`) as of step 6a,
talking to each other only over NATS/Redis/Postgres - none of them hold a
direct Java reference into any of the others.

## Not done yet (future work)

- **Observability**: metrics, a health-check endpoint, load tests (step
  6b). Only `ActivityLog`'s append-only text log exists today.
- **Kubernetes/K3s manifests** (step 6c) - only Docker Compose exists.

Three smaller gaps that used to be listed here are now closed:

- **`MatchQueue`'s queue moving to Redis** - `server.MatchmakingQueueStore`
  (`InMemoryMatchmakingQueueStore`/`RedisMatchmakingQueueStore`, same
  dual-mode split as `ReconnectRegistry`/`RoomDirectory`) now backs the
  waiting list itself when `KFC_REDIS_URL` is set (`MatchmakerService`),
  so a queued player survives the Matchmaker process restarting instead of
  just vanishing. Still deliberately scoped to a single active Matchmaker
  instance at a time - nothing coordinates two instances racing to match
  the same entry, since only one is ever actually deployed. The live
  `OutboundConnection` objects and timeout tasks always stay local to
  whichever process is running (see `MatchQueue`'s class Javadoc) - a
  stale store entry a restarted instance never registered locally is
  self-pruned the next time `play()` scans past it.
- **`games`/`moves` tables** in PostgreSQL/SQLite - `server.auth.GameResultRepository`
  (same one-connection-per-call, `CREATE TABLE IF NOT EXISTS` pattern as
  `UserRepository`, sharing its exact database via `UserRepository.getJdbcUrl()`)
  now records every finished game's result and both sides' full move logs
  the moment `GameSession.applyRatingChangeIfGameJustEnded` scores it -
  right next to the existing ELO update, guarded by the same
  `ratingApplied` flag so it only ever fires once per game.
- **Load-aware shard allocation** - `GameAllocatorService.assignShard()`
  now asks every configured shard's own `"shard.<id>.load"` (answered by
  `GameServerShardController`/`GameServerShardService.activeGameCount()`,
  backed by `Lobby.activeGameCount()`) how many in-progress games it's
  hosting, and picks the least-loaded one. A shard that doesn't answer in
  time is simply excluded from that round, not treated as an error; if
  none respond at all, `ShardPicker`'s static round-robin (kept, unchanged)
  is still there as a fallback so a room can always be assigned.
