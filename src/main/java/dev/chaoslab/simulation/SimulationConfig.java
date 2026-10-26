package dev.chaoslab.simulation;

import dev.chaoslab.integration.DormandPrince45Integrator;
import dev.chaoslab.integration.Integrator;
import dev.chaoslab.physics.NBodySystem;
import dev.chaoslab.physics.State;
import java.util.function.Supplier;

/**
 * Everything needed to define one simulation run. Immutable.
 *
 * <h2>Two stepping modes</h2>
 * <b>Fixed step</b> (the default constructor): the step size is fixed for the lifetime of a {@link SimulationRunner}
 * and simulated time is always {@code t0 + stepCount * stepSize}, which makes results independent of how fast the run
 * is paced or how often the user interface samples it. To change the step size, create a new runner.
 *
 * <p><b>Adaptive step</b> ({@link #adaptive}): {@link DormandPrince45Integrator} chooses each step so that its
 * estimated local error stays within the tolerances, taking tiny steps through close encounters and large ones
 * elsewhere. {@code stepSize} is then only the first trial step. Time is the running sum of the accepted step sizes.
 * The step sequence is a pure function of the initial state, the tolerances and the number of steps taken, so results
 * are still independent of pacing and of the sampling rate: the state after N steps is always bit-identical.
 *
 * @param system            the physical system
 * @param initial           initial state; also the state restored by reset
 * @param integratorFactory creates a fresh integrator; called at start and on every reset so that no cached
 *                          data survives a reset
 * @param stepSize          fixed integration step (adaptive mode: the first trial step), finite and &gt; 0
 * @param maxSteps          the run finishes after this many steps ({@link #UNBOUNDED} for no limit)
 * @param initialSpeed      requested speed in simulation time units per wall-clock second, finite and &gt; 0
 * @param adaptiveTolerances null for fixed-step mode; otherwise the error tolerances of adaptive mode, which requires
 *                          the factory to produce a {@link DormandPrince45Integrator}
 */
public record SimulationConfig(
        NBodySystem system,
        State initial,
        Supplier<Integrator> integratorFactory,
        double stepSize,
        long maxSteps,
        double initialSpeed,
        DormandPrince45Integrator.Tolerances adaptiveTolerances) {

    public static final long UNBOUNDED = Long.MAX_VALUE;

    /** Fixed-step configuration. */
    public SimulationConfig(NBodySystem system, State initial, Supplier<Integrator> integratorFactory,
            double stepSize, long maxSteps, double initialSpeed) {
        this(system, initial, integratorFactory, stepSize, maxSteps, initialSpeed, null);
    }

    /**
     * Adaptive-step configuration. The factory must produce {@link DormandPrince45Integrator} instances (checked when
     * the runner is created).
     *
     * @param initialStep first trial step; the controller adjusts it immediately
     */
    public static SimulationConfig adaptive(NBodySystem system, State initial,
            Supplier<Integrator> integratorFactory, double initialStep,
            DormandPrince45Integrator.Tolerances tolerances, long maxSteps, double initialSpeed) {
        if (tolerances == null) {
            throw new IllegalArgumentException("tolerances must not be null");
        }
        return new SimulationConfig(system, initial, integratorFactory, initialStep, maxSteps, initialSpeed,
                tolerances);
    }

    public boolean isAdaptive() {
        return adaptiveTolerances != null;
    }

    public SimulationConfig {
        if (system == null || initial == null || integratorFactory == null) {
            throw new IllegalArgumentException("system, initial state and integrator factory must not be null");
        }
        if (system.bodyCount() != initial.bodyCount()) {
            throw new IllegalArgumentException("Initial state has " + initial.bodyCount()
                    + " bodies but the system has " + system.bodyCount());
        }
        if (!Double.isFinite(stepSize) || stepSize <= 0.0) {
            throw new IllegalArgumentException("stepSize must be finite and > 0, got " + stepSize);
        }
        if (maxSteps <= 0) {
            throw new IllegalArgumentException("maxSteps must be > 0, got " + maxSteps);
        }
        if (!Double.isFinite(initialSpeed) || initialSpeed <= 0.0) {
            throw new IllegalArgumentException("initialSpeed must be finite and > 0, got " + initialSpeed);
        }
    }
}
