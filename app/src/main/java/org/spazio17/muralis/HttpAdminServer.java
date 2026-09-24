/*
 * Copyright 2026 Muralis contributors
 * All rights reserved. See LICENSE at the repository root.
 */
package org.spazio17.muralis;

import android.content.Context;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
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
    private static final int MIN_ADMIN_PASSWORD_LENGTH = 8;
    /**
     * The sections that have a box of their own on the settings page, and can therefore show
     * their own message. Anything else falls back to the banner above the grid.
     */
    private static final java.util.Set<String> BOXED_SECTIONS = Collections.unmodifiableSet(
            new java.util.HashSet<>(java.util.Arrays.asList(
                    "dashboard", "mqtt", "webadmin", "sequences")));
    private static final int SOCKET_TIMEOUT_MS = 10_000;
    private static final int HANDSHAKE_TIMEOUT_MS = 2_000;
    /** Long enough for a socket close to land, short enough that a reload never looks like a hang. */
    private static final int SHUTDOWN_WAIT_MS = 1_000;
    private static final int MAX_REQUEST_LINE_LENGTH = 4_096;
    private static final int MAX_HEADER_LINES = 40;
    private static final int MAX_BODY_BYTES = 16_384;
    /**
     * The one request that carries megabytes: the picture upload for the screensaver's local
     * folder. Its body budget and deadline are its own; every other request keeps the 16 KB
     * and eight seconds that suit commands and settings. A browser sends every chosen file in
     * one request, so the whole-request cap is the working limit a person sees on the page.
     */
    private static final int MAX_UPLOAD_BYTES = 24 * 1024 * 1024;
    private static final int MAX_PICTURE_BYTES = 12 * 1024 * 1024;
    private static final int UPLOAD_DEADLINE_MS = 120_000;
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
    private final String themeScript;
    private final String pictureScript;
    private final String pageCss;


    private final Context context;
    private final KioskService kioskService;

    private ServerSocket serverSocket;
    /**
     * Null only when this device's Keystore could not make a certificate (see AdminCertificate);
     * then the server speaks plain HTTP as it did before 2026-09-09, and says so. Otherwise every
     * accepted connection is expected to start a TLS handshake, and one that does not is sent to
     * the https address without ever being asked for a password.
     */
    private SSLSocketFactory tlsFactory;
    private String certificateFingerprint = "";
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
     * per connection, since every response here closes its connection and an attacker gets a fresh
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
        themeScript = script(R.raw.admin_theme);
        pictureScript = script(R.raw.admin_pictures);
        pageCss = readRawText(R.raw.admin);
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
        AdminCertificate certificate = AdminCertificate.load(context);
        tlsFactory = certificate == null ? null : certificate.socketFactory;
        certificateFingerprint = certificate == null ? "" : certificate.fingerprint;
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
        KioskRuntimeState.publishHttpAdminTls(tlsFactory != null, certificateFingerprint);
        Log.i(TAG, (tlsFactory != null ? "HTTPS" : "HTTP (no certificate)")
                + " admin listening on port " + config.httpPort);
    }

    void stop() {
        running = false;
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

            String requestLine = readLine(input, MAX_REQUEST_LINE_LENGTH);
            if (requestLine == null || requestLine.isEmpty()) {
                return;
            }
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
            for (int i = 0; i < MAX_HEADER_LINES; i++) {
                String line = readLine(input, MAX_REQUEST_LINE_LENGTH);
                if (line == null || line.isEmpty()) {
                    break;
                }
                int colon = line.indexOf(':');
                if (colon > 0) {
                    headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT),
                            line.substring(colon + 1).trim());
                }
            }

            // Credentials first, body second, always. The picture upload is allowed megabytes,
            // and reading them before judging the password would let anyone on the network have
            // this panel allocate 24 MB per worker thread on demand, an OutOfMemoryError that no
            // barrier below catches (2026-09-09 review). Answering a request before its body is
            // read is what every HTTP server does with a 401, and this server closes the
            // connection after every response anyway.
            String remoteHost = remoteHostOf(socket);
            // elapsedRealtime, not wall time: a lockout must not be escapable by setting the clock,
            // and must not stall while the device is suspended.
            long nowMs = android.os.SystemClock.elapsedRealtime();
            long lockedForMs = authThrottle.lockedOutFor(remoteHost, nowMs);
            if (lockedForMs > 0L) {
                // 429 with Retry-After rather than another 401. It is the honest answer, it tells a
                // legitimate operator who mistyped their password how long to wait instead of
                // leaving them guessing, and refusing here costs no worker thread, which is the
                // whole reason this throttles by refusing rather than by sleeping.
                Map<String, String> extra = new HashMap<>();
                extra.put("Retry-After", Long.toString((lockedForMs + 999L) / 1000L));
                writeResponse(output, 429, "text/plain",
                        bytes("Too Many Requests"), extra);
                return;
            }
            if (!isAuthorized(headers.get("authorization"))) {
                if (authThrottle.recordFailure(remoteHost, nowMs)) {
                    KioskRuntimeState.recordAuthLockout(remoteHost);
                    // Only when a lockout begins. Logging every failure would let anyone on the LAN
                    // fill this panel's log by guessing, which is a denial of service wearing the
                    // costume of an audit trail. Never logs the credential that was tried.
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
            if (method.equals("POST")) {
                String refusal = crossSiteRefusal(headers);
                if (refusal != null) {
                    writeResponse(output, 403, "text/plain", bytes("Forbidden: " + refusal));
                    return;
                }
            }
            String requestPath = target.split("\\?", 2)[0];
            boolean upload = method.equals("POST") && requestPath.equals("/api/pictures");
            if (upload) {
                uploadHeld = uploadSlot.tryAcquire();
                if (!uploadHeld) {
                    writeResponse(output, 503, "text/plain", bytes("Another upload is in progress"),
                            Collections.singletonMap("Retry-After", "5"));
                    return;
                }
                input.extend(UPLOAD_DEADLINE_MS);
                deadline.cancel(false);
                deadline = connectionDeadlines.schedule(() -> closeQuietly(socket),
                        UPLOAD_DEADLINE_MS, TimeUnit.MILLISECONDS);
            }
            byte[] body;
            try {
                body = readBody(input, headers, upload ? MAX_UPLOAD_BYTES : MAX_BODY_BYTES);
            } catch (BodyTooLargeException tooLarge) {
                writeResponse(output, 413, "text/plain",
                        bytes("Payload Too Large: " + tooLarge.getMessage()));
                return;
            }


            route(method, target, headers, body, output);
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
                writeResponse(output != null ? output : channel.getOutputStream(), 500,
                        "text/plain", bytes("Internal Server Error"));
            } catch (IOException | RuntimeException ignored) {
                // The socket is already unusable, or the response was partly written. Nothing left
                // to say to this client; the point was to survive, and we have.
            }
        } finally {
            if (deadline != null) deadline.cancel(false);
            if (uploadHeld) uploadSlot.release();
            liveSockets.remove(socket);
            if (tlsToRelease != null) {
                // Sends the TLS close_notify where there was a session, and frees the native one
                // now rather than at GC. autoClose was false, so the raw socket below is still
                // ours, which is the whole point: the FIN and the drain come after this.
                closeQuietly(tlsToRelease);
            }
            lingeringClose(socket);
        }
    }

    private void route(
            String method, String target, Map<String, String> headers, byte[] body,
            OutputStream output) throws IOException {
        String path = target;
        String query = "";
        int questionMark = target.indexOf('?');
        if (questionMark >= 0) {
            path = target.substring(0, questionMark);
            query = target.substring(questionMark + 1);
        }

        // Every state-changing route below is POST, deliberately, so this one check covers all of
        // them. Placed before routing rather than inside each handler because the failure mode of
        // forgetting it in a new handler is silent.
        if (method.equals("POST")) {
            String crossSite = crossSiteRefusal(headers);
            if (crossSite != null) {
                Log.w(TAG, "Refused a state-changing request: " + crossSite);
                writeResponse(output, 403, "text/plain", bytes("Forbidden: " + crossSite));
                return;
            }
        }

        if (path.equals("/") && method.equals("GET")) {
            writeResponse(output, 200, "text/html; charset=utf-8", bytes(buildSettingsPage()));
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
        } else if (path.equals("/screensaver") && method.equals("GET")) {
            writeResponse(output, 200, "text/html; charset=utf-8",
                    bytes(buildScreensaverPage(null, queryValue(query, "at"))));
        } else if (path.equals("/api/pictures/browse") && method.equals("GET")) {
            // The browser as a fragment, for the page's own script. A GET because it changes
            // nothing, which is also what lets a folder be reached by its address again.
            writeResponse(output, 200, "text/html; charset=utf-8",
                    bytes(pictureBrowser(queryValue(query, "at"))));
        } else if (path.equals("/api/pictures") && method.equals("POST")) {
            handlePictureUpload(headers, body, query, output);
        } else if (path.equals("/api/pictures/delete") && method.equals("POST")) {
            Map<String, String> form = parseFormBody(headers, body);
            String refusal = PictureLibrary.get(context).deleteLocal(form.get("uri"));
            KioskService.publishTelemetrySoon(context);
            answerPictureChange(query, form, refusal == null ? "Picture deleted."
                    : "Not deleted: " + refusal + ".", refusal == null, output);
        } else if (path.equals("/api/pictures/caption") && method.equals("POST")) {
            Map<String, String> form = parseFormBody(headers, body);
            String refusal = PictureLibrary.get(context)
                    .setCaption(form.get("name"), form.getOrDefault("caption", ""));
            answerPictureChange(query, form, refusal == null ? "Name saved."
                    : "Not saved: " + refusal + ".", refusal == null, output);
        } else if (path.equals("/api/pictures/select") && method.equals("POST")) {
            Map<String, String> form = parseFormBody(headers, body);
            Boolean selected = KioskCommandDispatcher.parseEnabledFlag(form.get("selected"));
            String refusal = selected == null ? "Supply a boolean selection"
                    : PictureLibrary.get(context).selectPicture(form.get("uri"), selected);
            answerPictureChange(query, form, refusal == null ? "Playlist updated."
                    : "Not changed: " + refusal + ".", refusal == null, output);
        } else if (path.equals("/api/pictures/folder/pick") && method.equals("POST")) {
            String refusal = kioskService.dispatch("screensaver.pick_folder",
                    KioskCommandDispatcher.CommandArgs.EMPTY).detail;
            boolean asked = refusal == null || refusal.isEmpty();
            answerPictureChange(query, parseFormBody(headers, body), asked
                    ? "The picture browser is open inside Muralis on the panel."
                    : "Not opened: " + refusal + ".", asked, output);
        } else if (path.startsWith("/api/playlists") && method.equals("POST")) {
            handlePlaylistChange(path, query, parseFormBody(headers, body), output);
        } else if (path.equals("/api/playlists") && method.equals("GET")) {
            writeResponse(output, 200, "application/json; charset=utf-8",
                    bytes(playlistsJson()));
        } else if (path.equals("/api/pictures/refresh") && method.equals("POST")) {
            PictureLibrary.get(context).refresh(KioskConfig.screensaverOf(context).source, null);
            answerPictureChange(query, parseFormBody(headers, body),
                    "Fetching the pictures again; the sentence above updates when it is done.",
                    true, output);
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
        } else if (path.equals("/api/stats") && method.equals("GET")) {
            writeResponse(output, 200, "application/json",
                    bytes(kioskService.statsJson().toString()));
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

        String contentType = headers.getOrDefault("content-type", "");
        if (method.equals("POST") && contentType.contains("application/json") && body.length > 0) {
            try {
                JSONObject envelope = new JSONObject(new String(body, StandardCharsets.UTF_8));
                command = envelope.optString("command", "");
                JSONObject args = envelope.optJSONObject("args");
                if (args != null) {
                    percent = args.optInt("percent", -1);
                    url = args.has("url") ? args.optString("url", null) : null;
                    value = args.has("value") ? args.optString("value", null) : null;
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
                command, new KioskCommandDispatcher.CommandArgs(percent, url, enabled, value));
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
     * for the brightness slider and the auto-brightness checkbox. The boxes that keep their Save
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
                String stale = staleFormRefusal(form,
                        fresh.dashboardUrl + "|" + fresh.deviceId);
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
                String deviceId = form.getOrDefault("device_id", fresh.deviceId).trim();
                String idProblem = KioskCommandDispatcher.validateDeviceId(deviceId);
                if (idProblem != null) {
                    return "Not saved: " + idProblem + ".";
                }
                KioskConfig.edit(context)
                        .dashboardUrl(url)
                        .deviceId(deviceId)
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
            case "mqtt": {
                String stale = staleFormRefusal(form,
                        fresh.mqttHost + "|" + fresh.mqttPort + "|" + fresh.mqttUsername);
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
                        .mqttUsername(form.getOrDefault("mqtt_username", fresh.mqttUsername));
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
                String portProblem = KioskCommandDispatcher.validateAdminPort(adminPort);
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
            // Named after a box that no longer exists, and kept anyway: it is the wire name
            // POST /api/setting has always accepted. It now covers the stats-overlay switch alone,
            // the publish interval having been removed. See settingScript.
            case "behaviour":
                // Presence used to carry the meaning, because an unchecked box sends nothing and
                // the whole box was posted at once. These controls now post one at a time as they
                // are touched, so presence would read every post as "the others were just
                // cleared". The value carries the meaning instead, and an absent key is simply not
                // being set. No stale-form guard either: the posted value is what the operator
                // just touched, not what the page remembered. One key per request, and each key
                // applies on its own, in this order: a hand-built request carrying several keys
                // gets the earlier ones applied and the first refusal reported. The screensaver
                // keys are the exception, checked as a set before any of them is stored.
                if (form.containsKey("stats_overlay")) {
                    KioskConfig.edit(context)
                            .statsOverlay(isTrue(form.get("stats_overlay")))
                            .apply();
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
                verdict = SettingProbe.adminPort(value, boundPort);
                break;
            case "dashboard_url":
                verdict = SettingProbe.dashboardUrl(value);
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
     * The head, header and theme picker every page on this server shares.
     *
     * <p>One method rather than one copy per page, so a page added later cannot come out looking
     * like a different application. The subtitle is what each page has to say for itself: the
     * panel's id on the settings page, the way back on any page below it.
     */
    private String pageStart(String title, String subtitle) {
        return "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>Muralis admin</title><style>" + pageCss + "</style></head>"
                + "<body><main>"
                + "<header><div><h1>" + escapeHtml(title) + "</h1><p class=\"sub\">"
                + escapeHtml(subtitle) + "</p></div>" + statusChip() + "</header>"
                + "<div class=\"badges\">"
                + "<div class=\"themepick\" role=\"group\" aria-label=\"Colour theme\">"
                + "<button type=\"button\" data-theme=\"system\">Auto</button>"
                + "<button type=\"button\" data-theme=\"light\">Light</button>"
                + "<button type=\"button\" data-theme=\"dark\">Dark</button>"
                + "</div></div>";
    }

    /**
     * The settings page, optionally carrying one message about the box named by {@code
     * noticeSection}.
     *
     * <p>The message goes inside that box rather than at the top of the page. Each box is its own
     * form posting to this same page, so a save re-renders everything and the browser lands
     * wherever the fragment in the form's action points: at the box that was saved. A banner above
     * the first box would then be off screen, which is how the operator used to lose both their
     * place and the reason their save was refused.
     *
     */
    private String buildSettingsPage(String notice, String noticeSection) {
        KioskConfig config = KioskConfig.load(context);
        StringBuilder html = new StringBuilder(pageStart("Muralis", config.deviceId));

        // Only what no box on this page can carry: everything posted from a Save button names a
        // section, and that message is drawn in the section instead.
        if (notice != null && !BOXED_SECTIONS.contains(noticeSection)) {
            html.append("<p class=\"notice\">").append(escapeHtml(notice)).append("</p>");
        }

        // Every box that can be saved carries its own form and its own button, so no control is
        // stranded away from the thing that saves it.
        html.append("<div class=\"grid\">")

                .append(sectionFormStart("dashboard", "Dashboard", notice, noticeSection))
                // Each Save box carries the values it was rendered from, so a submit from a page
                // that has gone stale is refused instead of reverting a newer change; see
                // staleFormRefusal. The baseline strings here must mirror saveSettings exactly.
                .append(baselineField(config.dashboardUrl + "|" + config.deviceId))
                .append(urlField("dashboard_url", "Dashboard URL", config.dashboardUrl))
                .append(field("text", "device_id", "Device ID", config.deviceId))
                // Two buttons on one input, the shape the tablet's Dashboard card has had since
                // 2026-09-07: Save stores what is typed as THE dashboard; Open once shows it
                // until the next kiosk restart and stores nothing (kiosk.open_url, never
                // set_url). It used to be a separate "Open a URL now" box with a "Go" button,
                // which was removed on 2026-09-08 so the two surfaces offer the same thing in
                // the same place. Open once is a submit button with its own formaction, so with
                // scripting unavailable the browser still posts the box to /api/command (which
                // reads dashboard_url for it, see handleCommand); with scripting, commandScript
                // intercepts the click and reports the result inline like every other command.
                .append("<p class=\"hint\">Open once shows the address until the next kiosk ")
                .append("restart and stores nothing.</p>")
                .append(sectionFormEnd("Save",
                        "<button type=\"submit\" id=\"open-once\" name=\"cmnd\" "
                                + "value=\"kiosk.open_url\" formaction=\"/api/command\" "
                                + "formmethod=\"post\">Open once</button>"))

                .append(sectionFormStart("mqtt", "MQTT", notice, noticeSection))
                .append(baselineField(
                        config.mqttHost + "|" + config.mqttPort + "|" + config.mqttUsername))
                .append(urlField("mqtt_host", "Broker host", config.mqttHost))
                .append(field("number", "mqtt_port", "Broker port",
                        Integer.toString(config.mqttPort)))
                .append(field("text", "mqtt_username", "Username", config.mqttUsername))
                .append(field("password", "mqtt_password",
                        "Password (blank keeps the current one)", ""))
                .append(sectionFormEnd("Save"))

                .append(sectionFormStart("webadmin", "Local web admin", notice, noticeSection))
                .append(baselineField(Integer.toString(config.httpPort)))
                .append(field("number", "http_port", "Port", Integer.toString(config.httpPort)))
                .append(field("password", "http_admin_password",
                        "Admin password (blank keeps the current one)", ""))
                .append("<p class=\"hint\">At least ").append(MIN_ADMIN_PASSWORD_LENGTH)
                .append(" characters. No username.</p>")
                .append(certificateHint())
                .append(sectionFormEnd("Save"))

                .append(sectionFormStart("sequences", "Escape sequences", notice, noticeSection))
                .append("<input type=\"hidden\" name=\"sequence_baseline\" value=\"")
                .append(escapeHtml(config.settingsSequence + "|" + config.launcherSequence))
                .append("\">")
                .append("<p class=\"hint\">Corner taps in order: TL, TR, BL, BR, separated by ")
                .append("commas. Between ").append(EscapeSequence.MIN_LENGTH).append(" and ")
                .append(EscapeSequence.MAX_LENGTH).append(" taps. Recording them on the tablet ")
                .append("is easier than typing them here.</p>")
                .append(field("text", "settings_sequence", "Open Muralis settings",
                        config.settingsSequence))
                .append(field("text", "launcher_sequence", "Leave Muralis for the home screen",
                        config.launcherSequence))
                .append("<p class=\"hint\">Optional PIN, asked after either combination: ")
                .append(EscapePin.MIN_LENGTH).append(" to ").append(EscapePin.MAX_LENGTH)
                .append(" digits. A combination can be watched and repeated; a PIN has to be known. ")
                .append(KioskConfig.escapePinSet(context) ? "One is set." : "None is set.").append("</p>")
                .append(field("password", "escape_pin", "PIN (blank keeps the current one)", ""))
                .append(KioskConfig.escapePinSet(context)
                        ? "<label class=\"check\"><input type=\"checkbox\" name=\"escape_pin_clear\" "
                                + "value=\"1\">Remove the PIN</label>"
                        : "")
                .append(sectionFormEnd("Save"))

                // Kept sorted by label; add new actions in alphabetical place. There is no
                // "Main dashboard" (kiosk.home) action any more, dropped 2026-09-07 together with
                // the tablet's button of the same name: on the tablet it was pressed in place of
                // the save, and it was decided that the two surfaces offer the same set. The way back
                // from a one-off URL is "Restart kiosk", which the one-off box's hint already
                // promises, and Home Assistant keeps its kiosk.home button.
                .append("<fieldset><legend>Quick actions</legend><div class=\"actions\">")
                .append(quickAction("system.reboot", "Reboot"))
                .append(quickAction("kiosk.reload", "Reload"))
                .append(quickAction("kiosk.restart", "Restart kiosk"))
                .append("</div></fieldset>")

                .append("<fieldset><legend>Display</legend><div class=\"actions\">")
                .append(quickAction("display.wake", "Display on"))
                .append(quickAction("display.visual_off", "Display off"))
                .append("</div>")
                .append(brightnessControl())
                .append(autoBrightnessControl())
                .append(writeSettingsHint())
                .append(orientationControl())
                .append(displayOffControl())
                .append("</fieldset>")

                .append(screensaverCard())

                // The switch sits under the readout it governs, so "what is this?" and "show
                // it on the glass too" are one glance apart. No form and no Save button: it stands
                // alone and applies itself, the way the brightness controls already did.
                // data-setting names the field it posts; see settingScript.
                .append("<fieldset><legend>System stats</legend>")
                .append("<pre id=\"stats\">loading...</pre>")
                .append("<label class=\"check\"><input type=\"checkbox\" ")
                .append("data-setting=\"stats_overlay\" id=\"stats-overlay\"")
                .append(config.statsOverlay ? " checked" : "")
                .append("> Show system stats on the dashboard</label>")
                .append("</fieldset>")

                .append("<fieldset><legend>Legal</legend><div class=\"actions\">")
                .append(navButton("/privacy", context.getString(R.string.privacy_policy_title)))
                .append(navButton("/terms", context.getString(R.string.terms_title)))
                .append("</div></fieldset>")

                .append("</div>");

        html.append(commandScript);
        html.append(settingScript);
        html.append(checkScript);
        html.append(statsScript);
        html.append(themeScript);
        html.append("</main></body></html>");
        return html.toString();
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
        StringBuilder html = new StringBuilder();
        html.append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
                .append("<title>").append(escapeHtml(title)).append(" &middot; Muralis</title>")
                .append("<style>").append(pageCss)
                .append("main{max-width:640px}h2{color:var(--accent);font-size:1rem;"
                        + "letter-spacing:.04em;margin:1.6rem 0 .4rem}"
                        + "p.doc{margin:.2rem 0}</style></head><body><main>")
                .append(navButton("/", "← Back"))
                .append("<h1>").append(escapeHtml(title)).append("</h1>");
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
        html.append("</main></body></html>");
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
                percent = display.optInt("brightness_percent", 70);
                sensorInCharge = display.optBoolean("auto", false);
                mode = describeBrightnessMode(display.optString("source"), sensorInCharge);
            }
        } catch (RuntimeException unavailable) {
            // Fall back to the old fixed position rather than dropping the control.
        }
        percent = Math.max(1, Math.min(100, percent));
        // The mode label is ALWAYS rendered and carries an id, so statsScript can keep it current.
        // It first shipped as a bare "(automatic)" with no id, emitted only in automatic mode: it never
        // changed to "manual" when the checkbox was cleared, and never changed at all after page load,
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
     * Names the brightness mode, from the checkbox and nothing else.
     *
     * <p>Two states only, by design. There was briefly a third, "manual override", for a window
     * override pinned above automatic mode; it was removed by making {@code display.brightness} write
     * the system setting instead, so the state cannot arise. **The checkbox is the mode**: ticked means
     * the sensor decides, cleared means the slider does, and this label can never contradict it.
     *
     * <p>{@code display_off} is not a mode. It is the {@code display.visual_off} presentation state,
     * where the panel is dimmed to 1% behind a black overlay, and it says so rather than claiming the
     * operator chose 1%.
     */
    private static String describeBrightnessMode(String source, boolean autoMode) {
        if ("display_off".equals(source)) {
            return "display off";
        }
        if ("screensaver".equals(source)) {
            return "screensaver";
        }
        return autoMode ? "automatic" : "manual";
    }

    /**
     * The orientation toggle. A command rather than a {@code data-setting}, deliberately: a setting
     * post only persists, while this has to turn the panel now, and the command path already carries
     * the change through KioskService to the activity that owns the window.
     */
    private String orientationControl() {
        String current = KioskConfig.orientationOf(context);
        StringBuilder options = new StringBuilder();
        // "auto" only where an accelerometer exists to drive it, the same gate the
        // auto-brightness checkbox above sits behind.
        if (KioskService.hasAccelerometer(context)) {
            options.append(selectOption("auto", "Auto-rotate", current));
        }
        options.append(selectOption("landscape", "Landscape", current));
        options.append(selectOption("portrait", "Portrait", current));
        // A plain label, not label.check: that class is display:flex for a checkbox and its text,
        // and a select carries width:100%, so the two fought over one line and the caption ended
        // up beside the control instead of above it, alone among this page's fields. The default
        // block label is what Port, Broker host and every other field here already use.
        return "<label>Orientation<select id=\"orientation\">"
                + options + "</select></label>";
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
                    : library.saveLocal(part.filename, part.data);
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

    /**
     * The Pictures mode's settings: where the pictures come from and how they are shown.
     *
     * <p>Which pictures a panel actually has is a different question and lives in
     * {@link #pictureLibraryBox}, because it needs the width and this box does not.
     */
    private String pictureOptions(ScreensaverPolicy.Settings saver) {
        PictureLibrary library = PictureLibrary.get(context);
        boolean local = PictureSources.LOCAL.equals(saver.source);
        StringBuilder sources = new StringBuilder();
        sources.append(selectOption(PictureSources.LOCAL,
                "This panel: uploads and folders of your own", saver.source));
        sources.append(selectOption(PictureSources.BING, "Bing image of the day (unofficial, credited)",
                saver.source));
        sources.append(selectOption(PictureSources.WIKIMEDIA,
                "Wikimedia Commons picture of the day (credited)", saver.source));
        StringBuilder transitions = new StringBuilder();
        transitions.append(selectOption(ScreensaverPolicy.TRANSITION_NONE, "Cut", saver.transition));
        transitions.append(selectOption(ScreensaverPolicy.TRANSITION_FADE, "Fade", saver.transition));
        transitions.append(selectOption(ScreensaverPolicy.TRANSITION_SLIDE, "Slide", saver.transition));
        StringBuilder corners = new StringBuilder();
        corners.append(selectOption(ScreensaverPolicy.CORNER_BOTTOM_LEFT, "Bottom left", saver.creditCorner));
        corners.append(selectOption(ScreensaverPolicy.CORNER_BOTTOM_RIGHT, "Bottom right", saver.creditCorner));
        corners.append(selectOption(ScreensaverPolicy.CORNER_TOP_LEFT, "Top left", saver.creditCorner));
        corners.append(selectOption(ScreensaverPolicy.CORNER_TOP_RIGHT, "Top right", saver.creditCorner));

        String online = "<div id=\"screensaver-online\"" + (local ? " class=\"gone\"" : "") + ">"
                + "<form method=\"post\" action=\"/api/pictures/refresh\" style=\"display:inline\">"
                + "<button type=\"submit\">Fetch the pictures again</button></form>"
                + "<p class=\"hint\">Bing's archive is an unofficial endpoint; its pictures are "
                + "copyrighted and shown with the line Bing prints under them. Wikimedia Commons "
                + "pictures carry free licences that require the author and licence to be named, "
                + "which the credit line does.</p></div>";

        String sourceProblem = library.problem(saver.source);
        return "<div id=\"screensaver-pictures\""
                + (ScreensaverPolicy.PICTURES.equals(saver.mode) ? "" : " class=\"gone\"") + ">"
                + "<label>Pictures from<select id=\"screensaver-source\" data-setting=\"screensaver_source\">"
                + sources + "</select></label>"
                + "<p class=\"hint" + (sourceProblem != null ? " bad" : "") + "\" id=\"screensaver-source-note\">"
                + escapeHtml(library.state(saver.source)) + "</p>"
                + online
                + "<label>Each picture stays for (seconds)"
                + "<input type=\"number\" id=\"screensaver-picture-s\" data-setting=\"screensaver_picture_s\""
                + " min=\"1\" max=\"" + ScreensaverPolicy.MAX_SECONDS + "\" step=\"1\" value=\""
                + saver.pictureSeconds + "\"></label>"
                + "<label>Change of picture<select id=\"screensaver-transition\" data-setting=\"screensaver_transition\">"
                + transitions + "</select></label>"
                + "<label class=\"check\"><input type=\"checkbox\" id=\"screensaver-shuffle\" data-setting=\"screensaver_shuffle\""
                + (saver.shuffle ? " checked" : "") + "> Shuffle the order</label>"
                + "<label class=\"check\"><input type=\"checkbox\" id=\"screensaver-one\" data-setting=\"screensaver_one_per_cycle\""
                + (saver.onePerCycle ? " checked" : "") + "> One picture per screensaver, the next one next time</label>"
                + "<label class=\"check\"><input type=\"checkbox\" id=\"screensaver-credit\" data-setting=\"screensaver_credit\""
                + (saver.creditShown() ? " checked" : "") + (local ? "" : " disabled")
                + "> Show the title and credit line (always on for the online sources)</label>"
                + "<label>Credit line in the corner<select id=\"screensaver-corner\" data-setting=\"screensaver_credit_corner\">"
                + corners + "</select></label>"
                + "</div>";
    }

    /**
     * The playlists, the folder browser and the upload form: everything that changes <em>which</em>
     * pictures this panel has.
     *
     * <p>A box of its own, the full width of the page, because it is two panes and a table and it
     * was being squeezed into a 300 px column beside the fields (Juri, 2026-09-10, C6 and C8).
     * Shown only for the Pictures mode with this panel as the source; there is nothing to browse
     * when the pictures come from Bing.
     */
    private String pictureLibraryBox(String at) {
        ScreensaverPolicy.Settings saver = KioskConfig.screensaverOf(context);
        boolean shown = ScreensaverPolicy.PICTURES.equals(saver.mode)
                && PictureSources.LOCAL.equals(saver.source);
        return "<fieldset id=\"screensaver-library\" class=\"wide" + (shown ? "" : " gone") + "\">"
                + "<legend>Playlists and pictures</legend>"
                + "<div id=\"picture-browser\">" + pictureBrowser(at) + "</div>"
                + "<form id=\"picture-upload\" method=\"post\" action=\"/api/pictures\""
                + " enctype=\"multipart/form-data\">"
                + "<label>Add pictures (JPEG, PNG or WebP; up to "
                + (MAX_UPLOAD_BYTES / (1024 * 1024)) + " MB per upload)"
                + "<input type=\"file\" name=\"picture\""
                + " accept=\"image/jpeg,image/png,image/webp\" multiple>"
                + "</label><button type=\"submit\">Upload</button></form>"
                // Directly under the button that fills it, so the answer to "did that work" is
                // where the eye already is rather than at the top of a page that scrolled away
                // (Juri, 2026-09-10, C4).
                + "<p class=\"banner gone\" id=\"picture-banner\" role=\"status\"><span></span>"
                + "<button type=\"button\" class=\"dismiss\" aria-label=\"Close\">&times;</button>"
                + "</p>"
                + "</fieldset>";
    }

    /**
     * The playlists and the folder browser, as one fragment.
     *
     * <p>A fragment and not a page: every action inside it replaces exactly this much of the
     * document and nothing else, which is what stops the browser scrolling back to the top on
     * every folder tap and every tick (Juri, 2026-09-10, C3). {@code admin_pictures.js} fetches it
     * from {@code /api/pictures/browse}; a browser with no scripting follows the links instead and
     * gets the whole page, which is the same content in a slower way rather than a lesser one.
     *
     * <p>The browse token is {@code offset|location}, or a bare location for the first page. No
     * depth rides along: the folders come from the panel's own picture index, which hands back a
     * path, so there is nothing to measure against a tree and nothing to cap.
     */
    private String pictureBrowser(String token) {
        String location = token;
        int offset = 0;
        int separator = token.indexOf('|');
        if (separator >= 0) {
            offset = boundedToken(token.substring(0, separator), 100_000);
            location = token.substring(separator + 1);
        }
        PictureLibrary library = PictureLibrary.get(context);
        if (!library.browsesOwnStorage()) {
            return "<p class=\"hint bad\">This panel may not read its own pictures yet. Allow it "
                    + "on the panel's Screensaver settings; Android will only ask there.</p>";
        }
        PlaylistDocument document = library.playlists().load();
        PlaylistDocument.Playlist active = document.active();
        StringBuilder html = new StringBuilder(playlistTable(document, token));
        html.append("<p class=\"hint\">")
                .append(active == null
                        ? "Adding a picture below starts a playlist."
                        : "Adding or removing a picture below changes <b>"
                                + escapeHtml(active.name) + "</b>, the playlist in use.")
                .append("</p>");

        // Two panes, the same shape as the panel's own page: the folders on the left, the folder
        // that is open on the right. Flat and indented rather than a level at a time, because the
        // whole tree comes from one query and hiding it would only add round trips.
        html.append("<div class=\"panes\">");
        html.append("<div class=\"pane\"><h3>Folders</h3><ul class=\"folders\">");
        html.append(folderLink(PictureBrowser.UPLOADS, "Uploaded to Muralis",
                library.uploads(0).available, 0, location));
        for (PictureBrowser.Folder folder : library.browser().folders()) {
            html.append(folderLink(folder.path, folder.name, folder.total, folder.depth, location));
        }
        html.append("</ul></div>");

        html.append("<div class=\"pane\"><h3>")
                .append(escapeHtml(PictureBrowser.UPLOADS.equals(location) ? "Uploaded to Muralis"
                        : location.isEmpty() ? "Internal storage" : location))
                .append("</h3>");
        PictureBrowser.Page page = PictureBrowser.UPLOADS.equals(location)
                ? library.uploads(offset) : library.browser().pictures(location, offset);
        if (page.problem != null) {
            html.append("<p class=\"hint bad\">").append(escapeHtml(page.problem)).append("</p>");
        }
        java.util.Map<String, String> captions = library.captions();
        html.append("<ul class=\"pictures\">");
        for (PictureBrowser.Entry entry : page.entries) {
            boolean selected = active != null && active.items.contains(entry.uri);
            html.append("<li><span class=\"name\">").append(escapeHtml(entry.name)).append("</span>")
                    .append("<form class=\"caption\" method=\"post\" action=\"/api/pictures/caption\">")
                    .append(hidden("name", entry.uri)).append(hidden("at", token))
                    .append("<input type=\"text\" name=\"caption\" maxlength=\"200\"")
                    .append(" placeholder=\"Name for the credit\" value=\"")
                    .append(escapeHtml(captions.getOrDefault(entry.uri, "")))
                    .append("\" autocapitalize=\"off\" autocorrect=\"off\" spellcheck=\"false\">")
                    .append("<button type=\"submit\">Save the name</button></form>")
                    .append("<span class=\"acts\">")
                    .append("<form method=\"post\" action=\"/api/pictures/select\">")
                    .append(hidden("uri", entry.uri)).append(hidden("at", token))
                    .append(hidden("selected", String.valueOf(!selected)))
                    .append("<button type=\"submit\"")
                    .append(selected ? " class=\"primary\"" : "").append(">")
                    .append(selected ? "In the playlist" : "Add to playlist")
                    .append("</button></form>");
            // Only an upload gets a Delete: a file the person picked in their own storage
            // leaves the playlist but is never removed from disk here.
            if (library.isUpload(entry.uri)) {
                html.append("<form method=\"post\" action=\"/api/pictures/delete\">")
                        .append(hidden("uri", entry.uri)).append(hidden("at", token))
                        .append("<button type=\"submit\">Delete the file</button></form>");
            }
            html.append("</span></li>");
        }
        html.append("</ul>");
        if (page.entries.isEmpty() && page.problem == null) {
            html.append("<p class=\"hint\">No pictures directly in this folder.</p>");
        }
        if (page.available > page.entries.size()) {
            html.append("<p class=\"hint\">").append(offset + 1).append(" to ")
                    .append(offset + page.entries.size()).append(" of ").append(page.available)
                    .append("</p>");
        }
        html.append("<div class=\"actions\">");
        if (offset > 0) {
            html.append(pageLink(Math.max(0, offset - PictureBrowser.PAGE_SIZE) + "|" + location,
                    "Previous page"));
        }
        if (page.more) {
            html.append(pageLink((offset + PictureBrowser.PAGE_SIZE) + "|" + location,
                    "Next page"));
        }
        html.append("</div></div></div>");
        return html.toString();
    }

    /**
     * One folder as a link rather than a button.
     *
     * <p>A button is a thing that does something; a folder is a place you go, and a list of forty
     * buttons reads as forty decisions (Juri, 2026-09-10, C7). It is a real {@code href} so it
     * works with no scripting and can be opened in a second tab; the script intercepts it and
     * swaps the fragment instead of reloading.
     */
    private static String folderLink(String path, String label, int count, int depth,
            String openPath) {
        boolean open = path.equals(openPath);
        return "<li style=\"padding-left:" + (Math.min(depth, 6) * 14) + "px\">"
                + "<a class=\"folder" + (open ? " open" : "") + "\" href=\"/screensaver?at="
                + urlEncode(path) + "\" data-at=\"" + escapeHtml(path) + "\">"
                + escapeHtml(label) + " <span class=\"count\">" + count + "</span></a></li>";
    }

    /** Previous and Next: the same link, carrying an offset as well as a folder. */
    private static String pageLink(String token, String label) {
        return "<a class=\"pager\" href=\"/screensaver?at=" + urlEncode(token)
                + "\" data-at=\"" + escapeHtml(token) + "\">" + escapeHtml(label) + "</a>";
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
     * Use, rename and delete, from the table above.
     *
     * <p>One entry point for all three rather than three branches in the router, because they are
     * the same operation to this class: load the document, change it, write it back, answer with
     * the page the person is looking at. The rules and every refusal come from
     * {@link PlaylistDocument}, so the panel and this page cannot disagree about them.
     */
    private void handlePlaylistChange(String path, String query, Map<String, String> form,
            OutputStream output) throws IOException {
        PictureLibrary library = PictureLibrary.get(context);
        PlaylistDocument document = library.playlists().load();
        String id = form.getOrDefault("id", "");
        String refusal;
        String done;
        long now = System.currentTimeMillis();
        switch (path) {
            case "/api/playlists/activate":
                refusal = document.activate(id);
                done = "Playlist in use.";
                break;
            case "/api/playlists/rename":
                refusal = document.rename(id, form.getOrDefault("name", ""), now);
                done = "Playlist renamed.";
                break;
            case "/api/playlists/delete":
                refusal = document.delete(id);
                done = "Playlist deleted. The pictures themselves are not deleted.";
                break;
            case "/api/playlists":
                String fresh = library.playlists().newId();
                refusal = document.create(fresh,
                        form.getOrDefault("name", ""), now);
                done = "Playlist created. Add pictures to it below.";
                break;
            default:
                writeResponse(output, 404, "text/plain; charset=utf-8", bytes("Unknown\n"));
                return;
        }
        if (refusal == null) {
            refusal = library.playlists().store(document);
        }
        library.forgetLocalCount();
        KioskService.publishTelemetrySoon(context);
        answerPictureChange(query, form,
                refusal == null ? done : "Not changed: " + refusal + ".", refusal == null, output);
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
     * The playlists as a real table: name and count, then Use, a rename box and Delete.
     *
     * <p>A {@code <table>} with header scopes rather than divs, so it is a table to a screen
     * reader and to keyboard navigation as well as to the eye, which is what parity with the panel
     * means here. It had no styling of its own at all until 2026-09-10, so every cell ran into the
     * next one at both widths Juri tried it at (his C6).
     */
    private String playlistTable(PlaylistDocument document, String at) {
        StringBuilder html = new StringBuilder("<h3>Playlists</h3>");
        if (document.size() == 0) {
            html.append("<p class=\"hint\">No playlists yet. Name one below, or add a picture and "
                    + "one is started for you.</p>");
        } else {
            html.append("<div class=\"tablewrap\"><table class=\"playlists\">"
                    + "<thead><tr><th scope=\"col\">Playlist</th><th scope=\"col\">Pictures</th>"
                    + "<th scope=\"col\">In use</th><th scope=\"col\">Rename</th>"
                    + "<th scope=\"col\">Delete</th></tr></thead><tbody>");
            for (PlaylistDocument.Playlist playlist : document.all()) {
                html.append("<tr><th scope=\"row\">").append(escapeHtml(playlist.name))
                        .append("</th><td class=\"count\">").append(playlist.items.size())
                        .append("</td><td>");
                if (playlist.active) {
                    html.append("<span class=\"inuse\">In use</span>");
                } else {
                    html.append("<form method=\"post\" action=\"/api/playlists/activate\">")
                            .append(hidden("id", playlist.id))
                            .append(hidden("at", at))
                            .append("<button type=\"submit\">Use</button></form>");
                }
                html.append("</td><td>")
                        .append("<form method=\"post\" action=\"/api/playlists/rename\">")
                        .append(hidden("id", playlist.id))
                        .append(hidden("at", at))
                        .append("<input type=\"text\" name=\"name\" maxlength=\"")
                        .append(PlaylistDocument.MAX_NAME_LENGTH)
                        .append("\" value=\"").append(escapeHtml(playlist.name))
                        .append("\" autocapitalize=\"off\" autocorrect=\"off\" spellcheck=\"false\">")
                        .append("<button type=\"submit\">Rename</button></form></td><td>")
                        .append("<form method=\"post\" action=\"/api/playlists/delete\">")
                        .append(hidden("id", playlist.id))
                        .append(hidden("at", at))
                        .append("<button type=\"submit\">Delete</button></form></td></tr>");
            }
            html.append("</tbody></table></div>");
        }
        // Naming a playlist before picking its pictures is the panel's own order, and without this
        // the only way to start one here was to add a picture and rename what appeared.
        html.append("<form class=\"newplaylist\" method=\"post\" action=\"/api/playlists\">")
                .append(hidden("at", at))
                .append("<input type=\"text\" name=\"name\" maxlength=\"")
                .append(PlaylistDocument.MAX_NAME_LENGTH)
                .append("\" placeholder=\"New playlist name\"")
                .append(" autocapitalize=\"off\" autocorrect=\"off\" spellcheck=\"false\">")
                .append("<button type=\"submit\">Create playlist</button></form>");
        return html.toString();
    }

    /**
     * Answers a change made from the picture browser.
     *
     * <p>Two callers and one method. The page's own script asks for a fragment and gets a sentence
     * and a flag, which it shows in the banner and then re-reads the browser with, so nothing
     * navigates and the reader keeps their place. A browser with no scripting posts the form for
     * real and gets the whole screensaver page back, which is all a form submit can do.
     */
    private void answerPictureChange(String query, Map<String, String> form, String message,
            boolean ok, OutputStream output) throws IOException {
        if ("1".equals(queryValue(query, "fragment"))) {
            java.util.Map<String, Object> answer = new java.util.LinkedHashMap<>();
            answer.put("ok", ok);
            answer.put("message", message);
            writeResponse(output, ok ? 200 : 400, "application/json; charset=utf-8",
                    bytes(TinyJson.write(answer)));
            return;
        }
        writeResponse(output, ok ? 200 : 400, "text/html; charset=utf-8",
                bytes(buildScreensaverPage(message, form.getOrDefault("at", ""))));
    }

    /** One decoded parameter from a query string, or "" when it is not there. */
    private static String queryValue(String query, String name) {
        return parseQuery(query).getOrDefault(name, "");
    }

    /** One non-negative number from a browse token, clamped, never a reason to fail a page. */
    private static int boundedToken(String value, int ceiling) {
        try {
            return Math.max(0, Math.min(ceiling, Integer.parseInt(value.trim())));
        } catch (NumberFormatException notANumber) {
            return 0;
        }
    }

    private static String hidden(String name, String value) {
        return "<input type=\"hidden\" name=\"" + name + "\" value=\"" + escapeHtml(value) + "\">";
    }

    /** Why the wake choice is greyed out for the film; the same words admin_setting.js uses. */
    static final String WAKE_CHOICE_FILM_NOTE =
            "The black film has nothing to glance at: a wake shows the page.";

    /**
     * The Screensaver card on the settings page: the mode, the sentence it adds up to, the two
     * actions, and the way to the rest of it.
     *
     * <p>The rest used to be a {@code <details>} inside this card, which meant a two-pane picture
     * browser and a playlist table were unfolding inside a 300 px column of a multi-column page.
     * They have a page of their own now, which Juri asked for in as many words: "More screensaver
     * settings should open its own page same as the APP version because it is more clear to work
     * on" (2026-09-10, C5).
     */
    private String screensaverCard() {
        ScreensaverPolicy.Settings saver = KioskConfig.screensaverOf(context);
        boolean problem = saver.enabled()
                && ScreensaverPolicy.modeProblem(saver.mode, saver.url) != null;
        return "<fieldset><legend>Screensaver</legend>"
                + "<label>Mode<select id=\"screensaver-mode\" data-setting=\"screensaver_mode\">"
                + screensaverModeOptions(saver) + "</select></label>"
                + "<p class=\"hint" + (problem ? " bad" : "") + "\" id=\"screensaver-note\">"
                + escapeHtml(ScreensaverPolicy.describe(saver,
                        KioskRuntimeState.screensaverActive()))
                + "</p>"
                + "<div class=\"actions\">"
                + quickAction("screensaver.start", "Show it now")
                + quickAction("screensaver.stop", "Back to the page")
                + navButton("/screensaver", "More screensaver settings")
                + "</div></fieldset>";
    }

    private String screensaverModeOptions(ScreensaverPolicy.Settings saver) {
        return selectOption(ScreensaverPolicy.OFF, "Off", saver.mode)
                + selectOption(ScreensaverPolicy.DIM, "Dimmed page", saver.mode)
                + selectOption(ScreensaverPolicy.FILM, "Black film", saver.mode)
                + selectOption(ScreensaverPolicy.URL, "Web page", saver.mode)
                + selectOption(ScreensaverPolicy.PICTURES, "Pictures", saver.mode);
    }

    /**
     * The screensaver's own page: the timings and the mode's own options side by side, and the
     * playlists and the picture browser below them at the full width of the window.
     *
     * <p>{@code at} is where the folder browser is looking. It is a per-request value that is
     * never stored, so two browsers on two machines cannot fight over it, and it rides in the
     * query string rather than in a POST body so that reloading this page, or coming back to it
     * after the session has lapsed and signing in again, shows the same folder instead of a bare
     * "Not found" (Juri, 2026-09-10, C1).
     */
    private String buildScreensaverPage(String notice, String at) {
        ScreensaverPolicy.Settings saver = KioskConfig.screensaverOf(context);
        boolean problem = saver.enabled()
                && ScreensaverPolicy.modeProblem(saver.mode, saver.url) != null;
        String onWake = selectOption(ScreensaverPolicy.WAKE_SCREENSAVER,
                "Screensaver first, a touch opens the page", saver.onWake)
                + selectOption(ScreensaverPolicy.WAKE_DASHBOARD, "The page at once", saver.onWake);
        StringBuilder html = new StringBuilder(
                pageStart("Screensaver", "What the panel shows when nobody touches it"));
        html.append("<div class=\"actions back\">").append(navButton("/", "← Back to settings"))
                .append("</div>");
        if (notice != null && !notice.isEmpty()) {
            html.append("<p class=\"notice\">").append(escapeHtml(notice)).append("</p>");
        }
        html.append("<div class=\"grid\">")
                .append("<fieldset><legend>When it comes on</legend>")
                .append("<label>Mode<select id=\"screensaver-mode\" data-setting=\"screensaver_mode\">")
                .append(screensaverModeOptions(saver)).append("</select></label>")
                .append("<p class=\"hint").append(problem ? " bad" : "").append("\" id=\"screensaver-note\">")
                .append(escapeHtml(ScreensaverPolicy.describe(saver,
                        KioskRuntimeState.screensaverActive())))
                .append("</p>")
                .append("<label>Idle before the screensaver (seconds, 0 = off)")
                .append("<input type=\"number\" id=\"screensaver-idle\" data-setting=\"screensaver_idle_s\"")
                .append(" min=\"0\" max=\"").append(ScreensaverPolicy.MAX_SECONDS)
                .append("\" step=\"1\" value=\"").append(saver.idleSeconds).append("\"></label>")
                .append("<label>Screensaver before display off (seconds, 0 = never)")
                .append("<input type=\"number\" id=\"screensaver-off\" data-setting=\"screensaver_off_s\"")
                .append(" min=\"0\" max=\"").append(ScreensaverPolicy.MAX_SECONDS)
                .append("\" step=\"1\" value=\"").append(saver.offSeconds).append("\"></label>")
                .append("<label id=\"screensaver-url-field\"")
                .append(ScreensaverPolicy.URL.equals(saver.mode) ? "" : " class=\"gone\"")
                .append(">Web page to show")
                .append("<input type=\"text\" id=\"screensaver-url\" data-setting=\"screensaver_url\"")
                .append(" inputmode=\"url\" autocapitalize=\"off\" autocorrect=\"off\" spellcheck=\"false\"")
                .append(" placeholder=\"").append(KioskCommandDispatcher.EXAMPLE_DASHBOARD_URL)
                .append("\" value=\"").append(escapeHtml(saver.url)).append("\"></label>")
                .append("<label id=\"screensaver-dim-field\"")
                .append(ScreensaverPolicy.DIM.equals(saver.mode) ? "" : " class=\"gone\"")
                .append(">Brightness while dimmed (percent)")
                .append("<input type=\"number\" id=\"screensaver-dim\" data-setting=\"screensaver_dim_percent\"")
                .append(" min=\"1\" max=\"100\" step=\"1\" value=\"").append(saver.dimPercent)
                .append("\"></label>")
                .append("<label>After a wake from display off")
                .append("<select id=\"screensaver-on-wake\" data-setting=\"screensaver_on_wake\"")
                .append(ScreensaverPolicy.wakeChoiceApplies(saver.mode) ? "" : " disabled title=\""
                        + WAKE_CHOICE_FILM_NOTE + "\"")
                .append(">").append(onWake).append("</select></label>")
                .append("<div class=\"actions\">")
                .append(quickAction("screensaver.start", "Show it now"))
                .append(quickAction("screensaver.stop", "Back to the page"))
                .append("</div>")
                .append("</fieldset>")
                .append("<fieldset><legend>Pictures</legend>")
                .append(pictureOptions(saver))
                .append("</fieldset>")
                .append("</div>")
                .append(pictureLibraryBox(at));
        html.append(commandScript);
        html.append(settingScript);
        html.append(pictureScript);
        html.append(statsScript);
        html.append(themeScript);
        html.append("</main></body></html>");
        return html.toString();
    }

    /**
     * The screensaver's instant settings. Each field is checked by the same rule the tablet
     * applies and refused with the reason, never clamped: a value that cannot be stored is not
     * stored, and the page's field turns red with the sentence.
     */
    private String saveScreensaverSettings(Map<String, String> form) {
        KioskConfig.Editor editor = KioskConfig.edit(context);
        boolean changed = false;
        if (form.containsKey("screensaver_mode")) {
            String mode = form.get("screensaver_mode");
            if (!ScreensaverPolicy.isMode(mode)) {
                return "Not saved: the screensaver mode must be off, dim, film or url.";
            }
            editor.screensaverMode(mode);
            changed = true;
        }
        if (form.containsKey("screensaver_idle_s")) {
            Integer seconds = ScreensaverPolicy.parseSeconds(form.get("screensaver_idle_s"));
            if (seconds == null) {
                return "Not saved: the idle time " + ScreensaverPolicy.SECONDS_RULE + ".";
            }
            editor.screensaverIdleSeconds(seconds);
            changed = true;
        }
        if (form.containsKey("screensaver_off_s")) {
            Integer seconds = ScreensaverPolicy.parseSeconds(form.get("screensaver_off_s"));
            if (seconds == null) {
                return "Not saved: the time before display off "
                        + ScreensaverPolicy.SECONDS_RULE + ".";
            }
            editor.screensaverOffSeconds(seconds);
            changed = true;
        }
        if (form.containsKey("screensaver_url")) {
            String url = form.get("screensaver_url").trim();
            if (!url.isEmpty()) {
                String problem = KioskCommandDispatcher.validateDashboardUrl(url);
                if (problem != null) {
                    return "Not saved: " + problem + ".";
                }
            }
            editor.screensaverUrl(url);
            changed = true;
        }
        if (form.containsKey("screensaver_dim_percent")) {
            Integer percent = ScreensaverPolicy.parseDimPercent(form.get("screensaver_dim_percent"));
            if (percent == null) {
                return "Not saved: the dimmed brightness " + ScreensaverPolicy.DIM_RULE + ".";
            }
            editor.screensaverDimPercent(percent);
            changed = true;
        }
        if (form.containsKey("screensaver_on_wake")) {
            String onWake = form.get("screensaver_on_wake");
            if (!ScreensaverPolicy.isOnWake(onWake)) {
                return "Not saved: the wake choice must be screensaver or dashboard.";
            }
            editor.screensaverOnWake(onWake);
            changed = true;
        }
        if (form.containsKey("screensaver_source")) {
            String source = form.get("screensaver_source");
            if (!PictureSources.isSource(source)) {
                return "Not saved: the picture source must be local, bing or wikimedia.";
            }
            editor.screensaverSource(source);
            changed = true;
        }
        if (form.containsKey("screensaver_picture_s")) {
            Integer seconds = ScreensaverPolicy.parsePictureSeconds(form.get("screensaver_picture_s"));
            if (seconds == null) {
                return "Not saved: the time per picture " + ScreensaverPolicy.PICTURE_SECONDS_RULE + ".";
            }
            editor.screensaverPictureSeconds(seconds);
            changed = true;
        }
        if (form.containsKey("screensaver_transition")) {
            String transition = form.get("screensaver_transition");
            if (!ScreensaverPolicy.isTransition(transition)) {
                return "Not saved: the transition must be none, fade or slide.";
            }
            editor.screensaverTransition(transition);
            changed = true;
        }
        if (form.containsKey("screensaver_shuffle")) {
            editor.screensaverShuffle(isTrue(form.get("screensaver_shuffle")));
            changed = true;
        }
        if (form.containsKey("screensaver_one_per_cycle")) {
            editor.screensaverOnePerCycle(isTrue(form.get("screensaver_one_per_cycle")));
            changed = true;
        }
        if (form.containsKey("screensaver_credit")) {
            editor.screensaverCredit(isTrue(form.get("screensaver_credit")));
            changed = true;
        }
        if (form.containsKey("screensaver_credit_corner")) {
            String corner = form.get("screensaver_credit_corner");
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
        return "<option value=\"" + value + "\"" + (value.equals(current) ? " selected" : "")
                + ">" + label + "</option>";
    }

    /**
     * How "Display off" darkens the panel, as a select posted the moment it changes, with the
     * sentence the tablet's Display card shows under it, kept current by the stats poll. The real
     * screen-off is offered only to a device owner, the same gate the tablet applies.
     */
    private String displayOffControl() {
        String current = KioskConfig.displayOffMethodOf(context);
        StringBuilder options = new StringBuilder();
        options.append(selectOption(DisplayOffPolicy.AUTO, "Automatic", current));
        if (KioskService.isDeviceOwner(context)) {
            options.append(selectOption(DisplayOffPolicy.SLEEP, "Turn the screen off", current));
        }
        options.append(selectOption(DisplayOffPolicy.FILM, "Black film", current));
        return "<label>Display off<select id=\"display-off-method\" "
                + "data-setting=\"display_off_method\">" + options + "</select></label>"
                + "<p class=\"hint" + (KioskService.displayOffWarning(context) ? " bad" : "")
                + "\" id=\"display-off-note\">"
                + escapeHtml(KioskService.describeDisplayOff(context)) + "</p>";
    }

    /**
     * The automatic-brightness control: a checkbox showing state, or nothing at all.
     *
     * <p>Omitted entirely on hardware with no ambient light sensor, because a control that cannot work
     * is worse than an absent one. The command behind it agrees:
     * {@code display.auto_brightness} is rejected with "this device has no ambient light sensor"
     * rather than silently accepted.
     *
     * <p><b>This was a button whose label flipped</b>, reading "Switch to manual brightness" when
     * automatic was on. Two problems, and the second was a bug. It described an action while looking
     * like a statement of state, which is genuinely ambiguous; and the state was read once when the
     * page was rendered and never refreshed, so if the mode changed afterwards, from the tablet's own
     * shade toggle or another browser, the button kept sending the stale value and did the opposite of
     * what it said. A checkbox shows the state directly, and {@code statsScript} keeps it honest by
     * syncing it from every poll.
     *
     * <p>It maps to exactly the setting a person toggles next to the brightness slider in the
     * notification shade: {@code Settings.System.SCREEN_BRIGHTNESS_MODE}, system-wide, not an
     * app-local preference.
     */
    private String autoBrightnessControl() {
        if (!KioskService.hasLightSensor(context)) {
            return "";
        }
        boolean on = KioskService.isAutoBrightnessOn(context);
        return "<label class=\"check\"><input type=\"checkbox\" id=\"auto-brightness\""
                + (on ? " checked" : "") + "> Adjust brightness automatically</label>";
    }

    /**
     * Why the brightness controls above do nothing, on a panel that has not been granted
     * {@code WRITE_SETTINGS}, or nothing at all once it has.
     *
     * <p>The same sentence the tablet's Display card shows, so the two surfaces describe one rule,
     * with the adb command in place of the tablet's grant button: this page cannot hand a browser
     * the Settings screen that grants an app-op, and on a wall-mounted panel a cable is often the
     * shorter route anyway. Both controls are affected and neither says so on its own: the
     * checkbox writes {@code SCREEN_BRIGHTNESS_MODE}, the slider writes
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
     * Opens a box that is itself a form, so its save button sits inside it.
     *
     * <p>The action carries the box's own fragment. A browser applies the fragment of the URL it
     * posted to when it renders the reply, so a save comes back to the box it was made in instead
     * of at the top of the page, which on a phone is several screens away from the MQTT fields.
     * Confirmed against Chrome rather than assumed. {@code notice}, when it is about this box,
     * is drawn under the legend for the same reason.
     */
    private static String sectionFormStart(String section, String legend, String notice,
            String noticeSection) {
        String message = notice != null && section.equals(noticeSection)
                ? "<p class=\"notice\">" + escapeHtml(notice) + "</p>"
                : "";
        return "<form method=\"post\" action=\"/#box-" + section + "\"><fieldset id=\"box-"
                + section + "\"><legend>" + escapeHtml(legend) + "</legend>" + message
                + "<input type=\"hidden\" name=\"section\" value=\"" + section + "\">";
    }

    private static String sectionFormEnd(String label) {
        return "<button class=\"primary\" type=\"submit\">" + escapeHtml(label)
                + "</button></fieldset></form>";
    }

    /** The same, with a second button beside Save; the caller supplies that button's markup. */
    private static String sectionFormEnd(String label, String besideHtml) {
        return "<div class=\"actions\"><button class=\"primary\" type=\"submit\">"
                + escapeHtml(label) + "</button>" + besideHtml + "</div></fieldset></form>";
    }

    /** The hidden input staleFormRefusal checks on submit. */
    private static String baselineField(String value) {
        return "<input type=\"hidden\" name=\"baseline\" value=\"" + escapeHtml(value) + "\">";
    }

    /**
     * One labelled input. A text box on this page holds a machine value, an address, a host, an
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
        return field(type, name, label, value, "");
    }

    private static String field(String type, String name, String label, String value,
            String extraAttributes) {
        return "<label>" + escapeHtml(label) + "<input type=\"" + type + "\" name=\"" + name
                + "\" value=\"" + escapeHtml(value) + "\"" + (type.equals("text") ? MACHINE_TEXT : "")
                + extraAttributes + "></label>";
    }

    /** A text box holding a URL or a host name: the URL keyboard on a phone, see {@link #field}. */
    private static String urlField(String name, String label, String value) {
        return field("text", name, label, value, " inputmode=\"url\"");
    }

    /** See {@link #field}: a text box that must not be autocorrected, capitalised or spellchecked. */
    private static final String MACHINE_TEXT =
            " autocapitalize=\"off\" autocorrect=\"off\" spellcheck=\"false\"";

    private static String quickAction(String command, String label) {
        return "<form class=\"cmd\" method=\"post\" action=\"/api/command\" style=\"display:inline\">"
                + "<input type=\"hidden\" name=\"cmnd\" value=\"" + command + "\">"
                + "<button type=\"submit\">" + escapeHtml(label) + "</button></form>";
    }

    /** A plain navigation, styled as a button rather than a hyperlink, matching quickAction(). */
    private static String navButton(String path, String label) {
        return "<form method=\"get\" action=\"" + path + "\" style=\"display:inline\">"
                + "<button type=\"submit\">" + escapeHtml(label) + "</button></form>";
    }

    private static String escapeHtml(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
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
    private String certificateHint() {
        if (tlsFactory == null) {
            return "<p class=\"hint bad\">Served over plain HTTP: this device could not create a "
                    + "certificate, so the password travels unencrypted. Use it on a trusted "
                    + "network only.</p>";
        }
        return "<p class=\"hint\">Served over HTTPS with a certificate this panel made itself, so "
                + "your browser warned once. Its SHA-256 fingerprint, to compare with the one the "
                + "browser shows for this page: <code>" + escapeHtml(certificateFingerprint)
                + "</code></p>";
    }

    /** BoringSSL's words for "that was an HTTP request, not a handshake". */
    private static boolean looksLikePlainHttp(IOException handshakeFailure) {
        // Conscrypt reports "Handshake failed" and keeps BoringSSL's reason in the cause.
        for (Throwable step = handshakeFailure; step != null; step = step.getCause()) {
            String message = String.valueOf(step.getMessage()).toUpperCase(Locale.ROOT);
            if (message.contains("HTTP_REQUEST") || message.contains("HTTP REQUEST")
                    || message.contains("WRONG_VERSION_NUMBER") || message.contains("WRONG VERSION")) {
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
        BodyTooLargeException(String message) {
            super(message);
        }
    }

    private static byte[] readBody(InputStream input, Map<String, String> headers, int limit)
            throws IOException {
        String lengthHeader = headers.get("content-length");
        if (lengthHeader == null) {
            return new byte[0];
        }
        int length;
        try {
            length = Integer.parseInt(lengthHeader.trim());
        } catch (NumberFormatException invalid) {
            return new byte[0];
        }
        if (length <= 0) {
            return new byte[0];
        }
        if (length > limit) {
            throw new BodyTooLargeException("the request carries " + length + " bytes, the limit is "
                    + limit);
        }
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

    private static void writeResponse(
            OutputStream output, int status, String contentType, byte[] body,
            Map<String, String> extraHeaders) throws IOException {
        StringBuilder header = new StringBuilder();
        header.append("HTTP/1.1 ").append(status).append(' ').append(reasonPhrase(status))
                .append("\r\n");
        header.append("Content-Type: ").append(contentType).append("\r\n");
        header.append("Content-Length: ").append(body.length).append("\r\n");
        header.append("Connection: close\r\n");
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
            case 404:
                return "Not Found";
            case 405:
                return "Method Not Allowed";
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
     * <p>Every response here carries {@code Connection: close}, and several of them are written
     * <em>before</em> the request body has been read, deliberately: a 401, a 403, a 413 and a 503
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
