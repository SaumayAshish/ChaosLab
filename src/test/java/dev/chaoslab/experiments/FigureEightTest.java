package dev.chaoslab.experiments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.chaoslab.analysis.LyapunovTracker;
import dev.chaoslab.analysis.ReferenceTrack;
import dev.chaoslab.config.ScenarioDraft;
import dev.chaoslab.integration.VelocityVerletIntegrator;
import dev.chaoslab.physics.Diagnostics;
import dev.chaoslab.physics.NBodySystem;
import dev.chaoslab.physics.State;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link FigureEight}. The bounds quote what was measured when the tests were written (JDK 21, reference
 * track at tolerance 1e-13); each is several times looser than the measurement so that it fails on a wrong number,
 * not on rounding.
 */
class FigureEightTest {

    private static final NBodySystem SYSTEM = FigureEight.system();
    private static final double T = FigureEight.PERIOD;

    private static ReferenceTrack track(State start) {
        return new ReferenceTrack(SYSTEM, start, 0.005, ReferenceTrack.DEFAULT_TOLERANCES);
    }

    @Test
    void initialStateHasTheDocumentedDigitsAndExactSymmetry() {
        State s = FigureEight.state();
        double[] q = s.positions();
        double[] v = s.velocities();
        assertEquals(-0.97000436, q[0], 0.0);
        assertEquals(0.24308753, q[1], 0.0);
        assertEquals(0.97000436, q[3], 0.0);
        assertEquals(-0.24308753, q[4], 0.0);
        assertEquals(0.0, q[6], 0.0);
        assertEquals(0.0, q[7], 0.0);
        // the published velocity of body 3, reproduced by an exact doubling of the half velocity of bodies 1 and 2
        assertEquals(0.93240737, v[6], 0.0);
        assertEquals(0.86473146, v[7], 0.0);
        assertEquals(v[0], v[3], 0.0);
        assertEquals(v[1], v[4], 0.0);
        assertEquals(-v[6], 2.0 * v[0], 0.0);
        assertEquals(-v[7], 2.0 * v[1], 0.0);
    }

    @Test
    void momentumCentreOfMassAndAngularMomentumAreExactlyZero() {
        Diagnostics.Invariants inv = Diagnostics.measure(SYSTEM, FigureEight.state());
        assertEquals(0.0, inv.momentum().norm(), 0.0);
        assertEquals(0.0, inv.centreOfMass().norm(), 0.0);
        assertEquals(0.0, inv.angularMomentum().norm(), 0.0);
    }

    @Test
    void energyIsTheValueOfTheRoundedData() {
        double e = Diagnostics.measure(SYSTEM, FigureEight.state()).totalEnergy();
        assertEquals(-1.287141991766, e, 1e-11); // also found independently with SciPy DOP853
    }

    @Test
    void theOrbitReturnsToItsStartAfterOnePeriod() {
        State s0 = FigureEight.state();
        ReferenceTrack ref = track(s0);
        double gap = ReferenceTrack.maxPositionGap(ref.positionsAt(T), s0.positions());
        assertTrue(gap < 5e-8, "gap after one period " + gap); // measured 1.6e-8: the 8-digit data are not exact
    }

    @Test
    void theChoreographyStaysClosedForTenPeriods() {
        State s0 = FigureEight.state();
        ReferenceTrack ref = track(s0);
        double gap = ReferenceTrack.maxPositionGap(ref.positionsAt(10 * T), s0.positions());
        assertTrue(gap < 1e-6, "gap after ten periods " + gap); // measured 1.4e-7
    }

    @Test
    void thePeriodConstantIsRightToBetterThanAThousandth() {
        State s0 = FigureEight.state();
        ReferenceTrack ref = track(s0);
        double atPeriod = ReferenceTrack.maxPositionGap(ref.positionsAt(T), s0.positions());
        double late = ReferenceTrack.maxPositionGap(ref.positionsAt(T * 1.001), s0.positions());
        assertTrue(late > 100.0 * atPeriod, "a period 0.1% too long must be clearly worse: " + late + " vs "
                + atPeriod);
    }

    @Test
    void theClosestApproachJustifiesAModerateStep() {
        State s0 = FigureEight.state();
        ReferenceTrack ref = track(s0);
        double closest = Double.POSITIVE_INFINITY;
        for (int i = 0; i <= 2000; i++) {
            double[] q = ref.positionsAt(T * i / 2000.0);
            for (int a = 0; a < 3; a++) {
                for (int b = a + 1; b < 3; b++) {
                    closest = Math.min(closest, Math.hypot(q[3 * a] - q[3 * b], q[3 * a + 1] - q[3 * b + 1]));
                }
            }
        }
        assertTrue(closest > 0.68 && closest < 0.70, "closest pair distance " + closest); // measured 0.6905
    }

    /** Position gap of a fixed-step Verlet run to the reference after {@code periods} periods. */
    private static double verletGap(double h, int periods) {
        State s0 = FigureEight.state();
        ReferenceTrack ref = track(s0);
        VelocityVerletIntegrator verlet = new VelocityVerletIntegrator();
        double[] q = s0.positions().clone();
        double[] v = s0.velocities().clone();
        int n = (int) Math.round(periods * T / h);
        for (int i = 0; i < n; i++) {
            verlet.step(SYSTEM, q, v, h);
        }
        return ReferenceTrack.maxPositionGap(q, ref.positionsAt(n * h));
    }

    @Test
    void verletErrorGrowsRoughlyLinearlyInTimeAndByAboutFourWhenTheStepDoubles() {
        double fiveCoarse = verletGap(0.01, 5);
        double tenCoarse = verletGap(0.01, 10);
        double fiveFine = verletGap(0.005, 5);
        assertTrue(fiveCoarse > 1e-3 && fiveCoarse < 3e-3, "h=0.01, 5 periods: " + fiveCoarse); // measured 1.8e-3
        double growth = tenCoarse / fiveCoarse; // linear growth would give 2, exponential growth much more
        assertTrue(growth > 1.6 && growth < 2.5, "10 periods over 5 periods: " + growth); // measured 2.02
        double ratio = fiveCoarse / fiveFine; // second order: about 4
        assertTrue(ratio > 3.4 && ratio < 4.6, "h=0.01 over h=0.005: " + ratio); // measured 4.0
    }

    @Test
    void aOnePercentDisplacementOfBodyOneDestroysTheChoreography() {
        State s0 = FigureEight.state();
        double[] q = s0.positions().clone();
        q[0] += 0.01;
        State perturbed = State.of(0.0, q, s0.velocities());
        ReferenceTrack exact = track(s0);
        ReferenceTrack moved = track(perturbed);
        double gap = ReferenceTrack.maxPositionGap(moved.positionsAt(5 * T), exact.positionsAt(5 * T));
        assertTrue(gap > 0.1, "gap after five periods " + gap); // measured 0.60
    }

    @Test
    void theLyapunovEstimateShowsTheStableSignature() {
        LyapunovTracker tracker = new LyapunovTracker(SYSTEM, FigureEight.state(), LyapunovTracker.DEFAULT_TOLERANCES,
                LyapunovTracker.DEFAULT_SEPARATION, 1L);
        tracker.advanceTo(60.0);
        double running = tracker.runningExponent();
        double slope = tracker.recentSlope(0.5);
        assertTrue(running < 0.2, "running mean " + running); // measured 0.098 and falling like 1/t
        assertTrue(slope < 0.5 * running, "recent slope " + slope + " vs running " + running); // measured 0.0225
    }

    @Test
    void thePresetDraftReadsBackAsTheSameValidScenario() {
        ScenarioDraft draft = ScenarioDraft.of(SYSTEM, FigureEight.state(), 0.005);
        ScenarioDraft.Validation v = draft.validate(true);
        assertTrue(v.isValid(), String.valueOf(v.issues()));
        assertTrue(v.issues().isEmpty(), "no notes or warnings expected: " + v.issues());
        State back = v.scenario().initial();
        assertEquals(0.0, maxDifference(FigureEight.state().positions(), back.positions()), 0.0);
        assertEquals(0.0, maxDifference(FigureEight.state().velocities(), back.velocities()), 0.0);
    }

    private static double maxDifference(double[] a, double[] b) {
        double m = 0.0;
        for (int i = 0; i < a.length; i++) {
            m = Math.max(m, Math.abs(a[i] - b[i]));
        }
        return m;
    }
}
