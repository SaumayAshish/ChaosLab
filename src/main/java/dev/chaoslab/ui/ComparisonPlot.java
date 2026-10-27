package dev.chaoslab.ui;

import dev.chaoslab.ui.ComparisonSeries.Series;
import java.util.List;
import java.util.Locale;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;

/**
 * Two stacked plots of an integrator comparison on logarithmic vertical axes: the size of each method's relative energy
 * drift, and its separation from the reference, both against time. One coloured curve per method, with a marker at each
 * sample (there are about twenty), so a method that stopped early simply ends.
 *
 * <p>Separation is measured against a numerical reference: read it together with the reference-uncertainty sentences in
 * the text, which say when a separation is too small to mean anything. Values of zero (the first sample) are drawn on
 * the lower edge. JavaFX application thread only; the numbers come from {@link ComparisonSeries}.
 */
final class ComparisonPlot extends Region {

    private static final Color BACKGROUND = Color.web("#070b14");
    private static final Color FRAME = Color.web("#ffffff", 0.18);
    private static final Color GRID = Color.web("#ffffff", 0.06);
    private static final Color LABEL = Color.web("#9fb0c8");
    /** One colour per method, in the order the methods are listed. */
    static final String[] PALETTE = {"#fb7185", "#fbbf24", "#22d3ee", "#a78bfa", "#34d399", "#f472b6", "#94a3b8"};

    private static final double LEFT = 52.0;
    private static final double RIGHT = 14.0;
    private static final double LEGEND = 40.0;
    private static final double PANEL_GAP = 26.0;
    private static final double BOTTOM = 24.0;
    private static final double CHARACTER_WIDTH = 6.7;

    private final Canvas canvas = new Canvas();
    private List<Series> series = List.of();
    private String timeLabel = "";

    ComparisonPlot() {
        canvas.setManaged(false);
        getChildren().add(canvas);
        setMinSize(0, 0);
    }

    /**
     * Shows the given curves, or nothing for an empty list.
     *
     * @param newTimeLabel names the time axis, for example "time in d"
     */
    void show(List<Series> newSeries, String newTimeLabel) {
        this.series = newSeries;
        this.timeLabel = newTimeLabel;
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
        if (series.isEmpty()) {
            return;
        }
        g.setFont(Font.font("Consolas", 11));
        drawLegend(g, w - 100.0);
        g.setFill(LABEL);
        g.setTextAlign(TextAlignment.RIGHT);
        g.fillText(timeLabel, w - RIGHT, 15.0);

        double left = LEFT;
        double right = w - RIGHT;
        double usable = h - LEGEND - BOTTOM - PANEL_GAP;
        if (right - left < 60.0 || usable < 80.0) {
            return;
        }
        double panel = usable / 2.0;
        double[] time = ComparisonSeries.timeRange(series);
        drawPanel(g, true, "relative energy drift |dE/E0|", left, right, LEGEND, LEGEND + panel, time);
        double secondTop = LEGEND + panel + PANEL_GAP;
        drawPanel(g, false, "separation from the reference", left, right, secondTop, secondTop + panel, time);

        g.setFill(LABEL);
        g.setTextAlign(TextAlignment.CENTER);
        for (int i = 0; i <= 4; i++) {
            double f = i / 4.0;
            g.fillText(String.format(Locale.ROOT, "%.4g", time[0] + f * (time[1] - time[0])),
                    left + f * (right - left), h - 6.0);
        }
    }

    private void drawLegend(GraphicsContext g, double width) {
        double x = LEFT;
        g.setTextAlign(TextAlignment.LEFT);
        for (int i = 0; i < series.size(); i++) {
            String label = series.get(i).label();
            double needed = 24.0 + label.length() * CHARACTER_WIDTH + 16.0;
            if (x + needed > width) {
                return; // no room: leave the rest out rather than overlap
            }
            Color colour = colour(i);
            g.setStroke(colour);
            g.setLineWidth(2.0);
            g.strokeLine(x, 11.0, x + 16.0, 11.0);
            g.setFill(LABEL);
            g.fillText(label, x + 20.0, 15.0);
            x += needed;
        }
    }

    private void drawPanel(GraphicsContext g, boolean energy, String title, double left, double right, double top,
                           double bottom, double[] time) {
        int[] decades = ComparisonSeries.decades(series, energy);
        LogAxis axis = new LogAxis(decades[0], decades[1]);
        g.setStroke(FRAME);
        g.setLineWidth(1.0);
        g.strokeRect(left, top, right - left, bottom - top);
        g.setTextAlign(TextAlignment.RIGHT);
        for (int exponent : axis.tickExponents(5)) {
            double y = bottom - axis.fraction(Math.pow(10.0, exponent)) * (bottom - top);
            g.setStroke(GRID);
            g.strokeLine(left, y, right, y);
            g.setFill(LABEL);
            g.fillText(LogAxis.label(exponent), left - 6.0, y + 4.0);
        }
        g.setFill(LABEL);
        g.setTextAlign(TextAlignment.LEFT);
        g.fillText(title + "  (log scale)", left, top - 6.0); // above the frame: curves never cross it

        double span = time[1] - time[0];
        for (int s = 0; s < series.size(); s++) {
            Series one = series.get(s);
            double[] values = energy ? one.energy() : one.separation();
            int n = values.length;
            double[] xs = new double[n];
            double[] ys = new double[n];
            for (int i = 0; i < n; i++) {
                xs[i] = left + (one.times()[i] - time[0]) / span * (right - left);
                ys[i] = bottom - axis.fraction(values[i]) * (bottom - top);
            }
            Color colour = colour(s);
            g.setStroke(colour);
            g.setLineWidth(1.6);
            if (n >= 2) {
                g.strokePolyline(xs, ys, n);
            }
            g.setFill(colour);
            for (int i = 0; i < n; i++) {
                g.fillOval(xs[i] - 2.5, ys[i] - 2.5, 5.0, 5.0);
            }
        }
    }

    private static Color colour(int index) {
        return Color.web(PALETTE[index % PALETTE.length]);
    }
}
