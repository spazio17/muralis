/*
 * Copyright 2026 Muralis contributors
 * All rights reserved. See LICENSE at the repository root.
 */
package org.spazio17.muralis;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
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
 * Bluetooth beacons, the companion app's Beacon Monitor and BLE Transmitter in one: the panel
 * listens for iBeacons around it and reports how many are in reach, each with its distance, and
 * can send a beacon of its own so other devices know they are near this panel.
 *
 * <p>A beacon is in reach while it has been heard within the "out of reach after" time; the
 * automations see the count. Beacons are named on the sensor's page, by their
 * {@code uuid:major:minor}, and an unnamed one is listed there by that id until it is.
 */
final class Beacons implements Sensors.Reading {
    private static final String TAG = "MuralisBeacons";
    private static final int APPLE = 0x004C;

    /** One beacon heard: its id, its last signal and when. */
    static final class Seen {
        final String id;
        int rssi;
        int txPower;
        long heardAtMs;

        Seen(String id) {
            this.id = id;
        }

        /** The companion app's estimate, in metres, from the calibrated power and the signal. */
        double distance() {
            if (txPower == 0 || rssi == 0) {
                return Double.NaN;
            }
            double ratio = rssi * 1.0 / txPower;
            double metres = ratio < 1.0 ? Math.pow(ratio, 10)
                    : 0.89976 * Math.pow(ratio, 7.7095) + 0.111;
            return Math.round(metres * 10) / 10.0;
        }
    }

    private final Context context;
    private final Map<String, Seen> seen = new LinkedHashMap<>();
    private BluetoothLeScanner scanner;
    private BluetoothLeAdvertiser advertiser;
    private boolean scanning;
    private boolean advertising;
    /** Not before this moment after a failed scan: Android throttles five starts in 30 s. */
    private long retryAtMs;
    private static final long RETRY_MS = 7_000;

    Beacons(Context context) {
        this.context = context;
    }

    private BluetoothAdapter adapter() {
        BluetoothManager manager = context.getSystemService(BluetoothManager.class);
        return manager == null ? null : manager.getAdapter();
    }

    /** Starts or stops the scan and the transmitter to match the stored settings. */
    synchronized void refresh(boolean sensorOn) {
        boolean transmit = sensorOn && KioskConfig.sensorOptionOn(context, "beacons_transmit", false);
        BluetoothAdapter adapter = adapter();
        if (adapter == null || !adapter.isEnabled()) {
            stopScan();
            stopAdvertising();
            return;
        }
        if (sensorOn && !scanning) {
            if (SystemClock.elapsedRealtime() >= retryAtMs) {
                startScan(adapter);
            }
        } else if (!sensorOn && scanning) {
            stopScan();
        }
        if (transmit && !advertising) {
            startAdvertising(adapter);
        } else if (!transmit && advertising) {
            stopAdvertising();
        }
    }

    synchronized void stop() {
        stopScan();
        stopAdvertising();
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
                one = new Seen(id);
                seen.put(id, one);
            }
            one.rssi = result.getRssi();
            one.txPower = apple[22];
            one.heardAtMs = SystemClock.elapsedRealtime();
        }
    }

    private long reachMs() {
        return KioskConfig.sensorOptionInt(context, "beacons_reach_s", 30) * 1000L;
    }

    /** The beacons heard within the reach time, newest signal first. */
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
                } else if (now - one.heardAtMs > 24 * 3_600_000L) {
                    each.remove();
                }
            }
        }
        return list;
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
        JSONObject attributes = new JSONObject();
        org.json.JSONArray list = new org.json.JSONArray();
        for (Seen beacon : reach) {
            JSONObject entry = new JSONObject();
            entry.put("id", beacon.id);
            String name = KioskConfig.sensorOption(context, "beacon_" + beacon.id, "");
            if (!name.isEmpty()) {
                entry.put("name", name);
            }
            entry.put("rssi", beacon.rssi);
            if (!Double.isNaN(beacon.distance())) {
                entry.put("distance_m", beacon.distance());
            }
            list.put(entry);
        }
        attributes.put("beacons", list);
        attributes.put("transmitting", advertising);
        one.put("attributes", attributes);
    }

    @Override
    public Automations.Sample sample() {
        return Automations.Sample.of(inReach().size());
    }

    private final AdvertiseCallback advertiseCallback = new AdvertiseCallback() {
        @Override
        public void onStartFailure(int errorCode) {
            Log.w(TAG, "Transmitting failed: " + errorCode);
            synchronized (Beacons.this) {
                advertising = false;
            }
        }
    };

    static UUID uuidOf(String text) {
        try {
            return UUID.fromString(text.trim());
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
    }

    private void startAdvertising(BluetoothAdapter adapter) {
        advertiser = adapter.getBluetoothLeAdvertiser();
        UUID uuid = uuidOf(KioskConfig.sensorOption(context, "beacons_uuid", ""));
        if (advertiser == null || uuid == null) {
            return;
        }
        int major = KioskConfig.sensorOptionInt(context, "beacons_major", 1) & 0xffff;
        int minor = KioskConfig.sensorOptionInt(context, "beacons_minor", 1) & 0xffff;
        ByteBuffer payload = ByteBuffer.allocate(23);
        payload.put((byte) 0x02).put((byte) 0x15);
        payload.putLong(uuid.getMostSignificantBits()).putLong(uuid.getLeastSignificantBits());
        payload.putShort((short) major).putShort((short) minor);
        // The calibrated signal at one metre, the figure every iBeacon carries; -59 dBm is the
        // usual value for a phone-class radio and what the companion app sends.
        payload.put((byte) -59);
        try {
            advertiser.startAdvertising(new AdvertiseSettings.Builder()
                            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
                            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                            .setConnectable(false).build(),
                    new AdvertiseData.Builder().addManufacturerData(APPLE, payload.array())
                            .build(), advertiseCallback);
            advertising = true;
            Log.i(TAG, "Transmitting " + uuid + ":" + major + ":" + minor);
        } catch (SecurityException | IllegalStateException refused) {
            Log.w(TAG, "Cannot transmit a beacon", refused);
        }
    }

    private void stopAdvertising() {
        if (advertising && advertiser != null) {
            try {
                advertiser.stopAdvertising(advertiseCallback);
            } catch (SecurityException | IllegalStateException gone) {
                // The adapter went away under us.
            }
            Log.i(TAG, "Stopped transmitting");
        }
        advertising = false;
    }

    boolean transmitting() {
        return advertising;
    }
}
