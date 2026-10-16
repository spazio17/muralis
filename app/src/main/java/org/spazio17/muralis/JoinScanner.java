/*
 * Copyright 2026 Muralis contributors
 * Fair Core License, FCL-1.0-ALv2: see LICENSE.md at the repository root.
 */
package org.spazio17.muralis;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
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
import android.util.Log;
import android.util.Size;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.ReaderException;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The camera of Add a panel: reads the QR of another panel's Join screen. Camera2,
 * as the Camera sensor uses it, one lens at a time; each frame's brightness plane is decoded with
 * ZXing and drawn, grey and upright, as the preview, so no preview surface has to be turned to
 * match the sensor. The Camera sensor lets go of the camera meanwhile
 * ({@link PanelCamera#heldByScanner}).
 */
final class JoinScanner {
    private static final String TAG = "MuralisScanner";
    /** Wide enough to read a QR on another screen at arm's length, small enough for old tablets. */
    private static final int WANTED_WIDTH = 1280;
    private static final int PREVIEW_LONG_SIDE = 640;

    /** What the scanner tells its screen, always on the main thread. */
    interface Listener {
        void preview(Bitmap frame);

        void found(String text);

        void failed(String reason);
    }

    private final Context context;
    private final Listener listener;
    private final int displayRotation;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
    private HandlerThread thread;
    private Handler handler;
    private CameraDevice device;
    private CameraCaptureSession session;
    private ImageReader reader;
    private volatile boolean running;
    /** Camera thread: an open asked of the system and not answered yet; see stop. */
    private boolean opening;
    private boolean front;
    private int sensorOrientation;

    /** {@code displayRotation} in degrees, as the screen is turned now. */
    JoinScanner(Context context, int displayRotation, Listener listener) {
        this.context = context.getApplicationContext();
        this.displayRotation = displayRotation;
        this.listener = listener;
        hints.put(DecodeHintType.POSSIBLE_FORMATS,
                Collections.singletonList(BarcodeFormat.QR_CODE));
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
    }

    /** The lenses, the back ones first: a phone scans with its back, a tablet its front. */
    static List<String> lenses(Context context) {
        List<String> back = new ArrayList<>();
        List<String> other = new ArrayList<>();
        CameraManager manager = context.getSystemService(CameraManager.class);
        if (manager == null) {
            return back;
        }
        try {
            for (String id : manager.getCameraIdList()) {
                Integer facing = manager.getCameraCharacteristics(id)
                        .get(CameraCharacteristics.LENS_FACING);
                (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK ? back : other)
                        .add(id);
            }
        } catch (CameraAccessException | RuntimeException unreadable) {
            Log.w(TAG, "The cameras could not be listed", unreadable);
        }
        back.addAll(other);
        return back;
    }

    /** Opens {@code id} and reads frames until {@link #stop}. */
    void start(String id) {
        CameraManager manager = context.getSystemService(CameraManager.class);
        if (manager == null) {
            listener.failed("This device has no camera.");
            return;
        }
        PanelCamera.heldByScanner = true;
        KioskService.refreshSensorsSoon(context);
        running = true;
        thread = new HandlerThread("MuralisScanner");
        thread.start();
        handler = new Handler(thread.getLooper());
        handler.post(() -> open(manager, id, 0));
    }

    /** How often a camera still held by the Camera sensor is asked again, a second apart. */
    private static final int OPEN_TRIES = 6;

    /**
     * Camera thread. The Camera sensor lets go a moment after it is told, so a busy camera is
     * asked again.
     */
    private void open(CameraManager manager, String id, int tried) {
        if (!running) {
            return;
        }
        if (context.checkSelfPermission(android.Manifest.permission.CAMERA)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            fail("Muralis needs the camera to read the code.");
            return;
        }
        try {
            CameraCharacteristics facts = manager.getCameraCharacteristics(id);
            Integer facing = facts.get(CameraCharacteristics.LENS_FACING);
            front = facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT;
            Integer orientation = facts.get(CameraCharacteristics.SENSOR_ORIENTATION);
            sensorOrientation = orientation == null ? 0 : orientation;
            Size size = pickSize(facts);
            if (reader != null) {
                reader.close();
            }
            reader = ImageReader.newInstance(size.getWidth(), size.getHeight(),
                    ImageFormat.YUV_420_888, 2);
            reader.setOnImageAvailableListener(this::frame, handler);
            opening = true;
            manager.openCamera(id, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice opened) {
                    opening = false;
                    if (!running) {
                        opened.close();
                        thread.quitSafely();
                        return;
                    }
                    device = opened;
                    startSession();
                }

                @Override
                public void onDisconnected(CameraDevice gone) {
                    opening = false;
                    gone.close();
                    if (!running) {
                        thread.quitSafely();
                        return;
                    }
                    fail("The camera was taken by another app.");
                }

                @Override
                public void onError(CameraDevice failed, int error) {
                    opening = false;
                    failed.close();
                    if (!running) {
                        thread.quitSafely();
                        return;
                    }
                    boolean busy = error == ERROR_CAMERA_IN_USE
                            || error == ERROR_MAX_CAMERAS_IN_USE;
                    if (busy && tried + 1 < OPEN_TRIES && running) {
                        handler.postDelayed(() -> open(manager, id, tried + 1), 1_000);
                        return;
                    }
                    Log.w(TAG, "The camera failed: " + error);
                    fail(busy ? "The camera is in use by another app."
                            : error == ERROR_CAMERA_DISABLED
                                    ? "The camera is switched off on this device."
                                    : "The camera could not be opened.");
                }
            }, handler);
        } catch (CameraAccessException refused) {
            opening = false;
            // A busy camera can be refused here as well as in onError; both are asked again.
            int reason = refused.getReason();
            if ((reason == CameraAccessException.CAMERA_IN_USE
                    || reason == CameraAccessException.MAX_CAMERAS_IN_USE)
                    && tried + 1 < OPEN_TRIES) {
                handler.postDelayed(() -> open(manager, id, tried + 1), 1_000);
                return;
            }
            Log.w(TAG, "The camera would not open", refused);
            fail(reason == CameraAccessException.CAMERA_DISABLED
                    ? "The camera is switched off on this device."
                    : "The camera could not be opened.");
        } catch (RuntimeException refused) {
            opening = false;
            Log.w(TAG, "The camera would not open", refused);
            fail("The camera could not be opened.");
        }
    }

    void stop() {
        running = false;
        if (handler != null) {
            handler.post(() -> {
                if (session != null) {
                    session.close();
                    session = null;
                }
                if (device != null) {
                    device.close();
                    device = null;
                }
                if (reader != null) {
                    reader.close();
                    reader = null;
                }
                // An open still unanswered keeps the thread: its answer closes the camera and
                // ends the thread then, rather than arriving at a thread already gone.
                if (!opening) {
                    thread.quitSafely();
                }
            });
        }
        PanelCamera.heldByScanner = false;
        KioskService.refreshSensorsSoon(context);
    }

    private void fail(String reason) {
        main.post(() -> {
            if (running) {
                running = false;
                listener.failed(reason);
            }
        });
    }

    private Size pickSize(CameraCharacteristics facts) {
        StreamConfigurationMap map =
                facts.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        Size[] sizes = map == null ? null : map.getOutputSizes(ImageFormat.YUV_420_888);
        if (sizes == null || sizes.length == 0) {
            return new Size(640, 480);
        }
        Size best = sizes[0];
        for (Size one : sizes) {
            if (Math.abs(one.getWidth() - WANTED_WIDTH)
                    < Math.abs(best.getWidth() - WANTED_WIDTH)) {
                best = one;
            }
        }
        return best;
    }

    private void startSession() {
        try {
            CaptureRequest.Builder request =
                    device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            request.addTarget(reader.getSurface());
            request.set(CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            device.createCaptureSession(Collections.singletonList(reader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession configured) {
                            if (!running || device == null) {
                                configured.close();
                                return;
                            }
                            session = configured;
                            try {
                                configured.setRepeatingRequest(request.build(), null, handler);
                            } catch (CameraAccessException | RuntimeException refused) {
                                fail("The camera stopped.");
                            }
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession refused) {
                            fail("The camera could not be started.");
                        }
                    }, handler);
        } catch (CameraAccessException | RuntimeException refused) {
            Log.w(TAG, "The camera session would not start", refused);
            fail("The camera could not be started.");
        }
    }

    /** One frame: its brightness decoded, then drawn as the preview. Camera thread. */
    private void frame(ImageReader from) {
        Image image = from.acquireLatestImage();
        if (image == null) {
            return;
        }
        int width = image.getWidth();
        int height = image.getHeight();
        Image.Plane plane = image.getPlanes()[0];
        int stride = plane.getRowStride();
        ByteBuffer buffer = plane.getBuffer();
        byte[] luminance = new byte[stride * height];
        buffer.get(luminance, 0, Math.min(buffer.remaining(), luminance.length));
        image.close();
        if (!running) {
            return;
        }
        String text = null;
        try {
            Result result = new QRCodeReader().decode(new BinaryBitmap(new HybridBinarizer(
                    new PlanarYUVLuminanceSource(luminance, stride, height, 0, 0, width, height,
                            false))), hints);
            text = result.getText();
        } catch (ReaderException notThere) {
            // No QR in this frame; the next one may have it.
        }
        Bitmap shown = previewOf(luminance, stride, width, height);
        String found = text;
        main.post(() -> {
            if (!running) {
                return;
            }
            listener.preview(shown);
            if (found != null) {
                listener.found(found);
            }
        });
    }

    /** The brightness plane in grey, smaller, turned upright, and mirrored for a front lens. */
    private Bitmap previewOf(byte[] luminance, int stride, int width, int height) {
        int step = Math.max(1, Math.max(width, height) / PREVIEW_LONG_SIDE);
        int w = width / step;
        int h = height / step;
        int[] pixels = new int[w * h];
        for (int y = 0; y < h; y++) {
            int row = y * step * stride;
            for (int x = 0; x < w; x++) {
                int grey = luminance[row + x * step] & 0xff;
                pixels[y * w + x] = Color.rgb(grey, grey, grey);
            }
        }
        Bitmap raw = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888);
        // As the Camera sensor turns its pictures upright: the sensor's mounting against the way
        // the screen is turned, the other way round for a front lens.
        int device = (360 - displayRotation) % 360;
        int degrees = (sensorOrientation + (front ? -device : device) + 360) % 360;
        Matrix turn = new Matrix();
        turn.postRotate(degrees);
        if (front) {
            turn.postScale(-1, 1);
        }
        return Bitmap.createBitmap(raw, 0, 0, w, h, turn, false);
    }
}
