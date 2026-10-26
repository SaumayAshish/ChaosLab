# 0008. The explanation panel states measured numbers and conditional claims only

Status: accepted

## Decision
`Explanation` is a pure class: measured facts in, ordered findings out. It adds no data of its own.
- Conservation is banded: at most 1e-6 is "conserved", at most 1e-3 is informational, more is a warning. These thresholds are rules
  of thumb for this program and the panel says so.
- Accuracy is stated in digits of agreement with the reference.
- "Conserved is not accurate": good energy conservation with a large gap is called out, because a symplectic integrator can do that.
- The sensitivity horizon, ln(1e13) / lambda, is worded "if that growth stayed exponential", because it is an extrapolation.
  Hand check: lambda = 0.3671 per unit time gives 81.5, which the window showed.

## Consequences
Every sentence can be traced to a number on screen. A dead rule found by mutation testing (a gap-over-drift ratio that could never
differ from the other conditions) was removed.
