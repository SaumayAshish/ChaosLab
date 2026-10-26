package dev.chaoslab.cli;

import dev.chaoslab.analysis.IntegratorComparison;
import dev.chaoslab.analysis.IntegratorComparison.MethodSpec;
import dev.chaoslab.analysis.IntegratorComparison.Report;
import dev.chaoslab.integration.DormandPrince45Integrator;
import dev.chaoslab.integration.EulerIntegrator;
import dev.chaoslab.integration.Rk4Integrator;
import dev.chaoslab.integration.VelocityVerletIntegrator;
import dev.chaoslab.physics.NBodySystem;
import dev.chaoslab.physics.State;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Prints integrator comparisons for two-body orbits (normalized units, G = 1, two unit masses, separation 1).
 *
 * <p>Both runs give every method about the same number of force evaluations per sample, so the comparison is at
 * roughly equal computational cost: Euler 1 evaluation per step, RK4 4, Velocity Verlet 1, Dormand-Prince 6.
 *
 * <p>Usage: run with no arguments for the tables, or pass a file path to also write the eccentric-orbit samples
 * as CSV.
 */
public final class IntegratorComparisonDemo {

    private IntegratorComparisonDemo() {
    }

    public static void main(String[] args) throws IOException {
        NBodySystem system = NBodySystem.normalized(1.0, 1.0);

        System.out.println("=== Circular orbit (speed factor 1.0), t = 0..20 ===");
        System.out.println(IntegratorComparison.run(system, orbit(1.0), 20.0, 10, specs(120)).toTable());

        System.out.println("=== Eccentric orbit (speed factor 0.8), t = 0..40 ===");
        Report eccentric = IntegratorComparison.run(system, orbit(0.8), 40.0, 10, specs(120));
        System.out.println(eccentric.toTable());

        if (args.length > 0) {
            Path out = Path.of(args[0]);
            Files.writeString(out, eccentric.toCsv(), StandardCharsets.UTF_8);
            System.out.println("Wrote eccentric-orbit samples to " + out.toAbsolutePath());
        }
    }

    /** Two unit masses at separation 1 about the origin; factor 1 is circular, below 1 is eccentric and bound. */
    private static State orbit(double speedFactor) {
        double v = speedFactor * Math.sqrt(0.5);
        return State.of(0.0, new double[] {-0.5, 0, 0, 0.5, 0, 0}, new double[] {0, -v, 0, 0, v, 0});
    }

    private static List<MethodSpec> specs(int stepsPerSampleForOneEvaluationMethods) {
        int n = stepsPerSampleForOneEvaluationMethods;
        return List.of(
                new MethodSpec("Euler", EulerIntegrator::new, n),
                new MethodSpec("RK4", Rk4Integrator::new, n / 4),
                new MethodSpec("Velocity Verlet", VelocityVerletIntegrator::new, n),
                new MethodSpec("Dormand-Prince (fixed)", DormandPrince45Integrator::new, n / 6));
    }
}
