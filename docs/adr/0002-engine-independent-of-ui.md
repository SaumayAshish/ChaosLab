# 0002. Engine independent of the UI

Status: accepted

## Context
A physics result must not depend on frame rate, window size or thread timing.

## Decision
- Packages `physics`, `integration`, `analysis`, `simulation`, `experiments`, `config`, `io` do not import JavaFX.
- Hot loops use a flat `double[]` state (positions then velocities). Across threads only immutable values travel: `State`,
  `Snapshot`, the monitors' `Reading`.
- The simulation runs on a background thread. The UI polls `runner.snapshot()` once per frame.
- Reference and twin computations run on their own daemon threads, keep only the newest request, and tag results with a generation
  counter so a reset cannot be overwritten by a late result.

## Consequences
- Almost all behaviour is testable without a window; no test touches JavaFX.
- Readings from other threads are older than what is on screen. `ReferenceAligner` compares like with like: it draws the reference
  ring at the displayed position plus the measured error vector, not at the monitor's lagging position. Measured at speed 2 t/s over
  415 readings: raw ring offset mean 3.979e-2, aligned 1.459e-4, equal to the true mean gap 1.459e-4.
