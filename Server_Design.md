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
4. ✅ **API Gateway split** into its own process - this step.
5. ⬜ **WebSocket Gateway split** from the Game Server Shard.
6. ⬜ **Game Allocator + multiple Game Server Shards.**
7. ⬜ **Observability + Kubernetes/K3s manifests.**

## Component mapping

| Reference design | This project, today |
|---|---|
| API Gateway (REST: login, rooms, history) | `server.HttpApiServer` - `POST /api/login`, `POST /api/register`, `POST /api/rooms`. Runs as its own process, `server.ApiGateway`, in the Docker Compose deployment (see "Step 4" below) - separate from the WS Gateway/Game Server Shard, talking to it only over NATS request-reply. Still embeds in the same process for local/offline runs (no Redis/NATS configured). |
| WS Gateway (live connections, state updates) | `server.KungFuChessServer` (a `WebSocketServer`). First message is `attach <token>` (a bearer token minted by the REST login/register call); once attached, `play`/`join_room <code>`/`click`/`jump`/`rematch` are unchanged from before this iteration. Still bundled with the one Game Server Shard - see step 5. |
| Matchmaker | `server.Lobby.play(...)` - ELO-ranged (±100) matchmaking queue, in-process. |
| Game Allocator (decides which shard runs a room) | **Not implemented.** There's only ever one Game Server Shard (see below), so there's nothing to allocate between yet. |
| Game Server Shards (authoritative GameEngine, many instances) | `server.GameSession` + `gameengine.GameEngine` - exactly the same engine the offline client uses. Always exactly **one** shard: the same JVM as the WS/API Gateway. |
| Observability (logs, metrics, health checks, load tests) | `logging.ActivityLog` only (append-only text log, server-side + one per client). No metrics, no health-check endpoint, no load tests. |
| NATS / Redis (internal pub-sub between services) | `bus.Bus` - an interface now. `InMemoryBus` (local/offline runs, every unit test) or `NatsBus` (a real NATS connection, `KFC_NATS_URL` set - the Docker Compose deployment). Genuinely cross-process-capable now, but there's still only one process publishing *or* subscribing - see "Still one process". |
| Redis (sessions, active rooms, reconnect, matchmaking queue) | **Partly done** - see "Step 3" below for exactly what moved to Redis and what's deliberately still in-process (`Lobby.rooms`/`sessionByConnection`/`matchmakingQueue`, and `KungFuChessServer`'s `usernames`/`ratings`). |
| PostgreSQL (users, games, results, move history) | `server.auth.UserRepository` now speaks PostgreSQL when deployed via Docker Compose (see below) - `users` table only (accounts + rating). No `games`/`results`/move-history tables exist yet - move history lives only in each `GameSession`'s in-memory log, never persisted. |
| Docker Compose (small runnable version) | `Dockerfile` + `docker-compose.yml` at the repo root - `docker compose up --build` runs 5 containers: `postgres`, `redis`, `nats`, `server` (WS Gateway + Game Server Shard), `api-gateway`. |
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
  stage anyway), `Lobby.sessionByConnection` (`WebSocket → GameSession` -
  inherently local, this will *never* move to Redis in any future step
  either), and `Lobby.matchmakingQueue` (today it stores live `WebSocket`
  refs alongside username/rating; moving just the data half to Redis now
  would add complexity with zero payoff until step 4/5 actually gives a
  second process something to read it from - revisit then).

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

## Still one process for the WS Gateway + Game Server Shard

The API Gateway is now genuinely separate (step 4), but `KungFuChessServer`
still bundles the WS Gateway and the one Game Server Shard together in a
single JVM / container. This is a deliberate scope decision, not the end
state: a live `WebSocket` connection and a `GameSession` are still things
only one process can hold, and splitting the Gateway from the Shard means
the Gateway would need to forward every `click`/`jump`/`play`/`join_room`
over NATS and relay `STATE`/`EVENT` broadcasts back - real, non-trivial
work, exactly what step 5 is for. Attempting a full split into
independently deployable services in one pass risked landing "a lot that
doesn't run" instead of "a small step that works" - the explicit
instruction behind this whole roadmap.

## Not done yet (future work)

- **WebSocket Gateway split from the Game Server Shard** (step 5) - the
  biggest remaining step: `KungFuChessServer` forwarding commands to a
  separate shard process over NATS instead of calling `GameSession`
  directly, and relaying `STATE`/`EVENT` broadcasts back the same way.
- **Game Allocator + multiple Game Server Shards** (step 6). Needs the
  Redis-backed state from step 3 (already in place) plus the Gateway split
  above, so an allocator can hand a new room to any of several shard
  processes and a Gateway can find which shard owns a given room.
- **`Lobby.matchmakingQueue` moving to Redis** - deliberately deferred to
  step 6 alongside the Game Allocator, since it only gains real value once
  there's more than one process that needs to see the same queue (see
  "Step 3" above).
- **`games`/`results`/move-history tables** in PostgreSQL - move history
  currently lives only in each `GameSession`'s in-memory log.
- **Observability**: metrics, a health-check endpoint, load tests (step 7).
  Only `ActivityLog`'s append-only text log exists today.
- **Kubernetes/K3s manifests** (step 7) - only Docker Compose exists.
