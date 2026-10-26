package dev.chaoslab.integration;

import dev.chaoslab.physics.NBodySystem;
import java.util.Arrays;

/**
 * Velocity Verlet in its kick-drift-kick form:
 * <pre>
 *     v += (h/2) a(q);   q += h v;   v += (h/2) a(q)
 * </pre>
 *
 * <p>Second-order accurate, <b>symplectic</b> and <b>time-reversible</b>, using one new force evaluation per step
 * (the acceleration at the end of a step is reused as the start of the next; the reuse is validated by comparing
 * positions, so externally modified state is always handled correctly).
 *
 * <p>For a fixed step size h it exactly conserves a nearby "shadow" Hamiltonian, so the energy error stays
 * bounded and oscillates instead of drifting, even over very long runs. That is its strength for long-term
 * orbital studies. Its limits: only second-order, so a given accuracy needs a small h; the bounded-energy
 * guarantee requires a <em>fixed</em> step (varying h destroys it); and near a close encounter a fixed h that was
 * fine elsewhere gives large errors with no built-in warning.
 */
public final class VelocityVerletIntegrator implements Integrator {

    private double[] a = new double[0];
    private double[] qKey = new double[0];
    private NBodySystem keySystem;
    private boolean haveAcceleration;

    @Override
    public String name() {
        return "Velocity Verlet";
    }

    @Override
    public int order() {
        return 2;
    }

    @Override
    public boolean isSymplectic() {
        return true;
    }

    @Override
    public void step(NBodySystem sys, double[] q, double[] v, double h) {
        Integrator.requireStepSize(h);
        final int n = sys.stateLength();
        if (a.length != n) {
            a = new double[n];
            qKey = new double[n];
            haveAcceleration = false;
        }
        if (!(haveAcceleration && keySystem == sys && Arrays.equals(q, qKey))) {
            sys.accelerations(q, a);
        }
        haveAcceleration = false; // stays false if anything below throws

        final double half = 0.5 * h;
        for (int i = 0; i < n; i++) {
            v[i] += half * a[i];
            q[i] += h * v[i];
        }
        sys.accelerations(q, a);
        for (int i = 0; i < n; i++) {
            v[i] += half * a[i];
        }
        Integrator.requireFinite(q, v, name(), h);

        System.arraycopy(q, 0, qKey, 0, n);
        keySystem = sys;
        haveAcceleration = true;
    }
}
