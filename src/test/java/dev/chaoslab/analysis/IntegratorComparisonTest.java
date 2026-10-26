package dev.chaoslab.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.chaoslab.analysis.IntegratorComparison.MethodResult;
import dev.chaoslab.analysis.IntegratorComparison.MethodSpec;
import dev.chaoslab.analysis.IntegratorComparison.Report;
import dev.chaoslab.analysis.IntegratorComparison.Sample;
import dev.chaoslab.integration.DormandPrince45Integrator;
import dev.chaoslab.integration.EulerIntegrator;
import dev.chaoslab.integration.Integrator;
import dev.chaoslab.integration.NumericalFailureException;
import dev.chaoslab.integration.Rk4Integrator;
import dev.chaoslab.integration.VelocityVerletIntegrator;
import dev.chaoslab.physics.NBodySystem;
import dev.chaoslab.physics.State;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link IntegratorComparison}.
 *
 * <p>Failure interpretation: the structural, CSV, validation and failure-capture tests fail only on a harness defect.
 * The two ranking tests encode <em>measured, deterministic</em> behaviour of the four integrators on specific
 * orbits (see the comments for the observed values). They are not universal laws: if one fails after a legitimate
 * change to an integrator, first decide whether the integrator or the expectation is wrong before editing the test.
 */
class IntegratorComparisonTest {

    private static final double W = Math.sqrt(2.0);

    private static NBodySystem system() {
        return NBodySystem.normalized(1.0, 1.0);
    }

    private static State orbit(double f) {
        double v = f * Math.sqrt(0.5);
        return State.of(0.0, new double[] {-0.5, 0, 0, 0.5, 0, 0}, new double[] {0, -v, 0, 0, v, 0});
    }

    /** Roughly equal force-evaluation cost per sample: Euler 1/step, RK4 4/step, Verlet 1/step, DP 6/step. */
    private static List<MethodSpec> equalCostSpecs(int stepsPerSample) {
        return List.of(
                new MethodSpec("Euler", EulerIntegrator::new, stepsPerSample),
                new MethodSpec("RK4", Rk4Integrator::new, stepsPerSample / 4),
                new MethodSpec("Verlet", VelocityVerletIntegrator::new, stepsPerSample),
                new MethodSpec("DP5 fixed", DormandPrince45Integrator::new, stepsPerSample / 6));
    }

    private static MethodResult find(Report r, String label) {
        return r.results().stream().filter(m -> m.label().equals(label)).findFirst().orElseThrow();
    }

    /** A deliberately broken integrator, to prove that failures are recorded rather than propagated. */
    private static final class ExplodingIntegrator implements Integrator {
        private int calls;

        @Override public String name() { return "Exploding"; }
        @Override public int order() { return 1; }
        @Override public boolean isSymplectic() { return false; }

        @Override
        public void step(NBodySystem sys, double[] q, double[] v, double h) {
            if (++calls == 5) {
                throw new NumericalFailureException("boom on step 5");
            }
        }
    }

    // ---------------------------------------------------------------- structure

    @Test
    void everyMethodIsSampledAtIdenticalTimesStartingFromZeroDrift() {
        Report r = IntegratorComparison.run(system(), orbit(1.0), 20.0, 10, equalCostSpecs(120));
        assertEquals(4, r.results().size());
        assertEquals(11, r.reference().size());
        for (MethodResult m : r.results()) {
            assertTrue(m.completed(), m.label() + " failed: " + m.failure());
            assertEquals(11, m.samples().size());
            Sample first = m.samples().get(0);
            assertEquals(0.0, first.time(), 0.0);
            assertEquals(0.0, first.relativeEnergyDrift(), 0.0);
            assertEquals(0.0, first.separationFromReference(), 0.0);
            for (int k = 0; k <= 10; k++) {
                assertEquals(2.0 * k, m.samples().get(k).time(), 1e-12);
            }
        }
        assertEquals(1200, find(r, "Euler").stepsCompleted());
        assertEquals(300, find(r, "RK4").stepsCompleted());
        assertEquals(200, find(r, "DP5 fixed").stepsCompleted());
    }

    @Test
    void referenceTrajectoryMatchesTheExactCircularOrbit() {
        Report r = IntegratorComparison.run(system(), orbit(1.0), 20.0, 10, equalCostSpecs(120));
        double worst = 0.0;
        for (State s : r.reference()) {
            double c = 0.5 * Math.cos(W * s.time());
            double sn = 0.5 * Math.sin(W * s.time());
            double[] exact = {-c, -sn, 0, c, sn, 0};
            double[] q = s.positions();
            for (int i = 0; i < 6; i++) {
                worst = Math.max(worst, Math.abs(q[i] - exact[i]));
            }
        }
        assertTrue(worst < 1e-8, "reference differs from the exact orbit by " + worst);
        assertTrue(r.referenceSpread() < 1e-7, "reference spread was " + r.referenceSpread());
    }

    @Test
    void reportIsDeterministic() {
        Report a = IntegratorComparison.run(system(), orbit(0.8), 10.0, 5, equalCostSpecs(60));
        Report b = IntegratorComparison.run(system(), orbit(0.8), 10.0, 5, equalCostSpecs(60));
        assertEquals(a, b);
    }

    // ---------------------------------------------------------------- measured behaviour (see class comment)

    @Test
    void onACircularOrbitEulerIsFarWorseThanTheOtherThree() {
        // Measured (T = 20, ~120 steps per sample): Euler max|dE/E| = 0.42, final separation 1.08;
        // RK4 5.9e-6 / 8.7e-5; Verlet 7.7e-8 / 2.6e-3; fixed Dormand-Prince 3.1e-7 / 3.1e-7.
        Report r = IntegratorComparison.run(system(), orbit(1.0), 20.0, 10, equalCostSpecs(120));
        MethodResult euler = find(r, "Euler");
        assertTrue(euler.maxAbsEnergyDrift() > 0.1, "Euler energy drift was " + euler.maxAbsEnergyDrift());
        for (String other : List.of("RK4", "Verlet", "DP5 fixed")) {
            MethodResult m = find(r, other);
            assertTrue(euler.last().separationFromReference() > 100.0 * m.last().separationFromReference(),
                    other + " separation " + m.last().separationFromReference() + " vs Euler "
                            + euler.last().separationFromReference());
            assertTrue(m.maxAbsEnergyDrift() < 1e-4, other + " energy drift " + m.maxAbsEnergyDrift());
        }
        // Verlet conserves angular momentum to rounding for pairwise central forces.
        assertTrue(Math.abs(find(r, "Verlet").last().relativeAngularMomentumDrift()) < 1e-12);
    }

    @Test
    void onAnEccentricOrbitAtEqualCostTheSymplecticMethodBeatsTheHigherOrderFixedStepMethods() {
        // Measured (e = f 0.8, T = 40, ~120 steps per sample): final separation Euler 1.67, RK4 0.65,
        // fixed Dormand-Prince 0.11, Verlet 0.068; max|dE/E| Euler 0.80, RK4 0.13, DP 0.36, Verlet 2.0e-3.
        // The fixed step that is fine on the circular orbit is too coarse at pericentre for the 5th-order
        // method: order alone does not decide the outcome. This is a result about THESE step sizes, not a
        // general ranking.
        Report r = IntegratorComparison.run(system(), orbit(0.8), 40.0, 10, equalCostSpecs(120));
        MethodResult verlet = find(r, "Verlet");
        for (String other : List.of("Euler", "RK4", "DP5 fixed")) {
            assertTrue(verlet.last().separationFromReference() < find(r, other).last().separationFromReference(),
                    "Verlet should end closer to the reference than " + other);
        }
        assertTrue(verlet.maxAbsEnergyDrift() < 1e-2, "Verlet max energy drift " + verlet.maxAbsEnergyDrift());
        assertTrue(find(r, "DP5 fixed").maxAbsEnergyDrift() > 0.1,
                "fixed-step DP5 drift " + find(r, "DP5 fixed").maxAbsEnergyDrift());
    }

    // ---------------------------------------------------------------- failure capture

    @Test
    void aFailingMethodIsRecordedWithItsPartialDataWhileOthersContinue() {
        List<MethodSpec> specs = List.of(
                new MethodSpec("Boom", ExplodingIntegrator::new, 3),
                new MethodSpec("Verlet", VelocityVerletIntegrator::new, 3));
        Report r = IntegratorComparison.run(system(), orbit(1.0), 4.0, 4, specs);

        MethodResult boom = find(r, "Boom");
        assertTrue(!boom.completed());
        assertTrue(boom.failure().contains("boom on step 5"), boom.failure());
        assertTrue(boom.failure().contains("NumericalFailureException"), boom.failure());
        assertEquals(2, boom.samples().size()); // t = 0 and the first complete sample interval
        assertEquals(4, boom.stepsCompleted());  // the fifth step threw before being counted
        assertTrue(find(r, "Verlet").completed());
        assertEquals(5, find(r, "Verlet").samples().size());
        assertTrue(r.toTable().contains("FAILED"));
    }

    @Test
    void anUncomputableReferenceIsReportedNotHidden() {
        State headOn = State.of(0, new double[] {-0.5, 0, 0, 0.5, 0, 0}, new double[6]); // free fall to collision
        NumericalFailureException e = assertThrows(NumericalFailureException.class,
                () -> IntegratorComparison.run(system(), headOn, 3.0, 3, equalCostSpecs(60)));
        assertTrue(e.getMessage().contains("Reference trajectory failed"), e.getMessage());
    }

    // ---------------------------------------------------------------- output formats

    @Test
    void csvContainsEverySampleAndRoundTripsValuesExactly() {
        Report r = IntegratorComparison.run(system(), orbit(0.8), 10.0, 5, equalCostSpecs(60));
        String[] lines = r.toCsv().split("\n");
        int expected = 1;
        for (MethodResult m : r.results()) {
            expected += m.samples().size();
        }
        assertEquals(expected, lines.length);
        assertEquals("method,time,relative_energy_drift,relative_angular_momentum_drift,separation_from_reference",
                lines[0]);

        // Row 1 + 6 + 6 + 6 is the first row of the fourth method ("DP5 fixed"), sample index 0; take sample 3 instead.
        Sample expectedSample = find(r, "Verlet").samples().get(3);
        String verletRow = null;
        int seen = 0;
        for (String line : lines) {
            if (line.startsWith("Verlet,")) {
                if (seen++ == 3) {
                    verletRow = line;
                }
            }
        }
        String[] f = verletRow.split(",");
        assertEquals(expectedSample.time(), Double.parseDouble(f[1]), 0.0);
        assertEquals(expectedSample.relativeEnergyDrift(), Double.parseDouble(f[2]), 0.0);
        assertEquals(expectedSample.relativeAngularMomentumDrift(), Double.parseDouble(f[3]), 0.0);
        assertEquals(expectedSample.separationFromReference(), Double.parseDouble(f[4]), 0.0);
    }

    @Test
    void tableNamesEveryMethodAndTheReferenceSpread() {
        String table = IntegratorComparison.run(system(), orbit(1.0), 4.0, 2, equalCostSpecs(60)).toTable();
        for (String label : List.of("Euler", "RK4", "Verlet", "DP5 fixed", "reference spread")) {
            assertTrue(table.contains(label), "missing " + label + " in:\n" + table);
        }
    }

    // ---------------------------------------------------------------- validation

    @Test
    void invalidArgumentsAreRejected() {
        NBodySystem sys = system();
        State s = orbit(1.0);
        List<MethodSpec> ok = equalCostSpecs(60);
        assertThrows(IllegalArgumentException.class, () -> IntegratorComparison.run(sys, s, 0.0, 5, ok));
        assertThrows(IllegalArgumentException.class, () -> IntegratorComparison.run(sys, s, Double.NaN, 5, ok));
        assertThrows(IllegalArgumentException.class, () -> IntegratorComparison.run(sys, s, 5.0, 0, ok));
        assertThrows(IllegalArgumentException.class, () -> IntegratorComparison.run(sys, s, 5.0, 5, List.of()));
        assertThrows(IllegalArgumentException.class, () -> IntegratorComparison.run(sys, s, 5.0, 5, null));
        assertThrows(IllegalArgumentException.class,
                () -> IntegratorComparison.run(NBodySystem.normalized(1.0, 1.0, 1.0), s, 5.0, 5, ok));
        assertThrows(IllegalArgumentException.class, () -> new MethodSpec(" ", EulerIntegrator::new, 1));
        assertThrows(IllegalArgumentException.class, () -> new MethodSpec("x", null, 1));
        assertThrows(IllegalArgumentException.class, () -> new MethodSpec("x", EulerIntegrator::new, 0));
    }
}
