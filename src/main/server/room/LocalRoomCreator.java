package server.room;

import server.Lobby;

/** {@link RoomCreator} for when {@code HttpApiServer} is still embedded in the same process as {@code Lobby} - the local/offline, single-process topology (see KungFuChessServerService). Just delegates directly. */
public class LocalRoomCreator implements RoomCreator {
    private final Lobby lobby;

    public LocalRoomCreator(Lobby lobby) {
        this.lobby = lobby;
    }

    @Override
    public String createRoom(String username) {
        return lobby.createRoom(username);
    }
}
