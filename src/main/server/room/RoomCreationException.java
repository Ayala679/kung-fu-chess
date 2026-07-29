package server.room;

/** Thrown by a {@link RoomCreator} when a room genuinely couldn't be created - e.g. {@link RemoteRoomCreator} timing out waiting for a reply. */
public class RoomCreationException extends RuntimeException {
    public RoomCreationException(String message) {
        super(message);
    }

    public RoomCreationException(String message, Throwable cause) {
        super(message, cause);
    }
}
