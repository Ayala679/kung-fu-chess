package server.auth;

import logging.ActivityLog;

/**
 * Controller for the login/register step of the wire protocol: takes the
 * command {@link AuthCommandParser} already parsed and calls into
 * {@link UserRepository} - the service that actually owns account logic and
 * persistence - to carry it out. Kept separate from
 * {@code server.KungFuChessServer} so this orchestration (and logging of the
 * outcome) isn't mixed into WebSocket connection bookkeeping; the server
 * still owns the connection itself (sending the reply, closing on failure).
 * Does not parse raw wire text itself - see {@link AuthCommandParser}.
 */
public class AuthController {
    private final UserRepository userRepository;
    private final ActivityLog activityLog;

    public AuthController(UserRepository userRepository, ActivityLog activityLog) {
        this.userRepository = userRepository;
        this.activityLog = activityLog;
    }

    /** The result of handling one raw auth message - either malformed, or a service-level AuthResult for a given username. */
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

    /** Parses {@code message} via {@link AuthCommandParser} and delegates to {@link UserRepository#register} or {@link UserRepository#authenticate}. */
    public Outcome handleAuth(String message) {
        AuthCommandParser.ParsedCommand parsed = AuthCommandParser.parse(message);
        if (parsed == null) {
            return Outcome.malformed();
        }

        UserRepository.AuthResult result = parsed.mode() == AuthCommandParser.Mode.REGISTER
                ? userRepository.register(parsed.username(), parsed.password())
                : userRepository.authenticate(parsed.username(), parsed.password());

        if (!result.isSuccess()) {
            activityLog.log("AUTH_FAILED " + parsed.username() + ": " + result.getFailureReason());
        } else {
            activityLog.log(parsed.username() + " authenticated (rating " + result.getRating() + ")");
        }
        return Outcome.of(parsed.username(), result);
    }
}
