package dev.chaoslab.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TelemetryHistoryTest {

    @Test
    void storesEverySampleWhileThereIsRoom() {
        TelemetryHistory h = new TelemetryHistory(8);
        for (int i = 0; i < 8; i++) {
            h.offer(i, 0.5 * i, 1e-6 * (i + 1), 2e-6 * (i + 1), 3e-6 * (i + 1));
        }
        assertEquals(8, h.size());
        assertEquals(1, h.stride());
        assertEquals(3.5, h.time(7), 0.0);
        assertEquals(8e-6, h.energy(7), 0.0);
        assertEquals(16e-6, h.momentum(7), 0.0);
        assertEquals(24e-6, h.angularMomentum(7), 0.0);
        assertEquals(0.0, h.startTime(), 0.0);
    }

    @Test
    void valuesAreStoredAsMagnitudes() {
        TelemetryHistory h = new TelemetryHistory(4);
        h.offer(0, 0.0, -3.5e-4, 0.0, 0.0);
        assertEquals(3.5e-4, h.energy(0), 0.0);
    }

    @Test
    void theBufferNeverExceedsItsCapacityHoweverLongTheRun() {
        TelemetryHistory h = new TelemetryHistory(64);
        long n = 1_000_000;
        for (long i = 0; i < n; i++) {
            h.offer(i, i * 0.005, 1e-9, 1e-15, 1e-15);
        }
        assertTrue(h.size() <= 64);
        assertEquals(n, h.acceptedSamples());
        // every entry but the newest stands for exactly 'stride' samples, so the count is fully accounted for
        assertEquals((n + h.stride() - 1) / h.stride(), h.size());
    }

    @Test
    void aSingleSpikeSurvivesAnyNumberOfCompactions() {
        TelemetryHistory h = new TelemetryHistory(16);
        long spikeAt = 3777;
        for (long i = 0; i < 20_000; i++) {
            double e = i == spikeAt ? 0.75 : 1e-12;
            h.offer(i, i * 0.01, e, 1e-15, 1e-15);
        }
        double max = 0.0;
        for (int i = 0; i < h.size(); i++) {
            max = Math.max(max, h.energy(i));
        }
        assertEquals(0.75, max, 0.0);
        assertTrue(h.stride() > 1);
    }

    @Test
    void timeStaysIncreasingAndEndsAtTheLatestSample() {
        TelemetryHistory h = new TelemetryHistory(32);
        for (long i = 0; i < 5000; i++) {
            h.offer(i, 2.0 + i * 0.1, 1e-10, 1e-14, 1e-14);
        }
        for (int i = 1; i < h.size(); i++) {
            assertTrue(h.time(i) > h.time(i - 1), "time must increase at " + i);
        }
        assertEquals(2.0 + 4999 * 0.1, h.time(h.size() - 1), 1e-9);
        assertEquals(2.0, h.startTime(), 0.0);   // the start of the run is remembered even after merging
    }

    @Test
    void aLowerStepCountMeansResetAndRestartsTheHistory() {
        TelemetryHistory h = new TelemetryHistory(8);
        for (int i = 0; i < 100; i++) {
            h.offer(i, i * 0.1, 1e-3, 1e-14, 1e-14);
        }
        assertTrue(h.stride() > 1);
        h.offer(0, 0.0, 0.0, 0.0, 0.0);          // the simulation was reset
        assertEquals(1, h.size());
        assertEquals(1, h.stride());
        assertEquals(0.0, h.energy(0), 0.0);
        assertEquals(1, h.acceptedSamples());
    }

    @Test
    void aRepeatedStepCountIsIgnored() {
        TelemetryHistory h = new TelemetryHistory(8);
        h.offer(0, 0.0, 0.0, 0.0, 0.0);
        h.offer(0, 0.0, 0.0, 0.0, 0.0);          // e.g. a pause republished the same state
        h.offer(1, 0.01, 1e-12, 0.0, 0.0);
        h.offer(1, 0.01, 1e-12, 0.0, 0.0);
        assertEquals(2, h.size());
        assertEquals(2, h.acceptedSamples());
    }

    @Test
    void nonFiniteSamplesAreIgnored() {
        TelemetryHistory h = new TelemetryHistory(8);
        h.offer(0, 0.0, 0.0, 0.0, 0.0);
        h.offer(1, 0.01, Double.NaN, 0.0, 0.0);
        h.offer(2, Double.POSITIVE_INFINITY, 0.0, 0.0, 0.0);
        h.offer(3, 0.03, 0.0, Double.NaN, 0.0);
        h.offer(4, 0.04, 0.0, 0.0, Double.NEGATIVE_INFINITY);
        assertEquals(1, h.size());
    }

    @Test
    void clearKeepsTheCapacityAndIndexingIsChecked() {
        TelemetryHistory h = new TelemetryHistory(4);
        h.offer(0, 0.0, 1e-3, 1e-3, 1e-3);
        h.clear();
        assertEquals(0, h.size());
        assertEquals(4, h.capacity());
        assertThrows(IndexOutOfBoundsException.class, () -> h.time(0));
        assertThrows(IndexOutOfBoundsException.class, () -> h.energy(-1));
        h.offer(0, 5.0, 1e-3, 1e-3, 1e-3);       // usable again, and the start time follows the new run
        assertEquals(5.0, h.startTime(), 0.0);
    }

    @Test
    void capacityMustBeEvenAndAtLeastFour() {
        assertThrows(IllegalArgumentException.class, () -> new TelemetryHistory(3));
        assertThrows(IllegalArgumentException.class, () -> new TelemetryHistory(7));
        assertThrows(IllegalArgumentException.class, () -> new TelemetryHistory(2));
        new TelemetryHistory(4);
    }
}
