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
5. ✅ **WebSocket Gateway split** from the Game Server Shard - this step.
6. ⬜ **Game Allocator + multiple Game Server Shards.**
7. ⬜ **Observability + Kubernetes/K3s manifests.**

## Component mapping

| Reference design | This project, today |
|---|---|
| API Gateway (REST: login, rooms, history) | `server.HttpApiServer` - `POST /api/login`, `POST /api/register`, `POST /api/rooms`. Runs as its own process, `server.ApiGateway`, in the Docker Compose deployment (see "Step 4" below) - separate from the WS Gateway/Game Server Shard, talking to it only over NATS request-reply. Still embeds in the same process for local/offline runs (no Redis/NATS configured). |
| WS Gateway (live connections, state updates) | `server.KungFuChessServer`, now a genuinely separate process/container (`ws-gateway`) from the Game Server Shard in the Docker Compose deployment - see "Step 5" below. First message is still `attach <token>`; once attached, every command (`play`/`join_room <code>`/`click`/`jump`/`rematch`) is relayed to the Shard over NATS rather than handled in-process. Still embeds `Lobby`/`GameSession` directly for local/offline runs (no Redis/NATS configured), unchanged. |
| Matchmaker | `server.Lobby.play(...)` - ELO-ranged (±100) matchmaking queue. Lives inside the Game Server Shard now (`server.GameServerShard`), reached by the WS Gateway only through the NATS relay described in "Step 5". |
| Game Allocator (decides which shard runs a room) | **Not implemented.** There's only ever one Game Server Shard (see below), so there's nothing to allocate between yet. |
| Game Server Shards (authoritative GameEngine, many instances) | `server.GameSession` + `gameengine.GameEngine` - exactly the same engine the offline client uses. Now its own process, `server.GameServerShard` (see "Step 5"), separate from the WS/API Gateways. Always exactly **one** shard - see step 6. |
| Observability (logs, metrics, health checks, load tests) | `logging.ActivityLog` only (append-only text log, server-side + one per client). No metrics, no health-check endpoint, no load tests. |
| NATS / Redis (internal pub-sub between services) | `bus.Bus` - an interface now. `InMemoryBus` (local/offline runs, every unit test) or `NatsBus` (a real NATS connection, `KFC_NATS_URL` set - the Docker Compose deployment). Genuinely in active cross-process use as of step 5 - see below - not just a ready-but-unused abstraction any more. |
| Redis (sessions, active rooms, reconnect, matchmaking queue) | **Partly done** - see "Step 3" below for exactly what moved to Redis and what's deliberately still in-process (`Lobby.rooms`/`sessionByConnection`/`matchmakingQueue`). |
| PostgreSQL (users, games, results, move history) | `server.auth.UserRepository` now speaks PostgreSQL when deployed via Docker Compose (see below) - `users` table only (accounts + rating). No `games`/`results`/move-history tables exist yet - move history lives only in each `GameSession`'s in-memory log, never persisted. |
| Docker Compose (small runnable version) | `Dockerfile` + `docker-compose.yml` at the repo root - `docker compose up --build` runs 6 containers: `postgres`, `redis`, `nats`, `ws-gateway`, `game-server-shard`, `api-gateway`. |
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
  either), and `Lobby.matchmakingQueue` (today it stores a connection
  reference alongside username/rating; moving just the data half to Redis
  now would add complexity with zero payoff until step 6 actually gives a
  second Game Server Shard process something to read it from - revisit
  then).

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

## All three Gateway/Matchmaker/Shard roles are now separate processes

`api-gateway`, `ws-gateway`, and `game-server-shard` are three genuinely
independent containers as of step 5, talking to each other only over
NATS/Redis/Postgres - none of them hold a direct Java reference into
either of the others. What's still a deliberate scope decision, not the
end state: there's only ever **one** Game Server Shard process, so there's
nothing yet for a Game Allocator to allocate between - that's step 6.

## Not done yet (future work)

- **Game Allocator + multiple Game Server Shards** (step 6) - the biggest
  remaining step. Needs a component deciding which of several shard
  processes a new room goes to, and `ws-gateway` needing to know which
  shard owns a given room's `connectionId` (today it just publishes to one
  fixed `"gateway.command"` subject, since there's only one shard
  listening).
- **`Lobby.matchmakingQueue` moving to Redis** - deliberately deferred to
  step 6 alongside the Game Allocator, since it only gains real value once
  there's more than one process that needs to see the same queue (see
  "Step 3" above).
- **`games`/`results`/move-history tables** in PostgreSQL - move history
  currently lives only in each `GameSession`'s in-memory log.
- **Observability**: metrics, a health-check endpoint, load tests (step 7).
  Only `ActivityLog`'s append-only text log exists today.
- **Kubernetes/K3s manifests** (step 7) - only Docker Compose exists.
