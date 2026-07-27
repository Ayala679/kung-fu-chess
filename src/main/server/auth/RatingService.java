package server.auth;

/**
 * Applies the ELO consequence of one finished game: computes both sides' new
 * ratings via EloCalculator and persists them through UserRepository. Kept
 * separate from GameSession so "what happens to ratings when a game ends" is
 * one small, independently readable unit, the same way AuthCommandParser/
 * AuthController were split out of the auth flow.
 */
public class RatingService {
    private final UserRepository userRepository;

    public RatingService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /** The persisted new ratings for {@code winnerName}/{@code loserName}. */
    public static final class Outcome {
        public final int newWinnerRating;
        public final int newLoserRating;

        private Outcome(int newWinnerRating, int newLoserRating) {
            this.newWinnerRating = newWinnerRating;
            this.newLoserRating = newLoserRating;
        }
    }

    /** Computes and persists the ELO update for a finished game; returns the new ratings for logging. */
    public Outcome applyGameEnd(String winnerName, int winnerRating, String loserName, int loserRating) {
        int newWinnerRating = EloCalculator.winnerNewRating(winnerRating, loserRating);
        int newLoserRating = EloCalculator.loserNewRating(loserRating, winnerRating);
        userRepository.updateRating(winnerName, newWinnerRating);
        userRepository.updateRating(loserName, newLoserRating);
        return new Outcome(newWinnerRating, newLoserRating);
    }
}
