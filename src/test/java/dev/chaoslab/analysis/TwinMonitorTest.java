package dev.chaoslab.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.chaoslab.experiments.FigureEight;
import dev.chaoslab.experiments.LagrangeTriangle;
import org.junit.jupiter.api.Test;

/** Tests for {@link TwinMonitor}. Waits are polls with a generous deadline, never fixed sleeps that must be exact. */
class TwinMonitorTest {

    private static final long DEADLINE_MILLISECONDS = 30_000L;

    private static TwinRun.Reading await(TwinMonitor m, double time) throws InterruptedException {
        long end = System.currentTimeMillis() + DEADLINE_MILLISECONDS;
        while (System.currentTimeMillis() < end) {
            TwinRun.Reading r = m.latest();
            if (r != null && r.time() == time) {
                return r;
            }
            Thread.sleep(5);
        }
        throw new AssertionError("no reading for t = " + time + " within the deadline");
    }

    @Test
    void aReadingEqualsTheDirectComputationBitForBit() throws InterruptedException {
        try (TwinMonitor m = new TwinMonitor(LagrangeTriangle.system(), LagrangeTriangle.state(0.01))) {
            m.submit(20.0);
            TwinRun.Reading viaThread = await(m, 20.0);
            TwinRun.Reading direct = new TwinRun(LagrangeTriangle.system(), LagrangeTriangle.state(0.01)).at(20.0);
            assertEquals(direct.separation(), viaThread.separation(), 0.0);
            assertEquals(direct.length(), viaThread.length(), 0.0);
            assertTrue(java.util.Arrays.equals(direct.offsets(), viaThread.offsets()));
        }
    }

    @Test
    void onlyTheNewestRequestMattersAndTheLastOneIsAlwaysAnswered() throws InterruptedException {
        try (TwinMonitor m = new TwinMonitor(FigureEight.system(), FigureEight.state())) {
            for (int k = 1; k <= 200; k++) {
                m.submit(0.1 * k);
            }
            TwinRun.Reading last = await(m, 20.0);
            assertNull(last.failure());
        }
    }

    @Test
    void resetDiscardsTheReadingAtOnce() throws InterruptedException {
        try (TwinMonitor m = new TwinMonitor(FigureEight.system(), FigureEight.state())) {
            m.submit(5.0);
            assertNotNull(await(m, 5.0));
            m.reset();
            assertNull(m.latest());
            m.submit(1.0);
            assertEquals(1.0, await(m, 1.0).time(), 0.0);
        }
    }

    @Test
    void aRequestBeingWorkedOnWhenResetHappensIsNeverPublished() throws InterruptedException {
        try (TwinMonitor m = new TwinMonitor(LagrangeTriangle.system(), LagrangeTriangle.state(0.01))) {
            m.submit(150.0); // a long computation
            Thread.sleep(5);
            m.reset();
            long end = System.currentTimeMillis() + 3000L;
            while (System.currentTimeMillis() < end) {
                assertNull(m.latest(), "a reading from before the reset appeared");
                Thread.sleep(10);
            }
        }
    }

    @Test
    void closeStopsTheThreadAndLaterSubmitsAreIgnored() throws InterruptedException {
        TwinMonitor m = new TwinMonitor(FigureEight.system(), FigureEight.state());
        assertTrue(m.isRunning());
        m.close();
        assertFalse(m.isRunning());
        m.submit(1.0); // ignored, no exception
        m.close(); // idempotent
        assertNull(m.latest());
    }

    @Test
    void lengthIsTheCharacteristicLength() {
        try (TwinMonitor m = new TwinMonitor(FigureEight.system(), FigureEight.state())) {
            assertEquals(TwinRun.characteristicLength(FigureEight.state()), m.length(), 0.0);
        }
    }

    @Test
    void invalidArgumentsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new TwinMonitor(null, FigureEight.state()));
        assertThrows(IllegalArgumentException.class, () -> new TwinMonitor(FigureEight.system(), null));
        try (TwinMonitor m = new TwinMonitor(FigureEight.system(), FigureEight.state())) {
            assertThrows(IllegalArgumentException.class, () -> m.submit(-1.0));
            assertThrows(IllegalArgumentException.class, () -> m.submit(Double.POSITIVE_INFINITY));
        }
    }
}
