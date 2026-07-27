package model;

public class GameState {
    private long currentTime = 0;
    private boolean gameOver = false;
    private Piece.Color winner;

    public void advanceTime(long ms) {
        currentTime += ms;
    }

    public long getCurrentTime() {
        return currentTime;
    }

    /** Ends the game with a definite winner - recorded directly by whoever caused it (e.g. a king capture), not re-derived later from board state. */
    public void setGameOver(Piece.Color winner) {
        this.gameOver = true;
        this.winner = winner;
    }

    /** Ends the game with no winner - e.g. a disconnect that leaves nobody to fairly credit with a win. */
    public void abandon() {
        this.gameOver = true;
        this.winner = null;
    }

    public boolean isGameOver() {
        return gameOver;
    }

    /** The winning color, or null if the game isn't over (or is over with no winner - see abandon()). */
    public Piece.Color getWinner() {
        return winner;
    }
}

