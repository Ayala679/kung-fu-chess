package server.auth;

import logging.ActivityLog;

/**
 * Service for the login/register step of the wire protocol: takes the
 * command {@link AuthCommandParser} already parsed and calls into
 * {@link UserRepository} - the repository that actually owns account logic
 * and persistence - to carry it out. Kept separate from {@code
 * server.KungFuChessServerService}/{@code server.HttpApiServer} so this
 * orchestration (and logging of the outcome) isn't mixed into WebSocket/HTTP
 * connection bookkeeping; the caller still owns the connection itself
 * (sending the reply, closing on failure). Does not parse raw wire text
 * itself - see {@link AuthCommandParser}.
 */
public class AuthService {
    private final UserRepository userRepository;
    private final ActivityLog activityLog;

    public AuthService(UserRepository userRepository, ActivityLog activityLog) {
        this.userRepository = userRepository;
        this.activityLog = activityLog;
    }

    /** The result of handling one raw auth message - either malformed, or a repository-level AuthResult for a given username. */
    public static final class Outcome {
        private final boolean malformed;
        private final String username;
        private final UserRepository.AuthResult result;

        private Outcome(boolean malformed, String username, UserRepository.AuthResult result) {
            this.malformed = malformed;
            this.username = username;
            this.result = result;
        }

        static Outcome malformed() { return new Outcome(true, null, null); }
        static Outcome of(String username, UserRepository.AuthResult result) { return new Outcome(false, username, result); }

        /** True if the message wasn't a well-formed "login/register &lt;username&gt; &lt;password&gt;" command. */
        public boolean isMalformed() { return malformed; }
        public String getUsername() { return username; }
        public UserRepository.AuthResult getResult() { return result; }
    }

    /**
     * Parses {@code message} via {@link AuthCommandParser} and delegates to
     * {@link UserRepository#register} or {@link UserRepository#authenticate}.
     * Guards against an unexpected failure in the repository (e.g. the
     * SQLite file becoming unreadable mid-session) - without this, an
     * uncaught exception here would propagate all the way up through
     * server.KungFuChessServerService.handleMessage's own try/catch, leaving
     * the connection with no reply at all instead of a clean ERROR.
     */
    public Outcome handleAuth(String message) {
        AuthCommandParser.ParsedCommand parsed = AuthCommandParser.parse(message);
        if (parsed == null) {
            return Outcome.malformed();
        }

        UserRepository.AuthResult result;
        try {
            result = parsed.mode() == AuthCommandParser.Mode.REGISTER
                    ? userRepository.register(parsed.username(), parsed.password())
                    : userRepository.authenticate(parsed.username(), parsed.password());
        } catch (RuntimeException e) {
            activityLog.log("AUTH_ERROR " + parsed.username() + ": " + e);
            return Outcome.of(parsed.username(), UserRepository.AuthResult.failure("internal error - please try again"));
        }

        if (!result.isSuccess()) {
            activityLog.log("AUTH_FAILED " + parsed.username() + ": " + result.getFailureReason());
        } else {
            activityLog.log(parsed.username() + " authenticated (rating " + result.getRating() + ")");
        }
        return Outcome.of(parsed.username(), result);
    }
}
