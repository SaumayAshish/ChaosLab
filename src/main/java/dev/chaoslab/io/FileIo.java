package dev.chaoslab.io;

import dev.chaoslab.ui.GapHistory;
import dev.chaoslab.ui.TelemetryHistory;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reading and writing the files of {@link ExperimentFile} and {@link CsvExport}. Everything is UTF-8. A file is written
 * to a temporary name beside the target and then moved over it, so a failure half way leaves the old file intact rather
 * than half of a new one.
 *
 * <p>One export writes up to four files that share a base name: {@code <base>-experiment.json} (the scenario and
 * integrator, loadable again), {@code <base>-drift.csv}, {@code <base>-error.csv} and {@code <base>-twin.csv}. A CSV is
 * written only if its history has entries (no reference yet, or no twin run, means no file rather than a file with only
 * a header). The experiment file is always written, so the curves can always be tied to the settings that produced them.
 */
public final class FileIo {

    /** Largest experiment file read; real ones are a few kilobytes. */
    public static final long MAX_EXPERIMENT_BYTES = 1_000_000L;

    private FileIo() {
    }

    // ---------------------------------------------------------------- experiment files

    /** Writes the experiment file, replacing any existing file at {@code path}. */
    public static void writeExperiment(Path path, ExperimentFile file) throws IOException {
        writeText(path, file.toJson());
    }

    /**
     * Reads an experiment file.
     *
     * @throws ExperimentFile.FormatException if the file is too large, not valid UTF-8, or not an experiment
     * @throws IOException                    if it cannot be read
     */
    public static ExperimentFile readExperiment(Path path) throws IOException {
        long size = Files.size(path);
        if (size > MAX_EXPERIMENT_BYTES) {
            throw new ExperimentFile.FormatException("The file is " + size + " bytes; an experiment file is far"
                    + " smaller than " + MAX_EXPERIMENT_BYTES + " bytes, so this is probably not one.");
        }
        byte[] bytes = Files.readAllBytes(path);
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new ExperimentFile.FormatException("The file is not valid UTF-8 text.");
        }
        return ExperimentFile.fromJson(text);
    }

    // ---------------------------------------------------------------- names

    /** {@code chosen} with the given extension (such as {@code ".json"}) added unless it already ends with it. */
    public static Path withExtension(Path chosen, String extension) {
        String name = chosen.getFileName().toString();
        if (name.toLowerCase(Locale.ROOT).endsWith(extension.toLowerCase(Locale.ROOT))) {
            return chosen;
        }
        return chosen.resolveSibling(name + extension);
    }

    /** The file name without a trailing {@code .csv} or {@code .json} (any letter case). */
    public static String baseName(Path chosen) {
        String name = chosen.getFileName().toString();
        String lower = name.toLowerCase(Locale.ROOT);
        for (String ext : new String[] {".csv", ".json"}) {
            if (lower.endsWith(ext)) {
                return name.substring(0, name.length() - ext.length());
            }
        }
        return name;
    }

    /** The files an export to {@code chosen} would write, in the order they are written. */
    public static List<Path> plannedExport(Path chosen, boolean drift, boolean error, boolean twin) {
        String base = baseName(chosen);
        List<Path> list = new ArrayList<>();
        list.add(chosen.resolveSibling(base + "-experiment.json"));
        if (drift) {
            list.add(chosen.resolveSibling(base + "-drift.csv"));
        }
        if (error) {
            list.add(chosen.resolveSibling(base + "-error.csv"));
        }
        if (twin) {
            list.add(chosen.resolveSibling(base + "-twin.csv"));
        }
        return list;
    }

    // ---------------------------------------------------------------- export

    /**
     * Writes the experiment file and the CSV of every non-empty history, replacing files of the same names.
     *
     * @param chosen     the name the person picked; the base name is taken from it
     * @param timeColumn name of the time column, for example {@code time} or {@code time_days}
     * @param error      null if there is no reference history
     * @param twin       null if there is no twin history
     * @return the files written, in order
     */
    public static List<Path> exportRun(Path chosen, ExperimentFile experiment, String timeColumn,
                                       TelemetryHistory drift, GapHistory error, GapHistory twin) throws IOException {
        boolean hasDrift = drift != null && drift.size() > 0;
        boolean hasError = error != null && error.size() > 0;
        boolean hasTwin = twin != null && twin.size() > 0;
        // render everything first, so a bad value stops the export before any file is touched
        List<Path> paths = plannedExport(chosen, hasDrift, hasError, hasTwin);
        List<String> texts = new ArrayList<>();
        texts.add(experiment.toJson());
        if (hasDrift) {
            texts.add(CsvExport.drift(drift, timeColumn));
        }
        if (hasError) {
            texts.add(CsvExport.error(error, timeColumn));
        }
        if (hasTwin) {
            texts.add(CsvExport.twin(twin, timeColumn));
        }
        for (int i = 0; i < paths.size(); i++) {
            writeText(paths.get(i), texts.get(i));
        }
        return paths;
    }

    private static void writeText(Path path, String text) throws IOException {
        Path temp = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            Files.writeString(temp, text, StandardCharsets.UTF_8);
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
