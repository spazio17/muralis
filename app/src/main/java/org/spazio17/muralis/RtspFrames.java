/*
 * Copyright 2026 Juri Calleri (spazio17) and the Muralis contributors
 * Licensed under the PolyForm Shield License 1.0.0: see LICENSE.md at the repository root.
 */
package org.spazio17.muralis;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Surface;

import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.rtsp.RtspMediaSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * An RTSP camera played on the panel and handed on as JPEG pictures (2026-10-25, the cameras of
 * 0.7.2): Media3 decodes the stream into a texture, each frame is drawn at most
 * {@link #MAX_SIDE} pixels a side and read back as a JPEG in memory. The camera view shows them
 * as an MJPEG stream ({@link CameraView}), so a grid, zoom and full screen work as for every
 * other camera, and the web admin takes one of them as a still picture.
 *
 * <p>One stream per address, shared: a cheap camera allows one to three sessions, so a tile and
 * a picture asked from the web admin read the same frames. The stream stays open a few seconds
 * after its last reader, so the view's probe and the stream after it are one session.
 *
 * <p>Nothing is written to the device: no cache, the pictures live in memory only. The address
 * carries the sign-in ({@link Cameras#rtspAddress}), so it is never logged, and Media3's own log
 * is switched off; only the camera's name is.
 */
@OptIn(markerClass = UnstableApi.class)
final class RtspFrames {
    private static final String TAG = "MuralisRtsp";
    /**
     * The longest side of a picture and how often a frame becomes one: a tile or a pop-up, and a
     * camera alone on the screen. Each picture is read back and encoded on the CPU, so these are
     * what an Android 8 panel can keep up on a wall: at twelve a second and 1280 pixels the
     * Huawei ran one core for a tile and two for full screen, 10 degrees warmer in 15 seconds
     * (2026-10-25).
     */
    static final int MAX_SIDE = 640;
    static final int MAX_SIDE_BIG = 960;
    private static final long FRAME_EVERY_MS = 160;
    private static final long FRAME_EVERY_MS_BIG = 100;
    private static final int QUALITY = 70;
    /** How long a stream stays open after its last reader leaves. */
    private static final long LINGER_MS = 5_000;
    private static final long TIMEOUT_MS = 10_000;

    private static final Map<String, RtspFrames> OPEN = new HashMap<>();

    /** One picture and its number; numbers only grow. */
    static final class Frame {
        final long number;
        final byte[] jpeg;

        Frame(long number, byte[] jpeg) {
            this.number = number;
            this.jpeg = jpeg;
        }
    }

    private final String address;
    private final String name;
    private final int maxSide;
    private final long frameEveryMs;
    /** Readers waiting in next(): a frame is encoded only when one is. */
    private int waiting;
    private final HandlerThread thread;
    private final Handler handler;
    private int readers;
    private boolean closed;
    private Frame latest;
    /** "denied" when the camera refused the sign-in, "none" when it gave no picture. */
    private String problem;

    private ExoPlayer player;
    private SurfaceTexture texture;
    private Surface surface;
    private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
    private EGLContext context = EGL14.EGL_NO_CONTEXT;
    private EGLConfig config;
    private EGLSurface pbuffer = EGL14.EGL_NO_SURFACE;
    private int program;
    private int textureId;
    private int width;
    private int height;
    private int videoWidth;
    private int videoHeight;
    private long lastFrameAt;
    private ByteBuffer pixels;
    private final ByteArrayOutputStream jpeg = new ByteArrayOutputStream(64 * 1024);
    private final FloatBuffer quad = ByteBuffer.allocateDirect(16 * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer();
    private Bitmap bitmap;
    private final float[] transform = new float[16];

    private RtspFrames(Context app, String address, String name, boolean big) {
        this.address = address;
        this.name = name;
        maxSide = big ? MAX_SIDE_BIG : MAX_SIDE;
        frameEveryMs = big ? FRAME_EVERY_MS_BIG : FRAME_EVERY_MS;
        thread = new HandlerThread("MuralisRtsp");
        thread.start();
        handler = new Handler(thread.getLooper());
        handler.post(() -> start(app));
    }

    /**
     * The stream of {@code address} (with its sign-in, see {@link Cameras#rtspAddress}), opened
     * when nobody reads it yet; each acquire is paired with one {@link #release}. {@code big} is
     * a camera alone on the screen, drawn larger and more often; the first reader's use counts.
     */
    static RtspFrames acquire(Context context, String address, String name, boolean big) {
        synchronized (OPEN) {
            RtspFrames frames = OPEN.get(address);
            if (frames == null) {
                frames = new RtspFrames(context.getApplicationContext(), address, name, big);
                OPEN.put(address, frames);
            }
            synchronized (frames) {
                frames.readers++;
            }
            return frames;
        }
    }

    /** One reader less; the stream closes a few seconds after the last, or at once after a failure. */
    void release() {
        boolean failed;
        synchronized (this) {
            readers--;
            failed = problem != null;
        }
        handler.postDelayed(this::closeIfUnread, failed ? 0 : LINGER_MS);
    }

    /**
     * The first picture newer than {@code after}, waiting up to {@code timeoutMs}; null when
     * none came or the stream failed ({@link #problem} says why).
     */
    synchronized Frame next(long after, long timeoutMs) {
        long until = System.currentTimeMillis() + timeoutMs;
        waiting++;
        try {
            while (problem == null && !closed && (latest == null || latest.number <= after)) {
                long left = until - System.currentTimeMillis();
                if (left <= 0) {
                    return null;
                }
                try {
                    wait(left);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
            return problem == null && latest != null && latest.number > after ? latest : null;
        } finally {
            waiting--;
        }
    }

    synchronized String problem() {
        return problem;
    }

    /** Wakes the readers waiting in {@link #next}, so a closed stream ends at once. */
    synchronized void wake() {
        notifyAll();
    }

    private void closeIfUnread() {
        synchronized (OPEN) {
            synchronized (this) {
                if (readers > 0 || closed) {
                    return;
                }
                closed = true;
                notifyAll();
            }
            if (OPEN.get(address) == this) {
                OPEN.remove(address);
            }
        }
        tearDown();
        thread.quitSafely();
    }

    private void fail(String why) {
        synchronized (OPEN) {
            // The next tile or picture opens the stream anew rather than reading a dead one.
            if (OPEN.get(address) == this) {
                OPEN.remove(address);
            }
        }
        synchronized (this) {
            problem = why;
            notifyAll();
        }
        handler.post(this::closeIfUnread);
    }

    private void start(Context app) {
        try {
            androidx.media3.common.util.Log.setLogLevel(
                    androidx.media3.common.util.Log.LOG_LEVEL_OFF);
            startGl();
            textureId = externalTexture();
            texture = new SurfaceTexture(textureId);
            texture.setOnFrameAvailableListener(available -> onFrame(), handler);
            surface = new Surface(texture);
            player = new ExoPlayer.Builder(app).setLooper(thread.getLooper()).build();
            // Pictures only: the camera's sound is not played and not decoded.
            player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build());
            player.setVideoSurface(surface);
            player.addListener(new Player.Listener() {
                @Override
                public void onVideoSizeChanged(VideoSize size) {
                    videoWidth = size.width;
                    videoHeight = size.height;
                }

                @Override
                public void onPlayerError(PlaybackException error) {
                    String why = refusedSignIn(error) ? "denied" : "none";
                    Log.i(TAG, "Camera " + name + (why.equals("denied")
                            ? " refused the sign-in" : " gave no RTSP picture: "
                                    + error.getErrorCodeName()));
                    fail(why);
                }
            });
            // Over the RTSP connection itself (TCP), as go2rtc does: one port, no packets lost
            // to the Wi-Fi or a firewall.
            player.setMediaSource(new RtspMediaSource.Factory().setForceUseRtpTcp(true)
                    .setTimeoutMs(TIMEOUT_MS).createMediaSource(MediaItem.fromUri(address)));
            player.prepare();
            player.play();
        } catch (RuntimeException failed) {
            Log.w(TAG, "Camera " + name + " could not be played: "
                    + failed.getClass().getSimpleName());
            fail("none");
        }
    }

    /** Whether the camera answered 401, which Media3 reports in its error's message chain. */
    private static boolean refusedSignIn(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            String message = cause.getMessage();
            // The camera's answer, "401 Unauthorized", or its code alone; not any number with 401
            // in it.
            if (message != null && (message.contains("Unauthorized")
                    || message.matches("(?s).*(^|[^0-9])401([^0-9]|$).*"))) {
                return true;
            }
        }
        return false;
    }

    private void onFrame() {
        if (closed || texture == null) {
            return;
        }
        // Taken every time, or the decoder stops handing frames over.
        texture.updateTexImage();
        long now = android.os.SystemClock.elapsedRealtime();
        boolean wanted;
        synchronized (this) {
            wanted = waiting > 0;
        }
        // Nobody waits during the linger, or while a reader is still writing the last one.
        if (!wanted || now - lastFrameAt < frameEveryMs || videoWidth <= 0
                || videoHeight <= 0) {
            return;
        }
        lastFrameAt = now;
        float scale = Math.min(1f, (float) maxSide / Math.max(videoWidth, videoHeight));
        int w = Math.max(2, Math.round(videoWidth * scale) & ~1);
        int h = Math.max(2, Math.round(videoHeight * scale) & ~1);
        if (w != width || h != height) {
            resize(w, h);
        }
        texture.getTransformMatrix(transform);
        draw();
        pixels.rewind();
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE,
                pixels);
        pixels.rewind();
        bitmap.copyPixelsFromBuffer(pixels);
        jpeg.reset();
        bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, jpeg);
        synchronized (this) {
            latest = new Frame(latest == null ? 1 : latest.number + 1, jpeg.toByteArray());
            notifyAll();
        }
    }

    private void startGl() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        int[] version = new int[2];
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
            throw new IllegalStateException("EGL did not start");
        }
        int[] attributes = {EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE};
        EGLConfig[] configs = new EGLConfig[1];
        int[] count = new int[1];
        if (!EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0)
                || count[0] == 0) {
            throw new IllegalStateException("No EGL configuration");
        }
        config = configs[0];
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
                new int[] {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
        resize(2, 2);
        program = program();
    }

    /** A drawing surface of the picture's size, current on this thread. */
    private void resize(int w, int h) {
        if (pbuffer != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT);
            EGL14.eglDestroySurface(display, pbuffer);
        }
        pbuffer = EGL14.eglCreatePbufferSurface(display, config,
                new int[] {EGL14.EGL_WIDTH, w, EGL14.EGL_HEIGHT, h, EGL14.EGL_NONE}, 0);
        EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context);
        width = w;
        height = h;
        pixels = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder());
        if (bitmap != null) {
            bitmap.recycle();
        }
        bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
    }

    private static int externalTexture() {
        int[] id = new int[1];
        GLES20.glGenTextures(1, id, 0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, id[0]);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER,
                GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER,
                GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S,
                GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T,
                GLES20.GL_CLAMP_TO_EDGE);
        return id[0];
    }

    private static final String VERTEX = "attribute vec4 position;attribute vec4 coordinate;"
            + "uniform mat4 transform;varying vec2 at;"
            + "void main(){gl_Position=position;at=(transform*coordinate).xy;}";
    private static final String FRAGMENT = "#extension GL_OES_EGL_image_external : require\n"
            + "precision mediump float;varying vec2 at;uniform samplerExternalOES frame;"
            + "void main(){gl_FragColor=texture2D(frame,at);}";
    /**
     * The whole surface, its texture upside down: glReadPixels reads the bottom row first and a
     * bitmap starts at the top, so the picture comes out the right way up.
     */
    private static final float[] QUAD = {-1, -1, 0, 1, 1, -1, 1, 1, -1, 1, 0, 0, 1, 1, 1, 0};

    private static int program() {
        int program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, shader(GLES20.GL_VERTEX_SHADER, VERTEX));
        GLES20.glAttachShader(program, shader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT));
        GLES20.glLinkProgram(program);
        int[] linked = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0);
        if (linked[0] == 0) {
            throw new IllegalStateException("The picture's program did not link");
        }
        return program;
    }

    private static int shader(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] compiled = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            throw new IllegalStateException("A shader of the picture did not compile");
        }
        return shader;
    }

    private void draw() {
        GLES20.glViewport(0, 0, width, height);
        GLES20.glUseProgram(program);
        quad.clear();
        quad.put(QUAD).position(0);
        int position = GLES20.glGetAttribLocation(program, "position");
        int coordinate = GLES20.glGetAttribLocation(program, "coordinate");
        GLES20.glEnableVertexAttribArray(position);
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 16, quad);
        quad.position(2);
        GLES20.glEnableVertexAttribArray(coordinate);
        GLES20.glVertexAttribPointer(coordinate, 2, GLES20.GL_FLOAT, false, 16, quad);
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "transform"), 1, false,
                transform, 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "frame"), 0);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    }

    private void tearDown() {
        if (player != null) {
            player.release();
            player = null;
        }
        if (surface != null) {
            surface.release();
            surface = null;
        }
        if (texture != null) {
            texture.release();
            texture = null;
        }
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT);
            if (pbuffer != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(display, pbuffer);
            }
            EGL14.eglDestroyContext(display, context);
            EGL14.eglTerminate(display);
            display = EGL14.EGL_NO_DISPLAY;
        }
        if (bitmap != null) {
            bitmap.recycle();
            bitmap = null;
        }
    }

    /**
     * The pictures as an MJPEG stream for one reader, as a camera sends one: each new picture
     * is one part. Closing it ends the stream and lets the camera go.
     */
    static final class Mjpeg extends InputStream {
        static final String TYPE = "multipart/x-mixed-replace; boundary=muralisframe";
        private final RtspFrames frames;
        private byte[] part = new byte[0];
        private int at;
        private long number;
        private boolean ended;

        Mjpeg(RtspFrames frames) {
            this.frames = frames;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] into, int offset, int length) throws IOException {
            if (at >= part.length) {
                Frame frame = ended ? null : frames.next(number, TIMEOUT_MS);
                if (frame == null) {
                    return -1;
                }
                number = frame.number;
                byte[] head = ("--muralisframe\r\nContent-Type: image/jpeg\r\nContent-Length: "
                        + frame.jpeg.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
                part = new byte[head.length + frame.jpeg.length + 2];
                System.arraycopy(head, 0, part, 0, head.length);
                System.arraycopy(frame.jpeg, 0, part, head.length, frame.jpeg.length);
                part[part.length - 2] = '\r';
                part[part.length - 1] = '\n';
                at = 0;
            }
            int count = Math.min(length, part.length - at);
            System.arraycopy(part, at, into, offset, count);
            at += count;
            return count;
        }

        @Override
        public void close() {
            synchronized (this) {
                if (ended) {
                    return;
                }
                ended = true;
            }
            frames.release();
            frames.wake();
        }
    }
}
