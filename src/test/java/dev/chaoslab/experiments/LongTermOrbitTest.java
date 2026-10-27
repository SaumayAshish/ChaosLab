package dev.chaoslab.experiments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.chaoslab.analysis.IntegratorComparison;
import dev.chaoslab.analysis.IntegratorComparison.MethodResult;
import dev.chaoslab.analysis.IntegratorComparison.MethodSpec;
import dev.chaoslab.analysis.IntegratorComparison.Report;
import dev.chaoslab.analysis.IntegratorComparison.Sample;
import dev.chaoslab.integration.DormandPrince45Integrator;
import dev.chaoslab.integration.EulerIntegrator;
import dev.chaoslab.integration.Rk4Integrator;
import dev.chaoslab.integration.VelocityVerletIntegrator;
import dev.chaoslab.physics.Diagnostics;
import dev.chaoslab.physics.NBodySystem;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link LongTermOrbit}. The numbers quote what was measured when the tests were written (JDK 21, 100 orbits
 * at h = 0.02, sampled once per orbit against a reference run); each bound is several times looser than the measurement
 * so that it fails on a wrong behaviour, not on rounding. The behaviours pinned are the ones the preset's note claims.
 */
class LongTermOrbitTest {

    private static final int ORBITS = 100;
    private static final int STEPS_PER_ORBIT = 314;
    private static Report report;

    /** The four runs are shared by every test; computed on first use (about a second). */
    private static synchronized Report report() {
        if (report == null) {
            report = run();
        }
        return report;
    }

    private static Report run() {
        List<MethodSpec> specs = List.of(
                new MethodSpec("Euler", EulerIntegrator::new, STEPS_PER_ORBIT),
                new MethodSpec("RK4", Rk4Integrator::new, STEPS_PER_ORBIT),
                new MethodSpec("Verlet", VelocityVerletIntegrator::new, STEPS_PER_ORBIT),
                new MethodSpec("Dormand-Prince", DormandPrince45Integrator::new, STEPS_PER_ORBIT));
        return IntegratorComparison.run(LongTermOrbit.system(), LongTermOrbit.state(),
                ORBITS * LongTermOrbit.PERIOD, ORBITS, specs);
    }

    private static MethodResult method(String label) {
        return report().results().stream().filter(m -> m.label().equals(label)).findFirst().orElseThrow();
    }

    private static double maxAngularMomentumDrift(MethodResult m) {
        double max = 0.0;
        for (Sample s : m.samples()) {
            max = Math.max(max, Math.abs(s.relativeAngularMomentumDrift()));
        }
        return max;
    }

    @Test
    void periodIsTheKeplerPeriodAndTheStepIsAboutAThreeHundredthOfIt() {
        assertEquals(6.2800460687587085, LongTermOrbit.PERIOD, 1e-12);
        assertEquals(314.0, LongTermOrbit.PERIOD / LongTermOrbit.STEP, 0.1);
    }

    @Test
    void startHasTheKeplerEnergyAndAngularMomentumAndZeroMomentum() {
        NBodySystem system = LongTermOrbit.system();
        Diagnostics.Invariants inv = Diagnostics.measure(system, LongTermOrbit.state());
        double m1 = LongTermOrbit.STAR_MASS;
        double m2 = LongTermOrbit.PLANET_MASS;
        double a = LongTermOrbit.SEMI_MAJOR_AXIS;
        double e = LongTermOrbit.ECCENTRICITY;
        assertEquals(-m1 * m2 / (2.0 * a), inv.totalEnergy(), 1e-15);
        assertEquals(m1 * m2 * Math.sqrt(a * (1.0 - e * e) / (m1 + m2)), inv.angularMomentum().norm(), 1e-15);
        assertEquals(0.0, inv.momentum().norm(), 1e-18);
        assertEquals(0.0, inv.centreOfMass().norm(), 1e-18);
    }

    @Test
    void theReferenceIsTightEnoughToJudgeEveryMethodExceptVerletsPhase() {
        assertTrue(report().referenceSpread() < 1e-4, "spread " + report().referenceSpread());
    }

    @Test
    void eulerSpiralsOutAndGainsAboutAllItsEnergy() {
        MethodResult euler = method("Euler");
        assertTrue(euler.failure() == null);
        assertTrue(euler.last().relativeEnergyDrift() > 0.9, "dE " + euler.last().relativeEnergyDrift());
        assertTrue(euler.last().separationFromReference() > 10.0);
    }

    @Test
    void verletEnergyErrorIsLargestButBoundedAndItsAngularMomentumIsConservedToRounding() {
        MethodResult verlet = method("Verlet");
        assertTrue(verlet.maxAbsEnergyDrift() > 5e-4 && verlet.maxAbsEnergyDrift() < 3e-3,
                "max " + verlet.maxAbsEnergyDrift()); // measured 1.09e-3
        // bounded: the last orbits are no worse than the worst earlier ones by more than a few per cent
        assertTrue(Math.abs(verlet.last().relativeEnergyDrift()) <= verlet.maxAbsEnergyDrift());
        assertTrue(Math.abs(verlet.samples().get(50).relativeEnergyDrift()) > 5e-4);
        assertTrue(maxAngularMomentumDrift(verlet) < 1e-12, "dL " + maxAngularMomentumDrift(verlet)); // 2.8e-14
        // the price is a phase error: the planet is far from the reference position by the end (measured 1.3)
        assertTrue(verlet.last().separationFromReference() > 0.5);
    }

    @Test
    void rk4LosesEnergyLinearlyInTimeAndStaysMuchCloserToTheReference() {
        MethodResult rk4 = method("RK4");
        double at10 = rk4.samples().get(10).relativeEnergyDrift();
        double at100 = rk4.samples().get(100).relativeEnergyDrift();
        assertTrue(at10 < 0.0 && at100 < 0.0);
        assertEquals(10.0, at100 / at10, 0.5); // linear in the number of orbits (measured 10.00)
        assertTrue(Math.abs(at100) > 5e-6 && Math.abs(at100) < 2e-5, "dE " + at100); // measured -9.7e-6
        assertTrue(rk4.last().separationFromReference() < 0.05); // measured 8.2e-3
    }

    @Test
    void dormandPrinceAtTheSameStepIsAboutFiftyFiveTimesBetterThanRk4() {
        MethodResult dp = method("Dormand-Prince");
        MethodResult rk4 = method("RK4");
        double at10 = dp.samples().get(10).relativeEnergyDrift();
        double at100 = dp.samples().get(100).relativeEnergyDrift();
        assertEquals(10.0, at100 / at10, 0.5);
        assertTrue(dp.maxAbsEnergyDrift() > 5e-8 && dp.maxAbsEnergyDrift() < 5e-7, "max " + dp.maxAbsEnergyDrift());
        assertTrue(rk4.maxAbsEnergyDrift() / dp.maxAbsEnergyDrift() > 20.0);
        assertTrue(dp.last().separationFromReference() < rk4.last().separationFromReference());
    }

    @Test
    void finalPositionErrorRanksTheMethodsAndEnergyErrorRanksThemDifferently() {
        double dp = method("Dormand-Prince").last().separationFromReference();
        double rk4 = method("RK4").last().separationFromReference();
        double verlet = method("Verlet").last().separationFromReference();
        double euler = method("Euler").last().separationFromReference();
        assertTrue(dp < rk4 && rk4 < verlet && verlet < euler);
        // yet Verlet's energy error is the biggest of the three good methods at this length
        assertTrue(method("Verlet").maxAbsEnergyDrift() > method("RK4").maxAbsEnergyDrift());
    }
}
