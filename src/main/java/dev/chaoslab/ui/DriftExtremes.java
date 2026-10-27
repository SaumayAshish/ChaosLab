package dev.chaoslab.ui;

/**
 * The largest conservation drifts seen since the run (re)started, one each for energy, momentum and angular momentum.
 *
 * <p>The explanation panel reports conservation from these maxima, not from the latest values. A symplectic method's
 * energy error oscillates, passing close to zero twice per orbit while its peaks are orders of magnitude larger, so a
 * judgement made from the instantaneous value would say "conserved to 7 digits" at a trough while the plot above it
 * shows peaks of 1e-4.
 */
final class DriftExtremes {

    private double energy;
    private double momentum;
    private double angularMomentum;

    /** Records one set of drifts; signs are ignored and NaN values (a failed run) leave the maxima unchanged. */
    void offer(double relativeEnergy, double relativeMomentum, double relativeAngularMomentum) {
        energy = larger(energy, relativeEnergy);
        momentum = larger(momentum, relativeMomentum);
        angularMomentum = larger(angularMomentum, relativeAngularMomentum);
    }

    /** Forgets everything; called when the run restarts or is reset. */
    void reset() {
        energy = 0.0;
        momentum = 0.0;
        angularMomentum = 0.0;
    }

    double energy() {
        return energy;
    }

    double momentum() {
        return momentum;
    }

    double angularMomentum() {
        return angularMomentum;
    }

    private static double larger(double max, double value) {
        double magnitude = Math.abs(value);
        return Double.isNaN(magnitude) ? max : Math.max(max, magnitude);
    }
}
