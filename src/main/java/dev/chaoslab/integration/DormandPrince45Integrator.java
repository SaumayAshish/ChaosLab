package dev.chaoslab.integration;

import dev.chaoslab.physics.NBodySystem;
import dev.chaoslab.physics.State;
import java.util.Arrays;

/**
 * Dormand-Prince 5(4): an embedded Runge-Kutta pair with seven stages. The fifth-order solution is propagated
 * ("local extrapolation") and the difference to the embedded fourth-order solution gives the local error estimate.
 *
 * <h2>Three ways to use it</h2>
 * <ul>
 *   <li>As a fixed-step {@link Integrator} (order 5), via {@link #step}.</li>
 *   <li>As an adaptive integrator over a time interval via {@link #integrate}, which chooses each step so the
 *       estimated local error stays within the requested {@link Tolerances} and lands exactly on the end time.</li>
 *   <li>One accepted adaptive step at a time via {@link #adaptiveStep}, with no end time, for interactive
 *       simulation. It makes exactly the same step-size decisions as {@link #integrate}.</li>
 * </ul>
 *
 * <h2>Error control</h2>
 * Each of the 6N components (positions and velocities) has scale {@code sc_i = atol + rtol * max(|y_i|, |y_new_i|)}.
 * The error norm is {@code sqrt( (1/6N) * sum (e_i / sc_i)^2 )}; a step is accepted when it is at most 1. The next
 * step is {@code h * clamp(0.9 * err^(-1/5), 0.2, 5)} (never growing right after a rejection). This follows the
 * standard formulation in Hairer, Norsett and Wanner, <i>Solving Ordinary Differential Equations I</i>.
 *
 * <h2>What the tolerance does and does not mean</h2>
 * The tolerance bounds the <em>local</em> error per step. The <em>global</em> error after many steps is typically
 * larger and is not guaranteed by this controller. Positions and velocities share one atol/rtol, so choose a unit
 * system in which they are of comparable magnitude (the normalized units used by the default experiments).
 *
 * <h2>Suitability</h2>
 * Adaptive stepping is the right tool when the dynamics have very different time scales, such as a close
 * encounter: the step shrinks near pericentre and grows elsewhere. It is <b>not symplectic</b> and the step changes
 * break the structure that gives Verlet its bounded energy error, so over very long smooth integrations energy
 * drifts. It is also bounded by double precision: it cannot resolve a true collision, and on failure (step
 * underflow or too many steps) it throws {@link NumericalFailureException} instead of returning a wrong answer.
 */
public final class DormandPrince45Integrator implements Integrator {

    // Butcher tableau, rows 2..7 (row 7 equals the fifth-order weights: first-same-as-last).
    private static final double[][] A = {
        {1.0 / 5.0},
        {3.0 / 40.0, 9.0 / 40.0},
        {44.0 / 45.0, -56.0 / 15.0, 32.0 / 9.0},
        {19372.0 / 6561.0, -25360.0 / 2187.0, 64448.0 / 6561.0, -212.0 / 729.0},
        {9017.0 / 3168.0, -355.0 / 33.0, 46732.0 / 5247.0, 49.0 / 176.0, -5103.0 / 18656.0},
        {35.0 / 384.0, 0.0, 500.0 / 1113.0, 125.0 / 192.0, -2187.0 / 6784.0, 11.0 / 84.0}
    };

    // Error coefficients e_j = b_j (5th order) - b*_j (4th order embedded).
    private static final double[] E = {
        71.0 / 57600.0, 0.0, -71.0 / 16695.0, 71.0 / 1920.0, -17253.0 / 339200.0, 22.0 / 525.0, -1.0 / 40.0
    };

    private static final double SAFETY = 0.9;
    private static final double MIN_FACTOR = 0.2;
    private static final double MAX_FACTOR = 5.0;
    private static final int MAX_REJECTIONS_PER_STEP = 60;

    /** Absolute and relative error tolerances, both finite and strictly positive. */
    public record Tolerances(double absolute, double relative) {
        public Tolerances {
            if (!Double.isFinite(absolute) || absolute <= 0.0 || !Double.isFinite(relative) || relative <= 0.0) {
                throw new IllegalArgumentException("Tolerances must be finite and > 0, got absolute=" + absolute
                        + " relative=" + relative);
            }
        }
    }

    /** Summary of an adaptive run. Step statistics exclude the final step, which is shortened to land on tEnd. */
    public record AdaptiveResult(
            State finalState,
            int acceptedSteps,
            int rejectedSteps,
            double minStep,
            double maxStep,
            double maxErrorNorm) {
    }

    /** Receives every accepted step. The arrays are internal buffers: copy them if you keep them. */
    @FunctionalInterface
    public interface StepObserver {
        /** @param t time after the step; @param h the step just taken; @param q,v the state after the step */
        void accepted(double t, double h, double[] q, double[] v);
    }

    /**
     * One accepted step of {@link #adaptiveStep}.
     *
     * @param stepSize   the size of the step that was taken
     * @param rejections trial steps that were rejected and retried smaller before this one was accepted
     * @param errorNorm  scaled local error estimate of the accepted step (at most 1)
     */
    public record AcceptedStep(double stepSize, int rejections, double errorNorm) {
    }

    // ---- adaptive stepping state (used only by adaptiveStep) ----
    private double suggestedStep;

    // ---- scratch ----
    private double[][] kq = new double[7][0];
    private double[][] kv = new double[7][0];
    private double[] qs = new double[0];
    private double[] vs = new double[0];
    // first-same-as-last cache: two (q, a(q)) entries, validated by exact comparison of q
    private double[] cacheQ0 = new double[0];
    private double[] cacheA0 = new double[0];
    private double[] cacheQ1 = new double[0];
    private double[] cacheA1 = new double[0];
    private boolean valid0;
    private boolean valid1;
    private NBodySystem cacheSystem;

    @Override
    public String name() {
        return "Dormand-Prince 5(4)";
    }

    @Override
    public int order() {
        return 5;
    }

    @Override
    public boolean isSymplectic() {
        return false;
    }

    /** Fixed-step use: advances by exactly h with the fifth-order solution. */
    @Override
    public void step(NBodySystem sys, double[] q, double[] v, double h) {
        Integrator.requireStepSize(h);
        attempt(sys, q, v, h, 1.0, 1.0);
        System.arraycopy(qs, 0, q, 0, q.length);
        System.arraycopy(vs, 0, v, 0, v.length);
        Integrator.requireFinite(q, v, name(), h);
    }

    public AdaptiveResult integrate(NBodySystem sys, State start, double tEnd, double initialStep,
                                    Tolerances tol, int maxSteps) {
        return integrate(sys, start, tEnd, initialStep, tol, maxSteps, null);
    }

    /**
     * Integrates forward from {@code start.time()} to exactly {@code tEnd} with adaptive steps.
     *
     * @param initialStep first trial step, finite and &gt; 0 (the controller adjusts it immediately)
     * @param maxSteps    limit on accepted plus rejected steps
     * @param observer    optional, may be null
     * @throws NumericalFailureException if the step size underflows or {@code maxSteps} is exceeded
     */
    public AdaptiveResult integrate(NBodySystem sys, State start, double tEnd, double initialStep,
                                    Tolerances tol, int maxSteps, StepObserver observer) {
        if (sys.bodyCount() != start.bodyCount()) {
            throw new IllegalArgumentException("State has " + start.bodyCount() + " bodies but the system has "
                    + sys.bodyCount());
        }
        if (!Double.isFinite(tEnd) || tEnd <= start.time()) {
            throw new IllegalArgumentException("tEnd must be finite and greater than the start time "
                    + start.time() + ", got " + tEnd);
        }
        if (!Double.isFinite(initialStep) || initialStep <= 0.0) {
            throw new IllegalArgumentException("initialStep must be finite and > 0, got " + initialStep);
        }
        if (maxSteps <= 0) {
            throw new IllegalArgumentException("maxSteps must be > 0, got " + maxSteps);
        }
        sys.potentialEnergy(start.positions()); // fail fast, with the real cause, on a singular initial state

        final double t0 = start.time();
        final double span = tEnd - t0;
        final double[] q = start.positions();
        final double[] v = start.velocities();
        double t = t0;
        double h = Math.min(initialStep, span);
        int accepted = 0;
        int rejected = 0;
        double minStep = Double.POSITIVE_INFINITY;
        double maxStep = 0.0;
        double maxErr = 0.0;
        boolean previousRejected = false;

        while (t < tEnd) {
            if (accepted + rejected >= maxSteps) {
                throw new NumericalFailureException("Adaptive integration exceeded maxSteps=" + maxSteps
                        + " at t=" + t + " (target " + tEnd + "), last step size " + h
                        + ". Loosen the tolerance, raise maxSteps, or expect a very stiff close encounter.");
            }
            boolean last = false;
            double hTry = h;
            if (t + 1.01 * hTry >= tEnd) {
                hTry = tEnd - t;
                last = true;
            }

            double err;
            try {
                err = attempt(sys, q, v, hTry, tol.absolute(), tol.relative());
            } catch (NBodySystem.SingularConfigurationException e) {
                err = Double.POSITIVE_INFINITY; // an over-long trial step overshot into a singularity: shrink
            }

            if (err <= 1.0) {
                System.arraycopy(qs, 0, q, 0, q.length);
                System.arraycopy(vs, 0, v, 0, v.length);
                t = last ? tEnd : t + hTry;
                accepted++;
                maxErr = Math.max(maxErr, err);
                if (!last) {
                    minStep = Math.min(minStep, hTry);
                    maxStep = Math.max(maxStep, hTry);
                }
                if (observer != null) {
                    observer.accepted(t, hTry, q, v);
                }
                double factor = err == 0.0 ? MAX_FACTOR : Math.min(MAX_FACTOR, SAFETY * Math.pow(err, -0.2));
                factor = Math.max(MIN_FACTOR, factor);
                if (previousRejected) {
                    factor = Math.min(factor, 1.0);
                }
                h = hTry * factor;
                previousRejected = false;
            } else {
                rejected++;
                double factor = Double.isFinite(err) ? Math.max(MIN_FACTOR, SAFETY * Math.pow(err, -0.2))
                        : MIN_FACTOR;
                h = hTry * factor;
                previousRejected = true;
                double hMin = Math.max(1e-14 * span, 16.0 * Math.ulp(Math.max(Math.abs(t), Math.abs(tEnd))));
                if (h < hMin) {
                    throw new NumericalFailureException("Step size underflow at t=" + t + ": the requested"
                            + " tolerance (abs=" + tol.absolute() + ", rel=" + tol.relative() + ") cannot be met"
                            + " in double precision for this configuration (step fell to " + h + ").");
                }
            }
        }
        if (accepted == 1 || minStep == Double.POSITIVE_INFINITY) {
            minStep = 0.0; // too few interior steps to report a meaningful range
        }
        return new AdaptiveResult(State.of(tEnd, q, v), accepted, rejected, minStep, maxStep, maxErr);
    }

    /**
     * Takes <em>one</em> accepted adaptive step in place and returns how large it was. Unlike {@link #integrate} there
     * is no end time: the step is whatever the error controller allows, and it is never shortened to land on a
     * particular time. This is what an interactive simulation needs, and it keeps the result a pure function of the
     * initial state, the tolerances and the number of steps taken (no dependence on how the caller paces its calls).
     *
     * <p>The controller state (the suggested next step) lives in this instance, so use one instance per simulation
     * and a new instance after a reset, as with every {@link Integrator}.
     *
     * @param time        current simulation time; only used to decide when a step has become too small to be
     *                    meaningful in floating point
     * @param initialStep first trial step; used only on the first call of an instance, finite and &gt; 0
     * @throws NumericalFailureException if the tolerance cannot be met (the step falls to rounding level, or too many
     *                                   consecutive trials are rejected), for example in a true collision
     */
    public AcceptedStep adaptiveStep(NBodySystem sys, double[] q, double[] v, double time, double initialStep,
                                     Tolerances tol) {
        if (!Double.isFinite(initialStep) || initialStep <= 0.0) {
            throw new IllegalArgumentException("initialStep must be finite and > 0, got " + initialStep);
        }
        double hTry = suggestedStep > 0.0 ? suggestedStep : initialStep;
        // A step below this cannot advance the time in floating point (or is a few ulps of the time scale), so it
        // would let a caller loop forever without progress. It is a failure of the tolerance, not a step to accept.
        final double floor = 16.0 * Math.max(Math.ulp(Math.abs(time)), Math.ulp(initialStep));
        int rejections = 0;
        boolean sawSingularity = false;
        while (true) {
            if (hTry < floor || rejections >= MAX_REJECTIONS_PER_STEP) {
                throw new NumericalFailureException("Adaptive step underflow at t=" + time + ": the tolerance (abs="
                        + tol.absolute() + ", rel=" + tol.relative() + ") cannot be met in double precision (step fell to "
                        + hTry + " after " + rejections + " rejections)"
                        + (sawSingularity ? "; a force evaluation hit a singularity, which suggests a collision" : "")
                        + ".");
            }
            double err;
            try {
                err = attempt(sys, q, v, hTry, tol.absolute(), tol.relative());
            } catch (NBodySystem.SingularConfigurationException e) {
                err = Double.POSITIVE_INFINITY; // the trial step overshot into a singularity: shrink it
                sawSingularity = true;
            }
            if (err <= 1.0) {
                System.arraycopy(qs, 0, q, 0, q.length);
                System.arraycopy(vs, 0, v, 0, v.length);
                double factor = err == 0.0 ? MAX_FACTOR : Math.min(MAX_FACTOR, SAFETY * Math.pow(err, -0.2));
                factor = Math.max(MIN_FACTOR, factor);
                if (rejections > 0) {
                    factor = Math.min(factor, 1.0); // never grow straight after a rejection
                }
                suggestedStep = hTry * factor;
                return new AcceptedStep(hTry, rejections, err);
            }
            rejections++;
            hTry *= Double.isFinite(err) ? Math.max(MIN_FACTOR, SAFETY * Math.pow(err, -0.2)) : MIN_FACTOR;
        }
    }

    // ---------------------------------------------------------------- one trial step

    /**
     * Computes one trial step from (q, v), leaving the fifth-order result in {@code qs}/{@code vs}.
     *
     * @return the scaled error norm (accept when &le; 1), or +infinity if the trial is not finite
     */
    private double attempt(NBodySystem sys, double[] q, double[] v, double h, double atol, double rtol) {
        final int n = sys.stateLength();
        if (qs.length != n) {
            for (int s = 0; s < 7; s++) {
                kq[s] = new double[n];
                kv[s] = new double[n];
            }
            qs = new double[n];
            vs = new double[n];
            cacheQ0 = new double[n];
            cacheA0 = new double[n];
            cacheQ1 = new double[n];
            cacheA1 = new double[n];
            valid0 = false;
            valid1 = false;
        }
        if (cacheSystem != sys) {
            cacheSystem = sys;
            valid0 = false;
            valid1 = false;
        }

        // Stage 1: k1 = (v, a(q)), reusing a cached acceleration when q is bit-identical to a known one.
        System.arraycopy(v, 0, kq[0], 0, n);
        if (valid1 && Arrays.equals(q, cacheQ1)) {
            System.arraycopy(cacheA1, 0, kv[0], 0, n);
        } else if (valid0 && Arrays.equals(q, cacheQ0)) {
            System.arraycopy(cacheA0, 0, kv[0], 0, n);
        } else {
            sys.accelerations(q, kv[0]);
            System.arraycopy(q, 0, cacheQ0, 0, n);
            System.arraycopy(kv[0], 0, cacheA0, 0, n);
            valid0 = true;
        }

        // Stages 2..7. Stage 7 is evaluated at the fifth-order solution itself.
        for (int s = 1; s < 7; s++) {
            final double[] row = A[s - 1];
            for (int i = 0; i < n; i++) {
                double dq = 0.0;
                double dv = 0.0;
                for (int j = 0; j < s; j++) {
                    dq += row[j] * kq[j][i];
                    dv += row[j] * kv[j][i];
                }
                qs[i] = q[i] + h * dq;
                vs[i] = v[i] + h * dv;
                kq[s][i] = vs[i];
            }
            sys.accelerations(qs, kv[s]);
        }

        // Cache a(q_new) so the next step's first stage is free (first-same-as-last).
        System.arraycopy(qs, 0, cacheQ1, 0, n);
        System.arraycopy(kv[6], 0, cacheA1, 0, n);
        valid1 = true;

        // Embedded error estimate.
        double sum = 0.0;
        for (int i = 0; i < n; i++) {
            double eq = 0.0;
            double ev = 0.0;
            for (int j = 0; j < 7; j++) {
                eq += E[j] * kq[j][i];
                ev += E[j] * kv[j][i];
            }
            eq *= h;
            ev *= h;
            double scq = atol + rtol * Math.max(Math.abs(q[i]), Math.abs(qs[i]));
            double scv = atol + rtol * Math.max(Math.abs(v[i]), Math.abs(vs[i]));
            sum += (eq / scq) * (eq / scq) + (ev / scv) * (ev / scv);
        }
        double err = Math.sqrt(sum / (2.0 * n));
        return Double.isFinite(err) ? err : Double.POSITIVE_INFINITY;
    }
}
