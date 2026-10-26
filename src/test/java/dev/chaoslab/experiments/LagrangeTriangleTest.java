package dev.chaoslab.experiments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.chaoslab.integration.Rk4Integrator;
import dev.chaoslab.physics.Diagnostics;
import dev.chaoslab.physics.NBodySystem;
import dev.chaoslab.physics.State;
import dev.chaoslab.physics.Vec3;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link LagrangeTriangle}.
 *
 * <p>The first group checks the derivation independently of any integrator (acceleration against -omega^2 q,
 * analytic energy and angular momentum). The second checks that the exact solution is a solution: RK4 follows it
 * for a full period. The third demonstrates the physical instability and one numerical limitation, with thresholds
 * set from measurements made while writing this class (see comments).
 */
class LagrangeTriangleTest {

    private static final NBodySystem SYS = LagrangeTriangle.system();

    private static double maxDeviation(State a, State b) {
        double[] x = a.positions();
        double[] y = b.positions();
        double m = 0.0;
        for (int i = 0; i < x.length; i++) {
            m = Math.max(m, Math.abs(x[i] - y[i]));
        }
        return m;
    }

    // ---------------------------------------------------------------- the derivation

    @Test
    void accelerationIsExactlyCentripetalWithTheDerivedAngularVelocity() {
        double[] q = LagrangeTriangle.state(0).positions();
        double[] a = new double[9];
        SYS.accelerations(q, a);
        double w2 = Math.pow(LagrangeTriangle.angularVelocity(), 2);
        for (int i = 0; i < 9; i++) {
            assertEquals(-w2 * q[i], a[i], 1e-14, "component " + i); // measured 3.9e-16
        }
    }

    @Test
    void theTriangleIsEquilateralWithTheRequestedCircumradiusAndCentredOnTheOrigin() {
        State s = LagrangeTriangle.state(0);
        double side = LagrangeTriangle.side();
        assertEquals(Math.sqrt(3.0), side, 1e-15);
        for (int i = 0; i < 3; i++) {
            assertEquals(1.0, s.position(i).norm(), 1e-15);
            Vec3 d = s.position((i + 1) % 3).minus(s.position(i));
            assertEquals(side, d.norm(), 1e-14);
        }
        Vec3 com = Diagnostics.centreOfMass(SYS, s);
        assertEquals(0.0, com.norm(), 1e-15);
    }

    @Test
    void momentumIsZeroAndAngularMomentumAndEnergyMatchTheAnalyticValues() {
        State s = LagrangeTriangle.state(0);
        double w = LagrangeTriangle.angularVelocity();
        assertEquals(0.0, Diagnostics.linearMomentum(SYS, s).norm(), 1e-15);
        Vec3 l = Diagnostics.angularMomentum(SYS, s);
        assertEquals(3.0 * w, l.z(), 1e-14);          // L = sum m R^2 omega = 3 omega
        assertEquals(0.0, Math.hypot(l.x(), l.y()), 1e-15);
        double kinetic = 1.5 * w * w;                 // (1/2) * 3 * (omega R)^2
        double potential = -3.0 / LagrangeTriangle.side(); // three pairs, -G m^2 / a each
        assertEquals(kinetic + potential, Diagnostics.totalEnergy(SYS, s), 1e-14);
    }

    @Test
    void periodIsTwoPiOverOmegaAndTheExactSolutionReturnsToItsStart() {
        assertEquals(2.0 * Math.PI, LagrangeTriangle.period() * LagrangeTriangle.angularVelocity(), 1e-14);
        State start = LagrangeTriangle.state(0);
        State back = LagrangeTriangle.exactState(LagrangeTriangle.period());
        assertEquals(0.0, maxDeviation(start, back), 1e-14);
        assertEquals(start, LagrangeTriangle.exactState(0.0)); // identical construction at t = 0
    }

    // ---------------------------------------------------------------- the exact solution is a solution

    @Test
    void rk4FollowsTheExactRotationForOneFullPeriod() {
        double h = 1e-3;
        int steps = (int) Math.round(LagrangeTriangle.period() / h);
        State end = new Rk4Integrator().advance(SYS, LagrangeTriangle.state(0), h, steps);
        // measured 1.2e-13 for h = 1e-3 (and 5.0e-10 for h = 1e-2)
        assertEquals(0.0, maxDeviation(end, LagrangeTriangle.exactState(end.time())), 1e-11);
    }

    // ---------------------------------------------------------------- the perturbed scenario

    @Test
    void displacingOneBodyKeepsMomentumZeroAndShiftsTheCentreOfMassPredictably() {
        double d = 0.01;
        State s = LagrangeTriangle.state(d);
        assertEquals(0.0, Diagnostics.linearMomentum(SYS, s).norm(), 1e-15);
        Vec3 com = Diagnostics.centreOfMass(SYS, s);
        assertEquals(0.0, com.x(), 1e-15);
        assertEquals(d / 3.0, com.y(), 1e-15);        // body 0 sits at angle 90 degrees, so +y; mass fraction 1/3
        assertEquals(1.0 + d, s.position(0).norm(), 1e-15);
    }

    @Test
    void aSmallDisplacementGrowsLinearlyThenExponentiallyBecauseTheTriangleIsUnstable() {
        double h = 1e-3;
        int perPeriod = (int) Math.round(LagrangeTriangle.period() / h);
        double[] atOnePeriod = new double[2];
        double[] atTwoPeriods = new double[2];
        double[] displacements = {1e-2, 1e-4};
        for (int k = 0; k < 2; k++) {
            Rk4Integrator rk4 = new Rk4Integrator();
            State s = rk4.advance(SYS, LagrangeTriangle.state(displacements[k]), h, perPeriod);
            atOnePeriod[k] = maxDeviation(s, LagrangeTriangle.exactState(s.time()));
            s = rk4.advance(SYS, s, h, perPeriod);
            atTwoPeriods[k] = maxDeviation(s, LagrangeTriangle.exactState(s.time()));
        }
        // Measured: deviation after one period 1.47e-1 and 1.51e-3. Their ratio is 97, close to the 100 of the
        // displacements: the early response is linear in the perturbation.
        double ratio = atOnePeriod[0] / atOnePeriod[1];
        assertTrue(ratio > 70 && ratio < 130, "linear response expected, ratio " + ratio);
        // Measured: the 1e-4 run grows from 1.5e-3 to 1.2e-1 in the next period (x76), i.e. exponential growth.
        assertTrue(atTwoPeriods[1] / atOnePeriod[1] > 20,
                "growth factor per period " + atTwoPeriods[1] / atOnePeriod[1]);
    }

    @Test
    void limitationFixedStepRk4LosesEnergyCompletelyAfterTheTriangleBreaksIntoCloseEncounters() {
        // This documents a numerical failure, not physics: the exact flow conserves energy. Measured with
        // displacement 0.01 and h = 1e-3: energy is constant to 9 digits through t = 50 and then changes by about
        // 100 % by t = 99 once two bodies pass close to each other and the fixed step is too coarse.
        double h = 1e-3;
        int perPeriod = (int) Math.round(LagrangeTriangle.period() / h);
        Rk4Integrator rk4 = new Rk4Integrator();
        State s = LagrangeTriangle.state(0.01);
        double e0 = Diagnostics.totalEnergy(SYS, s);
        s = rk4.advance(SYS, s, h, 6 * perPeriod);
        assertTrue(Math.abs((Diagnostics.totalEnergy(SYS, s) - e0) / e0) < 1e-6, "early drift should be tiny");
        s = rk4.advance(SYS, s, h, 6 * perPeriod);
        assertTrue(Math.abs((Diagnostics.totalEnergy(SYS, s) - e0) / e0) > 0.5,
                "energy error after the close encounters should be of order one");
    }

    @Test
    void invalidDisplacementsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> LagrangeTriangle.state(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> LagrangeTriangle.state(-1.0));
        assertThrows(IllegalArgumentException.class, () -> LagrangeTriangle.state(-2.0));
    }
}
