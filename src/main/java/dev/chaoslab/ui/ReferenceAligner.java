package dev.chaoslab.ui;

/**
 * Puts the reference ("ghost") where the displayed bodies are, plus the error that was measured.
 *
 * <p>The reference monitor compares the reference with the positions the window sent it for one simulation time
 * {@code t}, and answers a few frames later, when the displayed bodies have already moved on. Drawing the reference at
 * its own time {@code t} would therefore place each ghost ring a visible distance away from its body even when the
 * numerical error is far below a pixel: the offset would be lag, not error. This class keeps the positions that were
 * sent and returns, for a reading made at time {@code t},
 * <pre>
 *     current displayed positions + (reference at t - displayed positions at t),
 * </pre>
 * so the offset between a ring and its body is exactly the measured error vector. The error vector is a few frames
 * old, which matters only if the error changes appreciably within a few frames; its growth rate is the Lyapunov
 * exponent or less, so over 50 ms it changes by far less than a pixel.
 *
 * <p>If the sent positions for that time are no longer remembered, or anything has the wrong length or is not finite,
 * the reference positions are returned unchanged (a copy), which is the honest but lagging picture.
 *
 * <p>Not thread-safe; use from the JavaFX application thread only.
 */
public final class ReferenceAligner {

    private final double[] times;
    private final double[][] positions;
    private int size;
    private int next;
    private double lastTime = Double.NaN;

    /** @param capacity how many recent submissions are remembered; at least 1 */
    public ReferenceAligner(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be >= 1, got " + capacity);
        }
        this.times = new double[capacity];
        this.positions = new double[capacity][];
    }

    /**
     * Remembers the positions sent to the monitor for simulation time {@code time}. A time earlier than the previous
     * one means the run was reset, so everything older is forgotten. The array is copied.
     */
    public void remember(double time, double[] sent) {
        if (!Double.isFinite(time) || sent == null) {
            return;
        }
        if (!Double.isNaN(lastTime) && time < lastTime) {
            clear();
        }
        lastTime = time;
        times[next] = time;
        positions[next] = sent.clone();
        next = (next + 1) % times.length;
        size = Math.min(size + 1, times.length);
    }

    /** Forgets everything. */
    public void clear() {
        size = 0;
        next = 0;
        lastTime = Double.NaN;
        java.util.Arrays.fill(positions, null);
    }

    /**
     * The reference positions to draw.
     *
     * @param time      simulation time of the reading
     * @param reference reference positions at {@code time}, or null
     * @param current   displayed positions now
     * @return a new array, or null if {@code reference} is null
     */
    public double[] aligned(double time, double[] reference, double[] current) {
        if (reference == null) {
            return null;
        }
        double[] sent = lookup(time);
        if (sent == null || current == null || sent.length != reference.length || current.length != reference.length) {
            return reference.clone();
        }
        double[] result = new double[reference.length];
        for (int i = 0; i < result.length; i++) {
            result[i] = current[i] + (reference[i] - sent[i]);
            if (!Double.isFinite(result[i])) {
                return reference.clone();
            }
        }
        return result;
    }

    /** The newest remembered positions with exactly this time, or null. */
    private double[] lookup(double time) {
        for (int k = 1; k <= size; k++) {
            int i = (next - k + times.length) % times.length;
            if (times[i] == time) {
                return positions[i];
            }
        }
        return null;
    }
}
