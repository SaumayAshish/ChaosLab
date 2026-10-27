package dev.chaoslab.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class DriftExtremesTest {

    @Test
    void startsAtZero() {
        DriftExtremes d = new DriftExtremes();
        assertEquals(0.0, d.energy(), 0.0);
        assertEquals(0.0, d.momentum(), 0.0);
        assertEquals(0.0, d.angularMomentum(), 0.0);
    }

    @Test
    void keepsThePeakWhileTheValueFallsBackThroughATrough() {
        DriftExtremes d = new DriftExtremes();
        d.offer(1e-8, 0.0, 0.0);
        d.offer(5e-4, 0.0, 0.0);
        d.offer(2e-8, 0.0, 0.0); // the oscillation is back at a trough
        assertEquals(5e-4, d.energy(), 0.0);
    }

    @Test
    void ignoresSignAndTracksEachQuantityOnItsOwn() {
        DriftExtremes d = new DriftExtremes();
        d.offer(-3e-5, 1e-12, -7e-9);
        d.offer(2e-5, -4e-12, 1e-9);
        assertEquals(3e-5, d.energy(), 0.0);
        assertEquals(4e-12, d.momentum(), 0.0);
        assertEquals(7e-9, d.angularMomentum(), 0.0);
    }

    @Test
    void nanLeavesTheMaximaAloneAndInfinityIsKept() {
        DriftExtremes d = new DriftExtremes();
        d.offer(1e-6, 1e-6, 1e-6);
        d.offer(Double.NaN, Double.NaN, Double.NaN);
        assertEquals(1e-6, d.energy(), 0.0);
        d.offer(Double.NEGATIVE_INFINITY, 0.0, 0.0);
        assertEquals(Double.POSITIVE_INFINITY, d.energy(), 0.0);
    }

    @Test
    void resetForgetsEverything() {
        DriftExtremes d = new DriftExtremes();
        d.offer(1.0, 2.0, 3.0);
        d.reset();
        assertEquals(0.0, d.energy(), 0.0);
        assertEquals(0.0, d.momentum(), 0.0);
        assertEquals(0.0, d.angularMomentum(), 0.0);
        d.offer(1e-9, 0.0, 0.0);
        assertEquals(1e-9, d.energy(), 0.0);
    }
}
