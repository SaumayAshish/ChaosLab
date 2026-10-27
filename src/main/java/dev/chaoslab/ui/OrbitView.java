package dev.chaoslab.ui;

import dev.chaoslab.physics.NBodySystem;
import dev.chaoslab.physics.State;
import dev.chaoslab.physics.Vec3;
import dev.chaoslab.simulation.Snapshot;
import java.util.Locale;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;

/**
 * The central orbit view: draws a {@link Snapshot} on a canvas with trails, velocity vectors, a centre-of-mass
 * marker, a scale bar and an info panel for the selected body.
 *
 * <h2>Interaction</h2>
 * Mouse wheel zooms about the cursor, dragging pans, a click selects the nearest body (click empty space to
 * deselect). Zooming or panning switches auto-fit off; the auto-fit property can be switched back on.
 *
 * <h2>What is drawn, and its limits</h2>
 * Everything shown is the numerical solution produced by the chosen integrator. Trails are polylines through
 * published snapshots, which are sampled at no more than about 120 Hz of wall-clock time, not through every
 * integration step; at very high speeds consecutive trail points are far apart in simulated time, so trails look
 * angular. That is a display limitation and does not affect the simulation. Velocity arrows use a
 * pixels-per-velocity scale that is set from the initial speeds of each run (the fastest body gets an arrow of about
 * 45 pixels) and is not tied to the zoom level; the scale bar states it.
 *
 * <h2>The twin</h2>
 * {@link #showTwin} draws a second, violet ring around each body: where that body would be in a twin run that
 * started 1e-6 of the system size away (see {@link dev.chaoslab.analysis.TwinRun}). The caller places each ring at the
 * displayed body plus the twin's offset, so the ring's displacement from its body is the physical separation of the
 * pair, free of the displayed integrator's own error; a thin line joins body and ring once they are visibly apart.
 * Early on the two coincide, and in regular motion they stay together; in chaotic motion the ring leaves its body
 * and ends up elsewhere in the system. Twin rings are not part of the auto-fit.
 *
 * <h2>Frames</h2>
 * {@link #setFrame} attaches the picture to one body: every position is drawn relative to that body at the same
 * instant (see {@link ReferenceFrame}), so the Moon's trail in an Earth frame is the loop it makes around the Earth.
 * Arrows show velocities relative to the frame body as well. With auto-fit on, the view fits the frame body and the
 * bodies within a factor of {@link ReferenceFrame#HIERARCHY_FACTOR} of the closest companion; farther bodies are
 * drawn where they are, possibly off screen. The frame body stays at the centre, so its own motion (and, for the
 * reference rings, its own error) is not visible; the other rings show the error of each body relative to it.
 *
 * <h2>The reference ("ghost")</h2>
 * {@link #showReference} adds a second, dashed picture: where a high-accuracy reference solution of the same
 * initial state is (hollow dashed rings) and where it has been (dashed trails in a paler version of each body's
 * colour). Where the dashed and the solid pictures part, the displayed run is wrong. The ghost is fed by a
 * background monitor; the caller places each ring at the displayed body plus the measured error (see
 * {@link ReferenceAligner}), so a ring's offset from its body is the error, not the monitor's lag. The gap plot, not
 * the picture, is the quantitative statement.
 *
 * <p>JavaFX application thread only.
 */
public final class OrbitView extends Region {

    private static final int TRAIL_CAPACITY = 6000;
    private static final int TRAIL_CHUNKS = 12;
    /** Arrow scale used when every body starts at rest (no speed to calibrate against). */
    private static final double DEFAULT_PIXELS_PER_VELOCITY = 60.0;
    /** Length in pixels of the arrow of the fastest body at the start of a run. */
    private static final double REFERENCE_ARROW_PIXELS = 45.0;
    private static final double PICK_RADIUS_PIXELS = 18.0;
    private static final double DRAG_THRESHOLD_PIXELS = 4.0;
    private static final double FIT_MARGIN = 0.12;

    private static final Color BACKGROUND = Color.web("#070b14");
    private static final Color AXIS = Color.web("#ffffff", 0.07);
    private static final Color LABEL = Color.web("#9fb0c8");
    private static final Color TWIN_COLOUR = Color.web("#a78bfa");
    private static final Color SCALE_BAR = Color.web("#6b7c96");
    private static final Color PANEL = Color.web("#0d1424", 0.85);
    /** Approximate width of one character of the 11 px monospaced legend font, used to size the plate behind it. */
    private static final double LEGEND_CHARACTER_WIDTH = 6.7;
    private static final Color[] PALETTE = {
            Color.web("#22d3ee"), // cyan
            Color.web("#a78bfa"), // violet
            Color.web("#fbbf24"), // amber
            Color.web("#34d399"),
            Color.web("#fb7185"),
            Color.web("#60a5fa")
    };

    private final Canvas canvas = new Canvas();
    private final Camera camera = new Camera();
    private final BooleanProperty autoFit = new SimpleBooleanProperty(true);
    private final BooleanProperty showTrails = new SimpleBooleanProperty(true);
    private final BooleanProperty showVectors = new SimpleBooleanProperty(true);
    private final BooleanProperty showGhost = new SimpleBooleanProperty(true);

    private NBodySystem system;
    private TrailBuffer trails;
    private TrailBuffer ghostTrails;
    private double[] ghostPositions;
    private double[] twinPositions;
    private double ghostTime = Double.NaN;
    private Snapshot latest;
    private int selected = -1;
    private int frame = -1;
    private long lastTrailStep = -1;
    private double pixelsPerVelocity = DEFAULT_PIXELS_PER_VELOCITY;

    private double pressX;
    private double pressY;
    private double lastX;
    private double lastY;
    private boolean dragged;

    public OrbitView() {
        canvas.setManaged(false);
        getChildren().add(canvas);
        setMinSize(0, 0);

        setOnScroll(e -> {
            if (e.getDeltaY() == 0.0) {
                return;
            }
            camera.zoomAt(e.getX(), e.getY(), Math.exp(e.getDeltaY() / 400.0));
            autoFit.set(false);
            redraw();
        });
        setOnMousePressed(e -> {
            pressX = e.getX();
            pressY = e.getY();
            lastX = pressX;
            lastY = pressY;
            dragged = false;
        });
        setOnMouseDragged(e -> {
            if (Math.hypot(e.getX() - pressX, e.getY() - pressY) > DRAG_THRESHOLD_PIXELS) {
                dragged = true;
            }
            if (dragged) {
                camera.panByPixels(e.getX() - lastX, e.getY() - lastY);
                autoFit.set(false);
            }
            lastX = e.getX();
            lastY = e.getY();
            redraw();
        });
        setOnMouseReleased(e -> {
            if (!dragged) {
                pick(e.getX(), e.getY());
            }
        });

        autoFit.addListener((o, was, is) -> redraw());
        showTrails.addListener((o, was, is) -> redraw());
        showVectors.addListener((o, was, is) -> redraw());
        showGhost.addListener((o, was, is) -> redraw());
    }

    // ---------------------------------------------------------------- public API

    public BooleanProperty autoFitProperty() {
        return autoFit;
    }

    public BooleanProperty showTrailsProperty() {
        return showTrails;
    }

    public BooleanProperty showVectorsProperty() {
        return showVectors;
    }

    /** Whether the dashed reference rings and trails are drawn. */
    public BooleanProperty showGhostProperty() {
        return showGhost;
    }

    /** Tells the view which system it is drawing (for masses) and discards all trails and the selection. */
    public void setSystem(NBodySystem newSystem) {
        this.system = newSystem;
        this.trails = new TrailBuffer(newSystem.bodyCount(), TRAIL_CAPACITY);
        this.ghostTrails = new TrailBuffer(newSystem.bodyCount(), TRAIL_CAPACITY);
        this.ghostPositions = null;
        this.twinPositions = null;
        this.ghostTime = Double.NaN;
        this.selected = -1;
        this.frame = -1;
        this.lastTrailStep = -1;
        this.latest = null;
        redraw();
    }

    /**
     * Draws everything relative to one body, or in the inertial frame.
     *
     * @param body index of the frame body, or -1 for the inertial frame
     * @throws IllegalArgumentException if the index is not -1 or a body of the current system
     */
    public void setFrame(int body) {
        int n = system == null ? 0 : system.bodyCount();
        if (body < -1 || body >= n) {
            throw new IllegalArgumentException("frame must be -1 or a body index below " + n + ", got " + body);
        }
        frame = body;
        redraw();
    }

    /** The frame body index, or -1 for the inertial frame. */
    public int frame() {
        return frame;
    }

    public void clearTrails() {
        if (trails != null) {
            trails.clear();
        }
        clearGhost();
        lastTrailStep = -1;
        redraw();
    }

    private void clearGhost() {
        if (ghostTrails != null) {
            ghostTrails.clear();
        }
        ghostPositions = null;
        ghostTime = Double.NaN;
    }

    /**
     * Shows where the high-accuracy reference is at simulation time {@code time}. Each call with a later time than
     * the previous one adds a point to the reference trails; a time that went backwards means the run was reset and
     * clears them. Positions that are null (the reference could not reach that time), of the wrong length or not
     * finite hide the rings but keep the trails.
     *
     * @param time      simulation time of the reference positions
     * @param positions flat positions of all bodies (x0, y0, z0, x1, ...), or null
     */
    public void showReference(double time, double[] positions) {
        if (system == null || !Double.isFinite(time)) {
            return;
        }
        if (!Double.isNaN(ghostTime) && time < ghostTime) {
            ghostTrails.clear();
        }
        boolean usable = positions != null && positions.length == 3 * system.bodyCount();
        for (int i = 0; usable && i < system.bodyCount(); i++) {
            usable = Double.isFinite(positions[3 * i]) && Double.isFinite(positions[3 * i + 1]);
        }
        if (usable) {
            if (Double.isNaN(ghostTime) || time > ghostTime) {
                ghostTrails.append(positions);
            }
            ghostPositions = positions.clone();
        } else {
            ghostPositions = null;
        }
        ghostTime = time;
        redraw();
    }

    /**
     * Shows (or, with null, hides) the twin rings.
     *
     * @param positions flat positions (x0, y0, z0, x1, ...) of all bodies in the twin run, in the same coordinates as
     *                  the displayed bodies; null, of the wrong length or not finite hides the rings
     */
    public void showTwin(double[] positions) {
        if (system == null) {
            return;
        }
        boolean usable = positions != null && positions.length == 3 * system.bodyCount();
        for (int i = 0; usable && i < system.bodyCount(); i++) {
            usable = Double.isFinite(positions[3 * i]) && Double.isFinite(positions[3 * i + 1]);
        }
        twinPositions = usable ? positions.clone() : null;
        redraw();
    }

    /**
     * Draws the given snapshot. Trail samples are added when the step count advances; step count 0 (the initial or
     * reset state) restarts the trails; a step count that went backwards (rollback after a failure) changes nothing.
     */
    public void show(Snapshot snapshot) {
        if (system == null) {
            return;
        }
        long steps = snapshot.stepCount();
        if (steps == 0) {
            pixelsPerVelocity = calibrateArrows(snapshot.state());
            trails.clear();
            trails.append(snapshot.state().positions());
            ghostTrails.clear();
            ghostPositions = null;
            twinPositions = null;
            ghostTime = Double.NaN;
            lastTrailStep = 0;
        } else if (steps > lastTrailStep) {
            trails.append(snapshot.state().positions());
            lastTrailStep = steps;
        }
        latest = snapshot;
        redraw();
    }

    /** Fits the view to everything currently in the trails, once, without changing the auto-fit setting. */
    public void fitNow() {
        fitToTrails();
        redraw();
    }

    /** Origin at the centre, default scale; switches auto-fit off. */
    public void resetView() {
        camera.reset();
        autoFit.set(false);
        redraw();
    }

    // ---------------------------------------------------------------- layout

    @Override
    protected void layoutChildren() {
        double w = getWidth();
        double h = getHeight();
        if (w >= 1.0 && h >= 1.0) {
            if (canvas.getWidth() != w || canvas.getHeight() != h) {
                canvas.setWidth(w);
                canvas.setHeight(h);
            }
            camera.setViewport(w, h);
            redraw();
        }
    }

    // ---------------------------------------------------------------- interaction

    private void pick(double sx, double sy) {
        if (latest == null) {
            return;
        }
        State s = latest.state();
        int best = -1;
        double bestDistance = PICK_RADIUS_PIXELS;
        for (int i = 0; i < s.bodyCount(); i++) {
            Vec3 p = s.position(i);
            double d = Math.hypot(camera.worldToScreenX(p.x() - frameX()) - sx,
                    camera.worldToScreenY(p.y() - frameY()) - sy);
            if (d <= bestDistance) {
                bestDistance = d;
                best = i;
            }
        }
        selected = best;
        redraw();
    }

    // ---------------------------------------------------------------- drawing

    private void fitToTrails() {
        double[] b = new double[4];
        boolean have = trails != null && ReferenceFrame.bounds(trails, frame, b);
        if (showGhost.get() && ghostTrails != null) {
            double[] gb = new double[4];
            if (ReferenceFrame.bounds(ghostTrails, frame, gb)) {
                if (have) {
                    b[0] = Math.min(b[0], gb[0]);
                    b[1] = Math.max(b[1], gb[1]);
                    b[2] = Math.min(b[2], gb[2]);
                    b[3] = Math.max(b[3], gb[3]);
                } else {
                    System.arraycopy(gb, 0, b, 0, 4);
                    have = true;
                }
            }
        }
        if (have) {
            camera.fit(b[0], b[1], b[2], b[3], FIT_MARGIN);
        }
    }

    /** x offset of the frame body in the displayed state (0 in the inertial frame). */
    private double frameX() {
        return frame < 0 ? 0.0 : latest.state().position(frame).x();
    }

    private double frameY() {
        return frame < 0 ? 0.0 : latest.state().position(frame).y();
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
        if (system == null || latest == null) {
            return;
        }
        if (autoFit.get()) {
            fitToTrails();
        }
        drawAxes(g, w, h);
        if (showGhost.get()) {
            drawGhostTrails(g);
        }
        if (showTrails.get()) {
            drawTrails(g);
        }
        drawCentreOfMass(g);
        drawBodies(g);
        if (showGhost.get()) {
            drawGhostRings(g);
        }
        drawTwinRings(g);
        drawScaleBar(g, h);
        if (selected >= 0) {
            drawSelectionPanel(g);
        }
    }

    private static Color colour(int body) {
        return PALETTE[body % PALETTE.length];
    }

    private void drawAxes(GraphicsContext g, double w, double h) {
        double ox = camera.worldToScreenX(-frameX());
        double oy = camera.worldToScreenY(-frameY());
        g.setStroke(AXIS);
        g.setLineWidth(1.0);
        if (ox >= 0 && ox <= w) {
            g.strokeLine(ox, 0, ox, h);
        }
        if (oy >= 0 && oy <= h) {
            g.strokeLine(0, oy, w, oy);
        }
    }

    private void drawTrails(GraphicsContext g) {
        int n = trails.size();
        if (n < 2) {
            return;
        }
        g.setLineWidth(1.6);
        for (int body = 0; body < trails.bodyCount(); body++) {
            Color base = colour(body);
            for (int c = 0; c < TRAIL_CHUNKS; c++) {
                int start = (int) ((long) c * (n - 1) / TRAIL_CHUNKS);
                int end = (int) ((long) (c + 1) * (n - 1) / TRAIL_CHUNKS);
                if (end <= start) {
                    continue;
                }
                double alpha = 0.05 + 0.80 * (c + 1) / TRAIL_CHUNKS; // older chunks fade out
                g.setStroke(base.deriveColor(0, 1, 1, alpha));
                g.beginPath();
                g.moveTo(camera.worldToScreenX(ReferenceFrame.x(trails, body, start, frame)),
                        camera.worldToScreenY(ReferenceFrame.y(trails, body, start, frame)));
                for (int k = start + 1; k <= end; k++) {
                    g.lineTo(camera.worldToScreenX(ReferenceFrame.x(trails, body, k, frame)),
                            camera.worldToScreenY(ReferenceFrame.y(trails, body, k, frame)));
                }
                g.stroke();
            }
        }
    }

    private void drawGhostTrails(GraphicsContext g) {
        int n = ghostTrails.size();
        if (n < 2) {
            return;
        }
        g.setLineWidth(1.2);
        g.setLineDashes(3.0, 5.0);
        for (int body = 0; body < ghostTrails.bodyCount(); body++) {
            g.setStroke(colour(body).deriveColor(0, 0.45, 1.2, 0.55));
            g.beginPath();
            g.moveTo(camera.worldToScreenX(ReferenceFrame.x(ghostTrails, body, 0, frame)),
                    camera.worldToScreenY(ReferenceFrame.y(ghostTrails, body, 0, frame)));
            for (int k = 1; k < n; k++) {
                g.lineTo(camera.worldToScreenX(ReferenceFrame.x(ghostTrails, body, k, frame)),
                        camera.worldToScreenY(ReferenceFrame.y(ghostTrails, body, k, frame)));
            }
            g.stroke();
        }
        g.setLineDashes();
    }

    /** Hollow dashed rings at the reference positions, drawn over the solid bodies. */
    private void drawGhostRings(GraphicsContext g) {
        if (ghostPositions == null) {
            return;
        }
        double maxMass = 0.0;
        for (int i = 0; i < system.bodyCount(); i++) {
            maxMass = Math.max(maxMass, system.mass(i));
        }
        double offsetX = frame < 0 ? 0.0 : ghostPositions[3 * frame];
        double offsetY = frame < 0 ? 0.0 : ghostPositions[3 * frame + 1];
        g.setLineWidth(1.5);
        g.setLineDashes(4.0, 3.0);
        for (int i = 0; i < system.bodyCount(); i++) {
            double x = camera.worldToScreenX(ghostPositions[3 * i] - offsetX);
            double y = camera.worldToScreenY(ghostPositions[3 * i + 1] - offsetY);
            double r = 4.0 + 5.0 * Math.cbrt(system.mass(i) / maxMass) + 3.0;
            g.setStroke(Color.web("#e5edf8", 0.9));
            g.strokeOval(x - r, y - r, 2 * r, 2 * r);
        }
        g.setLineDashes();
    }

    /** Violet rings at the twin positions, each joined to its body by a thin line once they are visibly apart. */
    private void drawTwinRings(GraphicsContext g) {
        if (twinPositions == null) {
            return;
        }
        double maxMass = 0.0;
        for (int i = 0; i < system.bodyCount(); i++) {
            maxMass = Math.max(maxMass, system.mass(i));
        }
        double offsetX = frame < 0 ? 0.0 : twinPositions[3 * frame];
        double offsetY = frame < 0 ? 0.0 : twinPositions[3 * frame + 1];
        g.setLineWidth(1.5);
        g.setLineDashes();
        for (int i = 0; i < system.bodyCount(); i++) {
            Vec3 p = latest.state().position(i);
            double bx = camera.worldToScreenX(p.x() - frameX());
            double by = camera.worldToScreenY(p.y() - frameY());
            double x = camera.worldToScreenX(twinPositions[3 * i] - offsetX);
            double y = camera.worldToScreenY(twinPositions[3 * i + 1] - offsetY);
            double r = 4.0 + 5.0 * Math.cbrt(system.mass(i) / maxMass) + 6.0;
            g.setStroke(TWIN_COLOUR);
            if (Math.hypot(x - bx, y - by) > r) {
                g.setLineWidth(1.0);
                g.strokeLine(bx, by, x, y);
                g.setLineWidth(1.5);
            }
            g.strokeOval(x - r, y - r, 2 * r, 2 * r);
        }
    }

    private void drawCentreOfMass(GraphicsContext g) {
        Vec3 com = latest.invariants().centreOfMass();
        double x = camera.worldToScreenX(com.x() - frameX());
        double y = camera.worldToScreenY(com.y() - frameY());
        g.setStroke(Color.web("#e5edf8", 0.85));
        g.setLineWidth(1.2);
        g.strokeLine(x - 6, y, x + 6, y);
        g.strokeLine(x, y - 6, x, y + 6);
        g.setFill(LABEL);
        g.setFont(Font.font("Consolas", 11));
        g.setTextAlign(TextAlignment.RIGHT); // below and to the left: body labels sit above and to the right
        g.fillText("COM", x - 8, y + 16);
        g.setTextAlign(TextAlignment.LEFT);
    }

    private void drawBodies(GraphicsContext g) {
        State s = latest.state();
        double maxMass = 0.0;
        for (int i = 0; i < system.bodyCount(); i++) {
            maxMass = Math.max(maxMass, system.mass(i));
        }
        g.setFont(Font.font("Consolas", 12));
        for (int i = 0; i < s.bodyCount(); i++) {
            Vec3 p = s.position(i);
            Vec3 v = s.velocity(i);
            double vx = v.x() - (frame < 0 ? 0.0 : s.velocity(frame).x());
            double vy = v.y() - (frame < 0 ? 0.0 : s.velocity(frame).y());
            double x = camera.worldToScreenX(p.x() - frameX());
            double y = camera.worldToScreenY(p.y() - frameY());
            double r = 4.0 + 5.0 * Math.cbrt(system.mass(i) / maxMass);
            Color c = colour(i);

            if (showVectors.get()) {
                drawArrow(g, c, x, y, x + vx * pixelsPerVelocity, y - vy * pixelsPerVelocity);
            }
            g.setFill(c.deriveColor(0, 1, 1, 0.18));
            g.fillOval(x - 2 * r, y - 2 * r, 4 * r, 4 * r);
            g.setFill(c);
            g.fillOval(x - r, y - r, 2 * r, 2 * r);
            if (i == selected) {
                g.setStroke(Color.WHITE);
                g.setLineWidth(1.5);
                g.strokeOval(x - r - 5, y - r - 5, 2 * r + 10, 2 * r + 10);
            }
            g.setFill(LABEL);
            g.fillText("m" + (i + 1), x + r + 5, y - r - 3);
        }
    }

    private static void drawArrow(GraphicsContext g, Color c, double x0, double y0, double x1, double y1) {
        double dx = x1 - x0;
        double dy = y1 - y0;
        double len = Math.hypot(dx, dy);
        if (len < 3.0) {
            return;
        }
        g.setStroke(c.deriveColor(0, 1, 1, 0.9));
        g.setLineWidth(1.3);
        g.strokeLine(x0, y0, x1, y1);
        double angle = Math.atan2(dy, dx);
        double head = Math.min(7.0, len * 0.4);
        for (int sign = -1; sign <= 1; sign += 2) {
            double a = angle + Math.PI - sign * Math.toRadians(25);
            g.strokeLine(x1, y1, x1 + head * Math.cos(a), y1 + head * Math.sin(a));
        }
    }

    private void drawScaleBar(GraphicsContext g, double h) {
        double length = niceLength(100.0 / camera.scale());
        double px = length * camera.scale();
        double x = 16.0;
        double y = h - 20.0;
        // the legend lines, bottom to top
        java.util.List<String> lines = new java.util.ArrayList<>();
        lines.add(String.format(Locale.ROOT, "%.3g length units", length));
        lines.add(String.format(Locale.ROOT, "arrows: 1 velocity unit = %.3g px", pixelsPerVelocity));
        if (showGhost.get()) {
            lines.add("dashed rings and trails: high-accuracy reference");
        }
        if (frame >= 0) {
            lines.add("frame: relative to m" + (frame + 1) + " (fitted to the bodies near it)");
        }
        // a plate behind the whole block, so a body or trail passing under it does not make the text unreadable
        int longest = 0;
        for (String line : lines) {
            longest = Math.max(longest, line.length());
        }
        double plateWidth = Math.max(px, longest * LEGEND_CHARACTER_WIDTH) + 14.0;
        double plateTop = y - 8.0 - 16.0 * (lines.size() - 1) - 13.0;
        g.setFill(PANEL);
        g.fillRect(x - 8.0, plateTop, plateWidth, y + 9.0 - plateTop);
        g.setStroke(SCALE_BAR);
        g.setLineWidth(1.2);
        g.strokeLine(x, y, x + px, y);
        g.strokeLine(x, y - 4, x, y + 4);
        g.strokeLine(x + px, y - 4, x + px, y + 4);
        g.setFill(LABEL);
        g.setFont(Font.font("Consolas", 11));
        for (int i = 0; i < lines.size(); i++) {
            g.fillText(lines.get(i), x, y - 8.0 - 16.0 * i);
        }
    }

    /**
     * Chooses the arrow scale so that the fastest body at the start of a run gets an arrow of about
     * {@link #REFERENCE_ARROW_PIXELS}. Velocities differ by many orders of magnitude between scenarios (a fixed
     * scale would make arrows invisible or thousands of pixels long), and fixing the scale at the start keeps
     * arrow lengths comparable as the run proceeds.
     */
    private static double calibrateArrows(State s) {
        double fastest = 0.0;
        for (int i = 0; i < s.bodyCount(); i++) {
            fastest = Math.max(fastest, s.velocity(i).norm());
        }
        return fastest > 0.0 && Double.isFinite(fastest) ? REFERENCE_ARROW_PIXELS / fastest
                : DEFAULT_PIXELS_PER_VELOCITY;
    }

    /** Rounds a positive length to 1, 2, 5 or 10 times a power of ten. */
    private static double niceLength(double x) {
        if (!(x > 0.0) || !Double.isFinite(x)) {
            return 1.0;
        }
        double exponent = Math.floor(Math.log10(x));
        double fraction = x / Math.pow(10.0, exponent);
        double nice = fraction < 1.5 ? 1.0 : fraction < 3.5 ? 2.0 : fraction < 7.5 ? 5.0 : 10.0;
        return nice * Math.pow(10.0, exponent);
    }

    private void drawSelectionPanel(GraphicsContext g) {
        State s = latest.state();
        if (selected >= s.bodyCount()) {
            return;
        }
        Vec3 p = s.position(selected);
        Vec3 v = s.velocity(selected);
        String[] lines = {
                String.format(Locale.ROOT, "body %d   m = %g", selected + 1, system.mass(selected)),
                String.format(Locale.ROOT, "pos  (%+.6f, %+.6f)", p.x(), p.y()),
                String.format(Locale.ROOT, "vel  (%+.6f, %+.6f)", v.x(), v.y()),
                String.format(Locale.ROOT, "|v|  %.6f", v.norm())
        };
        g.setFill(PANEL);
        g.fillRoundRect(12, 12, 290, 76, 8, 8);
        g.setStroke(colour(selected));
        g.setLineWidth(1.2);
        g.strokeRoundRect(12, 12, 290, 76, 8, 8);
        g.setFill(Color.web("#e5edf8"));
        g.setFont(Font.font("Consolas", 12));
        for (int i = 0; i < lines.length; i++) {
            g.fillText(lines[i], 22, 30 + 16 * i);
        }
    }
}
