package dev.chaoslab.experiments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.chaoslab.analysis.ReferenceTrack;
import dev.chaoslab.analysis.TwinRun;
import dev.chaoslab.integration.DormandPrince45Integrator;
import dev.chaoslab.integration.DormandPrince45Integrator.Tolerances;
import dev.chaoslab.integration.Integrator;
import dev.chaoslab.integration.Rk4Integrator;
import dev.chaoslab.integration.VelocityVerletIntegrator;
import dev.chaoslab.physics.Diagnostics;
import dev.chaoslab.physics.NBodySystem;
import dev.chaoslab.physics.State;
import dev.chaoslab.physics.Vec3;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link BinaryFlyby}. Every figure below was measured against a 1e-13 reference before the bound was set;
 * the bounds leave room for the last digit but would fail on a different encounter.
 */
class BinaryFlybyTest {

    private static final NBodySystem SYSTEM = BinaryFlyby.system();

    private static double distance(double[] p, int i, int j) {
        return Math.sqrt(Math.pow(p[3 * i] - p[3 * j], 2) + Math.pow(p[3 * i + 1] - p[3 * j + 1], 2)
                + Math.pow(p[3 * i + 2] - p[3 * j + 2], 2));
    }

    private static State reference(State start, double time) {
        return new DormandPrince45Integrator().integrate(SYSTEM, start, time, BinaryFlyby.FIRST_STEP,
                new Tolerances(1e-13, 1e-13), 10_000_000).finalState();
    }

    /** Orbital elements {energy, semi-major axis, eccentricity} of bodies 0 and 1 (G = 1, masses 1 and 1). */
    private static double[] binaryElements(State s) {
        double[] q = s.positions();
        double[] w = s.velocities();
        double rx = q[3] - q[0];
        double ry = q[4] - q[1];
        double rz = q[5] - q[2];
        double vx = w[3] - w[0];
        double vy = w[4] - w[1];
        double vz = w[5] - w[2];
        double r = Math.sqrt(rx * rx + ry * ry + rz * rz);
        double specificEnergy = 0.5 * (vx * vx + vy * vy + vz * vz) - 2.0 / r; // G (m1 + m2) = 2
        double hx = ry * vz - rz * vy;
        double hy = rz * vx - rx * vz;
        double hz = rx * vy - ry * vx;
        double h2 = hx * hx + hy * hy + hz * hz;
        double energy = 0.5 * specificEnergy; // reduced mass 1/2 for two unit masses
        return new double[] {energy, -2.0 / (2.0 * specificEnergy), Math.sqrt(1.0 + 2.0 * specificEnergy * h2 / 4.0)};
    }

    @Test
    void theStartIsInTheCentreOfMassFrame() {
        State s = BinaryFlyby.state();
        Vec3 com = Diagnostics.centreOfMass(SYSTEM, s);
        Vec3 p = Diagnostics.linearMomentum(SYSTEM, s);
        assertEquals(0.0, com.norm(), 1e-14);
        assertEquals(0.0, p.norm(), 1e-14);
    }

    @Test
    void theBinaryStartsOnACircularOrbitOfEnergyMinusHalf() {
        double[] e = binaryElements(BinaryFlyby.state());
        assertEquals(-0.5, e[0], 1e-14);
        assertEquals(1.0, e[1], 1e-14);
        assertEquals(0.0, e[2], 1e-7);
    }

    @Test
    void theIntruderStartsAboveTheLocalEscapeSpeed() {
        State s = BinaryFlyby.state();
        double[] q = s.positions();
        double bx = 0.5 * (q[0] + q[3]);
        double by = 0.5 * (q[1] + q[4]);
        double r = Math.hypot(q[6] - bx, q[7] - by);
        double escape = Math.sqrt(2.0 * (2.0 * BinaryFlyby.BINARY_MASS + BinaryFlyby.INTRUDER_MASS) / r);
        assertTrue(BinaryFlyby.SPEED > escape, "speed " + BinaryFlyby.SPEED + " vs escape " + escape);
        assertTrue(escape > 0.64 && escape < 0.65, "escape speed " + escape);
    }

    @Test
    void theTotalEnergyIsTheMeasuredValueComputedIndependently() {
        State s = BinaryFlyby.state();
        double[] q = s.positions();
        double[] v = s.velocities();
        double[] m = {1.0, 1.0, 0.5};
        double energy = 0.0;
        for (int i = 0; i < 3; i++) {
            energy += 0.5 * m[i] * (v[3 * i] * v[3 * i] + v[3 * i + 1] * v[3 * i + 1] + v[3 * i + 2] * v[3 * i + 2]);
            for (int j = i + 1; j < 3; j++) {
                energy -= m[i] * m[j] / distance(q, i, j);
            }
        }
        assertEquals(-0.4848281349, energy, 1e-9);
        assertEquals(energy, Diagnostics.totalEnergy(SYSTEM, s), 1e-13);
    }

    @Test
    void theIntruderPassesWithinAboutOneSixtiethOfTheBinaryOfBodyTwo() {
        ReferenceTrack track = new ReferenceTrack(SYSTEM, BinaryFlyby.state(), BinaryFlyby.FIRST_STEP,
                ReferenceTrack.DEFAULT_TOLERANCES);
        double lo = 12.05;
        double hi = 12.17;
        for (int k = 0; k < 80; k++) { // ternary search for the minimum of the distance to body 2
            double a = lo + (hi - lo) / 3.0;
            double b = hi - (hi - lo) / 3.0;
            if (distance(track.positionsAt(a), 1, 2) < distance(track.positionsAt(b), 1, 2)) {
                hi = b;
            } else {
                lo = a;
            }
        }
        double t = 0.5 * (lo + hi);
        double[] p = track.positionsAt(t);
        assertEquals(12.109, t, 0.005);
        assertEquals(0.0161, distance(p, 1, 2), 5e-4);
        assertTrue(distance(p, 0, 2) > 0.5, "the other binary member is far from the intruder at that moment");
    }

    @Test
    void theBinarySurvivesButIsLeftTighterAndAlmostRadial() {
        State s = reference(BinaryFlyby.state(), 80.0);
        double[] e = binaryElements(s);
        assertEquals(-0.676, e[0], 3e-3);
        assertEquals(0.7397, e[1], 2e-3);
        assertEquals(0.9891, e[2], 1e-3);
        double periapsis = e[1] * (1.0 - e[2]);
        assertEquals(0.0081, periapsis, 5e-4);
    }

    @Test
    void theIntruderIsDeflectedAboutOneHundredThirtyFiveDegreesAndEscapes() {
        State s = reference(BinaryFlyby.state(), 80.0);
        double[] q = s.positions();
        double[] w = s.velocities();
        double bx = 0.5 * (q[0] + q[3]);
        double by = 0.5 * (q[1] + q[4]);
        assertTrue(Math.hypot(q[6] - bx, q[7] - by) > 70.0);
        assertEquals(135.3, Math.toDegrees(Math.atan2(w[7], w[6])), 0.5);
        assertEquals(0.809, Math.hypot(w[6], w[7]), 3e-3);
        double bvx = 0.5 * (w[0] + w[3]);
        double bvy = 0.5 * (w[1] + w[4]);
        double relativeSpeed2 = Math.pow(w[6] - bvx, 2) + Math.pow(w[7] - bvy, 2);
        double r = Math.hypot(q[6] - bx, q[7] - by);
        double relativeEnergy = 0.5 * 0.4 * relativeSpeed2 - 0.5 * 2.0 / r; // reduced mass 0.4, G m M = 1
        assertEquals(0.191, relativeEnergy, 3e-3);
    }

    @Test
    void theOutcomeIsNotKnifeEdge() {
        State a = reference(BinaryFlyby.state(1.5), 80.0);
        State b = reference(BinaryFlyby.state(1.5 + 1e-6), 80.0);
        assertEquals(binaryElements(a)[1], binaryElements(b)[1], 1e-4);
        assertEquals(binaryElements(a)[2], binaryElements(b)[2], 1e-4);
    }

    @Test
    void theTwinNudgeGrowsRoughlyLinearlyAndChangesTheOutcomeByTheMeasuredAmount() {
        State start = BinaryFlyby.state();
        TwinRun twin = new TwinRun(SYSTEM, start);
        double s20 = twin.at(20.0).relativeSeparation();
        double s25 = twin.at(25.0).relativeSeparation();
        double s30 = twin.at(30.0).relativeSeparation();
        double s35 = twin.at(34.5).relativeSeparation();
        assertEquals(3.94e-3, s35, 0.3e-3);
        // measured increments per 5 time units are 0.82e-3 and 0.80e-3: linear growth, not exponential
        assertTrue(s25 - s20 > 0.6e-3 && s25 - s20 < 1.0e-3, "increment 20 to 25: " + (s25 - s20));
        assertTrue(s30 - s25 > 0.6e-3 && s30 - s25 < 1.0e-3, "increment 25 to 30: " + (s30 - s25));

        State a = reference(start, 80.0);
        State b = reference(TwinRun.nudged(start), 80.0);
        assertEquals(3.2e-4, Math.abs(binaryElements(a)[0] - binaryElements(b)[0]), 0.5e-4);
        double angleA = Math.toDegrees(Math.atan2(a.velocities()[7], a.velocities()[6]));
        double angleB = Math.toDegrees(Math.atan2(b.velocities()[7], b.velocities()[6]));
        assertEquals(0.126, Math.abs(angleA - angleB), 0.02);
    }

    @Test
    void fixedStepsAgreeBeforeTheEncounterAndFailAfterIt() {
        State start = BinaryFlyby.state();
        ReferenceTrack track = new ReferenceTrack(SYSTEM, start, BinaryFlyby.FIRST_STEP,
                ReferenceTrack.DEFAULT_TOLERANCES);
        double[] before = track.positionsAt(10.0);
        double[] after = track.positionsAt(13.0);

        Integrator verlet = new VelocityVerletIntegrator();
        State v10 = verlet.advance(SYSTEM, start, 0.005, 2000);
        assertTrue(ReferenceTrack.maxPositionGap(v10.positions(), before) < 3e-4, "Verlet before the encounter");
        State v13 = verlet.advance(SYSTEM, v10, 0.005, 600);
        assertTrue(ReferenceTrack.maxPositionGap(v13.positions(), after) > 1.0, "Verlet after the encounter");

        Integrator rk4 = new Rk4Integrator();
        State r10 = rk4.advance(SYSTEM, start, 0.005, 2000);
        assertTrue(ReferenceTrack.maxPositionGap(r10.positions(), before) < 1e-8, "RK4 before the encounter");
        State r13 = rk4.advance(SYSTEM, r10, 0.005, 600);
        assertTrue(ReferenceTrack.maxPositionGap(r13.positions(), after) > 1.0, "RK4 after the encounter");
    }

    @Test
    void aMuchSmallerFixedStepSurvivesTheFirstPassButNotTheBinarysOwn() {
        State start = BinaryFlyby.state();
        ReferenceTrack track = new ReferenceTrack(SYSTEM, start, BinaryFlyby.FIRST_STEP,
                ReferenceTrack.DEFAULT_TOLERANCES);
        State s13 = new VelocityVerletIntegrator().advance(SYSTEM, start, 0.0002, 65_000);
        assertTrue(ReferenceTrack.maxPositionGap(s13.positions(), track.positionsAt(13.0)) < 0.02);
    }

    @Test
    void theAdaptiveMethodFollowsItWhenTheToleranceIsTightEnough() {
        State start = BinaryFlyby.state();
        ReferenceTrack track = new ReferenceTrack(SYSTEM, start, BinaryFlyby.FIRST_STEP,
                ReferenceTrack.DEFAULT_TOLERANCES);
        double[] truth = track.positionsAt(80.0);
        var tight = new DormandPrince45Integrator().integrate(SYSTEM, start, 80.0, BinaryFlyby.FIRST_STEP,
                new Tolerances(1e-10, 1e-10), 10_000_000);
        assertTrue(ReferenceTrack.maxPositionGap(tight.finalState().positions(), truth) < 1e-4);
        assertTrue(tight.minStep() < 3e-5, "the step shrinks at the encounter: " + tight.minStep());
        var loose = new DormandPrince45Integrator().integrate(SYSTEM, start, 80.0, BinaryFlyby.FIRST_STEP,
                new Tolerances(1e-6, 1e-6), 10_000_000);
        assertTrue(ReferenceTrack.maxPositionGap(loose.finalState().positions(), truth) > 0.1,
                "a loose tolerance is not enough either");
    }
}
