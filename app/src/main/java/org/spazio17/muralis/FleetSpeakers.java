/*
 * Copyright 2026 Juri Calleri (spazio17) and the Muralis contributors
 * Licensed under the PolyForm Shield License 1.0.0: see LICENSE.md at the repository root.
 */
package org.spazio17.muralis;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The fleet's panels as speakers, the idea of multi-room audio made for announcements: the
 * Speakers card on both surfaces asks every panel how it plays, and sends Say, Play, Stop, a
 * volume or the network speaker's switch to all panels, a group, or chosen ones. Each panel is
 * asked over its member endpoint at once, in parallel; one that does not answer is reported as
 * not reached and is never sent it later (the no-backlog rule). Only a panel whose network speaker
 * is switched on takes sound (KioskService.speakerAction). Not on the main thread.
 */
final class FleetSpeakers {
    private FleetSpeakers() {
    }

    /**
     * A request not sent within this long is never sent (the no-backlog rule: a slow link does
     * not make a panel play after the card said it was not reached).
     */
    private static final long SEND_BY_MS = 10_000;
    /** How long the card waits: the last moment a request may go, then one read's deadline. */
    private static final long WAIT_MS = SEND_BY_MS + 9_000;
    /** A sentence for the fleet: an announcement, and within what one call to a panel carries. */
    static final int MAX_SENTENCE = 1000;
    static final int MAX_ADDRESS = 2000;

    /** Every panel of the fleet as a speaker: id, name, whether it answered, on, playing, volume. */
    static JSONObject states(Context context) {
        return states(context, null);
    }

    /** The same for the panel {@code onlyId} alone (the Fleet page's panel detail), or all. */
    static JSONObject states(Context context, String onlyId) {
        List<Map<String, Object>> members = new ArrayList<>();
        for (Map<String, Object> member
                : FleetDocument.members(KioskConfig.fleetDocumentOf(context))) {
            if (onlyId == null || onlyId.equals(TinyJson.string(member, FleetDocument.ID, ""))) {
                members.add(member);
            }
        }
        JSONObject[] answers = new JSONObject[members.size()];
        runAll(members, (member, at) -> answers[at] = FleetServer.speakerStateOf(context, member));
        // Copied once: an answer arriving after the wait does not change what was reported.
        JSONObject[] seen = answers.clone();
        JSONArray panels = new JSONArray();
        try {
            for (int at = 0; at < members.size(); at++) {
                Map<String, Object> member = members.get(at);
                JSONObject one = new JSONObject();
                one.put("id", TinyJson.string(member, FleetDocument.ID, ""));
                one.put("name", nameOf(member));
                one.put("self", FleetServer.isSelf(context, member));
                one.put("reached", seen[at] != null);
                if (seen[at] != null && seen[at].optBoolean("too_old")) {
                    one.put("too_old", true);
                } else if (seen[at] != null) {
                    one.put("on", seen[at].optBoolean("on"));
                    one.put("playing", seen[at].optBoolean("playing"));
                    one.put("volume", seen[at].optInt("volume", -1));
                }
                panels.put(one);
            }
            return new JSONObject().put("panels", panels);
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /**
     * Sends {@code action} to the panels {@code target} names: {@code all}, {@code group:<id>},
     * or {@code panels:<id>,<id>}. Answers each panel's outcome and one line for a person: "Said
     * on 2 panels." or "Said on 1 of 2: kitchen was not reached."
     */
    static JSONObject act(Context context, String target, String action, String value) {
        Map<String, Object> document = KioskConfig.fleetDocumentOf(context);
        List<Map<String, Object>> chosen = new ArrayList<>();
        List<Map<String, Object>> members = FleetDocument.members(document);
        if (target.equals("all")) {
            chosen.addAll(members);
        } else if (target.startsWith("group:")) {
            String group = target.substring("group:".length());
            for (Map<String, Object> member : members) {
                if (FleetDocument.memberGroups(document, member).contains(group)) {
                    chosen.add(member);
                }
            }
        } else if (target.startsWith("panels:")) {
            List<String> ids = java.util.Arrays.asList(
                    target.substring("panels:".length()).split(","));
            for (Map<String, Object> member : members) {
                if (ids.contains(TinyJson.string(member, FleetDocument.ID, ""))) {
                    chosen.add(member);
                }
            }
        }
        try {
            if (!java.util.Arrays.asList("say", "play", "stop", "volume", "switch")
                    .contains(action)) {
                return new JSONObject().put("error", "no such speaker action: " + action);
            }
            if ("say".equals(action) && value != null && value.length() > MAX_SENTENCE) {
                return new JSONObject().put("error", "a sentence for the fleet is at most "
                        + MAX_SENTENCE + " characters");
            }
            if ("play".equals(action) && value != null && value.length() > MAX_ADDRESS) {
                return new JSONObject().put("error", "the address is longer than "
                        + MAX_ADDRESS + " characters");
            }
            if (chosen.isEmpty()) {
                return new JSONObject().put("error", members.isEmpty()
                        ? "this panel is not in a fleet" : "no panel is chosen");
            }
            String[] outcomes = new String[chosen.size()];
            // A panel still not answered when the wait ends counts as not reached, never done.
            java.util.Arrays.fill(outcomes, "not reached");
            long sendBy = android.os.SystemClock.elapsedRealtime() + SEND_BY_MS;
            runAll(chosen, (member, at) -> outcomes[at] =
                    FleetServer.speakerActionOn(context, member, action, value, sendBy));
            // Copied once, after the wait: a call still running has not sent (or is reported
            // as sent with no answer by the line it set), and a late answer changes nothing here.
            String[] reported = outcomes.clone();
            JSONArray results = new JSONArray();
            List<String> missed = new ArrayList<>();
            int done = 0;
            for (int at = 0; at < chosen.size(); at++) {
                String outcome = reported[at];
                results.put(new JSONObject()
                        .put("id", TinyJson.string(chosen.get(at), FleetDocument.ID, ""))
                        .put("name", nameOf(chosen.get(at)))
                        .put("result", outcome == null ? "done" : outcome));
                if (outcome == null) {
                    done++;
                } else {
                    missed.add(nameOf(chosen.get(at)) + ": " + outcome);
                }
            }
            String verb = verb(action, value);
            String on = "switch".equals(action) ? " for "
                    : "say".equals(action) || "play".equals(action) ? " to " : " on ";
            String line = missed.isEmpty()
                    ? verb + on + count(done) + "."
                    : verb + on + done + " of " + count(chosen.size()) + ". "
                            + String.join("; ", missed) + ".";
            return new JSONObject().put("status", missed.isEmpty() ? "accepted" : "partial")
                    .put("detail", line).put("results", results);
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String verb(String action, String value) {
        switch (action) {
            case "say": return "Sentence sent";
            case "play": return "Sound sent";
            case "stop": return "Stopped";
            case "volume": return "Volume set";
            default: return "on".equals(value) ? "Network speaker switched on"
                    : "Network speaker switched off";
        }
    }

    private static String count(int panels) {
        return panels == 1 ? "1 panel" : panels + " panels";
    }

    private static String nameOf(Map<String, Object> member) {
        return TinyJson.string(member, FleetDocument.NAME,
                TinyJson.string(member, FleetDocument.ID, ""));
    }

    private interface PerPanel {
        void run(Map<String, Object> member, int at);
    }

    /** Asks every panel at once and waits for all, at most {@link #WAIT_MS}. */
    private static void runAll(List<Map<String, Object>> members, PerPanel each) {
        List<Thread> calls = new ArrayList<>();
        for (int at = 0; at < members.size(); at++) {
            Map<String, Object> member = members.get(at);
            int index = at;
            Thread call = new Thread(() -> each.run(member, index), "MuralisFleetSpeaker");
            call.start();
            calls.add(call);
        }
        long until = System.currentTimeMillis() + WAIT_MS;
        for (Thread call : calls) {
            try {
                call.join(Math.max(1, until - System.currentTimeMillis()));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
