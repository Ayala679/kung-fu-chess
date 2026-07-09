/**
 * Immutable coordinate representation.
 *
 * ENCAPSULATION: row/col are private final - cannot be modified or accessed directly.
 * This allows internal representation to change (e.g., to single long bitmask) without
 * affecting callers.
 *
 * FUTURE: For binary board optimization, replace:
 *   private int row, col;
 * With:
 *   private long coordinate; // (row << 3) | col for 8x8 board
 * Public API remains unchanged - no caller knows.
 */
package model;

public final class Position {
    private final int row;
    private final int col;

    public Position(int row, int col) {
        this.row = row;
        this.col = col;
    }

    public int getRow() { return row; }
    public int getCol() { return col; }

    public int rowDistance(Position other) {
        return Math.abs(this.row - other.row);
    }

    public int colDistance(Position other) {
        return Math.abs(this.col - other.col);
    }

    public int manhattanDistance(Position other) {
        return rowDistance(other) + colDistance(other);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Position)) return false;
        Position p = (Position) o;
        return row == p.row && col == p.col;
    }

    @Override
    public int hashCode() {
        return 31 * row + col;
    }

    @Override
    public String toString() {
        return "(" + row + "," + col + ")";
    }
}

