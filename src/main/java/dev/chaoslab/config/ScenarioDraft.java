package dev.chaoslab.config;

import dev.chaoslab.experiments.LagrangeTriangle;
import dev.chaoslab.physics.Diagnostics;
import dev.chaoslab.physics.NBodySystem;
import dev.chaoslab.physics.State;
import dev.chaoslab.physics.Vec3;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * A scenario exactly as a person types it: every number is still text. {@link #validate} turns it into a
 * {@link Scenario} that the simulation can run, or into a list of {@link Issue}s that say which field is wrong and why.
 * Keeping the text form separate from the numeric form means the user interface can show a problem next to the field
 * that causes it, and that nothing unvalidated ever reaches the simulation runner.
 *
 * <h2>What is checked</h2>
 * <ul>
 *   <li>Every field is a plain decimal number. The text is matched against a strict pattern first, so that
 *       {@code Double.parseDouble} quirks ({@code NaN}, {@code Infinity}, {@code 0x1p3}, a trailing {@code d} or
 *       {@code f}) are errors instead of silently accepted values. A comma is rejected with a hint, because
 *       {@code 1,5} must never be read as {@code 15} or as {@code 1.5} by accident.</li>
 *   <li>G &gt; 0, softening &ge; 0, step size &gt; 0, each mass &gt; 0, between {@value #MIN_BODIES} and
 *       {@value #MAX_BODIES} bodies. The upper limit is a limit of this interface, not of the physics.</li>
 *   <li>With zero softening, no two bodies at the same position (the force would be infinite).</li>
 *   <li>The configuration can actually be measured: the same {@link Diagnostics#measure} call the simulation runner
 *       makes at start-up succeeds and returns finite energy, momentum and angular momentum. This catches overflow
 *       from absurdly large inputs before the runner does.</li>
 * </ul>
 *
 * <h2>Warnings and notes (never block a run)</h2>
 * Non-zero softening (a different physical system), net momentum (the centre of mass drifts), positive energy in the
 * centre-of-mass frame (the system disperses), and a fixed step that is coarse compared with the shortest dynamical
 * time. That last one is a rule of thumb and says so.
 *
 * <p>Field identifiers used in {@link Issue#field()} are {@value #G}, {@value #SOFTENING}, {@value #STEP_SIZE},
 * {@value #BODIES}, and {@code body<k>.<component>} for body number k counted from 1, with component one of
 * {@link #COMPONENTS}. Use {@link #field(int, String)} to build them.
 *
 * @param g          gravitational constant, as text
 * @param softening  Plummer softening length, as text (0 for exact Newtonian gravity)
 * @param stepSize   fixed step, or the first trial step in adaptive mode, as text
 * @param bodies     one entry per body, in order
 */
public record ScenarioDraft(String g, String softening, String stepSize, List<BodyDraft> bodies) {

    public static final int MIN_BODIES = 2;
    public static final int MAX_BODIES = 8;

    public static final String G = "g";
    public static final String SOFTENING = "softening";
    public static final String STEP_SIZE = "stepSize";
    public static final String BODIES = "bodies";

    /** Component names of a body, in the order of {@link BodyDraft#values()}. */
    public static final List<String> COMPONENTS = List.of("mass", "x", "y", "z", "vx", "vy", "vz");

    private static final Pattern NUMBER = Pattern.compile("[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?");

    /** Net momentum below this fraction of sum(m |v|) counts as zero. */
    private static final double MOMENTUM_NOTE_THRESHOLD = 1e-12;

    /** A fixed step above this fraction of the shortest pair timescale draws a warning. */
    private static final double COARSE_STEP_FRACTION = 1.0 / 20.0;

    public ScenarioDraft {
        g = g == null ? "" : g;
        softening = softening == null ? "" : softening;
        stepSize = stepSize == null ? "" : stepSize;
        bodies = bodies == null ? List.of() : List.copyOf(bodies);
    }

    /** The text of one body's mass, position and velocity. */
    public record BodyDraft(String mass, String x, String y, String z, String vx, String vy, String vz) {
        public BodyDraft {
            mass = mass == null ? "" : mass;
            x = x == null ? "" : x;
            y = y == null ? "" : y;
            z = z == null ? "" : z;
            vx = vx == null ? "" : vx;
            vy = vy == null ? "" : vy;
            vz = vz == null ? "" : vz;
        }

        /** The seven fields in the order of {@link ScenarioDraft#COMPONENTS}. */
        public List<String> values() {
            return List.of(mass, x, y, z, vx, vy, vz);
        }

        /** Builds a body from seven texts in the order of {@link ScenarioDraft#COMPONENTS}. */
        public static BodyDraft of(List<String> v) {
            if (v.size() != 7) {
                throw new IllegalArgumentException("A body has 7 fields, got " + v.size());
            }
            return new BodyDraft(v.get(0), v.get(1), v.get(2), v.get(3), v.get(4), v.get(5), v.get(6));
        }
    }

    public enum Severity {
        /** Blocks the run. */
        ERROR,
        /** Allowed, but the person should know. */
        WARNING,
        /** Information that helps interpret the run. */
        INFO
    }

    /**
     * One finding.
     *
     * @param field identifier of the field it belongs to (see the class documentation)
     */
    public record Issue(Severity severity, String field, String message) {
    }

    /** A validated, runnable scenario. {@code initial} is at time 0. */
    public record Scenario(NBodySystem system, State initial, double stepSize, Diagnostics.Invariants invariants) {
    }

    /** The outcome of {@link #validate}: a scenario when there are no errors, and all findings in any case. */
    public record Validation(Scenario scenario, List<Issue> issues) {
        public Validation {
            issues = List.copyOf(issues);
        }

        public boolean isValid() {
            return scenario != null;
        }

        public List<Issue> errors() {
            return issues.stream().filter(i -> i.severity() == Severity.ERROR).toList();
        }

        /** The first error message, or null if there is none. */
        public String firstError() {
            return issues.stream().filter(i -> i.severity() == Severity.ERROR).map(Issue::message).findFirst()
                    .orElse(null);
        }
    }

    /** Identifier of one field of one body; {@code body} counts from 0, the identifier from 1. */
    public static String field(int body, String component) {
        return "body" + (body + 1) + "." + component;
    }

    // ---------------------------------------------------------------- validation

    /**
     * Parses and checks everything.
     *
     * @param fixedStep true if the chosen integrator uses a fixed step (so the step size matters for accuracy);
     *                  false in adaptive mode, where it is only the first trial step
     */
    public Validation validate(boolean fixedStep) {
        List<Issue> issues = new ArrayList<>();
        double gv = parse(g, G, "Gravitational constant G", issues);
        if (!Double.isNaN(gv) && gv <= 0.0) {
            error(issues, G, "Gravitational constant G must be greater than 0.");
        }
        double eps = parse(softening, SOFTENING, "Softening length", issues);
        if (!Double.isNaN(eps) && eps < 0.0) {
            error(issues, SOFTENING, "Softening length must be 0 or greater (0 means exact Newtonian gravity).");
        }
        double h = parse(stepSize, STEP_SIZE, "Step size", issues);
        if (!Double.isNaN(h) && h <= 0.0) {
            error(issues, STEP_SIZE, "Step size must be greater than 0.");
        }

        int n = bodies.size();
        if (n < MIN_BODIES || n > MAX_BODIES) {
            error(issues, BODIES, "The number of bodies must be between " + MIN_BODIES + " and " + MAX_BODIES
                    + ", got " + n + ".");
        }
        double[] masses = new double[n];
        double[] q = new double[3 * n];
        double[] v = new double[3 * n];
        for (int i = 0; i < n; i++) {
            BodyDraft b = bodies.get(i);
            String who = "Body " + (i + 1);
            masses[i] = parse(b.mass(), field(i, "mass"), who + " mass", issues);
            if (!Double.isNaN(masses[i]) && masses[i] <= 0.0) {
                error(issues, field(i, "mass"), who + " mass must be greater than 0.");
            }
            q[3 * i] = parse(b.x(), field(i, "x"), who + " x position", issues);
            q[3 * i + 1] = parse(b.y(), field(i, "y"), who + " y position", issues);
            q[3 * i + 2] = parse(b.z(), field(i, "z"), who + " z position", issues);
            v[3 * i] = parse(b.vx(), field(i, "vx"), who + " x velocity", issues);
            v[3 * i + 1] = parse(b.vy(), field(i, "vy"), who + " y velocity", issues);
            v[3 * i + 2] = parse(b.vz(), field(i, "vz"), who + " z velocity", issues);
        }
        if (hasError(issues)) {
            return new Validation(null, issues);
        }

        NBodySystem system;
        try {
            system = NBodySystem.of(gv, eps, masses);
        } catch (IllegalArgumentException e) {
            error(issues, BODIES, e.getMessage());
            return new Validation(null, issues);
        }

        if (eps == 0.0) {
            for (int i = 0; i < n; i++) {
                for (int j = i + 1; j < n; j++) {
                    if (squaredDistance(q, i, j) == 0.0) {
                        error(issues, field(j, "x"), "Bodies " + (i + 1) + " and " + (j + 1) + " are at the same"
                                + " position (or closer than about 1e-154), so the gravitational force between"
                                + " them is infinite. Move one of them, or use a softening length greater than 0.");
                    }
                }
            }
            if (hasError(issues)) {
                return new Validation(null, issues);
            }
        }

        State state = State.of(0.0, q, v);
        Diagnostics.Invariants inv;
        try {
            inv = Diagnostics.measure(system, state);
        } catch (ArithmeticException | IllegalArgumentException e) {
            error(issues, BODIES, "The configuration cannot be evaluated: " + e.getMessage());
            return new Validation(null, issues);
        }
        if (!Double.isFinite(inv.totalEnergy()) || !Double.isFinite(inv.momentum().norm())
                || !Double.isFinite(inv.angularMomentum().norm()) || !Double.isFinite(inv.centreOfMass().norm())) {
            error(issues, BODIES, "The numbers are so large that the energy or momentum overflows double precision."
                    + " Use smaller values or different units.");
            return new Validation(null, issues);
        }

        addNotes(issues, system, state, inv, eps, h, fixedStep);
        return new Validation(new Scenario(system, state, h, inv), issues);
    }

    private static void addNotes(List<Issue> issues, NBodySystem system, State state, Diagnostics.Invariants inv,
            double eps, double h, boolean fixedStep) {
        int n = system.bodyCount();
        if (eps > 0.0) {
            issues.add(new Issue(Severity.WARNING, SOFTENING, "Softening length " + eps + " > 0: this is the"
                    + " Plummer-softened system, a different (non-Newtonian) physical model. Do not present its"
                    + " results as exact Newtonian gravity."));
        }

        double pScale = 0.0;
        for (int i = 0; i < n; i++) {
            pScale += system.mass(i) * state.velocity(i).norm();
        }
        double p = inv.momentum().norm();
        if (pScale > 0.0 && p > MOMENTUM_NOTE_THRESHOLD * pScale) {
            Vec3 w = inv.centreOfMassVelocity();
            issues.add(new Issue(Severity.INFO, BODIES, String.format(Locale.ROOT,
                    "Net momentum is not zero: the centre of mass moves at (%.4g, %.4g, %.4g). The motion between"
                            + " the bodies is unaffected, but the picture drifts. \"Centre of mass frame\" removes"
                            + " it.", w.x(), w.y(), w.z())));
        }

        double eCentreOfMass = inv.totalEnergy() - 0.5 * p * p / system.totalMass();
        if (eCentreOfMass > 0.0) {
            issues.add(new Issue(Severity.INFO, BODIES, String.format(Locale.ROOT,
                    "Energy in the centre-of-mass frame is positive (%.4g): the system is not bound. By the"
                            + " Lagrange-Jacobi identity its size grows at least quadratically in time, so it"
                            + " disperses.", eCentreOfMass)));
        }

        double shortest = Double.POSITIVE_INFINITY;
        double[] q = state.positions();
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                double r2 = squaredDistance(q, i, j) + eps * eps;
                double r = Math.sqrt(r2);
                double t = Math.sqrt(r * r2 / (system.gravitationalConstant() * (system.mass(i) + system.mass(j))));
                shortest = Math.min(shortest, t);
            }
        }
        if (fixedStep && h > COARSE_STEP_FRACTION * shortest) {
            issues.add(new Issue(Severity.WARNING, STEP_SIZE, String.format(Locale.ROOT,
                    "Step size %.3g is more than 1/20 of the shortest pair timescale %.3g (sqrt(r^3 / (G (m_i +"
                            + " m_j))) at the start). This is a rule of thumb: steps this coarse usually give"
                            + " inaccurate orbits, and it says nothing about close encounters later in the run.",
                    h, shortest)));
        }
    }

    // ---------------------------------------------------------------- transformations

    /**
     * The same scenario shifted into the centre-of-mass frame: the centre of mass is moved to the origin and brought
     * to rest. Relative positions and velocities are unchanged.
     *
     * @throws IllegalStateException if this draft is not valid
     */
    public ScenarioDraft centred() {
        Validation r = validate(true);
        if (!r.isValid()) {
            throw new IllegalStateException("Cannot centre an invalid scenario: " + r.firstError());
        }
        Scenario s = r.scenario();
        Vec3 c = s.invariants().centreOfMass();
        Vec3 w = s.invariants().centreOfMassVelocity();
        List<BodyDraft> moved = new ArrayList<>();
        for (int i = 0; i < s.system().bodyCount(); i++) {
            Vec3 p = s.initial().position(i).minus(c);
            Vec3 u = s.initial().velocity(i).minus(w);
            moved.add(new BodyDraft(format(s.system().mass(i)), format(p.x()), format(p.y()), format(p.z()),
                    format(u.x()), format(u.y()), format(u.z())));
        }
        return new ScenarioDraft(g, softening, stepSize, moved);
    }

    // ---------------------------------------------------------------- building drafts from numbers

    /**
     * Writes a system and a state out as text. The text is the shortest decimal that reads back as exactly the same
     * double, so {@code validate} of this draft reproduces the state bit for bit.
     */
    public static ScenarioDraft of(NBodySystem system, State state, double stepSize) {
        List<BodyDraft> list = new ArrayList<>();
        for (int i = 0; i < system.bodyCount(); i++) {
            Vec3 p = state.position(i);
            Vec3 u = state.velocity(i);
            list.add(new BodyDraft(format(system.mass(i)), format(p.x()), format(p.y()), format(p.z()),
                    format(u.x()), format(u.y()), format(u.z())));
        }
        return new ScenarioDraft(format(system.gravitationalConstant()), format(system.softening()),
                format(stepSize), list);
    }

    /** Lagrange's equilateral triangle with body 1 displaced radially by {@code displacement}; normalized units. */
    public static ScenarioDraft lagrange(double displacement) {
        return of(LagrangeTriangle.system(), LagrangeTriangle.state(displacement), 0.005);
    }

    /** Two equal unit masses on a circular orbit, normalized units. */
    public static ScenarioDraft twoBodyCircular() {
        return twoBody(1.0);
    }

    /** Two equal unit masses released at apocentre at half the circular speed: eccentricity 0.75. */
    public static ScenarioDraft twoBodyEccentric() {
        return twoBody(0.5);
    }

    private static ScenarioDraft twoBody(double speedFactor) {
        double v = speedFactor * Math.sqrt(0.5);
        State s = State.of(0.0, new double[] {-0.5, 0, 0, 0.5, 0, 0}, new double[] {0, -v, 0, 0, v, 0});
        return of(NBodySystem.normalized(1.0, 1.0), s, 0.01);
    }

    /**
     * The Sun and the Earth on a circular orbit in SI units (kg, m, s), in their centre-of-mass frame, step one hour.
     *
     * <p>The inputs are rounded published values, not measurements made here: M_sun = 1.98841e30 kg (from GM_sun and
     * G), M_earth = 5.9722e24 kg, and 1 AU = 149 597 870 700 m (exact by IAU 2012 definition). The speed is the
     * circular one, sqrt(G (M + m) / r), so the real Earth's orbital eccentricity (about 0.017) is ignored. Treat it as
     * a realistic demonstration, not an ephemeris.
     */
    public static ScenarioDraft sunEarth() {
        double mSun = 1.98841e30;
        double mEarth = 5.9722e24;
        double r = 149_597_870_700.0;
        double total = mSun + mEarth;
        double vRel = Math.sqrt(NBodySystem.G_SI * total / r);
        double fSun = mEarth / total;
        double fEarth = mSun / total;
        State s = State.of(0.0,
                new double[] {-fSun * r, 0, 0, fEarth * r, 0, 0},
                new double[] {0, -fSun * vRel, 0, 0, fEarth * vRel, 0});
        return of(NBodySystem.si(mSun, mEarth), s, 3600.0);
    }

    // ---------------------------------------------------------------- helpers

    /** The shortest decimal text that parses back to exactly {@code d}; negative zero is written as 0.0. */
    public static String format(double d) {
        return d == 0.0 ? "0.0" : Double.toString(d);
    }

    private static double squaredDistance(double[] q, int i, int j) {
        double dx = q[3 * j] - q[3 * i];
        double dy = q[3 * j + 1] - q[3 * i + 1];
        double dz = q[3 * j + 2] - q[3 * i + 2];
        return dx * dx + dy * dy + dz * dz;
    }

    private static boolean hasError(List<Issue> issues) {
        return issues.stream().anyMatch(i -> i.severity() == Severity.ERROR);
    }

    private static void error(List<Issue> issues, String field, String message) {
        issues.add(new Issue(Severity.ERROR, field, message));
    }

    /** Parses strictly; returns NaN (after recording an error) if the text is not a finite decimal number. */
    private static double parse(String text, String field, String label, List<Issue> issues) {
        String t = text.trim();
        if (t.isEmpty()) {
            error(issues, field, label + " is empty.");
            return Double.NaN;
        }
        if (t.indexOf(',') >= 0) {
            error(issues, field, label + " contains a comma. Use a decimal point (for example 0.5) and no"
                    + " thousands separators.");
            return Double.NaN;
        }
        if (!NUMBER.matcher(t).matches()) {
            error(issues, field, label + " is not a number: \"" + t + "\". Use digits with an optional decimal"
                    + " point and exponent, for example -1.5 or 6.67e-11.");
            return Double.NaN;
        }
        double value = Double.parseDouble(t);
        if (!Double.isFinite(value)) {
            error(issues, field, label + " is too large for a double-precision number.");
            return Double.NaN;
        }
        return value;
    }
}
