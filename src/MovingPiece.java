public class MovingPiece {
    private String piece;
    private int fromRow, fromCol;
    private int toRow, toCol;
    private long arrivalTime;
    private long duration;

    public MovingPiece(String piece, int fromRow, int fromCol, int toRow, int toCol, long duration, long currentTime) {
        this.piece = piece;
        this.fromRow = fromRow;
        this.fromCol = fromCol;
        this.toRow = toRow;
        this.toCol = toCol;
        this.duration = duration;
        this.arrivalTime = currentTime + duration;
    }

    public String getPiece() {
        return piece;
    }

    public void setPiece(String piece) {
        this.piece = piece;
    }

    public int getFromRow() {
        return fromRow;
    }

    public int getFromCol() {
        return fromCol;
    }

    public int getToRow() {
        return toRow;
    }

    public int getToCol() {
        return toCol;
    }

    public long getArrivalTime() {
        return arrivalTime;
    }

    public void setArrivalTime(long arrivalTime) {
        this.arrivalTime = arrivalTime;
    }

    public long getDuration() {
        return duration;
    }

    public boolean isMoving() {
        return fromRow != toRow || fromCol != toCol;
    }

    public boolean hasArrived(long currentTime) {
        return currentTime >= arrivalTime;
    }
}

