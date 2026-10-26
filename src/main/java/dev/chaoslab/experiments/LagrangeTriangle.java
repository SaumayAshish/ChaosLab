package dev.chaoslab.experiments;

import dev.chaoslab.physics.NBodySystem;
import dev.chaoslab.physics.State;

/**
 * Lagrange's equilateral solution of the three-body problem for three equal masses, in normalized units (G = 1).
 *
 * <h2>Derivation (so that nothing here is an invented number)</h2>
 * Three masses m at the vertices of an equilateral triangle of side a, rotating rigidly about their common centre
 * of mass, are an exact solution of Newtonian gravity. The circumradius is R = a / sqrt(3). Each body feels two
 * attractions of size G m^2 / a^2, whose components toward the centre add to
 * 2 (G m^2 / a^2) cos(30 degrees) = sqrt(3) G m^2 / a^2. Setting this equal to the centripetal force m omega^2 R gives
 * <pre>
 *     omega^2 = sqrt(3) G m / (a^2 R) = 3 G m / a^3.
 * </pre>
 * With R = 1 (a = sqrt(3), m = 1, G = 1): omega = 3^(-1/4) and the period is T = 2 pi / omega. The tests check the
 * acceleration against -omega^2 q to rounding, so an error in this algebra would be caught.
 *
 * <h2>Why it is useful here, and its limit</h2>
 * It has a closed-form trajectory, {@link #exactState(double)}, so numerical error can be measured exactly rather
 * than against another numerical result. It is also <em>linearly unstable</em> for equal masses. The Routh
 * criterion for stability of Lagrange's triangle is (m1+m2+m3)^2 &gt; 27 (m1 m2 + m2 m3 + m3 m1); for three
 * unit masses that reads 9 &gt; 81, which is false. A small displacement therefore grows and the configuration
 * eventually breaks apart, which makes {@link #state(double)} a clean demonstration of sensitive dependence on
 * initial conditions. This is a demonstration scenario, not one of the curated literature experiments.
 */
public final class LagrangeTriangle {

    /** Circumradius of the unperturbed triangle. */
    public static final double RADIUS = 1.0;

    /** Common mass. */
    public static final double MASS = 1.0;

    private LagrangeTriangle() {
    }

    /** Three equal unit masses, exact Newtonian gravity, G = 1. */
    public static NBodySystem system() {
        return NBodySystem.normalized(MASS, MASS, MASS);
    }

    /** Side length a = sqrt(3) R of the unperturbed triangle. */
    public static double side() {
        return Math.sqrt(3.0) * RADIUS;
    }

    /** Angular velocity omega = sqrt(3 G m / a^3) of the rigid rotation. */
    public static double angularVelocity() {
        double a = side();
        return Math.sqrt(3.0 * NBodySystem.G_NORMALIZED * MASS / (a * a * a));
    }

    /** Period 2 pi / omega of one full rotation. */
    public static double period() {
        return 2.0 * Math.PI / angularVelocity();
    }

    /**
     * The exact solution at time t: the initial triangle rotated rigidly by omega t about the origin.
     * Body i starts at angle 90 + 120 i degrees.
     */
    public static State exactState(double t) {
        return build(t, 0.0, angularVelocity() * t);
    }

    /**
     * The initial state at t = 0 with body 0 displaced radially outward by {@code displacement}; the velocities are
     * left exactly as in the unperturbed solution, so total momentum stays exactly zero. With displacement 0 this
     * is the exact solution at t = 0.
     *
     * @param displacement finite, greater than {@code -RADIUS}
     */
    public static State state(double displacement) {
        if (!Double.isFinite(displacement) || displacement <= -RADIUS) {
            throw new IllegalArgumentException("displacement must be finite and > -" + RADIUS + ", got "
                    + displacement);
        }
        return build(0.0, displacement, 0.0);
    }

    /**
     * Unit vectors to the three vertices at 90, 210 and 330 degrees. They are written with {@code sqrt} instead of
     * {@code cos}/{@code sin} because the Java specification allows {@code Math.cos} and {@code Math.sin} to differ
     * by one unit in the last place between platforms, whereas {@code sqrt} is exactly rounded everywhere. The
     * unstable motion amplifies such a difference exponentially, so this keeps results reproducible across machines.
     */
    private static final double HALF_SQRT3 = Math.sqrt(3.0) / 2.0;
    private static final double[][] VERTEX = {{0.0, 1.0}, {-HALF_SQRT3, -0.5}, {HALF_SQRT3, -0.5}};

    private static State build(double time, double displacementOfBody0, double rotation) {
        double omega = angularVelocity();
        double c = Math.cos(rotation); // exactly 1 and 0 for rotation = 0, which is all state(...) uses
        double s = Math.sin(rotation);
        double[] q = new double[9];
        double[] v = new double[9];
        for (int i = 0; i < 3; i++) {
            double rx = c * VERTEX[i][0] - s * VERTEX[i][1]; // unit vector rotated by 'rotation'
            double ry = s * VERTEX[i][0] + c * VERTEX[i][1];
            double r = RADIUS + (i == 0 ? displacementOfBody0 : 0.0);
            q[3 * i] = r * rx;
            q[3 * i + 1] = r * ry;
            v[3 * i] = -omega * RADIUS * ry;     // velocity is perpendicular to the radius vector
            v[3 * i + 1] = omega * RADIUS * rx;
        }
        return State.of(time, q, v);
    }
}
