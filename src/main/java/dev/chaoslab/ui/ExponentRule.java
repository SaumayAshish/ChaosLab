package dev.chaoslab.ui;

/**
 * Decides whether the window may print a Lyapunov exponent, or must say why it does not.
 *
 * <p>The tracker reports two numbers: the running mean of the log growth (total growth divided by elapsed time) and
 * the slope of the later half of the cumulative growth. For a chaotic orbit the growth is close to linear in time,
 * so the two agree. For a regular orbit (two bodies, the figure-eight, a hierarchical system such as Sun, Earth and
 * Moon) the growth is only logarithmic, so the running mean keeps falling like 1/t and the later slope stays far
 * below it. An exponent is therefore claimed only when enough renormalisations exist and the later slope is at
 * least {@link #SLOPE_FRACTION} of the running mean.
 *
 * <h2>Measured behaviour of the rule</h2>
 * The sandbox evaluated the rule after 10, 12, ..., 90 renormalisations (fewer for the chaotic cases) on regular and
 * chaotic starts. The share of samples in which an exponent would have been claimed:
 * <pre>
 *                                  fraction 0.5   fraction 0.75   fraction 0.9
 *   regular: two bodies, circular      2 of 41        0 of 41        0 of 41
 *   regular: two bodies, e = 0.75      5 of 41        1 of 41        1 of 41
 *   regular: Sun and Earth             0 of 41        0 of 41        0 of 41
 *   regular: figure-eight              0 of 41        0 of 41        0 of 41
 *   regular: Sun, Earth and Moon      24 of 41        2 of 41        0 of 41
 *   chaotic: Lagrange, displaced 0.01 22 of 26       12 of 26        7 of 26
 *   chaotic: Lagrange, exact          11 of 11       11 of 11       11 of 11
 *   chaotic: Pythagorean 3-4-5        15 of 16       12 of 16       10 of 16
 * </pre>
 * The earlier fraction 0.5 claimed an exponent for the Sun-Earth-Moon system in more than half of the samples,
 * which is wrong. At 0.75 the rule is wrong in 3 of 205 regular samples (1.5%) and withholds an exponent in about
 * half of the chaotic samples, which is the safer error: the window then says the growth is slowing and does not
 * claim a number. No rule on two numbers can be exact; this one is a screen, not a proof of regularity.
 */
public final class ExponentRule {

    /** Fewer renormalisations than this and the value is shown as provisional. */
    public static final int MIN_RENORMALISATIONS = 10;

    /** The later slope must be at least this fraction of the running mean before an exponent is claimed. */
    public static final double SLOPE_FRACTION = 0.75;

    /** What the window may say. */
    public enum Verdict {
        /** No renormalisation has completed yet. */
        NO_DATA,
        /** Some data, but fewer than {@link #MIN_RENORMALISATIONS} renormalisations. */
        PROVISIONAL,
        /** Enough data, but the later growth is too slow compared with the running mean (or not measurable). */
        SLOWING,
        /** The growth is sustained: an exponent may be claimed as a finite-time estimate. */
        CLAIMED
    }

    private ExponentRule() {
    }

    /**
     * @param renormalisations completed renormalisations
     * @param running          running mean of the log growth per time unit (any consistent time unit)
     * @param slope            slope of the later half of the cumulative growth, same unit, or NaN
     */
    public static Verdict judge(int renormalisations, double running, double slope) {
        if (renormalisations <= 0) {
            return Verdict.NO_DATA;
        }
        if (renormalisations < MIN_RENORMALISATIONS) {
            return Verdict.PROVISIONAL;
        }
        if (Double.isNaN(slope) || Double.isNaN(running) || !(slope >= SLOPE_FRACTION * running)) {
            return Verdict.SLOWING;
        }
        return Verdict.CLAIMED;
    }
}
