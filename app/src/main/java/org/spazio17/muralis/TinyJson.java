/*
 * Copyright 2026 Muralis contributors
 * All rights reserved. See LICENSE at the repository root.
 */
package org.spazio17.muralis;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A JSON reader with no Android in it, so the parsers that turn Bing's and Wikimedia's answers
 * into credited pictures ({@link PictureSources}) can be host-tested against recorded responses.
 * {@code org.json} lives in the platform jar and is not on the host's class path; the credit line
 * is a licence obligation, which is exactly the kind of code that deserves a test on every build.
 *
 * <p>Objects come back as {@link LinkedHashMap}, arrays as {@link ArrayList}, strings as
 * {@link String}, numbers as {@link Double}, booleans as {@link Boolean} and {@code null} as
 * {@code null}. Malformed input throws {@link IllegalArgumentException}; the callers treat that as
 * "the service answered something else" and keep the last good set.
 */
final class TinyJson {
    /**
     * How deep an answer may nest. A service answer is a handful of levels; thousands are a
     * stack overflow, which is an Error no caller catches, so it is refused as malformed instead
     * (2026-09-09 review).
     */
    private static final int MAX_DEPTH = 60;

    private final String text;
    private int at;
    private int depth;
    private int values;

    private TinyJson(String text) {
        this.text = text;
    }

    static Object parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("no JSON");
        }
        TinyJson reader = new TinyJson(text);
        Object value = reader.value();
        reader.skipSpace();
        if (reader.at != text.length()) {
            throw reader.error("trailing characters");
        }
        return value;
    }

    /** The map at {@code value}, or an empty one when it is not an object. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> object(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : new LinkedHashMap<>();
    }

    /** The list at {@code value}, or an empty one when it is not an array. */
    @SuppressWarnings("unchecked")
    static List<Object> array(Object value) {
        return value instanceof List ? (List<Object>) value : new ArrayList<>();
    }

    /** The string at {@code key}, or {@code fallback} when absent or not a string. */
    static String string(Map<String, Object> object, String key, String fallback) {
        Object value = object.get(key);
        return value instanceof String ? (String) value : fallback;
    }

    private Object value() {
        skipSpace();
        if (++values > 50_000) throw error("too many values");
        if (depth > MAX_DEPTH) {
            throw error("nested deeper than " + MAX_DEPTH);
        }
        if (at >= text.length()) {
            throw error("unexpected end");
        }
        char c = text.charAt(at);
        switch (c) {
            case '{':
                return object();
            case '[':
                return array();
            case '"':
                return string();
            case 't':
                return literal("true", Boolean.TRUE);
            case 'f':
                return literal("false", Boolean.FALSE);
            case 'n':
                return literal("null", null);
            default:
                if (c == '-' || (c >= '0' && c <= '9')) {
                    return number();
                }
                throw error("unexpected character '" + c + "'");
        }
    }

    private Map<String, Object> object() {
        Map<String, Object> result = new LinkedHashMap<>();
        depth++;
        at++;
        skipSpace();
        if (peek() == '}') {
            at++;
            depth--;
            return result;
        }
        while (true) {
            skipSpace();
            if (peek() != '"') {
                throw error("object key must be a string");
            }
            String key = string();
            skipSpace();
            if (peek() != ':') {
                throw error("':' expected");
            }
            at++;
            result.put(key, value());
            skipSpace();
            char next = peek();
            at++;
            if (next == '}') {
                depth--;
                return result;
            }
            if (next != ',') {
                throw error("',' or '}' expected");
            }
        }
    }

    private List<Object> array() {
        List<Object> result = new ArrayList<>();
        depth++;
        at++;
        skipSpace();
        if (peek() == ']') {
            at++;
            depth--;
            return result;
        }
        while (true) {
            result.add(value());
            skipSpace();
            char next = peek();
            at++;
            if (next == ']') {
                depth--;
                return result;
            }
            if (next != ',') {
                throw error("',' or ']' expected");
            }
        }
    }

    private String string() {
        at++;
        StringBuilder out = new StringBuilder();
        while (true) {
            if (at >= text.length()) {
                throw error("unterminated string");
            }
            char c = text.charAt(at++);
            if (c == '"') {
                return out.toString();
            }
            if (c != '\\') {
                if (c < 0x20) throw error("unescaped control character");
                out.append(c);
                continue;
            }
            if (at >= text.length()) {
                throw error("unterminated escape");
            }
            char e = text.charAt(at++);
            switch (e) {
                case '"': out.append('"'); break;
                case '\\': out.append('\\'); break;
                case '/': out.append('/'); break;
                case 'b': out.append('\b'); break;
                case 'f': out.append('\f'); break;
                case 'n': out.append('\n'); break;
                case 'r': out.append('\r'); break;
                case 't': out.append('\t'); break;
                case 'u':
                    if (at + 4 > text.length()) {
                        throw error("short unicode escape");
                    }
                    for (int i = at; i < at + 4; i++) {
                        char hex = text.charAt(i);
                        if (!((hex >= '0' && hex <= '9') || (hex >= 'a' && hex <= 'f')
                                || (hex >= 'A' && hex <= 'F'))) throw error("bad unicode escape");
                    }
                    try {
                        out.append((char) Integer.parseInt(text.substring(at, at + 4), 16));
                    } catch (NumberFormatException bad) {
                        throw error("bad unicode escape");
                    }
                    at += 4;
                    break;
                default:
                    throw error("unknown escape '\\" + e + "'");
            }
        }
    }

    private Double number() {
        int start = at;
        while (at < text.length()) {
            char c = text.charAt(at);
            if ((c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.' || c == 'e'
                    || c == 'E') {
                at++;
            } else {
                break;
            }
        }
        try {
            String token = text.substring(start, at);
            if (!token.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")) {
                throw error("bad number");
            }
            Double number = Double.valueOf(token);
            if (number.isInfinite()) throw error("number out of range");
            return number;
        } catch (NumberFormatException bad) {
            throw error("bad number");
        }
    }

    private Object literal(String word, Object value) {
        if (!text.startsWith(word, at)) {
            throw error("'" + word + "' expected");
        }
        at += word.length();
        return value;
    }

    private char peek() {
        if (at >= text.length()) {
            throw error("unexpected end");
        }
        return text.charAt(at);
    }

    private void skipSpace() {
        while (at < text.length()) {
            char c = text.charAt(at);
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t') {
                at++;
            } else {
                break;
            }
        }
    }

    private IllegalArgumentException error(String what) {
        return new IllegalArgumentException("JSON: " + what + " at " + at);
    }
}
