package dev.chaoslab.analysis;

import dev.chaoslab.integration.DormandPrince45Integrator;
import dev.chaoslab.integration.DormandPrince45Integrator.Tolerances;
import dev.chaoslab.physics.NBodySystem;
import dev.chaoslab.physics.State;
import java.util.ArrayDeque;
import java.util.Iterator;

/**
 * A high-accuracy reference trajectory that can be asked for the positions at any simulation time. It exists so that
 * the trajectory shown on screen, computed by whatever integrator and step size the person chose, can be compared
 * with something much more accurate <em>at exactly the same time</em>.
 *
 * <h2>How it works</h2>
 * The track integrates the same initial state with {@link DormandPrince45Integrator#adaptiveStep} at a very tight
 * tolerance ({@link #DEFAULT_TOLERANCES}, 1e-13) and keeps the accepted steps as nodes (time, positions, velocities,
 * accelerations). A query for time t advances the integration until a node at or after t exists, then interpolates
 * the positions inside the bracketing step with a quintic Hermite polynomial that matches position, velocity and
 * acceleration at both ends. Its error is of order h^6 times the sixth derivative, which for the steps the error
 * controller chooses is far below the integration tolerance (see the tests, which compare with an exact orbit).
 *
 * <h2>Determinism</h2>
 * The sequence of nodes depends only on the initial state, the tolerances and the first step. The interpolant depends
 * only on the two nodes around t. So the positions returned for a given t are bit-identical whatever times were asked
 * before, in whatever order, and whatever the pacing of the caller. Asking for a time earlier than the retained
 * history restarts the integration from the initial state, which reproduces the same nodes bit for bit.
 *
 * <h2>Limits</h2>
 * <ul>
 *   <li>The reference is itself a numerical solution. For chaotic motion its error grows exponentially like any
 *       other, so beyond a predictability horizon the gap to the reference no longer measures the error of the
 *       displayed run. Measured at tolerance 1e-13: on the exact (unperturbed) Lagrange triangle the reference
 *       stays within 6e-10 of the exact solution at t = 30 and 3e-5 at t = 50, but has lost it (6e-3 at t = 60,
 *       1.7 at t = 70); on the triangle perturbed by 0.01 it differs from a tolerance-1e-14 run by 3e-7 at t = 60,
 *       9e-6 at t = 80 and 2e-4 at t = 100, which is an estimate of its own error there.
 *       {@link #maxRelativeEnergyDrift()} is a trust indicator but does not prove accuracy of the positions.</li>
 *   <li>If the integration fails (a collision, or a tolerance that double precision cannot meet), the track keeps
 *       everything computed so far and reports {@link #failure()}. Queries beyond the last node then throw
 *       {@link IllegalStateException}.</li>
 * </ul>
 *
 * <p>Not thread-safe: use one thread, or synchronise externally.
 */
public final class ReferenceTrack {

    /** Absolute and relative tolerance of the reference: far tighter than any interactive run. */
    public static final Tolerances DEFAULT_TOLERANCES = new Tolerances(1e-13, 1e-13);

    /** Nodes kept behind the bracketing step, so that small backward jumps (a rollback) need no restart. */
    private static final int RETAINED_BEHIND = 16;

    private record Node(double t, double[] q, double[] v, double[] a) {
    }

    private final NBodySystem system;
    private final State start;
    private final double firstStep;
    private final Tolerances tolerances;
    private final double e0;
    private final double energyScale;

    private final ArrayDeque<Node> nodes = new ArrayDeque<>();
    private DormandPrince45Integrator integrator;
    private double[] q;
    private double[] v;
    private double t;
    private double maxDrift;
    private String failure;
    private int rewinds;
    private long acceptedSteps;

    /**
     * @param system    the system
     * @param start     initial state (its time is the earliest time that can be queried)
     * @param firstStep first trial step, finite and &gt; 0 (the controller adjusts it immediately)
     * @param tolerances error tolerances of the reference, usually {@link #DEFAULT_TOLERANCES}
     * @throws NBodySystem.SingularConfigurationException if {@code start} is singular
     * @throws IllegalArgumentException                   for invalid arguments
     */
    public ReferenceTrack(NBodySystem system, State start, double firstStep, Tolerances tolerances) {
        if (system == null || start == null || tolerances == null) {
            throw new IllegalArgumentException("system, start state and tolerances must not be null");
        }
        if (system.bodyCount() != start.bodyCount()) {
            throw new IllegalArgumentException("State has " + start.bodyCount() + " bodies but the system has "
                    + system.bodyCount());
        }
        if (!Double.isFinite(firstStep) || firstStep <= 0.0) {
            throw new IllegalArgumentException("firstStep must be finite and > 0, got " + firstStep);
        }
        this.system = system;
        this.start = start;
        this.firstStep = firstStep;
        this.tolerances = tolerances;
        double[] q0 = start.positions();
        double[] v0 = start.velocities();
        system.potentialEnergy(q0); // fail fast, with the real cause, on a singular start state
        this.e0 = system.kineticEnergy(v0) + system.potentialEnergy(q0);
        this.energyScale = e0 != 0.0 ? Math.abs(e0) : 1.0;
        restart();
    }

    // ---------------------------------------------------------------- queries

    /**
     * Positions of all bodies at simulation time {@code time}, as a flat array (x0, y0, z0, x1, ...).
     *
     * @throws IllegalArgumentException if {@code time} is before the start time or not finite
     * @throws IllegalStateException    if the integration failed before reaching {@code time}; the message says why
     */
    public double[] positionsAt(double time) {
        if (!Double.isFinite(time) || time < start.time()) {
            throw new IllegalArgumentException("time must be finite and >= the start time " + start.time()
                    + ", got " + time);
        }
        if (time < nodes.peekFirst().t()) {
            restart(); // asked for a time older than the retained history: replay from the start (same bits)
            rewinds++;
        }
        while (nodes.peekLast().t() < time) {
            if (failure != null) {
                throw new IllegalStateException("The reference trajectory ends at t = " + nodes.peekLast().t()
                        + ", before the requested t = " + time + ": " + failure);
            }
            advanceOneStep();
        }
        Node previous = null;
        Node following = null;
        Iterator<Node> it = nodes.iterator();
        while (it.hasNext()) {
            Node n = it.next();
            if (n.t() >= time) {
                following = n;
                break;
            }
            previous = n;
        }
        if (previous == null) {
            return following.q().clone(); // time is exactly the first node (the start)
        }
        double[] result = interpolate(previous.t(), following.t(), previous.q(), previous.v(), previous.a(),
                following.q(), following.v(), following.a(), time);
        trim(previous);
        return result;
    }

    /** Time of the latest node: the reference has been integrated at least this far. */
    public double reachedTime() {
        return nodes.peekLast().t();
    }

    /**
     * Largest |E - E0| / |E0| of the reference over its nodes (E0 itself if it is zero). The exact dynamics conserve
     * energy, so a large value means the reference is failing. It is a trust indicator, not a proof of accuracy.
     */
    public double maxRelativeEnergyDrift() {
        return maxDrift;
    }

    /** Why the integration stopped early, or null if it has not. */
    public String failure() {
        return failure;
    }

    /** How many times a query for an old time forced a replay from the start. */
    public int rewinds() {
        return rewinds;
    }

    /** Number of nodes currently held in memory; stays small however far the queries advance. */
    public int retainedNodes() {
        return nodes.size();
    }

    /** Accepted integration steps taken so far in the current replay. */
    public long acceptedSteps() {
        return acceptedSteps;
    }

    /** Largest distance between corresponding bodies in two flat position arrays of equal length. */
    public static double maxPositionGap(double[] a, double[] b) {
        if (a.length != b.length || a.length % 3 != 0) {
            throw new IllegalArgumentException("Position arrays must have equal length divisible by 3, got "
                    + a.length + " and " + b.length);
        }
        double worst = 0.0;
        for (int i = 0; i < a.length; i += 3) {
            double dx = a[i] - b[i];
            double dy = a[i + 1] - b[i + 1];
            double dz = a[i + 2] - b[i + 2];
            worst = Math.max(worst, Math.sqrt(dx * dx + dy * dy + dz * dz));
        }
        return worst;
    }

    // ---------------------------------------------------------------- integration

    private void restart() {
        nodes.clear();
        integrator = new DormandPrince45Integrator();
        q = start.positions();
        v = start.velocities();
        t = start.time();
        maxDrift = 0.0;
        failure = null;
        acceptedSteps = 0;
        nodes.addLast(node());
    }

    private Node node() {
        double[] a = new double[q.length];
        system.accelerations(q, a);
        return new Node(t, q.clone(), v.clone(), a);
    }

    private void advanceOneStep() {
        double[] qTrial = q.clone();
        double[] vTrial = v.clone();
        try {
            DormandPrince45Integrator.AcceptedStep step = integrator.adaptiveStep(system, qTrial, vTrial, t,
                    firstStep, tolerances);
            double tNext = t + step.stepSize();
            // everything that can throw is evaluated before any field changes, so a failure leaves a consistent track
            double[] a = new double[qTrial.length];
            system.accelerations(qTrial, a);
            double energy = system.kineticEnergy(vTrial) + system.potentialEnergy(qTrial);
            q = qTrial;
            v = vTrial;
            t = tNext;
            nodes.addLast(new Node(t, q.clone(), v.clone(), a));
            acceptedSteps++;
            maxDrift = Math.max(maxDrift, Math.abs(energy - e0) / energyScale);
        } catch (RuntimeException e) {
            failure = e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    /** Drops nodes that are more than RETAINED_BEHIND steps older than the interval that was just used. */
    private void trim(Node intervalStart) {
        int behind = 0;
        for (Node n : nodes) {
            if (n == intervalStart) {
                break;
            }
            behind++;
        }
        for (int i = RETAINED_BEHIND; i < behind; i++) {
            nodes.removeFirst();
        }
    }

    // ---------------------------------------------------------------- interpolation

    /**
     * Quintic Hermite interpolation of positions on [t0, t1] from position, velocity and acceleration at both ends.
     * Reproduces every polynomial of degree 5 exactly, and returns the end values exactly at t = t0 and t = t1.
     */
    static double[] interpolate(double t0, double t1, double[] q0, double[] v0, double[] a0, double[] q1,
                                double[] v1, double[] a1, double time) {
        final double h = t1 - t0;
        final double s = (time - t0) / h;
        final double s2 = s * s;
        final double s3 = s2 * s;
        final double s4 = s3 * s;
        final double s5 = s4 * s;
        final double hq0 = 1.0 - 10.0 * s3 + 15.0 * s4 - 6.0 * s5;
        final double hv0 = (s - 6.0 * s3 + 8.0 * s4 - 3.0 * s5) * h;
        final double ha0 = (0.5 * s2 - 1.5 * s3 + 1.5 * s4 - 0.5 * s5) * h * h;
        final double hq1 = 10.0 * s3 - 15.0 * s4 + 6.0 * s5;
        final double hv1 = (-4.0 * s3 + 7.0 * s4 - 3.0 * s5) * h;
        final double ha1 = (0.5 * s3 - s4 + 0.5 * s5) * h * h;
        double[] out = new double[q0.length];
        for (int i = 0; i < out.length; i++) {
            out[i] = q0[i] * hq0 + v0[i] * hv0 + a0[i] * ha0 + q1[i] * hq1 + v1[i] * hv1 + a1[i] * ha1;
        }
        return out;
    }
}
