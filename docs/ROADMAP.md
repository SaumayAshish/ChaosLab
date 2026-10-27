# Roadmap

Ordered by how much each item improves the science or the trust in it, not by how visible it is.

## Next
1. Read the CI job log of a green run and record the test count per operating system (run #6 passed on Temurin 25, but the
   count has not yet been read from the log).
2. Fix the remaining visual defects: the control bar wrapping in narrow windows and number fields that still clip values
   longer than about 11 characters. (Legend and label overlaps were fixed and the fields widened on 2026-10-27.)

## Then
- Watch the new comparison plot, the adaptive row, Cancel, and the twin nudge selectors and trails in the running window
  (they compile and their logic is tested; the drawing has not been looked at yet).
- An `AdaptiveIntegrator` interface, once a second adaptive method exists (today adaptive stepping needs Dormand-Prince).
- A loaded scenario that carries a start time (files currently always start at 0).

## Later, only with a measured reason
- A symplectic adaptive scheme, or a regularised close-encounter method, to show the energy-error behaviour of the present
  adaptive integrator side by side.
- Poincare sections and a rotating reference frame.
- Automated tests for the JavaFX classes (for example with TestFX), if the interface keeps growing.

## Not planned
- GPU or networked computation. The project is deliberately CPU-only and runs offline.
