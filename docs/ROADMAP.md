# Roadmap

Ordered by how much each item improves the science or the trust in it, not by how visible it is.

## Next
1. Run the whole test suite on a real Java 25 runtime and record the result (all development runs so far used JDK 26.0.2 with
   `--release 25`).
2. Check the nine presets against the original list of required experiments, and add any that are missing.
3. See and fix the remaining visual defects: legend and label overlaps in the orbit view and the error plot, number fields that clip
   long values, the control bar wrapping in narrow windows.
4. Add screenshots taken from the running application.

## Then
- Apply of an edited custom scenario, and the "Reference (ghost)" switch turned off: both compile and are specified but have not
  yet been watched in the running window.
- Twin run: trails, rings included in the auto-fit, a choice of which body and direction to nudge.
- An `AdaptiveIntegrator` interface, once a second adaptive method exists (today adaptive stepping needs Dormand-Prince).
- A loaded scenario that carries a start time (files currently always start at 0).

## Later, only with a measured reason
- A symplectic adaptive scheme, or a regularised close-encounter method, to show the energy-error behaviour of the present
  adaptive integrator side by side.
- Poincare sections and a rotating reference frame.
- Automated tests for the JavaFX classes (for example with TestFX), if the interface keeps growing.

## Not planned
- GPU or networked computation. The project is deliberately CPU-only and runs offline.
