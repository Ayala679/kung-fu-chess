package server.auth;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Salted SHA-256 password hashing - no external dependency (no bcrypt/argon2
 * library), adequate for this project's scope. Each password gets its own
 * random salt so two users with the same password never produce the same
 * stored hash.
 */
public final class PasswordHasher {
    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordHasher() {}

    public static String newSalt() {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        return Base64.getEncoder().encodeToString(salt);
    }

    public static String hash(String password, String salt) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(Base64.getDecoder().decode(salt));
            byte[] hashed = digest.digest(password.getBytes());
            return Base64.getEncoder().encodeToString(hashed);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    public static boolean matches(String password, String salt, String expectedHash) {
        return hash(password, salt).equals(expectedHash);
    }
}
