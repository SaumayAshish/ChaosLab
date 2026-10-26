package dev.chaoslab.io;

import dev.chaoslab.ui.GapHistory;
import dev.chaoslab.ui.TelemetryHistory;

/**
 * Plain CSV of the curves the window draws, for plotting elsewhere. Every file has one header row, then one row per
 * stored history entry; lines end in a single newline; numbers are written with {@link Double#toString} (full
 * precision, '.' decimal point, independent of the computer's locale); a value that is unknown is an empty field.
 *
 * <p>The histories keep a bounded number of entries for a whole run (see {@link TelemetryHistory}): when they fill up,
 * neighbouring samples are merged and each entry stands for several samples, keeping the <em>largest</em> value and
 * the time of the latest. So these are "worst value per time bucket" curves, exactly as plotted, not every sample.
 *
 * <p>Drift values are magnitudes. The gap, uncertainty and separation are divided by the system's characteristic
 * length (its largest pair distance at the start), so they are dimensionless.
 */
public final class CsvExport {

    private CsvExport() {
    }

    /** Conservation drift: time, |dE/E0|, dP/Pscale, dL/|L0|. */
    public static String drift(TelemetryHistory h, String timeColumn) {
        StringBuilder b = header(timeColumn, "abs_dE_over_E0", "dP_over_Pscale", "dL_over_L0");
        for (int i = 0; i < h.size(); i++) {
            row(b, h.time(i), h.energy(i), h.momentum(i), h.angularMomentum(i));
        }
        return b.toString();
    }

    /** Numerical error against the reference: time, relative gap, relative reference uncertainty (empty if unknown). */
    public static String error(GapHistory h, String timeColumn) {
        StringBuilder b = header(timeColumn, "relative_gap", "relative_reference_uncertainty");
        for (int i = 0; i < h.size(); i++) {
            row(b, h.time(i), h.gap(i), h.uncertainty(i));
        }
        return b.toString();
    }

    /** Twin-run separation: time, relative separation (stored as the gap series of the history). */
    public static String twin(GapHistory h, String timeColumn) {
        StringBuilder b = header(timeColumn, "relative_separation");
        for (int i = 0; i < h.size(); i++) {
            row(b, h.time(i), h.gap(i));
        }
        return b.toString();
    }

    private static StringBuilder header(String... names) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < names.length; i++) {
            if (names[i] == null || !names[i].matches("[A-Za-z_][A-Za-z0-9_]*")) {
                throw new IllegalArgumentException("a column name must be letters, digits and underscores: "
                        + names[i]);
            }
            b.append(i == 0 ? "" : ",").append(names[i]);
        }
        return b.append('\n');
    }

    private static void row(StringBuilder b, double... values) {
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                b.append(',');
            }
            if (!Double.isNaN(values[i])) {
                b.append(Double.toString(values[i]));
            }
        }
        b.append('\n');
    }
}
