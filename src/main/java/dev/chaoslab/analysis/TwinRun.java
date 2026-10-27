package dev.chaoslab.analysis;

import dev.chaoslab.physics.NBodySystem;
import dev.chaoslab.physics.State;

/**
 * The "sensitivity pair": the same system started twice, the second time with one body moved by a tiny, fixed nudge,
 * and followed side by side with the high-accuracy reference integrator ({@link ReferenceTrack}).
 *
 * <h2>What it measures</h2>
 * The separation between the two runs at the same simulation time is how far a 1e-6 uncertainty in the starting
 * position has grown. It is a property of the motion, not of the integrator the person is watching: both runs use the
 * same tight tolerance (1e-13), so as long as the separation is far above the reference's own uncertainty the two
 * tracks differ because the physics amplifies the nudge, not because of rounding or step size. In regular motion the
 * separation grows slowly (roughly linearly); in chaotic motion it grows roughly like {@code exp(lambda t)} until it is
 * as large as the system itself and can grow no further.
 *
 * <h2>The nudge</h2>
 * By default body 0 is moved along +x (a {@link Nudge} chooses any body and any of the six axis directions) by
 * {@link #NUDGE} times the system's characteristic length (the largest pair distance at the
 * start, the same length that makes the gap plot dimensionless). Nothing else changes, so the pair starts with a
 * separation of exactly {@code NUDGE} in those units. The choice is fixed, so results are reproducible; a
 * different body or direction gives a different curve in detail but the same exponential rate in chaotic motion.
 *
 * <h2>Reading it</h2>
 * {@link Reading#separation()} is the largest distance between corresponding bodies of the two runs, in the system's
 * length units; {@link Reading#offsets()} is the flat vector (second run minus first run) of all body positions, which
 * the display adds to the displayed bodies to draw the twin. Asking for a time that either run cannot reach (a
 * singular encounter ended it) returns a reading with a {@linkplain Reading#failure() failure} and no numbers, it does
 * not throw. Results are deterministic functions of the start state and the time, whatever order times are asked in.
 *
 * <p>Not thread-safe; {@link TwinMonitor} runs it on a thread of its own.
 */
public final class TwinRun {

    /** The nudge as a fraction of the characteristic length. */
    public static final double NUDGE = 1e-6;

    /** The six axis directions a body can be nudged along. */
    public enum Direction {
        PLUS_X("+x", 0, 1.0), MINUS_X("-x", 0, -1.0),
        PLUS_Y("+y", 1, 1.0), MINUS_Y("-y", 1, -1.0),
        PLUS_Z("+z", 2, 1.0), MINUS_Z("-z", 2, -1.0);

        private final String label;
        private final int axis;
        private final double sign;

        Direction(String label, int axis, double sign) {
            this.label = label;
            this.axis = axis;
            this.sign = sign;
        }

        /** Axis index: 0 for x, 1 for y, 2 for z. */
        public int axis() {
            return axis;
        }

        /** +1 or -1. */
        public double sign() {
            return sign;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /**
     * Which body is moved, and which way.
     *
     * @param body      index of the body (0-based)
     * @param direction the direction of the displacement
     */
    public record Nudge(int body, Direction direction) {

        /** Body 0 along +x: what the twin run always did before the choice existed. */
        public static final Nudge DEFAULT = new Nudge(0, Direction.PLUS_X);

        public Nudge {
            if (body < 0) {
                throw new IllegalArgumentException("body index must not be negative, got " + body);
            }
            if (direction == null) {
                throw new IllegalArgumentException("direction must not be null");
            }
        }

        /** Short description in the program's numbering (bodies are shown as m1, m2, ...), e.g. "m2 along -y". */
        public String label() {
            return "m" + (body + 1) + " along " + direction;
        }
    }

    /**
     * One answer. Lengths are in simulation length units.
     *
     * @param time       the requested simulation time
     * @param offsets    second run minus first run, flat (x0, y0, z0, x1, ...), or null if a run could not reach
     *                   {@code time}
     * @param separation largest distance between corresponding bodies, or NaN if unavailable
     * @param length     the characteristic length that makes the separation dimensionless
     * @param failure    why the answer is unavailable, or null
     */
    public record Reading(double time, double[] offsets, double separation, double length, String failure) {

        public Reading {
            offsets = offsets == null ? null : offsets.clone();
        }

        /** A copy of the offsets, or null if unavailable. */
        @Override
        public double[] offsets() {
            return offsets == null ? null : offsets.clone();
        }

        /** {@link #separation()} divided by the characteristic length; NaN if unavailable. */
        public double relativeSeparation() {
            return separation / length;
        }
    }

    private final ReferenceTrack first;
    private final ReferenceTrack second;
    private final double length;
    private final State start;

    /**
     * @param system the system
     * @param start  the unnudged initial state
     * @throws NBodySystem.SingularConfigurationException if either start state is singular
     * @throws IllegalArgumentException                   for null arguments
     */
    public TwinRun(NBodySystem system, State start) {
        this(system, start, Nudge.DEFAULT);
    }

    /**
     * @param system the system
     * @param start  the unnudged initial state
     * @param nudge  which body to move and in which direction
     * @throws NBodySystem.SingularConfigurationException if either start state is singular
     * @throws IllegalArgumentException                   for null arguments or a body index outside the system
     */
    public TwinRun(NBodySystem system, State start, Nudge nudge) {
        if (system == null || start == null || nudge == null) {
            throw new IllegalArgumentException("system, start state and nudge must not be null");
        }
        if (nudge.body() >= start.bodyCount()) {
            throw new IllegalArgumentException("nudged body " + nudge.body() + " does not exist: the system has "
                    + start.bodyCount() + " bodies");
        }
        this.start = start;
        this.length = characteristicLength(start);
        double firstStep = 1e-3 * shortestTimescale(system, start);
        this.first = new ReferenceTrack(system, start, firstStep, ReferenceTrack.DEFAULT_TOLERANCES);
        this.second = new ReferenceTrack(system, nudged(start, nudge), firstStep, ReferenceTrack.DEFAULT_TOLERANCES);
    }

    /** The start state with body 0 moved by {@link #NUDGE} characteristic lengths along +x. */
    public static State nudged(State start) {
        return nudged(start, Nudge.DEFAULT);
    }

    /**
     * The start state with one body moved by {@link #NUDGE} characteristic lengths along one axis direction.
     *
     * @throws IllegalArgumentException if the body does not exist
     */
    public static State nudged(State start, Nudge nudge) {
        if (nudge.body() >= start.bodyCount()) {
            throw new IllegalArgumentException("nudged body " + nudge.body() + " does not exist: the system has "
                    + start.bodyCount() + " bodies");
        }
        double[] q = start.positions();
        q[3 * nudge.body() + nudge.direction().axis()] += nudge.direction().sign() * NUDGE * characteristicLength(start);
        return State.of(start.time(), q, start.velocities());
    }

    /** Largest pair distance of the state (3-D); 1 if there is only one body or all bodies coincide. */
    public static double characteristicLength(State state) {
        double[] q = state.positions();
        int n = state.bodyCount();
        double largest = 0.0;
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                double dx = q[3 * j] - q[3 * i];
                double dy = q[3 * j + 1] - q[3 * i + 1];
                double dz = q[3 * j + 2] - q[3 * i + 2];
                largest = Math.max(largest, Math.sqrt(dx * dx + dy * dy + dz * dz));
            }
        }
        return largest > 0.0 ? largest : 1.0;
    }

    /** The characteristic length used to make separations dimensionless. */
    public double length() {
        return length;
    }

    /** The separation at the start time, in length units: exactly the nudge. */
    public double initialSeparation() {
        return NUDGE * length;
    }

    /**
     * The state of the pair at {@code time}.
     *
     * @throws IllegalArgumentException if {@code time} is not finite or is before the start time
     */
    public Reading at(double time) {
        if (!Double.isFinite(time) || time < start.time()) {
            throw new IllegalArgumentException("time must be finite and >= the start time " + start.time()
                    + ", got " + time);
        }
        double[] a;
        double[] b;
        try {
            a = first.positionsAt(time);
            b = second.positionsAt(time);
        } catch (IllegalStateException e) {
            return new Reading(time, null, Double.NaN, length, e.getMessage());
        }
        double[] offsets = new double[a.length];
        double largest = 0.0;
        for (int i = 0; i < a.length; i++) {
            offsets[i] = b[i] - a[i];
        }
        for (int body = 0; body < a.length / 3; body++) {
            double dx = offsets[3 * body];
            double dy = offsets[3 * body + 1];
            double dz = offsets[3 * body + 2];
            largest = Math.max(largest, Math.sqrt(dx * dx + dy * dy + dz * dz));
        }
        return new Reading(time, offsets, largest, length, null);
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
