package dev.chaoslab.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.chaoslab.config.ScenarioDraft;
import dev.chaoslab.config.ScenarioDraft.BodyDraft;
import dev.chaoslab.experiments.BinaryFlyby;
import dev.chaoslab.experiments.FigureEight;
import dev.chaoslab.experiments.LagrangeTriangle;
import dev.chaoslab.experiments.PythagoreanProblem;
import dev.chaoslab.experiments.SunEarthMoon;
import dev.chaoslab.physics.NBodySystem;
import dev.chaoslab.physics.State;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExperimentFileTest {

    private static final String LABEL = "Velocity Verlet (symplectic, order 2)";

    private static ScenarioDraft flyby() {
        return ScenarioDraft.of(BinaryFlyby.system(), BinaryFlyby.state(), 0.001);
    }

    private static ExperimentFile sample() {
        return new ExperimentFile("Binary flyby", "A note, with \"quotes\"\nand a second line.", LABEL, flyby());
    }

    private static String valid() {
        return sample().toJson();
    }

    private static String replace(String text, String from, String to) {
        assertTrue(text.contains(from), "test setup: " + from + " not in " + text);
        return text.replace(from, to);
    }

    @Test
    void roundTripGivesTheSameFile() {
        ExperimentFile f = sample();
        assertEquals(f, ExperimentFile.fromJson(f.toJson()));
    }

    @Test
    void roundTripIsBitExactForAwkwardDoubles() {
        double[] awkward = {0.1, 1.0 / 3.0, Math.PI, 1e-300, 4.9e-324, 1.7976931348623157e308, -2.5e-17,
                6.674e-11, 123456789.123456789};
        for (double d : awkward) {
            ScenarioDraft draft = new ScenarioDraft(ScenarioDraft.format(d), "0.0", "0.01", List.of(
                    new BodyDraft(ScenarioDraft.format(Math.abs(d)), ScenarioDraft.format(d), "0.0", "0.0",
                            ScenarioDraft.format(-d), "0.0", "0.0"),
                    new BodyDraft("1.0", "5.0", "0.0", "0.0", "0.0", "0.0", "0.0")));
            ExperimentFile back = ExperimentFile.fromJson(new ExperimentFile("t", "n", LABEL, draft).toJson());
            assertEquals(d, Double.parseDouble(back.draft().g()), 0.0);
            assertEquals(d, Double.parseDouble(back.draft().bodies().get(0).x()), 0.0);
            assertEquals(-d, Double.parseDouble(back.draft().bodies().get(0).vx()), 0.0);
        }
    }

    @Test
    void aLoadedFileGivesTheExactStartingState() {
        ExperimentFile back = ExperimentFile.fromJson(valid());
        ScenarioDraft.Validation v = back.draft().validate(true);
        assertTrue(v.isValid(), "valid");
        State expected = BinaryFlyby.state();
        NBodySystem system = BinaryFlyby.system();
        assertEquals(system.bodyCount(), v.scenario().system().bodyCount());
        for (int k = 0; k < expected.positions().length; k++) {
            assertEquals(expected.positions()[k], v.scenario().initial().positions()[k], 0.0, "q" + k);
            assertEquals(expected.velocities()[k], v.scenario().initial().velocities()[k], 0.0, "v" + k);
        }
        assertEquals(0.001, v.scenario().stepSize(), 0.0);
    }

    @Test
    void everyBuiltInExperimentSurvivesTheRoundTripBitForBit() {
        NBodySystem[] systems = {LagrangeTriangle.system(), FigureEight.system(), PythagoreanProblem.system(),
                BinaryFlyby.system(), SunEarthMoon.system(), NBodySystem.of(1.0, 0.05, 1.0, 2.0)};
        State[] states = {LagrangeTriangle.state(0.01), FigureEight.state(), PythagoreanProblem.state(),
                BinaryFlyby.state(), SunEarthMoon.state(),
                State.of(0.0, new double[] {-0.3, 0.1, 0.2, 0.7, -0.2, 0.4},
                        new double[] {0.01, 0.0, -0.5, -0.02, 0.3, 0.1})};
        for (int k = 0; k < systems.length; k++) {
            NBodySystem system = systems[k];
            State state = states[k];
            ExperimentFile back = ExperimentFile.fromJson(
                    new ExperimentFile("t", "n", LABEL, ScenarioDraft.of(system, state, 0.01)).toJson());
            ScenarioDraft d = back.draft();
            String where = "experiment " + k;
            assertEquals(system.gravitationalConstant(), Double.parseDouble(d.g()), 0.0, where + " G");
            assertEquals(system.softening(), Double.parseDouble(d.softening()), 0.0, where + " softening");
            assertEquals(0.01, Double.parseDouble(d.stepSize()), 0.0, where + " step");
            assertEquals(system.bodyCount(), d.bodies().size(), where + " count");
            for (int i = 0; i < system.bodyCount(); i++) {
                BodyDraft b = d.bodies().get(i);
                assertEquals(system.mass(i), Double.parseDouble(b.mass()), 0.0, where + " mass " + i);
                assertEquals(state.positions()[3 * i], Double.parseDouble(b.x()), 0.0, where + " x " + i);
                assertEquals(state.positions()[3 * i + 1], Double.parseDouble(b.y()), 0.0, where + " y " + i);
                assertEquals(state.positions()[3 * i + 2], Double.parseDouble(b.z()), 0.0, where + " z " + i);
                assertEquals(state.velocities()[3 * i], Double.parseDouble(b.vx()), 0.0, where + " vx " + i);
                assertEquals(state.velocities()[3 * i + 1], Double.parseDouble(b.vy()), 0.0, where + " vy " + i);
                assertEquals(state.velocities()[3 * i + 2], Double.parseDouble(b.vz()), 0.0, where + " vz " + i);
            }
        }
    }

    @Test
    void textIsEscapedAndSurvives() {
        ExperimentFile back = ExperimentFile.fromJson(valid());
        assertEquals("A note, with \"quotes\"\nand a second line.", back.note());
        assertEquals("Binary flyby", back.title());
        assertEquals(LABEL, back.integrator());
    }

    @Test
    void fileStatesItsFormatAndVersion() {
        String json = valid();
        assertTrue(json.contains("\"format\": \"chaoslab-experiment\""));
        assertTrue(json.contains("\"version\": 1,"));
        assertTrue(json.endsWith("}\n"));
    }

    @Test
    void numbersAreWrittenInTheShortestExactForm() {
        ScenarioDraft draft = new ScenarioDraft("1", "0", "5e-3", List.of(
                new BodyDraft("1", "-0", "0", "0", "0", "0", "0"),
                new BodyDraft("2", "3", "0", "0", "0", "0", "0")));
        String json = new ExperimentFile("t", "", LABEL, draft).toJson();
        assertTrue(json.contains("\"gravitationalConstant\": 1.0"), json);
        assertTrue(json.contains("\"stepSize\": 0.005"), json);
        assertTrue(json.contains("\"position\": [0.0, 0.0, 0.0]"), json);
    }

    @Test
    void missingTitleAndNoteGetDefaults() {
        String json = valid();
        json = json.replaceAll("(?m)^  \"title\".*\\n", "").replaceAll("(?m)^  \"note\".*\\n", "");
        ExperimentFile back = ExperimentFile.fromJson(json);
        assertEquals("Loaded experiment", back.title());
        assertEquals("", back.note());
    }

    @Test
    void unknownExtraKeysAreIgnored() {
        String json = replace(valid(), "\"version\": 1,", "\"version\": 1,\n  \"futureThing\": {\"a\": [1, 2]},");
        assertEquals(sample(), ExperimentFile.fromJson(json));
    }

    @Test
    void aNewerVersionIsRefused() {
        FormatExceptionCheck.expect(replace(valid(), "\"version\": 1,", "\"version\": 2,"), "version 2");
    }

    @Test
    void versionMustBeAWholeNumberOfAtLeastOne() {
        FormatExceptionCheck.expect(replace(valid(), "\"version\": 1,", "\"version\": 1.5,"), "whole number");
        FormatExceptionCheck.expect(replace(valid(), "\"version\": 1,", "\"version\": \"1\","), "whole number");
        FormatExceptionCheck.expect(replace(valid(), "\"version\": 1,", "\"version\": 0,"), "at least 1");
        FormatExceptionCheck.expect(replace(valid(), "\"version\": 1,", "\"version\": -1,"), "whole number");
    }

    @Test
    void anotherFormatIsRefused() {
        FormatExceptionCheck.expect(replace(valid(), "chaoslab-experiment", "something-else"), "not a ChaosLab");
        FormatExceptionCheck.expect("[1, 2]", "the file must be an object");
        FormatExceptionCheck.expect("{}", "not a ChaosLab");
    }

    @Test
    void notJsonIsRefusedWithTheLocation() {
        FormatExceptionCheck.expect("{\"format\": ", "Invalid JSON at line 1");
    }

    @Test
    void missingOrWrongSectionsAreNamed() {
        String json = valid();
        FormatExceptionCheck.expect(json.replace("\"system\"", "\"sys\""), "\"system\" must be an object");
        FormatExceptionCheck.expect(json.replace("\"integrator\": {", "\"integrator\": [], \"x\": {"),
                "\"integrator\" must be an object");
        FormatExceptionCheck.expect(json.replace("\"gravitationalConstant\"", "\"g\""),
                "system.gravitationalConstant must be a number");
        FormatExceptionCheck.expect(json.replace("\"stepSize\"", "\"h\""), "integrator.stepSize must be a number");
        FormatExceptionCheck.expect(json.replace("\"label\"", "\"name\""), "integrator.label must be a string");
        FormatExceptionCheck.expect(json.replace("\"bodies\"", "\"b\""), "\"bodies\" must be an array");
    }

    @Test
    void numbersGivenAsTextAreRefused() {
        FormatExceptionCheck.expect(replace(valid(), "\"softening\": 0.0", "\"softening\": \"0.0\""),
                "system.softening must be a number");
    }

    @Test
    void numbersOutOfRangeAreRefused() {
        FormatExceptionCheck.expect(replace(valid(), "\"softening\": 0.0", "\"softening\": 1e999"),
                "system.softening is out of range");
    }

    @Test
    void bodyCountIsChecked() {
        String one = "{\"format\":\"chaoslab-experiment\",\"version\":1,\"system\":{\"gravitationalConstant\":1,"
                + "\"softening\":0},\"integrator\":{\"label\":\"x\",\"stepSize\":0.1},\"bodies\":["
                + "{\"mass\":1,\"position\":[0,0,0],\"velocity\":[0,0,0]}]}";
        FormatExceptionCheck.expect(one, "between 2 and 8 entries, it has 1");
        String none = one.replaceAll("\"bodies\":\\[.*\\]\\}$", "\"bodies\":[]}");
        FormatExceptionCheck.expect(none, "it has 0");
        String body = "{\"mass\":1,\"position\":[0,0,0],\"velocity\":[0,0,0]}";
        String nine = one.replace(body, String.join(",", java.util.Collections.nCopies(9, body)));
        FormatExceptionCheck.expect(nine, "it has 9");
        String eight = one.replace(body, String.join(",", java.util.Collections.nCopies(8, body)));
        assertEquals(8, ExperimentFile.fromJson(eight).draft().bodies().size());
    }

    @Test
    void bodyFieldsAreChecked() {
        String json = valid();
        FormatExceptionCheck.expect(json.replaceFirst("\"mass\"", "\"m\""), "bodies[0].mass must be a number");
        FormatExceptionCheck.expect(json.replaceFirst("\"position\": \\[[^\\]]*\\]", "\"position\": [1, 2]"),
                "bodies[0].position must be an array of 3 numbers");
        FormatExceptionCheck.expect(json.replaceFirst("\"velocity\": \\[[^\\]]*\\]", "\"velocity\": [1, 2, 3, 4]"),
                "bodies[0].velocity must be an array of 3 numbers");
        FormatExceptionCheck.expect(json.replaceFirst("\"velocity\": \\[([^,]*),", "\"velocity\": [\"a\","),
                "bodies[0].velocity[0] must be a number");
        FormatExceptionCheck.expect(json.replaceFirst("(?m)^    \\{ .*\\},$", "    7,"),
                "bodies[0] must be an object");
    }

    @Test
    void theErrorNamesTheRightBody() {
        String json = valid();
        int second = json.indexOf("\"mass\"", json.indexOf("\"mass\"") + 1);
        String broken = json.substring(0, second) + "\"mass\": true," + json.substring(json.indexOf(',', second) + 1);
        FormatExceptionCheck.expect(broken, "bodies[1].mass must be a number");
    }

    @Test
    void savingRefusesTextThatIsNotAFiniteNumber() {
        for (String bad : new String[] {"abc", "", "NaN", "Infinity", "1e999"}) {
            ScenarioDraft draft = new ScenarioDraft("1.0", "0.0", "0.01", List.of(
                    new BodyDraft("1.0", bad, "0.0", "0.0", "0.0", "0.0", "0.0"),
                    new BodyDraft("1.0", "3.0", "0.0", "0.0", "0.0", "0.0", "0.0")));
            ExperimentFile.FormatException e = assertThrows(ExperimentFile.FormatException.class,
                    () -> new ExperimentFile("t", "", LABEL, draft).toJson(), bad);
            assertTrue(e.getMessage().contains("body 1 x"), e.getMessage());
        }
    }

    @Test
    void whitespaceAroundANumberTextIsTolerated() {
        ScenarioDraft draft = new ScenarioDraft(" 1.0 ", "0.0", "0.01", List.of(
                new BodyDraft("1.0", "0.0", "0.0", "0.0", "0.0", "0.0", "0.0"),
                new BodyDraft("1.0", "3.0", "0.0", "0.0", "0.0", "0.0", "0.0")));
        assertEquals("1.0", ExperimentFile.fromJson(new ExperimentFile("t", "", LABEL, draft).toJson())
                .draft().g());
    }

    @Test
    void nullFieldsBecomeEmptyAndNullDraftIsRefused() {
        ExperimentFile f = new ExperimentFile(null, null, null, flyby());
        assertEquals("", f.title());
        assertEquals("", f.note());
        assertEquals("", f.integrator());
        assertThrows(IllegalArgumentException.class, () -> new ExperimentFile("t", "n", LABEL, null));
    }

    /** Asserts that reading the text fails with a FormatException whose message contains a phrase. */
    private static final class FormatExceptionCheck {
        static void expect(String text, String phrase) {
            ExperimentFile.FormatException e = assertThrows(ExperimentFile.FormatException.class,
                    () -> ExperimentFile.fromJson(text), phrase);
            assertTrue(e.getMessage().toLowerCase(java.util.Locale.ROOT).contains(phrase.toLowerCase(java.util.Locale.ROOT)),
                    "expected \"" + phrase + "\" in: " + e.getMessage());
        }
    }
}
