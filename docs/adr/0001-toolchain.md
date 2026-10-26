# 0001. Toolchain: Java 25, JavaFX 25, Maven

Status: accepted

## Context
The project must build offline once dependencies are cached, run CPU-only on Windows, and fail loudly on the wrong JDK.

## Decision
- Java 25 (LTS) with `maven.compiler.release` = 25. `release` (not `source`/`target`) also pins the API surface, so an older JDK
  fails the build instead of producing something different. No preview features are enabled and `-Xlint:all` is on.
- JavaFX 25.0.1 (`javafx-controls`; the native jars are picked by classifier). JUnit 5.14.1. All versions are properties in one place.
- `javafx-maven-plugin` 0.0.8 runs `dev.chaoslab.ui.Launcher` with `--enable-native-access=javafx.graphics`.
- The engine uses no dependency except the JDK.

## Consequences
- Development so far ran on JDK 26.0.2 compiling with `--release 25`; results from milestone 5 onward are JDK 26 runtime results.
  The reference output of `ChaosDemo` was identical on JDK 21 (Linux) and a Java 25 runtime (Windows). Installing JDK 25 and
  re-running `mvn test` is on the roadmap.
