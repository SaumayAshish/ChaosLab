package dev.chaoslab.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link State} and {@link Diagnostics}.
 *
 * <p>Failure interpretation: all expected values are derived by hand from the definitions of centre of mass,
 * momentum, angular momentum and energy, with no time integration. A failure means a defect in the diagnostic
 * formulas, in State's validation, or in the test's own arithmetic.
 */
class DiagnosticsTest {

    private static final double TIGHT = 1e-14;

    /** Equal masses 1, G = 1, separation 1, circular orbit about the origin, counter-clockwise seen from +z. */
    private static NBodySystem circularPair() {
        return NBodySystem.normalized(1.0, 1.0);
    }

    private static State circularPairState() {
        double v = Math.sqrt(0.5);
        return State.of(0.0,
                new double[] {-0.5, 0, 0, 0.5, 0, 0},
                new double[] {0, -v, 0, 0, v, 0});
    }

    // ---------------------------------------------------------------- State

    @Test
    void stateCopiesInputsAndOutputs() {
        double[] q = {0, 0, 0, 1, 0, 0};
        double[] v = {0, 0, 0, 0, 1, 0};
        State s = State.of(2.5, q, v);
        q[3] = 99;
        v[4] = 99;
        assertEquals(1.0, s.position(1).x(), 0.0);
        assertEquals(1.0, s.velocity(1).y(), 0.0);
        s.positions()[3] = 77;
        assertEquals(1.0, s.position(1).x(), 0.0);
        assertEquals(2, s.bodyCount());
        assertEquals(2.5, s.time(), 0.0);
    }

    @Test
    void stateRejectsInvalidInput() {
        double[] ok = new double[6];
        assertThrows(IllegalArgumentException.class, () -> State.of(0, null, ok));
        assertThrows(IllegalArgumentException.class, () -> State.of(0, ok, new double[3]));
        assertThrows(IllegalArgumentException.class, () -> State.of(0, new double[4], new double[4]));
        assertThrows(IllegalArgumentException.class, () -> State.of(0, new double[0], new double[0]));
        assertThrows(IllegalArgumentException.class, () -> State.of(Double.NaN, ok, ok));
        assertThrows(IllegalArgumentException.class,
                () -> State.of(0, new double[] {0, 0, 0, Double.NaN, 0, 0}, ok));
        assertThrows(IllegalArgumentException.class,
                () -> State.of(0, ok, new double[] {0, 0, Double.POSITIVE_INFINITY, 0, 0, 0}));
    }

    @Test
    void stateEqualityIsValueBased() {
        assertEquals(circularPairState(), circularPairState());
        assertEquals(circularPairState().hashCode(), circularPairState().hashCode());
    }

    // ---------------------------------------------------------------- centre of mass

    @Test
    void centreOfMassIsMassWeighted() {
        // masses 1 and 3 at x = 0 and x = 4  =>  R_x = (1*0 + 3*4) / 4 = 3
        NBodySystem sys = NBodySystem.normalized(1.0, 3.0);
        State s = State.of(0, new double[] {0, 0, 0, 4, 8, -4}, new double[6]);
        Vec3 r = Diagnostics.centreOfMass(sys, s);
        assertEquals(3.0, r.x(), TIGHT);
        assertEquals(6.0, r.y(), TIGHT);
        assertEquals(-3.0, r.z(), TIGHT);
    }

    @Test
    void centreOfMassOfSymmetricPairIsOrigin() {
        Vec3 r = Diagnostics.centreOfMass(circularPair(), circularPairState());
        assertEquals(0.0, r.norm(), TIGHT);
    }

    // ---------------------------------------------------------------- momentum

    @Test
    void linearMomentumIsSumOfMassTimesVelocity() {
        NBodySystem sys = NBodySystem.normalized(2.0, 3.0);
        State s = State.of(0, new double[] {0, 0, 0, 1, 0, 0}, new double[] {1, 0, 0, 0, -2, 4});
        Vec3 p = Diagnostics.linearMomentum(sys, s);
        assertEquals(2.0, p.x(), TIGHT);
        assertEquals(-6.0, p.y(), TIGHT);
        assertEquals(12.0, p.z(), TIGHT);
    }

    @Test
    void circularPairHasZeroMomentum() {
        assertEquals(0.0, Diagnostics.linearMomentum(circularPair(), circularPairState()).norm(), TIGHT);
    }

    // ---------------------------------------------------------------- angular momentum

    @Test
    void angularMomentumOfCircularPairPointsAlongPlusZ() {
        // each body contributes m * r * v = 1 * 0.5 * sqrt(0.5); total Lz = sqrt(0.5)
        Vec3 l = Diagnostics.angularMomentum(circularPair(), circularPairState());
        assertEquals(0.0, l.x(), TIGHT);
        assertEquals(0.0, l.y(), TIGHT);
        assertEquals(Math.sqrt(0.5), l.z(), TIGHT);
    }

    @Test
    void angularMomentumFlipsSignWhenOrbitReverses() {
        double v = Math.sqrt(0.5);
        State reversed = State.of(0, new double[] {-0.5, 0, 0, 0.5, 0, 0}, new double[] {0, v, 0, 0, -v, 0});
        assertEquals(-Math.sqrt(0.5), Diagnostics.angularMomentum(circularPair(), reversed).z(), TIGHT);
    }

    @Test
    void angularMomentumGeneralCrossProductCase() {
        // m = 2 at (1,2,3) with v = (4,5,6): L = 2 * (2*6-3*5, 3*4-1*6, 1*5-2*4) = 2 * (-3, 6, -3)
        NBodySystem sys = NBodySystem.normalized(2.0, 1.0);
        State s = State.of(0, new double[] {1, 2, 3, 100, 0, 0}, new double[] {4, 5, 6, 0, 0, 0});
        Vec3 l = Diagnostics.angularMomentum(sys, s);
        assertEquals(-6.0, l.x(), TIGHT);
        assertEquals(12.0, l.y(), TIGHT);
        assertEquals(-6.0, l.z(), TIGHT);
    }

    // ---------------------------------------------------------------- energy

    @Test
    void circularPairEnergyMatchesKeplerResult() {
        // Circular Kepler orbit: E = -G m0 m1 / (2 a) with a the separation (1)  =>  E = -1/2.
        // Also T = 1/2 and U = -1 (virial theorem: 2T + U = 0 for a 1/r potential).
        Diagnostics.Invariants inv = Diagnostics.measure(circularPair(), circularPairState());
        assertEquals(0.5, inv.kinetic(), TIGHT);
        assertEquals(-1.0, inv.potential(), TIGHT);
        assertEquals(-0.5, inv.totalEnergy(), TIGHT);
        assertEquals(0.0, 2.0 * inv.kinetic() + inv.potential(), TIGHT);
        assertEquals(inv.totalEnergy(), Diagnostics.totalEnergy(circularPair(), circularPairState()), 0.0);
    }

    // ---------------------------------------------------------------- symmetry properties

    @Test
    void translationLeavesEnergyAndMomentumUnchangedButShiftsAngularMomentum() {
        NBodySystem sys = circularPair();
        State s = circularPairState();
        State moved = State.of(0, new double[] {-0.5 + 10, 0 + 3, 0, 0.5 + 10, 0 + 3, 0}, s.velocities());

        Diagnostics.Invariants a = Diagnostics.measure(sys, s);
        Diagnostics.Invariants b = Diagnostics.measure(sys, moved);
        assertEquals(a.totalEnergy(), b.totalEnergy(), 1e-13);
        assertEquals(0.0, a.momentum().minus(b.momentum()).norm(), TIGHT);
        // L about the origin changes by R x P, which is 0 here because P = 0, so it is unchanged too.
        assertEquals(0.0, a.angularMomentum().minus(b.angularMomentum()).norm(), 1e-13);
        assertEquals(10.0, b.centreOfMass().x(), 1e-13);
        assertEquals(3.0, b.centreOfMass().y(), 1e-13);
    }

    @Test
    void galileanBoostShiftsCentreOfMassVelocityAndMomentumByTotalMassTimesBoost() {
        NBodySystem sys = circularPair();
        State s = circularPairState();
        double[] v = s.velocities();
        for (int i = 0; i < 2; i++) {
            v[3 * i] += 0.7; // boost along +x
        }
        State boosted = State.of(0, s.positions(), v);

        Diagnostics.Invariants b = Diagnostics.measure(sys, boosted);
        assertEquals(0.7, b.centreOfMassVelocity().x(), TIGHT);
        assertEquals(sys.totalMass() * 0.7, b.momentum().x(), TIGHT);
    }

    // ---------------------------------------------------------------- drift

    @Test
    void identicalMeasurementsHaveExactlyZeroDrift() {
        NBodySystem sys = circularPair();
        State s = circularPairState();
        Diagnostics.Invariants inv = Diagnostics.measure(sys, s);
        Diagnostics.Drift d = Diagnostics.drift(sys, s, inv, inv);
        assertEquals(0.0, d.relativeEnergy(), 0.0);
        assertEquals(0.0, d.relativeMomentum(), 0.0);
        assertEquals(0.0, d.relativeAngularMomentum(), 0.0);
    }

    @Test
    void relativeEnergyDriftIsSignedAndNormalizedByInitialEnergyMagnitude() {
        NBodySystem sys = circularPair();
        State s0 = circularPairState();
        Diagnostics.Invariants i0 = Diagnostics.measure(sys, s0);

        // Speed every body up by 10%: T grows by 1.21, so E = 0.605 - 1 = -0.395, drift = (-0.395 + 0.5) / 0.5 = 0.21
        double[] v = s0.velocities();
        for (int k = 0; k < v.length; k++) {
            v[k] *= 1.1;
        }
        State s1 = State.of(1.0, s0.positions(), v);
        Diagnostics.Drift d = Diagnostics.drift(sys, s0, i0, Diagnostics.measure(sys, s1));
        assertEquals(0.21, d.relativeEnergy(), 1e-14);
        assertEquals(1.0, d.time(), 0.0);
        assertTrue(d.relativeEnergy() > 0.0, "energy gain must be reported as positive drift");
    }

    @Test
    void momentumDriftUsesSpeedScaleWhenInitialMomentumIsZero() {
        NBodySystem sys = circularPair();
        State s0 = circularPairState();
        Diagnostics.Invariants i0 = Diagnostics.measure(sys, s0);

        // Add 0.1 to the x-velocity of body 0 only: dP = 0.1. Pscale = sum m|v| = 2 sqrt(0.5).
        double[] v = s0.velocities();
        v[0] += 0.1;
        State s1 = State.of(1.0, s0.positions(), v);
        Diagnostics.Drift d = Diagnostics.drift(sys, s0, i0, Diagnostics.measure(sys, s1));
        assertEquals(0.1 / (2.0 * Math.sqrt(0.5)), d.relativeMomentum(), 1e-14);
    }

    @Test
    void angularMomentumDriftIsRelativeToInitialMagnitude() {
        NBodySystem sys = circularPair();
        State s0 = circularPairState();
        Diagnostics.Invariants i0 = Diagnostics.measure(sys, s0);

        double[] v = s0.velocities();
        for (int k = 0; k < v.length; k++) {
            v[k] *= 1.05; // L scales linearly with speed for fixed positions
        }
        State s1 = State.of(1.0, s0.positions(), v);
        Diagnostics.Drift d = Diagnostics.drift(sys, s0, i0, Diagnostics.measure(sys, s1));
        assertEquals(0.05, d.relativeAngularMomentum(), 1e-14);
    }

    // ---------------------------------------------------------------- failure handling

    @Test
    void mismatchedBodyCountIsRejected() {
        NBodySystem threeBody = NBodySystem.normalized(1.0, 1.0, 1.0);
        assertThrows(IllegalArgumentException.class, () -> Diagnostics.measure(threeBody, circularPairState()));
        assertThrows(IllegalArgumentException.class, () -> Diagnostics.centreOfMass(threeBody, circularPairState()));
    }

    @Test
    void coincidentBodiesAreReportedByMeasure() {
        NBodySystem sys = circularPair();
        State s = State.of(0, new double[] {1, 1, 1, 1, 1, 1}, new double[6]);
        assertThrows(NBodySystem.SingularConfigurationException.class, () -> Diagnostics.measure(sys, s));
    }

    @Test
    void measurementIsReproducible() {
        Diagnostics.Invariants a = Diagnostics.measure(circularPair(), circularPairState());
        Diagnostics.Invariants b = Diagnostics.measure(circularPair(), circularPairState());
        assertEquals(a, b);
    }
}
