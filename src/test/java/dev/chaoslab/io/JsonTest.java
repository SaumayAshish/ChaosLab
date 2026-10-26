package dev.chaoslab.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonTest {

    @Test
    void readsTheScalarValues() {
        assertEquals(Boolean.TRUE, Json.parse("true"));
        assertEquals(Boolean.FALSE, Json.parse(" false "));
        assertNull(Json.parse("null"));
        assertEquals("hi", Json.parse("\"hi\""));
        assertEquals(new Json.Num("12"), Json.parse("12"));
    }

    @Test
    void aNumberKeepsItsExactText() {
        for (String text : new String[] {"0", "-0", "0.5", "-12.25e-3", "1E+9", "4.9E-324", "1.7976931348623157E308"}) {
            Object v = Json.parse(text);
            assertEquals(text, ((Json.Num) v).text());
        }
        assertEquals(0.1, ((Json.Num) Json.parse("0.1")).value(), 0.0);
    }

    @Test
    void objectsKeepFileOrderAndNest() {
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) Json.parse("{\"b\": 1, \"a\": [true, {\"c\": null}], \"z\": {}}");
        assertEquals(List.of("b", "a", "z"), List.copyOf(m.keySet()));
        List<?> a = (List<?>) m.get("a");
        assertEquals(2, a.size());
        assertTrue(((Map<?, ?>) a.get(1)).containsKey("c"));
        assertNull(((Map<?, ?>) a.get(1)).get("c"));
        assertTrue(((Map<?, ?>) m.get("z")).isEmpty());
    }

    @Test
    void emptyArrayAndWhitespaceAreFine() {
        assertEquals(List.of(), Json.parse("[ \n\t\r ]"));
    }

    @Test
    void stringEscapesAreDecoded() {
        assertEquals("a\"b\\c/d\b\f\n\r\tA", Json.parse("\"a\\\"b\\\\c\\/d\\b\\f\\n\\r\\t\\u0041\""));
        assertEquals("\u00e9\u20ac", Json.parse("\"\\u00E9\\u20ac\""));
    }

    @Test
    void aLeadingByteOrderMarkIsSkipped() {
        assertEquals(Boolean.TRUE, Json.parse("\uFEFFtrue"));
    }

    @Test
    void errorColumnsIgnoreALeadingByteOrderMark() {
        Json.JsonException plain = assertThrows(Json.JsonException.class, () -> Json.parse("hello"));
        Json.JsonException marked = assertThrows(Json.JsonException.class, () -> Json.parse("\uFEFFhello"));
        assertEquals(plain.getMessage(), marked.getMessage());
        assertTrue(marked.getMessage().contains("line 1, column 1:"));
    }

    @Test
    void quoteRoundTripsThroughParse() {
        String nasty = "line1\nline2\t\"quoted\" back\\slash \u0001 \u00e9 \u20ac";
        assertEquals(nasty, Json.parse(Json.quote(nasty)));
        assertEquals("\"\\u0001\"", Json.quote("\u0001"));
        assertEquals("\"\"", Json.quote(""));
    }

    @Test
    void rejectsTrailingCommas() {
        assertThrows(Json.JsonException.class, () -> Json.parse("[1,]"));
        assertThrows(Json.JsonException.class, () -> Json.parse("{\"a\":1,}"));
    }

    @Test
    void rejectsBadNumbers() {
        for (String text : new String[] {"01", "-", "1.", ".5", "+1", "1e", "1e+", "--1", "0x10", "NaN", "Infinity"}) {
            assertThrows(Json.JsonException.class, () -> Json.parse(text), text);
        }
    }

    @Test
    void aLeadingZeroIsNamedAsTheProblem() {
        for (String text : new String[] {"01", "-007", "[00]", "{\"a\": 012}"}) {
            Json.JsonException e = assertThrows(Json.JsonException.class, () -> Json.parse(text), text);
            assertTrue(e.getMessage().contains("leading zero"), text + " -> " + e.getMessage());
        }
        assertEquals("0", ((Json.Num) Json.parse("0")).text());
        assertEquals("0.5", ((Json.Num) Json.parse("0.5")).text());
        assertEquals("-0e5", ((Json.Num) Json.parse("-0e5")).text());
    }

    @Test
    void anUnprintableCharacterIsShownEscapedInTheMessage() {
        Json.JsonException e = assertThrows(Json.JsonException.class, () -> Json.parse("[\u0001]"));
        assertTrue(e.getMessage().contains("\\u0001"), e.getMessage());
        Json.JsonException d = assertThrows(Json.JsonException.class, () -> Json.parse("[\u007f]"));
        assertTrue(d.getMessage().contains("\\u007f"), d.getMessage());
    }

    @Test
    void rejectsWhatStandardJsonRejects() {
        for (String text : new String[] {"", "   ", "'a'", "{a:1}", "[1 2]", "{\"a\" 1}", "{\"a\":}", "tru", "nul",
                "// c\n1", "[1] [2]", "1 2", "\"abc", "\"a\nb\"", "\"\\x\"", "\"\\u12\"", "\"\\u12G4\"", "[", "{", "{\"a\":1"}) {
            assertThrows(Json.JsonException.class, () -> Json.parse(text), text);
        }
    }

    @Test
    void rejectsDuplicateKeys() {
        Json.JsonException e = assertThrows(Json.JsonException.class, () -> Json.parse("{\"a\":1,\"a\":2}"));
        assertTrue(e.getMessage().contains("duplicate key \"a\""), e.getMessage());
    }

    @Test
    void errorsSayWhereInLineAndColumn() {
        Json.JsonException e = assertThrows(Json.JsonException.class, () -> Json.parse("{\n  \"a\": 1,\n  \"b\": ?\n}"));
        assertTrue(e.getMessage().contains("line 3, column 8"), e.getMessage());
    }

    @Test
    void depthLimitIsEnforcedAndExactlyTheLimitIsAccepted() {
        String ok = "[".repeat(Json.MAX_DEPTH) + "]".repeat(Json.MAX_DEPTH);
        Json.parse(ok);
        String deep = "[".repeat(Json.MAX_DEPTH + 1) + "]".repeat(Json.MAX_DEPTH + 1);
        assertThrows(Json.JsonException.class, () -> Json.parse(deep));
        String deepObjects = "{\"a\":".repeat(Json.MAX_DEPTH + 1) + "1" + "}".repeat(Json.MAX_DEPTH + 1);
        assertThrows(Json.JsonException.class, () -> Json.parse(deepObjects));
    }

    @Test
    void nullTextIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> Json.parse(null));
    }

    @Test
    void parseErrorsAreIllegalArgumentExceptions() {
        assertTrue(IllegalArgumentException.class.isAssignableFrom(Json.JsonException.class));
    }
}
