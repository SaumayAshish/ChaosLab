package dev.chaoslab.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.chaoslab.integration.DormandPrince45Integrator.AdaptiveResult;
import dev.chaoslab.integration.DormandPrince45Integrator.Tolerances;
import dev.chaoslab.physics.NBodySystem;
import dev.chaoslab.physics.State;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link DormandPrince45Integrator}.
 *
 * <p>Failure interpretation: a wrong observed order means a wrong Butcher-tableau coefficient (implementation
 * defect). Tolerance-monotonicity tests fail if the error controller is wrong. Note that the controller bounds
 * the <em>local</em> error; these tests never assert that the <em>global</em> error is below the tolerance, because
 * that is not guaranteed (see the class documentation). The failure-path tests assert that problems are reported
 * rather than hidden.
 */
class DormandPrince45IntegratorTest {

    private static final double W = Math.sqrt(2.0);

    private static NBodySystem system() {
        return NBodySystem.normalized(1.0, 1.0);
    }

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

    private static Tolerances tol(double relative) {
        return new Tolerances(relative * 1e-3, relative);
    }

    // ---------------------------------------------------------------- fixed-step order

    @Test
    void fixedStepConvergesAtFifthOrder() {
        DormandPrince45Integrator dp = new DormandPrince45Integrator();
        double e1 = positionError(dp.advance(system(), orbit(1.0), 1.0 / 8, 8));
        double e2 = positionError(dp.advance(system(), orbit(1.0), 1.0 / 16, 16));
        double p = Math.log(e1 / e2) / Math.log(2.0);
        assertEquals(5.0, p, 0.5, "observed order of Dormand-Prince was " + p);
    }

    // ---------------------------------------------------------------- adaptive behaviour

    @Test
    void tighterToleranceGivesSmallerErrorAtTheCostOfMoreSteps() {
        DormandPrince45Integrator dp = new DormandPrince45Integrator();
        AdaptiveResult loose = dp.integrate(system(), orbit(1.0), 10.0, 0.01, tol(1e-6), 1_000_000);
        AdaptiveResult medium = dp.integrate(system(), orbit(1.0), 10.0, 0.01, tol(1e-8), 1_000_000);
        AdaptiveResult tight = dp.integrate(system(), orbit(1.0), 10.0, 0.01, tol(1e-10), 1_000_000);

        double eLoose = positionError(loose.finalState());
        double eMedium = positionError(medium.finalState());
        double eTight = positionError(tight.finalState());
        assertTrue(eLoose > eMedium && eMedium > eTight,
                "errors should shrink with tolerance: " + eLoose + ", " + eMedium + ", " + eTight);
        assertTrue(loose.acceptedSteps() < medium.acceptedSteps() && medium.acceptedSteps() < tight.acceptedSteps());
        assertTrue(eTight < 1e-7, "error at relative tolerance 1e-10 was " + eTight);
    }

    @Test
    void everyAcceptedStepMeetsTheErrorTest() {
        AdaptiveResult r = new DormandPrince45Integrator()
                .integrate(system(), orbit(1.0), 10.0, 0.01, tol(1e-9), 1_000_000);
        assertTrue(r.maxErrorNorm() <= 1.0, "max accepted error norm was " + r.maxErrorNorm());
        assertTrue(r.maxErrorNorm() > 0.0);
    }

    @Test
    void landsExactlyOnTheRequestedEndTime() {
        AdaptiveResult r = new DormandPrince45Integrator()
                .integrate(system(), orbit(1.0), 7.3, 0.01, tol(1e-9), 1_000_000);
        assertEquals(7.3, r.finalState().time(), 0.0);
    }

    @Test
    void anOversizedFirstStepIsRejectedAndRecovered() {
        AdaptiveResult r = new DormandPrince45Integrator()
                .integrate(system(), orbit(1.0), 10.0, 5.0, tol(1e-8), 1_000_000);
        assertTrue(r.rejectedSteps() > 0, "a step of 5 on a period-4.4 orbit must be rejected");
        assertTrue(positionError(r.finalState()) < 1e-6, "error after recovery was " + positionError(r.finalState()));
    }

    @Test
    void stepSizeVariesStronglyThroughAHighlyEccentricOrbit() {
        // f = 0.3 gives a pericentre far closer than the initial separation: the controller must use
        // much smaller steps there than near apocentre.
        AdaptiveResult r = new DormandPrince45Integrator()
                .integrate(system(), orbit(0.3), 10.0, 0.01, new Tolerances(1e-12, 1e-9), 1_000_000);
        assertTrue(r.minStep() > 0.0);
        assertTrue(r.maxStep() / r.minStep() > 20.0,
                "step range was only " + r.minStep() + " to " + r.maxStep());
    }

    @Test
    void observerSeesEveryAcceptedStepInOrder() {
        List<Double> times = new ArrayList<>();
        AdaptiveResult r = new DormandPrince45Integrator().integrate(system(), orbit(1.0), 5.0, 0.01,
                tol(1e-8), 1_000_000, (t, h, q, v) -> times.add(t));
        assertEquals(r.acceptedSteps(), times.size());
        for (int i = 1; i < times.size(); i++) {
            assertTrue(times.get(i) > times.get(i - 1), "times must increase strictly");
        }
        assertEquals(5.0, times.get(times.size() - 1), 0.0);
    }

    // ---------------------------------------------------------------- determinism and the first-same-as-last cache

    @Test
    void resultsAreBitIdenticalRegardlessOfIntegratorReuse() {
        DormandPrince45Integrator reused = new DormandPrince45Integrator();
        State first = reused.integrate(system(), orbit(0.7), 6.0, 0.01, tol(1e-9), 1_000_000).finalState();
        State second = reused.integrate(system(), orbit(0.7), 6.0, 0.01, tol(1e-9), 1_000_000).finalState();
        State fresh = new DormandPrince45Integrator()
                .integrate(system(), orbit(0.7), 6.0, 0.01, tol(1e-9), 1_000_000).finalState();
        assertEquals(first, second);
        assertEquals(first, fresh);
    }

    @Test
    void integrationDoesNotMutateTheInputState() {
        State s0 = orbit(0.7);
        State copy = orbit(0.7);
        new DormandPrince45Integrator().integrate(system(), s0, 3.0, 0.01, tol(1e-8), 1_000_000);
        assertEquals(copy, s0);
    }

    // ---------------------------------------------------------------- failures must be reported

    @Test
    void impossibleToleranceIsReportedAsStepUnderflow() {
        NumericalFailureException e = assertThrows(NumericalFailureException.class,
                () -> new DormandPrince45Integrator().integrate(system(), orbit(1.0), 10.0, 0.01,
                        new Tolerances(1e-300, 1e-300), 1_000_000));
        assertTrue(e.getMessage().contains("underflow"), e.getMessage());
    }

    @Test
    void exceedingMaxStepsIsReported() {
        NumericalFailureException e = assertThrows(NumericalFailureException.class,
                () -> new DormandPrince45Integrator().integrate(system(), orbit(1.0), 10.0, 0.01,
                        tol(1e-12), 20));
        assertTrue(e.getMessage().contains("maxSteps"), e.getMessage());
    }

    @Test
    void invalidArgumentsAreRejected() {
        DormandPrince45Integrator dp = new DormandPrince45Integrator();
        State s = orbit(1.0);
        NBodySystem sys = system();
        assertThrows(IllegalArgumentException.class, () -> new Tolerances(0.0, 1e-9));
        assertThrows(IllegalArgumentException.class, () -> new Tolerances(1e-9, -1.0));
        assertThrows(IllegalArgumentException.class, () -> new Tolerances(Double.NaN, 1e-9));
        assertThrows(IllegalArgumentException.class, () -> dp.integrate(sys, s, 0.0, 0.01, tol(1e-9), 100));
        assertThrows(IllegalArgumentException.class, () -> dp.integrate(sys, s, Double.NaN, 0.01, tol(1e-9), 100));
        assertThrows(IllegalArgumentException.class, () -> dp.integrate(sys, s, 1.0, 0.0, tol(1e-9), 100));
        assertThrows(IllegalArgumentException.class, () -> dp.integrate(sys, s, 1.0, 0.01, tol(1e-9), 0));
        assertThrows(IllegalArgumentException.class,
                () -> dp.integrate(NBodySystem.normalized(1.0, 1.0, 1.0), s, 1.0, 0.01, tol(1e-9), 100));
    }

    @Test
    void singularInitialStateIsReportedWithItsRealCause() {
        State coincident = State.of(0, new double[] {1, 1, 1, 1, 1, 1}, new double[6]);
        assertThrows(NBodySystem.SingularConfigurationException.class,
                () -> new DormandPrince45Integrator().integrate(system(), coincident, 1.0, 0.01, tol(1e-9), 100));
    }

    @Test
    void integratorDescribesItself() {
        DormandPrince45Integrator m = new DormandPrince45Integrator();
        assertEquals(5, m.order());
        assertTrue(!m.isSymplectic());
        assertEquals("Dormand-Prince 5(4)", m.name());
    }
}
