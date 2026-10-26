package dev.chaoslab.ui;

import java.util.Locale;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;

/**
 * Plot of the <em>numerical error</em> of the displayed run against time, on a logarithmic vertical axis.
 *
 * <ul>
 *   <li><b>Numerical error</b> (coral): the largest distance between a body of the displayed run and the same body of a
 *       high-accuracy reference solution at the same simulation time, divided by the size of the system (its longest
 *       pair distance at the start). It depends on the integrator and the step the person chose.</li>
 *   <li><b>Reference uncertainty</b> (grey, with the region below it shaded): how far the reference itself is from an
 *       even more accurate one. Below this line the gap cannot be told apart from the reference's own error, so it
 *       is not evidence of display error. In chaotic motion the line climbs exponentially, and when the coral curve
 *       reaches it the reference has stopped being a yardstick.</li>
 * </ul>
 * <p>Optionally ({@link #showTwin}) a third curve is drawn on the same axes:
 * <ul>
 *   <li><b>Twin separation</b> (violet): how far apart two runs have drifted that started 1e-6 of the system size
 *       apart, both computed with the high-accuracy reference integrator (see {@link
 *       dev.chaoslab.analysis.TwinRun}). Unlike the coral curve it does not depend on the integrator or step: it is the
 *       physics amplifying a tiny uncertainty. Where it climbs steeply the motion is sensitive; where it stays flat
 *       and low the motion is regular. It means something only while it is above the grey line.</li>
 * </ul>
 * Two fixed lines give scale: an error equal to the size of the system (the displayed bodies are as far from the
 * truth as the bodies are from each other), and the double-precision rounding unit.
 *
 * <p>This is deliberately a different quantity from the Lyapunov exponent shown in the readouts: that one is a
 * property of the motion itself and does not depend on the integrator, whereas this curve does.
 *
 * <p>Each plotted point is the worst value in its time bucket (see {@link GapHistory}). JavaFX application thread
 * only.
 */
public final class GapPlot extends Region {

    private static final Color BACKGROUND = Color.web("#070b14");
    private static final Color FRAME = Color.web("#ffffff", 0.18);
    private static final Color GRID = Color.web("#ffffff", 0.06);
    private static final Color LABEL = Color.web("#9fb0c8");
    private static final Color ERROR = Color.web("#fb7185");
    private static final Color TWIN = Color.web("#a78bfa");
    private static final Color UNCERTAINTY = Color.web("#9fb0c8");
    private static final Color UNCERTAINTY_FILL = Color.web("#9fb0c8", 0.14);
    private static final Color SIZE_LINE = Color.web("#fb7185", 0.6);
    private static final Color ROUNDING = Color.web("#9fb0c8", 0.75);

    private static final double ROUNDING_UNIT = 1.1102230246251565e-16; // 2^-53

    private static final double LEFT = 52.0;
    private static final double RIGHT = 14.0;
    private static final double TOP = 24.0;
    private static final double BOTTOM = 24.0;

    private final Canvas canvas = new Canvas();
    private GapHistory history;
    private GapHistory twin;
    private double[] xs = new double[0];
    private double[] ys = new double[0];

    public GapPlot() {
        canvas.setManaged(false);
        getChildren().add(canvas);
        setMinSize(0, 0);
    }

    /** Shows the given history (kept by reference) and redraws. Call again whenever it has changed. */
    public void show(GapHistory newHistory) {
        this.history = newHistory;
        redraw();
    }

    /**
     * Shows (or, with null, hides) the twin-separation curve. The history is kept by reference and only its gap series
     * is used (the separation divided by the characteristic length). Call {@link #show} or this method again whenever
     * it has changed.
     */
    public void showTwin(GapHistory newTwin) {
        this.twin = newTwin;
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
        int m = twin == null ? 0 : twin.size();
        double smallest = Double.POSITIVE_INFINITY;
        double largest = 0.0;
        for (int i = 0; i < n; i++) {
            double[] v = {history.gap(i), history.uncertainty(i)};
            for (double x : v) {
                if (x > 0.0) {
                    smallest = Math.min(smallest, x);
                }
                if (x > largest) { // NaN compares false and is skipped
                    largest = x;
                }
            }
        }
        for (int i = 0; i < m; i++) {
            double x = twin.gap(i);
            if (x > 0.0) {
                smallest = Math.min(smallest, x);
            }
            if (x > largest) {
                largest = x;
            }
        }
        LogAxis axis = LogAxis.covering(smallest == Double.POSITIVE_INFINITY ? 0.0 : smallest, largest);

        g.setFont(Font.font("Consolas", 11));
        drawGrid(g, axis, left, right, top, bottom);
        drawReferenceLine(g, axis, 1.0, "error = size of the system", SIZE_LINE, left, right, top, bottom, 13.0,
                false);
        drawReferenceLine(g, axis, ROUNDING_UNIT, "double-precision rounding 1.1e-16", ROUNDING, left, right, top,
                bottom, -4.0, true);

        double t0 = n == 0 ? 0.0 : history.startTime();
        double t1 = n == 0 ? 1.0 : history.time(n - 1);
        if (m > 0) {
            t0 = n == 0 ? twin.startTime() : Math.min(t0, twin.startTime());
            t1 = n == 0 ? twin.time(m - 1) : Math.max(t1, twin.time(m - 1));
        }
        if (!(t1 > t0)) {
            t1 = t0 + 1.0;
        }
        drawTimeAxis(g, t0, t1, left, right, bottom, h);

        ensureCapacity(Math.max(n, m));
        if (n >= 1) {
            plotUncertainty(g, axis, t0, t1, left, right, top, bottom, n);
            plotGap(g, axis, t0, t1, left, right, top, bottom, n);
        }
        if (m >= 1) {
            plotTwin(g, axis, t0, t1, left, right, top, bottom, m);
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
                                   double left, double right, double top, double bottom, double textOffset, boolean labelAtRight) {
        double y = yFor(value, axis, top, bottom);
        g.setStroke(colour);
        g.setLineWidth(1.0);
        g.setLineDashes(5.0, 4.0);
        g.strokeLine(left, y, right, y);
        g.setLineDashes();
        g.setFill(colour);
        g.setTextAlign(labelAtRight ? TextAlignment.RIGHT : TextAlignment.LEFT);
        g.fillText(text, labelAtRight ? right - 6.0 : left + 6.0, y + textOffset);
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

    /** The reference-uncertainty line, with the area under it shaded, in separate pieces where it is unknown. */
    private void plotUncertainty(GraphicsContext g, LogAxis axis, double t0, double t1, double left, double right,
                                 double top, double bottom, int n) {
        int i = 0;
        while (i < n) {
            while (i < n && Double.isNaN(history.uncertainty(i))) {
                i++;
            }
            int count = 0;
            while (i < n && !Double.isNaN(history.uncertainty(i))) {
                xs[count] = xFor(history.time(i), t0, t1, left, right);
                ys[count] = yFor(history.uncertainty(i), axis, top, bottom);
                count++;
                i++;
            }
            if (count == 0) {
                continue;
            }
            g.setStroke(UNCERTAINTY);
            g.setLineWidth(1.3);
            if (count == 1) {
                g.setFill(UNCERTAINTY);
                g.fillOval(xs[0] - 2.0, ys[0] - 2.0, 4.0, 4.0);
            } else {
                double[] px = new double[count + 2];
                double[] py = new double[count + 2];
                px[0] = xs[0];
                py[0] = bottom;
                System.arraycopy(xs, 0, px, 1, count);
                System.arraycopy(ys, 0, py, 1, count);
                px[count + 1] = xs[count - 1];
                py[count + 1] = bottom;
                g.setFill(UNCERTAINTY_FILL);
                g.fillPolygon(px, py, count + 2);
                g.strokePolyline(xs, ys, count);
            }
        }
    }

    private void plotGap(GraphicsContext g, LogAxis axis, double t0, double t1, double left, double right,
                         double top, double bottom, int n) {
        for (int i = 0; i < n; i++) {
            xs[i] = xFor(history.time(i), t0, t1, left, right);
            ys[i] = yFor(history.gap(i), axis, top, bottom);
        }
        g.setStroke(ERROR);
        g.setLineWidth(2.0);
        if (n == 1) {
            g.setFill(ERROR);
            g.fillOval(xs[0] - 2.5, ys[0] - 2.5, 5.0, 5.0);
        } else {
            g.strokePolyline(xs, ys, n);
        }
    }

    private void plotTwin(GraphicsContext g, LogAxis axis, double t0, double t1, double left, double right,
                          double top, double bottom, int m) {
        for (int i = 0; i < m; i++) {
            xs[i] = xFor(twin.time(i), t0, t1, left, right);
            ys[i] = yFor(twin.gap(i), axis, top, bottom);
        }
        g.setStroke(TWIN);
        g.setLineWidth(2.0);
        if (m == 1) {
            g.setFill(TWIN);
            g.fillOval(xs[0] - 2.5, ys[0] - 2.5, 5.0, 5.0);
        } else {
            g.strokePolyline(xs, ys, m);
        }
    }

    private void drawLegend(GraphicsContext g, double left, double top) {
        g.setTextAlign(TextAlignment.LEFT);
        double y = top - 8.0;
        double x = legendEntry(g, left, y, ERROR, "numerical error vs reference (relative to system size)");
        x = legendEntry(g, x, y, UNCERTAINTY, "reference uncertainty: below it the gap is noise");
        if (twin != null) {
            x = legendEntry(g, x, y, TWIN, "twin separation (1e-6 nudge, physics)");
        }
        g.setFill(LABEL);
        g.fillText("log scale", x + 4.0, y);
    }

    private static double legendEntry(GraphicsContext g, double x, double y, Color colour, String text) {
        g.setStroke(colour);
        g.setLineWidth(2.0);
        g.strokeLine(x, y - 4.0, x + 14.0, y - 4.0);
        g.setFill(colour);
        g.fillText(text, x + 19.0, y);
        return x + 19.0 + 6.6 * text.length() + 16.0;
    }

    private static double xFor(double time, double t0, double t1, double left, double right) {
        return left + (time - t0) / (t1 - t0) * (right - left);
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
