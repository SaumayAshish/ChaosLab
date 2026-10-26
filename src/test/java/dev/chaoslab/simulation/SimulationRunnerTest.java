package dev.chaoslab.simulation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.chaoslab.integration.EulerIntegrator;
import dev.chaoslab.integration.Integrator;
import dev.chaoslab.integration.NumericalFailureException;
import dev.chaoslab.integration.Rk4Integrator;
import dev.chaoslab.integration.VelocityVerletIntegrator;
import dev.chaoslab.physics.Diagnostics;
import dev.chaoslab.physics.NBodySystem;
import dev.chaoslab.physics.State;
import dev.chaoslab.simulation.Snapshot.Status;
import java.time.Duration;
import java.util.Random;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link SimulationRunner}.
 *
 * <p>The central property, checked repeatedly: whatever the pacing, pause pattern, speed changes or sampling rate,
 * the state at step N is bit-identical to what the integrator produces directly with
 * {@code Integrator.advance(system, initial, h, N)}. Failure interpretation: a mismatch there means the runner has
 * leaked wall-clock time into the physics, applied a step twice, lost one, or kept a stale cache across a reset. A
 * timing-related failure of {@link #neverRunsAheadOfTheRequestedSpeed()} means the pacing arithmetic is wrong; the
 * assertions that involve waiting only check things that must hold regardless of how slow the machine is.
 */
class SimulationRunnerTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final double H = 0.01;

    private static NBodySystem system() {
        return NBodySystem.normalized(1.0, 1.0);
    }

    private static State initial() {
        double v = 0.8 * Math.sqrt(0.5); // eccentric bound orbit
        return State.of(0.0, new double[] {-0.5, 0, 0, 0.5, 0, 0}, new double[] {0, -v, 0, 0, v, 0});
    }

    private static SimulationConfig config(Supplier<Integrator> factory, long maxSteps, double speed) {
        return new SimulationConfig(system(), initial(), factory, H, maxSteps, speed);
    }

    private static State reference(Supplier<Integrator> factory, long steps) {
        return factory.get().advance(system(), initial(), H, (int) steps);
    }

    private static Snapshot awaitOrFail(SimulationRunner runner, Predicate<Snapshot> condition, String what)
            throws InterruptedException {
        return runner.await(condition, TIMEOUT)
                .orElseThrow(() -> new AssertionError("timed out waiting for: " + what + "; last snapshot "
                        + runner.snapshot()));
    }

    /** Integrates like RK4 but throws a synthetic failure (after corrupting the arrays) on call number failAt. */
    private static final class FailingIntegrator implements Integrator {
        private final Rk4Integrator real = new Rk4Integrator();
        private final int failAt;
        private int calls;

        FailingIntegrator(int failAt) {
            this.failAt = failAt;
        }

        @Override public String name() { return "Failing RK4"; }
        @Override public int order() { return 4; }
        @Override public boolean isSymplectic() { return false; }

        @Override
        public void step(NBodySystem sys, double[] q, double[] v, double h) {
            if (++calls == failAt) {
                q[0] = Double.NaN; // a real failure can leave the arrays half-written
                throw new NumericalFailureException("synthetic failure");
            }
            real.step(sys, q, v, h);
        }
    }

    // ---------------------------------------------------------------- initial state and stepping

    @Test
    void initialSnapshotIsReadyAndConsistent() {
        try (SimulationRunner runner = new SimulationRunner(config(Rk4Integrator::new, 1000, 1.0))) {
            Snapshot s = runner.snapshot();
            assertEquals(Status.READY, s.status());
            assertEquals(0, s.sequence());
            assertEquals(0, s.stepCount());
            assertEquals(initial(), s.state());
            assertEquals(0.0, s.drift().relativeEnergy(), 0.0);
            assertEquals("RK4", s.integratorName());
            assertEquals(H, s.stepSize(), 0.0);
            assertEquals(1.0, s.speed(), 0.0);
            assertEquals(null, s.failure());
        }
    }

    @Test
    void stepAdvancesExactlyAndMatchesDirectIntegration() throws InterruptedException {
        try (SimulationRunner runner = new SimulationRunner(config(Rk4Integrator::new, 1000, 1.0))) {
            runner.step(10);
            Snapshot s = awaitOrFail(runner, x -> x.stepCount() == 10 && x.status() == Status.PAUSED, "10 steps");
            assertEquals(reference(Rk4Integrator::new, 10), s.state());
            assertEquals(10 * H, s.state().time(), 0.0);
            assertEquals(Diagnostics.totalEnergy(system(), s.state()), s.invariants().totalEnergy(), 0.0);
        }
    }

    @Test
    void stepBeyondMaxStepsClampsToFinishedAndFurtherCommandsAreIgnored() throws InterruptedException {
        try (SimulationRunner runner = new SimulationRunner(config(Rk4Integrator::new, 25, 1.0))) {
            runner.step(100);
            Snapshot s = awaitOrFail(runner, x -> x.status() == Status.FINISHED, "FINISHED");
            assertEquals(25, s.stepCount());
            assertEquals(reference(Rk4Integrator::new, 25), s.state());
            runner.step(5);
            runner.run();
            Thread.sleep(100);
            assertEquals(25, runner.snapshot().stepCount());
            assertEquals(Status.FINISHED, runner.snapshot().status());
        }
    }

    // ---------------------------------------------------------------- time independent of pacing

    @Test
    void runToCompletionIsBitIdenticalToDirectIntegrationRegardlessOfSpeed() throws InterruptedException {
        State expected = reference(Rk4Integrator::new, 3000);
        for (double speed : new double[] {1e9, 4000.0}) { // effectively instant, and ~400k steps per second
            try (SimulationRunner runner = new SimulationRunner(config(Rk4Integrator::new, 3000, speed))) {
                runner.run();
                Snapshot s = awaitOrFail(runner, x -> x.status() == Status.FINISHED, "FINISHED at speed " + speed);
                assertEquals(3000, s.stepCount());
                assertEquals(expected, s.state(), "state differs at speed " + speed);
            }
        }
    }

    @Test
    void neverRunsAheadOfTheRequestedSpeed() throws InterruptedException {
        double speed = 5.0; // simulation time units per second: 500 steps per second at h = 0.01
        try (SimulationRunner runner = new SimulationRunner(config(Rk4Integrator::new, SimulationConfig.UNBOUNDED, speed))) {
            long start = System.nanoTime();
            runner.run();
            Thread.sleep(300);
            runner.pause();
            Snapshot s = awaitOrFail(runner, x -> x.status() == Status.PAUSED, "PAUSED");
            double elapsed = (System.nanoTime() - start) / 1e9; // always >= the worker's own elapsed time
            assertTrue(s.stepCount() * H <= speed * elapsed + H,
                    "simulated " + s.stepCount() * H + " time units in " + elapsed + " s at speed " + speed);
            assertTrue(s.stepCount() > 10, "no meaningful progress: " + s.stepCount() + " steps");
        }
    }

    @Test
    void changingSpeedIsPublishedAndDoesNotAlterTheTrajectory() throws InterruptedException {
        try (SimulationRunner runner = new SimulationRunner(config(Rk4Integrator::new, 2000, 100.0))) {
            runner.run();
            awaitOrFail(runner, x -> x.stepCount() > 20, "some progress");
            runner.setSpeed(1e9);
            awaitOrFail(runner, x -> x.speed() == 1e9, "speed change");
            Snapshot s = awaitOrFail(runner, x -> x.status() == Status.FINISHED, "FINISHED");
            assertEquals(reference(Rk4Integrator::new, 2000), s.state());
        }
    }

    // ---------------------------------------------------------------- pause, resume, reset

    @Test
    void pauseStopsProgressAndResumeContinuesWithoutCorruption() throws InterruptedException {
        try (SimulationRunner runner = new SimulationRunner(config(VelocityVerletIntegrator::new,
                SimulationConfig.UNBOUNDED, 100.0))) {
            runner.run();
            awaitOrFail(runner, x -> x.stepCount() >= 50, "50 steps");
            runner.pause();
            Snapshot paused = awaitOrFail(runner, x -> x.status() == Status.PAUSED, "PAUSED");
            Thread.sleep(150);
            assertEquals(paused.stepCount(), runner.snapshot().stepCount()); // nothing moved while paused
            assertEquals(Status.PAUSED, runner.snapshot().status());

            runner.run();
            awaitOrFail(runner, x -> x.status() == Status.RUNNING && x.stepCount() >= paused.stepCount() + 50,
                    "progress after resume");
            runner.pause();
            Snapshot again = awaitOrFail(runner, x -> x.status() == Status.PAUSED
                    && x.stepCount() > paused.stepCount(), "PAUSED again");
            assertEquals(reference(VelocityVerletIntegrator::new, again.stepCount()), again.state());
        }
    }

    @Test
    void resetRestoresTheInitialStateAndTheRunIsReproducible() throws InterruptedException {
        try (SimulationRunner runner = new SimulationRunner(config(VelocityVerletIntegrator::new, 500, 1e9))) {
            runner.run();
            Snapshot first = awaitOrFail(runner, x -> x.status() == Status.FINISHED, "first FINISHED");
            runner.reset();
            Snapshot ready = awaitOrFail(runner, x -> x.status() == Status.READY, "READY after reset");
            assertEquals(0, ready.stepCount());
            assertEquals(initial(), ready.state());
            assertEquals(0.0, ready.drift().relativeEnergy(), 0.0);

            runner.run();
            Snapshot second = awaitOrFail(runner, x -> x.status() == Status.FINISHED, "second FINISHED");
            assertEquals(first.state(), second.state());
            assertEquals(first.invariants(), second.invariants());
        }
    }

    // ---------------------------------------------------------------- failures

    @Test
    void aNumericalFailureKeepsTheLastValidStateAndReportsTheCause() throws InterruptedException {
        try (SimulationRunner runner = new SimulationRunner(config(() -> new FailingIntegrator(5), 1000, 1e9))) {
            runner.run();
            Snapshot s = awaitOrFail(runner, x -> x.status() == Status.FAILED, "FAILED");
            assertEquals(4, s.stepCount());
            assertEquals(reference(Rk4Integrator::new, 4), s.state()); // the half-written NaN was rolled back
            assertTrue(s.failure().contains("synthetic failure"), s.failure());
            assertTrue(s.failure().contains("Step 5"), s.failure());
        }
    }

    @Test
    void aFailedRunnerIgnoresRunAndStepUntilItIsReset() throws InterruptedException {
        try (SimulationRunner runner = new SimulationRunner(config(() -> new FailingIntegrator(5), 1000, 1e9))) {
            runner.run();
            awaitOrFail(runner, x -> x.status() == Status.FAILED, "FAILED");
            runner.run();
            runner.step(3);
            Thread.sleep(100);
            assertEquals(Status.FAILED, runner.snapshot().status());
            assertEquals(4, runner.snapshot().stepCount());

            runner.reset();
            Snapshot ready = awaitOrFail(runner, x -> x.status() == Status.READY, "READY after reset");
            assertEquals(0, ready.stepCount());
            assertEquals(null, ready.failure());
        }
    }

    @Test
    void aStepIntoAnExactSingularityRollsBackToTheLastMeasurableState() throws InterruptedException {
        // Two unit masses at x = -1 and +1 moving toward each other at speed 1 with h = 1: forward Euler moves both
        // to the origin in one step. The step itself is finite, but the new configuration is singular.
        State headOn = State.of(0, new double[] {-1, 0, 0, 1, 0, 0}, new double[] {1, 0, 0, -1, 0, 0});
        SimulationConfig cfg = new SimulationConfig(system(), headOn, EulerIntegrator::new, 1.0, 100, 1.0);
        try (SimulationRunner runner = new SimulationRunner(cfg)) {
            runner.step(1);
            Snapshot s = awaitOrFail(runner, x -> x.status() == Status.FAILED, "FAILED");
            assertEquals(0, s.stepCount());
            assertEquals(headOn, s.state());
            assertTrue(s.failure().contains("Singular"), s.failure());
            assertTrue(s.failure().contains("rolled back"), s.failure());
        }
    }

    // ---------------------------------------------------------------- consistency under concurrency

    @Test
    void snapshotsAreNeverTornAndSequenceNumbersOnlyIncrease() throws InterruptedException {
        try (SimulationRunner runner = new SimulationRunner(config(Rk4Integrator::new, SimulationConfig.UNBOUNDED, 50.0))) {
            runner.run();
            long lastSequence = -1;
            long deadline = System.nanoTime() + 300_000_000L;
            int observed = 0;
            while (System.nanoTime() < deadline) {
                Snapshot s = runner.snapshot();
                assertTrue(s.sequence() >= lastSequence, "sequence went backward");
                lastSequence = s.sequence();
                // All parts of one snapshot describe the same instant.
                assertEquals(s.stepCount() * H, s.state().time(), 0.0);
                assertEquals(Diagnostics.totalEnergy(system(), s.state()), s.invariants().totalEnergy(), 0.0);
                assertEquals(s.invariants().time(), s.state().time(), 0.0);
                observed++;
            }
            assertTrue(observed > 100, "polled only " + observed + " times");
        }
    }

    @Test
    void randomControlSequencesNeverBreakDeterminism() throws InterruptedException {
        // Whatever the user does (run, pause, step, change speed, reset) in any order, every snapshot that is ever
        // observed must equal direct integration to the same step count. Verlet is used on purpose: it keeps a
        // cached acceleration that would expose any stale-state bug across pauses, steps and resets.
        Random rng = new Random(20261021L);
        try (SimulationRunner runner = new SimulationRunner(config(VelocityVerletIntegrator::new, 200_000, 50.0))) {
            long lastSequence = -1;
            for (int i = 0; i < 250; i++) {
                switch (rng.nextInt(6)) {
                    case 0 -> runner.run();
                    case 1 -> runner.pause();
                    case 2 -> runner.step(1 + rng.nextInt(5));
                    case 3 -> runner.setSpeed(1.0 + rng.nextInt(20_000));
                    case 4 -> {
                        if (rng.nextInt(4) == 0) {
                            runner.reset();
                        }
                    }
                    default -> { }
                }
                Thread.sleep(rng.nextInt(3));
                Snapshot s = runner.snapshot();
                assertTrue(s.sequence() >= lastSequence);
                lastSequence = s.sequence();
                assertEquals(reference(VelocityVerletIntegrator::new, s.stepCount()), s.state(),
                        "snapshot at step " + s.stepCount() + " (" + s.status() + ") differs from direct integration");
            }
            runner.pause();
            Snapshot end = awaitOrFail(runner, x -> x.status() != Status.RUNNING, "not running");
            assertEquals(reference(VelocityVerletIntegrator::new, end.stepCount()), end.state());
        }
    }

    // ---------------------------------------------------------------- lifecycle and validation

    @Test
    void closeStopsTheWorkerPublishesClosedAndIgnoresLaterCommands() throws InterruptedException {
        SimulationRunner runner = new SimulationRunner(config(Rk4Integrator::new, SimulationConfig.UNBOUNDED, 100.0));
        runner.run();
        awaitOrFail(runner, x -> x.stepCount() > 5, "some progress");
        runner.close();
        Snapshot closed = runner.snapshot();
        assertEquals(Status.CLOSED, closed.status());
        runner.run();
        runner.step(10);
        runner.reset();
        Thread.sleep(100);
        assertEquals(closed, runner.snapshot());
        runner.close(); // idempotent
    }

    @Test
    void invalidArgumentsAreRejected() {
        NBodySystem sys = system();
        State s = initial();
        Supplier<Integrator> f = Rk4Integrator::new;
        assertThrows(IllegalArgumentException.class, () -> new SimulationConfig(null, s, f, H, 10, 1.0));
        assertThrows(IllegalArgumentException.class, () -> new SimulationConfig(sys, null, f, H, 10, 1.0));
        assertThrows(IllegalArgumentException.class, () -> new SimulationConfig(sys, s, null, H, 10, 1.0));
        assertThrows(IllegalArgumentException.class, () -> new SimulationConfig(sys, s, f, 0.0, 10, 1.0));
        assertThrows(IllegalArgumentException.class, () -> new SimulationConfig(sys, s, f, Double.NaN, 10, 1.0));
        assertThrows(IllegalArgumentException.class, () -> new SimulationConfig(sys, s, f, H, 0, 1.0));
        assertThrows(IllegalArgumentException.class, () -> new SimulationConfig(sys, s, f, H, 10, 0.0));
        assertThrows(IllegalArgumentException.class,
                () -> new SimulationConfig(NBodySystem.normalized(1.0, 1.0, 1.0), s, f, H, 10, 1.0));
        try (SimulationRunner runner = new SimulationRunner(config(f, 10, 1.0))) {
            assertThrows(IllegalArgumentException.class, () -> runner.step(0));
            assertThrows(IllegalArgumentException.class, () -> runner.setSpeed(0.0));
            assertThrows(IllegalArgumentException.class, () -> runner.setSpeed(Double.POSITIVE_INFINITY));
        }
    }

    @Test
    void aSingularInitialStateFailsFastWithItsRealCause() {
        State coincident = State.of(0, new double[] {1, 1, 1, 1, 1, 1}, new double[6]);
        SimulationConfig cfg = new SimulationConfig(system(), coincident, Rk4Integrator::new, H, 10, 1.0);
        assertThrows(NBodySystem.SingularConfigurationException.class, () -> new SimulationRunner(cfg));
    }
}
