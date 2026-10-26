package dev.chaoslab.experiments;

import dev.chaoslab.physics.NBodySystem;
import dev.chaoslab.physics.State;

/**
 * A hierarchical triple in SI units: the Moon orbits the Earth, and the Earth-Moon pair orbits the Sun.
 *
 * <h2>Provenance of the numbers</h2>
 * Masses are the NASA fact-sheet values (Sun 1.98841e30 kg, Earth 5.9722e24 kg, Moon 0.07346e24 kg), the Earth-Moon
 * distance is the fact sheet's semimajor axis (384,400 km) and the Sun-barycentre distance is one astronomical unit
 * (149,597,870,700 m). Both orbits start circular, in one plane, with the Moon on the far side of the Earth from the
 * Sun. The real Moon's eccentricity (0.055) and its 5.1 degree inclination to the ecliptic are left out, so this is a
 * model, not an ephemeris. The frame is the centre-of-mass frame of the three bodies.
 *
 * <h2>What the project measured (reference: Dormand-Prince at tolerance 1e-13, one year)</h2>
 * <ul>
 *   <li>The Sun pulls the Moon about 2.2 times as hard as the Earth does, yet the Moon stays bound, because the Sun
 *       pulls Earth and Moon almost equally and only the difference matters for their relative motion.</li>
 *   <li>The Earth-Moon distance stays between 373,800 km and 388,200 km (the real Moon's mean perigee and apogee
 *       are 363,300 km and 405,500 km, which the circular start cannot reproduce).</li>
 *   <li>The Moon turns 13.499 times in 365 days, a period of 27.06 days against the real sidereal 27.32 days. The
 *       start is not the real orbit and the Sun perturbs it, so the 1% gap is expected.</li>
 *   <li>Velocity Verlet with h = 3600 s misplaces the Moon-Earth distance by 14 km after one year; h = 1800 s gives
 *       3.6 km and h = 600 s gives 0.4 km (second order).</li>
 *   <li>The motion is regular. The Lyapunov estimate is small and the window withholds an exponent (see
 *       {@code ExponentRule}).</li>
 * </ul>
 */
public final class SunEarthMoon {

    public static final double SUN_MASS = 1.98841e30;
    public static final double EARTH_MASS = 5.9722e24;
    public static final double MOON_MASS = 7.346e22;
    /** One astronomical unit, in metres. */
    public static final double SUN_DISTANCE = 149_597_870_700.0;
    /** Mean Earth-Moon distance (semimajor axis), in metres. */
    public static final double MOON_DISTANCE = 3.844e8;
    /** Fixed step shown in the panel, in seconds: about 650 steps per lunar orbit. */
    public static final double STEP = 3600.0;

    private SunEarthMoon() {
    }

    /** Masses in the order Sun, Earth, Moon; SI units with G = 6.6743e-11. */
    public static NBodySystem system() {
        return NBodySystem.si(SUN_MASS, EARTH_MASS, MOON_MASS);
    }

    /** The initial state at t = 0, in the centre-of-mass frame. */
    public static State state() {
        double g = NBodySystem.G_SI;
        double earthMoon = EARTH_MASS + MOON_MASS;
        double total = SUN_MASS + earthMoon;
        double barycentreSpeed = Math.sqrt(g * total / SUN_DISTANCE);
        double relativeSpeed = Math.sqrt(g * earthMoon / MOON_DISTANCE);
        double barycentreX = SUN_MASS / total * SUN_DISTANCE;
        double sunX = -earthMoon / total * SUN_DISTANCE;
        double earthX = barycentreX - MOON_MASS / earthMoon * MOON_DISTANCE;
        double moonX = barycentreX + EARTH_MASS / earthMoon * MOON_DISTANCE;
        double barycentreVy = SUN_MASS / total * barycentreSpeed;
        double sunVy = -earthMoon / total * barycentreSpeed;
        double earthVy = barycentreVy - MOON_MASS / earthMoon * relativeSpeed;
        double moonVy = barycentreVy + EARTH_MASS / earthMoon * relativeSpeed;
        double[] q = {sunX, 0.0, 0.0, earthX, 0.0, 0.0, moonX, 0.0, 0.0};
        double[] v = {0.0, sunVy, 0.0, 0.0, earthVy, 0.0, 0.0, moonVy, 0.0};
        return State.of(0.0, q, v);
    }
}
