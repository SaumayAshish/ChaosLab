package dev.chaoslab.analysis;

import dev.chaoslab.physics.NBodySystem;
import dev.chaoslab.physics.State;

/**
 * Runs a {@link TwinRun} on a thread of its own, so that following the pair (which may need many tight integration
 * steps when time jumps ahead) can never slow the display, and publishes immutable {@link TwinRun.Reading}s.
 *
 * <p>It follows the same rules as {@link ReferenceMonitor}: {@link #submit(double)} returns at once and keeps only the
 * newest request (older ones are dropped, never queued); every reading carries the time it belongs to; a reading's
 * numbers are bit-identical for a given time whatever requests came before, so pacing affects only how often a
 * reading appears. {@link #reset()} abandons everything and starts again from the initial state, and no reading from
 * before a reset can appear after it. {@link #close()} stops the thread.
 *
 * <p>Thread-safe: any thread may call every method.
 */
public final class TwinMonitor implements AutoCloseable {

    private static final long JOIN_MILLISECONDS = 1000L;

    private record Request(double time, int generation) {
    }

    private final NBodySystem system;
    private final State start;
    private final Thread thread;
    private final Object lock = new Object();

    // guarded by lock
    private Request pending;
    private int generation;
    private boolean closed;
    private volatile TwinRun.Reading latest;

    // used only by the monitor thread
    private final TwinRun.Nudge nudge;
    private TwinRun twin;
    private int builtGeneration;

    /**
     * Builds the pair (so a bad initial state is reported here, to the caller) and starts the thread.
     *
     * @throws NBodySystem.SingularConfigurationException if either start state is singular
     * @throws IllegalArgumentException                   for null arguments
     */
    public TwinMonitor(NBodySystem system, State start) {
        this(system, start, TwinRun.Nudge.DEFAULT);
    }

    /**
     * Like {@link #TwinMonitor(NBodySystem, State)} with a chosen body and direction for the nudge.
     *
     * @throws NBodySystem.SingularConfigurationException if either start state is singular
     * @throws IllegalArgumentException                   for null arguments or a body outside the system
     */
    public TwinMonitor(NBodySystem system, State start, TwinRun.Nudge nudge) {
        if (system == null || start == null || nudge == null) {
            throw new IllegalArgumentException("system, start state and nudge must not be null");
        }
        this.system = system;
        this.start = start;
        this.nudge = nudge;
        this.twin = new TwinRun(system, start, nudge);
        this.thread = new Thread(this::loop, "chaoslab-twin-monitor");
        this.thread.setDaemon(true);
        this.thread.start();
    }

    /**
     * Asks for the pair's state at {@code time}. Returns at once; replaces any request not yet started. Does nothing
     * after {@link #close()}.
     *
     * @throws IllegalArgumentException if {@code time} is not finite or is before the start time
     */
    public void submit(double time) {
        if (!Double.isFinite(time) || time < start.time()) {
            throw new IllegalArgumentException("time must be finite and >= the start time " + start.time()
                    + ", got " + time);
        }
        synchronized (lock) {
            if (closed) {
                return;
            }
            pending = new Request(time, generation);
            lock.notifyAll();
        }
    }

    /** The newest reading, or null if none has been produced since the start or the last reset. */
    public TwinRun.Reading latest() {
        return latest;
    }

    /** The characteristic length the readings are measured against. */
    public double length() {
        return TwinRun.characteristicLength(start);
    }

    /** Forgets everything and starts again; a request being worked on is abandoned and never published. */
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
                twin = new TwinRun(system, start, nudge);
                builtGeneration = request.generation();
            }
            TwinRun.Reading reading;
            try {
                reading = twin.at(request.time());
            } catch (RuntimeException e) { // never let the thread die silently: report instead
                reading = new TwinRun.Reading(request.time(), null, Double.NaN, twin.length(),
                        "Twin monitor error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
            synchronized (lock) {
                if (!closed && request.generation() == generation) {
                    latest = reading;
                }
            }
        }
    }
}
