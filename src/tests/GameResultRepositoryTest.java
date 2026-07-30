package tests;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

import model.MoveLogEntry;
import server.auth.GameResultRepository;

/** Tests server.auth.GameResultRepository - the games/moves persistence Server_Design.md's "Not done yet" used to flag as missing. Same SQLite-temp-file convention as UserRepositoryTest. */
class GameResultRepositoryTest {

    private static String jdbcUrlFor(Path tempDir) {
        return "jdbc:sqlite:" + tempDir.resolve("games.db");
    }

    @Test void testRecordGamePersistsAGamesRow(@TempDir Path tempDir) throws SQLException {
        String jdbcUrl = jdbcUrlFor(tempDir);
        GameResultRepository repo = new GameResultRepository(jdbcUrl);

        repo.recordGame("ABC123", "alice", "bob", "alice",
                1200, 1216, 1200, 1184,
                List.of(new MoveLogEntry(1000L, "e4")),
                List.of(new MoveLogEntry(1500L, "e5")));

        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             PreparedStatement ps = conn.prepareStatement("SELECT * FROM games WHERE room_code = ?")) {
            ps.setString(1, "ABC123");
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("alice", rs.getString("white_username"));
                assertEquals("bob", rs.getString("black_username"));
                assertEquals("alice", rs.getString("winner_username"));
                assertEquals(1200, rs.getInt("white_rating_before"));
                assertEquals(1216, rs.getInt("white_rating_after"));
                assertEquals(1200, rs.getInt("black_rating_before"));
                assertEquals(1184, rs.getInt("black_rating_after"));
                assertFalse(rs.next());
            }
        }
    }

    @Test void testRecordGamePersistsBothSidesMoveHistoryInOrder(@TempDir Path tempDir) throws SQLException {
        String jdbcUrl = jdbcUrlFor(tempDir);
        GameResultRepository repo = new GameResultRepository(jdbcUrl);

        repo.recordGame("ABC123", "alice", "bob", "alice",
                1200, 1216, 1200, 1184,
                List.of(new MoveLogEntry(1000L, "e4"), new MoveLogEntry(3000L, "Nf3")),
                List.of(new MoveLogEntry(1500L, "e5")));

        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             PreparedStatement gameIdQuery = conn.prepareStatement("SELECT id FROM games WHERE room_code = ?")) {
            gameIdQuery.setString(1, "ABC123");
            String gameId;
            try (ResultSet rs = gameIdQuery.executeQuery()) {
                assertTrue(rs.next());
                gameId = rs.getString("id");
            }

            try (PreparedStatement moves = conn.prepareStatement(
                    "SELECT * FROM moves WHERE game_id = ? AND color = ? ORDER BY seq")) {
                moves.setString(1, gameId);
                moves.setString(2, "WHITE");
                try (ResultSet rs = moves.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals(1, rs.getInt("seq"));
                    assertEquals("e4", rs.getString("notation"));
                    assertTrue(rs.next());
                    assertEquals(2, rs.getInt("seq"));
                    assertEquals("Nf3", rs.getString("notation"));
                    assertFalse(rs.next());
                }
            }

            try (PreparedStatement moves = conn.prepareStatement(
                    "SELECT * FROM moves WHERE game_id = ? AND color = ? ORDER BY seq")) {
                moves.setString(1, gameId);
                moves.setString(2, "BLACK");
                try (ResultSet rs = moves.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals("e5", rs.getString("notation"));
                    assertFalse(rs.next());
                }
            }
        }
    }

    @Test void testRecordGameAssignsADistinctIdToEachGame(@TempDir Path tempDir) throws SQLException {
        String jdbcUrl = jdbcUrlFor(tempDir);
        GameResultRepository repo = new GameResultRepository(jdbcUrl);

        repo.recordGame("ROOM01", "alice", "bob", "alice", 1200, 1216, 1200, 1184, List.of(), List.of());
        repo.recordGame("ROOM02", "carol", "dave", "dave", 1200, 1184, 1200, 1216, List.of(), List.of());

        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             PreparedStatement ps = conn.prepareStatement("SELECT COUNT(DISTINCT id) AS n FROM games")) {
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(2, rs.getInt("n"));
            }
        }
    }
}
