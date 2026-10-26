package dev.chaoslab.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.chaoslab.config.ScenarioDraft;
import dev.chaoslab.experiments.LagrangeTriangle;
import dev.chaoslab.physics.NBodySystem;
import dev.chaoslab.physics.State;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ReferenceMonitor}: what a reading contains, that it does not depend on how the requests were paced,
 * that a collision is reported without killing the thread, and that reset and close behave.
 */
class ReferenceMonitorTest {

    private static final NBodySystem LAGRANGE = LagrangeTriangle.system();
    private static final NBodySystem TWO_BODY = NBodySystem.normalized(1.0, 1.0);
    private static final long TIMEOUT_MILLIS = 60_000L;

    private static State headOn() {
        return State.of(0.0, new double[] {-0.5, 0, 0, 0.5, 0, 0}, new double[6]);
    }

    /** Waits until the monitor has published a reading for exactly {@code time}. */
    private static ReferenceMonitor.Reading await(ReferenceMonitor m, double time) {
        long deadline = System.nanoTime() + TIMEOUT_MILLIS * 1_000_000L;
        while (System.nanoTime() < deadline) {
            ReferenceMonitor.Reading r = m.latest();
            if (r != null && r.time() == time) {
                return r;
            }
            try {
                Thread.sleep(2);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted");
            }
        }
        throw new AssertionError("no reading for t = " + time + " within " + TIMEOUT_MILLIS + " ms");
    }

    private static ReferenceMonitor.Reading ask(ReferenceMonitor m, double time, double[] displayed) {
        m.submit(time, displayed);
        return await(m, time);
    }

    // ---------------------------------------------------------------- what a reading says

    @Test
    void aDisplayedRunThatIsTheExactSolutionHasATinyGapAndAFiniteUncertainty() {
        try (ReferenceMonitor m = new ReferenceMonitor(LAGRANGE, LagrangeTriangle.state(0.0))) {
            ReferenceMonitor.Reading r = ask(m, 10.0, LagrangeTriangle.exactState(10.0).positions());
            assertTrue(r.referenceFailure() == null);
            assertTrue(r.gap() < 1e-10, "gap " + r.gap());                       // measured 4.5e-12
            assertTrue(r.uncertainty() >= 0.0 && r.uncertainty() < 1e-10, "uncertainty " + r.uncertainty());
            assertEquals(Math.sqrt(3.0), r.length(), 1e-12);
            assertEquals(r.gap() / Math.sqrt(3.0), r.relativeGap(), 0.0);
            assertEquals(r.uncertainty() / Math.sqrt(3.0), r.relativeUncertainty(), 0.0);
            assertEquals(10.0, r.time(), 0.0);
            assertEquals(9, r.referencePositions().length);
        }
    }

    @Test
    void theGapIsTheLargestBodyDistanceFromTheReference() {
        try (ReferenceMonitor m = new ReferenceMonitor(LAGRANGE, LagrangeTriangle.state(0.0))) {
            double[] displayed = LagrangeTriangle.exactState(10.0).positions();
            displayed[3] += 0.01;  // body 1 is 0.01 off in x
            displayed[7] -= 0.004; // body 2 is 0.004 off in y
            ReferenceMonitor.Reading r = ask(m, 10.0, displayed);
            assertEquals(0.01, r.gap(), 1e-9);
        }
    }

    @Test
    void theReferenceUncertaintyGrowsByOrdersOfMagnitudeOnAChaoticOrbit() {
        State perturbed = LagrangeTriangle.state(0.01);
        try (ReferenceMonitor m = new ReferenceMonitor(LAGRANGE, perturbed)) {
            double[] any = perturbed.positions();
            double at20 = ask(m, 20.0, any).uncertainty();
            double at60 = ask(m, 60.0, any).uncertainty();
            assertTrue(at20 < 1e-9, "t=20 " + at20);                  // measured 3.4e-11
            assertTrue(at60 > 1e-8 && at60 < 1e-5, "t=60 " + at60);   // measured 1.9e-7
            assertTrue(at60 > 100.0 * at20, "growth " + at60 / at20);
        }
    }

    @Test
    void theLyapunovPartIsIndependentOfTheDisplayedRun() {
        try (ReferenceMonitor m = new ReferenceMonitor(LAGRANGE, LagrangeTriangle.state(0.0))) {
            double[] exact = LagrangeTriangle.exactState(50.0).positions();
            double[] garbage = exact.clone();
            garbage[0] += 1.0;
            ReferenceMonitor.Reading a = ask(m, 50.0, exact);
            assertEquals(0.537285, a.lyapunovSlope(), 1e-3, "slope " + a.lyapunovSlope()); // measured 0.53728
            assertTrue(a.renormalisations() >= 28, "renormalisations " + a.renormalisations());
            assertTrue(a.lyapunovElapsed() <= 50.0 && a.lyapunovElapsed() > 50.0 - a.lyapunovInterval());
            assertTrue(a.lyapunovFailure() == null);
            assertTrue(a.lyapunovEnergyDrift() < 1e-9);
            ReferenceMonitor.Reading b = ask(m, 50.5, garbage);
            assertTrue(b.gap() > 0.5, "the wrong displayed run shows a large gap " + b.gap());
            assertTrue(b.lyapunovSlope() > 0.5 && b.lyapunovSlope() < 0.6);
        }
    }

    // ---------------------------------------------------------------- determinism

    @Test
    void aReadingDoesNotDependOnWhichRequestsCameBefore() {
        State perturbed = LagrangeTriangle.state(0.01);
        double[] displayed = perturbed.positions();
        ReferenceMonitor.Reading paced;
        try (ReferenceMonitor m = new ReferenceMonitor(LAGRANGE, perturbed)) {
            for (double t = 3.0; t < 40.0; t += 3.7) {
                ask(m, t, displayed);
            }
            ask(m, 12.0, displayed); // going back is allowed
            paced = ask(m, 40.0, displayed);
        }
        ReferenceMonitor.Reading direct;
        try (ReferenceMonitor m = new ReferenceMonitor(LAGRANGE, perturbed)) {
            direct = ask(m, 40.0, displayed);
        }
        assertEquals(direct.gap(), paced.gap(), 0.0);
        assertEquals(direct.uncertainty(), paced.uncertainty(), 0.0);
        assertTrue(Arrays.equals(direct.referencePositions(), paced.referencePositions()));
        assertEquals(direct.renormalisations(), paced.renormalisations());
        assertEquals(direct.lyapunovSlope(), paced.lyapunovSlope(), 0.0);
        assertEquals(direct.lyapunovRunning(), paced.lyapunovRunning(), 0.0);
    }

    @Test
    void theNewestRequestWinsWhenSeveralArriveAtOnce() {
        try (ReferenceMonitor m = new ReferenceMonitor(LAGRANGE, LagrangeTriangle.state(0.0))) {
            double[] p = LagrangeTriangle.state(0.0).positions();
            for (int i = 1; i <= 200; i++) {
                m.submit(i * 0.1, p);
            }
            ReferenceMonitor.Reading last = await(m, 20.0);
            assertEquals(20.0, last.time(), 0.0);
        }
    }

    @Test
    void aReadingIsImmutable() {
        try (ReferenceMonitor m = new ReferenceMonitor(LAGRANGE, LagrangeTriangle.state(0.0))) {
            double[] displayed = LagrangeTriangle.exactState(5.0).positions();
            ReferenceMonitor.Reading r = ask(m, 5.0, displayed);
            double[] copy = r.referencePositions();
            copy[0] = 1e9;
            assertTrue(r.referencePositions()[0] < 10.0);
            displayed[0] = 1e9; // the caller's array is copied on submit
            assertTrue(m.latest().gap() < 1e-9);
        }
    }

    @Test
    void aCallerCannotChangeARequestThatIsStillWaiting() {
        State exact = LagrangeTriangle.state(0.0);
        try (ReferenceMonitor m = new ReferenceMonitor(LAGRANGE, exact)) {
            m.submit(300.0, exact.positions());      // keeps the thread busy for a while
            double[] mine = LagrangeTriangle.exactState(5.0).positions();
            m.submit(5.0, mine);                      // waits its turn
            Arrays.fill(mine, 1e9);                   // the caller reuses its array at once
            ReferenceMonitor.Reading r = await(m, 5.0);
            assertTrue(r.gap() < 1e-9, "gap " + r.gap());
        }
    }

    // ---------------------------------------------------------------- units

    @Test
    void worksInSiUnits() {
        ScenarioDraft.Scenario sunEarth = ScenarioDraft.sunEarth().validate(true).scenario();
        double year = 365.25 * 86400.0;
        ReferenceTrack alone = new ReferenceTrack(sunEarth.system(), sunEarth.initial(), 3600.0,
                ReferenceTrack.DEFAULT_TOLERANCES);
        double[] displayed = alone.positionsAt(year);
        try (ReferenceMonitor m = new ReferenceMonitor(sunEarth.system(), sunEarth.initial())) {
            ReferenceMonitor.Reading r = ask(m, year, displayed);
            assertTrue(r.referenceFailure() == null, r.referenceFailure());
            assertTrue(r.relativeGap() < 1e-9, "relative gap " + r.relativeGap());
            assertTrue(r.relativeUncertainty() < 1e-9, "relative uncertainty " + r.relativeUncertainty());
            assertEquals(1.495978707e11, r.length(), 1e5);
            assertTrue(r.renormalisations() >= 5, "renormalisations " + r.renormalisations());
            assertTrue(r.lyapunovFailure() == null, r.lyapunovFailure());
        }
    }

    // ---------------------------------------------------------------- failure

    @Test
    void aCollisionIsReportedAndEarlierTimesStillWork() {
        try (ReferenceMonitor m = new ReferenceMonitor(TWO_BODY, headOn())) {
            double[] p = headOn().positions();
            ReferenceMonitor.Reading before = ask(m, 0.5, p);
            assertTrue(before.referenceFailure() == null);
            ReferenceMonitor.Reading after = ask(m, 2.0, p); // beyond pi/4, and past the tracker's second interval
            assertTrue(after.referenceFailure() != null);
            assertTrue(after.referenceFailure().contains("ends at"), after.referenceFailure());
            assertTrue(after.referencePositions() == null);
            assertTrue(Double.isNaN(after.gap()));
            assertTrue(Double.isNaN(after.relativeGap()));
            assertTrue(after.lyapunovFailure() != null);
            ReferenceMonitor.Reading earlier = ask(m, 0.7, p); // the thread is alive and the past is still there
            assertTrue(earlier.referenceFailure() == null);
            assertFalse(Double.isNaN(earlier.gap()));
            assertTrue(m.isRunning());
        }
    }

    // ---------------------------------------------------------------- reset and close

    @Test
    void resetStartsAgainAndNoOldReadingSurvives() {
        State perturbed = LagrangeTriangle.state(0.01);
        double[] p = perturbed.positions();
        try (ReferenceMonitor m = new ReferenceMonitor(LAGRANGE, perturbed)) {
            ReferenceMonitor.Reading far = ask(m, 40.0, p);
            assertTrue(far.renormalisations() >= 20);
            m.reset();
            assertTrue(m.latest() == null);
            ReferenceMonitor.Reading near = ask(m, 5.0, p);
            assertTrue(near.renormalisations() <= 3, "renormalisations " + near.renormalisations());
            // identical to a monitor that never saw t = 40
            try (ReferenceMonitor fresh = new ReferenceMonitor(LAGRANGE, perturbed)) {
                ReferenceMonitor.Reading same = ask(fresh, 5.0, p);
                assertEquals(same.gap(), near.gap(), 0.0);
                assertEquals(same.uncertainty(), near.uncertainty(), 0.0);
                assertEquals(same.renormalisations(), near.renormalisations());
            }
        }
    }

    @Test
    void aResetWhileAReadingIsBeingComputedNeverPublishesTheOldResult() throws InterruptedException {
        State perturbed = LagrangeTriangle.state(0.01);
        try (ReferenceMonitor m = new ReferenceMonitor(LAGRANGE, perturbed)) {
            m.submit(300.0, perturbed.positions()); // takes about half a second
            Thread.sleep(50);
            m.reset();
            Thread.sleep(1500);                      // long enough for the abandoned computation to finish
            assertTrue(m.latest() == null, "a reading from before the reset was published");
        }
    }

    @Test
    void closeStopsTheThreadAndLaterRequestsDoNothing() {
        ReferenceMonitor m = new ReferenceMonitor(LAGRANGE, LagrangeTriangle.state(0.0));
        assertTrue(m.isRunning());
        m.close();
        assertFalse(m.isRunning());
        m.submit(1.0, LagrangeTriangle.state(0.0).positions()); // ignored, no exception
        m.close();                                               // idempotent
        assertTrue(m.latest() == null);
    }

    // ---------------------------------------------------------------- arguments

    @Test
    void invalidArgumentsAreRejected() {
        State s = LagrangeTriangle.state(0.0);
        assertThrows(IllegalArgumentException.class, () -> new ReferenceMonitor(null, s));
        assertThrows(IllegalArgumentException.class, () -> new ReferenceMonitor(LAGRANGE, null));
        assertThrows(IllegalArgumentException.class, () -> new ReferenceMonitor(TWO_BODY, s)); // 3 bodies vs 2
        State coincident = State.of(0, new double[] {1, 1, 1, 1, 1, 1}, new double[6]);
        assertThrows(NBodySystem.SingularConfigurationException.class,
                () -> new ReferenceMonitor(TWO_BODY, coincident));
        try (ReferenceMonitor m = new ReferenceMonitor(LAGRANGE, s)) {
            double[] p = s.positions();
            assertThrows(IllegalArgumentException.class, () -> m.submit(Double.NaN, p));
            assertThrows(IllegalArgumentException.class, () -> m.submit(-1.0, p));
            assertThrows(IllegalArgumentException.class, () -> m.submit(1.0, new double[6]));
            assertThrows(IllegalArgumentException.class, () -> m.submit(1.0, null));
            double[] bad = p.clone();
            bad[2] = Double.NaN;
            assertThrows(IllegalArgumentException.class, () -> m.submit(1.0, bad));
        }
    }
}
