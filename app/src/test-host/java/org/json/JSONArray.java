/*
 * Copyright 2026 Muralis contributors
 * All rights reserved. See LICENSE at the repository root.
 */
package org.json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** The host tests' stand-in for Android's org.json array; see JSONObject. */
public class JSONArray {
    final List<Object> list;

    public JSONArray() {
        list = new ArrayList<>();
    }

    public JSONArray(String text) throws JSONException {
        try {
            list = org.spazio17.muralis.HostJson.array(org.spazio17.muralis.HostJson.parse(text));
        } catch (RuntimeException malformed) {
            throw new JSONException(malformed.getMessage());
        }
    }

    public int length() {
        return list.size();
    }

    public JSONArray put(Object value) {
        list.add(value instanceof JSONObject ? ((JSONObject) value).map
                : value instanceof JSONArray ? ((JSONArray) value).list : value);
        return this;
    }

    public JSONObject optJSONObject(int index) {
        Object value = index < list.size() ? list.get(index) : null;
        return value instanceof Map ? new JSONObject(org.spazio17.muralis.HostJson.object(value)) : null;
    }

    @Override
    public String toString() {
        return org.spazio17.muralis.HostJson.write(list);
    }
}
