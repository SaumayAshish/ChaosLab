# 0003. Deterministic simulated time

Status: accepted

## Decision
- Fixed step: simulated time is `t0 + stepCount * h`, never an accumulated sum.
- Adaptive step: time is the running sum of accepted step sizes, added in the same order every time. A step is never shortened
  to land on a wall-clock target, so pacing and speed settings cannot change the numbers.
- An adaptive step below 16 ulp of max(|t|, first step) is reported as a failure of the tolerance, not retried forever.

## Consequences
- The same scenario gives bit-identical results at any playback speed (tested).
- Drift plots detect a reset by a decreasing step count.
