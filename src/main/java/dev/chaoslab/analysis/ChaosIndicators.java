package dev.chaoslab.analysis;

import dev.chaoslab.integration.Integrator;
import dev.chaoslab.physics.NBodySystem;
import dev.chaoslab.physics.State;
import java.util.Arrays;
import java.util.Random;
import java.util.function.Supplier;

/**
 * Indicators of sensitive dependence on initial conditions, computed by integrating a reference trajectory together
 * with a nearby "shadow" trajectory.
 *
 * <h2>Phase-space distance</h2>
 * The separation of two states is the Euclidean norm of the difference of the 6N-vector (positions, velocities).
 * It therefore depends on the unit system. The limiting Lyapunov exponent does not (all norms on a finite-dimensional
 * space are equivalent), but finite-time estimates do, slightly.
 *
 * <h2>Largest Lyapunov exponent (Benettin et al. method)</h2>
 * The shadow starts at distance {@code d0} from the reference in a random direction. Both are advanced by the same
 * integrator and step size for a fixed number of steps; then the separation d is measured, ln(d/d0) is accumulated,
 * and the shadow is pulled back to distance d0 along the current separation direction. The estimate after time t is
 * <pre>
 *     lambda(t) = (1/t) * sum_k ln(d_k / d0).
 * </pre>
 * The renormalisation keeps the shadow in the linear regime however chaotic the motion is, which is why this works
 * for exponentially diverging trajectories where a plain two-trajectory comparison saturates.
 *
 * <h2>What the number means, and does not mean</h2>
 * <ul>
 *   <li>It is a <em>finite-time</em> estimate along one trajectory. The running value lambda(t) converges slowly,
 *       like lambda + b/t, because the initial offset has some arbitrary component along the fastest-growing
 *       direction (b depends on the random starting direction). {@link LyapunovResult#slope} fits a line to the
 *       cumulative log growth over a window after the transient, which removes that offset and converges much
 *       faster; the running series is still returned so the convergence can be inspected.</li>
 *   <li>A regular (non-chaotic) orbit gives an estimate that decays toward zero like ln(t)/t, not exactly zero.</li>
 *   <li>The exponent describes the <em>numerical</em> trajectory. If the integrator or step size is poor, the
 *       estimate is contaminated by numerical error. Repeating the run with smaller steps and a different
 *       integrator, and checking that the value converges, is how physical chaos is told apart from numerical
 *       artefacts. Rounding error itself is amplified at the physical rate, so an unstable reference orbit stays
 *       faithful only for about ln(1e16)/lambda time units.</li>
 *   <li>{@link LyapunovResult#maxRelativeEnergyDrift} is a trust indicator: the exact dynamics conserve energy, so a
 *       large drift of the reference trajectory means the integrator is failing (typically at a close encounter)
 *       and the exponent should not be believed.</li>
 * </ul>
 * The exponent is in inverse simulation time units.
 */
public final class ChaosIndicators {

    private ChaosIndicators() {
    }

    // ---------------------------------------------------------------- Lyapunov exponent

    /**
     * Result of {@link #lyapunov}. Arrays have one entry per completed renormalisation.
     *
     * @param time             simulation time elapsed since the start at each renormalisation
     * @param separation       phase-space separation just before each renormalisation
     * @param logGrowth        cumulative sum of ln(separation / d0) after each renormalisation
     * @param runningExponent  lambda(t) = logGrowth / t after each renormalisation
     * @param exponent         the last running value, or NaN if no renormalisation completed
     * @param initialSeparation the distance d0 used
     * @param maxRelativeEnergyDrift largest |E - E0| / |E0| of the reference trajectory at the renormalisation
     *                         times (E0 itself if it is zero); a trust indicator, see the class description
     * @param failure          null if the whole run completed; otherwise why it stopped early (the arrays then hold
     *                         everything computed up to that point)
     */
    public record LyapunovResult(
            double[] time,
            double[] separation,
            double[] logGrowth,
            double[] runningExponent,
            double exponent,
            double initialSeparation,
            double maxRelativeEnergyDrift,
            String failure) {

        public int renormalisations() {
            return time.length;
        }

        public boolean completed() {
            return failure == null;
        }

        /**
         * Least-squares slope of the cumulative log growth against time over the renormalisations with
         * {@code t0 <= t <= t1}. This estimates the exponent over that window without the 1/t offset that the
         * running value carries from the initial transient.
         *
         * @throws IllegalArgumentException if fewer than two renormalisations fall in the window
         */
        public double slope(double t0, double t1) {
            int count = 0;
            double st = 0;
            double sy = 0;
            double stt = 0;
            double sty = 0;
            for (int i = 0; i < time.length; i++) {
                if (time[i] >= t0 && time[i] <= t1) {
                    count++;
                    st += time[i];
                    sy += logGrowth[i];
                    stt += time[i] * time[i];
                    sty += time[i] * logGrowth[i];
                }
            }
            if (count < 2) {
                throw new IllegalArgumentException("Fewer than two renormalisations in [" + t0 + ", " + t1 + "]");
            }
            return (count * sty - st * sy) / (count * stt - st * st);
        }

        /** CSV with header: time,separation,log_growth,running_exponent. */
        public String toCsv() {
            StringBuilder sb = new StringBuilder("time,separation,log_growth,running_exponent\n");
            for (int i = 0; i < time.length; i++) {
                sb.append(time[i]).append(',').append(separation[i]).append(',').append(logGrowth[i]).append(',')
                        .append(runningExponent[i]).append('\n');
            }
            return sb.toString();
        }
    }

    /**
     * Estimates the largest Lyapunov exponent along the trajectory that starts at {@code start}.
     *
     * @param sys                      the system
     * @param start                    initial state of the reference trajectory
     * @param integrators              creates a fresh integrator; called twice (reference and shadow)
     * @param stepSize                 fixed step, finite and &gt; 0
     * @param stepsPerRenormalisation  steps between renormalisations, at least 1
     * @param renormalisations         number of renormalisations (total time = this * steps * stepSize), at least 1
     * @param initialSeparation        d0, finite and &gt; 0; small enough to stay in the linear regime but well above
     *                                 rounding error (1e-8 is a sensible default in order-one units). The separation
     *                                 realised in floating point differs from it by about 1e-16 / d0 relatively, which
     *                                 is accounted for.
     * @param seed                     seed for the random initial direction of the offset (same seed, same result)
     * @return the running estimates; a numerical failure during the run is reported in
     *         {@link LyapunovResult#failure()} rather than thrown
     * @throws NBodySystem.SingularConfigurationException if {@code start} itself is singular
     * @throws IllegalArgumentException                   for invalid parameters
     */
    public static LyapunovResult lyapunov(NBodySystem sys, State start, Supplier<Integrator> integrators,
            double stepSize, int stepsPerRenormalisation, int renormalisations, double initialSeparation,
            long seed) {
        requireCommon(sys, start, integrators, stepSize, initialSeparation);
        if (stepsPerRenormalisation < 1 || renormalisations < 1) {
            throw new IllegalArgumentException("stepsPerRenormalisation and renormalisations must be >= 1, got "
                    + stepsPerRenormalisation + " and " + renormalisations);
        }
        final int n3 = sys.stateLength();
        double[] q = start.positions();
        double[] v = start.velocities();
        double[] sq = q.clone();
        double[] sv = v.clone();
        offsetRandomly(sq, sv, initialSeparation, seed);
        // The offset is added to numbers of order one, so the separation actually realised differs from the nominal
        // one in the last digits (relatively by about 1e-16 / d0). Every growth ratio is therefore taken against the
        // separation measured at the start of its interval, not against the nominal value.
        double startSeparation = distance(q, v, sq, sv);

        Integrator reference = integrators.get();
        Integrator shadow = integrators.get();
        double[] time = new double[renormalisations];
        double[] separation = new double[renormalisations];
        double[] growth = new double[renormalisations];
        double[] running = new double[renormalisations];
        double logSum = 0.0;
        double e0 = sys.kineticEnergy(v) + sys.potentialEnergy(q);
        double energyScale = e0 != 0.0 ? Math.abs(e0) : 1.0;
        double maxDrift = 0.0;
        int done = 0;
        String failure = null;

        try {
            for (int k = 1; k <= renormalisations; k++) {
                for (int s = 0; s < stepsPerRenormalisation; s++) {
                    reference.step(sys, q, v, stepSize);
                    shadow.step(sys, sq, sv, stepSize);
                }
                double d = distance(q, v, sq, sv);
                if (!(d > 0.0) || !Double.isFinite(d)) {
                    failure = "Separation became " + d + " at t = " + (k * (double) stepsPerRenormalisation
                            * stepSize) + ": trajectories merged or the computation overflowed.";
                    break;
                }
                logSum += Math.log(d / startSeparation);
                double t = k * (double) stepsPerRenormalisation * stepSize;
                time[done] = t;
                separation[done] = d;
                growth[done] = logSum;
                running[done] = logSum / t;
                maxDrift = Math.max(maxDrift,
                        Math.abs(sys.kineticEnergy(v) + sys.potentialEnergy(q) - e0) / energyScale);
                done++;
                double scale = initialSeparation / d;
                for (int i = 0; i < n3; i++) {
                    sq[i] = q[i] + (sq[i] - q[i]) * scale;
                    sv[i] = v[i] + (sv[i] - v[i]) * scale;
                }
                startSeparation = distance(q, v, sq, sv);
            }
        } catch (RuntimeException e) {
            failure = "Integration failed after " + done + " renormalisations (t = "
                    + (done * (double) stepsPerRenormalisation * stepSize) + "): " + e.getClass().getSimpleName()
                    + ": " + e.getMessage();
        }
        double[] t = Arrays.copyOf(time, done);
        double[] d = Arrays.copyOf(separation, done);
        double[] g = Arrays.copyOf(growth, done);
        double[] r = Arrays.copyOf(running, done);
        return new LyapunovResult(t, d, g, r, done == 0 ? Double.NaN : r[done - 1], initialSeparation, maxDrift,
                failure);
    }

    // ---------------------------------------------------------------- nearby-trajectory divergence

    /**
     * Separation of a reference and a nearby trajectory without renormalisation.
     *
     * @param time       sample times (including t = 0 relative to the start)
     * @param separation phase-space distance at each sample
     * @param failure    null if the run completed, otherwise why it stopped early
     */
    public record DivergenceResult(double[] time, double[] separation, String failure) {

        /**
         * Least-squares slope of ln(separation) against time over the samples with {@code t0 <= t <= t1}, an
         * estimate of the exponential growth rate in that window.
         *
         * @throws IllegalArgumentException if fewer than two samples fall in the window
         */
        public double logSlope(double t0, double t1) {
            int count = 0;
            double st = 0;
            double sy = 0;
            double stt = 0;
            double sty = 0;
            for (int i = 0; i < time.length; i++) {
                if (time[i] >= t0 && time[i] <= t1 && separation[i] > 0.0) {
                    double y = Math.log(separation[i]);
                    count++;
                    st += time[i];
                    sy += y;
                    stt += time[i] * time[i];
                    sty += time[i] * y;
                }
            }
            if (count < 2) {
                throw new IllegalArgumentException("Fewer than two samples in [" + t0 + ", " + t1 + "]");
            }
            return (count * sty - st * sy) / (count * stt - st * st);
        }
    }

    /**
     * Integrates a reference and a shadow trajectory (offset by {@code initialSeparation} in a random direction)
     * for the given number of steps and records their distance every {@code sampleEvery} steps. Unlike the Lyapunov
     * estimate the shadow is never pulled back, so for chaotic motion the separation grows roughly exponentially
     * and then <em>saturates</em> at the size of the accessible region: the growth rate is only meaningful before
     * saturation.
     */
    public static DivergenceResult divergence(NBodySystem sys, State start, Supplier<Integrator> integrators,
            double stepSize, long steps, int sampleEvery, double initialSeparation, long seed) {
        requireCommon(sys, start, integrators, stepSize, initialSeparation);
        if (steps < 1 || sampleEvery < 1) {
            throw new IllegalArgumentException("steps and sampleEvery must be >= 1, got " + steps + " and "
                    + sampleEvery);
        }
        double[] q = start.positions();
        double[] v = start.velocities();
        double[] sq = q.clone();
        double[] sv = v.clone();
        offsetRandomly(sq, sv, initialSeparation, seed);
        Integrator reference = integrators.get();
        Integrator shadow = integrators.get();

        int capacity = (int) Math.min(Integer.MAX_VALUE - 8, steps / sampleEvery + 1);
        double[] time = new double[capacity];
        double[] separation = new double[capacity];
        int count = 0;
        time[count] = 0.0;
        separation[count] = distance(q, v, sq, sv);
        count++;
        String failure = null;
        long step = 0;
        try {
            while (step < steps && count < capacity) {
                int chunk = (int) Math.min(sampleEvery, steps - step);
                for (int s = 0; s < chunk; s++) {
                    reference.step(sys, q, v, stepSize);
                    shadow.step(sys, sq, sv, stepSize);
                }
                step += chunk;
                time[count] = step * stepSize;
                separation[count] = distance(q, v, sq, sv);
                count++;
            }
        } catch (RuntimeException e) {
            failure = "Integration failed at step " + step + " (t = " + step * stepSize + "): "
                    + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        return new DivergenceResult(Arrays.copyOf(time, count), Arrays.copyOf(separation, count), failure);
    }

    // ---------------------------------------------------------------- helpers

    private static void requireCommon(NBodySystem sys, State start, Supplier<Integrator> integrators,
            double stepSize, double initialSeparation) {
        if (sys == null || start == null || integrators == null) {
            throw new IllegalArgumentException("system, start state and integrator factory must not be null");
        }
        if (sys.bodyCount() != start.bodyCount()) {
            throw new IllegalArgumentException("State has " + start.bodyCount() + " bodies but the system has "
                    + sys.bodyCount());
        }
        if (!Double.isFinite(stepSize) || stepSize <= 0.0) {
            throw new IllegalArgumentException("stepSize must be finite and > 0, got " + stepSize);
        }
        if (!Double.isFinite(initialSeparation) || initialSeparation <= 0.0) {
            throw new IllegalArgumentException("initialSeparation must be finite and > 0, got " + initialSeparation);
        }
    }

    /** Adds a vector of length {@code size}, uniformly random in direction, to (q, v). */
    private static void offsetRandomly(double[] q, double[] v, double size, long seed) {
        Random rng = new Random(seed);
        int n = q.length;
        double[] u = new double[2 * n];
        double norm2 = 0.0;
        for (int i = 0; i < u.length; i++) {
            u[i] = rng.nextGaussian();
            norm2 += u[i] * u[i];
        }
        double scale = size / Math.sqrt(norm2);
        for (int i = 0; i < n; i++) {
            q[i] += u[i] * scale;
            v[i] += u[n + i] * scale;
        }
    }

    private static double distance(double[] q, double[] v, double[] sq, double[] sv) {
        double sum = 0.0;
        for (int i = 0; i < q.length; i++) {
            double dq = sq[i] - q[i];
            double dv = sv[i] - v[i];
            sum += dq * dq + dv * dv;
        }
        return Math.sqrt(sum);
    }
}
