package dev.chaoslab.analysis;

import dev.chaoslab.integration.DormandPrince45Integrator;
import dev.chaoslab.integration.DormandPrince45Integrator.Tolerances;
import dev.chaoslab.integration.Integrator;
import dev.chaoslab.integration.NumericalFailureException;
import dev.chaoslab.physics.Diagnostics;
import dev.chaoslab.physics.NBodySystem;
import dev.chaoslab.physics.State;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Runs several integrators from identical initial conditions and measures, at common sample times, how each one
 * conserves energy and angular momentum and how far each trajectory has moved from a reference trajectory.
 *
 * <h2>The reference trajectory is itself numerical</h2>
 * There is no closed-form solution for a general three-body problem, so the reference is computed with the adaptive
 * {@link DormandPrince45Integrator} at a very tight tolerance (relative 1e-12). To show how far the reference can be
 * trusted, a second reference at relative tolerance 1e-10 is computed the same way and the largest separation between
 * the two is reported as {@link Report#referenceSpread()}. For regular orbits it is tiny. For chaotic orbits it grows
 * exponentially, and then <em>"separation from reference" is not an error measurement</em>: it only says that two
 * trajectories that began identically have parted, which is exactly what sensitive dependence means. Interpret the
 * separation columns together with the spread.
 *
 * <h2>Sampling and cost</h2>
 * All methods are sampled at the same times {@code t0 + k (tEnd - t0) / samples}. Each method is specified by
 * {@code stepsPerSample}, so its step is {@code h = dt / stepsPerSample} and sample times align exactly. A method that
 * throws (numerical failure or singularity) is recorded as failed with its message and the samples gathered so far;
 * the comparison continues with the other methods.
 */
public final class IntegratorComparison {

    private static final Tolerances REFERENCE_TIGHT = new Tolerances(1e-15, 1e-12);
    private static final Tolerances REFERENCE_LOOSE = new Tolerances(1e-13, 1e-10);
    private static final int REFERENCE_MAX_STEPS_PER_SAMPLE = 5_000_000;

    private IntegratorComparison() {
    }

    /**
     * One method to compare: a label, a factory for a fresh integrator, and the fixed steps taken per sample. A spec made
     * with {@link #adaptive} instead runs the adaptive Dormand-Prince 5(4) at the given tolerances; its
     * {@code stepsPerSample} is then 1 and unused, and the result reports the mean accepted step as its step size.
     */
    public record MethodSpec(String label, Supplier<Integrator> factory, int stepsPerSample, Tolerances tolerances) {
        public MethodSpec {
            if (label == null || label.isBlank()) {
                throw new IllegalArgumentException("Method label must not be blank");
            }
            if (factory == null) {
                throw new IllegalArgumentException("Integrator factory must not be null");
            }
            if (stepsPerSample <= 0) {
                throw new IllegalArgumentException("stepsPerSample must be > 0, got " + stepsPerSample);
            }
        }

        /** A fixed-step method. */
        public MethodSpec(String label, Supplier<Integrator> factory, int stepsPerSample) {
            this(label, factory, stepsPerSample, null);
        }

        /** The adaptive Dormand-Prince 5(4) at the given tolerances. */
        public static MethodSpec adaptive(String label, Tolerances tolerances) {
            if (tolerances == null) {
                throw new IllegalArgumentException("Tolerances must not be null");
            }
            return new MethodSpec(label, DormandPrince45Integrator::new, 1, tolerances);
        }
    }

    /** Measurements of one method at one sample time. */
    public record Sample(
            double time,
            double relativeEnergyDrift,
            double relativeAngularMomentumDrift,
            double separationFromReference) {
    }

    /** Outcome for one method. {@code failure} is null when the run completed. */
    public record MethodResult(
            String label,
            String methodName,
            double stepSize,
            long stepsCompleted,
            List<Sample> samples,
            String failure) {

        public boolean completed() {
            return failure == null;
        }

        public double maxAbsEnergyDrift() {
            double max = 0.0;
            for (Sample s : samples) {
                max = Math.max(max, Math.abs(s.relativeEnergyDrift()));
            }
            return max;
        }

        public Sample last() {
            return samples.get(samples.size() - 1);
        }
    }

    /** All results plus the reference trajectory they were measured against. */
    public record Report(
            double startTime,
            double endTime,
            int samples,
            List<State> reference,
            double referenceSpread,
            List<MethodResult> results) {

        /** A fixed-width text table with one summary line per method. */
        public String toTable() {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format(Locale.ROOT, "Integrator comparison on t = [%s, %s], %d samples%n",
                    fmt(startTime), fmt(endTime), samples));
            sb.append(String.format(Locale.ROOT, "%-24s %10s %9s %13s %13s %13s  %s%n",
                    "method", "step h", "steps", "max|dE/E|", "final dL/L", "final sep.", "status"));
            for (MethodResult r : results) {
                Sample last = r.last();
                sb.append(String.format(Locale.ROOT, "%-24s %10.4g %9d %13.3e %13.3e %13.3e  %s%n",
                        r.label(), r.stepSize(), r.stepsCompleted(), r.maxAbsEnergyDrift(),
                        Math.abs(last.relativeAngularMomentumDrift()), last.separationFromReference(),
                        r.completed() ? "ok" : "FAILED after t=" + fmt(last.time()) + ": " + r.failure()));
            }
            sb.append(String.format(Locale.ROOT,
                    "reference spread (tight vs loose adaptive reference): %.3e%n", referenceSpread));
            sb.append("Separation is measured against a numerical reference. If the reference spread is not much"
                    + " smaller than the separations, treat the separations as sensitivity, not as error.\n");
            return sb.toString();
        }

        /** Long-format CSV of every sample of every completed or partially completed method. */
        public String toCsv() {
            StringBuilder sb = new StringBuilder();
            sb.append("method,time,relative_energy_drift,relative_angular_momentum_drift,separation_from_reference\n");
            for (MethodResult r : results) {
                for (Sample s : r.samples()) {
                    sb.append(r.label().replace(',', ';')).append(',')
                            .append(s.time()).append(',')
                            .append(s.relativeEnergyDrift()).append(',')
                            .append(s.relativeAngularMomentumDrift()).append(',')
                            .append(s.separationFromReference()).append('\n');
                }
            }
            return sb.toString();
        }

        private static String fmt(double x) {
            return String.format(Locale.ROOT, "%.6g", x);
        }
    }

    // ---------------------------------------------------------------- entry point

    /**
     * @param endTime time to integrate to; must exceed {@code start.time()}
     * @param samples number of equal sampling intervals, at least 1 (each method yields samples + 1 points)
     * @throws NumericalFailureException if the <em>reference</em> trajectory itself cannot be computed
     */
    public static Report run(NBodySystem sys, State start, double endTime, int samples, List<MethodSpec> specs) {
        return run(sys, start, endTime, samples, specs, () -> false);
    }

    /**
     * As {@link #run(NBodySystem, State, double, int, List)}, but {@code cancelled} is polled before every sampling
     * interval of the reference and of each method, and a true answer ends the run with a
     * {@link CancellationException}. One interval, a twentieth of the run in the window, is not interruptible, so a
     * cancel takes effect within that time.
     *
     * @throws CancellationException if {@code cancelled} returned true
     */
    public static Report run(NBodySystem sys, State start, double endTime, int samples, List<MethodSpec> specs,
                             BooleanSupplier cancelled) {
        if (sys.bodyCount() != start.bodyCount()) {
            throw new IllegalArgumentException("State has " + start.bodyCount() + " bodies but the system has "
                    + sys.bodyCount());
        }
        if (!Double.isFinite(endTime) || endTime <= start.time()) {
            throw new IllegalArgumentException("endTime must be finite and greater than the start time "
                    + start.time() + ", got " + endTime);
        }
        if (samples < 1) {
            throw new IllegalArgumentException("samples must be >= 1, got " + samples);
        }
        if (specs == null || specs.isEmpty()) {
            throw new IllegalArgumentException("At least one method is required");
        }

        final double dt = (endTime - start.time()) / samples;
        List<State> tight = referenceTrajectory(sys, start, dt, samples, REFERENCE_TIGHT, cancelled);
        List<State> loose = referenceTrajectory(sys, start, dt, samples, REFERENCE_LOOSE, cancelled);
        double spread = 0.0;
        for (int k = 0; k <= samples; k++) {
            spread = Math.max(spread, maxSeparation(tight.get(k), loose.get(k)));
        }

        List<MethodResult> results = new ArrayList<>();
        for (MethodSpec spec : specs) {
            results.add(spec.tolerances() == null
                    ? runMethod(sys, start, dt, samples, tight, spec, cancelled)
                    : runAdaptive(sys, start, dt, samples, tight, spec, cancelled));
        }
        return new Report(start.time(), endTime, samples, Collections.unmodifiableList(tight), spread,
                Collections.unmodifiableList(results));
    }

    // ---------------------------------------------------------------- internals

    private static List<State> referenceTrajectory(NBodySystem sys, State start, double dt, int samples,
                                                   Tolerances tol, BooleanSupplier cancelled) {
        List<State> states = new ArrayList<>(samples + 1);
        states.add(start);
        DormandPrince45Integrator dp = new DormandPrince45Integrator();
        State current = start;
        for (int k = 1; k <= samples; k++) {
            checkCancelled(cancelled);
            double target = start.time() + k * dt;
            try {
                current = dp.integrate(sys, current, target, dt / 10.0, tol, REFERENCE_MAX_STEPS_PER_SAMPLE)
                        .finalState();
            } catch (NumericalFailureException e) {
                throw new NumericalFailureException("Reference trajectory failed in sample interval " + k + " of "
                        + samples + " (t = " + current.time() + " to " + target + "): " + e.getMessage());
            }
            states.add(current);
        }
        return states;
    }

    private static void checkCancelled(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean()) {
            throw new CancellationException("The comparison was cancelled");
        }
    }

    private static MethodResult runAdaptive(NBodySystem sys, State start, double dt, int samples,
                                            List<State> reference, MethodSpec spec, BooleanSupplier cancelled) {
        DormandPrince45Integrator dp = new DormandPrince45Integrator();
        final Diagnostics.Invariants i0 = Diagnostics.measure(sys, start);
        List<Sample> out = new ArrayList<>(samples + 1);
        out.add(new Sample(start.time(), 0.0, 0.0, 0.0));
        State current = start;
        long steps = 0;
        String failure = null;
        for (int k = 1; k <= samples && failure == null; k++) {
            checkCancelled(cancelled);
            try {
                DormandPrince45Integrator.AdaptiveResult r = dp.integrate(sys, current, start.time() + k * dt,
                        dt / 10.0, spec.tolerances(), REFERENCE_MAX_STEPS_PER_SAMPLE);
                steps += (long) r.acceptedSteps() + r.rejectedSteps() + 1L; // the +1 is the shortened final step
                current = r.finalState();
                Diagnostics.Drift d = Diagnostics.drift(sys, start, i0, Diagnostics.measure(sys, current));
                out.add(new Sample(current.time(), d.relativeEnergy(), d.relativeAngularMomentum(),
                        maxSeparation(current, reference.get(k))));
            } catch (ArithmeticException e) {
                failure = e.getClass().getSimpleName() + ": " + e.getMessage();
            }
        }
        double covered = out.get(out.size() - 1).time() - start.time();
        double meanStep = steps > 0 ? covered / steps : Double.NaN;
        return new MethodResult(spec.label(), dp.name() + " (adaptive)", meanStep, steps,
                Collections.unmodifiableList(out), failure);
    }

    private static MethodResult runMethod(NBodySystem sys, State start, double dt, int samples,
                                          List<State> reference, MethodSpec spec, BooleanSupplier cancelled) {
        Integrator integrator = spec.factory().get();
        final double h = dt / spec.stepsPerSample();
        final Diagnostics.Invariants i0 = Diagnostics.measure(sys, start);
        double[] q = start.positions();
        double[] v = start.velocities();
        List<Sample> out = new ArrayList<>(samples + 1);
        out.add(new Sample(start.time(), 0.0, 0.0, 0.0));
        long steps = 0;
        String failure = null;

        for (int k = 1; k <= samples && failure == null; k++) {
            checkCancelled(cancelled);
            try {
                for (int s = 0; s < spec.stepsPerSample(); s++) {
                    integrator.step(sys, q, v, h);
                    steps++;
                }
                State now = State.of(start.time() + k * dt, q, v);
                Diagnostics.Drift d = Diagnostics.drift(sys, start, i0, Diagnostics.measure(sys, now));
                out.add(new Sample(now.time(), d.relativeEnergy(), d.relativeAngularMomentum(),
                        maxSeparation(now, reference.get(k))));
            } catch (ArithmeticException e) {
                failure = e.getClass().getSimpleName() + ": " + e.getMessage();
            }
        }
        return new MethodResult(spec.label(), integrator.name(), h, steps,
                Collections.unmodifiableList(out), failure);
    }

    /** Largest Euclidean distance between corresponding bodies of two states. */
    private static double maxSeparation(State a, State b) {
        double max = 0.0;
        for (int i = 0; i < a.bodyCount(); i++) {
            max = Math.max(max, a.position(i).minus(b.position(i)).norm());
        }
        return max;
    }
}
