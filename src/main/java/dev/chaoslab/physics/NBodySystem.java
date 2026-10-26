package dev.chaoslab.physics;

import java.util.Arrays;

/**
 * The physical definition of a gravitating N-body system: masses, the gravitational constant and an
 * optional softening length. It is immutable and holds no dynamical state (positions and velocities
 * live in flat arrays owned by the caller), so one instance can be shared safely between threads.
 *
 * <h2>Model</h2>
 * Newtonian point-mass gravity. With r_ij = q_j - q_i the acceleration of body i is
 * <pre>
 *     a_i = G * sum_{j != i} m_j * r_ij / (|r_ij|^2 + eps^2)^(3/2)
 * </pre>
 * and the potential energy is
 * <pre>
 *     U = - G * sum_{i < j} m_i * m_j / sqrt(|r_ij|^2 + eps^2)
 * </pre>
 * where eps is the softening length.
 *
 * <p><b>eps = 0 is the exact Newtonian model.</b> eps &gt; 0 is the Plummer-softened model, a different
 * (non-Newtonian) physical system whose force is the exact gradient of the softened potential, so
 * total energy is still conserved by the continuous dynamics. Results computed with eps &gt; 0 must never
 * be presented as exact Newtonian results.
 *
 * <h2>Units</h2>
 * Nothing here assumes SI. Units are whatever the caller chooses, provided they are consistent:
 * [G] = length^3 / (mass * time^2). {@link #normalized(double...)} uses G = 1; {@link #si(double...)}
 * uses the CODATA value in m^3 kg^-1 s^-2 (masses in kg, lengths in m, times in s).
 *
 * <h2>Singularities</h2>
 * Coincident bodies (r = 0 with eps = 0) make the force infinite. This class does not hide that: it throws
 * {@link SingularConfigurationException} instead of returning NaN or infinity.
 */
public final class NBodySystem {

    /** Gravitational constant in the normalized unit system used by default experiments. */
    public static final double G_NORMALIZED = 1.0;

    /** Newtonian constant of gravitation, CODATA recommended value 6.67430e-11 m^3 kg^-1 s^-2. */
    public static final double G_SI = 6.67430e-11;

    private final double[] masses;
    private final double g;
    private final double softening;

    private NBodySystem(double g, double softening, double[] masses) {
        this.g = g;
        this.softening = softening;
        this.masses = masses;
    }

    // ---------------------------------------------------------------- construction

    /**
     * @param g         gravitational constant, finite and &gt; 0
     * @param softening Plummer softening length, finite and &ge; 0 (0 = exact Newtonian)
     * @param masses    at least two masses, each finite and &gt; 0
     * @throws IllegalArgumentException with an explanatory message for any invalid input
     */
    public static NBodySystem of(double g, double softening, double... masses) {
        if (!Double.isFinite(g) || g <= 0.0) {
            throw new IllegalArgumentException("Gravitational constant must be finite and > 0, got " + g);
        }
        if (!Double.isFinite(softening) || softening < 0.0) {
            throw new IllegalArgumentException("Softening length must be finite and >= 0, got " + softening);
        }
        if (masses == null || masses.length < 2) {
            throw new IllegalArgumentException("An N-body system needs at least 2 bodies, got "
                    + (masses == null ? "null" : masses.length));
        }
        for (int i = 0; i < masses.length; i++) {
            if (!Double.isFinite(masses[i]) || masses[i] <= 0.0) {
                throw new IllegalArgumentException(
                        "Mass of body " + i + " must be finite and > 0, got " + masses[i]);
            }
        }
        return new NBodySystem(g, softening, masses.clone());
    }

    /** Exact Newtonian gravity with an explicit G. */
    public static NBodySystem newtonian(double g, double... masses) {
        return of(g, 0.0, masses);
    }

    /** Exact Newtonian gravity in normalized units (G = 1). */
    public static NBodySystem normalized(double... masses) {
        return of(G_NORMALIZED, 0.0, masses);
    }

    /** Exact Newtonian gravity in SI units (kg, m, s). */
    public static NBodySystem si(double... masses) {
        return of(G_SI, 0.0, masses);
    }

    // ---------------------------------------------------------------- accessors

    public int bodyCount() {
        return masses.length;
    }

    /** Length of a flat position or velocity array: 3 * bodyCount. */
    public int stateLength() {
        return 3 * masses.length;
    }

    public double mass(int body) {
        return masses[body];
    }

    public double[] masses() {
        return masses.clone();
    }

    public double totalMass() {
        double sum = 0.0;
        for (double m : masses) {
            sum += m;
        }
        return sum;
    }

    public double gravitationalConstant() {
        return g;
    }

    public double softening() {
        return softening;
    }

    public boolean isExactNewtonian() {
        return softening == 0.0;
    }

    // ---------------------------------------------------------------- physics

    /**
     * Computes the acceleration of every body.
     *
     * <p>Each unordered pair is visited once and the two opposite contributions are derived from the same
     * intermediate quantities, so m_i a_i-contribution and m_j a_j-contribution are equal and opposite up to
     * rounding (Newton's third law holds by construction).
     *
     * @param q positions, flat array of length 3N
     * @param a output accelerations, flat array of length 3N (overwritten)
     * @throws SingularConfigurationException if two bodies coincide (eps = 0) or an intermediate value is not finite
     */
    public void accelerations(double[] q, double[] a) {
        requireLength(q, "positions");
        requireLength(a, "accelerations");
        Arrays.fill(a, 0.0);
        final int n = masses.length;
        final double eps2 = softening * softening;
        for (int i = 0; i < n; i++) {
            final int ki = 3 * i;
            for (int j = i + 1; j < n; j++) {
                final int kj = 3 * j;
                final double dx = q[kj] - q[ki];
                final double dy = q[kj + 1] - q[ki + 1];
                final double dz = q[kj + 2] - q[ki + 2];
                final double r2 = dx * dx + dy * dy + dz * dz + eps2;
                requireRegular(r2, i, j); // an infinite coordinate would otherwise yield a silent zero force
                final double r = Math.sqrt(r2);
                final double f = g / (r2 * r); // G / (r^2 + eps^2)^(3/2)
                requireRegular(f, i, j);
                final double fi = f * masses[j]; // acceleration factor on i, toward j
                final double fj = f * masses[i]; // acceleration factor on j, toward i
                a[ki] += fi * dx;
                a[ki + 1] += fi * dy;
                a[ki + 2] += fi * dz;
                a[kj] -= fj * dx;
                a[kj + 1] -= fj * dy;
                a[kj + 2] -= fj * dz;
            }
        }
    }

    /** Gravitational potential energy U (negative for bound pairs). Same singularity behaviour as {@link #accelerations}. */
    public double potentialEnergy(double[] q) {
        requireLength(q, "positions");
        final int n = masses.length;
        final double eps2 = softening * softening;
        double u = 0.0;
        for (int i = 0; i < n; i++) {
            final int ki = 3 * i;
            for (int j = i + 1; j < n; j++) {
                final int kj = 3 * j;
                final double dx = q[kj] - q[ki];
                final double dy = q[kj + 1] - q[ki + 1];
                final double dz = q[kj + 2] - q[ki + 2];
                final double r2 = dx * dx + dy * dy + dz * dz + eps2;
                requireRegular(r2, i, j);
                final double r = Math.sqrt(r2);
                final double term = g * masses[i] * masses[j] / r;
                requireRegular(term, i, j);
                u -= term;
            }
        }
        return u;
    }

    /** Kinetic energy T = (1/2) sum m_i |v_i|^2. */
    public double kineticEnergy(double[] v) {
        requireLength(v, "velocities");
        double t = 0.0;
        for (int i = 0; i < masses.length; i++) {
            final int k = 3 * i;
            t += masses[i] * (v[k] * v[k] + v[k + 1] * v[k + 1] + v[k + 2] * v[k + 2]);
        }
        return 0.5 * t;
    }

    // ---------------------------------------------------------------- validation helpers

    private void requireLength(double[] array, String what) {
        if (array == null || array.length != 3 * masses.length) {
            throw new IllegalArgumentException("Expected " + what + " array of length " + 3 * masses.length
                    + " (3 per body, " + masses.length + " bodies), got "
                    + (array == null ? "null" : array.length));
        }
    }

    private void requireRegular(double value, int i, int j) {
        if (!Double.isFinite(value)) {
            throw new SingularConfigurationException(i, j);
        }
    }

    @Override
    public String toString() {
        return "NBodySystem{N=" + masses.length + ", G=" + g + ", softening=" + softening
                + (isExactNewtonian() ? " (exact Newtonian)" : " (SOFTENED, not exact Newtonian)")
                + ", masses=" + Arrays.toString(masses) + "}";
    }

    // ---------------------------------------------------------------- exception

    /**
     * Thrown when the gravitational interaction between two bodies is not finite: they coincide (with zero
     * softening), a coordinate is NaN or infinite, or the pair is so close that the force overflows.
     * This is a physical/numerical failure to report, never to swallow.
     */
    public static final class SingularConfigurationException extends ArithmeticException {
        private static final long serialVersionUID = 1L;

        private final int bodyA;
        private final int bodyB;

        public SingularConfigurationException(int bodyA, int bodyB) {
            super("Singular or non-finite gravitational interaction between bodies " + bodyA + " and " + bodyB
                    + ": bodies coincide (with zero softening), are closer than floating-point range allows,"
                    + " or a coordinate is NaN/infinite.");
            this.bodyA = bodyA;
            this.bodyB = bodyB;
        }

        public int bodyA() {
            return bodyA;
        }

        public int bodyB() {
            return bodyB;
        }
    }
}
