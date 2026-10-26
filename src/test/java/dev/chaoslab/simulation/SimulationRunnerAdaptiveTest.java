package dev.chaoslab.simulation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.chaoslab.experiments.LagrangeTriangle;
import dev.chaoslab.integration.DormandPrince45Integrator;
import dev.chaoslab.integration.DormandPrince45Integrator.Tolerances;
import dev.chaoslab.integration.Integrator;
import dev.chaoslab.integration.Rk4Integrator;
import dev.chaoslab.integration.VelocityVerletIntegrator;
import dev.chaoslab.physics.NBodySystem;
import dev.chaoslab.physics.State;
import dev.chaoslab.simulation.Snapshot.Status;
import java.time.Duration;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

/**
 * Tests for the adaptive-step mode of {@link SimulationRunner}. As for fixed steps, the central property is that the
 * state after N steps is bit-identical to what direct stepping produces, whatever the pacing. The scenario tests
 * record the finding that motivated the mode: the perturbed Lagrange triangle breaks up with a close encounter that a
 * fixed-step Verlet run cannot resolve (energy error of order one) while the adaptive run conserves energy to 1e-8.
 */
class SimulationRunnerAdaptiveTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final double H0 = 0.01;
    private static final Tolerances TOL = new Tolerances(1e-10, 1e-10);

    private static NBodySystem two() {
        return NBodySystem.normalized(1.0, 1.0);
    }

    private static State eccentric() {
        double v = 0.5 * Math.sqrt(0.5);
        return State.of(0.0, new double[] {-0.5, 0, 0, 0.5, 0, 0}, new double[] {0, -v, 0, 0, v, 0});
    }

    private static SimulationConfig adaptiveConfig(long maxSteps, double speed) {
        return SimulationConfig.adaptive(two(), eccentric(), DormandPrince45Integrator::new, H0, TOL, maxSteps, speed);
    }

    /** Direct stepping with the same integrator, accumulating time in the same order as the runner. */
    private static State reference(long steps) {
        DormandPrince45Integrator dp = new DormandPrince45Integrator();
        State s = eccentric();
        double[] q = s.positions();
        double[] v = s.velocities();
        double t = s.time();
        for (long k = 0; k < steps; k++) {
            t += dp.adaptiveStep(two(), q, v, t, H0, TOL).stepSize();
        }
        return State.of(t, q, v);
    }

    private static Snapshot awaitOrFail(SimulationRunner runner, Predicate<Snapshot> condition, String what)
            throws InterruptedException {
        return runner.await(condition, TIMEOUT)
                .orElseThrow(() -> new AssertionError("timed out waiting for: " + what + "; last snapshot "
                        + runner.snapshot()));
    }

    @Test
    void adaptiveModeRequiresADormandPrinceIntegrator() {
        SimulationConfig config = SimulationConfig.adaptive(two(), eccentric(), Rk4Integrator::new, H0, TOL, 10, 1.0);
        assertThrows(IllegalArgumentException.class, () -> new SimulationRunner(config));
        assertThrows(IllegalArgumentException.class, () -> SimulationConfig.adaptive(two(), eccentric(),
                DormandPrince45Integrator::new, H0, null, 10, 1.0));
    }

    @Test
    void theInitialSnapshotReportsAdaptiveModeAndTheFirstTrialStep() {
        try (SimulationRunner runner = new SimulationRunner(adaptiveConfig(100, 1.0))) {
            Snapshot s = runner.snapshot();
            assertEquals(Status.READY, s.status());
            assertEquals("Dormand-Prince 5(4), adaptive", s.integratorName());
            assertEquals(H0, s.stepSize(), 0.0);
            assertEquals(eccentric(), s.state());
        }
    }

    @Test
    void stepTakesExactlyNAdaptiveStepsAndMatchesDirectStepping() throws InterruptedException {
        try (SimulationRunner runner = new SimulationRunner(adaptiveConfig(1000, 1.0))) {
            runner.step(37);
            Snapshot s = awaitOrFail(runner, x -> x.stepCount() == 37 && x.status() == Status.PAUSED, "37 steps");
            assertEquals(reference(37), s.state());
            assertTrue(s.state().time() > 0.0);
            assertTrue(s.stepSize() > 0.0 && s.stepSize() != H0, "reported step should be the last accepted step");
        }
    }

    @Test
    void runToCompletionIsBitIdenticalToDirectSteppingRegardlessOfSpeed() throws InterruptedException {
        State expected = reference(2000);
        for (double speed : new double[] {1e9, 150.0}) { // effectively instant, and a paced run of a few seconds
            try (SimulationRunner runner = new SimulationRunner(adaptiveConfig(2000, speed))) {
                runner.run();
                Snapshot s = awaitOrFail(runner, x -> x.status() == Status.FINISHED, "FINISHED at speed " + speed);
                assertEquals(2000, s.stepCount());
                assertEquals(expected, s.state(), "state differs at speed " + speed);
            }
        }
    }

    @Test
    void aPacedRunNeverMovesFarAheadOfTheRequestedSpeed() throws InterruptedException {
        try (SimulationRunner runner = new SimulationRunner(adaptiveConfig(SimulationConfig.UNBOUNDED, 5.0))) {
            long start = System.nanoTime();
            runner.run();
            Thread.sleep(600);
            runner.pause();
            Snapshot s = awaitOrFail(runner, x -> x.status() == Status.PAUSED, "PAUSED");
            double wall = (System.nanoTime() - start) / 1e9;
            // one step may overshoot the target; steps here are well below 1 time unit
            assertTrue(s.state().time() <= 5.0 * wall + 1.0, "t = " + s.state().time() + " after " + wall + " s");
            assertTrue(s.state().time() > 0.0);
            assertEquals(reference(s.stepCount()), s.state()); // and whatever it reached is exactly reproducible
        }
    }

    @Test
    void resetRestartsTheStepControllerSoTheRunIsReproducible() throws InterruptedException {
        try (SimulationRunner runner = new SimulationRunner(adaptiveConfig(1000, 1.0))) {
            runner.step(200);
            Snapshot first = awaitOrFail(runner, x -> x.stepCount() == 200 && x.status() == Status.PAUSED, "200");
            runner.reset();
            Snapshot ready = awaitOrFail(runner, x -> x.status() == Status.READY, "READY");
            assertEquals(eccentric(), ready.state());
            assertEquals(H0, ready.stepSize(), 0.0);
            runner.step(200);
            Snapshot again = awaitOrFail(runner, x -> x.stepCount() == 200 && x.status() == Status.PAUSED, "200 again");
            assertEquals(first.state(), again.state());
        }
    }

    // ---------------------------------------------------------------- the motivating scenario

    private static Snapshot runTriangle(SimulationConfig config, String what) throws InterruptedException {
        try (SimulationRunner runner = new SimulationRunner(config)) {
            runner.run();
            return awaitOrFail(runner, x -> x.status() == Status.FINISHED || x.status() == Status.FAILED, what);
        }
    }

    @Test
    void adaptiveSteppingResolvesTheCloseEncounterThatBreaksFixedStepVerlet() throws InterruptedException {
        NBodySystem system = LagrangeTriangle.system();
        State start = LagrangeTriangle.state(0.01);

        // fixed step h = 0.005 up to t = 80: measured relative energy error 3.79
        Snapshot fixed = runTriangle(new SimulationConfig(system, start, VelocityVerletIntegrator::new, 0.005,
                16_000, 1e9), "fixed-step Verlet");
        assertEquals(Status.FINISHED, fixed.status());
        assertTrue(Math.abs(fixed.drift().relativeEnergy()) > 1.0, "drift " + fixed.drift().relativeEnergy());

        // adaptive, tolerance 1e-10: measured 5188 steps to t = 100 and a worst energy error of 8.8e-9
        Snapshot adaptive = runTriangle(SimulationConfig.adaptive(system, start, DormandPrince45Integrator::new,
                0.005, TOL, 5000, 1e9), "adaptive Dormand-Prince");
        assertEquals(Status.FINISHED, adaptive.status(), adaptive.failure());
        assertTrue(adaptive.state().time() > 80.0, "reached only t = " + adaptive.state().time());
        assertTrue(Math.abs(adaptive.drift().relativeEnergy()) < 1e-7, "drift " + adaptive.drift().relativeEnergy());
        assertTrue(adaptive.drift().relativeAngularMomentum() < 1e-7);
    }

    @Test
    void aCollisionEndsInAFailedRunWithAnExplanationNotAHangOrANonFiniteState() throws InterruptedException {
        // two equal masses released at rest fall straight into each other
        State headOn = State.of(0.0, new double[] {-0.5, 0, 0, 0.5, 0, 0}, new double[6]);
        SimulationConfig config = SimulationConfig.adaptive(two(), headOn, DormandPrince45Integrator::new, 0.01,
                TOL, SimulationConfig.UNBOUNDED, 1e9);
        Snapshot s = runTriangleLike(config);
        assertEquals(Status.FAILED, s.status());
        assertTrue(s.failure() != null && !s.failure().isEmpty());
        for (double x : s.state().positions()) {
            assertTrue(Double.isFinite(x));
        }
        assertTrue(Double.isFinite(s.invariants().totalEnergy()));
        assertTrue(s.state().time() > 0.0, "should have fallen for a while before failing");
    }

    private static Snapshot runTriangleLike(SimulationConfig config) throws InterruptedException {
        return runTriangle(config, "collision to end in FAILED or FINISHED");
    }
}
