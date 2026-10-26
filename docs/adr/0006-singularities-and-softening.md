# 0006. Singularities fail loudly; softening is labelled

Status: accepted

## Decision
- Exact Newtonian gravity is the default (softening = 0). A positive Plummer softening is allowed, and every place that shows
  results says the run is non-Newtonian.
- If an interaction becomes singular or non-finite, the runner rolls back to the last valid state and reports FAILED with an
  explanation. Nothing continues silently with NaN or infinity.
- The UI limit of 8 bodies is an interface limit, not an engine limit.

## Consequences
A head-on two-body collision ends the run at the collision time with an explanation; the twin run reports its own failure instead
of throwing (tested).
