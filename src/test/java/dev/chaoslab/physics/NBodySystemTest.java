package dev.chaoslab.physics;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Tests for the force, potential and kinetic-energy model.
 *
 * <p>Failure interpretation: every test here is a closed-form or exactly derivable check on a single force
 * evaluation, with no time integration involved. A failure therefore indicates a defect in the physics
 * formulas or the test's own arithmetic, never a numerical-method limitation.
 */
class NBodySystemTest {

    private static final double TIGHT = 1e-14; // closed-form cases: only a few rounding errors expected

    // ---------------------------------------------------------------- closed-form accelerations

    @Test
    void twoBodyAccelerationFollowsInverseSquareLaw() {
        NBodySystem sys = NBodySystem.normalized(3.0, 5.0);
        double[] q = {0, 0, 0, 2, 0, 0}; // separation d = 2 along +x
        double[] a = new double[6];
        sys.accelerations(q, a);

        // a_0 = G m_1 / d^2 toward +x ; a_1 = -G m_0 / d^2
        assertEquals(5.0 / 4.0, a[0], TIGHT);
        assertEquals(0.0, a[1], TIGHT);
        assertEquals(0.0, a[2], TIGHT);
        assertEquals(-3.0 / 4.0, a[3], TIGHT);
        assertEquals(0.0, a[4], TIGHT);
        assertEquals(0.0, a[5], TIGHT);
    }

    @Test
    void softenedAccelerationUsesPlummerLaw() {
        // masses 1 and 1, d = 4, eps = 3  =>  r^2 + eps^2 = 25, (..)^(3/2) = 125, a = 4/125
        NBodySystem sys = NBodySystem.of(1.0, 3.0, 1.0, 1.0);
        double[] q = {0, 0, 0, 4, 0, 0};
        double[] a = new double[6];
        sys.accelerations(q, a);
        assertEquals(4.0 / 125.0, a[0], TIGHT);
        assertEquals(-4.0 / 125.0, a[3], TIGHT);
    }

    @Test
    void circularOrbitCentripetalConditionHolds() {
        // Equal masses m = 1, G = 1, separation d = 1. Circular orbit about the centre of mass:
        // relative speed^2 = G (m0 + m1) / d = 2, so each body moves at v = sqrt(1/2) on a circle of radius 1/2.
        // Centripetal acceleration v^2 / r = 0.5 / 0.5 = 1 must equal the gravitational acceleration G m / d^2 = 1.
        NBodySystem sys = NBodySystem.normalized(1.0, 1.0);
        double[] q = {-0.5, 0, 0, 0.5, 0, 0};
        double[] a = new double[6];
        sys.accelerations(q, a);

        double v = Math.sqrt(0.5);
        double radius = 0.5;
        double centripetal = v * v / radius;
        assertEquals(centripetal, a[0], TIGHT);   // body 0 accelerates toward +x (the centre)
        assertEquals(-centripetal, a[3], TIGHT);  // body 1 accelerates toward -x
    }

    // ---------------------------------------------------------------- Newton's third law

    @Test
    void forcesAreEqualAndOppositeSoNetForceVanishes() {
        NBodySystem sys = NBodySystem.of(1.0, 0.0, 1.3, 0.7, 2.9, 0.4);
        double[] q = randomPositions(4, 42L);
        double[] a = new double[12];
        sys.accelerations(q, a);

        double fx = 0, fy = 0, fz = 0;
        double scale = 0;
        for (int i = 0; i < 4; i++) {
            fx += sys.mass(i) * a[3 * i];
            fy += sys.mass(i) * a[3 * i + 1];
            fz += sys.mass(i) * a[3 * i + 2];
            scale += sys.mass(i) * Math.abs(a[3 * i]) + sys.mass(i) * Math.abs(a[3 * i + 1])
                    + sys.mass(i) * Math.abs(a[3 * i + 2]);
        }
        // Sum of m_i a_i must cancel to rounding error relative to the individual force magnitudes.
        double tol = 1e-13 * Math.max(1.0, scale);
        assertEquals(0.0, fx, tol);
        assertEquals(0.0, fy, tol);
        assertEquals(0.0, fz, tol);
    }

    // ---------------------------------------------------------------- force is the gradient of the potential

    @Test
    void exactForceIsNegativeGradientOfPotential() {
        assertForceMatchesPotentialGradient(NBodySystem.of(1.0, 0.0, 1.0, 2.0, 0.5));
    }

    @Test
    void softenedForceIsNegativeGradientOfSoftenedPotential() {
        assertForceMatchesPotentialGradient(NBodySystem.of(1.0, 0.3, 1.0, 2.0, 0.5));
    }

    /** Central finite difference: m_i a_i,k should equal -dU/dq_{i,k}. Error is O(h^2) + roundoff/h, so ~1e-9 here. */
    private static void assertForceMatchesPotentialGradient(NBodySystem sys) {
        double[] q = randomPositions(3, 7L);
        double[] a = new double[9];
        sys.accelerations(q, a);

        final double h = 1e-6;
        for (int k = 0; k < 9; k++) {
            double saved = q[k];
            q[k] = saved + h;
            double up = sys.potentialEnergy(q);
            q[k] = saved - h;
            double um = sys.potentialEnergy(q);
            q[k] = saved;
            double minusGrad = -(up - um) / (2 * h);
            double force = sys.mass(k / 3) * a[k];
            assertEquals(minusGrad, force, 1e-7 * Math.max(1.0, Math.abs(force)),
                    "component " + k + " of m*a disagrees with -dU/dq");
        }
    }

    // ---------------------------------------------------------------- energies

    @Test
    void potentialEnergyMatchesClosedForm() {
        // m0 = 2, m1 = 3, d = 4, G = 1  =>  U = -6/4
        NBodySystem exact = NBodySystem.normalized(2.0, 3.0);
        assertEquals(-1.5, exact.potentialEnergy(new double[] {0, 0, 0, 4, 0, 0}), TIGHT);

        // eps = 3 gives r_soft = 5 (3-4-5 triangle)  =>  U = -6/5
        NBodySystem soft = NBodySystem.of(1.0, 3.0, 2.0, 3.0);
        assertEquals(-1.2, soft.potentialEnergy(new double[] {0, 0, 0, 4, 0, 0}), TIGHT);
    }

    @Test
    void potentialEnergyScalesWithGAndMassesAndInverseLength() {
        // Dimensional-analysis check: U ~ G m^2 / L.
        double[] q = {0, 0, 0, 2, 0, 0};
        double[] q2 = {0, 0, 0, 4, 0, 0}; // lengths doubled
        double base = NBodySystem.of(1.0, 0.0, 1.0, 1.0).potentialEnergy(q);
        assertEquals(base / 2.0, NBodySystem.of(1.0, 0.0, 1.0, 1.0).potentialEnergy(q2), TIGHT);
        assertEquals(base * 4.0, NBodySystem.of(1.0, 0.0, 2.0, 2.0).potentialEnergy(q), TIGHT); // masses doubled
        assertEquals(base * 3.0, NBodySystem.of(3.0, 0.0, 1.0, 1.0).potentialEnergy(q), TIGHT); // G tripled
    }

    @Test
    void kineticEnergyMatchesClosedForm() {
        NBodySystem sys = NBodySystem.normalized(2.0, 4.0);
        double[] v = {3, 0, 0, 0, 1, 0};
        // T = 1/2 (2*9 + 4*1) = 11
        assertEquals(11.0, sys.kineticEnergy(v), TIGHT);
    }

    @Test
    void accelerationScalesWithMassAndInverseSquareOfLength() {
        double[] q = {0, 0, 0, 2, 0, 0};
        double[] q2 = {0, 0, 0, 4, 0, 0};
        double[] a = new double[6];
        double[] b = new double[6];

        NBodySystem.normalized(1.0, 1.0).accelerations(q, a);
        NBodySystem.normalized(2.0, 2.0).accelerations(q, b);
        assertEquals(2.0 * a[0], b[0], TIGHT); // a_i is linear in the *other* body's mass

        NBodySystem.normalized(1.0, 1.0).accelerations(q2, b);
        assertEquals(a[0] / 4.0, b[0], TIGHT); // doubling every length quarters the acceleration
    }

    // ---------------------------------------------------------------- failure handling

    @Test
    void coincidentBodiesRaiseSingularConfigurationWithoutSoftening() {
        NBodySystem sys = NBodySystem.normalized(1.0, 1.0, 1.0);
        double[] q = {0, 0, 0, 1, 0, 0, 1, 0, 0}; // bodies 1 and 2 coincide
        double[] a = new double[9];

        NBodySystem.SingularConfigurationException e =
                assertThrows(NBodySystem.SingularConfigurationException.class, () -> sys.accelerations(q, a));
        assertEquals(1, e.bodyA());
        assertEquals(2, e.bodyB());
        assertThrows(NBodySystem.SingularConfigurationException.class, () -> sys.potentialEnergy(q));
    }

    @Test
    void coincidentBodiesAreFiniteWithSofteningAndExertNoForce() {
        NBodySystem sys = NBodySystem.of(1.0, 0.1, 1.0, 1.0);
        double[] q = {1, 2, 3, 1, 2, 3};
        double[] a = new double[6];
        sys.accelerations(q, a);
        assertArrayEquals(new double[6], a, 0.0); // d = 0, so the softened force is exactly zero
        assertEquals(-1.0 / 0.1, sys.potentialEnergy(q), 1e-12);
    }

    @Test
    void nonFinitePositionIsReportedNotPropagated() {
        NBodySystem sys = NBodySystem.normalized(1.0, 1.0);
        double[] a = new double[6];
        assertThrows(NBodySystem.SingularConfigurationException.class,
                () -> sys.accelerations(new double[] {0, 0, 0, Double.NaN, 0, 0}, a));
        assertThrows(NBodySystem.SingularConfigurationException.class,
                () -> sys.accelerations(new double[] {0, 0, 0, Double.POSITIVE_INFINITY, 0, 0}, a));
    }

    @Test
    void invalidConstructionIsRejectedWithIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () -> NBodySystem.normalized(1.0));          // one body
        assertThrows(IllegalArgumentException.class, () -> NBodySystem.normalized(1.0, 0.0));     // zero mass
        assertThrows(IllegalArgumentException.class, () -> NBodySystem.normalized(1.0, -2.0));    // negative mass
        assertThrows(IllegalArgumentException.class, () -> NBodySystem.normalized(1.0, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> NBodySystem.of(0.0, 0.0, 1.0, 1.0));   // G = 0
        assertThrows(IllegalArgumentException.class, () -> NBodySystem.of(Double.NaN, 0.0, 1.0, 1.0));
        assertThrows(IllegalArgumentException.class, () -> NBodySystem.of(1.0, -0.1, 1.0, 1.0));  // eps < 0
        assertThrows(IllegalArgumentException.class, () -> NBodySystem.of(1.0, Double.NaN, 1.0, 1.0));
    }

    @Test
    void wrongArrayLengthIsRejected() {
        NBodySystem sys = NBodySystem.normalized(1.0, 1.0);
        assertThrows(IllegalArgumentException.class, () -> sys.accelerations(new double[5], new double[6]));
        assertThrows(IllegalArgumentException.class, () -> sys.accelerations(new double[6], new double[7]));
        assertThrows(IllegalArgumentException.class, () -> sys.potentialEnergy(new double[3]));
        assertThrows(IllegalArgumentException.class, () -> sys.kineticEnergy(new double[9]));
    }

    @Test
    void systemIsImmutableAndReproducible() {
        double[] masses = {1.0, 2.0};
        NBodySystem sys = NBodySystem.normalized(masses);
        masses[0] = 99.0;                       // caller mutation must not leak in
        sys.masses()[1] = 99.0;                 // returned copy must not leak back
        assertEquals(1.0, sys.mass(0), 0.0);
        assertEquals(2.0, sys.mass(1), 0.0);

        double[] q = randomPositions(2, 1L);
        double[] a1 = new double[6];
        double[] a2 = new double[6];
        sys.accelerations(q, a1);
        sys.accelerations(q, a2);
        assertArrayEquals(a1, a2, 0.0);         // identical inputs give bit-identical outputs
        assertTrue(sys.isExactNewtonian());
    }

    // ---------------------------------------------------------------- Vec3

    @Test
    void vec3CrossAndDotFollowRightHandRule() {
        Vec3 x = new Vec3(1, 0, 0);
        Vec3 y = new Vec3(0, 1, 0);
        assertEquals(new Vec3(0, 0, 1), x.cross(y));
        assertEquals(new Vec3(0, 0, -1), y.cross(x));
        assertEquals(0.0, x.dot(y), 0.0);
        assertEquals(5.0, new Vec3(3, 4, 0).norm(), TIGHT);
    }

    // ---------------------------------------------------------------- helpers

    /** Deterministic pseudo-random positions in [-1, 1]^3 with a fixed seed, so failures are reproducible. */
    private static double[] randomPositions(int n, long seed) {
        Random rng = new Random(seed);
        double[] q = new double[3 * n];
        for (int i = 0; i < q.length; i++) {
            q[i] = 2.0 * rng.nextDouble() - 1.0;
        }
        return q;
    }
}
