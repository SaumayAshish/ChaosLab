package dev.chaoslab.ui;

/**
 * A fixed-capacity history of body positions (x and y only) for drawing orbit trails. When full, the oldest sample
 * is overwritten. All bodies are appended together, so they share one write position and one length.
 *
 * <p>Free of JavaFX types so it can be unit-tested. Not thread-safe; use from the JavaFX application thread only.
 * Samples are whatever the caller appends (the UI appends published snapshots), not every integration step.
 */
public final class TrailBuffer {

    private final int bodyCount;
    private final int capacity;
    private final double[] xs;
    private final double[] ys;
    private int head;   // index (within a body's row) where the next sample is written
    private int size;

    /**
     * @param bodyCount number of bodies, at least 1
     * @param capacity  samples kept per body, at least 2
     */
    public TrailBuffer(int bodyCount, int capacity) {
        if (bodyCount < 1) {
            throw new IllegalArgumentException("bodyCount must be >= 1, got " + bodyCount);
        }
        if (capacity < 2) {
            throw new IllegalArgumentException("capacity must be >= 2, got " + capacity);
        }
        this.bodyCount = bodyCount;
        this.capacity = capacity;
        this.xs = new double[bodyCount * capacity];
        this.ys = new double[bodyCount * capacity];
    }

    public int bodyCount() {
        return bodyCount;
    }

    public int capacity() {
        return capacity;
    }

    /** Number of samples currently stored per body, at most {@link #capacity()}. */
    public int size() {
        return size;
    }

    /**
     * Appends one sample for every body.
     *
     * @param flatPositions positions as a flat array (body i at 3i..3i+2); only x and y are stored
     * @throws IllegalArgumentException if the array has the wrong length or a stored coordinate is not finite;
     *                                  the buffer is unchanged in that case
     */
    public void append(double[] flatPositions) {
        if (flatPositions == null || flatPositions.length != 3 * bodyCount) {
            throw new IllegalArgumentException("Expected " + 3 * bodyCount + " position values, got "
                    + (flatPositions == null ? "null" : flatPositions.length));
        }
        for (int i = 0; i < bodyCount; i++) {
            if (!Double.isFinite(flatPositions[3 * i]) || !Double.isFinite(flatPositions[3 * i + 1])) {
                throw new IllegalArgumentException("Non-finite position for body " + i);
            }
        }
        for (int i = 0; i < bodyCount; i++) {
            xs[i * capacity + head] = flatPositions[3 * i];
            ys[i * capacity + head] = flatPositions[3 * i + 1];
        }
        head = (head + 1) % capacity;
        if (size < capacity) {
            size++;
        }
    }

    /** Forgets all samples. */
    public void clear() {
        head = 0;
        size = 0;
    }

    /** x of sample {@code k} of a body, where 0 is the oldest stored sample and {@code size() - 1} the newest. */
    public double x(int body, int k) {
        return xs[body * capacity + slot(body, k)];
    }

    /** y of sample {@code k} of a body, where 0 is the oldest stored sample and {@code size() - 1} the newest. */
    public double y(int body, int k) {
        return ys[body * capacity + slot(body, k)];
    }

    private int slot(int body, int k) {
        if (body < 0 || body >= bodyCount) {
            throw new IndexOutOfBoundsException("body " + body + " of " + bodyCount);
        }
        if (k < 0 || k >= size) {
            throw new IndexOutOfBoundsException("sample " + k + " of " + size);
        }
        // oldest sample sits at head - size (mod capacity)
        return Math.floorMod(head - size + k, capacity);
    }

    /**
     * Bounding box of every stored sample of every body.
     *
     * @param out receives {minX, maxX, minY, maxY}; must have length 4
     * @return false (leaving {@code out} untouched) if the buffer is empty
     */
    public boolean bounds(double[] out) {
        if (out == null || out.length != 4) {
            throw new IllegalArgumentException("out must have length 4");
        }
        if (size == 0) {
            return false;
        }
        double minX = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        for (int b = 0; b < bodyCount; b++) {
            int base = b * capacity;
            for (int k = 0; k < size; k++) {
                int idx = base + Math.floorMod(head - size + k, capacity);
                minX = Math.min(minX, xs[idx]);
                maxX = Math.max(maxX, xs[idx]);
                minY = Math.min(minY, ys[idx]);
                maxY = Math.max(maxY, ys[idx]);
            }
        }
        out[0] = minX;
        out[1] = maxX;
        out[2] = minY;
        out[3] = maxY;
        return true;
    }
}
