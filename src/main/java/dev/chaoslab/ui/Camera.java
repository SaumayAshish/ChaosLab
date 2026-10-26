package dev.chaoslab.ui;

/**
 * The mapping between world coordinates (physics units, y up) and screen pixels (origin top-left, y down).
 *
 * <p>This class is deliberately free of any JavaFX type so that the arithmetic that decides what the user sees
 * can be unit-tested. It is not thread-safe; use it from the JavaFX application thread only.
 *
 * <pre>
 *     screenX = width/2  + (worldX - centerX) * scale
 *     screenY = height/2 - (worldY - centerY) * scale      (the minus flips y so that physics "up" is screen "up")
 * </pre>
 *
 * {@code scale} is in pixels per world unit and is clamped to [{@value #MIN_SCALE}, {@value #MAX_SCALE}].
 */
public final class Camera {

    public static final double MIN_SCALE = 1e-9;
    public static final double MAX_SCALE = 1e9;
    public static final double DEFAULT_SCALE = 150.0;

    private double centerX;
    private double centerY;
    private double scale = DEFAULT_SCALE;
    private double width = 1.0;
    private double height = 1.0;

    // ---------------------------------------------------------------- viewport

    /** Sets the size of the drawing area in pixels. Both must be finite and &gt; 0. */
    public void setViewport(double widthPixels, double heightPixels) {
        if (!(widthPixels > 0.0) || !(heightPixels > 0.0) || !Double.isFinite(widthPixels)
                || !Double.isFinite(heightPixels)) {
            throw new IllegalArgumentException("Viewport must be finite and > 0, got " + widthPixels + " x "
                    + heightPixels);
        }
        this.width = widthPixels;
        this.height = heightPixels;
    }

    public double width() {
        return width;
    }

    public double height() {
        return height;
    }

    public double centerX() {
        return centerX;
    }

    public double centerY() {
        return centerY;
    }

    /** Pixels per world unit. */
    public double scale() {
        return scale;
    }

    // ---------------------------------------------------------------- transforms

    public double worldToScreenX(double worldX) {
        return width / 2.0 + (worldX - centerX) * scale;
    }

    public double worldToScreenY(double worldY) {
        return height / 2.0 - (worldY - centerY) * scale;
    }

    public double screenToWorldX(double screenX) {
        return centerX + (screenX - width / 2.0) / scale;
    }

    public double screenToWorldY(double screenY) {
        return centerY - (screenY - height / 2.0) / scale;
    }

    // ---------------------------------------------------------------- navigation

    /** Back to the origin at the default scale. */
    public void reset() {
        centerX = 0.0;
        centerY = 0.0;
        scale = DEFAULT_SCALE;
    }

    /**
     * Zooms by {@code factor} (&gt; 1 magnifies) keeping the world point currently under the given screen position
     * fixed, which is what makes zooming with the mouse wheel feel anchored to the cursor.
     */
    public void zoomAt(double screenX, double screenY, double factor) {
        if (!(factor > 0.0) || !Double.isFinite(factor)) {
            throw new IllegalArgumentException("Zoom factor must be finite and > 0, got " + factor);
        }
        double wx = screenToWorldX(screenX);
        double wy = screenToWorldY(screenY);
        scale = Math.min(MAX_SCALE, Math.max(MIN_SCALE, scale * factor));
        centerX = wx - (screenX - width / 2.0) / scale;
        centerY = wy + (screenY - height / 2.0) / scale;
    }

    /** Drags the picture by the given number of pixels (content follows the mouse). */
    public void panByPixels(double dxPixels, double dyPixels) {
        centerX -= dxPixels / scale;
        centerY += dyPixels / scale;
    }

    /**
     * Centres the given world rectangle and chooses the largest scale at which it fits inside the viewport with
     * the given margin on every side. A zero extent in one direction (all points on a line) is handled by letting
     * the other direction decide the scale; if both extents are zero only the centre moves.
     *
     * @param marginFraction fraction of the viewport kept empty on each side, in [0, 0.45]
     */
    public void fit(double minX, double maxX, double minY, double maxY, double marginFraction) {
        if (!Double.isFinite(minX) || !Double.isFinite(maxX) || !Double.isFinite(minY) || !Double.isFinite(maxY)
                || maxX < minX || maxY < minY) {
            throw new IllegalArgumentException("Invalid bounds [" + minX + ", " + maxX + "] x [" + minY + ", "
                    + maxY + "]");
        }
        if (!(marginFraction >= 0.0) || marginFraction > 0.45) {
            throw new IllegalArgumentException("marginFraction must be in [0, 0.45], got " + marginFraction);
        }
        centerX = 0.5 * (minX + maxX);
        centerY = 0.5 * (minY + maxY);
        double spanX = maxX - minX;
        double spanY = maxY - minY;
        double usableW = width * (1.0 - 2.0 * marginFraction);
        double usableH = height * (1.0 - 2.0 * marginFraction);
        double sx = spanX > 0.0 ? usableW / spanX : Double.POSITIVE_INFINITY;
        double sy = spanY > 0.0 ? usableH / spanY : Double.POSITIVE_INFINITY;
        double best = Math.min(sx, sy);
        if (Double.isFinite(best)) {
            scale = Math.min(MAX_SCALE, Math.max(MIN_SCALE, best));
        }
    }
}
