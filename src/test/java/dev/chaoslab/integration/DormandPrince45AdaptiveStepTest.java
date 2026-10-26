package dev.chaoslab.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.chaoslab.integration.DormandPrince45Integrator.AcceptedStep;
import dev.chaoslab.integration.DormandPrince45Integrator.Tolerances;
import dev.chaoslab.physics.NBodySystem;
import dev.chaoslab.physics.State;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link DormandPrince45Integrator#adaptiveStep}, the one-step-at-a-time adaptive interface used by the
 * simulation runner. The strongest check is the first one: the new method must make exactly the same decisions, bit
 * for bit, as the already validated {@link DormandPrince45Integrator#integrate}.
 */
class DormandPrince45AdaptiveStepTest {

    private static final NBodySystem TWO = NBodySystem.normalized(1.0, 1.0);

    /** Two equal masses started at apocentre; factor 1 is a circular orbit, smaller factors are more eccentric. */
    private static State orbit(double factor) {
        double v = factor * Math.sqrt(0.5);
        return State.of(0.0, new double[] {-0.5, 0, 0, 0.5, 0, 0}, new double[] {0, -v, 0, 0, v, 0});
    }

    @Test
    void makesTheSameDecisionsBitForBitAsTheValidatedIntegrateMethod() {
        State start = orbit(0.5);                       // eccentricity 0.75: the step must change a lot
        // tolerance 1e-6 on this orbit (measured): about 150 of 400 steps follow a rejection, and 125 of those
        // have a small error that would invite growth, which the controller must suppress right after a rejection
        Tolerances tol = new Tolerances(1e-6, 1e-6);
        List<double[]> reference = new ArrayList<>();   // {h, q0..q5, v0..v5} after every accepted step
        new DormandPrince45Integrator().integrate(TWO, start, 40.0, 0.3, tol, 1_000_000,
                (t, h, q, v) -> {
                    double[] row = new double[13];
                    row[0] = h;
                    System.arraycopy(q, 0, row, 1, 6);
                    System.arraycopy(v, 0, row, 7, 6);
                    reference.add(row);
                });
        assertTrue(reference.size() > 100);

        DormandPrince45Integrator adaptive = new DormandPrince45Integrator();
        double[] q = start.positions();
        double[] v = start.velocities();
        double t = 0.0;
        int rejections = 0;
        int growthSuppressed = 0;
        // the last reference step is shortened to land on tEnd, so it is the only one not comparable
        for (int k = 0; k < reference.size() - 1; k++) {
            AcceptedStep step = adaptive.adaptiveStep(TWO, q, v, t, 0.3, tol);
            t += step.stepSize();
            rejections += step.rejections();
            double[] row = reference.get(k);
            assertEquals(row[0], step.stepSize(), 0.0, "step size differs at step " + k);
            for (int i = 0; i < 6; i++) {
                assertEquals(row[1 + i], q[i], 0.0, "q[" + i + "] differs at step " + k);
                assertEquals(row[7 + i], v[i], 0.0, "v[" + i + "] differs at step " + k);
            }
            assertTrue(step.errorNorm() <= 1.0);
            if (step.rejections() > 0 && step.errorNorm() < 0.55) {
                growthSuppressed++;   // 0.9 * err^(-1/5) > 1 here, so an unconstrained controller would grow the step
            }
        }
        assertTrue(rejections > 50, "expected many rejections, got " + rejections);
        assertTrue(growthSuppressed > 20, "the scenario must exercise the no-growth-after-rejection rule: " + growthSuppressed);
    }

    @Test
    void theStepShrinksAtPericentreAndGrowsAgain() {
        State start = orbit(0.5);
        DormandPrince45Integrator dp = new DormandPrince45Integrator();
        double[] q = start.positions();
        double[] v = start.velocities();
        double t = 0.0;
        double min = Double.POSITIVE_INFINITY;
        double max = 0.0;
        while (t < 8.0) { // about one orbital period (a = 4/7, period 2 pi a^1.5 / sqrt(2) = 1.92 here: several periods)
            AcceptedStep step = dp.adaptiveStep(TWO, q, v, t, 0.01, new Tolerances(1e-10, 1e-10));
            t += step.stepSize();
            min = Math.min(min, step.stepSize());
            max = Math.max(max, step.stepSize());
        }
        assertTrue(max > 4.0 * min, "steps should vary strongly along an eccentric orbit: min " + min + ", max " + max);
    }

    @Test
    void anAdaptiveCircularOrbitStaysCloseToTheExactSolution() {
        State start = orbit(1.0);
        double omega = Math.sqrt(2.0);                   // relative angular velocity sqrt(G (m1 + m2) / d^3), d = 1
        DormandPrince45Integrator dp = new DormandPrince45Integrator();
        double[] q = start.positions();
        double[] v = start.velocities();
        double t = 0.0;
        double period = 2.0 * Math.PI / omega;
        while (t < period) {
            t += dp.adaptiveStep(TWO, q, v, t, 0.05, new Tolerances(1e-10, 1e-10)).stepSize();
        }
        double expectedX = 0.5 * Math.cos(omega * t);    // body 1; body 0 is its mirror image
        double expectedY = 0.5 * Math.sin(omega * t);
        assertEquals(expectedX, q[3], 1e-7);             // measured error is about a hundred times the tolerance
        assertEquals(expectedY, q[4], 1e-7);
        assertEquals(-expectedX, q[0], 1e-7);
    }

    @Test
    void aFreshInstanceStartsAgainFromTheInitialStepAndRunsAreReproducible() {
        Tolerances tol = new Tolerances(1e-9, 1e-9);
        double[][] first = new double[2][];
        for (int run = 0; run < 2; run++) {
            DormandPrince45Integrator dp = new DormandPrince45Integrator();
            State s = orbit(0.6);
            double[] q = s.positions();
            double[] v = s.velocities();
            double t = 0.0;
            double[] sizes = new double[50];
            for (int k = 0; k < 50; k++) {
                sizes[k] = dp.adaptiveStep(TWO, q, v, t, 0.02, tol).stepSize();
                t += sizes[k];
            }
            if (run == 0) {
                first[0] = sizes;
            } else {
                first[1] = sizes;
            }
        }
        assertEquals(0.02, first[0][0], 0.0);            // the very first trial was accepted unchanged here
        for (int k = 0; k < 50; k++) {
            assertEquals(first[0][k], first[1][k], 0.0);
        }
    }

    @Test
    void anImpossibleToleranceIsReportedNotSilentlyIgnored() {
        State s = orbit(0.8);
        DormandPrince45Integrator dp = new DormandPrince45Integrator();
        NumericalFailureException e = assertThrows(NumericalFailureException.class,
                () -> dp.adaptiveStep(TWO, s.positions(), s.velocities(), 1.0, 0.01, new Tolerances(1e-40, 1e-40)));
        assertTrue(e.getMessage().contains("underflow"), e.getMessage());
    }

    @Test
    void aSingularStartStateAndABadInitialStepAreRejected() {
        State coincident = State.of(0, new double[] {1, 1, 1, 1, 1, 1}, new double[6]);
        DormandPrince45Integrator dp = new DormandPrince45Integrator();
        NumericalFailureException e = assertThrows(NumericalFailureException.class, () -> dp.adaptiveStep(TWO,
                coincident.positions(), coincident.velocities(), 0.0, 0.01, new Tolerances(1e-9, 1e-9)));
        assertTrue(e.getMessage().contains("singularity"), e.getMessage());
        State s = orbit(1.0);
        assertThrows(IllegalArgumentException.class, () -> dp.adaptiveStep(TWO, s.positions(), s.velocities(), 0.0,
                0.0, new Tolerances(1e-9, 1e-9)));
        assertThrows(IllegalArgumentException.class, () -> dp.adaptiveStep(TWO, s.positions(), s.velocities(), 0.0,
                Double.NaN, new Tolerances(1e-9, 1e-9)));
    }
}
