package server.auth;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Accounts, backed by either a local SQLite file (the original CTD 26 brief:
 * "save at SQLite db on server side" - still what local dev/tests use, zero
 * external services needed) or PostgreSQL (what the Docker Compose
 * deployment uses, see Server_Design.md - a network DB is what actually
 * lets this data outlive/be shared beyond one process, which a per-container
 * SQLite file can't). Passwords are never stored in the clear - see
 * PasswordHasher. One connection per call rather than a pool: this project
 * has at most a couple of players per process/shard, so pooling would be
 * unused complexity - would need revisiting if this ever runs as many
 * concurrently-loaded Game Server Shards.
 */
public class UserRepository {
    public static final int STARTING_RATING = 1200;

    private final String jdbcUrl;

    /**
     * @param dbPathOrJdbcUrl a full JDBC URL (e.g. {@code jdbc:postgresql://host:5432/db?user=u&password=p})
     *                        used as-is, or (legacy/local-dev behavior, unchanged) a bare SQLite file
     *                        path - detected by the absence of a "jdbc:" prefix - whose parent directory
     *                        is created and which is prefixed with "jdbc:sqlite:" for you.
     */
    public UserRepository(String dbPathOrJdbcUrl) {
        if (dbPathOrJdbcUrl.startsWith("jdbc:")) {
            this.jdbcUrl = dbPathOrJdbcUrl;
        } else {
            File dbFile = new File(dbPathOrJdbcUrl);
            File parent = dbFile.getParentFile();
            if (parent != null) parent.mkdirs();
            this.jdbcUrl = "jdbc:sqlite:" + dbPathOrJdbcUrl;
        }

        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS users (" +
                    "username TEXT PRIMARY KEY, " +
                    "salt TEXT NOT NULL, " +
                    "password_hash TEXT NOT NULL, " +
                    "rating INTEGER NOT NULL)");
        } catch (SQLException e) {
            throw new IllegalStateException("Could not initialize user database at " + jdbcUrl, e);
        }
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(jdbcUrl);
    }

    /** The resolved JDBC URL this repository connects with - lets {@link GameResultRepository} share the exact same database without re-deriving the sqlite-path-vs-jdbc-url logic in the constructor above. */
    public String getJdbcUrl() {
        return jdbcUrl;
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

    /**
     * Creates a new account with a fresh salt+hash (calls PasswordHasher);
     * fails if the username is taken. The upfront {@code findRating} check
     * is just a fast path for the common case (fails clearly without even
     * attempting an insert) - it is not what actually prevents a duplicate
     * username: two concurrent registrations for the same username could
     * both pass that check before either inserts, so the real guarantee
     * comes from the table's own PRIMARY KEY constraint on {@code username}
     * and the catch below, which recognizes a constraint violation and
     * reports it exactly like the fast-path check does, instead of leaking
     * it out as a raw {@code IllegalStateException}.
     */
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
            if (isUniqueViolation(e)) {
                return AuthResult.failure("username taken");
            }
            throw new IllegalStateException("Registration failed for " + username, e);
        }
    }

    // Best-effort, driver-agnostic detection of a duplicate-primary-key insert racing with another
    // registration for the same username: both sqlite-jdbc and postgresql report this under SQLState
    // class "23" ("Integrity Constraint Violation" in the ANSI SQL standard both drivers follow).
    private static boolean isUniqueViolation(SQLException e) {
        String sqlState = e.getSQLState();
        return sqlState != null && sqlState.startsWith("23");
    }

    // Looks up the stored salt/hash and checks the password via PasswordHasher.matches.
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
