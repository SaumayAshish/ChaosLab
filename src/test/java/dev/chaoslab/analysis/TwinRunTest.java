package dev.chaoslab.analysis;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.chaoslab.experiments.FigureEight;
import dev.chaoslab.experiments.LagrangeTriangle;
import dev.chaoslab.physics.NBodySystem;
import dev.chaoslab.physics.State;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link TwinRun}. The growth figures were measured with this class before the bounds were set: a 1e-6
 * nudge on the displaced Lagrange triangle grows to about 1.1 system sizes by t = 40 (a factor of about 1e6), the
 * figure-eight stays near 2.5e-4 at t = 126, and a circular two-body orbit drifts apart roughly linearly.
 */
class TwinRunTest {

    private static State twoBody() {
        return State.of(0.0, new double[] {0, 0, 0, 1, 0, 0}, new double[] {0, 0, 0, 0, 1, 0});
    }

    @Test
    void theNudgeMovesOnlyTheXOfBodyZero() {
        State s = FigureEight.state();
        State n = TwinRun.nudged(s);
        double[] q = s.positions();
        double[] p = n.positions();
        assertEquals(TwinRun.NUDGE * TwinRun.characteristicLength(s), p[0] - q[0], 1e-15);
        for (int i = 1; i < q.length; i++) {
            assertEquals(q[i], p[i], 0.0, "component " + i);
        }
        assertArrayEquals(s.velocities(), n.velocities(), 0.0);
        assertEquals(s.time(), n.time(), 0.0);
    }

    @Test
    void theSeparationAtTheStartIsExactlyTheNudge() {
        TwinRun t = new TwinRun(FigureEight.system(), FigureEight.state());
        TwinRun.Reading r = t.at(0.0);
        assertNull(r.failure());
        assertEquals(t.initialSeparation(), r.separation(), 1e-14);
        assertEquals(TwinRun.NUDGE, r.relativeSeparation(), 1e-14);
        double[] offsets = r.offsets();
        assertEquals(t.initialSeparation(), offsets[0], 1e-14);
        for (int i = 1; i < offsets.length; i++) {
            assertEquals(0.0, offsets[i], 1e-14, "component " + i);
        }
    }

    @Test
    void theCharacteristicLengthIsTheLyapunovTrackersLength() {
        State s = LagrangeTriangle.state(0.01);
        LyapunovTracker tracker = new LyapunovTracker(LagrangeTriangle.system(), s,
                LyapunovTracker.DEFAULT_TOLERANCES, LyapunovTracker.DEFAULT_SEPARATION, 1L);
        assertEquals(tracker.characteristicLength(), TwinRun.characteristicLength(s), 1e-14);
    }

    @Test
    void aChaoticStartAmplifiesTheNudgeByMoreThanFiveOrdersOfMagnitude() {
        TwinRun t = new TwinRun(LagrangeTriangle.system(), LagrangeTriangle.state(0.01));
        double start = t.at(0.0).separation();
        double later = t.at(40.0).separation();
        assertTrue(later / start > 1e5, "growth factor " + later / start);
    }

    @Test
    void aStableOrbitKeepsTheNudgeSmall() {
        TwinRun t = new TwinRun(FigureEight.system(), FigureEight.state());
        double rel = t.at(126.0).relativeSeparation();
        assertTrue(rel < 5e-4, "relative separation " + rel);
        assertTrue(rel > TwinRun.NUDGE, "it does still grow a little: " + rel);
    }

    @Test
    void aTwoBodyOrbitSeparatesRoughlyLinearlyNotExponentially() {
        TwinRun t = new TwinRun(NBodySystem.normalized(1.0, 1e-3), twoBody());
        double at60 = t.at(60.0).separation();
        double at600 = t.at(600.0).separation();
        double ratio = at600 / at60;
        assertTrue(ratio > 5.0 && ratio < 15.0, "ten times the time should give about ten times the separation: " + ratio);
    }

    @Test
    void theSeparationIsTheLargestBodyDistanceOfTheOffsets() {
        TwinRun t = new TwinRun(LagrangeTriangle.system(), LagrangeTriangle.state(0.01));
        TwinRun.Reading r = t.at(25.0);
        double[] o = r.offsets();
        double largest = 0.0;
        for (int i = 0; i < o.length / 3; i++) {
            largest = Math.max(largest, Math.sqrt(o[3 * i] * o[3 * i] + o[3 * i + 1] * o[3 * i + 1]
                    + o[3 * i + 2] * o[3 * i + 2]));
        }
        assertEquals(largest, r.separation(), 0.0);
    }

    @Test
    void theSeparationIsMeasuredInThreeDimensions() {
        // a circular two-body orbit in the x-z plane: the nudge along x grows components along z
        State s = State.of(0.0, new double[] {0, 0, 0, 1, 0, 0}, new double[] {0, 0, 0, 0, 0, 1});
        TwinRun t = new TwinRun(NBodySystem.normalized(1.0, 1e-3), s);
        TwinRun.Reading r = t.at(2.5); // measured: the z part is about 6.7 times the x-y part here
        double[] o = r.offsets();
        double full = 0.0;
        double planar = 0.0;
        for (int i = 0; i < 2; i++) {
            full = Math.max(full, Math.sqrt(o[3 * i] * o[3 * i] + o[3 * i + 1] * o[3 * i + 1]
                    + o[3 * i + 2] * o[3 * i + 2]));
            planar = Math.max(planar, Math.hypot(o[3 * i], o[3 * i + 1]));
        }
        assertTrue(full > planar * 3.0, "the z offsets must matter in this test: " + full + " vs " + planar);
        assertEquals(full, r.separation(), 0.0);
    }

    @Test
    void theAnswerDoesNotDependOnTheOrderOfTheQuestions() {
        TwinRun forward = new TwinRun(LagrangeTriangle.system(), LagrangeTriangle.state(0.01));
        TwinRun jumpy = new TwinRun(LagrangeTriangle.system(), LagrangeTriangle.state(0.01));
        double a = forward.at(15.0).separation();
        jumpy.at(30.0);
        double b = jumpy.at(15.0).separation();
        assertEquals(a, b, 0.0);
    }

    @Test
    void aRunThatCannotReachTheTimeReportsAFailureInsteadOfThrowing() {
        TwinRun t = new TwinRun(NBodySystem.normalized(1.0, 1.0),
                State.of(0.0, new double[] {0, 0, 0, 1, 0, 0}, new double[6]));
        assertNull(t.at(0.5).failure(), "before the collision at about 0.785");
        TwinRun.Reading r = t.at(1.0);
        assertNotNull(r.failure());
        assertNull(r.offsets());
        assertTrue(Double.isNaN(r.separation()));
        assertTrue(Double.isNaN(r.relativeSeparation()));
    }

    @Test
    void invalidArgumentsAreRejected() {
        TwinRun t = new TwinRun(FigureEight.system(), FigureEight.state());
        assertThrows(IllegalArgumentException.class, () -> t.at(-1.0));
        assertThrows(IllegalArgumentException.class, () -> t.at(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> new TwinRun(null, FigureEight.state()));
        assertThrows(IllegalArgumentException.class, () -> new TwinRun(FigureEight.system(), null));
    }

    @Test
    void readingsHideTheirOffsetArray() {
        TwinRun.Reading r = new TwinRun(FigureEight.system(), FigureEight.state()).at(0.0);
        double[] first = r.offsets();
        first[0] = 99.0;
        assertEquals(TwinRun.NUDGE * 2.0, r.offsets()[0], 1e-14);
    }
    @Test
    void everyDirectionMovesExactlyTheChosenComponentOfTheChosenBody() {
        State s = FigureEight.state();
        double shift = TwinRun.NUDGE * TwinRun.characteristicLength(s);
        double[] q = s.positions();
        for (int body = 0; body < s.bodyCount(); body++) {
            for (TwinRun.Direction d : TwinRun.Direction.values()) {
                State n = TwinRun.nudged(s, new TwinRun.Nudge(body, d));
                double[] p = n.positions();
                for (int i = 0; i < q.length; i++) {
                    double expected = i == 3 * body + d.axis() ? d.sign() * shift : 0.0;
                    assertEquals(expected, p[i] - q[i], 1e-15, "body " + body + " " + d + " component " + i);
                }
                assertArrayEquals(s.velocities(), n.velocities(), 0.0);
            }
        }
    }

    @Test
    void theDirectionsHaveTheirAxesAndSigns() {
        assertEquals(0, TwinRun.Direction.PLUS_X.axis());
        assertEquals(1, TwinRun.Direction.MINUS_Y.axis());
        assertEquals(2, TwinRun.Direction.PLUS_Z.axis());
        assertEquals(1.0, TwinRun.Direction.PLUS_Y.sign(), 0.0);
        assertEquals(-1.0, TwinRun.Direction.MINUS_Z.sign(), 0.0);
        assertEquals("-x", TwinRun.Direction.MINUS_X.toString());
    }

    @Test
    void theDefaultNudgeIsBodyZeroAlongPlusXAndChangesNothingForExistingCallers() {
        State s = FigureEight.state();
        assertArrayEquals(TwinRun.nudged(s).positions(), TwinRun.nudged(s, TwinRun.Nudge.DEFAULT).positions(), 0.0);
        assertEquals(0, TwinRun.Nudge.DEFAULT.body());
        assertEquals(TwinRun.Direction.PLUS_X, TwinRun.Nudge.DEFAULT.direction());
        TwinRun a = new TwinRun(FigureEight.system(), s);
        TwinRun b = new TwinRun(FigureEight.system(), s, TwinRun.Nudge.DEFAULT);
        assertEquals(a.at(5.0).separation(), b.at(5.0).separation(), 0.0);
    }

    @Test
    void aChosenNudgeStartsTheOffsetOnTheChosenComponentOnly() {
        TwinRun t = new TwinRun(FigureEight.system(), FigureEight.state(),
                new TwinRun.Nudge(2, TwinRun.Direction.MINUS_Y));
        double[] offsets = t.at(0.0).offsets();
        for (int i = 0; i < offsets.length; i++) {
            double expected = i == 3 * 2 + 1 ? -t.initialSeparation() : 0.0;
            assertEquals(expected, offsets[i], 1e-14, "component " + i);
        }
        assertEquals(t.initialSeparation(), t.at(0.0).separation(), 1e-14);
    }

    @Test
    void differentChoicesGiveDifferentCurvesButBothAmplifyTheNudge() {
        TwinRun plusX = new TwinRun(LagrangeTriangle.system(), LagrangeTriangle.state(0.01));
        TwinRun minusY = new TwinRun(LagrangeTriangle.system(), LagrangeTriangle.state(0.01),
                new TwinRun.Nudge(1, TwinRun.Direction.MINUS_Y));
        double a = plusX.at(30.0).relativeSeparation();
        double b = minusY.at(30.0).relativeSeparation();
        assertTrue(Math.abs(a - b) > 1e-3 * Math.max(a, b), "a=" + a + " b=" + b);
        assertTrue(a > 1e3 * TwinRun.NUDGE && b > 1e3 * TwinRun.NUDGE, "a=" + a + " b=" + b);
    }

    @Test
    void invalidNudgesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new TwinRun.Nudge(-1, TwinRun.Direction.PLUS_X));
        assertThrows(IllegalArgumentException.class, () -> new TwinRun.Nudge(0, null));
        assertThrows(IllegalArgumentException.class, () -> new TwinRun(FigureEight.system(), FigureEight.state(), null));
        TwinRun.Nudge outside = new TwinRun.Nudge(3, TwinRun.Direction.PLUS_X);
        assertThrows(IllegalArgumentException.class,
                () -> new TwinRun(FigureEight.system(), FigureEight.state(), outside));
        assertThrows(IllegalArgumentException.class, () -> TwinRun.nudged(FigureEight.state(), outside));
        new TwinRun.Nudge(2, TwinRun.Direction.PLUS_Z); // the last body is allowed
    }

    @Test
    void theLabelNumbersBodiesFromOne() {
        assertEquals("m1 along +x", TwinRun.Nudge.DEFAULT.label());
        assertEquals("m3 along -z", new TwinRun.Nudge(2, TwinRun.Direction.MINUS_Z).label());
    }
}
