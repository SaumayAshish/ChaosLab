package dev.chaoslab.io;

import dev.chaoslab.config.ScenarioDraft;
import dev.chaoslab.config.ScenarioDraft.BodyDraft;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * An experiment as a JSON file: the scenario (system constants, step size, every body's mass, position and velocity),
 * the integrator by name, and a title and note. Loading a file and applying it reproduces the run bit for bit, because
 * every number is written as the shortest decimal that reads back as exactly the same double (the text of the
 * {@link ScenarioDraft}, which is what the configuration panel works with).
 *
 * <pre>
 * {
 *   "format": "chaoslab-experiment",
 *   "version": 1,
 *   "title": "...",
 *   "note": "...",
 *   "system": { "gravitationalConstant": 1.0, "softening": 0.0 },
 *   "integrator": { "label": "Velocity Verlet (symplectic, order 2)", "stepSize": 0.005 },
 *   "bodies": [
 *     { "mass": 1.0, "position": [x, y, z], "velocity": [vx, vy, vz] },
 *     ...
 *   ]
 * }
 * </pre>
 *
 * <p>The start time is always 0. For an adaptive integrator {@code stepSize} is the first trial step. The integrator
 * label is the name shown in the window; if a loaded file names an integrator this version does not have, the caller
 * keeps the current one and says so. Unknown extra keys are ignored, so later versions can add information without
 * breaking this reader; a file of a higher {@code version} than {@value #VERSION} is refused rather than guessed at.
 *
 * <p>This class checks the <em>format</em> (types, counts, finite numbers). Whether the physics is acceptable (for
 * example coincident bodies) is decided afterwards by {@link ScenarioDraft#validate}, with the messages the person
 * already knows from editing the fields by hand.
 *
 * @param title      name shown in the explanation line
 * @param note       explanation shown with the scenario
 * @param integrator the integrator's label as shown in the window
 * @param draft      the scenario as text
 */
public record ExperimentFile(String title, String note, String integrator, ScenarioDraft draft) {

    public static final String FORMAT = "chaoslab-experiment";
    public static final int VERSION = 1;

    /** A file that is not a readable ChaosLab experiment. */
    public static final class FormatException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        FormatException(String message) {
            super(message);
        }
    }

    public ExperimentFile {
        title = title == null ? "" : title;
        note = note == null ? "" : note;
        integrator = integrator == null ? "" : integrator;
        if (draft == null) {
            throw new IllegalArgumentException("draft must not be null");
        }
    }

    // ---------------------------------------------------------------- writing

    /**
     * The file's text.
     *
     * @throws FormatException if a number in the draft is not a finite decimal (nothing is written then)
     */
    public String toJson() {
        StringBuilder b = new StringBuilder(1024);
        b.append("{\n");
        b.append("  \"format\": ").append(Json.quote(FORMAT)).append(",\n");
        b.append("  \"version\": ").append(VERSION).append(",\n");
        b.append("  \"title\": ").append(Json.quote(title)).append(",\n");
        b.append("  \"note\": ").append(Json.quote(note)).append(",\n");
        b.append("  \"system\": { \"gravitationalConstant\": ").append(number(draft.g(), "G"))
                .append(", \"softening\": ").append(number(draft.softening(), "softening")).append(" },\n");
        b.append("  \"integrator\": { \"label\": ").append(Json.quote(integrator)).append(", \"stepSize\": ")
                .append(number(draft.stepSize(), "step size")).append(" },\n");
        b.append("  \"bodies\": [\n");
        List<BodyDraft> bodies = draft.bodies();
        for (int k = 0; k < bodies.size(); k++) {
            BodyDraft d = bodies.get(k);
            String where = "body " + (k + 1);
            b.append("    { \"mass\": ").append(number(d.mass(), where + " mass"))
                    .append(", \"position\": [").append(number(d.x(), where + " x")).append(", ")
                    .append(number(d.y(), where + " y")).append(", ").append(number(d.z(), where + " z"))
                    .append("], \"velocity\": [").append(number(d.vx(), where + " vx")).append(", ")
                    .append(number(d.vy(), where + " vy")).append(", ").append(number(d.vz(), where + " vz"))
                    .append("] }").append(k + 1 < bodies.size() ? "," : "").append('\n');
        }
        b.append("  ]\n}\n");
        return b.toString();
    }

    private static String number(String text, String what) {
        double d;
        try {
            d = Double.parseDouble(text);
        } catch (NumberFormatException e) {
            throw new FormatException("Cannot save: " + what + " is not a number: \"" + text + "\"");
        }
        if (!Double.isFinite(d)) {
            throw new FormatException("Cannot save: " + what + " is not finite: \"" + text + "\"");
        }
        return ScenarioDraft.format(d);
    }

    // ---------------------------------------------------------------- reading

    /**
     * Reads a file's text.
     *
     * @throws FormatException if the text is not valid JSON or not a ChaosLab experiment; the message says what
     */
    public static ExperimentFile fromJson(String text) {
        Object root;
        try {
            root = Json.parse(text);
        } catch (Json.JsonException e) {
            throw new FormatException(e.getMessage());
        }
        Map<String, Object> top = object(root, "the file");
        Object format = top.get("format");
        if (!FORMAT.equals(format)) {
            throw new FormatException("This is not a ChaosLab experiment file (\"format\" should be \"" + FORMAT
                    + "\").");
        }
        Object versionValue = top.get("version");
        if (!(versionValue instanceof Json.Num v) || !v.text().matches("[0-9]+")) {
            throw new FormatException("\"version\" must be a whole number.");
        }
        double version = v.value();
        if (version < 1) {
            throw new FormatException("\"version\" must be at least 1.");
        }
        if (version > VERSION) {
            throw new FormatException("This file has version " + v.text() + ", written by a newer ChaosLab; this"
                    + " version reads up to " + VERSION + ".");
        }
        String title = optionalString(top, "title", "Loaded experiment");
        String note = optionalString(top, "note", "");

        Map<String, Object> system = object(top.get("system"), "\"system\"");
        double g = number(system.get("gravitationalConstant"), "system.gravitationalConstant");
        double softening = number(system.get("softening"), "system.softening");
        Map<String, Object> integrator = object(top.get("integrator"), "\"integrator\"");
        Object label = integrator.get("label");
        if (!(label instanceof String)) {
            throw new FormatException("integrator.label must be a string.");
        }
        double step = number(integrator.get("stepSize"), "integrator.stepSize");

        Object bodiesValue = top.get("bodies");
        if (!(bodiesValue instanceof List<?> list)) {
            throw new FormatException("\"bodies\" must be an array.");
        }
        if (list.size() < ScenarioDraft.MIN_BODIES || list.size() > ScenarioDraft.MAX_BODIES) {
            throw new FormatException("\"bodies\" must have between " + ScenarioDraft.MIN_BODIES + " and "
                    + ScenarioDraft.MAX_BODIES + " entries, it has " + list.size() + ".");
        }
        List<BodyDraft> bodies = new ArrayList<>();
        for (int k = 0; k < list.size(); k++) {
            String where = "bodies[" + k + "]";
            Map<String, Object> body = object(list.get(k), where);
            double[] p = vector(body.get("position"), where + ".position");
            double[] u = vector(body.get("velocity"), where + ".velocity");
            bodies.add(new BodyDraft(text(number(body.get("mass"), where + ".mass")), text(p[0]), text(p[1]),
                    text(p[2]), text(u[0]), text(u[1]), text(u[2])));
        }
        ScenarioDraft draft = new ScenarioDraft(text(g), text(softening), text(step), bodies);
        return new ExperimentFile(title, note, (String) label, draft);
    }

    private static String text(double d) {
        return ScenarioDraft.format(d);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value, String what) {
        if (!(value instanceof Map<?, ?>)) {
            throw new FormatException(what + " must be an object.");
        }
        return (Map<String, Object>) value;
    }

    private static String optionalString(Map<String, Object> map, String key, String fallback) {
        Object v = map.get(key);
        if (v == null) {
            return fallback;
        }
        if (!(v instanceof String s)) {
            throw new FormatException("\"" + key + "\" must be a string.");
        }
        return s;
    }

    private static double number(Object value, String what) {
        if (!(value instanceof Json.Num n)) {
            throw new FormatException(what + " must be a number.");
        }
        double d = n.value();
        if (!Double.isFinite(d)) {
            throw new FormatException(what + " is out of range: " + n.text());
        }
        return d;
    }

    private static double[] vector(Object value, String what) {
        if (!(value instanceof List<?> list) || list.size() != 3) {
            throw new FormatException(what + " must be an array of 3 numbers.");
        }
        double[] v = new double[3];
        for (int k = 0; k < 3; k++) {
            v[k] = number(list.get(k), what + "[" + k + "]");
        }
        return v;
    }
}
