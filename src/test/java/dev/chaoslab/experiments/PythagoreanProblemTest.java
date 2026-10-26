package dev.chaoslab.experiments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.chaoslab.analysis.ReferenceTrack;
import dev.chaoslab.config.ScenarioDraft;
import dev.chaoslab.integration.DormandPrince45Integrator;
import dev.chaoslab.integration.DormandPrince45Integrator.Tolerances;
import dev.chaoslab.integration.Integrator;
import dev.chaoslab.integration.Rk4Integrator;
import dev.chaoslab.integration.VelocityVerletIntegrator;
import dev.chaoslab.physics.Diagnostics;
import dev.chaoslab.physics.NBodySystem;
import dev.chaoslab.physics.State;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link PythagoreanProblem}. The numbers in the comments were measured in this project (reference:
 * Dormand-Prince at tolerance 1e-13), not taken from a table.
 */
class PythagoreanProblemTest {

    private static final NBodySystem SYSTEM = PythagoreanProblem.system();

    private static ReferenceTrack track(double tolerance) {
        return new ReferenceTrack(SYSTEM, PythagoreanProblem.state(), PythagoreanProblem.FIRST_STEP,
                new Tolerances(tolerance, tolerance));
    }

    private static double distance(double[] q, int a, int b) {
        return Math.sqrt(Math.pow(q[3 * a] - q[3 * b], 2) + Math.pow(q[3 * a + 1] - q[3 * b + 1], 2)
                + Math.pow(q[3 * a + 2] - q[3 * b + 2], 2));
    }

    /** Time and value of the minimum distance between bodies a and b, searched in [centre - 0.01, centre + 0.01]. */
    private static double[] closestPass(ReferenceTrack ref, int a, int b, double centre) {
        double lo = centre - 0.01;
        double hi = centre + 0.01;
        for (int i = 0; i < 100; i++) {
            double m1 = lo + (hi - lo) / 3.0;
            double m2 = hi - (hi - lo) / 3.0;
            if (distance(ref.positionsAt(m1), a, b) < distance(ref.positionsAt(m2), a, b)) {
                hi = m2;
            } else {
                lo = m1;
            }
        }
        double t = 0.5 * (lo + hi);
        return new double[] {t, distance(ref.positionsAt(t), a, b)};
    }

    @Test
    void theTriangleHasSides3And4And5OppositeMassesThreeFourAndFive() {
        double[] q = PythagoreanProblem.state().positions();
        assertEquals(3.0, distance(q, 1, 2), 1e-15); // opposite mass 3
        assertEquals(4.0, distance(q, 0, 2), 1e-15); // opposite mass 4
        assertEquals(5.0, distance(q, 0, 1), 1e-15); // opposite mass 5
        assertEquals(3.0, SYSTEM.mass(0), 0.0);
        assertEquals(4.0, SYSTEM.mass(1), 0.0);
        assertEquals(5.0, SYSTEM.mass(2), 0.0);
    }

    @Test
    void theBodiesStartAtRestWithZeroMomentumAngularMomentumAndCentreOfMass() {
        State s0 = PythagoreanProblem.state();
        for (double v : s0.velocities()) {
            assertEquals(0.0, v, 0.0);
        }
        double cx = 0.0;
        double cy = 0.0;
        for (int i = 0; i < 3; i++) {
            cx += SYSTEM.mass(i) * s0.position(i).x();
            cy += SYSTEM.mass(i) * s0.position(i).y();
        }
        assertEquals(0.0, cx, 0.0);
        assertEquals(0.0, cy, 0.0);
    }

    @Test
    void theEnergyIsMinus769Over60() {
        double e = Diagnostics.measure(SYSTEM, PythagoreanProblem.state()).totalEnergy();
        assertEquals(PythagoreanProblem.ENERGY, e, 1e-13);
        assertEquals(-12.8166666667, e, 1e-9);
    }

    @Test
    void massesFourAndFiveHaveTheirFirstAndTheirClosestPassWhereTheNoteSaysThey() {
        ReferenceTrack ref = track(1e-13);
        double[] first = closestPass(ref, 1, 2, 1.8795);
        assertTrue(Math.abs(first[0] - 1.8793) < 0.002, "first pass at t = " + first[0]); // measured 1.879343
        assertTrue(first[1] > 0.009 && first[1] < 0.0105, "first pass distance " + first[1]); // measured 0.00970
        double[] deep = closestPass(ref, 1, 2, 15.83);
        assertTrue(Math.abs(deep[0] - 15.83) < 0.002, "deepest pass at t = " + deep[0]); // measured 15.829920
        assertTrue(deep[1] > 1e-4 && deep[1] < 1e-3, "deepest pass distance " + deep[1]); // measured 4.1e-4
    }

    @Test
    void fixedStepMethodsLoseTheOrbitAtTheFirstCloseEncounter() {
        double tEnd = 2.0;
        ReferenceTrack ref = track(1e-13);
        double[] truth = ref.positionsAt(tEnd);
        double verlet = fixedStepGap(new VelocityVerletIntegrator(), 1e-3, tEnd, truth);
        double rk4 = fixedStepGap(new Rk4Integrator(), 1e-3, tEnd, truth);
        double verletFine = fixedStepGap(new VelocityVerletIntegrator(), 1e-4, tEnd, truth);
        assertTrue(verlet > 0.5, "Verlet h=1e-3 gap at t=2: " + verlet); // measured 2.4
        assertTrue(rk4 > 0.5, "RK4 h=1e-3 gap at t=2: " + rk4); // measured 3.4
        assertTrue(verletFine > 1e-3, "Verlet h=1e-4 gap at t=2: " + verletFine); // measured 1.0e-2
    }

    private static double fixedStepGap(Integrator integrator, double h, double tEnd, double[] truth) {
        State s0 = PythagoreanProblem.state();
        double[] q = s0.positions().clone();
        double[] v = s0.velocities().clone();
        long n = Math.round(tEnd / h);
        for (long i = 0; i < n; i++) {
            integrator.step(SYSTEM, q, v, h);
        }
        return ReferenceTrack.maxPositionGap(q, truth);
    }

    @Test
    void adaptiveStepsFollowTheOrbitThroughTheEncounters() {
        double tEnd = 10.0;
        double[] truth = track(1e-13).positionsAt(tEnd);
        DormandPrince45Integrator dp = new DormandPrince45Integrator();
        double tight = ReferenceTrack.maxPositionGap(truth, dp.integrate(SYSTEM, PythagoreanProblem.state(), tEnd,
                PythagoreanProblem.FIRST_STEP, new Tolerances(1e-10, 1e-10), 10_000_000).finalState().positions());
        double loose = ReferenceTrack.maxPositionGap(truth, dp.integrate(SYSTEM, PythagoreanProblem.state(), tEnd,
                PythagoreanProblem.FIRST_STEP, new Tolerances(1e-8, 1e-8), 10_000_000).finalState().positions());
        assertTrue(tight < 1e-6, "tolerance 1e-10 gap at t=10: " + tight); // measured 1.7e-7
        assertTrue(loose > 10.0 * tight, "tolerance 1e-8 gap " + loose + " vs " + tight); // measured 4.3e-5
    }

    @Test
    void theLightestBodyEscapesAtEveryReferenceToleranceButTheDetailsDiffer() {
        for (double tol : new double[] {1e-12, 1e-13, 1e-14}) {
            double[] q = track(tol).positionsAt(70.0);
            assertTrue(distance(q, 0, 1) > 10.0 && distance(q, 0, 2) > 10.0,
                    "mass 3 is far from both others at tolerance " + tol); // measured 29 to 33
            assertTrue(distance(q, 1, 2) < 2.0, "masses 4 and 5 stay together at tolerance " + tol); // 0.9 to 1.06
        }
    }

    @Test
    void theReferenceItselfLosesThePredictabilityAfterAboutFiftyFiveTimeUnits() {
        ReferenceTrack a = track(1e-13);
        ReferenceTrack b = track(1e-14);
        double early = ReferenceTrack.maxPositionGap(a.positionsAt(10.0), b.positionsAt(10.0));
        double late = ReferenceTrack.maxPositionGap(a.positionsAt(70.0), b.positionsAt(70.0));
        assertTrue(early < 1e-9, "1e-13 against 1e-14 at t=10: " + early); // measured 8.4e-11
        assertTrue(late > 0.5, "1e-13 against 1e-14 at t=70: " + late); // measured 2.1
    }

    @Test
    void thePresetDraftIsValidAndReadsBackAsTheSameScenario() {
        ScenarioDraft draft = ScenarioDraft.of(SYSTEM, PythagoreanProblem.state(), PythagoreanProblem.FIRST_STEP);
        ScenarioDraft.Validation v = draft.validate(true);
        assertTrue(v.isValid(), String.valueOf(v.issues()));
        State back = v.scenario().initial();
        assertEquals(0.0, maxDifference(PythagoreanProblem.state().positions(), back.positions()), 0.0);
        assertEquals(0.0, maxDifference(PythagoreanProblem.state().velocities(), back.velocities()), 0.0);
    }

    private static double maxDifference(double[] a, double[] b) {
        double m = 0.0;
        for (int i = 0; i < a.length; i++) {
            m = Math.max(m, Math.abs(a[i] - b[i]));
        }
        return m;
    }
}
