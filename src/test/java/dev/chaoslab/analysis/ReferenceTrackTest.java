package dev.chaoslab.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.chaoslab.experiments.LagrangeTriangle;
import dev.chaoslab.integration.DormandPrince45Integrator;
import dev.chaoslab.integration.DormandPrince45Integrator.Tolerances;
import dev.chaoslab.physics.NBodySystem;
import dev.chaoslab.physics.State;
import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ReferenceTrack}: the interpolation formula itself, agreement with an exact orbit, bit-for-bit
 * independence from the pattern of queries, replay after an old time is requested, and failure reporting.
 */
class ReferenceTrackTest {

    private static final NBodySystem TWO_BODY = NBodySystem.normalized(1.0, 1.0);
    private static final double OMEGA = Math.sqrt(2.0); // two unit masses, separation 1, G = 1

    /** Two unit masses on a circular orbit of separation 1; the exact motion is a rigid rotation at OMEGA. */
    private static State circular() {
        double v = Math.sqrt(0.5);
        return State.of(0.0, new double[] {-0.5, 0, 0, 0.5, 0, 0}, new double[] {0, -v, 0, 0, v, 0});
    }

    /** Two unit masses at rest, separation 1: they fall together and collide at exactly pi/4. */
    private static State headOn() {
        return State.of(0.0, new double[] {-0.5, 0, 0, 0.5, 0, 0}, new double[6]);
    }

    private static ReferenceTrack track(NBodySystem sys, State start) {
        return new ReferenceTrack(sys, start, 0.01, ReferenceTrack.DEFAULT_TOLERANCES);
    }

    /** Largest distance between the track's bodies and the exact circular orbit at time t. */
    private static double errorVersusExactCircle(double[] p, double t) {
        double ex = 0.5 * Math.cos(OMEGA * t);
        double ey = 0.5 * Math.sin(OMEGA * t);
        return Math.max(Math.hypot(p[0] + ex, p[1] + ey), Math.hypot(p[3] - ex, p[4] - ey));
    }

    // ---------------------------------------------------------------- the interpolation formula

    @Test
    void quinticHermiteReproducesEveryDegreeFivePolynomialExactly() {
        double[][] c = {{1.0, 2.0, 3.0, -1.0, 0.5, -2.0}, {-0.3, 0.7, -1.1, 2.5, -0.25, 1.5}};
        double t0 = 0.3;
        double t1 = 0.9;
        double[] q0 = new double[2];
        double[] v0 = new double[2];
        double[] a0 = new double[2];
        double[] q1 = new double[2];
        double[] v1 = new double[2];
        double[] a1 = new double[2];
        for (int i = 0; i < 2; i++) {
            q0[i] = poly(c[i], t0, 0);
            v0[i] = poly(c[i], t0, 1);
            a0[i] = poly(c[i], t0, 2);
            q1[i] = poly(c[i], t1, 0);
            v1[i] = poly(c[i], t1, 1);
            a1[i] = poly(c[i], t1, 2);
        }
        for (double t : new double[] {0.3, 0.37, 0.5, 0.6, 0.77, 0.9}) {
            double[] p = ReferenceTrack.interpolate(t0, t1, q0, v0, a0, q1, v1, a1, t);
            assertEquals(poly(c[0], t, 0), p[0], 1e-13, "coordinate 0 at t=" + t);
            assertEquals(poly(c[1], t, 0), p[1], 1e-13, "coordinate 1 at t=" + t);
        }
    }

    /** The k-th derivative of the polynomial with coefficients c (c[0] + c[1] t + ...) at t. */
    private static double poly(double[] c, double t, int k) {
        double sum = 0.0;
        for (int n = k; n < c.length; n++) {
            double factor = 1.0;
            for (int j = 0; j < k; j++) {
                factor *= n - j;
            }
            sum += c[n] * factor * Math.pow(t, n - k);
        }
        return sum;
    }

    @Test
    void interpolationReturnsTheEndValuesExactlyAtTheEnds() {
        double[] q0 = {0.1, -0.2, 0.3};
        double[] v0 = {1, 2, 3};
        double[] a0 = {-1, 0.5, 2};
        double[] q1 = {0.7, 0.8, -0.9};
        double[] v1 = {-3, 1, 0};
        double[] a1 = {4, -5, 6};
        assertTrue(Arrays.equals(q0, ReferenceTrack.interpolate(2.0, 2.37, q0, v0, a0, q1, v1, a1, 2.0)));
        assertTrue(Arrays.equals(q1, ReferenceTrack.interpolate(2.0, 2.37, q0, v0, a0, q1, v1, a1, 2.37)));
    }

    // ---------------------------------------------------------------- accuracy

    @Test
    void followsAnExactCircularOrbitAtArbitraryTimesIncludingBetweenSteps() {
        ReferenceTrack t = track(TWO_BODY, circular());
        Random rng = new Random(1);
        double worst = 0.0;
        for (int k = 0; k < 2000; k++) {
            double time = k * 0.02 + rng.nextDouble() * 0.02; // increasing, off the step grid
            worst = Math.max(worst, errorVersusExactCircle(t.positionsAt(time), time));
        }
        // measured 2.5e-10 after 9 orbits (accumulated integration error at tolerance 1e-13)
        assertTrue(worst < 1e-9, "worst error " + worst);
        assertTrue(t.maxRelativeEnergyDrift() > 0.0 && t.maxRelativeEnergyDrift() < 1e-10,
                "energy drift " + t.maxRelativeEnergyDrift()); // measured 1.2e-11
        assertEquals(0, t.rewinds());
        assertTrue(t.failure() == null);
    }

    @Test
    void memoryStaysBoundedWhenQueriesAdvanceSteadily() {
        ReferenceTrack t = track(TWO_BODY, circular());
        for (int k = 1; k <= 400; k++) {
            t.positionsAt(0.1 * k);
        }
        assertTrue(t.acceptedSteps() > 1000, "steps " + t.acceptedSteps());
        assertTrue(t.retainedNodes() < 60, "retained " + t.retainedNodes());
    }

    // ---------------------------------------------------------------- determinism

    @Test
    void nodeTimesAreReturnedExactlyAsTheIntegratorProducedThem() {
        ReferenceTrack t = track(TWO_BODY, circular());
        t.positionsAt(5.0);
        double reached = t.reachedTime();
        assertTrue(reached >= 5.0);

        // replay the same step sequence by hand
        DormandPrince45Integrator dp = new DormandPrince45Integrator();
        double[] q = circular().positions();
        double[] v = circular().velocities();
        double time = 0.0;
        while (time < reached) {
            time += dp.adaptiveStep(TWO_BODY, q, v, time, 0.01, ReferenceTrack.DEFAULT_TOLERANCES).stepSize();
        }
        assertEquals(reached, time, 0.0);
        assertTrue(Arrays.equals(q, t.positionsAt(reached)), "positions at the last node differ from a manual replay");
    }

    @Test
    void resultsDoNotDependOnWhichTimesWereAskedBefore() {
        ReferenceTrack asked = track(TWO_BODY, circular());
        for (int k = 1; k <= 500; k++) {
            asked.positionsAt(0.04 * k); // many queries up to t = 20
        }
        ReferenceTrack direct = track(TWO_BODY, circular());
        assertTrue(Arrays.equals(direct.positionsAt(20.0), asked.positionsAt(20.0)));
        assertTrue(Arrays.equals(direct.positionsAt(20.123), asked.positionsAt(20.123)));
    }

    @Test
    void askingForAnOldTimeReplaysFromTheStartAndGivesTheSameBits() {
        ReferenceTrack t = track(TWO_BODY, circular());
        t.positionsAt(30.0);
        double[] again = t.positionsAt(0.5); // far older than the retained nodes
        assertTrue(t.rewinds() >= 1, "rewinds " + t.rewinds());
        assertTrue(Arrays.equals(track(TWO_BODY, circular()).positionsAt(0.5), again));
        assertTrue(Arrays.equals(track(TWO_BODY, circular()).positionsAt(30.0), t.positionsAt(30.0)));
    }

    @Test
    void aSmallStepBackwardNeedsNoReplay() {
        ReferenceTrack t = track(TWO_BODY, circular());
        double[] first = t.positionsAt(10.0);
        double[] back = t.positionsAt(9.9); // about ten steps back, within the retained history
        assertEquals(0, t.rewinds());
        assertTrue(Arrays.equals(track(TWO_BODY, circular()).positionsAt(9.9), back));
        assertTrue(Arrays.equals(first, t.positionsAt(10.0)));
    }

    @Test
    void aLaterStartTimeIsTheEarliestQueryableTime() {
        State late = circular().withTime(5.0);
        ReferenceTrack t = track(TWO_BODY, late);
        assertTrue(Arrays.equals(late.positions(), t.positionsAt(5.0)));
        assertThrows(IllegalArgumentException.class, () -> t.positionsAt(4.999));
        double[] p = t.positionsAt(6.0);
        assertTrue(Math.hypot(p[3] - 0.5 * Math.cos(OMEGA), p[4] - 0.5 * Math.sin(OMEGA)) < 1e-9); // 1 time unit later
    }

    // ---------------------------------------------------------------- chaos: what the reference can and cannot do

    @Test
    void followsTheExactLagrangeSolutionUntilRoundingErrorHasGrownAndNoLonger() {
        NBodySystem sys = LagrangeTriangle.system();
        ReferenceTrack t = track(sys, LagrangeTriangle.state(0.0));
        double at30 = ReferenceTrack.maxPositionGap(t.positionsAt(30.0), LagrangeTriangle.exactState(30.0).positions());
        double at50 = ReferenceTrack.maxPositionGap(t.positionsAt(50.0), LagrangeTriangle.exactState(50.0).positions());
        double at70 = ReferenceTrack.maxPositionGap(t.positionsAt(70.0), LagrangeTriangle.exactState(70.0).positions());
        assertTrue(at30 < 1e-8, "t=30 gap " + at30);          // measured 6.4e-10
        assertTrue(at50 < 1e-3, "t=50 gap " + at50);          // measured 2.8e-5
        assertTrue(at70 > 0.1, "t=70 gap " + at70);           // measured 1.7: rounding errors have grown to order one
        // the growth between t=30 and t=50 is the known instability rate 0.537 per time unit
        double rate = Math.log(at50 / at30) / 20.0;
        assertEquals(0.5373, rate, 0.02, "growth rate " + rate);
    }

    // ---------------------------------------------------------------- failure

    @Test
    void aCollisionEndsTheTrackAndIsReportedNotHidden() {
        ReferenceTrack t = track(TWO_BODY, headOn());
        t.positionsAt(0.5);
        assertTrue(t.failure() == null);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> t.positionsAt(1.0));
        assertTrue(e.getMessage().contains("ends at"), e.getMessage());
        assertFalse(t.failure() == null);
        assertEquals(Math.PI / 4.0, t.reachedTime(), 1e-9); // measured 0.785398163397, exact pi/4 = 0.785398163397
        assertTrue(t.reachedTime() < Math.PI / 4.0 + 1e-9);
        // everything before the end is still available
        double[] p = t.positionsAt(0.7);
        assertTrue(p[0] < 0.0 && p[3] > 0.0);
    }

    // ---------------------------------------------------------------- utilities and arguments

    @Test
    void maxPositionGapIsTheLargestDistanceBetweenCorrespondingBodies() {
        double[] a = {0, 0, 0, 1, 1, 1};
        double[] b = {0, 0, 3, 1, 1, 1};
        assertEquals(3.0, ReferenceTrack.maxPositionGap(a, b), 0.0);
        assertEquals(0.0, ReferenceTrack.maxPositionGap(a, a), 0.0);
        assertThrows(IllegalArgumentException.class, () -> ReferenceTrack.maxPositionGap(a, new double[3]));
        assertThrows(IllegalArgumentException.class, () -> ReferenceTrack.maxPositionGap(new double[4], new double[4]));
    }

    @Test
    void invalidArgumentsAreRejected() {
        Tolerances tol = ReferenceTrack.DEFAULT_TOLERANCES;
        State s = circular();
        assertThrows(IllegalArgumentException.class, () -> new ReferenceTrack(null, s, 0.01, tol));
        assertThrows(IllegalArgumentException.class, () -> new ReferenceTrack(TWO_BODY, null, 0.01, tol));
        assertThrows(IllegalArgumentException.class, () -> new ReferenceTrack(TWO_BODY, s, 0.01, null));
        assertThrows(IllegalArgumentException.class, () -> new ReferenceTrack(TWO_BODY, s, 0.0, tol));
        assertThrows(IllegalArgumentException.class, () -> new ReferenceTrack(TWO_BODY, s, Double.NaN, tol));
        assertThrows(IllegalArgumentException.class,
                () -> new ReferenceTrack(LagrangeTriangle.system(), s, 0.01, tol)); // 3 bodies vs 2-body state
        State coincident = State.of(0, new double[] {1, 1, 1, 1, 1, 1}, new double[6]);
        assertThrows(NBodySystem.SingularConfigurationException.class, () -> new ReferenceTrack(TWO_BODY, coincident,
                0.01, tol));
        ReferenceTrack t = track(TWO_BODY, s);
        assertThrows(IllegalArgumentException.class, () -> t.positionsAt(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> t.positionsAt(-1.0));
    }
}
