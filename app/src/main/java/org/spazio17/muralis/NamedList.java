/*
 * Copyright 2026 Muralis contributors
 * All rights reserved. See LICENSE at the repository root.
 */
package org.spazio17.muralis;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Names given to things by their ids, as one document: each entry its id, its name and when it
 * changed, and a marker for each name taken away, so the fleet of 0.7 can merge two copies,
 * the newer winning, and carry a deletion (2026-09-30). Today the beacons' names; the known
 * tags of 0.7 take the same shape. Pure, for the host tests.
 *
 * <pre>{"names": [{"id", "name", "changed_at"}], "deleted": [{"id", "deleted_at"}]}</pre>
 */
final class NamedList {
    /** Deletion markers kept, the newest. */
    static final int MAX_DELETED = 100;

    private final Map<String, String> names = new LinkedHashMap<>();
    private final Map<String, Long> changedAt = new LinkedHashMap<>();
    private final Map<String, Long> deleted = new LinkedHashMap<>();

    /** The document, or an empty list for nothing stored or a broken document. */
    static NamedList parse(String json) {
        NamedList list = new NamedList();
        if (json == null || json.isEmpty()) {
            return list;
        }
        try {
            JSONObject document = new JSONObject(json);
            JSONArray entries = document.optJSONArray("names");
            for (int index = 0; entries != null && index < entries.length(); index++) {
                JSONObject one = entries.optJSONObject(index);
                if (one != null && !one.optString("id", "").isEmpty()
                        && !one.optString("name", "").isEmpty()) {
                    list.names.put(one.optString("id", ""), one.optString("name", ""));
                    list.changedAt.put(one.optString("id", ""), one.optLong("changed_at", 0));
                }
            }
            JSONArray gone = document.optJSONArray("deleted");
            for (int index = 0; gone != null && index < gone.length(); index++) {
                JSONObject one = gone.optJSONObject(index);
                if (one != null && !one.optString("id", "").isEmpty()) {
                    list.deleted.put(one.optString("id", ""), one.optLong("deleted_at", 0));
                }
            }
        } catch (JSONException broken) {
            return new NamedList();
        }
        return list;
    }

    /** The name of an id, or empty. */
    String name(String id) {
        String name = names.get(id);
        return name == null ? "" : name;
    }

    /** Every id to its name, in the order they were named. */
    Map<String, String> names() {
        return new LinkedHashMap<>(names);
    }

    /** Names an id, or takes its name away when {@code name} is blank, which leaves a marker. */
    void set(String id, String name, long now) {
        String clean = name == null ? "" : name.trim();
        if (clean.isEmpty()) {
            if (names.remove(id) != null) {
                changedAt.remove(id);
                deleted.remove(id);
                deleted.put(id, now);
            }
            return;
        }
        if (!clean.equals(names.get(id))) {
            names.put(id, clean);
            changedAt.put(id, now);
            deleted.remove(id);
        }
    }

    String store() {
        JSONObject document = new JSONObject();
        try {
            JSONArray entries = new JSONArray();
            for (Map.Entry<String, String> one : names.entrySet()) {
                JSONObject entry = new JSONObject();
                entry.put("id", one.getKey());
                entry.put("name", one.getValue());
                Long at = changedAt.get(one.getKey());
                entry.put("changed_at", at == null ? 0L : at);
                entries.put(entry);
            }
            document.put("names", entries);
            List<Map.Entry<String, Long>> markers = new ArrayList<>(deleted.entrySet());
            JSONArray gone = new JSONArray();
            for (int index = Math.max(0, markers.size() - MAX_DELETED); index < markers.size();
                    index++) {
                JSONObject marker = new JSONObject();
                marker.put("id", markers.get(index).getKey());
                marker.put("deleted_at", markers.get(index).getValue());
                gone.put(marker);
            }
            document.put("deleted", gone);
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
        return document.toString();
    }
}
