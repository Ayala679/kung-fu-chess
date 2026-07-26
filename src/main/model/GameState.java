package model;

public class GameState {
    private long currentTime = 0;
    private Piece.Color winner;

    public void advanceTime(long ms) {
        currentTime += ms;
    }

    public long getCurrentTime() {
        return currentTime;
    }

    /** Ends the game with a definite winner - recorded directly by whoever caused it (e.g. a king capture), not re-derived later from board state. */
    public void setGameOver(Piece.Color winner) {
        this.winner = winner;
    }

    public boolean isGameOver() {
        return winner != null;
    }

    /** The winning color, or null if the game isn't over. */
    public Piece.Color getWinner() {
        return winner;
    }
}

