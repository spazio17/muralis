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

/**
 * A local HTTP control/admin surface mirroring the authenticated MQTT command set, the same way
 * Tasmota or WLED let HTTP and MQTT drive identical commands. Plaintext HTTP only, intended for a
 * trusted LAN exactly like the plain-TCP MQTT transport. Fails closed: no
 * socket is bound unless an admin password has been configured locally on the device first.
 */
final class HttpAdminServer {
    private static final String TAG = "MuralisHttp";
    private static final int MIN_ADMIN_PASSWORD_LENGTH = 8;
    private static final int SOCKET_TIMEOUT_MS = 10_000;
    /** Long enough for a socket close to land, short enough that a reload never looks like a hang. */
    private static final int SHUTDOWN_WAIT_MS = 1_000;
    private static final int MAX_REQUEST_LINE_LENGTH = 4_096;
    private static final int MAX_HEADER_LINES = 40;
    private static final int MAX_BODY_BYTES = 16_384;
    /**
     * Eight rather than four, so one misbehaving host cannot own every worker.
     *
     * <p>Raised together with {@link #PER_HOST_CONNECTIONS}: the two numbers only mean anything as a
     * pair. Six held by one host out of eight workers leaves two free for everyone else, which is
     * the difference between an admin server that is slow during an attack and one that is simply
     * absent. Threads that spend their lives blocked on a socket cost a stack and nothing else, and
     * measured on the panel the whole server answers a request in 13 to 47 ms, so these are idle
     * essentially all the time.
     */
    private static final int WORKER_THREADS = 8;
    /**
     * Concurrent connections allowed from a single remote address.
     *
     * <p>Bounding the total (see {@link #ACCEPT_QUEUE_DEPTH}) stops the panel being bricked, but on
     * its own it does not keep the admin server reachable: measured against the panel, 300 sockets
     * from one host left exactly {@code WORKER_THREADS + ACCEPT_QUEUE_DEPTH} held and every
     * legitimate request refused, because the flooding host held all of them. A per-host cap is what
     * makes the difference, and on a home LAN it is effective, because the attacker is a device on
     * that LAN rather than a botnet with a thousand source addresses.
     *
     * <p>Six because that is also the per-host limit browsers use, and every response here sets
     * {@code Connection: close} with all CSS and JS inlined, so one operator page load plus its
     * polling XHRs stays under it. A refused poll is retried on the next tick and costs nothing.
     */
    private static final int PER_HOST_CONNECTIONS = 6;
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
    private final String pageCss;


    private final Context context;
    private final KioskService kioskService;

    private ServerSocket serverSocket;
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
        Log.i(TAG, "HTTP admin listening on port " + config.httpPort);
    }

    void stop() {
        running = false;
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
                // Silently, and without reading a byte. Logging here would hand an attacker a way
                // to fill the panel's log by connecting, and there is nothing an operator could do
                // with one line per refused socket anyway.
                closeQuietly(socket);
                continue;
            }
            try {
                workers.execute(() -> {
                    try {
                        handleConnection(socket);
                    } finally {
                        releaseHostSlot(host);
                    }
                });
            } catch (RejectedExecutionException busy) {
                releaseHostSlot(host);
                closeQuietly(socket);
            }
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
        try {
            socket.setSoTimeout(SOCKET_TIMEOUT_MS);
            // Wrapped, so both readLine and readBody inherit the whole-request deadline without
            // either of them having to know about it. See REQUEST_DEADLINE_MS.
            InputStream input = new DeadlineInputStream(socket.getInputStream(), REQUEST_DEADLINE_MS);
            OutputStream output = socket.getOutputStream();

            String requestLine = readLine(input, MAX_REQUEST_LINE_LENGTH);
            if (requestLine == null || requestLine.isEmpty()) {
                return;
            }
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

            byte[] body = readBody(input, headers);

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

            route(method, target, headers, body, output);
        } catch (IOException exception) {
            Log.w(TAG, "HTTP admin connection error", exception);
        } catch (RuntimeException unexpected) {
            // The barrier that keeps a bad request from killing the panel.
            //
            // These run on an ExecutorService worker, so an escaping RuntimeException reaches
            // Android's default uncaught handler and takes the whole process down with it: the
            // dashboard blinks out and every uptime counter resets. That is unacceptable here for
            // any input, and it was reachable from a single malformed query string -- URLDecoder
            // throws IllegalArgumentException on a truncated escape like "100%", which the
            // documented `?cmnd=kiosk.set_url&url=...` workflow makes easy to send by accident.
            //
            // Fail soft like the rest of the app: log it, answer 500, keep serving.
            Log.w(TAG, "HTTP admin request failed", unexpected);
            try {
                writeResponse(socket.getOutputStream(), 500, "text/plain",
                        bytes("Internal Server Error"));
            } catch (IOException | RuntimeException ignored) {
                // The socket is already unusable, or the response was partly written. Nothing left
                // to say to this client; the point was to survive, and we have.
            }
        } finally {
            liveSockets.remove(socket);
            closeQuietly(socket);
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
            String refusal = saveSettings(parseFormBody(headers, body));
            writeResponse(output, refusal == null ? 200 : 400, "text/html; charset=utf-8",
                    bytes(buildSettingsPage(refusal)));
            if (refusal == null) {
                // Reload asynchronously through the service's own message queue: doing it inline
                // here would have this worker thread join the very executor it is running on.
                KioskService.reloadConfiguration(context);
            }
        } else if (path.equals("/api/setting") && method.equals("POST")) {
            handleSetting(parseFormBody(headers, body), output);
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
            // automatically, and <img src=".../api/check?kind=mqtt_host&value=10.0.0.5&port=22">
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

        String contentType = headers.getOrDefault("content-type", "");
        if (method.equals("POST") && contentType.contains("application/json") && body.length > 0) {
            try {
                JSONObject envelope = new JSONObject(new String(body, StandardCharsets.UTF_8));
                command = envelope.optString("command", "");
                JSONObject args = envelope.optJSONObject("args");
                if (args != null) {
                    percent = args.optInt("percent", -1);
                    url = args.has("url") ? args.optString("url", null) : null;
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
                command, new KioskCommandDispatcher.CommandArgs(percent, url, enabled));
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
                // just touched, not what the page remembered.
                if (form.containsKey("stats_overlay")) {
                    KioskConfig.edit(context)
                            .statsOverlay(isTrue(form.get("stats_overlay")))
                            .apply();
                }
                return null;
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
        if (settingsSequence.equals(launcherSequence)) {
            return "Not saved: the settings and launcher sequences must be different, or the "
                    + "settings gesture becomes unreachable.";
        }
        fresh.settingsSequence = settingsSequence;
        fresh.launcherSequence = launcherSequence;
        fresh.saveEscapeSequences(context);
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
        return buildSettingsPage(null);
    }

    private String buildSettingsPage(String notice) {
        KioskConfig config = KioskConfig.load(context);
        StringBuilder html = new StringBuilder();
        html.append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
                .append("<title>Muralis admin</title><style>")
                .append(pageCss)
                .append("</style></head><body><main>")
                .append("<header><div><h1>Muralis</h1><p class=\"sub\">")
                .append(escapeHtml(config.deviceId)).append("</p></div>")
                .append(statusChip())
                .append("</header>")
                .append("<div class=\"badges\">")
                .append("<div class=\"themepick\" role=\"group\" aria-label=\"Colour theme\">")
                .append("<button type=\"button\" data-theme=\"system\">Auto</button>")
                .append("<button type=\"button\" data-theme=\"light\">Light</button>")
                .append("<button type=\"button\" data-theme=\"dark\">Dark</button>")
                .append("</div></div>");

        if (notice != null) {
            html.append("<p class=\"notice\">").append(escapeHtml(notice)).append("</p>");
        }

        // Every box that can be saved carries its own form and its own button, so no control is
        // stranded away from the thing that saves it.
        html.append("<div class=\"grid\">")

                .append(sectionFormStart("dashboard", "Dashboard"))
                // Each Save box carries the values it was rendered from, so a submit from a page
                // that has gone stale is refused instead of reverting a newer change; see
                // staleFormRefusal. The baseline strings here must mirror saveSettings exactly.
                .append(baselineField(config.dashboardUrl + "|" + config.deviceId))
                .append(field("text", "dashboard_url", "Dashboard URL", config.dashboardUrl))
                .append(field("text", "device_id", "Device ID", config.deviceId))
                .append(sectionFormEnd("Save"))

                .append(sectionFormStart("mqtt", "MQTT"))
                .append(baselineField(
                        config.mqttHost + "|" + config.mqttPort + "|" + config.mqttUsername))
                .append(field("text", "mqtt_host", "Broker host", config.mqttHost))
                .append(field("number", "mqtt_port", "Broker port",
                        Integer.toString(config.mqttPort)))
                .append(field("text", "mqtt_username", "Username", config.mqttUsername))
                .append(field("password", "mqtt_password",
                        "Password (blank keeps the current one)", ""))
                .append(sectionFormEnd("Save"))

                .append(sectionFormStart("webadmin", "Local web admin"))
                .append(baselineField(Integer.toString(config.httpPort)))
                .append(field("number", "http_port", "Port", Integer.toString(config.httpPort)))
                .append(field("password", "http_admin_password",
                        "Admin password (blank keeps the current one)", ""))
                .append("<p class=\"hint\">At least ").append(MIN_ADMIN_PASSWORD_LENGTH)
                .append(" characters. No username.</p>")
                .append(sectionFormEnd("Save"))

                .append(sectionFormStart("sequences", "Escape sequences"))
                .append("<input type=\"hidden\" name=\"sequence_baseline\" value=\"")
                .append(escapeHtml(config.settingsSequence + "|" + config.launcherSequence))
                .append("\">")
                .append("<p class=\"hint\">Corner taps in order: TL, TR, BL, BR, separated by ")
                .append("commas. Between ").append(EscapeSequence.MIN_LENGTH).append(" and ")
                .append(EscapeSequence.MAX_LENGTH).append(" taps. Recording them on the tablet ")
                .append("is easier than typing them here.</p>")
                .append(field("text", "settings_sequence", "Open Muralis settings",
                        config.settingsSequence))
                .append(field("text", "launcher_sequence", "Exit to the system launcher",
                        config.launcherSequence))
                .append(sectionFormEnd("Save"))

                // Kept sorted by label; add new actions in alphabetical place.
                .append("<fieldset><legend>Quick actions</legend><div class=\"actions\">")
                .append(quickAction("kiosk.home", "Main dashboard"))
                .append(quickAction("system.reboot", "Reboot"))
                .append(quickAction("kiosk.reload", "Reload"))
                .append(quickAction("kiosk.restart", "Restart kiosk"))
                .append("</div></fieldset>")

                .append("<fieldset><legend>Display</legend><div class=\"actions\">")
                .append(quickAction("display.wake", "Wake display"))
                .append(quickAction("display.visual_off", "Display off"))
                .append("</div>")
                .append(brightnessControl())
                .append(autoBrightnessControl())
                .append(portraitControl())
                .append("</fieldset>")

                // kiosk.open_url, NOT kiosk.set_url: this box is for a URL with one-off query
                // parameters, and it used to store whatever was typed as the panel's dashboard,
                // so the way back was retyping the original by hand (reported 2026-08-24). The
                // Dashboard box above is where the stored URL changes.
                // The hint sits above the input so what the box does is read before it is used.
                // No "Main dashboard" button here: Quick actions already has it, and the way
                // back does not need to exist twice on one page.
                .append("<fieldset><legend>Open a URL now</legend>")
                .append("<p class=\"hint\">Shown until the next kiosk restart; the stored ")
                .append("dashboard is unchanged.</p>")
                .append("<form class=\"cmd\" method=\"post\" action=\"/api/command\">")
                .append("<input type=\"hidden\" name=\"cmnd\" value=\"kiosk.open_url\">")
                .append("<input type=\"text\" name=\"url\" ")
                .append("placeholder=\"http://homeassistant.local:8123/\">")
                .append("<button type=\"submit\">Go</button>")
                .append("</form></fieldset>")

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
                .append("<h1>").append(escapeHtml(title)).append("</h1>")
                .append("<p class=\"notice\">")
                .append(escapeHtml(context.getString(R.string.legal_draft_warning)))
                .append("</p>");
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
        return autoMode ? "automatic" : "manual";
    }

    /**
     * The orientation toggle. A command rather than a {@code data-setting}, deliberately: a setting
     * post only persists, while this has to turn the panel now, and the command path already carries
     * the change through KioskService to the activity that owns the window.
     */
    private String portraitControl() {
        return "<label class=\"check\"><input type=\"checkbox\" id=\"portrait\""
                + (KioskConfig.portraitEnabled(context) ? " checked" : "")
                + "> Use portrait mode</label>";
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

    /** Opens a box that is itself a form, so its save button sits inside it. */
    private static String sectionFormStart(String section, String legend) {
        return "<form method=\"post\" action=\"/\"><fieldset><legend>" + escapeHtml(legend)
                + "</legend><input type=\"hidden\" name=\"section\" value=\"" + section + "\">";
    }

    private static String sectionFormEnd(String label) {
        return "<button class=\"primary\" type=\"submit\">" + escapeHtml(label)
                + "</button></fieldset></form>";
    }

    /** The hidden input staleFormRefusal checks on submit. */
    private static String baselineField(String value) {
        return "<input type=\"hidden\" name=\"baseline\" value=\"" + escapeHtml(value) + "\">";
    }

    private static String field(String type, String name, String label, String value) {
        return "<label>" + escapeHtml(label) + "<input type=\"" + type + "\" name=\"" + name
                + "\" value=\"" + escapeHtml(value) + "\"></label>";
    }

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
        private final long deadlineAtMs;

        DeadlineInputStream(InputStream wrapped, int budgetMs) {
            super(wrapped);
            this.deadlineAtMs = android.os.SystemClock.elapsedRealtime() + budgetMs;
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

    private static byte[] readBody(InputStream input, Map<String, String> headers)
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
        if (length > MAX_BODY_BYTES) {
            throw new IOException("request body too large");
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
        output.write(header.toString().getBytes(StandardCharsets.US_ASCII));
        output.write(body);
        output.flush();
    }

    private static String reasonPhrase(int status) {
        switch (status) {
            case 200:
                return "OK";
            case 400:
                return "Bad Request";
            case 401:
                return "Unauthorized";
            case 404:
                return "Not Found";
            case 405:
                return "Method Not Allowed";
            case 413:
                return "Payload Too Large";
            case 429:
                return "Too Many Requests";
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
}
