/*
 * Copyright 2026 Muralis contributors
 * All rights reserved. See LICENSE at the repository root.
 */
package org.spazio17.muralis;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Bluetooth beacons, the companion app's Beacon Monitor: the panel listens for iBeacons around
 * it and reports how many are in reach, each with its distance. It only listens and never sends
 * a beacon of its own (Juri, 2026-10-01: "Only listen for beacons, never send a beacon").
 *
 * <p>A beacon is in reach while it has been heard within the "out of reach after" time; the
 * automations see the count. Beacons are named on the sensor's page, by their
 * {@code uuid:major:minor}, and an unnamed one is listed there by that id until it is.
 */
final class Beacons implements Sensors.Reading {
    private static final String TAG = "MuralisBeacons";
    private static final int APPLE = 0x004C;

    /** One beacon heard: its id, its signal over the last seconds, what it announces, when. */
    static final class Seen {
        final String id;
        int txPower;
        long heardAtMs;
        /** {elapsed ms, dBm} per packet heard within BeaconDistance.WINDOW_MS. */
        private final java.util.ArrayDeque<long[]> readings = new java.util.ArrayDeque<>();

        Seen(String id) {
            this.id = id;
        }

        private void heard(int rssi, long nowMs) {
            readings.addLast(new long[] {nowMs, rssi});
            while (!readings.isEmpty()
                    && nowMs - readings.peekFirst()[0] > BeaconDistance.WINDOW_MS) {
                readings.removeFirst();
            }
        }

        /** The signal averaged over the window, the outliers left out; NaN for none. */
        double rssi() {
            int[] values = new int[readings.size()];
            int index = 0;
            for (long[] one : readings) {
                values[index++] = (int) one[1];
            }
            return BeaconDistance.average(values);
        }

        /** How many packets the average holds. */
        int readings() {
            return readings.size();
        }
    }

    /**
     * What this panel heard of each calibrated beacon, by id: {at one metre, at three}, NaN
     * for a step not done; see BeaconDistance. Kept as one JSON setting of this panel alone.
     */
    private final Map<String, double[]> calibrations = new LinkedHashMap<>();

    /** Where the calibrations are kept: a setting of this panel alone (SensorSettings). */
    static final String CALIBRATION_KEY = "beacons_calibration";

    private void loadCalibrations() {
        synchronized (calibrations) {
            calibrations.clear();
            try {
                JSONObject stored = new JSONObject(
                        KioskConfig.sensorOption(context, CALIBRATION_KEY, "{}"));
                java.util.Iterator<String> ids = stored.keys();
                while (ids.hasNext()) {
                    String id = ids.next();
                    JSONObject one = stored.optJSONObject(id);
                    if (one != null) {
                        calibrations.put(id, new double[] {
                            one.optDouble("near", Double.NaN), one.optDouble("far", Double.NaN)});
                    }
                }
            } catch (JSONException unreadable) {
                Log.w(TAG, "Beacon calibrations unreadable, left out", unreadable);
            }
        }
    }

    private void storeCalibrations() {
        JSONObject stored = new JSONObject();
        synchronized (calibrations) {
            try {
                for (Map.Entry<String, double[]> entry : calibrations.entrySet()) {
                    JSONObject one = new JSONObject();
                    if (!Double.isNaN(entry.getValue()[0])) {
                        one.put("near", Math.round(entry.getValue()[0] * 10) / 10.0);
                    }
                    if (!Double.isNaN(entry.getValue()[1])) {
                        one.put("far", Math.round(entry.getValue()[1] * 10) / 10.0);
                    }
                    stored.put(entry.getKey(), one);
                }
            } catch (JSONException impossible) {
                throw new IllegalStateException(impossible);
            }
            if (calibrations.isEmpty()) {
                KioskConfig.edit(context).removeSensorOption(CALIBRATION_KEY).apply();
            } else {
                KioskConfig.edit(context).sensorOption(CALIBRATION_KEY, stored.toString())
                        .apply();
            }
        }
    }

    /** What this panel heard of a beacon at one and three metres, NaN for a step not done. */
    private double[] calibration(String id) {
        synchronized (calibrations) {
            double[] one = calibrations.get(id);
            return one == null ? new double[] {Double.NaN, Double.NaN} : one.clone();
        }
    }

    /** Whether any beacon was calibrated on this panel. */
    boolean calibrated() {
        synchronized (calibrations) {
            return !calibrations.isEmpty();
        }
    }

    /** The calibrated beacons with their steps, "Dog at 1 m and 3 m"; empty for none. */
    String calibratedSummary(NamedList names) {
        List<String> parts = new ArrayList<>();
        synchronized (calibrations) {
            for (Map.Entry<String, double[]> entry : calibrations.entrySet()) {
                String name = names.name(entry.getKey());
                parts.add((name.isEmpty() ? entry.getKey() : name) + " at "
                        + steps(entry.getValue()));
            }
        }
        return String.join(", ", parts);
    }

    private static String steps(double[] calibration) {
        boolean near = !Double.isNaN(calibration[0]);
        boolean far = !Double.isNaN(calibration[1]);
        return near && far ? "1 m and 3 m" : near ? "1 m" : "3 m";
    }

    /** The beacon's distance in metres with this panel's calibration of it; NaN when unknown. */
    double distance(Seen one) {
        double[] calibration = calibration(one.id);
        synchronized (seen) {
            return BeaconDistance.metres(one.rssi(), one.txPower, calibration[0], calibration[1]);
        }
    }

    /** The beacon's signal, averaged, in whole dBm; 0 when unknown. */
    int signal(Seen one) {
        synchronized (seen) {
            double rssi = one.rssi();
            return Double.isNaN(rssi) ? 0 : (int) Math.round(rssi);
        }
    }

    /**
     * A beacon's state in words, the one wording both surfaces show: "in reach, 1.2 m,
     * -63 dBm" or "out of reach"; the signal beside the distance, so the estimate can be
     * checked against the raw number (Juri, 2026-09-30).
     */
    String describe(Seen one, boolean inReach) {
        if (!inReach) {
            return "out of reach";
        }
        double metres = distance(one);
        int dbm = signal(one);
        return "in reach" + (Double.isNaN(metres) ? "" : ", " + metres + " m")
                + (dbm == 0 ? "" : ", " + dbm + " dBm");
    }

    /** The fewest packets in the window a calibration takes, so one packet cannot set it. */
    static final int CALIBRATION_READINGS = 5;

    /** The id of the beacon the last calibration step was kept for, for the panel to name. */
    private volatile String lastCalibrated;

    String lastCalibrated() {
        return lastCalibrated;
    }

    /**
     * One step of a beacon's calibration on this panel: "near", the beacon held at one metre;
     * "far", at three; "reset", the calibration forgotten. {@code beaconId} names the beacon,
     * or null for the strongest in reach (for reset, every beacon). The reason when refused.
     */
    String calibrate(String step, String beaconId) {
        String wanted = beaconId == null || beaconId.trim().isEmpty() ? null : beaconId.trim();
        String kind = step == null ? "" : step;
        if (kind.equals("reset")) {
            synchronized (calibrations) {
                if (wanted == null) {
                    calibrations.clear();
                } else if (calibrations.remove(wanted) == null) {
                    return "that beacon is not calibrated on this panel";
                }
            }
            storeCalibrations();
            Log.i(TAG, "Beacon calibration reset: " + (wanted == null ? "every beacon" : wanted));
            return null;
        }
        if (!kind.equals("near") && !kind.equals("far")) {
            return "the step must be near, far or reset";
        }
        Seen chosen = null;
        double heard = Double.NaN;
        synchronized (seen) {
            for (Seen one : inReach()) {
                double rssi = one.rssi();
                if (Double.isNaN(rssi)) {
                    continue;
                }
                if (wanted != null ? one.id.equals(wanted)
                        : chosen == null || rssi > heard) {
                    chosen = one;
                    heard = rssi;
                }
            }
            if (chosen == null) {
                return wanted == null ? "no beacon is in reach" : "that beacon is not in reach";
            }
            if (chosen.readings() < CALIBRATION_READINGS) {
                return "the beacon has not been heard long enough; hold it there a few seconds "
                        + "more";
            }
        }
        double[] calibration = calibration(chosen.id);
        if (kind.equals("near")) {
            calibration[0] = heard;
        } else {
            String problem = BeaconDistance.farProblem(
                    BeaconDistance.atOneMetre(chosen.txPower, calibration[0]), heard);
            if (problem != null) {
                return problem;
            }
            calibration[1] = heard;
        }
        synchronized (calibrations) {
            calibrations.put(chosen.id, calibration);
        }
        storeCalibrations();
        lastCalibrated = chosen.id;
        Log.i(TAG, String.format(java.util.Locale.ROOT,
                "Beacon %s calibrated at %s: heard %.1f dBm, announcing %d", chosen.id,
                kind.equals("near") ? "1 m" : "3 m", heard, chosen.txPower));
        return null;
    }

    private final Context context;
    /** Heard beacons by id, in the order first heard; see {@link #MAX_SEEN}. */
    private final Map<String, Seen> seen = new LinkedHashMap<>();
    /**
     * How many beacons are kept: anyone in radio range can advertise a new id every second
     * (a phone app, an ESP32), and an unbounded list grew the page and the heap with nobody
     * signed in (review, 2026-10-01). The one heard longest ago goes first.
     */
    static final int MAX_SEEN = 100;
    /** A beacon out of reach this long is forgotten, name box and all. */
    private static final long FORGET_MS = 10 * 60_000L;
    private BluetoothLeScanner scanner;
    private boolean scanning;
    /** When the scan began, so the rules hear nothing before one reach time has passed. */
    private long scanStartedAtMs;
    /** Not before this moment after a failed scan: Android throttles five starts in 30 s. */
    private long retryAtMs;
    private static final long RETRY_MS = 7_000;

    Beacons(Context context) {
        this.context = context;
        loadCalibrations();
    }

    private BluetoothAdapter adapter() {
        BluetoothManager manager = context.getSystemService(BluetoothManager.class);
        return manager == null ? null : manager.getAdapter();
    }

    /** Starts or stops the scan to match the sensor's switch. */
    synchronized void refresh(boolean sensorOn) {
        BluetoothAdapter adapter = adapter();
        if (adapter == null || !adapter.isEnabled() || locationOff()) {
            // Location switched off in Android stops a scan that is not declared "never for
            // location" from hearing anything, so it is stopped and the row says why.
            stopScan();
            return;
        }
        if (sensorOn && !scanning) {
            if (SystemClock.elapsedRealtime() >= retryAtMs) {
                startScan(adapter);
            }
        } else if (!sensorOn && scanning) {
            stopScan();
        }
    }

    synchronized void stop() {
        stopScan();
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            heard(result);
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            for (ScanResult result : results) {
                heard(result);
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            Log.w(TAG, "Scan failed: " + errorCode);
            synchronized (Beacons.this) {
                scanning = false;
                retryAtMs = SystemClock.elapsedRealtime() + RETRY_MS;
            }
        }
    };

    private void startScan(BluetoothAdapter adapter) {
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            return;
        }
        // Filtered to iBeacon frames: an unfiltered scan is stopped by the system while the
        // screen is off (Android 8.1 on), and a wall panel sleeps most of its life.
        List<ScanFilter> filters = new ArrayList<>();
        filters.add(new ScanFilter.Builder().setManufacturerData(APPLE,
                new byte[] {0x02, 0x15}, new byte[] {(byte) 0xff, (byte) 0xff}).build());
        try {
            scanner.startScan(filters, new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build(), scanCallback);
            scanning = true;
            scanStartedAtMs = SystemClock.elapsedRealtime();
            retryAtMs = SystemClock.elapsedRealtime() + RETRY_MS;
            Log.i(TAG, "Listening for beacons");
        } catch (SecurityException | IllegalStateException refused) {
            Log.w(TAG, "Cannot scan for beacons", refused);
            retryAtMs = SystemClock.elapsedRealtime() + RETRY_MS;
        }
    }

    private void stopScan() {
        if (scanning && scanner != null) {
            try {
                scanner.stopScan(scanCallback);
            } catch (SecurityException | IllegalStateException gone) {
                // The adapter went away under us; nothing to stop.
            }
            Log.i(TAG, "Stopped listening for beacons");
        }
        scanning = false;
        synchronized (seen) {
            seen.clear();
        }
    }

    private void heard(ScanResult result) {
        if (result.getScanRecord() == null) {
            return;
        }
        byte[] apple = result.getScanRecord().getManufacturerSpecificData(APPLE);
        if (apple == null || apple.length < 23 || apple[0] != 0x02 || apple[1] != 0x15) {
            return;
        }
        ByteBuffer buffer = ByteBuffer.wrap(apple, 2, 20);
        UUID uuid = new UUID(buffer.getLong(), buffer.getLong());
        int major = ((apple[18] & 0xff) << 8) | (apple[19] & 0xff);
        int minor = ((apple[20] & 0xff) << 8) | (apple[21] & 0xff);
        String id = uuid + ":" + major + ":" + minor;
        synchronized (seen) {
            Seen one = seen.get(id);
            if (one == null) {
                if (seen.size() >= MAX_SEEN) {
                    Seen oldest = null;
                    for (Seen each : seen.values()) {
                        if (oldest == null || each.heardAtMs < oldest.heardAtMs) {
                            oldest = each;
                        }
                    }
                    if (oldest != null) {
                        seen.remove(oldest.id);
                    }
                }
                one = new Seen(id);
                seen.put(id, one);
            }
            long now = SystemClock.elapsedRealtime();
            one.heard(result.getRssi(), now);
            one.txPower = apple[22];
            one.heardAtMs = now;
        }
    }

    private long reachMs() {
        return KioskConfig.sensorOptionInt(context, "beacons_reach_s", 30) * 1000L;
    }

    /** The beacons heard within the reach time, in the order first heard. */
    List<Seen> inReach() {
        long now = SystemClock.elapsedRealtime();
        long reach = reachMs();
        List<Seen> list = new ArrayList<>();
        synchronized (seen) {
            java.util.Iterator<Seen> each = seen.values().iterator();
            while (each.hasNext()) {
                Seen one = each.next();
                if (now - one.heardAtMs <= reach) {
                    list.add(one);
                } else if (now - one.heardAtMs > reach + FORGET_MS) {
                    each.remove();
                }
            }
        }
        return list;
    }

    /** Whether the radio is off in Android: the scan cannot run, and the row says so. */
    boolean radioOff() {
        BluetoothAdapter adapter = adapter();
        return adapter == null || !adapter.isEnabled();
    }

    /**
     * Whether Location is switched off in Android: since the scan counts as location use (the
     * manifest no longer says "never for location"), it then hears nothing (review,
     * 2026-10-01).
     */
    boolean locationOff() {
        android.location.LocationManager location =
                context.getSystemService(android.location.LocationManager.class);
        if (location == null) {
            return false;
        }
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            return !location.isLocationEnabled();
        }
        try {
            return android.provider.Settings.Secure.getInt(context.getContentResolver(),
                    android.provider.Settings.Secure.LOCATION_MODE)
                    == android.provider.Settings.Secure.LOCATION_MODE_OFF;
        } catch (android.provider.Settings.SettingNotFoundException unknown) {
            return false;
        }
    }

    /** Every beacon heard since the scan began, for the page where one is named. */
    List<Seen> everHeard() {
        synchronized (seen) {
            return new ArrayList<>(seen.values());
        }
    }

    @Override
    public void fill(JSONObject one) throws JSONException {
        List<Seen> reach = inReach();
        one.put("value", reach.size());
        one.put("calibrated", calibrated());
        if (radioOff()) {
            one.put("radio_off", true);
        } else if (locationOff()) {
            one.put("location_off", true);
        }
        JSONObject attributes = new JSONObject();
        org.json.JSONArray list = new org.json.JSONArray();
        NamedList names = KioskConfig.beaconNames(context);
        for (Seen beacon : reach) {
            JSONObject entry = new JSONObject();
            entry.put("id", beacon.id);
            String name = names.name(beacon.id);
            if (!name.isEmpty()) {
                entry.put("name", name);
            }
            entry.put("rssi", signal(beacon));
            double metres = distance(beacon);
            if (!Double.isNaN(metres)) {
                entry.put("distance_m", metres);
            }
            double[] calibration = calibration(beacon.id);
            org.json.JSONArray steps = new org.json.JSONArray();
            if (!Double.isNaN(calibration[0])) {
                steps.put(1);
            }
            if (!Double.isNaN(calibration[1])) {
                steps.put(3);
            }
            if (steps.length() > 0) {
                entry.put("calibrated_m", steps);
            }
            list.put(entry);
        }
        attributes.put("beacons", list);
        one.put("attributes", attributes);
    }

    @Override
    public Automations.Sample seedSample() {
        synchronized (this) {
            return scanning ? Automations.Sample.of(0) : null;
        }
    }

    @Override
    public Automations.Sample sample() {
        // Nothing to say while not listening: Bluetooth switched off in Android used to read
        // as "the last beacon went out of reach" and fire those rules (review, 2026-10-01).
        // Nor for one reach time after the scan starts: an empty list before the first result
        // read as "out of reach" the moment Bluetooth came back on.
        synchronized (this) {
            if (!scanning || SystemClock.elapsedRealtime() - scanStartedAtMs < reachMs()) {
                return null;
            }
        }
        return Automations.Sample.of(inReach().size());
    }
}
