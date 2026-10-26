package dev.chaoslab.ui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Tests for {@link ReferenceFrame}: same-sample subtraction, the fit rule, and the inertial frame as identity. */
class ReferenceFrameTest {

    /** Sun at the origin, Earth on a circle of radius 1000, Moon on a circle of radius 1 around the Earth. */
    private static TrailBuffer hierarchy() {
        TrailBuffer t = new TrailBuffer(3, 400);
        for (int k = 0; k < 400; k++) {
            double a = 2.0 * Math.PI * k / 400.0;
            double ex = 1000.0 * Math.cos(a);
            double ey = 1000.0 * Math.sin(a);
            double m = 12.0 * a;
            t.append(new double[] {0, 0, 0, ex, ey, 0, ex + Math.cos(m), ey + Math.sin(m), 0});
        }
        return t;
    }

    @Test
    void inTheEarthFrameTheMoonMakesItsOwnLoop() {
        TrailBuffer t = hierarchy();
        for (int k = 0; k < t.size(); k += 37) {
            double a = 2.0 * Math.PI * k / 400.0;
            assertEquals(Math.cos(12.0 * a), ReferenceFrame.x(t, 2, k, 1), 1e-12);
            assertEquals(Math.sin(12.0 * a), ReferenceFrame.y(t, 2, k, 1), 1e-12);
        }
    }

    @Test
    void theFrameBodyIsAtTheOriginAtEverySample() {
        TrailBuffer t = hierarchy();
        for (int k = 0; k < t.size(); k += 41) {
            assertEquals(0.0, ReferenceFrame.x(t, 1, k, 1), 0.0);
            assertEquals(0.0, ReferenceFrame.y(t, 1, k, 1), 0.0);
        }
    }

    @Test
    void theSubtractionUsesTheSameSampleNotTheLatestOne() {
        // the frame body moves along x; a companion stays 5 ahead of it, so its relative x is 5 at every sample
        TrailBuffer t = new TrailBuffer(2, 10);
        for (int k = 0; k < 10; k++) {
            t.append(new double[] {k, 0, 0, k + 5.0, 0, 0});
        }
        for (int k = 0; k < 10; k++) {
            assertEquals(5.0, ReferenceFrame.x(t, 1, k, 0), 0.0);
        }
        double[] out = new double[4];
        assertTrue(ReferenceFrame.bounds(t, 0, out));
        assertEquals(5.0, out[1], 0.0); // a latest-position subtraction would give a spread of 9
        assertEquals(0.0, out[0], 0.0); // the frame body itself is at 0
    }

    @Test
    void theInertialFrameChangesNothing() {
        TrailBuffer t = hierarchy();
        for (int k = 0; k < t.size(); k += 53) {
            assertEquals(t.x(2, k), ReferenceFrame.x(t, 2, k, -1), 0.0);
            assertEquals(t.y(2, k), ReferenceFrame.y(t, 2, k, -1), 0.0);
        }
        double[] a = new double[4];
        double[] b = new double[4];
        assertTrue(t.bounds(a));
        assertTrue(ReferenceFrame.bounds(t, -1, b));
        assertArrayEquals(a, b, 0.0);
        boolean[] all = ReferenceFrame.included(t, -1);
        assertTrue(all[0] && all[1] && all[2]);
    }

    @Test
    void theFitRuleKeepsTheMoonAndDropsTheSunInTheEarthFrame() {
        TrailBuffer t = hierarchy();
        double[] far = ReferenceFrame.largestDistances(t, 1);
        assertEquals(0.0, far[1], 0.0);
        assertEquals(1.0, far[2], 1e-9);
        assertEquals(1000.0, far[0], 1e-9);
        boolean[] in = ReferenceFrame.included(t, 1);
        assertTrue(in[1] && in[2]);
        assertFalse(in[0], "the Sun is 1000 times farther than the Moon");
        double[] out = new double[4];
        assertTrue(ReferenceFrame.bounds(t, 1, out));
        assertTrue(out[0] > -1.0001 && out[1] < 1.0001 && out[2] > -1.0001 && out[3] < 1.0001,
                "bounds follow the Moon's loop: " + java.util.Arrays.toString(out));
    }

    @Test
    void theSameBuffersFitsEverythingInTheInertialFrame() {
        double[] out = new double[4];
        assertTrue(ReferenceFrame.bounds(hierarchy(), -1, out));
        assertTrue(out[1] > 999.0 && out[0] < -999.0);
    }

    @Test
    void bodiesOfSimilarScaleAreAllIncluded() {
        TrailBuffer t = new TrailBuffer(3, 5);
        for (int k = 0; k < 5; k++) {
            t.append(new double[] {0, 0, 0, 1, 0, 0, 0, 3, 0});
        }
        boolean[] in = ReferenceFrame.included(t, 0);
        assertTrue(in[0] && in[1] && in[2]);
    }

    @Test
    void theHierarchyFactorIsTheBoundary() {
        TrailBuffer t = new TrailBuffer(3, 5);
        for (int k = 0; k < 5; k++) {
            t.append(new double[] {0, 0, 0, 1, 0, 0, ReferenceFrame.HIERARCHY_FACTOR, 0, 0});
        }
        assertTrue(ReferenceFrame.included(t, 0)[2], "exactly the factor times the closest is still included");
        TrailBuffer u = new TrailBuffer(3, 5);
        for (int k = 0; k < 5; k++) {
            u.append(new double[] {0, 0, 0, 1, 0, 0, Math.nextUp(ReferenceFrame.HIERARCHY_FACTOR), 0, 0});
        }
        assertFalse(ReferenceFrame.included(u, 0)[2]);
    }

    @Test
    void anEmptyBufferHasNoBoundsAndLeavesTheOutputAlone() {
        TrailBuffer t = new TrailBuffer(2, 5);
        double[] out = {7, 7, 7, 7};
        assertFalse(ReferenceFrame.bounds(t, 0, out));
        assertArrayEquals(new double[] {7, 7, 7, 7}, out, 0.0);
        assertEquals(0.0, ReferenceFrame.largestDistances(t, 0)[1], 0.0);
    }

    @Test
    void invalidFramesAndOutputsAreRejected() {
        TrailBuffer t = new TrailBuffer(2, 5);
        assertThrows(IllegalArgumentException.class, () -> ReferenceFrame.included(t, 2));
        assertThrows(IllegalArgumentException.class, () -> ReferenceFrame.included(t, -2));
        assertThrows(IllegalArgumentException.class, () -> ReferenceFrame.bounds(t, 0, new double[3]));
        assertThrows(IllegalArgumentException.class, () -> ReferenceFrame.largestDistances(null, 0));
    }
}
