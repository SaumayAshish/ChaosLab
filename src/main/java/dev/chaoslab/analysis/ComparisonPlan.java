package dev.chaoslab.analysis;

import dev.chaoslab.analysis.IntegratorComparison.MethodResult;
import dev.chaoslab.analysis.IntegratorComparison.MethodSpec;
import dev.chaoslab.analysis.IntegratorComparison.Report;
import dev.chaoslab.integration.DormandPrince45Integrator;
import dev.chaoslab.integration.EulerIntegrator;
import dev.chaoslab.integration.Rk4Integrator;
import dev.chaoslab.integration.VelocityVerletIntegrator;
import dev.chaoslab.physics.NBodySystem;
import dev.chaoslab.physics.State;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Turns "compare the integrators on this scenario for this long" into a concrete {@link IntegratorComparison} run and
 * turns the resulting {@link Report} into plain sentences. It holds no JavaFX, so the window only has to show what this
 * class decides, and all of it is tested.
 *
 * <p>Four methods are compared at one common fixed step: Euler, RK4, Velocity Verlet and Dormand-Prince 5(4) used with
 * a fixed step. The adaptive Dormand-Prince is not in the list because {@link IntegratorComparison} compares fixed
 * steps; the main window runs it.
 *
 * <p>The step the person chose is honoured as closely as the sampling allows: the duration is split into
 * {@link #SAMPLES} equal intervals (fewer if the duration is only a few steps), each holding a whole number of steps,
 * so the step actually used, {@link Plan#stepSize()}, differs from the requested one by at most about
 * {@code 1/(2 * steps per sample)} of itself and is reported.
 */
public final class ComparisonPlan {

    /** Number of equal sampling intervals the duration is split into. */
    public static final int SAMPLES = 20;

    /** The most steps any one method may take; the reference needs far more work per step, so this bounds the wait. */
    public static final long MAX_STEPS_PER_METHOD = 1_000_000L;

    /** A method whose total energy changes by more than this fraction has left the true motion. */
    public static final double ENERGY_VALID_LIMIT = 0.1;

    private ComparisonPlan() {
    }

    /** The shape of a comparison: all times in the simulation's own units (seconds for SI scenarios). */
    public record Plan(double duration, int samples, int stepsPerSample, double stepSize, long stepsPerMethod) {
    }

    /**
     * @param requestedStep the scenario's fixed step h
     * @param duration how long to integrate, in simulation units
     * @throws IllegalArgumentException with a sentence for the person when the request cannot be run
     */
    public static Plan plan(double requestedStep, double duration) {
        if (!(requestedStep > 0.0) || !Double.isFinite(requestedStep)) {
            throw new IllegalArgumentException("The step size must be a positive number, got " + requestedStep + ".");
        }
        if (!(duration > 0.0) || !Double.isFinite(duration)) {
            throw new IllegalArgumentException("The duration must be a positive number, got " + duration + ".");
        }
        double wanted = duration / requestedStep;
        if (wanted < 2.0) {
            throw new IllegalArgumentException("The duration is shorter than two steps (step "
                    + format(requestedStep) + "); make it longer.");
        }
        int samples = (int) Math.min(SAMPLES, Math.floor(wanted));
        long perSample = Math.max(1L, Math.round(wanted / samples));
        long steps = perSample * samples;
        if (Math.round(wanted) > MAX_STEPS_PER_METHOD) {
            throw new IllegalArgumentException("That needs about " + Math.round(wanted) + " steps per method; the"
                    + " limit is " + MAX_STEPS_PER_METHOD + ". At this step the longest run is "
                    + format(MAX_STEPS_PER_METHOD * requestedStep) + " (simulation units).");
        }
        return new Plan(duration, samples, (int) perSample, duration / steps, steps);
    }

    /** The four methods, all at the same fixed step (the plan's step is applied through steps per sample). */
    public static List<MethodSpec> specs(Plan plan) {
        int n = plan.stepsPerSample();
        return List.of(
                new MethodSpec("Euler", EulerIntegrator::new, n),
                new MethodSpec("RK4", Rk4Integrator::new, n),
                new MethodSpec("Velocity Verlet", VelocityVerletIntegrator::new, n),
                new MethodSpec("Dormand-Prince, fixed h", DormandPrince45Integrator::new, n));
    }

    /**
     * Plans and runs the comparison from the scenario's start.
     *
     * @throws IllegalArgumentException if the request cannot be planned
     * @throws dev.chaoslab.integration.NumericalFailureException if the reference trajectory itself fails (typically a
     *         very close encounter inside the interval)
     */
    public static Report run(NBodySystem system, State start, double requestedStep, double duration) {
        Plan plan = plan(requestedStep, duration);
        return IntegratorComparison.run(system, start, start.time() + duration, plan.samples(), specs(plan));
    }

    /**
     * Reads a duration typed in display units and returns it in simulation units.
     *
     * @param timeScale simulation units per display unit (86400 for days in SI scenarios, 1 otherwise)
     */
    public static double parseDuration(String text, double timeScale) {
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Enter a duration.");
        }
        double value;
        try {
            value = Double.parseDouble(trimmed);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + trimmed + "' is not a number.");
        }
        if (!Double.isFinite(value) || value <= 0.0) {
            throw new IllegalArgumentException("The duration must be a positive number, got " + trimmed + ".");
        }
        return value * timeScale;
    }

    /**
     * Sentences that say what the numbers in a report show, using only the report.
     *
     * <p>Methods are ranked only if they stayed valid: one whose total energy changed by more than
     * {@link #ENERGY_VALID_LIMIT} (10%) has left the true motion, so it is named as invalid and left out of the
     * ranking. Two further cautions come from the reference itself: a reference whose two tolerances disagree by more
     * than a tenth of the starting system's size has not converged, and separations that are not clearly larger than
     * the reference spread cannot be told apart from the reference's own uncertainty. When the energy-best and the
     * position-best method differ by less than that spread they count as tied, and no disagreement is claimed.
     */
    public static List<String> summary(Report report) {
        List<String> lines = new ArrayList<>();
        List<MethodResult> valid = new ArrayList<>();
        List<String> invalid = new ArrayList<>();
        int completed = 0;
        for (MethodResult r : report.results()) {
            if (!r.completed()) {
                lines.add(r.label() + " failed after t = " + format(r.last().time()) + ": " + r.failure());
                continue;
            }
            completed++;
            if (!(r.maxAbsEnergyDrift() <= ENERGY_VALID_LIMIT)) {
                invalid.add(r.label() + " changed the total energy by more than 10% (max |dE/E| "
                        + format(r.maxAbsEnergyDrift()) + "), so its run is not a valid trajectory at this step.");
            } else {
                valid.add(r);
            }
        }
        if (completed == 0) {
            lines.add("No method completed, so there is nothing to rank.");
            return lines;
        }
        if (valid.isEmpty()) {
            lines.addAll(invalid);
            lines.add("No method kept the energy within 10% at this step, so there is nothing to rank. The step is"
                    + " too coarse for this scenario (typically a close encounter, or a run too long for it): use the"
                    + " adaptive Dormand-Prince in the main window, or a shorter duration.");
            addReferenceCautions(report, lines, Double.NaN);
            return lines;
        }
        MethodResult bestEnergy = valid.get(0);
        MethodResult worstEnergy = valid.get(0);
        MethodResult bestPosition = valid.get(0);
        for (MethodResult r : valid) {
            if (r.maxAbsEnergyDrift() < bestEnergy.maxAbsEnergyDrift()) {
                bestEnergy = r;
            }
            if (r.maxAbsEnergyDrift() > worstEnergy.maxAbsEnergyDrift()) {
                worstEnergy = r;
            }
            if (sep(r) < sep(bestPosition)) {
                bestPosition = r;
            }
        }
        if (valid.size() > 1) {
            lines.add("Smallest energy drift: " + bestEnergy.label() + " (max |dE/E| "
                    + format(bestEnergy.maxAbsEnergyDrift()) + "); largest: " + worstEnergy.label() + " ("
                    + format(worstEnergy.maxAbsEnergyDrift()) + ").");
            lines.add("Closest to the reference at the end: " + bestPosition.label() + " (separation "
                    + format(sep(bestPosition)) + ").");
            boolean tied = Math.abs(sep(bestEnergy) - sep(bestPosition)) <= report.referenceSpread();
            if (bestEnergy != bestPosition && !tied) {
                lines.add("The method that conserves energy best (" + bestEnergy.label() + ") is not the one whose"
                        + " position is closest to the reference (" + bestPosition.label() + "): a small energy"
                        + " drift does not mean an accurate position.");
            }
        } else {
            MethodResult only = valid.get(0);
            lines.add(only.label() + ": max |dE/E| " + format(only.maxAbsEnergyDrift())
                    + ", separation at the end " + format(sep(only)) + ".");
        }
        lines.addAll(invalid);
        addReferenceCautions(report, lines, sep(bestPosition));
        return lines;
    }

    private static void addReferenceCautions(Report report, List<String> lines, double smallestSeparation) {
        double extent = startExtent(report);
        if (extent > 0.0 && report.referenceSpread() > 0.1 * extent) {
            lines.add("The reference itself has not converged: its two tolerances differ by "
                    + format(report.referenceSpread()) + ", " + format(100.0 * report.referenceSpread() / extent)
                    + "% of the starting system's size, so separations from it cannot be trusted. Try a shorter"
                    + " duration.");
        } else if (!Double.isNaN(smallestSeparation) && report.referenceSpread() * 10.0 > smallestSeparation) {
            lines.add("The reference itself is uncertain here (spread " + format(report.referenceSpread())
                    + " against a smallest separation of " + format(smallestSeparation) + "), so separations of that"
                    + " size or smaller cannot be told apart from the reference's own uncertainty.");
        }
    }

    /** The largest coordinate of any body at the start, a size to judge the reference spread against; 0 if unknown. */
    private static double startExtent(Report report) {
        if (report.reference().isEmpty()) {
            return 0.0;
        }
        double extent = 0.0;
        for (double q : report.reference().get(0).positions()) {
            extent = Math.max(extent, Math.abs(q));
        }
        return extent;
    }

    /**
     * Breaks every line longer than {@code width} at spaces so that it fits a window without sideways scrolling.
     * A continuation line keeps the leading indentation of its line, plus two spaces under a "- " bullet.
     */
    public static String wrapLines(String text, int width) {
        StringBuilder out = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            int indent = 0;
            while (indent < line.length() && line.charAt(indent) == ' ') {
                indent++;
            }
            String prefix = " ".repeat(indent + (line.startsWith("- ", indent) ? 2 : 0));
            String rest = line;
            while (rest.length() > width) {
                int cut = rest.lastIndexOf(' ', width);
                if (cut <= prefix.length()) {
                    cut = width;
                }
                out.append(rest, 0, cut).append('\n');
                rest = prefix + rest.substring(cut).stripLeading();
            }
            out.append(rest).append('\n');
        }
        out.setLength(out.length() - 1); // split(-1) kept the trailing empty piece, so drop the one extra newline
        return out.toString();
    }

    /** "365 d (3.1536e+07 s)"-style description of a duration for the dialog. */
    public static String describeDuration(double duration, double timeScale, String unit) {
        if (timeScale == 1.0) {
            return format(duration) + " " + unit;
        }
        return format(duration / timeScale) + " " + unit + " (" + format(duration) + " s)";
    }

    private static double sep(MethodResult r) {
        return r.last().separationFromReference();
    }

    private static String format(double x) {
        return String.format(Locale.ROOT, "%.3g", x);
    }
}
