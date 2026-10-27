package dev.chaoslab.ui;

import dev.chaoslab.analysis.ComparisonPlan;
import dev.chaoslab.analysis.IntegratorComparison.Report;
import dev.chaoslab.config.ScenarioDraft.Scenario;
import dev.chaoslab.integration.NumericalFailureException;
import dev.chaoslab.io.FileIo;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

/**
 * A window that runs the same scenario with Euler, RK4, Velocity Verlet and fixed-step Dormand-Prince at one common
 * step, against a high-accuracy reference, and prints the table and a few plain sentences about it.
 *
 * <p>All the planning, the limits and the wording are in {@link ComparisonPlan}, which has unit tests. This class only
 * reads the duration, runs the comparison on a background thread so the main window stays responsive, and shows the
 * result. It uses the <em>applied</em> scenario, not values typed into the scenario panel but not yet applied.
 */
final class ComparisonDialog {

    /** Characters per line before the text is wrapped; the window shows about 130 at this font size. */
    private static final int WRAP_COLUMNS = 118;
    private static final String MONO = "-fx-font-family: 'Consolas'; -fx-font-size: 12px;";

    private final Scenario scenario;
    private final double timeScale;
    private final String unit;
    private final Stage window = new Stage();
    private final TextField durationField = new TextField();
    private final Button runButton = new Button("Run comparison");
    private final Button saveButton = new Button("Save CSV...");
    private final Label status = new Label();
    private final TextArea output = new TextArea();
    private final ComparisonPlot plot = new ComparisonPlot();
    private AtomicBoolean cancelFlag = new AtomicBoolean();
    private boolean running;
    private File directory;
    private Report lastReport;
    private int generation;

    private ComparisonDialog(Stage owner, String title, Scenario scenario, double timeScale, String unit,
                             double defaultDuration, File directory) {
        this.scenario = scenario;
        this.timeScale = timeScale;
        this.unit = unit;
        this.directory = directory;

        Label heading = new Label("Compare integrators on: " + title);
        heading.setStyle("-fx-font-size: 14px; -fx-text-fill: #e5edf8; -fx-font-weight: bold;");
        heading.setWrapText(true);
        Label explain = new Label("Runs Euler, RK4, Velocity Verlet and Dormand-Prince with a fixed step, all at the"
                + " scenario's step (shown below, adjusted slightly so the duration divides into whole steps), and"
                + " the adaptive Dormand-Prince at tolerance 1e-10 (it picks its own steps, so its step column is the"
                + " mean and its cost is not comparable), and measures each against a high-accuracy reference. The"
                + " scenario is the applied one, not unapplied edits in the panel.");
        explain.setWrapText(true);
        explain.setStyle("-fx-text-fill: #9fb0c8;");

        durationField.setText(String.format(Locale.ROOT, "%.6g", defaultDuration / timeScale));
        durationField.setPrefColumnCount(12);
        Label durationLabel = new Label("Duration (" + unit + ")");
        durationLabel.setStyle("-fx-text-fill: #e5edf8;");
        HBox row = new HBox(10, durationLabel, durationField, runButton, saveButton);
        row.setStyle("-fx-alignment: center-left;");
        saveButton.setDisable(true);

        status.setWrapText(true);
        status.setStyle(MONO + " -fx-text-fill: #22d3ee;");
        plot.setPrefHeight(270);
        plot.setManaged(false);
        plot.setVisible(false);
        output.setEditable(false);
        output.setWrapText(false);
        output.setStyle(MONO + " -fx-control-inner-background: #0b1220; -fx-text-fill: #e5edf8;");
        VBox.setVgrow(output, Priority.ALWAYS);

        VBox root = new VBox(8, heading, explain, row, status, plot, output);
        root.setPadding(new Insets(12, 14, 12, 14));
        root.setStyle("-fx-background-color: #070b14;");

        window.setTitle("Compare integrators");
        window.setScene(new Scene(root, 980, 780));
        window.initOwner(owner);
        window.setOnHidden(e -> {
            generation++; // a result still being computed is then ignored
            cancelFlag.set(true); // and the worker stops at its next sampling interval
        });

        runButton.setOnAction(e -> run());
        durationField.setOnAction(e -> run());
        saveButton.setOnAction(e -> saveCsv());
    }

    /**
     * Opens the window.
     *
     * @param defaultDuration the duration to suggest, in simulation units
     * @param timeScale simulation units per displayed time unit (86400 for days)
     */
    static void open(Stage owner, String title, Scenario scenario, double timeScale, String unit,
                     double defaultDuration, File directory) {
        ComparisonDialog d = new ComparisonDialog(owner, title, scenario, timeScale, unit, defaultDuration, directory);
        d.window.show();
    }

    private void run() {
        if (running) {
            cancelFlag.set(true);
            runButton.setDisable(true);
            status.setText("Cancelling: the run stops at the end of the current sampling interval.");
            return;
        }
        ComparisonPlan.Plan plan;
        try {
            double duration = ComparisonPlan.parseDuration(durationField.getText(), timeScale);
            plan = ComparisonPlan.plan(scenario.stepSize(), duration);
        } catch (IllegalArgumentException e) {
            warn(e.getMessage());
            return;
        }
        running = true;
        runButton.setText("Cancel");
        saveButton.setDisable(true);
        plot.setManaged(false);
        plot.setVisible(false);
        output.setText("");
        final AtomicBoolean myFlag = new AtomicBoolean();
        cancelFlag = myFlag;
        status.setStyle(MONO + " -fx-text-fill: #22d3ee;");
        status.setText("Running " + plan.stepsPerMethod() + " steps for each of four methods, plus two reference"
                + " runs. This can take a while for long durations; you can keep using the main window.");
        final int mine = ++generation;
        final ComparisonPlan.Plan chosen = plan;
        Thread worker = new Thread(() -> {
            Report report = null;
            String failure = null;
            try {
                report = ComparisonPlan.run(scenario.system(), scenario.initial(), scenario.stepSize(), chosen.duration(), myFlag::get);
            } catch (CancellationException e) {
                failure = "Cancelled.";
            } catch (NumericalFailureException e) {
                failure = "The reference run itself failed, so there is nothing to compare against: " + e.getMessage()
                        + " This usually means a very close encounter inside the interval; try a shorter duration.";
            } catch (RuntimeException e) {
                failure = "The comparison failed: " + e;
            }
            final Report done = report;
            final String problem = failure;
            Platform.runLater(() -> finish(mine, chosen, done, problem));
        }, "chaoslab-compare");
        worker.setDaemon(true);
        worker.start();
    }

    private void finish(int mine, ComparisonPlan.Plan plan, Report report, String problem) {
        if (mine != generation) {
            return; // the window was closed or another run was started
        }
        running = false;
        runButton.setText("Run comparison");
        runButton.setDisable(false);
        if (report == null) {
            warn(problem);
            return;
        }
        lastReport = report;
        saveButton.setDisable(false);
        status.setStyle(MONO + " -fx-text-fill: #22d3ee;");
        status.setText("Done.");
        StringBuilder sb = new StringBuilder();
        sb.append("Duration ").append(ComparisonPlan.describeDuration(plan.duration(), timeScale, unit))
                .append("; step used h = ").append(String.format(Locale.ROOT, "%.6g", plan.stepSize()))
                .append(" (scenario step ").append(String.format(Locale.ROOT, "%.6g", scenario.stepSize()))
                .append("), ").append(plan.stepsPerMethod()).append(" steps per method, ")
                .append(plan.samples()).append(" samples.\n");
        if (timeScale != 1.0) {
            sb.append("Times in the table and the CSV file are in seconds, and separations in metres.\n");
        }
        sb.append('\n').append(report.toTable()).append("\nWhat this shows\n");
        for (String line : ComparisonPlan.summary(report)) {
            sb.append("  - ").append(line).append('\n');
        }
        output.setText(ComparisonPlan.wrapLines(sb.toString(), WRAP_COLUMNS));
        plot.show(ComparisonSeries.of(report, timeScale), "time in " + unit);
        plot.setManaged(true);
        plot.setVisible(true);
    }

    private void warn(String text) {
        status.setStyle(MONO + " -fx-text-fill: #fbbf24;");
        status.setText(text);
        runButton.setDisable(false);
    }

    private void saveCsv() {
        if (lastReport == null) {
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save comparison samples");
        chooser.setInitialFileName("integrator-comparison.csv");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV (*.csv)", "*.csv"));
        if (directory != null && directory.isDirectory()) {
            chooser.setInitialDirectory(directory);
        }
        File file = chooser.showSaveDialog(window);
        if (file == null) {
            return;
        }
        directory = file.getParentFile();
        Path path = FileIo.withExtension(file.toPath(), ".csv");
        try {
            Files.writeString(path, lastReport.toCsv(), StandardCharsets.UTF_8);
            status.setStyle(MONO + " -fx-text-fill: #22d3ee;");
            status.setText("Saved " + path.getFileName() + ".");
        } catch (IOException e) {
            warn("Could not save " + path.getFileName() + ": " + e.getMessage());
        }
    }
}
