# 0004. A reference trajectory, and two kinds of divergence kept apart

Status: accepted

## Context
In a chaotic system, any numerical error grows. A plot of "how far from the truth" mixes two different things: the integrator's
error and the motion's own sensitivity. Hiding that difference is the most common way to misread a three-body simulation.

## Decision
- `ReferenceTrack`: Dormand-Prince 5(4) adaptive at tolerance 1e-13, accepted steps as nodes, quintic Hermite interpolation.
  A second reference at 1e-14 gives the reference's own uncertainty.
- Numerical error (coral) is the gap between the displayed run and the reference at the same time, in units of the system size,
  with the reference uncertainty drawn as a floor below which the gap is noise.
- Physical sensitivity (violet) is measured on the reference integrator only: the Lyapunov exponent (Benettin method) and a twin
  run with body 0 moved by 1e-6 of the system size. It does not depend on the integrator the user chose.
- Dormand-Prince's tolerance bounds the local error, not the global error, and Verlet's bounded energy error does not make it more
  accurate. The window says both.

## Consequences
- The reference has a predictability horizon of its own (about t = 150 on the displaced Lagrange triangle). The window reports when
  the gap is below the reference's resolution, and the reference reports failure explicitly (for example at a head-on collision,
  it ends at t = 0.785398163397, which is pi/4).
- The binary-flyby preset exists to show a limitation: the failure of fixed steps there is a time-scale problem at a close pass
  (gap at t = 10 / t = 13: Verlet h = 0.005 1.1e-4 / 2.2), not chaos. Moving the impact parameter by 1e-6 changes the final
  semi-major axis by only 2e-6.
