package dev.chaoslab.ui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.chaoslab.analysis.IntegratorComparison.MethodResult;
import dev.chaoslab.analysis.IntegratorComparison.Report;
import dev.chaoslab.analysis.IntegratorComparison.Sample;
import dev.chaoslab.ui.ComparisonSeries.Series;
import java.util.List;
import org.junit.jupiter.api.Test;

class ComparisonSeriesTest {

    private static MethodResult method(String label, Sample... samples) {
        return new MethodResult(label, label, 0.1, 10, List.of(samples), null);
    }

    private static Report report(MethodResult... results) {
        return new Report(0.0, 4.0, 2, List.of(), 0.0, List.of(results));
    }

    @Test
    void seriesHoldScaledTimesAbsoluteEnergyAndSeparation() {
        Report r = report(
                method("A", new Sample(0.0, 0.0, 0.0, 0.0), new Sample(86_400.0, -2e-6, 0.0, 3.0),
                        new Sample(172_800.0, 5e-6, 0.0, 4.0)),
                method("B", new Sample(0.0, 0.0, 0.0, 0.0), new Sample(86_400.0, 1e-3, 0.0, 0.5)));
        List<Series> s = ComparisonSeries.of(r, 86_400.0);
        assertEquals(2, s.size());
        assertEquals("A", s.get(0).label());
        assertArrayEquals(new double[] {0.0, 1.0, 2.0}, s.get(0).times(), 1e-12);
        assertArrayEquals(new double[] {0.0, 2e-6, 5e-6}, s.get(0).energy(), 0.0);
        assertArrayEquals(new double[] {0.0, 3.0, 4.0}, s.get(0).separation(), 0.0);
        assertEquals(2, s.get(1).times().length); // a method that stopped early has fewer samples
    }

    @Test
    void decadesContainTheDataAndIgnoreZeroNanAndInfinity() {
        List<Series> s = List.of(new Series("A", new double[] {0, 1, 2, 3},
                new double[] {0.0, 3e-9, Double.NaN, 0.5}, new double[] {0.0, 2e3, Double.POSITIVE_INFINITY, 8e3}));
        assertArrayEquals(new int[] {-9, 0}, ComparisonSeries.decades(s, true));
        assertArrayEquals(new int[] {3, 4}, ComparisonSeries.decades(s, false));
    }

    @Test
    void decadesAreAtLeastOneTallNeverBelowTheFloorAndDefaultWhenThereIsNoData() {
        List<Series> same = List.of(new Series("A", new double[] {0, 1}, new double[] {1e-5, 1e-5}, new double[2]));
        assertArrayEquals(new int[] {-5, -4}, ComparisonSeries.decades(same, true));
        List<Series> tiny = List.of(new Series("A", new double[] {0, 1}, new double[] {1e-30, 1.0}, new double[2]));
        assertArrayEquals(new int[] {-17, 0}, ComparisonSeries.decades(tiny, true));
        assertArrayEquals(new int[] {-16, 0}, ComparisonSeries.decades(tiny, false)); // all zero
        assertArrayEquals(new int[] {-16, 0}, ComparisonSeries.decades(List.of(), true));
    }

    @Test
    void timeRangeSpansAllSeriesAndDefaultsToZeroOne() {
        List<Series> s = List.of(new Series("A", new double[] {0, 2}, new double[2], new double[2]),
                new Series("B", new double[] {0, 1, 5}, new double[3], new double[3]));
        assertArrayEquals(new double[] {0.0, 5.0}, ComparisonSeries.timeRange(s), 0.0);
        assertArrayEquals(new double[] {0.0, 1.0}, ComparisonSeries.timeRange(List.of()), 0.0);
        List<Series> single = List.of(new Series("A", new double[] {3}, new double[1], new double[1]));
        assertArrayEquals(new double[] {0.0, 1.0}, ComparisonSeries.timeRange(single), 0.0);
    }
}
