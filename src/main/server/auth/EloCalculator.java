package server.auth;

/**
 * Standard ELO rating update (no draws - this engine's games always end in
 * a king capture, so only win/loss are possible).
 */
public final class EloCalculator {
    private static final int K_FACTOR = 32;

    private EloCalculator() {}

    /** The winner's new rating after beating an opponent rated {@code loserRating}. */
    public static int winnerNewRating(int winnerRating, int loserRating) {
        double expected = expectedScore(winnerRating, loserRating);
        return (int) Math.round(winnerRating + K_FACTOR * (1 - expected));
    }

    /** The loser's new rating after losing to an opponent rated {@code winnerRating}. */
    public static int loserNewRating(int loserRating, int winnerRating) {
        double expected = expectedScore(loserRating, winnerRating);
        return (int) Math.round(loserRating + K_FACTOR * (0 - expected));
    }

    private static double expectedScore(int ratingA, int ratingB) {
        return 1.0 / (1.0 + Math.pow(10, (ratingB - ratingA) / 400.0));
    }
}
