package dev.chaoslab.ui;

/**
 * Bounded history of the position gap between the displayed run and the high-accuracy reference, together with the
 * reference's own uncertainty, built for plotting a whole run in constant memory. It works exactly like
 * {@link TelemetryHistory}: while there is room every sample is stored; when the buffer is full, neighbouring entries
 * are merged pairwise and the {@linkplain #stride() stride} doubles, so the buffer always spans the whole run.
 *
 * <p>A merged entry keeps the <em>largest</em> value of each series and the time of the latest sample, so a sudden
 * jump in the gap (an under-resolved close encounter) can never be thinned away.
 *
 * <p>Both series are lengths divided by the system's characteristic length, so they are dimensionless and the same
 * axis serves every unit system. The uncertainty may be unknown (NaN) at some times, for example when the second
 * reference failed first; a merged entry is unknown only if all of its samples were. A sample whose gap is not finite
 * (the reference could not reach that time) is ignored.
 *
 * <p>Reset detection: a sample whose time is earlier than the previous one means the run was reset, so the history is
 * cleared and restarted. A sample with the same time as the previous one carries no new information (for example a
 * pause) and is ignored.
 *
 * <p>Not thread-safe; use from the JavaFX application thread only.
 */
public final class GapHistory {

    private final int capacity;
    private final double[] time;
    private final double[] gap;
    private final double[] uncertainty;

    private int size;
    private int stride = 1;
    private int lastFill;
    private boolean hasLast;
    private double lastTime;
    private long accepted;
    private double startTime;

    /**
     * @param capacity maximum number of stored entries; even and at least 4
     */
    public GapHistory(int capacity) {
        if (capacity < 4 || capacity % 2 != 0) {
            throw new IllegalArgumentException("capacity must be even and >= 4, got " + capacity);
        }
        this.capacity = capacity;
        this.time = new double[capacity];
        this.gap = new double[capacity];
        this.uncertainty = new double[capacity];
    }

    /**
     * Offers one sample.
     *
     * @param t           time in display units
     * @param relativeGap gap divided by the characteristic length; negative values are taken as magnitudes
     * @param relativeUncertainty reference uncertainty divided by the characteristic length, or NaN if unknown
     */
    public void offer(double t, double relativeGap, double relativeUncertainty) {
        if (!Double.isFinite(t) || !Double.isFinite(relativeGap)) {
            return;
        }
        if (hasLast && t < lastTime) {
            clear();
        } else if (hasLast && t == lastTime) {
            return;
        }
        hasLast = true;
        lastTime = t;
        if (size == 0) {
            startTime = t;
        }
        accepted++;
        double g = Math.abs(relativeGap);
        double u = Double.isFinite(relativeUncertainty) ? Math.abs(relativeUncertainty) : Double.NaN;

        if (size > 0 && lastFill < stride) { // the newest entry still has room: fold the sample into it
            int i = size - 1;
            time[i] = t;
            gap[i] = Math.max(gap[i], g);
            uncertainty[i] = largest(uncertainty[i], u);
            lastFill++;
            return;
        }
        if (size == capacity) {
            compact();
        }
        time[size] = t;
        gap[size] = g;
        uncertainty[size] = u;
        size++;
        lastFill = 1;
    }

    /** The larger of two values, where NaN means "unknown" and loses against any number. */
    private static double largest(double a, double b) {
        if (Double.isNaN(a)) {
            return b;
        }
        if (Double.isNaN(b)) {
            return a;
        }
        return Math.max(a, b);
    }

    private void compact() {
        int half = size / 2;
        for (int k = 0; k < half; k++) {
            int a = 2 * k;
            int b = a + 1;
            time[k] = time[b];
            gap[k] = Math.max(gap[a], gap[b]);
            uncertainty[k] = largest(uncertainty[a], uncertainty[b]);
        }
        size = half;
        stride *= 2;
    }

    /** Discards everything and starts again (the capacity is kept). */
    public void clear() {
        size = 0;
        stride = 1;
        lastFill = 0;
        hasLast = false;
        lastTime = 0.0;
        accepted = 0;
        startTime = 0.0;
    }

    /** Number of stored entries, at most the capacity. */
    public int size() {
        return size;
    }

    public int capacity() {
        return capacity;
    }

    /** Number of offered samples each entry (except possibly the newest) stands for. */
    public int stride() {
        return stride;
    }

    /** Number of samples accepted since the last clear. */
    public long acceptedSamples() {
        return accepted;
    }

    /** Time of the first sample since the last clear; 0 if there is none. */
    public double startTime() {
        return startTime;
    }

    /** Time of the latest sample covered by entry {@code i}. */
    public double time(int i) {
        check(i);
        return time[i];
    }

    /** Largest relative gap over the samples covered by entry {@code i}. */
    public double gap(int i) {
        check(i);
        return gap[i];
    }

    /** Largest relative reference uncertainty over the samples covered by entry {@code i}, or NaN if unknown. */
    public double uncertainty(int i) {
        check(i);
        return uncertainty[i];
    }

    private void check(int i) {
        if (i < 0 || i >= size) {
            throw new IndexOutOfBoundsException("index " + i + ", size " + size);
        }
    }
}
