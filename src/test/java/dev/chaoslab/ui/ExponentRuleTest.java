package dev.chaoslab.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.chaoslab.analysis.LyapunovTracker;
import dev.chaoslab.config.ScenarioDraft;
import dev.chaoslab.experiments.FigureEight;
import dev.chaoslab.ui.ExponentRule.Verdict;
import org.junit.jupiter.api.Test;

/** Tests for {@link ExponentRule}: the thresholds, and its verdicts on a regular and on a chaotic start. */
class ExponentRuleTest {

    @Test
    void withoutAnyRenormalisationThereIsNoData() {
        assertEquals(Verdict.NO_DATA, ExponentRule.judge(0, Double.NaN, Double.NaN));
    }

    @Test
    void fewerThanTheMinimumIsProvisionalWhateverTheNumbersSay() {
        assertEquals(Verdict.PROVISIONAL, ExponentRule.judge(1, 0.5, 0.5));
        assertEquals(Verdict.PROVISIONAL, ExponentRule.judge(ExponentRule.MIN_RENORMALISATIONS - 1, 0.5, 0.5));
    }

    @Test
    void theMinimumItselfIsEnough() {
        assertEquals(Verdict.CLAIMED, ExponentRule.judge(ExponentRule.MIN_RENORMALISATIONS, 0.5, 0.5));
    }

    @Test
    void aSlopeFarBelowTheRunningMeanIsSlowing() {
        assertEquals(Verdict.SLOWING, ExponentRule.judge(40, 0.10, 0.02));
    }

    @Test
    void theSlopeFractionIsTheBoundary() {
        double running = 0.2;
        double edge = ExponentRule.SLOPE_FRACTION * running;
        assertEquals(Verdict.CLAIMED, ExponentRule.judge(40, running, edge));
        assertEquals(Verdict.SLOWING, ExponentRule.judge(40, running, Math.nextDown(edge)));
    }

    @Test
    void aSlopeLargerThanTheRunningMeanIsClaimed() {
        assertEquals(Verdict.CLAIMED, ExponentRule.judge(30, 0.14, 0.23));
    }

    @Test
    void anUnavailableSlopeOrRunningMeanIsNeverClaimed() {
        assertEquals(Verdict.SLOWING, ExponentRule.judge(40, 0.1, Double.NaN));
        assertEquals(Verdict.SLOWING, ExponentRule.judge(40, Double.NaN, 0.1));
    }

    @Test
    void theFractionIsStricterThanTheEarlierHalf() {
        // the earlier rule (0.5) claimed an exponent for a regular Sun-Earth-Moon system at slope/running = 0.57
        assertTrue(ExponentRule.SLOPE_FRACTION > 0.57);
        assertEquals(Verdict.SLOWING, ExponentRule.judge(27, 0.0400, 0.0228));
    }

    /** Samples the verdict after 10, 12, ..., 60 renormalisations and counts the claims. */
    private static int claims(dev.chaoslab.physics.NBodySystem system, dev.chaoslab.physics.State start) {
        LyapunovTracker tracker = new LyapunovTracker(system, start, LyapunovTracker.DEFAULT_TOLERANCES,
                LyapunovTracker.DEFAULT_SEPARATION, 1L);
        int claimed = 0;
        for (int k = 10; k <= 60; k += 2) {
            tracker.advanceTo(k * tracker.interval());
            if (ExponentRule.judge(tracker.renormalisations(), tracker.runningExponent(), tracker.recentSlope(0.5))
                    == Verdict.CLAIMED) {
                claimed++;
            }
        }
        return claimed;
    }

    @Test
    void theFigureEightIsNeverClaimed() {
        assertEquals(0, claims(FigureEight.system(), FigureEight.state())); // measured 0 of 26 (0 of 41 to k=90)
    }

    @Test
    void aCircularTwoBodyOrbitIsNeverClaimed() {
        ScenarioDraft.Scenario s = ScenarioDraft.twoBodyCircular().validate(true).scenario();
        assertEquals(0, claims(s.system(), s.initial())); // measured 0 of 41 to k=90
    }

    @Test
    void theExactLagrangeTriangleIsClaimedInEverySample() {
        ScenarioDraft.Scenario s = ScenarioDraft.lagrange(0.0).validate(true).scenario();
        LyapunovTracker tracker = new LyapunovTracker(s.system(), s.initial(), LyapunovTracker.DEFAULT_TOLERANCES,
                LyapunovTracker.DEFAULT_SEPARATION, 1L);
        for (int k = 10; k <= 30; k += 2) {
            tracker.advanceTo(k * tracker.interval());
            assertEquals(Verdict.CLAIMED, ExponentRule.judge(tracker.renormalisations(), tracker.runningExponent(),
                    tracker.recentSlope(0.5)), "after " + k + " renormalisations"); // slope/running 1.03 to 1.10
        }
    }
}
