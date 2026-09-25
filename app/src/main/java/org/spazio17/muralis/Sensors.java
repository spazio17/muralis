/*
 * Copyright 2026 Muralis contributors
 * All rights reserved. See LICENSE at the repository root.
 */
package org.spazio17.muralis;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioManager;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The panel's sensors, each one a row a person switches on, a reading in the status document
 * and a Home Assistant entity through discovery.
 *
 * <p>Modelled on the Home Assistant companion app's "Manage sensors" (Juri, 2026-09-26): every
 * sensor is off until somebody switches it on, a sensor the device does not have is not listed,
 * and one that is on reports its reading and its attributes. The hardware ones register a
 * listener only while they are on, so a panel with everything off costs nothing; the state ones
 * (Bluetooth, NFC, audio) are read when the status document is built. The names are the ones the
 * companion app gives its sensors, so the entities land as {@code sensor.muralis_<id>_light} and
 * friends under the panel's device.
 *
 * <p>One sensor also acts: proximity, when on, wakes the display the moment something comes
 * near, which is the use case that started this (a case over the power button, a hand in front
 * of the panel). Only the change to near counts, at most once every few seconds, so a mount that
 * covers the sensor does not wake the panel again and again; a person who finds that switches
 * the row off, the companion app's answer to the same problem.
 */
final class Sensors implements SensorEventListener {
    private static final String TAG = "MuralisSensors";

    /** Which discovery platform a sensor announces itself on. */
    static final String SENSOR = "sensor";
    static final String BINARY = "binary_sensor";

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

        Def(String id, String name, String platform, String deviceClass, String unit,
                String stateClass, int androidType) {
            this.id = id;
            this.name = name;
            this.platform = platform;
            this.deviceClass = deviceClass;
            this.unit = unit;
            this.stateClass = stateClass;
            this.androidType = androidType;
        }
    }

    static final Def PROXIMITY = new Def("proximity", "Proximity", SENSOR, "distance", "cm",
            "measurement", Sensor.TYPE_PROXIMITY);
    static final Def LIGHT = new Def("light", "Light", SENSOR, "illuminance", "lx",
            "measurement", Sensor.TYPE_LIGHT);
    static final Def PRESSURE = new Def("pressure", "Pressure", SENSOR, "atmospheric_pressure",
            "hPa", "measurement", Sensor.TYPE_PRESSURE);
    static final Def TEMPERATURE = new Def("temperature", "Ambient temperature", SENSOR,
            "temperature", "°C", "measurement", Sensor.TYPE_AMBIENT_TEMPERATURE);
    static final Def HUMIDITY = new Def("humidity", "Humidity", SENSOR, "humidity", "%",
            "measurement", Sensor.TYPE_RELATIVE_HUMIDITY);
    static final Def MOVEMENT = new Def("movement", "Movement", BINARY, "moving", null, null,
            Sensor.TYPE_ACCELEROMETER);
    static final Def BLUETOOTH = new Def("bluetooth", "Bluetooth", BINARY, null, null, null, 0);
    static final Def NFC = new Def("nfc", "NFC", BINARY, null, null, null, 0);
    static final Def AUDIO = new Def("audio", "Ringer mode", SENSOR, "enum", null, null, 0);

    /** Every sensor this app knows, in the order the two surfaces list them. */
    static final List<Def> ALL = Collections.unmodifiableList(Arrays.asList(
            PROXIMITY, LIGHT, PRESSURE, TEMPERATURE, HUMIDITY, MOVEMENT, BLUETOOTH, NFC, AUDIO));

    /** A movement stays reported this long after the last shake, so an automation can see it. */
    private static final long MOVING_HOLD_MS = 5_000L;
    /** Acceleration change, in m/s², that counts as the panel being handled rather than noise. */
    private static final float MOVE_THRESHOLD = 1.5f;
    /** How soon proximity may wake the display again. */
    private static final long WAKE_GAP_MS = 3_000L;

    private final Context context;
    private final SensorManager manager;
    private final Runnable onNear;
    private final Map<String, Float> readings = new HashMap<>();
    private final Map<String, Boolean> registered = new HashMap<>();
    private float lastMagnitude = Float.NaN;
    private long movedAtMs = -MOVING_HOLD_MS;
    private long wokeAtMs = -WAKE_GAP_MS;
    private boolean near;

    Sensors(Context context, Runnable onNear) {
        this.context = context;
        this.manager = context.getSystemService(SensorManager.class);
        this.onNear = onNear;
    }

    static Def byId(String id) {
        for (Def def : ALL) {
            if (def.id.equals(id)) {
                return def;
            }
        }
        return null;
    }

    /** Whether this device has the sensor at all; an absent one is not listed anywhere. */
    boolean available(Def def) {
        if (def.androidType != 0) {
            return manager != null && manager.getDefaultSensor(def.androidType) != null;
        }
        if (def == BLUETOOTH) {
            return context.getPackageManager().hasSystemFeature(
                    android.content.pm.PackageManager.FEATURE_BLUETOOTH);
        }
        if (def == NFC) {
            return context.getPackageManager().hasSystemFeature(
                    android.content.pm.PackageManager.FEATURE_NFC);
        }
        return true;
    }

    /** Registers the hardware listeners the stored switches ask for, and drops the others. */
    synchronized void refresh() {
        for (Def def : ALL) {
            if (def.androidType == 0 || !available(def)) {
                continue;
            }
            boolean wanted = KioskConfig.sensorEnabled(context, def.id);
            boolean have = Boolean.TRUE.equals(registered.get(def.id));
            if (wanted == have) {
                continue;
            }
            Sensor sensor = manager.getDefaultSensor(def.androidType);
            if (wanted) {
                // The wake-up variant where there is one, so proximity still answers while the
                // panel sleeps; the ordinary one otherwise, which works under the black film.
                Sensor waking = manager.getDefaultSensor(def.androidType, true);
                manager.registerListener(this, waking != null ? waking : sensor,
                        def == MOVEMENT ? SensorManager.SENSOR_DELAY_UI
                                : SensorManager.SENSOR_DELAY_NORMAL);
                Log.i(TAG, def.name + " on");
            } else {
                manager.unregisterListener(this, sensor);
                Sensor waking = manager.getDefaultSensor(def.androidType, true);
                if (waking != null) {
                    manager.unregisterListener(this, waking);
                }
                readings.remove(def.id);
                Log.i(TAG, def.name + " off");
            }
            registered.put(def.id, wanted);
        }
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
                synchronized (this) {
                    readings.put(PROXIMITY.id, value);
                }
                // Near is less than the sensor's range; most phones answer 0 or the range only.
                boolean nowNear = value < event.sensor.getMaximumRange();
                long now = SystemClock.elapsedRealtime();
                if (nowNear && !near && now - wokeAtMs >= WAKE_GAP_MS) {
                    wokeAtMs = now;
                    onNear.run();
                }
                near = nowNear;
                break;
            }
            case Sensor.TYPE_ACCELEROMETER: {
                float magnitude = (float) Math.sqrt(event.values[0] * event.values[0]
                        + event.values[1] * event.values[1] + event.values[2] * event.values[2]);
                if (!Float.isNaN(lastMagnitude)
                        && Math.abs(magnitude - lastMagnitude) > MOVE_THRESHOLD) {
                    movedAtMs = SystemClock.elapsedRealtime();
                }
                lastMagnitude = magnitude;
                break;
            }
            default: {
                Def def = byType(event.sensor.getType());
                if (def != null) {
                    synchronized (this) {
                        readings.put(def.id, value);
                    }
                }
            }
        }
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

    /**
     * The block the status document carries: one object per sensor the device has, with
     * whether it is on and, when it is, its reading and attributes.
     */
    JSONObject snapshot() {
        JSONObject block = new JSONObject();
        try {
            for (Def def : ALL) {
                if (!available(def)) {
                    continue;
                }
                JSONObject one = new JSONObject();
                boolean on = KioskConfig.sensorEnabled(context, def.id);
                one.put("name", def.name);
                one.put("enabled", on);
                if (on) {
                    fill(def, one);
                }
                block.put(def.id, one);
            }
        } catch (JSONException impossible) {
            // Keys are constants and values are primitives.
        }
        return block;
    }

    private void fill(Def def, JSONObject one) throws JSONException {
        if (def == MOVEMENT) {
            one.put("value", SystemClock.elapsedRealtime() - movedAtMs < MOVING_HOLD_MS);
            return;
        }
        if (def == BLUETOOTH) {
            android.bluetooth.BluetoothAdapter adapter = null;
            android.bluetooth.BluetoothManager bluetooth =
                    context.getSystemService(android.bluetooth.BluetoothManager.class);
            if (bluetooth != null) {
                adapter = bluetooth.getAdapter();
            }
            one.put("value", adapter != null && adapter.isEnabled());
            return;
        }
        if (def == NFC) {
            android.nfc.NfcAdapter nfc = android.nfc.NfcAdapter.getDefaultAdapter(context);
            one.put("value", nfc != null && nfc.isEnabled());
            return;
        }
        if (def == AUDIO) {
            AudioManager audio = context.getSystemService(AudioManager.class);
            if (audio == null) {
                one.put("value", JSONObject.NULL);
                return;
            }
            String mode;
            switch (audio.getRingerMode()) {
                case AudioManager.RINGER_MODE_SILENT: mode = "silent"; break;
                case AudioManager.RINGER_MODE_VIBRATE: mode = "vibrate"; break;
                default: mode = "normal";
            }
            one.put("value", mode);
            JSONObject attributes = new JSONObject();
            attributes.put("music_active", audio.isMusicActive());
            attributes.put("volume_music", volumePercent(audio, AudioManager.STREAM_MUSIC));
            attributes.put("volume_alarm", volumePercent(audio, AudioManager.STREAM_ALARM));
            attributes.put("volume_notification",
                    volumePercent(audio, AudioManager.STREAM_NOTIFICATION));
            attributes.put("volume_ring", volumePercent(audio, AudioManager.STREAM_RING));
            one.put("attributes", attributes);
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
        } else {
            one.put("value", Math.round(reading * 10) / 10.0);
        }
    }

    static int volumePercent(AudioManager audio, int stream) {
        int max = audio.getStreamMaxVolume(stream);
        return max <= 0 ? 0 : Math.round(100f * audio.getStreamVolume(stream) / max);
    }

    /** The one-line reading a row shows, from a snapshot entry; empty for a sensor that is off. */
    static String describe(JSONObject one) {
        if (one == null || !one.optBoolean("enabled")) {
            return "";
        }
        Object value = one.opt("value");
        if (value == null || value == JSONObject.NULL) {
            return "--";
        }
        String name = one.optString("name");
        if (value instanceof Boolean) {
            if ("Movement".equals(name)) {
                return (Boolean) value ? "moving" : "still";
            }
            return (Boolean) value ? "on" : "off";
        }
        Def def = null;
        for (Def candidate : ALL) {
            if (candidate.name.equals(name)) {
                def = candidate;
            }
        }
        String unit = def == null || def.unit == null ? "" : " " + def.unit;
        if (def == PROXIMITY) {
            return one.optBoolean("near") ? "near" : "far";
        }
        return value + unit;
    }

    /** The ids of every sensor that is on, for a summary line. */
    static List<String> enabledIds(JSONObject block) {
        List<String> on = new ArrayList<>();
        if (block == null) {
            return on;
        }
        java.util.Iterator<String> keys = block.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (block.optJSONObject(key) != null && block.optJSONObject(key).optBoolean("enabled")) {
                on.add(key);
            }
        }
        return on;
    }
}
