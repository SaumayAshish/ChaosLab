package dev.chaoslab.ui;

/**
 * The arithmetic behind the orbit view's "relative to body k" frames, free of any JavaFX type so it can be tested.
 *
 * <p>In a frame attached to body {@code f} every position is drawn minus the position of body {@code f} <em>at the
 * same instant</em>. For a trail that means subtracting the frame body's position at the same sample, not at the
 * latest one: subtracting the latest position from the whole old trail would only shift the inertial picture. With
 * the right subtraction, the Moon's trail in an Earth frame is the loop it really makes around the Earth.
 *
 * <p>A frame of {@code -1} means the ordinary inertial picture and changes nothing. The frame is a change of
 * viewpoint only; it is a translation, so the physics shown is the same, and the frame body itself stays at the
 * centre by construction (its own motion and, for the reference rings, its own error are not visible).
 *
 * <h2>Choosing what to fit</h2>
 * In a frame, fitting the view to every trail can hide the interesting part: the Sun's relative path in an Earth
 * frame is 400 times larger than the Moon's loop. {@link #bounds} therefore fits the bodies whose largest distance
 * from the frame body is at most {@link #HIERARCHY_FACTOR} times that of the closest companion (the closest is the
 * one with the smallest largest-distance), plus the frame body itself. Bodies beyond that are still drawn where they
 * are, possibly off screen; the zoom and pan controls reach them. For similar-scale systems (three equal masses)
 * every body is included.
 */
public final class ReferenceFrame {

    /** Bodies up to this many times as far (at their farthest) as the closest companion are fitted. */
    public static final double HIERARCHY_FACTOR = 10.0;

    private ReferenceFrame() {
    }

    /** x of sample {@code k} of {@code body} relative to the frame body at that sample (frame -1: unchanged). */
    public static double x(TrailBuffer t, int body, int k, int frame) {
        return frame < 0 ? t.x(body, k) : t.x(body, k) - t.x(frame, k);
    }

    /** y of sample {@code k} of {@code body} relative to the frame body at that sample (frame -1: unchanged). */
    public static double y(TrailBuffer t, int body, int k, int frame) {
        return frame < 0 ? t.y(body, k) : t.y(body, k) - t.y(frame, k);
    }

    /**
     * For each body, the largest distance in the x-y plane from the frame body over all stored samples (0 for the
     * frame body itself). Empty buffer: all zeros.
     */
    public static double[] largestDistances(TrailBuffer t, int frame) {
        check(t, frame);
        double[] far = new double[t.bodyCount()];
        for (int body = 0; body < far.length; body++) {
            if (body == frame) {
                continue;
            }
            for (int k = 0; k < t.size(); k++) {
                far[body] = Math.max(far[body], Math.hypot(x(t, body, k, frame), y(t, body, k, frame)));
            }
        }
        return far;
    }

    /** Which bodies the fit includes: the frame body, and every body within the hierarchy factor of the closest. */
    public static boolean[] included(TrailBuffer t, int frame) {
        check(t, frame);
        boolean[] in = new boolean[t.bodyCount()];
        if (frame < 0) {
            java.util.Arrays.fill(in, true);
            return in;
        }
        double[] far = largestDistances(t, frame);
        double closest = Double.POSITIVE_INFINITY;
        for (int body = 0; body < far.length; body++) {
            if (body != frame) {
                closest = Math.min(closest, far[body]);
            }
        }
        for (int body = 0; body < far.length; body++) {
            in[body] = body == frame || far[body] <= HIERARCHY_FACTOR * closest;
        }
        return in;
    }

    /**
     * Bounds {minX, maxX, minY, maxY} of the included bodies' trails in the frame, written into {@code out}.
     * Returns false (leaving {@code out} untouched) when the buffer is empty. For frame -1 this is exactly
     * {@link TrailBuffer#bounds}.
     */
    public static boolean bounds(TrailBuffer t, int frame, double[] out) {
        check(t, frame);
        if (out == null || out.length != 4) {
            throw new IllegalArgumentException("out must have length 4");
        }
        if (frame < 0) {
            return t.bounds(out);
        }
        if (t.size() == 0) {
            return false;
        }
        boolean[] in = included(t, frame);
        double minX = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        for (int body = 0; body < in.length; body++) {
            if (!in[body]) {
                continue;
            }
            for (int k = 0; k < t.size(); k++) {
                double px = x(t, body, k, frame);
                double py = y(t, body, k, frame);
                minX = Math.min(minX, px);
                maxX = Math.max(maxX, px);
                minY = Math.min(minY, py);
                maxY = Math.max(maxY, py);
            }
        }
        out[0] = minX;
        out[1] = maxX;
        out[2] = minY;
        out[3] = maxY;
        return true;
    }

    private static void check(TrailBuffer t, int frame) {
        if (t == null) {
            throw new IllegalArgumentException("trail buffer is null");
        }
        if (frame < -1 || frame >= t.bodyCount()) {
            throw new IllegalArgumentException("frame must be -1 or a body index below " + t.bodyCount() + ", got "
                    + frame);
        }
    }
}
