package dev.chaoslab.analysis;

import dev.chaoslab.integration.DormandPrince45Integrator.Tolerances;
import dev.chaoslab.physics.NBodySystem;
import dev.chaoslab.physics.State;

/**
 * Runs the two comparisons of "chaos in the window" on a thread of its own, so that neither can slow the display,
 * and publishes the results as immutable {@link Reading}s.
 *
 * <h2>The two things it measures, and why they are kept apart</h2>
 * <ul>
 *   <li><b>Numerical error</b> ({@link Reading#gap()}): the distance between the bodies of the displayed run, which
 *       was computed by whatever integrator and step size the person chose, and a high-accuracy reference trajectory
 *       ({@link ReferenceTrack}, tolerance 1e-13) at the same simulation time. It says how wrong the displayed
 *       run is.</li>
 *   <li><b>Reference uncertainty</b> ({@link Reading#uncertainty()}): the distance between that reference and a second,
 *       even tighter one (tolerance 1e-14). It says how much the reference itself can be trusted. A gap below this
 *       floor cannot be interpreted as display error. In chaotic motion it grows exponentially, so beyond some time
 *       even the reference stops being a yardstick.</li>
 *   <li><b>Physical sensitivity</b> ({@link Reading#lyapunovSlope()}): the Lyapunov exponent of the motion itself,
 *       measured by {@link LyapunovTracker} on its own tight integrator. It does not depend on the displayed
 *       integrator at all: it is a property of the initial state.</li>
 * </ul>
 *
 * <h2>How it is used</h2>
 * The user interface calls {@link #submit(double, double[])} once per rendered frame with the simulation time and the
 * positions of the displayed run, and reads {@link #latest()}. Only the newest request is kept: if the monitor is
 * busy, older requests are dropped, never queued, so it cannot fall behind. Every reading carries the time it
 * belongs to. For a given time and given positions the reading's numbers are bit-identical whatever requests came
 * before (the reference, the second reference and the Lyapunov tracker are deterministic functions of the initial
 * state and the time), so pacing affects only how often a reading appears, not what it says.
 *
 * <p>{@link #reset()} discards everything computed so far and starts again from the initial state, for when the
 * displayed run is reset. {@link #close()} stops the thread.
 *
 * <p>Thread-safe: any thread may call every method.
 */
public final class ReferenceMonitor implements AutoCloseable {

    /** Seed of the random offset direction in the Lyapunov tracker: fixed, so the readout is reproducible. */
    public static final long LYAPUNOV_SEED = 1L;

    /** Tolerance of the second reference, one decade tighter than {@link ReferenceTrack#DEFAULT_TOLERANCES}. */
    public static final Tolerances CHECK_TOLERANCES = new Tolerances(1e-14, 1e-14);

    private static final long JOIN_MILLISECONDS = 1000L;

    /**
     * What the monitor knew at one requested time. All quantities refer to that time. Lengths are in simulation
     * length units; rates are per simulation time unit.
     *
     * @param time                   the requested simulation time
     * @param referencePositions     positions of the reference at {@code time} (flat x0, y0, z0, x1, ...), or null if
     *                               the reference could not reach {@code time}
     * @param gap                    largest distance between corresponding bodies of the displayed run and the
     *                               reference, or NaN if the reference is unavailable
     * @param uncertainty            largest distance between the reference and the tighter second reference, or NaN if
     *                               that is unavailable
     * @param length                 the system's characteristic length (largest pair distance at the start), which
     *                               makes the gap and the uncertainty dimensionless
     * @param referenceFailure       why the reference is unavailable at {@code time}, or null
     * @param referenceEnergyDrift   largest relative energy drift of the reference so far (a trust indicator)
     * @param lyapunovRunning        total log growth divided by elapsed time, or NaN before the first renormalisation
     * @param lyapunovSlope          slope of the later half of the cumulative log growth, or NaN while fewer than four
     *                               renormalisations exist
     * @param lyapunovElapsed        time since the start covered by the Lyapunov estimate (a multiple of
     *                               {@code lyapunovInterval}, at most {@code time})
     * @param lyapunovInterval       time between renormalisations (the shortest pair timescale)
     * @param renormalisations       number of completed renormalisations
     * @param lyapunovFailure        why the Lyapunov tracker stopped, or null
     * @param lyapunovEnergyDrift    largest relative energy drift of the Lyapunov reference trajectory so far
     */
    public record Reading(
            double time,
            double[] referencePositions,
            double gap,
            double uncertainty,
            double length,
            String referenceFailure,
            double referenceEnergyDrift,
            double lyapunovRunning,
            double lyapunovSlope,
            double lyapunovElapsed,
            double lyapunovInterval,
            int renormalisations,
            String lyapunovFailure,
            double lyapunovEnergyDrift) {

        public Reading {
            referencePositions = referencePositions == null ? null : referencePositions.clone();
        }

        /** A copy of the reference positions, or null if the reference was unavailable. */
        @Override
        public double[] referencePositions() {
            return referencePositions == null ? null : referencePositions.clone();
        }

        /** {@link #gap()} divided by the characteristic length; NaN if the reference was unavailable. */
        public double relativeGap() {
            return gap / length;
        }

        /** {@link #uncertainty()} divided by the characteristic length; NaN if unavailable. */
        public double relativeUncertainty() {
            return uncertainty / length;
        }
    }

    private record Request(double time, double[] positions, int generation) {
    }

    /** The objects that are rebuilt on reset. Used only on the monitor thread. */
    private static final class Engines {
        final ReferenceTrack reference;
        final ReferenceTrack check;
        final LyapunovTracker lyapunov;

        Engines(NBodySystem system, State start, double firstStep) {
            reference = new ReferenceTrack(system, start, firstStep, ReferenceTrack.DEFAULT_TOLERANCES);
            check = new ReferenceTrack(system, start, firstStep, CHECK_TOLERANCES);
            lyapunov = new LyapunovTracker(system, start, LyapunovTracker.DEFAULT_TOLERANCES,
                    LyapunovTracker.DEFAULT_SEPARATION, LYAPUNOV_SEED);
        }
    }

    private final NBodySystem system;
    private final State start;
    private final double firstStep;
    private final int bodyCount;
    private final Thread thread;
    private final Object lock = new Object();

    // guarded by lock
    private Request pending;
    private int generation;
    private boolean closed;
    private volatile Reading latest;

    // used only by the monitor thread
    private Engines engines;
    private int builtGeneration;

    /**
     * Builds the trackers (so a bad initial state is reported here, to the caller) and starts the thread.
     *
     * @param system the system
     * @param start  initial state; its time is the earliest time that can be requested
     * @throws NBodySystem.SingularConfigurationException if {@code start} is singular
     * @throws IllegalArgumentException                   for invalid arguments
     */
    public ReferenceMonitor(NBodySystem system, State start) {
        if (system == null || start == null) {
            throw new IllegalArgumentException("system and start state must not be null");
        }
        this.system = system;
        this.start = start;
        this.bodyCount = start.bodyCount();
        this.firstStep = 1e-3 * Math.max(1e-300, shortestTimescale(system, start));
        this.engines = new Engines(system, start, firstStep);
        this.builtGeneration = 0;
        this.thread = new Thread(this::loop, "chaoslab-reference-monitor");
        this.thread.setDaemon(true);
        this.thread.start();
    }

    // ---------------------------------------------------------------- public interface

    /**
     * Asks for a reading at {@code time} for the displayed run whose positions are {@code positions}. Returns at once.
     * Replaces any request that has not been started yet. Does nothing after {@link #close()}.
     *
     * @param time      simulation time of the displayed state, finite and not before the start time
     * @param positions positions of all bodies of the displayed state (flat); copied
     * @throws IllegalArgumentException for a wrong length or a non-finite or too early time
     */
    public void submit(double time, double[] positions) {
        if (!Double.isFinite(time) || time < start.time()) {
            throw new IllegalArgumentException("time must be finite and >= the start time " + start.time()
                    + ", got " + time);
        }
        if (positions == null || positions.length != 3 * bodyCount) {
            throw new IllegalArgumentException("positions must have length " + 3 * bodyCount + ", got "
                    + (positions == null ? "null" : String.valueOf(positions.length)));
        }
        for (double x : positions) {
            if (!Double.isFinite(x)) {
                throw new IllegalArgumentException("positions must be finite");
            }
        }
        synchronized (lock) {
            if (closed) {
                return;
            }
            pending = new Request(time, positions.clone(), generation);
            lock.notifyAll();
        }
    }

    /** The newest reading, or null if none has been produced since the start or the last reset. */
    public Reading latest() {
        return latest;
    }

    /**
     * Forgets everything and starts again from the initial state. A request that is being worked on is abandoned and
     * its result is never published, so no reading from before the reset can appear afterwards.
     */
    public void reset() {
        synchronized (lock) {
            generation++;
            pending = null;
            latest = null;
            lock.notifyAll();
        }
    }

    /** True until {@link #close()} has taken effect. */
    public boolean isRunning() {
        return thread.isAlive();
    }

    /** Stops the thread (waiting at most a second for it) and discards pending work. Idempotent. */
    @Override
    public void close() {
        synchronized (lock) {
            closed = true;
            pending = null;
            lock.notifyAll();
        }
        thread.interrupt();
        try {
            thread.join(JOIN_MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---------------------------------------------------------------- the monitor thread

    private void loop() {
        while (true) {
            Request request;
            synchronized (lock) {
                while (pending == null && !closed) {
                    try {
                        lock.wait();
                    } catch (InterruptedException e) {
                        if (closed) {
                            return;
                        }
                    }
                }
                if (closed) {
                    return;
                }
                request = pending;
                pending = null;
            }
            if (request.generation() != builtGeneration) {
                engines = new Engines(system, start, firstStep);
                builtGeneration = request.generation();
            }
            Reading reading = compute(request);
            synchronized (lock) {
                if (!closed && request.generation() == generation) {
                    latest = reading;
                }
            }
        }
    }

    private Reading compute(Request request) {
        final double time = request.time();
        try {
            double[] reference = null;
            double gap = Double.NaN;
            double uncertainty = Double.NaN;
            String referenceFailure = null;
            try {
                reference = engines.reference.positionsAt(time);
                gap = ReferenceTrack.maxPositionGap(request.positions(), reference);
                try {
                    uncertainty = ReferenceTrack.maxPositionGap(reference, engines.check.positionsAt(time));
                } catch (IllegalStateException e) {
                    uncertainty = Double.NaN; // the tighter run failed first: the floor is unknown here
                }
            } catch (IllegalStateException e) {
                referenceFailure = e.getMessage();
            }
            engines.lyapunov.advanceTo(time);
            LyapunovTracker ly = engines.lyapunov;
            return new Reading(time, reference, gap, uncertainty, ly.characteristicLength(), referenceFailure,
                    engines.reference.maxRelativeEnergyDrift(), ly.runningExponent(), ly.recentSlope(0.5),
                    ly.elapsed(), ly.interval(), ly.renormalisations(), ly.failure(), ly.maxRelativeEnergyDrift());
        } catch (RuntimeException e) { // never let the thread die silently: report instead
            return new Reading(time, null, Double.NaN, Double.NaN, engines.lyapunov.characteristicLength(),
                    "Monitor error: " + e.getClass().getSimpleName() + ": " + e.getMessage(), Double.NaN,
                    Double.NaN, Double.NaN, 0.0, engines.lyapunov.interval(), 0, null, Double.NaN);
        }
    }

    /** Shortest pair timescale sqrt(r^3 / (G (mi + mj))) of the start state, softening included. */
    private static double shortestTimescale(NBodySystem system, State start) {
        double shortest = Double.POSITIVE_INFINITY;
        final int n = system.bodyCount();
        final double eps2 = system.softening() * system.softening();
        double[] q = start.positions();
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                double dx = q[3 * j] - q[3 * i];
                double dy = q[3 * j + 1] - q[3 * i + 1];
                double dz = q[3 * j + 2] - q[3 * i + 2];
                double rs2 = dx * dx + dy * dy + dz * dz + eps2;
                double tau = Math.sqrt(rs2 * Math.sqrt(rs2)
                        / (system.gravitationalConstant() * (system.mass(i) + system.mass(j))));
                shortest = Math.min(shortest, tau);
            }
        }
        return Double.isFinite(shortest) && shortest > 0.0 ? shortest : 1.0;
    }
}
