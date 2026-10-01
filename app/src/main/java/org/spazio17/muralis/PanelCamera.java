/*
 * Copyright 2026 Muralis contributors
 * All rights reserved. See LICENSE at the repository root.
 */
package org.spazio17.muralis;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.Size;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The panel's camera as an IP camera (Juri, 2026-09-27): a live MJPEG stream and a snapshot on
 * the web admin, behind its password; the picture's name and the time drawn into it as a
 * watermark; and motion detection, the one thing the automations and Home Assistant see of it.
 * The settings follow a camera firmware's: which lens, the size, the frame rate, mirror,
 * upside down, the watermark, the sensitivity. Over MQTT only what Frigate and Blue Iris
 * publish: the motion sensor, and a picture on motion when asked for.
 *
 * <p>Frames come from Camera2 as YUV, are turned into JPEG by the platform's own encoder, and
 * only pass through a bitmap when mirror, upside down, the watermark, a turn to stand upright
 * or a crop to the chosen orientation asks for it. Motion is
 * a coarse grid of the brightness plane compared with the last frame's: the share of cells
 * that changed beyond the sensitivity is motion. The camera is open only while the sensor is
 * on; nothing is recorded.
 */
final class PanelCamera implements Sensors.Reading {
    private static final String TAG = "MuralisCamera";
    static final List<String> SIZES = Collections.unmodifiableList(Arrays.asList(
            "320x240", "640x480", "1280x720", "1920x1080"));
    static final List<Integer> RATES = Collections.unmodifiableList(Arrays.asList(1, 2, 5, 10, 15));
    /**
     * The picture's orientation (Juri, 2026-09-28): upright as the panel is turned, or always
     * tall, or always wide. A camera's sensor is fixed in the panel, so a tall picture from a
     * panel lying wide is the middle of its wide one, and the other way round.
     */
    static final List<String> ORIENTATIONS = Collections.unmodifiableList(
            Arrays.asList("device", "portrait", "landscape"));
    private static final int GRID = 24;

    private final Context context;
    private HandlerThread thread;
    private Handler handler;
    private CameraDevice device;
    private CameraCaptureSession session;
    private ImageReader reader;
    private volatile boolean open;
    private volatile byte[] lastJpeg;
    private volatile long lastFrameAtMs;
    private long lastKeptAtMs;
    private int[] lastGrid;
    private volatile long movedAtMs = Long.MIN_VALUE;
    private volatile boolean motionNow;
    /** The open camera's facts for the picture's orientation, read when it is chosen. */
    private volatile int sensorOrientation;
    private volatile boolean frontFacing;
    private final java.util.concurrent.atomic.AtomicInteger viewers =
            new java.util.concurrent.atomic.AtomicInteger();
    private final Runnable onMotion;
    /**
     * Which opening the callbacks belong to: one that arrives after a stop, or from a previous
     * opening, is ignored, and a stale onOpened closes the device it was handed.
     */
    private int attempt;
    /** Not before this moment after a failure, so a camera that is gone is asked again calmly. */
    private long retryAtMs;
    /** When the camera was last asked to open: the silence a stall is judged from starts here. */
    private long openedAtMs;
    private static final long RETRY_MS = 15_000;
    private static final long STALL_MS = 20_000;

    PanelCamera(Context context, Runnable onMotion) {
        this.context = context;
        this.onMotion = onMotion;
    }

    static String defaultName(Context context) {
        return KioskConfig.deviceIdOf(context);
    }

    String name() {
        String name = KioskConfig.sensorOption(context, "camera_name", "").trim();
        return name.isEmpty() ? defaultName(context) : name;
    }

    /** Opens or closes the camera to match the stored switch; safe to call again. */
    synchronized void refresh(boolean wanted) {
        long now = SystemClock.elapsedRealtime();
        if (wanted && !open) {
            if (now >= retryAtMs) {
                start();
            }
        } else if (!wanted && open) {
            stop();
        } else if (wanted && open && settingsChanged()) {
            stop();
            start();
        } else if (wanted && open && now - Math.max(openedAtMs, lastFrameAtMs) > STALL_MS) {
            // Opened, and silent for far longer than any frame rate allows: the platform took
            // the pictures away without a word, so the camera is opened again.
            Log.w(TAG, "The camera stalled; opening it again");
            stop();
            start();
        }
    }

    private String appliedSettings = "";

    private String settingsKey() {
        return KioskConfig.sensorOption(context, "camera_lens", "front") + "|"
                + KioskConfig.sensorOption(context, "camera_size", "640x480") + "|"
                + KioskConfig.sensorOption(context, "camera_fps", "5");
    }

    private boolean settingsChanged() {
        return !settingsKey().equals(appliedSettings);
    }

    private void start() {
        CameraManager manager = context.getSystemService(CameraManager.class);
        if (manager == null) {
            return;
        }
        String wantedLens = KioskConfig.sensorOption(context, "camera_lens", "front");
        String id = null;
        Size size = null;
        try {
            for (String candidate : manager.getCameraIdList()) {
                CameraCharacteristics facts = manager.getCameraCharacteristics(candidate);
                Integer facing = facts.get(CameraCharacteristics.LENS_FACING);
                boolean front = facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT;
                if (front == wantedLens.equals("front") || id == null) {
                    id = candidate;
                    size = pickSize(facts);
                    Integer turned = facts.get(CameraCharacteristics.SENSOR_ORIENTATION);
                    sensorOrientation = turned == null ? 0 : turned;
                    frontFacing = front;
                    if (front == wantedLens.equals("front")) {
                        break;
                    }
                }
            }
        } catch (CameraAccessException | RuntimeException failed) {
            Log.w(TAG, "No camera could be listed", failed);
            return;
        }
        if (id == null || size == null) {
            Log.w(TAG, "No camera to open");
            retryAtMs = SystemClock.elapsedRealtime() + RETRY_MS;
            return;
        }
        if (context.checkSelfPermission(android.Manifest.permission.CAMERA)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "No camera permission");
            retryAtMs = SystemClock.elapsedRealtime() + RETRY_MS;
            return;
        }
        thread = new HandlerThread("MuralisCamera");
        thread.start();
        handler = new Handler(thread.getLooper());
        reader = ImageReader.newInstance(size.getWidth(), size.getHeight(),
                ImageFormat.YUV_420_888, 3);
        reader.setOnImageAvailableListener(this::frame, handler);
        appliedSettings = settingsKey();
        lastGrid = null;
        lastFrameAtMs = 0;
        openedAtMs = SystemClock.elapsedRealtime();
        final int mine = ++attempt;
        open = true;
        try {
            manager.openCamera(id, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice opened) {
                    synchronized (PanelCamera.this) {
                        if (mine != attempt || !open) {
                            opened.close();
                            return;
                        }
                        device = opened;
                        startSession();
                    }
                }

                @Override
                public void onDisconnected(CameraDevice gone) {
                    Log.w(TAG, "The camera was taken away");
                    failed(mine);
                }

                @Override
                public void onError(CameraDevice failed, int error) {
                    Log.w(TAG, "The camera failed: " + error);
                    failed(mine);
                }
            }, handler);
            Log.i(TAG, "Camera " + id + " opening at " + size);
        } catch (CameraAccessException | RuntimeException refused) {
            Log.w(TAG, "The camera would not open", refused);
            failed(mine);
        }
    }

    /** A failure of the opening {@code mine}: closed, and tried again after a pause. */
    private synchronized void failed(int mine) {
        if (mine != attempt) {
            return;
        }
        retryAtMs = SystemClock.elapsedRealtime() + RETRY_MS;
        stop();
    }

    private Size pickSize(CameraCharacteristics facts) {
        StreamConfigurationMap map = facts.get(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map == null) {
            return null;
        }
        String wanted = KioskConfig.sensorOption(context, "camera_size", "640x480");
        int width = 640;
        int height = 480;
        try {
            String[] parts = wanted.split("x");
            width = Integer.parseInt(parts[0]);
            height = Integer.parseInt(parts[1]);
        } catch (RuntimeException notASize) {
            // The default stands.
        }
        Size best = null;
        long bestGap = Long.MAX_VALUE;
        for (Size size : map.getOutputSizes(ImageFormat.YUV_420_888)) {
            long gap = Math.abs((long) size.getWidth() * size.getHeight() - (long) width * height);
            if (gap < bestGap) {
                bestGap = gap;
                best = size;
            }
        }
        return best;
    }

    private void startSession() {
        CameraDevice opened = device;
        if (opened == null || reader == null) {
            return;
        }
        final int mine = attempt;
        try {
            final CaptureRequest.Builder request =
                    opened.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            request.addTarget(reader.getSurface());
            opened.createCaptureSession(Collections.singletonList(reader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession ready) {
                            synchronized (PanelCamera.this) {
                                if (mine != attempt || !open) {
                                    return;
                                }
                                session = ready;
                                try {
                                    ready.setRepeatingRequest(request.build(), null, handler);
                                } catch (CameraAccessException | IllegalStateException failed) {
                                    Log.w(TAG, "The camera would not stream", failed);
                                    failed(mine);
                                }
                            }
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession failed) {
                            Log.w(TAG, "The camera session was refused");
                            failed(mine);
                        }
                    }, handler);
        } catch (CameraAccessException | IllegalStateException failed) {
            Log.w(TAG, "The camera session failed", failed);
            failed(mine);
        }
    }

    synchronized void stop() {
        open = false;
        if (session != null) {
            try {
                session.close();
            } catch (RuntimeException gone) {
                // Already closed.
            }
            session = null;
        }
        if (device != null) {
            device.close();
            device = null;
        }
        if (reader != null) {
            // Closed on the camera's own thread, behind any frame still being converted there:
            // closing it under a running frame() is undefined, and the image's close would
            // throw on that thread and take the process with it.
            final ImageReader gone = reader;
            reader = null;
            Handler owner = handler;
            if (owner != null && Looper.myLooper() != owner.getLooper()) {
                owner.post(gone::close);
            } else {
                gone.close();
            }
        }
        if (thread != null) {
            thread.quitSafely();
            thread = null;
            handler = null;
        }
        lastJpeg = null;
        motionNow = false;
        lastGrid = null;
    }

    boolean open() {
        return open;
    }

    private int fps() {
        return Math.max(1, Math.min(30, KioskConfig.sensorOptionInt(context, "camera_fps", 5)));
    }

    private void frame(ImageReader from) {
        Image image = from.acquireLatestImage();
        if (image == null) {
            return;
        }
        try {
            long now = SystemClock.elapsedRealtime();
            if (now - lastKeptAtMs < 1000 / fps()) {
                return;
            }
            lastKeptAtMs = now;
            byte[] nv21 = toNv21(image);
            int width = image.getWidth();
            int height = image.getHeight();
            detectMotion(nv21, width, height, now);
            lastJpeg = encode(nv21, width, height);
            lastFrameAtMs = now;
        } catch (RuntimeException failed) {
            Log.w(TAG, "A frame was dropped", failed);
        } finally {
            image.close();
        }
    }

    private static byte[] toNv21(Image image) {
        int width = image.getWidth();
        int height = image.getHeight();
        Image.Plane[] planes = image.getPlanes();
        byte[] out = new byte[width * height * 3 / 2];
        ByteBuffer y = planes[0].getBuffer();
        int yRow = planes[0].getRowStride();
        for (int row = 0; row < height; row++) {
            y.position(row * yRow);
            y.get(out, row * width, width);
        }
        ByteBuffer u = planes[1].getBuffer();
        ByteBuffer v = planes[2].getBuffer();
        int uvRow = planes[1].getRowStride();
        int uvPixel = planes[1].getPixelStride();
        int at = width * height;
        for (int row = 0; row < height / 2; row++) {
            for (int column = 0; column < width / 2; column++) {
                int index = row * uvRow + column * uvPixel;
                out[at++] = v.get(index);
                out[at++] = u.get(index);
            }
        }
        return out;
    }

    private void detectMotion(byte[] nv21, int width, int height, long now) {
        if (!KioskConfig.sensorOptionOn(context, "camera_motion", true)) {
            motionNow = false;
            lastGrid = null;
            return;
        }
        int[] grid = new int[GRID * GRID];
        int cellW = Math.max(1, width / GRID);
        int cellH = Math.max(1, height / GRID);
        for (int gy = 0; gy < GRID; gy++) {
            for (int gx = 0; gx < GRID; gx++) {
                int sum = 0;
                int count = 0;
                for (int yy = gy * cellH; yy < (gy + 1) * cellH && yy < height; yy += 4) {
                    int rowAt = yy * width;
                    for (int xx = gx * cellW; xx < (gx + 1) * cellW && xx < width; xx += 4) {
                        sum += nv21[rowAt + xx] & 0xff;
                        count++;
                    }
                }
                grid[gy * GRID + gx] = count == 0 ? 0 : sum / count;
            }
        }
        if (lastGrid != null) {
            int threshold;
            double share;
            switch (KioskConfig.sensorOption(context, "camera_sensitivity", "normal")) {
                case "high": threshold = 12; share = 0.01; break;
                case "low": threshold = 40; share = 0.08; break;
                default: threshold = 24; share = 0.03;
            }
            int changed = 0;
            for (int index = 0; index < grid.length; index++) {
                if (Math.abs(grid[index] - lastGrid[index]) > threshold) {
                    changed++;
                }
            }
            if (changed >= grid.length * share) {
                boolean fresh = !motionNow;
                movedAtMs = now;
                motionNow = true;
                if (fresh) {
                    Log.i(TAG, "Motion");
                    onMotion.run();
                }
            }
        }
        lastGrid = grid;
        long stillAfter = KioskConfig.sensorOptionInt(context, "camera_still_s", 30) * 1000L;
        if (motionNow && now - movedAtMs > stillAfter) {
            motionNow = false;
            Log.i(TAG, "Nothing moving");
        }
    }

    /**
     * How far to turn the sensor's picture, clockwise, for it to stand upright as the panel is
     * turned now: Android's rule for a camera picture (CameraCharacteristics.JPEG_ORIENTATION's
     * example), with the display's rotation standing for the device's, which is what a panel
     * locked to an orientation shows.
     */
    private int uprightDegrees() {
        android.hardware.display.DisplayManager displays =
                context.getSystemService(android.hardware.display.DisplayManager.class);
        android.view.Display display = displays == null ? null
                : displays.getDisplay(android.view.Display.DEFAULT_DISPLAY);
        int screen = display == null ? 0 : display.getRotation() * 90;
        // The display turns against the device: a device turned 90 degrees clockwise shows
        // ROTATION_270.
        int device = (360 - screen) % 360;
        if (frontFacing) {
            device = -device;
        }
        return (sensorOrientation + device + 360) % 360;
    }

    private byte[] encode(byte[] nv21, int width, int height) {
        boolean mirror = KioskConfig.sensorOptionOn(context, "camera_mirror", false);
        boolean flip = KioskConfig.sensorOptionOn(context, "camera_flip", false);
        boolean watermark = KioskConfig.sensorOptionOn(context, "camera_watermark", true);
        String shape = KioskConfig.sensorOption(context, "camera_orientation", "device");
        int degrees = uprightDegrees();
        boolean tall = degrees % 180 == 0 ? height > width : width > height;
        boolean crop = ("portrait".equals(shape) && !tall) || ("landscape".equals(shape) && tall);
        ByteArrayOutputStream out = new ByteArrayOutputStream(width * height / 4);
        new YuvImage(nv21, ImageFormat.NV21, width, height, null)
                .compressToJpeg(new Rect(0, 0, width, height), 80, out);
        if (!mirror && !flip && !watermark && degrees == 0 && !crop) {
            return out.toByteArray();
        }
        byte[] plain = out.toByteArray();
        Bitmap bitmap = BitmapFactory.decodeByteArray(plain, 0, plain.length);
        if (bitmap == null) {
            return plain;
        }
        if (mirror || flip || degrees != 0) {
            // Upright first, then mirror and upside down as a person sees the upright picture.
            Matrix matrix = new Matrix();
            matrix.postRotate(degrees);
            matrix.postScale(mirror ? -1 : 1, flip ? -1 : 1);
            Bitmap turned = Bitmap.createBitmap(bitmap, 0, 0, width, height, matrix, false);
            bitmap.recycle();
            bitmap = turned;
        }
        if (crop) {
            // The middle of the picture in the other shape, the same aspect turned: a wide
            // 1280 by 720 gives a tall 405 by 720.
            int w = bitmap.getWidth();
            int h = bitmap.getHeight();
            int cw = w > h ? Math.max(1, h * h / w) : w;
            int ch = w > h ? h : Math.max(1, w * w / h);
            Bitmap middle = Bitmap.createBitmap(bitmap, (w - cw) / 2, (h - ch) / 2, cw, ch);
            if (middle != bitmap) {
                bitmap.recycle();
            }
            bitmap = middle;
        }
        width = bitmap.getWidth();
        height = bitmap.getHeight();
        if (watermark) {
            Bitmap drawn = bitmap.isMutable() ? bitmap : bitmap.copy(Bitmap.Config.ARGB_8888, true);
            if (drawn != bitmap) {
                bitmap.recycle();
                bitmap = drawn;
            }
            Canvas canvas = new Canvas(bitmap);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            float textSize = Math.max(12, height / 24f);
            paint.setTextSize(textSize);
            paint.setColor(Color.WHITE);
            paint.setShadowLayer(textSize / 6, 0, 0, Color.BLACK);
            String line = name() + "  " + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss",
                    java.util.Locale.ROOT).format(new java.util.Date());
            canvas.drawText(line, textSize / 2, height - textSize / 2, paint);
        }
        ByteArrayOutputStream marked = new ByteArrayOutputStream(plain.length);
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, marked);
        bitmap.recycle();
        return marked.toByteArray();
    }

    /** The last frame as JPEG, or null while the camera has not delivered one. */
    byte[] snapshot() {
        return lastJpeg;
    }

    long lastFrameAtMs() {
        return lastFrameAtMs;
    }

    /** One more viewer; the count with it, for the cap. */
    int viewerJoined() {
        return viewers.incrementAndGet();
    }

    void viewerLeft() {
        viewers.updateAndGet(count -> Math.max(0, count - 1));
    }

    /** How many are watching the stream; the admin serves at most {@link #MAX_VIEWERS}. */
    int viewers() {
        return viewers.get();
    }

    /** Streams at once: each holds one of the admin's workers for as long as it is watched. */
    static final int MAX_VIEWERS = 2;

    @Override
    public void fill(JSONObject one) throws JSONException {
        one.put("value", motionNow);
        JSONObject attributes = new JSONObject();
        attributes.put("name", name());
        attributes.put("open", open);
        attributes.put("viewers", viewers.get());
        attributes.put("detecting", KioskConfig.sensorOptionOn(context, "camera_motion", true));
        // Whether pictures go to the broker: discovery announces the snapshot button only then,
        // and the web page's switch follows a flip made elsewhere.
        attributes.put("pictures", KioskConfig.sensorOptionOn(context, "camera_mqtt", false));
        one.put("attributes", attributes);
    }

    @Override
    public Automations.Sample sample() {
        return open ? Automations.Sample.of(motionNow) : null;
    }
}
