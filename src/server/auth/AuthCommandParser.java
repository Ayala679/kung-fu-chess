package server.auth;

import net.Protocol;

/**
 * The single place that knows the raw "login/register &lt;username&gt;
 * &lt;password&gt;" wire text shape - mirrors how parsing.BoardMapper/
 * PieceMapper are kept separate from the engine that consumes their output.
 * {@link AuthController} calls this instead of splitting strings itself;
 * {@link UserRepository} (the service) never sees raw wire text at all.
 */
public final class AuthCommandParser {
    private AuthCommandParser() {}

    public enum Mode { LOGIN, REGISTER }

    /** A successfully parsed auth command. */
    public record ParsedCommand(Mode mode, String username, String password) {}

    /** Returns null if {@code message} isn't a well-formed "login/register &lt;username&gt; &lt;password&gt;" command. */
    public static ParsedCommand parse(String message) {
        String[] parts = message.trim().split("\\s+", 3);
        boolean isLogin = parts.length > 0 && parts[0].equals(Protocol.LOGIN);
        boolean isRegister = parts.length > 0 && parts[0].equals(Protocol.REGISTER);
        if (parts.length < 3 || !(isLogin || isRegister)) {
            return null;
        }
        return new ParsedCommand(isRegister ? Mode.REGISTER : Mode.LOGIN, parts[1], parts[2]);
    }
}
