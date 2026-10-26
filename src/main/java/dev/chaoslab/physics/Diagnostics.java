package dev.chaoslab.physics;

/**
 * Conservation-law diagnostics for an {@link NBodySystem}.
 *
 * <p>For the exact Newtonian model (softening = 0) and an isolated system, the exact flow conserves:
 * <ul>
 *   <li>total energy E = T + U,</li>
 *   <li>linear momentum P = sum m_i v_i (so the centre of mass moves uniformly: R(t) = R0 + (P/M) t),</li>
 *   <li>angular momentum L = sum m_i q_i x v_i, taken about the coordinate origin.</li>
 * </ul>
 * A numerical integrator conserves these only approximately. Measuring how badly it fails is the point of this
 * class. With softening the force is still the gradient of the softened potential, so E, P and L are still
 * conserved by the continuous dynamics, but the system is not the Newtonian one.
 *
 * <p>Drift is reported as dimensionless relative numbers so that different systems and units are comparable.
 * Because "relative to zero" is meaningless, each drift measure has a documented fallback scale; see the methods.
 */
public final class Diagnostics {

    private Diagnostics() {
    }

    /** All conserved or derived quantities at one instant. */
    public record Invariants(
            double time,
            double kinetic,
            double potential,
            double totalEnergy,
            Vec3 momentum,
            Vec3 angularMomentum,
            Vec3 centreOfMass,
            Vec3 centreOfMassVelocity) {
    }

    /** Dimensionless departures from the reference measurement. All values are non-negative except energy. */
    public record Drift(
            double time,
            double relativeEnergy,
            double relativeMomentum,
            double relativeAngularMomentum) {
    }

    // ---------------------------------------------------------------- individual quantities

    public static Vec3 centreOfMass(NBodySystem sys, State s) {
        requireMatch(sys, s);
        double x = 0, y = 0, z = 0;
        for (int i = 0; i < sys.bodyCount(); i++) {
            Vec3 r = s.position(i);
            double m = sys.mass(i);
            x += m * r.x();
            y += m * r.y();
            z += m * r.z();
        }
        double inv = 1.0 / sys.totalMass();
        return new Vec3(x * inv, y * inv, z * inv);
    }

    public static Vec3 linearMomentum(NBodySystem sys, State s) {
        requireMatch(sys, s);
        double x = 0, y = 0, z = 0;
        for (int i = 0; i < sys.bodyCount(); i++) {
            Vec3 w = s.velocity(i);
            double m = sys.mass(i);
            x += m * w.x();
            y += m * w.y();
            z += m * w.z();
        }
        return new Vec3(x, y, z);
    }

    /** Angular momentum about the coordinate origin: sum m_i (q_i x v_i). */
    public static Vec3 angularMomentum(NBodySystem sys, State s) {
        requireMatch(sys, s);
        Vec3 total = Vec3.ZERO;
        for (int i = 0; i < sys.bodyCount(); i++) {
            total = total.plus(s.position(i).cross(s.velocity(i)).times(sys.mass(i)));
        }
        return total;
    }

    public static double totalEnergy(NBodySystem sys, State s) {
        requireMatch(sys, s);
        return sys.kineticEnergy(s.velocities()) + sys.potentialEnergy(s.positions());
    }

    // ---------------------------------------------------------------- full measurement

    /**
     * Measures every invariant at once.
     *
     * @throws NBodySystem.SingularConfigurationException if two bodies coincide (zero softening)
     * @throws IllegalArgumentException                   if the state does not belong to this system
     */
    public static Invariants measure(NBodySystem sys, State s) {
        requireMatch(sys, s);
        double t = sys.kineticEnergy(s.velocities());
        double u = sys.potentialEnergy(s.positions());
        Vec3 p = linearMomentum(sys, s);
        return new Invariants(
                s.time(), t, u, t + u,
                p,
                angularMomentum(sys, s),
                centreOfMass(sys, s),
                p.times(1.0 / sys.totalMass()));
    }

    // ---------------------------------------------------------------- drift

    /**
     * Compares a later measurement with the reference (usually the initial one).
     *
     * <p>Definitions:
     * <ul>
     *   <li><b>relativeEnergy</b> = (E - E0) / |E0|. Signed: positive means the integrator added energy.
     *       If E0 = 0 exactly, the scale falls back to T0 + |U0| (the magnitude of the energy terms).</li>
     *   <li><b>relativeMomentum</b> = |P - P0| / Pscale where Pscale = sum m_i |v_i| at the reference. This is a
     *       bound on the momentum the system could possibly have, so it is meaningful even when P0 = 0, which
     *       is the usual case. If Pscale = 0 the system is at rest and the drift is |P - P0|.</li>
     *   <li><b>relativeAngularMomentum</b> = |L - L0| / |L0|, falling back to Lscale = sum m_i |q_i||v_i| when
     *       L0 = 0. If that is also 0, the absolute difference is returned.</li>
     * </ul>
     */
    public static Drift drift(NBodySystem sys, State reference, Invariants referenceInvariants, Invariants now) {
        requireMatch(sys, reference);
        double e0 = referenceInvariants.totalEnergy();
        double eScale = e0 != 0.0 ? Math.abs(e0)
                : referenceInvariants.kinetic() + Math.abs(referenceInvariants.potential());
        double relE = (now.totalEnergy() - e0) / orOne(eScale);

        double pScale = 0.0;
        double lScale = 0.0;
        for (int i = 0; i < sys.bodyCount(); i++) {
            double speed = reference.velocity(i).norm();
            pScale += sys.mass(i) * speed;
            lScale += sys.mass(i) * reference.position(i).norm() * speed;
        }
        double dp = now.momentum().minus(referenceInvariants.momentum()).norm();
        double relP = dp / orOne(pScale);

        double l0 = referenceInvariants.angularMomentum().norm();
        double dl = now.angularMomentum().minus(referenceInvariants.angularMomentum()).norm();
        double relL = dl / orOne(l0 != 0.0 ? l0 : lScale);

        return new Drift(now.time(), relE, relP, relL);
    }

    /** Divides by x, or by 1 if x is 0 so that a perfectly static reference yields an absolute difference. */
    private static double orOne(double x) {
        return x == 0.0 ? 1.0 : x;
    }

    // ---------------------------------------------------------------- validation

    private static void requireMatch(NBodySystem sys, State s) {
        if (sys.bodyCount() != s.bodyCount()) {
            throw new IllegalArgumentException("State has " + s.bodyCount() + " bodies but the system has "
                    + sys.bodyCount());
        }
    }
}
