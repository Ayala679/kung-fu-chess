package server.auth;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Accounts, backed by a SQLite file (server-side only - see the CTD 26
 * brief's "save at SQLite db on server side"). Passwords are never stored in
 * the clear - see PasswordHasher. One connection per call rather than a
 * pool: this project has at most two players per process, so pooling would
 * be unused complexity.
 */
public class UserRepository {
    public static final int STARTING_RATING = 1200;

    private final String jdbcUrl;

    public UserRepository(String dbFilePath) {
        File dbFile = new File(dbFilePath);
        File parent = dbFile.getParentFile();
        if (parent != null) parent.mkdirs();
        this.jdbcUrl = "jdbc:sqlite:" + dbFilePath;

        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS users (" +
                    "username TEXT PRIMARY KEY, " +
                    "salt TEXT NOT NULL, " +
                    "password_hash TEXT NOT NULL, " +
                    "rating INTEGER NOT NULL)");
        } catch (SQLException e) {
            throw new IllegalStateException("Could not initialize user database at " + dbFilePath, e);
        }
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(jdbcUrl);
    }

    /** Result of a register/authenticate attempt: either a rating, or a failure reason. */
    public static final class AuthResult {
        private final boolean success;
        private final String failureReason;
        private final int rating;

        private AuthResult(boolean success, String failureReason, int rating) {
            this.success = success;
            this.failureReason = failureReason;
            this.rating = rating;
        }

        static AuthResult ok(int rating) { return new AuthResult(true, null, rating); }
        static AuthResult failure(String reason) { return new AuthResult(false, reason, 0); }

        public boolean isSuccess() { return success; }
        public String getFailureReason() { return failureReason; }
        public int getRating() { return rating; }
    }

    public AuthResult register(String username, String password) {
        try (Connection conn = connect()) {
            if (findRating(conn, username) != null) {
                return AuthResult.failure("username taken");
            }
            String salt = PasswordHasher.newSalt();
            String hash = PasswordHasher.hash(password, salt);
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO users (username, salt, password_hash, rating) VALUES (?, ?, ?, ?)")) {
                ps.setString(1, username);
                ps.setString(2, salt);
                ps.setString(3, hash);
                ps.setInt(4, STARTING_RATING);
                ps.executeUpdate();
            }
            return AuthResult.ok(STARTING_RATING);
        } catch (SQLException e) {
            throw new IllegalStateException("Registration failed for " + username, e);
        }
    }

    public AuthResult authenticate(String username, String password) {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT salt, password_hash, rating FROM users WHERE username = ?")) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return AuthResult.failure("unknown username");
                String salt = rs.getString("salt");
                String expectedHash = rs.getString("password_hash");
                int rating = rs.getInt("rating");
                if (!PasswordHasher.matches(password, salt, expectedHash)) {
                    return AuthResult.failure("wrong password");
                }
                return AuthResult.ok(rating);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Authentication failed for " + username, e);
        }
    }

    public void updateRating(String username, int newRating) {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement("UPDATE users SET rating = ? WHERE username = ?")) {
            ps.setInt(1, newRating);
            ps.setString(2, username);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Rating update failed for " + username, e);
        }
    }

    private Integer findRating(Connection conn, String username) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT rating FROM users WHERE username = ?")) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt("rating") : null;
            }
        }
    }
}
