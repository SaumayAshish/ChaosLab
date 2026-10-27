package dev.chaoslab.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.chaoslab.analysis.ComparisonPlan.Plan;
import dev.chaoslab.analysis.IntegratorComparison.MethodResult;
import dev.chaoslab.analysis.IntegratorComparison.MethodSpec;
import dev.chaoslab.analysis.IntegratorComparison.Report;
import dev.chaoslab.analysis.IntegratorComparison.Sample;
import dev.chaoslab.experiments.LongTermOrbit;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Tests for {@link ComparisonPlan}. The run test uses a real ten-orbit comparison; the summaries use made-up reports. */
class ComparisonPlanTest {

    private static MethodResult result(String label, double maxDrift, double separation, String failure) {
        Sample first = new Sample(0.0, 0.0, 0.0, 0.0);
        Sample last = new Sample(10.0, maxDrift, 0.0, separation);
        return new MethodResult(label, label, 0.01, 100, List.of(first, last), failure);
    }

    private static Report report(double spread, MethodResult... results) {
        return new Report(0.0, 10.0, 1, List.of(), spread, List.of(results));
    }

    @Test
    void planKeepsTheRequestedStepWhenItDividesTheDuration() {
        Plan p = ComparisonPlan.plan(0.02, 628.0);
        assertEquals(ComparisonPlan.SAMPLES, p.samples());
        assertEquals(1570, p.stepsPerSample());
        assertEquals(31_400L, p.stepsPerMethod());
        assertEquals(0.02, p.stepSize(), 1e-15);
        assertEquals(628.0, p.stepSize() * p.stepsPerMethod(), 1e-9);
    }

    @Test
    void planRoundsTheStepSoTheDurationIsCoveredExactly() {
        Plan p = ComparisonPlan.plan(0.03, 1.0);
        assertEquals(20, p.samples());
        assertEquals(2, p.stepsPerSample()); // 1 / (20 * 0.03) = 1.67, rounded to 2
        assertEquals(1.0, p.stepSize() * p.stepsPerMethod(), 1e-12);
        assertEquals(0.025, p.stepSize(), 1e-12);
    }

    @Test
    void aDurationOfOnlyAFewStepsUsesFewerSamples() {
        Plan p = ComparisonPlan.plan(1.0, 5.0);
        assertEquals(5, p.samples());
        assertEquals(1, p.stepsPerSample());
        assertEquals(1.0, p.stepSize(), 0.0);
    }

    @Test
    void planRefusesBadInputWithASentence() {
        assertThrows(IllegalArgumentException.class, () -> ComparisonPlan.plan(0.0, 10.0));
        assertThrows(IllegalArgumentException.class, () -> ComparisonPlan.plan(-1.0, 10.0));
        assertThrows(IllegalArgumentException.class, () -> ComparisonPlan.plan(0.01, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> ComparisonPlan.plan(0.01, 0.0));
        IllegalArgumentException tooShort =
                assertThrows(IllegalArgumentException.class, () -> ComparisonPlan.plan(1.0, 1.5));
        assertTrue(tooShort.getMessage().contains("two steps"));
    }

    @Test
    void planRefusesMoreThanTheStepLimitAndSaysHowLongIsAllowed() {
        assertEquals(1_000_000L, ComparisonPlan.plan(1.0, 1_000_000.0).stepsPerMethod());
        IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, () -> ComparisonPlan.plan(1.0, 1_000_001.0));
        assertTrue(e.getMessage().contains("1000000"), e.getMessage());
        assertTrue(e.getMessage().contains("1e+06") || e.getMessage().contains("1.00e+06"), e.getMessage());
    }

    @Test
    void specsAreFourFixedMethodsAtTheSameStepsPerSampleThenTheAdaptiveOne() {
        List<MethodSpec> specs = ComparisonPlan.specs(ComparisonPlan.plan(0.02, 628.0));
        assertEquals(List.of("Euler", "RK4", "Velocity Verlet", "Dormand-Prince, fixed h",
                "Dormand-Prince, adaptive"), specs.stream().map(MethodSpec::label).toList());
        for (int i = 0; i < 4; i++) {
            assertEquals(1570, specs.get(i).stepsPerSample());
            assertEquals(null, specs.get(i).tolerances());
        }
        assertEquals(1e-10, specs.get(4).tolerances().relative(), 0.0);
        assertEquals(1e-10, specs.get(4).tolerances().absolute(), 0.0);
    }

    @Test
    void parseDurationConvertsDisplayUnitsAndRejectsBadText() {
        assertEquals(86_400.0 * 365.0, ComparisonPlan.parseDuration(" 365 ", 86_400.0), 1e-6);
        assertEquals(2.5e3, ComparisonPlan.parseDuration("2.5e3", 1.0), 0.0);
        for (String bad : new String[] {"", "   ", "abc", "-3", "0", "NaN", "Infinity"}) {
            assertThrows(IllegalArgumentException.class, () -> ComparisonPlan.parseDuration(bad, 1.0), bad);
        }
        assertThrows(IllegalArgumentException.class, () -> ComparisonPlan.parseDuration(null, 1.0));
    }

    @Test
    void describeDurationShowsSecondsOnlyWhenTheDisplayUnitIsNotTheSimulationUnit() {
        assertEquals("100 t", ComparisonPlan.describeDuration(100.0, 1.0, "t"));
        assertEquals("365 d (3.15e+07 s)", ComparisonPlan.describeDuration(365.0 * 86_400.0, 86_400.0, "d"));
    }

    @Test
    void aCancelledRunStopsWithACancellationException() {
        int[] polls = {0};
        assertThrows(java.util.concurrent.CancellationException.class, () -> ComparisonPlan.run(
                LongTermOrbit.system(), LongTermOrbit.state(), LongTermOrbit.STEP, 10.0 * LongTermOrbit.PERIOD,
                () -> ++polls[0] > 3));
        assertEquals(4, polls[0]);
    }

    @Test
    void realTenOrbitRunRanksEulerWorstAndDormandPrinceBestForEnergy() {
        Report r = ComparisonPlan.run(LongTermOrbit.system(), LongTermOrbit.state(), LongTermOrbit.STEP,
                10.0 * LongTermOrbit.PERIOD);
        assertEquals(5, r.results().size());
        assertEquals(10.0 * LongTermOrbit.PERIOD, r.endTime() - r.startTime(), 1e-9);
        double euler = r.results().get(0).maxAbsEnergyDrift();
        double rk4 = r.results().get(1).maxAbsEnergyDrift();
        double dp = r.results().get(3).maxAbsEnergyDrift();
        assertTrue(euler > 0.5 && dp < rk4 && rk4 < 1e-4, euler + " " + rk4 + " " + dp);
        List<String> lines = ComparisonPlan.summary(r);
        // the adaptive row reaches 6.5e-09 with half the steps of the fixed one (1.8e-08)
        assertTrue(lines.get(0).startsWith("Smallest energy drift: Dormand-Prince, adaptive"), lines.get(0));
        assertTrue(r.results().get(4).stepsCompleted() < r.results().get(3).stepsCompleted());
        // Euler gained about its whole energy, so it is named as invalid and left out of the ranking
        assertTrue(lines.get(0).contains("largest: Velocity Verlet"), lines.get(0));
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("Euler changed the total energy by more than 10%")),
                lines.toString());
    }

    @Test
    void summaryWarnsWhenEnergyAndPositionRankingsDisagree() {
        Report r = report(1e-9,
                result("A", 1e-6, 5e-1, null), // best energy, but far from the reference
                result("B", 1e-3, 1e-4, null)); // worst energy, closest position
        List<String> lines = ComparisonPlan.summary(r);
        assertTrue(lines.get(0).startsWith("Smallest energy drift: A") && lines.get(0).contains("largest: B"));
        assertTrue(lines.get(1).startsWith("Closest to the reference at the end: B"));
        assertTrue(lines.get(2).contains("not the one whose position is closest"), lines.get(2));
        assertEquals(3, lines.size());
    }

    @Test
    void summaryHasNoDisagreementLineWhenOneMethodWinsBoth() {
        Report r = report(1e-9, result("A", 1e-6, 1e-4, null), result("B", 1e-3, 5e-1, null));
        assertEquals(2, ComparisonPlan.summary(r).size());
    }

    @Test
    void summaryReportsFailuresAndRanksOnlyTheCompletedMethods() {
        Report r = report(1e-9, result("A", 1e-6, 1e-4, null), result("B", Double.NaN, Double.NaN, "step blew up"));
        List<String> lines = ComparisonPlan.summary(r);
        assertTrue(lines.get(0).startsWith("B failed after t = 10.0: step blew up"), lines.get(0));
        assertTrue(lines.get(1).startsWith("A: max |dE/E|"), lines.get(1));
    }

    @Test
    void summaryWithNoCompletedMethodSaysSo() {
        Report r = report(1e-9, result("A", 1.0, 1.0, "boom"));
        List<String> lines = ComparisonPlan.summary(r);
        assertEquals("No method completed, so there is nothing to rank.", lines.get(lines.size() - 1));
    }

    @Test
    void summaryWarnsWhenTheReferenceIsNotMuchTighterThanTheBestSeparation() {
        List<String> noisy = ComparisonPlan.summary(report(1e-2, result("A", 1e-6, 5e-2, null)));
        assertTrue(noisy.get(noisy.size() - 1).contains("reference itself is uncertain"), noisy.toString());
        List<String> clean = ComparisonPlan.summary(report(1e-6, result("A", 1e-6, 5e-2, null)));
        assertTrue(clean.stream().noneMatch(l -> l.contains("uncertain")), clean.toString());
    }

    @Test
    void methodsWithinTheReferenceSpreadAreTiedAndNoDisagreementIsClaimed() {
        // the Sun-Earth case: energy-best and position-best differ by 0.009 m while the reference spread is 89 m
        Report r = report(89.0, result("RK4", 1.65e-14, 1.375, null), result("DP", 3.2e-14, 1.366, null));
        List<String> lines = ComparisonPlan.summary(r);
        assertTrue(lines.stream().noneMatch(l -> l.contains("not the one whose position")), lines.toString());
        assertTrue(lines.get(lines.size() - 1).contains("cannot be told apart from the reference's own uncertainty"),
                lines.toString());
    }

    @Test
    void ifNoMethodKeepsItsEnergyNothingIsRankedAndTheAdaptiveMethodIsSuggested() {
        // the Pythagorean case: energy changed by factors of 39 to 419
        Report r = report(1e-9, result("A", 72.0, 900.0, null), result("B", 419.0, 2000.0, null),
                result("C", 39.0, 690.0, null));
        List<String> lines = ComparisonPlan.summary(r);
        assertTrue(lines.stream().noneMatch(l -> l.startsWith("Smallest energy drift")), lines.toString());
        assertEquals(1, lines.stream().filter(l -> l.startsWith("A changed the total energy by more than 10%")).count());
        assertEquals(1, lines.stream().filter(l -> l.startsWith("B changed")).count());
        assertEquals(1, lines.stream().filter(l -> l.startsWith("C changed")).count());
        assertTrue(lines.get(lines.size() - 1).startsWith("No method kept the energy within 10%"), lines.toString());
        assertTrue(lines.get(lines.size() - 1).contains("adaptive Dormand-Prince"));
    }

    @Test
    void aValidMethodIsRankedAndAnInvalidOneIsNamedAfterIt() {
        Report r = report(1e-9, result("A", 1e-6, 1e-4, null), result("B", 0.5, 3.0, null),
                result("C", 1e-3, 1e-2, null));
        List<String> lines = ComparisonPlan.summary(r);
        assertTrue(lines.get(0).contains("largest: C"), lines.get(0));
        assertTrue(lines.get(2).startsWith("B changed the total energy by more than 10%"), lines.toString());
    }

    @Test
    void aReferenceThatDisagreesWithItselfByATenthOfTheSystemSizeIsReportedAsNotConverged() {
        dev.chaoslab.physics.State start = dev.chaoslab.physics.State.of(0.0,
                new double[] {1.0, 3.0, 0.0, -2.0, -1.0, 0.0}, new double[6]);
        Report bad = new Report(0.0, 60.0, 1, List.of(start), 3.465, List.of(result("A", 1e-6, 1e-4, null)));
        assertTrue(ComparisonPlan.summary(bad).get(ComparisonPlan.summary(bad).size() - 1)
                .startsWith("The reference itself has not converged"), ComparisonPlan.summary(bad).toString());
        Report fine = new Report(0.0, 60.0, 1, List.of(start), 1e-6, List.of(result("A", 1e-6, 1e-4, null)));
        assertTrue(ComparisonPlan.summary(fine).stream().noneMatch(l -> l.contains("reference itself")));
    }

    @Test
    void wrapLinesBreaksLongLinesAtSpacesAndIndentsBulletContinuations() {
        String text = "short\n  - " + "word ".repeat(12).trim() + "\nlast";
        String wrapped = ComparisonPlan.wrapLines(text, 30);
        for (String line : wrapped.split("\n")) {
            assertTrue(line.length() <= 30, "'" + line + "'");
        }
        String[] lines = wrapped.split("\n");
        assertEquals("short", lines[0]);
        assertTrue(lines[1].startsWith("  - word"), lines[1]);
        assertTrue(lines[2].startsWith("    word"), lines[2]); // bullet text is indented under the bullet
        assertEquals("last", lines[lines.length - 1]);
        assertEquals(text.replace("\n", "\n"), ComparisonPlan.wrapLines(text, 1000));
    }

    @Test
    void wrapLinesKeepsTrailingNewlineAndBreaksAWordLongerThanTheWidth() {
        assertEquals("abc\n", ComparisonPlan.wrapLines("abc\n", 10));
        String wrapped = ComparisonPlan.wrapLines("x".repeat(25), 10);
        for (String line : wrapped.split("\n")) {
            assertTrue(line.length() <= 10, line);
        }
        assertEquals("x".repeat(25), wrapped.replace("\n", ""));
    }

    @Test
    void wrapLinesLeavesALineOfExactlyTheWidthAloneAndBreaksAnIndentedLongWord() {
        String exact = "aaaa bbbb cccc dddd"; // 19 characters
        assertEquals(exact, ComparisonPlan.wrapLines(exact, 19));
        String indented = "  " + "x".repeat(25);
        String wrapped = ComparisonPlan.wrapLines(indented, 10);
        for (String line : wrapped.split("\n")) {
            assertTrue(line.length() <= 10, "'" + line + "'");
        }
        assertEquals("x".repeat(25), wrapped.replace("\n", "").replace(" ", ""));
    }
}
