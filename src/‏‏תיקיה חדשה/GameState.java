package model;

public class GameState {
    private long currentTime = 0;
    private boolean isGameOver = false;

    public void advanceTime(long ms) {
        currentTime += ms;
    }

    public long getCurrentTime() {
        return currentTime;
    }

    public void setGameOver() {
        isGameOver = true;
    }

    public boolean isGameOver() {
        return isGameOver;
    }
}

