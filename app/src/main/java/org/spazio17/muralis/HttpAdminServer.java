/*
 * Copyright 2026 Juri Calleri (spazio17) and the Muralis contributors
 * Licensed under the PolyForm Shield License 1.0.0: see LICENSE.md at the repository root.
 */
package org.spazio17.muralis;

import android.content.Context;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * A local HTTP control/admin surface mirroring the authenticated MQTT command set, the same way
 * Tasmota or WLED let HTTP and MQTT drive identical commands. HTTPS with the panel's own
 * certificate (see AdminCertificate), intended for a
 * trusted LAN exactly like the plain-TCP MQTT transport. Fails closed: no
 * socket is bound unless an admin password has been configured locally on the device first.
 */
final class HttpAdminServer {
    private static final String TAG = "MuralisHttp";
    static final int MIN_ADMIN_PASSWORD_LENGTH = 8;
    /**
     * The sections that have a box of their own on the settings page, and can therefore show
     * their own message. Anything else falls back to the banner above the grid.
     */
    private static final java.util.Set<String> BOXED_SECTIONS = Collections.unmodifiableSet(
            new java.util.HashSet<>(java.util.Arrays.asList(
                    "dashboard", "mqtt", "webadmin", "fleet", "sequences", "panel",
                    "certificate", "adopt")));
    private static final int SOCKET_TIMEOUT_MS = 10_000;

    /**
     * The box a notice belongs in: the certificate block's answers stand inside the web admin's
     * box, which opens with them (review, 2026-10-05: a refusal sat in a closed box).
     */
    private static String boxOf(String noticeSection) {
        return "certificate".equals(noticeSection) ? "webadmin"
                : "adopt".equals(noticeSection) ? "fleet" : noticeSection;
    }
    /**
     * From the accept to the request line, TLS handshake included. Two seconds cut off one
     * request in seven from a laptop to the tablet over a Wi-Fi link that resent 7% of its
     * segments (2026-09-30): a lost segment waits a second or more for its resend. Eight, like
     * {@link #REQUEST_DEADLINE_MS}, still frees a worker held by a silent client quickly.
     */
    private static final int HANDSHAKE_TIMEOUT_MS = 8_000;
    /** Long enough for a socket close to land, short enough that a reload never looks like a hang. */
    private static final int SHUTDOWN_WAIT_MS = 1_000;
    private static final int MAX_REQUEST_LINE_LENGTH = 4_096;
    private static final int MAX_HEADER_LINES = 40;
    private static final int MAX_BODY_BYTES = 16_384;
    /**
     * The one request that carries megabytes: the picture upload for Ambient's
     * playlists. Its body budget and deadline are its own; every other request keeps the 16 KB
     * and eight seconds that suit commands and settings, but for a playlist's order, which gets
     * {@link #MAX_ORDER_BYTES} in the same eight seconds. A browser sends every chosen file in
     * one request, so the whole-request cap is the working limit a person sees on the page.
     */
    private static final int MAX_UPLOAD_BYTES = 24 * 1024 * 1024;
    /**
     * A playlist's whole order, which is every address in it: up to {@link
     * PlaylistDocument#MAX_PICTURES} of them, form-encoded, at roughly a hundred bytes each for
     * the addresses panels really hold and room for longer file names. 16 KB held about 170, so a
     * big playlist could not be reordered from the page at all. Eight seconds still suffice.
     */
    private static final int MAX_ORDER_BYTES = 256 * 1024;
    /**
     * The three documents of the fleet shape (/api/automations, /api/sensors/settings,
     * /api/beacons/names): 64 rules with 500-character arguments in a script of three bytes a
     * character, or full of escaped quotes, come to about 130 KB, which the 16 KB budget
     * refused, so "post back the shape GET gives" failed (review, 2026-10-01). A rule may now
     * hold several Whens and up to 20 actions, and the list is bounded by
     * Automations.MAX_STORED_CHARS instead, 200,000 characters that may each take three bytes
     * as UTF-8: 640 KB holds that. The editor's save carries a rule's whole baseline, so it has
     * the same budget.
     */
    private static final int MAX_DOCUMENT_BYTES = 640 * 1024;
    /**
     * A certificate upload: a full chain of RSA 4096 certificates and its key come to about
     * 12 KB, so the 16 KB budget would refuse a long chain with its multipart wrapping.
     */
    private static final int MAX_CERTIFICATE_BYTES = 64 * 1024;
    private static final int MAX_PICTURE_BYTES = 12 * 1024 * 1024;
    private static final int UPLOAD_DEADLINE_MS = 120_000;
    /**
     * The two routes that wait on another panel (its picture, Identify) get the member call's
     * own deadline plus the capture's on top of the request's, so a panel that does not answer
     * is reported as such in the reply, rather than this connection being closed first.
     */
    private static final int FLEET_PROXY_DEADLINE_MS = 20_000;
    /**
     * Add a panel by its code looks for the panel for up to thirty seconds, then
     * joins it with calls of their own: the answer must still find the browser connected.
     */
    private static final int ADOPT_DEADLINE_MS = (int) FleetServer.CODE_SEARCH_MS + 20_000;
    /**
     * Saving an integration can Try its service first, which waits on that service: the answer
     * must still find the browser connected, with the reason when the service is slow or silent.
     */
    private static final int INTEGRATION_DEADLINE_MS = (int) IntegrationRunner.TRY_DEADLINE_MS
            + 5_000;
    /** Browser preconnects need headroom; twelve from one address leave four workers free. */
    private static final int WORKER_THREADS = 16;
    private static final int PER_HOST_CONNECTIONS = 12;
    /**
     * How many accepted connections may wait for a worker before the next one is refused.
     *
     * <p>This number is the whole fix for an unauthenticated denial of service, so it is worth
     * saying why it exists rather than only what it is. {@code Executors.newFixedThreadPool} pairs
     * its threads with an <b>unbounded</b> queue, so every accepted socket was queued no matter how
     * many were already waiting, and each one holds a file descriptor. Anyone on the LAN could open
     * connections until the process ran out of descriptors, at which point {@code accept()} fails
     * permanently, the web admin is gone, and every other socket this app needs, MQTT included,
     * fails to open too. No credentials required, because this happens before a byte is read.
     *
     * <p>With a bounded queue the server holds at most {@code WORKER_THREADS + ACCEPT_QUEUE_DEPTH}
     * descriptors and refuses the rest immediately, which the {@code RejectedExecutionException}
     * branch in {@link #acceptLoop()} already handled correctly and could never previously reach.
     * Refusing a connection during a flood is the right trade: the alternative is a panel that
     * needs a power cycle.
     */
    private static final int ACCEPT_QUEUE_DEPTH = 8;
    /**
     * Longest a single request may spend being read, headers and body together.
     *
     * <p>Separate from {@link #SOCKET_TIMEOUT_MS}, which is a per-read timeout and cannot catch the
     * attack it looks like it should. A client that sends one byte every nine seconds resets that
     * timeout on every read and holds its worker for as long as it likes; four such clients own all
     * four workers and the admin server is unreachable indefinitely. Slowloris, and it costs the
     * attacker nothing. A deadline measured across the whole request cannot be reset by dribbling.
     *
     * <p>Eight seconds is far longer than a LAN request to a page this small needs, measured at 13
     * to 47 ms on the panel, and it also sets how fast a worker held by an attacker comes back. The
     * cost of being wrong is one refused request from a client on a genuinely awful link.
     */
    private static final int REQUEST_DEADLINE_MS = 8_000;
    /**
     * How long a connection is kept open after an answer for the browser's next request
     * (keep-alive, 2026-10-05). A page and the burst that follows it (its scripts, its
     * stylesheet, a folder of thumbnails) then share one TLS handshake per connection instead of
     * paying one per request. Short, because an idle connection holds a worker: three seconds
     * covers a burst and lets the stats poll, five seconds apart, start afresh.
     */
    private static final int KEEP_ALIVE_IDLE_MS = 3_000;
    /** Requests one connection may carry before it is closed, as servers bound it. */
    private static final int KEEP_ALIVE_MAX_REQUESTS = 100;
    /**
     * Set by the connection, read by {@link #writeResponse}: whether the answer being written may
     * keep the connection open. Only for a request read whole, so nothing of it is left unread.
     */
    private static final ThreadLocal<Boolean> keepOpen = ThreadLocal.withInitial(() -> false);
    /** Set by {@link #writeResponse} when it answered with the connection kept open. */
    private static final ThreadLocal<Boolean> answeredOpen = ThreadLocal.withInitial(() -> false);
    /** How long the camera stream waits for a frame before it ends; see serveStream. */
    private static final long STREAM_STALL_MS = 15_000;
    /** Backoff after a failed {@code accept()}; see the comment at that call site. */
    private static final long ACCEPT_RETRY_DELAY_MS = 250L;

    /**
     * The admin page's JavaScript and CSS, loaded from {@code res/raw} at construction rather
     * than living here as Java string literals. As literals, nothing on the way to the device
     * ever parsed them: a syntax error or a name collision shipped and only showed up as a blank
     * box in somebody's browser, which happened twice. As real files (admin_command.js,
     * admin_setting.js, admin_stats.js, admin_theme.js, admin.css) they get an editor's syntax
     * support and {@code scripts/test-host.sh} checks them directly; each file carries its own
     * design rationale as comments. Read once, because this server is rebuilt whenever
     * configuration reloads and a packaged resource cannot change under a running process.
     */
    private final String commandScript;
    private final String settingScript;
    private final String checkScript;
    private final String statsScript;
    private final String automationScript;
    private final String listDetailScript;
    private final String themeScript;
    private final String pictureScript;
    private final String playlistScript;
    private final String menuScript;
    private final String fleetScript;
    private final String speakersScript;
    private final String qrScript;
    private final String cameraScript;
    private final String clockScript;
    private final String pageCss;


    private final Context context;
    private final KioskService kioskService;

    private ServerSocket serverSocket;
    /**
     * The certificate served, one object read once wherever it is used, so a reload never pairs
     * one certificate's words with another's fingerprint (review, 2026-10-05). Its socket factory
     * is null when the stored key cannot be read yet, before the first unlock, or HTTPS could not
     * be set up at all (see AdminCertificate); then the server answers every request closed
     * and never asks for the password. Otherwise every accepted connection is expected to start a TLS
     * handshake, and one that does not is sent to the https address without ever being asked
     * for a password. Null only before the first start.
     */
    private volatile AdminCertificate served;
    /** Orders a reload's publish with stop(): a reload finishing late must not speak for a stopped server. */
    private final Object tlsLock = new Object();
    /**
     * The reloads asked for, so only the latest one publishes: two reading at once (an upload's
     * and the panel's choice) finished in the wrong order and served what nobody chose last
     * (review, 2026-10-05).
     */
    private final java.util.concurrent.atomic.AtomicLong reloads =
            new java.util.concurrent.atomic.AtomicLong();
    /**
     * The kept connections waiting for their next request, closed when the certificate changes so
     * none goes on with the old one.
     */
    private final java.util.Set<Socket> idleKept =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** Set by {@link #writeResponse} for the request being served: an answer has been written. */
    private static final ThreadLocal<Boolean> answered = ThreadLocal.withInitial(() -> false);
    /** How long a reload that could read nothing waits before it tries once more. */
    private static final long RELOAD_RETRY_MS = 5_000;
    /**
     * Every accepted connection, so {@code stop()} can close them. A worker blocked reading a
     * browser's speculative connection is not interruptible and only wakes when SOCKET_TIMEOUT_MS
     * expires, which made a configuration reload freeze the caller for ten seconds.
     */
    private final java.util.Set<Socket> liveSockets =
            Collections.synchronizedSet(new java.util.HashSet<>());
    /**
     * Live connections per remote address, for {@link #PER_HOST_CONNECTIONS}.
     *
     * <p>Counted from {@code accept()} rather than from the worker, because a connection waiting in
     * the queue is holding a descriptor just as firmly as one being served, and a cap that ignored
     * the queue would be a cap on nothing.
     */
    private final Map<String, Integer> hostConnections = new java.util.HashMap<>();
    /**
     * Rate-limits password guessing per address. Lives for as long as the server does rather than
     * per connection, since a refused request closes its connection and an attacker gets a fresh
     * one per attempt.
     */
    private final AuthThrottle authThrottle = new AuthThrottle();
    // Process-wide: a controller reload must not overlap two 24 MB upload bodies.
    private static final java.util.concurrent.Semaphore uploadSlot =
            new java.util.concurrent.Semaphore(1);
    private final java.util.concurrent.ScheduledExecutorService connectionDeadlines =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
    private long lastCapacityLogMs;
    private ExecutorService workers;
    private Thread acceptThread;
    private volatile boolean running;
    private String boundAdminPassword = "";
    /** The port the running server actually bound, or -1 while it is down; see checkValue. */
    private volatile int boundPort = -1;

    HttpAdminServer(Context context, KioskService kioskService) {
        this.context = context;
        this.kioskService = kioskService;
        commandScript = script(R.raw.admin_command);
        settingScript = script(R.raw.admin_setting);
        checkScript = script(R.raw.admin_check);
        statsScript = script(R.raw.admin_stats);
        automationScript = script(R.raw.admin_automation);
        listDetailScript = script(R.raw.admin_listdetail);
        themeScript = script(R.raw.admin_theme);
        pictureScript = script(R.raw.admin_pictures);
        playlistScript = script(R.raw.admin_albums);
        menuScript = script(R.raw.admin_menu);
        fleetScript = script(R.raw.admin_fleet);
        speakersScript = script(R.raw.admin_speakers);
        qrScript = script(R.raw.admin_qr);
        cameraScript = script(R.raw.admin_camera);
        clockScript = script(R.raw.admin_clock);
        // The scheme's colours ride along, light and dark (Palettes).
        pageCss = readRawText(R.raw.admin) + "\n" + readRawText(R.raw.admin_palettes);
    }

    /** One raw JavaScript resource, wrapped in the tag the page splices it in with. */
    private String script(int rawRes) {
        return "<script>\n" + readRawText(rawRes) + "</script>";
    }

    void start() {
        KioskConfig config = KioskConfig.load(context);
        if (!config.webAdminEnabled) {
            // Off by the operator's own flag, password untouched: turning the surface off must
            // not cost the credential, and turning it back on must not require retyping one.
            Log.i(TAG, "HTTP admin disabled by the operator");
            KioskRuntimeState.publishHttpAdminState(false, config.httpPort, "disabled");
            return;
        }
        if (config.httpAdminPassword.length() < MIN_ADMIN_PASSWORD_LENGTH) {
            Log.i(TAG, "HTTP admin disabled: no admin password of sufficient length is configured");
            KioskRuntimeState.publishHttpAdminState(false, config.httpPort, "no password");
            return;
        }
        served = AdminCertificate.load(context);
        try {
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress(config.httpPort));
        } catch (IOException exception) {
            Log.e(TAG, "Unable to bind HTTP admin port " + config.httpPort, exception);
            serverSocket = null;
            KioskRuntimeState.publishHttpAdminState(false, config.httpPort, "port unavailable");
            return;
        }
        boundAdminPassword = config.httpAdminPassword;
        boundPort = config.httpPort;
        // Deliberately not Executors.newFixedThreadPool: that helper's queue is unbounded. See
        // ACCEPT_QUEUE_DEPTH. Core and max are equal, so the queue is what absorbs a burst and
        // rejection is what stops a flood.
        workers = new java.util.concurrent.ThreadPoolExecutor(
                WORKER_THREADS, WORKER_THREADS, 0L, TimeUnit.MILLISECONDS,
                new java.util.concurrent.ArrayBlockingQueue<>(ACCEPT_QUEUE_DEPTH));
        running = true;
        acceptThread = new Thread(this::acceptLoop, "MuralisHttpAccept");
        acceptThread.start();
        KioskRuntimeState.publishHttpAdminState(true, config.httpPort, "");
        publish(served);
        Log.i(TAG, (served.secure() ? "HTTPS" : "HTTP (no certificate)")
                + " admin listening on port " + config.httpPort);
    }

    private static void publish(AdminCertificate certificate) {
        KioskRuntimeState.publishHttpAdminTls(certificate.secure(), certificate.fingerprint,
                !certificate.secure() ? "" : certificate.uploaded
                        ? KioskConfig.CERTIFICATE_UPLOADED : KioskConfig.CERTIFICATE_OWN,
                certificate.uploadProblem);
    }

    /** Whether this server speaks HTTPS now. */
    private boolean secure() {
        AdminCertificate certificate = served;
        return certificate != null && certificate.secure();
    }

    /**
     * Serves the certificate now chosen from the next connection on, without closing the listening
     * socket: the connection that asked closes after its answer and the kept ones close at once,
     * so the browser's next one shakes hands with the new certificate. A restart of the whole server would have cut the answer that
     * said the upload was kept. Called after an upload, a removal and a change of the choice,
     * from any surface, and at the first unlock when an upload could not be read before it.
     */
    void reloadCertificate() {
        reloadCertificate(true);
    }

    private void reloadCertificate(boolean mayRetry) {
        if (!running) {
            return;
        }
        long ticket = reloads.incrementAndGet();
        // Read outside the lock: a Keystore round trip must not hold up stop().
        AdminCertificate loaded = AdminCertificate.load(context);
        synchronized (tlsLock) {
            if (!running || ticket != reloads.get()) {
                // Stopped meanwhile, or a later reload was asked for and speaks instead.
                return;
            }
            AdminCertificate current = served;
            if (!loaded.secure() && current != null && current.secure()) {
                // Nothing could be read this moment (a Keystore briefly away): the certificate
                // already serving stays, never a close mid-life, and the read is
                // tried once more, so a Delete does not leave its certificate served until the
                // next restart (review, 2026-10-05).
                Log.w(TAG, "No certificate could be read; the one served stays"
                        + (mayRetry ? " and the read is tried again" : ""));
                if (mayRetry) {
                    try {
                        // On a thread of its own when it fires: the deadline thread must not
                        // wait on the Keystore the retry is there for (review, 2026-10-05).
                        connectionDeadlines.schedule(() -> new Thread(
                                () -> reloadCertificate(false), "MuralisCertificateRetry").start(),
                                RELOAD_RETRY_MS, TimeUnit.MILLISECONDS);
                    } catch (java.util.concurrent.RejectedExecutionException stopping) {
                        // The server is stopping; its successor reads afresh.
                    }
                }
                return;
            }
            served = loaded;
            publish(loaded);
        }
        // The answer to the request that asked for this, if one is being served on this thread,
        // closes its connection, and the kept connections waiting for a next request close now:
        // the browser's next fetch shakes hands with the new certificate (review, 2026-10-05).
        keepOpen.set(false);
        for (Socket idle : idleKept) {
            closeQuietly(idle);
        }
        Log.i(TAG, "Web admin now serves " + (!loaded.secure() ? "nothing (closed until a certificate can be used)"
                : loaded.uploaded ? "the uploaded certificate" : "this panel's own certificate"));
    }

    void stop() {
        synchronized (tlsLock) {
            running = false;
        }
        connectionDeadlines.shutdownNow();
        KioskRuntimeState.publishHttpAdminState(false, KioskRuntimeState.httpAdminPort(),
                "stopped");
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
                // The listening socket is being discarded either way.
            }
        }
        // Closing the accepted sockets is what actually unblocks the workers: a thread parked in a
        // socket read ignores interrupt(), so shutdownNow() alone left stop() waiting for the read
        // timeout. A configuration reload runs on the main thread, so that wait was a frozen UI.
        synchronized (liveSockets) {
            for (Socket socket : liveSockets) {
                closeQuietly(socket);
            }
            liveSockets.clear();
        }
        if (acceptThread != null) {
            try {
                acceptThread.join(SHUTDOWN_WAIT_MS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        if (workers != null) {
            workers.shutdownNow();
            try {
                workers.awaitTermination(SHUTDOWN_WAIT_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void acceptLoop() {
        while (running) {
            Socket socket;
            try {
                socket = serverSocket.accept();
            } catch (IOException exception) {
                if (running) {
                    Log.w(TAG, "HTTP admin accept failed", exception);
                    // Back off before retrying. accept() failing usually means the process is out
                    // of file descriptors, and that condition persists: retrying immediately spun
                    // this thread at 100% CPU with a log line per iteration, on a panel that is
                    // supposed to sit on a wall for months. A short sleep costs nothing on the
                    // one-off failures and turns the pathological case into a slow retry.
                    try {
                        Thread.sleep(ACCEPT_RETRY_DELAY_MS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                continue;
            }
            String host = remoteHostOf(socket);
            if (!reserveHostSlot(host)) {
                logCapacityRefusal();
                closeQuietly(socket);
                continue;
            }
            try {
                liveSockets.add(socket);
                workers.execute(() -> {
                    try {
                        handleConnection(socket);
                    } finally {
                        releaseHostSlot(host);
                    }
                });
            } catch (RejectedExecutionException busy) {
                liveSockets.remove(socket);
                logCapacityRefusal();
                releaseHostSlot(host);
                closeQuietly(socket);
            }
        }
    }

    private void logCapacityRefusal() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastCapacityLogMs >= 30_000L) {
            lastCapacityLogMs = now;
            Log.w(TAG, "Web admin connection capacity reached; refusing new connections");
        }
    }

    /** Remote address as a bare string, or a constant when it cannot be determined. */
    private static String remoteHostOf(Socket socket) {
        java.net.InetAddress address = socket.getInetAddress();
        return address == null ? "unknown" : address.getHostAddress();
    }

    /**
     * Claims one of {@link #PER_HOST_CONNECTIONS} for this address.
     *
     * @return false when the address already holds its share, in which case nothing was claimed and
     *     the caller must close the socket.
     */
    private boolean reserveHostSlot(String host) {
        synchronized (hostConnections) {
            int held = hostConnections.containsKey(host) ? hostConnections.get(host) : 0;
            if (held >= PER_HOST_CONNECTIONS) {
                return false;
            }
            hostConnections.put(host, held + 1);
            return true;
        }
    }

    /** Releases a slot, and drops the entry entirely at zero so the map cannot grow without bound. */
    private void releaseHostSlot(String host) {
        synchronized (hostConnections) {
            Integer held = hostConnections.get(host);
            if (held == null) {
                return;
            }
            if (held <= 1) {
                hostConnections.remove(host);
            } else {
                hostConnections.put(host, held - 1);
            }
        }
    }

    private void handleConnection(Socket socket) {
        liveSockets.add(socket);
        // The channel a reply goes down: the TLS socket once the handshake is done, the raw one
        // before. Declared here so the failure barrier below answers on the right one; a 500
        // written to the raw socket under TLS would be plaintext inside the encrypted stream.
        Socket channel = socket;
        // Held separately from the channel so a wrapper whose handshake failed is still released:
        // autoClose is false, so closing it sends at most a TLS alert and never touches the raw
        // socket, which is what lets the redirect's lingering close below finish the job.
        SSLSocket tlsToRelease = null;
        OutputStream output = null;
        boolean uploadHeld = false;
        java.util.concurrent.ScheduledFuture<?> deadline = null;
        try {
            socket.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
            deadline = connectionDeadlines.schedule(() -> closeQuietly(socket),
                    HANDSHAKE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            // Every connection is handed to TLS first. Android's Conscrypt wraps the socket's file
            // descriptor, not its streams, so nothing can be peeked before it and handed back:
            // the handshake itself is the protocol detector. BoringSSL names a plain HTTP request
            // it was fed instead of a ClientHello, and that one case is answered on the raw
            // socket with a redirect to this panel's https address, without a password ever
            // being asked over plain text. Everything else that fails a handshake, a browser
            // that did not accept the certificate above all, is the caller's decision.
            InputStream plain;
            AdminCertificate certificate = served;
            SSLSocketFactory tlsFactory = certificate == null ? null : certificate.socketFactory;
            if (tlsFactory != null) {
                SSLSocket tls = (SSLSocket) tlsFactory.createSocket(
                        socket, null, socket.getPort(), false);
                tls.setUseClientMode(false);
                tls.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
                tlsToRelease = tls;
                // Assigned only once the handshake has succeeded, which is what makes the
                // plain-HTTP redirect below survive. Set before it (as it briefly was on
                // 2026-09-10), the barrier at the end closes this wrapper over the same file
                // descriptor the redirect was just written to, and Conscrypt's teardown of a
                // socket whose handshake failed takes the reply with it: the 301's headers
                // reached the browser, its 50 byte body never did, and the connection ended in
                // an RST, so Chrome drew an empty page and Firefox hung. Measured, both engines.
                try {
                    tls.startHandshake();
                } catch (IOException refused) {
                    if (looksLikePlainHttp(refused)) {
                        redirectToHttps(socket);
                    } else {
                        Log.d(TAG, "TLS handshake did not complete: " + refused.getMessage()
                                + (refused.getCause() == null ? "" : " / " + refused.getCause().getMessage()));
                    }
                    return;
                }
                channel = tls;
                plain = tls.getInputStream();
            } else {
                // No certificate can be used right now (before the first unlock after a restart,
                // or none could be made): the admin stays closed rather than take the password
                // over plain text. Nothing is asked and nothing of the request is read.
                answerClosed(socket);
                return;
            }
            // Wrapped, so both readLine and readBody inherit the whole-request deadline without
            // either of them having to know about it. See REQUEST_DEADLINE_MS.
            DeadlineInputStream input = new DeadlineInputStream(plain, REQUEST_DEADLINE_MS);
            output = channel.getOutputStream();

            // One request after another on the same connection while each answer keeps it open
            // (keep-alive); anything else ends the loop and the connection with it.
            for (int requests = 0; ; requests++) {
                if (requests > 0) {
                    idleKept.add(socket);
                    if (served != certificate) {
                        // The certificate changed between the answer and here: this
                        // connection still has the old one (review, 2026-10-05).
                        return;
                    }
                    if (uploadHeld) {
                        uploadSlot.release();
                        uploadHeld = false;
                    }
                    deadline.cancel(false);
                    deadline = connectionDeadlines.schedule(() -> closeQuietly(socket),
                            KEEP_ALIVE_IDLE_MS + 1_000, TimeUnit.MILLISECONDS);
                    input.extend(KEEP_ALIVE_IDLE_MS + 1_000);
                    try {
                        channel.setSoTimeout(KEEP_ALIVE_IDLE_MS);
                    } catch (IOException closedMeanwhile) {
                        // Closed by a certificate change a moment ago: nothing to say.
                        return;
                    }
                }
                String requestLine;
                try {
                    requestLine = readLine(input, MAX_REQUEST_LINE_LENGTH);
                    idleKept.remove(socket);
                } catch (IOException idle) {
                    if (requests > 0) {
                        // A kept connection nobody used again: closed without a word in the log.
                        return;
                    }
                    throw idle;
                }
                if (requestLine == null || requestLine.isEmpty()) {
                    return;
                }
                answered.set(false);
                input.extend(REQUEST_DEADLINE_MS);
                deadline.cancel(false);
                deadline = connectionDeadlines.schedule(() -> closeQuietly(socket),
                        REQUEST_DEADLINE_MS, TimeUnit.MILLISECONDS);
                channel.setSoTimeout(SOCKET_TIMEOUT_MS);
                String[] parts = requestLine.split(" ", 3);
                if (parts.length < 2) {
                    writeResponse(output, 400, "text/plain", bytes("Bad Request"));
                    return;
                }
                String method = parts[0];
                String target = parts[1];

                Map<String, String> headers = new HashMap<>();
                boolean headersWhole = false;
                boolean lengthsDiffer = false;
                for (int i = 0; i < MAX_HEADER_LINES; i++) {
                    String line = readLine(input, MAX_REQUEST_LINE_LENGTH);
                    if (line == null || line.isEmpty()) {
                        headersWhole = line != null;
                        break;
                    }
                    int colon = line.indexOf(':');
                    if (colon > 0) {
                        String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                        String value = line.substring(colon + 1).trim();
                        lengthsDiffer |= name.equals("content-length")
                                && headers.containsKey(name) && !headers.get(name).equals(value);
                        headers.put(name, value);
                    }
                }
                String declaredLength = headers.get("content-length");
                if (lengthsDiffer || (declaredLength != null
                        && !declaredLength.matches("[0-9]{1,10}"))) {
                    // A length that is not one plain number cannot say where this request ends,
                    // and on a kept connection the rest would be read as the next request.
                    writeResponse(output, 400, "text/plain", bytes("Bad Request: Content-Length"));
                    return;
                }

                // Credentials first, body second, always. The picture upload is allowed megabytes,
                // and reading them before judging the password would let anyone on the network have
                // this panel allocate 24 MB per worker thread on demand, an OutOfMemoryError that
                // no barrier below catches (2026-09-09 review). Answering a request before its body
                // is read is what every HTTP server does with a 401, and every answer written
                // before the body is read closes the connection.
                String remoteHost = remoteHostOf(socket);
                // elapsedRealtime, not wall time: a lockout must not be escapable by setting the
                // clock, and must not stall while the device is suspended.
                long nowMs = android.os.SystemClock.elapsedRealtime();
                long lockedForMs = authThrottle.lockedOutFor(remoteHost, nowMs);
                if (lockedForMs > 0L) {
                    // 429 with Retry-After rather than another 401. It is the honest answer, it
                    // tells a legitimate operator who mistyped their password how long to wait
                    // instead of leaving them guessing, and refusing here costs no worker thread,
                    // which is the whole reason this throttles by refusing rather than by sleeping.
                    Map<String, String> extra = new HashMap<>();
                    extra.put("Retry-After", Long.toString((lockedForMs + 999L) / 1000L));
                    writeResponse(output, 429, "text/plain",
                            bytes("Too Many Requests"), extra);
                    return;
                }
                if (!isAuthorized(headers.get("authorization"))) {
                    if (authThrottle.recordFailure(remoteHost, nowMs)) {
                        KioskRuntimeState.recordAuthLockout(remoteHost);
                        // Only when a lockout begins. Logging every failure would let anyone on the
                        // LAN fill this panel's log by guessing, which is a denial of service
                        // wearing the costume of an audit trail. Never logs the credential that was
                        // tried.
                        Log.w(TAG, "Web admin authentication failed "
                                + AuthThrottle.FAILURES_BEFORE_LOCKOUT + " times from " + remoteHost
                                + "; that address is now refused for "
                                + (authThrottle.lockedOutFor(remoteHost, nowMs) / 1000L) + "s");
                    }
                    Map<String, String> extra = new HashMap<>();
                    extra.put("WWW-Authenticate", "Basic realm=\"Muralis\"");
                    writeResponse(output, 401, "text/plain", bytes("Unauthorized"), extra);
                    return;
                }
                authThrottle.recordSuccess(remoteHost, nowMs);
                // Every state-changing route is POST, deliberately, so this one check before the
                // routing covers all of them, including any handler added later.
                if (method.equals("POST")) {
                    String refusal = crossSiteRefusal(headers);
                    if (refusal != null) {
                        Log.w(TAG, "Refused a state-changing request: " + refusal);
                        writeResponse(output, 403, "text/plain", bytes("Forbidden: " + refusal));
                        return;
                    }
                }
                String requestPath = target.split("\\?", 2)[0];
                if (method.equals("GET") && requestPath.equals("/camera/stream")) {
                    // The stream lasts as long as the viewer does; the request deadline that
                    // closes a slow connection would cut it at eight seconds.
                    deadline.cancel(false);
                }
                if (method.equals("GET") && requestPath.equals("/api/camera/picture")) {
                    // An RTSP camera takes a few seconds to answer and send its first picture.
                    deadline.cancel(false);
                    deadline = connectionDeadlines.schedule(() -> closeQuietly(socket),
                            CAMERA_PICTURE_DEADLINE_MS, TimeUnit.MILLISECONDS);
                }
                boolean upload = method.equals("POST") && requestPath.equals("/api/pictures");
                if (upload) {
                    uploadHeld = uploadSlot.tryAcquire();
                    if (!uploadHeld) {
                        writeResponse(output, 503, "text/plain",
                                bytes("Another upload is in progress"),
                                Collections.singletonMap("Retry-After", "5"));
                        return;
                    }
                    input.extend(UPLOAD_DEADLINE_MS);
                    deadline.cancel(false);
                    deadline = connectionDeadlines.schedule(() -> closeQuietly(socket),
                            UPLOAD_DEADLINE_MS, TimeUnit.MILLISECONDS);
                }
                if (method.equals("POST") && requestPath.equals("/adopt")) {
                    input.extend(ADOPT_DEADLINE_MS);
                    deadline.cancel(false);
                    deadline = connectionDeadlines.schedule(() -> closeQuietly(socket),
                            ADOPT_DEADLINE_MS, TimeUnit.MILLISECONDS);
                }
                if (method.equals("POST") && requestPath.equals("/integrations")) {
                    input.extend(INTEGRATION_DEADLINE_MS);
                    deadline.cancel(false);
                    deadline = connectionDeadlines.schedule(() -> closeQuietly(socket),
                            INTEGRATION_DEADLINE_MS, TimeUnit.MILLISECONDS);
                }
                if (requestPath.equals("/api/fleet/screenshot")
                        || requestPath.equals("/api/fleet/identify")
                        || requestPath.equals("/api/fleet/panel")) {
                    input.extend(FLEET_PROXY_DEADLINE_MS);
                    deadline.cancel(false);
                    deadline = connectionDeadlines.schedule(() -> closeQuietly(socket),
                            FLEET_PROXY_DEADLINE_MS, TimeUnit.MILLISECONDS);
                }
                byte[] body;
                try {
                    boolean order = method.equals("POST")
                            && requestPath.equals("/api/albums/order");
                    boolean document = method.equals("POST")
                            && (requestPath.equals("/api/automations")
                                    || requestPath.equals("/api/automations/save")
                                    || requestPath.equals("/settings-file")
                                    || requestPath.equals("/api/sensors/settings")
                                    || requestPath.equals("/api/beacons/names"));
                    boolean certificateUpload = method.equals("POST")
                            && (requestPath.equals("/api/certificate")
                                    || requestPath.equals("/mqtt-certificate")
                                    || (requestPath.equals("/") && isMultipart(headers)));
                    body = readBody(input, headers, upload ? MAX_UPLOAD_BYTES
                            : order ? MAX_ORDER_BYTES : document ? MAX_DOCUMENT_BYTES
                            : certificateUpload ? MAX_CERTIFICATE_BYTES : MAX_BODY_BYTES);
                } catch (BodyTooLargeException tooLarge) {
                    if (requestPath.equals("/") && method.equals("POST") && isMultipart(headers)) {
                        // A file picked by mistake on the page's upload: the page, with the reason
                        // in the certificate block, not a bare line of text (review, 2026-10-05).
                        // What the browser is sending is read first, within the picture upload's
                        // budget: answered before it and closed after the half second a lingering
                        // close drains, a 5 MB photo on its way over Wi-Fi ended in a connection
                        // reset instead of this page (Chrome and Firefox, measured on the phone).
                        if (tooLarge.declared <= MAX_UPLOAD_BYTES) {
                            input.extend(UPLOAD_DEADLINE_MS);
                            deadline.cancel(false);
                            deadline = connectionDeadlines.schedule(() -> closeQuietly(socket),
                                    UPLOAD_DEADLINE_MS, TimeUnit.MILLISECONDS);
                            drain(input, tooLarge.declared);
                        }
                        writeResponse(output, 413, "text/html; charset=utf-8",
                                bytes(buildSettingsPage("Not uploaded: the files are larger than "
                                        + (MAX_CERTIFICATE_BYTES / 1024) + " KB, and a certificate "
                                        + "with its key takes a few.", "certificate")));
                        return;
                    }
                    writeResponse(output, 413, "text/plain",
                            bytes("Payload Too Large: " + tooLarge.getMessage()));
                    return;
                }


                keepOpen.set(mayKeepOpen(method, parts, headers, headersWhole, requests));
                answeredOpen.set(false);
                try {
                    route(method, target, headers, body, output, socket);
                } finally {
                    keepOpen.set(false);
                }
                // Closed too when the certificate changed since this connection's handshake, so
                // the page that announces a new certificate is fetched on a connection that has
                // it (review, 2026-10-05).
                if (!answeredOpen.get() || served != certificate) {
                    return;
                }
                answeredOpen.set(false);
            }
        } catch (IOException exception) {
            Log.w(TAG, "HTTP admin connection error", exception);
        } catch (RuntimeException | OutOfMemoryError unexpected) {
            // The barrier that keeps a bad request from killing the panel.
            //
            // These run on an ExecutorService worker, so an escaping RuntimeException reaches
            // Android's default uncaught handler and takes the whole process down with it: the
            // dashboard blinks out and every uptime counter resets. That is unacceptable here for
            // any input, and it was reachable from a single malformed query string -- URLDecoder
            // throws IllegalArgumentException on a truncated escape like "100%", which the
            // documented `?cmnd=dashboard.url&url=...` workflow makes easy to send by accident.
            //
            // OutOfMemoryError is caught with it since 2026-09-09: the upload path allocates a
            // body of its own size, and a panel that dies rather than refusing one request is
            // the failure this barrier exists to prevent.
            //
            // Fail soft like the rest of the app: log it, answer 500, keep serving.
            Log.w(TAG, "HTTP admin request failed", unexpected);
            try {
                // Only where nothing was answered yet: a 500 after a whole answer would reach a
                // browser that may read it as the answer to its next request (review,
                // 2026-10-05). The connection closes either way.
                if (!answered.get()) {
                    writeResponse(output != null ? output : channel.getOutputStream(), 500,
                            "text/plain", bytes("Internal Server Error"));
                }
            } catch (IOException | RuntimeException ignored) {
                // The socket is already unusable, or the response was partly written. Nothing left
                // to say to this client; the point was to survive, and we have.
            }
        } finally {
            if (deadline != null) deadline.cancel(false);
            if (uploadHeld) uploadSlot.release();
            liveSockets.remove(socket);
            idleKept.remove(socket);
            keepOpen.set(false);
            answeredOpen.set(false);
            answered.set(false);
            if (tlsToRelease != null) {
                // Sends the TLS close_notify where there was a session, and frees the native one
                // now rather than at GC. autoClose was false, so the raw socket below is still
                // ours, which is the whole point: the FIN and the drain come after this.
                closeQuietly(tlsToRelease);
            }
            lingeringClose(socket);
        }
    }

    private static boolean isMultipart(Map<String, String> headers) {
        String type = headers.get("content-type");
        return type != null && type.toLowerCase(Locale.ROOT).startsWith("multipart/form-data");
    }

    private void route(
            String method, String target, Map<String, String> headers, byte[] body,
            OutputStream output, java.net.Socket socket) throws IOException {
        String path = target;
        String query = "";
        int questionMark = target.indexOf('?');
        if (questionMark >= 0) {
            path = target.substring(0, questionMark);
            query = target.substring(questionMark + 1);
        }

        if (path.equals("/") && method.equals("GET")) {
            writeResponse(output, 200, "text/html; charset=utf-8", bytes(buildSettingsPage()));
        } else if (path.equals("/") && method.equals("POST") && isMultipart(headers)) {
            // The web admin box's upload, posted to the page's own address so a refusal is drawn
            // there and not under an address that answers only a POST (review, 2026-10-05).
            handleCertificateUpload(headers, body, output, true);
        } else if (path.equals("/") && method.equals("POST")
                && "certificate".equals(parseFormBody(headers, body).get("section"))) {
            handleCertificateDelete(parseFormBody(headers, body), output, true);
        } else if (path.equals("/") && method.equals("POST")) {
            Map<String, String> form = parseFormBody(headers, body);
            String refusal = saveSettings(form);
            writeResponse(output, refusal == null ? 200 : 400, "text/html; charset=utf-8",
                    bytes(buildSettingsPage(refusal, form.getOrDefault("section", ""))));
            if (refusal == null) {
                // Reload asynchronously through the service's own message queue: doing it inline
                // here would have this worker thread join the very executor it is running on.
                KioskService.reloadConfiguration(context);
            }
        } else if (path.equals("/api/setting") && method.equals("POST")) {
            handleSetting(parseFormBody(headers, body), output);
        } else if (path.equals("/api/certificate") && method.equals("POST")) {
            handleCertificateUpload(headers, body, output, false);
        } else if (path.equals("/mqtt-certificate") && method.equals("POST")) {
            handleMqttClientUpload(headers, body, output);
        } else if (path.equals("/api/certificate/delete") && method.equals("POST")) {
            handleCertificateDelete(parseFormBody(headers, body), output, false);
        } else if (path.equals("/sensors") && method.equals("GET")) {
            writeResponse(output, 200, "text/html; charset=utf-8",
                    bytes(buildSensorsPage(queryValue(query, "id"), false)));
        } else if (path.equals("/sensor") && method.equals("GET")) {
            writeResponse(output, 200, "text/html; charset=utf-8",
                    bytes(buildSensorPage(queryValue(query, "id"))));
        } else if (path.equals("/automations") && method.equals("GET")) {
            writeResponse(output, 200, "text/html; charset=utf-8",
                    bytes(buildAutomationsPage(null, queryValue(query, "id"), null, false)));
        } else if (path.equals("/automation") && method.equals("GET")) {
            String wanted = queryValue(query, "id");
            Automations.Rule rule = Automations.find(KioskConfig.automationsOf(context), wanted);
            if (rule == null && wanted != null && !wanted.isEmpty()) {
                writeResponse(output, 200, "text/html; charset=utf-8",
                        bytes(buildAutomationsPage("No automation is called that.")));
            } else {
                writeResponse(output, 200, "text/html; charset=utf-8",
                        bytes(buildAutomationPage(rule == null ? new Automations.Rule() : rule,
                                null)));
            }
        } else if (path.equals("/api/automations") && method.equals("GET")) {
            writeJson(output, 200, automationsDocument());
        } else if (path.equals("/api/automations") && method.equals("POST")) {
            replaceAutomations(headers, body, output);
        } else if (path.equals("/api/sensors/settings") && method.equals("GET")) {
            writeJson(output, 200, KioskConfig.sensorSettingsDocument(context));
        } else if (path.equals("/api/sensors/settings") && method.equals("POST")) {
            applySensorSettings(headers, body, output);
        } else if (path.equals("/api/beacons/names") && method.equals("GET")) {
            writeResponse(output, 200, "application/json",
                    bytes(KioskConfig.beaconNames(context).store()));
        } else if (path.equals("/api/beacons/names") && method.equals("POST")) {
            applyBeaconNames(headers, body, output);
        } else if (path.equals("/api/automations/save") && method.equals("POST")) {
            saveAutomation(parseFormBody(headers, body), output);
        } else if (path.equals("/api/automations/delete") && method.equals("POST")) {
            deleteAutomation(parseFormBody(headers, body), output);
        } else if (path.equals("/stats") && method.equals("GET")) {
            writeResponse(output, 200, "text/html; charset=utf-8", bytes(buildStatsPage()));
        } else if (path.equals("/api/camera/picture") && method.equals("GET")) {
            serveCameraPicture(queryValue(query, "name"), output);
        } else if (path.equals("/camera/snapshot.jpg") && method.equals("GET")) {
            serveSnapshot(output);
        } else if (path.equals("/camera/stream") && method.equals("GET")) {
            serveStream(output, socket);
        } else if (path.equals("/settings-file") && method.equals("GET")) {
            writeResponse(output, 200, "text/html; charset=utf-8",
                    bytes(buildSettingsFilePage(null, null)));
        } else if (path.equals("/settings-file") && method.equals("POST")) {
            handleSettingsFile(headers, body, output);
        } else if (path.equals("/api/setup-qr.svg") && method.equals("GET")) {
            String content = setupQrContent();
            String svg = content == null ? null : QrCodes.svg(content);
            if (svg == null) {
                writeResponse(output, 413, "text/plain", bytes("The settings do not fit in a QR"));
            } else {
                writeResponse(output, 200, "image/svg+xml", bytes(svg),
                        java.util.Collections.singletonMap("Cache-Control", "no-store"));
            }
        } else if (path.equals("/app-lock") && method.equals("GET")) {
            writeResponse(output, 200, "text/html; charset=utf-8", bytes(buildAppLockPage(null,
                    "1".equals(queryValue(query, "system")))));
        } else if (path.equals("/app-lock") && method.equals("POST")) {
            String refused = saveAppLock(parseFormBody(headers, body));
            if (refused == null) {
                // Back to Dashboard with App picked as its draft: the Dashboard's Save switches.
                writeResponse(output, 303, "text/plain", bytes("saved"),
                        Collections.singletonMap("Location", "/?draft=app"
                                + settingsAt("dashboard").substring(1)));
            } else {
                writeResponse(output, 400, "text/html; charset=utf-8",
                        bytes(buildAppLockPage(refused, false)));
            }
        } else if (path.equals("/alerts") && method.equals("GET")) {
            // Alerts live on the Fleet page's Shared settings since 2026-10-25: a bookmark lands
            // there.
            writeResponse(output, 303, "text/plain", bytes("moved"),
                    Collections.singletonMap("Location", "/fleet?tab=shared&section=alerts"));
        } else if (path.equals("/alerts") && method.equals("POST")) {
            Map<String, String> form = parseFormBody(headers, body);
            String refused = saveAlerts(form);
            FleetView alerts = FleetView.of(Collections.singletonMap("tab", "shared"));
            alerts.section = "alerts";
            alerts.scope = form.getOrDefault("scope", FleetDocument.EVERYONE);
            // Saved, a fresh page that says so, so a reload does not post again.
            if (refused == null) {
                writeResponse(output, 303, "text/plain", bytes("saved"),
                        Collections.singletonMap("Location", alerts.address() + "&saved=1"));
            } else {
                writeResponse(output, 400, "text/html; charset=utf-8",
                        bytes(buildFleetPage(refused, alerts)));
            }
        } else if (path.equals("/page-options") && method.equals("GET")) {
            writeResponse(output, 200, "text/html; charset=utf-8",
                    bytes(buildPageOptionsPage(null, null)));
        } else if (path.equals("/page-options") && method.equals("POST")) {
            Map<String, String> form = parseFormBody(headers, body);
            String refusal = saveSettings(form);
            String section = form.getOrDefault("section", "");
            writeResponse(output, refusal == null ? 200 : 400, "text/html; charset=utf-8",
                    bytes(buildPageOptionsPage(refusal, section)));
            if (refusal == null) {
                KioskService.reloadConfiguration(context);
            }
        } else if (path.equals("/camera-groups") && method.equals("POST")) {
            Map<String, String> form = parseFormBody(headers, body);
            String notice = saveCameraGroup(form);
            writeResponse(output, notice == null || !notice.startsWith("Not ") ? 200 : 400,
                    "text/html; charset=utf-8", bytes(buildCamerasPage(notice,
                            savedItem("g:", form, notice), false)));
        } else if (path.equals("/clock/page") && method.equals("GET")) {
            String id = clockInstance(queryValue(query, "id"));
            if (id == null) {
                writeResponse(output, 404, "text/plain", bytes("No Bitcoin clock is added"));
            } else {
                java.util.Map<String, String> framed = new java.util.HashMap<>();
                framed.put(FRAMED_BY_SELF, "1");
                framed.put("Cache-Control", "no-store");
                writeResponse(output, 200, "text/html; charset=utf-8", bytes(BitcoinClockPage.html(
                        context, "/clock/state?id=" + urlEncode(id),
                        "/clock/picture?id=" + urlEncode(id), true)), framed);
            }
        } else if (path.equals("/clock/state") && method.equals("GET")) {
            String id = clockInstance(queryValue(query, "id"));
            if (id == null) {
                writeResponse(output, 404, "text/plain", bytes("No Bitcoin clock is added"));
            } else {
                writeResponse(output, 200, "application/json",
                        bytes(BitcoinClockPage.state(context, id)),
                        java.util.Collections.singletonMap("Cache-Control", "no-store"));
            }
        } else if (path.equals("/clock/picture") && method.equals("GET")) {
            byte[] jpeg = clockInstance(queryValue(query, "id")) == null ? null
                    : BitcoinClockPage.picture(context, queryValue(query, "p"));
            if (jpeg == null) {
                writeResponse(output, 404, "text/plain", bytes("No such picture"));
            } else {
                writeResponse(output, 200, "image/jpeg", jpeg,
                        java.util.Collections.singletonMap("Cache-Control", "no-store"));
            }
        } else if (path.equals("/clock-options") && method.equals("GET")) {
            String id = clockInstance(queryValue(query, "id"));
            writeResponse(output, id == null ? 404 : 200, "text/html; charset=utf-8",
                    bytes(buildClockOptionsPage(id,
                            "1".equals(queryValue(query, "saved")) ? "Saved." : null)));
        } else if (path.equals("/clock-options") && method.equals("POST")) {
            Map<String, String> form = parseFormBody(headers, body);
            String id = clockInstance(form.get("id"));
            String refused = id == null ? "no Bitcoin clock is added" : saveClockOptions(id, form);
            if (refused == null) {
                writeResponse(output, 303, "text/plain", bytes("saved"),
                        Collections.singletonMap("Location", "/clock-options?id=" + urlEncode(id)
                                + "&saved=1"));
            } else {
                writeResponse(output, 400, "text/html; charset=utf-8",
                        bytes(buildClockOptionsPage(id, "Not saved: " + refused + ".")));
            }
        } else if (path.equals("/camera-view") && method.equals("GET")) {
            writeResponse(output, 200, "text/html; charset=utf-8", bytes(buildCameraViewPage(null)));
        } else if (path.equals("/camera-view") && method.equals("POST")) {
            Map<String, String> form = parseFormBody(headers, body);
            // Chosen here and stored, as on the panel; whether the dashboard shows the cameras
            // is the Dashboard's own Save (2026-10-27).
            String refused = kioskService.setDashboardCameras(
                    form.getOrDefault("dashboard_cameras", ""));
            if (refused == null && form.containsKey("camera_layout")) {
                KioskConfig.edit(context).cameraLayout(form.get("camera_layout"),
                        form.getOrDefault("camera_big", ""),
                        isTrue(form.getOrDefault("camera_names", "0"))).apply();
                KioskService.publishTelemetrySoon(context);
                KioskService.cameraViewMayHaveChanged(context);
            }
            writeResponse(output, refused == null ? 200 : 400, "text/html; charset=utf-8",
                    bytes(buildCameraViewPage(refused == null ? "Saved." : "Not saved: " + refused
                            + ".")));
        } else if (path.equals("/integrations") && method.equals("GET")) {
            writeResponse(output, 200, "text/html; charset=utf-8",
                    bytes(buildIntegrationsPage(null, queryValue(query, "id"), false, null)));
        } else if (path.equals("/integration") && method.equals("GET")) {
            writeResponse(output, 200, "text/html; charset=utf-8",
                    bytes(buildIntegrationsPage(null, queryValue(query, "id"), true, null)));
        } else if (path.equals("/integrations") && method.equals("POST")) {
            postIntegration(parseFormBody(headers, body), output);
        } else if (path.equals("/cameras") && method.equals("GET")) {
            writeResponse(output, 200, "text/html; charset=utf-8",
                    bytes(buildCamerasPage(null, queryValue(query, "id"), false)));
        } else if (path.equals("/camera") && method.equals("GET")) {
            String item = queryValue(query, "id");
            writeResponse(output, 200, "text/html; charset=utf-8",
                    bytes(buildCamerasPage(null, item == null ? "c:" : item, true)));
        } else if (path.equals("/adopt") && method.equals("POST")) {
            adoptByCode(parseFormBody(headers, body), output);
        } else if (path.equals("/cameras") && method.equals("POST")) {
            // Show answers with a notice too; only a refusal ("Not ...") is a 400.
            Map<String, String> form = parseFormBody(headers, body);
            String notice = saveCamera(form);
            writeResponse(output, notice == null || !notice.startsWith("Not ") ? 200 : 400,
                    "text/html; charset=utf-8", bytes(buildCamerasPage(notice,
                            savedItem("c:", form, notice), false)));
        } else if (path.equals("/fleet") && method.equals("GET")) {
            writeResponse(output, 200, "text/html; charset=utf-8",
                    bytes(buildFleetPage(fleetNotice(parseQuery(query)),
                            FleetView.of(parseQuery(query)))));
        } else if (path.equals("/fleet") && method.equals("POST")) {
            Map<String, String> form = parseFormBody(headers, body);
            String refused = saveFleetPage(form);
            // Back where the form was, as a fresh page, so a reload does not post it again; a
            // refusal is drawn there with its reason.
            // A Fleet page address and nothing else: it goes into the Location header.
            String back = form.getOrDefault("return", "/fleet");
            if (!back.matches("/fleet([?][A-Za-z0-9/?&=%._-]*)?")) {
                back = "/fleet";
            }
            if (refused == null) {
                writeResponse(output, 303, "text/plain", bytes("done"),
                        Collections.singletonMap("Location", back));
            } else {
                int question = back.indexOf('?');
                writeResponse(output, 400, "text/html; charset=utf-8", bytes(buildFleetPage(
                        refused, FleetView.of(question < 0 ? Collections.emptyMap()
                                : parseQuery(back.substring(question + 1))))));
            }
        } else if (path.equals("/gallery") && method.equals("GET")) {
            writeResponse(output, 200, "text/html; charset=utf-8",
                    bytes(buildGalleryPage(queryValue(query, "said"), queryValue(query, "tab"),
                            browseTarget(query),
                            PictureBrowser.pageSize(queryValue(query, "n")))));
        } else if (path.equals("/picture") && method.equals("GET")) {
            writeResponse(output, 200, "text/html; charset=utf-8",
                    bytes(buildPicturePage(queryValue(query, "uri"),
                            queryValue(query, "said"))));
        } else if (path.equals("/api/pictures/large") && method.equals("GET")) {
            android.graphics.Bitmap large = PictureLibrary.get(context)
                    .preview(queryValue(query, "uri"), 1280);
            if (large == null) {
                writeResponse(output, 404, "text/plain", bytes("No such picture"));
            } else {
                java.io.ByteArrayOutputStream jpeg = new java.io.ByteArrayOutputStream();
                large.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, jpeg);
                large.recycle();
                writeResponse(output, 200, "image/jpeg", jpeg.toByteArray(),
                        Collections.singletonMap("Cache-Control", "private, max-age=600"));
            }
        } else if ((path.equals("/api/gallery/add") || path.equals("/api/gallery/delete"))
                && method.equals("POST")) {
            Map<String, String> form = parseFormBody(headers, body);
            answerGallerySelection(path.endsWith("/add"), form, query, output);
        } else if (path.equals("/ambient") && method.equals("GET")) {
            writeResponse(output, 200, "text/html; charset=utf-8",
                    bytes(buildScreensaverPage(null, browseTarget(query))));
        } else if (path.equals("/album") && method.equals("GET")) {
            writeResponse(output, 200, "text/html; charset=utf-8",
                    bytes(buildPlaylistPage(null, queryValue(query, "id"), browseTarget(query),
                            PictureBrowser.pageSize(queryValue(query, "n")))));
        } else if (path.equals("/api/albums/table") && method.equals("GET")) {
            // The list of playlists as a fragment, for the Ambient page's script to re-read
            // after Use, Delete or Create without the page reloading under the reader.
            writeResponse(output, 200, "text/html; charset=utf-8",
                    bytes(playlistTable(PictureLibrary.get(context).playlists().load())));
        } else if (path.equals("/api/albums/items") && method.equals("GET")) {
            // What the playlist holds, as a fragment: a tick in the browser changes this list
            // too, and the page must not reload to say so.
            PictureLibrary itemLibrary = PictureLibrary.get(context);
            PlaylistDocument.Playlist edited = itemLibrary.playlists().load()
                    .byId(queryValue(query, "album"));
            writeResponse(output, 200, "text/html; charset=utf-8",
                    bytes(edited == null ? "<p class=\"hint bad\">That album is gone.</p>"
                            : playlistItems(edited, itemLibrary)));
        } else if (path.equals("/api/pictures/folders") && method.equals("GET")) {
            // The Folders panel as a fragment: the open mark and the upload count move with every
            // change, and the page must not reload to say so.
            writeResponse(output, 200, "text/html; charset=utf-8",
                    bytes(folderList(browseLocation(browseTarget(query)),
                            queryValue(query, "album"),
                            PictureBrowser.pageSize(queryValue(query, "n")))));
        } else if (path.equals("/api/pictures/content") && method.equals("GET")) {
            // The open folder as a fragment. A GET because it changes nothing, which is also what
            // lets a folder be reached by its address again.
            writeResponse(output, 200, "text/html; charset=utf-8",
                    bytes(folderContent(browseTarget(query), queryValue(query, "album"),
                            PictureBrowser.pageSize(queryValue(query, "n")))));
        } else if (path.equals("/api/pictures/thumb") && method.equals("GET")) {
            serveThumbnail(queryValue(query, "uri"), output);
        } else if (path.equals("/api/pictures/view") && method.equals("POST")) {
            // How a folder is shown, one preference for the panel and this page; see folderContent.
            Map<String, String> form = parseFormBody(headers, body);
            KioskConfig.edit(context).pictureView(form.get("view")).apply();
            answerPictureChange(query, form, "", true, output);
        } else if (path.equals("/api/pictures") && method.equals("POST")) {
            handlePictureUpload(headers, body, query, output);
        } else if (path.equals("/api/pictures/delete") && method.equals("POST")) {
            Map<String, String> form = parseFormBody(headers, body);
            String refusal = PictureLibrary.get(context).deleteLocal(form.get("uri"));
            KioskService.publishTelemetrySoon(context);
            answerPictureChange(query, form, refusal == null ? "Picture deleted."
                    : refused("Not deleted", refusal), refusal == null, output);
        } else if (path.equals("/api/pictures/caption") && method.equals("POST")) {
            Map<String, String> form = parseFormBody(headers, body);
            String refusal = PictureLibrary.get(context)
                    .setCaption(form.get("name"), form.getOrDefault("caption", ""));
            answerPictureChange(query, form, refusal == null ? "Name saved."
                    : refused("Not saved", refusal), refusal == null, output);
        } else if (path.equals("/api/pictures/credit") && method.equals("POST")) {
            // One picture's credit switched off or back on, from the eye beside its name box.
            // Stored at once, like the name, because it belongs to the picture and not to a draft.
            Map<String, String> form = parseFormBody(headers, body);
            Boolean shown = KioskCommandDispatcher.parseEnabledFlag(form.get("shown"));
            String refusal = shown == null ? "Supply shown as true or false"
                    : PictureLibrary.get(context).setCreditHidden(form.get("name"), !shown);
            answerPictureChange(query, form, refusal == null ? ""
                    : refused("Not changed", refusal), refusal == null, output);
        } else if (path.equals("/api/pictures/select") && method.equals("POST")) {
            Map<String, String> form = parseFormBody(headers, body);
            Boolean selected = KioskCommandDispatcher.parseEnabledFlag(form.get("selected"));
            String refusal = selected == null ? "Supply a boolean selection"
                    : PictureLibrary.get(context).selectPicture(
                            form.get("album"), form.get("uri"), selected);
            answerPictureChange(query, form, refusal == null ? "Album updated."
                    : refused("Not changed", refusal), refusal == null, output);
        } else if (path.startsWith("/api/albums") && method.equals("POST")) {
            handlePlaylistChange(path, query, parseFormBody(headers, body), output);
        } else if (path.equals("/api/albums") && method.equals("GET")) {
            writeResponse(output, 200, "application/json; charset=utf-8",
                    bytes(playlistsJson()));
        } else if (path.equals("/api/command") && method.equals("POST")) {
            handleCommand(method, query, headers, body, output);
        } else if (path.equals("/api/command") && method.equals("GET")) {
            // GET used to be accepted here and it was the worst hole in this surface. Commands
            // change state, Basic-auth credentials are attached by the browser automatically, and a
            // browser sends neither Origin nor a usable Referer for a subresource load, so
            // <img src="http://panel:8080/api/command?cmnd=app.restart"> on any page the operator
            // happened to visit fired a real command with their credentials. No token scheme fixes
            // that while the verb stays GET, because the request never carries anything the page
            // could have put in it. So the verb is gone.
            //
            // Answered explicitly rather than 404, because the alternative is an automation that
            // silently stops working with no clue why. The query string is still accepted on POST,
            // so the fix for a caller is to add -X POST and nothing else.
            writeResponse(output, 405, "application/json", bytes(
                    "{\"status\":\"rejected\",\"detail\":\"use POST; GET cannot change state\"}"),
                    java.util.Collections.singletonMap("Allow", "POST"));
        } else if (path.equals("/api/fleet/code") && method.equals("POST")) {
            // Join a fleet and New code: the open code, or a new one when none is.
            FleetServer.openJoinCode(context);
            writeResponse(output, 200, "application/json",
                    bytes(FleetServer.joinState(context).toString()));
        } else if (path.equals("/api/fleet/code/close") && method.equals("POST")) {
            FleetJoin.shared.close();
            writeResponse(output, 200, "application/json",
                    bytes(FleetServer.joinState(context).toString()));
        } else if (path.equals("/api/fleet/invite") && method.equals("GET")) {
            writeResponse(output, 200, "application/json",
                    bytes(FleetServer.inviteState(context).toString()));
        } else if (path.equals("/api/fleet/invite") && method.equals("POST")) {
            FleetInvite.shared.cancel();
            String refusal = FleetServer.openInvite(context);
            if (refusal != null) {
                writeResponse(output, 409, "application/json", bytes(rejectedJson(
                        "Not invited: " + refusal + ".")));
            } else {
                writeResponse(output, 200, "application/json",
                        bytes(FleetServer.inviteState(context).toString()));
            }
        } else if (path.equals("/api/fleet/invite/cancel") && method.equals("POST")) {
            FleetInvite.shared.cancel();
            writeResponse(output, 200, "application/json",
                    bytes(FleetServer.inviteState(context).toString()));
        } else if (path.equals("/api/fleet/invite.svg") && method.equals("GET")) {
            String content = FleetServer.inviteQrContent(context);
            String svg = content == null ? null : QrCodes.svg(content, true);
            if (svg == null) {
                writeResponse(output, 404, "text/plain", bytes("No invitation is open"));
            } else {
                writeResponse(output, 200, "image/svg+xml", bytes(svg),
                        java.util.Collections.singletonMap("Cache-Control", "no-store"));
            }
        } else if (path.equals("/api/fleet/code.svg") && method.equals("GET")) {
            String content = FleetServer.joinQrContent(context);
            String svg = content == null ? null : QrCodes.svg(content, true);
            if (svg == null) {
                writeResponse(output, 404, "text/plain", bytes("No join code is open"));
            } else {
                writeResponse(output, 200, "image/svg+xml", bytes(svg),
                        java.util.Collections.singletonMap("Cache-Control", "no-store"));
            }
        } else if (path.equals("/api/fleet/leave") && method.equals("POST")) {
            FleetServer.leaveFleet(context, "from the web admin", true);
            writeResponse(output, 200, "application/json",
                    bytes(FleetServer.joinState(context).toString()));
        } else if (path.startsWith("/api/fleet/") && !FleetServer.proStatus(context).pro
                && (path.equals("/api/fleet/screenshot") || path.equals("/api/fleet/identify")
                        || path.equals("/api/fleet/speaker") || path.equals("/api/fleet/panel"))) {
            // The Fleet page's own requests, refused like the page itself without Pro.
            writeResponse(output, 403, "application/json", bytes(rejectedJson(
                    "the Fleet page is Muralis Pro. " + FleetServer.proLine(context))));
        } else if (path.equals("/api/fleet/screenshot") && method.equals("GET")) {
            serveFleetScreenshot(queryValue(query, "panel"), output);
        } else if (path.equals("/api/fleet/speaker") && method.equals("GET")) {
            writeResponse(output, 200, "application/json",
                    bytes(FleetSpeakers.states(context, queryValue(query, "panel").isEmpty() ? null
                            : queryValue(query, "panel")).toString()));
        } else if (path.equals("/api/fleet/speaker") && method.equals("POST")) {
            Map<String, String> form = new java.util.HashMap<>(parseQuery(query));
            form.putAll(parseFormBody(headers, body));
            org.json.JSONObject done = FleetSpeakers.act(context, form.getOrDefault("target", ""),
                    form.getOrDefault("action", ""), form.get("value"));
            writeResponse(output, done.has("error") ? 400 : 200, "application/json",
                    bytes(done.toString()));
        } else if (path.equals("/api/fleet/panel") && method.equals("POST")) {
            // One panel's settings opened, or Muralis left for its home screen (action =
            // settings or home_screen), from the Fleet page of any panel.
            Map<String, String> form = new java.util.HashMap<>(parseQuery(query));
            form.putAll(parseFormBody(headers, body));
            java.util.Map<String, Object> member = FleetServer.memberById(context,
                    form.getOrDefault("panel", ""));
            String action = form.getOrDefault("action", "");
            String problem = member == null ? "no such panel is in this fleet"
                    : !FleetServer.SETTINGS.equals(action) && !FleetServer.HOME_SCREEN.equals(action)
                            && !FleetServer.DASHBOARD.equals(action)
                    ? "action must be dashboard, settings or home_screen"
                    : FleetServer.panelActionOn(context, member, action);
            writeResponse(output, problem == null ? 200 : 409, "application/json",
                    bytes(problem == null ? "{\"status\":\"accepted\",\"detail\":\"\"}"
                            : rejectedJson(problem)));
        } else if (path.equals("/api/fleet/identify") && method.equals("POST")) {
            Map<String, String> form = new java.util.HashMap<>(parseQuery(query));
            form.putAll(parseFormBody(headers, body));
            serveFleetIdentify(form.getOrDefault("panel", ""), output);
        } else if (path.equals("/api/stats") && method.equals("GET")) {
            writeResponse(output, 200, "application/json",
                    bytes(kioskService.statsJson().toString()));
        } else if (path.equals("/api/log") && method.equals("GET")) {
            // The app's own log, plain text, the last AppLog.LINES lines at or above ?level=
            // (V, D, I, W, E; everything without it), only Muralis's own tags with ?own=1, that
            // contain ?q= (anything without it): what the settings page's log block polls, and
            // what a curl gets for a support mail.
            writeResponse(output, 200, "text/plain; charset=utf-8", bytes(AppLog.text(
                    AppLog.matching(AppLog.tail(AppLog.LINES,
                            AppLog.levelOf(queryValue(query, "level")),
                            KioskCommandDispatcher.parseEnabledFlag(queryValue(query, "own"))
                                    == Boolean.TRUE), queryValue(query, "q")))));
        } else if (path.equals("/api/screenshot") && method.equals("GET")) {
            serveScreenshot(output);
        } else if (path.equals("/api/diagnostics") && method.equals("GET")) {
            // Plain text as an attachment, so a phone's browser saves it rather than shows it,
            // under a name that carries the panel and the minute. Built on request, never kept.
            Map<String, String> extra = new HashMap<>();
            extra.put("Content-Disposition",
                    "attachment; filename=\"" + kioskService.diagnosticsFileName() + "\"");
            extra.put("Cache-Control", "no-store");
            writeResponse(output, 200, "text/plain; charset=utf-8",
                    bytes(kioskService.diagnosticsText()), extra);
        } else if (path.equals("/api/check") && method.equals("POST")) {
            // POST, not GET, even though it changes nothing on this device. It makes the panel
            // open TCP connections and HTTP requests to a caller-chosen address, so as a GET it
            // was an SSRF and LAN-scanning primitive: Basic-auth credentials ride along
            // automatically, and <img src=".../api/check?kind=mqtt_host&value=192.0.2.5&port=22">
            // on any page the operator visited would have had the panel probe it and leak
            // reachability through onload/onerror timing. POST puts it behind the same
            // crossSiteRefusal gate as the commands, which is why that gate is keyed on the verb.
            writeResponse(output, 200, "application/json",
                    bytes(checkValue(checkParams(query, headers, body))));
        } else if (path.equals("/privacy") && method.equals("GET")) {
            writeResponse(output, 200, "text/html; charset=utf-8", bytes(renderLegalPage(
                    context.getString(R.string.privacy_policy_title), R.raw.privacy)));
        } else if (path.equals("/about/fonts") && method.equals("GET")) {
            // The licence the Bitcoin clock's fonts ship under, as the licence asks.
            writeResponse(output, 200, "text/plain; charset=utf-8",
                    bytes(readRawText(R.raw.clock_fonts_ofl)));
        } else if (path.equals("/about") && method.equals("GET")) {
            writeResponse(output, 200, "text/html; charset=utf-8", bytes(renderAboutPage()));
        } else if (path.equals("/terms") && method.equals("GET")) {
            writeResponse(output, 200, "text/html; charset=utf-8", bytes(renderLegalPage(
                    context.getString(R.string.terms_title), R.raw.terms)));
        } else {
            writeResponse(output, 404, "text/plain", bytes("Not Found"));
        }
    }

    /**
     * Refuses a state-changing request that a browser was tricked into sending from another site.
     *
     * <p>The decision itself lives in {@link RequestOrigin}, which is free of Android imports so
     * {@code scripts/test-host.sh} can exercise every case. This method only pulls the three headers
     * it needs out of the request.
     */
    private static String crossSiteRefusal(Map<String, String> headers) {
        return RequestOrigin.crossSiteRefusal(
                headers.get("sec-fetch-site"),
                headers.get("origin"),
                headers.getOrDefault("host", ""));
    }

    private void handleCommand(
            String method, String query, Map<String, String> headers, byte[] body,
            OutputStream output) throws IOException {
        String command;
        int percent = -1;
        String url = null;
        Boolean enabled = null;
        String value = null;
        String beacon = null;
        // Every argument by name too, for the commands with more than the fields above.
        Map<String, String> extra = new java.util.HashMap<>();

        String contentType = headers.getOrDefault("content-type", "");
        if (method.equals("POST") && contentType.contains("application/json") && body.length > 0) {
            try {
                JSONObject envelope = new JSONObject(new String(body, StandardCharsets.UTF_8));
                command = envelope.optString("command", "");
                JSONObject args = envelope.optJSONObject("args");
                if (args != null) {
                    java.util.Iterator<String> names = args.keys();
                    while (names.hasNext()) {
                        String name = names.next();
                        if (!args.isNull(name)) {
                            extra.put(name, String.valueOf(args.opt(name)));
                        }
                    }
                    percent = args.optInt("percent", -1);
                    url = args.has("url") ? args.optString("url", null) : null;
                    value = args.has("value") ? args.optString("value", null) : null;
                    // isNull covers a missing key and a JSON null, which optString would turn
                    // into the beacon id "null" (review, 2026-10-04).
                    beacon = args.isNull("beacon") ? null : args.optString("beacon", null);
                    if (args.has("enabled")) {
                        // Shared parser, shared refusal: optBoolean(..., false) coerced any
                        // non-boolean, the number 1 included, to false, so the JSON and query
                        // encodings of the same command disagreed while both answered accepted.
                        enabled = KioskCommandDispatcher.parseEnabledFlag(args.opt("enabled"));
                        if (enabled == null) {
                            writeResponse(output, 400, "application/json", bytes(
                                    "{\"status\":\"rejected\","
                                    + "\"detail\":\"enabled must be true or false\"}"));
                            return;
                        }
                    }
                }
            } catch (JSONException malformed) {
                writeResponse(output, 400, "application/json",
                        bytes("{\"status\":\"rejected\",\"detail\":\"malformed json\"}"));
                return;
            }
        } else {
            // Query first, then the form body on top. Both are accepted so that dropping GET costs
            // a scripted caller exactly one flag: `curl -X POST 'http://panel:8080/api/command?
            // cmnd=dashboard.reload'` still works untouched, while the page's own forms and fetch send
            // the same names in a urlencoded body.
            Map<String, String> params = new java.util.HashMap<>(parseQuery(query));
            String formType = headers.getOrDefault("content-type", "");
            if (formType.contains("application/x-www-form-urlencoded") && body.length > 0) {
                params.putAll(parseFormBody(headers, body));
            }
            command = params.getOrDefault("cmnd", "");
            extra.putAll(params);
            if (params.containsKey("percent")) {
                try {
                    percent = Integer.parseInt(params.get("percent"));
                } catch (NumberFormatException ignored) {
                    percent = -1;
                }
            }
            url = params.get("url");
            if (url == null && "dashboard.open_once".equals(command)) {
                // The Dashboard box's Open once button posts the whole box, whose input is the
                // stored dashboard's field. Same value, read under its own name.
                url = params.get("dashboard_url");
            }
            value = params.get("value");
            beacon = params.get("beacon");
            if (params.containsKey("enabled")) {
                // The same parser the JSON path uses. The old spelling list here treated every
                // unrecognized value as false, so ?enabled=yes silently turned things off.
                enabled = KioskCommandDispatcher.parseEnabledFlag(params.get("enabled"));
                if (enabled == null) {
                    writeResponse(output, 400, "application/json", bytes(
                            "{\"status\":\"rejected\","
                            + "\"detail\":\"enabled must be true or false\"}"));
                    return;
                }
            }
        }

        if (command.isEmpty()) {
            writeResponse(output, 400, "application/json",
                    bytes("{\"status\":\"rejected\",\"detail\":\"missing command\"}"));
            return;
        }

        KioskCommandDispatcher.Result result = kioskService.dispatch(
                command, new KioskCommandDispatcher.CommandArgs(percent, url, enabled, value,
                        beacon, extra));
        JSONObject response = new JSONObject();
        try {
            response.put("status", result.status);
            response.put("detail", result.detail);
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
        writeResponse(output, 200, "application/json", bytes(response.toString()));
    }

    /**
     * Applies one standalone control, the moment it is touched, with no Save button anywhere near it.
     *
     * <p>Each of these stands on its own (today only the overlay switch; the publish interval was
     * removed along with its presets). A control that has to agree with nothing else makes "tick a
     * box and then find a button" pure ceremony, and it is ceremony this page had already dropped
     * for the brightness slider and the auto-brightness switch. The boxes that keep their Save
     * button,
     * Dashboard, MQTT, the web admin and the escape sequences, are the ones whose fields only mean
     * something together: a host with no password, or one sequence saved before the other, is a
     * half-applied setting, and for those a deliberate save is the point.
     *
     * <p>Deliberately confined to sections whose settings are read live by whoever uses them, so no
     * controller has to be restarted to apply one. Restarting the MQTT client and rebinding the admin
     * socket on every keystroke is not something to do behind an operator's back; those sections keep
     * their button and their explicit reload.
     */
    private void handleSetting(Map<String, String> form, OutputStream output) throws IOException {
        String section = form.getOrDefault("section", "");
        if (!section.equals("behaviour")) {
            writeResponse(output, 400, "application/json",
                    bytes("{\"status\":\"rejected\",\"detail\":\"not an instantly applied "
                            + "setting\"}"));
            return;
        }
        String refusal = saveSettings(form);
        if (refusal == null) {
            // The mirror of what KioskService.dispatch does after an accepted command, and needed
            // for the same reason: these controls bypass the dispatcher, so without this the change
            // reaches storage and the tablet but not the smart home, whose switch then disagrees
            // with the panel until the next telemetry tick, up to five minutes at the longest
            // interval preset.
            KioskService.publishTelemetrySoon(context);
        }
        JSONObject response = new JSONObject();
        try {
            response.put("status", refusal == null ? "accepted" : "rejected");
            response.put("detail", refusal == null ? "saved" : refusal);
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
        writeResponse(output, refusal == null ? 200 : 400, "application/json",
                bytes(response.toString()));
    }

    /**
     * Applies one box's fields. Returns a message to show the operator, or null when the save was
     * unremarkable. A password below the minimum used to be stored happily and then refuse to bind,
     * so the page that had just been used to set it became permanently unreachable with the reason
     * only in logcat.
     */
    private String saveSettings(Map<String, String> form) {
        KioskConfig fresh = KioskConfig.load(context);
        String section = form.getOrDefault("section", "main");

        // Each box on the page posts only its own fields, and each writes only what it posts,
        // through KioskConfig.edit. Applying everything from every post is what once made a
        // partial form read as "all checkboxes unchecked".
        switch (section) {
            case "sequences":
                return saveEscapeSequences(form, fresh);
            case "dashboard": {
                String stale = staleFormRefusal(form, fresh.dashboardUrl);
                if (stale != null) {
                    return stale;
                }
                String storedShows = KioskConfig.dashboardShowsOf(context);
                if (form.containsKey("shows_baseline")
                        && (!storedShows.equals(form.get("shows_baseline"))
                        || !KioskConfig.dashboardIntegrationOf(context)
                                .equals(form.getOrDefault("integration_baseline", "")))) {
                    return "Not saved: the dashboard was changed elsewhere while this page was"
                            + " open. It now shows " + wordOf(storedShows) + ".";
                }
                String shows = form.getOrDefault("dashboard_shows", storedShows);
                String url = form.getOrDefault("dashboard_url", fresh.dashboardUrl).trim();
                if (!KioskCommandDispatcher.DASHBOARD_PAGE.equals(shows)
                        && url.equals(fresh.dashboardUrl)) {
                    // Another answer saved, the address as it was: only the choice changes.
                    return saveDashboardShows(shows, form.get("dashboard_integration"));
                }
                // The dispatcher's rules, not local ones: this box used to store what
                // dashboard.url would refuse with a reason, so a schemeless address was retried
                // every ten seconds forever, and a bad device id was stored, answered with a
                // success page, and then refused by MQTT with only a logcat line to show for it.
                String urlProblem = KioskCommandDispatcher.validateDashboardUrl(url);
                if (urlProblem != null) {
                    return "Not saved: " + urlProblem + ".";
                }
                KioskConfig.edit(context)
                        .dashboardUrl(url)
                        .apply();
                if (!url.equals(fresh.dashboardUrl)) {
                    // Saving a new URL must also navigate to it; the form path used to only save,
                    // so the panel sat on the old page until something else reloaded it, while the
                    // command path (dashboard.url) always did both. Route through the same method
                    // the dispatcher uses, so the two paths cannot disagree again.
                    kioskService.setDashboardUrl(url);
                }
                return saveDashboardShows(shows, form.get("dashboard_integration"));
            }
            case "certificates": {
                String host = form.getOrDefault("host", "");
                if (!CertificatePins.isHost(host)) {
                    return "Not done: no such host.";
                }
                if ("trust".equals(form.get("action"))) {
                    String refused = KioskService.trustCertificate(context, host,
                            form.getOrDefault("sha256", ""));
                    return refused == null ? null : "Not trusted: " + refused + ".";
                }
                if ("forget".equals(form.get("action"))) {
                    KioskService.forgetCertificate(context, host);
                    return null;
                }
                return "Not done: nothing to do.";
            }
            case "browser": {
                String stale = staleFormRefusal(form, browserBaseline(KioskConfig.browserOf(context)));
                if (stale != null) {
                    return stale;
                }
                String agent = form.getOrDefault("browser_user_agent", "");
                String problem = BrowserOptions.checkUserAgent(agent);
                Integer text = BrowserOptions.parseTextSize(form.get("browser_text_size"));
                Integer zoom = BrowserOptions.parsePageZoom(form.get("browser_page_zoom"));
                Integer back = BrowserOptions.parseBackAfter(form.get("browser_back_after"));
                String user = form.getOrDefault("browser_user", "").trim();
                if (problem == null && text == null) {
                    problem = BrowserOptions.TEXT_SIZE_RULE;
                }
                if (problem == null && zoom == null) {
                    problem = BrowserOptions.ZOOM_RULE;
                }
                if (problem == null && back == null) {
                    problem = BrowserOptions.BACK_AFTER_RULE;
                }
                if (problem == null) {
                    problem = BrowserOptions.checkSignInUser(user);
                }
                if (problem == null && isTrue(form.getOrDefault("browser_dark", "0"))
                        && !BrowserOptions.darkLookPossible(android.os.Build.VERSION.SDK_INT)) {
                    problem = BrowserOptions.DARK_RULE;
                }
                boolean camera = isTrue(form.getOrDefault("browser_page_camera", "0"));
                boolean microphone = isTrue(form.getOrDefault("browser_page_microphone", "0"));
                if (problem == null && ((camera && context.checkSelfPermission(
                        android.Manifest.permission.CAMERA)
                        != android.content.pm.PackageManager.PERMISSION_GRANTED)
                        || (microphone && context.checkSelfPermission(
                                android.Manifest.permission.RECORD_AUDIO)
                                != android.content.pm.PackageManager.PERMISSION_GRANTED))) {
                    problem = BrowserOptions.PERMISSION_RULE;
                }
                if (problem != null) {
                    return "Not saved: " + problem + ".";
                }
                KioskConfig.Editor editor = KioskConfig.edit(context)
                        .browserUserAgent(agent)
                        .browserTextSize(text)
                        .browserPageZoom(zoom)
                        .browserBackAfter(back)
                        .browserDark(isTrue(form.getOrDefault("browser_dark", "0")))
                        .browserPageCamera(camera)
                        .browserPageMicrophone(microphone)
                        .browserUser(user);
                String password = form.getOrDefault("browser_password", "");
                if (!password.isEmpty() && !user.isEmpty()) {
                    editor.browserPassword(password);
                }
                editor.apply();
                kioskService.browserOptionsSaved();
                return null;
            }
            case "panel": {
                // The panel's name: what the smart home and MQTT call it, out of the Dashboard
                // box since 2026-09-27 (the owner: the id is the panel's identity, not the page's).
                String stale = staleFormRefusal(form, fresh.deviceId);
                if (stale != null) {
                    return stale;
                }
                String deviceId = form.getOrDefault("device_id", fresh.deviceId).trim();
                String idProblem = FleetServer.nameProblem(context, deviceId);
                if (idProblem != null) {
                    // The box is called Name; "device id" is the dispatcher's word for MQTT.
                    return "Not saved: " + idProblem.replace("device id", "the name") + ".";
                }
                KioskConfig.edit(context).deviceId(deviceId).apply();
                return null;
            }
            case "mqtt": {
                String action = form.get("mqtt_action");
                if (action != null) {
                    return saveMqttTrust(action, form, fresh);
                }
                String stale = staleFormRefusal(form, mqttBaseline(fresh));
                if (stale != null) {
                    return stale;
                }
                Integer brokerPort = parsePort(form.get("mqtt_port"), fresh.mqttPort);
                if (brokerPort == null) {
                    return "Broker port must be between 1 and 65535.";
                }
                KioskConfig.Editor editor = KioskConfig.edit(context)
                        .mqttHost(form.getOrDefault("mqtt_host", fresh.mqttHost))
                        .mqttPort(brokerPort)
                        .mqttUsername(form.getOrDefault("mqtt_username", fresh.mqttUsername))
                        // The whole box posts at once: an unticked switch sends nothing.
                        .mqttTls(isTrue(form.getOrDefault("mqtt_tls", "0")));
                String mqttPassword = form.get("mqtt_password");
                if (mqttPassword != null && !mqttPassword.isEmpty()) {
                    editor.mqttPassword(mqttPassword);
                }
                editor.apply();
                return null;
            }
            case "speaker": {
                String refused = KioskService.saveNetworkSpeakerPort(context,
                        form.get("network_speaker_port"));
                return refused == null ? null : "Not saved: " + refused + ".";
            }
            case "webadmin": {
                String stale = staleFormRefusal(form, Integer.toString(fresh.httpPort));
                if (stale != null) {
                    return stale;
                }
                // Range-checked and REFUSED, not clamped, and this is the highest-severity input on
                // the page. ServerSocket.bind throws IllegalArgumentException, not IOException, for
                // a port outside 1-65535, and HttpAdminServer.start only catches IOException. So a
                // value like 99999 was persisted, answered with a success page, and then threw an
                // unchecked exception on the main thread inside restartControllers. START_STICKY
                // brings the service back, onCreate starts the controllers, and it throws again:
                // a permanent crash loop that survives reboot, takes the HOME activity down with
                // it, and cannot be undone from the panel because the panel no longer runs.
                Integer adminPort = parsePortDigits(form.get("http_port"), fresh.httpPort);
                if (adminPort == null) {
                    return "Not saved: the web admin port must be a number.";
                }
                // Floor at 1024, proven necessary on hardware; see validateAdminPort. And a port
                // another service already holds is refused too: the pre-check paints the field red
                // before anyone gets here, but a submit that ignored the colour must not be able
                // to save the admin into a bind failure. Saving the port it already serves on is
                // not a conflict, the holder is us.
                String portProblem = KioskCommandDispatcher.validateAdminPort(adminPort,
                        fresh.httpPort, fresh.fleetPort);
                if (portProblem != null) {
                    return "Not saved: " + portProblem + ".";
                }
                if (adminPort != fresh.httpPort && !SettingProbe.portFree(adminPort)) {
                    return "Not saved: port " + adminPort
                            + " is already in use by another service on this device.";
                }
                KioskConfig.Editor editor = KioskConfig.edit(context).httpPort(adminPort);
                String adminPassword = form.get("http_admin_password");
                if (adminPassword != null && !adminPassword.isEmpty()) {
                    if (adminPassword.length() < MIN_ADMIN_PASSWORD_LENGTH) {
                        return "Password unchanged: it must be at least "
                                + MIN_ADMIN_PASSWORD_LENGTH + " characters. A shorter one would "
                                + "switch this page off, and only the tablet could switch it back "
                                + "on.";
                    }
                    editor.httpAdminPassword(adminPassword);
                }
                editor.apply();
                return null;
            }
            case "fleet": {
                // The member port, by the web admin port's rules: a number, 1024-65535, never
                // the web admin's port, and free when it changes (2026-10-08).
                String stale = staleFormRefusal(form, Integer.toString(fresh.fleetPort));
                if (stale != null) {
                    return stale;
                }
                Integer memberPort = parsePortDigits(form.get("fleet_port"), fresh.fleetPort);
                if (memberPort == null) {
                    return "Not saved: the member port must be a number.";
                }
                String portProblem = KioskCommandDispatcher.validateMemberPort(memberPort,
                        fresh.fleetPort, fresh.httpPort);
                if (portProblem != null) {
                    return "Not saved: " + portProblem + ".";
                }
                if (memberPort != fresh.fleetPort && !SettingProbe.portFree(memberPort)) {
                    return "Not saved: port " + memberPort
                            + " is already in use by another service on this device.";
                }
                KioskConfig.edit(context).fleetPort(memberPort).apply();
                return null;
            }
            // Named after a box that no longer exists, and kept anyway: it is the wire name
            // POST /api/setting has always accepted. It covers the stats-overlay switch, the
            // display and Ambient keys, and since the sensors the sensor switches, their
            // options, the automation switches and the beacon names. See settingScript.
            case "behaviour":
                // Presence used to carry the meaning, because an unchecked box sends nothing and
                // the whole box was posted at once. These controls now post one at a time as they
                // are touched, so presence would read every post as "the others were just
                // cleared". The value carries the meaning instead, and an absent key is simply not
                // being set. No stale-form guard either: the posted value is what the operator
                // just touched, not what the page remembered. One key per request, and each key
                // applies on its own, in this order: a hand-built request carrying several keys
                // gets the earlier ones applied and the first refusal reported. Ambient
                // keys are the exception, checked as a set before any of them is stored.
                for (Map.Entry<String, String> entry : form.entrySet()) {
                    if (isSwitchKey(entry.getKey())
                            && KioskCommandDispatcher.parseEnabledFlag(entry.getValue()) == null) {
                        return "Not saved: " + entry.getKey() + " must be 1 or 0 (true or false,"
                                + " on or off).";
                    }
                }
                for (String key : form.keySet()) {
                    if (key.startsWith("sensor_") && !key.startsWith("sensor_option_")
                            && Sensors.byId(key.substring("sensor_".length())) == null) {
                        return "no sensor is called " + key.substring("sensor_".length());
                    }
                }
                for (Sensors.Def def : Sensors.ALL) {
                    String key = "sensor_" + def.id;
                    if (form.containsKey(key)) {
                        String problem = kioskService.setSensorEnabled(def.id, isTrue(form.get(key)));
                        if (problem != null) {
                            return problem;
                        }
                    }
                }
                for (Map.Entry<String, String> entry : form.entrySet()) {
                    if (entry.getKey().startsWith("sensor_option_")) {
                        String problem = saveSensorOption(
                                entry.getKey().substring("sensor_option_".length()),
                                entry.getValue());
                        if (problem != null) {
                            return problem;
                        }
                    } else if (entry.getKey().equals("ambient_album")) {
                        String problem = kioskService.setScreensaverPlaylist(entry.getValue());
                        if (problem != null) {
                            return "Not changed: " + problem + ".";
                        }
                    } else if (entry.getKey().startsWith("integration_")) {
                        String problem = IntegrationSettings.setEnabled(context,
                                entry.getKey().substring("integration_".length()),
                                isTrue(entry.getValue()));
                        if (problem != null) {
                            return "Not changed: " + problem + ".";
                        }
                    } else if (entry.getKey().startsWith("automation_")) {
                        String problem = kioskService.setAutomationEnabled(
                                entry.getKey().substring("automation_".length()),
                                isTrue(entry.getValue()));
                        if (problem != null) {
                            return problem;
                        }
                    }
                }
                for (String key : form.keySet()) {
                    if (key.equals("fleet_follows")) {
                        String refused = FleetServer.setFollowsFleet(context, isTrue(form.get(key)));
                        if (refused != null) {
                            return "Not changed: " + refused + ".";
                        }
                    } else if (key.startsWith("fleet_group_")) {
                        String id = key.substring("fleet_group_".length());
                        java.util.Map<String, Object> list = KioskConfig.fleetDocumentOf(context);
                        java.util.List<String> groups = new java.util.ArrayList<>(
                                FleetDocument.memberGroups(list, FleetDocument.memberByKey(list,
                                        KioskRuntimeState.fleetKey())));
                        groups.remove(id);
                        if (isTrue(form.get(key))) {
                            groups.add(id);
                        }
                        String refused = FleetServer.setMyGroups(context, groups);
                        if (refused != null) {
                            return "Not changed: " + refused + ".";
                        }
                    } else if (key.startsWith("alert_")) {
                        // The fleet's alerts, the same keys the Fleet page shares.
                        String refused = KioskConfig.saveAlertSetting(context, key, form.get(key));
                        if (refused != null) {
                            return "Not saved: " + refused + ".";
                        }
                        KioskService.alertsChanged(context);
                    }
                }
                if (form.containsKey("volume_buttons_disabled")) {
                    KioskConfig.edit(context)
                            .volumeButtonsDisabled(isTrue(form.get("volume_buttons_disabled")))
                            .apply();
                }
                // The status light, by the commands' own check and the same way to the frame; a
                // colour chosen while the light is off is kept for when it comes on.
                if (form.containsKey("status_light") || form.containsKey("status_light_color")) {
                    Integer color = null;
                    if (form.containsKey("status_light_color")) {
                        color = StatusLight.parseColor(form.get("status_light_color"));
                        if (color == null) {
                            return "Not saved: the " + StatusLight.COLOR_RULE + ".";
                        }
                    }
                    kioskService.setStatusLight(form.containsKey("status_light")
                            ? isTrue(form.get("status_light"))
                            : KioskConfig.statusLightOf(context).on, color, -1);
                }
                if (form.containsKey("network_speaker")) {
                    String refused = KioskService.setNetworkSpeaker(context,
                            isTrue(form.get("network_speaker")));
                    if (refused != null) {
                        return "Not switched on: " + refused + ".";
                    }
                }
                if (form.containsKey("stats_overlay")) {
                    KioskConfig.edit(context)
                            .statsOverlay(isTrue(form.get("stats_overlay")))
                            .apply();
                }
                if (form.containsKey("http_certificate")) {
                    String value = form.get("http_certificate").trim();
                    String problem = KioskConfig.checkHttpCertificate(context, value);
                    if (problem != null) {
                        return "Not saved: " + problem + ".";
                    }
                    if (!value.equals(KioskConfig.httpCertificateOf(context))) {
                        KioskConfig.edit(context).httpCertificate(value).apply();
                        // This server serves it from the next connection; the page reloads
                        // itself onto it (admin_setting.js).
                        reloadCertificate();
                    }
                }
                if (form.containsKey("device_id")) {
                    // The panel's name stores when its box lets go of the focus, as on the panel
                    // since 2026-09-27; the Save button stays only for a browser without
                    // scripting (review, 2026-10-01).
                    String deviceId = form.get("device_id").trim();
                    String idProblem = FleetServer.nameProblem(context, deviceId);
                    if (idProblem != null) {
                        return "Not saved: " + idProblem.replace("device id", "the name") + ".";
                    }
                    if (!deviceId.equals(KioskConfig.load(context).deviceId)) {
                        KioskConfig.edit(context).deviceId(deviceId).apply();
                        KioskService.reloadConfiguration(context);
                    }
                }
                if (form.containsKey("display_off_method")) {
                    String method = form.get("display_off_method");
                    if (!DisplayOffPolicy.isMethod(method)) {
                        return "Display off method must be auto, sleep or film.";
                    }
                    if (DisplayOffPolicy.SLEEP.equals(method)
                            && !KioskService.isDeviceOwner(context)) {
                        return "A real screen-off needs the device-owner install.";
                    }
                    DarkWatch.setMethod(context, method);
                }
                return saveScreensaverSettings(form);
            default:
                Log.i(TAG, "Ignoring a settings post with no known section");
                return null;
        }
    }

    /**
     * The stale-form check for the Save-button boxes, generalising the escape-sequences baseline:
     * a page keeps the values that were current when it loaded, and submitting it after another
     * surface changed one of those fields would silently revert that change. Refused with an
     * explanation rather than ignored, because the response re-renders the page and the operator
     * sees the current values immediately. A missing baseline is treated as stale, since the only
     * pages without one are older than this check.
     */
    private static String staleFormRefusal(Map<String, String> form, String currentBaseline) {
        String baseline = form.get("baseline");
        if (baseline != null && baseline.equals(currentBaseline)) {
            return null;
        }
        return "Not saved: these settings were changed elsewhere after this page loaded. "
                + "The page now shows the current values; please re-apply your edit.";
    }

    /**
     * Query first, then a urlencoded body on top, the same shape {@code /api/command} accepts, so
     * a scripted caller can use either and the page's own fetch can use the body.
     */
    private static Map<String, String> checkParams(
            String query, Map<String, String> headers, byte[] body) {
        Map<String, String> params = new java.util.HashMap<>(parseQuery(query));
        if (headers.getOrDefault("content-type", "").contains("application/x-www-form-urlencoded")
                && body.length > 0) {
            params.putAll(parseFormBody(headers, body));
        }
        return params;
    }

    /**
     * The JSON face of {@link SettingProbe}, for the page's green/red pre-check
     * (admin_check.js). The probing itself is shared with the tablet so the two surfaces can
     * never disagree about what "reachable" means.
     */
    private String checkValue(Map<String, String> params) {
        String kind = params.getOrDefault("kind", "");
        String value = params.getOrDefault("value", "").trim();
        SettingProbe.Verdict verdict;
        switch (kind) {
            case "http_port":
                verdict = SettingProbe.adminPort(value, boundPort,
                        KioskConfig.fleetPortOf(context));
                break;
            case "fleet_port":
                verdict = SettingProbe.memberPort(value, KioskRuntimeState.fleetListening()
                        ? KioskRuntimeState.fleetPort() : -1, boundPort);
                break;
            case "dashboard_url":
                // Probed as a panel loads it, its placeholders filled.
                verdict = SettingProbe.dashboardUrl(FleetProfile.fillPlaceholders(context, value));
                break;
            case "mqtt_host":
                verdict = SettingProbe.mqttHost(value,
                        parseIntOrDefault(params.get("port"), 1883));
                break;
            default:
                verdict = new SettingProbe.Verdict(false, "unknown check kind");
        }
        org.json.JSONObject result = new org.json.JSONObject();
        try {
            result.put("ok", verdict.ok);
            result.put("detail", verdict.detail);
            result.put("reason", verdict.reason);
        } catch (org.json.JSONException impossible) {
            // Two puts of primitives on a fresh object cannot fail; satisfy the checked signature.
        }
        return result.toString();
    }

    /** The spellings a browser form, a shell or a hand-written client is likely to send. */
    private static boolean isTrue(String value) {
        return Boolean.TRUE.equals(KioskCommandDispatcher.parseEnabledFlag(value));
    }

    /**
     * The on/off keys of POST /api/setting's behaviour section. Their value is checked with the
     * commands' parser before any is stored: isTrue read every other word as "off" and the
     * answer said saved (pre-release device pass, 2026-10-22).
     */
    private static boolean isSwitchKey(String key) {
        return (key.startsWith("sensor_") && !key.startsWith("sensor_option_"))
                || key.startsWith("automation_") || key.startsWith("integration_")
                || key.startsWith("fleet_group_")
                || key.equals("fleet_follows") || key.equals("volume_buttons_disabled")
                || key.equals("status_light") || key.equals("stats_overlay")
                || key.equals("ambient_shuffle") || key.equals("ambient_one_per_start")
                || key.equals("ambient_credit") || key.equals("network_speaker");
    }

    /**
     * Escape sequences are saved only from their own form, and only when this page was rendered
     * from the values still stored on the device.
     *
     * <p>A page left open in a browser keeps the combination that was current when it loaded.
     * Submitting it later silently reverted a combination recorded on the tablet in the meantime,
     * which is exactly what happened twice on 2026-08-17. A missing baseline is treated as stale
     * rather than current, because the only pages without one are older than this check.
     */
    private String saveEscapeSequences(Map<String, String> form, KioskConfig fresh) {
        String baseline = form.get("sequence_baseline");
        String current = fresh.settingsSequence + "|" + fresh.launcherSequence;
        if (baseline == null || !baseline.equals(current)) {
            // Refused out loud, like the other three boxes. This used to be a silent Log.i, so
            // the one form whose stale submit was actually seen twice (2026-08-17) was also the
            // one that looked saved when it was not.
            return "Not saved: the sequences were changed elsewhere after this page loaded. "
                    + "The page now shows the current values; please re-apply your edit.";
        }

        // Resolve both candidates first, then compare once, in both directions. The old check
        // only guarded the launcher field against the settings field, so the settings gesture
        // could be set equal to the launcher gesture and become unreachable: on equal sequences
        // the tap handler resolves to the launcher, and nothing on screen explains why settings
        // stopped opening. The tablet recorder refuses the same collision (KioskActivity's
        // recorder); this is the web admin's half of that rule.
        String settingsSequence = fresh.settingsSequence;
        String posted = form.get("settings_sequence");
        if (posted != null && EscapeSequence.isValid(EscapeSequence.parse(posted))) {
            settingsSequence = EscapeSequence.format(EscapeSequence.parse(posted));
        }
        String launcherSequence = fresh.launcherSequence;
        posted = form.get("launcher_sequence");
        if (posted != null && EscapeSequence.isValid(EscapeSequence.parse(posted))) {
            launcherSequence = EscapeSequence.format(EscapeSequence.parse(posted));
        }
        // Both unset happens on a device still in its first-start wizard (there is no default
        // any more); the collision message below would be nonsense for two empty fields.
        if (settingsSequence.isEmpty() && launcherSequence.isEmpty()) {
            return "Not saved: neither field holds a valid combination. Between "
                    + EscapeSequence.MIN_LENGTH + " and " + EscapeSequence.MAX_LENGTH
                    + " corner taps, TL, TR, BL, BR separated by commas.";
        }
        if (settingsSequence.equals(launcherSequence)) {
            return "Not saved: Open settings and Go to home screen must be different"
                    + " combinations, or Open settings cannot be reached.";
        }
        // The PIN rides on this box because it stands behind these two gestures. Judged before
        // anything is written, so a refused PIN saves nothing at all.
        String pin = form.get("escape_pin");
        boolean clearPin = "1".equals(form.get("escape_pin_clear"));
        if (!clearPin && pin != null && !pin.isEmpty()) {
            String pinProblem = EscapePin.validationProblem(pin);
            if (pinProblem != null) {
                return "Not saved: " + pinProblem + ".";
            }
        }
        fresh.settingsSequence = settingsSequence;
        fresh.launcherSequence = launcherSequence;
        fresh.saveEscapeSequences(context);
        if (clearPin) {
            KioskConfig.setEscapePinHash(context, null);
        } else if (pin != null && !pin.isEmpty()) {
            KioskConfig.setEscapePinHash(context, EscapePin.hash(pin));
        }
        return null;
    }

    private boolean isAuthorized(String authorizationHeader) {
        // Explicit, not merely implied by start() refusing to bind without a password. Left to the
        // comparison below, an empty expected password matched an empty supplied one and
        // authorized everyone, unreachable today, but only by an invariant enforced in a
        // different method, which is one refactor away from an authentication bypass.
        if (boundAdminPassword.isEmpty()) {
            return false;
        }
        if (authorizationHeader == null || !authorizationHeader.startsWith("Basic ")) {
            return false;
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(authorizationHeader.substring(6).trim());
        } catch (IllegalArgumentException invalid) {
            return false;
        }
        String credentials = new String(decoded, StandardCharsets.UTF_8);
        int colon = credentials.indexOf(':');
        String password = colon >= 0 ? credentials.substring(colon + 1) : credentials;
        byte[] expected = boundAdminPassword.getBytes(StandardCharsets.UTF_8);
        byte[] actual = password.getBytes(StandardCharsets.UTF_8);
        return expected.length == actual.length && MessageDigest.isEqual(expected, actual);
    }

    private String buildSettingsPage() {
        return buildSettingsPage(null, "");
    }


    /**
     * The head, app bar and theme picker every page on this server shares.
     *
     * <p>One method rather than one copy per page, so a page added later cannot come out looking
     * like a different application. The subtitle is what each page has to say for itself: the
     * panel's id on the settings page, nothing on a page below it.
     */
    private String pageStart(String title, String subtitle) {
        return pageStart(title, subtitle, null);
    }


    /**
     * The settings with one section open, where a page under that section goes back to, as the
     * panel's arrow comes back to the section it left (review, 2026-10-07: with nothing
     * remembered between page loads, a bare "/" opened them all closed); admin_menu.js opens
     * the section the address names, as it does after a Save.
     */
    private static String settingsAt(String section) {
        return "/#box-" + section;
    }

    /**
     * The same, with the way back as the arrow at the left of the app bar, where every Material
     * screen has it and once only: the Back buttons at the head and foot of a page went with the
     * redesign of 2026-09-23.
     */
    private String pageStart(String title, String subtitle, String backTo) {
        // A page below the settings page says its name and nothing else: the panel's id, its
        // address and its load belong where somebody is configuring the panel, not on a page
        // about one feature of it (2026-09-11, C15).
        String titles = "<div class=\"titles\"><h1>" + escapeHtml(title) + "</h1>"
                + (subtitle == null ? "" : "<p class=\"sub\">" + escapeHtml(subtitle) + "</p>")
                + "</div>";
        return pageStartWith(titles, backTo, subtitle != null);
    }


    /**
     * The same shell around a title block a page has built for itself: the playlist's own name.
     *
     * <p>The {@code js} class on the root element is set by an inline script this server
     * emits, in the head so it is there before the first paint: the list-detail layout of the
     * settings page needs scripting to open a section from its list, so the stylesheet applies it
     * only where scripting runs and every other browser gets the accordion, which works with none.
     */
    private String pageStartWith(String titles, String backTo, boolean chip) {
        return "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>Muralis admin</title><style>" + pageCss + "</style>"
                + "<script>document.documentElement.className+=' js';</script></head>"
                + "<body><main><header class=\"appbar\">"
                + (backTo == null ? "" : iconLink(backTo, "back", "Back"))
                + titles
                + (chip ? statusChip() : "")
                // The settings page's own controls, as on the panel: day and night, the commands.
                + (chip && backTo == null ? themeToggle() + commandsMenu() : "")
                + "</header>";
    }

    /**
     * Material's glyphs, inline so they take the text colour, one path each. Names rather than
     * markup at the call sites, so a glyph is drawn the same wherever it appears and the panel's
     * vector drawables can carry the same shapes.
     */
    private static final Map<String, String> GLYPHS = glyphs();

    private static Map<String, String> glyphs() {
        Map<String, String> paths = new HashMap<>();
        paths.put("back", "<path d=\"M19 12H5M12 19l-7-7 7-7\"/>");
        paths.put("pencil", "<path d=\"M12 20h9M16.5 3.5a2.1 2.1 0 013 3L7 19l-4 1 1-4z\"/>");
        paths.put("trash", "<path d=\"M4 7h16M10 11v6M14 11v6M6 7l1 13h10l1-13M9 7V4h6v3\"/>");
        paths.put("minus", "<circle cx=\"12\" cy=\"12\" r=\"9\"/><path d=\"M8 12h8\"/>");
        paths.put("check", "<path d=\"M5 12l5 5L20 7\"/>");
        // Material's drag_indicator, the six dots a person grabs to move a row.
        paths.put("drag", "<path d=\"M9 5h.01M15 5h.01M9 12h.01M15 12h.01M9 19h.01M15 19h.01\"/>");
        paths.put("shuffle", "<path d=\"M16 3h5v5M4 20L21 3M21 16v5h-5M15 15l6 6M4 4l5 5\"/>");
        // A picture's credit shown, or switched off: Material's visibility pair.
        paths.put("eye", "<path d=\"M2 12s3.6-7 10-7 10 7 10 7-3.6 7-10 7S2 12 2 12z\"/>"
                + "<circle cx=\"12\" cy=\"12\" r=\"3\"/>");
        paths.put("eye_off", "<path d=\"M2 12s3.6-7 10-7 10 7 10 7-3.6 7-10 7S2 12 2 12z\"/>"
                + "<circle cx=\"12\" cy=\"12\" r=\"3\"/><path d=\"M4 4l16 16\"/>");
        paths.put("prev", "<path d=\"M15 6l-6 6 6 6\"/>");
        paths.put("next", "<path d=\"M9 6l6 6-6 6\"/>");
        paths.put("plus", "<path d=\"M12 5v14M5 12h14\"/>");
        paths.put("sensors", "<circle cx=\"12\" cy=\"12\" r=\"1\"/><path d=\"M7.8 16.2a6 6 0 010-8.4M16.2 7.8a6 6 0 010 8.4M5 19a10 10 0 010-14M19 5a10 10 0 010 14\"/>");
        paths.put("panel", "<rect x=\"4\" y=\"2\" width=\"16\" height=\"20\" rx=\"2\"/><path d=\"M9 18h6\"/>");
        paths.put("tag", "<path d=\"M3 12V4h8l10 10-8 8z\"/><circle cx=\"7.5\" cy=\"8.5\" r=\"1.5\"/>");
        // The sensors' glyphs, the same paths the panel's drawables carry.
        paths.put("proximity", "<circle cx=\"12\" cy=\"10\" r=\"2.5\"/><path d=\"M7.5 17.5a4.5 3.5 0 019 0M3.13 8.6A9.5 9.5 0 018.6 3.13M15.4 3.13A9.5 9.5 0 0120.87 8.6M20.87 15.4A9.5 9.5 0 0115.4 20.87M8.6 20.87A9.5 9.5 0 013.13 15.4\"/>");
        paths.put("light", "<circle cx=\"12\" cy=\"12\" r=\"4\"/><path d=\"M12 2v2M12 20v2M2 12h2M20 12h2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4\"/>");
        paths.put("movement", "<path d=\"M3 12h3l3-7 4 14 3-7h5\"/>");
        paths.put("bluetooth", "<path d=\"M7 7l10 10-5 5V2l5 5L7 17\"/>");
        paths.put("nfc", "<rect x=\"3\" y=\"3\" width=\"18\" height=\"18\" rx=\"3\"/><path d=\"M8 8v8M12 8v8M16 8v8\"/>");
        paths.put("audio", "<path d=\"M4 10v4h4l5 4V6L8 10zM16 9a4 4 0 010 6M18.5 6.5a8 8 0 010 11\"/>");
        paths.put("camera", "<path d=\"M4 8h4l2-3h4l2 3h4v11H4z\"/><circle cx=\"12\" cy=\"13\" r=\"3.5\"/>");
        paths.put("microphone", "<rect x=\"9\" y=\"3\" width=\"6\" height=\"11\" rx=\"3\"/><path d=\"M5 11a7 7 0 0014 0M12 18v3M9 21h6\"/>");
        paths.put("pressure", "<circle cx=\"12\" cy=\"13\" r=\"8\"/><path d=\"M12 13l4-4M12 5V3\"/>");
        paths.put("temperature", "<path d=\"M10 4a2 2 0 014 0v9.5a4 4 0 11-4 0z\"/>");
        paths.put("humidity", "<path d=\"M12 3s6 7 6 11a6 6 0 01-12 0c0-4 6-11 6-11z\"/>");
        paths.put("display", "<rect x=\"3\" y=\"4\" width=\"18\" height=\"13\" rx=\"2\"/><path d=\"M8 21h8\"/>");
        paths.put("automations", "<path d=\"M17 21H7a3 3 0 01-3-3v-1h10v1a3 3 0 006 0V5a2 2 0 00-2-2H9a2 2 0 00-2 2v12M11 8h5M11 12h5\"/>");
        paths.put("asleep_off", "<circle cx=\"12\" cy=\"12\" r=\"1\"/><path d=\"M7.8 16.2a6 6 0 010-8.4M16.2 7.8a6 6 0 010 8.4M5 19a10 10 0 010-14M19 5a10 10 0 010 14M3 3l18 18\"/>");
        paths.put("ambient", "<rect x=\"3\" y=\"5\" width=\"18\" height=\"14\" rx=\"2\"/><path d=\"M3 15l5-5 4 4 3-3 6 6\"/><circle cx=\"16\" cy=\"9\" r=\"1.5\"/>");
        paths.put("battery", "<rect x=\"3\" y=\"7\" width=\"16\" height=\"10\" rx=\"2\"/><path d=\"M21 11v2M6 10v4M9 10v4\"/>");
        paths.put("power", "<path d=\"M9 3v5M15 3v5M6 8h12v4a6 6 0 01-12 0zM12 18v3\"/>");
        paths.put("network", "<path d=\"M2 9a15 15 0 0120 0M5.5 12.5a10 10 0 0113 0M9 16a5 5 0 016 0\"/><circle cx=\"12\" cy=\"19\" r=\"1\"/>");
        paths.put("memory", "<rect x=\"5\" y=\"5\" width=\"14\" height=\"14\" rx=\"2\"/><rect x=\"9\" y=\"9\" width=\"6\" height=\"6\"/><path d=\"M9 2v3M15 2v3M9 19v3M15 19v3M2 9h3M2 15h3M19 9h3M19 15h3\"/>");
        paths.put("processor", "<path d=\"M4 20V10M10 20V4M16 20v-7M22 20H2\"/>");
        paths.put("down", "<path d=\"M6 9l6 6 6-6\"/>");
        paths.put("list", "<path d=\"M4 6h16M4 12h16M4 18h16\"/>");
        paths.put("details", "<rect x=\"3\" y=\"4\" width=\"5\" height=\"5\" rx=\"1\"/>"
                + "<path d=\"M11 6.5h10\"/><rect x=\"3\" y=\"14\" width=\"5\" height=\"5\" rx=\"1\"/>"
                + "<path d=\"M11 16.5h10M11 19.5h6\"/>");
        StringBuilder small = new StringBuilder();
        for (String y : new String[] {"3", "9.5", "16"}) {
            for (String x : new String[] {"3", "9.5", "16"}) {
                small.append("<rect x=\"").append(x).append("\" y=\"").append(y)
                        .append("\" width=\"5\" height=\"5\" rx=\"1\"/>");
            }
        }
        paths.put("small", small.toString());
        paths.put("big", "<rect x=\"3\" y=\"3\" width=\"8\" height=\"8\" rx=\"1.5\"/>"
                + "<rect x=\"13\" y=\"3\" width=\"8\" height=\"8\" rx=\"1.5\"/>"
                + "<rect x=\"3\" y=\"13\" width=\"8\" height=\"8\" rx=\"1.5\"/>"
                + "<rect x=\"13\" y=\"13\" width=\"8\" height=\"8\" rx=\"1.5\"/>");
        paths.put("upload", "<path d=\"M12 16V4M6 10l6-6 6 6M4 20h16\"/>");
        paths.put("folder", "<path d=\"M3 7h6l2 2h10v10H3z\"/>");
        paths.put("storage", "<rect x=\"3\" y=\"6\" width=\"18\" height=\"14\" rx=\"2\"/>"
                + "<path d=\"M3 10h18\"/>");
        // A dashboard: four tiles, two tall and two short, Material's own arrangement. The
        // monitor on a stand it replaces drew hardware, which no wall panel has
        // (2026-09-24).
        paths.put("dash", "<path d=\"M4 4h6v7H4zM14 4h6v4h-6zM4 15h6v5H4zM14 12h6v8h-6z\"/>");
        // A hub: four things joined to one in the middle, which is what a broker is. The
        // broadcast arcs it replaces were read as Wi-Fi by everybody who saw them
        // (2026-09-23).
        paths.put("mqtt", "<circle cx=\"12\" cy=\"12\" r=\"3\"/><circle cx=\"5\" cy=\"5\" r=\"1.5\"/>"
                + "<circle cx=\"19\" cy=\"5\" r=\"1.5\"/><circle cx=\"5\" cy=\"19\" r=\"1.5\"/>"
                + "<circle cx=\"19\" cy=\"19\" r=\"1.5\"/>"
                + "<path d=\"M9.9 9.9L6.2 6.2M14.1 9.9l3.7-3.7M9.9 14.1l-3.7 3.7M14.1 14.1l3.7 3.7\"/>");
        paths.put("web", "<circle cx=\"12\" cy=\"12\" r=\"9\"/>"
                + "<path d=\"M3 12h18M12 3a14 14 0 010 18M12 3a14 14 0 000 18\"/>");
        // Three panels that know each other, the panel's ic_fleet.
        paths.put("fleet", "<circle cx=\"5\" cy=\"7\" r=\"2.5\"/><circle cx=\"19\" cy=\"7\" r=\"2.5\"/>"
                + "<circle cx=\"12\" cy=\"18\" r=\"2.5\"/>"
                + "<path d=\"M7.5 7h9M6.3 9.2l4.5 6.6M17.7 9.2l-4.5 6.6\"/>");
        paths.put("esc", "<rect x=\"3\" y=\"3\" width=\"18\" height=\"18\" rx=\"3\"/>"
                + "<path d=\"M7 7h.01M17 7h.01M7 17h.01M17 17h.01\"/>");
        paths.put("disp", "<circle cx=\"12\" cy=\"12\" r=\"4\"/><path d=\"M12 2v2M12 20v2M2 12h2"
                + "M20 12h2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4\"/>");
        paths.put("saver", "<rect x=\"3\" y=\"5\" width=\"18\" height=\"14\" rx=\"2\"/>"
                + "<path d=\"M3 15l5-5 4 4 3-3 6 6\"/><circle cx=\"16\" cy=\"9\" r=\"1.5\"/>");
        // Cameras, a video camera; Integrations, a puzzle piece; the overflow menu's three dots:
        // the panel's ic_cameras, ic_integrations and Material's more_vert.
        paths.put("cameras", "<path d=\"M4 7h10a1 1 0 011 1v8a1 1 0 01-1 1H4a1 1 0 01-1-1V8a1 1 0 011-1zM15 11l6-3.5v9L15 13z\"/>");
        paths.put("integrations", "<path d=\"M10 5V4a2 2 0 014 0v1h4a1 1 0 011 1v4h1a2 2 0 010 4h-1v4a1 1 0 01-1 1H6a1 1 0 01-1-1v-4h1a2 2 0 000-4H5V6a1 1 0 011-1z\"/>");
        paths.put("more", "<path d=\"M12 5h.01M12 12h.01M12 19h.01\" stroke-width=\"3\"/>");
        // An item's own options, the panel's ic_sensor_tune.
        paths.put("tune", "<path d=\"M4 6h9M17 6h3M4 12h3M11 12h9M4 18h11M19 18h1M13 6a2 2 0 1 0 4 0a2 2 0 1 0-4 0M7 12a2 2 0 1 0 4 0a2 2 0 1 0-4 0M15 18a2 2 0 1 0 4 0a2 2 0 1 0-4 0\"/>");
        paths.put("stats", "<path d=\"M4 20V10M10 20V4M16 20v-7M22 20H2\"/>");
        // Close a dialog or a selection: Material's close.
        paths.put("close", "<path d=\"M6 6l12 12M18 6L6 18\"/>");
        paths.put("bolt", "<path d=\"M13 2L4 14h7l-1 8 9-12h-7z\"/>");
        // An automation's three parts: When the bolt above, Then Material's arrow_forward, If
        // its filter_alt funnel.
        paths.put("then", "<path d=\"M5 12h14M13 5l7 7-7 7\"/>");
        paths.put("if", "<path d=\"M4 5h16l-6 7.5V19l-4-2v-4.5z\"/>");
        // An automation's action moved in its list, and a Wait: Material's arrow_upward,
        // arrow_downward and timer.
        paths.put("arrow_up", "<path d=\"M12 19V5M5 12l7-7 7 7\"/>");
        paths.put("arrow_down", "<path d=\"M12 5v14M19 12l-7 7-7-7\"/>");
        paths.put("timer", "<circle cx=\"12\" cy=\"13\" r=\"8\"/><path d=\"M12 13V9M9 2h6M18 7l1.5-1.5\"/>");
        paths.put("info", "<circle cx=\"12\" cy=\"12\" r=\"9\"/><path d=\"M12 11v5M12 8h.01\"/>");
        paths.put("reboot", "<path d=\"M4 4v6h6M20 20v-6h-6M20 9A8 8 0 006.3 5.3L4 10"
                + "M4 15a8 8 0 0013.7 3.7L20 14\"/>");
        paths.put("reload", "<path d=\"M21 12a9 9 0 11-3-6.7M21 3v6h-6\"/>");
        // The log block's row: Material's search, pause, play, content_copy and block.
        paths.put("search", "<circle cx=\"11\" cy=\"11\" r=\"7\"/><path d=\"M20 20l-4-4\"/>");
        paths.put("pause", "<path d=\"M8 5v14M16 5v14\"/>");
        paths.put("play", "<path d=\"M7 5l12 7-12 7z\"/>");
        paths.put("copy", "<rect x=\"9\" y=\"9\" width=\"11\" height=\"11\" rx=\"1.5\"/>"
                + "<path d=\"M15 9V5.5A1.5 1.5 0 0013.5 4h-8A1.5 1.5 0 004 5.5v8A1.5 1.5 0 005.5 15H9\"/>");
        paths.put("clear", "<circle cx=\"12\" cy=\"12\" r=\"9\"/><path d=\"M5.6 5.6l12.8 12.8\"/>");
        paths.put("download", "<path d=\"M12 4v11M7 10l5 5 5-5M5 20h14\"/>");
        paths.put("power", "<path d=\"M12 3v9M18.4 6.6a8 8 0 11-12.8 0\"/>");
        paths.put("open", "<path d=\"M14 4h6v6M20 4l-9 9M18 14v5a1 1 0 01-1 1H5a1 1 0 01-1-1V7"
                + "a1 1 0 011-1h5\"/>");
        // The Folders head's one button: chevrons closing on each other, or opening away.
        paths.put("unfold_less", "<path d=\"M7 4l5 5 5-5M7 20l5-5 5 5\"/>");
        // Material's fullscreen, four corners: what opens a QR at the size of the window.
        paths.put("fullscreen", "<path d=\"M4 9V4h5M15 4h5v5M20 15v5h-5M9 20H4v-5\"/>");
        paths.put("unfold_more", "<path d=\"M7 9l5-5 5 5M7 15l5 5 5-5\"/>");
        paths.put("sun", "<path fill=\"currentColor\" stroke=\"none\" d=\"M12,7c-2.76,0 -5,2.24 -5,5s2.24,5 5,5 5,-2.24 5,-5 -2.24,-5 -5,-5zM2,13h2c0.55,0 1,-0.45 1,-1s-0.45,-1 -1,-1L2,11c-0.55,0 -1,0.45 -1,1s0.45,1 1,1zM20,13h2c0.55,0 1,-0.45 1,-1s-0.45,-1 -1,-1h-2c-0.55,0 -1,0.45 -1,1s0.45,1 1,1zM11,2v2c0,0.55 0.45,1 1,1s1,-0.45 1,-1L13,2c0,-0.55 -0.45,-1 -1,-1s-1,0.45 -1,1zM11,20v2c0,0.55 0.45,1 1,1s1,-0.45 1,-1v-2c0,-0.55 -0.45,-1 -1,-1s-1,0.45 -1,1zM5.99,4.58c-0.39,-0.39 -1.03,-0.39 -1.41,0 -0.39,0.39 -0.39,1.03 0,1.41l1.06,1.06c0.39,0.39 1.03,0.39 1.41,0s0.39,-1.03 0,-1.41L5.99,4.58zM18.36,16.95c-0.39,-0.39 -1.03,-0.39 -1.41,0 -0.39,0.39 -0.39,1.03 0,1.41l1.06,1.06c0.39,0.39 1.03,0.39 1.41,0 0.39,-0.39 0.39,-1.03 0,-1.41l-1.06,-1.06zM19.42,5.99c0.39,-0.39 0.39,-1.03 0,-1.41 -0.39,-0.39 -1.03,-0.39 -1.41,0l-1.06,1.06c-0.39,0.39 -0.39,1.03 0,1.41s1.03,0.39 1.41,0l1.06,-1.06zM7.05,18.36c0.39,-0.39 0.39,-1.03 0,-1.41 -0.39,-0.39 -1.03,-0.39 -1.41,0l-1.06,1.06c-0.39,0.39 -0.39,1.03 0,1.41s1.03,0.39 1.41,0l1.06,-1.06z\"/>");
        // Material's brightness_medium, the half-lit sun for following the device.
        paths.put("auto", "<path fill=\"currentColor\" stroke=\"none\" d=\"M20 15.31L23.31 12 20 8.69V4h-4.69L12 .69 8.69 4H4v4.69L.69 12 4 15.31V20h4.69L12 23.31 15.31 20H20v-4.69zM12 18V6c3.31 0 6 2.69 6 6s-2.69 6-6 6z\"/>");
        paths.put("moon", "<path fill=\"currentColor\" stroke=\"none\" d=\"M12,3c-4.97,0 -9,4.03 -9,9s4.03,9 9,9 9,-4.03 9,-9c0,-0.46 -0.04,-0.92 -0.1,-1.36 -0.98,1.37 -2.58,2.26 -4.4,2.26 -2.98,0 -5.4,-2.42 -5.4,-5.4 0,-1.81 0.89,-3.42 2.26,-4.4C12.92,3.04 12.46,3 12,3z\"/>");
        paths.put("theme", "<circle cx=\"12\" cy=\"12\" r=\"9\"/>"
                + "<path d=\"M12 3a9 9 0 010 18z\" fill=\"currentColor\"/>");
        return Collections.unmodifiableMap(paths);
    }

    private static String glyph(String name) {
        return "<svg viewBox=\"0 0 24 24\" aria-hidden=\"true\">" + GLYPHS.get(name) + "</svg>";
    }

    /**
     * "Show full screen" with its glyph under a QR: admin_qr.js opens the image named by {@code imageId} at
     * the size of the window, with the text of {@code leftId}, the time left, when there is one.
     */
    private static String fullScreenButton(String imageId, String leftId) {
        return "<div class=\"actions\"><button type=\"button\" class=\"text\" data-qr-full=\""
                + imageId + "\"" + (leftId == null ? "" : " data-qr-left=\"" + leftId + "\"") + ">"
                + glyph("fullscreen") + "Show full screen</button></div>";
    }

    /** A 40 px icon button that submits its form; the label is its tooltip and its spoken name. */
    private static String iconButton(String glyphName, String label, String extraClass) {
        return iconButton(glyphName, label, extraClass, false);
    }

    /** The same, greyed and inert when {@code disabled}: the eye while every credit is off. */
    private static String iconButton(String glyphName, String label, String extraClass,
            boolean disabled) {
        return "<button type=\"submit\" class=\"ib" + (extraClass.isEmpty() ? "" : " " + extraClass)
                + "\" title=\"" + escapeHtml(label) + "\" aria-label=\"" + escapeHtml(label) + "\""
                + (disabled ? " disabled" : "") + ">" + glyph(glyphName) + "</button>";
    }

    /** The same shape as a link: Back, Edit. */
    private static String iconLink(String href, String glyphName, String label) {
        return "<a class=\"ib\" href=\"" + escapeHtml(href) + "\" title=\"" + escapeHtml(label)
                + "\" aria-label=\"" + escapeHtml(label) + "\">" + glyph(glyphName) + "</a>";
    }


    /**
     * The settings page: one list of sections, each a glyph, its name and a one-line summary of
     * what is stored, opening in place (the owner's drawing of 2026-09-23). Inside are the essentials;
     * a section with more behind it ends with a text button that opens its own page.
     *
     * <p>The message about a refused save goes inside the section it is about, and that section
     * is the one open, so the reader lands on the reason rather than at the top of the page.
     * {@code admin_menu.js} keeps one section open at a time and remembers none between page
     * loads, opening the one the address names; without it the sections are plain
     * {@code <details>} and still work.
     */
    private String buildSettingsPage(String notice, String noticeSection) {
        KioskConfig config = KioskConfig.load(context);
        StringBuilder html = new StringBuilder(pageStart("Muralis", config.deviceId));

        // Only what no section can carry: everything posted from a Save button names a section,
        // and that message is drawn in the section instead.
        if (notice != null && !BOXED_SECTIONS.contains(noticeSection)) {
            html.append("<p class=\"notice\">").append(escapeHtml(notice)).append("</p>");
        }

        // In groups under a heading each, as Android's settings are, the same as the panel (the
        // UI round, 2026-10-15); About this panel stands alone at the end.
        java.util.List<String[]> sections = new java.util.ArrayList<>();
        sections.add(new String[] {"dashboard", "dash", "Dashboard",
                dashboardSummary(config), dashboardBody(config, notice, noticeSection),
                "Panel"});
        sections.add(new String[] {"ambient", "saver", "Ambient", screensaverSummary(),
                screensaverBody(), "Panel"});
        sections.add(new String[] {"cameras", "cameras", "Cameras",
                Cameras.summary(KioskConfig.camerasOf(context)), camerasBody(), "Panel"});
        sections.add(new String[] {"gallery", "folder", "Gallery", gallerySummary(context),
                galleryBody(), "Panel"});
        sections.add(new String[] {"display", "disp", "Display", displaySummary(), displayBody(),
                "Screen and sound"});
        sections.add(new String[] {"speaker", "audio", "Sound", speakerSummary(config),
                speakerBody(config, notice, noticeSection), "Screen and sound"});
        sections.add(new String[] {"sensors", "sensors", "Sensors", sensorsSummary(),
                sensorsSectionBody(), "Automation"});
        sections.add(new String[] {"automations", "automations", "Automations", automationsSummary(),
                automationsSectionBody(), "Automation"});
        sections.add(new String[] {"integrations", "integrations", "Integrations",
                integrationsSummary(), integrationsBody(), "Automation"});
        sections.add(new String[] {"mqtt", "mqtt", "MQTT",
                config.mqttHost.isEmpty() ? "Not configured" : config.mqttHost + ":" + config.mqttPort,
                mqttBody(config, notice, noticeSection), "Connections"});
        sections.add(new String[] {"webadmin", "web", "Web admin",
                "Port " + config.httpPort + (!secure() ? " · HTTP" : " · HTTPS")
                        + (config.webAdminEnabled ? "" : " · off"),
                webAdminBody(config, notice, noticeSection), "Connections"});
        sections.add(new String[] {"fleet", "fleet", "Fleet", FleetServer.summaryOf(context),
                fleetBody(config, notice, noticeSection), "Connections"});
        sections.add(new String[] {"sequences", "esc", "Way out",
                sequencesSummary(config), sequencesBody(config, notice, noticeSection),
                "Security"});
        sections.add(new String[] {"backup", "storage", "Backup and diagnostics",
                config.statsOverlay ? "Stats overlay on" : "Stats overlay off",
                backupBody(config), "System"});
        sections.add(new String[] {"panel", "panel", "About this panel", config.deviceId,
                panelBody(config, notice, noticeSection), "System"});

        StringBuilder nav = new StringBuilder("<nav class=\"sections\" aria-label=\"Sections\">");
        StringBuilder boxes = new StringBuilder("<div class=\"boxes\">");
        String group = null;
        for (String[] section : sections) {
            String key = section[0];
            if (!section[5].equals(group)) {
                // A group's heading, Material's list subheader, over its own list of segments.
                boxes.append(group == null ? "" : "</div>").append("<div class=\"grp\">");
                group = section[5];
                if (!group.isEmpty()) {
                    nav.append("<p class=\"subhead\">").append(escapeHtml(group)).append("</p>");
                    boxes.append("<h2 class=\"subhead\">").append(escapeHtml(group)).append("</h2>");
                } else {
                    nav.append("<p class=\"subhead gap\"></p>");
                }
            }
            boolean open = notice != null && key.equals(boxOf(noticeSection));
            nav.append("<a class=\"item\" href=\"#box-").append(key).append("\" data-box=\"box-")
                    .append(key).append("\">").append(summaryRow(section[1], section[2],
                            section[3], key)).append("</a>");
            boxes.append("<details class=\"box\" id=\"box-").append(key).append("\"")
                    .append(open ? " open" : "").append("><summary>")
                    .append(summaryRow(section[1], section[2], section[3], null))
                    .append("<span class=\"chev\">").append(glyph("down")).append("</span>")
                    .append("</summary><div class=\"body\" data-title=\"")
                    .append(escapeHtml(section[2])).append("\">")
                    .append(section[4]).append("</div></details>");
        }
        nav.append("</nav>");
        boxes.append("</div></div>");
        html.append("<div class=\"menu\">").append(nav).append(boxes).append("</div>");

        html.append(commandScript);
        html.append(settingScript);
        html.append(checkScript);
        html.append(statsScript);
        html.append(themeScript);
        html.append(menuScript);
        html.append(qrScript);
        html.append("</main></body></html>");
        return html.toString();
    }

    /** A section's glyph, name and summary, as the list on the left and the row of its box show it. */
    private static String summaryRow(String glyphName, String title, String summary, String sumKey) {
        return "<span class=\"lead\">" + glyph(glyphName) + "</span><span class=\"text\">"
                + "<span class=\"h\">" + escapeHtml(title) + "</span><span class=\"s\""
                + (sumKey == null ? "" : " id=\"sum-" + sumKey + "\"") + ">"
                + escapeHtml(summary) + "</span></span>";
    }

    // ---- the summaries: stored values only, never a status line the pages do not already carry

    /**
     * Each summary is one line of a list a third of the column wide, so it says the one thing
     * that section is about and leaves out what the page already shows: the device id is the app
     * bar's subtitle, the uptime is in the stats readout.
     */
    private String dashboardSummary(KioskConfig config) {
        // An app or cameras instead of the page say so, as the Dashboard choice does.
        if (!KioskConfig.appLockOf(context).isEmpty()) {
            return "App: " + KioskConfig.appLockLabelOf(context);
        }
        if (!KioskConfig.shownCamerasOf(context).isEmpty()) {
            return "Cameras: " + KioskConfig.shownCamerasOf(context);
        }
        if (!KioskConfig.shownIntegrationOf(context).isEmpty()) {
            return BitcoinClockPage.nameOf(context, KioskConfig.shownIntegrationOf(context));
        }
        String host = config.dashboardUrl.replaceFirst("^[a-z]+://", "").replaceFirst("/.*$", "");
        return host.isEmpty() ? "No dashboard yet" : host;
    }

    private String sequencesSummary(KioskConfig config) {
        // A TV has no corners to tap: the remote's Back held, or Menu, is its way in.
        if (!config.touchscreen) {
            return "Back held or Menu" + (KioskConfig.escapePinSet(context) ? " · PIN" : " · no PIN");
        }
        if (config.settingsSequence.isEmpty() || config.launcherSequence.isEmpty()) {
            return "Not recorded yet";
        }
        return "2 combinations" + (KioskConfig.escapePinSet(context) ? " · PIN" : " · no PIN");
    }

    private String displaySummary() {
        String orientation = KioskConfig.orientationOf(context);
        return (KioskConfig.ORIENTATION_AUTO.equals(orientation) ? "Auto-rotate"
                : KioskConfig.ORIENTATION_PORTRAIT.equals(orientation) ? "Portrait" : "Landscape")
                + (KioskService.isAutoBrightnessOn(context) ? " · auto brightness"
                        : " · manual brightness");
    }

    private String screensaverSummary() {
        ScreensaverPolicy.Settings saver = KioskConfig.screensaverOf(context);
        return ScreensaverPolicy.OFF.equals(saver.mode) ? "Off"
                : ScreensaverPolicy.modeName(saver.mode) + " · after "
                        + ScreensaverPolicy.describeDuration(saver.idleSeconds);
    }

    /** "n of m on" for the Sensors section and page; admin_stats.js keeps it current. */
    private String sensorsSummary() {
        return Sensors.summary(KioskRuntimeState.sensors());
    }

    private String automationsSummary() {
        org.json.JSONArray rules = KioskRuntimeState.automations();
        int on = 0;
        for (int index = 0; index < rules.length(); index++) {
            org.json.JSONObject rule = rules.optJSONObject(index);
            if (rule != null && rule.optBoolean("enabled")) {
                on++;
            }
        }
        return rules.length() == 0 ? "None yet" : on + " of " + rules.length() + " on";
    }

    /** One list row: a glyph, two lines, and whatever stands at the right. */
    private static String listRow(String glyphName, String head, String headId, String sub,
            String subId, String trail, boolean dim) {
        return listRow(glyphName, head, headId, sub, subId, trail, dim, "");
    }

    /** The same, with {@code mark}, markup, before the second line: see stopsMark. */
    private static String listRow(String glyphName, String head, String headId, String sub,
            String subId, String trail, boolean dim, String mark) {
        return "<li class=\"two" + (dim ? " dim" : "") + "\"><span class=\"lead\">"
                + glyph(glyphName) + "</span><span class=\"text\"><span class=\"h\""
                + (headId == null ? "" : " id=\"" + headId + "\"") + ">" + escapeHtml(head)
                + "</span><span class=\"s\"" + (subId == null ? "" : " id=\"" + subId + "\"")
                + ">" + mark + escapeHtml(sub) + "</span></span>"
                + (trail.isEmpty() ? "" : "<span class=\"trail\">" + trail + "</span>") + "</li>";
    }

    /** The same row with its text a link to the page it names. */
    private static String linkRow(String href, String glyphName, String head, String sub,
            String subId, String trail) {
        return linkRow(href, glyphName, head, sub, subId, trail, "");
    }

    private static String linkRow(String href, String glyphName, String head, String sub,
            String subId, String trail, String mark) {
        // A row with nothing to say under its name is one line high.
        boolean two = !sub.isEmpty() || subId != null || !mark.isEmpty();
        return "<li" + (two ? " class=\"two\"" : "") + "><a class=\"rowlink plain\" href=\""
                + href + "\"><span class=\"lead\">" + glyph(glyphName) + "</span><span class=\"text\">"
                + "<span class=\"h\">" + escapeHtml(head) + "</span>" + (two ? "<span class=\"s\""
                + (subId == null ? "" : " id=\"" + subId + "\"") + ">" + mark + escapeHtml(sub)
                + "</span>" : "") + "</span></a>"
                + (trail.isEmpty() ? "" : "<span class=\"trail\">" + trail + "</span>") + "</li>";
    }

    /**
     * The mark before a rule's sentence when any sensor it reads is not running while the
     * display sleeps, the panel's own: the sensor with a slash, small, in the warning colour.
     */
    private String stopsMarkAny(java.util.Collection<String> sensorIds) {
        boolean stops = false;
        for (String sensorId : sensorIds) {
            stops |= KioskService.stopsWhileAsleep(context, sensorId);
        }
        // The words for a screen reader too, not only in a tooltip (review, 2026-10-01).
        return stops
                ? "<span class=\"stops\" title=\"Not running while the display is off\">"
                        + glyph("asleep_off") + "<span class=\"sr\">Not running while the display"
                        + " is off. </span></span>"
                : "";
    }

    private static String sensorSwitch(Sensors.Def def, boolean on) {
        return sensorSwitch(def, on, "");
    }

    /** The same switch under another id, for the detail's own row beside the list's. */
    private static String sensorSwitch(Sensors.Def def, boolean on, String suffix) {
        return "<input type=\"checkbox\" class=\"sw\" id=\"sensor-" + def.id + suffix
                + "\" data-setting=\"sensor_" + def.id + "\" aria-label=\"" + escapeHtml(def.name)
                + "\"" + (on ? " checked" : "") + ">";
    }

    /** A list row as a row of the list beside a detail: its id on it, and marked when chosen. */
    private static String navRow(String li, String id, boolean on) {
        java.util.regex.Matcher tag = java.util.regex.Pattern.compile("<li( class=\"([^\"]*)\")?")
                .matcher(li);
        if (!tag.find()) {
            return li;
        }
        String classes = (tag.group(2) == null ? "" : tag.group(2)) + (on ? " on" : "");
        return li.substring(0, tag.start()) + "<li data-id=\"" + escapeHtml(id) + "\" class=\""
                + classes.trim() + "\"" + li.substring(tag.end());
    }

    /**
     * The list-detail shell the Sensors and Automations pages share, the settings page's own
     * shape from 840 px: the list at the left, the chosen item's page at the right. Below that
     * one of the two shows, which one {@code detailPage} says, and from 600 px the column is
     * no wider than 640 px (2026-09-27).
     */
    private String listDetail(String kind, boolean detailPage, String nav, String detail) {
        return listDetail(kind, detailPage, nav, detail, "");
    }

    /** The same, {@code attributes} telling the script a page's own addresses (Cameras). */
    private String listDetail(String kind, boolean detailPage, String nav, String detail,
            String attributes) {
        return "<div class=\"ld\" id=\"ld-" + kind + "\" data-kind=\"" + kind + "\" data-page=\""
                + (detailPage ? "detail" : "list") + "\" data-title=\""
                + escapeHtml(Character.toUpperCase(kind.charAt(0)) + kind.substring(1)) + "\""
                + attributes + listsAttributes(kind + "_page") + ">"
                + nav + "<div class=\"detail\">" + detail + "</div></div>" + listDetailScript;
    }

    /**
     * The key a list was drawn from, for admin_stats.js: when the stats carry another, the list
     * is fetched again and swapped in place, unless something in it is being edited. See
     * Sensors.listKeys; the rules as stored, as the pages draw them.
     */
    private String listsAttributes(String kind) {
        String key = kind.equals("fleet") ? FleetServer.fleetListKey(context)
                : kind.startsWith("integrations_")
                ? IntegrationRunner.listKey(KioskRuntimeState.integrations())
                : Sensors.listKeys(KioskRuntimeState.sensors(),
                        Automations.statusJson(KioskConfig.automationsOf(context)))
                        .optString(kind, "");
        return " data-lists-kind=\"" + kind + "\" data-lists=\"" + escapeHtml(key) + "\"";
    }

    /** The settings page's Sensors section: only what is on, and the way to the rest. */
    private String sensorsSectionBody() {
        org.json.JSONObject block = KioskRuntimeState.sensors();
        StringBuilder rows = new StringBuilder();
        for (Sensors.Def def : Sensors.ALL) {
            org.json.JSONObject one = block.optJSONObject(def.id);
            if (one == null || !one.optBoolean("active")) {
                continue;
            }
            // The switch on the row too (2026-09-27): switched off, admin_stats.js takes
            // the row out at the next poll; the Sensors page switches it on again.
            rows.append(navRow(listRow(def.glyph, def.name, null, one.optString("reading", ""),
                    "sensor-" + def.id + "-value", sensorSwitch(def, true, "-home"), false),
                    def.id, false));
        }
        return "<div id=\"sensors-body\"" + listsAttributes("sensors_home") + ">"
                + (rows.length() == 0 ? "" : "<ul class=\"list seg\" id=\"sensors-home\">" + rows + "</ul>")
                + "<div class=\"actions" + (rows.length() == 0 ? " tight" : "") + "\">"
                + "<a class=\"btn text\" href=\"/sensors\">More sensor settings" + glyph("next")
                + "</a></div></div>";
    }

    private String automationsSectionBody() {
        // As stored, not the status document's copy, which follows a change by a tick: the
        // list's key is taken from what is stored, and the two must agree.
        StringBuilder rows = new StringBuilder();
        for (Automations.Rule rule : KioskConfig.automationsOf(context)) {
            if (!rule.enabled) {
                continue;
            }
            // Ids pass Automations.ID_SHAPE on the way in; escaped here all the same.
            String id = escapeHtml(rule.id);
            rows.append(navRow(listRow("automations", rule.name,
                    null, Automations.sentence(rule), null,
                    "<input type=\"checkbox\" class=\"sw\" id=\"automation-" + id
                            + "-home\" data-setting=\"automation_" + id + "\" aria-label=\""
                            + escapeHtml(rule.name) + "\" checked>", false,
                    stopsMarkAny(rule.sensors())),
                    rule.id, false));
        }
        return "<div id=\"automations-body\"" + listsAttributes("automations_home") + ">"
                + (rows.length() == 0 ? "" : "<ul class=\"list seg\" id=\"automations-home\">" + rows + "</ul>")
                + "<div class=\"actions" + (rows.length() == 0 ? " top0" : "") + "\">"
                + "<a class=\"btn text\" href=\"/automations\">More automation settings"
                + glyph("next") + "</a></div></div>";
    }

    /**
     * About this panel: its name, then the stats and log, the version
     * and the legal pages as rows.
     */
    private String panelBody(KioskConfig config, String notice, String noticeSection) {
        return sectionFormStart("panel", notice, noticeSection)
                + baselineField(config.deviceId)
                // Stored on change, as the panel's box is; Save only where nothing runs.
                + field("text", "device_id", "Name", config.deviceId,
                        " data-setting=\"device_id\"", null)
                + "<div class=\"actions nojs\"><button class=\"primary\" type=\"submit\">Save"
                + "</button></div></form>"
                // Pro read only, as Buy is on the panel; the version opens the details page; the
                // legal texts are pages here, so a chevron each (the UI round, 2026-10-26).
                + "<ul class=\"list gap\">"
                + "<li class=\"two\"><span class=\"lead\">" + glyph("check") + "</span>"
                + "<span class=\"text\"><span class=\"h\">Muralis Pro</span><span class=\"s wrap\">"
                + escapeHtml(FleetServer.proLine(context)) + "</span></span></li>"
                + linkRow("/about", "info", "Version and device details", appVersionName(), null,
                        glyph("next"))
                + linkRow("/privacy", "details", context.getString(R.string.privacy_policy_title),
                        "", null, glyph("next"))
                + linkRow("/terms", "details", context.getString(R.string.terms_title), "", null,
                        glyph("next"))
                + "</ul>";
    }

    /** The panel's About page: the same cards, {@link KioskActivity#aboutCards}. */
    private String renderAboutPage() {
        // No subtitle: it would turn on the stats chip, which this page has no script to fill.
        StringBuilder html = new StringBuilder(pageStart("About", null, settingsAt("panel")));
        html.append("<div class=\"doc one\">");
        for (String[][] card : KioskActivity.aboutCards(context)) {
            html.append("<section class=\"card\"><h2>").append(escapeHtml(card[0][0]))
                    .append("</h2><dl class=\"values\">");
            for (int row = 1; row < card.length; row++) {
                html.append("<dt>").append(escapeHtml(card[row][0])).append("</dt><dd>")
                        .append(escapeHtml(card[row][1])).append("</dd>");
            }
            html.append("</dl></section>");
        }
        html.append("<p class=\"hint\"><a href=\"/about/fonts\">The fonts' licence</a></p>");
        html.append("</div>").append(themeScript).append("</main></body></html>");
        return html.toString();
    }

    /**
     * Backup and diagnostics (the settings round, 2026-10-26): the settings file, the stats and
     * the log, the screen as the wall shows it and the diagnostics file, out of About, as
     * Android keeps its tools apart; the addresses wrap so they can be copied.
     */
    private String backupBody(KioskConfig config) {
        // Black titles, a short line, one trailing icon: a chevron for a page here, open-outside
        // for the screenshot the browser shows, download for the file (the UI round, 2026-10-26).
        return "<ul class=\"list\">"
                + linkRow("/settings-file", "folder", "Settings file",
                        "Export, import, a setup QR", null, glyph("next"))
                + linkRow("/stats", "stats", "System stats and log",
                        config.statsOverlay ? "Stats overlay on" : "Stats overlay off", null,
                        glyph("next"))
                + linkRow("/api/screenshot", "display", "Screenshot", "The screen now", null,
                        glyph("open"))
                + linkRow("/api/diagnostics", "info", "Download diagnostics",
                        "A file for a bug report", null, glyph("download"))
                + "</ul>";
    }

    /**
     * The Sensors page: the one flat list, alphabetical, every row the same shape (see Sensors),
     * and beside it from 840 px the chosen sensor's cards.
     */
    private String buildSensorsPage(String selectedId, boolean detailPage) {
        org.json.JSONObject block = KioskRuntimeState.sensors();
        Sensors.Def chosen = null;
        for (Sensors.Def def : Sensors.ALL) {
            org.json.JSONObject one = block.optJSONObject(def.id);
            if (one == null) {
                continue;
            }
            if (def.id.equals(selectedId)) {
                chosen = def;
                break;
            }
            if (chosen == null && one.optBoolean("available")) {
                chosen = def;
            }
        }
        StringBuilder rows = new StringBuilder();
        StringBuilder absent = new StringBuilder();
        for (Sensors.Def def : Sensors.ALL) {
            org.json.JSONObject one = block.optJSONObject(def.id);
            if (one == null) {
                continue;
            }
            String reading = one.optString("reading", "");
            String readingId = "sensor-" + def.id + "-value";
            boolean on = def == chosen;
            if (!one.optBoolean("available")) {
                absent.append(navRow(listRow(def.glyph, def.name, null, reading, null,
                        "<input type=\"checkbox\" class=\"sw\" disabled aria-label=\""
                                + escapeHtml(def.name) + "\">", true), def.id, on));
                continue;
            }
            String row;
            if (def.kind == Sensors.Kind.PERMISSION && !one.optBoolean("permitted")) {
                row = listRow(def.glyph, def.name, null, reading, readingId,
                        "<span class=\"allow\">Allow on the panel</span>", false);
            } else if (webPage(def)) {
                row = linkRow("/sensor?id=" + def.id, def.glyph, def.name, reading, readingId,
                        "<span class=\"chev\">" + glyph("next") + "</span><span class=\"divider\"></span>"
                                + sensorSwitch(def, one.optBoolean("enabled")));
            } else {
                row = listRow(def.glyph, def.name, null, reading, readingId,
                        sensorSwitch(def, one.optBoolean("enabled")), false);
            }
            rows.append(navRow(row, def.id, on));
        }
        String nav = "<section class=\"card nav\"><div class=\"cardhead\"><h2>Sensors</h2>"
                + "<span class=\"count\" id=\"sum-sensors\">" + escapeHtml(sensorsSummary())
                + "</span></div><ul class=\"list seg\">" + rows + absent + "</ul></section>";
        // Every sensor's cards are on the page, one pane each, so choosing one at the left is
        // a switch of panes and not a page load, the way the settings menu opens a section.
        StringBuilder detail = new StringBuilder();
        for (Sensors.Def def : Sensors.ALL) {
            org.json.JSONObject one = block.optJSONObject(def.id);
            if (one == null) {
                continue;
            }
            detail.append("<div class=\"pane\" data-id=\"").append(def.id).append("\"")
                    .append(def == chosen ? "" : " hidden").append(">")
                    .append(sensorCards(def, one)).append("</div>");
        }
        return pageStart(detailPage && chosen != null ? chosen.name : "Sensors", null,
                detailPage ? "/sensors" : settingsAt("sensors"))
                + listDetail("sensors", detailPage, nav, detail.toString()) + pageEnd();
    }

    /**
     * Whether the sensor's page says anything on this surface beyond its row: the calibration
     * and the test while asleep are the panel's alone, so a chevron to a page that only
     * repeats the row is left off here (review, 2026-10-01).
     */
    private static boolean webPage(Sensors.Def def) {
        return def.page && (def == Sensors.MOVEMENT || def == Sensors.CAMERA
                || def == Sensors.BLUETOOTH || def == Sensors.NFC);
    }

    private static String choice(String key, String label, String radios) {
        return "<div class=\"choice\"><p class=\"label\" id=\"opt-" + key + "-label\">" + label
                + "</p>" + radioGroup(key, radios) + "</div>";
    }

    /** The radios of one choice, named by the label whose id is {@code opt-<key>-label}. */
    private static String radioGroup(String key, String radios) {
        return "<div class=\"radios\" role=\"radiogroup\" aria-labelledby=\"opt-" + key
                + "-label\">" + radios + "</div>";
    }

    /**
     * One radio: {@code dataSetting} is what a pick stores (a sensor's {@code sensor_option_<key>}
     * or a plain setting's own key), {@code key} names the group, the label HTML is given.
     */
    private static String radioOf(String dataSetting, String key, String value, String current,
            String labelHtml) {
        return radioOf(dataSetting, key, value, current, labelHtml, null);
    }

    /**
     * The same, greyed and inert while {@code disabledReason} says why: a choice there is
     * nothing for, the reason in its tooltip and, through the title, in what a screen reader
     * says of it.
     */
    private static String radioOf(String dataSetting, String key, String value, String current,
            String labelHtml, String disabledReason) {
        String reason = disabledReason == null ? ""
                : " title=\"" + escapeHtml(disabledReason) + "\"";
        return "<label class=\"radio\"" + reason + "><input type=\"radio\" name=\"opt-" + key
                + "\" id=\"opt-" + key + "-" + escapeHtml(value) + "\" value=\"" + escapeHtml(value)
                + "\" data-setting=\"" + dataSetting + "\""
                + (value.equals(current) ? " checked" : "")
                + (disabledReason == null ? "" : " disabled" + reason) + ">"
                + labelHtml + "</label>";
    }

    /**
     * A sensor option of five or fewer choices as radios, the panel's shape for the same
     * settings, each one stored the moment it is picked through admin_setting.js (a menu here
     * and radios on the panel broke the mirror, review of 2026-10-01); the same builders as
     * This panel's look (choice, radioOf).
     */
    private static String optionRadios(String key, String label, String current,
            String... valuesAndLabels) {
        StringBuilder radios = new StringBuilder();
        for (int index = 0; index + 1 < valuesAndLabels.length; index += 2) {
            radios.append(radioOf("sensor_option_" + key, key, valuesAndLabels[index], current,
                    escapeHtml(valuesAndLabels[index + 1])));
        }
        return choice(key, escapeHtml(label), radios.toString());
    }

    private static String optionSelect(String key, String label, String current,
            String... valuesAndLabels) {
        StringBuilder options = new StringBuilder();
        for (int index = 0; index + 1 < valuesAndLabels.length; index += 2) {
            options.append(selectOption(valuesAndLabels[index], valuesAndLabels[index + 1],
                    current));
        }
        return selectField("opt-" + key, null, label, "sensor_option_" + key, options.toString(),
                null);
    }

    private static String optionField(String key, String label, String current, String type) {
        return fieldWithId("opt-" + key, type, null, label, current,
                " data-setting=\"sensor_option_" + key + "\"", null);
    }

    private static String optionSwitch(String key, String label, boolean on) {
        return switchRow("opt-" + key, label, " data-setting=\"sensor_option_" + key + "\"", on);
    }

    /** A sensor's own page: its switch, then what it can be told; the list beside it when wide. */
    private String buildSensorPage(String id) {
        Sensors.Def def = Sensors.byId(id == null ? "" : id);
        if (def == null || KioskRuntimeState.sensors().optJSONObject(def.id) == null) {
            return pageStart("Sensors", null, "/sensors")
                    + "<p class=\"hint bad\">No sensor page is called that.</p>" + pageEnd();
        }
        return buildSensorsPage(def.id, true);
    }

    /**
     * The cards of a sensor's page: its own row first, with what its kind allows at the right,
     * then what it can be told. Its row carries ids of its own, so the list beside it keeps
     * the ids the stats poll follows.
     */
    private String sensorCards(Sensors.Def def, org.json.JSONObject one) {
        boolean on = one.optBoolean("enabled");
        StringBuilder html = new StringBuilder();
        String reading = one.optString("reading", "");
        String trailing;
        String label = switchLabel(def);
        if (!one.optBoolean("available")) {
            trailing = "<input type=\"checkbox\" class=\"sw\" disabled aria-label=\""
                    + escapeHtml(def.name) + "\">";
            label = def.name;
        } else if (def.kind == Sensors.Kind.PERMISSION && !one.optBoolean("permitted")) {
            trailing = "<span class=\"allow\">Allow on the panel</span>";
        } else {
            trailing = sensorSwitch(def, on, "-page").replace("class=\"sw\"", "class=\"sw pageswitch\"");
        }
        // No title on the first card: the app bar and the row already name the sensor (review,
        // 2026-10-01). From 840 px the switch hides with the list beside it, and the row then
        // says the name, not the switch's words (the stylesheet's .pageswitch and .pagename).
        String words = label.equals(def.name) ? escapeHtml(def.name)
                : "<span class=\"switchname\">" + escapeHtml(label) + "</span><span class=\"pagename\">"
                        + escapeHtml(def.name) + "</span>";
        String head = "<section class=\"card\"><ul class=\"list\">"
                + listRow(def.glyph, label, null, reading, "sensor-" + def.id + "-page-value",
                        trailing, !one.optBoolean("available"))
                        .replaceFirst("<span class=\"h\">" + java.util.regex.Matcher.quoteReplacement(escapeHtml(label)),
                                java.util.regex.Matcher.quoteReplacement("<span class=\"h\">" + words)) + "</ul>";
        // The calibration and the test while asleep are the panel's alone: both need a hand
        // at the glass, and a remote button for that is a strange thing (2026-09-27).
        if (!one.optBoolean("available") || def.kind == Sensors.Kind.PANEL
                || (def.kind == Sensors.Kind.PERMISSION && !one.optBoolean("permitted"))) {
            html.append(head).append("</section>");
        } else if (def == Sensors.PROXIMITY) {
            html.append(head).append("</section>");
        } else if (def == Sensors.MOVEMENT) {
            // Low to High, as the camera says it; a light push is the most sensitive setting.
            html.append(head)
                    .append(optionRadios("movement_sensitivity", "Sensitivity",
                            KioskConfig.sensorOption(context, "movement_sensitivity", "normal"),
                            "heavy", "Low", "normal", "Normal", "light", "High"))
                    .append(optionField("movement_still_s", "Still after (seconds)",
                            KioskConfig.sensorOption(context, "movement_still_s", "5"), "number"))
                    .append("</section>");
        } else if (def == Sensors.NFC) {
            html.append(head).append("</section>")
                    .append("<section class=\"card\"><h2>Tags</h2>").append(tagRows())
                    .append("</section>");
        } else if (def == Sensors.BLUETOOTH) {
            html.append(head)
                    .append(optionField("beacons_reach_s", "Out of reach after (seconds)",
                            KioskConfig.sensorOption(context, "beacons_reach_s", "30"), "number"))
                    .append(optionRadios(Beacons.LISTENING_KEY, "Listening",
                            KioskConfig.sensorOption(context, Beacons.LISTENING_KEY, "low"),
                            "low", "Low, saves battery", "high", "High, listens all the time"))
                    .append("</section><section class=\"card\"><h2>Beacons</h2>")
                    .append(beaconRows()).append("</section>");
        } else if (def == Sensors.CAMERA) {
            StringBuilder sizes = new StringBuilder();
            for (String size : PanelCamera.SIZES) {
                sizes.append(size).append(',').append(size.replace("x", " × ")).append(',');
            }
            StringBuilder rates = new StringBuilder();
            for (int rate : PanelCamera.RATES) {
                rates.append(rate).append(',').append(rate).append(',');
            }
            String address = KioskRuntimeState.httpAdminAddress();
            html.append(head)
                    .append(optionField("camera_name", "Name",
                            KioskConfig.sensorOption(context, "camera_name",
                                    PanelCamera.defaultName(context)), "text"))
                    // Radios, as the panel draws a choice of five or fewer.
                    .append(optionRadios("camera_lens", "Lens",
                            KioskConfig.sensorOption(context, "camera_lens", "front"),
                            "front", "Front", "back", "Back"))
                    .append(optionRadios("camera_size", "Size",
                            KioskConfig.sensorOption(context, "camera_size", "640x480"),
                            sizes.toString().split(",")))
                    .append(optionRadios("camera_fps", "Frames per second",
                            KioskConfig.sensorOption(context, "camera_fps", "5"),
                            rates.toString().split(",")))
                    .append(optionRadios("camera_orientation", "Orientation",
                            KioskConfig.sensorOption(context, "camera_orientation", "device"),
                            "device", "Follow the device", "portrait", "Portrait",
                            "landscape", "Landscape"))
                    .append("<div class=\"switches\">")
                    .append(optionSwitch("camera_mirror", "Mirror",
                            KioskConfig.sensorOptionOn(context, "camera_mirror", false)))
                    .append(optionSwitch("camera_flip", "Upside down",
                            KioskConfig.sensorOptionOn(context, "camera_flip", false)))
                    .append(optionSwitch("camera_watermark", "Watermark, name and time",
                            KioskConfig.sensorOptionOn(context, "camera_watermark", true)))
                    .append("</div></section>")
                    .append("<section class=\"card\"><h2>Motion</h2><div class=\"switches\">")
                    .append(optionSwitch("camera_motion", "Detect motion",
                            KioskConfig.sensorOptionOn(context, "camera_motion", true)))
                    .append("</div>")
                    .append(optionRadios("camera_sensitivity", "Sensitivity",
                            KioskConfig.sensorOption(context, "camera_sensitivity", "normal"),
                            "low", "Low", "normal", "Normal", "high", "High"))
                    .append(optionField("camera_still_s", "Still after (seconds)",
                            KioskConfig.sensorOption(context, "camera_still_s", "30"), "number"))
                    .append("<div class=\"switches\">")
                    .append(optionSwitch("camera_mqtt", "Picture to MQTT",
                            KioskConfig.sensorOptionOn(context, "camera_mqtt", false)))
                    .append("</div></section>")
                    // The addresses wrap (.wrap): an address cut with dots cannot be copied.
                    .append("<section class=\"card\"><h2>Stream</h2><ul class=\"list\">")
                    .append(linkRow("/camera/stream", "open", "Live stream",
                            address + "/camera/stream", null, "")
                            .replace("<span class=\"s\"", "<span class=\"s wrap\""))
                    .append(linkRow("/camera/snapshot.jpg", "open", "Snapshot",
                            address + "/camera/snapshot.jpg", null, "")
                            .replace("<span class=\"s\"", "<span class=\"s wrap\""))
                    .append("</ul></section>");
        } else {
            html.append(head).append("</section>");
        }
        return html.toString();
    }

    private static String switchLabel(Sensors.Def def) {
        if (def == Sensors.MOVEMENT) {
            return "Report movement";
        }
        if (def == Sensors.NFC) {
            return "Read tags";
        }
        if (def == Sensors.BLUETOOTH) {
            return "Listen for beacons";
        }
        if (def == Sensors.CAMERA) {
            return "Use the camera";
        }
        return def.name;
    }

    /**
     * The tags read lately, newest first, by their id and when; a tag is named and used in
     * the smart home, as the panel's own page has it.
     */
    private String tagRows() {
        Map<String, Long> seen = KioskConfig.seenTags(context);
        if (seen.isEmpty()) {
            return "<p class=\"hint\">No tag has been held to the panel yet.</p>";
        }
        StringBuilder rows = new StringBuilder("<ul class=\"list seg\">");
        for (Map.Entry<String, Long> tag : seen.entrySet()) {
            rows.append(listRow("tag", tag.getKey(), null, "Read "
                    + android.text.format.DateUtils.getRelativeTimeSpanString(tag.getValue()),
                    null, "", false));
        }
        return rows.append("</ul>").toString();
    }

    /**
     * The beacons heard, each with a box for its name and its line under it. The stats poll
     * repaints the lines ({@code data-beacon}, from {@code beacon_lines}) and fetches the list
     * again when its key moves ({@code lists.beacons}), so a beacon heard after the page loaded
     * appears; the lines kept the state the page was drawn with (2026-10-05).
     */
    private String beaconRows() {
        Beacons beacons = kioskService.beacons();
        java.util.Map<String, String> heard = beacons == null
                ? Collections.<String, String>emptyMap() : beacons.lines();
        StringBuilder rows = new StringBuilder("<div id=\"beacon-rows\" data-lists-kind=\"beacons\""
                + " data-lists=\"" + escapeHtml(Beacons.listKey(heard.keySet())) + "\">");
        if (heard.isEmpty()) {
            return rows.append("<p class=\"hint\">No beacon has been heard yet.</p></div>")
                    .toString();
        }
        rows.append("<ul class=\"list gap\">");
        NamedList names = KioskConfig.beaconNames(context);
        for (Map.Entry<String, String> line : heard.entrySet()) {
            String id = line.getKey();
            rows.append("<li class=\"two\"><span class=\"lead\">").append(glyph("bluetooth"))
                    .append("</span><span class=\"text\">")
                    .append(fieldWithId("beacon-" + id.hashCode(), "text", null, "Name",
                            names.name(id),
                            " data-setting=\"sensor_option_beacon_" + escapeHtml(id) + "\"",
                            line.getValue(), " data-beacon=\"" + escapeHtml(id) + "\""))
                    .append("</span></li>");
        }
        return rows.append("</ul></div>").toString();
    }

    /** Stores one setting of a sensor's page, checked by key; the rules are Sensors.checkOption. */
    private String saveSensorOption(String key, String value) {
        String problem = Sensors.checkOption(key, value);
        if (problem != null) {
            return problem;
        }
        if (key.startsWith("beacon_")) {
            // A beacon's name lives in the beacons' named list, not a setting of its own.
            KioskConfig.beaconName(context, key.substring("beacon_".length()), value);
            KioskService.publishTelemetrySoon(context);
            return null;
        }
        KioskConfig.edit(context).sensorOption(key, Sensors.cleanOption(key, value)).apply();
        KioskService.refreshSensorsSoon(context);
        return null;
    }

    private String buildAutomationsPage(String notice) {
        return buildAutomationsPage(notice, null, null, false);
    }

    /**
     * The Automations page: the rules with Add under them, and beside them from 840 px the
     * editor of the chosen rule, of {@code editing} when a save came back refused or a
     * structure change drew it again, or of a new one. As a detail page it is the editor alone
     * below 840 px.
     */
    private String buildAutomationsPage(String notice, String selectedId,
            Automations.Rule editing, boolean detailPage) {
        return buildAutomationsPage(notice, selectedId, editing, detailPage, null, null);
    }

    private String buildAutomationsPage(String notice, String selectedId,
            Automations.Rule editing, boolean detailPage, String baseline, AutomationForm typed) {
        java.util.List<Automations.Rule> rules = KioskConfig.automationsOf(context);
        Automations.Rule rule = editing;
        if (rule == null) {
            Automations.Rule stored = Automations.find(rules, selectedId);
            rule = stored != null ? stored : rules.isEmpty() ? new Automations.Rule() : rules.get(0);
        }
        StringBuilder rows = new StringBuilder();
        for (Automations.Rule each : rules) {
            // Ids pass Automations.ID_SHAPE on the way in; escaped here all the same.
            String id = escapeHtml(each.id);
            rows.append(navRow(linkRow("/automation?id=" + urlEncode(each.id), "automations",
                    each.name, Automations.sentence(each), null,
                    "<input type=\"checkbox\" class=\"sw\" id=\"automation-" + id
                            + "\" data-setting=\"automation_" + id + "\" aria-label=\""
                            + escapeHtml(each.name) + "\"" + (each.enabled ? " checked" : "") + ">",
                    stopsMarkAny(each.sensors())),
                    each.id, each.id.equals(rule.id)));
        }
        String nav = "<section class=\"card nav\"><div class=\"cardhead\"><h2>Automations</h2>"
                + "<span class=\"count\" id=\"sum-automations\">" + escapeHtml(automationsSummary())
                + "</span></div>"
                + (rows.length() == 0 ? "<p class=\"hint\">No automations yet.</p>"
                        : "<ul class=\"list seg\">" + rows + "</ul>")
                + "<div class=\"actions\"><a class=\"btn tonal\" href=\"/automation\">" + glyph("plus")
                + "Add an automation</a></div></section>";
        boolean fresh = rule.id.isEmpty();
        // The open rule's editor alone, or a new rule's: a rule's editor is tens of kilobytes,
        // and every rule's at once made the page megabytes long at the limit. Another row of
        // the list is its own page (admin_listdetail.js follows the row's link when its pane
        // is not on the page); the rule being edited stands in for its stored twin.
        String detail = "<div class=\"pane\" data-id=\"" + escapeHtml(rule.id) + "\">"
                + automationEditor(rule, baseline, typed) + "</div>";
        return pageStart(!detailPage ? "Automations" : fresh ? "New automation" : "Edit automation",
                null, detailPage ? "/automations" : settingsAt("automations"))
                + (notice == null ? "" : "<p class=\"notice\">" + escapeHtml(notice) + "</p>")
                + listDetail("automations", detailPage, nav, detail) + pageEnd();
    }

    /** The editor as a page of its own, the list beside it when wide. */
    private String buildAutomationPage(Automations.Rule rule, String notice) {
        return buildAutomationsPage(notice, rule.id, rule, true, null, null);
    }

    /** The same for a draft: the baseline its form carried, and the boxes as typed. */
    private String buildAutomationPage(Automations.Rule rule, String notice, String baseline,
            AutomationForm typed) {
        return buildAutomationsPage(notice, rule.id, rule, true, baseline, typed);
    }

    /**
     * The panel's delete question (KioskActivity.confirmDelete) in one string for the browser's
     * confirm(), which has no title of its own: the title, a blank line, the text.
     */
    private static String deleteQuestion(String name) {
        String quoted = "\"" + name + "\"";
        return "Delete " + quoted + "?\n\n" + quoted + " will be deleted.";
    }

    /** The same for something taken off a list, an automation, which is removed, not deleted. */
    private static String removeQuestion(String name) {
        String quoted = "\"" + name + "\"";
        return "Remove " + quoted + "?\n\n" + quoted + " will be removed.";
    }

    /**
     * A rule read back from the editor's form, with the boxes as they were typed, kept by the
     * part they belong to: a structure change moves the parts, so a box is found again by its
     * part, not by its place, and a refused save shows what was typed rather than what the
     * rule could hold of it (a number box left at "-1").
     */
    private static final class AutomationForm {
        final Automations.Rule rule;
        final Map<String, String> form;
        /** Each When, action and condition read, to the prefix of its names in the form. */
        final Map<Object, String> prefixes = new java.util.IdentityHashMap<>();

        AutomationForm(Automations.Rule rule, Map<String, String> form) {
            this.rule = rule;
            this.form = form;
        }

        /** The box as typed, or null for a part the form did not carry (one just added). */
        String typed(Object part, String box) {
            String prefix = prefixes.get(part);
            return prefix == null ? null : form.get(prefix + "_" + box);
        }
    }

    /** What every part of one editor draws from, looked up once per editor. */
    private static final class EditorData {
        /** The ids' suffix: several editors stand on one page, one per rule. */
        String sfx;
        org.json.JSONObject block;
        java.util.List<Cameras.Camera> cameras;
        java.util.List<Cameras.Group> groups;
        boolean anyCamera;
        /** The integrations' values by sensor id, with their names. */
        Map<String, String> values;
        java.util.Set<String> tagsRead;
        AutomationForm typed;

        /** The box as typed where a draft is drawn again, else {@code drawn}. */
        String shown(Object part, String box, String drawn) {
            String typedText = typed == null ? null : typed.typed(part, box);
            return typedText != null ? typedText : drawn;
        }
    }

    /**
     * The editor: Name, a card per When with its Then list, and If, as the products it copies
     * write an automation. {@code baseline} is the one the form carried where a draft is drawn
     * again (null: the stored rule's), {@code typed} what was typed in it.
     *
     * <p>Add, Remove, Move up and Move down are submit buttons named op: each posts the whole
     * form to saveAutomation, which changes the draft's shape (Automations.applyOp) and draws it
     * again, storing nothing, so the editor works without scripting. Each lands on the part it
     * changed by the fragment of the address it posts to, which the browser applies to the reply.
     */
    private String automationEditor(Automations.Rule rule, String baseline, AutomationForm typed) {
        boolean fresh = rule.id.isEmpty();
        // The stored rule as the baseline, so a page left open cannot overwrite a change made
        // on the panel or in the smart home meanwhile: the Save-button forms' rule.
        Automations.Rule stored = Automations.find(KioskConfig.automationsOf(context), rule.id);
        if (baseline == null) {
            baseline = stored == null ? "" : Automations.baseline(stored);
        }
        EditorData data = new EditorData();
        data.sfx = "-" + (fresh ? "new" : escapeHtml(rule.id));
        data.block = KioskRuntimeState.sensors();
        data.cameras = KioskConfig.camerasOf(context);
        data.groups = KioskConfig.cameraGroupsOf(context);
        data.anyCamera = !data.cameras.isEmpty() || !data.groups.isEmpty();
        data.values = IntegrationRunner.sensorNames(KioskRuntimeState.integrations());
        data.tagsRead = KioskConfig.seenTags(context).keySet();
        data.typed = typed;
        // A rule with no When yet (a new one) is drawn with one, which has one action; nothing
        // is added to the caller's rule.
        java.util.List<Automations.When> whens = rule.whens;
        if (whens.isEmpty()) {
            Automations.When blank = new Automations.When();
            blank.then.add(new Automations.Step());
            whens = Collections.singletonList(blank);
        }
        int steps = 0;
        for (Automations.When when : whens) {
            steps += Math.max(1, when.then.size());
        }
        StringBuilder html = new StringBuilder();
        html.append("<form method=\"post\" action=\"/api/automations/save\" class=\"automation\" id=\"editor")
                .append(data.sfx).append("\"")
                // A draft drawn again from its form (a change of shape, a refused save) differs
                // from what is stored though no box differs from how it was drawn: marked, so
                // the stats poll never swaps it for the stored rule (admin_stats.js editingIn).
                .append(typed == null ? "" : " data-draft=\"1\"").append(">")
                // Enter in a box submits with the form's first submit button, and Add, Remove and
                // the moves are submit buttons too: this Save comes first, out of sight, so Enter
                // saves (review of 2026-09-27: Enter in the Name box used to post to Delete).
                .append("<button type=\"submit\" class=\"enter\" tabindex=\"-1\" aria-hidden=\"true\">Save</button>")
                .append("<input type=\"hidden\" name=\"id\" value=\"").append(escapeHtml(rule.id)).append("\">")
                .append(baselineField(baseline))
                // How many of each the form carries, so saveAutomation reads them all back.
                .append("<input type=\"hidden\" name=\"whens\" value=\"").append(whens.size()).append("\">")
                .append("<input type=\"hidden\" name=\"conditions\" value=\"")
                .append(rule.conditions.size()).append("\">")
                .append("<section class=\"card\">")
                // Words, not a machine value: capitals and the keyboard's corrections stay on.
                .append(fieldWithId("a-name" + data.sfx, "text", "name", "Name", rule.name, "", null)
                        .replace(MACHINE_TEXT, ""))
                .append("</section>");
        for (int index = 0; index < whens.size(); index++) {
            if (index > 0) {
                html.append("<p class=\"or\">or</p>");
            }
            html.append(whenBlock(index, whens.get(index), whens.size(), steps, data));
        }
        if (whens.size() < Automations.MAX_WHENS && steps < Automations.MAX_STEPS) {
            // On an "or" line after the last When, so it reads as one more When, not as the If
            // card's below it.
            html.append("<div class=\"or more\">or")
                    .append(addButton("add_when", "w" + whens.size() + data.sfx, "Add a When", "text"))
                    .append("</div>");
        }
        html.append(ifCard(rule, data))
                // One row, as the panel lays it out (2026-10-26): Save, then Remove, which asks
                // first. No Cancel: the back arrow leaves.
                .append("<div class=\"actions\">")
                .append("<button type=\"submit\" class=\"primary\">Save</button>")
                .append(fresh ? "" : "<button type=\"submit\" class=\"danger\""
                        + " formaction=\"/api/automations/delete\""
                        + " data-confirm=\"" + escapeHtml(removeQuestion(
                                stored == null ? rule.name : stored.name)) + "\">Remove</button>")
                .append("</div></form>");
        return html.toString();
    }

    /**
     * A structure change as a submit button: posts the whole form with {@code op} and lands on
     * {@code anchor}, the id of the part it changed. Never held up by a box the browser finds
     * wrong, since removing that box's part must stay possible.
     */
    private static String opButton(String op, String anchor, String classes, String label,
            String inner, boolean disabled) {
        return "<button type=\"submit\" name=\"op\" value=\"" + escapeHtml(op) + "\" formnovalidate"
                + " formaction=\"/api/automations/save#" + escapeHtml(anchor) + "\" class=\""
                + classes + "\" aria-label=\"" + escapeHtml(label) + "\""
                + (classes.startsWith("ib") ? " title=\"" + escapeHtml(label) + "\"" : "")
                + (disabled ? " disabled" : "") + ">" + inner + "</button>";
    }

    /** A Move up, Move down or Remove icon button. */
    private static String opIcon(String op, String anchor, String glyphName, String label,
            boolean disabled) {
        return opButton(op, anchor, "ib", label, glyph(glyphName), disabled);
    }

    /** A section's Add: the word on the button, what it adds in its spoken name. */
    private static String addButton(String op, String anchor, String spoken, String classes) {
        return opButton(op, anchor, classes, spoken, glyph("plus") + "Add", false);
    }

    /**
     * One When as its card: the trigger (a sensor and one of its events, with what the event
     * needs), then its actions in order. With several Whens the first says "any of these" and
     * each has a Remove; with several actions each has Move up, Move down and Remove.
     */
    private String whenBlock(int index, Automations.When when, int whenCount, int steps,
            EditorData data) {
        String prefix = "w" + index;
        java.util.List<Automations.Step> then = when.then;
        if (then.isEmpty()) {
            then = Collections.singletonList(new Automations.Step());
        }
        StringBuilder card = new StringBuilder();
        card.append("<section class=\"card when\" id=\"").append(prefix).append(data.sfx)
                .append("\" data-p=\"").append(prefix).append("\"><div class=\"cardhead\"><h2>")
                .append(glyph("bolt"))
                .append(whenCount > 1 && index == 0 ? "When (any of these)" : "When").append("</h2>");
        if (whenCount > 1) {
            card.append(opIcon("remove_when:" + index, "w" + Math.max(index - 1, 0) + data.sfx,
                    "minus", "Remove", false));
        }
        card.append("</div>")
                .append("<input type=\"hidden\" name=\"").append(prefix).append("_steps\" value=\"")
                .append(then.size()).append("\">")
                .append(triggerFields(prefix, when, data))
                .append("<h3 class=\"then\">").append(glyph("then"))
                .append(then.size() > 1 ? "Then, in order" : "Then")
                .append("</h3>");
        for (int step = 0; step < then.size(); step++) {
            card.append(stepRow(index, step, then.get(step), then.size(), data));
        }
        if (then.size() < Automations.MAX_STEPS_PER_WHEN && steps < Automations.MAX_STEPS) {
            card.append("<div class=\"actions tight\">")
                    .append(addButton("add_action:" + index,
                            prefix + "-a" + then.size() + data.sfx, "Add an action", "text"))
                    .append("</div>");
        }
        return card.append("</section>").toString();
    }

    /**
     * A When's sensor menu with its Choose button, the chosen sensor's events as radios, and
     * only the boxes those events need, each named with the When's prefix. Another sensor is
     * drawn by the server: Choose posts the form ("sensor:<w>"), which the script presses when
     * the menu changes, so a page carries one sensor's choices per When, not every sensor's.
     */
    private String triggerFields(String prefix, Automations.When when, EditorData data) {
        String sfx = data.sfx;
        // A new When starts on the proximity sensor where there is one, as on the panel, so
        // the two surfaces offer the same first rule; a chosen option is always rendered, so
        // the stats poll never takes a fresh editor for one being edited (review, 2026-10-01).
        String chosenSensor = when.sensor;
        if (chosenSensor.isEmpty()) {
            chosenSensor = available(data.block, "proximity") ? "proximity"
                    : firstAvailableSensor(data.block);
        }
        if (chosenSensor.isEmpty()) {
            // No sensor of the panel's own has an event: the clock, which every panel has.
            chosenSensor = Automations.TIME;
        }
        StringBuilder sensorOptions = new StringBuilder();
        boolean offered = false;
        for (Sensors.Def def : Sensors.ALL) {
            if (Automations.EVENTS.containsKey(def.id) && available(data.block, def.id)) {
                offered |= def.id.equals(chosenSensor);
                sensorOptions.append(selectOption(def.id, def.name, chosenSensor));
            }
        }
        // The clock: "At" a time of day.
        offered |= Automations.TIME.equals(chosenSensor);
        sensorOptions.append(selectOption(Automations.TIME, Automations.TIME_NAME, chosenSensor));
        // A camera's event, once there is a camera or a group to wait for.
        if (data.anyCamera || Automations.CAMERAS.equals(when.sensor)) {
            offered |= Automations.CAMERAS.equals(chosenSensor);
            sensorOptions.append(selectOption(Automations.CAMERAS, Automations.CAMERAS_NAME,
                    chosenSensor));
        }
        // The integrations' values, each a sensor of its own, with the events its type has.
        for (Map.Entry<String, String> value : data.values.entrySet()) {
            java.util.List<Automations.Event> list = Automations.events(value.getKey());
            if (list != null && !list.isEmpty()) {
                offered |= value.getKey().equals(chosenSensor);
                sensorOptions.append(selectOption(value.getKey(), value.getValue(), chosenSensor));
            }
        }
        if (!offered) {
            // A rule on a sensor this device lacks keeps it: saving the rule for its name must
            // not move it to the first sensor of the menu (review, 2026-10-01).
            sensorOptions.append(selectOption(chosenSensor, missingName(chosenSensor),
                    chosenSensor));
        }
        java.util.List<Automations.Event> list = Automations.events(chosenSensor);
        if (list == null) {
            list = Collections.emptyList();
        }
        // The chosen event, or the sensor's first: a form posted without scripting carries an
        // event even where the radio is hidden (one event is a line of text, label.radio.one).
        boolean known = false;
        for (Automations.Event event : list) {
            known |= event.id.equals(when.event);
        }
        boolean camera = Automations.CAMERAS.equals(chosenSensor);
        boolean level = false, at = false, holds = false, tag = false, text = false, cools = false;
        String one_ = list.size() == 1 ? " one" : "";
        StringBuilder events = new StringBuilder("<div class=\"radios events\" data-sensor=\"")
                .append(escapeHtml(chosenSensor)).append("\" role=\"radiogroup\" aria-label=\"")
                .append("Events\">");
        for (int index = 0; index < list.size(); index++) {
            Automations.Event event = list.get(index);
            boolean checked = known ? event.id.equals(when.event) : index == 0;
            level |= event.levelUnit != null && !Automations.TIME_UNIT.equals(event.levelUnit);
            at |= Automations.TIME_UNIT.equals(event.levelUnit);
            holds |= event.holds;
            tag |= event.oneShot && !camera;
            text |= event.matches && !event.oneShot;
            cools |= Automations.cools(event);
            events.append("<label class=\"radio").append(one_).append("\"><input type=\"radio\" name=\"")
                    .append(prefix).append("_event_").append(escapeHtml(chosenSensor))
                    .append("\" value=\"").append(event.id).append("\"")
                    .append(checked ? " checked" : "")
                    // Present when the event has a level, its unit perhaps empty (a count).
                    .append(event.levelUnit == null ? "" : " data-level=\""
                            + escapeHtml(event.levelUnit) + "\"")
                    .append(" data-holds=\"").append(event.holds ? "1" : "")
                    .append("\" data-tag=\"").append(event.oneShot ? "1" : "")
                    .append("\" data-camera=\"").append(camera ? "1" : "")
                    .append("\" data-text=\"").append(event.matches && !event.oneShot ? "1" : "")
                    .append("\"").append(coolsAttributes(event)).append(">")
                    .append(escapeHtml(event.label)).append("</label>");
        }
        events.append("</div>");
        String id = "a-" + prefix + "-";
        StringBuilder html = new StringBuilder("<div class=\"menurow\">")
                .append(selectField(id + "sensor" + sfx, prefix + "_sensor", "Sensor", null,
                        sensorOptions.toString(), null))
                .append(chooseButton("sensor:" + prefix.substring(1), prefix + sfx))
                .append("</div>")
                .append(stopsNote(chosenSensor))
                .append(events);
        if (level) {
            html.append(fieldWithId(id + "level" + sfx, "number", prefix + "_level", "Level",
                    data.shown(when, "level",
                            Double.isNaN(when.level) ? "" : Automations.number(when.level)),
                    " step=\"any\"", null));
        }
        if (at) {
            html.append(fieldWithId(id + "at" + sfx, "time", prefix + "_at", "At",
                    data.shown(when, "at", Automations.TIME.equals(when.sensor)
                            && !Double.isNaN(when.level)
                            ? Automations.clock((int) when.level) : "07:00"), "", null));
        }
        if (holds) {
            html.append(fieldWithId(id + "minutes" + sfx, "number", prefix + "_minutes",
                    "For (minutes)", data.shown(when, "minutes",
                            when.minutes == 0 ? "" : Integer.toString(when.minutes)),
                    " min=\"0\" max=\"" + Automations.MAX_MINUTES + "\"", null));
        }
        if (tag) {
            // Every tag read lately, by its id, newest first, as the panel's own editor has it.
            StringBuilder tags = new StringBuilder(selectOption("", "Any tag", when.match));
            for (String seen : data.tagsRead) {
                tags.append(selectOption(seen, seen, when.match));
            }
            if (!when.match.isEmpty() && !data.tagsRead.contains(when.match)) {
                tags.append(selectOption(when.match, when.match, when.match));
            }
            html.append(selectField(id + "tag" + sfx, prefix + "_tag", "Tag", null,
                    tags.toString(), null));
        }
        if (text) {
            // The text an integration's value must become ("Busy"), upper and lower case
            // alike.
            html.append(fieldWithId(id + "text" + sfx, "text", prefix + "_match_text", "Text",
                    Integrations.isSensorId(when.sensor) ? when.match : "", "",
                    "Upper and lower case count the same.").replace(MACHINE_TEXT, ""));
        }
        if (camera) {
            html.append(selectField(id + "camera" + sfx, prefix + "_camera_tag",
                    "Camera or group", null, cameraOptions(data.cameras, data.groups,
                            Automations.CAMERAS.equals(when.sensor) ? when.match : ""), null));
        }
        if (cools) {
            html.append(fieldWithId(id + "cooldown" + sfx, "number", prefix + "_cooldown",
                    "Not again within (seconds)",
                    data.shown(when, "cooldown", Integer.toString(when.cooldown)),
                    " min=\"0\" max=\"" + Automations.MAX_COOLDOWN + "\"", null));
        }
        return html.toString();
    }

    /**
     * Choose beside a Sensor menu: posts the form so the server draws the chosen sensor's
     * choices, landing on {@code anchor}, the part's own row. Hidden where the script runs,
     * which presses it when the menu changes.
     */
    private static String chooseButton(String op, String anchor) {
        return opButton(op, anchor, "choose", "Choose", "Choose", false);
    }

    /** Whether the sensors block says the panel has the sensor. */
    private static boolean available(org.json.JSONObject block, String sensorId) {
        org.json.JSONObject one = block.optJSONObject(sensorId);
        return one != null && one.optBoolean("available");
    }

    /** A sensor a rule names that this device does not offer, as its menu shows it. */
    private static String missingName(String sensor) {
        Sensors.Def missing = Sensors.byId(sensor);
        return (missing == null ? sensor : missing.name) + " (not on this device)";
    }

    /**
     * One action of a When, its boxes named "w1_a2_..." and its row's id the anchor its moves
     * land on; with several actions, Move up, Move down (greyed at the ends) and Remove.
     */
    private String stepRow(int whenIndex, int index, Automations.Step step, int count,
            EditorData data) {
        String prefix = "w" + whenIndex + "_a" + index;
        String row = "w" + whenIndex + "-a";
        String id = "a-" + prefix + "-";
        String sfx = data.sfx;
        String chosenAction = step.action.isEmpty() ? "display_on" : step.action;
        StringBuilder actionOptions = new StringBuilder();
        for (Automations.Action action : Automations.ACTIONS) {
            boolean camera = action.id.equals("camera");
            // Show a camera once there is one to show.
            if (camera && !data.anyCamera && !camera(chosenAction)) {
                continue;
            }
            actionOptions.append(selectOption(action.id, action.label, chosenAction));
        }
        // Only the boxes the chosen action uses, its own words on its text box ("Colour
        // (r,g,b)", "Message"); another action is drawn by the server, as another sensor is:
        // Choose posts the form ("action:<w>:<s>"), which the script presses on a change.
        Automations.Action chosen = Automations.action(chosenAction);
        boolean waiting = Automations.waits(chosenAction);
        boolean cameraAction = camera(chosenAction);
        boolean shows = Automations.shows(chosenAction);
        StringBuilder html = new StringBuilder();
        // With several actions the moves stand beside the Action menu from 600 px (the stylesheet,
        // by "moving"), under the row below that.
        html.append("<div class=\"step").append(count > 1 ? " moving" : "").append("\" id=\"")
                .append(row).append(index).append(sfx)
                .append("\" data-p=\"").append(prefix).append("\"><div class=\"menurow\">")
                .append(selectField(id + "action" + sfx, prefix + "_action", "Action", null,
                        actionOptions.toString(), null))
                .append(chooseButton("action:" + whenIndex + ":" + index, row + index + sfx))
                .append("</div>");
        if (chosen != null && chosen.argumentLabel != null && !cameraAction) {
            // The address keyboard for an address, as the panel's box has it; a sentence or a
            // message keeps the keyboard's capitals and corrections, a machine value (a colour,
            // a topic) does not.
            boolean address = chosenAction.equals("play_sound") || chosenAction.equals("open_page")
                    || chosenAction.equals("call_url");
            boolean words = chosenAction.equals("say") || chosenAction.equals("popup");
            String argument = fieldWithId(id + "argument" + sfx, "text", prefix + "_argument",
                    chosen.argumentLabel, step.argument, address ? " inputmode=\"url\"" : "", null);
            html.append(words ? argument.replace(MACHINE_TEXT, "") : argument);
        }
        if (cameraAction) {
            html.append(selectField(id + "target" + sfx, prefix + "_target", "Camera or group",
                    null, cameraOptions(data.cameras, data.groups,
                            camera(step.action) ? step.argument : ""), null))
                    .append(selectField(id + "show" + sfx, prefix + "_show", "Show as", null,
                            selectOption(Automations.SHOW_POPUP, "Pop-up", step.show)
                                    + selectOption(Automations.SHOW_FULLSCREEN, "Full screen",
                                            step.show), null));
        }
        if (waiting || shows) {
            // A Wait's length in the same box as a pop-up's, with its own caption, bounds and
            // the timer glyph at its start.
            html.append(fieldWithId(id + "seconds" + sfx, "number", prefix + "_seconds",
                    waiting ? "Seconds" : "For (seconds, 0 = until touched or closed)",
                    data.shown(step, "seconds", Integer.toString(step.seconds)), waiting
                            ? " min=\"" + Automations.MIN_WAIT + "\" max=\"" + Automations.MAX_WAIT + "\""
                            : " min=\"0\" max=\"" + Popup.MAX_SECONDS + "\"", null)
                    .replace("<div class=\"field\">", "<div class=\"field" + (waiting ? " wait" : "")
                            + "\"><span class=\"lead\">" + glyph("timer") + "</span>"));
        }
        if (shows) {
            html.append(switchRow(id + "chime" + sfx, "Chime",
                    " name=\"" + prefix + "_chime\" value=\"1\"", step.chime));
        }
        if (count > 1) {
            String op = whenIndex + ":" + index;
            html.append("<div class=\"moves\">")
                    .append(opIcon("up:" + op, row + (index - 1) + sfx, "arrow_up", "Move up",
                            index == 0))
                    .append(opIcon("down:" + op, row + (index + 1) + sfx, "arrow_down",
                            "Move down", index == count - 1))
                    .append(opIcon("remove_action:" + op, row + Math.max(index - 1, 0) + sfx,
                            "minus", "Remove", false))
                    .append("</div>");
        }
        return html.append("</div>").toString();
    }

    /**
     * The If card: the conditions, each a row, "All of these" or "Any of these" once there are
     * two, and Add. The rule runs only when they hold as a When starts it.
     */
    private String ifCard(Automations.Rule rule, EditorData data) {
        int count = rule.conditions.size();
        StringBuilder card = new StringBuilder();
        card.append("<section class=\"card gap\" id=\"if").append(data.sfx).append("\"><h2 class=\"part\">")
                .append(glyph("if")).append("If</h2>");
        if (count > 1) {
            card.append("<div class=\"radios join\" role=\"radiogroup\" aria-label=\"If\">")
                    .append("<label class=\"radio\"><input type=\"radio\" name=\"if_any\" value=\"0\"")
                    .append(rule.anyCondition ? "" : " checked").append(">All of these</label>")
                    .append("<label class=\"radio\"><input type=\"radio\" name=\"if_any\" value=\"1\"")
                    .append(rule.anyCondition ? " checked" : "").append(">Any of these</label>")
                    .append("</div>");
        }
        for (int index = 0; index < count; index++) {
            card.append(conditionRow(index, rule.conditions.get(index), data));
        }
        if (count < Automations.MAX_CONDITIONS) {
            card.append("<div class=\"actions tight\">")
                    .append(addButton("add_condition", "c" + count + data.sfx, "Add a condition",
                            "text"))
                    .append("</div>");
        }
        return card.append("</section>").toString();
    }

    private static final String[] DAY_CHIPS = {"Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"};

    /**
     * One If condition: its sensor menu (Time of day first, then the device's sensors and the
     * integrations' values that have a state to ask about) with Choose and Remove at the row's
     * head; then the chosen sensor's states as radios with a level or a text where they have
     * one, or for a time of day From, To and the days as filter chips. Another sensor is drawn
     * by the server, as in a When ("csensor:<c>").
     */
    private String conditionRow(int index, Automations.Condition condition, EditorData data) {
        String prefix = "c" + index;
        String id = "a-" + prefix + "-";
        String sfx = data.sfx;
        String chosen = condition.sensor.isEmpty() ? Automations.TIME : condition.sensor;
        StringBuilder sensorOptions = new StringBuilder(
                selectOption(Automations.TIME, Automations.TIME_NAME, chosen));
        boolean offered = Automations.TIME.equals(chosen);
        Map<String, String> sensors = new java.util.LinkedHashMap<>();
        for (Sensors.Def def : Sensors.ALL) {
            if (Automations.EVENTS.containsKey(def.id) && available(data.block, def.id)) {
                sensors.put(def.id, def.name);
            }
        }
        sensors.putAll(data.values);
        for (Map.Entry<String, String> sensor : sensors.entrySet()) {
            // A tag read, a camera, a Changes: nothing a sensor stays in, so no condition.
            if (!stateEvents(sensor.getKey()).isEmpty()) {
                offered |= sensor.getKey().equals(chosen);
                sensorOptions.append(selectOption(sensor.getKey(), sensor.getValue(), chosen));
            }
        }
        if (!offered) {
            sensorOptions.append(selectOption(chosen, missingName(chosen), chosen));
        }
        StringBuilder html = new StringBuilder("<div class=\"cond\" id=\"").append(prefix)
                .append(sfx).append("\" data-p=\"").append(prefix).append("\">")
                .append("<div class=\"condhead\">")
                .append(selectField(id + "sensor" + sfx, prefix + "_sensor", "Sensor", null,
                        sensorOptions.toString(), null))
                .append(chooseButton("csensor:" + index, prefix + sfx))
                .append(opIcon("remove_condition:" + index, "if" + sfx, "minus", "Remove", false))
                .append("</div>");
        if (Automations.TIME.equals(chosen)) {
            StringBuilder days = new StringBuilder();
            for (int day = 1; day <= 7; day++) {
                boolean on = (condition.days & 1 << (day - 1)) != 0;
                days.append("<label class=\"fchip\"><input type=\"checkbox\" name=\"").append(prefix)
                        .append("_day_").append(day).append("\" value=\"1\"")
                        .append(on ? " checked" : "").append(">").append(glyph("check"))
                        .append("<span>").append(DAY_CHIPS[day - 1]).append("</span></label>");
            }
            return html.append("<div class=\"two\">")
                    .append(fieldWithId(id + "from" + sfx, "time", prefix + "_from", "From",
                            data.shown(condition, "from", condition.timed()
                                    ? Automations.clock(condition.from) : ""), "", null))
                    .append(fieldWithId(id + "to" + sfx, "time", prefix + "_to", "To",
                            data.shown(condition, "to", condition.timed()
                                    ? Automations.clock(condition.to) : ""), "", null))
                    .append("</div><div class=\"days\" role=\"group\" aria-label=\"Days\">")
                    .append(days).append("</div></div>").toString();
        }
        java.util.List<Automations.Event> states = stateEvents(chosen);
        boolean known = false;
        for (Automations.Event event : states) {
            known |= event.id.equals(condition.state);
        }
        boolean integration = Integrations.isSensorId(chosen);
        String valueName = sensors.containsKey(chosen) ? sensors.get(chosen) : chosen;
        boolean level = false, text = false;
        String one_ = states.size() == 1 ? " one" : "";
        html.append("<div class=\"radios events\" data-sensor=\"").append(escapeHtml(chosen))
                .append("\" role=\"radiogroup\" aria-label=\"States\">");
        for (int at = 0; at < states.size(); at++) {
            Automations.Event event = states.get(at);
            boolean checked = known ? event.id.equals(condition.state) : at == 0;
            level |= event.levelUnit != null;
            text |= event.matches;
            html.append("<label class=\"radio").append(one_).append("\"><input type=\"radio\" name=\"")
                    .append(prefix).append("_state_").append(escapeHtml(chosen))
                    .append("\" value=\"").append(event.id).append("\"")
                    .append(checked ? " checked" : "")
                    .append(event.levelUnit == null ? "" : " data-level=\""
                            + escapeHtml(event.levelUnit) + "\"")
                    .append(" data-text=\"").append(event.matches ? "1" : "").append("\">")
                    // An integration's state words follow the value's name: "Price above".
                    .append(escapeHtml(integration ? valueName + " " + event.stateLabel
                            : event.stateLabel))
                    .append("</label>");
        }
        html.append("</div>");
        if (level) {
            html.append(fieldWithId(id + "level" + sfx, "number", prefix + "_level", "Level",
                    data.shown(condition, "level", Double.isNaN(condition.level) ? ""
                            : Automations.number(condition.level)),
                    " step=\"any\"", null));
        }
        if (text) {
            html.append(fieldWithId(id + "text" + sfx, "text", prefix + "_text", "Text",
                    condition.match, "", "Upper and lower case count the same.")
                    .replace(MACHINE_TEXT, ""));
        }
        return html.append("</div>").toString();
    }

    /** The events of a sensor an If condition can ask about, the states it stays in. */
    private static java.util.List<Automations.Event> stateEvents(String sensor) {
        java.util.List<Automations.Event> states = new java.util.ArrayList<>();
        java.util.List<Automations.Event> list = Automations.events(sensor);
        for (Automations.Event event : list == null
                ? Collections.<Automations.Event>emptyList() : list) {
            if (Automations.conditionEvent(event)) {
                states.add(event);
            }
        }
        return states;
    }

    /**
     * "sensor:<w>", "csensor:<c>" and "action:<w>:<s>": the Sensor menu of a When or a
     * condition, or an action's Action menu, changed (its Choose, which the script presses on a
     * change). The part starts over on the sensor or action the form names: a sensor with its
     * first event or state and that event's "Not again within", an action with no argument and
     * the default seconds. The boxes as typed in it are dropped, being about the choice before.
     * Whether the op was one of these; a part that is not there changes nothing.
     */
    private static boolean choose(AutomationForm draft, String op) {
        String[] parts = op.split(":", -1);
        boolean kind = parts[0].equals("sensor") || parts[0].equals("csensor")
                || parts[0].equals("action");
        for (int part = 1; part < parts.length; part++) {
            if (!parts[part].matches("\\d{1,2}")) {
                return kind;
            }
        }
        if (parts[0].equals("action")) {
            if (parts.length == 3 && Integer.parseInt(parts[1]) < draft.rule.whens.size()) {
                java.util.List<Automations.Step> then =
                        draft.rule.whens.get(Integer.parseInt(parts[1])).then;
                int index = Integer.parseInt(parts[2]);
                if (index < then.size()) {
                    Automations.Step step = then.get(index);
                    step.argument = "";
                    step.seconds = Popup.DEFAULT_SECONDS;
                    step.chime = false;
                    step.show = Automations.SHOW_POPUP;
                    draft.prefixes.remove(step);
                }
            }
            return true;
        }
        if (parts.length != 2) {
            return kind;
        }
        int index = Integer.parseInt(parts[1]);
        if (parts[0].equals("sensor")) {
            if (index < draft.rule.whens.size()) {
                Automations.When when = draft.rule.whens.get(index);
                java.util.List<Automations.Event> list = Automations.events(when.sensor);
                Automations.Event first = list == null || list.isEmpty() ? null : list.get(0);
                when.event = first == null ? "" : first.id;
                when.level = Double.NaN;
                when.minutes = 0;
                when.match = "";
                when.cooldown = Automations.defaultCooldown(first);
                draft.prefixes.remove(when);
            }
            return true;
        }
        if (parts[0].equals("csensor")) {
            if (index < draft.rule.conditions.size()) {
                Automations.Condition condition = draft.rule.conditions.get(index);
                java.util.List<Automations.Event> states = stateEvents(condition.sensor);
                condition.state = states.isEmpty() ? "" : states.get(0).id;
                condition.level = Double.NaN;
                condition.match = "";
                condition.from = -1;
                condition.to = -1;
                condition.days = Automations.ALL_DAYS;
                draft.prefixes.remove(condition);
            }
            return true;
        }
        return false;
    }

    /**
     * On an event radio whose When has a "Not again within" (a tag, a camera, a Changes): the
     * mark admin_automation.js shows the box by, and the seconds it starts at when the event is
     * picked.
     */
    private static String coolsAttributes(Automations.Event event) {
        return Automations.cools(event) ? " data-cools=\"1\" data-cooldown=\""
                + Automations.defaultCooldown(event) + "\"" : "";
    }

    private static boolean camera(String action) {
        return "camera".equals(action);
    }

    /** The cameras, then the groups (marked as such), with {@code chosen} selected. */
    private static String cameraOptions(java.util.List<Cameras.Camera> cameras,
            java.util.List<Cameras.Group> groups, String chosen) {
        StringBuilder options = new StringBuilder();
        boolean found = false;
        for (Cameras.Camera camera : cameras) {
            options.append(selectOption(camera.name, camera.name, chosen));
            found |= camera.name.equalsIgnoreCase(chosen);
        }
        for (Cameras.Group group : groups) {
            options.append(selectOption(group.name, group.name + " (group)", chosen));
            found |= group.name.equalsIgnoreCase(chosen);
        }
        if (!chosen.isEmpty() && !found) {
            // Removed since: kept, so saving the rule does not move it to another camera.
            options.append(selectOption(chosen, chosen + " (gone)", chosen));
        }
        return options.toString();
    }

    /** A "Time of day" rule's time box to its level, the minute of the day; NaN when it is none. */
    private static double timeLevel(String typed) {
        int minutes = Automations.minutesOf(typed);
        return minutes < 0 || minutes >= 24 * 60 ? Double.NaN : minutes;
    }

    /** The first sensor the editor offers, for a new rule on a panel with no proximity sensor. */
    private static String firstAvailableSensor(org.json.JSONObject block) {
        for (Sensors.Def def : Sensors.ALL) {
            org.json.JSONObject one = block.optJSONObject(def.id);
            if (Automations.EVENTS.containsKey(def.id) && one != null
                    && one.optBoolean("available")) {
                return def.id;
            }
        }
        return "";
    }

    /**
     * Under a When's sensor, for a sensor whose test found it not running while the display
     * sleeps, the panel's own note; nothing for any other. Another sensor is drawn again by
     * the server, so the note follows the menu.
     */
    private String stopsNote(String chosen) {
        return !KioskService.stopsWhileAsleep(context, chosen) ? ""
                : "<ul class=\"list stopsnote\">"
                        + listRow("asleep_off", "Not running while the display is off", null,
                                "The black film keeps it running", null, "", false)
                        + "</ul>";
    }

    // ---- The documents the fleet of 0.7 reads and replaces (2026-09-30): the automations, the
    // sensor settings and the beacons' names, each whole, checked by the rules the pages use.

    private void writeJson(OutputStream output, int status, JSONObject document)
            throws IOException {
        writeResponse(output, status, "application/json", bytes(document.toString()));
    }

    private void refuseJson(OutputStream output, String detail) throws IOException {
        writeResponse(output, 400, "application/json", bytes("{\"status\":\"rejected\","
                + "\"detail\":" + JSONObject.quote(detail) + "}"));
    }

    /** The body as a JSON object, or null after a refusal was written. */
    private JSONObject jsonBody(Map<String, String> headers, byte[] body, OutputStream output)
            throws IOException {
        if (!headers.getOrDefault("content-type", "").contains("application/json")) {
            refuseJson(output, "send the document as application/json");
            return null;
        }
        try {
            return new JSONObject(new String(body, StandardCharsets.UTF_8));
        } catch (JSONException malformed) {
            refuseJson(output, "malformed json");
            return null;
        }
    }

    /** {"rules": [...], "deleted": [{"id", "deleted_at"}]}, each rule with its changed_at. */
    private JSONObject automationsDocument() {
        JSONObject document = new JSONObject();
        try {
            document.put("rules", Automations.toJson(KioskConfig.automationsOf(context)));
            document.put("deleted", new org.json.JSONArray(KioskConfig.automationsDeleted(context)));
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
        return document;
    }

    /**
     * Replaces every rule with {"rules": [...]}, the shape GET gives: each checked by the
     * editor's rule book, a rule without an id (or with one used twice) given a new one, at most
     * MAX_RULES; stored through the one path, so each rule's change time and the markers of the
     * rules left out follow. changed_at and deleted are the panel's own and a post's copies are
     * ignored. All or nothing.
     */
    private void replaceAutomations(Map<String, String> headers, byte[] body, OutputStream output)
            throws IOException {
        JSONObject document = jsonBody(headers, body, output);
        if (document == null) {
            return;
        }
        org.json.JSONArray array = document.optJSONArray("rules");
        if (array == null) {
            refuseJson(output, "the document needs a rules array");
            return;
        }
        if (array.length() > Automations.MAX_RULES) {
            refuseJson(output, "at most " + Automations.MAX_RULES + " automations");
            return;
        }
        java.util.List<Automations.Rule> rules = new java.util.ArrayList<>();
        for (int index = 0; index < array.length(); index++) {
            JSONObject one = array.optJSONObject(index);
            if (one == null) {
                refuseJson(output, "rule " + (index + 1) + " is not an object");
                return;
            }
            rules.add(Automations.parseRule(one));
        }
        synchronized (Automations.STORE) {
            java.util.Set<String> ids = new java.util.HashSet<>();
            for (Automations.Rule rule : rules) {
                if (rule.id.isEmpty() || !ids.add(rule.id)) {
                    rule.id = Automations.newId(rules);
                    ids.add(rule.id);
                }
                String problem = Automations.validate(rule, Sensors.names());
                if (problem != null) {
                    refuseJson(output, "\"" + rule.name + "\": " + problem);
                    return;
                }
            }
            String tooMuch = Automations.checkList(rules);
            if (tooMuch != null) {
                refuseJson(output, tooMuch);
                return;
            }
            KioskConfig.storeAutomations(context, rules);
        }
        KioskService.refreshSensorsSoon(context);
        writeJson(output, 200, automationsDocument());
    }

    /**
     * Applies {"switches": {"<id>": {"on": true}}, "shared": {"<key>": {"value": "..."}}}, the
     * shape GET gives: each switch and shared setting checked as the pages check it, all before
     * anything is written. A setting of this panel alone under shared is refused, the fleet never
     * pushes one (SensorSettings); this_panel and changed_at are the panel's own and ignored.
     */
    private void applySensorSettings(Map<String, String> headers, byte[] body,
            OutputStream output) throws IOException {
        JSONObject document = jsonBody(headers, body, output);
        if (document == null) {
            return;
        }
        Map<String, String> values = new java.util.LinkedHashMap<>();
        JSONObject shared = document.optJSONObject("shared");
        java.util.Iterator<String> keys = shared == null ? null : shared.keys();
        while (keys != null && keys.hasNext()) {
            String key = keys.next();
            if (key.startsWith("beacon_")) {
                // The names are a document of their own; stored here they went into the old
                // loose keys the names list no longer reads (review, 2026-10-01).
                refuseJson(output, "beacon names go through /api/beacons/names");
                return;
            }
            if (!SensorSettings.shared(key)) {
                refuseJson(output, key + " belongs to this panel alone");
                return;
            }
            JSONObject one = shared.optJSONObject(key);
            Object value = one == null ? null : one.opt("value");
            if (!(value instanceof String)) {
                refuseJson(output, key + " needs {\"value\": \"...\"}");
                return;
            }
            String problem = Sensors.checkOption(key, (String) value);
            if (problem != null) {
                refuseJson(output, key + ": " + problem);
                return;
            }
            values.put(key, Sensors.cleanOption(key, (String) value));
        }
        Map<String, Boolean> switchesWanted = new java.util.LinkedHashMap<>();
        JSONObject switches = document.optJSONObject("switches");
        java.util.Iterator<String> ids = switches == null ? null : switches.keys();
        while (ids != null && ids.hasNext()) {
            String id = ids.next();
            JSONObject one = switches.optJSONObject(id);
            Object on = one == null ? null : one.opt("on");
            if (!(on instanceof Boolean)) {
                refuseJson(output, id + " needs {\"on\": true} or {\"on\": false}");
                return;
            }
            Sensors.Def def = Sensors.byId(id);
            // Judged against the switch as it stands, which for the panel's own values is on
            // with nothing stored: against a stored-or-false default, display could not be
            // switched off and switching it on stamped a change that never happened (review,
            // 2026-10-01).
            if (def != null && (Boolean) on == Sensors.enabled(context, def)) {
                continue;
            }
            String problem = kioskService.sensorSwitchProblem(id, (Boolean) on);
            if (problem != null) {
                refuseJson(output, problem);
                return;
            }
            switchesWanted.put(id, (Boolean) on);
        }
        KioskConfig.Editor editor = KioskConfig.edit(context);
        for (Map.Entry<String, String> value : values.entrySet()) {
            editor.sensorOption(value.getKey(), value.getValue());
        }
        for (Map.Entry<String, Boolean> wanted : switchesWanted.entrySet()) {
            editor.sensorEnabled(wanted.getKey(), wanted.getValue());
        }
        editor.apply();
        KioskService.refreshSensorsSoon(context);
        writeJson(output, 200, KioskConfig.sensorSettingsDocument(context));
    }

    /** Names in one post: twice the beacons the panel keeps (Beacons.MAX_SEEN). */
    private static final int MAX_BEACON_NAMES = 200;

    /**
     * Applies {"names": [{"id": "<uuid:major:minor>", "name": "..."}]}, the shape GET gives; a
     * blank name takes it away, a beacon left out keeps its name. changed_at and deleted are
     * the panel's own and ignored.
     */
    private void applyBeaconNames(Map<String, String> headers, byte[] body, OutputStream output)
            throws IOException {
        JSONObject document = jsonBody(headers, body, output);
        if (document == null) {
            return;
        }
        org.json.JSONArray names = document.optJSONArray("names");
        if (names == null) {
            refuseJson(output, "the document needs a names array");
            return;
        }
        if (names.length() > MAX_BEACON_NAMES) {
            refuseJson(output, "at most " + MAX_BEACON_NAMES + " names in one document");
            return;
        }
        Map<String, String> wanted = new java.util.LinkedHashMap<>();
        for (int index = 0; index < names.length(); index++) {
            JSONObject one = names.optJSONObject(index);
            String id = one == null ? "" : one.optString("id", "");
            if (id.isEmpty()) {
                refuseJson(output, "name " + (index + 1) + " needs an id");
                return;
            }
            String name = one.optString("name", "");
            String problem = Sensors.checkBeaconName(id, name);
            if (problem != null) {
                refuseJson(output, id + ": " + problem);
                return;
            }
            wanted.put(id, name);
        }
        for (Map.Entry<String, String> name : wanted.entrySet()) {
            KioskConfig.beaconName(context, name.getKey(), name.getValue());
        }
        KioskService.publishTelemetrySoon(context);
        writeResponse(output, 200, "application/json",
                bytes(KioskConfig.beaconNames(context).store()));
    }

    /**
     * The editor's form: Save stores the rule; a button named op (Add, Remove, Move up, Move
     * down) changes the draft's shape and draws it again, storing nothing.
     */
    private void saveAutomation(Map<String, String> form, OutputStream output)
            throws IOException {
        java.util.List<Automations.Rule> rules = KioskConfig.automationsOf(context);
        String id = form.getOrDefault("id", "").trim();
        Automations.Rule existing = Automations.find(rules, id);
        if (!id.isEmpty() && existing == null) {
            // Saved from a page whose rule was deleted meanwhile: not brought back under a
            // new id.
            writeResponse(output, 200, "text/html; charset=utf-8", bytes(
                    buildAutomationsPage("Not saved: that automation was deleted.")));
            return;
        }
        if (existing != null) {
            String stale = staleFormRefusal(form, Automations.baseline(existing));
            if (stale != null) {
                writeResponse(output, 200, "text/html; charset=utf-8",
                        bytes(buildAutomationPage(existing, stale)));
                return;
            }
        }
        AutomationForm draft = readAutomationForm(form, existing);
        Automations.Rule rule = draft.rule;
        // The baseline the page carried goes on with the draft, so the Save at the end is
        // still judged against the rule as it was when the page loaded.
        String baseline = form.getOrDefault("baseline", "");
        String op = form.getOrDefault("op", "");
        if (!op.isEmpty()) {
            // A menu's Choose draws what the new sensor or action needs; any other op changes the
            // shape. A change past a limit or of a part that is not there changes nothing; the
            // buttons for those are not drawn, so only a stale or crafted form asks for one.
            if (!choose(draft, op)) {
                Automations.applyOp(rule, op);
            }
            writeResponse(output, 200, "text/html; charset=utf-8",
                    bytes(buildAutomationPage(rule, null, baseline, draft)));
            return;
        }
        String problem = Automations.validate(rule, Sensors.names());
        if (problem != null) {
            // Still without an id when it is new, so the page it goes back to is the one for
            // a new rule, without a Delete for a rule that was never stored.
            writeResponse(output, 200, "text/html; charset=utf-8", bytes(buildAutomationPage(
                    rule, "Not saved: " + problem + ".", baseline, draft)));
            return;
        }
        synchronized (Automations.STORE) {
            rules = KioskConfig.automationsOf(context);
            existing = Automations.find(rules, id);
            if (existing == null) {
                // The cap judged under the lock, so two adds at once cannot pass it together.
                if (rules.size() >= Automations.MAX_RULES) {
                    writeResponse(output, 200, "text/html; charset=utf-8", bytes(
                            buildAutomationPage(rule, "Not saved: there are already "
                                    + Automations.MAX_RULES + " automations.", baseline, draft)));
                    return;
                }
                rule.id = Automations.newId(rules);
                rules.add(rule);
            } else {
                // The switch beside the editor may have been flipped while it was open, on any
                // surface: the stored state stands, the editor never carried it.
                rule.enabled = existing.enabled;
                rules.set(rules.indexOf(existing), rule);
            }
            String tooMuch = Automations.checkList(rules);
            if (tooMuch != null) {
                if (existing == null) {
                    // The page for a new rule again, as for any other refusal.
                    rule.id = "";
                }
                writeResponse(output, 200, "text/html; charset=utf-8", bytes(buildAutomationPage(
                        rule, "Not saved: " + tooMuch + ".", baseline, draft)));
                return;
            }
            KioskConfig.storeAutomations(context, rules);
        }
        KioskService.refreshSensorsSoon(context);
        writeResponse(output, 303, "text/plain", bytes("saved"),
                Collections.singletonMap("Location", "/automations?id=" + rule.id));
    }

    /**
     * The whole rule as the editor's form carries it: the Whens, each with its actions, and
     * the conditions, by the indexed names automationEditor gives them ("w1_a2_action",
     * "c0_from") and the counts it writes beside them. A count is read one past its limit at
     * most, so validate can name the limit, and never further.
     */
    private static AutomationForm readAutomationForm(Map<String, String> form,
            Automations.Rule existing) {
        Automations.Rule rule = new Automations.Rule();
        if (existing != null) {
            rule.id = existing.id;
            rule.enabled = existing.enabled;
            rule.changedAt = existing.changedAt;
        }
        rule.name = form.getOrDefault("name", "");
        AutomationForm draft = new AutomationForm(rule, form);
        int whens = formCount(form, "whens", Automations.MAX_WHENS);
        for (int index = 0; index < whens; index++) {
            String prefix = "w" + index;
            Automations.When when = readWhen(form, prefix);
            draft.prefixes.put(when, prefix);
            int steps = formCount(form, prefix + "_steps", Automations.MAX_STEPS_PER_WHEN);
            for (int step = 0; step < steps; step++) {
                String stepPrefix = prefix + "_a" + step;
                Automations.Step read = readStep(form, stepPrefix);
                draft.prefixes.put(read, stepPrefix);
                when.then.add(read);
            }
            rule.whens.add(when);
        }
        int conditions = formCount(form, "conditions", Automations.MAX_CONDITIONS);
        for (int index = 0; index < conditions; index++) {
            String prefix = "c" + index;
            Automations.Condition condition = readCondition(form, prefix);
            draft.prefixes.put(condition, prefix);
            rule.conditions.add(condition);
        }
        rule.anyCondition = "1".equals(form.get("if_any"));
        return draft;
    }

    /** A count the form carries, 0 when missing or not a number, at most {@code limit} + 1. */
    private static int formCount(Map<String, String> form, String key, int limit) {
        String text = form.getOrDefault(key, "").trim();
        return text.matches("\\d{1,3}") ? Math.min(Integer.parseInt(text), limit + 1) : 0;
    }

    private static Automations.When readWhen(Map<String, String> form, String prefix) {
        Automations.When when = new Automations.When();
        when.sensor = form.getOrDefault(prefix + "_sensor", "");
        when.event = form.getOrDefault(prefix + "_event_" + when.sensor, "");
        when.level = Automations.TIME.equals(when.sensor)
                ? timeLevel(form.get(prefix + "_at")) : parseLevel(form.get(prefix + "_level"));
        when.minutes = parseMinutes(form.get(prefix + "_minutes"));
        when.match = (Automations.CAMERAS.equals(when.sensor)
                ? form.getOrDefault(prefix + "_camera_tag", "")
                : Integrations.isSensorId(when.sensor)
                        ? form.getOrDefault(prefix + "_match_text", "")
                        : form.getOrDefault(prefix + "_tag", "")).trim();
        String cooldown = form.getOrDefault(prefix + "_cooldown", "").trim();
        when.cooldown = cooldown.isEmpty() ? 0 : cooldown.matches("\\d{1,5}")
                ? Integer.parseInt(cooldown) : -1;
        return when;
    }

    private static Automations.Step readStep(Map<String, String> form, String prefix) {
        Automations.Step step = new Automations.Step();
        step.action = form.getOrDefault(prefix + "_action", "");
        step.argument = camera(step.action) ? form.getOrDefault(prefix + "_target", "")
                : form.getOrDefault(prefix + "_argument", "");
        String seconds = form.getOrDefault(prefix + "_seconds", "").trim();
        step.seconds = seconds.isEmpty() ? Popup.DEFAULT_SECONDS : seconds.matches("\\d{1,4}")
                ? Integer.parseInt(seconds) : -1;
        step.chime = isTrue(form.getOrDefault(prefix + "_chime", "0"));
        step.show = form.getOrDefault(prefix + "_show", Automations.SHOW_POPUP);
        return step;
    }

    private static Automations.Condition readCondition(Map<String, String> form, String prefix) {
        Automations.Condition condition = new Automations.Condition();
        condition.sensor = form.getOrDefault(prefix + "_sensor", "");
        if (Automations.TIME.equals(condition.sensor)) {
            condition.from = conditionTime(form.get(prefix + "_from"));
            condition.to = conditionTime(form.get(prefix + "_to"));
            condition.days = 0;
            for (int day = 1; day <= 7; day++) {
                if (isTrue(form.getOrDefault(prefix + "_day_" + day, "0"))) {
                    condition.days |= 1 << (day - 1);
                }
            }
            return condition;
        }
        condition.state = form.getOrDefault(prefix + "_state_" + condition.sensor, "");
        condition.level = parseLevel(form.get(prefix + "_level"));
        condition.match = form.getOrDefault(prefix + "_text", "").trim();
        return condition;
    }

    /**
     * A time box of a time condition: empty is no time (the whole day), and anything that is
     * not a time of day a minute past the day, which validate refuses by name rather than
     * reading it as the whole day.
     */
    private static int conditionTime(String typed) {
        if (typed == null || typed.trim().isEmpty()) {
            return -1;
        }
        int minutes = Automations.minutesOf(typed);
        return minutes < 0 ? 24 * 60 + 1 : minutes;
    }

    private void deleteAutomation(Map<String, String> form, OutputStream output)
            throws IOException {
        synchronized (Automations.STORE) {
            java.util.List<Automations.Rule> rules = KioskConfig.automationsOf(context);
            Automations.Rule rule = Automations.find(rules, form.getOrDefault("id", "").trim());
            if (rule != null) {
                rules.remove(rule);
                KioskConfig.storeAutomations(context, rules);
                KioskService.refreshSensorsSoon(context);
            }
        }
        writeResponse(output, 303, "text/plain", bytes("deleted"),
                Collections.singletonMap("Location", "/automations"));
    }

    private static double parseLevel(String text) {
        if (text == null || text.trim().isEmpty()) {
            return Double.NaN;
        }
        try {
            return Double.parseDouble(text.trim());
        } catch (NumberFormatException notANumber) {
            return Double.NaN;
        }
    }

    /** The minutes as typed: empty is none, anything that is not a small number is refused. */
    private static int parseMinutes(String text) {
        if (text == null || text.trim().isEmpty()) {
            return 0;
        }
        return text.trim().matches("\\d{1,4}") ? Integer.parseInt(text.trim()) : -1;
    }

    /** System stats and the log, on their own page under This panel. */
    private String buildStatsPage() {
        KioskConfig config = KioskConfig.load(context);
        // Two panels, as the panel's page has them (2026-09-28): side by side from 840 px,
        // one under the other below, the way every page of several panels without a menu is.
        return pageStart("System stats and log", null, settingsAt("backup"))
                + "<div class=\"two\"><section class=\"card\"><h2>System stats</h2>"
                + statsBody(config) + "</section>"
                + "<section class=\"card\"><h2>Log</h2>" + logBody() + "</section></div>"
                + pageEnd();
    }

    /**
     * The screen as the wall shows it, a PNG, or the refusal in the usual JSON with 409 when no
     * window of the app is in front. See KioskService.captureScreen.
     */
    private void serveScreenshot(OutputStream output) throws IOException {
        KioskService.Screenshot shot = kioskService.captureScreen();
        if (shot.png == null) {
            writeResponse(output, 409, "application/json", bytes("{\"status\":\"rejected\","
                    + "\"detail\":" + JSONObject.quote(shot.problem) + "}"));
            return;
        }
        writeResponse(output, 200, "image/png", shot.png,
                Collections.singletonMap("Cache-Control", "no-store"));
    }

    /**
     * {@code GET /api/fleet/screenshot?panel=<id>}: one member's screen as the Fleet page's
     * thumbnail, this panel's through its own capture and another's fetched from
     * its member endpoint, a JPEG with the long side at most 1280 px, or the refusal in the
     * usual JSON: 404 for an id not in the fleet, 409 when the panel did not answer or
     * refused. Never a stale picture: nothing is kept between requests.
     */
    private void serveFleetScreenshot(String panel, OutputStream output) throws IOException {
        java.util.Map<String, Object> member = FleetServer.memberById(context, panel);
        if (member == null) {
            writeResponse(output, 404, "application/json", bytes(rejectedJson(
                    "no panel with the id " + panel + " is in this fleet")));
            return;
        }
        FleetServer.Picture picture = FleetServer.screenshotOf(context, member);
        if (picture.jpeg == null) {
            writeResponse(output, 409, "application/json", bytes(rejectedJson(picture.problem)));
            return;
        }
        writeResponse(output, 200, "image/jpeg", picture.jpeg,
                Collections.singletonMap("Cache-Control", "no-store"));
    }

    /**
     * {@code POST /api/fleet/identify} with {@code panel=<id>} or {@code panel=all}: Identify
     * on that panel, or on every panel of the fleet at once, each asked in parallel within the
     * fleet call's deadline. The reply is the command's usual JSON; for {@code all} the detail
     * holds one line per panel, and the status is accepted only when every panel accepted.
     */
    private void serveFleetIdentify(String panel, OutputStream output) throws IOException {
        java.util.List<java.util.Map<String, Object>> asked = new java.util.ArrayList<>();
        if ("all".equals(panel)) {
            asked.addAll(FleetDocument.members(KioskConfig.fleetDocumentOf(context)));
        } else {
            java.util.Map<String, Object> member = FleetServer.memberById(context, panel);
            if (member != null) {
                asked.add(member);
            }
        }
        if (asked.isEmpty()) {
            writeResponse(output, 404, "application/json", bytes(rejectedJson("all".equals(panel)
                    ? "this panel is not in a fleet"
                    : "no panel with the id " + panel + " is in this fleet")));
            return;
        }
        String[] lines = new String[asked.size()];
        boolean[] refused = {false};
        java.util.List<Thread> calls = new java.util.ArrayList<>();
        for (int i = 0; i < asked.size(); i++) {
            java.util.Map<String, Object> member = asked.get(i);
            int at = i;
            Thread call = new Thread(() -> {
                String problem = FleetServer.identifyOn(context, member);
                String name = TinyJson.string(member, FleetDocument.NAME,
                        TinyJson.string(member, FleetDocument.ID, ""));
                synchronized (refused) {
                    refused[0] |= problem != null;
                    lines[at] = asked.size() == 1 ? (problem == null ? "" : problem)
                            : name + ": " + (problem == null ? "accepted" : problem);
                }
            }, "MuralisFleetIdentify");
            call.start();
            calls.add(call);
        }
        for (Thread call : calls) {
            try {
                call.join();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        JSONObject response = new JSONObject();
        try {
            response.put("status", refused[0] ? "rejected" : "accepted");
            response.put("detail", String.join("\n", lines));
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
        writeResponse(output, 200, "application/json", bytes(response.toString()));
    }

    private static String rejectedJson(String detail) {
        return "{\"status\":\"rejected\",\"detail\":" + JSONObject.quote(detail) + "}";
    }

    private void serveSnapshot(OutputStream output) throws IOException {
        PanelCamera camera = kioskService.camera();
        byte[] jpeg = camera == null ? null : camera.snapshot();
        if (jpeg == null) {
            writeResponse(output, 503, "text/plain",
                    bytes(camera != null && camera.open() ? "No picture yet." : "The camera is off."));
            return;
        }
        writeResponse(output, 200, "image/jpeg", jpeg,
                Collections.singletonMap("Cache-Control", "no-store"));
    }

    /** A frame not written within this long is a viewer that stopped reading; the stream ends. */
    private static final long STREAM_WRITE_MS = 10_000;

    /**
     * The live stream: MJPEG, one part per frame, until the viewer goes. At most
     * {@link PanelCamera#MAX_VIEWERS} at once, and each frame's write under its own deadline:
     * a viewer that stops reading (a laptop asleep with the tab open) held one of the admin's
     * workers until TCP gave up, with no limit on how long a write may block (review,
     * 2026-10-01).
     */
    private void serveStream(OutputStream output, java.net.Socket socket) throws IOException {
        PanelCamera camera = kioskService.camera();
        if (camera == null || !camera.open()) {
            writeResponse(output, 503, "text/plain", bytes("The camera is off."));
            return;
        }
        // Counted before the check, so requests arriving together cannot all pass it.
        if (camera.viewerJoined() > PanelCamera.MAX_VIEWERS) {
            camera.viewerLeft();
            writeResponse(output, 503, "text/plain", bytes("The stream has "
                    + PanelCamera.MAX_VIEWERS + " viewers already; close one first."),
                    Collections.singletonMap("Retry-After", "10"));
            return;
        }
        long shown = -1;
        java.util.concurrent.ScheduledFuture<?> writeDeadline = null;
        try {
            output.write(("HTTP/1.1 200 OK\r\nContent-Type: multipart/x-mixed-replace; "
                    + "boundary=frame\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            // Answered: a failure from here on ends the stream, never adds a 500 to it.
            answered.set(true);
            long waitingSinceMs = System.currentTimeMillis();
            while (camera.open()) {
                byte[] jpeg = camera.snapshot();
                long at = camera.lastFrameAtMs();
                if (jpeg == null || at == shown) {
                    if (System.currentTimeMillis() - waitingSinceMs > STREAM_STALL_MS) {
                        // No picture for this long is a camera that stopped, not a slow one;
                        // the connection ends rather than holding a worker for a viewer who
                        // may be long gone.
                        break;
                    }
                    Thread.sleep(50);
                    continue;
                }
                waitingSinceMs = System.currentTimeMillis();
                shown = at;
                // The deadline covers the write alone and is lifted once the frame is out, so
                // a gap between frames (a slow camera) never closes a healthy stream.
                writeDeadline = connectionDeadlines.schedule(() -> closeQuietly(socket),
                        STREAM_WRITE_MS, TimeUnit.MILLISECONDS);
                output.write(("--frame\r\nContent-Type: image/jpeg\r\nContent-Length: "
                        + jpeg.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                output.write(jpeg);
                output.write("\r\n".getBytes(StandardCharsets.US_ASCII));
                output.flush();
                writeDeadline.cancel(false);
                writeDeadline = null;
            }
        } catch (InterruptedException ended) {
            Thread.currentThread().interrupt();
        } finally {
            if (writeDeadline != null) {
                writeDeadline.cancel(false);
            }
            camera.viewerLeft();
        }
    }

    private String appVersionName() {
        try {
            String name = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionName;
            return name == null ? "" : name;
        } catch (android.content.pm.PackageManager.NameNotFoundException impossible) {
            return "";
        }
    }

    // ---- the bodies, each the section's essentials in the texts the page has always had

    /**
     * The Dashboard's choice and integration from its Save, through the service as a command
     * would; null when stored, else why not, in the section's words.
     */
    private String saveDashboardShows(String shows, String integration) {
        if (KioskCommandDispatcher.DASHBOARD_INTEGRATION.equals(shows) && integration != null
                && !integration.isEmpty()) {
            String refused = kioskService.setDashboardIntegration(integration);
            if (refused != null) {
                return "Not saved: " + refused + ".";
            }
        }
        if (shows.equals(KioskConfig.dashboardShowsOf(context))) {
            return null;
        }
        String refused = kioskService.setDashboardShows(shows);
        if (KioskCommandDispatcher.NO_APP_CHOSEN.equals(refused)) {
            return "Not saved: choose the app first, under App.";
        }
        if (KioskCommandDispatcher.NO_CAMERA_CHOSEN.equals(refused)) {
            return "Not saved: choose the cameras first, under Cameras.";
        }
        return refused == null ? null : "Not saved: " + refused + ".";
    }

    private static String wordOf(String shows) {
        for (String[] word : KioskCommandDispatcher.DASHBOARD_WORDS) {
            if (word[0].equals(shows)) {
                return word[1];
            }
        }
        return shows;
    }

    private String dashboardBody(KioskConfig config, String notice, String noticeSection) {
        // What the dashboard shows, first and as a choice, as on the panel: the web page and its
        // address, an app, cameras, or an integration's own page. Every answer and its fields
        // are drawn; admin_setting.js shows the picked one as a draft, and the one Save stores
        // the choice, the integration and the address together (Juri, 2026-10-27). Without
        // scripting all of them show and Save still posts the radio picked.
        String stored = KioskConfig.dashboardShowsOf(context);
        String chosenApp = KioskConfig.chosenAppOf(context);
        String chosenCameras = KioskConfig.dashboardCamerasOf(context);
        java.util.List<Integrations.Instance> pages = BitcoinClockPage.pageInstances(context);
        String storedIntegration = KioskConfig.dashboardIntegrationOf(context);
        String integration = Integrations.pageInstance(pages, storedIntegration) != null
                ? storedIntegration : pages.isEmpty() ? "" : pages.get(0).id;
        // Cameras, the third answer, once there is a camera or a group to show; Integration, the
        // fourth, once an integration with a page of its own is added (the Bitcoin clock).
        boolean offerCameras = KioskCommandDispatcher.DASHBOARD_CAMERAS.equals(stored)
                || !KioskConfig.camerasOf(context).isEmpty()
                || !KioskConfig.cameraGroupsOf(context).isEmpty();
        boolean offerIntegration = KioskCommandDispatcher.DASHBOARD_INTEGRATION.equals(stored)
                || !pages.isEmpty();
        StringBuilder answers = new StringBuilder();
        for (String[] word : KioskCommandDispatcher.DASHBOARD_WORDS) {
            if ((KioskCommandDispatcher.DASHBOARD_CAMERAS.equals(word[0]) && !offerCameras)
                    || (KioskCommandDispatcher.DASHBOARD_INTEGRATION.equals(word[0])
                            && !offerIntegration)) {
                continue;
            }
            answers.append("<label><input type=\"radio\" name=\"dashboard_shows\" value=\"")
                    .append(word[0]).append('"').append(word[0].equals(stored) ? " checked" : "")
                    .append('>').append(escapeHtml(word[1])).append("</label>");
        }
        StringBuilder rows = new StringBuilder();
        for (Integrations.Instance page : pages) {
            // One row each: the radio chooses, the gear opens that integration's own options,
            // chosen or not (Emmet's sketch, 2026-10-27).
            BitcoinClock.Options clock = KioskConfig.clockOptionsOf(context, page.id);
            int on = 0;
            for (BitcoinClock.Screen screen : clock.screens) {
                on += screen.on ? 1 : 0;
            }
            String id = escapeHtml(page.id);
            rows.append("<li class=\"two\"><label class=\"rowlink plain\" for=\"dash-int-").append(id)
                    .append("\"><span class=\"lead\"><input type=\"radio\" id=\"dash-int-").append(id)
                    .append("\" name=\"dashboard_integration\" value=\"").append(id).append('"')
                    .append(page.id.equals(integration) ? " checked" : "").append("></span>")
                    .append("<span class=\"text\"><span class=\"h\">").append(escapeHtml(page.name))
                    .append("</span><span class=\"s\">").append(escapeHtml(styleWord(clock.style)))
                    .append(" · ").append(on).append(on == 1 ? " screen" : " screens")
                    .append("</span></span></label><span class=\"trail\"><a class=\"ib\" href=\"/clock-options?id=")
                    .append(urlEncode(page.id)).append("\" title=\"").append(escapeHtml(page.name))
                    .append(" options\" aria-label=\"").append(escapeHtml(page.name)).append(" options\">")
                    .append(glyph("tune")).append("</a></span></li>");
        }
        String problem = chosenApp.isEmpty() ? null : AppLock.problem(context, chosenApp);
        return sectionFormStart("dashboard", notice, noticeSection)
                // Each Save box carries the values it was rendered from, so a submit from a page
                // that has gone stale is refused instead of reverting a newer change; see
                // staleFormRefusal. The baseline strings here must mirror saveSettings exactly.
                + baselineField(config.dashboardUrl)
                + hidden("shows_baseline", stored)
                + hidden("integration_baseline", storedIntegration)
                + "<div class=\"segradios\" role=\"radiogroup\" aria-label=\"Dashboard\""
                + " id=\"dashboard-answers\">" + answers + "</div>"
                + "<p class=\"hint\" id=\"dashboard-now\" hidden>Shows now: "
                + escapeHtml(dashboardSummary(config)) + "</p>"
                + "<div data-answer=\"page\">" + sharedLine("dashboard")
                + urlField("dashboard_url", "Dashboard URL", config.dashboardUrl)
                // Open once shows the typed address until the next kiosk restart and stores
                // nothing (dashboard.open_once); a submit with its own formaction, so with
                // scripting unavailable the browser still posts the box to /api/command.
                + "<div class=\"actions\"><button type=\"submit\" class=\"tonal\" id=\"open-once\""
                + " name=\"cmnd\" value=\"dashboard.open_once\" formaction=\"/api/command\""
                + " formmethod=\"post\">Open once</button><a class=\"btn text\" href=\"/page-options\">"
                + "Page options" + glyph("next") + "</a></div></div>"
                + "<div data-answer=\"app\"><p class=\"appline\">"
                + escapeHtml(chosenApp.isEmpty() ? "No app chosen yet"
                        : KioskConfig.appLockLabelOf(context)) + "</p>"
                + (problem == null ? "" : "<p class=\"hint bad\">"
                        + escapeHtml(Character.toUpperCase(problem.charAt(0))
                                + problem.substring(1)) + ".</p>")
                + "<div class=\"actions\"><a class=\"btn text\" href=\"/app-lock\">Choose the app"
                + glyph("next") + "</a></div></div>"
                + "<div data-answer=\"cameras\"><ul class=\"list\">" + linkRow("/camera-view",
                        "cameras", chosenCameras.isEmpty() ? "No camera chosen yet" : chosenCameras,
                        "Choose the cameras", null, glyph("next")) + "</ul></div>"
                + "<div data-answer=\"integration\"><ul class=\"list\">" + rows + "</ul></div>"
                + "<div class=\"actions\" id=\"dashboard-save\"><button class=\"primary\""
                + " type=\"submit\">Save</button><span class=\"hint\" id=\"dashboard-unsaved\""
                + " hidden>Not saved</span></div></form>";
    }

    private static String styleWord(String style) {
        for (String[] row : BitcoinClock.STYLE_WORDS) {
            if (row[0].equals(style)) {
                return row[1];
            }
        }
        return style;
    }

    /** The added Bitcoin clock for {@code id}, its instance id; the first when none is named. */
    private String clockInstance(String id) {
        java.util.List<Integrations.Instance> pages = BitcoinClockPage.pageInstances(context);
        if (id == null || id.isEmpty()) {
            return pages.isEmpty() ? null : pages.get(0).id;
        }
        return Integrations.pageInstance(pages, id);
    }

    /**
     * Clock options, as on the panel and laid out as page editors are (decided 2026-10-26): the
     * options in one column of groups at the left, one open at a time, each closed group saying
     * its value, Save pinned under them; the live preview at the right, kept in view, and pinned
     * on top on a narrow screen. admin_clock.js shows each change in the preview at once, keeps
     * the screens' order and the groups' value lines, and asks before leaving unsaved changes;
     * without it the form still saves, the order as it was.
     */
    private String buildClockOptionsPage(String id, String notice) {
        StringBuilder html = new StringBuilder(pageStart(id == null ? "Clock options"
                : BitcoinClockPage.nameOf(context, id) + " options", null,
                settingsAt("dashboard")));
        if (id == null) {
            return html.append("<p class=\"hint\">Add the Bitcoin clock in Integrations first.</p>")
                    .append(pageEnd()).toString();
        }
        BitcoinClock.Options options = KioskConfig.clockOptionsOf(context, id);
        if (notice != null) {
            html.append("<p class=\"notice\">").append(escapeHtml(notice)).append("</p>");
        }
        html.append("<div class=\"clock-editor\">");
        // The preview in the panel's own shape, read from its screen.
        android.util.DisplayMetrics screen = context.getResources().getDisplayMetrics();
        html.append("<div class=\"clock-stage\"><div class=\"clock-preview\" style=\"aspect-ratio:")
                .append(screen.widthPixels).append('/').append(screen.heightPixels)
                .append(";--ar:").append(String.format(java.util.Locale.ROOT, "%.4f",
                        (float) screen.widthPixels / Math.max(1, screen.heightPixels)))
                .append("\"><iframe id=\"clock-frame\" src=\"/clock/page?id=").append(urlEncode(id))
                .append("\" title=\"Preview\"></iframe></div><div class=\"actions\">"
                        + "<button type=\"button\" class=\"ib\" data-clock-step=\"-1\" title=\"Previous"
                        + " screen\" aria-label=\"Previous screen\">" + glyph("prev") + "</button>"
                        + "<button type=\"button\" class=\"ib\" data-clock-step=\"1\" title=\"Next"
                        + " screen\" aria-label=\"Next screen\">" + glyph("next") + "</button>"
                        + "<button type=\"button\" class=\"tonal\" id=\"clock-flash\">Show a new"
                        + " block</button></div></div>");
        html.append("<form method=\"post\" action=\"/clock-options\" id=\"clock-form\""
                + " class=\"clock-options\">")
                .append(hidden("id", id)).append(hidden("baseline", options.toJson()))
                .append("<div class=\"clock-groups\">");
        // Style, with its four colours under the names that style gives them; each style
        // carries its own default colours and names for admin_clock.js to fill in on a switch.
        StringBuilder styles = new StringBuilder();
        for (String[] style : BitcoinClock.STYLE_WORDS) {
            BitcoinClock.Options colours = BitcoinClock.withStyle(options, style[0]);
            styles.append("<label><input type=\"radio\" name=\"style\" value=\"")
                    .append(style[0]).append("\" data-colours=\"").append(colours.background)
                    .append('|').append(colours.cells).append('|').append(colours.text)
                    .append('|').append(colours.accent).append("\" data-words=\"")
                    .append(String.join("|", BitcoinClock.colourWords(style[0])))
                    .append("\" data-font=\"").append(BitcoinClock.usesFont(style[0]))
                    .append("\"").append(style[0].equals(options.style) ? " checked" : "")
                    .append('>').append(escapeHtml(style[1])).append("</label>");
        }
        String[] words = BitcoinClock.colourWords(options.style);
        html.append(clockGroup("style", "Style", "<div class=\"segradios\""
                + " role=\"radiogroup\" aria-label=\"Style\">" + styles + "</div>"
                + clockColour("background", words[0], BitcoinClock.SWATCHES[0],
                        options.background)
                + clockColour("cells", words[1], BitcoinClock.SWATCHES[1], options.cells)
                + clockColour("text", words[2], BitcoinClock.SWATCHES[2], options.text)
                // Nixie's Lamp only while it has a label to sit by (admin_clock.js follows).
                + "<div data-colour-block=\"accent\""
                + (BitcoinClock.usesAccent(options.style, options.label) ? "" : " hidden") + ">"
                + clockColour("accent", words[3], BitcoinClock.SWATCHES[3], options.accent)
                + "</div>"));
        // The digits' font, for the styles that draw them in it.
        html.append(clockGroup("digits", "Font",
                clockRadios("font", "Font", BitcoinClock.FONT_WORDS, options.font))
                .replaceFirst("<details ", BitcoinClock.usesFont(options.style) ? "<details "
                        : "<details hidden "));
        html.append(clockGroup("layout", "Layout", "<p class=\"label\">Size</p>"
                + clockRadios("size", "Size", BitcoinClock.SIZE_WORDS, options.size)
                + "<p class=\"label\">Position</p>"
                + clockRadios("position", "Position", BitcoinClock.POSITION_WORDS,
                        options.position)
                + "<p class=\"label\">Label</p>"
                + clockRadios("label", "Label", BitcoinClock.LABEL_WORDS, options.label)
                + "<p class=\"label\">Date and time</p>"
                + clockRadios("strip", "Date and time", BitcoinClock.STRIP_WORDS, options.strip)));
        // A picture behind the clock: one of the uploads, darkened.
        StringBuilder pictures = new StringBuilder(selectOption("", "None", options.picture));
        PictureBrowser.Page uploads = PictureLibrary.get(context).uploads(0, 200);
        for (PictureBrowser.Entry entry : uploads.entries) {
            pictures.append(selectOption(entry.uri, entry.name, options.picture));
        }
        html.append(clockGroup("picture", "Background picture",
                selectField("clock-picture", "picture", "Picture", null, pictures.toString(),
                        uploads.entries.isEmpty() ? "No pictures added yet." : null)
                        + "<div class=\"actions\"><a class=\"btn text\" href=\"/gallery?tab=pictures\">"
                        + "Manage pictures in Gallery" + glyph("next") + "</a></div>"
                        + "<label class=\"range\">Dimming <input type=\"range\" name=\"dim\""
                        + " min=\"0\" max=\"" + BitcoinClock.MAX_DIM + "\" value=\"" + options.dim
                        + "\"></label>"));
        // The screens, in order, each on or off; the arrows move them, a name shows it.
        StringBuilder rows = new StringBuilder();
        StringBuilder order = new StringBuilder();
        for (BitcoinClock.Screen one : options.screens) {
            String name = escapeHtml(BitcoinClock.screenName(one.key));
            order.append(order.length() == 0 ? "" : ",").append(one.key);
            rows.append("<li data-key=\"").append(one.key).append("\">")
                    .append("<button type=\"button\" class=\"ib\" data-move=\"-1\" title=\"Move ")
                    .append(name).append(" up\" aria-label=\"Move ").append(name).append(" up\">")
                    .append(glyph("arrow_up")).append("</button>")
                    .append("<button type=\"button\" class=\"ib\" data-move=\"1\" title=\"Move ")
                    .append(name).append(" down\" aria-label=\"Move ").append(name)
                    .append(" down\">").append(glyph("arrow_down")).append("</button>")
                    .append("<button type=\"button\" class=\"text\" data-show=\"").append(one.key)
                    .append("\">").append(name).append("</button>")
                    .append("<input type=\"checkbox\" class=\"sw\" name=\"on_").append(one.key)
                    .append("\" value=\"1\" aria-label=\"").append(name).append('"')
                    .append(one.on ? " checked" : "").append("></li>");
        }
        html.append(clockGroup("screens", "Screens", "<ul class=\"list clock-screens\">"
                + rows + "</ul>" + hidden("screens", order.toString())));
        html.append(clockGroup("time", "Time and flash",
                field("number", "seconds", "Time per screen (seconds)",
                        String.valueOf(options.seconds))
                        + "<div class=\"switches\">"
                        + switchRow("clock-flash-on", "Flash on a new block",
                                " name=\"flash\" value=\"1\"", options.flash)
                        + "</div><input type=\"hidden\" name=\"flash_sent\" value=\"1\">"));
        html.append("</div><div class=\"clock-save\"><button class=\"primary\" type=\"submit\">Save"
                + "</button><span class=\"hint\" id=\"clock-unsaved\" hidden>Not saved</span></div>"
                + "</form></div>");
        return html.append(clockScript).append(pageEnd()).toString();
    }

    /**
     * One group of Clock options: a line with its name and value that opens it, one open at a
     * time and all closed at first, so every value line shows (admin_clock.js closes the others
     * and writes the value line).
     */
    private static String clockGroup(String key, String title, String body) {
        return "<details class=\"clock-group\" data-group=\"" + key + "\"><summary><span class=\"text\"><span class=\"h\">" + escapeHtml(title)
                + "</span><span class=\"s\" data-value=\"" + key + "\"></span></span>"
                + glyph("down") + "</summary><div class=\"clock-body\">" + body + "</div></details>";
    }

    /** One colour of the clock: its swatches, which fill the box, and the r,g,b box. */
    private static String clockColour(String name, String label, String[] swatches,
            String value) {
        StringBuilder html = new StringBuilder("<p class=\"label\" data-colour-label=\"" + name
                + "\">" + escapeHtml(label) + "</p><div class=\"swatches\">");
        for (String swatch : swatches) {
            html.append("<button type=\"button\" class=\"swatch").append(swatch.equals(value)
                    ? " on" : "").append("\" data-swatch=\"").append(name).append("\" data-rgb=\"")
                    .append(swatch).append("\" style=\"background:rgb(").append(swatch)
                    .append(")\" title=\"").append(swatch).append("\" aria-label=\"")
                    .append(escapeHtml(label)).append(' ').append(swatch).append("\"></button>");
        }
        return html.append("</div>").append(field("text", name, label + " (r,g,b)", value))
                .toString();
    }

    /** A plain group of radios over one of BitcoinClock's tables, saved with the form. */
    private static String clockRadios(String name, String label, String[][] table,
            String chosen) {
        // Drawn as a segmented button, as on the panel, and still plain radios underneath.
        StringBuilder radios = new StringBuilder("<div class=\"segradios\" role=\"radiogroup\""
                + " aria-label=\"" + escapeHtml(label) + "\">");
        for (String[] row : table) {
            radios.append("<label><input type=\"radio\" name=\"").append(name)
                    .append("\" value=\"").append(row[0]).append('"')
                    .append(row[0].equals(chosen) ? " checked" : "").append('>')
                    .append(escapeHtml(row[1])).append("</label>");
        }
        return radios.append("</div>").toString();
    }

    /**
     * The Clock options form stored, or why not: refused whole when another surface changed the
     * options since the page was drawn, or when a value breaks a rule.
     */
    private String saveClockOptions(String id, Map<String, String> form) {
        BitcoinClock.Options stored = KioskConfig.clockOptionsOf(context, id);
        if (!stored.toJson().equals(form.get("baseline"))) {
            return "the options were changed elsewhere after this page loaded. The page now shows"
                    + " the current ones";
        }
        Map<String, String> fields = new java.util.HashMap<>();
        for (String key : new String[] {"style", "background", "cells", "text", "accent", "font",
                "picture", "dim", "size", "position", "label", "strip", "seconds"}) {
            if (form.containsKey(key)) {
                fields.put(key, form.get(key));
            }
        }
        // A checkbox sends nothing when off: the hidden companions say the form was whole.
        if (form.containsKey("flash_sent")) {
            fields.put("flash", form.containsKey("flash") ? "1" : "0");
        }
        if (form.containsKey("screens")) {
            StringBuilder order = new StringBuilder();
            for (String key : form.get("screens").split(",")) {
                order.append(order.length() == 0 ? "" : ",")
                        .append(form.containsKey("on_" + key.trim()) ? "" : "-").append(key.trim());
            }
            fields.put("screens", order.toString());
        }
        String problem = BitcoinClock.problem(fields);
        String picture = fields.getOrDefault("picture", "");
        if (problem == null && !picture.isEmpty()
                && !BitcoinClockPage.pictureExists(context, picture)) {
            problem = "the picture must be one of the uploads";
        }
        if (problem != null) {
            return problem;
        }
        KioskConfig.storeClockOptions(context, id, BitcoinClock.apply(stored, fields));
        return null;
    }

    /**
     * Page options, a page of its own as on the panel (the UI round, 2026-10-15; inline in
     * the Dashboard box until then): the browser options and the trusted certificates, each
     * form posting back here.
     */
    private String buildPageOptionsPage(String notice, String noticeSection) {
        return pageStart("Page options", null, settingsAt("dashboard"))
                + "<div class=\"card\">" + browserForm(notice, noticeSection) + "</div>"
                + "<div class=\"card gap\">" + certificatesBox(notice, noticeSection) + "</div>"
                + pageEnd();
    }

    /**
     * Dashboard app (App lock), a page of its own as on the panel: one installed app, and
     * whether Muralis may display over other apps, which only the panel itself can allow.
     */
    private String buildAppLockPage(String notice, boolean withSystem) {
        StringBuilder html = new StringBuilder(pageStart("App", null,
                settingsAt("dashboard")));
        if (notice != null) {
            html.append("<p class=\"notice\">").append(escapeHtml(notice)).append("</p>");
        }
        String chosen = KioskConfig.chosenAppOf(context);
        html.append("<form method=\"post\" action=\"/app-lock\" class=\"card\">"
                + "<h2>The app this panel shows</h2><p class=\"hint\">Opened again whenever it"
                + " closes.</p>");
        if (!AppLock.canDrawOver(context)) {
            html.append("<p class=\"hint bad\">Needs \"Display over other apps\": allow it on the"
                    + " panel.</p>");
        }
        // A switch, as on the panel; it reloads the list (admin_setting.js, data-go).
        html.append("<div class=\"switches\">").append(switchRow("app-lock-system",
                "Show the apps that came with the device",
                " data-go=\"/app-lock" + (withSystem ? "" : "?system=1") + "\"", withSystem))
                .append("</div>");
        // No "Off" here: the web page is another answer of the Dashboard choice.
        StringBuilder radios = new StringBuilder();
        boolean listed = chosen.isEmpty();
        for (AppLock.App app : AppLock.launchable(context, withSystem)) {
            radios.append(plainRadio("app_lock", app.packageName, chosen, app.label));
            listed |= app.packageName.equals(chosen);
        }
        if (!listed) {
            boolean installed = context.getPackageManager()
                    .getLaunchIntentForPackage(chosen) != null;
            radios.append(plainRadio("app_lock", chosen, chosen, KioskConfig.appLockLabelOf(context)
                    + (installed ? "" : " (not installed)")));
        }
        if (radios.length() == 0) {
            // An empty list says why and what to do, not nothing.
            html.append("<div class=\"empty\"><p class=\"h\">No apps of your own on this panel</p>"
                    + "<p class=\"s\">Install one, or show the apps that came with the device."
                    + "</p></div>");
        }
        html.append("<div class=\"radios\" role=\"radiogroup\" aria-label=\"The app\">")
                .append(radios).append("</div><div class=\"actions\"><button type=\"submit\""
                        + " class=\"primary\">Save</button></div></form>");
        return html.append(pageEnd()).toString();
    }

    private static String plainRadio(String name, String value, String current, String label) {
        return "<label class=\"radio\"><input type=\"radio\" name=\"" + name + "\" value=\""
                + escapeHtml(value) + "\"" + (value.equals(current) ? " checked" : "") + ">"
                + escapeHtml(label) + "</label>";
    }

    /**
     * Stores the dashboard's app, an installed app with something to open, and shows it: the
     * page is reached from the Dashboard choice's App.
     */
    private String saveAppLock(Map<String, String> form) {
        String chosen = form.getOrDefault("app_lock", "").trim();
        if (chosen.isEmpty()) {
            return "Not saved: choose an app.";
        }
        if (context.getPackageName().equals(chosen)) {
            return "Not saved: Muralis cannot lock the panel to itself.";
        }
        if (context.getPackageManager().getLaunchIntentForPackage(chosen) == null) {
            return "Not saved: no installed app " + chosen + " has anything to open.";
        }
        if (AppLock.doors(context).contains(chosen)) {
            return "Not saved: " + chosen + " opens Android's settings, a home screen or the"
                    + " Play Store, which would hand the panel's whole device to anyone.";
        }
        KioskConfig.edit(context).appLock(chosen).apply();
        KioskService.appLockChanged();
        KioskService.publishTelemetrySoon(context);
        return null;
    }

    /**
     * The Cameras page, as Automations is: the cameras, then the groups under their own head
     * when there are any, Add a camera and Add a group under them, and beside them from 840 px the chosen one's
     * form; below 840 px the list, and a form is a page of its own ({@code detailPage}). The
     * panel's Cameras page is the same. {@code selected} is "c:" or "g:" and a name, empty to
     * add one.
     */
    private String buildCamerasPage(String notice, String selected, boolean detailPage) {
        java.util.List<Cameras.Camera> cameras = KioskConfig.camerasOf(context);
        java.util.List<Cameras.Group> groups = KioskConfig.cameraGroupsOf(context);
        java.util.Map<String, Cameras.Login> logins = KioskConfig.cameraLoginsOf(context);
        String chosen = cameraItemOrFirst(selected, cameras, groups);
        StringBuilder rows = new StringBuilder();
        for (Cameras.Camera camera : cameras) {
            String id = "c:" + camera.name;
            rows.append(navRow(linkRow("/camera?id=" + urlEncode(id), "cameras", camera.name,
                    Cameras.rowLine(camera.url), null, ""), id, id.equals(chosen)));
        }
        StringBuilder groupRows = new StringBuilder();
        for (Cameras.Group group : groups) {
            String id = "g:" + group.name;
            int members = Cameras.resolve(cameras, groups, group.name).size();
            groupRows.append(navRow(linkRow("/camera?id=" + urlEncode(id), "cameras", group.name,
                    members == 1 ? "1 camera" : members + " cameras", null, ""),
                    id, id.equals(chosen)));
        }
        String nav = "<section class=\"card nav\"><div class=\"cardhead\"><h2>Cameras</h2>"
                + "<span class=\"count\">" + escapeHtml(Cameras.summary(cameras)) + "</span></div>"
                + (rows.length() == 0 ? "<p class=\"hint\">No cameras yet.</p>"
                        : "<ul class=\"list seg\">" + rows + "</ul>")
                // The groups under a head of their own, as the cameras, only when there are any.
                + (groupRows.length() == 0 ? "" : "<div class=\"cardhead groups\"><h2>Groups</h2>"
                        + "<span class=\"count\">" + escapeHtml(Cameras.groupsSummary(groups))
                        + "</span></div><ul class=\"list seg\">" + groupRows + "</ul>")
                + "<div class=\"actions\">"
                + (cameras.size() >= Cameras.MAX_CAMERAS ? ""
                        : "<a class=\"btn tonal\" href=\"/camera?id=c%3A\" data-pane=\"c:\">"
                                + glyph("plus") + "Add a camera</a>")
                + (cameras.isEmpty() || groups.size() >= Cameras.MAX_GROUPS ? ""
                        : "<a class=\"btn tonal\" href=\"/camera?id=g%3A\" data-pane=\"g:\">"
                                + glyph("plus") + "Add a group</a>")
                + "</div></section>";
        // One form per camera and per group, and one to add each, every one in its pane, so
        // choosing at the left is a switch of panes.
        StringBuilder detail = new StringBuilder();
        for (Cameras.Camera camera : cameras) {
            detail.append(pane("c:" + camera.name, chosen, cameraForm(camera,
                    logins.get(camera.name.toLowerCase(java.util.Locale.ROOT)))));
        }
        if (cameras.size() < Cameras.MAX_CAMERAS) {
            detail.append(pane("c:", chosen, cameraForm(null, null)));
        }
        for (Cameras.Group group : groups) {
            detail.append(pane("g:" + group.name, chosen, cameraGroupForm(cameras, group)));
        }
        if (!cameras.isEmpty() && groups.size() < Cameras.MAX_GROUPS) {
            detail.append(pane("g:", chosen, cameraGroupForm(cameras, null)));
        }
        boolean group = chosen.startsWith("g:");
        boolean fresh = chosen.length() == 2;
        return pageStart(!detailPage ? "Cameras" : fresh ? (group ? "New group" : "New camera")
                        : (group ? "Edit group" : "Edit camera"),
                null, detailPage ? "/cameras" : settingsAt("cameras"))
                + (notice == null ? "" : "<p class=\"notice\">" + escapeHtml(notice) + "</p>")
                + listDetail("cameras", detailPage, nav, detail.toString(),
                        " data-base=\"/cameras?id=\" data-list=\"/cameras\"")
                + cameraScript + pageEnd();
    }

    private static String pane(String id, String chosen, String content) {
        return "<div class=\"pane\" data-id=\"" + escapeHtml(id) + "\""
                + (id.equals(chosen) ? "" : " hidden") + ">" + content + "</div>";
    }

    /** The item to show: the one asked for when it still exists, else the first camera. */
    private static String cameraItemOrFirst(String item, java.util.List<Cameras.Camera> cameras,
            java.util.List<Cameras.Group> groups) {
        if (item != null) {
            if (item.equals("c:") || (item.equals("g:") && !cameras.isEmpty())) {
                return item;
            }
            if (item.startsWith("c:") && Cameras.find(cameras, item.substring(2)) != null) {
                return "c:" + Cameras.find(cameras, item.substring(2)).name;
            }
            if (item.startsWith("g:") && Cameras.findGroup(groups, item.substring(2)) != null) {
                return "g:" + Cameras.findGroup(groups, item.substring(2)).name;
            }
        }
        return cameras.isEmpty() ? "c:" : "c:" + cameras.get(0).name;
    }

    /** What the page shows after a form's post: the saved one, or the one that was being edited. */
    private static String savedItem(String kind, Map<String, String> form, String notice) {
        if (notice == null || !notice.startsWith("Not ")) {
            return "remove".equals(form.get("what")) ? null
                    : kind + form.getOrDefault("name", form.getOrDefault("was", "")).trim();
        }
        return kind + form.getOrDefault("was", "").trim();
    }

    private static String cameraForm(Cameras.Camera camera, Cameras.Login login) {
        boolean fresh = camera == null;
        String sfx = fresh ? "-new" : "-" + Integer.toHexString(camera.name.hashCode());
        return "<form method=\"post\" action=\"/cameras\" class=\"card\">"
                + "<h2>" + (fresh ? "Add a camera" : escapeHtml(camera.name)) + "</h2>"
                + "<input type=\"hidden\" name=\"was\" value=\""
                + (fresh ? "" : escapeHtml(camera.name)) + "\">"
                + fieldWithId("c-name" + sfx, "text", "name", "Name", fresh ? "" : camera.name,
                        "", null).replace(MACHINE_TEXT, "")
                + fieldWithId("c-url" + sfx, "text", "url", "Address", fresh ? "" : camera.url,
                        " inputmode=\"url\"", Cameras.ADDRESS_HINT)
                + rtspBrands()
                + fieldWithId("c-full" + sfx, "text", "full", "Full-screen address (optional)",
                        fresh ? "" : camera.full, " inputmode=\"url\"", null)
                + fieldWithId("c-user" + sfx, "text", "user", "User",
                        login == null ? "" : login.user, "", null)
                + fieldWithId("c-password" + sfx, "password", "password", "Password", "", "",
                        login != null && !login.password.isEmpty() ? BLANK_KEEPS : null)
                // A browser cannot play RTSP: the panel plays it and sends one picture on request.
                + (fresh || !Cameras.isRtsp(camera.url) ? "" : "<div class=\"camera-picture\">"
                        + "<img alt=\"A picture from " + escapeHtml(camera.name) + "\" hidden>"
                        + "<p class=\"hint\">Plays on the panel. A picture shows here on"
                        + " request.</p><div class=\"shotbar\"><span class=\"hint\" data-taken>"
                        + "</span><button type=\"button\" class=\"tonal\" data-camera-picture=\""
                        + escapeHtml(camera.name) + "\">Show a picture</button></div></div>")
                + "<div class=\"actions\"><button type=\"submit\" class=\"primary\" name=\"what\""
                + " value=\"save\">" + (fresh ? "Add" : "Save") + "</button>"
                + (fresh ? "" : "<button type=\"submit\" name=\"what\" value=\"show\">Show"
                        + "</button><button type=\"submit\" class=\"danger\" name=\"what\""
                        + " value=\"remove\" data-confirm=\""
                        + escapeHtml(removeQuestion("Remove \"" + camera.name + "\"?",
                                Cameras.REMOVED))
                        + "\">Remove</button>")
                + "</div></form>";
    }

    /** The RTSP addresses of the common brands, folded under the address as on the panel. */
    private static String rtspBrands() {
        StringBuilder html = new StringBuilder("<details class=\"brands\"><summary>RTSP"
                + " addresses by brand</summary><dl>");
        for (String[] brand : Cameras.RTSP_BRANDS) {
            html.append("<dt>").append(escapeHtml(brand[0])).append("</dt><dd><code>")
                    .append(escapeHtml(brand[1])).append("</code>")
                    .append(brand[2].isEmpty() ? "" : " " + escapeHtml(brand[2])).append("</dd>");
        }
        return html.append("</dl></details>").toString();
    }

    /** The Way out section's words on a screen without touch, a TV, on both surfaces. */
    static final String NO_TOUCH_WAY_IN = "No touch here: hold Back for three seconds, or press"
            + " Menu, to open the settings.";

    /**
     * The question before Go to home screen, for the browser's confirm(): this
     * panel when {@code name} is null, else the one named. The panel asks the same words.
     */
    static String leaveQuestion(String name) {
        return leaveTitle(name) + "\n\n" + LEAVE_CONSEQUENCE;
    }

    static String leaveTitle(String name) {
        return name == null ? "Go to the home screen?" : "Go to the home screen on " + name + "?";
    }

    static final String LEAVE_CONSEQUENCE = "Its screen shows the device's home screen, with its"
            + " apps, until Muralis is opened again.";

    /**
     * The panel's removal question (KioskActivity.confirmRemove) in one string for the
     * browser's confirm(): the title, a blank line, what changes.
     */
    private static String removeQuestion(String title, String consequence) {
        return title + "\n\n" + consequence;
    }

    /** How long the web admin waits for an RTSP camera's picture, inside the deadline below. */
    private static final long CAMERA_PICTURE_WAIT_MS = 15_000;
    private static final long CAMERA_PICTURE_DEADLINE_MS = 20_000;

    /**
     * One picture of an RTSP camera, for its form: played on this panel (RtspFrames), the same
     * stream a tile on the panel reads when it shows that camera, and sent from memory.
     */
    private void serveCameraPicture(String name, OutputStream output) throws IOException {
        Cameras.Camera camera = Cameras.find(KioskConfig.camerasOf(context),
                name == null ? "" : name);
        if (camera == null || !Cameras.isRtsp(camera.url)) {
            writeResponse(output, 404, "text/plain; charset=utf-8",
                    bytes("No RTSP camera of that name"));
            return;
        }
        RtspFrames frames = RtspFrames.acquire(context, Cameras.rtspAddress(camera.url,
                KioskConfig.cameraLoginsOf(context).get(camera.name.toLowerCase(Locale.ROOT))),
                camera.name, false);
        RtspFrames.Frame frame;
        String problem;
        try {
            frame = frames.next(0, CAMERA_PICTURE_WAIT_MS);
            problem = frames.problem();
        } finally {
            frames.release();
        }
        if (frame == null) {
            writeResponse(output, 502, "text/plain; charset=utf-8", bytes("denied".equals(problem)
                    ? "No picture: the camera refused the sign-in"
                    : "No picture: the camera did not answer"));
            return;
        }
        writeResponse(output, 200, "image/jpeg", frame.jpeg,
                Collections.singletonMap("Cache-Control", "no-store"));
    }

    /** The file read for Import, waiting for Apply, with its token; one at a time. */
    private volatile SettingsTransfer.Contents pendingImport;
    private volatile String pendingImportToken;

    /** What a QR at error correction M holds with room to spare; more is refused. */
    private static final int SETUP_QR_MAX_BYTES = 2_300;

    /**
     * The setup QR for a new tablet: the site's provisioning values and, in the admin
     * extras, this panel's settings file without passwords; null when it does not fit.
     */
    private String setupQrContent() {
        try {
            JSONObject extras = new JSONObject().put("muralis_settings_z",
                    SettingsTransfer.packed(SettingsTransfer.export(
                            KioskConfig.transferableSettings(context), null, null, appVersionName())));
            String content = KioskDeviceAdminReceiver.provisioningQr(context, extras);
            return content == null || content.getBytes(StandardCharsets.UTF_8).length
                    > SETUP_QR_MAX_BYTES ? null : content;
        } catch (JSONException impossible) {
            return null;
        }
    }

    /**
     * The settings file page: Export (passwords only when asked, then encrypted with
     * a passphrase typed twice), Import (the file, then the list of what changes and Apply),
     * and the setup QR for a new tablet.
     */
    private String buildSettingsFilePage(String notice, String preview) {
        StringBuilder html = new StringBuilder(pageStart("Settings file", null,
                settingsAt("backup")));
        if (notice != null) {
            html.append("<p class=\"notice\">").append(escapeHtml(notice)).append("</p>");
        }
        if (preview != null) {
            html.append(preview);
        }
        html.append("<form method=\"post\" action=\"/settings-file\" class=\"card\">"
                + "<h2>Export</h2><input type=\"hidden\" name=\"what\" value=\"export\">"
                + "<p class=\"hint\">All settings, without the name, keys, fleet, certificates or"
                + " pictures.</p>"
                + "<div class=\"switches\">" + switchRow("include-passwords", "Include passwords",
                        " name=\"include_passwords\" value=\"1\"", false) + "</div>"
                + field("password", "passphrase", "Passphrase for the passwords", "", "",
                        "At least " + SettingsTransfer.MIN_PASSPHRASE + " characters.")
                + field("password", "passphrase_again", "The passphrase again", "", "", null)
                + "<div class=\"actions\"><button type=\"submit\" class=\"primary\">Export"
                + "</button></div></form>");
        html.append("<form method=\"post\" action=\"/settings-file\" enctype=\"multipart/form-data\""
                + " class=\"card\"><h2>Import</h2>"
                + "<input type=\"hidden\" name=\"what\" value=\"read\">"
                + "<p class=\"hint\">Nothing changes until Apply.</p>"
                + fileRow("settings-file-input", "file", "Settings file", ".json,application/json")
                + field("password", "passphrase", "Passphrase, when the file carries passwords",
                        "", "", null)
                + "<div class=\"actions\"><button type=\"submit\" class=\"primary\">Read the file"
                + "</button></div></form>");
        boolean fits = setupQrContent() != null;
        // A debug build names a download address only when it was built with one.
        boolean downloadable = KioskDeviceAdminReceiver.provisioningQr(context, new JSONObject())
                != null;
        html.append("<div class=\"card\"><h2>Setup QR for a new tablet</h2>"
                + "<p class=\"hint\">Scan it during a new tablet's setup. Passwords stay out.</p>"
                + (fits ? "<img id=\"setup-qr\" src=\"/api/setup-qr.svg\""
                        + " alt=\"Setup QR\" width=\"320\" height=\"320\">"
                        + fullScreenButton("setup-qr", null)
                        : !downloadable ? "<p class=\"hint bad\">No download address in this build.</p>"
                        : "<p class=\"hint bad\">These settings do not fit in a QR (it holds about"
                        + " 2 KB packed, the rules take the most). Set the tablet up with the plain QR on"
                        + " the website, then import the file here.</p>")
                + "</div>");
        return html.append(pageEnd()).toString();
    }

    /** Export, read and apply, the settings file page's three posts. */
    private void handleSettingsFile(Map<String, String> headers, byte[] body, OutputStream output)
            throws IOException {
        Map<String, String> form;
        String fileText = null;
        if (isMultipart(headers)) {
            form = new HashMap<>();
            String boundary = MultipartForm.boundaryOf(headers.get("content-type"));
            for (MultipartForm.Part part : boundary == null
                    ? java.util.Collections.<MultipartForm.Part>emptyList()
                    : MultipartForm.parse(body, boundary)) {
                if ("file".equals(part.name)) {
                    fileText = new String(part.data, StandardCharsets.UTF_8);
                } else {
                    form.put(part.name, new String(part.data, StandardCharsets.UTF_8));
                }
            }
        } else {
            form = parseFormBody(headers, body);
        }
        String what = form.getOrDefault("what", "");
        if ("export".equals(what)) {
            boolean withPasswords = isTrue(form.getOrDefault("include_passwords", "0"));
            String passphrase = form.getOrDefault("passphrase", "");
            if (withPasswords && passphrase.length() < SettingsTransfer.MIN_PASSPHRASE) {
                writeResponse(output, 400, "text/html; charset=utf-8", bytes(buildSettingsFilePage(
                        "Not exported: the passphrase needs at least "
                                + SettingsTransfer.MIN_PASSPHRASE + " characters.", null)));
                return;
            }
            if (withPasswords && !passphrase.equals(form.getOrDefault("passphrase_again", ""))) {
                writeResponse(output, 400, "text/html; charset=utf-8", bytes(buildSettingsFilePage(
                        "Not exported: the two passphrases differ.", null)));
                return;
            }
            String file = SettingsTransfer.export(KioskConfig.transferableSettings(context),
                    withPasswords ? KioskConfig.transferableSecrets(context) : null,
                    withPasswords ? passphrase : null, appVersionName());
            Map<String, String> extra = new HashMap<>();
            extra.put("Content-Disposition", "attachment; filename=\"muralis-settings-"
                    + KioskConfig.deviceIdOf(context) + ".json\"");
            extra.put("Cache-Control", "no-store");
            // Indented, one setting a line, so the file reads and edits in any text editor; the
            // setup QR packs the compact form.
            writeResponse(output, 200, "application/json; charset=utf-8",
                    bytes(TinyJson.writeIndented(TinyJson.parse(file))), extra);
            return;
        }
        if ("read".equals(what)) {
            if (fileText == null || fileText.isEmpty()) {
                writeResponse(output, 400, "text/html; charset=utf-8", bytes(buildSettingsFilePage(
                        "Not read: no file was chosen.", null)));
                return;
            }
            SettingsTransfer.Contents contents = SettingsTransfer.read(fileText,
                    form.getOrDefault("passphrase", ""));
            if (contents.problem != null) {
                writeResponse(output, 400, "text/html; charset=utf-8", bytes(buildSettingsFilePage(
                        "Not read: " + contents.problem + ".", null)));
                return;
            }
            Map<String, String> changes = SettingsImport.changes(context, contents);
            String token = Long.toHexString(new java.security.SecureRandom().nextLong());
            pendingImport = contents;
            pendingImportToken = token;
            StringBuilder list = new StringBuilder("<div class=\"card\"><h2>What would change</h2>");
            if (changes.isEmpty()) {
                list.append("<p class=\"hint\">Nothing: this panel has these settings already.</p>");
            } else {
                list.append("<ul class=\"plain\">");
                for (Map.Entry<String, String> change : changes.entrySet()) {
                    list.append("<li><code>").append(escapeHtml(change.getKey())).append("</code>: ")
                            .append(escapeHtml(change.getValue())).append("</li>");
                }
                list.append("</ul><form method=\"post\" action=\"/settings-file\">"
                        + "<input type=\"hidden\" name=\"what\" value=\"apply\">"
                        + "<input type=\"hidden\" name=\"token\" value=\"" + token + "\">"
                        + "<div class=\"actions\"><button type=\"submit\" class=\"primary\">Apply"
                        + "</button><a class=\"btn text\" href=\"/settings-file\">Cancel</a></div>"
                        + "</form>");
            }
            writeResponse(output, 200, "text/html; charset=utf-8",
                    bytes(buildSettingsFilePage(null, list.append("</div>").toString())));
            return;
        }
        if ("apply".equals(what)) {
            SettingsTransfer.Contents contents = pendingImport;
            String token = pendingImportToken;
            if (contents == null || token == null
                    || !token.equals(form.getOrDefault("token", ""))) {
                writeResponse(output, 400, "text/html; charset=utf-8", bytes(buildSettingsFilePage(
                        "Not applied: read the file again; another import came in between.",
                        null)));
                return;
            }
            pendingImport = null;
            pendingImportToken = null;
            java.util.List<String> refused = SettingsImport.apply(context, contents);
            writeResponse(output, 200, "text/html; charset=utf-8", bytes(buildSettingsFilePage(
                    refused.isEmpty() ? "Applied." : "Applied, except: " + String.join("; ",
                            refused) + ".", null)));
            return;
        }
        writeResponse(output, 400, "text/html; charset=utf-8",
                bytes(buildSettingsFilePage("Not done: nothing to do.", null)));
    }

    /**
     * Dashboard cameras, a page of its own as the app's is: the camera or group the dashboard
     * shows. The panel's page is the same.
     */
    private String buildCameraViewPage(String notice) {
        StringBuilder html = new StringBuilder(pageStart("Dashboard cameras", null,
                settingsAt("dashboard")));
        if (notice != null) {
            html.append("<p class=\"notice\">").append(escapeHtml(notice)).append("</p>");
        }
        String chosen = KioskConfig.dashboardCamerasOf(context);
        html.append("<form method=\"post\" action=\"/camera-view\" class=\"card\">"
                + "<h2>The cameras this panel shows</h2><p class=\"hint\">A camera, or a group as"
                + " a grid, instead of the web page. A tap opens one camera of a grid, pinch to"
                + " zoom; a tap brings the grid back.</p><div class=\"radios\">");
        int count = 0;
        for (Cameras.Camera camera : KioskConfig.camerasOf(context)) {
            html.append(radioRow("cv-" + count++, camera.name, camera.name, chosen));
        }
        for (Cameras.Group group : KioskConfig.cameraGroupsOf(context)) {
            html.append(radioRow("cv-" + count++, group.name, group.name + " (group)", chosen));
        }
        html.append("</div>");
        java.util.List<Cameras.Camera> shownNow = Cameras.resolve(KioskConfig.camerasOf(context),
                KioskConfig.cameraGroupsOf(context), chosen);
        if (count == 0) {
            html.append("<p class=\"hint\">No cameras yet. Add one in Cameras, under Panel.</p>");
        } else if (shownNow.size() > 1) {
            // The layout is for a group of more than one camera, as on the panel.
            // The layout, as on the panel: Auto or a fixed one, the big tile's camera for a
            // group in 1+3 or 1+5, and the names on the tiles.
            String layout = KioskConfig.cameraLayoutOf(context);
            StringBuilder layouts = new StringBuilder();
            for (String[] one : Cameras.LAYOUTS) {
                layouts.append(selectOption(one[0], one[1], layout));
            }
            StringBuilder bigs = new StringBuilder();
            java.util.List<Cameras.Camera> members = Cameras.resolve(KioskConfig.camerasOf(context),
                    KioskConfig.cameraGroupsOf(context), chosen);
            for (Cameras.Camera camera : members) {
                bigs.append(selectOption(camera.name, camera.name, KioskConfig.cameraBigOf(context)));
            }
            html.append("<h2>Layout</h2>")
                    .append(selectField("cv-layout", "camera_layout", "Layout", null,
                            layouts.toString(), null));
            if (members.size() > 1) {
                html.append(selectField("cv-big", "camera_big", "Big tile (one big layouts)", null,
                        bigs.toString(), null));
            }
            html.append(switchRow("cv-names", "Names on the tiles",
                    " name=\"camera_names\" value=\"1\"", KioskConfig.cameraNamesOf(context)));
        }
        html.append("<div class=\"actions\"><button type=\"submit\" class=\"primary\">Save</button>"
                + "</div></form>");
        return html.append(pageEnd()).toString();
    }

    private static String radioRow(String id, String value, String label, String chosen) {
        return "<label class=\"radio\" for=\"" + id + "\"><input type=\"radio\" id=\"" + id
                + "\" name=\"dashboard_cameras\" value=\"" + escapeHtml(value) + "\""
                + (value.equalsIgnoreCase(chosen) ? " checked" : "") + ">" + escapeHtml(label)
                + "</label>";
    }

    private static String cameraGroupForm(java.util.List<Cameras.Camera> cameras,
            Cameras.Group group) {
        boolean fresh = group == null;
        String sfx = fresh ? "-new" : "-" + Integer.toHexString(group.name.hashCode());
        String labelId = "g-cams" + sfx;
        StringBuilder ticks = new StringBuilder("<div role=\"group\" aria-labelledby=\"" + labelId
                + "\"><p class=\"label\" id=\"" + labelId + "\">Cameras</p>");
        for (int i = 0; i < cameras.size(); i++) {
            Cameras.Camera camera = cameras.get(i);
            boolean member = false;
            if (!fresh) {
                for (String one : group.cameras) {
                    member |= one.equalsIgnoreCase(camera.name);
                }
            }
            String id = "g-cam" + sfx + "-" + i;
            ticks.append("<label class=\"check\" for=\"").append(id).append("\"><input type=\"checkbox\" id=\"")
                    .append(id).append("\" name=\"cam").append(i).append("\" value=\"")
                    .append(escapeHtml(camera.name)).append("\"").append(member ? " checked" : "")
                    .append("> ").append(escapeHtml(camera.name)).append("</label>");
        }
        ticks.append("</div>");
        return "<form method=\"post\" action=\"/camera-groups\" class=\"card\">"
                + "<h2>" + (fresh ? "Add a group" : escapeHtml(group.name)) + "</h2>"
                + "<input type=\"hidden\" name=\"was\" value=\""
                + (fresh ? "" : escapeHtml(group.name)) + "\">"
                + fieldWithId("g-name" + sfx, "text", "name", "Name", fresh ? "" : group.name,
                        "", null).replace(MACHINE_TEXT, "")
                + ticks
                + "<div class=\"actions\"><button type=\"submit\" class=\"primary\" name=\"what\""
                + " value=\"save\">" + (fresh ? "Add" : "Save") + "</button>"
                + (fresh ? "" : "<button type=\"submit\" name=\"what\" value=\"show\">Show"
                        + "</button><button type=\"submit\" class=\"danger\" name=\"what\""
                        + " value=\"remove\" data-confirm=\""
                        + escapeHtml(removeQuestion("Remove \"" + group.name + "\"?",
                                Cameras.GROUP_REMOVED))
                        + "\">Remove</button>")
                + "</div></form>";
    }

    /** One group form's post: saved, shown or removed. */
    private String saveCameraGroup(Map<String, String> form) {
        String was = form.getOrDefault("was", "").trim();
        if ("remove".equals(form.get("what"))) {
            KioskService.removeCameraGroup(context, was);
            return null;
        }
        if ("show".equals(form.get("what"))) {
            String refused = kioskService.cameraShow(was, Automations.SHOW_POPUP);
            return refused == null ? "Shown on the panel." : "Not shown: " + refused + ".";
        }
        java.util.List<String> members = new java.util.ArrayList<>();
        for (int i = 0; i < Cameras.MAX_CAMERAS; i++) {
            String member = form.get("cam" + i);
            if (member != null && !member.trim().isEmpty()) {
                members.add(member.trim());
            }
        }
        String problem = KioskService.saveCameraGroup(context, was, form.getOrDefault("name", ""),
                members);
        return problem == null ? null : "Not saved: " + problem + ".";
    }

    /** One camera form's post: saved by the checks both surfaces share, shown, or removed. */
    private String saveCamera(Map<String, String> form) {
        String was = form.getOrDefault("was", "").trim();
        if ("remove".equals(form.get("what"))) {
            KioskService.removeCamera(context, was);
            return null;
        }
        if ("show".equals(form.get("what"))) {
            String refused = kioskService.cameraShow(was, Automations.SHOW_POPUP);
            return refused == null ? "Shown on the panel." : "Not shown: " + refused + ".";
        }
        String problem = KioskService.saveCamera(context, was, form.getOrDefault("name", ""),
                form.getOrDefault("url", ""), form.getOrDefault("full", ""),
                form.getOrDefault("user", ""), form.getOrDefault("password", ""));
        return problem == null ? null : "Not saved: " + problem + ".";
    }

    /**
     * The certificates trusted for pages Android does not trust: the one waiting for
     * an answer with Trust, then each trusted host with its fingerprint and Remove. Each button
     * posts its own small form, so a refusal comes back under this box.
     */
    private String certificatesBox(String notice, String noticeSection) {
        StringBuilder html = new StringBuilder("<h2>Trusted certificates</h2>");
        String message = notice != null && "certificates".equals(noticeSection)
                ? "<p class=\"notice\">" + escapeHtml(notice) + "</p>" : "";
        html.append(message);
        CertificatePins.Waiting waiting = CertificatePins.waiting;
        if (waiting != null) {
            html.append("<p class=\"hint bad\">Waiting for an answer: ")
                    .append(escapeHtml(waiting.host))
                    .append(waiting.issuedTo.isEmpty() ? ""
                            : ", issued to " + escapeHtml(waiting.issuedTo))
                    .append(waiting.validUntil.isEmpty() ? ""
                            : ", valid until " + escapeHtml(waiting.validUntil))
                    .append("<br>SHA-256 ").append(escapeHtml(waiting.fingerprint)).append("</p>")
                    .append(certificateForm("trust", waiting.host, waiting.fingerprint,
                            "Trust this certificate", "tonal"));
        }
        java.util.Map<String, String> pins = KioskConfig.certificatePinsOf(context);
        if (pins.isEmpty() && waiting == null) {
            html.append("<p class=\"hint\">None yet</p>");
        }
        for (java.util.Map.Entry<String, String> pin : pins.entrySet()) {
            html.append("<p class=\"hint\">").append(escapeHtml(pin.getKey()))
                    .append("<br>SHA-256 ").append(escapeHtml(pin.getValue())).append("</p>")
                    .append(certificateForm("forget", pin.getKey(), "", "Remove", "text",
                            removeQuestion("Remove the certificate for " + pin.getKey() + "?",
                                    CertificatePins.REMOVED)));
        }
        if (!pins.isEmpty()) {
            html.append("<p class=\"hint\">A removed certificate is refused from the browser's"
                    + " next connection, at the latest after the nightly restart.</p>");
        }
        return html.toString();
    }

    private static String certificateForm(String action, String host, String fingerprint,
            String label, String buttonClass) {
        return certificateForm(action, host, fingerprint, label, buttonClass, null);
    }

    /** The same, its button asking {@code question} first; null asks nothing. */
    private static String certificateForm(String action, String host, String fingerprint,
            String label, String buttonClass, String question) {
        return "<form method=\"post\" action=\"/page-options\">"
                + "<input type=\"hidden\" name=\"section\" value=\"certificates\">"
                + "<input type=\"hidden\" name=\"action\" value=\"" + action + "\">"
                + "<input type=\"hidden\" name=\"host\" value=\"" + escapeHtml(host) + "\">"
                + "<input type=\"hidden\" name=\"sha256\" value=\"" + escapeHtml(fingerprint)
                + "\"><div class=\"actions tight\"><button type=\"submit\""
                + (question == null ? "" : " data-confirm=\"" + escapeHtml(question) + "\"")
                + " class=\"" + buttonClass + "\">" + escapeHtml(label) + "</button></div></form>";
    }

    /**
     * The page's browser options, a box of their own under the dashboard address,
     * saved together like the panel's Browser options page and checked by the commands' rules.
     */
    private String browserForm(String notice, String noticeSection) {
        BrowserOptions.Settings browser = KioskConfig.browserOf(context);
        return "<h2>The page</h2>"
                // Its own section name, posted back to the Page options page it sits on.
                + sectionFormStart("browser", notice, noticeSection)
                        .replace(settingsAt("browser"), "/page-options")
                + baselineField(browserBaseline(browser))
                + field("text", "browser_user_agent", "User agent", browser.userAgent, "",
                        "Empty: the browser's own, with Muralis.")
                + field("number", "browser_text_size", "Text size (percent)",
                        String.valueOf(browser.textSize))
                + field("number", "browser_page_zoom", "Page zoom (percent, 0 = automatic)",
                        String.valueOf(browser.pageZoom))
                + field("number", "browser_back_after", "Back to the dashboard after (seconds, 0 = never)",
                        String.valueOf(browser.backAfterSeconds))
                + "<div class=\"switches\">"
                + switchRow("browser-dark", "Dark look for the page",
                        " name=\"browser_dark\" value=\"1\""
                                + (BrowserOptions.darkLookPossible(android.os.Build.VERSION.SDK_INT)
                                        ? "" : " disabled"), browser.dark)
                + "</div>"
                + (BrowserOptions.darkLookPossible(android.os.Build.VERSION.SDK_INT) ? ""
                        : "<p class=\"hint\">The dark look needs Android 10 or newer.</p>")
                + "<p class=\"label\">Permissions</p><div class=\"switches\">"
                + switchRow("browser-page-camera", "The camera",
                        " name=\"browser_page_camera\" value=\"1\"", browser.pageCamera)
                + switchRow("browser-page-microphone", "The microphone",
                        " name=\"browser_page_microphone\" value=\"1\"", browser.pageMicrophone)
                + "</div>"
                + field("text", "browser_user", "Page sign-in name", browser.signInUser, "",
                        "Empty: no sign-in.")
                + field("password", "browser_password", "Page sign-in password", "", "",
                        BLANK_KEEPS)
                + sectionFormEnd("Save");
    }

    /** What the browser box's Save compares, the password apart; the panel's page does the same. */
    private static String browserBaseline(BrowserOptions.Settings browser) {
        return browser.userAgent + "|" + browser.textSize + "|" + browser.pageZoom + "|"
                + browser.dark + "|" + browser.backAfterSeconds + "|" + browser.signInUser;
    }

    private static String mqttBaseline(KioskConfig config) {
        return config.mqttHost + "|" + config.mqttPort + "|" + config.mqttUsername + "|"
                + config.mqttTls;
    }

    private String mqttBody(KioskConfig config, String notice, String noticeSection) {
        CertificatePins.Waiting refused = MqttTls.waiting;
        // Only while TLS is on, as on the panel (KioskActivity.addMqttTrust).
        boolean asking = config.mqttTls && refused != null
                && refused.host.equalsIgnoreCase(config.mqttHost)
                && !refused.fingerprint.equals(config.mqttTlsPin);
        StringBuilder trust = new StringBuilder();
        // The broker's certificate: one refused, with Trust; or the one trusted, with Remove.
        // The same lines the panel's MQTT section shows (KioskActivity.addMqttTrust).
        if (asking || !config.mqttTlsPin.isEmpty()) {
            trust.append("<form method=\"post\" action=\"").append(settingsAt("mqtt"))
                    .append("\"><input type=\"hidden\" name=\"section\" value=\"mqtt\">"
                            + "<input type=\"hidden\" name=\"mqtt_action\" value=\"")
                    .append(asking ? "trust" : "forget").append("\"><input type=\"hidden\""
                            + " name=\"sha256\" value=\"")
                    .append(escapeHtml(asking ? refused.fingerprint : config.mqttTlsPin))
                    .append("\"><p class=\"hint").append(asking ? " bad" : "").append("\">")
                    .append(escapeHtml(asking ? MqttTls.waitingLine(refused)
                            : "Trusted broker certificate, SHA-256 " + config.mqttTlsPin))
                    .append("</p><div class=\"actions tight\"><button type=\"submit\"")
                    // Trust is a choice made on the line above; Remove takes the broker away.
                    .append(asking ? "" : " data-confirm=\"" + escapeHtml(removeQuestion(
                            "Remove the trusted broker certificate?", MqttTls.REMOVED)) + "\"")
                    .append(" class=\"text\">").append(asking ? "Trust" : "Remove")
                    .append("</button></div></form>");
        }
        // The client certificate: uploaded here only, since files go through the web admin.
        trust.append("<p class=\"hint\">")
                .append(escapeHtml(MqttTls.clientLine(config.mqttClientName,
                        config.mqttClientUntil))).append("</p>")
                .append("<form method=\"post\" action=\"/mqtt-certificate\""
                        + " enctype=\"multipart/form-data\">")
                .append(fileRow("mqtt-client-file", "certificate", "Client certificate"))
                .append(fileRow("mqtt-client-key", "key", "Its private key"))
                .append("<div class=\"actions\"><button class=\"tonal\" type=\"submit\">")
                .append("Upload</button></div></form>");
        if (!config.mqttClientName.isEmpty()) {
            trust.append("<form method=\"post\" action=\"").append(settingsAt("mqtt"))
                    .append("\" class=\"actions\"><input type=\"hidden\" name=\"section\""
                            + " value=\"mqtt\"><input type=\"hidden\" name=\"mqtt_action\""
                            + " value=\"client_delete\"><button type=\"submit\" class=\"danger\""
                            + " data-confirm=\"")
                    .append(escapeHtml("Delete the client certificate?\n\nThe broker is then"
                            + " reached without one."))
                    .append("\">Delete</button></form>");
        }
        return sharedLine("mqtt") + sectionFormStart("mqtt", notice, noticeSection)
                + baselineField(mqttBaseline(config))
                + urlField("mqtt_host", "Broker host", config.mqttHost)
                + field("number", "mqtt_port", "Broker port", Integer.toString(config.mqttPort))
                + field("text", "mqtt_username", "Username", config.mqttUsername)
                + field("password", "mqtt_password", "Password", "", "", BLANK_KEEPS)
                + "<div class=\"switches\">" + switchRow("mqtt-tls", "Encrypted (TLS)",
                        " name=\"mqtt_tls\" value=\"1\"", config.mqttTls) + "</div>"
                + sectionFormEnd("Save") + trust;
    }

    /**
     * Trust or Remove for the broker's certificate, and Delete for the client certificate. Trust
     * takes only the certificate the page showed, and only while it is still the one refused.
     */
    private String saveMqttTrust(String action, Map<String, String> form, KioskConfig fresh) {
        String sha256 = form.getOrDefault("sha256", "");
        switch (action) {
            case "trust": {
                CertificatePins.Waiting refused = MqttTls.waiting;
                if (refused == null || !refused.fingerprint.equals(sha256)) {
                    return "Not trusted: the broker now shows another certificate, or none;"
                            + " look at the one shown now.";
                }
                KioskConfig.edit(context).mqttTlsPin(sha256).apply();
                MqttTls.waiting = null;
                break;
            }
            case "forget":
                if (!sha256.equals(fresh.mqttTlsPin)) {
                    return "Not removed: the trusted certificate changed meanwhile.";
                }
                KioskConfig.edit(context).mqttTlsPin("").apply();
                break;
            case "client_delete":
                MqttController.forgetClientCertificate(context);
                break;
            default:
                return "Not changed: no such action.";
        }
        KioskService.reloadConfiguration(context);
        KioskService.publishTelemetrySoon(context);
        return null;
    }

    /**
     * The Fleet box: the member port, saved by its own button like the web admin's
     * port, and whether the member endpoint listens on it.
     */
    private String fleetBody(KioskConfig config, String notice, String noticeSection) {
        // In the order of the UI round of 2026-10-26, as on the panel: the fleet and a count, the
        // ways in outside one, what this panel follows, the port with Save and Leave on one row,
        // the Fleet page last.
        boolean member = !KioskConfig.fleetIdOf(context).isEmpty();
        // In a fleet, adding a panel and the invitation live on the Fleet page with its panels;
        // before one, Add by code here is how a fleet is created.
        return fleetMembership()
                + (member ? "" : addByCodeForm(notice, noticeSection))
                + sharedSwitches()
                + "<p class=\"label\">Connection</p>"
                + sectionFormStart("fleet", notice, noticeSection)
                + baselineField(Integer.toString(config.fleetPort))
                + field("number", "fleet_port", "Member port", Integer.toString(config.fleetPort))
                + "<p class=\"hint" + (KioskRuntimeState.fleetListening() ? "" : " bad")
                + "\" id=\"fleet-state\">" + escapeHtml(FleetServer.stateLine()) + "</p>"
                + sectionFormEnd("Save", "<button type=\"button\" class=\"danger\""
                        + " id=\"fleet-leave\"" + (member ? "" : " hidden") + ">Leave</button>")
                + (member ? "<div class=\"actions\"><a class=\"btn text\" href=\"/fleet\">Fleet page"
                        + glyph("next") + "</a></div>" : "");
    }

    /**
     * What this panel follows: the whole fleet's shared settings, and the groups it is in, one
     * switch each. The groups themselves are made, removed and shared from on the Fleet page.
     */
    private String sharedSwitches() {
        if (KioskConfig.fleetIdOf(context).isEmpty()) {
            return "";
        }
        java.util.Map<String, Object> list = KioskConfig.fleetDocumentOf(context);
        java.util.Map<String, Object> me = FleetDocument.memberByKey(list,
                KioskRuntimeState.fleetKey());
        java.util.List<String> mine = FleetDocument.memberGroups(list, me);
        StringBuilder html = new StringBuilder("<div class=\"switches\">"
                + switchRow("fleet-follows", "Follow the fleet's shared settings",
                        " data-setting=\"fleet_follows\"", FleetDocument.followsFleet(me))
                + "</div><p class=\"label\">Groups</p><div class=\"switches\">");
        for (java.util.Map.Entry<String, String> group : FleetDocument.groups(list).entrySet()) {
            String id = escapeHtml(group.getKey());
            html.append(switchRow("fleet-group-" + id, group.getValue(),
                    " data-setting=\"fleet_group_" + id + "\"", mine.contains(group.getKey())));
        }
        html.append("</div>");
        if (FleetDocument.groups(list).isEmpty()) {
            html.append("<p class=\"hint\">No groups yet.</p>");
        }
        return html.toString();
    }

    /** "From the whole fleet …" at the top of a section the fleet shares, or nothing. */
    private String sharedLine(String section) {
        String line = FleetProfile.sharedLine(context, section);
        // What the whole fleet shares a panel may stop following, right where it shows.
        return line == null ? "" : "<p class=\"hint shared\">" + escapeHtml(line)
                + (FleetProfile.fromWholeFleet(section) ? " <button type=\"button\""
                        + " class=\"text\" data-stop-following=\"1\">Stop following the whole fleet</button>" : "")
                + "</p>";
    }

    /**
     * Whether this panel is in a fleet, Join a fleet or Leave the fleet, and the Join code as the
     * panel's Join screen shows it, drawn by admin_stats.js from the status poll.
     */
    private String fleetMembership() {
        boolean member = !KioskConfig.fleetIdOf(context).isEmpty();
        return "<div class=\"fleet-member\"><p id=\"fleet-member\">"
                + escapeHtml(FleetServer.memberLine(context)) + "</p>"
                + "<div class=\"actions\">"
                + "<button type=\"button\" class=\"tonal\" id=\"fleet-join-open\""
                + (member ? " hidden" : "")
                + (KioskRuntimeState.fleetListening() ? "" : " disabled") + ">Join</button>"
                + "</div>"
                + "<p class=\"hint bad\" id=\"fleet-error\" role=\"alert\" hidden></p>"
                + "<div class=\"fleet-join\" id=\"fleet-join\" hidden>"
                + "<img id=\"fleet-join-qr\" alt=\"Join QR\" width=\"240\""
                + " height=\"240\" hidden>"
                + fullScreenButton("fleet-join-qr", "fleet-join-left")
                + "<p class=\"join-code\" id=\"fleet-join-code\"></p>"
                + "<p class=\"hint\" id=\"fleet-join-detail\"></p>"
                + "<p class=\"hint\" id=\"fleet-join-left\"></p>"
                + "<p id=\"fleet-join-line\"></p>"
                + "<div class=\"actions\">"
                + "<button type=\"button\" class=\"text\" id=\"fleet-join-close\">Close</button>"
                + "<button type=\"button\" class=\"tonal\" id=\"fleet-join-new\" hidden>New"
                + " code</button></div></div></div>";
    }

    /**
     * The Add by code form's post, on a path of its own so the connection is given
     * the search's time ({@link #ADOPT_DEADLINE_MS}): the page with who joined, or 400 with why
     * not.
     */
    private void adoptByCode(Map<String, String> form, OutputStream output) throws IOException {
        String joined = KioskConfig.fleetIdOf(context);
        String fleet = joined.isEmpty() ? form.getOrDefault("fleet_name", "").trim()
                : KioskConfig.fleetNameOf(context);
        if (fleet.isEmpty()) {
            fleet = "Muralis-" + (1000 + new java.security.SecureRandom().nextInt(9000));
        }
        String answer;
        boolean added = false;
        String invalid = FleetJoin.validateName("the fleet name", fleet);
        if (invalid != null) {
            answer = "Not added: " + invalid + ".";
        } else {
            String[] panel = new String[1];
            FleetServer.Adopted adopted = FleetServer.adoptByCode(context,
                    form.getOrDefault("join_code", ""),
                    joined.isEmpty() ? FleetServer.newFleetId() : joined, fleet, panel);
            added = adopted.refusal == null;
            answer = !added ? "Not added: " + adopted.refusal + "."
                    : panel[0] + " joined the fleet " + fleet + "."
                            + (adopted.unconfirmed == null ? "" : " It did not confirm"
                            + " afterwards: " + adopted.unconfirmed + ".");
        }
        // From the Fleet page's Add a panel: added, a fresh Fleet page names the new panel, so
        // a reload does not search again; refused, the reason is drawn there.
        // Not when the new panel did not confirm: that sentence is drawn in full instead.
        if ("fleet".equals(form.get("back")) && added && !answer.contains("It did not confirm")) {
            writeResponse(output, 303, "text/plain", bytes("added"), Collections.singletonMap(
                    "Location", "/fleet?added=" + urlEncode(answer.replaceAll(" joined the fleet .*", ""))));
            return;
        }
        writeResponse(output, added ? 200 : 400, "text/html; charset=utf-8",
                bytes("fleet".equals(form.get("back"))
                        ? buildFleetPage(answer, FleetView.of(Collections.emptyMap()))
                        : buildSettingsPage(answer, "adopt")));
    }

    /**
     * Add a panel by the code under its Join QR, for this browser that has no
     * camera: the code, the new fleet's name when this panel founds one, and Add, which looks
     * for the panel on the network for up to 30 seconds before it answers.
     */
    private String addByCodeForm(String notice, String noticeSection) {
        if (!KioskRuntimeState.fleetListening()) {
            return "";
        }
        boolean founding = KioskConfig.fleetIdOf(context).isEmpty();
        // Greyed without Pro rather than refused after the search, with the Pro line in place
        // of the hint to say why.
        boolean pro = FleetServer.proStatus(context).pro;
        return "<p class=\"hint\">" + (pro ? (founding ? "Create a fleet" : "Add a panel")
                + " by its code" : escapeHtml(FleetServer.proLine(context))) + "</p>"
                + sectionFormStart("adopt", notice, noticeSection)
                        .replace(settingsAt("adopt"), "/adopt")
                + (pro ? "" : "<fieldset disabled style=\"border:0;margin:0;padding:0\">")
                + field("text", "join_code", "Code under the other panel's Join QR", "",
                        " inputmode=\"numeric\" autocomplete=\"off\"", null)
                + (founding ? fieldWithId("f-fleet_name", "text", "fleet_name",
                        "Name of the new fleet", "", "", null).replace(MACHINE_TEXT, "") : "")
                + "<p class=\"hint\">Add looks for the panel on this network for up to 30"
                + " seconds.</p>"
                + (pro ? sectionFormEnd("Add")
                        : sectionFormEnd("Add").replace("</form>", "</fieldset></form>"));
    }

    /**
     * Invite a new tablet, as the panel's Fleet section has it: greyed without Pro, the Pro line
     * saying why; pressed, an invitation opens (FleetInvite, ten minutes, single use) and its QR
     * shows with Show full screen and the time left, then what it came to. admin_stats.js draws
     * it from {@code /api/fleet/invite}. Only in a fleet that exists.
     */
    private String inviteBlock() {
        if (!KioskRuntimeState.fleetListening() || KioskConfig.fleetIdOf(context).isEmpty()) {
            return "";
        }
        boolean pro = FleetServer.proStatus(context).pro;
        return "<div class=\"fleet-invite\" id=\"fleet-invite\">"
                + (pro ? "" : "<p class=\"hint\">" + escapeHtml(FleetServer.proLine(context))
                        + "</p>")
                + "<div class=\"actions\"><button type=\"button\" class=\"tonal\""
                + " id=\"fleet-invite-open\"" + (pro ? "" : " disabled")
                + ">Invite a new tablet</button></div>"
                + "<div class=\"fleet-join\" id=\"fleet-invite-box\" hidden>"
                + "<img id=\"fleet-invite-qr\" alt=\"Invitation QR\""
                + " width=\"240\" height=\"240\" hidden>"
                + fullScreenButton("fleet-invite-qr", "fleet-invite-left")
                + "<p id=\"fleet-invite-left\" aria-live=\"polite\"></p>"
                + "<p id=\"fleet-invite-line\" aria-live=\"polite\"></p>"
                + "<div class=\"actions\">"
                + "<button type=\"button\" class=\"text\" id=\"fleet-invite-cancel\">Cancel"
                + " invitation</button></div></div>"
                + "<p class=\"hint bad\" id=\"fleet-invite-error\" role=\"alert\" hidden></p>"
                + "</div>";
    }

    /** The one hint that left a label's brackets for the field's own slot under the box. */
    private static final String BLANK_KEEPS = "Blank keeps the current one.";

    /** The Network speaker section's line: off, on with its port, or why it is not on. */
    private static String speakerSummary(KioskConfig config) {
        String state = KioskRuntimeState.speakerState();
        return (!config.networkSpeaker ? "Speaker off" : "on".equals(state) ? "Speaker on"
                : "Speaker not on") + (config.volumeButtonsDisabled ? " · buttons off"
                        : " · buttons on");
    }

    /**
     * The network speaker (a DLNA renderer): the switch, applied the moment it is touched as on
     * the panel, what it means, and its port with Save. Off by default: DLNA has no password.
     */
    private String speakerBody(KioskConfig config, String notice, String noticeSection) {
        String state = KioskRuntimeState.speakerState();
        return sharedLine("speaker") + "<div class=\"switches\">"
                + switchRow("network-speaker", "Network speaker",
                        " data-setting=\"network_speaker\"", config.networkSpeaker)
                + "</div>"
                + "<p class=\"hint\">While it is on, anyone on your network can play sound"
                + " here.</p>"
                + (config.networkSpeaker && !"on".equals(state)
                        ? "<p class=\"hint bad\">Not on: " + escapeHtml(state) + ".</p>" : "")
                + sectionFormStart("speaker", notice, noticeSection)
                + field("number", "network_speaker_port", "Port",
                        Integer.toString(config.networkSpeakerPort))
                + sectionFormEnd("Save")
                // The device's volume keys, sound like the speaker (the settings round,
                // 2026-10-26: in Way out until then), stored the moment the switch is touched.
                + "<div class=\"switches\">"
                + switchRow("volume-buttons-disabled", "Disable volume buttons",
                        " data-setting=\"volume_buttons_disabled\"",
                        KioskConfig.volumeButtonsDisabled(context))
                + "</div>";
    }

    private String webAdminBody(KioskConfig config, String notice, String noticeSection) {
        return sharedLine("web_admin") + sectionFormStart("webadmin", notice, noticeSection)
                + baselineField(Integer.toString(config.httpPort))
                + field("number", "http_port", "Port", Integer.toString(config.httpPort))
                + field("password", "http_admin_password", "Admin password", "", "",
                        "At least " + MIN_ADMIN_PASSWORD_LENGTH + " characters. " + BLANK_KEEPS)
                + sectionFormEnd("Save")
                + certificateControls(notice, noticeSection);
    }

    /**
     * The certificate the web admin serves, as the panel's Local web admin card has it: the
     * choice between this panel's own and the uploaded one, why an upload chosen is not served,
     * the fingerprint to compare, then the upload of a certificate and its key, and Delete for
     * one kept (planned 2026-09-24, built 2026-10-04). After the Save of port and password,
     * outside that form, since the upload is a form of its own and forms do not nest. Both post
     * to the page's own address. The block carries its state key, so the stats poll draws it
     * again when the panel or the server changed it (AdminCertificate.stateKey, review
     * 2026-10-05).
     */
    private String certificateControls(String notice, String noticeSection) {
        KioskConfig facts = KioskConfig.certificateFacts(context);
        AdminCertificate certificate = served;
        boolean kept = !facts.httpUploadSha256.isEmpty();
        long now = System.currentTimeMillis();
        String radios = radioOf("http_certificate", "http_certificate",
                KioskConfig.CERTIFICATE_OWN, facts.httpCertificate,
                "<span class=\"text\"><span class=\"h\">This panel's own</span></span>")
                + radioOf("http_certificate", "http_certificate",
                        KioskConfig.CERTIFICATE_UPLOADED, facts.httpCertificate,
                        "<span class=\"text\"><span class=\"h\">Uploaded</span><span class=\"s"
                                + (AdminCertificate.uploadExpired(facts, now) ? " bad" : "") + "\">"
                                + escapeHtml(AdminCertificate.uploadLine(facts, now))
                                + "</span></span>", kept ? null : AdminCertificate.NOTHING_UPLOADED);
        String problem = certificate == null || certificate.uploadProblem == null ? ""
                : certificate.uploadProblem;
        return "<div class=\"certificate\" id=\"certificate\" data-lists-kind=\"certificate\" "
                + "data-lists=\"" + escapeHtml(AdminCertificate.stateKey(facts)) + "\">"
                // An upload or a Delete answers here, by the controls that asked, not at the top
                // of the box above Port (review, 2026-10-05).
                + (notice != null && "certificate".equals(noticeSection)
                        ? "<p class=\"notice\">" + escapeHtml(notice) + "</p>" : "")
                + choice("http_certificate", "Certificate", radios)
                + (problem.isEmpty() ? "" : "<p class=\"hint bad\">"
                        + escapeHtml(AdminCertificate.notServedSentence(problem, true)) + "</p>")
                + certificateHint(certificate)
                // The two files, each a button with the name chosen beside it, as the pictures'
                // upload; one file holding both, or the two swapped, work too.
                + "<form method=\"post\" action=\"" + settingsAt("webadmin") + "\" "
                + "enctype=\"multipart/form-data\" id=\"certificate-upload\">"
                + fileRow("certificate-file", "certificate", "Certificate")
                + fileRow("certificate-key", "key", "Private key")
                + "<div class=\"actions\"><button class=\"tonal\" type=\"submit\">"
                + "Upload</button></div></form>"
                + (kept ? "<form method=\"post\" action=\"" + settingsAt("webadmin") + "\" "
                        + "class=\"actions\" data-native=\"1\">"
                        + "<input type=\"hidden\" name=\"section\" value=\"certificate\">"
                        + "<input type=\"hidden\" name=\"sha256\" value=\""
                        + escapeHtml(facts.httpUploadSha256) + "\">"
                        + "<button type=\"submit\" class=\"danger\" data-confirm=\""
                        + escapeHtml(deleteQuestion(AdminCertificate.mainName(facts)) + " "
                                + AdminCertificate.DELETE_CONSEQUENCE)
                        + "\">Delete</button></form>"
                        : "")
                + "</div>";
    }

    /**
     * One file to choose: its button, then the name chosen, as the pictures' upload row has
     * them.
     */
    private static String fileRow(String id, String name, String label) {
        return fileRow(id, name, label, ".pem,.crt,.cer,.key,.txt");
    }

    /** The same for other files, {@code accept} naming the kinds the picker offers. */
    private static String fileRow(String id, String name, String label, String accept) {
        return "<div class=\"uploadrow\">"
                + "<input type=\"file\" id=\"" + id + "\" name=\"" + name + "\" "
                + "accept=\"" + accept + "\" class=\"offscreen\" "
                + "data-chosen=\"" + id + "-chosen\">"
                + "<label class=\"filebutton\" for=\"" + id + "\">" + glyph("upload")
                + label + "</label>"
                + "<span class=\"chosen\" id=\"" + id + "-chosen\">No file chosen</span>"
                + "</div>";
    }

    /**
     * A certificate and its key, posted as files by the web admin's form (to the page's own
     * address) or by curl to /api/certificate
     * ({@code -F certificate=@fullchain.pem -F key=@privkey.pem}). Refused with
     * every reason {@link CertificateUpload} gives. The form's post is answered with the settings
     * page, a script's with JSON.
     */
    private void handleCertificateUpload(Map<String, String> headers, byte[] body,
            OutputStream output, boolean fromPage) throws IOException {
        String boundary = MultipartForm.boundaryOf(headers.get("content-type"));
        java.util.List<MultipartForm.Part> parts = boundary == null
                ? java.util.Collections.emptyList() : MultipartForm.parse(body, boundary);
        String certificateText = "";
        String keyText = "";
        for (MultipartForm.Part part : parts) {
            if (part.name.equals("certificate")) {
                certificateText = new String(part.data, StandardCharsets.UTF_8);
            } else if (part.name.equals("key")) {
                keyText = new String(part.data, StandardCharsets.UTF_8);
            }
        }
        String problem;
        if (boundary == null) {
            problem = "the request was not a file upload";
        } else if (parts.isEmpty() && body.length > 0) {
            problem = "the upload did not arrive complete; please try again";
        } else {
            problem = AdminCertificate.upload(context, certificateText, keyText);
            // Read again whatever the answer: a pair that could not be kept was forgotten.
            reloadCertificate();
            KioskService.publishTelemetrySoon(context);
        }
        answerCertificateChange(fromPage, problem == null ? null : "Not uploaded: " + problem + ".",
                output);
    }

    /**
     * The MQTT client certificate and its key, posted as files by the MQTT box's form; refused
     * Answered with the settings page.
     */
    private void handleMqttClientUpload(Map<String, String> headers, byte[] body,
            OutputStream output) throws IOException {
        String boundary = MultipartForm.boundaryOf(headers.get("content-type"));
        java.util.List<MultipartForm.Part> parts = boundary == null
                ? java.util.Collections.emptyList() : MultipartForm.parse(body, boundary);
        String certificateText = "";
        String keyText = "";
        for (MultipartForm.Part part : parts) {
            if (part.name.equals("certificate")) {
                certificateText = new String(part.data, StandardCharsets.UTF_8);
            } else if (part.name.equals("key")) {
                keyText = new String(part.data, StandardCharsets.UTF_8);
            }
        }
        String problem;
        if (boundary == null) {
            problem = "the request was not a file upload";
        } else if (parts.isEmpty() && body.length > 0) {
            problem = "the upload did not arrive complete; please try again";
        } else {
            problem = MqttController.keepClientCertificate(context, certificateText, keyText);
        }
        if (problem == null) {
            KioskService.reloadConfiguration(context);
            KioskService.publishTelemetrySoon(context);
            writeResponse(output, 303, "text/plain", bytes("done"),
                    Collections.singletonMap("Location", settingsAt("mqtt")));
        } else {
            writeResponse(output, 400, "text/html; charset=utf-8",
                    bytes(buildSettingsPage("Not uploaded: " + problem + ".", "mqtt")));
        }
    }

    /**
     * Deletes the upload. The page's form carries the SHA-256 of the upload it showed, so a
     * renewal that landed meanwhile is not deleted in its place; a script may leave it out.
     */
    private void handleCertificateDelete(Map<String, String> form, OutputStream output,
            boolean fromPage) throws IOException {
        String problem = AdminCertificate.removeUpload(context, form.get("sha256"));
        if (problem == null) {
            reloadCertificate();
            KioskService.publishTelemetrySoon(context);
        }
        answerCertificateChange(fromPage, problem == null ? null : "Not deleted: " + problem + ".",
                output);
    }

    /**
     * The page's own post: done, it goes on to the settings page's web admin box, so a reload
     * does not post it again, and the box itself, the radio moved and the fingerprint changed,
     * is the answer; refused, the settings page with the reason in that box, at the page's own
     * address. A script's post: JSON, the shape /api/setting answers in.
     */
    private void answerCertificateChange(boolean fromPage, String refusal, OutputStream output)
            throws IOException {
        if (fromPage && refusal == null) {
            writeResponse(output, 303, "text/plain", bytes("done"),
                    Collections.singletonMap("Location", settingsAt("webadmin")));
        } else if (fromPage) {
            writeResponse(output, 400, "text/html; charset=utf-8",
                    bytes(buildSettingsPage(refusal, "certificate")));
        } else {
            JSONObject response = new JSONObject();
            try {
                response.put("status", refusal == null ? "accepted" : "rejected");
                response.put("detail", refusal == null ? "saved" : refusal);
            } catch (JSONException impossible) {
                throw new IllegalStateException(impossible);
            }
            writeResponse(output, refusal == null ? 200 : 400, "application/json",
                    bytes(response.toString()));
        }
    }

    private String sequencesBody(KioskConfig config, String notice, String noticeSection) {
        return sectionFormStart("sequences", notice, noticeSection)
                + "<input type=\"hidden\" name=\"sequence_baseline\" value=\""
                + escapeHtml(config.settingsSequence + "|" + config.launcherSequence) + "\">"
                // A TV has no corners to tap: Back held, or Menu, opens the settings.
                + (config.touchscreen
                        ? "<p class=\"hint\">Corner taps: TL, TR, BL, BR, separated by commas, "
                                + EscapeSequence.MIN_LENGTH + " to " + EscapeSequence.MAX_LENGTH
                                + ".</p>"
                                + field("text", "settings_sequence", "Open settings",
                                        config.settingsSequence)
                                + field("text", "launcher_sequence", "Go to home screen",
                                        config.launcherSequence)
                        : "<p class=\"hint\">" + escapeHtml(NO_TOUCH_WAY_IN) + "</p>")
                + "<p class=\"hint\">Optional PIN, asked after either combination: "
                + EscapePin.MIN_LENGTH + " to " + EscapePin.MAX_LENGTH
                + " digits. " + (KioskConfig.escapePinSet(context) ? "One is set." : "None is set.")
                + "</p>"
                + field("password", "escape_pin", "PIN", "", "", BLANK_KEEPS)
                // A second submit on the Save row: it posts escape_pin_clear only when pressed.
                + (KioskConfig.escapePinSet(context)
                        ? sectionFormEnd("Save", "<button type=\"submit\" class=\"danger\""
                                + " name=\"escape_pin_clear\" value=\"1\">Remove the PIN</button>")
                        : sectionFormEnd("Save"));
    }

    /**
     * Cameras: one row a camera, then More camera settings, as Automations lists its rules; a
     * row opens the Cameras page on that camera, as on the panel.
     */
    private String camerasBody() {
        StringBuilder rows = new StringBuilder();
        for (Cameras.Camera camera : KioskConfig.camerasOf(context)) {
            rows.append(linkRow("/cameras?id=" + urlEncode("c:" + camera.name), "cameras",
                    camera.name, Cameras.rowLine(camera.url), null, ""));
        }
        return (rows.length() == 0 ? "" : "<ul class=\"list seg\">" + rows + "</ul>")
                + "<div class=\"actions" + (rows.length() == 0 ? " top0" : "") + "\">"
                + "<a class=\"btn text\" href=\"/cameras\">More camera settings" + glyph("next")
                + "</a></div>";
    }

    /** The Integrations section's line: how many are added and how many answer. */
    private static String integrationsSummary() {
        org.json.JSONArray block = KioskRuntimeState.integrations();
        if (block.length() == 0) {
            return "None yet";
        }
        int running = 0;
        for (int index = 0; index < block.length(); index++) {
            org.json.JSONObject entry = block.optJSONObject(index);
            if (entry != null && entry.optBoolean("enabled")) {
                running++;
            }
        }
        return running + " of " + block.length() + " on";
    }

    /** An integration's row switch, on or off at once (/api/setting, integration_<id>). */
    private static String integrationSwitch(String id, String name, boolean on, String suffix) {
        // Ids pass the store's shape on the way in; escaped here all the same.
        String key = escapeHtml(id);
        return "<input type=\"checkbox\" class=\"sw\" id=\"integration-" + key + suffix
                + "\" data-setting=\"integration_" + key + "\" aria-label=\"" + escapeHtml(name)
                + "\"" + (on ? " checked" : "") + ">";
    }

    /**
     * Integrations: one row an added integration, with its status line, then More integration
     * settings, as Cameras lists its cameras; a row opens the Integrations page on it.
     */
    private String integrationsBody() {
        org.json.JSONArray block = KioskRuntimeState.integrations();
        StringBuilder rows = new StringBuilder();
        for (int index = 0; index < block.length(); index++) {
            org.json.JSONObject entry = block.optJSONObject(index);
            if (entry == null) {
                continue;
            }
            rows.append(linkRow("/integrations?id=" + urlEncode("i:" + entry.optString("id")),
                    "integrations", entry.optString("name"),
                    integrationLine(entry, KioskConfig.hiddenKindsOf(context)), null,
                    integrationSwitch(entry.optString("id"), entry.optString("name"),
                            entry.optBoolean("enabled"), "-home")));
        }
        return "<div id=\"integrations-body\"" + listsAttributes("integrations_home") + ">"
                + (rows.length() == 0 ? "<div class=\"empty\"><p class=\"h\">No integrations"
                        + " yet</p></div>"
                        : "<ul class=\"list seg\">" + rows + "</ul>")
                + "<div class=\"actions\"><a class=\"btn text\" href=\"/integrations\">"
                + (rows.length() == 0 ? "Add an integration" : "More integration settings")
                + glyph("next") + "</a></div></div>";
    }

    /** A row's line: Off, the problem, or the first value in words. */
    private static String integrationLine(org.json.JSONObject entry,
            java.util.Set<String> hidden) {
        // A kind unticked under Integrations offered keeps running and says so first, where
        // the line's end is cut on a narrow row.
        String state = integrationLine(entry);
        return hidden.contains(entry.optString("definition")) ? "Not offered · " + state : state;
    }

    private static String integrationLine(org.json.JSONObject entry) {
        // One short state, the same on both surfaces: Off, the problem, or the first value.
        if (!entry.optBoolean("enabled")) {
            return "Off";
        }
        if (!"ok".equals(entry.optString("state"))) {
            return entry.optString("status");
        }
        org.json.JSONObject values = entry.optJSONObject("values");
        java.util.Iterator<String> ids = values == null
                ? java.util.Collections.emptyIterator() : values.keys();
        while (ids.hasNext()) {
            org.json.JSONObject value = values.optJSONObject(ids.next());
            if (value != null) {
                return (values.length() > 1 ? value.optString("name") + " " : "")
                        + valueWords(value);
            }
        }
        return entry.optString("status");
    }

    /** One value of the status block in words, as IntegrationSettings.describe says it. */
    private static String valueWords(org.json.JSONObject value) {
        if (!value.optBoolean("available") || value.isNull("value")) {
            return "unknown";
        }
        switch (value.optString("type")) {
            case "flag":
                return value.optBoolean("value") ? "on" : "off";
            case "text":
                return "\"" + value.optString("value") + "\"";
            case "time":
                return IntegrationSettings.timeWords((long) value.optDouble("value"));
            default: {
                double number = value.optDouble("value");
                String figure = number == Math.rint(number) && Math.abs(number) < 1e15
                        ? Long.toString((long) number) : Double.toString(number);
                String unit = value.optString("unit");
                return unit.isEmpty() ? figure : figure + " " + unit;
            }
        }
    }

    /**
     * The Integrations page, as Cameras is: the added integrations at the left with their status
     * lines, then the ones this version offers to add, and the chosen one's box at the right;
     * below 840 dp one list, and a box is a page of its own. {@code selected} is "i:" and an
     * instance id, or "d:" and a definition id to add one. {@code typed} refills the box that
     * was posted (a try before saving, a refusal), so nothing typed is lost.
     */
    private String buildIntegrationsPage(String notice, String selected, boolean detailPage,
            Map<String, String> typed) {
        java.util.List<Integrations.Instance> instances = KioskConfig.integrationsOf(context);
        org.json.JSONArray block = KioskRuntimeState.integrations();
        java.util.Map<String, org.json.JSONObject> status = new java.util.HashMap<>();
        for (int index = 0; index < block.length(); index++) {
            org.json.JSONObject entry = block.optJSONObject(index);
            if (entry != null) {
                status.put(entry.optString("id"), entry);
            }
        }
        java.util.Set<String> hidden = KioskConfig.hiddenKindsOf(context);
        String chosen = integrationItemOrFirst(selected, instances, hidden);
        StringBuilder rows = new StringBuilder();
        for (Integrations.Instance instance : instances) {
            String id = "i:" + instance.id;
            org.json.JSONObject entry = status.get(instance.id);
            String line = entry == null ? (hidden.contains(instance.definition) ? "Not offered"
                    : "") : integrationLine(entry, hidden);
            rows.append(navRow(linkRow("/integration?id=" + urlEncode(id), "integrations",
                    instance.name, line, null,
                    integrationSwitch(instance.id, instance.name, instance.enabled, "")),
                    id, id.equals(chosen)));
        }
        StringBuilder offered = new StringBuilder();
        for (Integrations.Definition definition : Integrations.DEFINITIONS.values()) {
            if (!Integrations.offered(definition, hidden)) {
                // Business does not exist yet, and an unticked kind is not offered.
                continue;
            }
            String id = "d:" + definition.id;
            // A kind opens its box in place from 840 px (data-pane), its page below.
            offered.append(linkRow("/integration?id=" + urlEncode(id), "integrations",
                    definition.name, definition.summary, null, glyph("next"))
                    .replace("<a class=\"rowlink plain\"", "<a data-pane=\"" + id
                            + "\" class=\"rowlink plain\""));
        }
        String nav = "<section class=\"card nav\" id=\"integrations-nav\""
                + listsAttributes("integrations_page") + "><div class=\"cardhead\">"
                + "<h2>Integrations</h2><span class=\"count\">"
                + escapeHtml(integrationsSummary()) + "</span></div>"
                + (rows.length() == 0 ? "<p class=\"hint\">No integrations yet.</p>"
                        : "<ul class=\"list seg\">" + rows + "</ul>")
                // Under the list, as every Add; the kinds are its pane at the right.
                + (instances.size() >= Integrations.MAX_INSTANCES
                        // Full, no Add list: Integrations offered is still reachable.
                        ? "<div class=\"actions\"><a class=\"btn text\" href=\"/integration?id=offered\""
                                + " data-pane=\"offered\">Integrations offered" + glyph("next") + "</a></div>"
                        : "<div class=\"actions\"><a class=\"btn tonal\" href=\"/integration?id=add\""
                                + " data-pane=\"add\">" + glyph("plus") + "Add an integration</a></div>")
                + "</section>";
        StringBuilder detail = new StringBuilder();
        for (Integrations.Instance instance : instances) {
            String id = "i:" + instance.id;
            detail.append(pane(id, chosen, integrationForm(instance,
                    Integrations.DEFINITIONS.get(instance.definition), status.get(instance.id),
                    id.equals(chosen) ? typed : null)));
        }
        if (instances.size() < Integrations.MAX_INSTANCES) {
            detail.append(pane("add", chosen, "<section class=\"card\"><h2>Add an integration"
                    + "</h2>" + (offered.length() == 0 ? "<p class=\"hint\">None is offered.</p>"
                            : "<ul class=\"list\">" + offered + "</ul>")
                    // Which kinds this list shows, at its foot (decided 2026-10-27).
                    + "<ul class=\"list\">" + linkRow("/integration?id=offered", "tune",
                            "Integrations offered", offeredLine(hidden), null, glyph("next"))
                            .replace("<a class=\"rowlink plain\"",
                                    "<a data-pane=\"offered\" class=\"rowlink plain\"")
                    + "</ul></section>"));
            for (Integrations.Definition definition : Integrations.DEFINITIONS.values()) {
                if (!Integrations.offered(definition, hidden)) {
                    continue;
                }
                String id = "d:" + definition.id;
                detail.append(pane(id, chosen, integrationForm(null, definition, null,
                        id.equals(chosen) ? typed : null)));
            }
        }
        detail.append(pane("offered", chosen, offeredForm(hidden)));
        boolean fresh = chosen.startsWith("d:");
        return pageStart(!detailPage ? "Integrations" : chosen.equals("offered")
                        ? "Integrations offered" : chosen.equals("add")
                        ? "Add an integration" : fresh ? "New integration" : "Edit integration",
                null, detailPage ? "/integrations" : settingsAt("integrations"))
                + (notice == null ? "" : "<p class=\"notice\">" + escapeHtml(notice) + "</p>")
                + listDetail("integrations", detailPage, nav, detail.toString(),
                        " data-base=\"/integrations?id=\" data-list=\"/integrations\"")
                + pageEnd();
    }

    /** "4 of 5 offered": the line of Integrations offered at the foot of the Add list. */
    private static String offeredLine(java.util.Set<String> hidden) {
        int all = 0;
        int shown = 0;
        for (Integrations.Definition definition : Integrations.DEFINITIONS.values()) {
            if (Integrations.offerable(definition)) {
                all++;
                shown += hidden.contains(definition.id) ? 0 : 1;
            }
        }
        return shown + " of " + all + " offered";
    }

    /**
     * Integrations offered: a switch per kind, grouped by who it is for, saying which kinds the
     * Add lists show; with the fleet sharing the integrations, on every panel. Added ones keep
     * running. The baseline refuses a save over a change made elsewhere meanwhile.
     */
    private static String offeredForm(java.util.Set<String> hidden) {
        StringBuilder groups = new StringBuilder();
        for (String[] audience : Integrations.AUDIENCE_WORDS) {
            StringBuilder switches = new StringBuilder();
            for (Integrations.Definition definition : Integrations.DEFINITIONS.values()) {
                if (Integrations.offerable(definition)
                        && audience[0].equals(Integrations.audienceOf(definition.id))) {
                    switches.append(switchRow("offer-" + definition.id, definition.name,
                            " name=\"offer_" + definition.id + "\" value=\"1\"",
                            !hidden.contains(definition.id)));
                }
            }
            if (switches.length() > 0) {
                groups.append("<p class=\"label\">").append(escapeHtml(audience[1]))
                        .append("</p><div class=\"switches\">").append(switches).append("</div>");
            }
        }
        return "<form method=\"post\" action=\"/integrations\" class=\"card\"><h2>Integrations"
                + " offered</h2><p class=\"hint\">What Add an integration lists. Added ones keep"
                + " running.</p>" + hidden("what", "offered")
                + hidden("baseline", Integrations.storeHidden(hidden)) + groups
                + "<div class=\"actions\"><button class=\"primary\" type=\"submit\">Save</button>"
                + "</div></form>";
    }

    /** The item to show: the one asked for when it exists, else the first added, else the first offered. */
    private static String integrationItemOrFirst(String item,
            java.util.List<Integrations.Instance> instances, java.util.Set<String> hidden) {
        boolean room = instances.size() < Integrations.MAX_INSTANCES;
        if ("offered".equals(item)) {
            return item;
        }
        if (item != null) {
            if (room && (item.equals("add") || item.startsWith("d:")
                    && Integrations.DEFINITIONS.containsKey(item.substring(2))
                    && !hidden.contains(item.substring(2)))) {
                return item;
            }
            for (Integrations.Instance instance : instances) {
                if (item.equals("i:" + instance.id)) {
                    return item;
                }
            }
        }
        return instances.isEmpty() ? "add" : "i:" + instances.get(0).id;
    }

    /**
     * One integration's box: its name, its settings drawn from the definition's fields, its
     * status and values while added, and Save or Add, Try, Remove. A secret
     * box renders empty, as every password box: empty keeps the stored one.
     */
    private static String integrationForm(Integrations.Instance instance,
            Integrations.Definition definition, org.json.JSONObject entry,
            Map<String, String> typed) {
        boolean fresh = instance == null;
        if (definition == null) {
            return "<section class=\"card\"><h2>" + escapeHtml(instance.name) + "</h2>"
                    + "<p class=\"hint\">This version of Muralis does not have the "
                    + escapeHtml(instance.definition) + " integration. A newer version added it."
                    + "</p><form method=\"post\" action=\"/integrations\"><input type=\"hidden\""
                    + " name=\"id\" value=\"" + escapeHtml(instance.id) + "\"><div class=\"actions\">"
                    + "<button type=\"submit\" class=\"danger\" name=\"what\" value=\"remove\""
                    + " data-confirm=\"" + escapeHtml(removeQuestion("Remove \"" + instance.name
                            + "\"?", IntegrationSettings.REMOVED)) + "\">Remove</button></div>"
                    + "</form></section>";
        }
        String sfx = fresh ? "-" + definition.id : "-" + instance.id;
        StringBuilder form = new StringBuilder();
        form.append("<form method=\"post\" action=\"/integrations\" class=\"card\">")
                .append("<h2>").append(escapeHtml(fresh ? definition.name : instance.name))
                .append("</h2>");
        if (fresh) {
            form.append("<p class=\"hint\">").append(escapeHtml(definition.summary))
                    .append("</p>");
        } else if (entry != null) {
            // The kind only where the name does not already say it.
            form.append("<p class=\"hint\">").append(instance.name.equals(definition.name) ? ""
                    : escapeHtml(definition.name) + " · ")
                    .append(escapeHtml(entry.optString("status"))).append("</p>");
            org.json.JSONObject values = entry.optJSONObject("values");
            if (values != null && values.length() > 0 && "ok".equals(entry.optString("state"))) {
                StringBuilder list = new StringBuilder();
                java.util.Iterator<String> ids = values.keys();
                while (ids.hasNext()) {
                    org.json.JSONObject value = values.optJSONObject(ids.next());
                    if (value != null) {
                        list.append("<li class=\"two\"><span class=\"text\"><span class=\"h\">")
                                .append(escapeHtml(valueWords(value)))
                                .append("</span><span class=\"s\">")
                                .append(escapeHtml(value.optString("name") + " · "
                                        + value.optString("sensor")))
                                .append("</span></span></li>");
                    }
                }
                form.append("<ul class=\"list seg values\">").append(list).append("</ul>");
            }
        }
        form.append("<input type=\"hidden\" name=\"id\" value=\"")
                .append(fresh ? "" : escapeHtml(instance.id)).append("\">")
                .append("<input type=\"hidden\" name=\"definition\" value=\"")
                .append(escapeHtml(definition.id)).append("\">");
        String name = typed != null ? typed.getOrDefault("name", "")
                : fresh ? definition.name : instance.name;
        form.append(fieldWithId("n" + sfx, "text", "name", "Name", name, "", null)
                .replace(MACHINE_TEXT, ""));
        for (Integrations.Field field : definition.fields) {
            String id = "f" + sfx + "-" + field.id;
            String key = "f_" + field.id;
            // A secret box renders empty, also after a Try or a refusal: what was typed into it
            // is never sent back to the browser.
            String value = field.secret ? "" : typed != null ? typed.getOrDefault(key, "")
                    : fresh ? "" : instance.settings.getOrDefault(field.id, "");
            boolean stored = !fresh && entry != null && jsonArrayHas(
                    entry.optJSONArray("secrets_set"), field.id);
            String support = field.secret && stored ? BLANK_KEEPS
                    : !field.hint.isEmpty() ? field.hint : null;
            if (field.kind == Integrations.Kind.CHOICE) {
                String current = value.isEmpty() ? field.fallback : value;
                StringBuilder options = new StringBuilder();
                for (String option : field.options) {
                    options.append("<option value=\"").append(escapeHtml(option)).append("\"")
                            .append(option.equals(current) ? " selected" : "").append(">")
                            .append(escapeHtml(option)).append("</option>");
                }
                form.append(selectField(id, key, field.label, null, options.toString(),
                        support));
            } else {
                String type = field.secret ? "password"
                        : field.kind == Integrations.Kind.NUMBER ? "number" : "text";
                String extra = field.kind == Integrations.Kind.URL ? " inputmode=\"url\""
                        : field.kind == Integrations.Kind.NUMBER
                                ? " min=\"" + field.min + "\" max=\"" + field.max + "\"" : "";
                // What empty means, greyed inside the box.
                if (!field.secret && !field.fallback.isEmpty()) {
                    extra += " placeholder=\"" + escapeHtml(field.fallback) + "\"";
                }
                // "(optional)" only where empty means nothing.
                boolean optional = !field.required && field.fallback.isEmpty();
                form.append(fieldWithId(id, type, key, field.label
                        + (optional ? " (optional)" : ""), value, extra, support));
            }
        }
        form.append("<div class=\"actions\"><button type=\"submit\" class=\"primary\""
                + " name=\"what\" value=\"save\">").append(fresh ? "Add" : "Save")
                .append("</button><button type=\"submit\" name=\"what\" value=\"test\">Try"
                        + "</button>");
        if (!fresh) {
            // On and off is the row's switch in the list.
            form.append("<button type=\"submit\" class=\"danger\" name=\"what\""
                            + " value=\"remove\" data-confirm=\"")
                    .append(escapeHtml(removeQuestion("Remove \"" + instance.name + "\"?",
                            IntegrationSettings.REMOVED)))
                    .append("\">Remove</button>");
        }
        form.append("</div>");
        if (!fresh && Integrations.hasPage(instance.definition)) {
            // Its own page, the dashboard's fourth answer: how it looks is set there.
            form.append("<div class=\"actions\"><a class=\"btn text\" href=\"/clock-options?id=")
                    .append(urlEncode(instance.id)).append("\">Clock options").append(glyph("next"))
                    .append("</a></div>");
        }
        return form.append("</form>").toString();
    }

    private static boolean jsonArrayHas(org.json.JSONArray array, String value) {
        for (int index = 0; array != null && index < array.length(); index++) {
            if (value.equals(array.optString(index))) {
                return true;
            }
        }
        return false;
    }

    /**
     * A post from an integration's box: Add or Save, Try, Remove. Answers the
     * page again with what happened, on the integration it concerned; a try or a refusal keeps
     * what was typed.
     */
    private void postIntegration(Map<String, String> form, OutputStream output)
            throws IOException {
        String what = form.getOrDefault("what", "");
        String id = form.getOrDefault("id", "").trim();
        String definition = form.getOrDefault("definition", "").trim();
        Map<String, String> values = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, String> entry : form.entrySet()) {
            if (entry.getKey().startsWith("f_")) {
                values.put(entry.getKey().substring(2), entry.getValue());
            }
        }
        String item = id.isEmpty() ? "d:" + definition : "i:" + id;
        String notice;
        boolean refused;
        Map<String, String> keep = null;
        switch (what) {
            case "save": {
                IntegrationSettings.Saved saved = IntegrationSettings.save(context, id,
                        definition, form.getOrDefault("name", ""), values);
                refused = saved.problem != null;
                notice = refused ? "Not saved: " + saved.problem + "." : id.isEmpty()
                        ? "Added. It asks its service now." : "Saved.";
                if (refused) {
                    keep = form;
                } else {
                    item = "i:" + saved.id;
                }
                break;
            }
            case "test":
                notice = IntegrationSettings.test(context, id, definition, values);
                refused = notice.startsWith("Not ");
                keep = form;
                break;
            case "offered": {
                if (!Integrations.storeHidden(KioskConfig.hiddenKindsOf(context))
                        .equals(form.getOrDefault("baseline", ""))) {
                    notice = "Not saved: Integrations offered was changed elsewhere after this"
                            + " page loaded. The page now shows the current choice.";
                    refused = true;
                } else {
                    java.util.Set<String> ticked = new java.util.HashSet<>();
                    for (String key : form.keySet()) {
                        if (key.startsWith("offer_")) {
                            ticked.add(key.substring("offer_".length()));
                        }
                    }
                    IntegrationSettings.setOffered(context, ticked);
                    notice = "Saved.";
                    refused = false;
                }
                item = "offered";
                break;
            }
            case "remove": {
                String problem = IntegrationSettings.remove(context, id);
                refused = problem != null;
                notice = refused ? "Not removed: " + problem + "." : "Removed.";
                item = null;
                break;
            }
            default:
                notice = "Not done: unknown action.";
                refused = true;
        }
        writeResponse(output, refused ? 400 : 200, "text/html; charset=utf-8",
                bytes(buildIntegrationsPage(notice, item, false, keep)));
    }

    /**
     * Day and night at the end of the settings page's app bar, as on the panel: one icon button
     * showing the mode in force (the sun, the moon, the half-lit sun for following the device),
     * each tap moving to the next (admin_theme.js). Kept in the browser, a preference of whoever
     * reads the page, not of the panel.
     */
    private static String themeToggle() {
        return "<button type=\"button\" class=\"ib themetoggle\" id=\"theme-toggle\""
                + " title=\"Day and night\" aria-label=\"Day and night\">"
                + "<span class=\"m-light\">" + glyph("sun") + "</span>"
                + "<span class=\"m-dark\">" + glyph("moon") + "</span>"
                + "<span class=\"m-system\">" + glyph("auto") + "</span></button>";
    }

    private String displayBody() {
        // The theme switch stands beside the section's name (see the menu builder); the app
        // bar's Auto/Light/Dark went (2026-09-27).
        return sharedLine("display")
                + brightnessControl()
                + autoBrightnessControl()
                + writeSettingsHint()
                + orientationControl()
                + displayOffControl()
                + statusLightControl();
    }

    /**
     * The status light: the switch and the colour by name, applied as they are touched, the same
     * two controls as on the panel. The brightness and any other colour are set over HTTP, MQTT
     * and the smart home; a stored colour that has no name is offered as it is.
     */
    private String statusLightControl() {
        StatusLight.State light = KioskConfig.statusLightOf(context);
        String current = StatusLight.formatColor(light.color);
        StringBuilder colours = new StringBuilder();
        if (StatusLight.nameOf(light.color) == null) {
            colours.append(selectOption(current, current, current));
        }
        for (String[] named : StatusLight.NAMED) {
            colours.append(selectOption(named[1], named[0], current));
        }
        return "<div class=\"switches\">"
                + switchRow("status-light", "Status light",
                        " data-setting=\"status_light\"", light.on)
                + "</div>"
                + selectField("status-light-color", null, "Status light colour",
                        "status_light_color", colours.toString(), null);
    }

    private String screensaverBody() {
        ScreensaverPolicy.Settings saver = KioskConfig.screensaverOf(context);
        boolean problem = saver.enabled()
                && ScreensaverPolicy.modeProblem(saver.mode, saver.url) != null;
        // Radios, as on the panel; the Ambient page carries no chooser.
        return sharedLine("ambient")
                + choice("ambient_mode", "Mode", screensaverModeOptions(saver))
                + "<p class=\"hint" + (problem ? " bad" : "") + "\" id=\"ambient-note\">"
                + escapeHtml(ScreensaverPolicy.describe(saver, KioskRuntimeState.screensaverActive()))
                + "</p>"
                // Off has no page to lead to: the page holds a mode's options and Off has none
                // (2026-09-11); admin_setting.js follows the radios. Preview is on the page.
                + "<div class=\"actions\">"
                + "<a class=\"btn text" + (ScreensaverPolicy.OFF.equals(saver.mode) ? " gone" : "")
                + "\" id=\"ambient-more\" href=\"/ambient\">More Ambient settings"
                + glyph("next") + "</a></div>";
    }

    private String statsBody(KioskConfig config) {
        // The switch sits under the readout it governs. No form and no Save button: it stands
        // alone and applies itself, the way the brightness controls do; see settingScript.
        return "<pre id=\"stats\">loading...</pre>"
                + switchRow("stats-overlay", "Stats overlay",
                        " data-setting=\"stats_overlay\"", config.statsOverlay);
    }

    /**
     * The Log panel, the panel's own in the same order: the level chips (the app's own lines by
     * default), a search box, pause, copy, download and clear-from-here, then the block
     * admin_stats.js fills and colours. Empty until the first poll, since a "loading" word in a
     * log would read as a log line.
     */
    private String logBody() {
        return "<div class=\"logbar\" id=\"logbar\">"
                + "<div class=\"levels\" role=\"group\" aria-label=\"What the log shows\">"
                + "<button type=\"button\" class=\"lvl\" data-level=\"V\" data-own=\"1\""
                + " aria-pressed=\"true\">Muralis</button>"
                + "<button type=\"button\" class=\"lvl\" data-level=\"E\" aria-pressed=\"false\" aria-label=\"Errors\" title=\"Errors\">E</button>"
                + "<button type=\"button\" class=\"lvl\" data-level=\"W\" aria-pressed=\"false\" aria-label=\"Warnings\" title=\"Warnings\">W</button>"
                + "<button type=\"button\" class=\"lvl\" data-level=\"V\" aria-pressed=\"false\">All</button>"
                + "</div>"
                + "<span class=\"logcount\" id=\"log-count\"></span>"
                + "<label class=\"logsearch\">" + glyph("search")
                + "<input type=\"search\" id=\"log-q\" autocomplete=\"off\""
                + " aria-label=\"Search the log\"></label>"
                + "<button type=\"button\" class=\"ib\" id=\"log-pause\" title=\"Pause\""
                + " aria-label=\"Pause\">" + glyph("pause") + glyph("play") + "</button>"
                + "<button type=\"button\" class=\"ib\" id=\"log-copy\" title=\"Copy\""
                + " aria-label=\"Copy\">" + glyph("copy") + "</button>"
                + "<button type=\"button\" class=\"ib\" id=\"log-download\" title=\"Download\""
                + " aria-label=\"Download\">" + glyph("download") + "</button>"
                + "<button type=\"button\" class=\"ib\" id=\"log-clear\" title=\"Clear\""
                + " aria-label=\"Clear\">" + glyph("clear") + "</button>"
                + "</div>"
                + "<pre id=\"log\" role=\"log\" tabindex=\"0\" aria-label=\"The app's log\"></pre>";
    }

    /**
     * The commands a person at a browser has where one at the panel has Open dashboard and the
     * combinations: Material's overflow menu at the end of the settings page's app bar (the UI
     * round, 2026-10-15; a section of their own, Quick actions, until then). The "Main
     * dashboard" action left this menu on 2026-09-07; Open dashboard (panel.dashboard) is in the
     * Way out section since 2026-10-25, beside Open settings and Go to home screen, where it brings
     * Muralis back after it left. Without scripting the menu cannot open, so it is shown as its
     * three buttons in a row.
     */
    private static String commandsMenu() {
        return "<div class=\"overflow\"><button type=\"button\" class=\"ib\" id=\"commands-button\""
                + " aria-haspopup=\"menu\" aria-expanded=\"false\" aria-controls=\"commands-menu\""
                + " title=\"Commands\" aria-label=\"Commands\">"
                + glyph("more") + "</button>"
                + "<div class=\"m3menu\" id=\"commands-menu\" role=\"menu\">"
                // The way in and out from here, without the corner taps, and the display at
                // once: commands, not settings (the settings round, 2026-10-26).
                + quickAction("panel.dashboard", "Open dashboard", "", "dash")
                + quickAction("panel.settings", "Open settings", "", "esc")
                // Asked first: whoever stands at the panel gets the device's apps.
                + "<form class=\"cmd\" method=\"post\" action=\"/api/command\">"
                + "<input type=\"hidden\" name=\"cmnd\" value=\"panel.home_screen\">"
                + "<button type=\"submit\" data-confirm=\"" + escapeHtml(leaveQuestion(null))
                + "\">" + glyph("open") + "Go to home screen</button></form>"
                + quickAction("display.wake", "Display on", "", "sun")
                + quickAction("display.visual_off", "Display off", "", "moon")
                + quickAction("dashboard.reload", "Reload dashboard", "", "reload")
                + quickAction("app.restart", "Restart app", "", "power")
                + quickAction("system.reboot", "Reboot", "", "reboot")
                + "</div></div>";
    }



    /**
     * Renders one legal document ({@code res/raw/privacy.txt} or {@code terms.txt}) as a
     * standalone page, for parity with the tablet's own About screen. Behind the same Basic Auth as
     * everything else on this server: whoever reaches this page already reached the rest of it.
     *
     * <p>Blocks are separated by blank lines; an ALL-CAPS block is a heading, matching the format
     * {@code KioskActivity#addDocumentBlocks} renders on the tablet.
     *
     * <p>Nothing is added to the document, for the reason {@code KioskActivity#showLegalDocument}
     * gives: this page and that screen render the canonical text and nothing else, so a reader
     * cannot be told one thing here and another on the published page.
     */
    private String renderLegalPage(String title, int rawRes) {
        StringBuilder html = new StringBuilder(pageStart(title, null, settingsAt("panel")));
        html.append("<div class=\"doc one\">");
        for (String block : readRawText(rawRes).trim().split("\n\\s*\n")) {
            String content = block.trim();
            if (content.isEmpty()) {
                continue;
            }
            boolean heading = content.equals(content.toUpperCase(Locale.ROOT))
                    && content.chars().anyMatch(Character::isLetter);
            html.append(heading ? "<h2>" : "<p class=\"doc\">")
                    .append(escapeHtml(content))
                    .append(heading ? "</h2>" : "</p>");
        }
        // Where the public copy of the same document lives, as a real link: unlike the tablet,
        // whoever is reading this page has a browser. Sentence and URLs come from legal.xml so
        // this surface and the About screen cannot drift; the %1$s placeholder is replaced by an
        // anchor, which is why the two halves are escaped separately around it.
        String template = context.getString(R.string.legal_also_published);
        String publicUrl = context.getString(rawRes == R.raw.privacy
                ? R.string.legal_privacy_url : R.string.legal_terms_url);
        int urlAt = template.indexOf("%1$s");
        html.append("<p class=\"hint\">")
                .append(escapeHtml(template.substring(0, urlAt)))
                .append("<a href=\"").append(escapeHtml(publicUrl)).append("\">")
                .append(escapeHtml(publicUrl)).append("</a>")
                .append(escapeHtml(template.substring(urlAt + 4))).append(".</p>");
        html.append("</div>").append(themeScript).append("</main></body></html>");
        return html.toString();
    }

    /** Reads a {@code res/raw} text file in full, mirroring {@code KioskActivity#readRawText}. */
    private String readRawText(int rawRes) {
        try (InputStream in = context.getResources().openRawResource(rawRes);
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException unavailable) {
            Log.w(TAG, "Could not read document resource", unavailable);
            return "This document could not be loaded.";
        }
    }

    /**
     * The brightness slider, positioned at the backlight's actual level.
     *
     * <p>It used to be hardcoded to {@code value="70"} and never moved, which was worse than
     * uninformative: with the ambient sensor covered the panel was almost black while the slider still
     * read 70%, so the page confidently contradicted the device. Reported from the panel 2026-08-19.
     *
     * <p>Rendered from {@code KioskService.statsJson().display} and then kept live by
     * {@code statsScript} on every poll, which matters most in automatic mode, where the value moves
     * on its own as the light changes and no page load would ever catch up.
     */
    private String brightnessControl() {
        int percent = 70;
        String mode = "manual";
        boolean sensorInCharge = false;
        try {
            org.json.JSONObject display = kioskService.statsJson().optJSONObject("display");
            if (display != null) {
                sensorInCharge = display.optBoolean("auto", false);
                mode = describeBrightnessMode(display.optString("source"), sensorInCharge);
                // A dark panel reports zero, which this slider cannot show (its floor is 1%, the
                // lowest level anyone can ask for); the mode label says "display off" instead and
                // the slider keeps its resting position.
                if (!"display off".equals(mode)) {
                    percent = display.optInt("brightness_percent", 70);
                }
            }
        } catch (RuntimeException unavailable) {
            // Fall back to the old fixed position rather than dropping the control.
        }
        percent = Math.max(1, Math.min(100, percent));
        // The mode label is ALWAYS rendered and carries an id, so statsScript can keep it current.
        // It first shipped as a bare "(automatic)" with no id, emitted only in automatic mode: it never
        // changed to "manual" when the switch was turned off, and never changed at all after page load,
        // which is precisely the staleness this control was rewritten to remove. Naming the mode is
        // worth doing, because in automatic mode the slider is a readout the sensor keeps moving, and
        // dragging it takes the panel away from the sensor.
        // Disabled while the sensor is in charge, because since 2026-08-20 a level set in automatic
        // mode is refused rather than written. It was refused for a good reason (the backlight never
        // followed it, so the panel reported a brightness nobody was looking at), but a control that
        // moves and is then rejected is its own small lie. The mode label beside it says why, and
        // statsScript keeps both in step with the switch.
        // Stacked, not a flex row. As one row of caption + slider + readout + mode, this was the
        // widest thing on the page and it could not shrink: a range input has an intrinsic minimum
        // width, the readout reserved 3.2rem, and the whole row therefore had a min-content width
        // larger than a narrow phone's column. A grid item defaults to min-width:auto, so the
        // Display fieldset could not be squeezed to match its neighbours and hung over the right
        // edge of the screen while every other box lined up. Seen on a 360px-wide phone
        // 2026-08-23; invisible on a large one, which is exactly why it survived this long. The
        // grid and the fieldset were both given room to shrink as well; see pageCss.
        //
        // Stacking also puts the readout where it was asked to go, directly under the caption,
        // where it reads as a value belonging to "Brightness" rather than as a number floating at
        // the far end of a track.
        return "<label class=\"slider\">Brightness"
                + "<span class=\"readout\"><output id=\"brightness-value\">" + percent
                + "%</output> <small id=\"brightness-mode\">(" + mode + ")</small></span>"
                + "<input type=\"range\" id=\"brightness\" min=\"1\" max=\"100\" value=\""
                + percent + "\"" + (sensorInCharge ? " disabled" : "") + "></label>";
    }

    /**
     * Names the brightness mode, from the switch and nothing else.
     *
     * <p>Two states only, by design. There was briefly a third, "manual override", for a window
     * override pinned above automatic mode; it was removed by making {@code display.brightness} write
     * the system setting instead, so the state cannot arise. **The switch is the mode**: on means the
     * sensor decides, off means the slider does, and this label can never contradict it.
     *
     * <p>{@code display_off} is not a mode. It is the {@code display.visual_off} presentation state,
     * where the panel is dimmed to 1% behind a black overlay, and it says so rather than claiming the
     * operator chose 1%.
     */
    private static String describeBrightnessMode(String source, boolean autoMode) {
        if ("display_off".equals(source)) {
            return "display off";
        }
        if ("ambient".equals(source)) {
            return "ambient";
        }
        return autoMode ? "automatic" : "manual";
    }


    /**
     * The orientation menu. A command rather than a {@code data-setting}, deliberately: a setting
     * post only persists, while this has to turn the panel now, and the command path already carries
     * the change through KioskService to the activity that owns the window.
     */
    private String orientationControl() {
        String current = KioskConfig.orientationOf(context);
        StringBuilder options = new StringBuilder();
        // "auto" only where an accelerometer exists to drive it, the same gate the
        // auto-brightness switch above sits behind.
        for (String[] choice : KioskCommandDispatcher.ORIENTATION_WORDS) {
            if (!KioskConfig.ORIENTATION_AUTO.equals(choice[0])
                    || KioskService.hasAccelerometer(context)) {
                options.append(selectOption(choice[0], choice[1], current));
            }
        }
        return selectField("orientation", null, "Orientation", null, options.toString(), null);
    }

    /**
     * One picture's thumbnail, made by the panel once and kept; see {@code PictureLibrary.thumbnail}.
     *
     * <p>Only the panel's own pictures, MediaStore rows and uploads, which is what the library
     * answers for: any other address is refused before a file is opened, so this route cannot be
     * pointed at a file on the device. A day of caching in the browser, private to it, because a
     * picture under the same address does not change.
     */
    private void serveThumbnail(String uri, OutputStream output) throws IOException {
        File thumb = PictureLibrary.get(context).thumbnail(uri);
        if (thumb == null) {
            writeResponse(output, 404, "text/plain", bytes("No thumbnail"));
            return;
        }
        byte[] data = java.nio.file.Files.readAllBytes(thumb.toPath());
        writeResponse(output, 200, "image/jpeg", data,
                Collections.singletonMap("Cache-Control", "private, max-age=86400"));
    }

    /**
     * Pictures for the local playlist, as many as the browser put in one request.
     * Each is checked by its bytes, not its name, stored in the private app store, and the page comes
     * back with what happened to every file, so a picture that was refused is named rather than
     * silently missing from the list.
     */
    private void handlePictureUpload(Map<String, String> headers, byte[] body, String query,
            OutputStream output) throws IOException {
        Map<String, String> none = java.util.Collections.emptyMap();
        String boundary = MultipartForm.boundaryOf(headers.get("content-type"));
        if (boundary == null) {
            answerPictureChange(query, none,
                    "Not uploaded: the request was not a file upload.", false, output);
            return;
        }
        PictureLibrary library = PictureLibrary.get(context);
        int stored = 0;
        StringBuilder problems = new StringBuilder();
        java.util.List<MultipartForm.Part> parts = MultipartForm.parse(body, boundary);
        if (parts.isEmpty() && body.length > 0) {
            // Nothing parsed out of a body that had bytes: the request ended before its closing
            // boundary, so the browser or the network cut it off mid-upload.
            answerPictureChange(query, none,
                    "Not uploaded: the upload did not arrive complete. Please try again.",
                    false, output);
            return;
        }
        for (MultipartForm.Part part : parts) {
            if (part.filename.isEmpty() || part.data.length == 0) {
                continue;
            }
            String problem = part.data.length > MAX_PICTURE_BYTES
                    ? "larger than " + (MAX_PICTURE_BYTES / (1024 * 1024)) + " MB"
                    : library.saveLocal(queryValue(query, "album"), part.filename, part.data);
            if (problem == null) {
                stored++;
            } else if (problem.startsWith(PictureLibrary.STORED_NOT_ADDED)) {
                stored++;
                problems.append(problems.length() > 0 ? " " : "")
                        .append(part.filename).append(": ").append(problem).append('.');
            } else {
                problems.append(problems.length() > 0 ? " " : "")
                        .append(part.filename).append(": ").append(problem).append('.');
            }
        }
        KioskService.publishTelemetrySoon(context);
        String notice = stored == 0 && problems.length() == 0
                ? "Not uploaded: no file was chosen."
                : (stored > 0 ? stored + (stored == 1 ? " picture" : " pictures") + " stored."
                        : "Not uploaded.")
                        + (problems.length() > 0 ? " " + problems : "");
        answerPictureChange(query, none, notice, stored > 0, output);
    }


    private String pictureOptions(ScreensaverPolicy.Settings saver) {
        PictureLibrary library = PictureLibrary.get(context);
        StringBuilder transitions = new StringBuilder();
        for (String[] choice : KioskCommandDispatcher.AMBIENT_TRANSITION_WORDS) {
            transitions.append(selectOption(choice[0], choice[1], saver.transition));
        }
        StringBuilder fits = new StringBuilder();
        for (String[] choice : KioskCommandDispatcher.AMBIENT_FIT_WORDS) {
            fits.append(selectOption(choice[0], choice[1], saver.pictureFit));
        }
        StringBuilder corners = new StringBuilder();
        for (String[] choice : KioskCommandDispatcher.AMBIENT_CORNER_WORDS) {
            corners.append(selectOption(choice[0], choice[1], saver.creditCorner));
        }

        String picturesProblem = library.problem();
        return "<div id=\"ambient-pictures\""
                + (ScreensaverPolicy.PICTURES.equals(saver.mode) ? "" : " class=\"gone\"") + ">"
                + "<p class=\"hint" + (picturesProblem != null ? " bad" : "") + "\" id=\"ambient-pictures-note\">"
                + escapeHtml(library.state()) + "</p>"
                + fieldWithId("ambient-picture-s", "number", null,
                        "Picture duration (seconds)", String.valueOf(saver.pictureSeconds),
                        " data-setting=\"ambient_picture_s\" min=\"1\" max=\""
                                + ScreensaverPolicy.MAX_SECONDS + "\" step=\"1\"", null)
                + selectField("ambient-transition", null, "Transition",
                        "ambient_transition", transitions.toString(), null)
                + selectField("ambient-fit", null, "Picture fit",
                        "ambient_picture_fit", fits.toString(), null)
                + "<div class=\"switches\">"
                + switchRow("ambient-shuffle", "Shuffle",
                        " data-setting=\"ambient_shuffle\"", saver.shuffle)
                + switchRow("ambient-one", "One picture per start",
                        " data-setting=\"ambient_one_per_start\"", saver.onePerCycle)
                + switchRow("ambient-credit", "Credit line",
                        " data-setting=\"ambient_credit\"", saver.credit)
                + "</div>"
                + selectField("ambient-corner", null, "Credit corner",
                        "ambient_credit_corner", corners.toString(), null)
                + "</div>";
    }


    /**
     * The album Ambient plays, picked here and stored at once; the albums themselves are made
     * and filled in the Gallery (2026-10-26). Shown only for the Pictures mode.
     */
    private String pictureLibraryBox() {
        ScreensaverPolicy.Settings saver = KioskConfig.screensaverOf(context);
        boolean shown = ScreensaverPolicy.PICTURES.equals(saver.mode);
        PlaylistDocument document = PictureLibrary.get(context).playlists().load();
        PlaylistDocument.Playlist playing = document.active();
        String current = playing == null ? "" : playing.name;
        StringBuilder albums = new StringBuilder(selectOption("", "No album", current));
        for (PlaylistDocument.Playlist album : document.all()) {
            albums.append(selectOption(album.name, album.name, current));
        }
        // The cell around the card is what leaves for another mode, so the options keep the
        // right-hand column of the grid whether or not the album is shown.
        return "<div" + (shown ? "" : " class=\"gone\"") + " id=\"ambient-library\">"
                + "<section class=\"card\"><h2>Album</h2>"
                + selectField("ambient-album", null, "Ambient plays", "ambient_album",
                        albums.toString(), document.size() == 0 ? "No albums yet." : null)
                + "<div class=\"actions\"><a class=\"btn text\" href=\"/gallery\">Edit in Gallery"
                + glyph("next") + "</a></div></section></div>";
    }


    /** The Gallery section's line, the same on both surfaces: "24 pictures · 3 albums". */
    static String gallerySummary(Context context) {
        PictureLibrary library = PictureLibrary.get(context);
        int pictures = library.uploadCount();
        int albums = library.playlists().load().size();
        return pictures + (pictures == 1 ? " picture · " : " pictures · ") + albums
                + (albums == 1 ? " album" : " albums");
    }

    /**
     * The Gallery section: the albums, each opening its page, then adding pictures and the
     * Gallery itself.
     */
    private String galleryBody() {
        PlaylistDocument document = PictureLibrary.get(context).playlists().load();
        StringBuilder rows = new StringBuilder();
        for (PlaylistDocument.Playlist album : document.all()) {
            int count = album.items.size();
            rows.append(linkRow("/album?id=" + urlEncode(album.id), "saver", album.name,
                    count + (count == 1 ? " picture" : " pictures")
                            + (album.active ? " · Ambient plays it" : ""), null, glyph("next")));
        }
        return (rows.length() == 0 ? "<p class=\"hint\">No albums yet.</p>"
                : "<ul class=\"list\">" + rows + "</ul>")
                + "<div class=\"actions\"><a class=\"btn tonal\" href=\"/gallery?tab=pictures\">"
                + glyph("plus") + "Add pictures</a><a class=\"btn text\" href=\"/gallery\">"
                + "Open Gallery" + glyph("next") + "</a></div>";
    }

    /**
     * The Gallery (2026-10-26): one place for the pictures, apart from what uses them. Two
     * tabs: Albums, the named lists Ambient and the Bitcoin clock pick from, and Pictures, the
     * folder browser with Added here (the uploads) and the panel's own folders, where pictures
     * are added, opened, selected into an album or deleted.
     */
    private String buildGalleryPage(String notice, String tab, String at, int size) {
        boolean pictures = "pictures".equals(tab);
        PictureLibrary library = PictureLibrary.get(context);
        StringBuilder html = new StringBuilder(pageStart("Gallery", null, settingsAt("gallery")));
        if (notice != null && !notice.isEmpty()) {
            html.append("<p class=\"notice\">").append(escapeHtml(notice)).append("</p>");
        }
        html.append("<div class=\"segmented\" role=\"group\" aria-label=\"Gallery\">")
                .append("<a href=\"/gallery\" role=\"button\" aria-pressed=\"").append(!pictures)
                .append("\">").append(pictures ? "" : glyph("check")).append("Albums</a>")
                .append("<a href=\"/gallery?tab=pictures\" role=\"button\" aria-pressed=\"")
                .append(pictures).append("\">").append(pictures ? glyph("check") : "")
                .append("Pictures</a></div>");
        if (!pictures) {
            html.append("<section class=\"card\"><div id=\"album-table\">")
                    .append(playlistTable(library.playlists().load())).append("</div>")
                    .append("<p class=\"banner bad gone\" id=\"album-banner\" role=\"status\">")
                    .append("<span></span><button type=\"button\" class=\"dismiss\" ")
                    .append("aria-label=\"Close\">&times;</button></p></section>");
            return html.append(playlistScript).append(pageEnd()).toString();
        }
        html.append("<div class=\"two album gallery\">")
                .append("<section class=\"card\" id=\"album-folders\">")
                .append("<div class=\"cardhead\"><h2>Folders</h2>")
                .append("<button type=\"button\" class=\"ib\" id=\"tree-toggle\" title=\"Close every "
                        + "folder\" aria-label=\"Close every folder\">").append(glyph("unfold_less"))
                .append("</button></div>")
                .append("<div id=\"folder-list\">").append(folderList(browseLocation(at), "", size))
                .append("</div>").append(uploadForm(""))
                .append("</section>")
                .append("<section class=\"card\" id=\"album-content\">")
                .append("<div id=\"folder-content\">").append(folderContent(at, "", size))
                .append("</div></section></div>");
        return html.append(pictureScript).append(pageEnd()).toString();
    }

    /**
     * One picture's page in the Gallery: the picture, its name for the credit and whether the
     * credit shows, the albums it is in (and one to add it to), what uses it, and Delete for an
     * upload, whose question names what uses it. Every form posts and comes back here.
     */
    private String buildPicturePage(String uri, String notice) {
        PictureLibrary library = PictureLibrary.get(context);
        if (!library.servable(uri)) {
            return pageStart("Picture", null, "/gallery?tab=pictures")
                    + "<p class=\"hint bad\">That picture is gone, or this panel may not read it.</p>"
                    + pageEnd();
        }
        String label = library.displayPath(uri);
        String name = label.substring(label.lastIndexOf('/') + 1);
        StringBuilder html = new StringBuilder(pageStart(name, null, "/gallery?tab=pictures"));
        if (notice != null && !notice.isEmpty()) {
            html.append("<p class=\"notice\">").append(escapeHtml(notice)).append("</p>");
        }
        PlaylistDocument document = library.playlists().load();
        java.util.List<String> usedBy = library.usersOf(uri, document);
        boolean off = library.hiddenCredits().contains(uri);
        String fallback = PictureSources.creditFromLabel(label);
        html.append("<div class=\"two picture\"><section class=\"card\">")
                .append("<img class=\"large\" src=\"/api/pictures/large?uri=").append(urlEncode(uri))
                .append("\" alt=\"\"></section><section class=\"card\"><h2>Credit</h2>")
                .append("<form method=\"post\" action=\"/api/pictures/caption\">")
                .append(hidden("name", uri)).append(hidden("back", uri))
                .append(fieldWithId("picture-caption", "text", "caption", "Name for the credit",
                        library.captions().getOrDefault(uri, ""),
                        " maxlength=\"200\" placeholder=\"" + escapeHtml(fallback.isEmpty() ? " "
                                : fallback) + "\"", null))
                .append("<div class=\"actions\"><button class=\"primary\" type=\"submit\">Save"
                        + "</button></div></form>")
                .append("<form method=\"post\" action=\"/api/pictures/credit\">")
                .append(hidden("name", uri)).append(hidden("back", uri))
                .append(hidden("shown", off ? "true" : "false"))
                .append("<div class=\"actions\"><button class=\"text\" type=\"submit\">")
                .append(glyph(off ? "eye" : "eye_off")).append(off ? "Show credits"
                        : "Hide credits").append("</button></div></form>")
                .append("<h2>In albums</h2>");
        StringBuilder chips = new StringBuilder();
        StringBuilder others = new StringBuilder();
        for (PlaylistDocument.Playlist album : document.all()) {
            if (album.items.contains(uri)) {
                chips.append("<li><a class=\"rowlink plain\" href=\"/album?id=")
                        .append(urlEncode(album.id)).append("\"><span class=\"text\"><span class=\"h\">")
                        .append(escapeHtml(album.name)).append("</span></span></a>")
                        .append("<span class=\"trail\"><form method=\"post\" action=\"/api/pictures/select\">")
                        .append(hidden("uri", uri)).append(hidden("album", album.id))
                        .append(hidden("back", uri)).append(hidden("selected", "false"))
                        .append(iconButton("minus", "Take it out of " + album.name, ""))
                        .append("</form></span></li>");
            } else {
                others.append(selectOption(album.id, album.name, ""));
            }
        }
        html.append(chips.length() == 0 ? "<p class=\"hint\">In no album.</p>"
                : "<ul class=\"list seg\">" + chips + "</ul>");
        if (others.length() > 0) {
            html.append("<form class=\"beside\" method=\"post\" action=\"/api/pictures/select\">")
                    .append(hidden("uri", uri)).append(hidden("back", uri))
                    .append(hidden("selected", "true"))
                    .append(selectField("picture-album", "album", "Album", null, others.toString(),
                            null))
                    .append("<button class=\"tonal\" type=\"submit\">Add to album</button></form>");
        }
        html.append("<h2>Used by</h2>");
        if (usedBy.isEmpty()) {
            html.append("<p class=\"hint\">Nothing.</p>");
        } else {
            html.append("<ul class=\"list\">");
            for (String user : usedBy) {
                html.append("<li><span class=\"text\"><span class=\"h\">").append(escapeHtml(user))
                        .append("</span></span></li>");
            }
            html.append("</ul>");
        }
        if (library.isUpload(uri)) {
            html.append("<form method=\"post\" action=\"/api/pictures/delete\" class=\"actions\""
                    + " data-native=\"1\">").append(hidden("uri", uri)).append(hidden("back", ""))
                    .append("<button type=\"submit\" class=\"danger\" data-confirm=\"")
                    .append(escapeHtml(deleteQuestion(name) + (usedBy.isEmpty() ? ""
                            : " It is used by " + String.join(", ", usedBy) + ".")
                            + " It leaves every album that holds it."))
                    .append("\">Delete</button></form>");
        } else {
            html.append("<p class=\"hint\">This panel's own picture: it is never deleted here.</p>");
        }
        return html.append("</section></div>").append(pageEnd()).toString();
    }

    private String buildPlaylistPage(String notice, String id, String at, int size) {
        PictureLibrary library = PictureLibrary.get(context);
        PlaylistDocument document = library.playlists().load();
        PlaylistDocument.Playlist playlist = document.byId(id);
        String said = notice == null || notice.isEmpty() ? ""
                : "<p class=\"notice\">" + escapeHtml(notice) + "</p>";
        if (playlist == null) {
            return pageStart("Album", null, "/gallery")
                    + said + "<p class=\"hint bad\">That album is gone.</p>" + pageEnd();
        }
        StringBuilder html = new StringBuilder(pageStartWith(playlistHeading(playlist),
                "/gallery", false));
        html.append(said)
                .append("<div class=\"two album\">")
                .append("<section class=\"card\" id=\"album-folders\">")
                .append("<div class=\"cardhead\"><h2>Folders</h2>")
                // Closes every branch, or opens every one; the script paints which. Scripting
                // only, like the carets: without it the tree follows the open folder instead.
                .append("<button type=\"button\" class=\"ib\" id=\"tree-toggle\" title=\"Close every "
                        + "folder\" aria-label=\"Close every folder\">").append(glyph("unfold_less"))
                .append("</button></div>")
                .append("<div id=\"folder-list\">")
                .append(folderList(browseLocation(at), playlist.id, size))
                .append("</div>")
                .append(uploadForm(playlist.id))
                .append("</section>")
                .append("<section class=\"card\" id=\"album-content\">")
                .append("<div id=\"folder-content\">")
                .append(folderContent(at, playlist.id, size))
                .append("</div>")
                .append("</section>")
                .append("<section class=\"card\" id=\"album-held\">")
                .append("<div class=\"cardhead\"><h2>In this album</h2>")
                .append(shuffleMark())
                .append("</div>")
                .append("<div id=\"album-items\">")
                .append(playlistItems(playlist, library))
                .append("</div>")
                .append("</section>")
                .append("</div>")
                // Delete, outlined red as in every editor (the delete rules, 2026-10-01), asked
                // first in the panel's words; it left the Ambient page's row on 2026-10-04.
                // The answer is the Ambient page, since this one is gone.
                .append("<form method=\"post\" action=\"/api/albums/delete\" class=\"actions\" data-native=\"1\">")
                .append(hidden("id", playlist.id))
                // A refusal other than gone, a failed save, is answered on this page.
                .append(hidden("album", playlist.id))
                .append("<button type=\"submit\" class=\"danger\" data-confirm=\"")
                .append(escapeHtml(deleteQuestion(playlist.name) + (playlist.active
                        ? " It is in use: Ambient will have no album." : "")
                        + " The pictures stay."))
                .append("\">Delete</button></form>");
        return html.append(pageEnd()).toString();
    }


    /**
     * The shuffle glyph in the held list's head while Ambient shuffles, or nothing: a
     * mark and not a button, since shuffle is set with the other Ambient settings, and a mark
     * rather than a sentence, because a sentence is the last resort (2026-09-25).
     */
    private String shuffleMark() {
        if (!KioskConfig.screensaverOf(context).shuffle) {
            return "";
        }
        String says = "Shuffle is on, so Ambient does not follow this order";
        return "<span class=\"mark\" role=\"img\" title=\"" + says + "\" aria-label=\"" + says
                + "\">" + glyph("shuffle") + "</span>";
    }

    /**
     * The page's title is the playlist's own name, with the rename pencil beside it in the app
     * bar, the way a pull request's title is edited (2026-09-19).
     *
     * <p>A {@code <details>} rather than a box the script makes: it opens, closes and posts with
     * no scripting at all, and the script only keeps the answer on this page. The Cancel button
     * is the script's, so a {@code <noscript>} rule hides it; the pencil itself closes the box
     * for a browser without one.
     */
    private String playlistHeading(PlaylistDocument.Playlist playlist) {
        return "<div class=\"titles\"><div class=\"titlerow\"><h1 id=\"album-name\">"
                + escapeHtml(playlist.name) + "</h1>"
                + "<details class=\"rename\">"
                + "<summary class=\"ib\" aria-label=\"Rename this album\" title=\"Rename\">"
                + glyph("pencil") + "</summary>"
                + "<form method=\"post\" action=\"/api/albums/rename\">"
                + hidden("id", playlist.id)
                // So the answer comes back as this page rather than as the Ambient page,
                // which is the rule every form on this page follows.
                + hidden("album", playlist.id)
                + fieldWithId("rename-name", "text", "name", "Album name", playlist.name,
                        " maxlength=\"" + PlaylistDocument.MAX_NAME_LENGTH + "\" required", null)
                + "<button class=\"primary\" type=\"submit\">Save</button>"
                + "<button type=\"button\" class=\"text cancel\">Cancel</button>"
                + "<span class=\"hint bad gone\" role=\"alert\"></span>"
                + "</form></details>"
                + "<noscript><style>details.rename .cancel{display:none}</style></noscript>"
                + "</div></div>";
    }



    /**
     * Everything the album holds, in the order Ambient shows it: the picture, the folder and the
     * name, which opens the picture's page in the Gallery, and the button that takes it out of
     * the album, deleting nothing.
     *
     * <p>The folder is prepended because this list is the one place two pictures with the same
     * name from two folders sit next to each other (2026-09-10). It is a fragment of its
     * own because a tick in the browser changes it, and the page must not reload to say so.
     *
     * <p>A picture is dragged by the grip beside it, the six dots every list app uses, or by its
     * thumbnail; {@code admin_pictures.js} does the drag and posts the whole order, and without
     * scripting the grip is not drawn at all.
     */
    private String playlistItems(PlaylistDocument.Playlist playlist, PictureLibrary library) {
        StringBuilder html = new StringBuilder();
        html.append("<p class=\"hint\">")
                .append(playlist.items.size()).append(playlist.items.size() == 1
                        ? " picture" : " pictures")
                .append(playlist.active ? ", and this is the album in use." : ".")
                .append("</p>");
        if (playlist.items.isEmpty()) {
            return html.append("<p class=\"hint\">Nothing picked yet. Open a folder and add its")
                    .append(" pictures.</p>").toString();
        }
        boolean movable = playlist.items.size() > 1;
        html.append("<ul class=\"pictures held\" id=\"held-list\">");
        for (String uri : playlist.items) {
            // The name opens the picture's page in the Gallery, where its credit is set
            // (2026-10-26: the credit belongs to the picture, not to one album).
            html.append("<li data-uri=\"").append(escapeHtml(uri)).append("\">")
                    .append("<span class=\"grab\">")
                    .append(movable ? "<span class=\"grip\">" + glyph("drag") + "</span>" : "")
                    .append(thumbnail(uri)).append("</span>")
                    .append("<a class=\"name\" href=\"/picture?uri=").append(urlEncode(uri))
                    .append("\">").append(escapeHtml(library.displayPath(uri))).append("</a>")
                    .append("<span class=\"acts\">")
                    .append("<form method=\"post\" action=\"/api/pictures/select\">")
                    .append(hidden("uri", uri)).append(hidden("album", playlist.id))
                    .append(hidden("selected", "false"))
                    .append(iconButton("minus", "Take it out of the album", ""))
                    .append("</form></span></li>");
        }
        return html.append("</ul>").toString();
    }

    /**
     * A picture's thumbnail as an image tag, fetched from this server and made by the panel once;
     * see {@code PictureLibrary.thumbnail}. A picture that cannot be read leaves a plain tile.
     */
    private static String thumbnail(String uri) {
        return "<img class=\"thumb\" src=\"/api/pictures/thumb?uri=" + urlEncode(uri)
                + "\" alt=\"\" loading=\"lazy\" onerror=\"this.style.visibility='hidden'\">";
    }


    /** The upload form and the banner that answers it, on the page where pictures are chosen. */
    private String uploadForm(String playlistId) {
        // The playlist rides in the action because an upload is multipart: with no scripting
        // there is no place else to put it that this server reads.
        return "<form id=\"picture-upload\" method=\"post\" action=\"/api/pictures?album="
                + urlEncode(playlistId) + "\" enctype=\"multipart/form-data\">"
                + "<p class=\"hint\">Add pictures (JPEG, PNG or WebP; up to "
                + (MAX_UPLOAD_BYTES / (1024 * 1024)) + " MB per upload)</p>"
                // The file input itself is off screen and the word Browse is its label, so only
                // that word opens the file dialog (2026-09-11, C9).
                + "<div class=\"uploadrow\">"
                + "<input type=\"file\" id=\"picture-file\" name=\"picture\""
                + " accept=\"image/jpeg,image/png,image/webp\" multiple class=\"offscreen\">"
                + "<label class=\"filebutton\" for=\"picture-file\">" + glyph("upload")
                + "Browse</label>"
                + "<span class=\"chosen\" id=\"picture-chosen\">No file chosen</span>"
                + "<button class=\"primary\" type=\"submit\">Upload</button>"
                + "</div></form>"
                // Directly under the button that fills it, so the answer to "did that work" is
                // where the eye already is rather than at the top of a page that scrolled away.
                + "<p class=\"banner gone\" id=\"picture-banner\" role=\"status\"><span></span>"
                + "<button type=\"button\" class=\"dismiss\" aria-label=\"Close\">&times;</button>"
                + "</p>";
    }

    /** The scripts and the closing tags every page of this server ends with. */
    private String pageEnd() {
        return commandScript + settingScript + pictureScript + statsScript + automationScript
                + themeScript + qrScript + "</main></body></html>";
    }

    /** The browse token is {@code offset|location}, or a bare location for the first page. */
    private static String browseLocation(String token) {
        int separator = token == null ? -1 : token.indexOf('|');
        return separator < 0 ? token : token.substring(separator + 1);
    }

    private static int browseOffset(String token) {
        int separator = token == null ? -1 : token.indexOf('|');
        return separator < 0 ? 0 : boundedToken(token.substring(0, separator), 100_000);
    }

    /**
     * The hint where the panel may not read its own pictures: uploads work without it, so the
     * playlists and the Uploaded folder stay (2026-10-05), and Android only asks on the panel.
     */
    private static String ownPicturesHint() {
        return "<p class=\"hint bad\">" + escapeHtml(PictureLibrary.OWN_PICTURES_NEED_PERMISSION)
                + " Allow it on the panel, in Gallery; Android only asks there.</p>";
    }

    /** Why the panels cannot show pictures, as the hint both of them then carry, or null. */
    private static String browserRefusal(PictureLibrary library, String playlistId) {
        // The album this page was opened for, not whichever happens to be playing: the page is
        // per album since 2026-09-12, which is what removed the ambiguity the old inline
        // browser had. No album is the Gallery's own browser (2026-10-26).
        if (playlistId == null || playlistId.isEmpty()) {
            return null;
        }
        if (library.playlists().load().byId(playlistId) == null) {
            return "<p class=\"hint bad\">That album is gone.</p>";
        }
        return null;
    }

    /**
     * The Folders panel: the folder tree, indented by depth, the open one marked, a caret on every
     * folder with folders under it, Added here first.
     *
     * <p>A fragment of its own since 2026-09-19, when the browser's two panes became two panels;
     * {@code admin_pictures.js} fetches it from {@code /api/pictures/folders} and the open folder
     * from {@code /api/pictures/content}, so each action replaces exactly that much of the page
     * and the reader keeps their place (2026-09-10, C3). The whole tree comes down in one
     * fragment, and which branches are open is decided in the browser: this method opens the top
     * of the volume and the way down to the open folder, which is the tree a browser with no
     * scripting gets and what the script starts from before the reader's own carets take over
     * (2026-09-23: every folder at once was a list nobody could read).
     *
     * <p>The count beside a folder is the pictures directly in it, not everything below it: a
     * folder that said 41 and opened onto three pictures read as a miscount (2026-09-19).
     */
    private String folderList(String location, String playlistId, int size) {
        PictureLibrary library = PictureLibrary.get(context);
        String refusal = browserRefusal(library, playlistId);
        if (refusal != null) {
            return refusal;
        }
        java.util.List<PictureBrowser.Folder> tree = library.browser().folders();
        boolean readable = library.browsesOwnStorage();
        java.util.Set<String> parents = new java.util.HashSet<>();
        for (PictureBrowser.Folder folder : tree) {
            if (!folder.path.isEmpty()) {
                parents.add(PictureBrowser.parentOf(folder.path));
            }
        }
        java.util.Set<String> open = new java.util.HashSet<>();
        open.add("");
        String walk = location;
        while (walk != null && !walk.isEmpty() && !PictureBrowser.UPLOADS.equals(walk)) {
            walk = PictureBrowser.parentOf(walk);
            open.add(walk);
        }
        StringBuilder html = new StringBuilder("<ul class=\"folders\">");
        html.append(folderLink(PictureBrowser.UPLOADS, "Added here",
                library.uploadCount(), 0, location, playlistId, size, false, false, true));
        for (PictureBrowser.Folder folder : tree) {
            boolean shown = true;
            for (String above = folder.path; !above.isEmpty(); ) {
                above = PictureBrowser.parentOf(above);
                if (!open.contains(above)) {
                    shown = false;
                    break;
                }
            }
            html.append(folderLink(folder.path, folder.name, folder.pictures, folder.depth,
                    location, playlistId, size, parents.contains(folder.path),
                    open.contains(folder.path), shown));
        }
        // The Uploaded folder is above, always there; the panel's own folders need the
        // permission, and the hint under the list says so.
        return html.append("</ul>").append(readable ? "" : ownPicturesHint()).toString();
    }


    /**
     * The Content card: its head with the view button, the open folder's name, the header check
     * box that selects or clears the page, then the pictures in the view in force, list, details,
     * small or big thumbnails, and the way to the next page.
     *
     * <p>The whole card is the fragment, head included, because the view button shows the view
     * that is on and changes with it. In or out of the playlist is a check box on every row and a
     * check circle on every tile, the same control the panel uses, posted the moment it changes;
     * an upload keeps its bin, which asks first. The view is one preference for the panel and
     * this page alike ({@code KioskConfig.pictureViewOf}), so both open the next folder the same
     * way (2026-09-23).
     */
    private String folderContent(String token, String playlistId, int size) {
        PictureLibrary library = PictureLibrary.get(context);
        String refusal = browserRefusal(library, playlistId);
        String view = KioskConfig.pictureViewOf(context);
        StringBuilder html = new StringBuilder(contentHead(view, token, playlistId, size));
        if (refusal != null) {
            return html.append(refusal).toString();
        }
        String location = browseLocation(token);
        if (location == null) {
            // Nothing opened yet, the same first sight the panel's own page offers.
            return html.append("<p class=\"hint\">Pick a folder to begin.</p>").toString();
        }
        // No album: the Gallery's own browser, where a picture opens its page and a tick
        // selects it for the bar under the list (Add to album, Delete).
        boolean gallery = playlistId == null || playlistId.isEmpty();
        PlaylistDocument.Playlist target = gallery ? null
                : library.playlists().load().byId(playlistId);
        if (!gallery && target == null) {
            // Deleted from another browser between the check above and this read.
            return html.append("<p class=\"hint bad\">That album is gone.</p>").toString();
        }
        int offset = browseOffset(token);
        html.append("<p class=\"where\">")
                .append(escapeHtml(PictureBrowser.UPLOADS.equals(location) ? "Added here"
                        : location.isEmpty() ? "Internal storage" : trimSlash(location)))
                .append("</p>");
        PictureBrowser.Page page = PictureBrowser.UPLOADS.equals(location)
                ? library.uploads(offset, size)
                : library.browser().pictures(location, offset, size);
        if (page.problem != null) {
            html.append("<p class=\"hint bad\">").append(escapeHtml(page.problem)).append("</p>");
        }
        if (page.entries.isEmpty() && page.problem == null) {
            html.append("<p class=\"hint\">No pictures directly in this folder.</p>");
        }
        if (!page.entries.isEmpty()) {
            int held = 0;
            for (PictureBrowser.Entry entry : page.entries) {
                if (!gallery && target.items.contains(entry.uri)) {
                    held++;
                }
            }
            html.append(gallery ? galleryAllRow(page.entries.size(), page.available)
                    : selectAllRow(held, page.entries.size(), page.available));
            boolean tiles = PictureBrowser.VIEW_SMALL.equals(view) || PictureBrowser.VIEW_BIG.equals(view);
            html.append(tiles ? "<div class=\"tiles " + view + "\">"
                    : "<ul class=\"pictures " + view + "\">");
            for (PictureBrowser.Entry entry : page.entries) {
                boolean selected = !gallery && target.items.contains(entry.uri);
                String pick = gallery ? galleryPick(entry)
                        : pickForm(entry.uri, selected, token, size, target.id);
                // Only an upload gets a bin: a file the person picked in their own storage leaves
                // the playlist but is never removed from disk here.
                String bin = library.isUpload(entry.uri)
                        ? "<form method=\"post\" action=\"/api/pictures/delete\" class=\"bin\">"
                                + hidden("uri", entry.uri) + hidden("at", token)
                                + hidden("n", String.valueOf(size))
                                + hidden("album", gallery ? "" : target.id)
                                + iconButton("trash", "Delete the file", "") + "</form>"
                        : "";
                // In the Gallery a picture's name and thumbnail open its page.
                String name = gallery ? "<a class=\"name\" href=\"/picture?uri="
                        + urlEncode(entry.uri) + "\">" + escapeHtml(entry.name) + "</a>"
                        : "<span class=\"name\">" + escapeHtml(entry.name) + "</span>";
                String thumb = gallery ? "<a href=\"/picture?uri=" + urlEncode(entry.uri)
                        + "\" tabindex=\"-1\">" + thumbnail(entry.uri) + "</a>"
                        : thumbnail(entry.uri);
                if (tiles) {
                    html.append("<div class=\"tile").append(selected ? " in" : "").append("\">")
                            .append("<span class=\"pic\">").append(thumb)
                            .append(pick).append(bin).append("</span>")
                            .append(name);
                    if (PictureBrowser.VIEW_BIG.equals(view) && !entry.details().isEmpty()) {
                        html.append("<small>").append(escapeHtml(entry.details())).append("</small>");
                    }
                    html.append("</div>");
                } else {
                    html.append("<li>").append(pick);
                    if (PictureBrowser.VIEW_DETAILS.equals(view)) {
                        html.append(thumb)
                                .append("<span class=\"text\"><span class=\"h\">")
                                .append(gallery ? name : escapeHtml(entry.name))
                                .append("</span><span class=\"s\">")
                                .append(escapeHtml(entry.details())).append("</span></span>");
                    } else {
                        html.append(name);
                    }
                    if (!bin.isEmpty()) {
                        html.append("<span class=\"acts\">").append(bin).append("</span>");
                    }
                    html.append("</li>");
                }
            }
            html.append(tiles ? "</div>" : "</ul>");
            if (gallery) {
                html.append(gallerySelectionBar(library));
            }
        }
        if (page.available > page.entries.size()) {
            // Previous and Next as chevrons around the count: a pager everybody has seen.
            html.append("<div class=\"pager\">")
                    .append(pageLink(Math.max(0, offset - size) + "|" + location, "prev",
                            "Previous page", playlistId, size, offset > 0))
                    .append("<span>").append(offset + 1).append(" to ")
                    .append(offset + page.entries.size()).append(" of ").append(page.available)
                    .append("</span>")
                    .append(pageLink((offset + size) + "|" + location, "next", "Next page",
                            playlistId, size, page.more))
                    .append("</div>");
        }
        // Show 10, 25, 50 or 100 as chips, the one in force filled, only where there is more than
        // the smallest page to show. A size link starts the folder over: page three of ten is
        // nowhere in particular at fifty.
        if (page.available > PictureBrowser.PAGE_SIZES[0]) {
            html.append("<div class=\"sizes\">");
            for (int choice : PictureBrowser.PAGE_SIZES) {
                html.append("<a class=\"size").append(choice == size ? " on" : "")
                        .append("\" href=\"").append(browseHref(playlistId))
                        .append("&amp;at=").append(urlEncode(location))
                        .append("&amp;n=").append(choice)
                        .append("\" data-n=\"").append(choice).append("\">").append(choice)
                        .append("</a>");
            }
            html.append("</div>");
        }
        return html.toString();
    }

    /**
     * The Content card's head: its name and the view button, which shows the view in force and
     * moves to the next one, list, details, small thumbnails, big thumbnails, then list again.
     */
    private static String contentHead(String view, String token, String playlistId, int size) {
        String next = PictureBrowser.nextView(view);
        return "<div class=\"cardhead\"><h2>Content</h2>"
                + "<form method=\"post\" action=\"/api/pictures/view\" class=\"view\">"
                + hidden("view", next) + hidden("album", playlistId) + hidden("at", token)
                + hidden("n", String.valueOf(size))
                + iconButton(view, PictureBrowser.viewLabel(view) + ". Switch to "
                        + PictureBrowser.viewLabel(next).toLowerCase(Locale.ROOT), "selected")
                + "</form></div>";
    }

    /**
     * The header check box, in the same column as the rows' boxes: ticked when every picture on
     * the page is in the playlist, a dash while only some are, empty otherwise. The script ticks
     * or clears the page from it; without scripting it is a readout.
     */
    private static String selectAllRow(int held, int shown, int available) {
        String state = held == 0 ? "" : held == shown ? " checked" : " data-some=\"1\"";
        return "<ul class=\"pictures selecthead\"><li>"
                + "<span class=\"lead\"><input type=\"checkbox\" id=\"select-all\"" + state
                + " aria-label=\"Select all on this page\"></span>"
                + "<span class=\"text\"><span class=\"s\">Select all"
                + (available > shown ? " on this page" : "") + " · " + held + " of "
                + available + " in the album</span></span></li></ul>";
    }

    /**
     * The Gallery's selection bar: the selected pictures, one address a line, added to an album
     * or deleted. Deleting takes uploads only; the panel's own pictures are counted as kept.
     */
    private void answerGallerySelection(boolean add, Map<String, String> form, String query,
            OutputStream output) throws IOException {
        PictureLibrary library = PictureLibrary.get(context);
        int done = 0;
        int kept = 0;
        String problem = null;
        for (String uri : PlaylistDocument.parseOrder(form.get("uris"))) {
            String refusal = add ? library.selectPicture(form.getOrDefault("album", ""), uri, true)
                    : library.isUpload(uri) ? library.deleteLocal(uri) : "own";
            if (refusal == null) {
                done++;
            } else if ("own".equals(refusal)) {
                kept++;
            } else if (problem == null) {
                problem = refusal;
            }
        }
        KioskService.publishTelemetrySoon(context);
        String said = (add ? done + (done == 1 ? " picture added." : " pictures added.")
                : done + (done == 1 ? " picture deleted." : " pictures deleted."))
                + (kept > 0 ? " " + kept + " of this panel's own " + (kept == 1 ? "was" : "were")
                        + " kept: they are never deleted here." : "")
                + (problem == null ? "" : " Not done for the rest: " + problem);
        answerPictureChange(query, form, said, problem == null, output);
    }

    /** The Gallery's header check box: selects or clears the page for the selection bar. */
    private static String galleryAllRow(int shown, int available) {
        return "<ul class=\"pictures selecthead\"><li>"
                + "<span class=\"lead\"><input type=\"checkbox\" id=\"gallery-all\""
                + " aria-label=\"Select all on this page\"></span>"
                + "<span class=\"text\"><span class=\"s\">Select all"
                + (available > shown ? " on this page" : "") + "</span></span></li></ul>";
    }

    /** A picture's selection box in the Gallery: no form, the bar under the list acts on it. */
    private static String galleryPick(PictureBrowser.Entry entry) {
        return "<span class=\"lead\"><input type=\"checkbox\" class=\"gsel\" data-uri=\""
                + escapeHtml(entry.uri) + "\" aria-label=\"Select " + escapeHtml(entry.name)
                + "\"></span>";
    }

    /**
     * The bar the Gallery shows while pictures are selected: how many, Add to album with the
     * album to add them to, and Delete, which removes uploads and leaves the panel's own files
     * alone. admin_pictures.js shows it and posts it; it needs scripting, as selecting does.
     */
    private String gallerySelectionBar(PictureLibrary library) {
        StringBuilder albums = new StringBuilder();
        for (PlaylistDocument.Playlist album : library.playlists().load().all()) {
            albums.append(selectOption(album.id, album.name, ""));
        }
        return "<div class=\"selbar\" id=\"gallery-bar\" hidden><span id=\"gallery-count\"></span>"
                + (albums.length() == 0 ? "<span class=\"hint\">Make an album to add them to.</span>"
                        : "<select id=\"gallery-album\" aria-label=\"Album\">" + albums
                                + "</select><button type=\"button\" class=\"tonal\""
                                + " id=\"gallery-add\">Add to album</button>")
                + "<button type=\"button\" class=\"danger\" id=\"gallery-delete\">Delete</button>"
                + "</div>";
    }

    /** The check box that puts a picture in the album or takes it out, posted on change. */
    private static String pickForm(String uri, boolean selected, String token, int size,
            String playlistId) {
        return "<form method=\"post\" action=\"/api/pictures/select\" class=\"pick\">"
                + hidden("uri", uri) + hidden("at", token) + hidden("n", String.valueOf(size))
                + hidden("album", playlistId) + hidden("selected", String.valueOf(!selected))
                + "<span class=\"lead\"><input type=\"checkbox\"" + (selected ? " checked" : "")
                + " aria-label=\"In the album\"></span>"
                + "<noscript><button class=\"text\" type=\"submit\">"
                + (selected ? "Remove" : "Add") + "</button></noscript></form>";
    }


    /**
     * One folder as a row: the caret if it has folders under it, then the link that opens it,
     * with its glyph, its name and its count. {@code branchOpen} and {@code shown} are the
     * server's opening position; the script keeps its own from there. The caret is a span and
     * not a button, since without scripting it does nothing and must not look as if it did.
     *
     * <p>A button is a thing that does something; a folder is a place you go, and a list of forty
     * buttons reads as forty decisions (2026-09-10, C7). It is a real {@code href} so it
     * works with no scripting and can be opened in a second tab; the script intercepts it and
     * swaps the fragment instead of reloading.
     */
    private static String folderLink(String path, String label, int count, int depth,
            String openPath, String playlistId, int size, boolean hasChildren,
            boolean branchOpen, boolean shown) {
        boolean open = path.equals(openPath);
        String glyphName = PictureBrowser.UPLOADS.equals(path) ? "upload"
                : path.isEmpty() ? "storage" : "folder";
        return "<li class=\"row" + (shown ? "" : " hidden") + (branchOpen ? " open" : "")
                + "\" style=\"padding-left:" + (Math.min(depth, 6) * 20) + "px\""
                + " data-at=\"" + escapeHtml(path) + "\""
                + (hasChildren ? " data-kids=\"1\"" : "")
                + (PictureBrowser.UPLOADS.equals(path) ? " data-top=\"1\"" : "") + ">"
                + (hasChildren ? "<span class=\"caret\" role=\"button\" tabindex=\"0\" aria-label=\""
                        + (branchOpen ? "Close " : "Open ") + escapeHtml(label) + "\">"
                        + glyph("down") + "</span>" : "<span class=\"caret none\"></span>")
                + "<a class=\"folder" + (open ? " open" : "") + "\" href=\""
                + browseHref(playlistId) + "&amp;at=" + urlEncode(path) + "&amp;n=" + size
                + "\" data-at=\"" + escapeHtml(path) + "\">"
                + "<span class=\"lead\">" + glyph(glyphName) + "</span>"
                + "<span class=\"name\">" + escapeHtml(label) + "</span>"
                + "<span class=\"badge" + (count == 0 ? " zero" : "") + "\">" + count
                + "</span></a></li>";
    }


    /** Previous or Next as a chevron, carrying an offset and the page size as well as a folder. */
    private static String pageLink(String token, String glyphName, String label, String playlistId,
            int size, boolean enabled) {
        return "<a class=\"ib pager" + (enabled ? "" : " dis") + "\" href=\""
                + browseHref(playlistId) + "&amp;at=" + urlEncode(token) + "&amp;n=" + size
                + "\" data-at=\"" + escapeHtml(token) + "\" title=\"" + label + "\" aria-label=\""
                + label + "\"" + (enabled ? "" : " aria-disabled=\"true\"") + ">" + glyph(glyphName)
                + "</a>";
    }

    /**
     * Where the browser lives: an album's page, or the Gallery's Pictures when it edits no album;
     * the folder and the page size follow as more parameters.
     */
    private static String browseHref(String playlistId) {
        return playlistId == null || playlistId.isEmpty() ? "/gallery?tab=pictures"
                : "/album?id=" + urlEncode(playlistId);
    }

    /** "Pictures/holidays/" reads as "Pictures/holidays" in a heading, as it does on the panel. */
    private static String trimSlash(String path) {
        return path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    }

    private static String urlEncode(String value) {
        try {
            return java.net.URLEncoder.encode(value, "UTF-8");
        } catch (java.io.UnsupportedEncodingException impossible) {
            // UTF-8 is required of every JVM; this cannot happen and must not fail a page.
            return "";
        }
    }

    /**
     * Use, rename, delete and create from the table above, and the order from a playlist's page.
     *
     * <p>One entry point for all of them rather than a branch each in the router, because they are
     * the same operation to this class: load the document, change it, write it back, answer with
     * the page the person is looking at. The rules and every refusal come from
     * {@link PlaylistDocument}, so the panel and this page cannot disagree about them.
     */
    private void handlePlaylistChange(String path, String query, Map<String, String> form,
            OutputStream output) throws IOException {
        PictureLibrary library = PictureLibrary.get(context);
        String id = form.getOrDefault("id", "");
        long now = System.currentTimeMillis();
        java.util.function.Function<PlaylistDocument, String> change;
        String done;
        switch (path) {
            case "/api/albums/activate":
                change = document -> document.activate(id);
                // Nothing to say: the "In use" mark moving to the row is the whole answer, and a
                // sentence on top of it was noise (2026-09-19).
                done = null;
                break;
            case "/api/albums/rename":
                change = document -> document.rename(id, form.getOrDefault("name", ""), now);
                done = "Album renamed.";
                break;
            case "/api/albums/delete":
                change = document -> document.delete(id);
                done = "Album deleted. The pictures themselves are not deleted.";
                break;
            case "/api/albums":
                change = document -> document.create(library.playlists().newId(),
                        form.getOrDefault("name", ""), now);
                done = "Album created. Open it to pick its pictures.";
                break;
            case "/api/albums/order": {
                // The whole order, one address per line, from a drag on the playlist page. A list
                // that is not exactly the playlist's pictures is refused whole (see reorder), so a
                // page opened before somebody else's change cannot scramble it. No order at all
                // is its own refusal: "the playlist changed" would send a person looking for a
                // change that never happened.
                java.util.List<String> order = PlaylistDocument.parseOrder(form.get("order"));
                change = document -> order.isEmpty() ? "no order was sent"
                        : document.reorder(id, order, now);
                // Nothing to say: the list already stands in the order that was dropped.
                done = null;
                break;
            }
            default:
                writeResponse(output, 404, "text/plain; charset=utf-8", bytes("Unknown\n"));
                return;
        }
        // Under the library's lock, with the other two surfaces' edits: a load-modify-store of
        // its own here lost whichever edit the smart home or the panel made meanwhile.
        String refusal = library.editPlaylists(change);
        KioskService.publishTelemetrySoon(context);
        if (path.equals("/api/albums/delete")
                && !"1".equals(queryValue(query, "fragment"))
                && (refusal == null || PlaylistDocument.GONE.equals(refusal))) {
            // The playlist page's own Delete, posted by the browser: sent on to the Ambient
            // page, as the automation editor's Delete is sent to Automations, so a reload does
            // not post the delete again and Back does not return to a page that is gone
            // (review, 2026-10-04). A playlist already gone is sent on too, gone it is either
            // way; any other refusal, a failed save, is answered here with its reason.
            writeResponse(output, 303, "text/plain", bytes("deleted"),
                    Collections.singletonMap("Location", "/gallery"));
            return;
        }
        answerPictureChange(query, form,
                refusal == null ? done : refused("Not changed", refusal), refusal == null, output);
    }

    /**
     * The playlists as JSON, so a caller can read the table without scraping the page.
     *
     * <p>Built through {@link TinyJson#write}, which is the same writer the stored document uses,
     * so the escaping is settled in one place rather than by hand here.
     */
    private String playlistsJson() {
        PlaylistDocument document = PictureLibrary.get(context).playlists().load();
        PlaylistDocument.Playlist active = document.active();
        java.util.List<Object> rows = new java.util.ArrayList<>();
        for (PlaylistDocument.Playlist playlist : document.all()) {
            java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("id", playlist.id);
            row.put("name", playlist.name);
            row.put("count", playlist.items.size());
            row.put("updatedAt", playlist.updatedAt);
            row.put("active", playlist.active);
            rows.add(row);
        }
        java.util.Map<String, Object> answer = new java.util.LinkedHashMap<>();
        answer.put("active", active == null ? null : active.id);
        answer.put("albums", rows);
        return TinyJson.write(answer);
    }


    /**
     * The playlists as a Material list: the Ambient glyph and the name over its count open
     * the playlist's own page, and the Use radio at the end says which one plays. Delete is on
     * the playlist's page.
     *
     * <p>Use is not a button any more (2026-09-23): the radio is the state and the control at
     * once, posted the moment it is picked. Without scripting the radio still marks the playlist
     * in use and a Use button appears beside it, so the page works either way.
     */
    private String playlistTable(PlaylistDocument document) {
        StringBuilder html = new StringBuilder();
        if (document.size() == 0) {
            html.append("<p class=\"hint\">No albums yet.</p>");
        } else {
            html.append("<ul class=\"list seg\">");
            for (PlaylistDocument.Playlist playlist : document.all()) {
                int count = playlist.items.size();
                // The album's cover, its name over its count, and which one Ambient plays; a
                // tap opens the album's own page. Which album Ambient plays is chosen in
                // Ambient (2026-10-26: Gallery keeps the pictures, the others pick from it).
                String cover = playlist.items.isEmpty() ? "<span class=\"lead\">"
                        + glyph("saver") + "</span>"
                        : "<span class=\"lead cover\">" + thumbnail(playlist.items.get(0))
                                + "</span>";
                html.append("<li class=\"two\">")
                        .append("<a class=\"rowlink plain\" href=\"/album?id=").append(urlEncode(playlist.id))
                        .append("\">").append(cover)
                        .append("<span class=\"text\"><span class=\"h\">").append(escapeHtml(playlist.name))
                        .append("</span><span class=\"s\">").append(count)
                        .append(count == 1 ? " picture" : " pictures")
                        .append(playlist.active ? " · <span class=\"inuse\">Ambient plays it</span>" : "")
                        .append("</span></span></a>")
                        .append("<span class=\"trail\">").append(glyph("next")).append("</span></li>");
            }
            html.append("</ul>");
        }
        // Naming an album before picking its pictures is the panel's own order.
        html.append("<form class=\"beside newalbum\" method=\"post\" action=\"/api/albums\">")
                .append(fieldWithId("new-album-name", "text", "name", "New album name", "",
                        " maxlength=\"" + PlaylistDocument.MAX_NAME_LENGTH + "\"", null))
                .append("<button class=\"primary\" type=\"submit\">Create</button></form>");
        return html.toString();
    }

    /**
     * Answers a change made from the picture browser.
     *
     * <p>Two callers and one method. The page's own script asks for a fragment and gets a sentence
     * and a flag, which it shows in the banner and then re-reads the browser with, so nothing
     * navigates and the reader keeps their place. A browser with no scripting posts the form for
     * real and gets the whole Ambient page back, which is all a form submit can do.
     */
    private void answerPictureChange(String query, Map<String, String> form, String message,
            boolean ok, OutputStream output) throws IOException {
        if ("1".equals(queryValue(query, "fragment"))) {
            java.util.Map<String, Object> answer = new java.util.LinkedHashMap<>();
            answer.put("ok", ok);
            answer.put("message", message == null ? "" : message);
            writeResponse(output, ok ? 200 : 400, "application/json; charset=utf-8",
                    bytes(TinyJson.write(answer)));
            return;
        }
        if (form.containsKey("back")) {
            // A picture's own page in the Gallery: its forms come back to it, by its own
            // address, so a reload or Back never posts again; its Delete goes to the Gallery.
            String said = message == null ? "" : "&said=" + urlEncode(message);
            String back = form.get("back");
            writeResponse(output, 303, "text/plain", bytes("done"),
                    Collections.singletonMap("Location", back.isEmpty()
                            ? "/gallery?tab=pictures" + said
                            : "/picture?uri=" + urlEncode(back) + said));
            return;
        }
        String playlist = form.containsKey("album")
                ? form.get("album") : queryValue(query, "album");
        writeResponse(output, ok ? 200 : 400, "text/html; charset=utf-8",
                playlist == null || playlist.isEmpty()
                        ? bytes(buildGalleryPage(message, "pictures", form.get("at"),
                                PictureBrowser.pageSize(form.get("n"))))
                        : bytes(buildPlaylistPage(message, playlist, form.get("at"),
                                PictureBrowser.pageSize(form.get("n")))));
    }

    /** One decoded parameter from a query string, or "" when it is not there. */
    private static String queryValue(String query, String name) {
        return parseQuery(query).getOrDefault(name, "");
    }

    /**
     * Where the folder browser is looking, or null when no folder has been opened yet.
     *
     * <p>Null and "" are different answers and the difference is the whole of what the right pane
     * says: "" is the top of the volume, which is a folder like any other, and null is "you have
     * not picked one". They were the same value once on the panel too, where the top row read as
     * open while the pane beside it asked for a folder (2026-09-10).
     */
    private static String browseTarget(String query) {
        Map<String, String> parameters = parseQuery(query);
        return parameters.containsKey("at") ? parameters.get("at") : null;
    }

    /** One non-negative number from a browse token, clamped, never a reason to fail a page. */
    private static int boundedToken(String value, int ceiling) {
        try {
            return Math.max(0, Math.min(ceiling, Integer.parseInt(value.trim())));
        } catch (NumberFormatException notANumber) {
            return 0;
        }
    }

    /**
     * "Not changed: that playlist is gone." A refusal arrives in either house style, a fragment
     * ("that playlist is gone") or a sentence ("Not a supported picture."), and the second used
     * to come out with two full stops.
     */
    private static String refused(String prefix, String refusal) {
        String said = refusal.endsWith(".") ? refusal.substring(0, refusal.length() - 1) : refusal;
        return prefix + ": " + said + ".";
    }

    private static String hidden(String name, String value) {
        return value == null ? ""
                : "<input type=\"hidden\" name=\"" + name + "\" value=\"" + escapeHtml(value)
                        + "\">";
    }



    /**
     * The mode as a menu, for the settings page's own Ambient card, the one place it is
     * chosen: Ambient page stopped repeating it on 2026-09-28.
     */
    private String screensaverModeOptions(ScreensaverPolicy.Settings saver) {
        StringBuilder options = new StringBuilder();
        for (String[] choice : KioskCommandDispatcher.AMBIENT_MODE_WORDS) {
            options.append(radioOf("ambient_mode", "ambient_mode", choice[0], saver.mode,
                    escapeHtml(choice[1])));
        }
        return options.toString();
    }

    /**
     * The Speakers card (sketch agreed 2026-10-22): the panels as speakers, as multi-room audio
     * lists its rooms, for announcements. Where it goes (all panels, a group, chosen panels), a
     * sentence to say, a sound or stream to play, Stop all, the volume of all, and one row a
     * panel with its state, its volume and its network speaker's switch. The rows' states come
     * from each panel through /api/fleet/speaker (admin_speakers.js), never with the page, since
     * each is a call to that panel. Only a panel with its network speaker on takes sound.
     */
    private static String speakersCard(java.util.Map<String, Object> list,
            java.util.Map<String, String> groups) {
        StringBuilder rows = new StringBuilder();
        for (java.util.Map<String, Object> member : FleetDocument.members(list)) {
            String id = TinyJson.string(member, FleetDocument.ID, "");
            String name = TinyJson.string(member, FleetDocument.NAME, id);
            java.util.List<String> in = FleetDocument.memberGroups(list, member);
            rows.append("<li class=\"two speaker\" data-id=\"").append(escapeHtml(id))
                    .append("\" data-groups=\"|").append(escapeHtml(String.join("|", in)))
                    .append("|\"><label class=\"tick\" hidden><input type=\"checkbox\""
                            + " data-tick aria-label=\"").append(escapeHtml(name))
                    .append("\"></label><span class=\"lead\">").append(glyph("audio"))
                    .append("</span><span class=\"text\"><span class=\"h\">")
                    .append(escapeHtml(name)).append("</span><span class=\"s\" data-state>"
                            + "Asking the panel...</span><label class=\"slider small\">"
                            + "<span class=\"sr\">Volume of ").append(escapeHtml(name))
                    .append("</span><input type=\"range\" min=\"0\" max=\"100\" value=\"0\""
                            + " data-volume disabled></label></span>"
                            + "<span class=\"trail\"><input type=\"checkbox\" class=\"sw\""
                            + " data-switch disabled aria-label=\"Network speaker of ")
                    .append(escapeHtml(name)).append("\"></span></li>");
        }
        StringBuilder groupOptions = new StringBuilder();
        for (java.util.Map.Entry<String, String> group : groups.entrySet()) {
            groupOptions.append("<option value=\"").append(escapeHtml(group.getKey()))
                    .append("\">").append(escapeHtml(group.getValue())).append("</option>");
        }
        return "<div class=\"card\" id=\"speakers\"><div class=\"cardhead\"><h2>Speakers</h2>"
                + "<span class=\"count\" id=\"speakers-count\"></span></div>"
                + "<div class=\"segmented\" role=\"group\" aria-label=\"Send to\">"
                + "<button type=\"button\" data-target=\"all\" aria-pressed=\"true\">All panels"
                + "</button>"
                + (groups.isEmpty() ? "" : "<button type=\"button\" data-target=\"group\""
                        + " aria-pressed=\"false\">A group</button>")
                + "<button type=\"button\" data-target=\"panels\" aria-pressed=\"false\">Choose"
                + "</button></div>"
                + (groups.isEmpty() ? "" : "<div id=\"speakers-group\" hidden>"
                        + selectField("speakers-group-menu", null, "Group", null,
                                groupOptions.toString(), null) + "</div>")
                + "<div class=\"row-field\">" + fieldWithId("speakers-sentence", "text", null,
                        "Sentence", "", "", null).replace(MACHINE_TEXT, "")
                + "<button type=\"button\" class=\"primary\" data-action=\"say\">Say</button>"
                + "</div><div class=\"row-field\">" + fieldWithId("speakers-sound", "text", null,
                        "Sound or stream address", "", " inputmode=\"url\"", null)
                + "<button type=\"button\" class=\"tonal\" data-action=\"play\">Play</button>"
                + "<button type=\"button\" class=\"text\" data-action=\"stop\">Stop all"
                + "</button></div>"
                + "<label class=\"slider\">Volume of all<span class=\"readout\"><output"
                + " id=\"speakers-volume-value\">50%</output></span><input type=\"range\""
                + " id=\"speakers-volume\" min=\"0\" max=\"100\" value=\"50\"></label>"
                + "<p class=\"hint\" id=\"speakers-result\" hidden></p>"
                + "<ul class=\"list seg\" id=\"speakers-list\">" + rows + "</ul>"
                + "<p class=\"hint\">The switch is each panel's network speaker, switched here for"
                + " that panel. Only a panel with it on takes Say, Play and the volume; Stop all"
                + " reaches every panel. A panel that is not reached does not play it later.</p>"
                + "</div>";
    }

    /**
     * Where on the Fleet page a request lands (the redesign of 2026-10-25): the tab, the panel or
     * the shared section open, the scope, the health filter and the group menu. Every state has
     * an address of its own, so Back and a reload come back to it.
     */
    private static final class FleetView {
        String tab = "panels";
        String panel = "";
        String scope = FleetDocument.EVERYONE;
        String section = "";
        String show = "all";
        String group = "";
        /** A row's own page below 840 px, the detail alone with the arrow back to the list. */
        boolean detail;

        static FleetView of(Map<String, String> query) {
            FleetView view = new FleetView();
            String tab = query.getOrDefault("tab", "");
            view.tab = tab.equals("groups") || tab.equals("shared") ? tab : "panels";
            view.panel = query.getOrDefault("panel", "");
            view.scope = query.getOrDefault("scope", FleetDocument.EVERYONE);
            view.section = query.getOrDefault("section", "");
            String show = query.getOrDefault("show", "all");
            view.show = show.equals("healthy") || show.equals("unhealthy") ? show : "all";
            view.group = query.getOrDefault("group", "");
            view.detail = "1".equals(query.get("detail"));
            return view;
        }

        /** The address of this state, where a form sends the person back after it is saved. */
        String address() {
            StringBuilder at = new StringBuilder("/fleet");
            char join = '?';
            if (!tab.equals("panels")) {
                at.append(join).append("tab=").append(tab);
                join = '&';
            }
            if (tab.equals("panels") && !panel.isEmpty()) {
                at.append(join).append("panel=").append(urlEncode(panel));
                join = '&';
            }
            if (tab.equals("shared")) {
                at.append(join).append("scope=").append(urlEncode(scope));
                join = '&';
                if (!section.isEmpty()) {
                    at.append("&section=").append(urlEncode(section));
                }
            }
            if (detail) {
                at.append(join).append("detail=1");
            }
            return at.toString();
        }
    }

    /**
     * The Fleet page (redesigned 2026-10-25 from the agreed sketch): the fleet's name and how
     * many panels are online, Add a panel and Identify all at the top, then three
     * tabs. Panels lists every panel, the not healthy first with their reason, and opens one at
     * the right with its picture, health, groups, network speaker, Identify and Remove. Groups
     * makes and removes groups. Shared settings shows what the whole fleet or a group shares,
     * with Alerts and History. The counts, the list, a panel's status and health, the groups and
     * the shared rows are drawn again by the stats poll when FleetHealth.listKey moves.
     */
    private String buildFleetPage(String notice, FleetView view) {
        java.util.Map<String, Object> list = KioskConfig.fleetDocumentOf(context);
        String noticeHtml = notice == null || notice.isEmpty() ? ""
                : "<p class=\"notice\">" + escapeHtml(notice) + "</p>";
        if (list == null) {
            return pageStart("Fleet", null, settingsAt("fleet")) + noticeHtml
                    + "<div class=\"card\"><p>This panel is not in a fleet, or its list of panels"
                    + " has not arrived yet.</p></div>" + pageEnd();
        }
        if (!FleetServer.proStatus(context).pro) {
            // The Fleet page is Muralis Pro; the panels stay members meanwhile.
            return pageStart("Fleet", KioskConfig.fleetNameOf(context), settingsAt("fleet"))
                    + noticeHtml + "<div class=\"card\"><p>The Fleet page is Muralis Pro. "
                    + escapeHtml(FleetServer.proLine(context)) + "</p></div>" + pageEnd();
        }
        java.util.List<FleetHealth.Member> health = FleetServer.healthMembers(context);
        int online = 0;
        for (FleetHealth.Member member : health) {
            online += member.online ? 1 : 0;
        }
        String key = FleetServer.fleetListKey(context);
        StringBuilder html = new StringBuilder(pageStart("Fleet", KioskConfig.fleetNameOf(context),
                view.detail ? FleetView.of(java.util.Collections.singletonMap("tab", view.tab))
                        .address() : settingsAt("fleet")));
        html.append(noticeHtml);
        if (FleetServer.proStatus(context).warn) {
            html.append("<p class=\"notice\">").append(escapeHtml(FleetServer.proLine(context)))
                    .append("</p>");
        }
        // The head: how many are online, and the two actions every tab offers. Announce acts on
        // panels, so it is in the selection bar with them.
        html.append("<div class=\"fleethead\"><p id=\"fleet-counts\" class=\"counts\"")
                .append(fleetLists(key)).append(">").append(fleetCounts(health.size(), online))
                .append("</p><div class=\"fleetactions\">");
        html.append("<a class=\"btn primary\" href=\"#add-panel\" data-dialog=\"add-panel\">")
                .append(glyph("plus")).append("Add a panel</a>")
                .append("<button type=\"button\" class=\"ib\" data-identify=\"all\""
                        + " aria-label=\"Identify all\" title=\"Identify all\">")
                .append(glyph("panel")).append("</button></div></div>")
                .append("<p class=\"hint\" id=\"fleet-identify-all\" hidden></p>");
        html.append("<nav class=\"tabs\" aria-label=\"Fleet\">")
                .append(fleetTab("/fleet", "Panels", view.tab.equals("panels")))
                .append(fleetTab("/fleet?tab=groups", "Groups", view.tab.equals("groups")))
                .append(fleetTab("/fleet?tab=shared", "Shared settings", view.tab.equals("shared")))
                .append("</nav>");
        switch (view.tab) {
            case "groups":
                html.append(fleetGroupsTab(list, view, key));
                break;
            case "shared":
                html.append(fleetSharedTab(list, view, key));
                break;
            default:
                html.append(fleetPanelsTab(list, health, view, key));
        }
        html.append(fleetDialogs(list));
        html.append(fleetScript).append(speakersScript);
        return html.append(pageEnd()).toString();
    }

    /**
     * What a fresh Fleet page says after a save that sent the browser to it: "Saved." or
     * "<panel> joined the fleet." The panel's name is read back from the fleet's list, never
     * shown as the address gives it.
     */
    private String fleetNotice(Map<String, String> query) {
        if ("1".equals(query.get("saved"))) {
            return "Saved.";
        }
        String added = query.get("added");
        if (added != null) {
            for (java.util.Map<String, Object> member
                    : FleetDocument.members(KioskConfig.fleetDocumentOf(context))) {
                if (added.equals(TinyJson.string(member, FleetDocument.NAME, ""))) {
                    return added + " joined the fleet " + KioskConfig.fleetNameOf(context) + ".";
                }
            }
        }
        return null;
    }

    /** The key a Fleet page region was drawn from, for the stats poll to swap it when it moves. */
    private static String fleetLists(String key) {
        return " data-lists-kind=\"fleet\" data-lists=\"" + escapeHtml(key) + "\"";
    }

    /** "6 panels, 5 online". */
    private static String fleetCounts(int panels, int online) {
        return (panels == 1 ? "1 panel" : panels + " panels") + ", " + online + " online";
    }

    private static String fleetTab(String href, String label, boolean on) {
        return "<a href=\"" + href + "\"" + (on ? " aria-current=\"page\"" : "") + ">"
                + escapeHtml(label) + "</a>";
    }

    /** A not healthy panel's reason as its row says it, or how it was last heard from. */
    private static String fleetRowLine(FleetHealth.Member member, java.util.List<String> reasons) {
        if (reasons.isEmpty()) {
            return member.self ? "This panel" : "Online";
        }
        return fleetReasonWords(member, reasons.get(0));
    }

    private static String fleetReasonWords(FleetHealth.Member member, String reason) {
        switch (reason) {
            case FleetHealth.OFFLINE:
                return member.seenAgoMs < 0 ? "Not heard from yet" : "Offline since "
                        + FleetServer.readableTime(java.time.Instant.now()
                                .minusMillis(member.seenAgoMs).toString());
            case FleetHealth.PAGE_ERROR:
                return "Page error";
            case FleetHealth.UPDATE:
                return "Update available";
            default:
                return "Not following the fleet";
        }
    }

    /**
     * The Panels tab: the health chips, search and group menu, the selection's actions, and the
     * panels, the not healthy first; at the right the chosen panel (list-detail from 840 px).
     */
    private String fleetPanelsTab(java.util.Map<String, Object> list,
            java.util.List<FleetHealth.Member> health, FleetView view, String key) {
        java.util.Map<String, String> groups = FleetDocument.groups(list);
        boolean shares = FleetServer.fleetShares(list);
        String newest = FleetHealth.newest(health);
        java.util.List<FleetHealth.Member> sorted = FleetHealth.sorted(health, newest, shares);
        java.util.Map<String, java.util.Map<String, Object>> entries = new java.util.HashMap<>();
        for (java.util.Map<String, Object> entry : FleetDocument.members(list)) {
            entries.put(TinyJson.string(entry, FleetDocument.ID, ""), entry);
        }
        // Opened on this panel unless one is asked for: each panel opened is asked for its
        // picture, and the first in the list is often one that does not answer.
        String self = "";
        for (FleetHealth.Member member : sorted) {
            self = member.self ? member.id : self;
        }
        String chosen = entries.containsKey(view.panel) ? view.panel
                : !self.isEmpty() ? self : sorted.isEmpty() ? "" : sorted.get(0).id;
        int unhealthy = 0;
        for (FleetHealth.Member member : sorted) {
            unhealthy += FleetHealth.reasons(member, newest, shares).isEmpty() ? 0 : 1;
        }
        StringBuilder nav = new StringBuilder("<section class=\"card nav fleetnav\">");
        // The chips are the counts and the filter at once; without scripting each is a link.
        nav.append("<div class=\"fleettools\" id=\"fleet-tools\"><div class=\"chips\""
                + " role=\"group\" aria-label=\"Show\">")
                .append(fleetChip("all", "All", sorted.size(), view.show))
                .append(fleetChip("healthy", "Healthy", sorted.size() - unhealthy, view.show))
                .append(fleetChip("unhealthy", "Not healthy", unhealthy, view.show))
                .append("</div><div class=\"fleetfind\"><input type=\"search\" id=\"fleet-search\""
                        + " placeholder=\"Search panels\" aria-label=\"Search panels\""
                        + MACHINE_TEXT + ">");
        if (!groups.isEmpty()) {
            nav.append("<select id=\"fleet-group-menu\" aria-label=\"Group\"><option value=\"\">"
                    + "Group: All</option>");
            for (java.util.Map.Entry<String, String> group : groups.entrySet()) {
                nav.append("<option value=\"").append(escapeHtml(group.getKey())).append("\"")
                        .append(group.getKey().equals(view.group) ? " selected" : "").append(">")
                        .append(escapeHtml(group.getValue())).append("</option>");
            }
            nav.append("</select>");
        }
        nav.append("<div class=\"segmented small\" role=\"group\" aria-label=\"View\">"
                + "<button type=\"button\" data-view=\"list\" aria-pressed=\"true\">List</button>"
                + "<button type=\"button\" data-view=\"pictures\" aria-pressed=\"false\">Pictures"
                + "</button></div></div></div>");
        // Ticking rows swaps the tools for what can be done to all of them: how many and Close on
        // the first line, then Announce, Identify, the group to add them to with Add to group
        // (kept together), and Remove.
        nav.append("<div class=\"selbar\" id=\"fleet-selbar\" hidden><span id=\"fleet-selcount\">"
                + "</span><button type=\"button\" class=\"ib\" data-sel=\"close\""
                + " aria-label=\"Close\" title=\"Close\">").append(glyph("close"))
                .append("</button><div class=\"actions tight\">");
        if (sorted.size() > 1) {
            nav.append("<button type=\"button\" class=\"text\" data-sel=\"say\">Announce"
                    + "</button>");
        }
        nav.append("<button type=\"button\" class=\"text\" data-sel=\"identify\">Identify"
                + "</button>");
        if (!groups.isEmpty()) {
            nav.append("<span class=\"togroup\"><select id=\"fleet-sel-group\""
                    + " aria-label=\"Group to add them to\">");
            for (java.util.Map.Entry<String, String> group : groups.entrySet()) {
                nav.append("<option value=\"").append(escapeHtml(group.getKey())).append("\">")
                        .append(escapeHtml(group.getValue())).append("</option>");
            }
            nav.append("</select><button type=\"button\" class=\"text\" data-sel=\"group\">Add to"
                    + " group</button></span>");
        }
        nav.append("<button type=\"button\" class=\"text danger\" data-sel=\"remove\">Remove"
                + "</button></div><p class=\"hint\" id=\"fleet-sel-result\" hidden></p></div>");
        // Select all: the selection bar's actions for every panel shown, Announce included.
        nav.append("<label class=\"selall\"><input type=\"checkbox\" id=\"fleet-tick-all\">"
                + "Select all</label>");
        nav.append("<div id=\"fleet-rows\"").append(fleetLists(key)).append(">");
        StringBuilder bad = new StringBuilder();
        StringBuilder good = new StringBuilder();
        for (FleetHealth.Member member : sorted) {
            java.util.List<String> reasons = FleetHealth.reasons(member, newest, shares);
            java.util.Map<String, Object> entry = entries.get(member.id);
            java.util.List<String> in = FleetDocument.memberGroups(list, entry);
            in.remove(FleetDocument.EVERYONE);
            String state = reasons.isEmpty() ? "ok" : reasons.get(0).equals(FleetHealth.OFFLINE)
                    ? "off" : "warn";
            boolean shown = view.show.equals("all")
                    || view.show.equals("healthy") == reasons.isEmpty();
            StringBuilder groupWords = new StringBuilder();
            if (!in.isEmpty()) {
                groupWords.append(escapeHtml(groups.get(in.get(0))))
                        .append(in.size() > 1 ? " +" + (in.size() - 1) : "");
            }
            String row = "<li class=\"two fleetrow\" data-health=\""
                    + (reasons.isEmpty() ? "healthy" : "unhealthy") + "\" data-groups=\"|"
                    + escapeHtml(String.join("|", in)) + "|\" data-name=\""
                    + escapeHtml(member.name.toLowerCase(java.util.Locale.ROOT)) + "\""
                    + (shown ? "" : " hidden") + "><label class=\"tick\"><input type=\"checkbox\""
                    + " data-tick value=\"" + escapeHtml(TinyJson.string(entry, FleetDocument.SPKI,
                            "")) + "\" data-name=\"" + escapeHtml(member.name) + "\""
                    + (member.self ? " data-self=\"1\"" : "") + " aria-label=\""
                    + escapeHtml(member.name) + "\"></label><a class=\"rowlink plain\" href=\"/fleet"
                    + "?panel=" + urlEncode(member.id) + "&amp;detail=1\"><span class=\"lead\">"
                    + "<span class=\"dot " + state + "\"></span></span><span class=\"text\">"
                    + "<span class=\"h\">" + escapeHtml(member.name) + "</span><span class=\"s\">"
                    + escapeHtml(fleetRowLine(member, reasons)) + "</span></span></a>"
                    + "<span class=\"trail\"><span class=\"groups\">" + groupWords + "</span>"
                    + "<button type=\"button\" class=\"ib\" data-identify=\""
                    + escapeHtml(member.id) + "\" aria-label=\"Identify " + escapeHtml(member.name)
                    + "\" title=\"Identify\">" + glyph("panel") + "</button></span></li>";
            (reasons.isEmpty() ? good : bad).append(navRow(row, member.id,
                    member.id.equals(chosen)));
        }
        // The heads only when both kinds are there: a list of one kind needs no title.
        boolean both = bad.length() > 0 && good.length() > 0;
        if (bad.length() > 0) {
            nav.append(both ? "<p class=\"label\">Not healthy</p>" : "")
                    .append("<ul class=\"list seg\">").append(bad).append("</ul>");
        }
        if (good.length() > 0) {
            nav.append(both ? "<p class=\"label\">Healthy</p>" : "")
                    .append("<ul class=\"list seg\">").append(good).append("</ul>");
        }
        nav.append("</div>");
        if (sorted.size() == 1) {
            nav.append("<div class=\"firstrun\"><h3>Add your next panel</h3><p class=\"hint\">By its"
                    + " Join code, or an invitation QR.</p>"
                    + "<div class=\"actions\"><a class=\"btn tonal\" href=\"#add-panel\""
                    + " data-dialog=\"add-panel\">").append(glyph("plus"))
                    .append("Add a panel</a></div></div>");
        }
        nav.append("</section>");
        StringBuilder detail = new StringBuilder();
        for (FleetHealth.Member member : sorted) {
            detail.append(pane(member.id, chosen, fleetPanelDetail(list, groups, member,
                    entries.get(member.id), newest, shares, view, key)));
        }
        return listDetail("fleet", view.detail, nav.toString(), detail.toString(),
                " data-base=\"/fleet?panel=\" data-list=\"/fleet\"");
    }

    private static String fleetChip(String show, String label, int count, String chosen) {
        return "<a class=\"fchip\" href=\"/fleet" + (show.equals("all") ? "" : "?show=" + show)
                + "\" data-show=\"" + show + "\"" + (show.equals(chosen) ? " aria-current=\"true\""
                        : "") + ">"
                + escapeHtml(label) + " <span data-count=\"" + show + "\">" + count
                + "</span></a>";
    }

    /** One panel at the right: its picture, health, groups, network speaker, Identify, Remove. */
    private String fleetPanelDetail(java.util.Map<String, Object> list,
            java.util.Map<String, String> groups, FleetHealth.Member member,
            java.util.Map<String, Object> entry, String newest, boolean shares, FleetView view,
            String key) {
        String id = escapeHtml(member.id);
        String name = escapeHtml(member.name);
        String contact = fleetStatusLine(member);
        StringBuilder html = new StringBuilder("<section class=\"card fleetpanel\""
                + " data-panel=\"" + id + "\"><div class=\"cardhead\"><h2>" + name + "</h2></div>"
                + "<p class=\"hint\" id=\"fleet-status-" + id + "\"" + fleetLists(key) + ">"
                + escapeHtml(contact) + "</p>");
        // The picture is taken when the panel is opened, never with the page: each is a capture
        // on that wall.
        html.append("<div class=\"fleet-shot\" data-panel=\"").append(id).append("\"><img alt=\"The"
                + " screen of ").append(name).append("\" hidden><p class=\"hint\" hidden></p>"
                + "<div class=\"shotbar\"><span class=\"hint\" data-taken></span><button"
                + " type=\"button\" class=\"ib\" data-shot=\"").append(id).append("\""
                + " aria-label=\"Take a new picture\" title=\"Take a new picture\">")
                .append(glyph("reload")).append("</button></div></div>");
        html.append("<p class=\"label\">Health</p><div class=\"health\" id=\"fleet-health-")
                .append(id).append("\"").append(fleetLists(key)).append(">")
                .append(fleetHealthChips(member, FleetHealth.reasons(member, newest, shares)))
                .append("</div>");
        if (!groups.isEmpty()) {
            java.util.List<String> in = FleetDocument.memberGroups(list, entry);
            html.append("<p class=\"label\">Groups</p><form method=\"post\" action=\"/fleet\""
                    + " class=\"groupchips\">").append(hidden("what", "panel"))
                    .append(hidden("spki", TinyJson.string(entry, FleetDocument.SPKI, "")))
                    .append(hidden("return", view.address())).append("<div class=\"chips\">");
            for (java.util.Map.Entry<String, String> group : groups.entrySet()) {
                html.append("<label class=\"fchip\"><input type=\"checkbox\" name=\"group_")
                        .append(escapeHtml(group.getKey())).append("\" value=\"1\" data-at-once")
                        .append(in.contains(group.getKey()) ? " checked" : "").append(">")
                        .append(glyph("check")).append("<span>")
                        .append(escapeHtml(group.getValue())).append("</span></label>");
            }
            html.append("</div><div class=\"actions nojs\"><button class=\"tonal\""
                    + " type=\"submit\">Save groups</button></div></form>");
        }
        // The network speaker's state is asked of that panel when it is opened.
        html.append("<p class=\"label\">Network speaker</p><div class=\"pspeaker\" data-panel=\"")
                .append(id).append("\"><span class=\"s\" data-state>Asking the panel...</span>"
                        + "<label class=\"slider small\"><span class=\"sr\">Volume of ")
                .append(name).append("</span><input type=\"range\" min=\"0\" max=\"100\""
                        + " value=\"0\" data-volume disabled></label><input type=\"checkbox\""
                        + " class=\"sw\" data-switch disabled aria-label=\"Network speaker of ")
                .append(name).append("\"></div>");
        html.append("<div class=\"actions\"><button type=\"button\" class=\"text\" data-identify=\"")
                .append(id).append("\">Identify</button>")
                // That panel's settings, or Muralis left there, without its corner taps.
                .append("<button type=\"button\" class=\"text\" data-panel-action=\"dashboard\""
                        + " data-panel=\"").append(id).append("\">Open dashboard</button>")
                .append("<button type=\"button\" class=\"text\" data-panel-action=\"settings\""
                        + " data-panel=\"").append(id).append("\">Open settings</button>")
                .append("<button type=\"button\" class=\"text\" data-panel-action=\"home_screen\""
                        + " data-panel=\"").append(id).append("\" data-confirm=\"")
                .append(escapeHtml(leaveQuestion(member.name)))
                .append("\">Go to home screen</button>");
        if (!member.self) {
            // For a panel that cannot leave by itself; one that is reachable is told.
            html.append("<form method=\"post\" action=\"/fleet\">").append(hidden("what", "panel"))
                    .append(hidden("remove", "1"))
                    .append(hidden("spki", TinyJson.string(entry, FleetDocument.SPKI, "")))
                    .append(hidden("return", "/fleet"))
                    .append("<button type=\"submit\" class=\"text danger\" data-confirm=\"")
                    .append(escapeHtml("Remove " + member.name + "?\n\nIt is taken off the fleet's"
                            + " list on every panel and its key is no longer trusted. It can join"
                            + " again with a new code."))
                    .append("\">Remove</button></form>");
        }
        return html.append("</div><p class=\"hint\" data-identified hidden></p></section>")
                .toString();
    }

    /**
     * A panel's status under its name: words that do not move by the minute, so the page is not
     * drawn again with every heartbeat; offline says what to look at.
     */
    private static String fleetStatusLine(FleetHealth.Member member) {
        if (member.self) {
            return "This panel";
        }
        if (member.online) {
            return "Online";
        }
        return fleetReasonWords(member, FleetHealth.OFFLINE)
                + ": is it on, on this network, with Muralis open?";
    }

    /** A panel's health as glyphs with their words, and every reason it is not healthy. */
    private static String fleetHealthChips(FleetHealth.Member member, java.util.List<String> reasons) {
        StringBuilder chips = new StringBuilder();
        for (String reason : reasons) {
            chips.append("<span class=\"hchip bad\">").append(glyph("info"))
                    .append(escapeHtml(reason.equals(FleetHealth.PAGE_ERROR)
                            ? "Page error: " + member.pageError : fleetReasonWords(member, reason)))
                    .append("</span>");
        }
        if (member.batteryPercent >= 0) {
            chips.append("<span class=\"hchip\">").append(glyph(member.charging ? "power" : "battery"))
                    .append(member.batteryPercent).append('%')
                    .append(member.charging ? ", charging" : "").append("</span>");
        }
        String wifi = FleetHealth.wifiWord(member.wifiRssiDbm);
        if (!wifi.isEmpty()) {
            chips.append("<span class=\"hchip\">").append(glyph("network")).append("Wi-Fi ")
                    .append(wifi).append("</span>");
        }
        if (!member.appVersion.isEmpty()) {
            chips.append("<span class=\"hchip\">").append(glyph("info")).append("Muralis ")
                    .append(escapeHtml(member.appVersion)).append("</span>");
        }
        if (!member.android.isEmpty()) {
            chips.append("<span class=\"hchip\">").append(glyph("panel")).append("Android ")
                    .append(escapeHtml(member.android)).append("</span>");
        }
        return chips.length() == 0 ? "<span class=\"hint\">Not heard from yet</span>"
                : chips.toString();
    }

    /** The Groups tab: each group with its panels, its shared settings and Remove, and New group. */
    private String fleetGroupsTab(java.util.Map<String, Object> list, FleetView view, String key) {
        java.util.Map<String, String> groups = FleetDocument.groups(list);
        StringBuilder html = new StringBuilder("<section class=\"card\"><div class=\"cardhead\">"
                + "<h2>Groups</h2><span class=\"count\">" + (groups.size() == 1 ? "1 group"
                        : groups.size() + " groups") + "</span></div><div id=\"fleet-groups\""
                + fleetLists(key) + ">");
        if (groups.isEmpty()) {
            html.append("<p class=\"hint\">No groups yet.</p>");
        } else {
            html.append("<ul class=\"list seg\">");
            for (java.util.Map.Entry<String, String> group : groups.entrySet()) {
                StringBuilder names = new StringBuilder();
                int count = 0;
                for (java.util.Map<String, Object> member : FleetDocument.members(list)) {
                    if (FleetDocument.memberGroups(list, member).contains(group.getKey())) {
                        count++;
                        names.append("<span class=\"rchip\">").append(escapeHtml(TinyJson.string(
                                member, FleetDocument.NAME, ""))).append("</span>");
                    }
                }
                String name = escapeHtml(group.getValue());
                html.append("<li class=\"two group\"><span class=\"lead\">").append(glyph("fleet"))
                        .append("</span><span class=\"text\"><span class=\"h\">").append(name)
                        .append("</span><span class=\"s\">")
                        .append(count == 1 ? "1 panel" : count + " panels")
                        .append("</span><span class=\"chips\">").append(names)
                        .append("</span></span><span class=\"trail\"><a class=\"ib\" href=\"/fleet"
                                + "?tab=shared&amp;scope=").append(escapeHtml(urlEncode(group.getKey())))
                        .append("\" aria-label=\"Shared settings of ").append(name)
                        .append("\" title=\"Shared settings\">").append(glyph("open"))
                        .append("</a><form method=\"post\" action=\"/fleet\">")
                        .append(hidden("what", "remove_group")).append(hidden("id", group.getKey()))
                        .append(hidden("return", view.address()))
                        .append("<button type=\"submit\" class=\"ib\" aria-label=\"Remove ")
                        .append(name).append("\" title=\"Remove\" data-confirm=\"")
                        // The panel's question for it, in the same words.
                        .append(escapeHtml("Remove the group " + group.getValue() + "?\n\nWhat it"
                                + " shares is shared no more; the panels in it keep their"
                                + " settings."))
                        .append("\">").append(glyph("trash")).append("</button></form></span></li>");
            }
            html.append("</ul>");
        }
        html.append("</div>");
        StringBuilder suggestions = new StringBuilder();
        for (String suggestion : FleetDocument.GROUP_SUGGESTIONS) {
            if (!groups.containsValue(suggestion)) {
                suggestions.append("<option value=\"").append(escapeHtml(suggestion)).append("\">");
            }
        }
        html.append("<form method=\"post\" action=\"/fleet\">").append(hidden("what", "add_group"))
                .append(hidden("return", view.address()))
                .append(fieldWithId("fleet-page-group-name", "text", "name", "New group", "",
                        " list=\"fleet-page-suggestions\" autocomplete=\"off\"", null))
                .append("<datalist id=\"fleet-page-suggestions\">").append(suggestions)
                .append("</datalist><div class=\"actions tight\"><button class=\"tonal\""
                        + " type=\"submit\">Add group</button></div></form></section>");
        return html.toString();
    }

    /** The settings section a shared section is changed in on this panel, or null. */
    private static String settingsBoxOf(String section) {
        switch (section) {
            case "web_admin": return "webadmin";
            case "alerts": return null;
            default: return section;
        }
    }

    /**
     * The Shared settings tab: the scope as chips, then one row per section with what it shares,
     * Alerts among them and History last; at the right the chosen one (list-detail).
     */
    private String fleetSharedTab(java.util.Map<String, Object> list, FleetView view, String key) {
        java.util.Map<String, String> groups = FleetDocument.groups(list);
        String scope = FleetDocument.EVERYONE.equals(view.scope) || groups.containsKey(view.scope)
                ? view.scope : FleetDocument.EVERYONE;
        java.util.List<String> ids = new java.util.ArrayList<>(
                java.util.Arrays.asList(FleetDocument.SECTIONS));
        ids.add("history");
        String chosen = ids.contains(view.section) ? view.section : ids.get(0);
        String base = "/fleet?tab=shared&amp;scope=" + escapeHtml(urlEncode(scope));
        StringBuilder nav = new StringBuilder("<section class=\"card nav\"><div class=\"chips\""
                + " role=\"group\" aria-label=\"Shared in\">");
        nav.append(scopeChip(FleetDocument.EVERYONE, FleetDocument.EVERYONE_NAME, scope));
        for (java.util.Map.Entry<String, String> group : groups.entrySet()) {
            nav.append(scopeChip(group.getKey(), group.getValue(), scope));
        }
        nav.append("</div><ul class=\"list seg\" id=\"fleet-shared\"").append(fleetLists(key))
                .append(">");
        for (String section : FleetDocument.SECTIONS) {
            java.util.Map<String, Object> values = FleetDocument.sectionValues(list, scope, section);
            String summary = FleetProfile.summary(section, values);
            if (FleetDocument.EVERYONE.equals(scope)) {
                // Where a group shares the section too, its own values win for its panels.
                java.util.List<String> winners = new java.util.ArrayList<>();
                for (java.util.Map.Entry<String, String> group : groups.entrySet()) {
                    if (FleetDocument.sectionValues(list, group.getKey(), section) != null) {
                        winners.add(group.getValue());
                    }
                }
                if (!winners.isEmpty()) {
                    summary += winners.size() == 1 ? " · " + winners.get(0) + " wins"
                            : " · " + winners.size() + " groups win";
                }
            }
            nav.append(navRow("<li class=\"two\"><a class=\"rowlink plain\" href=\"" + base
                    + "&amp;section=" + section + "&amp;detail=1\"><span class=\"lead\">"
                    + glyph(values == null ? "minus" : "check") + "</span><span class=\"text\">"
                    + "<span class=\"h\">" + escapeHtml(FleetDocument.sectionLabel(section))
                    + "</span><span class=\"s\">" + escapeHtml(summary) + "</span></span></a></li>",
                    section, section.equals(chosen)));
        }
        java.util.List<FleetServer.HistoryEntry> history = FleetServer.history(context);
        nav.append(navRow("<li class=\"two\"><a class=\"rowlink plain\" href=\"" + base
                + "&amp;section=history&amp;detail=1\"><span class=\"lead\">" + glyph("list")
                + "</span><span class=\"text\"><span class=\"h\">History</span><span class=\"s\">"
                + escapeHtml(history.isEmpty() ? "No versions kept yet" : "Version "
                        + history.get(0).version + (history.get(0).held ? ", held now" : ""))
                + "</span></span></a></li>", "history", "history".equals(chosen)));
        nav.append("</ul></section>");
        StringBuilder detail = new StringBuilder();
        for (String section : FleetDocument.SECTIONS) {
            detail.append(pane(section, chosen, fleetSectionDetail(list, scope, section, view)));
        }
        detail.append(pane("history", chosen, fleetHistoryDetail(view)));
        return listDetail("fleet", view.detail, nav.toString(), detail.toString(),
                " data-base=\"/fleet?tab=shared&scope=" + escapeHtml(urlEncode(scope))
                        + "&section=\" data-list=\"/fleet?tab=shared&scope="
                        + escapeHtml(urlEncode(scope)) + "\"");
    }

    private static String scopeChip(String scope, String label, String chosen) {
        return "<a class=\"fchip\" href=\"/fleet?tab=shared&amp;scope=" + escapeHtml(urlEncode(scope))
                + "\"" + (scope.equals(chosen) ? " aria-current=\"true\"" : "") + ">"
                + escapeHtml(label) + "</a>";
    }

    /** One shared section: its switch, applied at once, and the values the scope shares. */
    private String fleetSectionDetail(java.util.Map<String, Object> list, String scope,
            String section, FleetView view) {
        java.util.Map<String, Object> values = FleetDocument.sectionValues(list, scope, section);
        String label = FleetDocument.sectionLabel(section);
        String in = FleetDocument.EVERYONE.equals(scope) ? "the whole fleet"
                : "the group " + FleetDocument.groupName(list, scope);
        FleetView back = FleetView.of(java.util.Collections.emptyMap());
        back.tab = "shared";
        back.scope = scope;
        back.section = section;
        back.detail = view.detail;
        StringBuilder html = new StringBuilder("<section class=\"card\"><div class=\"cardhead\">"
                + "<h2>" + escapeHtml(label) + "</h2></div>");
        // Switched on, the section as this panel has it now is shared; the switch posts itself
        // where scripting runs (admin_fleet.js), Save does elsewhere.
        html.append("<form method=\"post\" action=\"/fleet\">").append(hidden("what", "share"))
                .append(hidden("scope", scope)).append(hidden("section", section))
                .append(hidden("return", back.address())).append("<div class=\"switches\">")
                .append(switchRow("fleet-share-" + section, "Shared in " + in,
                        " name=\"shared\" value=\"1\" data-submit=\"1\"", values != null))
                .append("</div><div class=\"actions nojs\"><button class=\"tonal\" type=\"submit\">"
                        + "Save</button></div></form>");
        html.append("<p class=\"hint\">").append(values == null ? (section.equals("alerts")
                ? "Not shared: no alerts are raised for " + escapeHtml(in) + ". Switched on, this"
                        + " panel's alerts are shared."
                : "Not shared: each panel keeps its own.") : "Shared with every panel of "
                + escapeHtml(in) + ".").append("</p>");
        if (section.equals("alerts")) {
            // Alerts are edited here, where they are shared; the other sections are changed in
            // their own settings section on a panel.
            return html.append(values == null ? "" : fleetAlertsForm(scope, values))
                    .append("</section>").toString();
        }
        if (values != null) {
            html.append("<dl class=\"values\">");
            for (FleetProfile.Field field : FleetProfile.fields(section)) {
                if (field.type.equals("password") || !values.containsKey(field.key)) {
                    continue;
                }
                Object value = values.get(field.key);
                String text = field.type.equals("switch") ? (Boolean.TRUE.equals(value) ? "On" : "Off")
                        : FleetProfile.shown(value);
                if (field.options != null) {
                    for (int i = 0; i + 1 < field.options.length; i += 2) {
                        if (field.options[i].equals(text)) {
                            text = field.options[i + 1];
                        }
                    }
                }
                html.append("<dt>").append(escapeHtml(field.label)).append("</dt><dd>")
                        .append(escapeHtml(text.isEmpty() ? "None" : text)).append("</dd>");
            }
            if (section.equals("integrations")) {
                html.append("<dt>Integrations</dt><dd>")
                        .append(escapeHtml(FleetProfile.summary(section, values))).append("</dd>");
            }
            html.append("</dl>");
        }
        String box = settingsBoxOf(section);
        if (box != null) {
            html.append("<div class=\"actions\"><a class=\"btn text\" href=\"").append(settingsAt(box))
                    .append("\">Change on this panel").append(glyph("next")).append("</a></div>");
        }
        return html.append("</section>").toString();
    }

    /**
     * The fleet's alerts as the scope shares them, edited and saved here for that scope. Alerts
     * are raised only while they are shared.
     */
    private String fleetAlertsForm(String scope, java.util.Map<String, Object> values) {
        String section = "alerts";
        StringBuilder html = new StringBuilder("<form method=\"post\" action=\"/alerts\">")
                .append(hidden("scope", scope)).append("<div class=\"switches\">");
        for (FleetProfile.Field field : FleetProfile.fields(section)) {
            Object value = values.get(field.key);
            String text = FleetProfile.shown(value);
            switch (field.type) {
                case "switch":
                    html.append(switchRow("alerts-" + section + "-" + field.key,
                            field.label, " name=\"" + field.key + "\" value=\"1\"",
                            Boolean.TRUE.equals(value)));
                    break;
                case "select": {
                    StringBuilder choices = new StringBuilder();
                    for (int i = 0; i + 1 < field.options.length; i += 2) {
                        choices.append(selectOption(field.options[i], field.options[i + 1],
                                text));
                    }
                    html.append("</div>").append(selectField("alerts-" + section + "-"
                            + field.key, field.key, field.label, null, choices.toString(),
                            null)).append("<div class=\"switches\">");
                    break;
                }
                case "password":
                    html.append("</div>").append(fieldWithId("alerts-" + section + "-"
                            + field.key, "password", field.key, field.label, "", "",
                            text.isEmpty() ? "None shared." : BLANK_KEEPS))
                            .append("<div class=\"switches\">");
                    break;
                default:
                    html.append("</div>").append(fieldWithId("alerts-" + section + "-"
                            + field.key, "number".equals(field.type) ? "number" : "text",
                            field.key, field.label, text, "", null))
                            .append("<div class=\"switches\">");
            }
        }
        html.append("</div><div class=\"actions\"><button class=\"primary\" type=\"submit\">Save"
                + "</button></div></form>");
        return html.toString();
    }

    /**
     * History: the versions this panel held, newest first, each with what it changed, and
     * Restore as new for every one but the version held; the newest few, the rest behind Older
     * versions, as on the panel.
     */
    private String fleetHistoryDetail(FleetView view) {
        FleetView back = FleetView.of(java.util.Collections.emptyMap());
        back.tab = "shared";
        back.scope = view.scope;
        back.section = "history";
        back.detail = view.detail;
        StringBuilder html = new StringBuilder("<section class=\"card\"><h2>History</h2>");
        java.util.List<FleetServer.HistoryEntry> history = FleetServer.history(context);
        if (history.isEmpty()) {
            html.append("<p class=\"hint\">No versions kept yet.</p>");
        }
        int shownCount = 0;
        html.append("<ul class=\"list seg\">");
        for (FleetServer.HistoryEntry entry : history) {
            if (shownCount++ == FleetServer.HISTORY_SHOWN) {
                html.append("</ul><details class=\"older\"><summary>Older versions</summary>"
                        + "<ul class=\"list seg\">");
            }
            html.append("<li class=\"two\"><span class=\"text\"><span class=\"h\">Version ")
                    .append(entry.version).append(entry.held ? ", held now" : "").append("</span>"
                            + "<span class=\"s wrap\">").append(escapeHtml(entry.author)).append(", ")
                    .append(escapeHtml(FleetServer.readableTime(entry.updated))).append(". ")
                    .append(entry.changes.isEmpty()
                            ? "No change to the fleet's name, panels, groups or shared settings."
                            : escapeHtml(capitalized(String.join("; ", entry.changes))) + ".")
                    .append("</span></span>");
            // Restore brings back a version's name, groups and shared settings, never its
            // panels: a version that only changed panels has nothing to restore.
            boolean panelsOnly = !entry.changes.isEmpty();
            for (String change : entry.changes) {
                panelsOnly &= change.startsWith("panel ");
            }
            if (!entry.held && !panelsOnly) {
                // The panel's question, in its words: it rewrites what every panel shares.
                html.append("<span class=\"trail\"><form method=\"post\" action=\"/fleet\">")
                        .append(hidden("what", "restore"))
                        .append(hidden("version", Long.toString(entry.version)))
                        .append(hidden("return", back.address()))
                        .append("<button class=\"ib\" type=\"submit\" aria-label=\"Restore version ")
                        .append(entry.version).append(" as new\" title=\"Restore as new\""
                                + " data-confirm=\"").append(escapeHtml("Restore version "
                                + entry.version + " as new?\n\nIts fleet name, groups and shared"
                                + " settings are written as a new version for every panel; the"
                                + " panels of the fleet stay as they are now."))
                        .append("\">").append(glyph("reload")).append("</button></form></span>");
            }
            html.append("</li>");
        }
        html.append("</ul>");
        if (shownCount > FleetServer.HISTORY_SHOWN) {
            html.append("</details>");
        }
        html.append("<p class=\"hint\">The last ").append(KioskConfig.FLEET_HISTORY_SIZE)
                .append(" versions. Restore as new brings back a version's name, groups and shared"
                        + " settings; the panels stay.</p></section>");
        return html.toString();
    }

    /**
     * The two dialogs of the head: Add a panel (by its code, or an invitation QR for a new
     * tablet) and Announce (the Speakers card), opened from the selection bar. Without
     * scripting a dialog opens as a section of the page through its address (#add-panel), as a
     * link to it does.
     */
    private String fleetDialogs(java.util.Map<String, Object> list) {
        // The form's own first line repeats the dialog's title here: left out.
        String byCode = addByCodeForm(null, null).replaceFirst("^<p class=\"hint\">[^<]*</p>", "")
                .replaceFirst("(<form[^>]*>)",
                        "$1" + java.util.regex.Matcher.quoteReplacement(hidden("back", "fleet")));
        return "<dialog id=\"add-panel\" class=\"sheet\" aria-labelledby=\"add-panel-title\">"
                + "<div class=\"cardhead\"><h2 id=\"add-panel-title\">Add a panel</h2>"
                + "<a class=\"ib\" href=\"#\" data-close aria-label=\"Close\" title=\"Close\">"
                + glyph("close") + "</a></div>"
                + "<div class=\"segmented\" role=\"group\" aria-label=\"How\">"
                + "<button type=\"button\" data-way=\"code\" aria-pressed=\"true\">By its code"
                + "</button><button type=\"button\" data-way=\"invite\" aria-pressed=\"false\">"
                + "Invitation QR</button></div>"
                + "<div data-way-pane=\"code\">" + byCode + "</div>"
                + "<div data-way-pane=\"invite\">" + inviteBlock() + "</div></dialog>"
                + (FleetDocument.members(list).size() > 1
                        ? "<dialog id=\"announce\" class=\"sheet\" aria-label=\"Announce\">"
                                + speakersCard(list, FleetDocument.groups(list))
                                        .replaceFirst("<h2>Speakers</h2>", "<h2>Announce</h2>")
                                        // Close at the end of the head, after the count.
                                        .replaceFirst("(id=\"speakers-count\"></span>)", "$1"
                                                + "<a class=\"ib\" href=\"#\" data-close"
                                                + " aria-label=\"Close\" title=\"Close\">"
                                                + java.util.regex.Matcher.quoteReplacement(
                                                        glyph("close")) + "</a>")
                                + "</dialog>" : "");
    }

    /** What the Alerts pane posts: the values, shared with the scope the form names. */
    private String saveAlerts(Map<String, String> form) {
        if (!FleetServer.proStatus(context).pro) {
            return "Not changed: the fleet's alerts are Muralis Pro. " + FleetServer.proLine(context);
        }
        String section = "alerts";
        java.util.Map<String, Object> list = KioskConfig.fleetDocumentOf(context);
        String asked = form.getOrDefault("scope", "");
        String scope = list != null && (FleetDocument.EVERYONE.equals(asked)
                || FleetDocument.groups(list).containsKey(asked)) ? asked
                : FleetProfile.alertsScope(list);
        java.util.Map<String, Object> shared = list == null ? null
                : FleetDocument.sectionValues(list, scope, section);
        String[] problem = new String[1];
        java.util.Map<String, Object> values = FleetProfile.fromForm(section, form,
                shared != null ? shared : FleetProfile.read(context, section), problem);
        String refused = values == null ? problem[0]
                : FleetServer.shareSection(context, scope, section, values);
        return refused == null ? null : "Not saved: " + refused + ".";
    }

    /** A line with its first letter capital, after a full stop. */
    private static String capitalized(String line) {
        return line.isEmpty() ? line : Character.toUpperCase(line.charAt(0)) + line.substring(1);
    }

    /** The key hashes a selection posts, comma-separated, blanks left out. */
    private static java.util.List<String> keyList(String text) {
        java.util.List<String> keys = new java.util.ArrayList<>();
        for (String one : text.split(",")) {
            if (!one.trim().isEmpty()) {
                keys.add(one.trim());
            }
        }
        return keys;
    }

    /** What the Fleet page's forms post: a panel's groups, a group, a scope's section. */
    private String saveFleetPage(Map<String, String> form) {
        if (!FleetServer.proStatus(context).pro) {
            return "Not changed: the Fleet page is Muralis Pro. " + FleetServer.proLine(context);
        }
        String what = form.getOrDefault("what", "");
        String refused;
        switch (what) {
            case "panel": {
                java.util.List<String> groups = new java.util.ArrayList<>();
                for (String key : form.keySet()) {
                    if (key.startsWith("group_") && isTrue(form.get(key))) {
                        groups.add(key.substring("group_".length()));
                    }
                }
                // Remove submits the same form, its own button naming it.
                refused = isTrue(form.get("remove"))
                        ? FleetServer.removeMember(context, form.getOrDefault("spki", ""), true)
                        : FleetServer.setMemberGroups(context, form.getOrDefault("spki", ""),
                                groups);
                break;
            }
            case "add_group":
                refused = FleetServer.createGroup(context, form.getOrDefault("name", ""),
                        new String[1]);
                break;
            case "add_to_group":
                refused = FleetServer.addToGroup(context, form.getOrDefault("group", ""),
                        keyList(form.getOrDefault("panels", "")));
                break;
            case "remove_panels": {
                // This panel leaves with Leave, never by Remove: skipped, as the page says.
                refused = null;
                for (String spki : keyList(form.getOrDefault("panels", ""))) {
                    if (!spki.equals(KioskRuntimeState.fleetKey())) {
                        String one = FleetServer.removeMember(context, spki, true);
                        refused = refused != null ? refused : one;
                    }
                }
                break;
            }
            case "remove_group":
                refused = FleetServer.removeGroup(context, form.getOrDefault("id", ""));
                break;
            case "restore": {
                long version;
                try {
                    version = Long.parseLong(form.getOrDefault("version", "").trim());
                } catch (NumberFormatException notANumber) {
                    refused = "no such version";
                    break;
                }
                refused = FleetServer.restoreVersion(context, version);
                break;
            }
            case "share": {
                String scope = form.getOrDefault("scope", FleetDocument.EVERYONE);
                String section = form.getOrDefault("section", "");
                if (!java.util.Arrays.asList(FleetDocument.SECTIONS).contains(section)) {
                    refused = "no such section";
                    break;
                }
                boolean on = isTrue(form.getOrDefault("shared", "0"));
                boolean was = FleetDocument.sectionValues(KioskConfig.fleetDocumentOf(context),
                        scope, section) != null;
                // Switched on, the section as this panel has it is shared; on already, nothing
                // changes, so a stale page cannot overwrite what the scope shares now.
                refused = on == was ? null : FleetServer.shareSection(context, scope, section,
                        on ? FleetProfile.read(context, section) : null);
                break;
            }
            default:
                refused = "nothing to do";
        }
        return refused == null ? null : "Not done: " + refused + ".";
    }

    private String buildScreensaverPage(String notice, String at) {
        ScreensaverPolicy.Settings saver = KioskConfig.screensaverOf(context);
        boolean problem = saver.enabled()
                && ScreensaverPolicy.modeProblem(saver.mode, saver.url) != null;
        StringBuilder onWake = new StringBuilder();
        for (String[] choice : KioskCommandDispatcher.AMBIENT_ON_WAKE_WORDS) {
            onWake.append(selectOption(choice[0], choice[1], saver.onWake));
        }
        StringBuilder html = new StringBuilder(pageStart("Ambient", null,
                settingsAt("ambient")));
        if (notice != null && !notice.isEmpty()) {
            html.append("<p class=\"notice\">").append(escapeHtml(notice)).append("</p>");
        }
        // The Sensors page's shape (2026-09-28): the playlists at the left and the chosen
        // mode's options at the right from 840 px, one under the other below. The mode itself
        // is chosen on the settings page, whose Ambient section leads here; the page's
        // shell carries the mode and every title, so admin_setting.js can rename the options
        // when the mode changes elsewhere, without a chooser of its own.
        org.json.JSONObject titles = new org.json.JSONObject();
        for (String mode : new String[] {ScreensaverPolicy.DIM, ScreensaverPolicy.FILM,
                ScreensaverPolicy.URL, ScreensaverPolicy.PICTURES}) {
            try {
                titles.put(mode, ScreensaverPolicy.optionsTitle(mode));
            } catch (org.json.JSONException ignored) {
                // A string keyed by a string cannot fail.
            }
        }
        // A mode without playlists leaves the options the page's only panel, centred at the
        // legal pages' width (class one); admin_setting.js keeps the class with the mode.
        boolean library = ScreensaverPolicy.PICTURES.equals(saver.mode);
        html.append("<div class=\"ld").append(library ? "" : " one")
                .append("\" id=\"ambient-page\" data-mode=\"")
                .append(escapeHtml(saver.mode)).append("\" data-titles=\"")
                .append(escapeHtml(titles.toString())).append("\">")
                .append(pictureLibraryBox())
                .append("<div class=\"detail\"><section class=\"card")
                .append(ScreensaverPolicy.OFF.equals(saver.mode) ? " gone" : "")
                .append("\" id=\"ambient-options\"><h2 id=\"ambient-options-title\">")
                .append(escapeHtml(ScreensaverPolicy.optionsTitle(saver.mode))).append("</h2>")
                .append(fieldWithId("ambient-idle", "number", null,
                        "Start after (seconds, 0 = never)",
                        String.valueOf(saver.idleSeconds),
                        " data-setting=\"ambient_start_after_s\" min=\"0\" max=\""
                                + ScreensaverPolicy.MAX_SECONDS + "\" step=\"1\"", null))
                .append(fieldWithId("ambient-off", "number", null,
                        "Before display off (seconds, 0 = never)",
                        String.valueOf(saver.offSeconds),
                        " data-setting=\"ambient_off_s\" min=\"0\" max=\""
                                + ScreensaverPolicy.MAX_SECONDS + "\" step=\"1\"", null))
                .append("<div id=\"ambient-url-field\"")
                .append(ScreensaverPolicy.URL.equals(saver.mode) ? "" : " class=\"gone\"").append(">")
                .append(fieldWithId("ambient-url", "text", null, "Web page address", saver.url,
                        " data-setting=\"ambient_url\" inputmode=\"url\"", null))
                .append("</div>")
                .append("<div id=\"ambient-dim-field\"")
                .append(ScreensaverPolicy.DIM.equals(saver.mode) ? "" : " class=\"gone\"").append(">")
                .append(fieldWithId("ambient-dim", "number", null,
                        "Dim brightness (percent)", String.valueOf(saver.dimPercent),
                        " data-setting=\"ambient_dim_percent\" min=\"1\" max=\"100\" step=\"1\"",
                        null))
                .append("</div>")
                // Gone rather than greyed out where it does not apply: a control that can never
                // be enabled is clutter (2026-09-11).
                .append("<div id=\"ambient-wake-field\"")
                .append(ScreensaverPolicy.wakeChoiceApplies(saver.mode) ? "" : " class=\"gone\"")
                .append(">")
                .append(selectField("ambient-on-wake", null, "On wake",
                        "ambient_on_wake", onWake.toString(), null))
                .append("</div>")
                .append(pictureOptions(saver))
                // The sentence it all adds up to, last in the panel, which is where the owner put it:
                // the settings above it, then what they mean in one line.
                .append("<p class=\"hint").append(problem ? " bad" : "").append("\" id=\"ambient-note\">")
                .append(escapeHtml(ScreensaverPolicy.describe(saver,
                        KioskRuntimeState.screensaverActive())))
                .append("</p>")
                .append("<div class=\"actions\">")
                .append(quickAction("ambient.start", "Preview", "tonal", null))
                .append("</div>")
                .append("</section></div>")
                .append("</div>");
        html.append(commandScript);
        html.append(settingScript);
        html.append(statsScript);
        html.append(themeScript);
        html.append("</main></body></html>");
        return html.toString();
    }

    /**
     * Ambient's instant settings. Each field is checked by the same rule the tablet
     * applies and refused with the reason, never clamped: a value that cannot be stored is not
     * stored, and the page's field turns red with the sentence.
     */
    private String saveScreensaverSettings(Map<String, String> form) {
        KioskConfig.Editor editor = KioskConfig.edit(context);
        boolean changed = false;
        if (form.containsKey("ambient_mode")) {
            String mode = form.get("ambient_mode");
            if (!ScreensaverPolicy.isMode(mode)) {
                return "Not saved: the Ambient mode must be off, dim, film, url or pictures.";
            }
            editor.screensaverMode(mode);
            changed = true;
        }
        if (form.containsKey("ambient_start_after_s")) {
            Integer seconds = ScreensaverPolicy.parseSeconds(form.get("ambient_start_after_s"));
            if (seconds == null) {
                return "Not saved: Ambient start after " + ScreensaverPolicy.SECONDS_RULE + ".";
            }
            editor.screensaverIdleSeconds(seconds);
            changed = true;
        }
        if (form.containsKey("ambient_off_s")) {
            Integer seconds = ScreensaverPolicy.parseSeconds(form.get("ambient_off_s"));
            if (seconds == null) {
                return "Not saved: the time before display off "
                        + ScreensaverPolicy.SECONDS_RULE + ".";
            }
            editor.screensaverOffSeconds(seconds);
            changed = true;
        }
        if (form.containsKey("ambient_url")) {
            String url = form.get("ambient_url").trim();
            if (!url.isEmpty()) {
                String problem = KioskCommandDispatcher.validateDashboardUrl(url);
                if (problem != null) {
                    return "Not saved: " + problem + ".";
                }
            }
            editor.screensaverUrl(url);
            changed = true;
        }
        if (form.containsKey("ambient_dim_percent")) {
            Integer percent = ScreensaverPolicy.parseDimPercent(form.get("ambient_dim_percent"));
            if (percent == null) {
                return "Not saved: the dimmed brightness " + ScreensaverPolicy.DIM_RULE + ".";
            }
            editor.screensaverDimPercent(percent);
            changed = true;
        }
        if (form.containsKey("ambient_on_wake")) {
            String onWake = form.get("ambient_on_wake");
            if (!ScreensaverPolicy.isOnWake(onWake)) {
                return "Not saved: the wake choice must be ambient or dashboard.";
            }
            editor.screensaverOnWake(onWake);
            changed = true;
        }
        if (form.containsKey("ambient_picture_s")) {
            Integer seconds = ScreensaverPolicy.parsePictureSeconds(form.get("ambient_picture_s"));
            if (seconds == null) {
                return "Not saved: the time per picture " + ScreensaverPolicy.PICTURE_SECONDS_RULE + ".";
            }
            editor.screensaverPictureSeconds(seconds);
            changed = true;
        }
        if (form.containsKey("ambient_transition")) {
            String transition = form.get("ambient_transition");
            if (!ScreensaverPolicy.isTransition(transition)) {
                return "Not saved: the transition must be cut, fade or slide.";
            }
            editor.screensaverTransition(transition);
            changed = true;
        }
        if (form.containsKey("ambient_picture_fit")) {
            String fit = form.get("ambient_picture_fit");
            if (!ScreensaverPolicy.isFit(fit)) {
                return "Not saved: the fit must be fit, fill, stretch or actual.";
            }
            editor.screensaverPictureFit(fit);
            changed = true;
        }
        if (form.containsKey("ambient_shuffle")) {
            editor.screensaverShuffle(isTrue(form.get("ambient_shuffle")));
            changed = true;
        }
        if (form.containsKey("ambient_one_per_start")) {
            editor.screensaverOnePerCycle(isTrue(form.get("ambient_one_per_start")));
            changed = true;
        }
        if (form.containsKey("ambient_credit")) {
            editor.screensaverCredit(isTrue(form.get("ambient_credit")));
            changed = true;
        }
        if (form.containsKey("ambient_credit_corner")) {
            String corner = form.get("ambient_credit_corner");
            if (!ScreensaverPolicy.isCorner(corner)) {
                return "Not saved: the corner must be bottom_left, bottom_right, top_left or top_right.";
            }
            editor.screensaverCreditCorner(corner);
            changed = true;
        }
        if (changed) {
            editor.apply();
        }
        return null;
    }

    private static String selectOption(String value, String label, String current) {
        return "<option value=\"" + escapeHtml(value) + "\""
                + (value.equals(current) ? " selected" : "") + ">" + escapeHtml(label)
                + "</option>";
    }


    /**
     * How "Display off" darkens the panel, as a menu posted the moment it changes, with the
     * sentence the tablet's Display card shows under it, kept current by the stats poll. The real
     * screen-off is offered only to a device owner, the same gate the tablet applies, and an
     * ordinary install gets the tablet's one greyed choice rather than a menu of Automatic and
     * Black film that meant the same thing (2026-09-24): a disabled menu, which the poll
     * leaves alone and the settings script never posts.
     */
    private String displayOffControl() {
        String current = KioskConfig.displayOffMethodOf(context);
        boolean deviceOwner = KioskService.isDeviceOwner(context);
        StringBuilder options = new StringBuilder();
        if (deviceOwner) {
            for (String[] choice : KioskCommandDispatcher.DISPLAY_OFF_WORDS) {
                options.append(selectOption(choice[0], choice[1], current));
            }
        } else {
            options.append(selectOption(DisplayOffPolicy.FILM, KioskCommandDispatcher.wordOf(
                    KioskCommandDispatcher.DISPLAY_OFF_WORDS, DisplayOffPolicy.FILM),
                    DisplayOffPolicy.FILM));
        }
        return selectField("display-off-method", null, "Display off method",
                deviceOwner ? "display_off_method" : null, options.toString(), null, !deviceOwner)
                + "<p class=\"hint bad\" id=\"display-off-note\""
                + (KioskService.displayOffWarning(context) ? "" : " hidden") + ">"
                + (KioskService.displayOffWarning(context)
                        ? escapeHtml(KioskService.describeDisplayOff(context)) : "") + "</p>";
    }


    /**
     * The automatic-brightness switch, showing state, or nothing at all.
     *
     * <p>Omitted entirely on hardware with no ambient light sensor, because a control that cannot work
     * is worse than an absent one. The command behind it agrees:
     * {@code display.auto_brightness} is rejected with "this device has no ambient light sensor"
     * rather than silently accepted. A switch shows the state directly, and {@code statsScript}
     * keeps it honest by syncing it from every poll; it maps to exactly the setting a person
     * toggles next to the brightness slider in the notification shade,
     * {@code Settings.System.SCREEN_BRIGHTNESS_MODE}.
     */
    private String autoBrightnessControl() {
        if (!KioskService.hasLightSensor(context)) {
            return "";
        }
        return switchRow("auto-brightness", "Automatic brightness", "",
                KioskService.isAutoBrightnessOn(context));
    }

    /**
     * Why the brightness controls above do nothing, on a panel that has not been granted
     * {@code WRITE_SETTINGS}, or nothing at all once it has.
     *
     * <p>The same sentence the tablet's Display card shows, so the two surfaces describe one rule,
     * with the adb command in place of the tablet's grant button: this page cannot hand a browser
     * the Settings screen that grants an app-op, and on a wall-mounted panel a cable is often the
     * shorter route anyway. Both controls are affected and neither says so on its own: the
     * switch writes {@code SCREEN_BRIGHTNESS_MODE}, the slider writes
     * {@code SCREEN_BRIGHTNESS}, and both live in {@code Settings.System}, which has no
     * device-owner setter. Added 2026-09-07, after a freshly provisioned panel showed two dead
     * brightness controls and no surface anywhere said which single grant was missing.
     */
    private String writeSettingsHint() {
        if (KioskService.canWriteSystemSettings(context)) {
            return "";
        }
        return "<p class=\"hint\">Brightness needs the \"Modify system settings\" permission:"
                + " grant it on the panel, in Display.</p>";
    }

    /**
     * The status chip: battery, network, address and load, each with its own glyph.
     *
     * <p>The five glyphs are the same shapes {@link StatusIcon} draws on the tablet, at the same
     * 24-unit geometry, and they are monochrome for the same reason, one tint for the whole family,
     * strength carried by shape. They inherit {@code currentColor}, so the theme switch above
     * recolours them with no extra work. Keep the two sets in step when either changes.
     */
    private static String statusChip() {
        return "<div class=\"chip\">"
                + "<div class=\"row\"><span id=\"chip-battery\">--</span>"
                + BATTERY_SVG + "</div>"
                + "<div class=\"row\"><span id=\"chip-address\">--</span>"
                + ADDRESS_SVG + "</div>"
                + "<div class=\"row pair\">"
                + "<span class=\"row\"><span id=\"chip-ram\">--</span>" + MEMORY_SVG + "</span>"
                + "<span class=\"row\"><span id=\"chip-cpu\">--</span>" + CPU_SVG + "</span>"
                + "</div></div>";
    }

    private static final String SVG_OPEN =
            "<svg class=\"ico\" viewBox=\"0 0 24 24\" fill=\"none\" "
            + "stroke=\"currentColor\" stroke-width=\"1.7\" stroke-linecap=\"round\" "
            + "stroke-linejoin=\"round\" aria-hidden=\"true\">";

    /**
     * Upright cell with a nub, filled from the bottom. No charging bolt, for the same reason
     * {@code StatusIcon} has none: it is a smudge at this size, and the words beside it say which way
     * the charge is going.
     */
    private static final String BATTERY_SVG =
            "<svg class=\"ico\" id=\"chip-battery-icon\" viewBox=\"0 0 24 24\" "
            + "fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.7\" "
            + "stroke-linejoin=\"round\" role=\"img\" aria-label=\"battery\">"
            + "<rect x=\"10\" y=\"1.9\" width=\"4\" height=\"2.3\" rx=\"1\" "
            + "fill=\"currentColor\" stroke=\"none\"/>"
            + "<rect x=\"6.5\" y=\"4\" width=\"11\" height=\"18\" rx=\"3\"/>"
            + "<rect id=\"chip-battery-fill\" x=\"8.4\" y=\"20.1\" width=\"7.2\" "
            + "height=\"0\" rx=\"1\" fill=\"currentColor\" stroke=\"none\"/>"
            + "</svg>";

    /** A globe: the address is how anything else on the network reaches this panel. */
    private static final String ADDRESS_SVG = SVG_OPEN
            + "<circle cx=\"12\" cy=\"12\" r=\"9\"/><path d=\"M3 12h18\"/>"
            + "<ellipse cx=\"12\" cy=\"12\" rx=\"4.2\" ry=\"9\"/></svg>";

    /** A memory chip with its pins, the conventional glyph for RAM. */
    private static final String MEMORY_SVG = SVG_OPEN
            + "<rect x=\"6\" y=\"6\" width=\"12\" height=\"12\" rx=\"2\"/>"
            + "<rect x=\"9.5\" y=\"9.5\" width=\"5\" height=\"5\" rx=\"1\" "
            + "fill=\"currentColor\" stroke=\"none\"/>"
            + "<path stroke-width=\"1.4\" d=\"M9 3v3M12 3v3M15 3v3M9 18v3M12 18v3M15 18v3"
            + "M3 9h3M3 12h3M3 15h3M18 9h3M18 12h3M18 15h3\"/></svg>";

    /** A dial with a needle: how hard the thing is working. */
    private static final String CPU_SVG = SVG_OPEN
            + "<path d=\"M3.5 16 A8.5 8.5 0 0 1 20.5 16\"/><path d=\"M12 16 L16.6 10\"/>"
            + "<circle cx=\"12\" cy=\"16\" r=\"1.5\" fill=\"currentColor\" "
            + "stroke=\"none\"/></svg>";


    /**
     * Opens a section's form, so its Save button sits inside it.
     *
     * <p>The action carries the section's own fragment. A browser applies the fragment of the URL
     * it posted to when it renders the reply, so a save comes back to the section it was made in
     * instead of at the top of the page. {@code notice}, when it is about this section, is drawn
     * at its top for the same reason.
     */
    private static String sectionFormStart(String section, String notice, String noticeSection) {
        String message = notice != null && section.equals(noticeSection)
                ? "<p class=\"notice\">" + escapeHtml(notice) + "</p>"
                : "";
        return "<form method=\"post\" action=\"" + settingsAt(section) + "\">" + message
                + "<input type=\"hidden\" name=\"section\" value=\"" + section + "\">";
    }


    private static String sectionFormEnd(String label) {
        return "<div class=\"actions\"><button class=\"primary\" type=\"submit\">"
                + escapeHtml(label) + "</button></div></form>";
    }


    /** The same, with a second button beside Save; the caller supplies that button's markup. */
    private static String sectionFormEnd(String label, String besideHtml) {
        return "<div class=\"actions\"><button class=\"primary\" type=\"submit\">"
                + escapeHtml(label) + "</button>" + besideHtml + "</div></form>";
    }

    /** The hidden input staleFormRefusal checks on submit. */
    private static String baselineField(String value) {
        return "<input type=\"hidden\" name=\"baseline\" value=\"" + escapeHtml(value) + "\">";
    }


    /**
     * One outlined text field: the box, its label on the border, and the helper text under it in
     * the field's own slot. A text box on this page holds a machine value, an address, a host, an
     * id, a username, and a phone browser's keyboard treats a plain text box as prose: a full stop
     * ends a sentence, so it gains a space and a capital, and unknown words are corrected. The
     * tablet's own settings screen turns the same habits off with its input types (see
     * KioskActivity.themedInput, and the broker host that arrived as "test. mosquito. org"); these
     * three attributes are how a page does it, and Chrome on Android maps them onto the same
     * keyboard flags. The address boxes add {@code inputmode="url"}, which is the URL keyboard the
     * tablet's own boxes get, and the one mode every keyboard was measured to leave a full stop
     * alone in. Not {@code type="url"}: the browser would then refuse a host without a scheme
     * before the server's own normalisation could add one.
     */
    private static String field(String type, String name, String label, String value) {
        return field(type, name, label, value, "", null);
    }


    private static String field(String type, String name, String label, String value,
            String extraAttributes, String support) {
        return fieldWithId("f-" + name, type, name, label, value, extraAttributes, support);
    }

    /**
     * The same field with its id chosen by the caller, for the controls the scripts find by id.
     * The placeholder is one space so {@code :placeholder-shown} could tell an empty box from a
     * filled one; the label floats on the border either way, which is what keeps the layout still.
     */
    private static String fieldWithId(String id, String type, String name, String label,
            String value, String extraAttributes, String support) {
        return fieldWithId(id, type, name, label, value, extraAttributes, support, "");
    }

    /** The same, {@code supportAttributes} on the helper line, for a script to find it. */
    private static String fieldWithId(String id, String type, String name, String label,
            String value, String extraAttributes, String support, String supportAttributes) {
        return "<div class=\"field\"><input type=\"" + type + "\" id=\"" + id + "\""
                + (name == null ? "" : " name=\"" + name + "\"")
                // A caller's own placeholder (a fallback shown greyed) replaces the blank one;
                // a browser keeps only the first of two.
                + " value=\"" + escapeHtml(value) + "\""
                + (extraAttributes.contains(" placeholder=") ? "" : " placeholder=\" \"")
                + (type.equals("text") ? MACHINE_TEXT : "") + extraAttributes + ">"
                + "<label for=\"" + id + "\">" + escapeHtml(label) + "</label>"
                + (support == null ? "" : "<p class=\"support\"" + supportAttributes + ">"
                        + escapeHtml(support) + "</p>")
                + "</div>";
    }

    /**
     * One outlined menu, the field's shape with a chevron: {@code dataSetting} names the field it
     * posts the moment it changes (see settingScript), or null for a menu a command script drives.
     */
    private static String selectField(String id, String name, String label, String dataSetting,
            String options, String support) {
        return selectField(id, name, label, dataSetting, options, support, false);
    }

    /** The same menu, {@code disabled} where the panel offers the one choice it shows. */
    private static String selectField(String id, String name, String label, String dataSetting,
            String options, String support, boolean disabled) {
        return "<div class=\"field\"><select id=\"" + id + "\""
                + (name == null ? "" : " name=\"" + name + "\"")
                + (dataSetting == null ? "" : " data-setting=\"" + dataSetting + "\"")
                + (disabled ? " disabled" : "") + ">"
                + options + "</select><label for=\"" + id + "\">" + escapeHtml(label) + "</label>"
                + (support == null ? "" : "<p class=\"support\">" + escapeHtml(support) + "</p>")
                + "</div>";
    }

    /**
     * An on/off setting as a switch, its words at the left and the switch at the right, the
     * Material row for a setting that applies the moment it is touched. Still a checkbox to the
     * scripts, which read {@code checked} and post it.
     */
    private static String switchRow(String id, String label, String attributes, boolean on) {
        return "<label class=\"switch\"><span>" + escapeHtml(label) + "</span>"
                + "<input type=\"checkbox\" id=\"" + id + "\"" + attributes
                + (on ? " checked" : "") + "></label>";
    }

    /** A text box holding a URL or a host name: the URL keyboard on a phone, see {@link #field}. */
    private static String urlField(String name, String label, String value) {
        return field("text", name, label, value, " inputmode=\"url\"", null);
    }

    /** See {@link #field}: a text box that must not be autocorrected, capitalised or spellchecked. */
    private static final String MACHINE_TEXT =
            " autocapitalize=\"off\" autocorrect=\"off\" spellcheck=\"false\"";


    /**
     * A command as a button: outlined, since it acts now and stores nothing, or tonal where it
     * is the second action of its section. The glyph is optional and leads the label.
     */
    private static String quickAction(String command, String label, String buttonClass,
            String glyphName) {
        return "<form class=\"cmd\" method=\"post\" action=\"/api/command\">"
                + "<input type=\"hidden\" name=\"cmnd\" value=\"" + command + "\">"
                + "<button type=\"submit\"" + (buttonClass.isEmpty() ? "" : " class=\"" + buttonClass + "\"")
                + ">" + (glyphName == null ? "" : glyph(glyphName)) + escapeHtml(label)
                + "</button></form>";
    }



    private static String escapeHtml(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    /**
     * The number a caller typed, or null when it is not a number at all. Range is deliberately
     * NOT judged here: validateAdminPort owns the admin port's range and says 1024-65535, and this
     * method conflating the two answered "must be a number" for 99999, which plainly is one.
     * An absent field means "not being set" and keeps the current value.
     */
    private static Integer parsePortDigits(String value, int fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    /**
     * A TCP port, or null when the caller sent something that is not one.
     *
     * <p>Null rather than the fallback so the caller can refuse with a reason. The tablet's
     * equivalent clamps ({@code KioskActivity.parsePort}) because a stored value has to yield
     * something usable whatever is in it; a form submission is somebody asking for a specific
     * thing, and silently substituting 8080 for the 99999 they typed is the quiet substitution this
     * project keeps deleting. An absent field means "not being set" and keeps the current value.
     */
    private static Integer parsePort(String value, int fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            int port = Integer.parseInt(value.trim());
            return port >= 1 && port <= 65535 ? port : null;
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    private static int parseIntOrDefault(String value, int fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException invalid) {
            return fallback;
        }
    }

    private static Map<String, String> parseQuery(String raw) {
        Map<String, String> result = new HashMap<>();
        if (raw == null || raw.isEmpty()) {
            return result;
        }
        for (String pair : raw.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int equals = pair.indexOf('=');
            String key = equals >= 0 ? pair.substring(0, equals) : pair;
            String value = equals >= 0 ? pair.substring(equals + 1) : "";
            String decodedKey = decodeOrNull(key);
            String decodedValue = decodeOrNull(value);
            if (decodedKey != null && decodedValue != null) {
                result.put(decodedKey, decodedValue);
            }
            // A pair that will not decode is dropped rather than failing the whole request: the
            // command handlers already reject a missing argument with a useful message, which is a
            // better answer than a blanket 500 for one bad field among several.
        }
        return result;
    }

    /**
     * Percent-decodes one field, or returns null if it is malformed.
     *
     * <p>{@code URLDecoder.decode} throws {@link IllegalArgumentException} on a truncated or
     * non-hex escape, {@code "100%"}, {@code "%zz"}, {@code "abc%2"}. That used to travel all the
     * way out of the worker thread and kill the process, which a documented curl one-liner could
     * trigger just by passing a URL containing a bare {@code %}.
     */
    private static String decodeOrNull(String raw) {
        try {
            return URLDecoder.decode(raw, "UTF-8");
        } catch (java.io.UnsupportedEncodingException impossible) {
            throw new IllegalStateException(impossible);
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }

    private static Map<String, String> parseFormBody(Map<String, String> headers, byte[] body) {
        String contentType = headers.getOrDefault("content-type", "");
        if (!contentType.contains("application/x-www-form-urlencoded")) {
            return Collections.emptyMap();
        }
        return parseQuery(new String(body, StandardCharsets.UTF_8));
    }

    /**
     * Enforces a deadline across a whole request, however slowly the bytes arrive.
     *
     * <p>Uses {@code elapsedRealtime} rather than {@code nanoTime} or wall clock: it advances during
     * suspend, unlike the former, and cannot jump when the clock is set, unlike the latter. A
     * deadline that moves under either condition is one an attacker can wait out.
     */
    private static final class DeadlineInputStream extends java.io.FilterInputStream {
        private long deadlineAtMs;

        DeadlineInputStream(InputStream wrapped, int budgetMs) {
            super(wrapped);
            this.deadlineAtMs = android.os.SystemClock.elapsedRealtime() + budgetMs;
        }

        /** More time for the one request that is allowed a large body; see MAX_UPLOAD_BYTES. */
        void extend(int budgetMs) {
            deadlineAtMs = android.os.SystemClock.elapsedRealtime() + budgetMs;
        }

        private void checkDeadline() throws IOException {
            if (android.os.SystemClock.elapsedRealtime() > deadlineAtMs) {
                throw new IOException("request exceeded its deadline");
            }
        }

        @Override
        public int read() throws IOException {
            checkDeadline();
            return super.read();
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            checkDeadline();
            return super.read(buffer, offset, length);
        }
    }

    /**
     * The sentence under the web admin's own box that lets a person check the padlock: the
     * fingerprint here has to match the one the browser shows for this page.
     */
    private String certificateHint(AdminCertificate certificate) {
        if (certificate == null || !certificate.secure()) {
            // Not reached: without a certificate the server answers every request closed.
            return "";
        }
        // The sentence, then the fingerprint in a block of its own: 95 characters with no space
        // to break at, so inline in the sentence it wrapped mid-value (2026-09-23).
        return "<p class=\"hint\">"
                + escapeHtml(AdminCertificate.FINGERPRINT_SENTENCE)
                + "</p><p class=\"mono wrap\">" + escapeHtml(certificate.fingerprint) + "</p>";
    }

    /** BoringSSL's words for "that was an HTTP request, not a handshake". */
    private static boolean looksLikePlainHttp(IOException handshakeFailure) {
        // Conscrypt reports "Handshake failed" and keeps BoringSSL's reason in the cause. The
        // Conscrypt of Android 16 (updated through the Play system updates) words it "Unable to
        // parse TLS packet header" instead: measured on the Mi, 2026-10-20, where plain http://
        // got an empty reply.
        for (Throwable step = handshakeFailure; step != null; step = step.getCause()) {
            String message = String.valueOf(step.getMessage()).toUpperCase(Locale.ROOT);
            if (message.contains("HTTP_REQUEST") || message.contains("HTTP REQUEST")
                    || message.contains("WRONG_VERSION_NUMBER") || message.contains("WRONG VERSION")
                    || message.contains("UNABLE TO PARSE TLS PACKET HEADER")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Every request while no certificate can be served: 503 with the reason, and no
     * WWW-Authenticate, so a browser never offers to send the password in clear.
     */
    private void answerClosed(Socket socket) throws IOException {
        writeResponse(socket.getOutputStream(), 503, "text/plain; charset=utf-8",
                bytes(AdminCertificate.CLOSED_WITHOUT_TLS_SENTENCE),
                Collections.singletonMap("Retry-After", "30"));
    }

    /**
     * A plain-HTTP request on the HTTPS port: its bytes went into the failed handshake, so the
     * path is gone, but where it was going is not: the address it connected to, over https.
     */
    private void redirectToHttps(Socket socket) throws IOException {
        String location = TlsPresentation.redirectLocation(
                socket.getLocalAddress().getHostAddress(), boundPort);
        writeResponse(socket.getOutputStream(), 301, "text/plain",
                bytes("This panel speaks HTTPS: " + location),
                Collections.singletonMap("Location", location));
    }

    private static String readLine(InputStream input, int maxLength) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(128);
        int b;
        while ((b = input.read()) != -1) {
            if (b == '\n') {
                byte[] bytes = buffer.toByteArray();
                int length = bytes.length;
                if (length > 0 && bytes[length - 1] == '\r') {
                    length--;
                }
                return new String(bytes, 0, length, StandardCharsets.UTF_8);
            }
            buffer.write(b);
            if (buffer.size() > maxLength) {
                throw new IOException("request line too long");
            }
        }
        return buffer.size() == 0 ? null : buffer.toString("UTF-8");
    }

    /** A body past its budget; answered with 413 where the caller can, dropped otherwise. */
    private static final class BodyTooLargeException extends IOException {
        /** The length the request declared, none of which was read. */
        final int declared;

        BodyTooLargeException(String message, int declared) {
            super(message);
            this.declared = declared;
        }
    }

    /** Reads and drops {@code length} bytes of a body nobody will use. */
    private static void drain(InputStream input, int length) throws IOException {
        byte[] sink = new byte[8192];
        int left = length;
        while (left > 0) {
            int chunk = input.read(sink, 0, Math.min(sink.length, left));
            if (chunk == -1) {
                return;
            }
            left -= chunk;
        }
    }

    private static byte[] readBody(InputStream input, Map<String, String> headers, int limit)
            throws IOException {
        String lengthHeader = headers.get("content-length");
        if (lengthHeader == null) {
            return new byte[0];
        }
        long declared;
        try {
            declared = Long.parseLong(lengthHeader.trim());
        } catch (NumberFormatException invalid) {
            return new byte[0];
        }
        if (declared <= 0) {
            return new byte[0];
        }
        // Read as a long: ten digits pass the request's check, and one past an int's range read
        // as no body at all left a kept connection to take the body for the next request
        // (review, 2026-10-05).
        if (declared > limit) {
            throw new BodyTooLargeException("the request carries " + declared
                    + " bytes, the limit is " + limit, (int) Math.min(declared, Integer.MAX_VALUE));
        }
        int length = (int) declared;
        byte[] body = new byte[length];
        int read = 0;
        while (read < length) {
            int chunk = input.read(body, read, length - read);
            if (chunk == -1) {
                throw new IOException("unexpected end of body");
            }
            read += chunk;
        }
        return body;
    }

    private static void writeResponse(
            OutputStream output, int status, String contentType, byte[] body) throws IOException {
        writeResponse(output, status, contentType, body, Collections.emptyMap());
    }

    /**
     * Whether the answer to this request may keep the connection open: a GET or a POST of
     * HTTP/1.1, read whole (its headers ended on their blank line; a chunked body is not read
     * here, so it could not be), not asking to close, within {@link #KEEP_ALIVE_MAX_REQUESTS}.
     * Anything left unread would be taken for the next request (review, 2026-10-05). A HEAD would be answered with a body the
     * client does not read, so it closes, as does everything answered before its body was read.
     */
    private static boolean mayKeepOpen(String method, String[] requestLine,
            Map<String, String> headers, boolean headersWhole, int requests) {
        return (method.equals("GET") || method.equals("POST"))
                && requestLine.length > 2 && requestLine[2].trim().equals("HTTP/1.1")
                && !"close".equalsIgnoreCase(String.valueOf(headers.get("connection")).trim())
                && !headers.containsKey("transfer-encoding")
                && headersWhole && requests + 1 < KEEP_ALIVE_MAX_REQUESTS;
    }

    /** A marker in a reply's extra headers, never sent: this page may be framed by our own. */
    private static final String FRAMED_BY_SELF = "x-muralis-framed-by-self";

    private static void writeResponse(
            OutputStream output, int status, String contentType, byte[] body,
            Map<String, String> extraHeaders) throws IOException {
        StringBuilder header = new StringBuilder();
        header.append("HTTP/1.1 ").append(status).append(' ').append(reasonPhrase(status))
                .append("\r\n");
        header.append("Content-Type: ").append(contentType).append("\r\n");
        header.append("Content-Length: ").append(body.length).append("\r\n");
        // Never inside another site's frame, where a signed-in browser's clicks would pass the
        // same-origin check, and never a type guessed from the bytes. The one page shown inside
        // this server's own pages, the Bitcoin clock's preview, may be framed by them alone.
        boolean ownFrame = extraHeaders.containsKey(FRAMED_BY_SELF);
        header.append(ownFrame ? "X-Frame-Options: SAMEORIGIN\r\nContent-Security-Policy:"
                + " frame-ancestors 'self'\r\n" : "X-Frame-Options: DENY\r\n"
                + "Content-Security-Policy: frame-ancestors 'none'\r\n");
        header.append("X-Content-Type-Options: nosniff\r\n");
        boolean open = keepOpen.get();
        answered.set(true);
        header.append(open ? "Connection: keep-alive\r\nKeep-Alive: timeout="
                + (KEEP_ALIVE_IDLE_MS / 1000) + "\r\n" : "Connection: close\r\n");
        answeredOpen.set(open);
        for (Map.Entry<String, String> entry : extraHeaders.entrySet()) {
            if (!entry.getKey().equals(FRAMED_BY_SELF)) {
                header.append(entry.getKey()).append(": ").append(entry.getValue())
                        .append("\r\n");
            }
        }
        header.append("\r\n");
        // Header and body in one write, so a reply that fits leaves in a single segment. Two
        // writes let a close race the second one, which is half of how the plain-HTTP redirect
        // lost its body (2026-09-10); the other half was closing the wrong socket.
        byte[] head = header.toString().getBytes(StandardCharsets.US_ASCII);
        byte[] whole = new byte[head.length + body.length];
        System.arraycopy(head, 0, whole, 0, head.length);
        System.arraycopy(body, 0, whole, head.length, body.length);
        output.write(whole);
        output.flush();
    }

    private static String reasonPhrase(int status) {
        switch (status) {
            case 200:
                return "OK";
            case 301:
                return "Moved Permanently";
            case 400:
                return "Bad Request";
            case 401:
                return "Unauthorized";
            case 403:
                return "Forbidden";
            case 303:
                return "See Other";
            case 404:
                return "Not Found";
            case 405:
                return "Method Not Allowed";
            case 409:
                return "Conflict";
            case 413:
                return "Payload Too Large";
            case 429:
                return "Too Many Requests";
            case 503:
                return "Service Unavailable";
            default:
                return "Error";
        }
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // The socket is being discarded either way.
        }
    }

    /** How long a lingering close spends draining a client's unread bytes before giving up. */
    private static final int LINGER_DRAIN_MS = 500;

    /**
     * Closes a connection the way a web server has to: FIN first, then drain, then close.
     *
     * <p>A connection ends here after an answer that closes it, or once a kept one has gone idle,
     * and several closing answers are written <em>before</em> the request body has been read,
     * deliberately: a 401, a 403, a 413 and a 503
     * all answer without allocating megabytes. That leaves bytes in the receive buffer, and
     * closing a socket with unread received data makes the kernel send an RST instead of a FIN
     * (RFC 1122's rule, and Linux implements it), which throws away the reply the client has not
     * read yet. The client then reports a network error rather than showing the 401 that was
     * actually sent. Apache calls the answer a lingering close and nginx calls it
     * {@code lingering_close}; both do exactly this: half-close so the FIN goes out, read and
     * discard whatever the client was still sending for a bounded moment, then close for real.
     *
     * <p>Bounded at {@link #LINGER_DRAIN_MS} and at one buffer per read, because a client that
     * keeps sending must not be able to hold a worker here.
     */
    private static void lingeringClose(Socket socket) {
        try {
            if (socket.isClosed()) {
                return;
            }
            try {
                socket.shutdownOutput();
            } catch (IOException | UnsupportedOperationException noHalfClose) {
                // A TLS socket refuses this; its own close already sent close_notify.
            }
            socket.setSoTimeout(LINGER_DRAIN_MS);
            byte[] scratch = new byte[4096];
            long deadline = android.os.SystemClock.elapsedRealtime() + LINGER_DRAIN_MS;
            InputStream in = socket.getInputStream();
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                if (in.read(scratch) < 0) {
                    break;
                }
            }
        } catch (IOException | RuntimeException done) {
            // A timeout, a reset from the other side, or a stream already gone. Either way the
            // reply has had its chance to leave and there is nothing else to wait for.
        }
        closeQuietly(socket);
    }
}
