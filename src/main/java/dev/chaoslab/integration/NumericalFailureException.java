package dev.chaoslab.integration;

/**
 * Thrown when an integration step produces a non-finite state (NaN or infinity), meaning the numerical method has
 * broken down for the given step size and initial conditions. It is reported, never swallowed: a silent NaN in a
 * trajectory would otherwise look like a physical result.
 */
public final class NumericalFailureException extends ArithmeticException {
    private static final long serialVersionUID = 1L;

    public NumericalFailureException(String message) {
        super(message);
    }
}
