package dev.chaoslab.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GapHistoryTest {

    @Test
    void storesEverySampleWhileThereIsRoom() {
        GapHistory h = new GapHistory(8);
        for (int i = 0; i < 8; i++) {
            h.offer(0.5 * i, 1e-6 * (i + 1), 1e-9 * (i + 1));
        }
        assertEquals(8, h.size());
        assertEquals(1, h.stride());
        assertEquals(3.5, h.time(7), 0.0);
        assertEquals(8e-6, h.gap(7), 0.0);
        assertEquals(8e-9, h.uncertainty(7), 0.0);
        assertEquals(0.0, h.startTime(), 0.0);
    }

    @Test
    void valuesAreStoredAsMagnitudes() {
        GapHistory h = new GapHistory(4);
        h.offer(0.0, -3.5e-4, -2e-9);
        assertEquals(3.5e-4, h.gap(0), 0.0);
        assertEquals(2e-9, h.uncertainty(0), 0.0);
    }

    @Test
    void theBufferNeverExceedsItsCapacityHoweverLongTheRun() {
        GapHistory h = new GapHistory(64);
        long n = 1_000_000;
        for (long i = 0; i < n; i++) {
            h.offer(i * 0.005, 1e-9, 1e-12);
        }
        assertTrue(h.size() <= 64);
        assertEquals(n, h.acceptedSamples());
        assertEquals((n + h.stride() - 1) / h.stride(), h.size());
    }

    @Test
    void aSingleJumpSurvivesAnyNumberOfCompactions() {
        GapHistory h = new GapHistory(16);
        long jumpAt = 3777;
        for (long i = 0; i < 20_000; i++) {
            double g = i == jumpAt ? 0.75 : 1e-9;
            h.offer(i * 0.01, g, 1e-12);
        }
        double max = 0.0;
        for (int i = 0; i < h.size(); i++) {
            max = Math.max(max, h.gap(i));
        }
        assertEquals(0.75, max, 0.0);
        assertTrue(h.stride() > 1);
    }

    @Test
    void timeStaysIncreasingAndEndsAtTheLatestSample() {
        GapHistory h = new GapHistory(32);
        for (long i = 0; i < 5000; i++) {
            h.offer(2.0 + i * 0.1, 1e-10, 1e-13);
        }
        for (int i = 1; i < h.size(); i++) {
            assertTrue(h.time(i) > h.time(i - 1), "time must increase at " + i);
        }
        assertEquals(2.0 + 4999 * 0.1, h.time(h.size() - 1), 1e-9);
        assertEquals(2.0, h.startTime(), 0.0);
    }

    @Test
    void anEarlierTimeMeansResetAndRestartsTheHistory() {
        GapHistory h = new GapHistory(8);
        for (int i = 0; i < 100; i++) {
            h.offer(i * 0.1, 1e-3, 1e-9);
        }
        assertTrue(h.size() > 1);
        h.offer(0.0, 5e-5, 1e-9); // the run was reset: time went back
        assertEquals(1, h.size());
        assertEquals(1, h.stride());
        assertEquals(1, h.acceptedSamples());
        assertEquals(5e-5, h.gap(0), 0.0);
        assertEquals(0.0, h.startTime(), 0.0);
    }

    @Test
    void aRepeatedTimeCarriesNoNewInformationAndIsIgnored() {
        GapHistory h = new GapHistory(8);
        h.offer(1.0, 1e-3, 1e-9);
        h.offer(1.0, 9e-1, 1e-9);
        assertEquals(1, h.size());
        assertEquals(1e-3, h.gap(0), 0.0);
        assertEquals(1, h.acceptedSamples());
    }

    @Test
    void anUnreachableReferenceIsIgnoredAndAnUnknownUncertaintyIsKeptAsUnknown() {
        GapHistory h = new GapHistory(8);
        h.offer(1.0, Double.NaN, 1e-9);            // no gap: nothing to plot
        h.offer(Double.NaN, 1e-3, 1e-9);
        h.offer(Double.POSITIVE_INFINITY, 1e-3, 1e-9);
        assertEquals(0, h.size());
        h.offer(2.0, 1e-3, Double.NaN);
        assertEquals(1, h.size());
        assertTrue(Double.isNaN(h.uncertainty(0)));
        assertEquals(1e-3, h.gap(0), 0.0);
    }

    @Test
    void mergingKeepsTheKnownUncertaintyWhenOnlyOneSideIsKnown() {
        GapHistory h = new GapHistory(4);
        h.offer(1.0, 1e-3, Double.NaN);
        h.offer(2.0, 1e-3, 4e-9);
        h.offer(3.0, 1e-3, Double.NaN);
        h.offer(4.0, 1e-3, Double.NaN);
        h.offer(5.0, 1e-3, Double.NaN);            // buffer full: merges (1,2) and (3,4), stride 2
        assertEquals(2, h.stride());
        assertEquals(2.0, h.time(0), 0.0);          // a merged entry carries the time of its latest sample
        assertEquals(4.0, h.time(1), 0.0);
        assertEquals(4e-9, h.uncertainty(0), 0.0);  // unknown merged with 4e-9 gives 4e-9
        assertTrue(Double.isNaN(h.uncertainty(1))); // unknown merged with unknown stays unknown
    }

    @Test
    void clearForgetsEverythingButKeepsTheCapacity() {
        GapHistory h = new GapHistory(8);
        for (int i = 0; i < 50; i++) {
            h.offer(10.0 + i, 1e-3, 1e-9);
        }
        assertEquals(10.0, h.startTime(), 0.0);
        h.clear();
        assertEquals(0.0, h.startTime(), 0.0);
        assertEquals(0, h.size());
        assertEquals(1, h.stride());
        assertEquals(0L, h.acceptedSamples());
        assertEquals(8, h.capacity());
        h.offer(0.0, 2e-3, 1e-9); // time 0 is accepted again (no stale "last time")
        assertEquals(1, h.size());
    }

    @Test
    void invalidArgumentsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new GapHistory(3));
        assertThrows(IllegalArgumentException.class, () -> new GapHistory(7));
        GapHistory h = new GapHistory(4);
        assertThrows(IndexOutOfBoundsException.class, () -> h.gap(0));
        assertThrows(IndexOutOfBoundsException.class, () -> h.time(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> h.uncertainty(0));
    }
}
