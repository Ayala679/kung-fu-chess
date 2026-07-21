package net;

import java.util.ArrayList;
import java.util.List;

import model.MoveLogEntry;
import model.Piece;
import model.Position;
import snapshot.GameSnapshot;
import snapshot.PieceSnapshot;
import snapshot.PieceVisualState;

/**
 * Encodes/decodes a GameSnapshot to a plain-text block for the wire, since
 * ImgRenderer needs every in-flight piece's animation progress - not just a
 * static board - to render the client's window. Plain text (not JSON) to
 * match the project's existing token/command conventions and avoid another
 * dependency. Pure functions, no I/O - see net.Protocol for how the block
 * this produces is framed as a "STATE" message.
 */
public final class SnapshotCodec {
    private static final String NONE = "-";

    private SnapshotCodec() {}

    public static String encode(GameSnapshot snapshot) {
        StringBuilder sb = new StringBuilder();

        Position sel = snapshot.positionSelected();
        sb.append(snapshot.boardWidth()).append(' ')
          .append(snapshot.boardHeight()).append(' ')
          .append(snapshot.gameOver()).append(' ')
          .append(snapshot.winner() == null ? NONE : snapshot.winner()).append(' ')
          .append(sel == null ? -1 : sel.getRow()).append(' ')
          .append(sel == null ? -1 : sel.getCol()).append(' ')
          .append(snapshot.whiteScore()).append(' ')
          .append(snapshot.blackScore()).append(' ')
          .append(snapshot.whiteName() == null ? NONE : snapshot.whiteName()).append(' ')
          .append(snapshot.blackName() == null ? NONE : snapshot.blackName()).append('\n');

        sb.append(encodePositions(snapshot.legalDestinations())).append('\n');
        sb.append(encodeMoveLog(snapshot.whiteMoves())).append('\n');
        sb.append(encodeMoveLog(snapshot.blackMoves())).append('\n');

        for (PieceSnapshot piece : snapshot.pieces()) {
            sb.append(piece.type()).append(' ')
              .append(piece.color()).append(' ')
              .append(piece.fromRow()).append(' ')
              .append(piece.fromCol()).append(' ')
              .append(piece.toRow()).append(' ')
              .append(piece.toCol()).append(' ')
              .append(piece.progress()).append(' ')
              .append(piece.state()).append(' ')
              .append(piece.stateElapsedMillis()).append('\n');
        }

        return sb.toString();
    }

    public static GameSnapshot decode(String block) {
        String[] lines = block.split("\n", -1);
        String[] header = lines[0].trim().split(" ");

        int width = Integer.parseInt(header[0]);
        int height = Integer.parseInt(header[1]);
        boolean gameOver = Boolean.parseBoolean(header[2]);
        String winner = header[3].equals(NONE) ? null : header[3];
        int selRow = Integer.parseInt(header[4]);
        int selCol = Integer.parseInt(header[5]);
        Position selection = selRow < 0 ? null : new Position(selRow, selCol);
        int whiteScore = Integer.parseInt(header[6]);
        int blackScore = Integer.parseInt(header[7]);
        String whiteName = header.length > 8 && !header[8].equals(NONE) ? header[8] : null;
        String blackName = header.length > 9 && !header[9].equals(NONE) ? header[9] : null;

        List<Position> legalDestinations = decodePositions(lines[1]);
        List<MoveLogEntry> whiteMoves = decodeMoveLog(lines[2]);
        List<MoveLogEntry> blackMoves = decodeMoveLog(lines[3]);

        List<PieceSnapshot> pieces = new ArrayList<>();
        for (int i = 4; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;
            String[] parts = line.split(" ");
            Piece.Type type = Piece.Type.valueOf(parts[0]);
            Piece.Color color = Piece.Color.valueOf(parts[1]);
            int fromRow = Integer.parseInt(parts[2]);
            int fromCol = Integer.parseInt(parts[3]);
            int toRow = Integer.parseInt(parts[4]);
            int toCol = Integer.parseInt(parts[5]);
            double progress = Double.parseDouble(parts[6]);
            PieceVisualState state = PieceVisualState.valueOf(parts[7]);
            long stateElapsedMillis = Long.parseLong(parts[8]);

            String id = color + "_" + type + "_" + fromRow + "_" + fromCol;
            pieces.add(new PieceSnapshot(id, type, color, fromRow, fromCol, toRow, toCol,
                    progress, state, stateElapsedMillis));
        }

        return new GameSnapshot(width, height, pieces, selection, gameOver, winner,
                whiteScore, blackScore, whiteMoves, blackMoves, legalDestinations, whiteName, blackName);
    }

    private static String encodePositions(List<Position> positions) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < positions.size(); i++) {
            if (i > 0) sb.append(' ');
            sb.append(positions.get(i).getRow()).append(':').append(positions.get(i).getCol());
        }
        return sb.toString();
    }

    private static List<Position> decodePositions(String line) {
        List<Position> positions = new ArrayList<>();
        String trimmed = line.trim();
        if (trimmed.isEmpty()) return positions;
        for (String token : trimmed.split(" ")) {
            String[] rc = token.split(":");
            positions.add(new Position(Integer.parseInt(rc[0]), Integer.parseInt(rc[1])));
        }
        return positions;
    }

    private static String encodeMoveLog(List<MoveLogEntry> entries) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) sb.append(' ');
            sb.append(entries.get(i).getTimestamp()).append('|').append(entries.get(i).getNotation());
        }
        return sb.toString();
    }

    private static List<MoveLogEntry> decodeMoveLog(String line) {
        List<MoveLogEntry> entries = new ArrayList<>();
        String trimmed = line.trim();
        if (trimmed.isEmpty()) return entries;
        for (String token : trimmed.split(" ")) {
            int sep = token.indexOf('|');
            long timestamp = Long.parseLong(token.substring(0, sep));
            String notation = token.substring(sep + 1);
            entries.add(new MoveLogEntry(timestamp, notation));
        }
        return entries;
    }
}
