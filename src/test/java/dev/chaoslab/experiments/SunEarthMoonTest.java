package dev.chaoslab.experiments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.chaoslab.analysis.ReferenceTrack;
import dev.chaoslab.config.ScenarioDraft;
import dev.chaoslab.integration.DormandPrince45Integrator.Tolerances;
import dev.chaoslab.integration.VelocityVerletIntegrator;
import dev.chaoslab.physics.Diagnostics;
import dev.chaoslab.physics.NBodySystem;
import dev.chaoslab.physics.State;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link SunEarthMoon}. The numbers in the comments were measured in this project (reference:
 * Dormand-Prince at tolerance 1e-13), not taken from a table.
 */
class SunEarthMoonTest {

    private static final NBodySystem SYSTEM = SunEarthMoon.system();
    private static final double DAY = 86_400.0;
    private static final double YEAR = 365.25 * DAY;

    private static double distance(double[] q, int a, int b) {
        return Math.sqrt(Math.pow(q[3 * a] - q[3 * b], 2) + Math.pow(q[3 * a + 1] - q[3 * b + 1], 2)
                + Math.pow(q[3 * a + 2] - q[3 * b + 2], 2));
    }

    private static ReferenceTrack track() {
        return new ReferenceTrack(SYSTEM, SunEarthMoon.state(), SunEarthMoon.STEP, new Tolerances(1e-13, 1e-13));
    }

    @Test
    void theStartHasTheStatedGeometryAndMasses() {
        double[] q = SunEarthMoon.state().positions();
        assertEquals(SunEarthMoon.MOON_DISTANCE, distance(q, 1, 2), 1e-2);
        assertEquals(SunEarthMoon.SUN_MASS, SYSTEM.mass(0), 0.0);
        assertEquals(SunEarthMoon.EARTH_MASS, SYSTEM.mass(1), 0.0);
        assertEquals(SunEarthMoon.MOON_MASS, SYSTEM.mass(2), 0.0);
        assertEquals(NBodySystem.G_SI, SYSTEM.gravitationalConstant(), 0.0);
        // the Earth-Moon barycentre is one astronomical unit from the Sun
        double em = SunEarthMoon.EARTH_MASS + SunEarthMoon.MOON_MASS;
        double bx = (SunEarthMoon.EARTH_MASS * q[3] + SunEarthMoon.MOON_MASS * q[6]) / em;
        assertEquals(SunEarthMoon.SUN_DISTANCE, bx - q[0], 1e-2);
    }

    @Test
    void theCentreOfMassAndTheMomentumAreZeroToRounding() {
        Diagnostics.Invariants inv = Diagnostics.measure(SYSTEM, SunEarthMoon.state());
        double momentumScale = SunEarthMoon.EARTH_MASS * 29_780.0; // Earth's orbital momentum, about 1.8e29
        assertTrue(inv.momentum().norm() < 1e-12 * momentumScale, "|P| = " + inv.momentum().norm()); // measured 8.5e12
        assertTrue(Math.abs(inv.centreOfMass().x()) < 1e-3, "COM x = " + inv.centreOfMass().x()); // measured 2.3e-12
        assertEquals(0.0, inv.centreOfMass().y(), 0.0);
    }

    @Test
    void theSunPullsTheMoonAboutTwoPointTwoTimesAsHardAsTheEarthDoes() {
        double[] q = SunEarthMoon.state().positions();
        double sun = SunEarthMoon.SUN_MASS / Math.pow(distance(q, 0, 2), 2);
        double earth = SunEarthMoon.EARTH_MASS / Math.pow(distance(q, 1, 2), 2);
        assertEquals(2.2, sun / earth, 0.1); // 2.19
    }

    @Test
    void theMoonStaysBoundAndRegularForAYear() {
        ReferenceTrack ref = track();
        double min = Double.MAX_VALUE;
        double max = 0.0;
        double unwrapped = 0.0;
        double previous = Double.NaN;
        for (double t = 0.0; t <= YEAR; t += 0.1 * DAY) {
            double[] q = ref.positionsAt(t);
            double d = distance(q, 1, 2);
            min = Math.min(min, d);
            max = Math.max(max, d);
            double angle = Math.atan2(q[7] - q[4], q[6] - q[3]);
            if (!Double.isNaN(previous)) {
                double step = angle - previous;
                while (step > Math.PI) {
                    step -= 2.0 * Math.PI;
                }
                while (step < -Math.PI) {
                    step += 2.0 * Math.PI;
                }
                unwrapped += step;
            }
            previous = angle;
        }
        assertTrue(min > 3.72e8 && min < 3.76e8, "closest Earth-Moon distance " + min); // measured 3.7379e8
        assertTrue(max > 3.87e8 && max < 3.90e8, "farthest Earth-Moon distance " + max); // measured 3.8822e8
        double period = YEAR / DAY / (unwrapped / (2.0 * Math.PI));
        assertTrue(period > 26.9 && period < 27.2, "period in days " + period); // measured 27.06 (real 27.32)
        assertTrue(ref.maxRelativeEnergyDrift() < 1e-12, "reference energy drift " + ref.maxRelativeEnergyDrift());
        assertEquals(null, ref.failure());
    }

    private static double verletSeparationError(ReferenceTrack ref, double h) {
        State s0 = SunEarthMoon.state();
        double[] q = s0.positions().clone();
        double[] v = s0.velocities().clone();
        VelocityVerletIntegrator verlet = new VelocityVerletIntegrator();
        long n = Math.round(YEAR / h);
        for (long i = 0; i < n; i++) {
            verlet.step(SYSTEM, q, v, h);
        }
        double[] truth = ref.positionsAt(n * h);
        return Math.abs(distance(q, 1, 2) - distance(truth, 1, 2));
    }

    @Test
    void verletErrorFallsByAboutFourWhenTheStepIsHalved() {
        ReferenceTrack ref = track();
        double coarse = verletSeparationError(ref, 3600.0);
        double fine = verletSeparationError(ref, 1800.0);
        assertTrue(coarse > 5e3 && coarse < 3e4, "h=3600 s separation error " + coarse); // measured 1.44e4 m
        double ratio = coarse / fine;
        assertTrue(ratio > 3.4 && ratio < 4.6, "error ratio for a halved step " + ratio); // measured 4.0
    }

    @Test
    void thePresetDraftIsValidAndReadsBackAsTheSameScenario() {
        ScenarioDraft draft = ScenarioDraft.of(SYSTEM, SunEarthMoon.state(), SunEarthMoon.STEP);
        ScenarioDraft.Validation v = draft.validate(true);
        assertTrue(v.isValid(), String.valueOf(v.issues()));
        State back = v.scenario().initial();
        assertEquals(0.0, maxDifference(SunEarthMoon.state().positions(), back.positions()), 0.0);
        assertEquals(0.0, maxDifference(SunEarthMoon.state().velocities(), back.velocities()), 0.0);
    }

    private static double maxDifference(double[] a, double[] b) {
        double m = 0.0;
        for (int i = 0; i < a.length; i++) {
            m = Math.max(m, Math.abs(a[i] - b[i]));
        }
        return m;
    }
}
