package server;

/**
 * "Create a fresh room, return its code" - what {@code HttpApiServer}'s
 * {@code POST /api/rooms} handler needs, without knowing whether the
 * {@code Lobby} that actually does it lives in this same process
 * ({@link LocalRoomCreator}) or in a separate Game Server Shard process
 * reached over NATS ({@link RemoteRoomCreator}) - see Server_Design.md's
 * "Step 4" for why the API Gateway can't just hold a {@code Lobby}
 * reference once it's a genuinely separate process.
 */
public interface RoomCreator {
    /** @throws RoomCreationException if the room couldn't be created (e.g. the remote Game Server Shard didn't respond in time). */
    String createRoom(String username);
}
