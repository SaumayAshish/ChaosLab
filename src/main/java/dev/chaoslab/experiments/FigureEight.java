package dev.chaoslab.experiments;

import dev.chaoslab.physics.NBodySystem;
import dev.chaoslab.physics.State;

/**
 * The figure-eight choreography: three equal masses chasing each other along a single figure-eight curve, in
 * normalized units (G = 1, every mass 1).
 *
 * <h2>Provenance of the numbers</h2>
 * The orbit was found numerically by C. Moore (Physical Review Letters, 1993) and its existence was proved by
 * A. Chenciner and R. Montgomery (Annals of Mathematics, 2000). The eight-digit initial conditions below are the ones
 * credited to C. Simo on R. Montgomery's N-body page and printed again in S. Luo's paper (arXiv:2304.01004). They are
 * checked here, not just copied: the tests integrate the orbit at tolerance 1e-13 and require it to return to its
 * starting point, and an independent run (SciPy DOP853, rtol 1e-13) gave the same period.
 *
 * <h2>Layout and symmetry</h2>
 * Body 1 starts at (-0.97000436, 0.24308753), body 2 at the opposite point and body 3 at the origin. Body 3 has
 * velocity {@code (0.93240737, 0.86473146)} and bodies 1 and 2 each have minus half of it, so the total momentum and
 * the centre of mass are exactly zero and so is the angular momentum. The partners of each number are produced by an
 * exact sign change or an exact doubling, so these identities hold bit for bit. The drift measure of the angular
 * momentum needs this: it is a relative measure, and an angular momentum that was only almost zero would make it
 * meaningless. Luo's paper prints the same orbit traversed in the opposite direction.
 *
 * <h2>Why it is in the library, and its limits</h2>
 * Unlike the Lagrange triangle it behaves as a stable orbit in this project's measurements: the error of a
 * fixed-step method grows about linearly in time instead of exponentially, and the Lyapunov estimate falls like 1/t
 * toward zero (see the tests). It is the control experiment against the chaotic ones. The eight-digit data are not
 * exactly periodic (after {@link #PERIOD} the bodies are about 1.6e-8 from their start), and a displacement of body 1
 * by one percent destroys the choreography (the tests measure the gap that opens).
 */
public final class FigureEight {

    /** Common mass. */
    public static final double MASS = 1.0;

    /**
     * Period of the orbit through the rounded initial data, computed here by integrating at tolerance 1e-13 and
     * minimizing the distance to the start (6.32591401). It is the period to about 1e-8, not a published constant.
     */
    public static final double PERIOD = 6.325914;

    private static final double X = 0.97000436;
    private static final double Y = 0.24308753;
    private static final double HALF_VX = 0.466203685;
    private static final double HALF_VY = 0.43236573;

    private FigureEight() {
    }

    /** Three unit masses, exact Newtonian gravity, G = 1. */
    public static NBodySystem system() {
        return NBodySystem.normalized(MASS, MASS, MASS);
    }

    /** The initial state at t = 0. */
    public static State state() {
        double[] q = {-X, Y, 0.0, X, -Y, 0.0, 0.0, 0.0, 0.0};
        double[] v = {-HALF_VX, -HALF_VY, 0.0, -HALF_VX, -HALF_VY, 0.0, 2.0 * HALF_VX, 2.0 * HALF_VY, 0.0};
        return State.of(0.0, q, v);
    }
}
