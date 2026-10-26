package dev.chaoslab.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.chaoslab.config.ScenarioDraft;
import dev.chaoslab.experiments.LagrangeTriangle;
import dev.chaoslab.integration.DormandPrince45Integrator.Tolerances;
import dev.chaoslab.physics.NBodySystem;
import dev.chaoslab.physics.State;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link LyapunovTracker}: it recovers the analytic exponent of the unstable Lagrange triangle, decays to
 * zero on a regular orbit, works in SI units, does not depend on how the caller paces its requests, and reports a
 * collision instead of hiding it.
 */
class LyapunovTrackerTest {

    /** Growth rate of the equal-mass Lagrange triangle's instability, from an independent Floquet computation. */
    private static final double TRUE_EXPONENT = 0.537285;

    private static final NBodySystem LAGRANGE = LagrangeTriangle.system();
    private static final NBodySystem TWO_BODY = NBodySystem.normalized(1.0, 1.0);
    private static final Tolerances TOL = LyapunovTracker.DEFAULT_TOLERANCES;

    private static LyapunovTracker lagrange(long seed) {
        return new LyapunovTracker(LAGRANGE, LagrangeTriangle.state(0.0), TOL, 1e-8, seed);
    }

    private static State circular() {
        double v = Math.sqrt(0.5);
        return State.of(0.0, new double[] {-0.5, 0, 0, 0.5, 0, 0}, new double[] {0, -v, 0, 0, v, 0});
    }

    // ---------------------------------------------------------------- accuracy

    @Test
    void recoversTheAnalyticExponentOfTheLagrangeTriangleFromEveryStartingDirection() {
        for (long seed = 1; seed <= 4; seed++) {
            LyapunovTracker t = lagrange(seed);
            t.advanceTo(50.0);
            assertTrue(t.failure() == null, t.failure());
            // measured over seeds 1..4 at t = 50: 0.53728 .. 0.53729
            assertEquals(TRUE_EXPONENT, t.recentSlope(0.5), 1e-4, "seed " + seed);
            assertTrue(t.maxRelativeEnergyDrift() > 0.0 && t.maxRelativeEnergyDrift() < 1e-9,
                    "energy drift " + t.maxRelativeEnergyDrift()); // measured 1.9e-11
        }
    }

    @Test
    void theRunningExponentConvergesMoreSlowlyThanTheSlope() {
        LyapunovTracker t = lagrange(1);
        t.advanceTo(50.0);
        // measured: running value 0.40 at t = 100, slope 0.53728 already at t = 25: the b/t offset is why
        double runningError = Math.abs(t.runningExponent() - TRUE_EXPONENT);
        double slopeError = Math.abs(t.recentSlope(0.5) - TRUE_EXPONENT);
        assertTrue(slopeError < runningError / 10.0, "slope error " + slopeError + ", running error " + runningError);
    }

    @Test
    void aRegularOrbitGivesAnExponentThatDecaysTowardZero() {
        LyapunovTracker t = new LyapunovTracker(TWO_BODY, circular(), TOL, 1e-8, 1);
        t.advanceTo(100.0);
        double at100 = t.recentSlope(0.5);
        t.advanceTo(400.0);
        double at400 = t.recentSlope(0.5);
        assertTrue(at100 < 0.03, "slope at t=100: " + at100);               // measured 0.0137
        assertTrue(at400 < 0.5 * at100, "slope at t=400: " + at400);          // measured 0.0034
        assertTrue(t.failure() == null, t.failure());
    }

    @Test
    void worksInSiUnitsWhereAnOffsetOfOneMetreWouldBeInvisible() {
        ScenarioDraft.Scenario sunEarth = ScenarioDraft.sunEarth().validate(true).scenario();
        LyapunovTracker t = new LyapunovTracker(sunEarth.system(), sunEarth.initial(), TOL, 1e-8, 1);
        double r = sunEarth.initial().position(1).minus(sunEarth.initial().position(0)).norm();
        double expectedTau = Math.sqrt(r * r * r / (sunEarth.system().gravitationalConstant()
                * (sunEarth.system().mass(0) + sunEarth.system().mass(1))));
        assertEquals(expectedTau, t.characteristicTime(), 1e-9 * expectedTau);
        assertEquals(r, t.characteristicLength(), 1e-9 * r);

        t.advanceTo(5.0 * 365.25 * 86400.0);
        assertTrue(t.failure() == null, t.failure());
        assertTrue(t.renormalisations() >= 30, "renormalisations " + t.renormalisations());
        assertTrue(Math.abs(t.recentSlope(0.5) * t.characteristicTime()) < 0.1,
                "dimensionless slope " + t.recentSlope(0.5) * t.characteristicTime()); // measured 0.02 at 10 years
        assertTrue(t.maxRelativeEnergyDrift() < 1e-9, "energy drift " + t.maxRelativeEnergyDrift());
    }

    @Test
    void theInitialOffsetHasTheRequestedDimensionlessSizeInAnyUnitSystem() {
        assertEquals(1e-8, lagrange(1).separation(), 1e-14);
        ScenarioDraft.Scenario sunEarth = ScenarioDraft.sunEarth().validate(true).scenario();
        LyapunovTracker si = new LyapunovTracker(sunEarth.system(), sunEarth.initial(), TOL, 1e-8, 1);
        assertEquals(1e-8, si.separation(), 1e-14); // 1.5e3 m on 1.5e11 m: still resolved in double precision
        LyapunovTracker finer = new LyapunovTracker(sunEarth.system(), sunEarth.initial(), TOL, 1e-10, 2);
        assertEquals(1e-10, finer.separation(), 1e-16);
        si.advanceTo(10.0 * si.interval());
        assertEquals(1e-8, si.separation(), 1e-14); // pulled back to d0 after every renormalisation
    }

    @Test
    void theScalesAreTheLongestPairDistanceAndTheShortestPairTimescale() {
        LyapunovTracker t = lagrange(1);
        double side = Math.sqrt(3.0);
        assertEquals(side, t.characteristicLength(), 1e-12);
        assertEquals(Math.sqrt(side * side * side / 2.0), t.characteristicTime(), 1e-12);
        assertEquals(t.characteristicTime(), t.interval(), 0.0);
    }

    // ---------------------------------------------------------------- determinism

    @Test
    void theResultDoesNotDependOnHowTheCallerPacesItsRequests() {
        LyapunovTracker paced = lagrange(3);
        paced.advanceTo(10.0);
        paced.advanceTo(10.0);
        paced.advanceTo(33.3);
        paced.advanceTo(20.0); // an earlier time is ignored
        paced.advanceTo(60.0);
        LyapunovTracker direct = lagrange(3);
        direct.advanceTo(60.0);
        assertEquals(direct.renormalisations(), paced.renormalisations());
        assertTrue(Arrays.equals(direct.cumulativeGrowth(), paced.cumulativeGrowth()));
        assertTrue(Arrays.equals(direct.times(), paced.times()));
    }

    @Test
    void sameSeedGivesTheSameResultAndADifferentSeedDoesNot() {
        LyapunovTracker a = lagrange(7);
        LyapunovTracker b = lagrange(7);
        LyapunovTracker c = lagrange(8);
        a.advanceTo(30.0);
        b.advanceTo(30.0);
        c.advanceTo(30.0);
        assertTrue(Arrays.equals(a.cumulativeGrowth(), b.cumulativeGrowth()));
        assertFalse(Arrays.equals(a.cumulativeGrowth(), c.cumulativeGrowth()));
    }

    // ---------------------------------------------------------------- before the first result

    @Test
    void nothingIsReportedBeforeTheFirstRenormalisationIsComplete() {
        LyapunovTracker t = lagrange(1);
        assertEquals(0, t.renormalisations());
        assertTrue(Double.isNaN(t.runningExponent()));
        assertTrue(Double.isNaN(t.recentSlope(0.5)));
        assertEquals(0.0, t.elapsed(), 0.0);
        t.advanceTo(0.9 * t.interval());
        assertEquals(0, t.renormalisations());
        t.advanceTo(t.interval());
        assertEquals(1, t.renormalisations());
        assertEquals(t.interval(), t.elapsed(), 0.0);
        assertTrue(Double.isNaN(t.recentSlope(0.5))); // fewer than 4 points
        t.advanceTo(4.0 * t.interval());
        assertEquals(4, t.renormalisations());
        assertFalse(Double.isNaN(t.recentSlope(0.5)));
        assertEquals(4, t.times().length);
    }

    // ---------------------------------------------------------------- failure

    @Test
    void aCollisionStopsTheTrackerAndKeepsWhatWasComputed() {
        State head = State.of(0.0, new double[] {-0.5, 0, 0, 0.5, 0, 0}, new double[6]); // collide at pi/4
        LyapunovTracker t = new LyapunovTracker(TWO_BODY, head, TOL, 1e-8, 1);
        t.advanceTo(5.0);
        assertFalse(t.failure() == null);
        assertTrue(t.failure().contains("Stopped after 1 renormalisations"), t.failure()); // interval 0.707 < pi/4
        assertEquals(1, t.renormalisations());
        int before = t.renormalisations();
        t.advanceTo(50.0);
        assertEquals(before, t.renormalisations()); // nothing more happens after a failure
        assertFalse(Double.isNaN(t.runningExponent()));
    }

    // ---------------------------------------------------------------- arguments

    @Test
    void invalidArgumentsAreRejected() {
        State s = LagrangeTriangle.state(0.0);
        assertThrows(IllegalArgumentException.class, () -> new LyapunovTracker(null, s, TOL, 1e-8, 1));
        assertThrows(IllegalArgumentException.class, () -> new LyapunovTracker(LAGRANGE, null, TOL, 1e-8, 1));
        assertThrows(IllegalArgumentException.class, () -> new LyapunovTracker(LAGRANGE, s, null, 1e-8, 1));
        assertThrows(IllegalArgumentException.class, () -> new LyapunovTracker(LAGRANGE, s, TOL, 1e-13, 1)); // too small
        assertThrows(IllegalArgumentException.class, () -> new LyapunovTracker(LAGRANGE, s, TOL, 0.1, 1));    // too large
        assertThrows(IllegalArgumentException.class, () -> new LyapunovTracker(LAGRANGE, s, TOL, Double.NaN, 1));
        assertThrows(IllegalArgumentException.class, () -> new LyapunovTracker(TWO_BODY, s, TOL, 1e-8, 1)); // 3 bodies
        State coincident = State.of(0, new double[] {1, 1, 1, 1, 1, 1}, new double[6]);
        assertThrows(NBodySystem.SingularConfigurationException.class,
                () -> new LyapunovTracker(TWO_BODY, coincident, TOL, 1e-8, 1));
        LyapunovTracker t = lagrange(1);
        assertThrows(IllegalArgumentException.class, () -> t.advanceTo(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> t.recentSlope(0.0));
        assertThrows(IllegalArgumentException.class, () -> t.recentSlope(1.5));
    }
}
