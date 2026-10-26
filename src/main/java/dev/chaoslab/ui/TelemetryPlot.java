package dev.chaoslab.ui;

import java.util.Locale;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;

/**
 * Plot of the three conservation-law drifts against simulation time on a logarithmic vertical axis.
 *
 * <ul>
 *   <li><b>|dE/E0|</b> (cyan): relative energy drift. The one that matters: it measures integrator failure.</li>
 *   <li><b>dP/Pscale</b> (violet): linear-momentum drift, scaled by the sum of m|v|.</li>
 *   <li><b>dL/|L0|</b> (amber): angular-momentum drift.</li>
 * </ul>
 * Two reference lines are drawn: the double-precision rounding unit (about 1.1e-16), below which a drift is
 * indistinguishable from zero, and the 1e-3 level at which the window warns about energy drift.
 *
 * <p>Each plotted point is the <em>worst</em> value in its time bucket (see {@link TelemetryHistory}), so spikes are
 * never hidden by thinning.
 *
 * <p>What each curve can reveal depends on the integrator. Linear momentum is conserved to rounding by all four
 * integrators (pairwise forces are equal and opposite). Angular momentum is conserved to rounding only by Velocity
 * Verlet, so for Verlet the energy curve is the only one that shows a failure. For RK4, Dormand-Prince and Euler the
 * angular momentum drifts as well and is a second warning. Measured on the perturbed Lagrange triangle with
 * h = 0.005 up to t = 77.4 (largest values over the run): Verlet dL/|L0| 1.2e-14, Dormand-Prince 7.0e-3, Euler 0.12,
 * RK4 0.75, while all four kept dP/Pscale below 5e-13.
 *
 * <p>JavaFX application thread only.
 */
public final class TelemetryPlot extends Region {

    private static final Color BACKGROUND = Color.web("#070b14");
    private static final Color FRAME = Color.web("#ffffff", 0.18);
    private static final Color GRID = Color.web("#ffffff", 0.06);
    private static final Color LABEL = Color.web("#9fb0c8");
    private static final Color ENERGY = Color.web("#22d3ee");
    private static final Color MOMENTUM = Color.web("#a78bfa");
    private static final Color ANGULAR = Color.web("#fbbf24");
    private static final Color THRESHOLD = Color.web("#fbbf24", 0.75);
    private static final Color ROUNDING = Color.web("#9fb0c8", 0.75);

    private static final double WARNING_LEVEL = 1e-3;
    private static final double ROUNDING_UNIT = 1.1102230246251565e-16; // 2^-53

    private static final double LEFT = 52.0;
    private static final double RIGHT = 14.0;
    private static final double TOP = 24.0;
    private static final double BOTTOM = 24.0;

    private final Canvas canvas = new Canvas();
    private TelemetryHistory history;
    private double[] xs = new double[0];
    private double[] ys = new double[0];

    public TelemetryPlot() {
        canvas.setManaged(false);
        getChildren().add(canvas);
        setMinSize(0, 0);
    }

    /** Shows the given history (kept by reference) and redraws. Call again whenever it has changed. */
    public void show(TelemetryHistory newHistory) {
        this.history = newHistory;
        redraw();
    }

    @Override
    protected void layoutChildren() {
        double w = getWidth();
        double h = getHeight();
        if (w >= 1.0 && h >= 1.0) {
            if (canvas.getWidth() != w || canvas.getHeight() != h) {
                canvas.setWidth(w);
                canvas.setHeight(h);
            }
            redraw();
        }
    }

    private void redraw() {
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        if (w < 1.0 || h < 1.0) {
            return;
        }
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.setFill(BACKGROUND);
        g.fillRect(0, 0, w, h);

        double left = LEFT;
        double right = w - RIGHT;
        double top = TOP;
        double bottom = h - BOTTOM;
        if (right - left < 40.0 || bottom - top < 30.0) {
            return;
        }

        int n = history == null ? 0 : history.size();
        double smallest = Double.POSITIVE_INFINITY;
        double largest = 0.0;
        for (int i = 0; i < n; i++) {
            double[] v = {history.energy(i), history.momentum(i), history.angularMomentum(i)};
            for (double x : v) {
                if (x > 0.0) {
                    smallest = Math.min(smallest, x);
                }
                largest = Math.max(largest, x);
            }
        }
        LogAxis axis = LogAxis.covering(smallest == Double.POSITIVE_INFINITY ? 0.0 : smallest, largest);

        g.setFont(Font.font("Consolas", 11));
        drawGrid(g, axis, left, right, top, bottom);
        drawReferenceLine(g, axis, WARNING_LEVEL, "warning level 1e-3", THRESHOLD, left, right, top, bottom);
        drawReferenceLine(g, axis, ROUNDING_UNIT, "double-precision rounding 1.1e-16", ROUNDING, left, right, top,
                bottom);

        double t0 = n == 0 ? 0.0 : history.startTime();
        double t1 = n == 0 ? 1.0 : history.time(n - 1);
        if (!(t1 > t0)) {
            t1 = t0 + 1.0;
        }
        drawTimeAxis(g, t0, t1, left, right, bottom, h);

        if (n >= 1) {
            ensureCapacity(n);
            plotSeries(g, 2, ANGULAR, axis, t0, t1, left, right, top, bottom, n);
            plotSeries(g, 1, MOMENTUM, axis, t0, t1, left, right, top, bottom, n);
            plotSeries(g, 0, ENERGY, axis, t0, t1, left, right, top, bottom, n);
        }
        drawLegend(g, left, top);

        g.setStroke(FRAME);
        g.setLineWidth(1.0);
        g.strokeRect(left, top, right - left, bottom - top);
    }

    private void drawGrid(GraphicsContext g, LogAxis axis, double left, double right, double top, double bottom) {
        g.setTextAlign(TextAlignment.RIGHT);
        g.setFill(LABEL);
        g.setStroke(GRID);
        g.setLineWidth(1.0);
        for (int exponent : axis.tickExponents(8)) {
            double y = yFor(Math.pow(10.0, exponent), axis, top, bottom);
            g.strokeLine(left, y, right, y);
            g.fillText(LogAxis.label(exponent), left - 6.0, y + 4.0);
        }
    }

    private void drawReferenceLine(GraphicsContext g, LogAxis axis, double value, String text, Color colour,
            double left, double right, double top, double bottom) {
        double y = yFor(value, axis, top, bottom);
        g.setStroke(colour);
        g.setLineWidth(1.0);
        g.setLineDashes(5.0, 4.0);
        g.strokeLine(left, y, right, y);
        g.setLineDashes();
        g.setFill(colour);
        g.setTextAlign(TextAlignment.RIGHT);
        g.fillText(text, right - 6.0, y - 4.0);
    }

    private void drawTimeAxis(GraphicsContext g, double t0, double t1, double left, double right, double bottom,
            double height) {
        g.setFill(LABEL);
        g.setStroke(FRAME);
        int divisions = 4;
        for (int i = 0; i <= divisions; i++) {
            double f = (double) i / divisions;
            double x = left + f * (right - left);
            g.strokeLine(x, bottom, x, bottom + 4.0);
            g.setTextAlign(i == 0 ? TextAlignment.LEFT : i == divisions ? TextAlignment.RIGHT : TextAlignment.CENTER);
            g.fillText(String.format(Locale.ROOT, "%.4g", t0 + f * (t1 - t0)), x, height - 6.0);
        }
    }

    private void plotSeries(GraphicsContext g, int series, Color colour, LogAxis axis, double t0, double t1,
            double left, double right, double top, double bottom, int n) {
        for (int i = 0; i < n; i++) {
            double value = series == 0 ? history.energy(i)
                    : series == 1 ? history.momentum(i) : history.angularMomentum(i);
            xs[i] = left + (history.time(i) - t0) / (t1 - t0) * (right - left);
            ys[i] = yFor(value, axis, top, bottom);
        }
        g.setStroke(colour);
        g.setLineWidth(series == 0 ? 1.8 : 1.2);
        if (n == 1) {
            g.setFill(colour);
            g.fillOval(xs[0] - 2.5, ys[0] - 2.5, 5.0, 5.0);
        } else {
            g.strokePolyline(xs, ys, n);
        }
    }

    private void drawLegend(GraphicsContext g, double left, double top) {
        g.setTextAlign(TextAlignment.LEFT);
        double y = top - 8.0;
        double x = legendEntry(g, left, y, ENERGY, "|dE/E0|");
        x = legendEntry(g, x, y, MOMENTUM, "dP/Pscale");
        x = legendEntry(g, x, y, ANGULAR, "dL/|L0|");
        g.setFill(LABEL);
        g.fillText("conservation drift, log scale (worst value per time bucket)", x + 8.0, y);
    }

    private static double legendEntry(GraphicsContext g, double x, double y, Color colour, String text) {
        g.setStroke(colour);
        g.setLineWidth(2.0);
        g.strokeLine(x, y - 4.0, x + 14.0, y - 4.0);
        g.setFill(colour);
        g.fillText(text, x + 19.0, y);
        return x + 19.0 + 7.0 * text.length() + 16.0;
    }

    private static double yFor(double value, LogAxis axis, double top, double bottom) {
        return bottom - axis.fraction(value) * (bottom - top);
    }

    private void ensureCapacity(int n) {
        if (xs.length < n) {
            xs = new double[n];
            ys = new double[n];
        }
    }
}
