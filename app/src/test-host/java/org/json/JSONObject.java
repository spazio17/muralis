/*
 * Copyright 2026 Muralis contributors
 * All rights reserved. See LICENSE at the repository root.
 */
package org.json;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The host tests' stand-in for Android's org.json: the handful of calls Automations makes,
 * over TinyJson, so the rules' vocabulary, storage and engine run without the SDK.
 */
public class JSONObject {
    public static final Object NULL = new Object();
    final Map<String, Object> map;

    public JSONObject() {
        map = new LinkedHashMap<>();
    }

    JSONObject(Map<String, Object> map) {
        this.map = map;
    }

    public JSONObject(String text) throws JSONException {
        try {
            map = org.spazio17.muralis.HostJson.object(org.spazio17.muralis.HostJson.parse(text));
        } catch (RuntimeException malformed) {
            throw new JSONException(malformed.getMessage());
        }
    }

    public JSONObject put(String key, Object value) throws JSONException {
        map.put(key, value instanceof JSONArray ? ((JSONArray) value).list
                : value instanceof JSONObject ? ((JSONObject) value).map : value);
        return this;
    }

    public JSONObject put(String key, int value) throws JSONException {
        map.put(key, (long) value);
        return this;
    }

    public JSONObject put(String key, double value) throws JSONException {
        map.put(key, value);
        return this;
    }

    public JSONObject put(String key, boolean value) throws JSONException {
        map.put(key, value);
        return this;
    }

    public boolean has(String key) {
        return map.containsKey(key);
    }

    public String optString(String key, String fallback) {
        Object value = map.get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    public int optInt(String key, int fallback) {
        Object value = map.get(key);
        return value instanceof Number ? ((Number) value).intValue() : fallback;
    }

    public double optDouble(String key, double fallback) {
        Object value = map.get(key);
        return value instanceof Number ? ((Number) value).doubleValue() : fallback;
    }

    public boolean optBoolean(String key, boolean fallback) {
        Object value = map.get(key);
        return value instanceof Boolean ? (Boolean) value : fallback;
    }

    public boolean optBoolean(String key) {
        return optBoolean(key, false);
    }

    public JSONObject optJSONObject(String key) {
        Object value = map.get(key);
        return value instanceof Map ? new JSONObject(org.spazio17.muralis.HostJson.object(value)) : null;
    }

    @Override
    public String toString() {
        return org.spazio17.muralis.HostJson.write(map);
    }
}
