package dev.chaoslab.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.chaoslab.config.ScenarioDraft.BodyDraft;
import dev.chaoslab.config.ScenarioDraft.Issue;
import dev.chaoslab.config.ScenarioDraft.Scenario;
import dev.chaoslab.config.ScenarioDraft.Severity;
import dev.chaoslab.config.ScenarioDraft.Validation;
import dev.chaoslab.experiments.LagrangeTriangle;
import dev.chaoslab.physics.NBodySystem;
import dev.chaoslab.physics.State;
import dev.chaoslab.physics.Vec3;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ScenarioDraft}: strict number parsing, field-level errors, the physics checks, the warnings and
 * the exactness of the round trip from numbers to text and back.
 */
class ScenarioDraftTest {

    /** Replaces one text field of one body (body counts from 0). */
    private static ScenarioDraft with(ScenarioDraft d, int body, String component, String value) {
        List<BodyDraft> list = new ArrayList<>(d.bodies());
        List<String> v = new ArrayList<>(list.get(body).values());
        v.set(ScenarioDraft.COMPONENTS.indexOf(component), value);
        list.set(body, BodyDraft.of(v));
        return new ScenarioDraft(d.g(), d.softening(), d.stepSize(), list);
    }

    private static boolean has(Validation v, Severity severity, String field) {
        return v.issues().stream().anyMatch(i -> i.severity() == severity && i.field().equals(field));
    }

    private static Scenario valid(ScenarioDraft d) {
        Validation v = d.validate(true);
        assertTrue(v.isValid(), "expected valid, got " + v.issues());
        return v.scenario();
    }

    // ---------------------------------------------------------------- round trip and presets

    @Test
    void theLagrangePresetReadsBackAsExactlyTheSameState() {
        Scenario s = valid(ScenarioDraft.lagrange(0.01));
        assertEquals(LagrangeTriangle.state(0.01), s.initial());     // bit for bit, not just close
        assertEquals(3, s.system().bodyCount());
        assertEquals(1.0, s.system().gravitationalConstant(), 0.0);
        assertEquals(0.0, s.system().softening(), 0.0);
        assertEquals(0.005, s.stepSize(), 0.0);
        assertEquals(0.0, s.initial().time(), 0.0);
    }

    @Test
    void everyPresetIsValidAndQuiet() {
        for (ScenarioDraft d : List.of(ScenarioDraft.lagrange(0.0), ScenarioDraft.lagrange(0.01),
                ScenarioDraft.twoBodyCircular(), ScenarioDraft.twoBodyEccentric(), ScenarioDraft.sunEarth())) {
            Validation v = d.validate(true);
            assertTrue(v.isValid(), v.issues().toString());
            assertTrue(v.issues().isEmpty(), "presets should raise no warnings: " + v.issues());
        }
    }

    @Test
    void textRoundTripIsExactForAwkwardNumbers() {
        double[] values = {1.0 / 3.0, 0.1, 1e-300, 6.6743e-11, 123456789.123456789, Math.sqrt(3.0) / 2.0, -2.5e17};
        for (double x : values) {
            assertEquals(x, Double.parseDouble(ScenarioDraft.format(x)), 0.0);
        }
        assertEquals("0.0", ScenarioDraft.format(-0.0));
        State s = State.of(0.0, new double[] {1.0 / 3.0, 0.1, 0, -2, 1e-300, 4}, new double[] {0, 0.7, 0, 0, -0.7, 0});
        ScenarioDraft d = ScenarioDraft.of(NBodySystem.normalized(1.0, 2.0), s, 0.01);
        assertEquals(s, valid(d).initial());
    }

    @Test
    void theSunEarthPresetIsACircularOrbitInTheCentreOfMassFrame() {
        Scenario s = valid(ScenarioDraft.sunEarth());
        double m1 = s.system().mass(0);
        double m2 = s.system().mass(1);
        double r = s.initial().position(1).minus(s.initial().position(0)).norm();
        assertEquals(149_597_870_700.0, r, 1e-4);
        // circular orbit: E = -G m1 m2 / (2 r)
        double expected = -s.system().gravitationalConstant() * m1 * m2 / (2.0 * r);
        assertEquals(expected, s.invariants().totalEnergy(), 1e-9 * Math.abs(expected));
        assertTrue(s.invariants().centreOfMass().norm() < 1e-3, "COM at " + s.invariants().centreOfMass());
        double scale = m2 * s.initial().velocity(1).norm();
        assertTrue(s.invariants().momentum().norm() < 1e-12 * scale);
    }

    // ---------------------------------------------------------------- strict number parsing

    @Test
    void acceptsPlainDecimalNumbersIncludingSignsExponentsAndSurroundingSpaces() {
        for (String ok : new String[] {"1", "+1.5", "-1.5", ".5", "5.", "1e-3", "1E3", "6.67e-11", "  2  ", "007"}) {
            ScenarioDraft d = with(ScenarioDraft.twoBodyCircular(), 0, "z", ok);
            assertTrue(d.validate(true).isValid(), "should accept [" + ok + "]");
        }
    }

    @Test
    void rejectsEverythingThatIsNotAPlainFiniteDecimal() {
        for (String bad : new String[] {"", "   ", "NaN", "Infinity", "-Infinity", "0x1p3", "1d", "1f", "1L", "abc",
            "1e", "e5", "--1", "1.2.3", "1 2", "(1)", "1e999", "-1e999"}) {
            Validation v = with(ScenarioDraft.twoBodyCircular(), 1, "vx", bad).validate(true);
            assertFalse(v.isValid(), "should reject [" + bad + "]");
            assertTrue(has(v, Severity.ERROR, "body2.vx"), "error should name body2.vx for [" + bad + "]: "
                    + v.issues());
        }
    }

    @Test
    void aCommaGetsAHelpfulMessageAndIsNeverReadAsADecimalPoint() {
        Validation v = with(ScenarioDraft.twoBodyCircular(), 0, "x", "1,5").validate(true);
        assertFalse(v.isValid());
        assertTrue(v.firstError().contains("comma"), v.firstError());
    }

    @Test
    void anEmptyDraftReportsErrorsInsteadOfThrowing() {
        Validation v = new ScenarioDraft(null, null, null, null).validate(true);
        assertFalse(v.isValid());
        assertTrue(has(v, Severity.ERROR, ScenarioDraft.G));
        assertTrue(has(v, Severity.ERROR, ScenarioDraft.SOFTENING));
        assertTrue(has(v, Severity.ERROR, ScenarioDraft.STEP_SIZE));
        assertTrue(has(v, Severity.ERROR, ScenarioDraft.BODIES));
    }

    // ---------------------------------------------------------------- physical limits

    @Test
    void massGStepAndSofteningLimitsAreEnforcedOnTheRightField() {
        ScenarioDraft base = ScenarioDraft.twoBodyCircular();
        assertTrue(has(with(base, 0, "mass", "0").validate(true), Severity.ERROR, "body1.mass"));
        assertTrue(has(with(base, 1, "mass", "-1").validate(true), Severity.ERROR, "body2.mass"));
        assertTrue(has(new ScenarioDraft("0", base.softening(), base.stepSize(), base.bodies()).validate(true),
                Severity.ERROR, ScenarioDraft.G));
        assertTrue(has(new ScenarioDraft(base.g(), "-0.1", base.stepSize(), base.bodies()).validate(true),
                Severity.ERROR, ScenarioDraft.SOFTENING));
        assertTrue(has(new ScenarioDraft(base.g(), base.softening(), "0", base.bodies()).validate(true),
                Severity.ERROR, ScenarioDraft.STEP_SIZE));
        assertTrue(has(new ScenarioDraft(base.g(), base.softening(), "-0.01", base.bodies()).validate(true),
                Severity.ERROR, ScenarioDraft.STEP_SIZE));
    }

    @Test
    void allErrorsAreReportedAtOnceNotJustTheFirst() {
        ScenarioDraft d = with(with(ScenarioDraft.twoBodyCircular(), 0, "mass", "-1"), 1, "y", "oops");
        Validation v = d.validate(true);
        assertEquals(2, v.errors().size());
    }

    @Test
    void bodyCountMustBeWithinTheInterfaceLimits() {
        ScenarioDraft base = ScenarioDraft.twoBodyCircular();
        ScenarioDraft one = new ScenarioDraft(base.g(), base.softening(), base.stepSize(), base.bodies().subList(0, 1));
        assertTrue(has(one.validate(true), Severity.ERROR, ScenarioDraft.BODIES));

        List<BodyDraft> many = new ArrayList<>();
        for (int i = 0; i <= ScenarioDraft.MAX_BODIES; i++) {
            many.add(new BodyDraft("1", Integer.toString(i), "0", "0", "0", "0", "0"));
        }
        ScenarioDraft nine = new ScenarioDraft(base.g(), base.softening(), base.stepSize(), many);
        assertTrue(has(nine.validate(true), Severity.ERROR, ScenarioDraft.BODIES));
        ScenarioDraft eight = new ScenarioDraft(base.g(), base.softening(), base.stepSize(),
                many.subList(0, ScenarioDraft.MAX_BODIES));
        assertTrue(eight.validate(true).isValid(), eight.validate(true).issues().toString());
    }

    @Test
    void coincidentBodiesAreAnErrorOnlyWithoutSoftening() {
        ScenarioDraft base = ScenarioDraft.twoBodyCircular();
        ScenarioDraft same = with(base, 1, "x", "-0.5"); // body 2 onto body 1
        Validation hard = same.validate(true);
        assertFalse(hard.isValid());
        assertTrue(has(hard, Severity.ERROR, "body2.x"), hard.issues().toString());
        assertTrue(hard.firstError().contains("same position"), hard.firstError());

        Validation soft = new ScenarioDraft(same.g(), "0.1", same.stepSize(), same.bodies()).validate(true);
        assertTrue(soft.isValid(), soft.issues().toString());
        assertTrue(has(soft, Severity.WARNING, ScenarioDraft.SOFTENING));
    }

    @Test
    void numbersTooLargeToEvaluateAreRefusedInsteadOfProducingNaN() {
        ScenarioDraft base = ScenarioDraft.twoBodyCircular();
        Validation farAway = with(base, 1, "x", "1e200").validate(true);   // separation squared overflows
        assertFalse(farAway.isValid());
        assertTrue(has(farAway, Severity.ERROR, ScenarioDraft.BODIES), farAway.issues().toString());

        Validation fast = with(base, 0, "vy", "1e200").validate(true);     // kinetic energy overflows
        assertFalse(fast.isValid());
        assertTrue(fast.firstError().contains("overflow"), fast.firstError());
    }

    // ---------------------------------------------------------------- notes that never block a run

    @Test
    void softeningIsAlwaysLabelledNonNewtonian() {
        ScenarioDraft base = ScenarioDraft.twoBodyCircular();
        Validation v = new ScenarioDraft(base.g(), "0.05", base.stepSize(), base.bodies()).validate(true);
        assertTrue(v.isValid());
        Issue note = v.issues().stream().filter(i -> i.field().equals(ScenarioDraft.SOFTENING)).findFirst().get();
        assertEquals(Severity.WARNING, note.severity());
        assertTrue(note.message().contains("non-Newtonian"), note.message());
        assertFalse(v.scenario().system().isExactNewtonian());
    }

    @Test
    void netMomentumIsNotedAndTheCentreOfMassFrameRemovesIt() {
        ScenarioDraft moving = with(with(ScenarioDraft.twoBodyCircular(), 0, "vx", "0.3"), 1, "vx", "0.3");
        Validation v = moving.validate(true);
        assertTrue(v.isValid());
        assertTrue(has(v, Severity.INFO, ScenarioDraft.BODIES), v.issues().toString());
        assertTrue(v.issues().get(0).message().contains("Net momentum"), v.issues().toString());

        ScenarioDraft centred = moving.centred();
        Validation c = centred.validate(true);
        assertTrue(c.isValid());
        assertTrue(c.issues().isEmpty(), "no notes expected after centring: " + c.issues());
        assertTrue(c.scenario().invariants().centreOfMass().norm() < 1e-15);
        assertTrue(c.scenario().invariants().momentum().norm() < 1e-15);
        // the motion between the bodies is untouched
        Vec3 before = v.scenario().initial().velocity(1).minus(v.scenario().initial().velocity(0));
        Vec3 after = c.scenario().initial().velocity(1).minus(c.scenario().initial().velocity(0));
        assertEquals(0.0, before.minus(after).norm(), 1e-15);
        Vec3 sepBefore = v.scenario().initial().position(1).minus(v.scenario().initial().position(0));
        Vec3 sepAfter = c.scenario().initial().position(1).minus(c.scenario().initial().position(0));
        assertEquals(0.0, sepBefore.minus(sepAfter).norm(), 1e-15);
    }

    @Test
    void anUnboundSystemIsNotedUsingTheEnergyInTheCentreOfMassFrame() {
        ScenarioDraft fast = with(with(ScenarioDraft.twoBodyCircular(), 0, "vy", "-2"), 1, "vy", "2");
        Validation v = fast.validate(true);
        assertTrue(v.isValid());
        assertTrue(v.issues().stream().anyMatch(i -> i.message().contains("not bound")), v.issues().toString());

        // a bound pair that merely drifts quickly through space is NOT unbound: the drift energy must not count
        ScenarioDraft drifting = with(with(ScenarioDraft.twoBodyCircular(), 0, "vx", "50"), 1, "vx", "50");
        Validation d = drifting.validate(true);
        assertTrue(d.isValid());
        assertFalse(d.issues().stream().anyMatch(i -> i.message().contains("not bound")), d.issues().toString());
    }

    @Test
    void aCoarseFixedStepIsWarnedAboutButAnAdaptiveRunIsNot() {
        ScenarioDraft base = ScenarioDraft.twoBodyCircular();   // shortest pair timescale 1/sqrt(2) = 0.707
        ScenarioDraft coarse = new ScenarioDraft(base.g(), base.softening(), "0.1", base.bodies());
        Validation fixed = coarse.validate(true);
        assertTrue(fixed.isValid());
        assertTrue(has(fixed, Severity.WARNING, ScenarioDraft.STEP_SIZE), fixed.issues().toString());
        assertTrue(fixed.issues().stream().anyMatch(i -> i.message().contains("rule of thumb")));

        Validation adaptive = coarse.validate(false);
        assertTrue(adaptive.isValid());
        assertFalse(has(adaptive, Severity.WARNING, ScenarioDraft.STEP_SIZE), adaptive.issues().toString());

        ScenarioDraft fine = new ScenarioDraft(base.g(), base.softening(), "0.01", base.bodies());
        assertFalse(has(fine.validate(true), Severity.WARNING, ScenarioDraft.STEP_SIZE));
    }

    @Test
    void centringAnInvalidDraftIsAnErrorNotASilentNoOp() {
        ScenarioDraft bad = with(ScenarioDraft.twoBodyCircular(), 0, "mass", "x");
        assertThrows(IllegalStateException.class, bad::centred);
    }

    @Test
    void aValidationWithErrorsNeverCarriesAScenario() {
        Validation v = with(ScenarioDraft.twoBodyCircular(), 0, "mass", "-3").validate(true);
        assertFalse(v.isValid());
        assertTrue(v.scenario() == null);
        assertFalse(v.errors().isEmpty());
    }
}
