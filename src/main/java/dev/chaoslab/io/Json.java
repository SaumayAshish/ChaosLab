package dev.chaoslab.io;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small, strict JSON reader (RFC 8259) and string quoter, so that experiment files need no third-party library and
 * work offline.
 *
 * <p>{@link #parse} returns {@code Map<String, Object>} (in file order), {@code List<Object>}, {@code String},
 * {@link Num}, {@code Boolean} or {@code null}. A number is kept as its original text (a {@link Num}) so a caller
 * decides how to read it; nothing is rounded on the way in. The reader rejects what the standard rejects: trailing
 * commas, comments, single quotes, leading zeros, a lone minus, unescaped control characters in strings, duplicate keys
 * in one object, text after the value, and nesting deeper than {@value #MAX_DEPTH}. A leading byte-order mark is
 * skipped. Errors say where (line and column, counted from 1).
 */
public final class Json {

    /** Deepest nesting of objects and arrays accepted. */
    public static final int MAX_DEPTH = 64;

    /** The text of a JSON number, exactly as written. */
    public record Num(String text) {

        /** The value as a double (may be infinite for something like 1e999: callers check). */
        public double value() {
            return Double.parseDouble(text);
        }
    }

    /** A syntax error in JSON text. */
    public static final class JsonException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        JsonException(String message) {
            super(message);
        }
    }

    private final String s;
    private int i;

    private Json(String text) {
        this.s = text;
    }

    /**
     * Parses one JSON value.
     *
     * @throws JsonException            for malformed text
     * @throws IllegalArgumentException if {@code text} is null
     */
    public static Object parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("text must not be null");
        }
        Json p = new Json(text);
        if (p.s.startsWith("﻿")) {
            p.i = 1;
        }
        p.skipSpace();
        Object value = p.value(0);
        p.skipSpace();
        if (p.i < p.s.length()) {
            throw p.error("unexpected text after the end of the value");
        }
        return value;
    }

    /** The text as a JSON string literal, quotes included. */
    public static String quote(String text) {
        StringBuilder b = new StringBuilder(text.length() + 2);
        b.append('"');
        for (int k = 0; k < text.length(); k++) {
            char c = text.charAt(k);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                case '\b' -> b.append("\\b");
                case '\f' -> b.append("\\f");
                default -> {
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        return b.append('"').toString();
    }

    // ---------------------------------------------------------------- the reader

    private Object value(int depth) {
        if (i >= s.length()) {
            throw error("unexpected end of text, a value was expected");
        }
        char c = s.charAt(i);
        switch (c) {
            case '{':
                return object(depth + 1);
            case '[':
                return array(depth + 1);
            case '"':
                return string();
            case 't':
                literal("true");
                return Boolean.TRUE;
            case 'f':
                literal("false");
                return Boolean.FALSE;
            case 'n':
                literal("null");
                return null;
            default:
                if (c == '-' || (c >= '0' && c <= '9')) {
                    return number();
                }
                throw error("unexpected character '" + printable(c) + "'");
        }
    }

    private Map<String, Object> object(int depth) {
        checkDepth(depth);
        Map<String, Object> map = new LinkedHashMap<>();
        i++; // {
        skipSpace();
        if (peek() == '}') {
            i++;
            return map;
        }
        while (true) {
            skipSpace();
            if (peek() != '"') {
                throw error("a quoted key was expected");
            }
            int keyAt = i;
            String key = string();
            skipSpace();
            expect(':');
            skipSpace();
            Object v = value(depth);
            if (map.containsKey(key)) {
                i = keyAt;
                throw error("duplicate key \"" + key + "\"");
            }
            map.put(key, v);
            skipSpace();
            char c = peek();
            i++;
            if (c == '}') {
                return map;
            }
            if (c != ',') {
                i--;
                throw error("',' or '}' was expected");
            }
        }
    }

    private List<Object> array(int depth) {
        checkDepth(depth);
        List<Object> list = new ArrayList<>();
        i++; // [
        skipSpace();
        if (peek() == ']') {
            i++;
            return list;
        }
        while (true) {
            skipSpace();
            list.add(value(depth));
            skipSpace();
            char c = peek();
            i++;
            if (c == ']') {
                return list;
            }
            if (c != ',') {
                i--;
                throw error("',' or ']' was expected");
            }
        }
    }

    private String string() {
        i++; // opening quote
        StringBuilder b = new StringBuilder();
        while (true) {
            if (i >= s.length()) {
                throw error("the string is not closed");
            }
            char c = s.charAt(i);
            if (c == '"') {
                i++;
                return b.toString();
            }
            if (c < 0x20) {
                throw error("a control character must be escaped inside a string");
            }
            if (c == '\\') {
                i++;
                if (i >= s.length()) {
                    throw error("the string is not closed");
                }
                char e = s.charAt(i);
                switch (e) {
                    case '"' -> b.append('"');
                    case '\\' -> b.append('\\');
                    case '/' -> b.append('/');
                    case 'b' -> b.append('\b');
                    case 'f' -> b.append('\f');
                    case 'n' -> b.append('\n');
                    case 'r' -> b.append('\r');
                    case 't' -> b.append('\t');
                    case 'u' -> {
                        if (i + 4 >= s.length()) {
                            throw error("an escape \\u needs four hexadecimal digits");
                        }
                        int code = 0;
                        for (int k = 1; k <= 4; k++) {
                            int d = Character.digit(s.charAt(i + k), 16);
                            if (d < 0) {
                                i += k;
                                throw error("an escape \\u needs four hexadecimal digits");
                            }
                            code = code * 16 + d;
                        }
                        b.append((char) code);
                        i += 4;
                    }
                    default -> throw error("unknown escape \\" + printable(e));
                }
                i++;
            } else {
                b.append(c);
                i++;
            }
        }
    }

    private Num number() {
        int start = i;
        if (peek() == '-') {
            i++;
        }
        if (i >= s.length() || !isDigit(s.charAt(i))) {
            throw error("a digit was expected in the number");
        }
        if (s.charAt(i) == '0') {
            i++;
            if (i < s.length() && isDigit(s.charAt(i))) {
                throw error("a number must not have a leading zero");
            }
        } else {
            while (i < s.length() && isDigit(s.charAt(i))) {
                i++;
            }
        }
        if (i < s.length() && s.charAt(i) == '.') {
            i++;
            if (i >= s.length() || !isDigit(s.charAt(i))) {
                throw error("a digit was expected after the decimal point");
            }
            while (i < s.length() && isDigit(s.charAt(i))) {
                i++;
            }
        }
        if (i < s.length() && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
            i++;
            if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) {
                i++;
            }
            if (i >= s.length() || !isDigit(s.charAt(i))) {
                throw error("a digit was expected in the exponent");
            }
            while (i < s.length() && isDigit(s.charAt(i))) {
                i++;
            }
        }
        return new Num(s.substring(start, i));
    }

    private void literal(String word) {
        if (!s.startsWith(word, i)) {
            throw error("'" + word + "' was expected");
        }
        i += word.length();
    }

    private void expect(char c) {
        if (peek() != c) {
            throw error("'" + c + "' was expected");
        }
        i++;
    }

    private char peek() {
        return i < s.length() ? s.charAt(i) : '\0';
    }

    private void skipSpace() {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                i++;
            } else {
                return;
            }
        }
    }

    private void checkDepth(int depth) {
        if (depth > MAX_DEPTH) {
            throw error("nesting is deeper than " + MAX_DEPTH);
        }
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static String printable(char c) {
        return c < 0x20 || c == 0x7f ? String.format("\\u%04x", (int) c) : String.valueOf(c);
    }

    private JsonException error(String message) {
        int line = 1;
        int column = 1;
        int end = Math.min(i, s.length());
        for (int k = 0; k < end; k++) {
            if (k == 0 && s.charAt(0) == '\uFEFF') {
                continue; // a byte-order mark is not a visible column
            }
            if (s.charAt(k) == '\n') {
                line++;
                column = 1;
            } else {
                column++;
            }
        }
        return new JsonException("Invalid JSON at line " + line + ", column " + column + ": " + message);
    }
}
