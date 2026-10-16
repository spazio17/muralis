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
        // the join route serves such a key, and only for the right code (handle).
        this.tls = FleetTls.context(identity.key, identity.certificate,
                spki -> admits(spki) || FleetJoin.shared.isOpen(joinClock()))
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
        StringBuilder others = new StringBuilder();
        String self = KioskConfig.deviceIdOf(context);
        for (java.util.Map<String, Object> one
                : FleetDocument.members(KioskConfig.fleetDocumentOf(context))) {
            if (!self.equals(TinyJson.string(one, FleetDocument.ID, ""))) {
                others.append(others.length() == 0 ? "" : ", ")
                        .append(TinyJson.string(one, FleetDocument.NAME, ""));
            }
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
            byte[] reply = length < 0 || length > MAX_BODY * 4 ? new byte[0]
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
            // Judged again after the handshake, as Syncthing and KDE Connect do: a resumed TLS
            // session presents no certificate and never reaches the trust manager, so a key
            // revoked since its first call would otherwise get back in. The join route is the
            // one place an unknown key is served; the code authenticates it there.
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
                } else {
                    respond(output, 405, error("only PUT"));
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
        Object addresses = member.get("addresses");
        String address = addresses instanceof java.util.List
                && !((java.util.List<?>) addresses).isEmpty()
                ? String.valueOf(((java.util.List<?>) addresses).get(0)) : "";
        if (address.isEmpty()) {
            return "no address is known for it";
        }
        try {
            String spki = TinyJson.string(member, FleetDocument.SPKI, "");
            Reply reply = call(context, address,
                    (int) TinyJson.number(member, "port", KioskConfig.DEFAULT_FLEET_PORT),
                    spki, "PUT", "/fleet/document", new JSONObject(text));
            if (reply.status != 200) {
                return reply.reason();
            }
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
