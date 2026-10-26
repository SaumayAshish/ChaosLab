package dev.chaoslab.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.chaoslab.analysis.ChaosIndicators.DivergenceResult;
import dev.chaoslab.analysis.ChaosIndicators.LyapunovResult;
import dev.chaoslab.experiments.LagrangeTriangle;
import dev.chaoslab.integration.DormandPrince45Integrator;
import dev.chaoslab.integration.EulerIntegrator;
import dev.chaoslab.integration.Integrator;
import dev.chaoslab.integration.Rk4Integrator;
import dev.chaoslab.integration.VelocityVerletIntegrator;
import dev.chaoslab.physics.NBodySystem;
import dev.chaoslab.physics.State;
import java.util.Arrays;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ChaosIndicators}.
 *
 * <h2>The validation target</h2>
 * Started exactly on Lagrange's equilateral solution, the largest Lyapunov exponent is the real part of the largest
 * eigenvalue of the linearised flow. In units of the angular velocity omega the eigenvalues solve
 * {@code x^4 + x^2 + c = 0} with {@code c = 27 (m1 m2 + m2 m3 + m3 m1) / (4 (m1 + m2 + m3)^2)}; its discriminant
 * {@code 1 - 4c >= 0} is exactly the Gascheau-Routh stability condition {@code (sum m)^2 >= 27 (sum m_i m_j)}. For
 * three equal masses c = 9/4, the roots are complex and the growth rate is {@code omega * sqrt((sqrt(c) - 1/2) / 2)}
 * = 0.537285 in the normalized units used here. That value was also obtained independently, to six digits, from the
 * eigenvalues of the numerically computed one-period flow-map matrix (largest |eigenvalue| growth rate 5.37285e-01,
 * determinant 1.0000004 as required for a symplectic map).
 *
 * <p>All thresholds below come from measurements made while writing the class; the measured values are quoted next
 * to them. The start states use no trigonometry, so every number is reproducible bit for bit on any platform.
 */
class ChaosIndicatorsTest {

    private static final NBodySystem LAGRANGE = LagrangeTriangle.system();
    private static final double TRUE_EXPONENT = analyticExponent();

    private static double analyticExponent() {
        double c = 27.0 / 4.0 * 3.0 / 9.0; // (m1m2 + m2m3 + m3m1) = 3, (sum m)^2 = 9
        double realPart = Math.sqrt((Math.sqrt(c) - 0.5) / 2.0); // complex-conjugate roots: |x| = sqrt(c)
        return realPart * LagrangeTriangle.angularVelocity();
    }

    private static LyapunovResult lagrange(Supplier<Integrator> f, double h, double tau, double totalTime,
            double d0, long seed) {
        return run(LAGRANGE, LagrangeTriangle.state(0), f, h, tau, totalTime, d0, seed);
    }

    private static LyapunovResult run(NBodySystem sys, State start, Supplier<Integrator> f, double h, double tau,
            double totalTime, double d0, long seed) {
        int stepsPer = (int) Math.round(tau / h);
        int count = (int) Math.round(totalTime / (stepsPer * h));
        return ChaosIndicators.lyapunov(sys, start, f, h, stepsPer, count, d0, seed);
    }

    private static State circularKepler() {
        double v = Math.sqrt(0.5);
        return State.of(0, new double[] {-0.5, 0, 0, 0.5, 0, 0}, new double[] {0, -v, 0, 0, v, 0});
    }

    // ---------------------------------------------------------------- validation against the analytic exponent

    @Test
    void theAnalyticTargetIsWhatWeThinkItIs() {
        assertEquals(0.537285, TRUE_EXPONENT, 1e-6); // matches the independent numerical Floquet computation
    }

    @Test
    void slopeRecoversTheAnalyticExponentFromEveryStartingDirection() {
        for (long seed = 1; seed <= 4; seed++) {
            LyapunovResult r = lagrange(Rk4Integrator::new, 0.002, 0.5, 60, 1e-8, seed);
            assertTrue(r.completed(), r.failure());
            // measured over 8 seeds: 0.53727 .. 0.53729 (error <= 0.003 %)
            assertEquals(TRUE_EXPONENT, r.slope(15, 50), 1e-4, "seed " + seed);
        }
    }

    @Test
    void theRunningValueApproachesFromBelowLikeOneOverTAndExtrapolatesToTheTrueValue() {
        double l20 = lagrange(Rk4Integrator::new, 0.002, 0.5, 20, 1e-8, 1).exponent();
        double l60 = lagrange(Rk4Integrator::new, 0.002, 0.5, 60, 1e-8, 1).exponent();
        assertTrue(l20 < l60 && l60 < TRUE_EXPONENT, "running estimate should rise toward the limit: " + l20
                + ", " + l60); // measured 0.4946, 0.5230
        double extrapolated = (60 * l60 - 20 * l20) / 40; // eliminates a b/T term; measured 0.53716
        assertEquals(TRUE_EXPONENT, extrapolated, 1.5e-3);
    }

    @Test
    void theEstimateDoesNotDependOnTheOffsetSizeOrTheRenormalisationInterval() {
        // measured for offsets 1e-6, 1e-8, 1e-10: 0.5372852, 0.5372852, 0.5372849 (true 0.5372850)
        double[] values = {
            lagrange(Rk4Integrator::new, 0.002, 0.5, 60, 1e-6, 1).slope(15, 50),
            lagrange(Rk4Integrator::new, 0.002, 0.5, 60, 1e-10, 1).slope(15, 50),
            lagrange(Rk4Integrator::new, 0.002, 0.25, 60, 1e-8, 1).slope(15, 50),
            lagrange(Rk4Integrator::new, 0.002, 2.0, 60, 1e-8, 1).slope(15, 50)
        };
        for (double v : values) {
            assertEquals(TRUE_EXPONENT, v, 1e-5, Arrays.toString(values));
        }
    }

    @Test
    void limitationAnOffsetCloseToRoundingErrorDegradesTheEstimate() {
        // 1e-13 against coordinates of order one leaves about three significant digits in the offset.
        // measured error: 9e-4 (1e-12: 6e-5, 1e-10: 1e-7)
        double tiny = lagrange(Rk4Integrator::new, 0.002, 0.5, 60, 1e-13, 1).slope(15, 50);
        assertTrue(Math.abs(tiny - TRUE_EXPONENT) > 3e-4, "offset 1e-13 gave " + tiny);
    }

    @Test
    void differentWellResolvedIntegratorsAgree() {
        // measured: Verlet h=0.005 0.53728, RK4 h=0.01 0.53729
        assertEquals(TRUE_EXPONENT, lagrange(VelocityVerletIntegrator::new, 0.005, 0.5, 60, 1e-8, 1).slope(15, 50), 2e-4);
        assertEquals(TRUE_EXPONENT, lagrange(Rk4Integrator::new, 0.01, 0.5, 60, 1e-8, 1).slope(15, 50), 2e-4);
        assertEquals(TRUE_EXPONENT, lagrange(DormandPrince45Integrator::new, 0.02, 0.5, 60, 1e-8, 1).slope(15, 50), 2e-4);
    }

    // ---------------------------------------------------------------- limitations, demonstrated

    @Test
    void limitationEulerGivesAWrongExponentThatConvergesOnlyAtFirstOrderAndTheEnergyIndicatorSaysSo() {
        LyapunovResult coarse = lagrange(EulerIntegrator::new, 0.01, 0.5, 50, 1e-8, 1);
        LyapunovResult medium = lagrange(EulerIntegrator::new, 0.002, 0.5, 50, 1e-8, 1);
        LyapunovResult fine = lagrange(EulerIntegrator::new, 0.001, 0.5, 50, 1e-8, 1);
        double eCoarse = Math.abs(coarse.slope(15, 50) - TRUE_EXPONENT);
        double eMedium = Math.abs(medium.slope(15, 50) - TRUE_EXPONENT);
        double eFine = Math.abs(fine.slope(15, 50) - TRUE_EXPONENT);
        assertTrue(eCoarse > 0.1 * TRUE_EXPONENT, "Euler h=0.01 should be off by >10 %, was " + coarse.slope(15, 50));
        assertTrue(eFine < eMedium && eMedium < eCoarse, "error should fall with h");
        assertTrue(eMedium / eFine > 1.5 && eMedium / eFine < 2.5, "halving h should halve the error (first order): "
                + eMedium / eFine); // measured: 0.0509 / 0.0275 = 1.85
        // the trust indicator flags it; a good integrator does not trigger it (measured 0.285 versus 2.7e-11)
        assertTrue(coarse.maxRelativeEnergyDrift() > 0.1, "drift " + coarse.maxRelativeEnergyDrift());
        assertTrue(lagrange(Rk4Integrator::new, 0.01, 0.5, 50, 1e-8, 1).maxRelativeEnergyDrift() < 1e-9);
    }

    @Test
    void limitationTheExponentIsLostBeyondTheRoundingErrorHorizon() {
        // The reference orbit is unstable, so rounding error (1e-16) grows like exp(0.537 t) and reaches order one
        // near t = ln(1e16) / 0.537 = 69. Afterwards the "reference" is no longer the Lagrange orbit.
        LyapunovResult r = lagrange(Rk4Integrator::new, 0.002, 0.5, 120, 1e-8, 1);
        assertEquals(TRUE_EXPONENT, r.slope(20, 50), 1e-3);            // measured 0.5373
        double late = r.slope(80, 110);   // measured window slopes 0.19, 0.21, 0.25, 0.07: they depend on the rounding noise
        assertTrue(Math.abs(late - TRUE_EXPONENT) > 0.1, "late-time slope " + late + " should have lost the orbit");
    }

    @Test
    void aRegularOrbitGivesAnEstimateThatDecaysTowardZero() {
        State kepler = circularKepler();
        NBodySystem two = NBodySystem.normalized(1.0, 1.0);
        LyapunovResult t100 = run(two, kepler, Rk4Integrator::new, 0.005, 0.5, 100, 1e-8, 1);
        LyapunovResult t800 = run(two, kepler, Rk4Integrator::new, 0.005, 0.5, 800, 1e-8, 1);
        assertTrue(t800.exponent() < 0.012, "T=800: " + t800.exponent());       // measured 0.0096
        assertTrue(t800.exponent() < t100.exponent() / 4, t100.exponent() + " -> " + t800.exponent()); // 0.056 -> 0.0096
        assertTrue(t800.exponent() < 0.1 * TRUE_EXPONENT);
        assertTrue(t800.maxRelativeEnergyDrift() < 1e-8);                        // measured 5.6e-10
    }

    @Test
    void limitationOnceTheTriangleBreaksUpIntegratorsDisagreeAndTheEnergyIndicatorWarns() {
        State p = LagrangeTriangle.state(0.01);
        LyapunovResult rk30 = run(LAGRANGE, p, Rk4Integrator::new, 0.001, 0.5, 30, 1e-8, 1);
        LyapunovResult vl30 = run(LAGRANGE, p, VelocityVerletIntegrator::new, 0.001, 0.5, 30, 1e-8, 1);
        assertEquals(rk30.exponent(), vl30.exponent(), 1e-3);                    // measured 0.4349 and 0.4349
        assertTrue(rk30.exponent() > 0.3, "chaotic orbit should have a clearly positive exponent");
        assertTrue(rk30.maxRelativeEnergyDrift() < 1e-8);                        // measured 3.4e-11

        LyapunovResult rk100 = run(LAGRANGE, p, Rk4Integrator::new, 0.001, 0.5, 100, 1e-8, 1);
        LyapunovResult vl100 = run(LAGRANGE, p, VelocityVerletIntegrator::new, 0.001, 0.5, 100, 1e-8, 1);
        assertTrue(rk100.maxRelativeEnergyDrift() > 0.5, "drift " + rk100.maxRelativeEnergyDrift()); // measured 1.04
        assertTrue(Math.abs(rk100.exponent() - vl100.exponent()) > 0.05,
                "RK4 " + rk100.exponent() + " and Verlet " + vl100.exponent()
                        + " should disagree once close encounters have occurred"); // measured 0.434 vs 0.231
    }

    // ---------------------------------------------------------------- nearby-trajectory divergence

    @Test
    void divergenceOfTwoPlainTrajectoriesHasTheSameGrowthRateOnTheExactOrbit() {
        DivergenceResult d = ChaosIndicators.divergence(LAGRANGE, LagrangeTriangle.state(0),
                Rk4Integrator::new, 0.002, 20_000, 250, 1e-10, 1);
        assertEquals(null, d.failure());
        // realised, not nominal: adding 1e-10 to coordinates of order one loses digits (measured 9.999996e-11)
        assertEquals(1e-10, d.separation()[0], 1e-14);
        assertEquals(TRUE_EXPONENT, d.logSlope(10, 30), 1e-3);                   // measured 0.5373
    }

    @Test
    void divergenceOfTheBrokenUpTriangleGrowsByManyOrdersOfMagnitude() {
        DivergenceResult d = ChaosIndicators.divergence(LAGRANGE, LagrangeTriangle.state(0.01),
                Rk4Integrator::new, 0.001, 45_000, 500, 1e-8, 1);
        assertEquals(null, d.failure());
        double max = 0.0;
        for (double s : d.separation()) {
            max = Math.max(max, s);
        }
        assertTrue(max > 1e4 * 1e-8, "separation grew only to " + max);         // measured: 1.0e-1 by t = 40
        assertTrue(d.logSlope(5, 25) > 0.2, "growth rate " + d.logSlope(5, 25)); // measured 0.36
    }

    // ---------------------------------------------------------------- failures, reproducibility, validation

    @Test
    void aSingularityDuringTheRunIsReportedWithThePartialResultNotThrown() {
        State head = State.of(0, new double[] {-1, 0, 0, 1, 0, 0}, new double[] {1, 0, 0, -1, 0, 0});
        LyapunovResult r = ChaosIndicators.lyapunov(NBodySystem.normalized(1.0, 1.0), head, EulerIntegrator::new,
                1.0, 1, 10, 1e-8, 1);
        assertFalse(r.completed());
        assertTrue(r.failure().contains("Singular"), r.failure());
        assertEquals(0, r.renormalisations());
        assertTrue(Double.isNaN(r.exponent()));
        assertThrows(IllegalArgumentException.class, () -> r.slope(0, 10));
    }

    @Test
    void aSingularStartStateFailsFast() {
        State coincident = State.of(0, new double[] {1, 1, 1, 1, 1, 1}, new double[6]);
        assertThrows(NBodySystem.SingularConfigurationException.class, () -> ChaosIndicators.lyapunov(
                NBodySystem.normalized(1.0, 1.0), coincident, Rk4Integrator::new, 0.01, 10, 10, 1e-8, 1));
    }

    @Test
    void sameSeedGivesIdenticalResultsAndDifferentSeedsDoNot() {
        LyapunovResult a = lagrange(Rk4Integrator::new, 0.01, 0.5, 20, 1e-8, 7);
        LyapunovResult b = lagrange(Rk4Integrator::new, 0.01, 0.5, 20, 1e-8, 7);
        LyapunovResult c = lagrange(Rk4Integrator::new, 0.01, 0.5, 20, 1e-8, 8);
        assertTrue(Arrays.equals(a.runningExponent(), b.runningExponent()));
        assertTrue(Arrays.equals(a.separation(), b.separation()));
        assertFalse(Arrays.equals(a.separation(), c.separation()));
        assertTrue(a.toCsv().startsWith("time,separation,log_growth,running_exponent\n"));
        assertEquals(a.renormalisations() + 1, a.toCsv().split("\n").length);
    }

    @Test
    void invalidArgumentsAreRejected() {
        State s = LagrangeTriangle.state(0);
        Supplier<Integrator> f = Rk4Integrator::new;
        assertThrows(IllegalArgumentException.class, () -> ChaosIndicators.lyapunov(null, s, f, 0.01, 10, 10, 1e-8, 1));
        assertThrows(IllegalArgumentException.class, () -> ChaosIndicators.lyapunov(LAGRANGE, null, f, 0.01, 10, 10, 1e-8, 1));
        assertThrows(IllegalArgumentException.class, () -> ChaosIndicators.lyapunov(LAGRANGE, s, null, 0.01, 10, 10, 1e-8, 1));
        assertThrows(IllegalArgumentException.class, () -> ChaosIndicators.lyapunov(LAGRANGE, s, f, 0.0, 10, 10, 1e-8, 1));
        assertThrows(IllegalArgumentException.class, () -> ChaosIndicators.lyapunov(LAGRANGE, s, f, 0.01, 0, 10, 1e-8, 1));
        assertThrows(IllegalArgumentException.class, () -> ChaosIndicators.lyapunov(LAGRANGE, s, f, 0.01, 10, 0, 1e-8, 1));
        assertThrows(IllegalArgumentException.class, () -> ChaosIndicators.lyapunov(LAGRANGE, s, f, 0.01, 10, 10, 0.0, 1));
        assertThrows(IllegalArgumentException.class, () -> ChaosIndicators.lyapunov(LAGRANGE, s, f, 0.01, 10, 10, Double.NaN, 1));
        assertThrows(IllegalArgumentException.class, () -> ChaosIndicators.lyapunov(
                NBodySystem.normalized(1.0, 1.0), s, f, 0.01, 10, 10, 1e-8, 1)); // 2 bodies vs 3-body state
        assertThrows(IllegalArgumentException.class, () -> ChaosIndicators.divergence(LAGRANGE, s, f, 0.01, 0, 1, 1e-8, 1));
        assertThrows(IllegalArgumentException.class, () -> ChaosIndicators.divergence(LAGRANGE, s, f, 0.01, 10, 0, 1e-8, 1));
    }
}
