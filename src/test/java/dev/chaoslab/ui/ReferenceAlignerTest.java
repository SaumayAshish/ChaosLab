package dev.chaoslab.ui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Tests for {@link ReferenceAligner}: the formula, the fallbacks, forgetting, and isolation from the caller. */
class ReferenceAlignerTest {

    private static final double[] SENT = {1.0, 2.0, 0.0, -1.0, 0.5, 0.0};
    private static final double[] REFERENCE = {1.001, 1.998, 0.0, -1.0, 0.5004, 0.0};
    private static final double[] NOW = {1.5, 2.5, 0.0, -1.5, 1.0, 0.0};

    @Test
    void theRingIsTheBodyNowPlusTheMeasuredErrorVector() {
        ReferenceAligner a = new ReferenceAligner(8);
        a.remember(3.0, SENT);
        double[] ghost = a.aligned(3.0, REFERENCE, NOW);
        for (int i = 0; i < ghost.length; i++) {
            assertEquals(NOW[i] + (REFERENCE[i] - SENT[i]), ghost[i], 1e-15);
        }
    }

    @Test
    void whenTheBodiesHaveNotMovedTheRingIsTheReferenceItself() {
        ReferenceAligner a = new ReferenceAligner(8);
        a.remember(3.0, SENT);
        assertArrayEquals(REFERENCE, a.aligned(3.0, REFERENCE, SENT), 1e-15);
    }

    @Test
    void theOffsetBetweenRingAndBodyIsTheErrorNotTheLag() {
        ReferenceAligner a = new ReferenceAligner(8);
        a.remember(3.0, SENT);
        double[] ghost = a.aligned(3.0, REFERENCE, NOW);
        for (int i = 0; i < ghost.length; i++) {
            assertEquals(REFERENCE[i] - SENT[i], ghost[i] - NOW[i], 1e-15);
        }
    }

    @Test
    void anUnknownTimeFallsBackToACopyOfTheReference() {
        ReferenceAligner a = new ReferenceAligner(8);
        a.remember(3.0, SENT);
        double[] ghost = a.aligned(4.0, REFERENCE, NOW);
        assertArrayEquals(REFERENCE, ghost, 0.0);
        assertNotSame(REFERENCE, ghost);
    }

    @Test
    void aNullReferenceGivesNull() {
        ReferenceAligner a = new ReferenceAligner(8);
        a.remember(3.0, SENT);
        assertNull(a.aligned(3.0, null, NOW));
    }

    @Test
    void wrongLengthsFallBackToTheReference() {
        ReferenceAligner a = new ReferenceAligner(8);
        a.remember(3.0, SENT);
        assertArrayEquals(REFERENCE, a.aligned(3.0, REFERENCE, new double[] {1.0, 2.0, 3.0}), 0.0);
        assertArrayEquals(REFERENCE, a.aligned(3.0, REFERENCE, null), 0.0);
        ReferenceAligner b = new ReferenceAligner(8);
        b.remember(3.0, new double[] {1.0, 2.0, 3.0});
        assertArrayEquals(REFERENCE, b.aligned(3.0, REFERENCE, NOW), 0.0);
    }

    @Test
    void aNonFiniteResultFallsBackToTheReference() {
        ReferenceAligner a = new ReferenceAligner(8);
        a.remember(3.0, SENT);
        double[] bad = NOW.clone();
        bad[2] = Double.NaN;
        assertArrayEquals(REFERENCE, a.aligned(3.0, REFERENCE, bad), 0.0);
    }

    @Test
    void theRememberedPositionsAreACopy() {
        ReferenceAligner a = new ReferenceAligner(8);
        double[] sent = SENT.clone();
        a.remember(3.0, sent);
        sent[0] = 99.0; // the caller reuses its array
        double[] ghost = a.aligned(3.0, REFERENCE, NOW);
        assertEquals(NOW[0] + (REFERENCE[0] - SENT[0]), ghost[0], 1e-15);
    }

    @Test
    void theResultIsNeverTheCallersArray() {
        ReferenceAligner a = new ReferenceAligner(8);
        a.remember(3.0, SENT);
        double[] ghost = a.aligned(3.0, REFERENCE, NOW);
        assertNotSame(REFERENCE, ghost);
        assertNotSame(NOW, ghost);
    }

    @Test
    void onlyTheNewestSubmissionsAreRemembered() {
        ReferenceAligner a = new ReferenceAligner(3);
        for (int k = 1; k <= 4; k++) {
            a.remember(k, new double[] {k, 0.0, 0.0});
        }
        double[] ref = {10.0, 0.0, 0.0};
        double[] now = {20.0, 0.0, 0.0};
        assertArrayEquals(ref, a.aligned(1.0, ref, now), 0.0); // forgotten: plain reference
        assertEquals(20.0 + (10.0 - 2.0), a.aligned(2.0, ref, now)[0], 1e-15);
        assertEquals(20.0 + (10.0 - 4.0), a.aligned(4.0, ref, now)[0], 1e-15);
    }

    @Test
    void anEarlierTimeMeansAResetAndForgetsTheOldRun() {
        ReferenceAligner a = new ReferenceAligner(8);
        a.remember(5.0, new double[] {5.0, 0.0, 0.0});
        a.remember(0.0, new double[] {0.0, 0.0, 0.0});
        double[] ref = {10.0, 0.0, 0.0};
        double[] now = {20.0, 0.0, 0.0};
        assertArrayEquals(ref, a.aligned(5.0, ref, now), 0.0);
        assertEquals(20.0 + 10.0, a.aligned(0.0, ref, now)[0], 1e-15);
    }

    @Test
    void theSameTimeSubmittedAgainReplacesNothingAndTheNewestWins() {
        ReferenceAligner a = new ReferenceAligner(8);
        a.remember(3.0, new double[] {1.0, 0.0, 0.0});
        a.remember(3.0, new double[] {2.0, 0.0, 0.0});
        double[] ref = {10.0, 0.0, 0.0};
        double[] now = {20.0, 0.0, 0.0};
        assertEquals(20.0 + (10.0 - 2.0), a.aligned(3.0, ref, now)[0], 1e-15);
    }

    @Test
    void clearForgetsEverythingAndBadInputIsIgnored() {
        ReferenceAligner a = new ReferenceAligner(8);
        a.remember(3.0, SENT);
        a.remember(Double.NaN, SENT);
        a.remember(4.0, null);
        a.clear();
        assertArrayEquals(REFERENCE, a.aligned(3.0, REFERENCE, NOW), 0.0);
    }

    @Test
    void capacityMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> new ReferenceAligner(0));
        assertTrue(new ReferenceAligner(1) != null);
    }
}
