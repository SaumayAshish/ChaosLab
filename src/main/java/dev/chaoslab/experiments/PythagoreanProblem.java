package dev.chaoslab.experiments;

import dev.chaoslab.physics.NBodySystem;
import dev.chaoslab.physics.State;

/**
 * The Pythagorean three-body problem: masses 3, 4 and 5 at rest at the corners of a 3-4-5 right triangle, each mass
 * opposite the side whose length equals it, in normalized units (G = 1).
 *
 * <h2>Provenance of the numbers</h2>
 * The problem was posed and integrated by hand by C. Burrau (Astronomische Nachrichten, 1913) and solved numerically
 * to its end by V. Szebehely and C. F. Peters (Astronomical Journal 72, 876, 1967), who found that the lightest body
 * is ejected and the two heavier ones remain as a binary. The coordinates used here (mass 3 at (1, 3), mass 4 at
 * (-2, -1), mass 5 at (1, -1)) are the ones printed in arXiv:2109.07013 for the Newtonian problem. They are checked,
 * not just copied: the sides come out as 3, 4 and 5 opposite masses 3, 4 and 5, the centre of mass is exactly the
 * origin, and the tests measure the behaviour described below.
 *
 * <h2>What the project measured (reference: Dormand-Prince at tolerance 1e-13)</h2>
 * <ul>
 *   <li>The energy is -769/60 = -12.8167. The total momentum, angular momentum and centre of mass are exactly
 *       zero.</li>
 *   <li>Masses 4 and 5 pass within 0.0097 at t = 1.879, within 4.1e-4 at t = 15.830 and within 0.0028 at t = 29.802.
 *       Each pass is a near-collision at high speed.</li>
 *   <li>Mass 3 is ejected between t = 60 and t = 70 and is 100 or more length units away at t = 100, with positive
 *       energy relative to the binary (+2.7 per unit reduced mass in the 1e-13 run). The same body escapes at
 *       reference tolerances 1e-12, 1e-13 and 1e-14.</li>
 *   <li>The details are not: the references at 1e-13 and 1e-14 differ by 2.4e-3 at t = 50, 0.13 at t = 60 and 2.1
 *       at t = 70. Only the qualitative result (who escapes) is trustworthy past t of about 55.</li>
 *   <li>Fixed-step methods fail at the first close encounter: Velocity Verlet with h = 0.001 is off by 2.4 at t = 2.
 *       Adaptive Dormand-Prince at tolerance 1e-10 is off by 1.7e-7 at t = 10 and 2.2e-4 at t = 40.</li>
 *   <li>The Lyapunov estimate in the window is crude here: its renormalisation interval comes from the initial
 *       distances (1.73), while the close passes last far less than that.</li>
 * </ul>
 */
public final class PythagoreanProblem {

    /** Masses of the three bodies, in the order of {@link #state()}. */
    public static final double[] MASSES = {3.0, 4.0, 5.0};

    /** Total energy, -(3*4/5 + 3*5/4 + 4*5/3) = -769/60. */
    public static final double ENERGY = -769.0 / 60.0;

    /** First trial step for adaptive integrators, and the fixed step shown in the panel (which is far too coarse). */
    public static final double FIRST_STEP = 0.001;

    private PythagoreanProblem() {
    }

    /** Normalized units, G = 1, exact Newtonian gravity. */
    public static NBodySystem system() {
        return NBodySystem.normalized(MASSES[0], MASSES[1], MASSES[2]);
    }

    /** The initial state at t = 0: all three bodies at rest. */
    public static State state() {
        double[] q = {1.0, 3.0, 0.0, -2.0, -1.0, 0.0, 1.0, -1.0, 0.0};
        return State.of(0.0, q, new double[9]);
    }
}
