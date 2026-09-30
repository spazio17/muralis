/*
 * Copyright 2026 Muralis contributors
 * All rights reserved. See LICENSE at the repository root.
 */
package org.spazio17.muralis;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * The microphone as a sound-level sensor: how loud the room is, 0 to 100, nothing recorded and
 * nothing kept. The loudness of each half second is measured in decibels of full scale and
 * placed between the room's quiet and loud, 0 to 100: Android's whole 16-bit range until the
 * person calibrates it, the room they heard after. The recorder runs only while the sensor is
 * on, and stops with it.
 *
 * <p>Heard through the voice-recognition source, not the plain microphone: Android requires
 * every device to capture it with noise reduction and automatic gain control off (CDD 5.4.2),
 * so a quiet room is not turned up until it reads like a conversation. The plain source let
 * the gain control lift an empty room to about 30 (Juri, 2026-09-28, the tablet).
 *
 * <p>Two readings, for two readers: the automations get each half second as it is, so a knock
 * or a shout reaches "louder than" at once; the reading on the rows and in the state document
 * is smoothed and moves only by whole steps, so it does not flicker every half second and a
 * broker is not sent a new number for every breath.
 */
final class Microphone implements Sensors.Reading {
    private static final String TAG = "MuralisMicrophone";
    /** The rate CDD 5.4.2 requires every device to capture the voice-recognition source at. */
    private static final int RATE = 44_100;
    private static final int WINDOW_MS = 500;
    /**
     * How much of each new half second the smoothed reading takes: a time constant of about
     * five seconds, the room's loudness rather than each word said in it. Faster settings
     * still swung between 35 and 54 in fifteen seconds on the tablet (2026-09-28).
     */
    private static final double SMOOTHING = 0.1;
    /**
     * The smallest move of the smoothed loudness that is reported, in dB: 3 dB, a change a
     * person hears without trying.
     */
    private static final double STEP_DB = 3;
    /** The half seconds a calibration step averages. */
    private static final long HEARD_MS = 3_000;
    /** The least gap, in dB, between a calibrated quiet room and its loud sound. */
    private static final double LEAST_RANGE_DB = 10;
    /** The bottom of 16-bit audio, where a silent input lands; the uncalibrated quiet. */
    static final double FLOOR_DB = -90;

    /**
     * One listener thread's own flag: a stop then a start (the switch flipped twice) must not
     * hand the old thread, still inside a blocking read, the new thread's "running".
     */
    private static final class Session {
        volatile boolean running = true;
        volatile AudioRecord recorder;
    }

    private Session session;
    private Thread thread;
    /** Not before this moment after a recorder that would not open, so a held microphone is
     * asked again calmly, not on every tick. */
    private volatile long retryAtMs;
    private static final long RETRY_MS = 15_000;
    private final Context context;
    /** The last half second, in dB of full scale; NaN while off. */
    private volatile double windowDb = Double.NaN;
    /** The smoothed loudness, in dB of full scale; NaN while off. */
    private volatile double smoothDb = Double.NaN;
    /** The smoothed loudness as last reported, in dB; NaN before the first. */
    private volatile double shownDb = Double.NaN;
    /** The half seconds of the last few seconds, each {elapsed ms, dB}, for a calibration. */
    private final java.util.ArrayDeque<double[]> recent = new java.util.ArrayDeque<>();
    private volatile double quietDb = FLOOR_DB;
    private volatile double loudDb = 0;
    private volatile boolean calibrated;

    Microphone(Context context) {
        this.context = context;
        loadCalibration();
    }

    /** The room the person calibrated, or Android's whole range. */
    private void loadCalibration() {
        try {
            double quiet = Double.parseDouble(
                    KioskConfig.sensorOption(context, "microphone_quiet_db", ""));
            double loud = Double.parseDouble(
                    KioskConfig.sensorOption(context, "microphone_loud_db", ""));
            if (loud - quiet >= LEAST_RANGE_DB) {
                quietDb = quiet;
                loudDb = loud;
                calibrated = true;
                return;
            }
        } catch (NumberFormatException none) {
            // Not calibrated.
        }
        quietDb = FLOOR_DB;
        loudDb = 0;
        calibrated = false;
    }

    /** 0 to 100 between the quiet room and the loud sound. */
    private int levelOf(double db) {
        return (int) Math.max(0, Math.min(100,
                Math.round((db - quietDb) / (loudDb - quietDb) * 100)));
    }

    synchronized void start() {
        if (session != null && session.running) {
            return;
        }
        if (android.os.SystemClock.elapsedRealtime() < retryAtMs) {
            return;
        }
        final Session mine = new Session();
        session = mine;
        thread = new Thread(() -> listen(mine), "MuralisMicrophone");
        thread.setDaemon(true);
        thread.start();
    }

    synchronized void stop() {
        if (session != null) {
            session.running = false;
            AudioRecord recorder = session.recorder;
            if (recorder != null) {
                try {
                    // Unblocks the read on the listener thread; the thread then lets the
                    // recorder go, so the next start does not fight it for the microphone.
                    recorder.stop();
                } catch (IllegalStateException notRecording) {
                    // Never started, or already stopped.
                }
            }
            session = null;
        }
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(1_000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            thread = null;
        }
        windowDb = Double.NaN;
        smoothDb = Double.NaN;
        shownDb = Double.NaN;
        synchronized (recent) {
            recent.clear();
        }
    }

    synchronized boolean running() {
        return session != null && session.running;
    }

    private void listen(Session mine) {
        int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        int window = RATE * WINDOW_MS / 1000;
        short[] buffer = new short[Math.max(window, min / 2)];
        AudioRecord recorder = open(mine, Math.max(min, buffer.length * 2));
        if (recorder == null) {
            mine.running = false;
            retryAtMs = android.os.SystemClock.elapsedRealtime() + RETRY_MS;
            return;
        }
        mine.recorder = recorder;
        try {
            recorder.startRecording();
            if (recorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                Log.w(TAG, "The microphone would not record");
                mine.running = false;
                return;
            }
            int empty = 0;
            while (mine.running) {
                int read = recorder.read(buffer, 0, window);
                if (read < 0) {
                    // An error, not silence: the recorder is broken, and the service's next
                    // tick starts a new one.
                    Log.w(TAG, "The microphone stopped answering: " + read);
                    mine.running = false;
                    break;
                }
                if (read == 0) {
                    if (++empty >= 10) {
                        // Five seconds of nothing from a recorder that says it records: the
                        // input went elsewhere, and a new recorder gets it back.
                        Log.w(TAG, "The microphone went quiet");
                        mine.running = false;
                        break;
                    }
                    Thread.sleep(WINDOW_MS);
                    continue;
                }
                empty = 0;
                double sum = 0;
                for (int index = 0; index < read; index++) {
                    sum += (double) buffer[index] * buffer[index];
                }
                double rms = Math.sqrt(sum / read);
                // 20·log10(rms / full scale) runs from about -90 dB (silence) to 0.
                double db = rms <= 1 ? FLOOR_DB : 20 * Math.log10(rms / 32768.0);
                if (mine.running) {
                    hear(db);
                }
            }
        } catch (InterruptedException ended) {
            // Stopped.
        } catch (RuntimeException failed) {
            Log.w(TAG, "The microphone stopped", failed);
            mine.running = false;
        } finally {
            try {
                recorder.stop();
            } catch (IllegalStateException notRecording) {
                // Never started.
            }
            recorder.release();
        }
    }

    /** One half second heard: the fast value, the smoothed one, and the step it is shown by. */
    private void hear(double db) {
        windowDb = db;
        double smooth = Double.isNaN(smoothDb) ? db : smoothDb + SMOOTHING * (db - smoothDb);
        smoothDb = smooth;
        double before = shownDb;
        int level = levelOf(smooth);
        // By steps of 3 dB, and to the ends exactly, so a room that falls silent reads 0.
        if (Double.isNaN(before) || Math.abs(smooth - before) >= STEP_DB
                || (level != levelOf(before) && (level == 0 || level == 100))) {
            shownDb = smooth;
        }
        long now = android.os.SystemClock.elapsedRealtime();
        synchronized (recent) {
            recent.addLast(new double[] {now, db});
            while (!recent.isEmpty() && now - recent.peekFirst()[0] > HEARD_MS) {
                recent.removeFirst();
            }
        }
    }

    /** The loudness of the last few seconds, averaged as sound power, or NaN if nothing. */
    private double heardDb() {
        long now = android.os.SystemClock.elapsedRealtime();
        double power = 0;
        int count = 0;
        synchronized (recent) {
            for (double[] one : recent) {
                if (now - one[0] <= HEARD_MS) {
                    power += Math.pow(10, one[1] / 10);
                    count++;
                }
            }
        }
        return count < 2 ? Double.NaN : 10 * Math.log10(power / count);
    }

    /**
     * One step of the calibration: "quiet" keeps the room at its quietest, "loud" keeps the
     * sound that should read 100 and stores the pair, "reset" goes back to Android's whole
     * range. Null when done, else why not. The same procedure as the proximity sensor's: the
     * person shows the panel both ends, nothing is guessed.
     */
    String calibrate(String step) {
        switch (step == null ? "" : step) {
            case "reset":
                KioskConfig.edit(context).removeSensorOption("microphone_quiet_db")
                        .removeSensorOption("microphone_loud_db")
                        .removeSensorOption("microphone_quiet_heard").apply();
                loadCalibration();
                return null;
            case "quiet": {
                double quiet = heardDb();
                if (Double.isNaN(quiet)) {
                    return "the microphone has not heard anything in the last seconds";
                }
                KioskConfig.edit(context)
                        .sensorOption("microphone_quiet_heard", Double.toString(quiet)).apply();
                return null;
            }
            case "loud": {
                double quiet;
                try {
                    quiet = Double.parseDouble(
                            KioskConfig.sensorOption(context, "microphone_quiet_heard", ""));
                } catch (NumberFormatException none) {
                    return "let it hear the quiet room first";
                }
                double loud = heardDb();
                if (Double.isNaN(loud)) {
                    return "the microphone has not heard anything in the last seconds";
                }
                KioskConfig.edit(context).removeSensorOption("microphone_quiet_heard").apply();
                if (loud - quiet < LEAST_RANGE_DB) {
                    return "the quiet room and the loud sound were too alike";
                }
                KioskConfig.edit(context)
                        .sensorOption("microphone_quiet_db", String.format(java.util.Locale.ROOT,
                                "%.1f", quiet))
                        .sensorOption("microphone_loud_db", String.format(java.util.Locale.ROOT,
                                "%.1f", loud))
                        .apply();
                loadCalibration();
                Log.i(TAG, String.format(java.util.Locale.ROOT,
                        "Microphone calibrated: quiet %.1f dB, loud %.1f dB", quiet, loud));
                return null;
            }
            default:
                return "the step must be quiet, loud or reset";
        }
    }

    /**
     * A recorder that initialised, or null. Asked up to six times over three seconds: the switch
     * flipped off and on hands the microphone to a new thread while the old one is still
     * letting go of it, and the second recorder fails to initialise until it has.
     */
    private AudioRecord open(Session mine, int bufferBytes) {
        for (int attempt = 0; attempt < 6 && mine.running; attempt++) {
            AudioRecord recorder;
            try {
                recorder = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferBytes);
            } catch (IllegalArgumentException | SecurityException refused) {
                Log.w(TAG, "The microphone would not open", refused);
                return null;
            }
            if (recorder.getState() == AudioRecord.STATE_INITIALIZED) {
                return recorder;
            }
            recorder.release();
            try {
                Thread.sleep(WINDOW_MS);
            } catch (InterruptedException ended) {
                return null;
            }
        }
        Log.w(TAG, "The microphone did not initialise");
        return null;
    }

    @Override
    public void fill(JSONObject one) throws JSONException {
        double db = shownDb;
        one.put("value", Double.isNaN(db) ? JSONObject.NULL : levelOf(db));
        one.put("calibrated", calibrated);
    }

    @Override
    public Automations.Sample sample() {
        double db = windowDb;
        return Double.isNaN(db) ? null : Automations.Sample.of(levelOf(db));
    }
}
