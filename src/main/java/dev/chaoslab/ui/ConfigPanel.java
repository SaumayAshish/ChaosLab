package dev.chaoslab.ui;

import dev.chaoslab.config.ScenarioDraft;
import dev.chaoslab.config.ScenarioDraft.BodyDraft;
import dev.chaoslab.config.ScenarioDraft.Issue;
import dev.chaoslab.config.ScenarioDraft.Scenario;
import dev.chaoslab.config.ScenarioDraft.Severity;
import dev.chaoslab.config.ScenarioDraft.Validation;
import dev.chaoslab.experiments.BinaryFlyby;
import dev.chaoslab.experiments.FigureEight;
import dev.chaoslab.experiments.PythagoreanProblem;
import dev.chaoslab.experiments.SunEarthMoon;
import dev.chaoslab.physics.Diagnostics;
import dev.chaoslab.physics.NBodySystem;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * The scenario editor: masses, positions and velocities of every body, G, softening and step size, as text fields
 * that are checked on every keystroke by {@link ScenarioDraft#validate}. A field with an error gets a red border and
 * the reason is listed under "Checks"; the Apply button stays disabled until there are no errors, so an invalid
 * scenario can never reach the simulation runner. Warnings (amber) and notes never block a run.
 *
 * <p>All the checking logic lives in {@link ScenarioDraft}, which has unit tests. This class only moves text between
 * fields and that logic and decides how to show the result. It must be used from the JavaFX application thread.
 */
public final class ConfigPanel extends VBox {

    /**
     * A scenario the person has applied: the validated numbers, plus a title and a note for the explanation line.
     * The note is a measured statement for presets and a general one for custom scenarios.
     */
    public record Applied(Scenario scenario, String title, String note) {
    }

    private record Preset(String label, Supplier<ScenarioDraft> draft, String note) {
        @Override
        public String toString() {
            return label;
        }
    }

    private enum Units {
        NORMALIZED("Normalized, G = 1"),
        SI("SI: kg, m, s, G = 6.6743e-11"),
        CUSTOM("Custom G");

        private final String label;

        Units(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private static final double PANEL_WIDTH = 560.0;
    private static final String CUSTOM_TITLE = "Custom scenario";
    private static final String GENERAL_NOTE = "Chaotic orbits have a finite predictability horizon: a small"
            + " numerical error grows exponentially, so conserved energy does not by itself mean the trajectory is"
            + " correct.";
    private static final String ERROR_STYLE = "-fx-border-color: #fb7185; -fx-border-width: 1.5;";
    private static final String WARNING_STYLE = "-fx-border-color: #fbbf24; -fx-border-width: 1.5;";
    private static final List<String> COLUMN_TITLES = List.of("", "mass", "x", "y", "z", "vx", "vy", "vz");

    private static final List<Preset> PRESETS = List.of(
            new Preset("Lagrange triangle, body 1 displaced by 0.01", () -> ScenarioDraft.lagrange(0.01),
                    "Three equal masses on an equilateral triangle, which is unstable for equal masses (growth rate"
                            + " 0.537 per time unit), so expect it to break up. Energy conservation does not make a"
                            + " chaotic trajectory correct: at adaptive tolerance 1e-10 the positions are off by"
                            + " 6e-4 at t = 60 and by 0.3 at t = 100 (measured against a 1e-13 run)."),
            new Preset("Lagrange triangle, exact (unperturbed)", () -> ScenarioDraft.lagrange(0.0),
                    "The exact solution is a rigid rotation, but it is unstable (growth rate 0.537 per time unit),"
                            + " so rounding errors of about 1e-16 grow to order one after roughly ln(1e16)/0.537 = 69"
                            + " time units."),
            new Preset("Figure-eight choreography (stable, three equal masses)",
                    () -> ScenarioDraft.of(FigureEight.system(), FigureEight.state(), 0.005),
                    "Three equal masses chase each other along one figure-eight curve (found numerically by Moore"
                            + " in 1993, proved to exist by Chenciner and Montgomery in 2000; the eight-digit start"
                            + " values are credited to Simo). Period 6.3259, closest approach 0.69. It behaves as a"
                            + " stable orbit, so numerical error grows about linearly in time, not exponentially:"
                            + " Velocity Verlet with h = 0.01 is off by 2e-4 after one period and 3.7e-3 after ten"
                            + " (measured against a 1e-13 run). The rounded data themselves return to the start"
                            + " within 2e-8 per period. Displace body 1 by 0.01 and the choreography is gone."),
            new Preset("Pythagorean 3-4-5 problem (Burrau, masses 3, 4, 5 at rest)",
                    () -> ScenarioDraft.of(PythagoreanProblem.system(), PythagoreanProblem.state(),
                            PythagoreanProblem.FIRST_STEP),
                    "Burrau's problem (1913): masses 3, 4 and 5 at rest at the corners of a 3-4-5 right triangle,"
                            + " each opposite the side of its own length. Szebehely and Peters solved it to the end"
                            + " in 1967: the lightest body is ejected and the other two stay together. Measured here"
                            + " against a 1e-13 reference: masses 4 and 5 pass within 0.0097 at t = 1.88, within"
                            + " 4e-4 at t = 15.83 and within 0.003 at t = 29.8, and mass 3 is gone between t = 60 and"
                            + " t = 70. A fixed step fails at the first pass (Velocity Verlet with h = 0.001 is off"
                            + " by 2.4 at t = 2), so choose Dormand-Prince adaptive with tolerance 1e-10 (off by"
                            + " 2e-7 at t = 10 and 2e-4 at t = 40). After about t = 55 even the reference is"
                            + " uncertain (the 1e-13 and 1e-14 runs differ by 0.13 at t = 60), so only the question"
                            + " of who escapes can be trusted there."),
            new Preset("Binary flyby (a close scattering encounter)",
                    () -> ScenarioDraft.of(BinaryFlyby.system(), BinaryFlyby.state(), BinaryFlyby.FIRST_STEP),
                    "A light intruder (mass 0.5) flies past a circular equal-mass binary (masses 1, separation 1)"
                            + " and passes within 0.016 of one member at t = 12.1. Measured against a 1e-13"
                            + " reference: the binary survives but is left tighter (energy -0.5 to -0.68,"
                            + " eccentricity 0 to 0.989, so its members now pass within 0.008 of each other every"
                            + " 2.8 time units), and the intruder is deflected by about 135 degrees and leaves with"
                            + " the energy the binary lost. The motion is not chaotic: moving the impact"
                            + " parameter by 1e-6 changes the final semi-major axis by 2e-6. Ticking Twin run shows"
                            + " the milder effect of nudging a binary member by 1e-6 of the system size: the pair"
                            + " separates to 4e-3 of the system size by t = 35, growing roughly linearly between"
                            + " t = 20 and 35 (not exponentially), with a jump at each close pass of the binary, and"
                            + " at t = 80 the binary's energy differs by 3e-4 and the deflection by 0.13 degrees."
                            + " The difficulty is a different one, the time scale"
                            + " of the pass. Every fixed step fails at t = 12: at t = 13 Velocity Verlet with"
                            + " h = 0.005 is off by 2 and RK4 with h = 0.005 by 3.5, although both were accurate to"
                            + " 1e-4 and 1e-9 at t = 10. Choose Dormand-Prince adaptive with tolerance 1e-10 (off by"
                            + " 1e-5 at t = 80; tolerance 1e-6 is off by 0.9)."),
            new Preset("Sun, Earth and Moon (hierarchical, SI units)",
                    () -> ScenarioDraft.of(SunEarthMoon.system(), SunEarthMoon.state(), SunEarthMoon.STEP),
                    "NASA fact-sheet masses and mean Earth-Moon distance (384,400 km), both orbits circular and in"
                            + " one plane, so the real Moon's eccentricity (0.055) and 5.1 degree inclination are"
                            + " left out. The Sun pulls the Moon 2.2 times as hard as the Earth does, yet the Moon"
                            + " stays bound because the Sun pulls Earth and Moon almost equally. Measured against a"
                            + " 1e-13 reference over one year: the Earth-Moon distance stays between 373,800 and"
                            + " 388,200 km, and the Moon turns once in 27.06 days (real sidereal period 27.32 days;"
                            + " the start is not the real orbit). Velocity Verlet with h = 3600 s misplaces the"
                            + " Moon-Earth distance by 14 km after a year, and by 3.6 km with h = 1800 s. The motion"
                            + " is regular, so the window withholds a Lyapunov exponent. Time is shown in days."),
            new Preset("Two bodies, circular orbit", ScenarioDraft::twoBodyCircular,
                    "A regular orbit: nearby orbits separate only linearly in time, so numerical error stays small"
                            + " for many periods, unlike the chaotic three-body cases."),
            new Preset("Two bodies, eccentric orbit (e = 0.75)", ScenarioDraft::twoBodyEccentric,
                    "The bodies pass close at pericentre every orbit. That is where a fixed step loses accuracy"
                            + " and where an adaptive step shrinks."),
            new Preset("Sun and Earth, circular orbit (SI units)", ScenarioDraft::sunEarth,
                    "Rounded published masses and distance, circular orbit, so the real eccentricity"
                            + " of about 0.017 is ignored. One orbit takes 365.26 days. Time is shown in days and the"
                            + " speed is in days per second."));

    private final Consumer<Applied> onApply;

    private final ComboBox<Preset> presetBox = new ComboBox<>();
    private final ComboBox<Units> unitsBox = new ComboBox<>();
    private final TextField gField = new TextField();
    private final TextField softeningField = new TextField();
    private final TextField stepField = new TextField();
    private final Label stepLabel = new Label("Step size h");
    private final GridPane bodiesGrid = new GridPane();
    private final Button addButton = new Button("+ Body");
    private final Button removeButton = new Button("- Body");
    private final Button centreButton = new Button("Centre of mass frame");
    private final Button applyButton = new Button("Apply and restart");
    private final VBox issueBox = new VBox(4);
    private final Label readout = new Label();
    private final Label appliedLabel = new Label();

    private final List<List<TextField>> bodyFields = new ArrayList<>();
    private final Map<String, TextField> fieldsById = new LinkedHashMap<>();

    private boolean fixedStep = true;
    private boolean loading;
    private Preset loadedPreset;
    private ScenarioDraft loadedPresetDraft;
    private ScenarioDraft appliedDraft;
    private Applied applied;

    /** @param onApply called with the validated scenario when the person presses Apply */
    public ConfigPanel(Consumer<Applied> onApply) {
        this.onApply = onApply;
        setSpacing(10);
        setPadding(new Insets(12, 14, 12, 14));
        setPrefWidth(PANEL_WIDTH);
        setStyle("-fx-background-color: #0b1220;");

        Label title = new Label("SCENARIO");
        title.setStyle("-fx-text-fill: #22d3ee; -fx-font-size: 13px; -fx-font-weight: bold;");

        presetBox.getItems().addAll(PRESETS);
        presetBox.setValue(PRESETS.get(0));
        presetBox.setPrefWidth(340);
        Button loadButton = new Button("Load preset");
        loadButton.setOnAction(e -> {
            Preset p = presetBox.getValue();
            if (p != null) {
                loadedPreset = p;
                loadedPresetDraft = p.draft().get();
                setFields(loadedPresetDraft);
            }
        });

        unitsBox.getItems().addAll(Units.values());
        unitsBox.setPrefWidth(260);
        unitsBox.setOnAction(e -> {
            if (loading || unitsBox.getValue() == null) {
                return;
            }
            switch (unitsBox.getValue()) {
                case NORMALIZED -> gField.setText(ScenarioDraft.format(NBodySystem.G_NORMALIZED));
                case SI -> gField.setText(ScenarioDraft.format(NBodySystem.G_SI));
                case CUSTOM -> { }
            }
            gField.setDisable(unitsBox.getValue() != Units.CUSTOM);
            revalidate();
        });

        GridPane globals = new GridPane();
        globals.setHgap(10);
        globals.setVgap(6);
        globals.add(new Label("G"), 0, 0);
        globals.add(gField, 1, 0);
        globals.add(new Label("Softening (0 = exact Newtonian)"), 0, 1);
        globals.add(softeningField, 1, 1);
        globals.add(stepLabel, 0, 2);
        globals.add(stepField, 1, 2);
        register(ScenarioDraft.G, gField);
        register(ScenarioDraft.SOFTENING, softeningField);
        register(ScenarioDraft.STEP_SIZE, stepField);

        bodiesGrid.setHgap(6);
        bodiesGrid.setVgap(5);

        addButton.setOnAction(e -> {
            List<BodyDraft> list = new ArrayList<>(draft().bodies());
            if (list.size() < ScenarioDraft.MAX_BODIES) {
                int n = list.size();
                list.add(new BodyDraft("1.0", ScenarioDraft.format(n), "0.0", "0.0", "0.0", "0.0", "0.0"));
                replaceBodies(list);
            }
        });
        removeButton.setOnAction(e -> {
            List<BodyDraft> list = new ArrayList<>(draft().bodies());
            if (list.size() > ScenarioDraft.MIN_BODIES) {
                list.remove(list.size() - 1);
                replaceBodies(list);
            }
        });
        centreButton.setOnAction(e -> {
            if (lastValid()) {
                setFields(draft().centred());
            }
        });

        applyButton.setOnAction(e -> apply());
        applyButton.setStyle("-fx-base: #0e7490;");

        readout.setStyle("-fx-font-family: 'Consolas'; -fx-font-size: 12px; -fx-text-fill: #9fb0c8;");
        appliedLabel.setWrapText(true);
        appliedLabel.setMaxWidth(PANEL_WIDTH - 200);

        Label checksTitle = new Label("CHECKS");
        checksTitle.setStyle("-fx-text-fill: #22d3ee; -fx-font-size: 13px; -fx-font-weight: bold;");
        Label bodiesTitle = new Label("BODIES");
        bodiesTitle.setStyle("-fx-text-fill: #22d3ee; -fx-font-size: 13px; -fx-font-weight: bold;");

        getChildren().addAll(title, new HBox(8, presetBox, loadButton), new HBox(8, new Label("Units"), unitsBox),
                globals, bodiesTitle, bodiesGrid, new HBox(8, addButton, removeButton, centreButton), checksTitle,
                issueBox, readout, new HBox(12, applyButton, appliedLabel));

        loadedPreset = PRESETS.get(0);
        loadedPresetDraft = loadedPreset.draft().get();
        setFields(loadedPresetDraft);
        Validation first = loadedPresetDraft.validate(fixedStep);
        if (!first.isValid()) {
            throw new IllegalStateException("The built-in first preset is invalid: " + first.firstError());
        }
        applied = buildApplied(first.scenario());
        appliedDraft = loadedPresetDraft;
        revalidate();
    }

    // ---------------------------------------------------------------- public API

    /** The scenario that was applied most recently (initially the first preset). */
    public Applied applied() {
        return applied;
    }

    /**
     * Tells the panel whether the chosen integrator uses a fixed step (then the step size matters for accuracy and
     * is checked against the dynamical time) or an adaptive one (then it is only the first trial step).
     */
    public void setFixedStep(boolean fixed) {
        this.fixedStep = fixed;
        stepLabel.setText(fixed ? "Step size h" : "First trial step");
        revalidate();
    }

    /** The text of every field right now, valid or not, as a draft; used to save the scenario being edited. */
    public ScenarioDraft currentDraft() {
        return draft();
    }

    /**
     * The title that goes with the fields right now: a preset's name while the fields still equal it exactly, and
     * "Custom scenario" as soon as any value differs. Same rule as the title shown after pressing Apply.
     */
    public String currentTitle() {
        return matchesLoaded() ? loadedPreset.label() : CUSTOM_TITLE;
    }

    /** The note that goes with {@link #currentTitle()}. */
    public String currentNote() {
        return matchesLoaded() ? loadedPreset.note() : GENERAL_NOTE;
    }

    /**
     * Puts a scenario read from a file into the fields and, if it is valid for the current integrator kind, applies it
     * at once. The title and note stay attached to it only while the fields are unedited, exactly like a preset. If it
     * is not valid the fields are still filled, so the person sees the problems next to the numbers and can correct
     * them; nothing is applied then.
     *
     * @param title a blank title becomes "Loaded experiment"
     * @param note  a blank note becomes the general note about predictability
     * @return the validation of the loaded fields (valid means it was applied)
     */
    public Validation loadAndApply(ScenarioDraft d, String title, String note) {
        String t = title == null || title.isBlank() ? "Loaded experiment" : title;
        String n = note == null || note.isBlank() ? GENERAL_NOTE : note;
        loadedPreset = new Preset(t, () -> d, n);
        loadedPresetDraft = d;
        setFields(d);
        Validation v = lastValidation;
        if (v.isValid()) {
            apply();
        }
        return v;
    }

    private boolean matchesLoaded() {
        return loadedPresetDraft != null && loadedPresetDraft.equals(draft());
    }

    // ---------------------------------------------------------------- field handling

    private void register(String id, TextField field) {
        fieldsById.put(id, field);
        field.textProperty().addListener((o, was, is) -> onEdited());
    }

    private void onEdited() {
        if (!loading) {
            revalidate();
        }
    }

    /** The current text of every field, as a draft. */
    private ScenarioDraft draft() {
        List<BodyDraft> list = new ArrayList<>();
        for (List<TextField> row : bodyFields) {
            List<String> values = new ArrayList<>();
            for (TextField f : row) {
                values.add(f.getText());
            }
            list.add(BodyDraft.of(values));
        }
        return new ScenarioDraft(gField.getText(), softeningField.getText(), stepField.getText(), list);
    }

    /** Writes a whole draft into the fields without treating it as an edit. */
    private void setFields(ScenarioDraft d) {
        loading = true;
        try {
            gField.setText(d.g());
            softeningField.setText(d.softening());
            stepField.setText(d.stepSize());
            syncUnits(d.g());
            rebuildRows(d.bodies());
        } finally {
            loading = false;
        }
        revalidate();
    }

    private void replaceBodies(List<BodyDraft> list) {
        loading = true;
        try {
            rebuildRows(list);
        } finally {
            loading = false;
        }
        revalidate();
    }

    private void syncUnits(String gText) {
        Units u = gText.equals(ScenarioDraft.format(NBodySystem.G_NORMALIZED)) ? Units.NORMALIZED
                : gText.equals(ScenarioDraft.format(NBodySystem.G_SI)) ? Units.SI : Units.CUSTOM;
        unitsBox.setValue(u);
        gField.setDisable(u != Units.CUSTOM);
    }

    private void rebuildRows(List<BodyDraft> bodies) {
        fieldsById.keySet().removeIf(id -> id.startsWith("body"));
        bodyFields.clear();
        bodiesGrid.getChildren().clear();
        for (int c = 0; c < COLUMN_TITLES.size(); c++) {
            Label h = new Label(COLUMN_TITLES.get(c));
            h.setStyle("-fx-text-fill: #6b7c96; -fx-font-size: 11px;");
            bodiesGrid.add(h, c, 0);
        }
        for (int i = 0; i < bodies.size(); i++) {
            Label name = new Label("m" + (i + 1));
            name.setStyle("-fx-text-fill: #9fb0c8;");
            name.setMinWidth(Region.USE_PREF_SIZE); // never shorten "m1" to "..."
            bodiesGrid.add(name, 0, i + 1);
            List<String> values = bodies.get(i).values();
            List<TextField> row = new ArrayList<>();
            for (int k = 0; k < ScenarioDraft.COMPONENTS.size(); k++) {
                TextField f = new TextField(values.get(k));
                f.setPrefColumnCount(7);
                Tooltip full = new Tooltip(); // long values are clipped in the field; the tooltip shows all digits
                full.textProperty().bind(f.textProperty());
                f.setTooltip(full);
                register(ScenarioDraft.field(i, ScenarioDraft.COMPONENTS.get(k)), f);
                row.add(f);
                bodiesGrid.add(f, k + 1, i + 1);
            }
            bodyFields.add(row);
        }
    }

    // ---------------------------------------------------------------- checking and display

    private Validation lastValidation;

    private boolean lastValid() {
        return lastValidation != null && lastValidation.isValid();
    }

    private void revalidate() {
        ScenarioDraft d = draft();
        Validation v = d.validate(fixedStep);
        lastValidation = v;

        for (Map.Entry<String, TextField> e : fieldsById.entrySet()) {
            e.getValue().setStyle(styleFor(v, e.getKey()));
        }

        issueBox.getChildren().clear();
        if (v.issues().isEmpty()) {
            Label ok = new Label("No problems found.");
            ok.setStyle("-fx-text-fill: #6b7c96;");
            issueBox.getChildren().add(ok);
        }
        for (Issue issue : v.issues()) {
            String prefix = switch (issue.severity()) {
                case ERROR -> "Error: ";
                case WARNING -> "Warning: ";
                case INFO -> "Note: ";
            };
            String colour = switch (issue.severity()) {
                case ERROR -> "#fb7185";
                case WARNING -> "#fbbf24";
                case INFO -> "#9fb0c8";
            };
            Label l = new Label(prefix + issue.message());
            l.setWrapText(true);
            l.setMaxWidth(PANEL_WIDTH - 40);
            l.setStyle("-fx-text-fill: " + colour + "; -fx-font-size: 12px;");
            issueBox.getChildren().add(l);
        }

        applyButton.setDisable(!v.isValid());
        centreButton.setDisable(!v.isValid());
        addButton.setDisable(bodyFields.size() >= ScenarioDraft.MAX_BODIES);
        removeButton.setDisable(bodyFields.size() <= ScenarioDraft.MIN_BODIES);
        readout.setText(v.isValid() ? readoutText(v.scenario()) : "");

        boolean same = d.equals(appliedDraft);
        appliedLabel.setText(same ? "These values are the running scenario."
                : "Edited: press Apply to restart with these values.");
        appliedLabel.setStyle("-fx-text-fill: " + (same ? "#6b7c96" : "#fbbf24") + ";");
    }

    private static String styleFor(Validation v, String fieldId) {
        boolean warning = false;
        for (Issue i : v.issues()) {
            if (i.field().equals(fieldId)) {
                if (i.severity() == Severity.ERROR) {
                    return ERROR_STYLE;
                }
                if (i.severity() == Severity.WARNING) {
                    warning = true;
                }
            }
        }
        return warning ? WARNING_STYLE : "";
    }

    private static String readoutText(Scenario s) {
        Diagnostics.Invariants inv = s.invariants();
        return String.format(Locale.ROOT,
                "total mass   %.6g%nenergy E     %.9g%n|P|          %.6g%n|L| about 0   %.6g%nCOM          (%.4g, %.4g, %.4g)",
                s.system().totalMass(), inv.totalEnergy(), inv.momentum().norm(), inv.angularMomentum().norm(),
                inv.centreOfMass().x(), inv.centreOfMass().y(), inv.centreOfMass().z());
    }

    // ---------------------------------------------------------------- applying

    private void apply() {
        if (!lastValid()) {
            return;
        }
        applied = buildApplied(lastValidation.scenario());
        appliedDraft = draft();
        revalidate();
        onApply.accept(applied);
    }

    /** A preset keeps its title and measured note only while the fields still match it exactly. */
    private Applied buildApplied(Scenario scenario) {
        return new Applied(scenario, currentTitle(), currentNote());
    }
}
