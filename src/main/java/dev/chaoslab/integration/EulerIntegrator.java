package dev.chaoslab.integration;

import dev.chaoslab.physics.NBodySystem;

/**
 * Explicit (forward) Euler: q' = q + h v, v' = v + h a(q), both using values at the start of the step.
 *
 * <p>First-order accurate. Provided as an <b>educational baseline</b>. For orbital motion it is unsuitable for
 * real work: each step is a small outward kick along the tangent, so energy grows systematically and bound orbits
 * spiral outward. It is not symplectic and not time-reversible. Use it to see why better methods exist.
 */
public final class EulerIntegrator implements Integrator {

    private double[] a = new double[0];

    @Override
    public String name() {
        return "Explicit Euler";
    }

    @Override
    public int order() {
        return 1;
    }

    @Override
    public boolean isSymplectic() {
        return false;
    }

    @Override
    public void step(NBodySystem sys, double[] q, double[] v, double h) {
        Integrator.requireStepSize(h);
        final int n = sys.stateLength();
        if (a.length != n) {
            a = new double[n];
        }
        sys.accelerations(q, a);
        for (int i = 0; i < n; i++) {
            q[i] += h * v[i]; // uses the old velocity: v is updated on the next line, same index
            v[i] += h * a[i]; // a was evaluated at the old position
        }
        Integrator.requireFinite(q, v, name(), h);
    }
}
