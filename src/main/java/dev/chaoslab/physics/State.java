package dev.chaoslab.physics;

import java.util.Arrays;

/**
 * An immutable snapshot of the dynamical state of an N-body system: time, positions and velocities.
 *
 * <p>Positions and velocities are flat arrays (body i at indices 3i..3i+2). This class defensively copies on the
 * way in and on the way out, so a {@code State} can be handed to another thread (for example published to the UI)
 * without any risk of the simulation mutating it later. Integrators work on their own scratch arrays in their hot
 * loops and create a new {@code State} only when a step is committed.
 *
 * <p>Construction validates that all values are finite. A NaN or infinity entering a State is a numerical failure
 * that must be reported where it happens, not discovered ten thousand steps later in a plot.
 */
public final class State {

    private final double time;
    private final double[] q;
    private final double[] v;

    private State(double time, double[] q, double[] v) {
        this.time = time;
        this.q = q;
        this.v = v;
    }

    /**
     * @param time simulation time, finite
     * @param q    positions, length 3N (copied)
     * @param v    velocities, length 3N (copied)
     * @throws IllegalArgumentException if arrays are null, of unequal or non-multiple-of-3 length, or contain
     *                                  a non-finite value
     */
    public static State of(double time, double[] q, double[] v) {
        if (q == null || v == null) {
            throw new IllegalArgumentException("Positions and velocities must not be null");
        }
        if (q.length != v.length || q.length == 0 || q.length % 3 != 0) {
            throw new IllegalArgumentException("Positions and velocities must have equal length that is a positive"
                    + " multiple of 3, got " + q.length + " and " + v.length);
        }
        if (!Double.isFinite(time)) {
            throw new IllegalArgumentException("Time must be finite, got " + time);
        }
        requireFinite(q, "position");
        requireFinite(v, "velocity");
        return new State(time, q.clone(), v.clone());
    }

    private static void requireFinite(double[] a, String what) {
        for (int i = 0; i < a.length; i++) {
            if (!Double.isFinite(a[i])) {
                throw new IllegalArgumentException("Non-finite " + what + " component " + (i % 3) + " of body "
                        + (i / 3) + ": " + a[i]);
            }
        }
    }

    public double time() {
        return time;
    }

    public int bodyCount() {
        return q.length / 3;
    }

    /** A copy of the flat position array. */
    public double[] positions() {
        return q.clone();
    }

    /** A copy of the flat velocity array. */
    public double[] velocities() {
        return v.clone();
    }

    public Vec3 position(int body) {
        return Vec3.fromArray(q, body);
    }

    public Vec3 velocity(int body) {
        return Vec3.fromArray(v, body);
    }

    /** The same positions and velocities at a different time label. */
    public State withTime(double newTime) {
        return of(newTime, q, v);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof State s && Double.compare(time, s.time) == 0
                && Arrays.equals(q, s.q) && Arrays.equals(v, s.v);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * Double.hashCode(time) + Arrays.hashCode(q)) + Arrays.hashCode(v);
    }

    @Override
    public String toString() {
        return "State{t=" + time + ", N=" + bodyCount() + "}";
    }
}
