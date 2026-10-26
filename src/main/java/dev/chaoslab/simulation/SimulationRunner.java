package dev.chaoslab.simulation;

import dev.chaoslab.integration.DormandPrince45Integrator;
import dev.chaoslab.integration.Integrator;
import dev.chaoslab.physics.Diagnostics;
import dev.chaoslab.physics.NBodySystem;
import dev.chaoslab.physics.State;
import dev.chaoslab.simulation.Snapshot.Status;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;

/**
 * Runs a simulation on its own thread and publishes immutable {@link Snapshot}s for the user interface.
 *
 * <h2>Threading model</h2>
 * All mutable simulation data (positions, velocities, integrator, counters) is confined to one worker thread.
 * Callers never touch it: control methods ({@link #run}, {@link #pause}, {@link #step}, {@link #reset},
 * {@link #setSpeed}) enqueue a command that the worker applies in order, and {@link #snapshot()} returns the most
 * recently published immutable snapshot. There are no locks on the stepping path and no shared mutable state, so a
 * UI thread can poll {@link #snapshot()} at any rate (for example once per rendered frame) without affecting the
 * physics.
 *
 * <h2>Time is independent of rendering</h2>
 * Simulated time is {@code t0 + stepCount * stepSize}. The wall clock only decides <em>how many</em> steps are due
 * (speed in simulation time units per second); it never changes the step size or which states are visited. Whatever
 * the pacing, pause pattern or snapshot sampling rate, the state after N steps is bit-identical.
 *
 * <p>In adaptive mode (see {@link SimulationConfig}) the step sizes vary, so time is the running sum of the accepted
 * step sizes. That sum is accumulated in the same order every time, and the step sequence depends only on the state,
 * so the state after N steps is still bit-identical whatever the pacing. The wall clock decides only how many steps
 * are taken; a step is never shortened to land on a requested time, so the simulated time may run ahead of the
 * requested speed by at most one step.
 *
 * <h2>Pacing and falling behind</h2>
 * The runner never runs ahead of the requested speed. If the CPU cannot keep up, it drops the backlog instead of
 * trying to catch up, so the achieved speed is lower than requested rather than the application freezing.
 *
 * <h2>Failures</h2>
 * A step that throws leaves the state at the last valid step and the runner in {@link Status#FAILED} with an
 * explanation. If a step produces a finite but singular configuration (for example two bodies exactly coincident),
 * that is detected when the next snapshot is measured and the runner rolls back to the last measurable snapshot,
 * which may be a few steps earlier. {@link #reset()} recovers from a failure.
 *
 * <p>Commands after {@link #close()} are ignored.
 */
public final class SimulationRunner implements AutoCloseable {

    private static final long BATCH_BUDGET_NANOS = 8_000_000L;      // longest uninterrupted stepping burst
    private static final long PUBLISH_INTERVAL_NANOS = 8_000_000L;  // about 120 snapshots per second at most
    private static final long PACING_PARK_NANOS = 1_000_000L;       // pause between pacing checks
    private static final double MAX_DUE_STEPS = 1e15;               // saturates absurd speeds

    private final NBodySystem system;
    private final State initialState;
    private final Diagnostics.Invariants initialInvariants;
    private final SimulationConfig config;
    private final double h;
    private final double t0;
    private final long maxSteps;
    private final DormandPrince45Integrator.Tolerances tolerances; // null in fixed-step mode

    // ---- published to other threads ----
    private final AtomicReference<Snapshot> latest;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition published = lock.newCondition();
    private final ConcurrentLinkedQueue<Runnable> commands = new ConcurrentLinkedQueue<>();
    private final Thread worker;
    private volatile boolean closeRequested;

    // ---- confined to the worker thread (after construction) ----
    private Integrator integrator;
    private double[] q;
    private double[] v;
    private double[] qGood;
    private double[] vGood;
    private long stepCount;
    private Status status = Status.READY;
    private double speed;
    private String failure;
    private long baseWallNanos;
    private long baseSteps;
    private double baseTime;
    private double currentTime;      // adaptive mode only: t0 plus the accepted step sizes
    private double lastStepSize;     // adaptive mode only: the most recent accepted step
    private long sequence;
    private long lastPublishNanos;
    private long lastPublishedSteps = -1;
    private State lastGoodState;
    private long lastGoodStepCount;
    private double lastGoodStepSize;
    private Diagnostics.Invariants lastGoodInvariants;
    private Diagnostics.Drift lastGoodDrift;

    /**
     * Creates the runner, measures the initial state and starts the worker thread (paused, in
     * {@link Status#READY}).
     *
     * @throws NBodySystem.SingularConfigurationException if the initial configuration is singular
     */
    public SimulationRunner(SimulationConfig config) {
        this.config = config;
        this.system = config.system();
        this.initialState = config.initial();
        this.h = config.stepSize();
        this.t0 = initialState.time();
        this.maxSteps = config.maxSteps();
        this.initialInvariants = Diagnostics.measure(system, initialState); // fail fast on a bad initial state
        this.speed = config.initialSpeed();

        this.tolerances = config.adaptiveTolerances();
        this.integrator = config.integratorFactory().get();
        if (tolerances != null && !(integrator instanceof DormandPrince45Integrator)) {
            throw new IllegalArgumentException("Adaptive stepping requires a Dormand-Prince 5(4) integrator, got "
                    + integrator.name());
        }
        this.currentTime = t0;
        this.lastStepSize = h;
        this.lastGoodStepSize = h;
        this.q = initialState.positions();
        this.v = initialState.velocities();
        this.qGood = q.clone();
        this.vGood = v.clone();
        this.lastGoodState = initialState;
        this.lastGoodStepCount = 0;
        this.lastGoodInvariants = initialInvariants;
        this.lastGoodDrift = Diagnostics.drift(system, initialState, initialInvariants, initialInvariants);

        Snapshot first = new Snapshot(0, Status.READY, initialState, initialInvariants, lastGoodDrift, 0, h,
                methodName(), speed, null);
        this.latest = new AtomicReference<>(first);
        this.sequence = 0;
        this.lastPublishNanos = System.nanoTime();

        this.worker = Thread.ofPlatform().name("chaoslab-sim").daemon(true).unstarted(this::loop);
        this.worker.start();
    }

    // ---------------------------------------------------------------- public API (any thread)

    /** The most recently published snapshot. Never null, lock-free, safe to call from the UI thread. */
    public Snapshot snapshot() {
        return latest.get();
    }

    /** Starts or resumes continuous running. Ignored unless READY or PAUSED. */
    public void run() {
        submit(() -> {
            if (status == Status.READY || status == Status.PAUSED) {
                status = Status.RUNNING;
                rebase();
                publishNow();
            }
        });
    }

    /** Stops continuous running; resumable with {@link #run()}. Ignored unless RUNNING. */
    public void pause() {
        submit(() -> {
            if (status == Status.RUNNING) {
                status = Status.PAUSED;
                publishNow();
            }
        });
    }

    /**
     * Takes {@code n} steps immediately (fixed steps, or accepted adaptive steps), then leaves the runner PAUSED
     * (or FINISHED at {@code maxSteps}).
     * Ignored unless READY or PAUSED. The worker is busy until the steps are done, so keep {@code n} modest.
     */
    public void step(int n) {
        if (n <= 0) {
            throw new IllegalArgumentException("n must be > 0, got " + n);
        }
        submit(() -> {
            if (status != Status.READY && status != Status.PAUSED) {
                return;
            }
            for (int i = 0; i < n && stepCount < maxSteps; i++) {
                if (!stepOnce()) {
                    return; // stepOnce already published the failure
                }
            }
            status = stepCount >= maxSteps ? Status.FINISHED : Status.PAUSED;
            publishNow();
        });
    }

    /** Restores the initial state with a fresh integrator and returns to READY. Recovers from FAILED. */
    public void reset() {
        submit(() -> {
            integrator = config.integratorFactory().get();
            q = initialState.positions();
            v = initialState.velocities();
            qGood = q.clone();
            vGood = v.clone();
            stepCount = 0;
            currentTime = t0;
            lastStepSize = h;
            lastGoodStepSize = h;
            failure = null;
            status = Status.READY;
            lastGoodState = initialState;
            lastGoodStepCount = 0;
            lastGoodInvariants = initialInvariants;
            lastGoodDrift = Diagnostics.drift(system, initialState, initialInvariants, initialInvariants);
            publishNow();
        });
    }

    /**
     * Changes the requested speed (simulation time units per wall-clock second). Takes effect immediately and does
     * not alter the step size or any simulated result.
     */
    public void setSpeed(double simulationTimePerSecond) {
        if (!Double.isFinite(simulationTimePerSecond) || simulationTimePerSecond <= 0.0) {
            throw new IllegalArgumentException("speed must be finite and > 0, got " + simulationTimePerSecond);
        }
        submit(() -> {
            speed = simulationTimePerSecond;
            rebase();
            publishNow();
        });
    }

    /**
     * Blocks until a published snapshot satisfies the predicate, or the timeout elapses. Intended for tests and
     * tools; the UI should poll {@link #snapshot()} instead.
     *
     * @return the matching snapshot, or empty on timeout
     */
    public Optional<Snapshot> await(Predicate<Snapshot> condition, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        lock.lock();
        try {
            while (true) {
                Snapshot s = latest.get();
                if (condition.test(s)) {
                    return Optional.of(s);
                }
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return Optional.empty();
                }
                published.awaitNanos(remaining);
            }
        } finally {
            lock.unlock();
        }
    }

    /** Stops the worker thread (waiting briefly for it) and publishes a final CLOSED snapshot. Idempotent. */
    @Override
    public void close() {
        closeRequested = true;
        LockSupport.unpark(worker);
        try {
            worker.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---------------------------------------------------------------- worker thread

    private void submit(Runnable command) {
        commands.add(command);
        LockSupport.unpark(worker);
    }

    private void loop() {
        try {
            while (!closeRequested) {
                drainCommands();
                if (closeRequested) {
                    break;
                }
                if (status == Status.RUNNING) {
                    runBatch();
                    long now = System.nanoTime();
                    if (stepCount != lastPublishedSteps && now - lastPublishNanos >= PUBLISH_INTERVAL_NANOS) {
                        publishNow();
                    }
                    LockSupport.parkNanos(PACING_PARK_NANOS);
                } else {
                    LockSupport.park();
                }
            }
            status = Status.CLOSED;
            publishNow();
        } catch (Throwable t) {
            failure = "Internal simulation error: " + t;
            status = Status.FAILED;
            try {
                publishNow();
            } catch (Throwable ignored) {
                // nothing more can be done; the last published snapshot remains readable
            }
        }
    }

    private void drainCommands() {
        Runnable command;
        while ((command = commands.poll()) != null) {
            command.run();
        }
    }

    private void rebase() {
        baseWallNanos = System.nanoTime();
        baseSteps = stepCount;
        baseTime = time();
    }

    /** Takes the steps that are due according to the wall clock, within a bounded time budget. */
    private void runBatch() {
        if (tolerances != null) {
            runAdaptiveBatch();
            return;
        }
        long now = System.nanoTime();
        double dueSim = speed * ((now - baseWallNanos) / 1e9);
        long dueDelta = (long) Math.min(MAX_DUE_STEPS, Math.floor(dueSim / h));
        long due = baseSteps + dueDelta;
        long limit = Math.min(due, maxSteps);
        long deadline = now + BATCH_BUDGET_NANOS;
        int counter = 0;
        while (stepCount < limit) {
            if (!stepOnce()) {
                return;
            }
            if ((++counter & 63) == 0 && System.nanoTime() > deadline) {
                rebase(); // still behind: drop the backlog instead of spiralling
                break;
            }
        }
        if (stepCount >= maxSteps) {
            status = Status.FINISHED;
            publishNow();
        }
    }

    /**
     * Adaptive mode: takes steps until the simulated time reaches what the wall clock and speed call for. The step
     * that crosses the target is kept whole, so time can end up at most one step past it.
     */
    private void runAdaptiveBatch() {
        long now = System.nanoTime();
        double target = baseTime + speed * ((now - baseWallNanos) / 1e9);
        long deadline = now + BATCH_BUDGET_NANOS;
        int counter = 0;
        while (currentTime < target && stepCount < maxSteps) {
            if (!stepOnce()) {
                return;
            }
            if ((++counter & 63) == 0 && System.nanoTime() > deadline) {
                rebase(); // still behind: drop the backlog instead of spiralling
                break;
            }
        }
        if (stepCount >= maxSteps) {
            status = Status.FINISHED;
            publishNow();
        }
    }

    /** One integration step. On failure restores the last valid state, marks FAILED, publishes, returns false. */
    private boolean stepOnce() {
        System.arraycopy(q, 0, qGood, 0, q.length);
        System.arraycopy(v, 0, vGood, 0, v.length);
        try {
            if (tolerances != null) {
                DormandPrince45Integrator.AcceptedStep accepted = ((DormandPrince45Integrator) integrator)
                        .adaptiveStep(system, q, v, currentTime, h, tolerances);
                currentTime += accepted.stepSize();
                lastStepSize = accepted.stepSize();
            } else {
                integrator.step(system, q, v, h);
            }
            stepCount++;
            return true;
        } catch (RuntimeException e) {
            System.arraycopy(qGood, 0, q, 0, q.length);
            System.arraycopy(vGood, 0, v, 0, v.length);
            failure = "Step " + (stepCount + 1) + " at t=" + time() + " failed: " + e.getClass().getSimpleName()
                    + ": " + e.getMessage();
            status = Status.FAILED;
            publishNow();
            return false;
        }
    }

    private double time() {
        return tolerances != null ? currentTime : t0 + stepCount * h;
    }

    /** The integrator name to report; adaptive runs say so. */
    private String methodName() {
        return tolerances != null ? integrator.name() + ", adaptive" : integrator.name();
    }

    /** The step size to report: the fixed step, or in adaptive mode the most recent accepted step. */
    private double reportedStepSize() {
        return tolerances != null ? lastStepSize : h;
    }

    /** Builds and publishes a snapshot of the current state, rolling back if the state cannot be measured. */
    private void publishNow() {
        State s = State.of(time(), q, v);
        Diagnostics.Invariants inv;
        Diagnostics.Drift drift;
        try {
            inv = Diagnostics.measure(system, s);
            drift = Diagnostics.drift(system, initialState, initialInvariants, inv);
            lastGoodState = s;
            lastGoodStepCount = stepCount;
            lastGoodStepSize = lastStepSize;
            lastGoodInvariants = inv;
            lastGoodDrift = drift;
        } catch (ArithmeticException e) {
            // The step produced a finite but singular configuration. Roll back to the last measurable state.
            long attempted = stepCount;
            System.arraycopy(lastGoodState.positions(), 0, q, 0, q.length);
            System.arraycopy(lastGoodState.velocities(), 0, v, 0, v.length);
            stepCount = lastGoodStepCount;
            currentTime = lastGoodState.time();
            lastStepSize = lastGoodStepSize;
            failure = "Singular configuration reached between step " + lastGoodStepCount + " and step " + attempted
                    + "; rolled back to step " + lastGoodStepCount + ". " + e.getMessage();
            status = Status.FAILED;
            s = lastGoodState;
            inv = lastGoodInvariants;
            drift = lastGoodDrift;
        }
        lastPublishedSteps = stepCount;
        lastPublishNanos = System.nanoTime();
        Snapshot snap = new Snapshot(++sequence, status, s, inv, drift, stepCount, reportedStepSize(), methodName(),
                speed, status == Status.FAILED ? failure : null);
        latest.set(snap);
        lock.lock();
        try {
            published.signalAll();
        } finally {
            lock.unlock();
        }
    }
}
