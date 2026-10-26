package dev.chaoslab.analysis;

import dev.chaoslab.integration.DormandPrince45Integrator;
import dev.chaoslab.integration.DormandPrince45Integrator.Tolerances;
import dev.chaoslab.physics.NBodySystem;
import dev.chaoslab.physics.State;
import java.util.Arrays;
import java.util.Random;

/**
 * An incremental estimate of the largest Lyapunov exponent along one trajectory, meant to run alongside an
 * interactive simulation. It measures <em>physical</em> sensitivity to the initial conditions, not the error of any
 * particular integrator: the trajectory and its nearby "shadow" are both integrated with Dormand-Prince 5(4) at a
 * very tight tolerance, whatever integrator the person chose for the displayed run.
 *
 * <h2>Method</h2>
 * Benettin et al.: the shadow starts at a tiny distance d0 from the reference in a random direction. Every
 * {@link #interval()} time units both are advanced to the same grid time, the separation d is measured,
 * ln(d / d_start) is added to a running sum, and the shadow is pulled back to distance d0 along the current
 * separation direction. The cumulative log growth against time has asymptotic slope equal to the exponent.
 * Both trajectories land exactly on the grid times t0 + k * interval, so the result depends only on the initial
 * state, the tolerances, the seed and the number of intervals, never on how often or when the caller asks.
 *
 * <h2>A dimensionless distance</h2>
 * Positions and velocities have different units, and in SI units the numbers differ by many orders of magnitude (an
 * offset of 1e-8 metres is invisible next to 1.5e11 metres). The separation is therefore measured in scaled
 * coordinates: positions divided by the {@link #characteristicLength()} (the largest pair distance at the start) and
 * velocities multiplied by {@link #characteristicTime()} (the shortest pair timescale
 * sqrt(r^3 / (G (m_i + m_j))) at the start) and divided by the same length. d0 is a dimensionless fraction of the
 * size of the system. The exponent itself does not depend on this choice (all norms are equivalent), but finite-time
 * values differ slightly.
 *
 * <h2>What the numbers mean</h2>
 * <ul>
 *   <li>{@link #runningExponent()} is the total log growth divided by the elapsed time; it converges like
 *       lambda + b/t. {@link #recentSlope(double)} fits a line to the later part of the cumulative growth, which
 *       removes that offset and converges much faster. Both are in inverse simulation time units; multiply by
 *       {@link #characteristicTime()} for a dimensionless value.</li>
 *   <li>A regular orbit gives a value that decays toward zero, not exactly zero.</li>
 *   <li>It is a finite-time estimate along this one trajectory; local growth rates vary along it.</li>
 *   <li>{@link #maxRelativeEnergyDrift()} is a trust indicator for the reference trajectory.</li>
 *   <li>When the trajectory suffers a collision or the tolerance cannot be met, the tracker stops and reports
 *       {@link #failure()}; the values computed before that stay available.</li>
 * </ul>
 *
 * <p>Not thread-safe: use one thread, or synchronise externally.
 */
public final class LyapunovTracker {

    /** Tolerance of both trajectories. Tighter than any interactive run, so integration error is negligible. */
    public static final Tolerances DEFAULT_TOLERANCES = new Tolerances(1e-13, 1e-13);

    /** Default dimensionless initial separation. */
    public static final double DEFAULT_SEPARATION = 1e-8;

    private static final int MAX_STEPS_PER_INTERVAL = 5_000_000;
    private static final int MIN_POINTS_FOR_SLOPE = 4;

    private final NBodySystem system;
    private final Tolerances tolerances;
    private final double t0;
    private final double interval;
    private final double length;
    private final double timescale;
    private final double d0;
    private final double e0;
    private final double energyScale;
    private final DormandPrince45Integrator referenceIntegrator = new DormandPrince45Integrator();
    private final DormandPrince45Integrator shadowIntegrator = new DormandPrince45Integrator();

    private double[] q;
    private double[] v;
    private double[] sq;
    private double[] sv;
    private double startSeparation;
    private double logSum;
    private double maxDrift;
    private int done;
    private double[] times = new double[64];
    private double[] growth = new double[64];
    private String failure;

    /**
     * @param system            the system
     * @param start             initial state of the trajectory to analyse
     * @param tolerances        tolerances of both trajectories, usually {@link #DEFAULT_TOLERANCES}
     * @param initialSeparation dimensionless d0, finite and &gt; 0 (see {@link #DEFAULT_SEPARATION}); must be well
     *                          above rounding error (1e-12 or more) and small enough to stay in the linear regime
     * @param seed              seed for the random direction of the initial offset (same seed, same result)
     * @throws NBodySystem.SingularConfigurationException if {@code start} is singular
     * @throws IllegalArgumentException                   for invalid arguments
     */
    public LyapunovTracker(NBodySystem system, State start, Tolerances tolerances, double initialSeparation,
                           long seed) {
        if (system == null || start == null || tolerances == null) {
            throw new IllegalArgumentException("system, start state and tolerances must not be null");
        }
        if (system.bodyCount() != start.bodyCount()) {
            throw new IllegalArgumentException("State has " + start.bodyCount() + " bodies but the system has "
                    + system.bodyCount());
        }
        if (!Double.isFinite(initialSeparation) || initialSeparation < 1e-12 || initialSeparation > 1e-2) {
            throw new IllegalArgumentException("initialSeparation must be between 1e-12 and 1e-2 (dimensionless),"
                    + " got " + initialSeparation);
        }
        this.system = system;
        this.tolerances = tolerances;
        this.t0 = start.time();
        this.d0 = initialSeparation;
        this.q = start.positions();
        this.v = start.velocities();
        system.potentialEnergy(q); // fail fast, with the real cause, on a singular start state
        this.e0 = system.kineticEnergy(v) + system.potentialEnergy(q);
        this.energyScale = e0 != 0.0 ? Math.abs(e0) : 1.0;

        double longest = 0.0;
        double shortest = Double.POSITIVE_INFINITY;
        final int n = system.bodyCount();
        final double eps2 = system.softening() * system.softening();
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                double dx = q[3 * j] - q[3 * i];
                double dy = q[3 * j + 1] - q[3 * i + 1];
                double dz = q[3 * j + 2] - q[3 * i + 2];
                double r2 = dx * dx + dy * dy + dz * dz;
                longest = Math.max(longest, Math.sqrt(r2));
                double rs2 = r2 + eps2;
                double tau = Math.sqrt(rs2 * Math.sqrt(rs2)
                        / (system.gravitationalConstant() * (system.mass(i) + system.mass(j))));
                shortest = Math.min(shortest, tau);
            }
        }
        this.length = longest > 0.0 ? longest : (system.softening() > 0.0 ? system.softening() : 1.0);
        this.timescale = shortest;
        this.interval = shortest;
        if (!(timescale > 0.0) || !Double.isFinite(timescale) || !Double.isFinite(length)) {
            throw new IllegalArgumentException("Cannot derive a time scale from this configuration (timescale = "
                    + timescale + ", length = " + length + ")");
        }

        this.sq = q.clone();
        this.sv = v.clone();
        offsetRandomly(seed);
        this.startSeparation = scaledDistance();
    }

    // ---------------------------------------------------------------- advancing

    /**
     * Completes every renormalisation interval that ends at or before {@code time}. Calling with a time earlier than
     * what has been reached does nothing. After a failure it does nothing.
     */
    public void advanceTo(double time) {
        if (!Double.isFinite(time)) {
            throw new IllegalArgumentException("time must be finite, got " + time);
        }
        while (failure == null) {
            double tStart = t0 + done * interval;
            double tNext = t0 + (done + 1) * interval;
            if (tNext > time) {
                return;
            }
            try {
                q = advance(referenceIntegrator, q, v, tStart, tNext);
                sq = advance(shadowIntegrator, sq, sv, tStart, tNext);
            } catch (RuntimeException e) {
                failure = "Stopped after " + done + " renormalisations (t = " + (tStart - t0) + " after the start): "
                        + e.getClass().getSimpleName() + ": " + e.getMessage();
                return;
            }
            double d = scaledDistance();
            if (!(d > 0.0) || !Double.isFinite(d)) {
                failure = "Separation became " + d + " at t = " + tNext + ": the trajectories merged or the"
                        + " computation overflowed.";
                return;
            }
            logSum += Math.log(d / startSeparation);
            record(tNext - t0, logSum);
            double energy = system.kineticEnergy(v) + system.potentialEnergy(q);
            maxDrift = Math.max(maxDrift, Math.abs(energy - e0) / energyScale);
            double scale = d0 / d;
            for (int i = 0; i < q.length; i++) {
                sq[i] = q[i] + (sq[i] - q[i]) * scale;
                sv[i] = v[i] + (sv[i] - v[i]) * scale;
            }
            startSeparation = scaledDistance();
        }
    }

    /**
     * Advances one trajectory from tStart to exactly tNext. The new velocities are written into {@code vel}; the new
     * positions are returned as a new array.
     */
    private double[] advance(DormandPrince45Integrator integrator, double[] pos, double[] vel, double tStart,
                             double tNext) {
        DormandPrince45Integrator.AdaptiveResult r = integrator.integrate(system, State.of(tStart, pos, vel), tNext,
                (tNext - tStart) / 20.0, tolerances, MAX_STEPS_PER_INTERVAL);
        State end = r.finalState();
        System.arraycopy(end.velocities(), 0, vel, 0, vel.length);
        return end.positions();
    }

    // ---------------------------------------------------------------- results

    /** Number of completed renormalisations. */
    public int renormalisations() {
        return done;
    }

    /** Time elapsed since the start at the last completed renormalisation (0 before the first). */
    public double elapsed() {
        return done == 0 ? 0.0 : times[done - 1];
    }

    /** Total log growth divided by the elapsed time, or NaN before the first renormalisation. */
    public double runningExponent() {
        return done == 0 ? Double.NaN : growth[done - 1] / times[done - 1];
    }

    /**
     * Least-squares slope of the cumulative log growth against time over the latest {@code fraction} of the
     * renormalisations (at least {@value #MIN_POINTS_FOR_SLOPE} points), or NaN if there are not enough yet.
     *
     * @param fraction in (0, 1]; 0.5 uses the later half
     */
    public double recentSlope(double fraction) {
        if (!(fraction > 0.0 && fraction <= 1.0)) {
            throw new IllegalArgumentException("fraction must be in (0, 1], got " + fraction);
        }
        int count = Math.max(MIN_POINTS_FOR_SLOPE, (int) Math.ceil(fraction * done));
        if (done < MIN_POINTS_FOR_SLOPE) {
            return Double.NaN;
        }
        count = Math.min(count, done);
        int first = done - count;
        double st = 0;
        double sy = 0;
        double stt = 0;
        double sty = 0;
        for (int i = first; i < done; i++) {
            st += times[i];
            sy += growth[i];
            stt += times[i] * times[i];
            sty += times[i] * growth[i];
        }
        return (count * sty - st * sy) / (count * stt - st * st);
    }

    /** Copy of the times (since the start) of the completed renormalisations. */
    public double[] times() {
        return Arrays.copyOf(times, done);
    }

    /** Copy of the cumulative log growth at each completed renormalisation. */
    public double[] cumulativeGrowth() {
        return Arrays.copyOf(growth, done);
    }

    /** Largest |E - E0| / |E0| of the reference trajectory at the renormalisation times; a trust indicator. */
    public double maxRelativeEnergyDrift() {
        return maxDrift;
    }

    /** Why the tracker stopped early, or null if it has not. */
    public String failure() {
        return failure;
    }

    /**
     * The current dimensionless distance between the trajectory and its shadow: the initial separation at the
     * start, and again after every renormalisation (up to rounding).
     */
    public double separation() {
        return startSeparation;
    }

    /** Time between renormalisations: the shortest pair timescale at the start. */
    public double interval() {
        return interval;
    }

    /** The largest pair distance at the start, used to make positions dimensionless. */
    public double characteristicLength() {
        return length;
    }

    /** The shortest pair timescale sqrt(r^3 / (G (m_i + m_j))) at the start, used to make velocities dimensionless. */
    public double characteristicTime() {
        return timescale;
    }

    // ---------------------------------------------------------------- helpers

    private void record(double elapsed, double cumulative) {
        if (done == times.length) {
            times = Arrays.copyOf(times, 2 * done);
            growth = Arrays.copyOf(growth, 2 * done);
        }
        times[done] = elapsed;
        growth[done] = cumulative;
        done++;
    }

    /** Adds a vector of scaled length d0, uniformly random in direction, to the shadow state. */
    private void offsetRandomly(long seed) {
        Random rng = new Random(seed);
        int n = q.length;
        double[] u = new double[2 * n];
        double norm2 = 0.0;
        for (int i = 0; i < u.length; i++) {
            u[i] = rng.nextGaussian();
            norm2 += u[i] * u[i];
        }
        double scale = d0 / Math.sqrt(norm2);
        double positionUnit = length;
        double velocityUnit = length / timescale;
        for (int i = 0; i < n; i++) {
            sq[i] += u[i] * scale * positionUnit;
            sv[i] += u[n + i] * scale * velocityUnit;
        }
    }

    private double scaledDistance() {
        double sum = 0.0;
        double velocityScale = timescale / length;
        for (int i = 0; i < q.length; i++) {
            double dq = (sq[i] - q[i]) / length;
            double dv = (sv[i] - v[i]) * velocityScale;
            sum += dq * dq + dv * dv;
        }
        return Math.sqrt(sum);
    }
}
