package dev.chaoslab.integration;

import dev.chaoslab.physics.NBodySystem;

/**
 * Classical fourth-order Runge-Kutta applied to the first-order system y = (q, v), y' = (v, a(q)).
 *
 * <p>Fourth-order accurate: halving h cuts the global error by about 16. Excellent short- and medium-term
 * accuracy at four force evaluations per step. <b>Not symplectic</b>: over very long integrations the energy
 * drifts slowly but systematically in one direction, whereas a symplectic method of lower order keeps energy
 * error bounded. With a fixed step it also cannot resolve close encounters; if h is too large for the closest
 * approach, accuracy collapses without warning other than conservation diagnostics. Use it for accurate
 * moderate-length runs with smooth dynamics.
 */
public final class Rk4Integrator implements Integrator {

    private double[] qt = new double[0];
    private double[] kq2 = new double[0];
    private double[] kq3 = new double[0];
    private double[] kq4 = new double[0];
    private double[] kv1 = new double[0];
    private double[] kv2 = new double[0];
    private double[] kv3 = new double[0];
    private double[] kv4 = new double[0];

    @Override
    public String name() {
        return "RK4";
    }

    @Override
    public int order() {
        return 4;
    }

    @Override
    public boolean isSymplectic() {
        return false;
    }

    @Override
    public void step(NBodySystem sys, double[] q, double[] v, double h) {
        Integrator.requireStepSize(h);
        final int n = sys.stateLength();
        if (qt.length != n) {
            qt = new double[n];
            kq2 = new double[n];
            kq3 = new double[n];
            kq4 = new double[n];
            kv1 = new double[n];
            kv2 = new double[n];
            kv3 = new double[n];
            kv4 = new double[n];
        }
        final double half = 0.5 * h;

        // Stage 1: k1q = v, k1v = a(q)
        sys.accelerations(q, kv1);

        // Stage 2
        for (int i = 0; i < n; i++) {
            qt[i] = q[i] + half * v[i];
            kq2[i] = v[i] + half * kv1[i];
        }
        sys.accelerations(qt, kv2);

        // Stage 3
        for (int i = 0; i < n; i++) {
            qt[i] = q[i] + half * kq2[i];
            kq3[i] = v[i] + half * kv2[i];
        }
        sys.accelerations(qt, kv3);

        // Stage 4
        for (int i = 0; i < n; i++) {
            qt[i] = q[i] + h * kq3[i];
            kq4[i] = v[i] + h * kv3[i];
        }
        sys.accelerations(qt, kv4);

        // Combine: y' = y + h/6 (k1 + 2 k2 + 2 k3 + k4)
        final double h6 = h / 6.0;
        for (int i = 0; i < n; i++) {
            q[i] += h6 * (v[i] + 2.0 * kq2[i] + 2.0 * kq3[i] + kq4[i]);
            v[i] += h6 * (kv1[i] + 2.0 * kv2[i] + 2.0 * kv3[i] + kv4[i]);
        }
        Integrator.requireFinite(q, v, name(), h);
    }
}
