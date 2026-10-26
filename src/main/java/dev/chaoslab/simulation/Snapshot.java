package dev.chaoslab.simulation;

import dev.chaoslab.physics.Diagnostics;
import dev.chaoslab.physics.State;

/**
 * An immutable, internally consistent picture of a simulation at one instant. This is the only thing that crosses
 * from the simulation thread to the user interface: because every component is immutable, it can be read from any
 * thread without locking and can never be seen half-updated.
 *
 * @param sequence     increases by one with every published snapshot (0 for the initial one); lets a reader
 *                     tell whether anything changed since it last looked
 * @param status       lifecycle state
 * @param state        time, positions and velocities
 * @param invariants   energy, momentum, angular momentum and centre of mass at this state
 * @param drift        departure of the invariants from their initial values
 * @param stepCount    number of integration steps taken since the last reset
 * @param stepSize     the fixed step; in adaptive mode the size of the most recent accepted step
 * @param integratorName name of the integrator in use; adaptive runs append ", adaptive"
 * @param speed        requested speed, simulation time units per wall-clock second
 * @param failure      explanation when {@code status == FAILED}, otherwise null
 */
public record Snapshot(
        long sequence,
        Status status,
        State state,
        Diagnostics.Invariants invariants,
        Diagnostics.Drift drift,
        long stepCount,
        double stepSize,
        String integratorName,
        double speed,
        String failure) {

    /** Lifecycle of a run. */
    public enum Status {
        /** At the initial state, never started (also after reset). */
        READY,
        /** Advancing in simulated time paced to the requested speed. */
        RUNNING,
        /** Stopped by the user, resumable. */
        PAUSED,
        /** Reached {@code maxSteps}. */
        FINISHED,
        /** A numerical failure or singularity occurred; the state is the last valid one. Reset to recover. */
        FAILED,
        /** The runner has been closed; no further changes will occur. */
        CLOSED
    }
}
