package event;

import snapshot.GameSnapshot;

/**
 * The surface view.BoardWindow needs from "whatever is driving the game" -
 * satisfied both by a local {@link EventEngine} (offline/single-process play)
 * and by net.NetworkGameClient (play against a remote server). BoardWindow
 * itself never needs to know which one it has.
 */
public interface GameClient {
    void handleClick(int row, int col);
    void handleJump(int row, int col);
    void waitFor(long ms);
    GameSnapshot snapshot();
}
