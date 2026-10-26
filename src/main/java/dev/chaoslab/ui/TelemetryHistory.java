package dev.chaoslab.ui;

/**
 * Bounded history of the conservation-law drifts, built for plotting a whole run in constant memory.
 *
 * <h2>How a long run fits in a fixed buffer</h2>
 * Samples are offered one per published snapshot. While there is room each sample is stored as it is. When the buffer
 * is full, neighbouring entries are merged pairwise and from then on every stored entry stands for twice as many
 * offered samples (the {@linkplain #stride() stride} doubles). The buffer therefore never holds more than its capacity,
 * yet always spans the whole run from the first sample to the latest.
 *
 * <h2>Why the maximum, not the average</h2>
 * A merged entry keeps the <em>largest</em> of each drift over the samples it covers, and the time of the latest one.
 * Drift spikes (a close encounter with a fixed step injects energy in a few steps) are exactly what the plot exists
 * to show; averaging or skipping samples would smooth or lose them. No spike survives or disappears depending on
 * the stride: the largest value ever offered is always still present in the buffer.
 *
 * <p>All three quantities are stored as magnitudes ({@code |dE/E0|}, {@code dP/Pscale}, {@code dL/|L0|}) because they
 * are drawn on a logarithmic axis; the sign of the energy drift is shown in the numeric read-out instead.
 *
 * <p>Reset detection: a sample whose step count is lower than the previous one means the simulation was reset (or
 * replaced), so the history is cleared and restarted. A sample with the same step count as the previous one carries
 * no new information (for example a pause) and is ignored. Samples with a non-finite component are ignored.
 *
 * <p>Not thread-safe; use from the JavaFX application thread only.
 */
public final class TelemetryHistory {

    private final int capacity;
    private final double[] time;
    private final double[] energy;
    private final double[] momentum;
    private final double[] angularMomentum;

    private int size;
    private int stride = 1;
    private int lastFill;
    private long lastStep = -1;
    private long accepted;
    private double startTime;

    /**
     * @param capacity maximum number of stored entries; even and at least 4
     */
    public TelemetryHistory(int capacity) {
        if (capacity < 4 || capacity % 2 != 0) {
            throw new IllegalArgumentException("capacity must be even and >= 4, got " + capacity);
        }
        this.capacity = capacity;
        this.time = new double[capacity];
        this.energy = new double[capacity];
        this.momentum = new double[capacity];
        this.angularMomentum = new double[capacity];
    }

    /**
     * Offers one sample. Drift values are taken as magnitudes.
     *
     * @param stepCount steps taken since the last reset, used only to detect resets and duplicates
     */
    public void offer(long stepCount, double t, double relativeEnergy, double relativeMomentum,
            double relativeAngularMomentum) {
        if (!Double.isFinite(t) || !Double.isFinite(relativeEnergy) || !Double.isFinite(relativeMomentum)
                || !Double.isFinite(relativeAngularMomentum)) {
            return;
        }
        if (stepCount < lastStep) {
            clear();
        } else if (stepCount == lastStep && size > 0) {
            return;
        }
        lastStep = stepCount;
        if (size == 0) {
            startTime = t;
        }
        accepted++;
        double e = Math.abs(relativeEnergy);
        double p = Math.abs(relativeMomentum);
        double l = Math.abs(relativeAngularMomentum);

        if (size > 0 && lastFill < stride) { // the newest entry still has room: fold the sample into it
            int i = size - 1;
            time[i] = t;
            energy[i] = Math.max(energy[i], e);
            momentum[i] = Math.max(momentum[i], p);
            angularMomentum[i] = Math.max(angularMomentum[i], l);
            lastFill++;
            return;
        }
        if (size == capacity) {
            compact();
        }
        time[size] = t;
        energy[size] = e;
        momentum[size] = p;
        angularMomentum[size] = l;
        size++;
        lastFill = 1;
    }

    /** Merges neighbouring pairs, halving the number of entries and doubling the stride. */
    private void compact() {
        int half = size / 2;
        for (int k = 0; k < half; k++) {
            int a = 2 * k;
            int b = a + 1;
            time[k] = time[b];
            energy[k] = Math.max(energy[a], energy[b]);
            momentum[k] = Math.max(momentum[a], momentum[b]);
            angularMomentum[k] = Math.max(angularMomentum[a], angularMomentum[b]);
        }
        size = half;
        stride *= 2;
    }

    /** Discards everything and starts again (the capacity is kept). */
    public void clear() {
        size = 0;
        stride = 1;
        lastFill = 0;
        lastStep = -1;
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

    /** Largest {@code |dE/E0|} over the samples covered by entry {@code i}. */
    public double energy(int i) {
        check(i);
        return energy[i];
    }

    /** Largest {@code dP/Pscale} over the samples covered by entry {@code i}. */
    public double momentum(int i) {
        check(i);
        return momentum[i];
    }

    /** Largest {@code dL/|L0|} over the samples covered by entry {@code i}. */
    public double angularMomentum(int i) {
        check(i);
        return angularMomentum[i];
    }

    private void check(int i) {
        if (i < 0 || i >= size) {
            throw new IndexOutOfBoundsException("index " + i + ", size " + size);
        }
    }
}
