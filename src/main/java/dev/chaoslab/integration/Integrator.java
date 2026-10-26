package dev.chaoslab.integration;

import dev.chaoslab.physics.NBodySystem;
import dev.chaoslab.physics.State;

/**
 * A fixed-step time integrator for the second-order system q'' = a(q) of an {@link NBodySystem}.
 *
 * <p>The core method {@link #step} advances flat position and velocity arrays <em>in place</em> so the hot loop
 * allocates nothing. Implementations keep private scratch buffers between calls, which makes an instance
 * <b>not thread-safe</b>: create one integrator per simulation. {@link #advance} is the safe, immutable
 * convenience wrapper that works on {@link State} objects.
 *
 * <p>Suitability is deliberately documented per implementation rather than ranked here: no method is best for
 * every purpose. See each class.
 */
public interface Integrator {

    /** Human-readable method name, for example "RK4". */
    String name();

    /** Order p of the global error: halving the step reduces the error by about 2^p. */
    int order();

    /** True if the method is symplectic (preserves phase-space area; bounded energy error for fixed step). */
    boolean isSymplectic();

    /**
     * Advances the state by one step of size {@code h} in place.
     *
     * @param sys system definition
     * @param q   positions, length 3N, updated in place
     * @param v   velocities, length 3N, updated in place
     * @param h   step size, finite and non-zero (a negative h integrates backward in time)
     * @throws NBodySystem.SingularConfigurationException if a force evaluation hits a singularity
     * @throws NumericalFailureException                  if the result is not finite
     */
    void step(NBodySystem sys, double[] q, double[] v, double h);

    /**
     * Advances an immutable state by {@code steps} fixed steps. The input is not modified. The final time is
     * computed as {@code t0 + steps * h} rather than accumulated, so it carries no summation error.
     */
    default State advance(NBodySystem sys, State s, double h, int steps) {
        if (steps < 0) {
            throw new IllegalArgumentException("steps must be >= 0, got " + steps);
        }
        if (sys.bodyCount() != s.bodyCount()) {
            throw new IllegalArgumentException("State has " + s.bodyCount() + " bodies but the system has "
                    + sys.bodyCount());
        }
        requireStepSize(h);
        double[] q = s.positions();
        double[] v = s.velocities();
        for (int k = 0; k < steps; k++) {
            step(sys, q, v, h);
        }
        return State.of(s.time() + steps * h, q, v);
    }

    /** Validates a step size: finite and non-zero. */
    static void requireStepSize(double h) {
        if (!Double.isFinite(h) || h == 0.0) {
            throw new IllegalArgumentException("Step size must be finite and non-zero, got " + h);
        }
    }

    /** Throws {@link NumericalFailureException} if any component is NaN or infinite. */
    static void requireFinite(double[] q, double[] v, String method, double h) {
        for (int i = 0; i < q.length; i++) {
            if (!Double.isFinite(q[i]) || !Double.isFinite(v[i])) {
                throw new NumericalFailureException(method + " produced a non-finite value at component " + i
                        + " (body " + (i / 3) + ") with step h=" + h + ". The step is too large for this"
                        + " configuration, or the bodies had a very close encounter.");
            }
        }
    }
}
