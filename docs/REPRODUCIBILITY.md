# Reproducibility

This page says what can be reproduced, how, and what is not claimed.

## Environment used
- Windows 11, Maven 3.9.16, JavaFX 25.0.1, JUnit 5.14.1.
- JDK 26.0.2 compiling with `--release 25` for milestones 5 onward. Earlier milestones ran on a Java 25 runtime.
- Part of the engine was also run on JDK 21 on Linux in a sandbox; the output of the command-line `ChaosDemo` was identical to the
  Windows Java 25 output.

## Steps
```
git clone <repository>
cd ChaosLab
mvn test
```
Expected: `Tests run: 426, Failures: 0, Errors: 0, Skipped: 0` in 34 classes, `BUILD SUCCESS` (about 20 seconds on the development laptop).

If your count differs, compare the per-class lines in the Surefire output, not only the total.

## What is deterministic
- Fixed-step and adaptive runs depend only on the scenario and the integrator, not on playback speed or pauses
  (`SimulationRunnerTest`, `SimulationRunnerAdaptiveTest`).
- Saving and loading an experiment reproduces the run bit for bit, because every number is written as the shortest decimal that
  reads back as the same double (`ExperimentFileTest`, `ScenarioDraftTest`).
- Cross-platform agreement was observed, not proved: the command-line `ChaosDemo` printed identical output on JDK 21 (Linux) and a
  Java 25 runtime (Windows). The Lagrange start states use exact square-root constants instead of sine and cosine. The code has not
  been audited for every use of `Math` functions whose last digit can differ between platforms.

## What is not reproducible bit for bit
- Anything measured with a clock: the live window's frame timing, reference lag, and the sparse sampling of the monitors at very
  high playback speed. The monitor drops requests while busy.
- The reference trajectory is a numerical solution with its own uncertainty (reported by the program); two references at tolerances
  1e-13 and 1e-14 differ by 8.4e-11 at t = 10 and by 2.1 at t = 70 on the Pythagorean problem.

## Where each quoted number comes from
Every number in a preset's note, in the README and in the decision records was measured by code in this repository. Tests that pin
them: `BinaryFlybyTest`, `LagrangeTriangleTest`, `FigureEightTest`, `PythagoreanProblemTest`, `SunEarthMoonTest`, `TwinRunTest`,
`ExponentRuleTest`, `ReferenceAlignerTest`. Where a number came from watching the running window instead, the milestone log says so.
No benchmark timings are claimed.
