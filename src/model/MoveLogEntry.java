package model;

/** One line of a side's move history: when it happened and its notation. */
public class MoveLogEntry {
    private final long timestamp;
    private final String notation;

    public MoveLogEntry(long timestamp, String notation) {
        this.timestamp = timestamp;
        this.notation = notation;
    }

    public long getTimestamp() { return timestamp; }
    public String getNotation() { return notation; }
}
