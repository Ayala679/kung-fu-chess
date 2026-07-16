package ruleengine;

import java.util.Collections;
import java.util.List;

import model.Board;
import model.MovingPiece;
import model.Piece;
import model.Position;

/**
 * PieceRules: all piece movement rules in one place.
 *
 * A single switch on the piece type decides which moves are geometrically
 * legal, so adding a new piece means adding one {@code case} here - not a new
 * class per piece. General checks (clear path) are delegated to
 * {@link MoveValidator}. These rules only inspect the board; they never move or
 * capture anything.
 */
public class PieceRules {

    public static boolean isValid(Piece.Type type, Board board, Position from, Position to, Piece piece) {
        return isValid(type, board, from, to, piece, Collections.emptyList(), 0);
    }

    /**
     * Same as {@link #isValid(Piece.Type, Board, Position, Position, Piece)},
     * but path-clearance also treats a cell whose occupant already has an
     * active outgoing move as empty - see
     * {@link MoveValidator#isPathClear(Position, Position, List, long)}.
     */
    public static boolean isValid(Piece.Type type, Board board, Position from, Position to, Piece piece,
                                   List<MovingPiece> activeMoves, long currentTime) {
        int rowDist = Math.abs(to.getRow() - from.getRow());
        int colDist = Math.abs(to.getCol() - from.getCol());
        MoveValidator general = new MoveValidator(board);

        switch (type) {
            case K:
                return rowDist <= 1 && colDist <= 1;
            case N:
                return (rowDist == 1 && colDist == 2) || (rowDist == 2 && colDist == 1);
            case R:
                return (rowDist == 0 || colDist == 0) && general.isPathClear(from, to, activeMoves, currentTime);
            case B:
                return (rowDist == colDist) && general.isPathClear(from, to, activeMoves, currentTime);
            case Q:
                return (rowDist == 0 || colDist == 0 || rowDist == colDist)
                        && general.isPathClear(from, to, activeMoves, currentTime);
            case P:
                return isValidPawn(board, general, from, to, piece, activeMoves, currentTime);
            default:
                return false;
        }
    }

    private static boolean isValidPawn(Board board, MoveValidator general, Position from, Position to, Piece piece,
                                        List<MovingPiece> activeMoves, long currentTime) {
        if (piece == null) return false;

        Piece.Color color = piece.getColor();
        int direction = (color == Piece.Color.WHITE) ? -1 : 1;
        int rowDiff = to.getRow() - from.getRow();
        int colDistance = Math.abs(to.getCol() - from.getCol());
        Piece destination = board.getCell(to);

        // single step forward
        if (rowDiff == direction && colDistance == 0 && destination == null) return true;

        // two steps from the starting row
        int startRow = (color == Piece.Color.WHITE) ? (board.getHeight() - 2) : 1;
        if (rowDiff == 2 * direction && colDistance == 0 && from.getRow() == startRow
                && destination == null && general.isPathClear(from, to, activeMoves, currentTime)) return true;

        // diagonal capture
        if (rowDiff == direction && colDistance == 1 && destination != null
                && destination.getColor() != color) return true;

        return false;
    }
}
