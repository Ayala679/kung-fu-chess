package tests;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import server.auth.EloCalculator;

class EloCalculatorTest {
    @Test void testEqualRatingsSplitTheKFactorEvenly() {
        // Expected score is 0.5 for both sides, so each moves by K/2 = 16.
        assertEquals(1216, EloCalculator.winnerNewRating(1200, 1200));
        assertEquals(1184, EloCalculator.loserNewRating(1200, 1200));
    }

    @Test void testWinnerGainsLessWhenAlreadyMuchHigherRated() {
        int newRating = EloCalculator.winnerNewRating(1600, 1200);
        assertTrue(newRating - 1600 < 16, "a big favorite winning should gain less than an even match");
        assertTrue(newRating > 1600, "the winner's rating should still go up");
    }

    @Test void testWinnerGainsMoreAsTheUnderdog() {
        int newRating = EloCalculator.winnerNewRating(1200, 1600);
        assertTrue(newRating - 1200 > 16, "an upset winner should gain more than an even match");
    }

    @Test void testLoserRatingAlwaysDecreases() {
        assertTrue(EloCalculator.loserNewRating(1200, 1200) < 1200);
        assertTrue(EloCalculator.loserNewRating(1600, 1200) < 1600);
        assertTrue(EloCalculator.loserNewRating(1200, 1600) < 1200);
    }

    @Test void testWinnerAndLoserDeltasAreSymmetric() {
        int winnerGain = EloCalculator.winnerNewRating(1250, 1400) - 1250;
        int loserLoss = 1400 - EloCalculator.loserNewRating(1400, 1250);
        assertEquals(winnerGain, loserLoss);
    }
}
