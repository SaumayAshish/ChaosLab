# Architecture decision records

One file per decision: context, the decision, consequences, and the evidence that was measured. Numbers quoted here were measured
in this project; the tests named in each record pin them.

| # | Decision |
|---|---|
| [0001](0001-toolchain.md) | Java 25, JavaFX 25, Maven, `--release` pinning |
| [0002](0002-engine-independent-of-ui.md) | Engine independent of the UI; flat arrays inside, immutable snapshots outside |
| [0003](0003-deterministic-time.md) | Deterministic simulated time |
| [0004](0004-reference-and-two-divergences.md) | A reference trajectory, and numerical error kept apart from physical sensitivity |
| [0005](0005-exponent-rule.md) | A Lyapunov exponent is claimed only under a stated rule |
| [0006](0006-singularities-and-softening.md) | Singularities fail loudly; softening is always labelled non-Newtonian |
| [0007](0007-experiment-files.md) | Experiment files: exact round trip, strict reader |
| [0008](0008-explanations.md) | The explanation panel states measured numbers and conditional claims only |
