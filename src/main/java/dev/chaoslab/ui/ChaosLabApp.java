package dev.chaoslab.ui;

import dev.chaoslab.analysis.ReferenceMonitor;
import dev.chaoslab.analysis.TwinMonitor;
import dev.chaoslab.analysis.TwinRun;
import dev.chaoslab.config.ScenarioDraft;
import dev.chaoslab.config.ScenarioDraft.Scenario;
import dev.chaoslab.integration.DormandPrince45Integrator;
import dev.chaoslab.integration.DormandPrince45Integrator.Tolerances;
import dev.chaoslab.integration.EulerIntegrator;
import dev.chaoslab.integration.Integrator;
import dev.chaoslab.integration.Rk4Integrator;
import dev.chaoslab.integration.VelocityVerletIntegrator;
import dev.chaoslab.io.ExperimentFile;
import dev.chaoslab.io.FileIo;
import dev.chaoslab.physics.NBodySystem;
import dev.chaoslab.physics.State;
import dev.chaoslab.simulation.SimulationConfig;
import dev.chaoslab.simulation.SimulationRunner;
import dev.chaoslab.simulation.Snapshot;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.control.TitledPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Screen;
import javafx.stage.Stage;

/**
 * The ChaosLab window: the orbit view, live telemetry, the conservation-drift plot, the numerical-error plot, the run
 * controls and the scenario editor. The simulation runs on its own thread inside a {@link SimulationRunner}; this
 * class only sends it commands and, once per rendered frame, reads the latest immutable {@link Snapshot}. A scenario
 * reaches the runner only after {@link ConfigPanel} has validated it.
 *
 * <h2>Two different divergences, shown and named differently</h2>
 * A {@link ReferenceMonitor} on a thread of its own compares the displayed run with a high-accuracy reference.
 * <ul>
 *   <li><b>Numerical error</b> (coral): how far the displayed run is from the reference. It depends on the integrator
 *       and the step chosen, and is drawn as the dashed ghost in the orbit view and in the error plot.</li>
 *   <li><b>Physical sensitivity</b> (violet): the Lyapunov exponent of the motion itself, how fast two nearby starts
 *       separate. It does not depend on the integrator.</li>
 * </ul>
 * The two are linked: in chaotic motion every numerical error grows roughly like e^(lambda t).
 *
 * <h2>The twin run</h2>
 * With "Twin run" ticked, a {@link TwinMonitor} follows the same start and a second start with body 1 nudged by 1e-6 of
 * the system size, both with the high-accuracy reference integrator. Their separation is drawn as a violet curve on the
 * error plot (same axis as the coral numerical error, so physical divergence and numerical error can be compared
 * directly), as violet rings in the orbit view, and in a readout line that compares the measured growth with the
 * Lyapunov prediction only when the exponent rule claims an exponent. The twin does not depend on the displayed
 * integrator at all.
 *
 * <h2>The explanation section</h2>
 * The right-hand column has two foldable sections, "Scenario editor" and "What you are seeing"; folding the editor
 * leaves the whole column to the explanation. "What you are seeing" restates the measurements as a few plain statements (conservation,
 * accuracy against the reference, sensitivity, the twin, the reference's own limit). The wording and thresholds live
 * in {@link Explanation}, which is tested; this class only collects the numbers and shows the result about twice a
 * second, rebuilding the labels only when the text changes.
 *
 * <h2>Saving, loading and exporting</h2>
 * "Save experiment" writes the values currently in the scenario panel, with the chosen integrator, as a JSON file
 * ({@link ExperimentFile}); "Load experiment" reads one back, puts it in the panel and applies it at once if it is
 * valid. "Export CSV" writes the curves the window has collected so far (drift, numerical error, twin separation) next
 * to a JSON file of the running scenario, so the curves can always be tied to the settings that produced them.
 *
 * <p>The ghost is drawn by {@link ReferenceAligner}: each reference reading is a few frames older than the bodies on
 * screen, so the ring is placed at the body's current position plus the error vector measured for that reading.
 */
public final class ChaosLabApp extends Application {

    private static final double ENERGY_WARNING_THRESHOLD = 1e-3;
    private static final int HISTORY_CAPACITY = 2400;
    private static final int GAP_CAPACITY = 2400;
    /** Submissions remembered for aligning the ghost; at 120 snapshots per second this covers about two seconds. */
    private static final int ALIGNER_CAPACITY = 256;
    private static final double PLOT_HEIGHT = 160.0;
    private static final long EXPLAIN_INTERVAL_NANOS = 400_000_000L;
    private static final double WINDOW_WIDTH = 1560.0;
    private static final double WINDOW_HEIGHT = 920.0;
    private static final double SECONDS_PER_DAY = 86_400.0;
    private static final String CORAL = "-fx-font-family: 'Consolas'; -fx-font-size: 12px; -fx-text-fill: #fb7185;";
    private static final String VIOLET = "-fx-font-family: 'Consolas'; -fx-font-size: 12px; -fx-text-fill: #a78bfa;";
    private static final String AMBER = "-fx-font-family: 'Consolas'; -fx-font-size: 12px; -fx-text-fill: #fbbf24;";

    /** An integrator choice shown in the combo box; {@code tolerances} is null for fixed-step methods. */
    private record IntegratorOption(String label, Supplier<Integrator> factory, Tolerances tolerances) {
        IntegratorOption(String label, Supplier<Integrator> factory) {
            this(label, factory, null);
        }

        static IntegratorOption adaptive(double tolerance, String text) {
            return new IntegratorOption("Dormand-Prince 5(4), adaptive, tol " + text, DormandPrince45Integrator::new,
                    new Tolerances(tolerance, tolerance));
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private final OrbitView view = new OrbitView();
    private final TelemetryHistory history = new TelemetryHistory(HISTORY_CAPACITY);
    private final TelemetryPlot plot = new TelemetryPlot();
    private final GapHistory gapHistory = new GapHistory(GAP_CAPACITY);
    private final GapPlot gapPlot = new GapPlot();
    private final ReferenceAligner aligner = new ReferenceAligner(ALIGNER_CAPACITY);
    private final Label errorLine = new Label();
    private final Label sensitivityLine = new Label();
    private final Label captionLine = new Label();
    private final Label statusLine = new Label();
    private final Label driftLine = new Label();
    private final Label warningLine = new Label();
    private final Label scenarioLabel = new Label();
    private final Button runPause = new Button("Run");
    private final Slider speedSlider = new Slider(-1.0, 2.5, Math.log10(2.0));
    private final Label speedLabel = new Label();
    private final ComboBox<IntegratorOption> integratorBox = new ComboBox<>();
    private final ComboBox<Integer> stepCountBox = new ComboBox<>();
    private final ComboBox<String> frameBox = new ComboBox<>();
    private boolean updatingFrameBox;
    private final CheckBox twinBox = new CheckBox("Twin run");
    private final GapHistory twinHistory = new GapHistory(GAP_CAPACITY);
    private final Label twinLine = new Label();
    private final Label fileLine = new Label();
    private final VBox explainBox = new VBox(8);
    private final Label equationsText = new Label();
    private Snapshot latestSnapshot;
    private long lastExplainNanos;
    private String lastExplainKey = "";
    private final DriftExtremes driftExtremes = new DriftExtremes();

    private Stage stage;
    private File lastDirectory;
    private boolean suppressIntegratorEvent;
    private ConfigPanel configPanel;
    private ConfigPanel.Applied applied;
    private SimulationRunner runner;
    private ReferenceMonitor monitor;
    private ReferenceMonitor.Reading lastReading;
    private TwinMonitor twinMonitor;
    private TwinRun.Reading lastTwin;
    private NBodySystem twinSystem;
    private State twinStart;
    private double[] latestPositions;
    private AnimationTimer timer;
    private long lastSequence = -1;

    /** Simulation time units per displayed time unit: 1 normally, one day in seconds for SI scenarios. */
    private double timeScale = 1.0;

    @Override
    public void start(Stage primaryStage) {
        this.stage = primaryStage;
        integratorBox.getItems().addAll(
                new IntegratorOption("Velocity Verlet (symplectic, order 2)", VelocityVerletIntegrator::new),
                new IntegratorOption("RK4 (order 4)", Rk4Integrator::new),
                new IntegratorOption("Dormand-Prince 5(4), fixed step", DormandPrince45Integrator::new),
                new IntegratorOption("Euler (order 1, for contrast)", EulerIntegrator::new),
                IntegratorOption.adaptive(1e-8, "1e-8"),
                IntegratorOption.adaptive(1e-10, "1e-10"),
                IntegratorOption.adaptive(1e-12, "1e-12"));
        integratorBox.getSelectionModel().selectFirst();
        stepCountBox.getItems().addAll(1, 10, 100, 1000);
        stepCountBox.getSelectionModel().select(Integer.valueOf(100));

        configPanel = new ConfigPanel(a -> {
            applied = a;
            startRunner();
        });
        applied = configPanel.applied();

        statusLine.setStyle("-fx-font-family: 'Consolas'; -fx-font-size: 13px; -fx-text-fill: #e5edf8;");
        driftLine.setStyle("-fx-font-family: 'Consolas'; -fx-font-size: 12px; -fx-text-fill: #22d3ee;");
        warningLine.setStyle("-fx-font-family: 'Consolas'; -fx-font-size: 12px; -fx-text-fill: #fbbf24;");
        warningLine.setWrapText(true);
        scenarioLabel.setStyle("-fx-text-fill: #9fb0c8; -fx-font-size: 11px;");
        scenarioLabel.setWrapText(true);
        errorLine.setStyle(CORAL);
        errorLine.setWrapText(true);
        sensitivityLine.setStyle(VIOLET);
        sensitivityLine.setWrapText(true);
        twinLine.setStyle(VIOLET);
        twinLine.setWrapText(true);
        twinLine.setVisible(false);
        twinLine.setManaged(false);
        fileLine.setWrapText(true);
        fileLine.setVisible(false);
        fileLine.setManaged(false);
        captionLine.setStyle("-fx-text-fill: #6b7c96; -fx-font-size: 11px;");
        captionLine.setWrapText(true);
        captionLine.setText("Coral = numerical error: how far the displayed run is from a high-accuracy reference "
                + "(it changes with the integrator and step you choose). Violet = physical sensitivity: the "
                + "Lyapunov exponent of the motion itself (it does not depend on the integrator). In chaotic "
                + "motion any numerical error grows roughly like e^(\u03bb t) until the run no longer follows the "
                + "true orbit.");

        VBox top = new VBox(4, statusLine, driftLine, errorLine, sensitivityLine, twinLine, warningLine, fileLine,
                scenarioLabel, captionLine);
        top.setPadding(new Insets(10, 14, 8, 14));

        Button stepButton = new Button("Step");
        Button resetButton = new Button("Reset");
        Button fitButton = new Button("Fit");
        Button resetViewButton = new Button("Reset view");
        CheckBox trailsBox = new CheckBox("Trails");
        CheckBox vectorsBox = new CheckBox("Vectors");
        CheckBox ghostBox = new CheckBox("Reference (ghost)");
        CheckBox autoFitBox = new CheckBox("Auto-fit");
        Button compareButton = new Button("Compare integrators...");
        compareButton.setTooltip(new Tooltip("Runs Euler, RK4, Velocity Verlet and fixed-step Dormand-Prince on the"
                + " applied scenario at its step and compares their energy drift and position with a reference."));
        Button saveButton = new Button("Save experiment...");
        Button loadButton = new Button("Load experiment...");
        Button exportButton = new Button("Export CSV...");
        saveButton.setTooltip(new Tooltip("Saves the values now in the scenario panel, and the integrator, as JSON."));
        loadButton.setTooltip(new Tooltip("Loads a saved experiment into the scenario panel and applies it."));
        exportButton.setTooltip(new Tooltip("Writes the drift, error and twin curves collected so far as CSV files,"
                + " plus a JSON file of the running scenario."));
        trailsBox.selectedProperty().bindBidirectional(view.showTrailsProperty());
        vectorsBox.selectedProperty().bindBidirectional(view.showVectorsProperty());
        ghostBox.selectedProperty().bindBidirectional(view.showGhostProperty());
        autoFitBox.selectedProperty().bindBidirectional(view.autoFitProperty());
        speedSlider.setPrefWidth(180);

        FlowPane controls = new FlowPane(10, 8, runPause, stepButton, stepCountBox, resetButton,
                new Label("Speed"), speedSlider, speedLabel, new Label("Integrator"), integratorBox,
                new Label("Frame"), frameBox,
                fitButton, resetViewButton, autoFitBox, trailsBox, vectorsBox, ghostBox, twinBox,
                compareButton, saveButton, loadButton, exportButton);
        controls.setPadding(new Insets(8, 14, 10, 14));
        controls.setStyle("-fx-alignment: center-left;");

        plot.setPrefHeight(PLOT_HEIGHT);
        gapPlot.setPrefHeight(PLOT_HEIGHT);
        BorderPane centre = new BorderPane();
        centre.setCenter(view);
        centre.setBottom(new VBox(2, plot, gapPlot));

        Label explainNote = new Label("Generated from the measured numbers only. The thresholds (for example"
                + " 'conserved' = drift of at most 1e-6) are rules of thumb for this program, not laws.");
        explainNote.setStyle("-fx-text-fill: #6b7c96; -fx-font-size: 11px;");
        explainNote.setWrapText(true);
        explainNote.setMaxWidth(520);
        Label equationsHeading = new Label("Equations");
        equationsHeading.setStyle("-fx-text-fill: #a78bfa; -fx-font-size: 12px; -fx-font-weight: bold;");
        equationsText.setWrapText(true);
        equationsText.setMaxWidth(520);
        equationsText.setStyle("-fx-text-fill: #c9d6ea; -fx-font-size: 12px; -fx-font-family: 'Consolas';");
        VBox explainSection = new VBox(8, explainNote, explainBox, new VBox(2, equationsHeading, equationsText));
        explainSection.setPadding(new Insets(4, 14, 16, 14));
        explainSection.setStyle("-fx-background-color: #0b1220;");
        // two sections that can be folded; each scrolls inside itself, so both title bars stay on screen
        ScrollPane editorScroll = new ScrollPane(configPanel);
        editorScroll.setFitToWidth(true);
        ScrollPane explainScroll = new ScrollPane(explainSection);
        explainScroll.setFitToWidth(true);
        TitledPane editorPane = new TitledPane("Scenario editor", editorScroll);
        TitledPane explainPane = new TitledPane("What you are seeing", explainScroll);
        VBox configScroll = new VBox(editorPane, explainPane);
        for (TitledPane pane : new TitledPane[] {editorPane, explainPane}) {
            pane.setExpanded(true);
            pane.setAnimated(false);
            // like Accordion: only an open section takes the spare height; a folded one is just its title bar
            pane.setMaxHeight(Double.MAX_VALUE);
            pane.expandedProperty().addListener((o, was, open) ->
                    pane.setMaxHeight(open ? Double.MAX_VALUE : Region.USE_PREF_SIZE));
            VBox.setVgrow(pane, Priority.ALWAYS);
        }
        configScroll.setPrefWidth(680);

        BorderPane root = new BorderPane();
        root.setTop(top);
        root.setCenter(centre);
        root.setRight(configScroll);
        root.setBottom(controls);
        root.setStyle("-fx-base: #0b1220; -fx-background: #070b14; -fx-accent: #22d3ee; "
                + "-fx-control-inner-background: #0d1424; -fx-text-base-color: #e5edf8;");

        startRunner();

        runPause.setOnAction(e -> {
            if (runner.snapshot().status() == Snapshot.Status.RUNNING) {
                runner.pause();
            } else {
                runner.run();
            }
        });
        stepButton.setOnAction(e -> runner.step(stepCountBox.getValue()));
        resetButton.setOnAction(e -> {
            runner.reset();
            driftExtremes.reset();
            view.clearTrails();
            if (monitor != null) {
                monitor.reset();
            }
            lastReading = null;
            aligner.clear();
            gapHistory.clear();
            gapPlot.show(gapHistory);
            if (twinMonitor != null) {
                twinMonitor.reset();
            }
            lastTwin = null;
            twinHistory.clear();
            gapPlot.showTwin(twinMonitor == null ? null : twinHistory);
        });
        twinBox.setOnAction(e -> restartTwin());
        saveButton.setOnAction(e -> saveExperiment());
        loadButton.setOnAction(e -> loadExperiment());
        exportButton.setOnAction(e -> exportCsv());
        compareButton.setOnAction(e -> compareIntegrators());
        fitButton.setOnAction(e -> view.fitNow());
        resetViewButton.setOnAction(e -> view.resetView());
        speedSlider.valueProperty().addListener((o, was, is) -> {
            speedLabel.setText(speedText());
            runner.setSpeed(currentSpeed());
        });
        frameBox.setOnAction(e -> {
            if (!updatingFrameBox && frameBox.getSelectionModel().getSelectedIndex() >= 0) {
                view.setFrame(frameBox.getSelectionModel().getSelectedIndex() - 1);
            }
        });
        integratorBox.setOnAction(e -> {
            if (suppressIntegratorEvent) {
                return;
            }
            configPanel.setFixedStep(integratorBox.getValue().tolerances() == null);
            startRunner();
        });

        timer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                Snapshot s = runner.snapshot();
                latestSnapshot = s;
                if (s.sequence() != lastSequence) {
                    lastSequence = s.sequence();
                    view.show(s);
                    history.offer(s.stepCount(), s.state().time() / timeScale, s.drift().relativeEnergy(),
                            s.drift().relativeMomentum(), s.drift().relativeAngularMomentum());
                    driftExtremes.offer(s.drift().relativeEnergy(), s.drift().relativeMomentum(),
                            s.drift().relativeAngularMomentum());
                    plot.show(history);
                    updateTelemetry(s);
                    latestPositions = s.state().positions();
                    aligner.remember(s.state().time(), latestPositions);
                    submitToMonitor(s);
                    submitToTwin(s);
                }
                showLatestReading();
                showLatestTwin();
                if (now - lastExplainNanos >= EXPLAIN_INTERVAL_NANOS) {
                    lastExplainNanos = now;
                    refreshExplanation();
                }
            }
        };
        timer.start();

        // Size the window to the usable screen (after display scaling), so the control row at the bottom can never
        // end up below the screen edge. The sizes are the scene's, which excludes the title bar and borders.
        Rectangle2D screen = Screen.getPrimary().getVisualBounds();
        double width = Math.min(WINDOW_WIDTH, screen.getWidth() - 20.0);
        double height = Math.min(WINDOW_HEIGHT, screen.getHeight() - 80.0);
        stage.setTitle("ChaosLab - three-body laboratory");
        stage.setScene(new Scene(root, width, height));
        stage.setX(screen.getMinX() + (screen.getWidth() - width) / 2.0);
        stage.setY(screen.getMinY() + 10.0);
        stage.show();
    }

    @Override
    public void stop() {
        if (timer != null) {
            timer.stop();
        }
        if (runner != null) {
            runner.close();
        }
        if (monitor != null) {
            monitor.close();
        }
        if (twinMonitor != null) {
            twinMonitor.close();
        }
    }

    // ---------------------------------------------------------------- explanation

    /** Collects the current measurements, asks {@link Explanation} what they mean, and shows it if it changed. */
    private void refreshExplanation() {
        Snapshot s = latestSnapshot;
        if (s == null) {
            return;
        }
        ReferenceMonitor.Reading r = lastReading;
        TwinRun.Reading t = lastTwin;
        double lambda = r == null ? Double.NaN : r.lyapunovRunning() * timeScale;
        ExponentRule.Verdict verdict = r == null ? ExponentRule.Verdict.NO_DATA
                : ExponentRule.judge(r.renormalisations(), lambda, r.lyapunovSlope() * timeScale);
        Explanation.Facts facts = new Explanation.Facts(
                s.status() == Snapshot.Status.FAILED, s.failure(), integratorBox.getValue().tolerances() != null,
                timeScale == 1.0 ? "t" : "d",
                driftExtremes.energy(), driftExtremes.momentum(),
                driftExtremes.angularMomentum(), applied.scenario().system().softening(),
                r == null ? Double.NaN : r.relativeGap(), r == null ? Double.NaN : r.relativeUncertainty(),
                r == null ? null : r.referenceFailure(), verdict, lambda, r == null ? 0 : r.renormalisations(),
                twinMonitor != null && t != null && t.failure() == null ? t.relativeSeparation() : Double.NaN);
        List<Explanation.Finding> findings = Explanation.explain(facts);

        StringBuilder key = new StringBuilder();
        for (Explanation.Finding f : findings) {
            key.append(f.level()).append('|').append(f.heading()).append('|').append(f.text()).append('\n');
        }
        if (key.toString().equals(lastExplainKey)) {
            return;
        }
        lastExplainKey = key.toString();
        explainBox.getChildren().clear();
        for (Explanation.Finding f : findings) {
            String colour = switch (f.level()) {
                case OK -> "#34d399";
                case INFO -> "#22d3ee";
                case WARNING -> "#fbbf24";
            };
            Label heading = new Label(f.heading());
            heading.setStyle("-fx-text-fill: " + colour + "; -fx-font-size: 12px; -fx-font-weight: bold;");
            Label text = new Label(f.text());
            text.setWrapText(true);
            text.setMaxWidth(520);
            text.setStyle("-fx-text-fill: #c9d6ea; -fx-font-size: 12px;");
            explainBox.getChildren().add(new VBox(2, heading, text));
        }
    }

    // ---------------------------------------------------------------- files

    /** Opens the integrator comparison window for the applied scenario. */
    private void compareIntegrators() {
        Scenario scenario = applied.scenario();
        double step = scenario.stepSize();
        double elapsed = latestSnapshot == null ? 0.0 : latestSnapshot.state().time() - scenario.initial().time();
        // suggest the time already simulated if it is a useful length, otherwise a thousand steps
        double suggested = elapsed >= 20.0 * step ? Math.min(elapsed, 100_000.0 * step) : 1000.0 * step;
        ComparisonDialog.open(stage, applied.title(), scenario, timeScale, timeScale == 1.0 ? "t" : "d", suggested,
                lastDirectory);
    }

    /** Writes the scenario panel's values and the integrator to a JSON file chosen by the person. */
    private void saveExperiment() {
        ExperimentFile experiment = new ExperimentFile(configPanel.currentTitle(), configPanel.currentNote(),
                integratorBox.getValue().label(), configPanel.currentDraft());
        FileChooser chooser = chooser("Save experiment", "ChaosLab experiment (*.json)", "*.json");
        chooser.setInitialFileName(safeName(configPanel.currentTitle()) + ".json");
        File file = chooser.showSaveDialog(stage);
        if (file == null) {
            return;
        }
        lastDirectory = file.getParentFile();
        Path path = FileIo.withExtension(file.toPath(), ".json");
        if (!path.equals(file.toPath()) && !confirmOverwrite(List.of(path))) {
            return;
        }
        try {
            FileIo.writeExperiment(path, experiment);
            fileMessage("Saved " + path.getFileName() + " (" + experiment.integrator() + "). It holds the values in the"
                    + " scenario panel, which may differ from the running scenario if you have edited them.", false);
        } catch (IllegalArgumentException | IOException e) {
            problem("Could not save " + path.getFileName(), describe(e));
        }
    }

    /** Reads a JSON experiment, selects its integrator, puts its scenario in the panel and applies it if valid. */
    private void loadExperiment() {
        FileChooser chooser = chooser("Load experiment", "ChaosLab experiment (*.json)", "*.json");
        File file = chooser.showOpenDialog(stage);
        if (file == null) {
            return;
        }
        lastDirectory = file.getParentFile();
        ExperimentFile experiment;
        try {
            experiment = FileIo.readExperiment(file.toPath());
        } catch (IllegalArgumentException | IOException e) {
            problem("Could not load " + file.getName(), describe(e));
            return;
        }

        IntegratorOption match = null;
        for (IntegratorOption option : integratorBox.getItems()) {
            if (option.label().equals(experiment.integrator())) {
                match = option;
            }
        }
        boolean integratorChanged = match != null && match != integratorBox.getValue();
        String integratorNote = "";
        if (match == null) {
            integratorNote = " The file names the integrator \"" + experiment.integrator() + "\", which this version"
                    + " does not have, so the current one (" + integratorBox.getValue().label() + ") is kept.";
        } else if (integratorChanged) {
            suppressIntegratorEvent = true;
            try {
                integratorBox.setValue(match);
            } finally {
                suppressIntegratorEvent = false;
            }
        }
        configPanel.setFixedStep(integratorBox.getValue().tolerances() == null);

        ScenarioDraft.Validation validation = configPanel.loadAndApply(experiment.draft(), experiment.title(),
                experiment.note());
        if (validation.isValid()) {
            // loadAndApply has already restarted the runner with the selected integrator
            fileMessage("Loaded " + file.getName() + " and applied it." + integratorNote, !integratorNote.isEmpty());
        } else {
            if (integratorChanged) {
                startRunner();
            }
            problem("Loaded " + file.getName() + " but did not apply it", "The scenario has a problem: "
                    + validation.firstError() + "\nThe values are in the scenario panel: correct them and press Apply."
                    + integratorNote);
        }
    }

    /** Writes the collected curves as CSV files, and a JSON file of the running scenario, beside a chosen name. */
    private void exportCsv() {
        Scenario scenario = applied.scenario();
        ExperimentFile experiment = new ExperimentFile(applied.title(), applied.note(),
                integratorBox.getValue().label(),
                ScenarioDraft.of(scenario.system(), scenario.initial(), scenario.stepSize()));
        FileChooser chooser = chooser("Export curves (the name is the start of every file name)", "CSV (*.csv)",
                "*.csv");
        chooser.setInitialFileName(safeName(applied.title()) + ".csv");
        File file = chooser.showSaveDialog(stage);
        if (file == null) {
            return;
        }
        lastDirectory = file.getParentFile();
        List<Path> planned = FileIo.plannedExport(file.toPath(), history.size() > 0, gapHistory.size() > 0,
                twinHistory.size() > 0);
        List<Path> existing = new ArrayList<>();
        for (Path p : planned) {
            if (Files.exists(p)) {
                existing.add(p);
            }
        }
        if (!existing.isEmpty() && !confirmOverwrite(existing)) {
            return;
        }
        try {
            String timeColumn = timeScale == SECONDS_PER_DAY ? "time_days" : "time";
            List<Path> written = FileIo.exportRun(file.toPath(), experiment, timeColumn, history, gapHistory,
                    twinHistory);
            StringBuilder names = new StringBuilder();
            for (Path p : written) {
                names.append(names.length() == 0 ? "" : ", ").append(p.getFileName());
            }
            String missing = gapHistory.size() == 0
                    ? " No error file: the reference has not produced a reading yet." : "";
            fileMessage("Exported " + written.size() + " files to " + file.getParent() + ": " + names + "." + missing
                    + " Time is in " + (timeScale == SECONDS_PER_DAY ? "days" : "simulation units")
                    + "; the curves are the worst value per time bucket, as plotted.", false);
        } catch (IllegalArgumentException | IOException e) {
            problem("Could not export", describe(e));
        }
    }

    private FileChooser chooser(String title, String description, String pattern) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(title);
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(description, pattern));
        if (lastDirectory != null && lastDirectory.isDirectory()) {
            chooser.setInitialDirectory(lastDirectory);
        }
        return chooser;
    }

    /** A file-name-safe version of a title: letters, digits, dot, dash and underscore only. */
    private static String safeName(String title) {
        String name = title.replaceAll("[^A-Za-z0-9._-]+", "-").replaceAll("^-+|-+$", "");
        return name.isEmpty() ? "chaoslab" : name;
    }

    private static String describe(Exception e) {
        if (e instanceof IllegalArgumentException) {
            return e.getMessage();
        }
        return e.getClass().getSimpleName() + ": " + e.getMessage();
    }

    private boolean confirmOverwrite(List<Path> paths) {
        StringBuilder text = new StringBuilder("These files already exist and will be replaced:\n");
        for (Path p : paths) {
            text.append("\n").append(p.getFileName());
        }
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, text.toString(), ButtonType.OK, ButtonType.CANCEL);
        alert.setTitle("Replace files?");
        alert.setHeaderText(null);
        styleDialog(alert);
        return alert.showAndWait().filter(b -> b == ButtonType.OK).isPresent();
    }

    private void problem(String header, String detail) {
        Alert alert = new Alert(Alert.AlertType.WARNING, detail, ButtonType.OK);
        alert.setTitle("ChaosLab");
        alert.setHeaderText(header);
        styleDialog(alert);
        alert.showAndWait();
        fileMessage(header + ".", true);
    }

    private static void styleDialog(Alert alert) {
        alert.getDialogPane().setStyle("-fx-base: #0b1220; -fx-background: #070b14; -fx-text-base-color: #e5edf8;");
    }

    private void fileMessage(String text, boolean warning) {
        fileLine.setStyle(warning ? AMBER : "-fx-font-family: 'Consolas'; -fx-font-size: 12px; -fx-text-fill: #22d3ee;");
        fileLine.setText(text);
        fileLine.setVisible(true);
        fileLine.setManaged(true);
    }

    // ---------------------------------------------------------------- simulation control

    /** Lists the inertial frame and one frame per body; selecting is done by index so names need not be unique. */
    private void populateFrameBox(int bodyCount) {
        updatingFrameBox = true;
        try {
            frameBox.getItems().clear();
            frameBox.getItems().add("Inertial");
            for (int i = 1; i <= bodyCount; i++) {
                frameBox.getItems().add("Relative to m" + i);
            }
            frameBox.getSelectionModel().select(0);
        } finally {
            updatingFrameBox = false;
        }
    }

    /** (Re)creates the runner for the applied scenario and the selected integrator, from the initial state. */
    private void startRunner() {
        if (runner != null) {
            runner.close();
        }
        Scenario scenario = applied.scenario();
        NBodySystem system = scenario.system();
        // SI scenarios run in seconds, so show time in days and make the speed slider mean days per second
        timeScale = system.gravitationalConstant() == NBodySystem.G_SI ? SECONDS_PER_DAY : 1.0;
        speedLabel.setText(speedText());
        scenarioLabel.setText(applied.title() + ". " + applied.note());
        equationsText.setText(applied.equations());

        IntegratorOption option = integratorBox.getValue();
        SimulationConfig config = option.tolerances() == null
                ? new SimulationConfig(system, scenario.initial(), option.factory(), scenario.stepSize(),
                SimulationConfig.UNBOUNDED, currentSpeed())
                : SimulationConfig.adaptive(system, scenario.initial(), option.factory(), scenario.stepSize(),
                option.tolerances(), SimulationConfig.UNBOUNDED, currentSpeed());
        runner = new SimulationRunner(config);
        view.setSystem(system);
        populateFrameBox(system.bodyCount());
        history.clear();
        driftExtremes.reset();
        plot.show(history);
        lastSequence = -1;
        aligner.clear();
        latestPositions = null;
        startMonitor(system, scenario);
        twinSystem = system;
        twinStart = scenario.initial();
        restartTwin();
    }

    /**
     * Replaces the twin monitor: the old one is closed off the UI thread; a new one is started only if "Twin run" is
     * ticked. Called when the scenario or integrator restarts and when the box is toggled.
     */
    private void restartTwin() {
        TwinMonitor old = twinMonitor;
        twinMonitor = null;
        lastTwin = null;
        if (old != null) {
            Thread closer = new Thread(old::close, "chaoslab-twin-closer");
            closer.setDaemon(true);
            closer.start();
        }
        twinHistory.clear();
        view.showTwin(null);
        if (!twinBox.isSelected()) {
            gapPlot.showTwin(null);
            twinLine.setVisible(false);
            twinLine.setManaged(false);
            return;
        }
        twinLine.setVisible(true);
        twinLine.setManaged(true);
        try {
            twinMonitor = new TwinMonitor(twinSystem, twinStart);
            gapPlot.showTwin(twinHistory);
            twinLine.setStyle(VIOLET);
            twinLine.setText("TWIN RUN   waiting for the first reading...");
        } catch (RuntimeException e) {
            gapPlot.showTwin(null);
            twinLine.setStyle(AMBER);
            twinLine.setText("TWIN RUN   unavailable: " + e.getMessage());
        }
    }

    private void submitToTwin(Snapshot s) {
        if (twinMonitor == null) {
            return;
        }
        try {
            twinMonitor.submit(s.state().time());
        } catch (IllegalArgumentException e) {
            twinLine.setStyle(AMBER);
            twinLine.setText("TWIN RUN   skipped: " + e.getMessage());
        }
    }

    /** Takes the twin monitor's newest reading, if it is one not yet shown, and updates curve, rings and readout. */
    private void showLatestTwin() {
        if (twinMonitor == null) {
            return;
        }
        TwinRun.Reading r = twinMonitor.latest();
        if (r == null || r == lastTwin) {
            return;
        }
        lastTwin = r;
        twinHistory.offer(r.time() / timeScale, r.relativeSeparation(), Double.NaN);
        gapPlot.showTwin(twinHistory);
        // the ring sits at the displayed body plus the twin's offset: the offset is the physical separation of the pair
        double[] offsets = r.offsets();
        if (offsets != null && latestPositions != null && offsets.length == latestPositions.length) {
            double[] ring = new double[offsets.length];
            for (int i = 0; i < ring.length; i++) {
                ring[i] = latestPositions[i] + offsets[i];
            }
            view.showTwin(ring);
        } else {
            view.showTwin(null);
        }
        updateTwinLine(r);
    }

    /** Replaces the reference monitor: the old one is closed off the UI thread, because closing can wait. */
    private void startMonitor(NBodySystem system, Scenario scenario) {
        ReferenceMonitor old = monitor;
        monitor = null;
        lastReading = null;
        if (old != null) {
            Thread closer = new Thread(old::close, "chaoslab-monitor-closer");
            closer.setDaemon(true);
            closer.start();
        }
        gapHistory.clear();
        gapPlot.show(gapHistory);
        try {
            monitor = new ReferenceMonitor(system, scenario.initial());
            errorLine.setStyle(CORAL);
            errorLine.setText("NUMERICAL ERROR   waiting for the first comparison with the reference...");
            sensitivityLine.setStyle(VIOLET);
            sensitivityLine.setText("PHYSICAL SENSITIVITY   collecting data...");
        } catch (RuntimeException e) {
            errorLine.setStyle(AMBER);
            errorLine.setText("NUMERICAL ERROR   reference unavailable: " + e.getMessage());
            sensitivityLine.setStyle(AMBER);
            sensitivityLine.setText("PHYSICAL SENSITIVITY   unavailable");
        }
    }

    private void submitToMonitor(Snapshot s) {
        if (monitor == null) {
            return;
        }
        try {
            monitor.submit(s.state().time(), s.state().positions());
        } catch (IllegalArgumentException e) {
            errorLine.setStyle(AMBER);
            errorLine.setText("NUMERICAL ERROR   comparison skipped: " + e.getMessage());
        }
    }

    /** Takes the monitor's newest reading, if it is one not yet shown, and updates plot, ghost and readouts. */
    private void showLatestReading() {
        if (monitor == null) {
            return;
        }
        ReferenceMonitor.Reading r = monitor.latest();
        if (r == null || r == lastReading) {
            return;
        }
        lastReading = r;
        gapHistory.offer(r.time() / timeScale, r.relativeGap(), r.relativeUncertainty());
        gapPlot.show(gapHistory);
        // draw the ghost at the displayed body plus the measured error, so a ring's offset is the error, not the lag
        view.showReference(r.time(), aligner.aligned(r.time(), r.referencePositions(), latestPositions));
        updateErrorLine(r);
        updateSensitivityLine(r);
    }

    /** Requested speed in simulation time units per wall-clock second. */
    private double currentSpeed() {
        return Math.pow(10.0, speedSlider.getValue()) * timeScale;
    }

    private String speedText() {
        return String.format(Locale.ROOT, "%.2f %s/s", Math.pow(10.0, speedSlider.getValue()),
                timeScale == 1.0 ? "t" : "d");
    }

    // ---------------------------------------------------------------- telemetry

    private void updateTelemetry(Snapshot s) {
        boolean si = timeScale != 1.0;
        runPause.setText(s.status() == Snapshot.Status.RUNNING ? "Pause" : "Run");
        statusLine.setText(String.format(Locale.ROOT,
                "%-8s t = %10.4f%s   step %-9d  %s   h = %g%s   E = %.9g",
                s.status(), s.state().time() / timeScale, si ? " d" : "", s.stepCount(), s.integratorName(),
                s.stepSize(), si ? " s" : "", s.invariants().totalEnergy()));
        driftLine.setText(String.format(Locale.ROOT,
                "drift   dE/|E0| = %+.3e    dP/Pscale = %.3e    dL/|L0| = %.3e",
                s.drift().relativeEnergy(), s.drift().relativeMomentum(), s.drift().relativeAngularMomentum()));
        if (s.status() == Snapshot.Status.FAILED) {
            warningLine.setText("FAILED: " + s.failure() + "  (Reset to recover)");
        } else if (Math.abs(s.drift().relativeEnergy()) > ENERGY_WARNING_THRESHOLD) {
            warningLine.setText("Energy drift exceeds 1e-3. The exact dynamics conserve energy, so this is "
                    + "numerical error (typically a close encounter with a fixed step), not physics."
                    + (s.integratorName().contains("adaptive") ? "" : " Try an adaptive Dormand-Prince option."));
        } else {
            warningLine.setText("");
        }
    }

    // ---------------------------------------------------------------- numerical error and sensitivity

    private void updateErrorLine(ReferenceMonitor.Reading r) {
        if (r.referenceFailure() != null) {
            errorLine.setStyle(AMBER);
            errorLine.setText("NUMERICAL ERROR   no reference at this time: " + r.referenceFailure());
            return;
        }
        errorLine.setStyle(CORAL);
        double gap = r.relativeGap();
        double uncertainty = r.relativeUncertainty();
        String verdict;
        if (gap == 0.0) {
            verdict = "identical to the reference (the starting state)";
        } else if (!Double.isNaN(uncertainty) && gap <= uncertainty) {
            verdict = "below the reference's own uncertainty: display error cannot be resolved";
        } else if (gap >= 0.1) {
            verdict = "the displayed run has left the true orbit";
        } else {
            int digits = Math.max(1, (int) Math.floor(-Math.log10(gap)));
            verdict = "displayed run agrees with the reference to about " + digits + (digits == 1 ? " digit" : " digits");
        }
        errorLine.setText(String.format(Locale.ROOT,
                "NUMERICAL ERROR   gap = %.2e of the system size   reference uncertainty %s   (reference dE %.1e)   %s",
                gap, Double.isNaN(uncertainty) ? "unknown" : String.format(Locale.ROOT, "%.1e", uncertainty),
                r.referenceEnergyDrift(), verdict));
    }

    private void updateSensitivityLine(ReferenceMonitor.Reading r) {
        boolean si = timeScale != 1.0;
        String per = si ? "per day" : "per t";
        String unit = si ? "d" : "t";
        if (r.lyapunovFailure() != null && r.renormalisations() == 0) {
            sensitivityLine.setStyle(AMBER);
            sensitivityLine.setText("PHYSICAL SENSITIVITY   stopped: " + r.lyapunovFailure());
            return;
        }
        if (r.renormalisations() == 0) {
            sensitivityLine.setStyle(VIOLET);
            sensitivityLine.setText(String.format(Locale.ROOT,
                    "PHYSICAL SENSITIVITY   collecting data: the first estimate comes after %.3g %s",
                    r.lyapunovInterval() / timeScale, unit));
            return;
        }
        double running = r.lyapunovRunning() * timeScale;
        double slope = r.lyapunovSlope() * timeScale;
        ExponentRule.Verdict verdict = ExponentRule.judge(r.renormalisations(), running, slope);
        if (verdict == ExponentRule.Verdict.PROVISIONAL) {
            String provisional = String.format(Locale.ROOT,
                    "PHYSICAL SENSITIVITY   provisional: %.4f %s after only %d of %d renormalisations (first %.4g %s)"
                            + "   a finite-time value, not yet an exponent: a regular orbit also starts positive"
                            + " and falls like 1/t",
                    running, per, r.renormalisations(), ExponentRule.MIN_RENORMALISATIONS,
                    r.lyapunovElapsed() / timeScale, unit);
            if (r.lyapunovFailure() != null) {
                sensitivityLine.setStyle(AMBER);
                sensitivityLine.setText(provisional + "   STOPPED: " + r.lyapunovFailure());
            } else {
                sensitivityLine.setStyle(VIOLET);
                sensitivityLine.setText(provisional);
            }
            return;
        }
        if (verdict == ExponentRule.Verdict.SLOWING) {
            String slowing = String.format(Locale.ROOT,
                    "PHYSICAL SENSITIVITY   no exponent claimed: running mean %.4f %s (first %.4g %s) but the recent"
                            + " slope is only %s, so the growth is slowing. A regular orbit looks like this (its"
                            + " running mean falls like 1/t toward 0); a chaotic orbit that has not converged yet"
                            + " can too   [%d renormalisations]",
                    running, per, r.lyapunovElapsed() / timeScale, unit,
                    Double.isNaN(slope) ? "not available" : String.format(Locale.ROOT, "%.4f", slope),
                    r.renormalisations());
            if (r.lyapunovFailure() != null) {
                sensitivityLine.setStyle(AMBER);
                sensitivityLine.setText(slowing + "   STOPPED: " + r.lyapunovFailure());
            } else {
                sensitivityLine.setStyle(VIOLET);
                sensitivityLine.setText(slowing);
            }
            return;
        }
        String growth = running > 0.0
                ? String.format(Locale.ROOT, "e-folding time %.3g %s", 1.0 / running, unit)
                : "no exponential growth measured";
        String text = String.format(Locale.ROOT,
                "PHYSICAL SENSITIVITY   Lyapunov exponent \u03bb = %.4f %s (mean over the first %.4g %s)   recent slope %s   %s   [%d renormalisations]",
                running, per, r.lyapunovElapsed() / timeScale, unit,
                Double.isNaN(slope) ? "n/a yet" : String.format(Locale.ROOT, "%.4f", slope), growth,
                r.renormalisations());
        if (r.lyapunovFailure() != null) {
            sensitivityLine.setStyle(AMBER);
            sensitivityLine.setText(text + "   STOPPED: " + r.lyapunovFailure());
        } else {
            sensitivityLine.setStyle(VIOLET);
            sensitivityLine.setText(text);
        }
    }

    private void updateTwinLine(TwinRun.Reading r) {
        if (r.failure() != null) {
            twinLine.setStyle(AMBER);
            twinLine.setText("TWIN RUN   no twin at this time: " + r.failure());
            return;
        }
        twinLine.setStyle(VIOLET);
        double separation = r.relativeSeparation();
        double growth = separation / TwinRun.NUDGE;
        StringBuilder text = new StringBuilder(String.format(Locale.ROOT,
                "TWIN RUN   m1 nudged by %.0e of the system size   separation now %.2e of the system size"
                        + " (x%.3g since the start)",
                TwinRun.NUDGE, separation, growth));
        ReferenceMonitor.Reading ly = lastReading;
        double uncertainty = ly == null ? Double.NaN : ly.relativeUncertainty();
        if (!Double.isNaN(uncertainty) && separation <= uncertainty) {
            text.append("   below the reference's own uncertainty: not resolved");
        } else if (separation >= 0.1) {
            text.append("   the two runs have fully separated; growth stops at about the size of the system");
        } else if (ly != null && ExponentRule.judge(ly.renormalisations(), ly.lyapunovRunning() * timeScale,
                ly.lyapunovSlope() * timeScale) == ExponentRule.Verdict.CLAIMED) {
            double predicted = Math.exp(ly.lyapunovRunning() * r.time());
            text.append(String.format(Locale.ROOT,
                    "   the Lyapunov exponent would predict x%.3g (the growth is only exponential while it is small)",
                    predicted));
        } else {
            text.append("   no exponent claimed, so no prediction to compare with");
        }
        twinLine.setText(text.toString());
    }
}
