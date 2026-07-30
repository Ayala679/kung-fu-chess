package server.auth;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import model.MoveLogEntry;

/**
 * Persists a finished game's result and its full move history - the
 * {@code games}/{@code moves} tables Server_Design.md's "Not done yet"
 * flagged as missing (move history previously lived only in each {@code
 * GameSession}'s in-memory log, gone the moment the process exited). Backed
 * by the same database as {@link UserRepository} (sqlite-jdbc locally/in
 * tests, postgresql in the Docker Compose deployment) - constructed with
 * {@link UserRepository#getJdbcUrl()} rather than re-deriving the
 * sqlite-path-vs-jdbc-url detection UserRepository's own constructor already
 * does. One connection per call, no pooling - same simplicity choice as
 * UserRepository (at most a couple of players per process/shard).
 */
public class GameResultRepository {
    // One instance per distinct jdbcUrl, shared across every GameSession backed by
    // the same database within this process - the constructor runs a CREATE TABLE
    // IF NOT EXISTS on every call, so a fresh instance per room/quick-match would
    // otherwise re-run that DDL statement on every single game created.
    private static final Map<String, GameResultRepository> INSTANCES = new ConcurrentHashMap<>();

    private final String jdbcUrl;

    /** The shared instance for this jdbcUrl - see the class-level note above on why this is preferred over {@code new GameResultRepository(...)} for every caller except tests that want a fresh, isolated instance. */
    public static GameResultRepository forJdbcUrl(String jdbcUrl) {
        return INSTANCES.computeIfAbsent(jdbcUrl, GameResultRepository::new);
    }

    public GameResultRepository(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;

        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS games (" +
                    "id TEXT PRIMARY KEY, " +
                    "room_code TEXT NOT NULL, " +
                    "white_username TEXT NOT NULL, " +
                    "black_username TEXT NOT NULL, " +
                    "winner_username TEXT NOT NULL, " +
                    "white_rating_before INTEGER NOT NULL, " +
                    "white_rating_after INTEGER NOT NULL, " +
                    "black_rating_before INTEGER NOT NULL, " +
                    "black_rating_after INTEGER NOT NULL, " +
                    "ended_at BIGINT NOT NULL)");
            st.execute("CREATE TABLE IF NOT EXISTS moves (" +
                    "game_id TEXT NOT NULL, " +
                    "seq INTEGER NOT NULL, " +
                    "color TEXT NOT NULL, " +
                    "notation TEXT NOT NULL, " +
                    "ts BIGINT NOT NULL)");
        } catch (SQLException e) {
            throw new IllegalStateException("Could not initialize game result database at " + jdbcUrl, e);
        }
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(jdbcUrl);
    }

    /**
     * Records one finished game: its own {@code games} row plus every move
     * from both sides' logs, in a single batch insert into {@code moves} -
     * {@code seq} is each side's own move number (1-based), not a merged
     * global sequence, matching how {@link snapshot.GameSnapshot#whiteMoves()}/
     * {@code blackMoves()} are already tracked as two separate per-color
     * lists everywhere else in this codebase.
     */
    public void recordGame(String roomCode, String whiteUsername, String blackUsername, String winnerUsername,
                            int whiteRatingBefore, int whiteRatingAfter, int blackRatingBefore, int blackRatingAfter,
                            List<MoveLogEntry> whiteMoves, List<MoveLogEntry> blackMoves) {
        String gameId = UUID.randomUUID().toString();
        try (Connection conn = connect()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO games (id, room_code, white_username, black_username, winner_username, " +
                            "white_rating_before, white_rating_after, black_rating_before, black_rating_after, ended_at) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                ps.setString(1, gameId);
                ps.setString(2, roomCode);
                ps.setString(3, whiteUsername);
                ps.setString(4, blackUsername);
                ps.setString(5, winnerUsername);
                ps.setInt(6, whiteRatingBefore);
                ps.setInt(7, whiteRatingAfter);
                ps.setInt(8, blackRatingBefore);
                ps.setInt(9, blackRatingAfter);
                ps.setLong(10, System.currentTimeMillis());
                ps.executeUpdate();
            }

            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO moves (game_id, seq, color, notation, ts) VALUES (?, ?, ?, ?, ?)")) {
                addMoveBatch(ps, gameId, "WHITE", whiteMoves);
                addMoveBatch(ps, gameId, "BLACK", blackMoves);
                ps.executeBatch();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not record game result for room " + roomCode, e);
        }
    }

    private void addMoveBatch(PreparedStatement ps, String gameId, String color, List<MoveLogEntry> moves) throws SQLException {
        for (int i = 0; i < moves.size(); i++) {
            MoveLogEntry move = moves.get(i);
            ps.setString(1, gameId);
            ps.setInt(2, i + 1);
            ps.setString(3, color);
            ps.setString(4, move.getNotation());
            ps.setLong(5, move.getTimestamp());
            ps.addBatch();
        }
    }
}
