package model;

public class MovingPiece {
    private final Piece piece;
    private final Position from;
    private final Position to;
    private long arrivalTime;
    private final long duration;

    public MovingPiece(Piece piece, Position from, Position to, long duration, long currentTime) {
        this.piece = piece;
        this.from = from;
        this.to = to;
        this.duration = duration;
        this.arrivalTime = currentTime + duration;
    }

    public Piece getPiece() { return piece; }
    public Position getFrom() { return from; }
    public Position getTo() { return to; }
    public long getArrivalTime() { return arrivalTime; }
    public void setArrivalTime(long arrivalTime) { this.arrivalTime = arrivalTime; }
    public long getDuration() { return duration; }
    public boolean isMoving() { return !from.equals(to); }
    public boolean hasArrived(long currentTime) { return currentTime >= arrivalTime; }
}

