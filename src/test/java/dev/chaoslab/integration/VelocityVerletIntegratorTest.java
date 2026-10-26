package dev.chaoslab.integration;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.chaoslab.physics.Diagnostics;
import dev.chaoslab.physics.NBodySystem;
import dev.chaoslab.physics.State;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link VelocityVerletIntegrator}.
 *
 * <p>Failure interpretation: the order and hand-step tests fail only on an implementation defect (the reference
 * solution is exact). The bounded-energy and reversibility tests encode the defining theoretical properties of a
 * symplectic, time-reversible method; failure indicates an implementation defect (for example a wrongly
 * placed half-kick) or a broken acceleration cache. The cache tests fail only if stale accelerations leak
 * between steps.
 */
class VelocityVerletIntegratorTest {

    private static final double W = Math.sqrt(2.0);

    private static NBodySystem system() {
        return NBodySystem.normalized(1.0, 1.0);
    }

    /** Two unit masses at separation 1; f = 1 is the circular orbit, f &lt; 1 is an eccentric bound orbit. */
    private static State orbit(double f) {
        double v = f * Math.sqrt(0.5);
        return State.of(0.0, new double[] {-0.5, 0, 0, 0.5, 0, 0}, new double[] {0, -v, 0, 0, v, 0});
    }

    private static double positionError(State s) {
        double c = 0.5 * Math.cos(W * s.time());
        double sn = 0.5 * Math.sin(W * s.time());
        double[] exact = {-c, -sn, 0, c, sn, 0};
        double[] q = s.positions();
        double max = 0.0;
        for (int i = 0; i < 6; i++) {
            max = Math.max(max, Math.abs(q[i] - exact[i]));
        }
        return max;
    }

    private static double errorAtUnitTime(int n) {
        return positionError(new VelocityVerletIntegrator().advance(system(), orbit(1.0), 1.0 / n, n));
    }

    // ---------------------------------------------------------------- exactness of one step

    @Test
    void singleStepMatchesHandComputedKickDriftKick() {
        double s = Math.sqrt(0.5);
        double h = 0.1;
        // Kick: body 0 has a = (+1,0,0) at the start, so v_half = (0.05, -s, 0).
        // Drift: q0 = (-0.5 + h*0.05, -h*s, 0) = (-0.495, -0.1 s, 0); by symmetry q1 = -q0.
        double q0x = -0.5 + h * 0.05;
        double q0y = -h * s;
        // Kick: separation vector from body 0 to body 1 is (-2 q0x, -2 q0y); a0 = G m d / |d|^3 with m = 1.
        double dx = -2 * q0x;
        double dy = -2 * q0y;
        double r = Math.sqrt(dx * dx + dy * dy);
        double a0x = dx / (r * r * r);
        double a0y = dy / (r * r * r);

        State next = new VelocityVerletIntegrator().advance(system(), orbit(1.0), h, 1);
        assertEquals(q0x, next.position(0).x(), 1e-15);
        assertEquals(q0y, next.position(0).y(), 1e-15);
        assertEquals(0.05 + 0.05 * a0x, next.velocity(0).x(), 1e-15);
        assertEquals(-s + 0.05 * a0y, next.velocity(0).y(), 1e-15);
    }

    // ---------------------------------------------------------------- convergence

    @Test
    void convergesAtSecondOrder() {
        double p = Math.log(errorAtUnitTime(100) / errorAtUnitTime(200)) / Math.log(2.0);
        assertEquals(2.0, p, 0.1, "observed order of Velocity Verlet was " + p);
    }

    // ---------------------------------------------------------------- the defining property: bounded energy error

    @Test
    void energyErrorStaysBoundedWhileRk4DriftsAtEqualCost() {
        // Eccentric orbit (f = 0.8), 400 time units, 40000 force evaluations for each method:
        // Verlet h = 0.01 (one evaluation per step) and RK4 h = 0.04 (four per step).
        NBodySystem sys = system();
        State s0 = orbit(0.8);
        Diagnostics.Invariants i0 = Diagnostics.measure(sys, s0);

        double[] q = s0.positions();
        double[] v = s0.velocities();
        VelocityVerletIntegrator verlet = new VelocityVerletIntegrator();
        int steps = 40000;
        double h = 0.01;
        double verletFirstHalf = 0.0;
        double verletSecondHalf = 0.0;
        for (int k = 1; k <= steps; k++) {
            verlet.step(sys, q, v, h);
            double d = Math.abs(Diagnostics.drift(sys, s0, i0,
                    Diagnostics.measure(sys, State.of(k * h, q, v))).relativeEnergy());
            if (k <= steps / 2) {
                verletFirstHalf = Math.max(verletFirstHalf, d);
            } else {
                verletSecondHalf = Math.max(verletSecondHalf, d);
            }
        }

        double[] qr = s0.positions();
        double[] vr = s0.velocities();
        Rk4Integrator rk4 = new Rk4Integrator();
        int rkSteps = 10000;
        double rkh = 0.04;
        double rkFirstHalf = 0.0;
        double rkSecondHalf = 0.0;
        for (int k = 1; k <= rkSteps; k++) {
            rk4.step(sys, qr, vr, rkh);
            double d = Math.abs(Diagnostics.drift(sys, s0, i0,
                    Diagnostics.measure(sys, State.of(k * rkh, qr, vr))).relativeEnergy());
            if (k <= rkSteps / 2) {
                rkFirstHalf = Math.max(rkFirstHalf, d);
            } else {
                rkSecondHalf = Math.max(rkSecondHalf, d);
            }
        }

        // Verlet: the envelope of the energy error does not grow between the first and second half of the run.
        assertTrue(verletSecondHalf <= 1.05 * verletFirstHalf,
                "Verlet error envelope grew: " + verletFirstHalf + " then " + verletSecondHalf);
        assertTrue(verletSecondHalf < 1e-3, "Verlet energy error was " + verletSecondHalf);
        // RK4: the error envelope keeps growing (secular drift) ...
        assertTrue(rkSecondHalf > 1.5 * rkFirstHalf,
                "RK4 error envelope did not grow: " + rkFirstHalf + " then " + rkSecondHalf);
        // ... and ends up larger than the Verlet bound, at the same computational cost.
        assertTrue(rkSecondHalf > verletSecondHalf,
                "expected RK4 drift " + rkSecondHalf + " to exceed bounded Verlet error " + verletSecondHalf);
    }

    @Test
    void angularMomentumIsConservedToRoundoffForPairwiseCentralForces() {
        // Drift preserves q x v exactly and the kicks add central pair forces that cancel in sum m q x a,
        // so L is conserved to rounding, independent of h.
        NBodySystem sys = system();
        State s0 = orbit(0.8);
        Diagnostics.Invariants i0 = Diagnostics.measure(sys, s0);
        State end = new VelocityVerletIntegrator().advance(sys, s0, 0.05, 5000);
        Diagnostics.Drift d = Diagnostics.drift(sys, s0, i0, Diagnostics.measure(sys, end));
        assertTrue(d.relativeAngularMomentum() < 1e-12, "L drift was " + d.relativeAngularMomentum());
        assertTrue(d.relativeMomentum() < 1e-13, "P drift was " + d.relativeMomentum());
    }

    // ---------------------------------------------------------------- time reversibility

    @Test
    void runningBackwardRetracesTheTrajectoryToRoundoffUnlikeRk4() {
        NBodySystem sys = system();
        State s0 = orbit(0.8);

        double verletRoundTrip = roundTripError(new VelocityVerletIntegrator(), sys, s0);
        double rk4RoundTrip = roundTripError(new Rk4Integrator(), sys, s0);

        assertTrue(verletRoundTrip < 1e-12, "Verlet round-trip error was " + verletRoundTrip);
        assertTrue(rk4RoundTrip > 1e-9, "RK4 should not be exactly reversible, round-trip error was " + rk4RoundTrip);
        assertTrue(rk4RoundTrip > 1000 * verletRoundTrip);
    }

    private static double roundTripError(Integrator method, NBodySystem sys, State s0) {
        double[] q = s0.positions();
        double[] v = s0.velocities();
        for (int k = 0; k < 1000; k++) {
            method.step(sys, q, v, 0.01);
        }
        for (int k = 0; k < 1000; k++) {
            method.step(sys, q, v, -0.01);
        }
        double max = 0.0;
        double[] q0 = s0.positions();
        for (int i = 0; i < q.length; i++) {
            max = Math.max(max, Math.abs(q[i] - q0[i]));
        }
        return max;
    }

    // ---------------------------------------------------------------- acceleration cache must never go stale

    @Test
    void externallyModifiedStateIsNotAffectedByTheAccelerationCache() {
        NBodySystem sys = system();
        State s0 = orbit(0.9);
        double[] q = s0.positions();
        double[] v = s0.velocities();
        VelocityVerletIntegrator reused = new VelocityVerletIntegrator();
        reused.step(sys, q, v, 0.01);

        q[0] += 0.05; // the caller moves a body between steps
        double[] qFresh = q.clone();
        double[] vFresh = v.clone();
        reused.step(sys, q, v, 0.01);
        new VelocityVerletIntegrator().step(sys, qFresh, vFresh, 0.01);

        assertArrayEquals(qFresh, q, 0.0);
        assertArrayEquals(vFresh, v, 0.0);
    }

    @Test
    void switchingSystemsBetweenStepsDoesNotReuseAccelerations() {
        NBodySystem light = NBodySystem.normalized(1.0, 1.0);
        NBodySystem heavy = NBodySystem.normalized(3.0, 3.0);
        State s0 = orbit(1.0);
        double[] q = s0.positions();
        double[] v = s0.velocities();
        VelocityVerletIntegrator reused = new VelocityVerletIntegrator();
        reused.step(light, q, v, 0.01);

        double[] qFresh = q.clone();
        double[] vFresh = v.clone();
        reused.step(heavy, q, v, 0.01); // identical positions, different masses
        new VelocityVerletIntegrator().step(heavy, qFresh, vFresh, 0.01);

        assertArrayEquals(qFresh, q, 0.0);
        assertArrayEquals(vFresh, v, 0.0);
    }

    @Test
    void integratorDescribesItself() {
        VelocityVerletIntegrator m = new VelocityVerletIntegrator();
        assertEquals(2, m.order());
        assertTrue(m.isSymplectic());
        assertEquals("Velocity Verlet", m.name());
    }
}
