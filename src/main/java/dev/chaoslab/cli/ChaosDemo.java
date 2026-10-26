package dev.chaoslab.cli;

import dev.chaoslab.analysis.ChaosIndicators;
import dev.chaoslab.analysis.ChaosIndicators.LyapunovResult;
import dev.chaoslab.experiments.LagrangeTriangle;
import dev.chaoslab.integration.DormandPrince45Integrator;
import dev.chaoslab.integration.EulerIntegrator;
import dev.chaoslab.integration.Integrator;
import dev.chaoslab.integration.Rk4Integrator;
import dev.chaoslab.integration.VelocityVerletIntegrator;
import dev.chaoslab.physics.NBodySystem;
import dev.chaoslab.physics.State;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * Runs the Lyapunov-exponent validation experiments and prints the results. Everything is computed here from the
 * code; the reference value is the analytic growth rate of Lagrange's equilateral solution for three equal masses.
 *
 * <p>Run with {@code mvn -q compile} and then {@code java -cp target\classes dev.chaoslab.cli.ChaosDemo}.
 */
public final class ChaosDemo {

    private static final NBodySystem LAGRANGE = LagrangeTriangle.system();

    private ChaosDemo() {
    }

    public static void main(String[] args) {
        double c = 27.0 / 4.0 * 3.0 / 9.0;
        double truth = Math.sqrt((Math.sqrt(c) - 0.5) / 2.0) * LagrangeTriangle.angularVelocity();
        System.out.printf(Locale.ROOT, "Largest Lyapunov exponent of Lagrange's equilateral orbit, three equal masses%n");
        System.out.printf(Locale.ROOT, "analytic value (linear stability theory): %.7f per time unit%n%n", truth);

        System.out.println("A. Estimate by integrator and step size (T = 50, slope of cumulative log growth over [15, 50])");
        System.out.println("   method                      h        lambda     error vs analytic   max |dE/E| of reference");
        row("RK4", Rk4Integrator::new, 0.01, truth);
        row("RK4", Rk4Integrator::new, 0.002, truth);
        row("Velocity Verlet", VelocityVerletIntegrator::new, 0.005, truth);
        row("Dormand-Prince 5(4) fixed", DormandPrince45Integrator::new, 0.02, truth);
        for (double h : new double[] {0.02, 0.01, 0.005, 0.002, 0.001}) {
            row("Euler", EulerIntegrator::new, h, truth);
        }

        System.out.println();
        System.out.println("B. Convergence in time (RK4, h = 0.002): running value lambda(T) = (1/T) sum ln(d/d0) and the slope");
        for (double t : new double[] {10, 20, 30, 40, 50, 60}) {
            LyapunovResult r = lagrange(Rk4Integrator::new, 0.002, 0.5, t, 1e-8, 1);
            System.out.printf(Locale.ROOT, "   T = %3.0f   running %.5f%n", t, r.exponent());
        }
        LyapunovResult long60 = lagrange(Rk4Integrator::new, 0.002, 0.5, 60, 1e-8, 1);
        System.out.printf(Locale.ROOT, "   slope over [15, 50]: %.7f  (analytic %.7f)%n", long60.slope(15, 50), truth);

        System.out.println();
        System.out.println("C. Limitation: rounding error destroys the reference orbit (RK4, h = 0.002, slope per window)");
        LyapunovResult longRun = lagrange(Rk4Integrator::new, 0.002, 0.5, 120, 1e-8, 1);
        for (double t = 10; t < 110; t += 10) {
            System.out.printf(Locale.ROOT, "   [%3.0f, %3.0f]  %.4f%s%n", t, t + 10, longRun.slope(t, t + 10),
                    t >= 60 ? "   <- no longer on the Lagrange orbit" : "");
        }
        System.out.printf(Locale.ROOT, "   expected horizon ln(1e16)/lambda = %.0f time units%n", Math.log(1e16) / truth);

        System.out.println();
        System.out.println("D. A regular orbit (circular two-body, RK4, h = 0.005): the estimate decays toward zero");
        NBodySystem two = NBodySystem.normalized(1.0, 1.0);
        double v = Math.sqrt(0.5);
        State kepler = State.of(0, new double[] {-0.5, 0, 0, 0.5, 0, 0}, new double[] {0, -v, 0, 0, v, 0});
        for (double t : new double[] {50, 100, 200, 400, 800}) {
            LyapunovResult r = run(two, kepler, Rk4Integrator::new, 0.005, 0.5, t, 1e-8, 1);
            System.out.printf(Locale.ROOT, "   T = %4.0f   lambda = %.5f%n", t, r.exponent());
        }

        System.out.println();
        System.out.println("E. The window scenario (Lagrange triangle, body 1 displaced by 0.01), h = 0.001: when to trust it");
        System.out.println("   T      RK4 lambda   RK4 max|dE/E|    Verlet lambda   Verlet max|dE/E|");
        State perturbed = LagrangeTriangle.state(0.01);
        for (double t : new double[] {30, 50, 70, 100}) {
            LyapunovResult rk = run(LAGRANGE, perturbed, Rk4Integrator::new, 0.001, 0.5, t, 1e-8, 1);
            LyapunovResult vl = run(LAGRANGE, perturbed, VelocityVerletIntegrator::new, 0.001, 0.5, t, 1e-8, 1);
            System.out.printf(Locale.ROOT, "   %-5.0f  %.4f       %.2e         %.4f         %.2e%n", t, rk.exponent(),
                    rk.maxRelativeEnergyDrift(), vl.exponent(), vl.maxRelativeEnergyDrift());
        }
        System.out.println("   The orbit is chaotic (lambda > 0), but once energy is no longer conserved the number is an");
        System.out.println("   artefact of the integrator: the two methods agree early and disagree after close encounters.");
    }

    private static void row(String name, Supplier<Integrator> f, double h, double truth) {
        LyapunovResult r = lagrange(f, h, 0.5, 50, 1e-8, 1);
        double lambda = r.slope(15, 50);
        System.out.printf(Locale.ROOT, "   %-26s %-8.4f %.5f    %+.2e           %.2e%n", name, h, lambda, lambda - truth,
                r.maxRelativeEnergyDrift());
    }

    private static LyapunovResult lagrange(Supplier<Integrator> f, double h, double tau, double totalTime, double d0,
            long seed) {
        return run(LAGRANGE, LagrangeTriangle.state(0), f, h, tau, totalTime, d0, seed);
    }

    private static LyapunovResult run(NBodySystem sys, State start, Supplier<Integrator> f, double h, double tau,
            double totalTime, double d0, long seed) {
        int stepsPer = (int) Math.round(tau / h);
        int count = (int) Math.round(totalTime / (stepsPer * h));
        return ChaosIndicators.lyapunov(sys, start, f, h, stepsPer, count, d0, seed);
    }
}
