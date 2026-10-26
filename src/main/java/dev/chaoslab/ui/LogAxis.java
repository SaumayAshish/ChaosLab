package dev.chaoslab.ui;

/**
 * A logarithmic axis covering whole decades, {@code 10^minExponent} to {@code 10^maxExponent}, for plotting
 * quantities that span many orders of magnitude (conservation drifts run from rounding level near 1e-16 up to order
 * one or more when an integrator fails).
 *
 * <p>The default range is 1e-17 to 1e0, so that double-precision rounding (about 1.1e-16) and the drift warning level
 * both sit inside the frame from the first moment. It widens, never narrows, to include the data.
 * Values at or below the lower bound (including exact zero) are drawn on the lower bound.
 *
 * @param minExponent decimal exponent of the lower end
 * @param maxExponent decimal exponent of the upper end, greater than {@code minExponent}
 */
public record LogAxis(int minExponent, int maxExponent) {

    public static final int DEFAULT_MIN_EXPONENT = -17;
    public static final int DEFAULT_MAX_EXPONENT = 0;

    public LogAxis {
        if (maxExponent <= minExponent) {
            throw new IllegalArgumentException("maxExponent must exceed minExponent, got " + minExponent + " and "
                    + maxExponent);
        }
    }

    /**
     * The default range, widened as needed to contain the data. Non-positive or non-finite arguments (no data of that
     * kind yet) are ignored.
     *
     * @param smallestPositive smallest strictly positive value that must be visible
     * @param largest          largest value that must be visible
     */
    public static LogAxis covering(double smallestPositive, double largest) {
        int lo = DEFAULT_MIN_EXPONENT;
        int hi = DEFAULT_MAX_EXPONENT;
        if (smallestPositive > 0.0 && Double.isFinite(smallestPositive)) {
            lo = Math.min(lo, (int) Math.floor(Math.log10(smallestPositive)));
        }
        if (largest > 0.0 && Double.isFinite(largest)) {
            hi = Math.max(hi, (int) Math.ceil(Math.log10(largest)));
        }
        return new LogAxis(lo, hi);
    }

    /** Number of decades shown. */
    public int decades() {
        return maxExponent - minExponent;
    }

    /**
     * Position of a value on the axis: 0 at the lower end, 1 at the upper end, clamped to [0, 1]. NaN maps to 0.
     */
    public double fraction(double value) {
        if (!(value > 0.0)) {
            return 0.0;
        }
        double f = (Math.log10(value) - minExponent) / decades();
        return Math.max(0.0, Math.min(1.0, f));
    }

    /**
     * Exponents at which to draw labelled grid lines, from the top downwards, spaced by a whole number of decades so
     * that there are at most {@code maxTicks} of them. The top exponent is always included.
     */
    public int[] tickExponents(int maxTicks) {
        if (maxTicks < 2) {
            throw new IllegalArgumentException("maxTicks must be >= 2, got " + maxTicks);
        }
        int step = 1;
        while (decades() / step + 1 > maxTicks) {
            step++;
        }
        int count = decades() / step + 1;
        int[] ticks = new int[count];
        for (int i = 0; i < count; i++) {
            ticks[i] = maxExponent - i * step;
        }
        return ticks;
    }

    /** Label for a tick: "1" for exponent 0, otherwise "1e-3" style. */
    public static String label(int exponent) {
        return exponent == 0 ? "1" : "1e" + exponent;
    }
}
