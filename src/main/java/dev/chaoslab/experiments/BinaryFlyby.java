package dev.chaoslab.experiments;

import dev.chaoslab.physics.NBodySystem;
import dev.chaoslab.physics.State;

/**
 * A close gravitational scattering: a light intruder flies past a tight, circular, equal-mass binary and passes very
 * near one of its members. Normalised units, G = 1.
 *
 * <h2>The start</h2>
 * <ul>
 *   <li>Two bodies of mass {@link #BINARY_MASS} each on a circular orbit of separation {@link #SEPARATION}, relative
 *       speed sqrt(2) (period 2 pi / sqrt(2)), binary energy -1/2; at the start they lie on the x axis, orbiting
 *       counter-clockwise.</li>
 *   <li>An intruder of mass {@link #INTRUDER_MASS} starts {@link #START_DISTANCE} from the binary's centre along -x,
 *       offset by the impact parameter {@link #IMPACT_PARAMETER} in y, moving along +x at {@link #SPEED} relative to
 *       the binary's centre. That is above the local escape speed from the binary and the intruder (about 0.645), so
 *       it is an unbound, nearly parabolic flyby.</li>
 *   <li>Everything is then shifted to the centre-of-mass frame of all three bodies (total momentum zero, centre at the
 *       origin), so in that frame the intruder's initial speed is 0.56, not 0.7.</li>
 * </ul>
 *
 * <h2>What happens (measured against a 1e-13 reference, see the tests)</h2>
 * <ul>
 *   <li>The intruder passes within 0.0161 of body 2 at t = 12.109, about one sixtieth of the binary's size.</li>
 *   <li>The binary survives, but it is changed: its energy goes from -0.5 to -0.676 (more tightly bound), its
 *       semi-major axis from 1 to 0.7397 and its eccentricity from 0 to 0.9891. Its members now pass within 0.0081 of
 *       each other every 2.83 time units, an encounter almost as hard as the first.</li>
 *   <li>The intruder is deflected by about 135 degrees and, at t = 80, is 74 away from the binary moving at 0.809: it
 *       carries off the energy the binary lost (its energy relative to the binary goes from +0.015 to +0.191).</li>
 *   <li>The outcome is not knife-edge: moving the impact parameter by 1e-6 changes the final semi-major axis by 2e-6;
 *       by 1e-3 it changes by 1.5e-3 and the eccentricity to 0.9885.</li>
 * </ul>
 *
 * <h2>Why it is in ChaosLab: a limitation of fixed steps</h2>
 * The motion is not chaotic here, yet a fixed step fails completely, because the difficulty is a <em>time scale</em>,
 * not sensitivity. Before the encounter every method agrees with the reference (Velocity Verlet with h = 0.005 is off
 * by 1e-4 at t = 10). After it, at t = 13, the fixed-step runs are off by 2 (Verlet h = 0.005), 3.5 (RK4 h = 0.005) and
 * 16 (fixed Dormand-Prince h = 0.005), with the relative energy error between 0.9 and 280, and they never recover: the symplectic
 * property does not help, because it bounds the energy error of a smooth orbit, not a step that is too long for a
 * 0.016 pass. A fixed step of h = 0.0002 follows it to 6e-3 at
 * t = 13 but is still off by 0.5 at t = 80, because the binary now has hard periapsis passes of its own. The adaptive
 * Dormand-Prince method shrinks its step to 1.3e-5 (its smallest over the run): tolerance 1e-10 gives an error of 1e-5 at t = 80
 * with 9417 steps, tolerance 1e-8 gives 6e-4, and tolerance 1e-6 is too loose (error 0.9).
 *
 * <p>The numbers above are measurements made with this class; none comes from the literature.
 */
public final class BinaryFlyby {

    /** Mass of each binary member. */
    public static final double BINARY_MASS = 1.0;

    /** Mass of the intruder. */
    public static final double INTRUDER_MASS = 0.5;

    /** Initial separation of the binary members (a circular orbit). */
    public static final double SEPARATION = 1.0;

    /** Initial distance of the intruder from the binary's centre, along -x. */
    public static final double START_DISTANCE = 12.0;

    /** Initial offset of the intruder in y. */
    public static final double IMPACT_PARAMETER = 1.5;

    /** Initial speed of the intruder along +x relative to the binary's centre. */
    public static final double SPEED = 0.7;

    /** A suitable first step for an adaptive integrator. */
    public static final double FIRST_STEP = 1e-3;

    private BinaryFlyby() {
    }

    /** The three-body system: binary member 1, binary member 2, intruder. */
    public static NBodySystem system() {
        return NBodySystem.normalized(BINARY_MASS, BINARY_MASS, INTRUDER_MASS);
    }

    /** The start state with the given impact parameter, in the centre-of-mass frame of all three bodies. */
    public static State state(double impactParameter) {
        double half = 0.5 * SEPARATION;
        double omega = Math.sqrt(2.0 * BINARY_MASS / (SEPARATION * SEPARATION * SEPARATION)); // sqrt(G M / a^3)
        double memberSpeed = half * omega;
        double[] mass = {BINARY_MASS, BINARY_MASS, INTRUDER_MASS};
        double[] q = {half, 0.0, 0.0, -half, 0.0, 0.0, -START_DISTANCE, impactParameter, 0.0};
        double[] v = {0.0, memberSpeed, 0.0, 0.0, -memberSpeed, 0.0, SPEED, 0.0, 0.0};
        double total = mass[0] + mass[1] + mass[2];
        double[] centre = new double[3];
        double[] drift = new double[3];
        for (int i = 0; i < 3; i++) {
            for (int k = 0; k < 3; k++) {
                centre[k] += mass[i] * q[3 * i + k] / total;
                drift[k] += mass[i] * v[3 * i + k] / total;
            }
        }
        for (int i = 0; i < 3; i++) {
            for (int k = 0; k < 3; k++) {
                q[3 * i + k] -= centre[k];
                v[3 * i + k] -= drift[k];
            }
        }
        return State.of(0.0, q, v);
    }

    /** The start state with the standard impact parameter {@link #IMPACT_PARAMETER}. */
    public static State state() {
        return state(IMPACT_PARAMETER);
    }
}
