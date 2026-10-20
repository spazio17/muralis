/*
 * Copyright 2026 Muralis contributors
 * Fair Core License, FCL-1.0-ALv2: see LICENSE.md at the repository root.
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
     * refused, so "post back the shape GET gives" failed (review, 2026-10-01).
     */
    private static final int MAX_DOCUMENT_BYTES = 160 * 1024;
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
    private final String qrScript;
    private final String pageCss;


    private final Context context;
    private final KioskService kioskService;

    private ServerSocket serverSocket;
    /**
     * The certificate served, one object read once wherever it is used, so a reload never pairs
     * one certificate's words with another's fingerprint (review, 2026-10-05). Its socket factory
     * is null when the stored key cannot be read yet, before the first unlock, or HTTPS could not
     * be set up at all (see AdminCertificate); then the server speaks plain HTTP as it did before
     * 2026-09-09, and says so. Otherwise every accepted connection is expected to start a TLS
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
        playlistScript = script(R.raw.admin_playlists);
        menuScript = script(R.raw.admin_menu);
        fleetScript = script(R.raw.admin_fleet);
        qrScript = script(R.raw.admin_qr);
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
                // already serving stays, never a fall to plain HTTP mid-life, and the read is
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
        Log.i(TAG, "Web admin now serves " + (!loaded.secure() ? "plain HTTP"
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
                plain = socket.getInputStream();
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
                if (requestPath.equals("/api/fleet/screenshot")
                        || requestPath.equals("/api/fleet/identify")) {
                    input.extend(FLEET_PROXY_DEADLINE_MS);
                    deadline.cancel(false);
                    deadline = connectionDeadlines.schedule(() -> closeQuietly(socket),
                            FLEET_PROXY_DEADLINE_MS, TimeUnit.MILLISECONDS);
                }
                byte[] body;
                try {
                    boolean order = method.equals("POST")
                            && requestPath.equals("/api/playlists/order");
                    boolean document = method.equals("POST")
                            && (requestPath.equals("/api/automations")
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
                    // Whether this request came over TLS, from the connection itself: a reload
                    // can change what the server serves while a request is on its way (review,
                    // 2026-10-05).
                    route(method, target, headers, body, output, socket,
                            channel instanceof SSLSocket);
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
            // documented `?cmnd=kiosk.set_url&url=...` workflow makes easy to send by accident.
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
            OutputStream output, java.net.Socket socket, boolean overTls) throws IOException {
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
            handleCertificateUpload(headers, body, output, overTls, true);
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
            handleCertificateUpload(headers, body, output, overTls, false);
        } else if (path.equals("/mqtt-certificate") && method.equals("POST")) {
            handleMqttClientUpload(headers, body, output, overTls);
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
                // Back to Dashboard, which now says what the panel shows.
                writeResponse(output, 303, "text/plain", bytes("saved"),
                        Collections.singletonMap("Location", settingsAt("dashboard")));
            } else {
                writeResponse(output, 400, "text/html; charset=utf-8",
                        bytes(buildAppLockPage(refused, false)));
            }
        } else if (path.equals("/alerts") && method.equals("GET")) {
            writeResponse(output, 200, "text/html; charset=utf-8", bytes(buildAlertsPage(null)));
        } else if (path.equals("/alerts") && method.equals("POST")) {
            String refused = saveAlerts(parseFormBody(headers, body));
            writeResponse(output, refused == null ? 200 : 400, "text/html; charset=utf-8",
                    bytes(buildAlertsPage(refused == null ? "Saved." : refused)));
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
        } else if (path.equals("/camera-view") && method.equals("GET")) {
            writeResponse(output, 200, "text/html; charset=utf-8", bytes(buildCameraViewPage(null)));
        } else if (path.equals("/camera-view") && method.equals("POST")) {
            Map<String, String> form = parseFormBody(headers, body);
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
                    bytes(buildFleetPage(null, queryValue(query, "scope"))));
        } else if (path.equals("/fleet") && method.equals("POST")) {
            Map<String, String> form = parseFormBody(headers, body);
            String refused = saveFleetPage(form);
            // Drawn again for the scope the person was looking at, whichever form posted.
            writeResponse(output, 200, "text/html; charset=utf-8",
                    bytes(buildFleetPage(refused, form.getOrDefault("scope",
                            queryValue(query, "scope")))));
        } else if (path.equals("/ambient") && method.equals("GET")) {
            writeResponse(output, 200, "text/html; charset=utf-8",
                    bytes(buildScreensaverPage(null, browseTarget(query))));
        } else if (path.equals("/playlist") && method.equals("GET")) {
            writeResponse(output, 200, "text/html; charset=utf-8",
                    bytes(buildPlaylistPage(null, queryValue(query, "id"), browseTarget(query),
                            PictureBrowser.pageSize(queryValue(query, "n")))));
        } else if (path.equals("/api/playlists/table") && method.equals("GET")) {
            // The list of playlists as a fragment, for the Ambient page's script to re-read
            // after Use, Delete or Create without the page reloading under the reader.
            writeResponse(output, 200, "text/html; charset=utf-8",
                    bytes(playlistTable(PictureLibrary.get(context).playlists().load())));
        } else if (path.equals("/api/playlists/items") && method.equals("GET")) {
            // What the playlist holds, as a fragment: a tick in the browser changes this list
            // too, and the page must not reload to say so.
            PictureLibrary itemLibrary = PictureLibrary.get(context);
            PlaylistDocument.Playlist edited = itemLibrary.playlists().load()
                    .byId(queryValue(query, "playlist"));
            writeResponse(output, 200, "text/html; charset=utf-8",
                    bytes(edited == null ? "<p class=\"hint bad\">That playlist is gone.</p>"
                            : playlistItems(edited, itemLibrary)));
        } else if (path.equals("/api/pictures/folders") && method.equals("GET")) {
            // The Folders panel as a fragment: the open mark and the upload count move with every
            // change, and the page must not reload to say so.
            writeResponse(output, 200, "text/html; charset=utf-8",
                    bytes(folderList(browseLocation(browseTarget(query)),
                            queryValue(query, "playlist"),
                            PictureBrowser.pageSize(queryValue(query, "n")))));
        } else if (path.equals("/api/pictures/content") && method.equals("GET")) {
            // The open folder as a fragment. A GET because it changes nothing, which is also what
            // lets a folder be reached by its address again.
            writeResponse(output, 200, "text/html; charset=utf-8",
                    bytes(folderContent(browseTarget(query), queryValue(query, "playlist"),
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
                            form.get("playlist"), form.get("uri"), selected);
            answerPictureChange(query, form, refusal == null ? "Playlist updated."
                    : refused("Not changed", refusal), refusal == null, output);
        } else if (path.startsWith("/api/playlists") && method.equals("POST")) {
            handlePlaylistChange(path, query, parseFormBody(headers, body), output);
        } else if (path.equals("/api/playlists") && method.equals("GET")) {
            writeResponse(output, 200, "application/json; charset=utf-8",
                    bytes(playlistsJson()));
        } else if (path.equals("/api/command") && method.equals("POST")) {
            handleCommand(method, query, headers, body, output);
        } else if (path.equals("/api/command") && method.equals("GET")) {
            // GET used to be accepted here and it was the worst hole in this surface. Commands
            // change state, Basic-auth credentials are attached by the browser automatically, and a
            // browser sends neither Origin nor a usable Referer for a subresource load, so
            // <img src="http://panel:8080/api/command?cmnd=kiosk.restart"> on any page the operator
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
                && (path.equals("/api/fleet/screenshot") || path.equals("/api/fleet/identify"))) {
            // The Fleet page's own requests, refused like the page itself without Pro.
            writeResponse(output, 403, "application/json", bytes(rejectedJson(
                    "the Fleet page is Muralis Pro. " + FleetServer.proLine(context))));
        } else if (path.equals("/api/fleet/screenshot") && method.equals("GET")) {
            serveFleetScreenshot(queryValue(query, "panel"), output);
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
            // cmnd=kiosk.reload'` still works untouched, while the page's own forms and fetch send
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
            if (url == null && "kiosk.open_url".equals(command)) {
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
            // reaches storage and the tablet but not Home Assistant, whose switch then disagrees
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
                String url = form.getOrDefault("dashboard_url", fresh.dashboardUrl).trim();
                // The dispatcher's rules, not local ones: this box used to store what
                // kiosk.set_url would refuse with a reason, so a schemeless address was retried
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
                    // command path (kiosk.set_url) always did both. Route through the same method
                    // the dispatcher uses, so the two paths cannot disagree again.
                    kioskService.setDashboardUrl(url);
                }
                return null;
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
                // The panel's name: what Home Assistant and MQTT call it, out of the Dashboard
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
        return "1".equals(value) || "true".equalsIgnoreCase(value) || "on".equalsIgnoreCase(value);
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
            return "Not saved: the settings and launcher sequences must be different, or the "
                    + "settings gesture becomes unreachable.";
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
        paths.put("stats", "<path d=\"M4 20V10M10 20V4M16 20v-7M22 20H2\"/>");
        paths.put("bolt", "<path d=\"M13 2L4 14h7l-1 8 9-12h-7z\"/>");
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
                "What the panel shows"});
        sections.add(new String[] {"display", "disp", "Display", displaySummary(), displayBody(),
                "Screen"});
        sections.add(new String[] {"ambient", "saver", "Ambient", screensaverSummary(),
                screensaverBody(), "Screen"});
        sections.add(new String[] {"cameras", "cameras", "Cameras",
                Cameras.summary(KioskConfig.camerasOf(context)), camerasBody(), "Screen"});
        sections.add(new String[] {"sensors", "sensors", "Sensors", sensorsSummary(),
                sensorsSectionBody(), "Automation"});
        sections.add(new String[] {"automations", "automations", "Automations", automationsSummary(),
                automationsSectionBody(), "Automation"});
        sections.add(new String[] {"integrations", "integrations", "Integrations", "Coming soon",
                integrationsBody(), "Automation"});
        sections.add(new String[] {"mqtt", "mqtt", "MQTT",
                config.mqttHost.isEmpty() ? "Not configured" : config.mqttHost + ":" + config.mqttPort,
                mqttBody(config, notice, noticeSection), "Connections"});
        sections.add(new String[] {"webadmin", "web", "Web admin",
                "Port " + config.httpPort + (!secure() ? " · HTTP" : " · HTTPS")
                        + (config.webAdminEnabled ? "" : " · off"),
                webAdminBody(config, notice, noticeSection), "Connections"});
        sections.add(new String[] {"fleet", "fleet", "Fleet", FleetServer.summaryOf(config.fleetPort),
                fleetBody(config, notice, noticeSection), "Connections"});
        sections.add(new String[] {"sequences", "esc", "Way out",
                sequencesSummary(config), sequencesBody(config, notice, noticeSection),
                "Security"});
        sections.add(new String[] {"panel", "panel", "About this panel", config.deviceId,
                panelBody(config, notice, noticeSection), ""});

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
        // An app instead of the page says so, as "The panel shows" does.
        if (!KioskConfig.appLockOf(context).isEmpty()) {
            return "App: " + KioskConfig.appLockLabelOf(context);
        }
        if (!KioskConfig.dashboardCamerasOf(context).isEmpty()) {
            return "Cameras: " + KioskConfig.dashboardCamerasOf(context);
        }
        String host = config.dashboardUrl.replaceFirst("^[a-z]+://", "").replaceFirst("/.*$", "");
        return host.isEmpty() ? "No dashboard yet" : host;
    }

    private String sequencesSummary(KioskConfig config) {
        if (config.settingsSequence.isEmpty() || config.launcherSequence.isEmpty()) {
            return "Not recorded yet";
        }
        return config.settingsSequence + " · " + config.launcherSequence
                + (KioskConfig.escapePinSet(context) ? " · PIN" : "");
    }

    private String displaySummary() {
        String orientation = KioskConfig.orientationOf(context);
        // An ordinary install has one way to darken, the film, whatever is stored: it offers
        // Black film alone, greyed (2026-09-24), and the summary must not say "automatic" beside it.
        String method = KioskService.isDeviceOwner(context) ? KioskConfig.displayOffMethodOf(context)
                : DisplayOffPolicy.FILM;
        return (KioskConfig.ORIENTATION_AUTO.equals(orientation) ? "Auto-rotate"
                : KioskConfig.ORIENTATION_PORTRAIT.equals(orientation) ? "Portrait" : "Landscape")
                + " · "
                + (DisplayOffPolicy.SLEEP.equals(method) ? "screen off"
                        : DisplayOffPolicy.FILM.equals(method) ? "black film" : "automatic");
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
        return "<li class=\"two\"><a class=\"rowlink plain\" href=\"" + href + "\">"
                + "<span class=\"lead\">" + glyph(glyphName) + "</span><span class=\"text\">"
                + "<span class=\"h\">" + escapeHtml(head) + "</span><span class=\"s\""
                + (subId == null ? "" : " id=\"" + subId + "\"") + ">" + mark + escapeHtml(sub)
                + "</span></span></a>"
                + (trail.isEmpty() ? "" : "<span class=\"trail\">" + trail + "</span>") + "</li>";
    }

    /**
     * The mark before a rule's sentence when its sensor is not running while the display
     * sleeps, the panel's own: the sensor with a slash, small, in the warning colour.
     */
    private String stopsMark(String sensorId) {
        // The words for a screen reader too, not only in a tooltip (review, 2026-10-01).
        return KioskService.stopsWhileAsleep(context, sensorId)
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
        String key = Sensors.listKeys(KioskRuntimeState.sensors(),
                Automations.toJson(KioskConfig.automationsOf(context), true)).optString(kind, "");
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
        org.json.JSONArray rules = Automations.toJson(KioskConfig.automationsOf(context), true);
        StringBuilder rows = new StringBuilder();
        for (int index = 0; index < rules.length(); index++) {
            org.json.JSONObject rule = rules.optJSONObject(index);
            if (rule == null || !rule.optBoolean("enabled")) {
                continue;
            }
            String id = rule.optString("id", "");
            rows.append(navRow(listRow("automations", rule.optString("name", ""),
                    null, rule.optString("sentence", ""), null,
                    "<input type=\"checkbox\" class=\"sw\" id=\"automation-" + id
                            + "-home\" data-setting=\"automation_" + id + "\" aria-label=\""
                            + escapeHtml(rule.optString("name", "")) + "\" checked>", false,
                    stopsMark(rule.optString("sensor", ""))),
                    id, false));
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
                + "<ul class=\"list gap\">"
                + "<li class=\"link\"><a class=\"rowlink\" href=\"/stats\"><span class=\"lead\">"
                + glyph("stats") + "</span><span class=\"text\"><span class=\"h\">System stats and log"
                + "</span><span class=\"s\">" + (config.statsOverlay ? "Overlay on" : "Overlay off")
                + "</span></span></a><span class=\"trail\">" + glyph("next") + "</span></li>"
                // The screen as the wall shows it, and the diagnostics file, beside the log, as
                // the camera's page links its snapshot: the address wraps so it can be copied.
                + linkRow("/api/screenshot", "open", "Screenshot",
                        KioskRuntimeState.httpAdminAddress() + "/api/screenshot", null, "")
                        .replace("<span class=\"s\"", "<span class=\"s wrap\"")
                + "<li class=\"link\"><a class=\"rowlink\" href=\"/settings-file\"><span class=\"lead\">"
                + glyph("download") + "</span><span class=\"text\"><span class=\"h\">Settings file"
                + "</span><span class=\"s\">Export, import, a setup QR for a new tablet</span></span>"
                + "</a><span class=\"trail\">" + glyph("next") + "</span></li>"
                + linkRow("/api/diagnostics", "download", "Download diagnostics",
                        KioskRuntimeState.httpAdminAddress() + "/api/diagnostics", null, "")
                        .replace("<span class=\"s\"", "<span class=\"s wrap\"")
                + "<li class=\"link\"><a class=\"rowlink\" href=\"" + REPOSITORY_URL
                + "\" target=\"_blank\" rel=\"noopener\"><span class=\"lead\">" + glyph("info")
                + "</span><span class=\"text\"><span class=\"h\">"
                + "Muralis " + escapeHtml(appVersionName()) + "</span><span class=\"s\">"
                + REPOSITORY_URL.replaceFirst("^https://", "") + "</span></span></a>"
                + "<span class=\"trail\">" + glyph("open") + "</span></li>"
                + "<li class=\"link\"><a class=\"rowlink\" href=\"/privacy\"><span class=\"lead\">"
                + glyph("open") + "</span><span class=\"text\">"
                + "<span class=\"h\">" + escapeHtml(context.getString(R.string.privacy_policy_title))
                + "</span></span></a><span class=\"trail\">" + glyph("next") + "</span></li>"
                + "<li class=\"link\"><a class=\"rowlink\" href=\"/terms\"><span class=\"lead\">"
                + glyph("open") + "</span><span class=\"text\">"
                + "<span class=\"h\">" + escapeHtml(context.getString(R.string.terms_title))
                + "</span></span></a><span class=\"trail\">" + glyph("next") + "</span></li>"
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
     * Home Assistant, as the panel's own page has it.
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
     * editor of the chosen rule, of {@code editing} when a save came back refused, or of a new
     * one. As a detail page it is the editor alone below 840 px.
     */
    private String buildAutomationsPage(String notice, String selectedId,
            Automations.Rule editing, boolean detailPage) {
        return buildAutomationsPage(notice, selectedId, editing, detailPage, null, null);
    }

    private String buildAutomationsPage(String notice, String selectedId,
            Automations.Rule editing, boolean detailPage, String typedFrom, String typedTo) {
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
                    stopsMark(each.sensor)),
                    each.id, each.id.equals(rule.id)));
        }
        String nav = "<section class=\"card nav\"><div class=\"cardhead\"><h2>Automations</h2>"
                + "<span class=\"count\" id=\"sum-automations\">" + escapeHtml(automationsSummary())
                + "</span></div>"
                + (rows.length() == 0 ? "<p class=\"hint\">No automations yet.</p>"
                        : "<ul class=\"list seg\">" + rows + "</ul>")
                + "<div class=\"actions\"><a class=\"btn tonal\" href=\"/automation\">" + glyph("plus")
                + "Add</a></div></section>";
        boolean fresh = rule.id.isEmpty();
        // One editor per rule and one for a new rule, each in its pane, so choosing at the left
        // is a switch of panes; the rule being edited stands in for its stored twin.
        StringBuilder detail = new StringBuilder();
        for (Automations.Rule each : rules) {
            boolean shown = each.id.equals(rule.id);
            detail.append("<div class=\"pane\" data-id=\"").append(escapeHtml(each.id)).append("\"")
                    .append(shown ? "" : " hidden").append(">")
                    .append(shown ? automationEditor(rule, typedFrom, typedTo) : automationEditor(each))
                    .append("</div>");
        }
        detail.append("<div class=\"pane\" data-id=\"\"").append(fresh ? "" : " hidden").append(">")
                .append(fresh ? automationEditor(rule, typedFrom, typedTo)
                        : automationEditor(new Automations.Rule())).append("</div>");
        return pageStart(!detailPage ? "Automations" : fresh ? "New automation" : "Edit automation",
                null, detailPage ? "/automations" : settingsAt("automations"))
                + (notice == null ? "" : "<p class=\"notice\">" + escapeHtml(notice) + "</p>")
                + listDetail("automations", detailPage, nav, detail.toString()) + pageEnd();
    }

    /** The editor as a page of its own, the list beside it when wide. */
    private String buildAutomationPage(Automations.Rule rule, String notice) {
        return buildAutomationsPage(notice, rule.id, rule, true, null, null);
    }

    private String buildAutomationPage(Automations.Rule rule, String notice, String typedFrom,
            String typedTo) {
        return buildAutomationsPage(notice, rule.id, rule, true, typedFrom, typedTo);
    }

    /** The editor: Name, When, Then, Only, as the products it copies write an automation. */
    private String automationEditor(Automations.Rule rule) {
        return automationEditor(rule, null, null);
    }

    /**
     * The panel's delete question (KioskActivity.confirmDelete) in one string for the browser's
     * confirm(), which has no title of its own: the title, a blank line, the text.
     */
    private static String deleteQuestion(String name) {
        String quoted = "\"" + name + "\"";
        return "Delete " + quoted + "?\n\n" + quoted + " will be deleted.";
    }

    /**
     * The same, with the times as they were typed where a refused save is shown again: the
     * rule holds no window then, and the boxes must not fall back to 07:00 and 22:00.
     */
    private String automationEditor(Automations.Rule rule, String typedFrom, String typedTo) {
        boolean fresh = rule.id.isEmpty();
        // The stored rule as the baseline, so a page left open cannot overwrite a change made
        // on the panel or in Home Assistant meanwhile: the Save-button forms' rule.
        Automations.Rule stored = Automations.find(KioskConfig.automationsOf(context), rule.id);
        String baseline = stored == null ? "" : Automations.baseline(stored);
        // Ids of its own per editor: several stand on one page, one per rule.
        String sfx = "-" + (fresh ? "new" : escapeHtml(rule.id));
        org.json.JSONObject block = KioskRuntimeState.sensors();
        StringBuilder sensorOptions = new StringBuilder();
        StringBuilder events = new StringBuilder();
        // A new rule starts on the proximity sensor where there is one, as on the panel, so
        // the two surfaces offer the same first rule; a chosen option is always rendered, so
        // the stats poll never takes a fresh editor for one being edited (review, 2026-10-01).
        String chosenSensor = rule.sensor;
        if (chosenSensor.isEmpty()) {
            org.json.JSONObject proximity = block.optJSONObject("proximity");
            chosenSensor = proximity != null && proximity.optBoolean("available") ? "proximity"
                    : firstAvailableSensor(block);
        }
        boolean offered = false;
        for (Sensors.Def def : Sensors.ALL) {
            java.util.List<Automations.Event> list = Automations.EVENTS.get(def.id);
            org.json.JSONObject one = block.optJSONObject(def.id);
            if (list == null || one == null || !one.optBoolean("available")) {
                continue;
            }
            offered |= def.id.equals(chosenSensor);
            sensorOptions.append(selectOption(def.id, def.name, chosenSensor));
            // One event is a line of text, not a choice: the radio is there for the form and
            // hidden by the stylesheet (label.radio.one).
            String one_ = list.size() == 1 ? " one" : "";
            events.append("<div class=\"radios events\" data-sensor=\"").append(def.id)
                    .append("\" role=\"radiogroup\" aria-label=\"").append(escapeHtml(def.name))
                    .append(" events\">");
            for (Automations.Event event : list) {
                // The only event of its sensor is ticked, so a form posted without scripting
                // carries it: the radio is hidden and could not be picked (review, 2026-10-01).
                boolean checked = def.id.equals(rule.sensor) && event.id.equals(rule.event)
                        || list.size() == 1;
                events.append("<label class=\"radio").append(one_).append("\"><input type=\"radio\" name=\"event_")
                        .append(def.id).append("\" value=\"").append(event.id).append("\"")
                        .append(checked ? " checked" : "")
                        .append(" data-level=\"").append(event.levelUnit == null ? "" : event.levelUnit)
                        .append("\" data-holds=\"").append(event.holds ? "1" : "")
                        .append("\" data-tag=\"").append(event.tag ? "1" : "").append("\">")
                        .append(escapeHtml(event.label)).append("</label>");
            }
            events.append("</div>");
        }
        // The clock, which every panel has: "At" a time of day.
        offered |= Automations.TIME.equals(chosenSensor);
        sensorOptions.append(selectOption(Automations.TIME, Automations.TIME_NAME, chosenSensor));
        events.append("<div class=\"radios events\" data-sensor=\"").append(Automations.TIME)
                .append("\" role=\"radiogroup\" aria-label=\"").append(Automations.TIME_NAME)
                .append(" events\"><label class=\"radio one\"><input type=\"radio\" name=\"event_")
                .append(Automations.TIME).append("\" value=\"at\" checked data-level=\"")
                .append(Automations.TIME_UNIT).append("\" data-holds=\"\" data-tag=\"\">At</label>")
                .append("</div>");
        // A camera's event, once there is a camera or a group to wait for.
        java.util.List<Cameras.Camera> cameraList = KioskConfig.camerasOf(context);
        java.util.List<Cameras.Group> groupList = KioskConfig.cameraGroupsOf(context);
        boolean anyCamera = !cameraList.isEmpty() || !groupList.isEmpty();
        if (anyCamera || Automations.CAMERAS.equals(rule.sensor)) {
            offered |= Automations.CAMERAS.equals(chosenSensor);
            sensorOptions.append(selectOption(Automations.CAMERAS, Automations.CAMERAS_NAME,
                    chosenSensor));
            events.append("<div class=\"radios events\" data-sensor=\"").append(Automations.CAMERAS)
                    .append("\" role=\"radiogroup\" aria-label=\"").append(Automations.CAMERAS_NAME)
                    .append(" events\"><label class=\"radio one\"><input type=\"radio\" name=\"event_")
                    .append(Automations.CAMERAS).append("\" value=\"triggered\" checked data-level=\"\""
                            + " data-holds=\"\" data-tag=\"1\" data-camera=\"1\">")
                    .append("A camera is triggered</label></div>");
        }
        if (!offered && !rule.sensor.isEmpty()) {
            // A rule on a sensor this device lacks keeps it: saving the rule for its name must
            // not move it to the first sensor of the menu (review, 2026-10-01).
            Sensors.Def missing = Sensors.byId(rule.sensor);
            sensorOptions.append(selectOption(rule.sensor,
                    (missing == null ? rule.sensor : missing.name) + " (not on this device)",
                    rule.sensor));
        }
        String chosenAction = rule.action.isEmpty() ? "display_on" : rule.action;
        StringBuilder actionOptions = new StringBuilder();
        for (Automations.Action action : Automations.ACTIONS) {
            boolean camera = action.id.equals("camera");
            // Show a camera once there is one to show.
            if (camera && !anyCamera && !camera(chosenAction)) {
                continue;
            }
            actionOptions.append("<option value=\"").append(action.id).append("\"")
                    .append(action.id.equals(chosenAction) ? " selected" : "")
                    .append(" data-argument=\"")
                    .append(action.argumentLabel == null || camera ? ""
                            : escapeHtml(action.argumentLabel))
                    .append("\" data-shows=\"").append(Automations.shows(action.id) ? "1" : "")
                    .append("\" data-camera=\"").append(camera ? "1" : "")
                    .append("\">").append(escapeHtml(action.label)).append("</option>");
        }
        String waitedFor = Automations.CAMERAS.equals(rule.sensor) ? rule.tag : "";
        String shown = camera(rule.action) ? rule.argument : "";
        // Every tag read lately, by its id, newest first, as the panel's own editor has it.
        StringBuilder tags = new StringBuilder(selectOption("", "Any tag", rule.tag));
        java.util.Set<String> tagsRead = KioskConfig.seenTags(context).keySet();
        for (String tag : tagsRead) {
            tags.append(selectOption(tag, tag, rule.tag));
        }
        if (!rule.tag.isEmpty() && !tagsRead.contains(rule.tag)) {
            tags.append(selectOption(rule.tag, rule.tag, rule.tag));
        }
        return "<form method=\"post\" action=\"/api/automations/save\" class=\"automation\" id=\"editor"
                + sfx + "\">"
                + "<input type=\"hidden\" name=\"id\" value=\"" + escapeHtml(rule.id) + "\">"
                + baselineField(baseline)
                + "<section class=\"card\">"
                // Words, not a machine value: capitals and the keyboard's corrections stay on.
                + fieldWithId("a-name" + sfx, "text", "name", "Name", rule.name, "", null)
                        .replace(MACHINE_TEXT, "")
                + "</section>"
                + "<section class=\"card gap\"><h2>When</h2>"
                + selectField("a-sensor" + sfx, "sensor", "Sensor", null, sensorOptions.toString(), null)
                + stopsNote(rule.sensor)
                + events
                + fieldWithId("a-level" + sfx, "number", "level", "Level",
                        Double.isNaN(rule.level) ? "" : Automations.number(rule.level),
                        " step=\"any\" min=\"0\"", null)
                + fieldWithId("a-at" + sfx, "time", "at", "At",
                        Automations.TIME.equals(rule.sensor) && !Double.isNaN(rule.level)
                                ? Automations.clock((int) rule.level) : "07:00", "", null)
                + fieldWithId("a-minutes" + sfx, "number", "minutes", "For (minutes)",
                        rule.minutes == 0 ? "" : Integer.toString(rule.minutes),
                        " min=\"0\" max=\"" + Automations.MAX_MINUTES + "\"", null)
                + selectField("a-tag" + sfx, "tag", "Tag", null, tags.toString(), null)
                + selectField("a-camera" + sfx, "camera_tag", "Camera or group", null,
                        cameraOptions(cameraList, groupList, waitedFor), null)
                + fieldWithId("a-cooldown" + sfx, "number", "cooldown",
                        "Not again within (seconds)", Integer.toString(rule.cooldown),
                        " min=\"0\" max=\"" + Automations.MAX_COOLDOWN + "\"", null)
                + "</section>"
                + "<section class=\"card\"><h2>Then</h2>"
                + selectField("a-action" + sfx, "action", "Action", null, actionOptions.toString(), null)
                + fieldWithId("a-argument" + sfx, "text", "argument", "Sentence",
                        camera(rule.action) ? "" : rule.argument, "", null)
                        .replace(MACHINE_TEXT, "")
                + selectField("a-target" + sfx, "target", "Camera or group", null,
                        cameraOptions(cameraList, groupList, shown), null)
                + selectField("a-show" + sfx, "show", "Show as", null,
                        selectOption(Automations.SHOW_POPUP, "Pop-up", rule.show)
                                + selectOption(Automations.SHOW_FULLSCREEN, "Full screen", rule.show),
                        null)
                + fieldWithId("a-seconds" + sfx, "number", "seconds",
                        "For (seconds, 0 = until touched or closed)", Integer.toString(rule.seconds),
                        " min=\"0\" max=\"" + Popup.MAX_SECONDS + "\"", null)
                + switchRow("a-chime" + sfx, "Chime", " name=\"chime\" value=\"1\"", rule.chime)
                + "</section>"
                + "<section class=\"card\"><h2>Only</h2>"
                + switchRow("a-only" + sfx, "Between these times", " name=\"only\" value=\"1\"",
                        rule.windowed())
                + "<div class=\"two\">"
                + fieldWithId("a-from" + sfx, "time", "only_from", "From",
                        typedFrom != null ? typedFrom
                                : rule.windowed() ? Automations.clock(rule.onlyFrom) : "07:00", "", null)
                + fieldWithId("a-to" + sfx, "time", "only_to", "To",
                        typedTo != null ? typedTo
                                : rule.windowed() ? Automations.clock(rule.onlyTo) : "22:00", "", null)
                + "</div></section>"
                // One row, as the panel lays it out (2026-09-28): Delete at the start,
                // Cancel then Save at the end, ordered by the stylesheet. Save is the form's
                // first submit button in the DOM, so Enter in a box saves (review of
                // 2026-09-27: Enter in the Name box used to post to Delete). Delete asks first.
                + "<div class=\"actions editor\">"
                + "<button type=\"submit\" class=\"primary\">Save</button>"
                + "<a class=\"btn text\" href=\"/automations\">Cancel</a>"
                + (fresh ? "" : "<button type=\"submit\" class=\"danger\""
                        + " formaction=\"/api/automations/delete\""
                        + " data-confirm=\"" + escapeHtml(deleteQuestion(
                                stored == null ? rule.name : stored.name)) + "\">Delete</button>")
                + "</div></form>";
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
     * Under the editor's sensor, for a sensor whose test found it not running while the
     * display sleeps, the panel's own note; admin_automation.js follows the menu by the ids
     * the list carries.
     */
    private String stopsNote(String chosen) {
        StringBuilder silent = new StringBuilder();
        for (Sensors.Def def : Sensors.ALL) {
            if (KioskService.stopsWhileAsleep(context, def.id)) {
                silent.append(silent.length() == 0 ? "" : ",").append(def.id);
            }
        }
        return "<ul class=\"list stopsnote" + (KioskService.stopsWhileAsleep(context, chosen)
                ? "" : " gone") + "\" data-silent=\"" + silent + "\">"
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
            document.put("rules", Automations.toJson(KioskConfig.automationsOf(context), false));
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
        Automations.Rule rule = existing == null ? new Automations.Rule() : existing.copy();
        if (existing != null) {
            String stale = staleFormRefusal(form, Automations.baseline(existing));
            if (stale != null) {
                writeResponse(output, 200, "text/html; charset=utf-8",
                        bytes(buildAutomationPage(existing, stale)));
                return;
            }
        }
        rule.name = form.getOrDefault("name", "");
        rule.sensor = form.getOrDefault("sensor", "");
        rule.event = form.getOrDefault("event_" + rule.sensor, "");
        rule.level = Automations.TIME.equals(rule.sensor)
                ? timeLevel(form.get("at")) : parseLevel(form.get("level"));
        rule.minutes = parseMinutes(form.get("minutes"));
        rule.tag = (Automations.CAMERAS.equals(rule.sensor) ? form.getOrDefault("camera_tag", "")
                : form.getOrDefault("tag", "")).trim();
        String cooldown = form.getOrDefault("cooldown", "").trim();
        rule.cooldown = cooldown.isEmpty() ? 0 : cooldown.matches("\\d{1,5}")
                ? Integer.parseInt(cooldown) : -1;
        rule.action = form.getOrDefault("action", "");
        rule.argument = camera(rule.action) ? form.getOrDefault("target", "")
                : form.getOrDefault("argument", "");
        String seconds = form.getOrDefault("seconds", "").trim();
        rule.seconds = seconds.isEmpty() ? Popup.DEFAULT_SECONDS : seconds.matches("\\d{1,4}")
                ? Integer.parseInt(seconds) : -1;
        rule.chime = isTrue(form.getOrDefault("chime", "0"));
        rule.show = form.getOrDefault("show", Automations.SHOW_POPUP);
        boolean only = isTrue(form.getOrDefault("only", "0"));
        String typedFrom = form.getOrDefault("only_from", "");
        String typedTo = form.getOrDefault("only_to", "");
        rule.onlyFrom = only ? Automations.minutesOf(typedFrom) : -1;
        rule.onlyTo = only ? Automations.minutesOf(typedTo) : -1;
        if (only && (rule.onlyFrom < 0 || rule.onlyTo < 0)) {
            // Shown again with the times as typed and the window switch on, so the page can be
            // corrected rather than filled in again.
            rule.onlyFrom = Math.max(rule.onlyFrom, 0);
            rule.onlyTo = rule.onlyTo < 0 ? 24 * 60 - 1 : rule.onlyTo;
            writeResponse(output, 200, "text/html; charset=utf-8", bytes(
                    buildAutomationPage(rule, "Not saved: the times must be like 07:00.",
                            typedFrom, typedTo)));
            return;
        }
        String problem = Automations.validate(rule, Sensors.names());
        if (problem != null) {
            // Still without an id when it is new, so the page it goes back to is the one for
            // a new rule, without a Delete for a rule that was never stored.
            writeResponse(output, 200, "text/html; charset=utf-8",
                    bytes(buildAutomationPage(rule, "Not saved: " + problem + ".")));
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
                                    + Automations.MAX_RULES + " automations.")));
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
            KioskConfig.storeAutomations(context, rules);
        }
        KioskService.refreshSensorsSoon(context);
        writeResponse(output, 303, "text/plain", bytes("saved"),
                Collections.singletonMap("Location", "/automations?id=" + rule.id));
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
        return pageStart("System stats and log", null, settingsAt("panel"))
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

    private String dashboardBody(KioskConfig config, String notice, String noticeSection) {
        // What the panel shows, first and as a choice, as on the panel (the UI round,
        // 2026-10-15): a web page, its address and its options below, or an app, App lock,
        // chosen on its own page. "A web page" switches App lock off and comes back here.
        String app = KioskConfig.appLockOf(context);
        boolean showsApp = !app.isEmpty();
        // Cameras, the third answer, once there is a camera or a group to show.
        String cameras = KioskConfig.dashboardCamerasOf(context);
        boolean showsCameras = !showsApp && !cameras.isEmpty();
        boolean offerCameras = showsCameras || !KioskConfig.camerasOf(context).isEmpty();
        boolean showsPage = !showsApp && !showsCameras;
        String shows = "<p class=\"label\" id=\"opt-shows-label\">The panel shows</p>"
                + "<div class=\"segmented\" role=\"group\" aria-labelledby=\"opt-shows-label\">"
                + "<form method=\"post\" action=\"/app-lock\"><input type=\"hidden\""
                + " name=\"app_lock\" value=\"\"><button type=\"submit\" aria-pressed=\""
                + showsPage + "\">" + (showsPage ? glyph("check") : "") + "A web page</button></form>"
                + "<a href=\"/app-lock\" role=\"button\" aria-pressed=\"" + showsApp + "\">"
                + (showsApp ? glyph("check") : "") + "An app</a>"
                + (offerCameras ? "<a href=\"/camera-view\" role=\"button\" aria-pressed=\""
                        + showsCameras + "\">" + (showsCameras ? glyph("check") : "") + "Cameras</a>"
                        : "")
                + "</div>";
        if (showsCameras) {
            return shows + "<ul class=\"list\">" + linkRow("/camera-view", "cameras", cameras,
                    "Shown as the main view", null, "") + "</ul>";
        }
        if (showsApp) {
            String problem = AppLock.problem(context, app);
            return shows
                    + "<p class=\"appline\">" + escapeHtml(KioskConfig.appLockLabelOf(context))
                    + "</p>"
                    + (problem == null ? "" : "<p class=\"hint bad\">"
                            + escapeHtml(Character.toUpperCase(problem.charAt(0))
                                    + problem.substring(1)) + ".</p>")
                    + "<div class=\"actions\"><a class=\"onward\" href=\"/app-lock\">Choose the"
                    + " app</a></div>";
        }
        // The fleet shares the address, not the choice above it, so its line stands here.
        return shows + sharedLine("dashboard") + sectionFormStart("dashboard", notice, noticeSection)
                // Each Save box carries the values it was rendered from, so a submit from a page
                // that has gone stale is refused instead of reverting a newer change; see
                // staleFormRefusal. The baseline strings here must mirror saveSettings exactly.
                + baselineField(config.dashboardUrl)
                + urlField("dashboard_url", "Dashboard URL", config.dashboardUrl)
                // Two buttons on one input, the shape the tablet's Dashboard card has had since
                // 2026-09-07: Save stores what is typed as THE dashboard; Open once shows it until
                // the next kiosk restart and stores nothing (kiosk.open_url, never set_url). Open
                // once is a submit button with its own formaction, so with scripting unavailable
                // the browser still posts the box to /api/command (which reads dashboard_url for
                // it, see handleCommand); with scripting, commandScript intercepts the click.
                + sectionFormEnd("Save",
                        "<button type=\"submit\" class=\"tonal\" id=\"open-once\" name=\"cmnd\" "
                                + "value=\"kiosk.open_url\" formaction=\"/api/command\" "
                                + "formmethod=\"post\">Open once</button>")
                + "<div class=\"actions\"><a class=\"onward\" href=\"/page-options\">Page options"
                + "</a></div>";
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
     * App lock, a page of its own as on the panel: Off or one installed app, and
     * whether Muralis may display over other apps, which only the panel itself can allow.
     */
    private String buildAppLockPage(String notice, boolean withSystem) {
        StringBuilder html = new StringBuilder(pageStart("App lock", null,
                settingsAt("dashboard")));
        if (notice != null) {
            html.append("<p class=\"notice\">").append(escapeHtml(notice)).append("</p>");
        }
        String chosen = KioskConfig.appLockOf(context);
        html.append("<form method=\"post\" action=\"/app-lock\" class=\"card\">"
                + "<h2>The app this panel shows</h2><p class=\"hint\">One app instead of the web"
                + " page, opened again whenever it closes. Your corner combinations work over it."
                + "</p>");
        if (!AppLock.canDrawOver(context)) {
            html.append("<p class=\"hint bad\">Muralis needs to display over other apps, or"
                    + " your corner taps cannot reach it over the app. Allow it on the panel:"
                    + " Dashboard, An app, Allow.</p>");
        }
        html.append("<p><a href=\"/app-lock").append(withSystem ? "" : "?system=1")
                .append("\">").append(withSystem ? "Hide the apps that came with the device"
                        : "Show the apps that came with the device").append("</a></p>");
        // No "Off" here: the web page is the other half of Dashboard's "The panel shows".
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

    /** Stores App lock's choice: Off, or an installed app with something to open. */
    private String saveAppLock(Map<String, String> form) {
        String chosen = form.getOrDefault("app_lock", "").trim();
        if (context.getPackageName().equals(chosen)) {
            return "Not saved: Muralis cannot lock the panel to itself.";
        }
        if (!chosen.isEmpty()
                && context.getPackageManager().getLaunchIntentForPackage(chosen) == null) {
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
                    camera.url, null, ""), id, id.equals(chosen)));
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
                + pageEnd();
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
                        " inputmode=\"url\"", "A snapshot, an MJPEG stream or a camera page.")
                + fieldWithId("c-full" + sfx, "text", "full", "Full-screen address (optional)",
                        fresh ? "" : camera.full, " inputmode=\"url\"",
                        "A bigger stream of the same camera, for full screen and zoom.")
                + fieldWithId("c-user" + sfx, "text", "user", "User",
                        login == null ? "" : login.user, "", null)
                + fieldWithId("c-password" + sfx, "password", "password", "Password", "", "",
                        login != null && !login.password.isEmpty() ? BLANK_KEEPS : null)
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

    /**
     * The panel's removal question (KioskActivity.confirmRemove) in one string for the
     * browser's confirm(): the title, a blank line, what changes.
     */
    private static String removeQuestion(String title, String consequence) {
        return title + "\n\n" + consequence;
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
                settingsAt("panel")));
        if (notice != null) {
            html.append("<p class=\"notice\">").append(escapeHtml(notice)).append("</p>");
        }
        if (preview != null) {
            html.append(preview);
        }
        html.append("<form method=\"post\" action=\"/settings-file\" class=\"card\">"
                + "<h2>Export</h2><input type=\"hidden\" name=\"what\" value=\"export\">"
                + "<p class=\"hint\">Every setting you set, the corner combinations included,"
                + " never this panel's name, keys, fleet, certificates or pictures.</p>"
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
                + "<p class=\"hint\">The file is read first; nothing changes until Apply.</p>"
                + "<div class=\"field\"><input type=\"file\" name=\"file\" accept=\".json,application/json\""
                + " id=\"settings-file-input\" aria-label=\"The settings file\"></div>"
                + field("password", "passphrase", "Passphrase, when the file carries passwords",
                        "", "", null)
                + "<div class=\"actions\"><button type=\"submit\" class=\"tonal\">Read the file"
                + "</button></div></form>");
        boolean fits = setupQrContent() != null;
        // A debug build names a download address only when it was built with one.
        boolean downloadable = KioskDeviceAdminReceiver.provisioningQr(context, new JSONObject())
                != null;
        html.append("<div class=\"card\"><h2>Setup QR for a new tablet</h2>"
                + "<p class=\"hint\">Scanned during a new tablet's first setup, it installs Muralis"
                + " as the device owner and starts it with these settings, never the passwords.</p>"
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
     * The camera view, a page of its own as App lock is: the camera or group the panel shows as
     * its main view. The panel's Camera view page is the same.
     */
    private String buildCameraViewPage(String notice) {
        StringBuilder html = new StringBuilder(pageStart("Camera view", null,
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
            html.append("<p class=\"hint\">No cameras yet. Add one in Cameras, under Screen.</p>");
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
            html.append("<p class=\"hint\">None. A page whose certificate Android does not trust"
                    + " asks first.</p>");
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
                + "<p class=\"hint\">The page may use</p><div class=\"switches\">"
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
        boolean asking = refused != null && refused.host.equalsIgnoreCase(config.mqttHost)
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
        // The client certificate: uploaded here only, since files go through the web admin, and
        // like the admin's own certificate never over plain HTTP, where its key would travel
        // in clear.
        String locked = KioskRuntimeState.httpAdminSecure() ? "" : " disabled title=\""
                + escapeHtml(AdminCertificate.UPLOAD_NEEDS_HTTPS) + "\"";
        trust.append("<p class=\"hint\">")
                .append(escapeHtml(MqttTls.clientLine(config.mqttClientName,
                        config.mqttClientUntil))).append("</p>")
                .append("<form method=\"post\" action=\"/mqtt-certificate\""
                        + " enctype=\"multipart/form-data\">")
                .append(fileRow("mqtt-client-file", "certificate", "Client certificate", locked))
                .append(fileRow("mqtt-client-key", "key", "Its private key", locked))
                .append("<div class=\"actions\"><button class=\"tonal\" type=\"submit\"")
                .append(locked).append(">Upload</button></div></form>");
        if (!config.mqttClientName.isEmpty()) {
            trust.append("<form method=\"post\" action=\"").append(settingsAt("mqtt"))
                    .append("\" class=\"actions\"><input type=\"hidden\" name=\"section\""
                            + " value=\"mqtt\"><input type=\"hidden\" name=\"mqtt_action\""
                            + " value=\"client_delete\"><button type=\"submit\" class=\"danger\""
                            + " data-confirm=\"")
                    .append(escapeHtml(deleteQuestion("the client certificate")
                            + " The broker is then reached without one."))
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
        // In the order a person needs it, as on the panel (the UI round, 2026-10-15): the fleet
        // and its panels, adding one, what this panel follows and the Fleet page, then the port
        // and Leave.
        boolean member = !KioskConfig.fleetIdOf(context).isEmpty();
        return fleetMembership()
                + addByCodeForm(notice, noticeSection)
                + inviteBlock()
                + sharedSwitches()
                + "<p class=\"label\">Connection</p>"
                + sectionFormStart("fleet", notice, noticeSection)
                + baselineField(Integer.toString(config.fleetPort))
                + field("number", "fleet_port", "Member port", Integer.toString(config.fleetPort))
                + "<p class=\"hint" + (KioskRuntimeState.fleetListening() ? "" : " bad")
                + "\" id=\"fleet-state\">" + escapeHtml(FleetServer.stateLine()) + "</p>"
                + sectionFormEnd("Save")
                + "<div class=\"actions\"><button type=\"button\" class=\"danger\" id=\"fleet-leave\""
                + (member ? "" : " hidden") + ">Leave</button></div>";
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
                + "</div><p class=\"hint\">This panel's groups</p><div class=\"switches\">");
        for (java.util.Map.Entry<String, String> group : FleetDocument.groups(list).entrySet()) {
            String id = escapeHtml(group.getKey());
            html.append(switchRow("fleet-group-" + id, group.getValue(),
                    " data-setting=\"fleet_group_" + id + "\"", mine.contains(group.getKey())));
        }
        html.append("</div>");
        if (FleetDocument.groups(list).isEmpty()) {
            html.append("<p class=\"hint\">No groups yet.</p>");
        }
        // The groups themselves and what each scope shares: the Fleet page. The fleet's alerts
        // have a page of their own: the one shared section with no section of its own to live in.
        return html.append("<div class=\"actions\"><a class=\"onward\" href=\"/fleet\">Open the"
                + " Fleet page</a><a class=\"onward\" href=\"/alerts\">Alerts</a></div>")
                .toString();
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
        writeResponse(output, added ? 200 : 400, "text/html; charset=utf-8",
                bytes(buildSettingsPage(answer, "adopt")));
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

    private String webAdminBody(KioskConfig config, String notice, String noticeSection) {
        return sharedLine("web_admin") + sectionFormStart("webadmin", notice, noticeSection)
                + baselineField(Integer.toString(config.httpPort))
                + field("number", "http_port", "Port", Integer.toString(config.httpPort))
                + field("password", "http_admin_password", "Admin password", "", "", BLANK_KEEPS)
                + "<p class=\"hint\">At least " + MIN_ADMIN_PASSWORD_LENGTH
                + " characters. No username.</p>"
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
     * 2026-10-05). On plain HTTP the upload is greyed with its reason, so no private key is ever
     * sent before the refusal could stop it.
     */
    private String certificateControls(String notice, String noticeSection) {
        KioskConfig facts = KioskConfig.certificateFacts(context);
        AdminCertificate certificate = served;
        boolean kept = !facts.httpUploadSha256.isEmpty();
        boolean overTls = certificate != null && certificate.secure();
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
        String locked = overTls ? "" : " disabled title=\""
                + escapeHtml(AdminCertificate.UPLOAD_NEEDS_HTTPS) + "\"";
        return "<div class=\"certificate\" id=\"certificate\" data-lists-kind=\"certificate\" "
                + "data-lists=\"" + escapeHtml(AdminCertificate.stateKey(facts)) + "\">"
                // An upload or a Delete answers here, by the controls that asked, not at the top
                // of the box above Port (review, 2026-10-05).
                + (notice != null && "certificate".equals(noticeSection)
                        ? "<p class=\"notice\">" + escapeHtml(notice) + "</p>" : "")
                + choice("http_certificate", "Certificate", radios)
                + (problem.isEmpty() ? "" : "<p class=\"hint bad\">"
                        + escapeHtml(AdminCertificate.notServedSentence(problem, overTls)) + "</p>")
                + certificateHint(certificate)
                // The two files, each a button with the name chosen beside it, as the pictures'
                // upload; one file holding both, or the two swapped, work too.
                + "<form method=\"post\" action=\"" + settingsAt("webadmin") + "\" "
                + "enctype=\"multipart/form-data\" id=\"certificate-upload\">"
                + fileRow("certificate-file", "certificate", "Certificate", locked)
                + fileRow("certificate-key", "key", "Private key", locked)
                + "<div class=\"actions\"><button class=\"primary\" type=\"submit\"" + locked
                + ">Upload</button></div></form>"
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
     * them. Greyed with its reason on the visible button too, since the input itself is off
     * screen (review, 2026-10-05).
     */
    private static String fileRow(String id, String name, String label, String locked) {
        return "<div class=\"uploadrow\">"
                + "<input type=\"file\" id=\"" + id + "\" name=\"" + name + "\" "
                + "accept=\".pem,.crt,.cer,.key,.txt\" class=\"offscreen\" "
                + "data-chosen=\"" + id + "-chosen\"" + locked + ">"
                + "<label class=\"filebutton\" for=\"" + id + "\""
                + (locked.isEmpty() ? "" : locked.replace(" disabled", "")) + ">" + glyph("upload")
                + label + "</label>"
                + "<span class=\"chosen\" id=\"" + id + "-chosen\">No file chosen</span>"
                + "</div>";
    }

    /**
     * A certificate and its key, posted as files by the web admin's form (to the page's own
     * address) or by curl to /api/certificate
     * ({@code -F certificate=@fullchain.pem -F key=@privkey.pem}). Refused when the request did
     * not come over TLS, since a private key must not cross the network unencrypted, and with
     * every reason {@link CertificateUpload} gives. The form's post is answered with the settings
     * page, a script's with JSON.
     */
    private void handleCertificateUpload(Map<String, String> headers, byte[] body,
            OutputStream output, boolean overTls, boolean fromPage) throws IOException {
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
        if (!overTls) {
            problem = "this page is served over plain HTTP right now, and a private key must not "
                    + "cross the network unencrypted";
        } else if (boundary == null) {
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
     * over plain HTTP like the web admin's own key. Answered with the settings page.
     */
    private void handleMqttClientUpload(Map<String, String> headers, byte[] body,
            OutputStream output, boolean overTls) throws IOException {
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
        if (!overTls) {
            problem = "this page is served over plain HTTP right now, and a private key must not "
                    + "cross the network unencrypted";
        } else if (boundary == null) {
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
                + "<p class=\"hint\">Corner taps in order: TL, TR, BL, BR, separated by commas. "
                + "Between " + EscapeSequence.MIN_LENGTH + " and " + EscapeSequence.MAX_LENGTH
                + " taps. Recording them on the tablet is easier than typing them here.</p>"
                + field("text", "settings_sequence", "Open Muralis settings", config.settingsSequence)
                + field("text", "launcher_sequence", "Leave Muralis for the home screen",
                        config.launcherSequence)
                + "<p class=\"hint\">Optional PIN, asked after either combination: "
                + EscapePin.MIN_LENGTH + " to " + EscapePin.MAX_LENGTH
                + " digits. A combination can be watched and repeated; a PIN has to be known. "
                + (KioskConfig.escapePinSet(context) ? "One is set." : "None is set.") + "</p>"
                + field("password", "escape_pin", "PIN", "", "", BLANK_KEEPS)
                + (KioskConfig.escapePinSet(context)
                        ? "<label class=\"check\"><input type=\"checkbox\" name=\"escape_pin_clear\" "
                                + "value=\"1\">Remove the PIN</label>"
                        : "")
                + sectionFormEnd("Save")
                // The volume buttons, a lock-down choice, so under Way out (the UI round,
                // 2026-10-15), stored the moment the switch is touched, as on the panel.
                + "<div class=\"switches\">"
                + switchRow("volume-buttons-disabled", "Disable volume buttons",
                        " data-setting=\"volume_buttons_disabled\"",
                        KioskConfig.volumeButtonsDisabled(context))
                + "</div>";
    }

    /**
     * Cameras: one row a camera, then More camera settings, as Automations lists its rules; a
     * row opens the Cameras page on that camera, as on the panel.
     */
    private String camerasBody() {
        StringBuilder rows = new StringBuilder();
        for (Cameras.Camera camera : KioskConfig.camerasOf(context)) {
            rows.append(linkRow("/cameras?id=" + urlEncode("c:" + camera.name), "cameras",
                    camera.name, camera.url, null, ""));
        }
        return (rows.length() == 0 ? "" : "<ul class=\"list seg\">" + rows + "</ul>")
                + "<div class=\"actions" + (rows.length() == 0 ? " top0" : "") + "\">"
                + "<a class=\"btn text\" href=\"/cameras\">More camera settings" + glyph("next")
                + "</a></div>";
    }

    /** Integrations (2026-10-15): in the menu already, empty until the first ones come. */
    private static String integrationsBody() {
        return "<div class=\"empty\"><p class=\"h\">No integrations yet</p><p class=\"s\">Muralis"
                + " will show and act on data from other services. The first integrations come"
                + " in an update.</p></div>";
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
        return sharedLine("display") + "<div class=\"actions tight\">"
                + quickAction("display.wake", "Display on", "tonal", null)
                + quickAction("display.visual_off", "Display off", "tonal", null)
                + "</div>"
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
     * and Home Assistant; a stored colour that has no name is offered as it is.
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
                + switchRow("status-light", "Status light, a coloured frame around the screen",
                        " data-setting=\"status_light\"", light.on)
                + "</div>"
                + selectField("status-light-color", null, "Status light colour",
                        "status_light_color", colours.toString(), null);
    }

    private String screensaverBody() {
        ScreensaverPolicy.Settings saver = KioskConfig.screensaverOf(context);
        boolean problem = saver.enabled()
                && ScreensaverPolicy.modeProblem(saver.mode, saver.url) != null;
        // A menu here and radios on the Ambient page, deliberately: this is one section among
        // nine, while the page's chooser decides what the rest of that page says.
        return sharedLine("ambient")
                + selectField("ambient-mode", null, "Mode", "ambient_mode",
                screensaverModeOptions(saver), null)
                + "<p class=\"hint" + (problem ? " bad" : "") + "\" id=\"ambient-note\">"
                + escapeHtml(ScreensaverPolicy.describe(saver, KioskRuntimeState.screensaverActive()))
                + "</p>"
                // No "Back to the page" beside these: it ends a showing Ambient, which is what
                // Display on already does to a lit panel (2026-09-11, C16).
                // Off has no page to lead to: the page holds a mode's options and Off has none
                // (2026-09-11), and since the page stopped carrying the chooser
                // (2026-09-28) there would be nothing on it; admin_setting.js follows the menu.
                + "<div class=\"actions\">"
                + quickAction("ambient.start", "Preview", "tonal", null)
                + "<a class=\"btn text" + (ScreensaverPolicy.OFF.equals(saver.mode) ? " gone" : "")
                + "\" id=\"ambient-more\" href=\"/ambient\">More Ambient settings"
                + glyph("next") + "</a></div>";
    }

    private String statsBody(KioskConfig config) {
        // The switch sits under the readout it governs. No form and no Save button: it stands
        // alone and applies itself, the way the brightness controls do; see settingScript.
        return "<pre id=\"stats\">loading...</pre>"
                + switchRow("stats-overlay", "Show system stats on the dashboard",
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
     * round, 2026-10-15; a section of their own, Quick actions, until then). There is no "Main
     * dashboard" (kiosk.home) action, dropped 2026-09-07; the way back from a one-off URL is
     * Restart kiosk, and Home Assistant keeps its kiosk.home button. Without scripting the menu
     * cannot open, so it is shown as its three buttons in a row.
     */
    private static String commandsMenu() {
        return "<div class=\"overflow\"><button type=\"button\" class=\"ib\" id=\"commands-button\""
                + " aria-haspopup=\"menu\" aria-expanded=\"false\" aria-controls=\"commands-menu\""
                + " title=\"Reload, restart, reboot\" aria-label=\"Reload, restart, reboot\">"
                + glyph("more") + "</button>"
                + "<div class=\"m3menu\" id=\"commands-menu\" role=\"menu\">"
                + quickAction("kiosk.reload", "Reload the page", "", "reload")
                + quickAction("kiosk.restart", "Restart kiosk", "", "power")
                + quickAction("system.reboot", "Reboot the device", "", "reboot")
                + "</div></div>";
    }

    private static final String REPOSITORY_URL = "https://github.com/spazio17/muralis";


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
        if (KioskService.hasAccelerometer(context)) {
            options.append(selectOption("auto", "Auto-rotate", current));
        }
        options.append(selectOption("landscape", "Landscape", current));
        options.append(selectOption("portrait", "Portrait", current));
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
                    : library.saveLocal(queryValue(query, "playlist"), part.filename, part.data);
            if (problem == null) {
                stored++;
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
        transitions.append(selectOption(ScreensaverPolicy.TRANSITION_NONE, "Cut", saver.transition));
        transitions.append(selectOption(ScreensaverPolicy.TRANSITION_FADE, "Fade", saver.transition));
        transitions.append(selectOption(ScreensaverPolicy.TRANSITION_SLIDE, "Slide", saver.transition));
        StringBuilder fits = new StringBuilder();
        fits.append(selectOption(ScreensaverPolicy.FIT_WHOLE, "Fit", saver.pictureFit));
        fits.append(selectOption(ScreensaverPolicy.FIT_FILL, "Fill", saver.pictureFit));
        fits.append(selectOption(ScreensaverPolicy.FIT_STRETCH, "Stretch", saver.pictureFit));
        fits.append(selectOption(ScreensaverPolicy.FIT_ACTUAL, "Actual size", saver.pictureFit));
        StringBuilder corners = new StringBuilder();
        corners.append(selectOption(ScreensaverPolicy.CORNER_BOTTOM_LEFT, "Bottom left", saver.creditCorner));
        corners.append(selectOption(ScreensaverPolicy.CORNER_BOTTOM_RIGHT, "Bottom right", saver.creditCorner));
        corners.append(selectOption(ScreensaverPolicy.CORNER_TOP_LEFT, "Top left", saver.creditCorner));
        corners.append(selectOption(ScreensaverPolicy.CORNER_TOP_RIGHT, "Top right", saver.creditCorner));

        String picturesProblem = library.problem();
        return "<div id=\"ambient-pictures\""
                + (ScreensaverPolicy.PICTURES.equals(saver.mode) ? "" : " class=\"gone\"") + ">"
                + "<p class=\"hint" + (picturesProblem != null ? " bad" : "") + "\" id=\"ambient-pictures-note\">"
                + escapeHtml(library.state()) + "</p>"
                + fieldWithId("ambient-picture-s", "number", null,
                        "Each picture stays for (seconds)", String.valueOf(saver.pictureSeconds),
                        " data-setting=\"ambient_picture_s\" min=\"1\" max=\""
                                + ScreensaverPolicy.MAX_SECONDS + "\" step=\"1\"", null)
                + selectField("ambient-transition", null, "Change of picture",
                        "ambient_transition", transitions.toString(), null)
                + selectField("ambient-fit", null, "How a picture fills the screen",
                        "ambient_picture_fit", fits.toString(),
                        "Fit shows the whole picture, Fill crops it to the edges, Stretch pulls "
                        + "it out of shape, Actual size does not scale it.")
                + "<div class=\"switches\">"
                + switchRow("ambient-shuffle", "Shuffle the order",
                        " data-setting=\"ambient_shuffle\"", saver.shuffle)
                + switchRow("ambient-one", "One picture per cycle",
                        " data-setting=\"ambient_one_per_cycle\"", saver.onePerCycle)
                + switchRow("ambient-credit", "Show the title and credit line",
                        " data-setting=\"ambient_credit\"", saver.credit)
                + "</div>"
                + selectField("ambient-corner", null, "Credit line in the corner",
                        "ambient_credit_corner", corners.toString(), null)
                + "</div>";
    }


    /**
     * The playlists on the Ambient page: the list and nothing else.
     *
     * <p>Shown only for the Pictures mode. A card since 2026-09-23, with the list re-read in place by
     * {@code admin_playlists.js} and the banner under it speaking only for a refusal: the radio
     * moving or a row appearing is the whole answer to Use and Create; Delete is on the
     * playlist's own page.
     */
    private String pictureLibraryBox() {
        ScreensaverPolicy.Settings saver = KioskConfig.screensaverOf(context);
        boolean shown = ScreensaverPolicy.PICTURES.equals(saver.mode);
        PictureLibrary library = PictureLibrary.get(context);
        // The cell around the panel is what leaves for another mode, so the options keep
        // the right-hand column of the grid whether or not the playlists are shown.
        StringBuilder html = new StringBuilder("<div")
                .append(shown ? "" : " class=\"gone\"").append(" id=\"ambient-library\">")
                .append("<section class=\"card\"><h2>Playlists</h2>");
        // Without the permission the playlists stay: uploads need none (2026-10-05).
        if (!library.browsesOwnStorage()) {
            html.append(ownPicturesHint());
        }
        return html.append("<div id=\"playlist-table\">")
                .append(playlistTable(library.playlists().load())).append("</div>")
                .append("<p class=\"banner bad gone\" id=\"playlist-banner\" role=\"status\">")
                .append("<span></span><button type=\"button\" class=\"dismiss\" ")
                .append("aria-label=\"Close\">&times;</button></p>")
                .append("</section></div>").toString();
    }


    private String buildPlaylistPage(String notice, String id, String at, int size) {
        PictureLibrary library = PictureLibrary.get(context);
        PlaylistDocument document = library.playlists().load();
        PlaylistDocument.Playlist playlist = document.byId(id);
        String said = notice == null || notice.isEmpty() ? ""
                : "<p class=\"notice\">" + escapeHtml(notice) + "</p>";
        if (playlist == null) {
            return pageStart("Picture playlist", null, "/ambient")
                    + said + "<p class=\"hint bad\">That playlist is gone.</p>" + pageEnd();
        }
        StringBuilder html = new StringBuilder(pageStartWith(playlistHeading(playlist),
                "/ambient", false));
        html.append(said)
                .append("<div class=\"two playlist\">")
                .append("<section class=\"card\" id=\"playlist-folders\">")
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
                .append("<section class=\"card\" id=\"playlist-content\">")
                .append("<div id=\"folder-content\">")
                .append(folderContent(at, playlist.id, size))
                .append("</div>")
                .append("</section>")
                .append("<section class=\"card\" id=\"playlist-held\">")
                .append("<div class=\"cardhead\"><h2>In this playlist</h2>")
                .append(shuffleMark())
                .append("</div>")
                .append("<div id=\"playlist-items\">")
                .append(playlistItems(playlist, library))
                .append("</div>")
                .append("</section>")
                .append("</div>")
                // Delete, outlined red as in every editor (the delete rules, 2026-10-01), asked
                // first in the panel's words; it left the Ambient page's row on 2026-10-04.
                // The answer is the Ambient page, since this one is gone.
                .append("<form method=\"post\" action=\"/api/playlists/delete\" class=\"actions editor\" data-native=\"1\">")
                .append(hidden("id", playlist.id))
                // A refusal other than gone, a failed save, is answered on this page.
                .append(hidden("playlist", playlist.id))
                .append("<button type=\"submit\" class=\"danger\" data-confirm=\"")
                .append(escapeHtml(deleteQuestion(playlist.name) + (playlist.active
                        ? " It is the playlist in use, so Ambient will have none until you "
                                + "choose another." : "") + " The pictures themselves are not deleted."))
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
        return "<div class=\"titles\"><div class=\"titlerow\"><h1 id=\"playlist-name\">"
                + escapeHtml(playlist.name) + "</h1>"
                + "<details class=\"rename\">"
                + "<summary class=\"ib\" aria-label=\"Rename this playlist\" title=\"Rename\">"
                + glyph("pencil") + "</summary>"
                + "<form method=\"post\" action=\"/api/playlists/rename\">"
                + hidden("id", playlist.id)
                // So the answer comes back as this page rather than as the Ambient page,
                // which is the rule every form on this page follows.
                + hidden("playlist", playlist.id)
                + fieldWithId("rename-name", "text", "name", "Playlist name", playlist.name,
                        " maxlength=\"" + PlaylistDocument.MAX_NAME_LENGTH + "\" required", null)
                + "<button class=\"primary\" type=\"submit\">Save</button>"
                + "<button type=\"button\" class=\"text cancel\">Cancel</button>"
                + "<span class=\"hint bad gone\" role=\"alert\"></span>"
                + "</form></details>"
                + "<noscript><style>details.rename .cancel{display:none}</style></noscript>"
                + "</div></div>";
    }



    /**
     * Everything the playlist holds, in the order Ambient shows it: the picture, the
     * folder and the name, then the box that names it for its credit, the eye that switches its
     * credit off or on, and the remove button, all 40 px on one line (2026-09-23 and
     * 2026-09-25). The box has no save tick: {@code admin_pictures.js} stores it when it lets go
     * of the focus, and Enter submits its form, which is also the path with no scripting.
     *
     * <p>The folder is prepended because this list is the one place two pictures with the same
     * name from two folders sit next to each other (2026-09-10). It is a fragment of its
     * own because a tick in the browser changes it, and the page must not reload to say so.
     * Remove takes the picture out of this playlist and deletes nothing; it posts to the same
     * endpoint the browser's tick does, with the selection off.
     *
     * <p>A picture is dragged by the grip beside it, the six dots every list app uses, or by its
     * thumbnail; {@code admin_pictures.js} does the drag and posts the whole order, and without
     * scripting the grip is not drawn at all. No sentence explains any of it (2026-09-25:
     * a sentence is the last resort). An empty name box shows, greyed, the words Ambient
     * prints instead, the file's name. With every credit switched off in Ambient
     * settings, each eye stands crossed out and inert, which says the same without a line.
     */
    private String playlistItems(PlaylistDocument.Playlist playlist, PictureLibrary library) {
        StringBuilder html = new StringBuilder();
        html.append("<p class=\"hint\">")
                .append(playlist.items.size()).append(playlist.items.size() == 1
                        ? " picture" : " pictures")
                .append(playlist.active ? ", and this is the playlist in use." : ".")
                .append("</p>");
        if (playlist.items.isEmpty()) {
            return html.append("<p class=\"hint\">Nothing picked yet. Open a folder and add its")
                    .append(" pictures.</p>").toString();
        }
        boolean creditsShown = KioskConfig.screensaverOf(context).credit;
        boolean movable = playlist.items.size() > 1;
        Map<String, String> captions = library.captions();
        java.util.Set<String> creditsOff = library.hiddenCredits();
        html.append("<ul class=\"pictures held\" id=\"held-list\">");
        int index = 0;
        for (String uri : playlist.items) {
            String label = library.displayPath(uri);
            String fallback = PictureSources.creditFromLabel(label);
            boolean off = creditsOff.contains(uri);
            html.append("<li data-uri=\"").append(escapeHtml(uri)).append("\">")
                    .append("<span class=\"grab\">")
                    .append(movable ? "<span class=\"grip\">" + glyph("drag") + "</span>" : "")
                    .append(thumbnail(uri)).append("</span>")
                    .append("<span class=\"name\">").append(escapeHtml(label)).append("</span>")
                    .append("<form class=\"caption\" method=\"post\"")
                    .append(" action=\"/api/pictures/caption\">")
                    .append(hidden("name", uri)).append(hidden("playlist", playlist.id))
                    .append("<div class=\"field dense").append(off ? " off" : "")
                    .append("\"><input type=\"text\" id=\"caption-")
                    .append(index).append("\" name=\"caption\" maxlength=\"200\" placeholder=\"")
                    .append(fallback.isEmpty() ? " " : escapeHtml(fallback)).append("\"")
                    .append(" value=\"").append(escapeHtml(captions.getOrDefault(uri, "")))
                    .append("\"").append(off ? " disabled" : "").append(MACHINE_TEXT).append(">")
                    .append("<label for=\"caption-").append(index).append("\">")
                    .append(off ? "Credit hidden" : "Name for the credit").append("</label>")
                    .append("</div></form>")
                    .append("<span class=\"acts\">")
                    .append("<form class=\"credit\" method=\"post\"")
                    .append(" action=\"/api/pictures/credit\">")
                    .append(hidden("name", uri)).append(hidden("playlist", playlist.id))
                    .append(hidden("shown", off ? "true" : "false"))
                    .append(creditsShown ? iconButton(off ? "eye_off" : "eye",
                            off ? "Show the credit" : "Hide the credit", "")
                            : iconButton("eye_off",
                                    "Credits are off in the Ambient settings", "", true))
                    .append("</form>")
                    .append("<form method=\"post\" action=\"/api/pictures/select\">")
                    .append(hidden("uri", uri)).append(hidden("playlist", playlist.id))
                    .append(hidden("selected", "false"))
                    .append(iconButton("minus", "Remove from the playlist", ""))
                    .append("</form></span></li>");
            index++;
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
        return "<form id=\"picture-upload\" method=\"post\" action=\"/api/pictures?playlist="
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
                + " Allow it on the panel's Ambient settings; Android only asks there.</p>";
    }

    /** Why the panels cannot show pictures, as the hint both of them then carry, or null. */
    private static String browserRefusal(PictureLibrary library, String playlistId) {
        // The playlist this page was opened for, not whichever happens to be playing: the page is
        // per playlist since 2026-09-12, which is what removed the ambiguity the old inline
        // browser had.
        if (library.playlists().load().byId(playlistId) == null) {
            return "<p class=\"hint bad\">That playlist is gone.</p>";
        }
        return null;
    }

    /**
     * The Folders panel: the folder tree, indented by depth, the open one marked, a caret on every
     * folder with folders under it, Uploaded to Muralis first.
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
        html.append(folderLink(PictureBrowser.UPLOADS, "Uploaded to Muralis",
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
        PlaylistDocument.Playlist target = library.playlists().load().byId(playlistId);
        if (target == null) {
            // Deleted from another browser between the check above and this read.
            return html.append("<p class=\"hint bad\">That playlist is gone.</p>").toString();
        }
        int offset = browseOffset(token);
        html.append("<p class=\"where\">")
                .append(escapeHtml(PictureBrowser.UPLOADS.equals(location) ? "Uploaded to Muralis"
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
                if (target.items.contains(entry.uri)) {
                    held++;
                }
            }
            html.append(selectAllRow(held, page.entries.size(), page.available));
            boolean tiles = PictureBrowser.VIEW_SMALL.equals(view) || PictureBrowser.VIEW_BIG.equals(view);
            html.append(tiles ? "<div class=\"tiles " + view + "\">"
                    : "<ul class=\"pictures " + view + "\">");
            for (PictureBrowser.Entry entry : page.entries) {
                boolean selected = target.items.contains(entry.uri);
                String pick = pickForm(entry.uri, selected, token, size, target.id);
                // Only an upload gets a bin: a file the person picked in their own storage leaves
                // the playlist but is never removed from disk here.
                String bin = library.isUpload(entry.uri)
                        ? "<form method=\"post\" action=\"/api/pictures/delete\" class=\"bin\">"
                                + hidden("uri", entry.uri) + hidden("at", token)
                                + hidden("n", String.valueOf(size)) + hidden("playlist", target.id)
                                + iconButton("trash", "Delete the file", "") + "</form>"
                        : "";
                if (tiles) {
                    html.append("<div class=\"tile").append(selected ? " in" : "").append("\">")
                            .append("<span class=\"pic\">").append(thumbnail(entry.uri))
                            .append(pick).append(bin).append("</span>")
                            .append("<span class=\"name\">").append(escapeHtml(entry.name)).append("</span>");
                    if (PictureBrowser.VIEW_BIG.equals(view) && !entry.details().isEmpty()) {
                        html.append("<small>").append(escapeHtml(entry.details())).append("</small>");
                    }
                    html.append("</div>");
                } else {
                    html.append("<li>").append(pick);
                    if (PictureBrowser.VIEW_DETAILS.equals(view)) {
                        html.append(thumbnail(entry.uri))
                                .append("<span class=\"text\"><span class=\"h\">")
                                .append(escapeHtml(entry.name)).append("</span><span class=\"s\">")
                                .append(escapeHtml(entry.details())).append("</span></span>");
                    } else {
                        html.append("<span class=\"name\">").append(escapeHtml(entry.name))
                                .append("</span>");
                    }
                    if (!bin.isEmpty()) {
                        html.append("<span class=\"acts\">").append(bin).append("</span>");
                    }
                    html.append("</li>");
                }
            }
            html.append(tiles ? "</div>" : "</ul>");
        }
        if (page.available > page.entries.size()) {
            // Previous and Next as chevrons around the count: a pager everybody has seen.
            html.append("<div class=\"pager\">")
                    .append(pageLink(Math.max(0, offset - size) + "|" + location, "prev",
                            "Previous page", target.id, size, offset > 0))
                    .append("<span>").append(offset + 1).append(" to ")
                    .append(offset + page.entries.size()).append(" of ").append(page.available)
                    .append("</span>")
                    .append(pageLink((offset + size) + "|" + location, "next", "Next page",
                            target.id, size, page.more))
                    .append("</div>");
        }
        // Show 10, 25, 50 or 100 as chips, the one in force filled, only where there is more than
        // the smallest page to show. A size link starts the folder over: page three of ten is
        // nowhere in particular at fifty.
        if (page.available > PictureBrowser.PAGE_SIZES[0]) {
            html.append("<div class=\"sizes\">");
            for (int choice : PictureBrowser.PAGE_SIZES) {
                html.append("<a class=\"size").append(choice == size ? " on" : "")
                        .append("\" href=\"/playlist?id=").append(urlEncode(target.id))
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
                + hidden("view", next) + hidden("playlist", playlistId) + hidden("at", token)
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
                + available + " in the playlist</span></span></li></ul>";
    }

    /** The check box that puts a picture in the playlist or takes it out, posted on change. */
    private static String pickForm(String uri, boolean selected, String token, int size,
            String playlistId) {
        return "<form method=\"post\" action=\"/api/pictures/select\" class=\"pick\">"
                + hidden("uri", uri) + hidden("at", token) + hidden("n", String.valueOf(size))
                + hidden("playlist", playlistId) + hidden("selected", String.valueOf(!selected))
                + "<span class=\"lead\"><input type=\"checkbox\"" + (selected ? " checked" : "")
                + " aria-label=\"In the playlist\"></span>"
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
                + "<a class=\"folder" + (open ? " open" : "") + "\" href=\"/playlist?id="
                + urlEncode(playlistId) + "&amp;at=" + urlEncode(path) + "&amp;n=" + size
                + "\" data-at=\"" + escapeHtml(path) + "\">"
                + "<span class=\"lead\">" + glyph(glyphName) + "</span>"
                + "<span class=\"name\">" + escapeHtml(label) + "</span>"
                + "<span class=\"badge" + (count == 0 ? " zero" : "") + "\">" + count
                + "</span></a></li>";
    }


    /** Previous or Next as a chevron, carrying an offset and the page size as well as a folder. */
    private static String pageLink(String token, String glyphName, String label, String playlistId,
            int size, boolean enabled) {
        return "<a class=\"ib pager" + (enabled ? "" : " dis") + "\" href=\"/playlist?id="
                + urlEncode(playlistId) + "&amp;at=" + urlEncode(token) + "&amp;n=" + size
                + "\" data-at=\"" + escapeHtml(token) + "\" title=\"" + label + "\" aria-label=\""
                + label + "\"" + (enabled ? "" : " aria-disabled=\"true\"") + ">" + glyph(glyphName)
                + "</a>";
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
            case "/api/playlists/activate":
                change = document -> document.activate(id);
                // Nothing to say: the "In use" mark moving to the row is the whole answer, and a
                // sentence on top of it was noise (2026-09-19).
                done = null;
                break;
            case "/api/playlists/rename":
                change = document -> document.rename(id, form.getOrDefault("name", ""), now);
                done = "Playlist renamed.";
                break;
            case "/api/playlists/delete":
                change = document -> document.delete(id);
                done = "Playlist deleted. The pictures themselves are not deleted.";
                break;
            case "/api/playlists":
                change = document -> document.create(library.playlists().newId(),
                        form.getOrDefault("name", ""), now);
                done = "Playlist created. Open it to pick its pictures.";
                break;
            case "/api/playlists/order": {
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
        // its own here lost whichever edit Home Assistant or the panel made meanwhile.
        String refusal = library.editPlaylists(change);
        KioskService.publishTelemetrySoon(context);
        if (path.equals("/api/playlists/delete")
                && !"1".equals(queryValue(query, "fragment"))
                && (refusal == null || PlaylistDocument.GONE.equals(refusal))) {
            // The playlist page's own Delete, posted by the browser: sent on to the Ambient
            // page, as the automation editor's Delete is sent to Automations, so a reload does
            // not post the delete again and Back does not return to a page that is gone
            // (review, 2026-10-04). A playlist already gone is sent on too, gone it is either
            // way; any other refusal, a failed save, is answered here with its reason.
            writeResponse(output, 303, "text/plain", bytes("deleted"),
                    Collections.singletonMap("Location", "/ambient"));
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
        answer.put("playlists", rows);
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
            html.append("<p class=\"hint\">No playlists yet. Create one, then open it and "
                    + "pick its pictures.</p>");
        } else {
            html.append("<ul class=\"list seg\">");
            for (PlaylistDocument.Playlist playlist : document.all()) {
                int count = playlist.items.size();
                // The left menu's row as the panel draws it (2026-10-04): the glyph, the
                // name over its count, a tap opening the playlist's own page, and Use at the
                // end, the radio that is state and control at once. Delete lives on that page.
                html.append("<li class=\"two\">")
                        .append("<a class=\"rowlink plain\" href=\"/playlist?id=").append(urlEncode(playlist.id))
                        .append("\"><span class=\"lead\">").append(glyph("saver")).append("</span>")
                        .append("<span class=\"text\"><span class=\"h\">").append(escapeHtml(playlist.name))
                        .append("</span><span class=\"s\">").append(count)
                        .append(count == 1 ? " picture" : " pictures")
                        .append(playlist.active ? " · <span class=\"inuse\">in use</span>" : "")
                        .append("</span></span></a>")
                        .append("<span class=\"trail\">")
                        .append("<form method=\"post\" action=\"/api/playlists/activate\" class=\"use\">")
                        .append(hidden("id", playlist.id))
                        .append("<input type=\"radio\" name=\"active\" value=\"")
                        .append(escapeHtml(playlist.id)).append("\"")
                        .append(playlist.active ? " checked" : "")
                        .append(" aria-label=\"Use ").append(escapeHtml(playlist.name)).append("\">")
                        .append(playlist.active ? ""
                                : "<noscript><button class=\"text\" type=\"submit\">Use</button></noscript>")
                        .append("</form></span></li>");
            }
            html.append("</ul>");
        }
        // Naming a playlist before picking its pictures is the panel's own order. The button sits
        // on the box's centre line, not the label's.
        html.append("<form class=\"beside newplaylist\" method=\"post\" action=\"/api/playlists\">")
                .append(fieldWithId("new-playlist-name", "text", "name", "New playlist name", "",
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
        String playlist = form.containsKey("playlist")
                ? form.get("playlist") : queryValue(query, "playlist");
        writeResponse(output, ok ? 200 : 400, "text/html; charset=utf-8",
                playlist == null || playlist.isEmpty()
                        ? bytes(buildScreensaverPage(message, form.get("at")))
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
        return selectOption(ScreensaverPolicy.OFF, "Off", saver.mode)
                + selectOption(ScreensaverPolicy.DIM, "Dimmed page", saver.mode)
                + selectOption(ScreensaverPolicy.FILM, "Black film", saver.mode)
                + selectOption(ScreensaverPolicy.URL, "Web page", saver.mode)
                + selectOption(ScreensaverPolicy.PICTURES, "Pictures", saver.mode);
    }

    /**
     * The Fleet page: the panels with their groups and
     * last contact, the groups, and the shared settings of a scope (the whole fleet or a group)
     * shown and edited here, as the fleet products do. Plain forms, posted to this page: a
     * person managing a fleet sits at a laptop, and nothing here needs to apply as it is typed.
     */
    private String buildFleetPage(String notice, String scopeAsked) {
        java.util.Map<String, Object> list = KioskConfig.fleetDocumentOf(context);
        StringBuilder html = new StringBuilder(pageStart("Fleet",
                KioskConfig.fleetNameOf(context), settingsAt("fleet")));
        if (notice != null && !notice.isEmpty()) {
            html.append("<p class=\"notice\">").append(escapeHtml(notice)).append("</p>");
        }
        if (list == null) {
            return html.append("<div class=\"card\"><p>This panel is not in a fleet, or its list"
                    + " of panels has not arrived yet.</p></div>").append(pageEnd()).toString();
        }
        if (!FleetServer.proStatus(context).pro) {
            // The Fleet page is Muralis Pro; the panels stay members meanwhile.
            return html.append("<div class=\"card\"><p>The Fleet page is Muralis Pro. ")
                    .append(escapeHtml(FleetServer.proLine(context))).append("</p></div>")
                    .append(pageEnd()).toString();
        }
        if (FleetServer.proStatus(context).warn) {
            html.append("<p class=\"notice\">").append(escapeHtml(FleetServer.proLine(context)))
                    .append("</p>");
        }
        java.util.Map<String, String> groups = FleetDocument.groups(list);
        String scope = scopeAsked == null || scopeAsked.isEmpty()
                || !(FleetDocument.EVERYONE.equals(scopeAsked) || groups.containsKey(scopeAsked))
                ? FleetDocument.EVERYONE : scopeAsked;
        // Panels: each with its picture on request, its groups to tick, its last contact and
        // Identify. Screenshots fetches every panel's picture at once; the images
        // load only then, never with the page, since each is a capture on that panel.
        html.append("<div class=\"card\"><h2>Panels</h2><div class=\"actions tight\">"
                + "<button type=\"button\" class=\"tonal\" id=\"fleet-shots\">Screenshots"
                + "</button><button type=\"button\" class=\"text\" data-identify=\"all\">"
                + "Identify all</button></div><p class=\"hint\" id=\"fleet-identify-all\""
                + " hidden></p>");
        org.json.JSONArray peers = FleetServer.peersJson(context);
        for (java.util.Map<String, Object> member : FleetDocument.members(list)) {
            String id = TinyJson.string(member, FleetDocument.ID, "");
            String spki = TinyJson.string(member, FleetDocument.SPKI, "");
            String name = TinyJson.string(member, FleetDocument.NAME, id);
            String contact = FleetServer.contactLine(context, member, peers);
            java.util.List<String> in = FleetDocument.memberGroups(list, member);
            html.append("<form method=\"post\" action=\"/fleet?scope=").append(escapeHtml(scope))
                    .append("\"><input type=\"hidden\" name=\"what\" value=\"panel\"><input"
                    + " type=\"hidden\" name=\"spki\""
                    + " value=\"").append(escapeHtml(spki)).append("\"><p><b>")
                    .append(escapeHtml(name)).append("</b> <span class=\"hint\">")
                    .append(escapeHtml(contact)).append("</span></p>")
                    .append("<div class=\"fleet-shot\" data-panel=\"").append(escapeHtml(id))
                    .append("\"><img alt=\"The screen of ").append(escapeHtml(name))
                    .append("\" hidden><p class=\"hint\" hidden></p></div>");
            if (!groups.isEmpty()) {
                html.append("<div class=\"switches\">");
                for (java.util.Map.Entry<String, String> group : groups.entrySet()) {
                    html.append("<label class=\"switch\"><span>").append(escapeHtml(group.getValue()))
                            .append("</span><input type=\"checkbox\" name=\"group_")
                            .append(escapeHtml(group.getKey())).append("\" value=\"1\"")
                            .append(in.contains(group.getKey()) ? " checked" : "")
                            .append("></label>");
                }
                html.append("</div><div class=\"actions tight\"><button class=\"tonal\""
                        + " type=\"submit\">Save groups</button>");
            } else {
                html.append("<div class=\"actions tight\">");
            }
            html.append("<button type=\"button\" class=\"text\" data-identify=\"")
                    .append(escapeHtml(id)).append("\">Identify</button>");
            if (!FleetServer.isSelf(context, member)) {
                // For a panel that cannot leave by itself; one that is reachable is told.
                html.append("<button type=\"submit\" class=\"text\" name=\"remove\" value=\"1\""
                        + " data-confirm=\"").append(escapeHtml("Remove " + name + "?\n\nIt is"
                        + " taken off the fleet's list on every panel and its key is no longer"
                        + " trusted. It can join again with a new code."))
                        .append("\">Remove</button>");
            }
            html.append("</div></form>");
        }
        html.append("<p class=\"hint\">A panel is added with Add a panel in the Fleet section: by"
                + " a panel's camera, or by typing the code under its Join QR.</p></div>");
        // Groups: make one, remove one.
        html.append("<div class=\"card\"><h2>Groups</h2>");
        if (groups.isEmpty()) {
            html.append("<p class=\"hint\">No groups yet. Panels with the same job, such as the"
                    + " meeting rooms or the lobby screens, can share more than the whole fleet"
                    + " does.</p>");
        }
        for (java.util.Map.Entry<String, String> group : groups.entrySet()) {
            html.append("<form method=\"post\" action=\"/fleet?scope=").append(escapeHtml(scope))
                    .append("\" class=\"actions tight\">"
                    + "<input type=\"hidden\" name=\"what\" value=\"remove_group\"><input"
                    + " type=\"hidden\" name=\"id\" value=\"").append(escapeHtml(group.getKey()))
                    .append("\"><span>").append(escapeHtml(group.getValue()))
                    .append("</span><button class=\"text\" type=\"submit\" data-confirm=\"")
                    // The panel's question for it, in the same words.
                    .append(escapeHtml("Remove the group " + group.getValue() + "?\n\nWhat it"
                            + " shares is shared no more; the panels in it keep their settings."))
                    .append("\">Remove</button></form>");
        }
        StringBuilder suggestions = new StringBuilder();
        for (String suggestion : FleetDocument.GROUP_SUGGESTIONS) {
            if (!groups.containsValue(suggestion)) {
                suggestions.append("<option value=\"").append(escapeHtml(suggestion)).append("\">");
            }
        }
        html.append("<form method=\"post\" action=\"/fleet?scope=").append(escapeHtml(scope))
                .append("\"><input type=\"hidden\" name=\"what\" value=\"add_group\">")
                .append(fieldWithId("fleet-page-group-name", "text", "name", "New group", "",
                        " list=\"fleet-page-suggestions\" autocomplete=\"off\"", null))
                .append("<datalist id=\"fleet-page-suggestions\">").append(suggestions)
                .append("</datalist><div class=\"actions tight\"><button class=\"tonal\""
                        + " type=\"submit\">Add group</button></div></form></div>");
        // Shared settings of one scope: the whole fleet or a group, chosen by a reload.
        html.append("<div class=\"card\"><h2>Shared settings</h2><form method=\"get\""
                + " action=\"/fleet\">");
        StringBuilder options = new StringBuilder(selectOption(FleetDocument.EVERYONE,
                FleetDocument.EVERYONE_NAME, scope));
        for (java.util.Map.Entry<String, String> group : groups.entrySet()) {
            options.append(selectOption(group.getKey(), group.getValue(), scope));
        }
        html.append(selectField("fleet-page-scope", "scope", "Shared in", null,
                options.toString(), null)).append("<div class=\"actions tight\"><button"
                + " class=\"text\" type=\"submit\">Show</button></div></form>");
        // Which sections the scope shares, one switch each (the UI round, 2026-10-15): the
        // values are the sections' own, edited where they live on any panel of the scope, and
        // never copied here. Switched on, the section as this panel has it now is shared; the
        // switch posts itself where scripting runs (admin_fleet.js), Save does elsewhere.
        html.append("<div class=\"switches\">");
        for (String section : FleetDocument.SECTIONS) {
            boolean isShared = FleetDocument.sectionValues(list, scope, section) != null;
            html.append("<form method=\"post\" action=\"/fleet?scope=").append(escapeHtml(scope))
                    .append("\"><input type=\"hidden\" name=\"what\" value=\"share\"><input"
                    + " type=\"hidden\" name=\"scope\" value=\"").append(escapeHtml(scope))
                    .append("\"><input type=\"hidden\" name=\"section\" value=\"").append(section)
                    .append("\">").append(switchRow("fleet-share-" + section,
                            FleetDocument.sectionLabel(section),
                            " name=\"shared\" value=\"1\" data-submit=\"1\"", isShared))
                    .append("<div class=\"actions nojs\"><button class=\"tonal\" type=\"submit\">"
                            + "Save</button></div></form>");
        }
        html.append("</div>");
        html.append("<p class=\"hint\">On, the section as it is here applies to every panel of"
                + " the scope. A group wins over the whole fleet.</p></div>");
        // History: the versions this panel held, newest first, each with what it
        // changed, and Restore as new for every one but the version held.
        html.append("<div class=\"card\"><h2>History</h2>");
        java.util.List<FleetServer.HistoryEntry> history = FleetServer.history(context);
        if (history.isEmpty()) {
            html.append("<p class=\"hint\">No versions kept yet.</p>");
        }
        // The newest few, the rest behind Older versions, as on the panel (the UI round,
        // 2026-10-15).
        int shownCount = 0;
        for (FleetServer.HistoryEntry entry : history) {
            if (shownCount++ == FleetServer.HISTORY_SHOWN) {
                html.append("<details class=\"older\"><summary>Older versions</summary>");
            }
            html.append("<form method=\"post\" action=\"/fleet?scope=").append(escapeHtml(scope))
                    .append("\"><input type=\"hidden\" name=\"what\" value=\"restore\"><input"
                    + " type=\"hidden\" name=\"version\" value=\"").append(entry.version)
                    .append("\"><p><b>Version ").append(entry.version).append("</b>")
                    .append(entry.held ? ", held now" : "").append(" <span class=\"hint\">")
                    .append(escapeHtml(entry.author)).append(", ")
                    .append(escapeHtml(FleetServer.readableTime(entry.updated)))
                    .append("</span></p>");
            html.append("<p class=\"hint\">").append(entry.changes.isEmpty()
                    ? "No change to the fleet's name, panels, groups or shared settings."
                    : escapeHtml(String.join("; ", entry.changes)) + ".").append("</p>");
            if (!entry.held) {
                // The panel's question, in its words: it rewrites what every panel shares.
                html.append("<div class=\"actions tight\"><button class=\"text\" type=\"submit\""
                        + " data-confirm=\"" + escapeHtml("Restore version " + entry.version
                                + " as new?\n\nIts fleet name, groups and shared settings are"
                                + " written as a new version for every panel; the panels of the"
                                + " fleet stay as they are now.") + "\">Restore as new</button></div>");
            }
            html.append("</form>");
        }
        if (shownCount > FleetServer.HISTORY_SHOWN) {
            html.append("</details>");
        }
        html.append("<p class=\"hint\">The last ").append(KioskConfig.FLEET_HISTORY_SIZE)
                .append(" versions. Restore as new brings back a version's name, groups and shared"
                + " settings; the panels stay.</p></div>");
        html.append(fleetScript);
        return html.append(pageEnd()).toString();
    }

    /**
     * Alerts (the UI round, 2026-10-15; on the Fleet page's shared settings until then), as on
     * the panel: the fleet's alerts, edited here and shared with the scope that shares them now,
     * or with the whole fleet when none does yet. Alerts are raised only while they are shared.
     */
    private String buildAlertsPage(String notice) {
        String section = "alerts";
        java.util.Map<String, Object> list = KioskConfig.fleetDocumentOf(context);
        String scope = FleetProfile.alertsScope(list);
        java.util.Map<String, Object> shared = list == null ? null
                : FleetDocument.sectionValues(list, scope, section);
        java.util.Map<String, Object> values = shared != null ? shared
                : FleetProfile.read(context, section);
        StringBuilder html = new StringBuilder(pageStart("Alerts", null, settingsAt("fleet")));
        if (notice != null) {
            html.append("<p class=\"notice\">").append(escapeHtml(notice)).append("</p>");
        }
        html.append("<form method=\"post\" action=\"/alerts\" class=\"card\"><h2>Alerts</h2>"
                + "<p class=\"hint\">").append(shared == null
                        ? "Not shared yet: Save shares them with the whole fleet."
                        : "Shared with " + escapeHtml(FleetDocument.EVERYONE.equals(scope)
                                ? "the whole fleet" : "the group "
                                        + FleetDocument.groupName(list, scope)) + ".")
                .append(" Each alert comes once when it starts and once when it ends.</p>"
                        + "<div class=\"switches\">");
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
        return html.append(pageEnd()).toString();
    }

    /** What the Alerts page posts: the values, shared with the scope the page named. */
    private String saveAlerts(Map<String, String> form) {
        if (!FleetServer.proStatus(context).pro) {
            return "Not changed: the fleet's alerts are Muralis Pro. " + FleetServer.proLine(context);
        }
        String section = "alerts";
        java.util.Map<String, Object> list = KioskConfig.fleetDocumentOf(context);
        String scope = FleetProfile.alertsScope(list);
        java.util.Map<String, Object> shared = list == null ? null
                : FleetDocument.sectionValues(list, scope, section);
        String[] problem = new String[1];
        java.util.Map<String, Object> values = FleetProfile.fromForm(section, form,
                shared != null ? shared : FleetProfile.read(context, section), problem);
        String refused = values == null ? problem[0]
                : FleetServer.shareSection(context, scope, section, values);
        return refused == null ? null : "Not saved: " + refused + ".";
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
        String onWake = selectOption(ScreensaverPolicy.WAKE_SCREENSAVER,
                "Show Ambient first, a touch opens the page", saver.onWake)
                + selectOption(ScreensaverPolicy.WAKE_DASHBOARD, "Show the page at once", saver.onWake);
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
                        "Idle before Ambient (seconds, 0 = off)",
                        String.valueOf(saver.idleSeconds),
                        " data-setting=\"ambient_idle_s\" min=\"0\" max=\""
                                + ScreensaverPolicy.MAX_SECONDS + "\" step=\"1\"", null))
                .append(fieldWithId("ambient-off", "number", null,
                        "Ambient before display off (seconds, 0 = never)",
                        String.valueOf(saver.offSeconds),
                        " data-setting=\"ambient_off_s\" min=\"0\" max=\""
                                + ScreensaverPolicy.MAX_SECONDS + "\" step=\"1\"", null))
                .append("<div id=\"ambient-url-field\"")
                .append(ScreensaverPolicy.URL.equals(saver.mode) ? "" : " class=\"gone\"").append(">")
                .append(fieldWithId("ambient-url", "text", null, "Web page to show", saver.url,
                        " data-setting=\"ambient_url\" inputmode=\"url\"", null))
                .append("</div>")
                .append("<div id=\"ambient-dim-field\"")
                .append(ScreensaverPolicy.DIM.equals(saver.mode) ? "" : " class=\"gone\"").append(">")
                .append(fieldWithId("ambient-dim", "number", null,
                        "Brightness while dimmed (percent)", String.valueOf(saver.dimPercent),
                        " data-setting=\"ambient_dim_percent\" min=\"1\" max=\"100\" step=\"1\"",
                        null))
                .append("</div>")
                // Gone rather than greyed out where it does not apply: a control that can never
                // be enabled is clutter (2026-09-11).
                .append("<div id=\"ambient-wake-field\"")
                .append(ScreensaverPolicy.wakeChoiceApplies(saver.mode) ? "" : " class=\"gone\"")
                .append(">")
                .append(selectField("ambient-on-wake", null, "After a wake from display off",
                        "ambient_on_wake", onWake, null))
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
        html.append(pictureScript);
        html.append(playlistScript);
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
        if (form.containsKey("ambient_idle_s")) {
            Integer seconds = ScreensaverPolicy.parseSeconds(form.get("ambient_idle_s"));
            if (seconds == null) {
                return "Not saved: the idle time " + ScreensaverPolicy.SECONDS_RULE + ".";
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
                return "Not saved: the transition must be none, fade or slide.";
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
        if (form.containsKey("ambient_one_per_cycle")) {
            editor.screensaverOnePerCycle(isTrue(form.get("ambient_one_per_cycle")));
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
            options.append(selectOption(DisplayOffPolicy.AUTO, "Automatic", current));
            options.append(selectOption(DisplayOffPolicy.SLEEP, "Turn the screen off", current));
            options.append(selectOption(DisplayOffPolicy.FILM, "Black film", current));
        } else {
            options.append(selectOption(DisplayOffPolicy.FILM, "Black film", DisplayOffPolicy.FILM));
        }
        return selectField("display-off-method", null, "Display off",
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
        return switchRow("auto-brightness", "Adjust brightness automatically", "",
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
        return "<p class=\"hint\">Brightness needs the \"Modify system settings\" permission: "
                + "grant it in the Display card on the tablet, or with "
                + "<code>adb shell appops set " + context.getPackageName()
                + " WRITE_SETTINGS allow</code>.</p>";
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
                + " value=\"" + escapeHtml(value) + "\" placeholder=\" \""
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
            return "<p class=\"hint bad\">" + AdminCertificate.PLAIN_HTTP_SENTENCE + "</p>";
        }
        // The sentence, then the fingerprint in a block of its own: 95 characters with no space
        // to break at, so inline in the sentence it wrapped mid-value (2026-09-23).
        return "<p class=\"hint\">"
                + escapeHtml(AdminCertificate.servedSentence(certificate.uploaded, "this page"))
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

    private static void writeResponse(
            OutputStream output, int status, String contentType, byte[] body,
            Map<String, String> extraHeaders) throws IOException {
        StringBuilder header = new StringBuilder();
        header.append("HTTP/1.1 ").append(status).append(' ').append(reasonPhrase(status))
                .append("\r\n");
        header.append("Content-Type: ").append(contentType).append("\r\n");
        header.append("Content-Length: ").append(body.length).append("\r\n");
        // Never inside another site's frame, where a signed-in browser's clicks would pass the
        // same-origin check, and never a type guessed from the bytes.
        header.append("X-Frame-Options: DENY\r\nContent-Security-Policy: frame-ancestors 'none'\r\n"
                + "X-Content-Type-Options: nosniff\r\n");
        boolean open = keepOpen.get();
        answered.set(true);
        header.append(open ? "Connection: keep-alive\r\nKeep-Alive: timeout="
                + (KEEP_ALIVE_IDLE_MS / 1000) + "\r\n" : "Connection: close\r\n");
        answeredOpen.set(open);
        for (Map.Entry<String, String> entry : extraHeaders.entrySet()) {
            header.append(entry.getKey()).append(": ").append(entry.getValue()).append("\r\n");
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
