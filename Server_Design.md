# Server Design

This documents where the Kung Fu Chess server stands against the course
staff's reference design for a scalable server (API Gateway/REST, WS
Gateway, Matchmaker, Game Allocator, Game Server Shards, Observability;
NATS/Redis, PostgreSQL, Docker/Kubernetes) - what maps to what today, the
scope decisions behind this iteration, and what's deliberately not done
yet. See `CLAUDE.md`'s "Networking" section for the full class-level
writeup this summarizes.

## Component mapping

| Reference design | This project, today |
|---|---|
| API Gateway (REST: login, rooms, history) | `server.HttpApiServer` - `POST /api/login`, `POST /api/register`, `POST /api/rooms`. Runs in the **same process** as the WS Gateway (see "Still one process" below), not a separate deployable service. |
| WS Gateway (live connections, state updates) | `server.KungFuChessServer` (a `WebSocketServer`). First message is `attach <token>` (a bearer token minted by the REST login/register call); once attached, `play`/`join_room <code>`/`click`/`jump`/`rematch` are unchanged from before this iteration. |
| Matchmaker | `server.Lobby.play(...)` - ELO-ranged (±100) matchmaking queue, in-process. |
| Game Allocator (decides which shard runs a room) | **Not implemented.** There's only ever one Game Server Shard (see below), so there's nothing to allocate between yet. |
| Game Server Shards (authoritative GameEngine, many instances) | `server.GameSession` + `gameengine.GameEngine` - exactly the same engine the offline client uses. Always exactly **one** shard: the same JVM as the WS/API Gateway. |
| Observability (logs, metrics, health checks, load tests) | `logging.ActivityLog` only (append-only text log, server-side + one per client). No metrics, no health-check endpoint, no load tests. |
| NATS / Redis (internal pub-sub between services) | `bus.Bus` - synchronous, **in-process** pub/sub. There is nothing external to publish to yet, since every "service" above is one process. |
| Redis (sessions, active rooms, reconnect, matchmaking queue) | All in-memory `java.util.Map`/`List` fields on `Lobby` (`rooms`, `sessionByConnection`, `sessionByUsername`, `matchmakingQueue`) and `KungFuChessServer` (`usernames`, `ratings`) - and `server.auth.SessionTokenStore` for the new REST bearer tokens. Lost on restart; not shared across processes. |
| PostgreSQL (users, games, results, move history) | `server.auth.UserRepository` now speaks PostgreSQL when deployed via Docker Compose (see below) - `users` table only (accounts + rating). No `games`/`results`/move-history tables exist yet - move history lives only in each `GameSession`'s in-memory log, never persisted. |
| Docker Compose (small runnable version) | `Dockerfile` + `docker-compose.yml` at the repo root - `docker compose up --build` runs the server + PostgreSQL. |
| Kubernetes / K3s | Not started. |

## What changed in this iteration

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

## Still one process

Everything above - the WS Gateway, the new REST API Gateway, the
Matchmaker, and the one Game Server Shard - still runs in a single JVM /
single container. This was a deliberate scope decision, not the end state:
`Lobby`'s room/session/matchmaking-queue state is plain in-memory Java
collections, partly keyed by live `WebSocket` objects - none of that can be
split across processes without first being externalized (e.g. to Redis),
and `bus.Bus` is a synchronous in-process pub/sub with no network hop to
replace with NATS/Redis yet. Attempting a full split into independently
deployable services in one pass risked landing "a lot that doesn't run"
instead of "a small step that works" - the explicit instruction behind this
round of changes.

## Not done yet (future work)

- **Game Allocator + multiple Game Server Shards.** Would need `Lobby`'s
  room/session state moved out of one process's heap (Redis is the obvious
  choice, matching the reference design) so an allocator can hand a new
  room to any of several shard processes, and so a WS Gateway process can
  find which shard owns a given room's connection.
- **Real NATS/Redis pub-sub** in place of `bus.Bus`, once there's more than
  one process for it to carry messages between.
- **Redis** for `Lobby`'s matchmaking queue and active-room/session
  tracking, and for `SessionTokenStore` - all currently plain in-memory
  maps, lost on restart, not shared across processes.
- **`games`/`results`/move-history tables** in PostgreSQL - move history
  currently lives only in each `GameSession`'s in-memory log.
- **Observability**: metrics, a health-check endpoint, load tests. Only
  `ActivityLog`'s append-only text log exists today.
- **Kubernetes/K3s manifests** - only Docker Compose exists.
