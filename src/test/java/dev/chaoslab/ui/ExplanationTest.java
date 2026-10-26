package dev.chaoslab.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.chaoslab.ui.Explanation.Facts;
import dev.chaoslab.ui.Explanation.Finding;
import dev.chaoslab.ui.Explanation.Level;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class ExplanationTest {

    /** Mutable stand-in for Facts, so each test changes only what it is about. */
    private static final class M {
        boolean failed;
        String failure = "";
        boolean adaptive = true;
        String unit = "t";
        double energy = 1e-12;
        double momentum = 1e-15;
        double angular = 1e-15;
        double softening;
        double gap = Double.NaN;
        double uncertainty = Double.NaN;
        String referenceFailure;
        ExponentRule.Verdict verdict = ExponentRule.Verdict.NO_DATA;
        double lambda = Double.NaN;
        int renorms;
        double twin = Double.NaN;

        Facts build() {
            return new Facts(failed, failure, adaptive, unit, energy, momentum, angular, softening, gap, uncertainty,
                    referenceFailure, verdict, lambda, renorms, twin);
        }
    }

    private static List<Finding> run(Consumer<M> change) {
        M m = new M();
        change.accept(m);
        return Explanation.explain(m.build());
    }

    private static Finding find(List<Finding> list, String heading) {
        for (Finding f : list) {
            if (f.heading().equals(heading)) {
                return f;
            }
        }
        return null;
    }

    private static String text(List<Finding> list, String heading) {
        Finding f = find(list, heading);
        assertTrue(f != null, "no finding \"" + heading + "\" in " + list);
        return f.text();
    }

    private static Level level(List<Finding> list, String heading) {
        Finding f = find(list, heading);
        assertTrue(f != null, "no finding \"" + heading + "\" in " + list);
        return f.level();
    }

    // ---------------------------------------------------------------- run and conservation

    @Test
    void aCalmStartGivesOnlyConservationWaitingAndCollecting() {
        List<Finding> list = run(m -> { });
        assertEquals(List.of("Conservation", "Accuracy", "Sensitivity"),
                list.stream().map(Finding::heading).toList());
        assertEquals(Level.OK, level(list, "Conservation"));
        assertEquals(Level.INFO, level(list, "Accuracy"));
        assertTrue(text(list, "Accuracy").contains("Waiting for the first comparison"));
        assertTrue(text(list, "Sensitivity").contains("Collecting data"));
    }

    @Test
    void aFailedRunComesFirstWithItsReason() {
        List<Finding> list = run(m -> {
            m.failed = true;
            m.failure = "Two bodies coincided at t = 0.78.";
        });
        assertEquals("The run stopped", list.get(0).heading());
        assertEquals(Level.WARNING, list.get(0).level());
        assertTrue(list.get(0).text().startsWith("Two bodies coincided at t = 0.78."));
        assertEquals("Conservation", list.get(1).heading());
    }

    @Test
    void conservationBandsHaveExactBoundaries() {
        assertEquals(Level.OK, level(run(m -> m.energy = 1e-6), "Conservation"));
        assertEquals(Level.INFO, level(run(m -> m.energy = 1.0001e-6), "Conservation"));
        assertEquals(Level.INFO, level(run(m -> m.energy = 1e-3), "Conservation"));
        assertEquals(Level.WARNING, level(run(m -> m.energy = 1.0001e-3), "Conservation"));
    }

    @Test
    void theWorstOfTheThreeDecidesAndIsNamed() {
        assertTrue(text(run(m -> m.energy = 1e-4), "Conservation").contains("in energy"));
        assertTrue(text(run(m -> m.momentum = 1e-4), "Conservation").contains("in momentum"));
        assertTrue(text(run(m -> m.angular = 1e-4), "Conservation").contains("in angular momentum"));
        assertEquals(Level.WARNING, level(run(m -> m.angular = 0.75), "Conservation"));
        assertEquals(Level.WARNING, level(run(m -> m.momentum = 0.5), "Conservation"));
    }

    @Test
    void conservedTextCountsDigitsAndShowsAllThreeNumbers() {
        String t = text(run(m -> {
            m.energy = 1e-9;
            m.momentum = 2e-12;
            m.angular = 3e-13;
        }), "Conservation");
        assertTrue(t.contains("conserved to 9 digits"), t);
        assertTrue(t.contains("energy 1.0e-09"), t);
        assertTrue(t.contains("momentum 2.0e-12"), t);
        assertTrue(t.contains("angular momentum 3.0e-13"), t);
        String exact = text(run(m -> {
            m.energy = 0;
            m.momentum = 0;
            m.angular = 0;
        }), "Conservation");
        assertTrue(exact.contains("conserved to rounding error"), exact);
    }

    @Test
    void theFixedStepHintAppearsOnlyForFixedSteps() {
        assertTrue(text(run(m -> {
            m.energy = 0.3;
            m.adaptive = false;
        }), "Conservation").contains("adaptive Dormand-Prince"));
        assertFalse(text(run(m -> {
            m.energy = 0.3;
            m.adaptive = true;
        }), "Conservation").contains("adaptive"));
    }

    // ---------------------------------------------------------------- accuracy

    @Test
    void anEndedReferenceSaysSoAndHidesTheGap() {
        List<Finding> list = run(m -> {
            m.gap = 0.5;
            m.referenceFailure = "the bodies collided";
        });
        assertTrue(text(list, "Accuracy").contains("no reference at this time (the bodies collided)"));
        assertEquals(Level.INFO, level(list, "Accuracy"));
    }

    @Test
    void anIdenticalStartIsSaidToBeIdentical() {
        assertTrue(text(run(m -> m.gap = 0.0), "Accuracy").contains("identical to the reference"));
        assertEquals(Level.OK, level(run(m -> m.gap = 0.0), "Accuracy"));
    }

    @Test
    void aGapAtOrBelowTheUncertaintyCannotBeResolved() {
        List<Finding> at = run(m -> {
            m.gap = 2e-9;
            m.uncertainty = 2e-9;
        });
        assertTrue(text(at, "Accuracy").contains("cannot be resolved"));
        assertTrue(text(at, "Accuracy").contains("2.0e-09"));
        List<Finding> above = run(m -> {
            m.gap = 2.1e-9;
            m.uncertainty = 2e-9;
        });
        assertTrue(text(above, "Accuracy").contains("agree with the reference"), text(above, "Accuracy"));
    }

    @Test
    void aLargeGapMeansTheRunLeftTheOrbit() {
        List<Finding> list = run(m -> {
            m.gap = 0.1;
            m.adaptive = false;
        });
        assertEquals(Level.WARNING, level(list, "Accuracy"));
        assertTrue(text(list, "Accuracy").contains("0.10 of the system size"));
        assertTrue(text(list, "Accuracy").contains("left the true orbit"));
        assertTrue(text(list, "Accuracy").contains("adaptive integrator"));
        assertFalse(text(run(m -> {
            m.gap = 0.1;
            m.adaptive = true;
        }), "Accuracy").contains("adaptive integrator"));
        assertEquals(Level.INFO, level(run(m -> m.gap = 0.0999), "Accuracy"));
    }

    @Test
    void agreementIsOkOnlyBelowTheNotableGap() {
        assertEquals(Level.OK, level(run(m -> m.gap = 0.9999e-3), "Accuracy"));
        assertEquals(Level.INFO, level(run(m -> m.gap = 1e-3), "Accuracy"));
    }

    @Test
    void agreementIsCountedInDigits() {
        assertTrue(text(run(m -> m.gap = 3e-4), "Accuracy").contains("about 3 digits (gap 3.0e-04"));
        assertTrue(text(run(m -> m.gap = 0.05), "Accuracy").contains("about 1 digit (gap"));
        assertTrue(text(run(m -> m.gap = 1.5e-10), "Accuracy").contains("about 9 digits"));
    }

    // ---------------------------------------------------------------- conserved is not accurate

    @Test
    void conservedButWrongIsCalledOutWithBothNumbers() {
        List<Finding> list = run(m -> {
            m.energy = 1e-9;
            m.gap = 4e-3;
        });
        String t = text(list, "Conserved is not accurate");
        assertTrue(t.contains("only 1.0e-09"), t);
        assertTrue(t.contains("4.0e-03 of the system size"), t);
    }

    @Test
    void conservedButWrongHasExactThresholds() {
        assertTrue(find(run(m -> {
            m.energy = 1e-6;
            m.gap = 1e-3 + 1e-9;
            m.momentum = 0;
            m.angular = 0;
        }), "Conserved is not accurate") != null, "at the drift limit");
        assertTrue(find(run(m -> {
            m.energy = 1.01e-6;
            m.gap = 0.05;
        }), "Conserved is not accurate") == null, "drift above conserved");
        assertTrue(find(run(m -> {
            m.energy = 1e-9;
            m.gap = 0.999e-3;
        }), "Conserved is not accurate") == null, "gap below notable");
        assertTrue(find(run(m -> {
            m.energy = 1e-6;
            m.gap = 1e-3;
        }), "Conserved is not accurate") != null, "gap exactly at the notable limit");
    }

    @Test
    void conservedButWrongIsNotSaidWithoutAReference() {
        assertTrue(find(run(m -> {
            m.energy = 1e-9;
            m.gap = Double.NaN;
        }), "Conserved is not accurate") == null);
        assertTrue(find(run(m -> {
            m.energy = 1e-9;
            m.gap = 0.01;
            m.referenceFailure = "ended";
        }), "Conserved is not accurate") == null);
    }

    // ---------------------------------------------------------------- sensitivity

    @Test
    void theThreeEarlyVerdictsDoNotClaimAnExponent() {
        String prov = text(run(m -> {
            m.verdict = ExponentRule.Verdict.PROVISIONAL;
            m.renorms = 4;
        }), "Sensitivity");
        assertTrue(prov.contains("4 of 10 renormalisations"), prov);
        String slow = text(run(m -> m.verdict = ExponentRule.Verdict.SLOWING), "Sensitivity");
        assertTrue(slow.contains("No exponent is claimed"), slow);
        assertTrue(slow.contains("not a proof of regularity"), slow);
    }

    @Test
    void aClaimedExponentGivesTheEFoldingTimeAndTheHorizon() {
        String t = text(run(m -> {
            m.verdict = ExponentRule.Verdict.CLAIMED;
            m.lambda = 0.5;
        }), "Sensitivity");
        assertTrue(t.contains("exponent of 0.5 per t"), t);
        assertTrue(t.contains("e-folding time 2 t"), t);
        // ln(1e13) / 0.5 = 59.9
        assertTrue(t.contains("after about 59.9 t"), t);
        assertTrue(t.contains("If that growth stayed exponential"), t);
    }

    @Test
    void theUnitIsTheDisplayedOne() {
        String t = text(run(m -> {
            m.verdict = ExponentRule.Verdict.CLAIMED;
            m.lambda = 0.0375;
            m.unit = "d";
        }), "Sensitivity");
        assertTrue(t.contains("0.0375 per d"), t);
        assertTrue(t.contains("e-folding time 26.7 d"), t);
        assertTrue(t.contains("after about 798 d"), t);
    }

    @Test
    void aZeroClaimedExponentGivesNoTimeEither() {
        String t = text(run(m -> {
            m.verdict = ExponentRule.Verdict.CLAIMED;
            m.lambda = 0.0;
        }), "Sensitivity");
        assertTrue(t.contains("not positive"), t);
    }

    @Test
    void aNonPositiveClaimedExponentGivesNoTime() {
        String t = text(run(m -> {
            m.verdict = ExponentRule.Verdict.CLAIMED;
            m.lambda = -0.01;
        }), "Sensitivity");
        assertTrue(t.contains("not positive"), t);
        assertFalse(t.contains("e-folding"), t);
    }

    // ---------------------------------------------------------------- twin run

    @Test
    void noTwinNoStatement() {
        assertTrue(find(run(m -> { }), "Twin run") == null);
    }

    @Test
    void theTwinSeparationIsGivenWithItsGrowthFactor() {
        String t = text(run(m -> m.twin = 3.94e-3), "Twin run");
        assertTrue(t.contains("Moving m1 by 1e-06 of the system size"), t);
        assertTrue(t.contains("3.9e-03 of the system size now (x3.94e+03 since the start)"), t);
    }

    @Test
    void theNumericalErrorIsComparedWithThePhysicalChange() {
        String smaller = text(run(m -> {
            m.twin = 1e-3;
            m.gap = 1e-5;
        }), "Twin run");
        assertTrue(smaller.contains("(1.0e-05) is smaller than that physical change"), smaller);
        String larger = text(run(m -> {
            m.twin = 1e-5;
            m.gap = 1e-3;
        }), "Twin run");
        assertTrue(larger.contains("(1.0e-03) is larger than that physical change"), larger);
        String equal = text(run(m -> {
            m.twin = 1e-4;
            m.gap = 1e-4;
        }), "Twin run");
        assertFalse(equal.contains("smaller") || equal.contains("larger"), equal);
        String noGap = text(run(m -> m.twin = 1e-4), "Twin run");
        assertFalse(noGap.contains("integrator's own error"), noGap);
        String ended = text(run(m -> {
            m.twin = 1e-4;
            m.gap = 1e-2;
            m.referenceFailure = "ended";
        }), "Twin run");
        assertFalse(ended.contains("integrator's own error"), ended);
    }

    @Test
    void fullySeparatedTwinsAreAWarning() {
        List<Finding> list = run(m -> m.twin = 0.1);
        assertEquals(Level.WARNING, level(list, "Twin run"));
        assertTrue(text(list, "Twin run").contains("fully apart"));
        assertEquals(Level.INFO, level(run(m -> m.twin = 0.0999), "Twin run"));
    }

    @Test
    void aTwinBelowTheReferenceUncertaintyIsNotResolved() {
        String t = text(run(m -> {
            m.twin = 1e-9;
            m.uncertainty = 1e-9;
        }), "Twin run");
        assertTrue(t.contains("not resolved"), t);
        assertFalse(text(run(m -> {
            m.twin = 1.1e-9;
            m.uncertainty = 1e-9;
        }), "Twin run").contains("not resolved"));
    }

    // ---------------------------------------------------------------- reference limit and softening

    @Test
    void anUncertainReferenceIsWarnedAbout() {
        assertTrue(find(run(m -> m.uncertainty = 1e-3), "Reference limit") == null);
        List<Finding> list = run(m -> m.uncertainty = 2e-3);
        assertEquals(Level.WARNING, level(list, "Reference limit"));
        assertTrue(text(list, "Reference limit").contains("2.0e-03"));
        assertTrue(find(run(m -> {
            m.uncertainty = 0.5;
            m.referenceFailure = "ended";
        }), "Reference limit") == null);
    }

    @Test
    void softeningIsLabelledNonNewtonian() {
        assertTrue(find(run(m -> m.softening = 0.0), "Softened gravity") == null);
        String t = text(run(m -> m.softening = 0.05), "Softened gravity");
        assertTrue(t.contains("Softening 0.05 is on"), t);
        assertTrue(t.contains("not Newtonian"), t);
    }

    @Test
    void everyStatementHasAHeadingAndText() {
        List<Finding> list = run(m -> {
            m.failed = true;
            m.failure = "x";
            m.energy = 0.2;
            m.gap = 0.3;
            m.uncertainty = 0.01;
            m.verdict = ExponentRule.Verdict.CLAIMED;
            m.lambda = 0.2;
            m.twin = 0.2;
            m.softening = 0.1;
        });
        assertTrue(list.size() >= 6, "size " + list.size());
        for (Finding f : list) {
            assertFalse(f.heading().isBlank());
            assertFalse(f.text().isBlank());
            assertTrue(f.level() != null);
        }
    }
}
