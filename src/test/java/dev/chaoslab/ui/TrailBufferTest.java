package dev.chaoslab.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link TrailBuffer}. Failure interpretation: the buffer feeds the orbit trails, so an error here shows
 * as trails drawn in the wrong order, with a jump where the ring wraps, or with stale points after a reset.
 */
class TrailBufferTest {

    /** Two bodies; body 0 at (n, 10n), body 1 at (-n, 100n). z is set to a sentinel that must never be stored. */
    private static double[] sample(double n) {
        return new double[] {n, 10 * n, 999, -n, 100 * n, 999};
    }

    @Test
    void startsEmptyAndReportsNoBounds() {
        TrailBuffer t = new TrailBuffer(2, 5);
        assertEquals(0, t.size());
        double[] out = {7, 7, 7, 7};
        assertTrue(!t.bounds(out), "empty buffer has no bounds");
        assertEquals(7.0, out[0], 0.0); // left untouched
    }

    @Test
    void storesSamplesOldestFirstAndIgnoresZ() {
        TrailBuffer t = new TrailBuffer(2, 5);
        for (int n = 1; n <= 3; n++) {
            t.append(sample(n));
        }
        assertEquals(3, t.size());
        for (int k = 0; k < 3; k++) {
            assertEquals(k + 1, t.x(0, k), 0.0);
            assertEquals(10 * (k + 1), t.y(0, k), 0.0);
            assertEquals(-(k + 1), t.x(1, k), 0.0);
            assertEquals(100 * (k + 1), t.y(1, k), 0.0);
        }
    }

    @Test
    void whenFullTheOldestSamplesAreOverwrittenAndOrderIsPreserved() {
        TrailBuffer t = new TrailBuffer(2, 4);
        for (int n = 1; n <= 11; n++) {   // wraps the ring two and a half times
            t.append(sample(n));
        }
        assertEquals(4, t.size());
        for (int k = 0; k < 4; k++) {     // must hold 8, 9, 10, 11 in that order
            assertEquals(8 + k, t.x(0, k), 0.0);
            assertEquals(-(8 + k), t.x(1, k), 0.0);
        }
    }

    @Test
    void boundsCoverExactlyTheStoredSamplesOfAllBodies() {
        TrailBuffer t = new TrailBuffer(2, 3);
        for (int n = 1; n <= 6; n++) {    // keeps 4, 5, 6
            t.append(sample(n));
        }
        double[] b = new double[4];
        assertTrue(t.bounds(b), "bounds expected");
        assertEquals(-6.0, b[0], 0.0);    // minX comes from body 1 at n = 6
        assertEquals(6.0, b[1], 0.0);     // maxX from body 0 at n = 6
        assertEquals(40.0, b[2], 0.0);    // minY: body 0 at n = 4 is 40 (body 1's y are 400..600)
        assertEquals(600.0, b[3], 0.0);   // maxY: body 1 at n = 6
    }

    @Test
    void clearForgetsEverythingAndTheBufferIsReusable() {
        TrailBuffer t = new TrailBuffer(2, 3);
        for (int n = 1; n <= 5; n++) {
            t.append(sample(n));
        }
        t.clear();
        assertEquals(0, t.size());
        t.append(sample(42));
        assertEquals(1, t.size());
        assertEquals(42.0, t.x(0, 0), 0.0);
    }

    @Test
    void outOfRangeAccessIsRejected() {
        TrailBuffer t = new TrailBuffer(2, 3);
        t.append(sample(1));
        assertThrows(IndexOutOfBoundsException.class, () -> t.x(0, 1));
        assertThrows(IndexOutOfBoundsException.class, () -> t.x(0, -1));
        assertThrows(IndexOutOfBoundsException.class, () -> t.x(2, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> t.y(-1, 0));
    }

    @Test
    void invalidInputIsRejectedAndLeavesTheBufferUnchanged() {
        TrailBuffer t = new TrailBuffer(2, 3);
        t.append(sample(1));
        assertThrows(IllegalArgumentException.class, () -> t.append(new double[3]));
        assertThrows(IllegalArgumentException.class, () -> t.append(null));
        assertThrows(IllegalArgumentException.class,
                () -> t.append(new double[] {1, 2, 0, Double.NaN, 4, 0}));
        assertThrows(IllegalArgumentException.class,
                () -> t.append(new double[] {Double.POSITIVE_INFINITY, 2, 0, 3, 4, 0}));
        assertEquals(1, t.size());
        assertEquals(1.0, t.x(0, 0), 0.0);
        assertThrows(IllegalArgumentException.class, () -> new TrailBuffer(0, 5));
        assertThrows(IllegalArgumentException.class, () -> new TrailBuffer(2, 1));
        assertThrows(IllegalArgumentException.class, () -> t.bounds(new double[3]));
    }
}
