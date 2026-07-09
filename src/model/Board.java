package model;

/**
 * Pure Model: Represents the chess board state using Piece objects.
 * No dependencies on other packages beyond model.
 */
public class Board {
    private final Piece[][] grid;
    private final int height;
    private final int width;

    public Board(Piece[][] grid) {
        this.grid = grid;
        this.height = grid.length;
        this.width = grid.length > 0 ? grid[0].length : 0;
    }

    public Piece[][] getGrid() { return grid; }
    public int getHeight() { return height; }
    public int getWidth() { return width; }

    public Piece getCell(int row, int col) {
        if (row < 0 || row >= height || col < 0 || col >= width) {
            return null;
        }
        return grid[row][col];
    }

    public Piece getCell(Position pos) {
        return getCell(pos.getRow(), pos.getCol());
    }

    public void setCell(int row, int col, Piece value) {
        if (row >= 0 && row < height && col >= 0 && col < width) {
            grid[row][col] = value;
        }
    }

    public void setCell(Position pos, Piece value) {
        setCell(pos.getRow(), pos.getCol(), value);
    }

    public boolean isEmpty() {
        return height == 0 || width == 0;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < height; i++) {
            for (int j = 0; j < width; j++) {
                Piece p = grid[i][j];
                sb.append(p == null ? "." : p.toToken());
                if (j < width - 1) sb.append(" ");
            }
            sb.append("\n");
        }
        return sb.toString();
    }
}

