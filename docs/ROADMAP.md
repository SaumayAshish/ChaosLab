# Roadmap

Ordered by how much each item improves the science or the trust in it, not by how visible it is.

## Next
1. Read the CI job log of a green run and record the test count per operating system (run #6 passed on Temurin 25, but the
   count has not yet been read from the log).
2. Extend the in-window integrator comparison. All seven experiments of the original brief are now covered (stable orbit,
   figure-eight, chaotic scattering, nearby initial conditions, integrator comparison, close encounter, long-term integration),
   each with its equations. Still missing: the adaptive Dormand-Prince as a row of the comparison (the engine compares fixed
   steps only), cancelling a long comparison, and a plot of the comparison samples.
3. Fix the remaining visual defects: number fields that clip long values, and the control bar wrapping in narrow windows.
   (The legend and label overlaps were fixed on 2026-10-27.)

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
