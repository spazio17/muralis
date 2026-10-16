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
 * The panel's cameras for pop-ups (block 6, after Dashie): each a name, an address, a mode for
 * this panel, how long it shows and a cooldown. {@code camera.show} shows one on demand,
 * {@code camera.trigger} is what a doorbell sends. Pure: the list's form, its checks and the
 * decision whether a trigger shows are host-tested.
 */
final class PopupCameras {
    static final int MAX_CAMERAS = 10;
    static final int MAX_NAME = 40;
    static final int MAX_COOLDOWN = 3600;
    static final int DEFAULT_COOLDOWN = 60;

    static final String ON_DEMAND = "on_demand";
    static final String TRIGGER = "trigger";
    static final String ALERT = "alert";
    static final String IGNORED = "ignored";

    /** The modes in the order both surfaces list them, with their words. */
    static final String[][] MODES = {
            {ON_DEMAND, "On demand"},
            {TRIGGER, "On a trigger"},
            {ALERT, "On a trigger, with display on and chime"},
            {IGNORED, "Ignored on this panel"},
    };

    static final class Camera {
        final String name;
        final String url;
        final String mode;
        final int seconds;
        final int cooldown;

        Camera(String name, String url, String mode, int seconds, int cooldown) {
            this.name = name;
            this.url = url;
            this.mode = mode;
            this.seconds = seconds;
            this.cooldown = cooldown;
        }
    }

    private PopupCameras() {
    }

    static boolean isMode(String value) {
        for (String[] mode : MODES) {
            if (mode[0].equals(value)) {
                return true;
            }
        }
        return false;
    }

    /** Null when the camera can be stored; otherwise the reason. */
    static String check(String name, String url, String mode, int seconds, int cooldown) {
        if (name == null || name.trim().isEmpty() || name.trim().length() > MAX_NAME
                || name.chars().anyMatch(Character::isISOControl)) {
            return "the name must be one line of 1 to " + MAX_NAME + " characters";
        }
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) {
            return "the address must start with http:// or https://";
        }
        if (!isMode(mode)) {
            return "the mode must be on_demand, trigger, alert or ignored";
        }
        if (seconds < 0 || seconds > Popup.MAX_SECONDS) {
            return "the time must be 0 to " + Popup.MAX_SECONDS + " seconds";
        }
        if (cooldown < 0 || cooldown > MAX_COOLDOWN) {
            return "the cooldown must be 0 to " + MAX_COOLDOWN + " seconds";
        }
        return null;
    }

    /** The stored list from its JSON; anything unreadable or refused by the checks is left out. */
    static List<Camera> parse(String json) {
        List<Camera> cameras = new ArrayList<>();
        if (json == null || json.isEmpty()) {
            return cameras;
        }
        try {
            for (Object one : TinyJson.array(TinyJson.parse(json))) {
                Map<String, Object> entry = TinyJson.object(one);
                String name = TinyJson.string(entry, "name", "").trim();
                String url = TinyJson.string(entry, "url", "").trim();
                String mode = TinyJson.string(entry, "mode", ON_DEMAND);
                int seconds = (int) TinyJson.number(entry, "seconds", Popup.DEFAULT_SECONDS);
                int cooldown = (int) TinyJson.number(entry, "cooldown", DEFAULT_COOLDOWN);
                if (check(name, url, mode, seconds, cooldown) == null
                        && find(cameras, name) == null && cameras.size() < MAX_CAMERAS) {
                    cameras.add(new Camera(name, url, mode, seconds, cooldown));
                }
            }
        } catch (IllegalArgumentException unreadable) {
            return new ArrayList<>();
        }
        return cameras;
    }

    static String format(List<Camera> cameras) {
        List<Object> out = new ArrayList<>();
        for (Camera camera : cameras) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", camera.name);
            entry.put("url", camera.url);
            entry.put("mode", camera.mode);
            entry.put("seconds", (long) camera.seconds);
            entry.put("cooldown", (long) camera.cooldown);
            out.add(entry);
        }
        return TinyJson.write(out);
    }

    /** By name, ignoring case; null when there is none. */
    static Camera find(List<Camera> cameras, String name) {
        for (Camera camera : cameras) {
            if (camera.name.equalsIgnoreCase(name == null ? "" : name.trim())) {
                return camera;
            }
        }
        return null;
    }

    /**
     * The list with one camera put in (a new one at the end, an existing name replaced in
     * place); null when it would be the eleventh.
     */
    static List<Camera> with(List<Camera> cameras, Camera camera) {
        List<Camera> next = new ArrayList<>();
        boolean replaced = false;
        for (Camera one : cameras) {
            if (one.name.equalsIgnoreCase(camera.name)) {
                next.add(camera);
                replaced = true;
            } else {
                next.add(one);
            }
        }
        if (!replaced) {
            if (next.size() >= MAX_CAMERAS) {
                return null;
            }
            next.add(camera);
        }
        return next;
    }

    static List<Camera> without(List<Camera> cameras, String name) {
        List<Camera> next = new ArrayList<>();
        for (Camera one : cameras) {
            if (!one.name.equalsIgnoreCase(name)) {
                next.add(one);
            }
        }
        return next;
    }

    /**
     * Why a camera does not show now, or null when it does: {@code trigger} for a doorbell's
     * {@code camera.trigger}, false for {@code camera.show}. {@code lastShownMs} is when it last
     * showed on this panel, 0 for never.
     */
    static String refusal(Camera camera, boolean trigger, long lastShownMs, long nowMs) {
        if (camera == null) {
            return "no camera has that name";
        }
        if (IGNORED.equals(camera.mode)) {
            return "this camera is ignored on this panel";
        }
        if (!trigger) {
            return null;
        }
        if (ON_DEMAND.equals(camera.mode)) {
            return "this camera shows on demand only on this panel";
        }
        if (lastShownMs > 0 && nowMs - lastShownMs < camera.cooldown * 1000L) {
            return "cooling down: shown " + ((nowMs - lastShownMs) / 1000) + " s ago, cooldown "
                    + camera.cooldown + " s";
        }
        return null;
    }

    /** The pop-up a camera shows: its name as the title, its address, its time. */
    static Popup.Request popup(Camera camera, boolean trigger) {
        boolean alert = trigger && ALERT.equals(camera.mode);
        return new Popup.Request(camera.name, "", camera.url, camera.seconds, alert || !trigger,
                alert);
    }
}
