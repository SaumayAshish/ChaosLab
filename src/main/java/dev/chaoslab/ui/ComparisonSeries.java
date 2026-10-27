package dev.chaoslab.ui;

import dev.chaoslab.analysis.IntegratorComparison.MethodResult;
import dev.chaoslab.analysis.IntegratorComparison.Report;
import dev.chaoslab.analysis.IntegratorComparison.Sample;
import java.util.ArrayList;
import java.util.List;

/**
 * The curves of an integrator comparison, ready to plot: for each method the sample times (in display units), the size
 * of its relative energy drift and its separation from the reference. Everything is computed here so that the plot
 * class only draws, and so that it can be tested.
 */
final class ComparisonSeries {

    /** Lowest decade ever shown; below this a value is indistinguishable from zero on screen. */
    static final int FLOOR_EXPONENT = -17;

    /** One method's curves. The arrays have equal length: the samples the method completed. */
    record Series(String label, double[] times, double[] energy, double[] separation) {
    }

    private ComparisonSeries() {
    }

    /** @param timeScale simulation units per display unit, so that times come out in days for SI scenarios */
    static List<Series> of(Report report, double timeScale) {
        List<Series> out = new ArrayList<>();
        for (MethodResult r : report.results()) {
            int n = r.samples().size();
            double[] t = new double[n];
            double[] e = new double[n];
            double[] s = new double[n];
            for (int i = 0; i < n; i++) {
                Sample sample = r.samples().get(i);
                t[i] = sample.time() / timeScale;
                e[i] = Math.abs(sample.relativeEnergyDrift());
                s[i] = sample.separationFromReference();
            }
            out.add(new Series(r.label(), t, e, s));
        }
        return out;
    }

    /**
     * The whole decades {@code {low, high}} that contain every positive finite value of the energy or the separation
     * curves, at least one decade tall, never below {@link #FLOOR_EXPONENT}. With no positive value it is {-16, 0}.
     */
    static int[] decades(List<Series> series, boolean energy) {
        double smallest = Double.POSITIVE_INFINITY;
        double largest = 0.0;
        for (Series s : series) {
            for (double v : energy ? s.energy() : s.separation()) {
                if (v > 0.0 && Double.isFinite(v)) {
                    smallest = Math.min(smallest, v);
                    largest = Math.max(largest, v);
                }
            }
        }
        if (largest <= 0.0) {
            return new int[] {-16, 0};
        }
        int low = Math.max(FLOOR_EXPONENT, (int) Math.floor(Math.log10(smallest)));
        int high = (int) Math.ceil(Math.log10(largest));
        if (high <= low) {
            high = low + 1;
        }
        return new int[] {low, high};
    }

    /** {first time, last time} over all series, or {0, 1} if there is no sample. */
    static double[] timeRange(List<Series> series) {
        double first = Double.POSITIVE_INFINITY;
        double last = Double.NEGATIVE_INFINITY;
        for (Series s : series) {
            for (double t : s.times()) {
                first = Math.min(first, t);
                last = Math.max(last, t);
            }
        }
        if (!(last > first)) {
            return new double[] {0.0, 1.0};
        }
        return new double[] {first, last};
    }
}
