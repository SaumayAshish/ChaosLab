package dev.chaoslab.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.chaoslab.config.ScenarioDraft;
import dev.chaoslab.experiments.BinaryFlyby;
import dev.chaoslab.ui.GapHistory;
import dev.chaoslab.ui.TelemetryHistory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class FileIoTest {

    private static ExperimentFile experiment() {
        return new ExperimentFile("Binary flyby", "café € note", "RK4 (order 4)",
                ScenarioDraft.of(BinaryFlyby.system(), BinaryFlyby.state(), 0.001));
    }

    private static Path dir() throws IOException {
        return Files.createTempDirectory("chaoslab-io-test");
    }

    private static void cleanup(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : s.toList()) {
                Files.deleteIfExists(p);
            }
        }
        Files.deleteIfExists(dir);
    }

    private static List<String> names(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    void experimentFileRoundTripsThroughDisk() throws IOException {
        Path d = dir();
        try {
            Path f = d.resolve("e.json");
            FileIo.writeExperiment(f, experiment());
            assertEquals(experiment(), FileIo.readExperiment(f));
            assertEquals(List.of("e.json"), names(d), "no temporary file is left behind");
        } finally {
            cleanup(d);
        }
    }

    @Test
    void writingReplacesAnExistingFile() throws IOException {
        Path d = dir();
        try {
            Path f = d.resolve("e.json");
            Files.writeString(f, "old content that is longer than nothing");
            FileIo.writeExperiment(f, experiment());
            assertEquals(experiment(), FileIo.readExperiment(f));
        } finally {
            cleanup(d);
        }
    }

    @Test
    void textIsWrittenAsUtf8() throws IOException {
        Path d = dir();
        try {
            Path f = d.resolve("e.json");
            FileIo.writeExperiment(f, experiment());
            String raw = new String(Files.readAllBytes(f), StandardCharsets.UTF_8);
            assertTrue(raw.contains("café € note"), raw);
        } finally {
            cleanup(d);
        }
    }

    @Test
    void aMissingFileIsAnIoError() throws IOException {
        Path d = dir();
        try {
            assertThrows(NoSuchFileException.class, () -> FileIo.readExperiment(d.resolve("nope.json")));
        } finally {
            cleanup(d);
        }
    }

    @Test
    void anOversizedFileIsRefusedBeforeItIsRead() throws IOException {
        Path d = dir();
        try {
            Path f = d.resolve("big.json");
            Files.write(f, new byte[(int) FileIo.MAX_EXPERIMENT_BYTES + 1]);
            ExperimentFile.FormatException e = assertThrows(ExperimentFile.FormatException.class,
                    () -> FileIo.readExperiment(f));
            assertTrue(e.getMessage().contains("1000001 bytes"), e.getMessage());
        } finally {
            cleanup(d);
        }
    }

    @Test
    void aFileOfExactlyTheLimitIsNotRefusedForSize() throws IOException {
        Path d = dir();
        try {
            Path f = d.resolve("limit.json");
            Files.write(f, new byte[(int) FileIo.MAX_EXPERIMENT_BYTES]);
            ExperimentFile.FormatException e = assertThrows(ExperimentFile.FormatException.class,
                    () -> FileIo.readExperiment(f));
            assertFalse(e.getMessage().contains("bytes; an experiment"), e.getMessage());
        } finally {
            cleanup(d);
        }
    }

    @Test
    void invalidUtf8IsRefusedNotGarbled() throws IOException {
        Path d = dir();
        try {
            Path f = d.resolve("bad.json");
            Files.write(f, new byte[] {'{', (byte) 0xC3, (byte) 0x28, '}'});
            ExperimentFile.FormatException e = assertThrows(ExperimentFile.FormatException.class,
                    () -> FileIo.readExperiment(f));
            assertTrue(e.getMessage().contains("not valid UTF-8"), e.getMessage());
        } finally {
            cleanup(d);
        }
    }

    @Test
    void notJsonOnDiskGivesAFormatError() throws IOException {
        Path d = dir();
        try {
            Path f = d.resolve("x.json");
            Files.writeString(f, "hello");
            assertThrows(ExperimentFile.FormatException.class, () -> FileIo.readExperiment(f));
        } finally {
            cleanup(d);
        }
    }

    @Test
    void extensionIsAddedOnlyWhenMissing() {
        Path base = Path.of("some", "dir");
        assertEquals(base.resolve("a.json"), FileIo.withExtension(base.resolve("a"), ".json"));
        assertEquals(base.resolve("a.json"), FileIo.withExtension(base.resolve("a.json"), ".json"));
        assertEquals(base.resolve("a.JSON"), FileIo.withExtension(base.resolve("a.JSON"), ".json"));
        assertEquals(base.resolve("a.txt.json"), FileIo.withExtension(base.resolve("a.txt"), ".json"));
    }

    @Test
    void baseNameDropsOnlyCsvAndJson() {
        assertEquals("run", FileIo.baseName(Path.of("run.csv")));
        assertEquals("run", FileIo.baseName(Path.of("run.json")));
        assertEquals("run", FileIo.baseName(Path.of("run.CSV")));
        assertEquals("run.v2", FileIo.baseName(Path.of("run.v2.csv")));
        assertEquals("run", FileIo.baseName(Path.of("run")));
        assertEquals("run.txt", FileIo.baseName(Path.of("dir", "run.txt")));
    }

    @Test
    void plannedExportListsOnlyWhatWillBeWritten() {
        Path chosen = Path.of("out", "run.csv");
        assertEquals(List.of(Path.of("out", "run-experiment.json")), FileIo.plannedExport(chosen, false, false, false));
        assertEquals(List.of(Path.of("out", "run-experiment.json"), Path.of("out", "run-drift.csv"),
                        Path.of("out", "run-error.csv"), Path.of("out", "run-twin.csv")),
                FileIo.plannedExport(chosen, true, true, true));
        assertEquals(List.of(Path.of("out", "run-experiment.json"), Path.of("out", "run-error.csv")),
                FileIo.plannedExport(chosen, false, true, false));
        assertEquals(List.of(Path.of("out", "run-experiment.json"), Path.of("out", "run-twin.csv")),
                FileIo.plannedExport(chosen, false, false, true));
    }

    @Test
    void exportWritesAllFilesWithTheContentOfTheCsvWriters() throws IOException {
        Path d = dir();
        try {
            TelemetryHistory drift = new TelemetryHistory(8);
            drift.offer(1, 0.5, 1e-9, 2e-12, 3e-10);
            GapHistory error = new GapHistory(8);
            error.offer(0.5, 1e-6, 1e-12);
            GapHistory twin = new GapHistory(8);
            twin.offer(0.5, 1e-5, Double.NaN);
            List<Path> written = FileIo.exportRun(d.resolve("run"), experiment(), "time_days", drift, error, twin);
            assertEquals(List.of("run-drift.csv", "run-error.csv", "run-experiment.json", "run-twin.csv"), names(d));
            assertEquals(4, written.size());
            assertEquals(CsvExport.drift(drift, "time_days"), Files.readString(d.resolve("run-drift.csv")));
            assertEquals(CsvExport.error(error, "time_days"), Files.readString(d.resolve("run-error.csv")));
            assertEquals(CsvExport.twin(twin, "time_days"), Files.readString(d.resolve("run-twin.csv")));
            assertEquals(experiment(), FileIo.readExperiment(d.resolve("run-experiment.json")));
        } finally {
            cleanup(d);
        }
    }

    @Test
    void emptyOrMissingHistoriesWriteNoCsv() throws IOException {
        Path d = dir();
        try {
            TelemetryHistory drift = new TelemetryHistory(8);
            drift.offer(1, 0.5, 1e-9, 2e-12, 3e-10);
            List<Path> written = FileIo.exportRun(d.resolve("run.csv"), experiment(), "time", drift,
                    new GapHistory(8), null);
            assertEquals(List.of("run-drift.csv", "run-experiment.json"), names(d));
            assertEquals(2, written.size());
            assertEquals(d.resolve("run-experiment.json"), written.get(0));
            assertEquals(d.resolve("run-drift.csv"), written.get(1));
        } finally {
            cleanup(d);
        }
    }

    @Test
    void emptyDriftAndTwinHistoriesAreSkippedLikeEmptyErrorOnes() throws IOException {
        Path d = dir();
        try {
            GapHistory error = new GapHistory(8);
            error.offer(0.5, 1e-6, 1e-12);
            FileIo.exportRun(d.resolve("run"), experiment(), "time", new TelemetryHistory(8), error,
                    new GapHistory(8));
            assertEquals(List.of("run-error.csv", "run-experiment.json"), names(d));
        } finally {
            cleanup(d);
        }
    }

    @Test
    void aFailedWriteLeavesNoTemporaryFile() throws IOException {
        Path d = dir();
        Path target = d.resolve("e.json");
        try {
            Files.createDirectory(target);
            Files.writeString(target.resolve("inside.txt"), "x");
            assertThrows(IOException.class, () -> FileIo.writeExperiment(target, experiment()));
            assertEquals(List.of("e.json"), names(d));
        } finally {
            Files.deleteIfExists(target.resolve("inside.txt"));
            cleanup(d);
        }
    }

    @Test
    void aBadTimeColumnWritesNothing() throws IOException {
        Path d = dir();
        try {
            TelemetryHistory drift = new TelemetryHistory(8);
            drift.offer(1, 0.5, 1e-9, 2e-12, 3e-10);
            assertThrows(IllegalArgumentException.class,
                    () -> FileIo.exportRun(d.resolve("run"), experiment(), "bad name", drift, null, null));
            assertEquals(List.of(), names(d));
        } finally {
            cleanup(d);
        }
    }

    @Test
    void anUnsavableScenarioWritesNothing() throws IOException {
        Path d = dir();
        try {
            ScenarioDraft bad = new ScenarioDraft("1.0", "0.0", "0.01", List.of(
                    new ScenarioDraft.BodyDraft("1.0", "x", "0.0", "0.0", "0.0", "0.0", "0.0"),
                    new ScenarioDraft.BodyDraft("1.0", "3.0", "0.0", "0.0", "0.0", "0.0", "0.0")));
            TelemetryHistory drift = new TelemetryHistory(8);
            drift.offer(1, 0.5, 1e-9, 2e-12, 3e-10);
            assertThrows(ExperimentFile.FormatException.class, () -> FileIo.exportRun(d.resolve("run"),
                    new ExperimentFile("t", "", "i", bad), "time", drift, null, null));
            assertEquals(List.of(), names(d));
        } finally {
            cleanup(d);
        }
    }

    @Test
    void exportReplacesFilesOfTheSameName() throws IOException {
        Path d = dir();
        try {
            Files.writeString(d.resolve("run-drift.csv"), "old");
            TelemetryHistory drift = new TelemetryHistory(8);
            drift.offer(1, 0.5, 1e-9, 2e-12, 3e-10);
            FileIo.exportRun(d.resolve("run"), experiment(), "time", drift, null, null);
            assertEquals(CsvExport.drift(drift, "time"), Files.readString(d.resolve("run-drift.csv")));
        } finally {
            cleanup(d);
        }
    }
}
