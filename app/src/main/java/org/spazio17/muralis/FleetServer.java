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
import java.nio.charset.StandardCharsets;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * The member endpoint (the fleet design): the member port (8081 until a person sets
 * another, {@link KioskConfig#fleetPortOf}) on every panel, free, mutual TLS,
 * independent of the web admin, which may be off. A caller is admitted only if the public key it
 * presents is one this panel trusts and has not revoked ({@link FleetTls}); nothing else
 * authenticates a fleet call, and plain HTTP is dropped, not redirected.
 *
 * <p>It answers {@code GET /fleet} (this panel's id, its fleet key and the fleet it is in),
 * {@code POST /fleet/join} (the one route an unknown key reaches, while a join code is open:
 * {@link FleetJoin}) and {@code POST /fleet/leave}. The document and the commands are the next
 * blocks. One request per connection, small and bounded: a fleet call is a few hundred bytes
 * between panels, never a browser's burst.
 */
final class FleetServer {
    private static final String TAG = "MuralisFleet";
    private static final int WORKERS = 2;
    private static final int QUEUE = 4;
    /**
     * Connections one address may hold, waiting ones included, as the web admin caps its own: two
     * workers and a short queue would otherwise be one device's to hold for the whole deadline.
     */
    private static final int PER_HOST_CONNECTIONS = 2;
    /** Handshake and request together; a member answers at once or not at all. */
    private static final int CONNECTION_DEADLINE_MS = 8_000;
    private static final int MAX_LINE = 2_048;
    private static final int MAX_HEADER_LINES = 50;
    private static final int MAX_BODY = 4_096;
    /** A fleet document: a few hundred bytes per member, and the profile to come (block 12d). */
    private static final int MAX_DOCUMENT = 65_536;
    /** One document change at a time: a delivery taken here and a panel added here. */
    private static final Object DOCUMENT_LOCK = new Object();

    private final Context context;
    private final FleetIdentity identity;
    private final SSLSocketFactory tls;
    private final ServerSocket serverSocket;
    private final int port;
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(WORKERS, WORKERS, 0L,
            TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(QUEUE));
    private final ScheduledExecutorService deadlines =
            Executors.newSingleThreadScheduledExecutor(work -> new Thread(work, "MuralisFleetDeadline"));
    private volatile boolean running = true;
    private final java.util.Map<String, Integer> hostConnections = new java.util.HashMap<>();

    private FleetServer(Context context, FleetIdentity identity, ServerSocket serverSocket) {
        this.context = context.getApplicationContext();
        this.identity = identity;
        this.serverSocket = serverSocket;
        this.port = serverSocket.getLocalPort();
        // While a join code is open, an unknown key gets through the handshake too; past it, only
        // the join route serves such a key, and only for the right code (handle). A revoked key
        // gets through as well, to be told on the check-in route that it was removed, and
        // nothing else.
        this.tls = FleetTls.context(identity.key, identity.certificate,
                spki -> admits(spki) || FleetJoin.shared.isOpen(joinClock())
                        || KioskConfig.fleetRevoked(context).contains(spki))
                .getSocketFactory();
    }

    /** Read at every handshake, so a key trusted or revoked meanwhile counts at the next call. */
    private boolean admits(String spki) {
        return FleetTls.admits(spki, KioskConfig.fleetTrusted(context),
                KioskConfig.fleetRevoked(context));
    }

    /**
     * Listening on the member port, or null when the fleet key cannot be read yet or used, or the
     * port is taken. Either way the outcome is published for the Fleet section of both surfaces
     * ({@link KioskRuntimeState#publishFleetState}).
     */
    static FleetServer start(Context context) {
        int port = KioskConfig.fleetPortOf(context);
        // A web admin that already holds this port keeps it, whether it is listening right now or
        // not (switched off, waiting for the unlock): decided by the stored ports, never by which
        // server happens to bind first.
        if (port == KioskConfig.httpPortOf(context)) {
            KioskRuntimeState.publishFleetState(false, port, "the web admin uses port " + port);
            return null;
        }
        ServerSocket socket;
        try {
            socket = new ServerSocket();
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(port));
        } catch (IOException | IllegalArgumentException taken) {
            // IllegalArgumentException for a stored port no check let through: no endpoint, never
            // a crash at every start.
            Log.e(TAG, "The member endpoint cannot listen on port " + port, taken);
            KioskRuntimeState.publishFleetState(false, port,
                    "port " + port + " is in use on this device");
            return null;
        }
        FleetIdentity identity = FleetIdentity.load(context);
        if (identity == null) {
            KioskRuntimeState.publishFleetState(false, port, "the fleet key cannot be read");
            try {
                socket.close();
            } catch (IOException ignored) {
                // Closing a socket nothing has used yet.
            }
            return null;
        }
        FleetServer server;
        try {
            server = new FleetServer(context, identity, socket);
        } catch (RuntimeException refused) {
            // A key TLS will not take must not crash the service at every start: no endpoint,
            // and the port let go.
            Log.e(TAG, "The member endpoint cannot use the fleet key", refused);
            KioskRuntimeState.publishFleetState(false, port, "the fleet key cannot be used");
            try {
                socket.close();
            } catch (IOException ignored) {
                // Closing a socket nothing has used yet.
            }
            return null;
        }
        Thread accept = new Thread(server::acceptLoop, "MuralisFleetAccept");
        accept.start();
        Log.i(TAG, "Member endpoint listening on port " + port + ", fleet key "
                + FleetTls.shortForm(identity.spki));
        KioskRuntimeState.publishFleetState(true, port, "");
        KioskRuntimeState.publishFleetKey(identity.spki);
        return server;
    }

    /** The Fleet section's summary on both surfaces, stored values only like every summary. */
    static String summaryOf(int memberPort) {
        return "Port " + memberPort;
    }

    /** The line under the member port on both surfaces, worded once; the web page's poll copies it. */
    static String stateLine() {
        return KioskRuntimeState.fleetListening()
                ? "Listening on port " + KioskRuntimeState.fleetPort()
                : "Not listening: " + (KioskRuntimeState.fleetDownReason().isEmpty()
                        ? "not started yet" : KioskRuntimeState.fleetDownReason());
    }

    /**
     * The clock of the join code and its lockout: time since boot, which a change of the
     * device's date cannot stretch or cut, as {@link AuthThrottle} counts its lockouts.
     */
    static long joinClock() {
        return android.os.SystemClock.elapsedRealtime();
    }

    /**
     * Opens a join code for Join a fleet and New code, on either surface, and for nothing else:
     * never for a member or while the endpoint is down, and the open code is kept, so both
     * surfaces show the same one.
     */
    static void openJoinCode(Context context) {
        long now = joinClock();
        if (KioskConfig.fleetIdOf(context).isEmpty() && KioskRuntimeState.fleetListening()
                && FleetJoin.shared.code(now) == null) {
            FleetJoin.shared.open(now);
        }
    }

    /** The Fleet section's membership line on both surfaces, with the other panels listed. */
    static String memberLine(Context context) {
        if (KioskConfig.fleetIdOf(context).isEmpty()) {
            return "Not in a fleet";
        }
        if (!removedFrom.isEmpty()) {
            return "Removed from the fleet " + removedFrom + ". Tap Leave, then this panel can"
                    + " create or join a fleet.";
        }
        StringBuilder others = new StringBuilder();
        String self = KioskConfig.deviceIdOf(context);
        long now = android.os.SystemClock.elapsedRealtime();
        for (java.util.Map<String, Object> one
                : FleetDocument.members(KioskConfig.fleetDocumentOf(context))) {
            if (self.equals(TinyJson.string(one, FleetDocument.ID, ""))) {
                continue;
            }
            Peer known = peers.get(TinyJson.string(one, FleetDocument.SPKI, ""));
            others.append(others.length() == 0 ? "" : ", ")
                    .append(TinyJson.string(one, FleetDocument.NAME, ""))
                    .append(known == null ? " (not heard from yet)"
                            : now - known.seenAt >= OFFLINE_AFTER_MS ? " (offline)" : "");
        }
        return "Member of the fleet " + KioskConfig.fleetNameOf(context)
                + (others.length() == 0 ? "" : ", with " + others);
    }

    /** What another panel's member endpoint answered: its status and its JSON. */
    static final class Reply {
        final int status;
        final JSONObject body;

        Reply(int status, JSONObject body) {
            this.status = status;
            this.body = body;
        }

        /** The refusal as the other panel worded it. */
        String reason() {
            return body.optString("error", "the other panel answered " + status);
        }
    }

    /**
     * One call to another panel's member endpoint, as this panel with its fleet key, and only to
     * the panel whose key hash is {@code spki}: the handshake fails against any other, so an
     * address taken over on the network gets nothing. Not on the main thread.
     */
    static Reply call(Context context, String address, int port, String spki, String method,
            String path, JSONObject body) throws IOException {
        FleetIdentity identity = FleetIdentity.load(context);
        if (identity == null) {
            throw new IOException("this panel's fleet key cannot be read");
        }
        SSLSocketFactory factory = FleetTls.context(identity.key, identity.certificate,
                spki::equals).getSocketFactory();
        Socket raw = new Socket();
        try {
            raw.connect(new InetSocketAddress(address, port), CONNECTION_DEADLINE_MS);
            raw.setSoTimeout(CONNECTION_DEADLINE_MS);
            SSLSocket channel = (SSLSocket) factory.createSocket(raw, address, port, true);
            channel.setUseClientMode(true);
            channel.startHandshake();
            byte[] content = body == null ? new byte[0]
                    : body.toString().getBytes(StandardCharsets.UTF_8);
            OutputStream output = channel.getOutputStream();
            output.write((method + " " + path + " HTTP/1.1\r\nHost: panel\r\n"
                    + "Content-Type: application/json\r\nContent-Length: " + content.length
                    + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            output.write(content);
            output.flush();
            InputStream input = channel.getInputStream();
            String statusLine = readLine(input);
            String[] parts = statusLine == null ? new String[0] : statusLine.split(" ");
            if (parts.length < 2 || !parts[1].matches("[0-9]{3}")) {
                throw new IOException("the other panel did not answer as a member endpoint");
            }
            int length = -1;
            for (int lines = 0; lines < MAX_HEADER_LINES; lines++) {
                String header = readLine(input);
                if (header == null || header.isEmpty()) {
                    break;
                }
                int colon = header.indexOf(':');
                if (colon > 0 && header.substring(0, colon).trim()
                        .equalsIgnoreCase("Content-Length")
                        && header.substring(colon + 1).trim().matches("[0-9]{1,5}")) {
                    length = Integer.parseInt(header.substring(colon + 1).trim());
                }
            }
            // As much as a fleet document may be: the list is fetched and handed back this way.
            byte[] reply = length < 0 || length > MAX_DOCUMENT ? new byte[0]
                    : readBody(input, length);
            JSONObject json;
            try {
                json = new JSONObject(new String(reply, StandardCharsets.UTF_8));
            } catch (JSONException notJson) {
                json = new JSONObject();
            }
            return new Reply(Integer.parseInt(parts[1]), json);
        } finally {
            closeQuietly(raw);
        }
    }

    /**
     * The Join screen's sentence on both surfaces, worded once: what the open code is for and
     * until when, why there is none, or that this panel joined.
     */
    static String joinLine(Context context) {
        long now = joinClock();
        if (!KioskConfig.fleetIdOf(context).isEmpty()) {
            return "This panel joined the fleet " + KioskConfig.fleetNameOf(context) + ".";
        }
        if (!KioskRuntimeState.fleetListening()) {
            return stateLine() + ".";
        }
        long locked = FleetJoin.shared.lockedUntil(now);
        if (locked > 0) {
            return "Five wrong codes were tried. A new code can be made at "
                    + timeOf(context, locked, now) + ".";
        }
        if (FleetJoin.shared.code(now) == null) {
            return "The code expired or was closed.";
        }
        int wrong = FleetJoin.shared.wrongCodes();
        // Said on the screen, so a person at the panel sees a typo, or a stranger guessing.
        return (wrong == 0 ? "" : wrong + (wrong == 1 ? " wrong code was" : " wrong codes were")
                + " tried. ")
                + "Scan the code with the panel or phone that manages the fleet, or type the numbers"
                + " there. It works once, until "
                + timeOf(context, FleetJoin.shared.expiresAt(), now) + ".";
    }

    /** The QR's content for the open code, or null when none is open. */
    static String joinQrContent(Context context) {
        long now = joinClock();
        String code = FleetJoin.shared.code(now);
        SystemStats.RuntimeFacts facts = KioskRuntimeState.lastFacts();
        String address = facts == null ? "" : facts.ipAddress;
        if (code == null || !KioskRuntimeState.fleetListening() || address.isEmpty()) {
            return null;
        }
        return FleetJoin.qrContent(KioskConfig.deviceIdOf(context), address,
                KioskRuntimeState.fleetPort(), KioskRuntimeState.fleetKey(), code);
    }

    /** What both surfaces draw the Join screen from, for the web page as JSON. */
    static JSONObject joinState(Context context) {
        long now = joinClock();
        try {
            String code = FleetJoin.shared.code(now);
            SystemStats.RuntimeFacts facts = KioskRuntimeState.lastFacts();
            return new JSONObject()
                    .put("member", !KioskConfig.fleetIdOf(context).isEmpty())
                    .put("member_line", memberLine(context))
                    .put("fleet", KioskConfig.fleetNameOf(context))
                    .put("can_join", KioskRuntimeState.fleetListening())
                    .put("code", code == null ? "" : FleetJoin.grouped(code))
                    .put("qr", joinQrContent(context) != null)
                    .put("detail", joinDetail(KioskRuntimeState.fleetKey(),
                            facts == null ? "" : facts.ipAddress, KioskRuntimeState.fleetPort()))
                    .put("locked", FleetJoin.shared.lockedUntil(now) > 0)
                    .put("line", joinLine(context));
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** "Key 1a2b3c4d, at 192.0.2.5 port 8081": what the Join screen says under the code. */
    static String joinDetail(String key, String address, int port) {
        return "Key " + FleetTls.shortForm(key) + (address.isEmpty() ? "" : ", at " + address)
                + " port " + port;
    }

    /** A moment on the join clock as the time of day, worded as the device words times. */
    private static String timeOf(Context context, long at, long now) {
        return android.text.format.DateFormat.getTimeFormat(context)
                .format(new java.util.Date(System.currentTimeMillis() + (at - now)));
    }

    /** This panel's key hash, as announced on the network and answered by {@code GET /fleet}. */
    String spki() {
        return identity.spki;
    }

    /** The port this endpoint listens on. */
    int port() {
        return port;
    }

    void stop() {
        running = false;
        closeQuietly(serverSocket);
        workers.shutdownNow();
        deadlines.shutdownNow();
    }

    private void acceptLoop() {
        while (running) {
            Socket socket;
            try {
                socket = serverSocket.accept();
            } catch (IOException failed) {
                if (!running) {
                    return;
                }
                Log.w(TAG, "Member endpoint accept failed", failed);
                // The web admin's lesson: an accept that fails usually keeps failing (no file
                // descriptors), and retrying at once spins a core.
                try {
                    Thread.sleep(1_000);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
                continue;
            }
            java.net.InetAddress address = socket.getInetAddress();
            String host = address == null ? "unknown" : address.getHostAddress();
            if (!reserveHostSlot(host)) {
                closeQuietly(socket);
                continue;
            }
            try {
                workers.execute(() -> {
                    try {
                        handle(socket);
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

    private boolean reserveHostSlot(String host) {
        synchronized (hostConnections) {
            Integer held = hostConnections.get(host);
            if (held != null && held >= PER_HOST_CONNECTIONS) {
                return false;
            }
            hostConnections.put(host, held == null ? 1 : held + 1);
            return true;
        }
    }

    /** Dropped at zero, so the map cannot grow without bound. */
    private void releaseHostSlot(String host) {
        synchronized (hostConnections) {
            Integer held = hostConnections.get(host);
            if (held == null || held <= 1) {
                hostConnections.remove(host);
            } else {
                hostConnections.put(host, held - 1);
            }
        }
    }

    private void handle(Socket socket) {
        ScheduledFuture<?> deadline = null;
        SSLSocket channel = null;
        try {
            deadline = deadlines.schedule(() -> closeQuietly(socket), CONNECTION_DEADLINE_MS,
                    TimeUnit.MILLISECONDS);
            socket.setSoTimeout(CONNECTION_DEADLINE_MS);
            channel = (SSLSocket) tls.createSocket(socket, null, socket.getPort(), true);
            channel.setUseClientMode(false);
            // Required, not wanted: a caller with no certificate, or one this panel does not
            // trust, never gets past the handshake.
            channel.setNeedClientAuth(true);
            try {
                channel.startHandshake();
            } catch (IOException refused) {
                Log.d(TAG, "Member endpoint handshake refused: " + refused.getMessage());
                return;
            }
            String caller = callerOf(channel);
            InputStream input = channel.getInputStream();
            OutputStream output = channel.getOutputStream();
            String requestLine = readLine(input);
            if (requestLine == null) {
                return;
            }
            int contentLength = 0;
            String lengthSeen = null;
            for (int lines = 0; ; lines++) {
                String header = readLine(input);
                if (header == null || header.isEmpty()) {
                    break;
                }
                if (lines >= MAX_HEADER_LINES) {
                    respond(output, 431, error("too many header lines"));
                    return;
                }
                int colon = header.indexOf(':');
                if (colon > 0 && header.substring(0, colon).trim()
                        .equalsIgnoreCase("Content-Length")) {
                    // As the web admin reads it: digits only, and one value however often sent.
                    String value = header.substring(colon + 1).trim();
                    if (!value.matches("[0-9]{1,10}")
                            || (lengthSeen != null && !lengthSeen.equals(value))) {
                        respond(output, 400, error("Content-Length must be one whole number"));
                        return;
                    }
                    lengthSeen = value;
                    contentLength = (int) Math.min(Long.parseLong(value), Integer.MAX_VALUE);
                }
            }
            String[] parts = requestLine.split(" ");
            if (parts.length != 3 || !parts[2].startsWith("HTTP/1.")) {
                respond(output, 400, error("not an HTTP/1.1 request"));
                return;
            }
            boolean joinRoute = "/fleet/join".equals(parts[1]);
            // A removed panel's heartbeat is answered with the fact, so it can say so on its
            // screen and the person taps Leave there; it gets nothing else.
            if ("/fleet/checkin".equals(parts[1])
                    && KioskConfig.fleetRevoked(context).contains(caller)) {
                readBody(input, Math.min(contentLength, MAX_BODY));
                Log.i(TAG, "A removed panel checked in: key " + FleetTls.shortForm(caller));
                JSONObject removed = error("this panel was removed from the fleet "
                        + KioskConfig.fleetNameOf(context));
                try {
                    removed.put("removed", true);
                } catch (JSONException impossible) {
                    throw new IllegalStateException(impossible);
                }
                respond(output, 403, removed);
                return;
            }
            // Judged again after the handshake, as Syncthing and KDE Connect do: a resumed TLS
            // session presents no certificate and never reaches the trust manager, so a key
            // revoked since its first call would otherwise get back in. The join route is the
            // one place an unknown key is served; the code authenticates it there.
            if (joinRoute && KioskConfig.fleetRevoked(context).contains(caller)) {
                respond(output, 403, error("this key was removed from this panel's fleet"));
                return;
            }
            if (!joinRoute && !admits(caller)) {
                Log.i(TAG, "Member endpoint refused key " + FleetTls.shortForm(caller));
                respond(output, 403, error("this key is not a member of this panel's fleet"));
                return;
            }
            int limit = "/fleet/document".equals(parts[1]) ? MAX_DOCUMENT : MAX_BODY;
            if (contentLength > limit) {
                respond(output, 413, error("the body must be at most " + limit + " bytes"));
                return;
            }
            byte[] body = readBody(input, contentLength);
            Log.i(TAG, "Fleet call " + parts[0] + " " + parts[1] + " from "
                    + FleetTls.shortForm(caller));
            if ("/fleet".equals(parts[1])) {
                if ("GET".equals(parts[0])) {
                    respond(output, 200, fleetState());
                } else {
                    respond(output, 405, error("only GET"));
                }
            } else if (joinRoute) {
                if ("POST".equals(parts[0])) {
                    join(caller, body, output);
                } else {
                    respond(output, 405, error("only POST"));
                }
            } else if ("/fleet/document".equals(parts[1])) {
                if ("PUT".equals(parts[0])) {
                    takeDocument(caller, body, output);
                } else if ("GET".equals(parts[0])) {
                    // The list held, for a member that heard of a newer version (heartbeat).
                    String held = KioskConfig.fleetDocumentText(context);
                    if (held.isEmpty()) {
                        respond(output, 404, error("this panel holds no fleet list yet"));
                    } else {
                        try {
                            respond(output, 200, new JSONObject(held));
                        } catch (JSONException impossible) {
                            throw new IllegalStateException(impossible);
                        }
                    }
                } else {
                    respond(output, 405, error("only PUT or GET"));
                }
            } else if ("/fleet/checkin".equals(parts[1])) {
                if ("POST".equals(parts[0])) {
                    checkin(caller, socket.getInetAddress().getHostAddress(), body, output);
                } else {
                    respond(output, 405, error("only POST"));
                }
            } else if ("/fleet/leave".equals(parts[1])) {
                if ("POST".equals(parts[0])) {
                    leave(caller, output);
                } else {
                    respond(output, 405, error("only POST"));
                }
            } else {
                respond(output, 404, error("no such endpoint"));
            }
        } catch (IOException | RuntimeException failed) {
            Log.d(TAG, "Member endpoint connection ended: " + failed.getMessage());
        } finally {
            if (deadline != null) {
                deadline.cancel(false);
            }
            closeQuietly(channel != null ? channel : socket);
        }
    }

    /**
     * What {@code GET /fleet} and a join answer: who this panel is and which fleet it is in.
     * Called only after the caller's key was admitted, or its code was.
     */
    private JSONObject fleetState() {
        try {
            String fleetId = KioskConfig.fleetIdOf(context);
            return new JSONObject()
                    .put("id", KioskConfig.deviceIdOf(context))
                    .put("spki", identity.spki)
                    .put("fleet", fleetId.isEmpty() ? JSONObject.NULL : new JSONObject()
                            .put("id", fleetId)
                            .put("name", KioskConfig.fleetNameOf(context)))
                    .put("version", FleetDocument.version(KioskConfig.fleetDocumentOf(context)));
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /**
     * {@code POST /fleet/join} with {@code {"code": "12345678", "fleet": {"id", "name"}}}: the
     * caller, whose key this panel does not know yet, proves with the code on this panel's Join
     * screen that a person here agreed, and becomes the first manager this panel trusts. A panel
     * already in a fleet refuses, naming it. The code is checked last, so a malformed request
     * never costs one of the five tries.
     */
    private void join(String caller, byte[] body, OutputStream output) throws IOException {
        String joined = KioskConfig.fleetNameOf(context);
        if (!KioskConfig.fleetIdOf(context).isEmpty()) {
            respond(output, 409, error("this panel is already in the fleet " + joined
                    + "; it must leave that fleet first"));
            return;
        }
        JSONObject request;
        try {
            request = new JSONObject(new String(body, StandardCharsets.UTF_8));
        } catch (JSONException notJson) {
            respond(output, 400, error("the body must be a JSON object"));
            return;
        }
        JSONObject fleet = request.optJSONObject("fleet");
        String fleetId = fleet == null ? "" : fleet.optString("id", "");
        String fleetName = fleet == null ? "" : fleet.optString("name", "");
        String invalid = FleetJoin.validateFleet(fleetId, fleetName);
        if (invalid != null) {
            respond(output, 400, error(invalid));
            return;
        }
        switch (FleetJoin.shared.attempt(request.optString("code", ""), joinClock())) {
            case ADMITTED:
                synchronized (DOCUMENT_LOCK) {
                    KioskConfig.edit(context).joinFleet(fleetId, fleetName, caller).apply();
                }
                // Applied outside the dispatcher, so the web admin is told at once.
                KioskService.publishTelemetrySoon(context);
                Log.i(TAG, "Joined the fleet " + fleetName.trim() + ", manager key "
                        + FleetTls.shortForm(caller));
                respond(output, 200, fleetState());
                return;
            case WRONG:
                Log.w(TAG, "Join refused: wrong code from key " + FleetTls.shortForm(caller));
                respond(output, 403, error("wrong code; after five, this panel takes no code for"
                        + " ten minutes"));
                return;
            case LOCKED:
                Log.w(TAG, "Join refused: too many wrong codes");
                respond(output, 429, error("too many wrong codes; this panel takes no code for"
                        + " ten minutes"));
                return;
            default:
                respond(output, 410, error("no join code is open on this panel; open one in its"
                        + " Fleet section"));
        }
    }

    /**
     * {@code PUT /fleet/document} from a key this panel trusts: the fleet document, merged with
     * the one held when a manager of this fleet signed it (FleetDocument.refusal, merge), refused
     * with the reason otherwise. Whoever delivers it, the signature decides. The merge is kept as
     * the incoming document when it adds nothing to it, as the held one when it brings nothing
     * new, and otherwise signed anew by this panel one version up, so panels that changed the
     * fleet apart end with the same list. A document that revokes this panel's key removes it
     * from the fleet.
     */
    private void takeDocument(String caller, byte[] body, OutputStream output) throws IOException {
        String text = new String(body, StandardCharsets.UTF_8);
        if (FleetDocument.parse(text) == null) {
            respond(output, 400, error("the body must be a fleet document, a JSON object"));
            return;
        }
        Taken taken = take(context, identity, text, caller);
        if (taken.refused != null) {
            respond(output, 409, error(taken.refused));
            return;
        }
        try {
            JSONObject reply = fleetState();
            if (taken.merged != null) {
                // The deliverer holds an older list than the merge: handed back, so it catches
                // up at once rather than at the next heartbeat.
                reply.put("document", taken.merged);
            }
            respond(output, 200, reply);
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** What taking a document came to: the refusal, or the merged text when it was signed anew. */
    private static final class Taken {
        final String refused;
        final String merged;

        Taken(String refused, String merged) {
            this.refused = refused;
            this.merged = merged;
        }
    }

    /**
     * Takes a fleet document, from a delivery or handed back by a member: merged with the one
     * held when a manager of this fleet signed it (FleetDocument.refusal, merge). The merge is
     * kept as the incoming document when it adds nothing to it, as the held one when it brings
     * nothing new, and otherwise signed anew by this panel one version up, so panels that changed
     * the fleet apart end with the same list. A document that revokes this panel's key removes it
     * from the fleet. {@code from} is the key it came from, for the log.
     */
    private static Taken take(Context context, FleetIdentity identity, String text, String from) {
        java.util.Map<String, Object> incoming = FleetDocument.parse(text);
        String rewritten = null;
        synchronized (DOCUMENT_LOCK) {
            java.util.Map<String, Object> held = KioskConfig.fleetDocumentOf(context);
            String refused = FleetDocument.refusal(held, incoming, FleetDocument.signer(incoming),
                    KioskConfig.fleetIdOf(context), KioskConfig.fleetTrusted(context),
                    KioskConfig.fleetRevoked(context));
            if (refused != null) {
                Log.w(TAG, "Fleet document refused from key " + FleetTls.shortForm(from) + ": "
                        + refused);
                return new Taken(refused, null);
            }
            java.util.Map<String, Object> merged = FleetDocument.merge(held, incoming);
            String kept;
            if (FleetDocument.sameContent(merged, incoming)) {
                kept = text;
            } else if (held != null && FleetDocument.sameContent(merged, held)) {
                kept = null;
            } else {
                long top = Math.max(FleetDocument.version(held), FleetDocument.version(incoming));
                merged.put("version", top + 1);
                merged.put("base_version", top);
                merged.put("author", KioskConfig.deviceIdOf(context));
                merged.put("updated", java.time.Instant.now().toString());
                try {
                    kept = FleetDocument.sign(merged, identity.key,
                            identity.certificate.getPublicKey());
                } catch (java.security.GeneralSecurityException failed) {
                    Log.w(TAG, "The merged fleet document could not be signed", failed);
                    return new Taken("this panel could not sign the merged document", null);
                }
                rewritten = kept;
                Log.i(TAG, "Fleet document merged: version " + FleetDocument.version(held)
                        + " held and " + FleetDocument.version(incoming) + " from key "
                        + FleetTls.shortForm(from) + " became " + (top + 1));
            }
            if (FleetDocument.revoked(merged).contains(identity.spki)) {
                leaveFleet(context, "by version " + FleetDocument.version(incoming)
                        + " of the fleet's list, which removed it");
                return new Taken(null, null);
            }
            if (kept == null) {
                Log.i(TAG, "Fleet document version " + FleetDocument.version(incoming)
                        + " from key " + FleetTls.shortForm(from) + " brought nothing new");
                return new Taken(null, null);
            }
            KioskConfig.edit(context).fleetDocument(context, kept).apply();
        }
        KioskService.publishTelemetrySoon(context);
        Log.i(TAG, "Fleet document version " + FleetDocument.version(incoming) + " taken from key "
                + FleetTls.shortForm(from));
        // A list just taken may name panels never heard from: greeted now, not in three minutes.
        heartbeatSoon(context, "list taken");
        syncProfileSoon(context, "list taken");
        return new Taken(null, rewritten);
    }

    /**
     * Forgets the fleet, from any surface, in step with the document changes above, so a leave
     * is never undone by a list being merged at the same moment. {@code how} says who asked.
     */
    static void leaveFleet(Context context, String how) {
        String name = KioskConfig.fleetNameOf(context);
        synchronized (DOCUMENT_LOCK) {
            if (KioskConfig.fleetIdOf(context).isEmpty()) {
                return;
            }
            KioskConfig.edit(context).leaveFleet().apply();
        }
        removedFrom = "";
        peers.clear();
        profileApplied.clear();
        for (String section : FleetDocument.SECTIONS) {
            KioskRuntimeState.publishFleetProfileRefused(section,
                    java.util.Collections.emptyList());
        }
        KioskService.publishTelemetrySoon(context);
        Log.i(TAG, "Left the fleet " + name + " " + how);
    }

    /**
     * Adds a panel that just joined to this panel's fleet document, signed as this panel, and
     * keeps it: the first one founds the fleet. Then hands the document to every other member
     * once; a member that cannot be reached gets it with the next change (retries are block 12b).
     * Returns the new member's refusal or network failure, null when it took the document. Not
     * on the main thread.
     */
    static String addMember(Context context, String fleetId, String fleetName,
            java.util.Map<String, Object> member) {
        FleetIdentity identity = FleetIdentity.load(context);
        if (identity == null) {
            // Checked by addRefusal before the join; the other panel trusts this one already.
            return "this panel's fleet key cannot be read";
        }
        java.util.Map<String, Object> self = new java.util.LinkedHashMap<>();
        String id = KioskConfig.deviceIdOf(context);
        self.put(FleetDocument.ID, id);
        self.put(FleetDocument.NAME, id);
        self.put(FleetDocument.SPKI, identity.spki);
        self.put(FleetDocument.MANAGER, true);
        SystemStats.RuntimeFacts facts = KioskRuntimeState.lastFacts();
        String address = facts == null ? "" : facts.ipAddress;
        self.put("addresses", address.isEmpty() ? new java.util.ArrayList<>()
                : java.util.Collections.singletonList(address));
        self.put("port", (long) KioskConfig.fleetPortOf(context));
        String text;
        synchronized (DOCUMENT_LOCK) {
            java.util.Map<String, Object> held = KioskConfig.fleetDocumentOf(context);
            // This panel's own entry keeps the name a person gave it and its last known address.
            for (java.util.Map<String, Object> one : FleetDocument.members(held)) {
                if (identity.spki.equals(TinyJson.string(one, FleetDocument.SPKI, ""))) {
                    self.put(FleetDocument.NAME, TinyJson.string(one, FleetDocument.NAME, id));
                    if (address.isEmpty() && one.get("addresses") != null) {
                        self.put("addresses", one.get("addresses"));
                    }
                }
            }
            java.util.Map<String, Object> next = FleetDocument.withMember(
                    held, fleetId, fleetName, self, member,
                    java.time.Instant.now().toString());
            try {
                text = FleetDocument.sign(next, identity.key, identity.certificate.getPublicKey());
            } catch (java.security.GeneralSecurityException failed) {
                Log.w(TAG, "The fleet document could not be signed", failed);
                return "this panel could not sign the fleet's list";
            }
            KioskConfig.edit(context).fleetDocument(context, text).apply();
        }
        KioskService.publishTelemetrySoon(context);
        String newcomer = null;
        for (java.util.Map<String, Object> one : FleetDocument.members(FleetDocument.parse(text))) {
            String spki = TinyJson.string(one, FleetDocument.SPKI, "");
            if (spki.equals(identity.spki)) {
                continue;
            }
            String outcome = deliver(context, one, text);
            if (spki.equals(TinyJson.string(member, FleetDocument.SPKI, ""))) {
                newcomer = outcome;
            } else if (outcome != null) {
                Log.w(TAG, "Fleet document not delivered to "
                        + TinyJson.string(one, FleetDocument.NAME, "") + ": " + outcome);
            }
        }
        return newcomer;
    }

    /**
     * Why this panel cannot add one now, or null: a member whose fleet's list has not reached it
     * yet would start a second list of the same fleet, trusting only itself and the newcomer; a
     * panel whose key cannot be read cannot sign the list; one with no network address yet would
     * list itself where no member can reach it. Asked before the code is sent, so the other
     * panel is never left in a fleet that does not list it.
     */
    static String addRefusal(Context context) {
        if (!KioskConfig.fleetIdOf(context).isEmpty()
                && KioskConfig.fleetDocumentOf(context) == null) {
            return "this panel has not received the fleet's list of panels yet; add it from the"
                    + " panel that added this one";
        }
        if (FleetIdentity.load(context) == null) {
            return "this panel's fleet key cannot be read";
        }
        SystemStats.RuntimeFacts facts = KioskRuntimeState.lastFacts();
        if (facts == null || facts.ipAddress.isEmpty()) {
            return "this panel has no network address yet";
        }
        return null;
    }

    /** Hands {@code text} to one member at its first address; null when it took it. */
    private static String deliver(Context context, java.util.Map<String, Object> member,
            String text) {
        String spki = TinyJson.string(member, FleetDocument.SPKI, "");
        String address = addressOf(member, spki);
        if (address.isEmpty()) {
            return "no address is known for it";
        }
        try {
            Reply reply = call(context, address, portOf(member, spki), spki, "PUT",
                    "/fleet/document", new JSONObject(text));
            if (reply.status != 200) {
                return reply.reason();
            }
            // Heard from: the Fleet section need not wait for its first heartbeat.
            Peer known = peers.get(spki);
            peers.put(spki, new Peer(address, portOf(member, spki),
                    reply.body.optLong("version", 0), android.os.SystemClock.elapsedRealtime(),
                    known == null ? new JSONObject() : known.health,
                    known == null ? 0 : known.clockSkewMs));
            // The member held more than this panel and handed the merge back: taken here too.
            String handedBack = reply.body.optString("document", "");
            FleetIdentity identity = FleetIdentity.load(context);
            if (!handedBack.isEmpty() && identity != null
                    && FleetDocument.parse(handedBack) != null) {
                take(context, identity, handedBack, spki);
            }
            return null;
        } catch (IOException | JSONException failed) {
            Log.d(TAG, "Fleet document delivery failed", failed);
            return "it did not answer";
        }
    }

    /**
     * What this panel knows of another from its heartbeats (block 12b): where it was last reached,
     * which version of the list it held, when, its health summary and its clock against this
     * panel's. In memory only: a restart starts with nothing seen.
     */
    static final class Peer {
        final String address;
        final int port;
        final long version;
        /** On the clock that only moves forward (SystemClock.elapsedRealtime), not the date. */
        final long seenAt;
        final JSONObject health;
        final long clockSkewMs;

        Peer(String address, int port, long version, long seenAt, JSONObject health,
                long clockSkewMs) {
            this.address = address;
            this.port = port;
            this.version = version;
            this.seenAt = seenAt;
            this.health = health;
            this.clockSkewMs = clockSkewMs;
        }
    }

    /** By key hash; see Peer. */
    private static final java.util.concurrent.ConcurrentHashMap<String, Peer> peers =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** Every few minutes each panel tells every other it is here and which version it holds. */
    static final long HEARTBEAT_INTERVAL_MS = 3 * 60_000L;
    /** A panel not heard from for this long is said to be offline. */
    static final long OFFLINE_AFTER_MS = 10 * 60_000L;
    /** The fleet a heartbeat was answered with "removed" from; said on the Fleet section. */
    private static volatile String removedFrom = "";
    private static final java.util.concurrent.atomic.AtomicBoolean heartbeating =
            new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * {@code POST /fleet/checkin} from a member: it is here, at the address it called from, holds
     * the version it says, with its health and clock. Answered with this panel's own state, whose
     * version the caller compares with its own; when the caller holds a newer list, this panel
     * fetches it from the caller.
     */
    private void checkin(String caller, String address, byte[] body, OutputStream output)
            throws IOException {
        JSONObject request;
        try {
            request = new JSONObject(new String(body, StandardCharsets.UTF_8));
        } catch (JSONException notJson) {
            respond(output, 400, error("the body must be a JSON object"));
            return;
        }
        long clock = request.optLong("clock", System.currentTimeMillis());
        int port = request.optInt("port", KioskConfig.DEFAULT_FLEET_PORT);
        peers.put(caller, new Peer(address,
                port < 1 || port > 65535 ? KioskConfig.DEFAULT_FLEET_PORT : port,
                request.optLong("version", 0), android.os.SystemClock.elapsedRealtime(),
                request.optJSONObject("health") == null ? new JSONObject()
                        : request.optJSONObject("health"), clock - System.currentTimeMillis()));
        respond(output, 200, fleetState());
        long held = FleetDocument.version(KioskConfig.fleetDocumentOf(context));
        if (request.optLong("version", 0) > held) {
            new Thread(() -> fetchFrom(context, identity, peers.get(caller), caller),
                    "MuralisFleetFetch").start();
        }
        KioskService.publishTelemetrySoon(context);
    }

    /** Asks {@code peer} for the list it holds and takes it (merge). */
    private static void fetchFrom(Context context, FleetIdentity identity, Peer peer,
            String spki) {
        if (peer == null) {
            return;
        }
        try {
            Reply reply = call(context, peer.address, peer.port, spki, "GET", "/fleet/document",
                    null);
            if (reply.status == 200 && FleetDocument.parse(reply.body.toString()) != null) {
                take(context, identity, reply.body.toString(), spki);
            } else {
                Log.w(TAG, "The newer fleet list could not be fetched from key "
                        + FleetTls.shortForm(spki) + ": " + reply.reason());
            }
        } catch (IOException failed) {
            Log.w(TAG, "The newer fleet list could not be fetched from key "
                    + FleetTls.shortForm(spki) + ": " + failed.getMessage());
        }
    }

    /**
     * One round of heartbeats, off the main thread and one round at a time: a check-in to every
     * other member of the held list, in parallel, each with the call's own deadline. A member
     * that holds a newer list is asked for it; one that answers "removed" is noted for the
     * Fleet section. A member that does not answer costs nothing but its deadline.
     */
    static void heartbeatSoon(Context context, String why) {
        if (KioskConfig.fleetIdOf(context).isEmpty() || !heartbeating.compareAndSet(false, true)) {
            return;
        }
        new Thread(() -> {
            try {
                heartbeat(context.getApplicationContext(), why);
            } finally {
                heartbeating.set(false);
            }
        }, "MuralisFleetHeartbeat").start();
    }

    private static void heartbeat(Context context, String why) {
        FleetIdentity identity = FleetIdentity.load(context);
        java.util.Map<String, Object> held = KioskConfig.fleetDocumentOf(context);
        if (identity == null || held == null) {
            return;
        }
        JSONObject mine = checkinBody(context, FleetDocument.version(held));
        java.util.List<Thread> calls = new java.util.ArrayList<>();
        java.util.concurrent.atomic.AtomicInteger answered = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger removed = new java.util.concurrent.atomic.AtomicInteger();
        for (java.util.Map<String, Object> member : FleetDocument.members(held)) {
            String spki = TinyJson.string(member, FleetDocument.SPKI, "");
            if (spki.equals(identity.spki)) {
                continue;
            }
            Thread call = new Thread(() -> heartbeatOne(context, identity, member, spki, mine,
                    answered, removed),
                    "MuralisFleetHeartbeat-" + FleetTls.shortForm(spki));
            calls.add(call);
            call.start();
        }
        for (Thread call : calls) {
            try {
                call.join();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        // Removed only when every member that answered says so: one member that fell behind
        // still holds an old removal of a panel that joined again, and catches up by itself.
        if (removed.get() > 0 && answered.get() == 0) {
            removedFrom = KioskConfig.fleetNameOf(context);
            Log.i(TAG, "Removed from the fleet " + removedFrom + ", say " + removed.get()
                    + " panels");
        } else if (answered.get() > 0) {
            removedFrom = "";
        }
        Log.i(TAG, "Heartbeat (" + why + ") to " + calls.size() + " panels done, "
                + answered.get() + " answered");
        KioskService.publishTelemetrySoon(context);
    }

    private static void heartbeatOne(Context context, FleetIdentity identity,
            java.util.Map<String, Object> member, String spki, JSONObject mine,
            java.util.concurrent.atomic.AtomicInteger answered,
            java.util.concurrent.atomic.AtomicInteger removed) {
        String address = addressOf(member, spki);
        if (address.isEmpty()) {
            return;
        }
        int port = portOf(member, spki);
        try {
            Reply reply = call(context, address, port, spki, "POST", "/fleet/checkin", mine);
            if (reply.status == 200) {
                answered.incrementAndGet();
                Peer known = peers.get(spki);
                peers.put(spki, new Peer(address, port, reply.body.optLong("version", 0),
                        android.os.SystemClock.elapsedRealtime(),
                        known == null ? new JSONObject() : known.health,
                        known == null ? 0 : known.clockSkewMs));
                if (reply.body.optLong("version", 0)
                        > FleetDocument.version(KioskConfig.fleetDocumentOf(context))) {
                    fetchFrom(context, identity, peers.get(spki), spki);
                }
            } else if (reply.status == 403 && reply.body.optBoolean("removed", false)) {
                removed.incrementAndGet();
                Log.i(TAG, "Removed from the fleet, says key " + FleetTls.shortForm(spki));
            } else {
                Log.d(TAG, "Heartbeat to key " + FleetTls.shortForm(spki) + " answered "
                        + reply.status + ": " + reply.reason());
            }
        } catch (IOException failed) {
            Log.d(TAG, "Heartbeat to key " + FleetTls.shortForm(spki) + " failed: "
                    + failed.getMessage());
        }
    }

    /** This panel's check-in: who it is, what it holds, its health and its clock. */
    private static JSONObject checkinBody(Context context, long version) {
        try {
            SystemStats.RuntimeFacts facts = KioskRuntimeState.lastFacts();
            JSONObject health = new JSONObject()
                    .put("app_version", BuildConfig.VERSION_NAME)
                    .put("android", android.os.Build.VERSION.RELEASE)
                    .put("device_owner", KioskService.isDeviceOwner(context));
            if (facts != null) {
                health.put("battery_percent", facts.batteryPercent)
                        .put("charging", facts.charging)
                        .put("wifi_rssi_dbm", facts.wifiRssiDbm)
                        .put("app_uptime_ms", facts.appUptimeMs)
                        .put("page_error", SystemStats.pageErrorIsCurrent(facts)
                                ? facts.lastPageError : "");
            }
            return new JSONObject()
                    .put("id", KioskConfig.deviceIdOf(context))
                    .put("version", version)
                    .put("port", KioskConfig.fleetPortOf(context))
                    .put("clock", System.currentTimeMillis())
                    .put("health", health);
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /**
     * Where a member was reached lately, else the first address its entry lists: a stale
     * contact must not shadow an entry a fresh join wrote.
     */
    private static String addressOf(java.util.Map<String, Object> member, String spki) {
        Peer known = recent(spki);
        if (known != null && !known.address.isEmpty()) {
            return known.address;
        }
        Object addresses = member.get("addresses");
        return addresses instanceof java.util.List && !((java.util.List<?>) addresses).isEmpty()
                ? String.valueOf(((java.util.List<?>) addresses).get(0)) : "";
    }

    private static int portOf(java.util.Map<String, Object> member, String spki) {
        Peer known = recent(spki);
        return known != null ? known.port
                : (int) TinyJson.number(member, "port", KioskConfig.DEFAULT_FLEET_PORT);
    }

    /** A member's contact when heard from within the offline limit, else null. */
    private static Peer recent(String spki) {
        Peer known = peers.get(spki);
        return known != null
                && android.os.SystemClock.elapsedRealtime() - known.seenAt < OFFLINE_AFTER_MS
                ? known : null;
    }

    /** The other panels as last heard from, for the web API and the console to come. */
    static org.json.JSONArray peersJson(Context context) {
        org.json.JSONArray list = new org.json.JSONArray();
        long now = android.os.SystemClock.elapsedRealtime();
        String self = KioskConfig.deviceIdOf(context);
        for (java.util.Map<String, Object> member
                : FleetDocument.members(KioskConfig.fleetDocumentOf(context))) {
            String spki = TinyJson.string(member, FleetDocument.SPKI, "");
            if (self.equals(TinyJson.string(member, FleetDocument.ID, ""))) {
                continue;
            }
            Peer known = peers.get(spki);
            try {
                list.put(new JSONObject()
                        .put("id", TinyJson.string(member, FleetDocument.ID, ""))
                        .put("name", TinyJson.string(member, FleetDocument.NAME, ""))
                        .put("spki", spki)
                        .put("seen_ago_ms", known == null ? -1 : now - known.seenAt)
                        .put("online", known != null && now - known.seenAt < OFFLINE_AFTER_MS)
                        .put("address", known == null ? addressOf(member, spki) : known.address)
                        .put("version", known == null ? JSONObject.NULL : known.version)
                        .put("clock_skew_ms", known == null ? JSONObject.NULL : known.clockSkewMs)
                        .put("health", known == null ? new JSONObject() : known.health));
            } catch (JSONException impossible) {
                throw new IllegalStateException(impossible);
            }
        }
        return list;
    }

    /**
     * Shares {@code section} with the fleet from this panel's values, or stops sharing it: a new
     * version of the list, signed here and handed to every member. Off the main thread.
     */
    static String shareSection(Context context, String section, boolean on) {
        FleetIdentity identity = FleetIdentity.load(context);
        if (identity == null || KioskConfig.fleetDocumentOf(context) == null) {
            return "this panel holds no fleet list yet";
        }
        return writeSection(context, identity, section,
                on ? FleetProfile.read(context, section) : null);
    }

    /**
     * The next list with {@code section} set as given, built on the list held at that moment,
     * kept here and handed to every member off the caller's thread.
     */
    private static String writeSection(Context context, FleetIdentity identity, String section,
            java.util.Map<String, Object> values) {
        String text;
        synchronized (DOCUMENT_LOCK) {
            java.util.Map<String, Object> held = KioskConfig.fleetDocumentOf(context);
            if (held == null) {
                return "this panel holds no fleet list yet";
            }
            java.util.Map<String, Object> next = FleetDocument.withSection(held,
                    KioskConfig.deviceIdOf(context), section, values,
                    java.time.Instant.now().toString());
            try {
                text = FleetDocument.sign(next, identity.key, identity.certificate.getPublicKey());
            } catch (java.security.GeneralSecurityException failed) {
                Log.w(TAG, "The fleet list could not be signed", failed);
                return "this panel could not sign the fleet's list";
            }
            KioskConfig.edit(context).fleetDocument(context, text).apply();
            // What was just written is what this panel has: nothing to apply back.
            profileApplied.put(section, FleetDocument.version(next));
            KioskRuntimeState.publishFleetProfileRefused(section,
                    java.util.Collections.emptyList());
        }
        Log.i(TAG, (values == null ? "Stopped sharing " : "Shared ") + section
                + " with the fleet, version " + FleetDocument.version(FleetDocument.parse(text)));
        KioskService.publishTelemetrySoon(context);
        new Thread(() -> {
            for (java.util.Map<String, Object> one
                    : FleetDocument.members(FleetDocument.parse(text))) {
                String spki = TinyJson.string(one, FleetDocument.SPKI, "");
                if (!spki.equals(identity.spki)) {
                    String outcome = deliver(context, one, text);
                    if (outcome != null) {
                        Log.w(TAG, "Fleet list not delivered to "
                                + TinyJson.string(one, FleetDocument.NAME, "") + ": " + outcome
                                + "; it catches up at its next heartbeat");
                    }
                }
            }
        }, "MuralisFleetDeliver").start();
        return null;
    }

    /** Per section, the version of the list whose values this panel applied last. */
    private static final java.util.concurrent.ConcurrentHashMap<String, Long> profileApplied =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.atomic.AtomicBoolean syncingProfile =
            new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * Brings this panel and the fleet's shared settings in step, off the main thread and one
     * round at a time: a section the fleet changed since it was last applied here is applied,
     * through the checks a person's own change takes, and what they refuse is kept for the Fleet
     * section; a shared section this panel changed since (a save on either surface) is written
     * into the list and handed to every member, unless something of it was refused here, since
     * that would push a half-applied section back out.
     */
    static void syncProfileSoon(Context context, String why) {
        if (KioskConfig.fleetIdOf(context).isEmpty()
                || !syncingProfile.compareAndSet(false, true)) {
            return;
        }
        new Thread(() -> {
            try {
                syncProfile(context.getApplicationContext(), why);
            } catch (RuntimeException failed) {
                Log.w(TAG, "The fleet's shared settings could not be brought in step", failed);
            } finally {
                syncingProfile.set(false);
            }
        }, "MuralisFleetProfile").start();
    }

    private static void syncProfile(Context context, String why) {
        FleetIdentity identity = FleetIdentity.load(context);
        java.util.Map<String, Object> held = KioskConfig.fleetDocumentOf(context);
        if (identity == null || held == null) {
            return;
        }
        for (String section : FleetDocument.SECTIONS) {
            java.util.Map<String, Object> shared = FleetDocument.sectionValues(held, section);
            long at = FleetDocument.sectionAt(held, section);
            Long applied = profileApplied.get(section);
            if (shared == null) {
                profileApplied.put(section, at);
                KioskRuntimeState.publishFleetProfileRefused(section,
                        java.util.Collections.emptyList());
                continue;
            }
            if (applied == null || applied < at) {
                java.util.List<String> refused = FleetProfile.apply(context, section, shared);
                profileApplied.put(section, at);
                KioskRuntimeState.publishFleetProfileRefused(section, refused);
                Log.i(TAG, "Applied the fleet's " + section + " (version " + at + ", " + why + ")"
                        + (refused.isEmpty() ? "" : ", refused: " + refused));
                continue;
            }
            if (!KioskRuntimeState.fleetProfileRefused(section).isEmpty()) {
                continue;
            }
            java.util.Map<String, Object> mine = FleetProfile.read(context, section);
            // A secret is a change only when both sides have one and they differ: one this
            // panel cannot read right now, or one the fleet shares empty (a member keeps its
            // own then), must not be pushed out as if a person had typed it.
            for (String key : FleetDocument.SECRET_KEYS) {
                if (shared.containsKey(key) && (String.valueOf(mine.get(key)).isEmpty()
                        || String.valueOf(shared.get(key)).isEmpty())) {
                    mine.put(key, shared.get(key));
                }
            }
            if (!FleetProfile.same(mine, shared)) {
                Log.i(TAG, "This panel changed the shared " + section + "; told the fleet");
                writeSection(context, identity, section, mine);
                held = KioskConfig.fleetDocumentOf(context);
                if (held == null) {
                    return;
                }
            }
        }
    }

    /** {@code POST /fleet/leave} from a key this panel trusts: forget the fleet. */
    private void leave(String caller, OutputStream output) throws IOException {
        leaveFleet(context, "at the request of key " + FleetTls.shortForm(caller));
        respond(output, 200, fleetState());
    }

    private static JSONObject error(String reason) {
        try {
            return new JSONObject().put("error", reason);
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String callerOf(SSLSocket channel) throws IOException {
        Certificate[] presented = channel.getSession().getPeerCertificates();
        return FleetTls.spkiOf((X509Certificate) presented[0]);
    }

    private static void respond(OutputStream output, int status, JSONObject body) throws IOException {
        byte[] content = (body.toString() + "\n").getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + status + " " + reason(status) + "\r\n"
                + "Content-Type: application/json; charset=utf-8\r\n"
                + "Content-Length: " + content.length + "\r\n"
                + "Connection: close\r\n\r\n";
        ByteArrayOutputStream whole = new ByteArrayOutputStream(head.length() + content.length);
        whole.write(head.getBytes(StandardCharsets.US_ASCII));
        whole.write(content);
        output.write(whole.toByteArray());
        output.flush();
    }

    private static String reason(int status) {
        switch (status) {
            case 200: return "OK";
            case 400: return "Bad Request";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 409: return "Conflict";
            case 410: return "Gone";
            case 413: return "Content Too Large";
            case 429: return "Too Many Requests";
            case 431: return "Request Header Fields Too Large";
            default: return "Error";
        }
    }

    private static byte[] readBody(InputStream input, int length) throws IOException {
        byte[] body = new byte[length];
        int read = 0;
        while (read < length) {
            int got = input.read(body, read, length - read);
            if (got == -1) {
                throw new IOException("the body ended early");
            }
            read += got;
        }
        return body;
    }

    private static String readLine(InputStream input) throws IOException {
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
            if (buffer.size() > MAX_LINE) {
                throw new IOException("line too long");
            }
        }
        return buffer.size() == 0 ? null : buffer.toString("UTF-8");
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignored) {
            // Being discarded either way.
        }
    }
}
