package dev.chaoslab.experiments;

import dev.chaoslab.physics.NBodySystem;
import dev.chaoslab.physics.State;

/**
 * A planet on an eccentric Kepler orbit round a star, meant to be run for many orbits so that the slow, systematic
 * errors of each integrator show up: how a method's energy and angular-momentum error behave over a long interval, not
 * whether a single pass is accurate. Normalised units, G = 1.
 *
 * <h2>The start</h2>
 * A star of mass {@link #STAR_MASS} and a planet of mass {@link #PLANET_MASS} on an orbit with semi-major axis
 * {@link #SEMI_MAJOR_AXIS} and eccentricity {@link #ECCENTRICITY}, released at periapsis (the hardest point of the
 * orbit for a fixed step) in the centre-of-mass frame. The relative orbit is an exact Kepler ellipse, so everything
 * about it is known in closed form:
 * <ul>
 *   <li>periapsis distance a (1 - e), relative speed there sqrt(G M (1 + e) / (a (1 - e))) with M = m1 + m2;</li>
 *   <li>period {@link #PERIOD} = 2 pi sqrt(a^3 / (G M));</li>
 *   <li>total energy -G m1 m2 / (2 a) and angular momentum m1 m2 sqrt(G a (1 - e^2) / M).</li>
 * </ul>
 * Because the exact orbit is periodic and conserves energy and angular momentum exactly, any drift measured here is
 * the integrator's, not the physics'. That is the point of the experiment.
 *
 * <p>What was measured with this class is in the tests; none of it comes from the literature.
 */
public final class LongTermOrbit {

    /** Mass of the star. */
    public static final double STAR_MASS = 1.0;

    /** Mass of the planet. */
    public static final double PLANET_MASS = 1e-3;

    /** Semi-major axis of the relative orbit. */
    public static final double SEMI_MAJOR_AXIS = 1.0;

    /** Eccentricity of the relative orbit. */
    public static final double ECCENTRICITY = 0.5;

    /** Period of the relative orbit, 2 pi sqrt(a^3 / (G M)). */
    public static final double PERIOD = 2.0 * Math.PI
            * Math.sqrt(SEMI_MAJOR_AXIS * SEMI_MAJOR_AXIS * SEMI_MAJOR_AXIS / (STAR_MASS + PLANET_MASS));

    /** A suitable step for the fixed-step methods: the period divided by about 314. */
    public static final double STEP = 0.02;

    /** A suitable first step for an adaptive integrator. */
    public static final double FIRST_STEP = 1e-3;

    private LongTermOrbit() {
    }

    /** The two-body system: the star, then the planet. */
    public static NBodySystem system() {
        return NBodySystem.normalized(STAR_MASS, PLANET_MASS);
    }

    /** The start state at periapsis, in the centre-of-mass frame; the planet is at +x and moves along +y. */
    public static State state() {
        double total = STAR_MASS + PLANET_MASS;
        double r = SEMI_MAJOR_AXIS * (1.0 - ECCENTRICITY);
        double vRel = Math.sqrt(total * (1.0 + ECCENTRICITY) / (SEMI_MAJOR_AXIS * (1.0 - ECCENTRICITY)));
        double starShare = PLANET_MASS / total;
        double planetShare = STAR_MASS / total;
        double[] q = {-starShare * r, 0.0, 0.0, planetShare * r, 0.0, 0.0};
        double[] v = {0.0, -starShare * vRel, 0.0, 0.0, planetShare * vRel, 0.0};
        return State.of(0.0, q, v);
    }
}
