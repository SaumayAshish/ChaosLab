package dev.chaoslab.ui;

import dev.chaoslab.analysis.TwinRun;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Turns the numbers the window has measured into a short list of plain statements about what the person is looking at.
 * Pure: it takes {@link Facts} and returns {@link Finding}s, so every sentence can be tested and nothing is said that a
 * measured number does not support.
 *
 * <h2>How it reasons</h2>
 * <ul>
 *   <li><b>Conservation.</b> The exact motion conserves energy, momentum and angular momentum (also with softening,
 *       for the softened law). So any drift is numerical. The worst of the three drifts is banded: up to
 *       {@value #CONSERVED} it counts as conserved, up to {@value #WARNING_DRIFT} as small but visible, above that as
 *       a warning. These bands are rules of thumb for this application, not laws.</li>
 *   <li><b>Accuracy.</b> The gap to the high-accuracy reference is the real error of the displayed positions. It is
 *       compared with the reference's own uncertainty first: a gap at or below it cannot be resolved.</li>
 *   <li><b>Conserved is not accurate.</b> When the worst drift is at most {@value #CONSERVED} but the gap is at least
 *       {@value #NOTABLE_GAP} of the system size (so the gap is at least a thousand times the drift), the bodies are
 *       on a nearly energy-conserving path that is not the true one. The statement says so with both numbers.</li>
 *   <li><b>Sensitivity.</b> The Lyapunov decision of {@link ExponentRule} is passed in, not recomputed. Only when an
 *       exponent is claimed is a predictability time given: {@code ln(1e13) / lambda}, the time for an error of 1e-13
 *       of the system size (the reference tolerance) to grow to the size of the system <em>if the growth stayed
 *       exponential</em>, which the statement says.</li>
 *   <li><b>Twin run.</b> The separation produced by the physical nudge is compared with the numerical gap, so the
 *       person sees whether the integrator's error is smaller or larger than a start known only to
 *       {@code TwinRun.NUDGE} of the system size.</li>
 * </ul>
 * Unknown quantities are NaN and the corresponding statement is left out or says it is waiting.
 */
public final class Explanation {

    /** Worst drift up to this is called conserved. */
    public static final double CONSERVED = 1e-6;
    /** Worst drift above this is a warning. */
    public static final double WARNING_DRIFT = 1e-3;
    /** The gap must be at least this (fraction of the system size) for "conserved is not accurate". */
    public static final double NOTABLE_GAP = 1e-3;
    /** A gap or separation at or above this fraction of the system size means the runs are fully apart. */
    public static final double APART = 0.1;
    /** A reference uncertainty above this is worth a warning. */
    public static final double REFERENCE_WARNING = 1e-3;
    /** The error whose growth the predictability time follows: the reference tolerance. */
    public static final double REFERENCE_ERROR = 1e-13;

    /** How much attention a statement needs. */
    public enum Level { OK, INFO, WARNING }

    /** One statement: a short heading and one or two sentences. */
    public record Finding(Level level, String heading, String text) {
    }

    /**
     * What the window knows right now. Times and rates are in the displayed time unit ({@code timeUnit}). Gaps,
     * uncertainties and the twin separation are fractions of the system size. Drifts are magnitudes. NaN means
     * unknown.
     *
     * @param failed          the run stopped on a numerical failure
     * @param failure         its explanation (used only if failed)
     * @param adaptive        the integrator is adaptive
     * @param timeUnit        "t" or "d"
     * @param energy          |dE/E0|
     * @param momentum        dP/Pscale
     * @param angularMomentum dL/|L0|
     * @param softening       Plummer softening, 0 for exact Newtonian gravity
     * @param gap             gap to the reference
     * @param uncertainty     the reference's own uncertainty
     * @param referenceFailure why the reference ended, or null
     * @param verdict         the {@link ExponentRule} decision
     * @param lambda          running Lyapunov mean per time unit
     * @param renormalisations completed renormalisations
     * @param twinSeparation  separation of the twin run
     */
    public record Facts(boolean failed, String failure, boolean adaptive, String timeUnit, double energy,
                        double momentum, double angularMomentum, double softening, double gap, double uncertainty,
                        String referenceFailure, ExponentRule.Verdict verdict, double lambda, int renormalisations,
                        double twinSeparation) {
    }

    private Explanation() {
    }

    /** The statements for these facts, most important context first. */
    public static List<Finding> explain(Facts f) {
        List<Finding> out = new ArrayList<>();
        if (f.failed()) {
            out.add(new Finding(Level.WARNING, "The run stopped",
                    f.failure() + " Reset to start again; an adaptive integrator or a smaller step may get past it."));
        }
        out.add(conservation(f));
        accuracy(f, out);
        conservedIsNotAccurate(f, out);
        sensitivity(f, out);
        twin(f, out);
        referenceLimit(f, out);
        if (f.softening() > 0.0) {
            out.add(new Finding(Level.INFO, "Softened gravity", String.format(Locale.ROOT,
                    "Softening %s is on, so the force is not Newtonian. The conservation figures above hold for the"
                            + " softened law, and the reference follows the same softened law.",
                    shortNumber(f.softening()))));
        }
        return out;
    }

    // ---------------------------------------------------------------- statements

    private static double worstDrift(Facts f) {
        return Math.max(f.energy(), Math.max(f.momentum(), f.angularMomentum()));
    }

    private static Finding conservation(Facts f) {
        double worst = worstDrift(f);
        String which = f.energy() >= f.momentum() && f.energy() >= f.angularMomentum() ? "energy"
                : f.angularMomentum() >= f.momentum() ? "angular momentum" : "momentum";
        String numbers = String.format(Locale.ROOT, "energy %.1e, momentum %.1e, angular momentum %.1e", f.energy(),
                f.momentum(), f.angularMomentum());
        if (worst <= CONSERVED) {
            return new Finding(Level.OK, "Conservation", "Energy, momentum and angular momentum are conserved to "
                    + digits(worst) + " (" + numbers + "). The exact motion conserves all three, so what remains is"
                    + " numerical.");
        }
        if (worst <= WARNING_DRIFT) {
            return new Finding(Level.INFO, "Conservation", String.format(Locale.ROOT,
                    "The worst drift is %.1e of the starting size, in %s (%s). The exact motion conserves all three, so"
                            + " this is numerical error, small but visible.", worst, which, numbers));
        }
        return new Finding(Level.WARNING, "Conservation", String.format(Locale.ROOT,
                "The %s has drifted by %.1e of its starting size (%s). The exact motion conserves all three, so this is"
                        + " numerical error%s.",
                which, worst, numbers, f.adaptive() ? "" : " (typically a close encounter with a fixed step; an"
                        + " adaptive Dormand-Prince option usually avoids it)"));
    }

    private static void accuracy(Facts f, List<Finding> out) {
        if (f.referenceFailure() != null) {
            out.add(new Finding(Level.INFO, "Accuracy", "There is no reference at this time (" + f.referenceFailure()
                    + "), so the displayed run cannot be checked from here on."));
            return;
        }
        double gap = f.gap();
        if (Double.isNaN(gap)) {
            out.add(new Finding(Level.INFO, "Accuracy", "Waiting for the first comparison with the reference."));
            return;
        }
        if (gap == 0.0) {
            out.add(new Finding(Level.OK, "Accuracy", "The displayed run is identical to the reference (the starting"
                    + " state)."));
            return;
        }
        double u = f.uncertainty();
        if (!Double.isNaN(u) && gap <= u) {
            out.add(new Finding(Level.INFO, "Accuracy", String.format(Locale.ROOT,
                    "The gap to the reference (%.1e of the system size) is at or below the reference's own"
                            + " uncertainty (%.1e), so the displayed error cannot be resolved.", gap, u)));
            return;
        }
        if (gap >= APART) {
            out.add(new Finding(Level.WARNING, "Accuracy", String.format(Locale.ROOT,
                    "The displayed bodies are %.2f of the system size from where the reference puts them: the run"
                            + " has left the true orbit and individual positions no longer mean anything.%s", gap,
                    f.adaptive() ? "" : " A smaller step or an adaptive integrator keeps it on the orbit longer.")));
            return;
        }
        out.add(new Finding(gap < NOTABLE_GAP ? Level.OK : Level.INFO, "Accuracy", String.format(Locale.ROOT,
                "The displayed positions agree with the reference to about %s (gap %.1e of the system size).",
                digitsOf(gap), gap)));
    }

    private static void conservedIsNotAccurate(Facts f, List<Finding> out) {
        double worst = worstDrift(f);
        double gap = f.gap();
        if (f.referenceFailure() != null || Double.isNaN(gap) || worst > CONSERVED || gap < NOTABLE_GAP) {
            return;
        }
        out.add(new Finding(Level.INFO, "Conserved is not accurate", String.format(Locale.ROOT,
                "The worst conservation drift is only %.1e, yet the bodies are %.1e of the system size from the"
                        + " reference. A method can keep the invariants nearly constant and still put the bodies at"
                        + " the wrong place along the orbit, so conservation alone does not show the orbit is right.",
                worst, gap)));
    }

    private static void sensitivity(Facts f, List<Finding> out) {
        String unit = f.timeUnit();
        switch (f.verdict()) {
            case NO_DATA -> out.add(new Finding(Level.INFO, "Sensitivity",
                    "Collecting data: no Lyapunov estimate yet."));
            case PROVISIONAL -> out.add(new Finding(Level.INFO, "Sensitivity", String.format(Locale.ROOT,
                    "Too early to say: %d of %d renormalisations. A regular orbit also starts with a positive"
                            + " finite-time value and then falls like 1/t.", f.renormalisations(),
                    ExponentRule.MIN_RENORMALISATIONS)));
            case SLOWING -> out.add(new Finding(Level.INFO, "Sensitivity",
                    "No exponent is claimed: the recent growth is slower than the running mean. That is how a regular"
                            + " orbit looks; a chaotic orbit that has not converged can look the same, so this is a"
                            + " screen, not a proof of regularity."));
            case CLAIMED -> {
                double lambda = f.lambda();
                if (lambda > 0.0) {
                    out.add(new Finding(Level.INFO, "Sensitivity", String.format(Locale.ROOT,
                            "Nearby starts separate exponentially, with a finite-time exponent of %s per %s"
                                    + " (e-folding time %s %s). If that growth stayed exponential, an error of 1e-13"
                                    + " of the system size would reach the size of the system after about %s %s;"
                                    + " after roughly that time even a very accurate integrator can no longer be"
                                    + " trusted to follow this orbit.",
                            shortNumber(lambda), unit, shortNumber(1.0 / lambda), unit,
                            shortNumber(Math.log(1.0 / REFERENCE_ERROR) / lambda), unit)));
                } else {
                    out.add(new Finding(Level.INFO, "Sensitivity", "An exponent was claimed but it is not positive,"
                            + " so no growth time can be given."));
                }
            }
        }
    }

    private static void twin(Facts f, List<Finding> out) {
        double sep = f.twinSeparation();
        if (Double.isNaN(sep)) {
            return;
        }
        double u = f.uncertainty();
        if (!Double.isNaN(u) && sep <= u) {
            out.add(new Finding(Level.INFO, "Twin run", String.format(Locale.ROOT,
                    "The twin separation (%.1e of the system size) is at or below the reference's own uncertainty"
                            + " (%.1e), so it is not resolved.", sep, u)));
            return;
        }
        String head = String.format(Locale.ROOT,
                "Moving m1 by %.0e of the system size changes the true motion by %.1e of the system size now"
                        + " (x%.3g since the start).", TwinRun.NUDGE, sep, sep / TwinRun.NUDGE);
        if (sep >= APART) {
            out.add(new Finding(Level.WARNING, "Twin run", head + " The two runs are fully apart: from a start known"
                    + " only to that precision, the positions at this time cannot be predicted."));
            return;
        }
        double gap = f.gap();
        if (Double.isNaN(gap) || f.referenceFailure() != null) {
            out.add(new Finding(Level.INFO, "Twin run", head));
        } else if (gap < sep) {
            out.add(new Finding(Level.INFO, "Twin run", head + String.format(Locale.ROOT,
                    " The integrator's own error (%.1e) is smaller than that physical change, so at this moment a"
                            + " start known to that precision matters more than the numerical method.", gap)));
        } else if (gap > sep) {
            out.add(new Finding(Level.INFO, "Twin run", head + String.format(Locale.ROOT,
                    " The integrator's own error (%.1e) is larger than that physical change: this run is less"
                            + " accurate than a start uncertain by that much.", gap)));
        } else {
            out.add(new Finding(Level.INFO, "Twin run", head));
        }
    }

    private static void referenceLimit(Facts f, List<Finding> out) {
        double u = f.uncertainty();
        if (f.referenceFailure() == null && !Double.isNaN(u) && u > REFERENCE_WARNING) {
            out.add(new Finding(Level.WARNING, "Reference limit", String.format(Locale.ROOT,
                    "The reference itself is uncertain by %.1e of the system size (two reference runs at different"
                            + " tolerances disagree by that much). Beyond this point even the reference is not the"
                            + " exact orbit, so only qualitative conclusions are safe.", u)));
        }
    }

    // ---------------------------------------------------------------- wording

    /** Three significant figures without trailing zeros, in plain notation: 0.5, 0.0375, 26.7, 798. */
    private static String shortNumber(double value) {
        return new BigDecimal(value).round(new MathContext(3)).stripTrailingZeros().toPlainString();
    }

    /** "N digits" for a drift or gap value; 0 means exact to rounding. */
    private static String digits(double value) {
        if (value <= 0.0) {
            return "rounding error";
        }
        return digitsOf(value);
    }

    private static String digitsOf(double value) {
        int d = Math.max(1, (int) Math.floor(-Math.log10(value)));
        return d + (d == 1 ? " digit" : " digits");
    }
}
