package dev.chaoslab.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.chaoslab.physics.Diagnostics;
import dev.chaoslab.physics.NBodySystem;
import dev.chaoslab.physics.State;
import org.junit.jupiter.api.Test;

/**
 * Tests for the fixed-step integrators.
 *
 * <p>Reference problem: two unit masses, G = 1, separation 1, on a circular orbit about their centre of mass.
 * The exact solution is known in closed form: angular frequency w = sqrt(G (m0 + m1) / d^3) = sqrt(2) and
 * body k at +-(1/2)(cos wt, sin wt, 0).
 *
 * <p>Failure interpretation: a wrong observed convergence order indicates an implementation defect in the
 * integrator (a wrong coefficient or stage), because the reference solution is exact. A failure of the
 * qualitative energy tests would indicate either such a defect or a changed expectation about the method.
 */
class IntegratorTest {

    private static final double W = Math.sqrt(2.0);

    private static NBodySystem system() {
        return NBodySystem.normalized(1.0, 1.0);
    }

    private static State initial() {
        double v = Math.sqrt(0.5);
        return State.of(0.0, new double[] {-0.5, 0, 0, 0.5, 0, 0}, new double[] {0, -v, 0, 0, v, 0});
    }

    /** Maximum absolute position-component error against the exact orbit at the state's own time. */
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

    /** Integrates to t = 1 with step 1/n and returns the position error. */
    private static double errorAtUnitTime(Integrator method, int n) {
        State end = method.advance(system(), initial(), 1.0 / n, n);
        return positionError(end);
    }

    /** Observed order from successive halvings: log2(e(h) / e(h/2)). */
    private static double observedOrder(Integrator method, int n) {
        return Math.log(errorAtUnitTime(method, n) / errorAtUnitTime(method, 2 * n)) / Math.log(2.0);
    }

    // ---------------------------------------------------------------- hand-computed single step

    @Test
    void eulerSingleStepMatchesHandComputation() {
        // Start: q0 = (-0.5,0,0), v0 = (0,-s,0) with s = sqrt(1/2); body 0 acceleration is (+1,0,0).
        // After h = 0.1:  q = (-0.5, -0.1 s, 0), v = (0.1, -s, 0).
        double s = Math.sqrt(0.5);
        State next = new EulerIntegrator().advance(system(), initial(), 0.1, 1);
        assertEquals(-0.5, next.position(0).x(), 1e-15);
        assertEquals(-0.1 * s, next.position(0).y(), 1e-15);
        assertEquals(0.1, next.velocity(0).x(), 1e-15);
        assertEquals(-s, next.velocity(0).y(), 1e-15);
        assertEquals(0.1, next.time(), 0.0);
    }

    // ---------------------------------------------------------------- convergence order

    @Test
    void eulerConvergesAtFirstOrder() {
        double p = observedOrder(new EulerIntegrator(), 200);
        assertEquals(1.0, p, 0.1, "observed order of Euler was " + p);
    }

    @Test
    void rk4ConvergesAtFourthOrder() {
        double p = observedOrder(new Rk4Integrator(), 20);
        assertEquals(4.0, p, 0.2, "observed order of RK4 was " + p);
    }

    @Test
    void rk4IsFarMoreAccurateThanEulerAtEqualCost() {
        // Euler at h = 1/400 uses 400 force evaluations; RK4 at h = 1/100 uses 400.
        double euler = errorAtUnitTime(new EulerIntegrator(), 400);
        double rk4 = errorAtUnitTime(new Rk4Integrator(), 100);
        assertTrue(rk4 < euler * 1e-4, "RK4 error " + rk4 + " vs Euler error " + euler);
    }

    // ---------------------------------------------------------------- energy behaviour

    @Test
    void eulerAddsEnergyAndTheDriftGrowsWithTime() {
        NBodySystem sys = system();
        State s0 = initial();
        Diagnostics.Invariants i0 = Diagnostics.measure(sys, s0);
        Integrator euler = new EulerIntegrator();

        double driftT1 = Diagnostics.drift(sys, s0, i0,
                Diagnostics.measure(sys, euler.advance(sys, s0, 0.01, 100))).relativeEnergy();
        double driftT2 = Diagnostics.drift(sys, s0, i0,
                Diagnostics.measure(sys, euler.advance(sys, s0, 0.01, 200))).relativeEnergy();

        assertTrue(driftT1 > 0.0, "Euler should gain energy on a circular orbit, drift was " + driftT1);
        assertTrue(driftT2 > driftT1, "Euler energy drift should keep growing: " + driftT1 + " then " + driftT2);
    }

    @Test
    void rk4ConservesEnergyToHighAccuracyOverShortRuns() {
        NBodySystem sys = system();
        State s0 = initial();
        Diagnostics.Invariants i0 = Diagnostics.measure(sys, s0);
        State end = new Rk4Integrator().advance(sys, s0, 0.01, 200); // two time units
        double drift = Diagnostics.drift(sys, s0, i0, Diagnostics.measure(sys, end)).relativeEnergy();
        assertTrue(Math.abs(drift) < 1e-8, "RK4 energy drift was " + drift);
    }

    @Test
    void bothMethodsConserveMomentumToRoundoff() {
        // Pairwise forces cancel exactly, so total momentum is preserved up to rounding by both methods.
        NBodySystem sys = system();
        State s0 = initial();
        Diagnostics.Invariants i0 = Diagnostics.measure(sys, s0);
        for (Integrator method : new Integrator[] {new EulerIntegrator(), new Rk4Integrator()}) {
            State end = method.advance(sys, s0, 0.01, 500);
            Diagnostics.Drift d = Diagnostics.drift(sys, s0, i0, Diagnostics.measure(sys, end));
            assertTrue(d.relativeMomentum() < 1e-13, method.name() + " momentum drift " + d.relativeMomentum());
        }
    }

    // ---------------------------------------------------------------- contract and failure handling

    @Test
    void advanceDoesNotMutateItsInputAndIsReproducible() {
        State s0 = initial();
        State copy = initial();
        Integrator rk4 = new Rk4Integrator();
        State a = rk4.advance(system(), s0, 0.01, 50);
        State b = new Rk4Integrator().advance(system(), s0, 0.01, 50);
        assertEquals(copy, s0);
        assertEquals(a, b); // bit-identical: no hidden randomness, no state leaking between instances
        assertEquals(0.5, a.time(), 1e-15);
    }

    @Test
    void zeroStepsReturnsTheSameState() {
        assertEquals(initial(), new Rk4Integrator().advance(system(), initial(), 0.01, 0));
    }

    @Test
    void invalidStepSizesAndCountsAreRejected() {
        Integrator rk4 = new Rk4Integrator();
        assertThrows(IllegalArgumentException.class, () -> rk4.advance(system(), initial(), 0.0, 1));
        assertThrows(IllegalArgumentException.class, () -> rk4.advance(system(), initial(), Double.NaN, 1));
        assertThrows(IllegalArgumentException.class,
                () -> rk4.advance(system(), initial(), Double.POSITIVE_INFINITY, 1));
        assertThrows(IllegalArgumentException.class, () -> rk4.advance(system(), initial(), 0.01, -1));
        assertThrows(IllegalArgumentException.class,
                () -> rk4.advance(NBodySystem.normalized(1.0, 1.0, 1.0), initial(), 0.01, 1));
    }

    @Test
    void absurdlyLargeStepIsReportedAsAFailureNotAsGarbage() {
        // A step of 1e200 sends positions to overflow. The result must be an exception, never a silent NaN.
        Integrator euler = new EulerIntegrator();
        assertThrows(ArithmeticException.class, () -> euler.advance(system(), initial(), 1e200, 3));
    }

    @Test
    void integratorsDescribeThemselves() {
        assertEquals(1, new EulerIntegrator().order());
        assertEquals(4, new Rk4Integrator().order());
        assertTrue(!new EulerIntegrator().isSymplectic() && !new Rk4Integrator().isSymplectic());
    }
}
