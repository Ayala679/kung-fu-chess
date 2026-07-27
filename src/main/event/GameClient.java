package event;

import snapshot.GameSnapshot;

/**
 * The surface view.BoardWindow needs from "whatever is driving the game" -
 * satisfied both by a local {@link EventEngine} (offline/single-process play)
 * and by client.NetworkGameClient (play against a remote server). BoardWindow
 * itself never needs to know which one it has.
 */
public interface GameClient {
    void handleClick(int row, int col);
    void handleJump(int row, int col);
    void waitFor(long ms);
    GameSnapshot snapshot();

    /**
     * Milliseconds until the opponent is auto-abandoned due to a network
     * disconnect, or &lt;= 0 if not applicable. Only meaningful for
     * client.NetworkGameClient - offline play has no concept of disconnect,
     * so EventEngine keeps the default (always "not applicable").
     */
    default long millisUntilOpponentAutoAbandon() { return 0; }

    /**
     * Requests a fresh rematch once the game is over. No-op by default -
     * offline play has nothing to "request" over a network; only
     * client.NetworkGameClient overrides this.
     */
    default void requestRematch() {}
}
