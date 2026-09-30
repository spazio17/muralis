/*
 * Copyright 2026 Muralis contributors
 * All rights reserved. See LICENSE at the repository root.
 */
package org.spazio17.muralis;

import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The panel's sensors: one flat list, alphabetical on the pages, the companion app's "Manage
 * sensors" (Juri, 2026-09-26 and 2026-09-27). Every row is the same shape and only its right
 * edge says what kind it is: a switch for the ones a person turns on, a chevron beside the
 * switch for the ones with a page of their own, Allow while a permission is missing, the reading
 * alone for what the panel always reports (display, screensaver, battery, power, network,
 * processor, memory), greyed at the end for what this device lacks.
 *
 * <p>Every reading is a row on both surfaces, a field of the {@code sensors} block in the status
 * document, a Home Assistant entity through discovery for the switchable ones (the panel's own
 * values have had their entities since before this class), and a sample for the automations:
 * {@link Automations.Engine} is fed from here, by the hardware listeners as readings arrive and
 * by {@link #poll} on the service's two-second tick for everything read on demand and, again,
 * for the last hardware reading, so a rule's "for N minutes" advances on a sensor that reports
 * on change alone.
 *
 * <p>A hardware sensor registers its listener only while it is on, asking for the wake-up
 * variant so it answers under a real sleep. The camera and the microphone live in their own
 * classes and are asked through {@link #source}.
 */
final class Sensors implements SensorEventListener {
    private static final String TAG = "MuralisSensors";

    static final String SENSOR = "sensor";
    static final String BINARY = "binary_sensor";

    /** How a row is controlled. */
    enum Kind {
        /** On or off by its switch. */
        SWITCH,
        /** On or off by its switch, once its permission is granted. */
        PERMISSION,
        /** What the panel always reports: no switch. */
        PANEL
    }

    /** One sensor's fixed description: what it is called and how Home Assistant should draw it. */
    static final class Def {
        final String id;
        final String name;
        final String platform;
        final String deviceClass;
        final String unit;
        final String stateClass;
        /** The Android sensor type behind it, or 0 for a state read on demand. */
        final int androidType;
        final Kind kind;
        /** Whether the sensor has settings of its own, and so a page. */
        final boolean page;
        /** The glyph both surfaces draw for it. */
        final String glyph;

        Def(String id, String name, String platform, String deviceClass, String unit,
                String stateClass, int androidType, Kind kind, boolean page, String glyph) {
            this.id = id;
            this.name = name;
            this.platform = platform;
            this.deviceClass = deviceClass;
            this.unit = unit;
            this.stateClass = stateClass;
            this.androidType = androidType;
            this.kind = kind;
            this.page = page;
            this.glyph = glyph;
        }
    }

    static final Def AUDIO = new Def("audio", "Audio", SENSOR, "enum", null, null, 0,
            Kind.SWITCH, false, "audio");
    static final Def BATTERY = new Def("battery", "Battery", SENSOR, "battery", "%",
            "measurement", 0, Kind.PANEL, false, "battery");
    static final Def CAMERA = new Def("camera", "Camera", BINARY, "motion", null, null, 0,
            Kind.PERMISSION, true, "camera");
    static final Def DISPLAY = new Def("display", "Display", BINARY, null, null, null, 0,
            Kind.PANEL, false, "display");
    static final Def HUMIDITY = new Def("humidity", "Humidity", SENSOR, "humidity", "%",
            "measurement", Sensor.TYPE_RELATIVE_HUMIDITY, Kind.SWITCH, true, "humidity");
    static final Def LIGHT = new Def("light", "Light", SENSOR, "illuminance", "lx",
            "measurement", Sensor.TYPE_LIGHT, Kind.SWITCH, true, "light");
    static final Def MEMORY = new Def("memory", "Memory", SENSOR, null, "%", "measurement", 0,
            Kind.PANEL, false, "memory");
    static final Def MICROPHONE = new Def("microphone", "Microphone", SENSOR, null, "%",
            "measurement", 0, Kind.PERMISSION, true, "microphone");
    static final Def MOVEMENT = new Def("movement", "Movement", BINARY, "moving", null, null,
            Sensor.TYPE_ACCELEROMETER, Kind.SWITCH, true, "movement");
    static final Def NETWORK = new Def("network", "Network", SENSOR, "enum", null, null, 0,
            Kind.PANEL, false, "network");
    static final Def POWER = new Def("power", "Power", SENSOR, "enum", null, null, 0,
            Kind.PANEL, false, "power");
    static final Def PRESSURE = new Def("pressure", "Pressure", SENSOR, "atmospheric_pressure",
            "hPa", "measurement", Sensor.TYPE_PRESSURE, Kind.SWITCH, true, "pressure");
    static final Def PROCESSOR = new Def("processor", "Processor", SENSOR, null, "%",
            "measurement", 0, Kind.PANEL, false, "processor");
    static final Def PROXIMITY = new Def("proximity", "Proximity", SENSOR, "distance", "cm",
            "measurement", Sensor.TYPE_PROXIMITY, Kind.SWITCH, true, "proximity");
    static final Def SCREENSAVER = new Def("screensaver", "Screensaver", BINARY, "running", null,
            null, 0, Kind.PANEL, false, "screensaver");
    static final Def TEMPERATURE = new Def("temperature", "Ambient temperature", SENSOR,
            "temperature", "°C", "measurement", Sensor.TYPE_AMBIENT_TEMPERATURE, Kind.SWITCH,
            true, "temperature");

    /** Every sensor this app knows, alphabetical by name, which is the order the pages use. */
    static final List<Def> ALL = Collections.unmodifiableList(Arrays.asList(
            TEMPERATURE, AUDIO, BATTERY, CAMERA, DISPLAY, HUMIDITY, LIGHT, MEMORY, MICROPHONE,
            MOVEMENT, NETWORK, POWER, PRESSURE, PROCESSOR, PROXIMITY, SCREENSAVER));

    /** What the camera and the microphone report, asked when a document is built. */
    interface Reading {
        /** Fills {@code one} with value and attributes; may leave value null. */
        void fill(JSONObject one) throws JSONException;

        /** The sample for the automations, or null when there is nothing to say yet. */
        Automations.Sample sample();
    }

    private final Context context;
    private final SensorManager manager;
    private final Automations.Engine engine;
    private final Map<String, Float> readings = new HashMap<>();

    /**
     * How the proximity sensor's events are read on this device once a person has calibrated
     * it: which of the event's values moves under a hand, the line between covered and clear,
     * and on which side of it near lies. Android defines the first value, a distance, and
     * "near" as less than the range; a driver that never moves it can only be read after the
     * person has shown the panel both states (Juri, 2026-09-27: no guessing at slots, a
     * procedure the user runs). Null until calibrated, and Android's rule applies.
     */
    static final class Calibration {
        final int slot;
        final float threshold;
        final boolean nearHigh;

        Calibration(int slot, float threshold, boolean nearHigh) {
            this.slot = slot;
            this.threshold = threshold;
            this.nearHigh = nearHigh;
        }

        boolean near(float[] values) {
            if (slot >= values.length) {
                return false;
            }
            return nearHigh ? values[slot] > threshold : values[slot] < threshold;
        }

        static Calibration of(Context context) {
            int slot = KioskConfig.sensorOptionInt(context, "proximity_slot", -1);
            if (slot < 0) {
                return null;
            }
            try {
                return new Calibration(slot, Float.parseFloat(
                        KioskConfig.sensorOption(context, "proximity_threshold", "")),
                        KioskConfig.sensorOptionOn(context, "proximity_near_high", false));
            } catch (NumberFormatException broken) {
                return null;
            }
        }

        /**
         * The calibration two averaged readings give, the sensor covered and clear: the value
         * that moved the most, relative to its size, is the one to watch, the line is halfway,
         * and near is the covered side. Null when nothing moved enough to tell the two apart.
         */
        static Calibration derive(float[] covered, float[] clear) {
            int best = -1;
            float bestRelative = 0;
            for (int slot = 0; slot < Math.min(covered.length, clear.length); slot++) {
                float gap = Math.abs(covered[slot] - clear[slot]);
                float scale = Math.max(Math.max(Math.abs(covered[slot]), Math.abs(clear[slot])),
                        0.001f);
                float relative = gap / scale;
                if (relative > bestRelative) {
                    bestRelative = relative;
                    best = slot;
                }
            }
            if (best < 0 || bestRelative < 0.2f) {
                return null;
            }
            return new Calibration(best, (covered[best] + clear[best]) / 2,
                    covered[best] > clear[best]);
        }
    }

    /** When each hardware sensor last reported, for the test of a sensor while the panel sleeps. */
    private final Map<String, Long> lastEventAt = new HashMap<>();
    /** What each hardware sensor last reported, every value, for the same test. */
    private final Map<String, float[]> lastValues = new HashMap<>();

    /** The sensor's last event, every value, or null before the first; a copy. */
    float[] lastValues(String id) {
        synchronized (this) {
            float[] values = lastValues.get(id);
            return values == null ? null : values.clone();
        }
    }

    /**
     * Whether the sensor's reading moved since {@code before}: the value Android defines, the
     * first, or the calibrated one for a calibrated proximity sensor. Not the whole event: the
     * MediaPad's driver answers a registration with a different raw count in an undefined slot
     * while the reading stays what it was, and that is no sensor watching (2026-09-27).
     */
    boolean readingMovedSince(Def def, float[] before) {
        float[] after = lastValues(def.id);
        if (after == null || before == null) {
            return after != null;
        }
        Calibration calibration = def == PROXIMITY ? proximityCalibration : null;
        int slot = calibration == null ? 0 : calibration.slot;
        if (slot >= after.length || slot >= before.length) {
            return false;
        }
        return after[slot] != before[slot];
    }

    /** When the sensor last reported, in elapsed-realtime milliseconds, or 0 never. */
    long lastEventAt(String id) {
        synchronized (this) {
            Long at = lastEventAt.get(id);
            return at == null ? 0 : at;
        }
    }

    private void noteEvent(Def def, float[] values) {
        synchronized (this) {
            lastEventAt.put(def.id, SystemClock.elapsedRealtime());
            lastValues.put(def.id, values.clone());
        }
    }

    private volatile Calibration proximityCalibration;
    /** The proximity events of the last seconds, each {time, values...}, for a calibration. */
    private final java.util.ArrayDeque<float[]> proximityRecent = new java.util.ArrayDeque<>();
    /** The last proximity events the calibration reads: a few seconds at any sensor rate. */
    private static final int PROXIMITY_RECENT_MAX = 64;
    private static final long RECENT_MS = 4_000;
    private final Map<String, Boolean> registered = new HashMap<>();
    private float lastMagnitude = Float.NaN;
    // Far in the past, not minus the hold: elapsed time is small right after a boot, and a
    // start at minus the hold read as "Moving" until the hold had passed (review, 2026-09-27).
    private volatile long movedAtMs = Long.MIN_VALUE / 2;
    private volatile boolean near;
    private final Map<String, Reading> sources = new HashMap<>();
    /** Told when a sensor changes state (near, moving), so nobody waits for the next tick. */
    private volatile Runnable onEdge;
    /** Told when a reading that moves all the time moved (light, pressure), for the rows. */
    private volatile Runnable onReading;
    /** Whether the last look said moving, for the edges in either direction. */
    private boolean wasMoving;

    /**
     * Who hears of a change of state the moment it happens: the rows showed "Near" up to three
     * seconds late, the service's two-second tick then the panel's one-second one, and a
     * broker heard of it on the next telemetry interval (Juri, 2026-09-28).
     */
    void onEdge(Runnable listener) {
        onEdge = listener;
    }

    /**
     * Who hears of a new reading from a sensor that moves all the time: the panel's rows, so
     * a hand over the light sensor shows at once; finding where a panel keeps its light
     * sensor was a guess against a reading two or three seconds late (Juri, 2026-09-28).
     */
    void onReading(Runnable listener) {
        onReading = listener;
    }

    private void edge() {
        Runnable listener = onEdge;
        if (listener != null) {
            listener.run();
        }
    }

    /** Moving started or ended since the last look; the start is seen on the event, the end
     * on the poll, when the still time has passed. */
    private void movingEdge() {
        boolean now;
        boolean changed;
        // Read, compared and fed under one lock of its own: the sensor's events (main thread)
        // and the poll (telemetry thread) both come here, and reading moving() outside it let
        // the two feed false and true out of order (review, 2026-10-01). Its own lock, not
        // this one, since feeding runs the rules' actions.
        synchronized (movementEdgeLock) {
            now = moving();
            synchronized (this) {
                changed = now != wasMoving;
                wasMoving = now;
            }
            if (changed) {
                // The rules hear of it on the edge, as the rows do: fed only on the poll,
                // "picked up" ran up to two seconds late (review, 2026-10-01).
                feed(MOVEMENT, Automations.Sample.of(now));
            }
        }
        if (changed) {
            edge();
        }
    }

    private final Object movementEdgeLock = new Object();

    Sensors(Context context, Automations.Engine engine) {
        this.context = context;
        this.manager = context.getSystemService(SensorManager.class);
        this.engine = engine;
    }

    /** Hands over the class that reads one of the sensors this one does not read itself. */
    void source(Def def, Reading reading) {
        synchronized (sources) {
            if (reading == null) {
                sources.remove(def.id);
            } else {
                sources.put(def.id, reading);
            }
        }
    }

    private Reading sourceOf(Def def) {
        synchronized (sources) {
            return sources.get(def.id);
        }
    }

    static Def byId(String id) {
        for (Def def : ALL) {
            if (def.id.equals(id)) {
                return def;
            }
        }
        return null;
    }

    /** The names by id, for the automation editor's vocabulary. */
    static Map<String, String> names() {
        Map<String, String> names = new java.util.LinkedHashMap<>();
        for (Def def : ALL) {
            names.put(def.id, def.name);
        }
        return names;
    }

    /** Whether this device has the sensor at all; an absent one is greyed at the end of the list. */
    boolean available(Def def) {
        if (def.androidType != 0) {
            return manager != null && manager.getDefaultSensor(def.androidType) != null;
        }
        PackageManager packages = context.getPackageManager();
        if (def == CAMERA) {
            return packages.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY);
        }
        if (def == MICROPHONE) {
            return packages.hasSystemFeature(PackageManager.FEATURE_MICROPHONE);
        }
        if (def == BATTERY) {
            SystemStats.RuntimeFacts facts = KioskRuntimeState.lastFacts();
            return facts == null || facts.batteryPresent;
        }
        return true;
    }

    /** The runtime permissions the sensor needs on this Android, none for most. */
    static String[] permissionsFor(Def def) {
        if (def == CAMERA) {
            return new String[] {android.Manifest.permission.CAMERA};
        }
        if (def == MICROPHONE) {
            return new String[] {android.Manifest.permission.RECORD_AUDIO};
        }
        return new String[0];
    }

    /**
     * The one rule book for a sensor's settings, whichever surface stores them: the reason a
     * value is refused, or null when it may be stored as {@link #cleanOption} gives it.
     */
    static String checkOption(String key, String value) {
        String clean = value == null ? "" : value.trim();
        switch (key) {
            case "movement_sensitivity":
                if (!clean.equals("light") && !clean.equals("normal") && !clean.equals("heavy")) {
                    return "sensitivity must be light, normal or heavy";
                }
                return null;
            case "camera_sensitivity":
                if (!clean.equals("low") && !clean.equals("normal") && !clean.equals("high")) {
                    return "sensitivity must be low, normal or high";
                }
                return null;
            case "movement_still_s": case "camera_still_s":
                if (!clean.matches("\\d{1,5}") || Integer.parseInt(clean) < 1) {
                    return "the seconds must be 1 or more";
                }
                return null;
            case "camera_lens":
                if (!clean.equals("front") && !clean.equals("back")) {
                    return "the lens is front or back";
                }
                return null;
            case "camera_size":
                if (!PanelCamera.SIZES.contains(clean)) {
                    return "that size is not offered";
                }
                return null;
            case "camera_fps":
                if (!clean.matches("\\d{1,2}")
                        || !PanelCamera.RATES.contains(Integer.parseInt(clean))) {
                    return "that frame rate is not offered";
                }
                return null;
            case "camera_orientation":
                if (!PanelCamera.ORIENTATIONS.contains(clean)) {
                    return "the orientation is device, portrait or landscape";
                }
                return null;
            case "camera_name":
                return clean.length() > 40 ? "the name is too long" : null;
            case "camera_mirror": case "camera_flip":
            case "camera_watermark": case "camera_motion": case "camera_mqtt":
                // true or false in the forms every surface sends; "yes" used to mean off
                // without a word (review, 2026-10-01).
                return isSwitchWord(clean) ? null : key + " must be true or false";
            default:
                return "no sensor setting is called " + key;
        }
    }

    private static boolean isSwitchWord(String clean) {
        switch (clean.toLowerCase(java.util.Locale.ROOT)) {
            case "true": case "false": case "on": case "off": case "1": case "0":
                return true;
            default:
                return false;
        }
    }

    /** The value as it is stored once {@link #checkOption} passed it. */
    static String cleanOption(String key, String value) {
        String clean = value == null ? "" : value.trim();
        switch (key) {
            case "camera_mirror": case "camera_flip":
            case "camera_watermark": case "camera_motion": case "camera_mqtt":
                return Boolean.toString(clean.equalsIgnoreCase("true") || clean.equalsIgnoreCase("on")
                        || clean.equals("1"));
            default:
                return clean;
        }
    }

    /** Whether every permission the sensor needs is granted. */
    boolean permitted(Def def) {
        return permitted(context, def);
    }

    /** The same, judged live by whoever holds a context: the panel's rows read it this way. */
    static boolean permitted(Context context, Def def) {
        for (String permission : permissionsFor(def)) {
            if (context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether the sensor's switch is on. Every sensor has one (Juri, 2026-09-27): the panel's
     * own values start on, since they were always reported before; the rest start off.
     */
    static boolean enabled(Context context, Def def) {
        return KioskConfig.sensorEnabled(context, def.id, def.kind == Kind.PANEL);
    }

    /** Whether the sensor is on: switched on and, where one is needed, permitted. */
    boolean on(Def def) {
        return enabled(context, def) && permitted(def);
    }

    /** Which sensors were on at the last refresh, for the engine's reset on the off edge. */
    private final Map<String, Boolean> wasOn = new HashMap<>();

    /** Registers the hardware listeners the stored switches ask for, and drops the others. */
    synchronized void refresh() {
        proximityCalibration = Calibration.of(context);
        for (Def def : ALL) {
            boolean now = available(def) && on(def);
            if (Boolean.TRUE.equals(wasOn.get(def.id)) && !now && engine != null) {
                // A rule waiting on its minutes must not carry the old start across an off
                // and on: the sensor starts over, and so do its rules.
                engine.reset(def.id);
            }
            wasOn.put(def.id, now);
        }
        for (Def def : ALL) {
            if (def.androidType == 0 || !available(def)) {
                continue;
            }
            boolean wanted = on(def);
            boolean have = Boolean.TRUE.equals(registered.get(def.id));
            if (wanted == have) {
                continue;
            }
            if (wanted) {
                register(def);
                Log.i(TAG, def.name + " on");
            } else {
                unregister(def);
                readings.remove(def.id);
                Log.i(TAG, def.name + " off");
            }
            registered.put(def.id, wanted);
        }
    }

    /**
     * Registers the sensor's listener: the wake-up variant where there is one, so proximity
     * still answers while the panel sleeps; the ordinary one otherwise, which works under the
     * black film.
     */
    private void register(Def def) {
        Sensor sensor = manager.getDefaultSensor(def.androidType);
        Sensor waking = manager.getDefaultSensor(def.androidType, true);
        manager.registerListener(this, waking != null ? waking : sensor,
                def == MOVEMENT ? SensorManager.SENSOR_DELAY_UI : SensorManager.SENSOR_DELAY_NORMAL);
    }

    /** Drops both variants' listeners, whichever one register took. */
    private void unregister(Def def) {
        manager.unregisterListener(this, manager.getDefaultSensor(def.androidType));
        Sensor waking = manager.getDefaultSensor(def.androidType, true);
        if (waking != null) {
            manager.unregisterListener(this, waking);
        }
    }

    /**
     * Registers the sensor again: an on-change sensor that is alive answers a registration with
     * its current value, a sensor the device stopped answers nothing, which is what the test
     * while asleep needs from a sensor nobody is touching (review, 2026-09-27).
     */
    synchronized void reRegister(Def def) {
        if (manager == null || def.androidType == 0 || !Boolean.TRUE.equals(registered.get(def.id))) {
            return;
        }
        unregister(def);
        register(def);
    }

    /** Drops every listener, for the service going away. */
    synchronized void stop() {
        if (manager != null) {
            manager.unregisterListener(this);
        }
        registered.clear();
        readings.clear();
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        float value = event.values[0];
        switch (event.sensor.getType()) {
            case Sensor.TYPE_PROXIMITY: {
                noteEvent(PROXIMITY, event.values);
                long now = SystemClock.elapsedRealtime();
                synchronized (this) {
                    readings.put(PROXIMITY.id, value);
                    float[] raw = new float[event.values.length + 1];
                    raw[0] = now;
                    System.arraycopy(event.values, 0, raw, 1, event.values.length);
                    proximityRecent.addLast(raw);
                    while (proximityRecent.size() > PROXIMITY_RECENT_MAX) {
                        proximityRecent.removeFirst();
                    }
                }
                // Near is less than the sensor's range; most phones answer 0 or the range only.
                // A calibrated panel reads the event the way the person showed it instead.
                Calibration calibration = proximityCalibration;
                boolean nowNear = calibration != null ? calibration.near(event.values)
                        : value < event.sensor.getMaximumRange();
                boolean flipped = nowNear != near;
                near = nowNear;
                feed(PROXIMITY, Automations.Sample.of(value, nowNear));
                if (flipped) {
                    edge();
                }
                break;
            }
            case Sensor.TYPE_ACCELEROMETER: {
                noteEvent(MOVEMENT, event.values);
                float magnitude = (float) Math.sqrt(event.values[0] * event.values[0]
                        + event.values[1] * event.values[1] + event.values[2] * event.values[2]);
                if (!Float.isNaN(lastMagnitude)
                        && Math.abs(magnitude - lastMagnitude) > movementThreshold()) {
                    movedAtMs = SystemClock.elapsedRealtime();
                }
                lastMagnitude = magnitude;
                // The start is told now; the still edge is seen on the poll when the hold ends.
                movingEdge();
                break;
            }
            default: {
                Def def = byType(event.sensor.getType());
                if (def != null) {
                    noteEvent(def, event.values);
                    Float before;
                    synchronized (this) {
                        before = readings.put(def.id, value);
                    }
                    feed(def, Automations.Sample.of(value));
                    Runnable listener = onReading;
                    if (listener != null && (before == null || before != value)) {
                        listener.run();
                    }
                }
            }
        }
    }

    /** How hard a push counts as movement, from the sensitivity the Movement page stores. */
    private float movementThreshold() {
        switch (KioskConfig.sensorOption(context, "movement_sensitivity", "normal")) {
            case "light": return 0.6f;
            case "heavy": return 4f;
            default: return 1.5f;
        }
    }

    private long movementHoldMs() {
        return KioskConfig.sensorOptionInt(context, "movement_still_s", 5) * 1000L;
    }

    boolean moving() {
        return SystemClock.elapsedRealtime() - movedAtMs < movementHoldMs();
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    private static Def byType(int type) {
        for (Def def : ALL) {
            if (def.androidType == type) {
                return def;
            }
        }
        return null;
    }

    private void feed(Def def, Automations.Sample sample) {
        if (engine == null || sample == null) {
            return;
        }
        engine.sample(def.id, sample, SystemClock.elapsedRealtime(), minuteOfDay());
    }

    static int minuteOfDay() {
        java.util.Calendar now = java.util.Calendar.getInstance();
        return now.get(java.util.Calendar.HOUR_OF_DAY) * 60 + now.get(java.util.Calendar.MINUTE);
    }

    /**
     * The proximity events of the last seconds averaged, or, since the sensor reports on change
     * alone and a hand held still sends nothing new, the last event by itself; null before the
     * first event.
     */
    private float[] recentProximityAverage() {
        synchronized (this) {
            long now = SystemClock.elapsedRealtime();
            float[] last = proximityRecent.peekLast();
            if (last == null) {
                return null;
            }
            float[] sum = null;
            int count = 0;
            for (float[] raw : proximityRecent) {
                if (now - raw[0] > RECENT_MS && raw != last) {
                    continue;
                }
                if (sum == null) {
                    sum = new float[raw.length - 1];
                }
                for (int slot = 0; slot < sum.length && slot + 1 < raw.length; slot++) {
                    sum[slot] += raw[slot + 1];
                }
                count++;
            }
            if (sum == null) {
                return null;
            }
            for (int slot = 0; slot < sum.length; slot++) {
                sum[slot] /= count;
            }
            return sum;
        }
    }

    /**
     * One step of the proximity calibration: "covered" keeps what the sensor reads under a
     * hand, "clear" reads it again in the open and derives the rule, "reset" goes back to
     * Android's distance. Null when done, else why not.
     */
    String calibrateProximity(String step) {
        switch (step == null ? "" : step) {
            case "reset":
                KioskConfig.edit(context).removeSensorOption("proximity_slot")
                        .removeSensorOption("proximity_threshold")
                        .removeSensorOption("proximity_near_high")
                        .removeSensorOption("proximity_covered").apply();
                proximityCalibration = null;
                Log.i(TAG, "Proximity calibration reset");
                return null;
            case "covered": {
                float[] covered = recentProximityAverage();
                if (covered == null) {
                    return "the sensor has not reported in the last seconds";
                }
                KioskConfig.edit(context).sensorOption("proximity_covered", join(covered)).apply();
                return null;
            }
            case "clear": {
                float[] covered = split(KioskConfig.sensorOption(context, "proximity_covered", ""));
                if (covered == null) {
                    return "cover the sensor first";
                }
                float[] clear = recentProximityAverage();
                if (clear == null) {
                    return "the sensor has not reported in the last seconds";
                }
                Calibration derived = Calibration.derive(covered, clear);
                KioskConfig.edit(context).removeSensorOption("proximity_covered").apply();
                if (derived == null) {
                    return "the sensor read the same covered and clear";
                }
                KioskConfig.edit(context)
                        .sensorOption("proximity_slot", Integer.toString(derived.slot))
                        .sensorOption("proximity_threshold", Float.toString(derived.threshold))
                        .sensorOption("proximity_near_high", Boolean.toString(derived.nearHigh))
                        .apply();
                proximityCalibration = derived;
                Log.i(TAG, "Proximity calibrated: value " + derived.slot + ", near "
                        + (derived.nearHigh ? "above " : "below ") + derived.threshold);
                return null;
            }
            default:
                return "the step must be covered, clear or reset";
        }
    }

    private static String join(float[] values) {
        StringBuilder text = new StringBuilder();
        for (float value : values) {
            text.append(text.length() == 0 ? "" : ",").append(value);
        }
        return text.toString();
    }

    private static float[] split(String text) {
        if (text.isEmpty()) {
            return null;
        }
        try {
            String[] parts = text.split(",");
            float[] values = new float[parts.length];
            for (int index = 0; index < parts.length; index++) {
                values[index] = Float.parseFloat(parts[index]);
            }
            return values;
        } catch (NumberFormatException broken) {
            return null;
        }
    }

    /**
     * Feeds the automations every reading that is not delivered by a listener: the panel's own
     * values, the movement hold, what the other classes report, and the last reading of each
     * hardware sensor once more (a steady room sends no light event, and "darker for 10 min"
     * has to advance anyway). Called on the service's two-second tick.
     */
    void poll() {
        if (available(MOVEMENT) && on(MOVEMENT)) {
            movingEdge();
        }
        if (engine == null) {
            return;
        }
        // The hardware sensors and movement feed the engine from their own events, once each;
        // feeding their last reading again here handed it an older sample after the newer one
        // and could fire a rule twice (review, 2026-10-01). The tick advances their minutes.
        long now = SystemClock.elapsedRealtime();
        for (Def def : ALL) {
            if (!available(def) || !on(def)) {
                continue;
            }
            Automations.Sample sample = sampleOf(def);
            if (sample == null) {
                continue;
            }
            if (def.androidType != 0 || def == MOVEMENT) {
                // Fed on their own events; here a rule that has seen nothing yet (a new or an
                // edited one, or every rule after a start) is only seeded with the state as it
                // stands, so the first event after it is a change and fires. Without this the
                // first event itself was the seed and was swallowed (review, 2026-10-01).
                engine.seed(def.id, sample, now);
            } else {
                feed(def, sample);
            }
        }
        engine.tick(now, minuteOfDay());
    }

    private Automations.Sample sampleOf(Def def) {
        if (def == MOVEMENT) {
            return Automations.Sample.of(moving());
        }
        if (def == AUDIO) {
            AudioManager audio = context.getSystemService(AudioManager.class);
            return audio == null ? null : Automations.Sample.of(audio.isMusicActive());
        }
        if (def == DISPLAY) {
            PowerManager power = context.getSystemService(PowerManager.class);
            return Automations.Sample.of(power == null || power.isInteractive());
        }
        if (def == SCREENSAVER) {
            return Automations.Sample.of(KioskRuntimeState.screensaverActive());
        }
        if (def == BATTERY) {
            SystemStats.RuntimeFacts facts = KioskRuntimeState.lastFacts();
            return facts == null || facts.batteryPercent < 0 ? null
                    : Automations.Sample.of(facts.batteryPercent, facts.plugged);
        }
        if (def == NETWORK) {
            return Automations.Sample.of(networkKind() != null);
        }
        if (def == PROCESSOR) {
            SystemStats.Sample sample = KioskRuntimeState.lastSample();
            return sample == null ? null : new Automations.Sample(sample.cpuBusyPercent, null,
                    sample.cpuTemperatureC);
        }
        if (def.androidType != 0) {
            // The last value again: a sensor that reports on change alone (light in a steady
            // room) would otherwise never advance a rule's "for N minutes".
            Float value;
            synchronized (this) {
                value = readings.get(def.id);
            }
            if (value == null) {
                return null;
            }
            return def == PROXIMITY ? Automations.Sample.of(value, near)
                    : Automations.Sample.of(value);
        }
        Reading source = sourceOf(def);
        return source == null ? null : source.sample();
    }

    /** "wifi", "ethernet", "mobile", or null while nothing is connected. */
    private String networkKind() {
        ConnectivityManager connectivity = context.getSystemService(ConnectivityManager.class);
        if (connectivity == null) {
            return null;
        }
        Network active = connectivity.getActiveNetwork();
        NetworkCapabilities capabilities = active == null ? null
                : connectivity.getNetworkCapabilities(active);
        if (capabilities == null) {
            return null;
        }
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            return "wifi";
        }
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
            return "ethernet";
        }
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            return "mobile";
        }
        return "other";
    }

    /**
     * The block the status document carries: one object per sensor this app knows, with its
     * kind, whether this device has it, whether it is on and, when it is, its reading, its
     * attributes and the one line a row shows.
     */
    JSONObject snapshot() {
        JSONObject block = new JSONObject();
        try {
            for (Def def : ALL) {
                JSONObject one = new JSONObject();
                one.put("name", def.name);
                one.put("kind", def.kind.name().toLowerCase(java.util.Locale.ROOT));
                one.put("page", def.page);
                one.put("glyph", def.glyph);
                boolean available = available(def);
                one.put("available", available);
                boolean permitted = available && permitted(def);
                one.put("permitted", permitted);
                boolean on = available && on(def);
                one.put("enabled", enabled(context, def));
                one.put("active", on);
                if (on) {
                    fill(def, one);
                }
                String reading = describe(def, one);
                if (def.androidType != 0) {
                    // What the test while asleep found: "reports", "silent", or nothing yet.
                    // Some devices stop a sensor while the display sleeps, and a person
                    // building a rule on it is told so on the row (Juri, 2026-09-27).
                    one.put("asleep", KioskConfig.sensorOption(context, def.id + "_asleep", ""));
                }
                one.put("reading", reading);
                block.put(def.id, one);
            }
        } catch (JSONException impossible) {
            // Keys are constants and values are primitives.
        }
        return block;
    }

    private void fill(Def def, JSONObject one) throws JSONException {
        if (def == MOVEMENT) {
            one.put("value", moving());
            return;
        }
        if (def == AUDIO) {
            AudioManager audio = context.getSystemService(AudioManager.class);
            if (audio == null) {
                one.put("value", JSONObject.NULL);
                return;
            }
            one.put("value", audio.isMusicActive() ? "playing" : "idle");
            JSONObject attributes = new JSONObject();
            attributes.put("volume_music", volumePercent(audio, AudioManager.STREAM_MUSIC));
            attributes.put("volume_alarm", volumePercent(audio, AudioManager.STREAM_ALARM));
            attributes.put("volume_notification",
                    volumePercent(audio, AudioManager.STREAM_NOTIFICATION));
            one.put("attributes", attributes);
            return;
        }
        if (def == DISPLAY) {
            PowerManager power = context.getSystemService(PowerManager.class);
            one.put("value", power == null || power.isInteractive());
            return;
        }
        if (def == SCREENSAVER) {
            one.put("value", KioskRuntimeState.screensaverActive());
            return;
        }
        if (def == BATTERY) {
            SystemStats.RuntimeFacts facts = KioskRuntimeState.lastFacts();
            if (facts == null || facts.batteryPercent < 0) {
                one.put("value", JSONObject.NULL);
                return;
            }
            one.put("value", Math.round(facts.batteryPercent));
            JSONObject attributes = new JSONObject();
            attributes.put("state", SystemStats.chargeStateLabel(facts));
            attributes.put("plugged", facts.plugged);
            one.put("attributes", attributes);
            return;
        }
        if (def == POWER) {
            SystemStats.RuntimeFacts facts = KioskRuntimeState.lastFacts();
            if (facts == null) {
                one.put("value", JSONObject.NULL);
                return;
            }
            one.put("value", !facts.batteryPresent || facts.plugged ? "mains" : "battery");
            if (!Double.isNaN(facts.mainsWatts)) {
                JSONObject attributes = new JSONObject();
                attributes.put("watts", Math.round(facts.mainsWatts * 10) / 10.0);
                one.put("attributes", attributes);
            }
            return;
        }
        if (def == NETWORK) {
            String kind = networkKind();
            one.put("value", kind == null ? "none" : kind);
            SystemStats.RuntimeFacts facts = KioskRuntimeState.lastFacts();
            if (facts != null) {
                JSONObject attributes = new JSONObject();
                attributes.put("ip_address", facts.ipAddress);
                if (facts.wifiRssiDbm != SystemStats.UNKNOWN) {
                    attributes.put("wifi_rssi_dbm", facts.wifiRssiDbm);
                }
                one.put("attributes", attributes);
            }
            return;
        }
        if (def == PROCESSOR) {
            SystemStats.Sample sample = KioskRuntimeState.lastSample();
            if (sample == null || Double.isNaN(sample.cpuBusyPercent)) {
                one.put("value", JSONObject.NULL);
            } else {
                one.put("value", Math.round(sample.cpuBusyPercent));
            }
            if (sample != null && !Double.isNaN(sample.cpuTemperatureC)) {
                JSONObject attributes = new JSONObject();
                attributes.put("temperature_c", Math.round(sample.cpuTemperatureC * 10) / 10.0);
                one.put("attributes", attributes);
            }
            return;
        }
        if (def == MEMORY) {
            SystemStats.Sample sample = KioskRuntimeState.lastSample();
            if (sample == null || sample.memTotalKb == SystemStats.UNKNOWN) {
                one.put("value", JSONObject.NULL);
                return;
            }
            one.put("value", SystemStats.usedPercent(sample.memUsedKb(), sample.memTotalKb));
            JSONObject attributes = new JSONObject();
            attributes.put("used_kb", sample.memUsedKb());
            attributes.put("total_kb", sample.memTotalKb);
            one.put("attributes", attributes);
            return;
        }
        Reading source = sourceOf(def);
        if (source != null) {
            source.fill(one);
            return;
        }
        Float reading;
        synchronized (this) {
            reading = readings.get(def.id);
        }
        if (reading == null) {
            one.put("value", JSONObject.NULL);
        } else if (def == PROXIMITY) {
            one.put("value", Math.round(reading * 10) / 10.0);
            one.put("near", near);
            one.put("calibrated", proximityCalibration != null);
        } else {
            one.put("value", Math.round(reading * 10) / 10.0);
        }
    }

    static int volumePercent(AudioManager audio, int stream) {
        int max = audio.getStreamMaxVolume(stream);
        return max <= 0 ? 0 : Math.round(100f * audio.getStreamVolume(stream) / max);
    }

    /** The one line a row shows under the name, from a filled snapshot entry. */
    static String describe(Def def, JSONObject one) {
        if (!one.optBoolean("available")) {
            return "Not on this device";
        }
        if (!one.optBoolean("active")) {
            if (def.kind == Kind.PERMISSION && !one.optBoolean("permitted")) {
                return "Needs permission";
            }
            return "Off";
        }
        Object value = one.opt("value");
        JSONObject attributes = one.optJSONObject("attributes");
        if (value == null || value == JSONObject.NULL) {
            return "--";
        }
        if (def == PROXIMITY) {
            return one.optBoolean("near") ? "Near" : "Far";
        }
        if (def == MOVEMENT) {
            return (Boolean) value ? "Moving" : "Still";
        }
        if (def == DISPLAY) {
            return (Boolean) value ? "On" : "Off";
        }
        if (def == SCREENSAVER) {
            return (Boolean) value ? "Showing" : "Off";
        }
        if (def == CAMERA) {
            return (Boolean) value ? "Motion" : "Nothing moving";
        }
        if (def == AUDIO) {
            int volume = attributes == null ? -1 : attributes.optInt("volume_music", -1);
            return ("playing".equals(value) ? "Playing" : "Idle")
                    + (volume < 0 ? "" : ", media volume " + volume + "%");
        }
        if (def == BATTERY) {
            String state = attributes == null ? "" : attributes.optString("state", "");
            return value + "%" + (state.isEmpty() ? "" : ", " + state);
        }
        if (def == POWER) {
            double watts = attributes == null ? Double.NaN : attributes.optDouble("watts");
            return ("mains".equals(value) ? "Mains" : "Battery")
                    + (Double.isNaN(watts) ? "" : ", " + watts + " W");
        }
        if (def == NETWORK) {
            String ip = attributes == null ? "" : attributes.optString("ip_address", "");
            String kind = "wifi".equals(value) ? "Wi-Fi" : "ethernet".equals(value) ? "Ethernet"
                    : "mobile".equals(value) ? "Mobile" : "none".equals(value) ? "None" : "Other";
            return kind + (ip.isEmpty() ? "" : ", " + ip);
        }
        if (def == PROCESSOR) {
            double celsius = attributes == null ? Double.NaN : attributes.optDouble("temperature_c");
            return value + "% busy" + (Double.isNaN(celsius) ? "" : ", " + celsius + " °C");
        }
        if (def == MEMORY) {
            return value + "% used";
        }
        if (def == MICROPHONE) {
            return value + "%";
        }
        return value + (def.unit == null ? "" : " " + def.unit);
    }

    /**
     * What each list of sensors and rules was drawn from, so a surface can tell that another
     * one changed what it shows, and draw it again: the settings page's two sections (the
     * sensors on, the rules on), the Sensors page (every sensor, whether this device has it and
     * may read it) and the Automations page (every rule by name and sentence; its switches
     * follow on their own). A rule added on the web stayed off the panel's pages until they
     * were reopened (Juri, 2026-09-28). Short hashes, compared, never read.
     */
    static JSONObject listKeys(JSONObject sensors, JSONArray rules) {
        StringBuilder on = new StringBuilder();
        StringBuilder all = new StringBuilder();
        for (Def def : ALL) {
            JSONObject one = sensors == null ? null : sensors.optJSONObject(def.id);
            if (one == null) {
                continue;
            }
            if (one.optBoolean("active")) {
                on.append(def.id).append(',');
            }
            all.append(def.id).append(one.optBoolean("available") ? 'a' : '-')
                    .append(one.optBoolean("permitted") ? 'p' : '-').append(',');
        }
        StringBuilder rulesOn = new StringBuilder();
        StringBuilder rulesAll = new StringBuilder();
        for (int index = 0; rules != null && index < rules.length(); index++) {
            JSONObject rule = rules.optJSONObject(index);
            if (rule == null) {
                continue;
            }
            String line = rule.optString("id", "") + '|' + rule.optString("name", "") + '|'
                    + rule.optString("sensor", "") + '|' + rule.optString("sentence", "") + '\n';
            rulesAll.append(line);
            if (rule.optBoolean("enabled")) {
                rulesOn.append(line);
            }
        }
        JSONObject keys = new JSONObject();
        try {
            keys.put("sensors_home", Integer.toHexString(on.toString().hashCode()));
            keys.put("sensors_page", Integer.toHexString(all.toString().hashCode()));
            keys.put("automations_home", Integer.toHexString(rulesOn.toString().hashCode()));
            keys.put("automations_page", Integer.toHexString(rulesAll.toString().hashCode()));
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
        return keys;
    }

    /** "4 of 12 on", counting every sensor this device has, for the summary lines. */
    static String summary(JSONObject block) {
        int have = 0;
        int on = 0;
        for (Def def : ALL) {
            JSONObject one = block == null ? null : block.optJSONObject(def.id);
            if (one == null || !one.optBoolean("available")) {
                continue;
            }
            have++;
            if (one.optBoolean("active")) {
                on++;
            }
        }
        return have == 0 ? "None on this device" : on + " of " + have + " on";
    }
}
