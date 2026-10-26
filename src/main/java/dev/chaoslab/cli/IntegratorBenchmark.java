package dev.chaoslab.cli;

import dev.chaoslab.integration.DormandPrince45Integrator;
import dev.chaoslab.integration.EulerIntegrator;
import dev.chaoslab.integration.Integrator;
import dev.chaoslab.integration.Rk4Integrator;
import dev.chaoslab.integration.VelocityVerletIntegrator;
import dev.chaoslab.physics.NBodySystem;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * A plain wall-clock micro-benchmark of the fixed-step cost of each integrator on a three-body system.
 *
 * <p>This is deliberately simple and is <b>not</b> a JMH benchmark: it warms the JIT with untimed runs, then times
 * several repetitions with {@code System.nanoTime} and reports the minimum and median. Treat results as indicative
 * for the machine and JVM that produced them, not as portable numbers. Do not quote them without the printed
 * environment line.
 *
 * <p>The state is the equilateral Lagrange configuration of three unit masses (a rotating rigid triangle), which
 * stays smooth over the short benchmark so no method hits a close encounter.
 */
public final class IntegratorBenchmark {

    private static final int STEPS_PER_RUN = 20_000;
    private static final int WARMUP_RUNS = 5;
    private static final int TIMED_RUNS = 9;
    private static final double H = 1e-3;

    private IntegratorBenchmark() {
    }

    /** Force evaluations per step as implemented (see each class): a fact about the code, not a measurement. */
    private record Entry(String name, Integrator integrator, int evaluationsPerStep) {
    }

    public static void main(String[] args) {
        NBodySystem system = NBodySystem.normalized(1.0, 1.0, 1.0);
        double[] q0 = new double[9];
        double[] v0 = new double[9];
        double omega = Math.pow(3.0, -0.25); // equilateral triangle, circumradius 1, unit masses, G = 1
        for (int k = 0; k < 3; k++) {
            double theta = 2.0 * Math.PI * k / 3.0;
            q0[3 * k] = Math.cos(theta);
            q0[3 * k + 1] = Math.sin(theta);
            v0[3 * k] = -omega * Math.sin(theta);
            v0[3 * k + 1] = omega * Math.cos(theta);
        }

        List<Entry> entries = List.of(
                new Entry("Explicit Euler", new EulerIntegrator(), 1),
                new Entry("RK4", new Rk4Integrator(), 4),
                new Entry("Velocity Verlet", new VelocityVerletIntegrator(), 1),
                new Entry("Dormand-Prince 5(4)", new DormandPrince45Integrator(), 6));

        System.out.printf("Environment: Java %s (%s), %s %s, %d logical processors%n",
                System.getProperty("java.version"), System.getProperty("java.vm.name"),
                System.getProperty("os.name"), System.getProperty("os.arch"),
                Runtime.getRuntime().availableProcessors());
        System.out.printf("Three bodies, h = %.0e, %d steps per run, %d warm-up runs, %d timed runs%n%n",
                H, STEPS_PER_RUN, WARMUP_RUNS, TIMED_RUNS);
        System.out.printf("%-22s %8s %14s %14s %16s%n", "method", "evals", "min ns/step", "median ns/step",
                "median ns/eval");

        double sink = 0.0; // consumed at the end so the JIT cannot discard the work
        for (Entry e : entries) {
            double[] times = new double[TIMED_RUNS];
            for (int run = -WARMUP_RUNS; run < TIMED_RUNS; run++) {
                double[] q = q0.clone();
                double[] v = v0.clone();
                long t0 = System.nanoTime();
                for (int s = 0; s < STEPS_PER_RUN; s++) {
                    e.integrator().step(system, q, v, H);
                }
                long elapsed = System.nanoTime() - t0;
                sink += q[0] + v[1];
                if (run >= 0) {
                    times[run] = (double) elapsed / STEPS_PER_RUN;
                }
            }
            Arrays.sort(times);
            double min = times[0];
            double median = times[TIMED_RUNS / 2];
            System.out.printf(Locale.ROOT, "%-22s %8d %14.1f %14.1f %16.1f%n", e.name(), e.evaluationsPerStep(),
                    min, median, median / e.evaluationsPerStep());
        }
        System.out.printf("%n(checksum %.6e, ignore: it only keeps the compiler honest)%n", sink);
        System.out.println("Evaluations per step: counted from the implementations (Verlet and Dormand-Prince reuse"
                + " the previous end-of-step acceleration).");
    }
}
