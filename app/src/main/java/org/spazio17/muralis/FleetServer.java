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
 * {@code POST /fleet/exchange} and {@code POST /fleet/join} (the two routes an unknown key
 * reaches, while a join code is open: {@link FleetJoin}), {@code POST /fleet/leave}, {@code GET /fleet/screenshot} (this panel's
 * screen as a JPEG, for the Fleet page's thumbnails) and {@code POST /fleet/identify} (the
 * panel's name shown and said on it). One request per connection, small and bounded: a fleet
 * call is a few hundred bytes between panels, never a browser's burst, the picture being the
 * one answer that is not.
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
        KioskConfig.rememberFleetSelfKey(context, identity.spki);
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

    /** What adopting a panel came to: a refusal, or added with perhaps no confirmation. */
    static final class Adopted {
        final String refusal;
        final String unconfirmed;

        Adopted(String refusal, String unconfirmed) {
            this.refusal = refusal;
            this.unconfirmed = unconfirmed;
        }
    }

    /**
     * Adds another panel to this one's fleet (founding it when this panel has none): the code
     * to its join route, pinned to the key it showed, then this panel's combinations when it
     * asks at its welcome screen, then the member in the fleet's list. The camera's Add a panel
     * and the typed code (block 11e) both end here. Off the main thread.
     */
    static Adopted adopt(Context context, FleetJoin.Invitation invitation, String fleetId,
            String fleetName, String name) {
        String problem = addRefusal(context);
        if (problem != null) {
            return new Adopted(problem, null);
        }
        FleetIdentity self = FleetIdentity.load(context);
        if (self == null) {
            return new Adopted("this panel's fleet key is not ready yet; try again in a moment",
                    null);
        }
        try {
            // The code proven, never sent (Spake2): a device that only claims to be the panel
            // learns nothing, and is not the panel this code belongs to.
            Spake2 exchange = new Spake2(true, invitation.code, new java.security.SecureRandom());
            Reply answered = call(context, invitation.address, invitation.port, invitation.spki,
                    "POST", "/fleet/exchange",
                    new org.json.JSONObject().put("share", exchange.share()));
            if (answered.status != 200) {
                return new Adopted(answered.reason(), null);
            }
            Spake2.Confirmations confirmations = exchange.finish(
                    answered.body.optString("share", ""), self.spki, invitation.spki);
            if (confirmations == null || !Spake2.matches(confirmations.panel,
                    answered.body.optString("confirmation", ""))) {
                return new Adopted(OTHER_CODE, null);
            }
            org.json.JSONObject request = new org.json.JSONObject()
                    .put("confirmation", Spake2.hex(confirmations.adopter))
                    .put("fleet", new org.json.JSONObject().put("id", fleetId)
                            .put("name", fleetName));
            Reply joined = call(context, invitation.address, invitation.port, invitation.spki,
                    "POST", "/fleet/join", request);
            if (joined.status != 200) {
                return new Adopted(joined.reason(), null);
            }
            // A panel at its welcome screen asked for the way out: this panel's combinations
            // and PIN, once, at setup (the Esper idea, 2026-10-13), sent only now that it is a
            // member and asked, never with the code alone.
            if (joined.body.optBoolean("needs_setup", false)) {
                KioskConfig own = KioskConfig.load(context);
                org.json.JSONObject setup = new org.json.JSONObject()
                        .put("settings_sequence", own.settingsSequence)
                        .put("launcher_sequence", own.launcherSequence);
                String pinHash = KioskConfig.escapePinHash(context);
                if (pinHash != null) {
                    setup.put("escape_pin_hash", pinHash);
                }
                Reply given = call(context, invitation.address, invitation.port,
                        invitation.spki, "POST", "/fleet/setup", setup);
                Log.i(TAG, given.status == 200 ? "Gave the new panel the combinations"
                        : "The new panel did not take the combinations: " + given.reason());
            }
            java.util.Map<String, Object> member = new java.util.LinkedHashMap<>();
            // The id the invitation carried, checked when it was read; the reply's is not.
            member.put(FleetDocument.ID, invitation.id);
            member.put(FleetDocument.NAME, name);
            member.put(FleetDocument.SPKI, invitation.spki);
            member.put(FleetDocument.MANAGER, true);
            member.put("addresses", java.util.Collections.singletonList(invitation.address));
            member.put("port", (long) invitation.port);
            // Added whatever the delivery says: the other panel already took this one's key,
            // and the fleet document now lists it.
            String unconfirmed = addMember(context, fleetId, fleetName, member);
            Log.i(TAG, "Added " + name + " (" + FleetTls.shortForm(invitation.spki)
                    + ") to the fleet " + fleetName + (unconfirmed == null ? ", confirmed"
                    : ", not confirmed: " + unconfirmed));
            return new Adopted(null, unconfirmed);
        } catch (javax.net.ssl.SSLException refusedKey) {
            // The other panel let no stranger in: its code is no longer open, or the key was
            // another panel's.
            problem = "the other panel did not let this one in; its code may have expired or"
                    + " been closed. Make a new code there and try again";
        } catch (java.io.IOException failed) {
            problem = plainNetworkReason(failed);
        } catch (org.json.JSONException impossible) {
            problem = "the request could not be made";
        }
        Log.w(TAG, "Adding " + name + " refused: " + problem);
        return new Adopted(problem, null);
    }

    /** A failed call to another panel, in words for the person at this one. */
    static String plainNetworkReason(java.io.IOException failed) {
        if (failed instanceof java.net.SocketTimeoutException) {
            return "the other panel did not answer in time";
        }
        if (failed instanceof java.net.ConnectException
                || failed instanceof java.net.NoRouteToHostException
                || failed instanceof java.net.UnknownHostException) {
            return "the other panel could not be reached; both must be on networks that reach"
                    + " each other";
        }
        return "the connection to the other panel broke off";
    }

    /** A new fleet's id, for the panel that founds one by adding the first other panel. */
    static String newFleetId() {
        byte[] bytes = new byte[8];
        new java.security.SecureRandom().nextBytes(bytes);
        StringBuilder id = new StringBuilder("fleet-");
        for (byte b : bytes) {
            id.append(Character.forDigit((b >> 4) & 0xF, 16))
                    .append(Character.forDigit(b & 0xF, 16));
        }
        return id.toString();
    }

    /** A panel that proved a different code than the one typed or read. */
    private static final String OTHER_CODE = "that panel shows another code";

    /** How long a typed code looks for its panel on the network (Juri, 2026-10-14: 30 s). */
    static final long CODE_SEARCH_MS = 30_000L;

    /**
     * Adopts the panel that shows {@code code}, for a device without a camera (block 11e): the
     * panels announced on this network as ready to be joined with the code's first two digits
     * as their tag are asked, each once, pinned to the key their announcement carries, until one
     * proves the same code or the time runs out. The code is what proves the person sees that
     * panel, and it never travels (Spake2). Blocks for up to {@link #CODE_SEARCH_MS};
     * off the main thread. Returns the panel's id as the name it was added with in
     * {@code added[0]}.
     */
    static Adopted adoptByCode(Context context, String code, String fleetId, String fleetName,
            String[] added) {
        String digits = code == null ? "" : code.replaceAll("\\s", "");
        if (!digits.matches("\\d{8}")) {
            return new Adopted("the code is the eight digits under the panel's Join QR", null);
        }
        android.net.nsd.NsdManager nsd =
                context.getApplicationContext().getSystemService(android.net.nsd.NsdManager.class);
        if (nsd == null) {
            return new Adopted("this device cannot look for panels on the network", null);
        }
        java.util.concurrent.LinkedBlockingQueue<android.net.nsd.NsdServiceInfo> found =
                new java.util.concurrent.LinkedBlockingQueue<>();
        android.net.nsd.NsdManager.DiscoveryListener listener =
                new android.net.nsd.NsdManager.DiscoveryListener() {
                    @Override public void onStartDiscoveryFailed(String type, int error) { }
                    @Override public void onStopDiscoveryFailed(String type, int error) { }
                    @Override public void onDiscoveryStarted(String type) { }
                    @Override public void onDiscoveryStopped(String type) { }
                    @Override public void onServiceLost(android.net.nsd.NsdServiceInfo info) { }

                    @Override
                    public void onServiceFound(android.net.nsd.NsdServiceInfo info) {
                        found.add(info);
                    }
                };
        // A panel whose display sleeps hears no announcement unless it holds this lock; the
        // person adds from a computer's browser while this panel's screen may well be off.
        android.net.wifi.WifiManager wifi = context.getApplicationContext()
                .getSystemService(android.net.wifi.WifiManager.class);
        android.net.wifi.WifiManager.MulticastLock listening = wifi == null ? null
                : wifi.createMulticastLock("muralis-add-by-code");
        if (listening != null) {
            listening.setReferenceCounted(false);
            listening.acquire();
        }
        String self = KioskConfig.deviceIdOf(context);
        java.util.Set<String> tried = new java.util.HashSet<>();
        String lastProblem = null;
        long deadline = android.os.SystemClock.elapsedRealtime() + CODE_SEARCH_MS;
        try {
            nsd.discoverServices("_muralis._tcp", android.net.nsd.NsdManager.PROTOCOL_DNS_SD,
                    listener);
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                android.net.nsd.NsdServiceInfo seen = found.poll(1, java.util.concurrent.TimeUnit.SECONDS);
                if (seen == null || !tried.add(seen.getServiceName())) {
                    continue;
                }
                android.net.nsd.NsdServiceInfo resolved = resolve(nsd, seen);
                if (resolved == null || resolved.getHost() == null) {
                    // Android 13 and older resolve one service at a time and refuse a second
                    // while the first runs, and nothing reports the service again: asked again
                    // after a pause, while the time lasts.
                    tried.remove(seen.getServiceName());
                    Thread.sleep(500);
                    found.add(seen);
                    continue;
                }
                java.util.Map<String, byte[]> txt = resolved.getAttributes();
                String id = text(txt, "id");
                String spki = text(txt, "spki");
                if (id.isEmpty() || spki.isEmpty() || id.equals(self)) {
                    continue;
                }
                if (!"joining".equals(text(txt, "state"))
                        || !digits.substring(0, 2).equals(text(txt, "tag"))) {
                    // Not showing this code yet: when it opens one it announces itself again,
                    // and is asked then.
                    tried.remove(seen.getServiceName());
                    continue;
                }
                String address = resolved.getHost().getHostAddress();
                FleetJoin.Invitation invitation = new FleetJoin.Invitation(id, address,
                        resolved.getPort(), spki, digits);
                Adopted adopted = adopt(context, invitation, fleetId, fleetName, id);
                if (adopted.refusal == null) {
                    added[0] = id;
                    return adopted;
                }
                lastProblem = adopted.refusal;
            }
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException refused) {
            Log.w(TAG, "Looking for panels on the network failed", refused);
        } finally {
            if (listening != null) {
                listening.release();
            }
            try {
                nsd.stopServiceDiscovery(listener);
            } catch (RuntimeException alreadyStopped) {
                Log.d(TAG, "Discovery already stopped");
            }
        }
        // A panel whose tag matched but which proved another code says more than the search.
        return new Adopted(lastProblem != null ? lastProblem
                + (lastProblem.equals(OTHER_CODE) ? "; check the numbers" : "")
                : "no panel answered with that code in time: check that it shows its Join code"
                + " and is on this network", null);
    }

    private static String text(java.util.Map<String, byte[]> txt, String key) {
        byte[] value = txt == null ? null : txt.get(key);
        return value == null ? "" : new String(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** One service resolved, or null within five seconds; Android resolves one at a time. */
    private static android.net.nsd.NsdServiceInfo resolve(android.net.nsd.NsdManager nsd,
            android.net.nsd.NsdServiceInfo info) throws InterruptedException {
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        android.net.nsd.NsdServiceInfo[] out = new android.net.nsd.NsdServiceInfo[1];
        android.net.nsd.NsdManager.ResolveListener listener =
                new android.net.nsd.NsdManager.ResolveListener() {
            @Override
            public void onResolveFailed(android.net.nsd.NsdServiceInfo failed, int error) {
                done.countDown();
            }

            @Override
            public void onServiceResolved(android.net.nsd.NsdServiceInfo resolved) {
                out[0] = resolved;
                done.countDown();
            }
        };
        nsd.resolveService(info, listener);
        if (!done.await(5, java.util.concurrent.TimeUnit.SECONDS)
                && android.os.Build.VERSION.SDK_INT >= 34) {
            // A resolve left running holds the slot the next one needs; Android 14 can stop it,
            // older ones end it themselves and the panel is asked again after a pause.
            try {
                nsd.stopServiceResolution(listener);
            } catch (RuntimeException alreadyEnded) {
                Log.d(TAG, "The resolve had already ended");
            }
        }
        return out[0];
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
        /** The body as sent, for the one route that answers a picture, not JSON. */
        final byte[] content;
        final String contentType;

        Reply(int status, JSONObject body) {
            this(status, body, new byte[0], "application/json");
        }

        Reply(int status, JSONObject body, byte[] content, String contentType) {
            this.status = status;
            this.body = body;
            this.content = content;
            this.contentType = contentType;
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
        return call(context, address, port, spki, method, path, body, MAX_DOCUMENT);
    }

    /** As above, taking a reply body of up to {@code replyLimit} bytes; longer is discarded. */
    static Reply call(Context context, String address, int port, String spki, String method,
            String path, JSONObject body, int replyLimit) throws IOException {
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
            String type = "application/json";
            for (int lines = 0; lines < MAX_HEADER_LINES; lines++) {
                String header = readLine(input);
                if (header == null || header.isEmpty()) {
                    break;
                }
                int colon = header.indexOf(':');
                String name = colon > 0 ? header.substring(0, colon).trim() : "";
                String value = colon > 0 ? header.substring(colon + 1).trim() : "";
                if (name.equalsIgnoreCase("Content-Length") && value.matches("[0-9]{1,8}")) {
                    length = Integer.parseInt(value);
                } else if (name.equalsIgnoreCase("Content-Type")) {
                    type = value;
                }
            }
            // As much as the caller allows: a fleet document, or a picture of a screen.
            byte[] reply = length < 0 || length > replyLimit ? new byte[0]
                    : readBody(input, length);
            JSONObject json = new JSONObject();
            if (type.startsWith("application/json")) {
                try {
                    json = new JSONObject(new String(reply, StandardCharsets.UTF_8));
                } catch (JSONException notJson) {
                    json = new JSONObject();
                }
            }
            return new Reply(Integer.parseInt(parts[1]), json, reply, type);
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
            return "This panel joined the fleet " + KioskConfig.fleetNameOf(context) + "."
                    + (setupTakenFrom.isEmpty() ? ""
                    : " The fleet's combinations open the settings here too.");
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
            boolean joinRoute = "/fleet/join".equals(parts[1])
                    || "/fleet/exchange".equals(parts[1]);
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
                if ("POST".equals(parts[0]) && "/fleet/exchange".equals(parts[1])) {
                    exchange(caller, body, output);
                } else if ("POST".equals(parts[0])) {
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
            } else if ("/fleet/setup".equals(parts[1])) {
                if ("POST".equals(parts[0])) {
                    setup(caller, body, output);
                } else {
                    respond(output, 405, error("only POST"));
                }
            } else if ("/fleet/checkin".equals(parts[1])) {
                if ("POST".equals(parts[0])) {
                    checkin(caller, socket.getInetAddress().getHostAddress(), body, output);
                } else {
                    respond(output, 405, error("only POST"));
                }
            } else if ("/fleet/left".equals(parts[1])) {
                if ("POST".equals(parts[0])) {
                    left(caller, output);
                } else {
                    respond(output, 405, error("only POST"));
                }
            } else if ("/fleet/leave".equals(parts[1])) {
                if ("POST".equals(parts[0])) {
                    leave(caller, output);
                } else {
                    respond(output, 405, error("only POST"));
                }
            } else if ("/fleet/screenshot".equals(parts[1])) {
                if ("GET".equals(parts[0])) {
                    screenshot(output);
                } else {
                    respond(output, 405, error("only GET"));
                }
            } else if ("/fleet/identify".equals(parts[1])) {
                if ("POST".equals(parts[0])) {
                    identify(output);
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
                    .put("version", FleetDocument.version(KioskConfig.fleetDocumentOf(context)))
                    // No way out recorded yet: the adder may send its own (POST /fleet/setup).
                    .put("needs_setup", !KioskConfig.load(context).escapeSequencesConfigured());
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /**
     * {@code POST /fleet/exchange} with {@code {"share": "<hex>"}}: the first half of proving the
     * code on this panel's Join screen (Spake2), answered with this panel's share and its
     * confirmation, which shows the caller this panel holds the same code. Each exchange is one
     * of the five tries until a join confirms it.
     */
    private void exchange(String caller, byte[] body, OutputStream output) throws IOException {
        if (!KioskConfig.fleetIdOf(context).isEmpty()) {
            respond(output, 409, error("this panel is already in the fleet "
                    + KioskConfig.fleetNameOf(context) + "; it must leave that fleet first"));
            return;
        }
        JSONObject request;
        try {
            request = new JSONObject(new String(body, StandardCharsets.UTF_8));
        } catch (JSONException notJson) {
            respond(output, 400, error("the body must be a JSON object"));
            return;
        }
        FleetJoin.Answer answer = FleetJoin.shared.exchange(request.optString("share", ""),
                caller, identity.spki, joinClock());
        if (answer.refused == null) {
            try {
                respond(output, 200, new JSONObject().put("share", answer.share)
                        .put("confirmation", answer.confirmation));
            } catch (JSONException impossible) {
                throw new IllegalStateException(impossible);
            }
            return;
        }
        joinRefused(answer.refused, caller, output);
    }

    /**
     * {@code POST /fleet/join} with {@code {"confirmation": "<hex>", "fleet": {"id", "name"}}}:
     * the caller, whose key this panel does not know yet, finishes proving the code on this
     * panel's Join screen (the exchange before), so a person here agreed, and becomes the first
     * manager this panel trusts. A panel already in a fleet refuses, naming it.
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
        FleetJoin.Outcome outcome = FleetJoin.shared.confirm(
                request.optString("confirmation", ""), caller, joinClock());
        if (outcome == FleetJoin.Outcome.ADMITTED) {
            synchronized (DOCUMENT_LOCK) {
                KioskConfig.edit(context).joinFleet(fleetId, fleetName, caller).apply();
            }
            // Applied outside the dispatcher, so the web admin is told at once.
            KioskService.publishTelemetrySoon(context);
            Log.i(TAG, "Joined the fleet " + fleetName.trim() + ", manager key "
                    + FleetTls.shortForm(caller));
            respond(output, 200, fleetState());
            return;
        }
        joinRefused(outcome, caller, output);
    }

    /** A refused exchange or join, with the reason. */
    private void joinRefused(FleetJoin.Outcome outcome, String caller, OutputStream output)
            throws IOException {
        switch (outcome) {
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
            String before = pageWithFacts(context);
            KioskConfig.edit(context).fleetDocument(context, kept).apply();
            reloadPageIfFactsChanged(context, before);
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
        leaveFleet(context, how, false);
    }

    /**
     * Leaves the fleet; with {@code tellFleet}, as a person's Leave on this panel or its web
     * admin, one member that answers is told first (off the caller's thread), so it takes this
     * panel off the list for everyone. This panel leaves whether or not one answers; the others
     * then see it offline until someone removes it on their Fleet page.
     */
    static void leaveFleet(Context context, String how, boolean tellFleet) {
        String name = KioskConfig.fleetNameOf(context);
        java.util.Map<String, Object> held = KioskConfig.fleetDocumentOf(context);
        FleetIdentity identity = tellFleet ? FleetIdentity.load(context) : null;
        if (held != null && identity != null && !KioskConfig.fleetIdOf(context).isEmpty()) {
            new Thread(() -> tellLeaving(context, identity, held), "MuralisFleetLeave").start();
        }
        synchronized (DOCUMENT_LOCK) {
            if (KioskConfig.fleetIdOf(context).isEmpty()) {
                return;
            }
            String before = pageWithFacts(context);
            KioskConfig.edit(context).leaveFleet(context).apply();
            reloadPageIfFactsChanged(context, before);
        }
        removedFrom = "";
        setupTakenFrom = "";
        peers.clear();
        profileApplied.clear();
        profileSource.clear();
        for (String section : FleetDocument.SECTIONS) {
            KioskRuntimeState.publishFleetProfileRefused(section,
                    java.util.Collections.emptyList());
        }
        KioskService.publishTelemetrySoon(context);
        Log.i(TAG, "Left the fleet " + name + " " + how);
    }

    /** Tells the first member that answers that this panel left; see {@link #leaveFleet}. */
    private static void tellLeaving(Context context, FleetIdentity identity,
            java.util.Map<String, Object> held) {
        for (java.util.Map<String, Object> one : FleetDocument.members(held)) {
            String spki = TinyJson.string(one, FleetDocument.SPKI, "");
            String address = addressOf(one, spki);
            if (spki.equals(identity.spki) || address.isEmpty()) {
                continue;
            }
            try {
                Reply reply = call(context, address, portOf(one, spki), spki, "POST",
                        "/fleet/left", new JSONObject());
                if (reply.status == 200) {
                    Log.i(TAG, "Told " + TinyJson.string(one, FleetDocument.NAME, "")
                            + " that this panel left");
                    return;
                }
                Log.w(TAG, "Leaving: " + TinyJson.string(one, FleetDocument.NAME, "")
                        + " refused: " + reply.reason());
            } catch (IOException failed) {
                Log.d(TAG, "Leaving: a member did not answer", failed);
            }
        }
        Log.w(TAG, "Left without telling the fleet: no member answered");
    }

    /**
     * Takes the member of key hash {@code spki} off the fleet's list and revokes its key, as Remove
     * on the Fleet page ({@code removed}: also handed the list, so a reachable panel sees it was
     * removed and leaves by itself) or for a panel that said it left. Null when done.
     */
    static String removeMember(Context context, String spki, boolean removed) {
        java.util.Map<String, Object> held = KioskConfig.fleetDocumentOf(context);
        if (held == null) {
            return "this panel holds no fleet list yet";
        }
        java.util.Map<String, Object> member = FleetDocument.memberByKey(held, spki);
        if (member == null) {
            return "no such panel";
        }
        if (spki.equals(KioskRuntimeState.fleetKey())) {
            return "this panel leaves with Leave the fleet, not Remove";
        }
        String name = TinyJson.string(member, FleetDocument.NAME, "");
        String refused = writeList(context, null, null, (identity, list) ->
                FleetDocument.withoutMember(list, KioskConfig.deviceIdOf(context), spki,
                        java.time.Instant.now().toString()),
                (removed ? "Removed " : "Took off the list ") + name);
        if (refused == null && removed) {
            String text = KioskConfig.fleetDocumentText(context);
            new Thread(() -> deliver(context, member, text), "MuralisFleetRemoved").start();
        }
        return refused;
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

    /** "just now", "3 min ago", "2 h 5 min ago": a contact's age on both surfaces. */
    static String agoWords(long ago) {
        long minutes = ago / 60_000;
        return minutes < 1 ? "just now" : minutes < 60 ? minutes + " min ago"
                : (minutes / 60) + " h " + (minutes % 60) + " min ago";
    }

    /**
     * A member's last contact on the Fleet page of both surfaces: "this panel", "online, seen
     * 2 min ago", "offline, last seen 1 h 3 min ago" or "not heard from yet".
     */
    static String contactLine(java.util.Map<String, Object> member, org.json.JSONArray peers,
            String selfId) {
        if (selfId.equals(TinyJson.string(member, FleetDocument.ID, ""))) {
            return "this panel";
        }
        String spki = TinyJson.string(member, FleetDocument.SPKI, "");
        for (int i = 0; i < peers.length(); i++) {
            JSONObject peer = peers.optJSONObject(i);
            if (peer != null && spki.equals(peer.optString("spki"))) {
                long ago = peer.optLong("seen_ago_ms", -1);
                if (ago >= 0) {
                    return (peer.optBoolean("online") ? "online, seen " : "offline, last seen ")
                            + agoWords(ago);
                }
            }
        }
        return "not heard from yet";
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
     * Shares {@code section} in {@code group} with the values given (the Fleet page's form), or
     * stops sharing it there with null: a new version of the list, signed here and handed to
     * every member, from any panel into any scope, as every panel manages the fleet.
     */
    static String shareSection(Context context, String group, String section,
            java.util.Map<String, Object> values) {
        java.util.Map<String, Object> held = KioskConfig.fleetDocumentOf(context);
        if (held == null) {
            return "this panel holds no fleet list yet";
        }
        if (!FleetDocument.EVERYONE.equals(group)
                && !FleetDocument.groups(held).containsKey(group)) {
            return "no such group";
        }
        // A person's values, not this panel's own: no group and section here, so the next sync
        // applies them on this panel too (it is in the scope or it is not), through the same
        // checks as on every other panel, instead of pushing this panel's old values back out.
        return writeList(context, null, null, (identity, list) -> FleetDocument.withSection(list,
                KioskConfig.deviceIdOf(context), group, section, values,
                java.time.Instant.now().toString()),
                (values != null ? "Shared " : "Stopped sharing ") + section + " in the group "
                        + FleetDocument.groupName(held, group));
    }

    /** A group made with the name a person gave; its id comes back, or the refusal. */
    static String createGroup(Context context, String name, String[] idOut) {
        String invalid = FleetDocument.validateGroupName(name);
        if (invalid != null) {
            return invalid;
        }
        java.util.Map<String, Object> held = KioskConfig.fleetDocumentOf(context);
        if (held == null) {
            return "this panel holds no fleet list yet";
        }
        for (String existing : FleetDocument.groups(held).values()) {
            if (existing.equalsIgnoreCase(name.trim())) {
                return "there is a group called " + existing + " already";
            }
        }
        byte[] bytes = new byte[6];
        new java.security.SecureRandom().nextBytes(bytes);
        String id = "group-" + FleetTls.hex(bytes);
        idOut[0] = id;
        return writeList(context, null, null, (identity, list) -> FleetDocument.withGroup(list,
                KioskConfig.deviceIdOf(context), id, name,
                java.time.Instant.now().toString()), "Made the group " + name.trim());
    }

    /** A group removed for good, what it shared with it. */
    static String removeGroup(Context context, String id) {
        java.util.Map<String, Object> held = KioskConfig.fleetDocumentOf(context);
        if (held == null) {
            return "this panel holds no fleet list yet";
        }
        if (FleetDocument.EVERYONE.equals(id) || !FleetDocument.groups(held).containsKey(id)) {
            return "no such group";
        }
        String name = FleetDocument.groupName(held, id);
        return writeList(context, null, null, (identity, list) -> FleetDocument.withoutGroup(list,
                KioskConfig.deviceIdOf(context), id, java.time.Instant.now().toString()),
                "Removed the group " + name);
    }

    /** This panel in exactly {@code groups} (Everyone aside). */
    static String setMyGroups(Context context, java.util.List<String> groups) {
        return setMemberGroups(context, KioskRuntimeState.fleetKey(), groups);
    }

    /** The member of key hash {@code spki} in exactly {@code groups}, from any panel. */
    static String setMemberGroups(Context context, String spki, java.util.List<String> groups) {
        java.util.Map<String, Object> held = KioskConfig.fleetDocumentOf(context);
        if (held == null) {
            return "this panel holds no fleet list yet";
        }
        if (FleetDocument.memberByKey(held, spki) == null) {
            return "no such panel";
        }
        for (String group : groups) {
            if (!FleetDocument.EVERYONE.equals(group)
                    && !FleetDocument.groups(held).containsKey(group)) {
                return "no such group";
            }
        }
        return writeList(context, null, null, (identity, list) -> FleetDocument.withMemberGroups(list,
                KioskConfig.deviceIdOf(context), spki, groups,
                java.time.Instant.now().toString()), "A panel's groups set");
    }

    /**
     * Restore as new (block 15): the version {@code version} of the history, written as a new,
     * higher version by this panel ({@link FleetDocument#restored}: its fleet name, groups and
     * shared settings; the members and revoked keys stay as held now) and handed to every
     * member, which applies it as any change.
     */
    static String restoreVersion(Context context, long version) {
        java.util.Map<String, Object> old = null;
        for (String text : KioskConfig.fleetHistory(context)) {
            java.util.Map<String, Object> entry = FleetDocument.parse(text);
            if (entry != null && FleetDocument.version(entry) == version) {
                old = entry;
                break;
            }
        }
        if (old == null) {
            return "version " + version + " is not in this panel's history";
        }
        java.util.Map<String, Object> held = KioskConfig.fleetDocumentOf(context);
        if (held == null) {
            return "this panel holds no fleet list yet";
        }
        if (version == FleetDocument.version(held)) {
            return "version " + version + " is the one held now";
        }
        java.util.Map<String, Object> restored = old;
        return writeList(context, null, null, (identity, list) -> FleetDocument.restored(restored,
                list, KioskConfig.deviceIdOf(context), java.time.Instant.now().toString()),
                "Restored version " + version + " as a new one");
    }

    /** One version of the history as both surfaces show it. */
    static final class HistoryEntry {
        final long version;
        /** The authoring panel's name, as the list held now knows it, else its id. */
        final String author;
        final String updated;
        /** What it changed against the version before it, one line each; see describeChange. */
        final java.util.List<String> changes;
        final boolean held;

        HistoryEntry(long version, String author, String updated, java.util.List<String> changes,
                boolean held) {
            this.version = version;
            this.author = author;
            this.updated = updated;
            this.changes = changes;
            this.held = held;
        }
    }

    /** The History card: the versions this panel held, newest first, each with its changes. */
    static java.util.List<HistoryEntry> history(Context context) {
        java.util.List<java.util.Map<String, Object>> versions = new java.util.ArrayList<>();
        for (String text : KioskConfig.fleetHistory(context)) {
            java.util.Map<String, Object> entry = FleetDocument.parse(text);
            if (entry != null) {
                versions.add(entry);
            }
        }
        java.util.Map<String, Object> held = KioskConfig.fleetDocumentOf(context);
        java.util.Map<String, String> names = new java.util.HashMap<>();
        for (java.util.Map<String, Object> member : FleetDocument.members(held)) {
            names.put(TinyJson.string(member, FleetDocument.ID, ""),
                    TinyJson.string(member, FleetDocument.NAME, ""));
        }
        java.util.List<HistoryEntry> history = new java.util.ArrayList<>();
        for (int i = 0; i < versions.size(); i++) {
            java.util.Map<String, Object> entry = versions.get(i);
            java.util.Map<String, Object> before = i + 1 < versions.size() ? versions.get(i + 1)
                    : null;
            String author = FleetDocument.author(entry);
            String name = names.get(author);
            history.add(new HistoryEntry(FleetDocument.version(entry),
                    name == null || name.isEmpty() ? author : name,
                    TinyJson.string(entry, "updated", ""),
                    FleetDocument.describeChange(before, entry),
                    // The newest kept is the one held (the store writes it first); two panels
                    // that changed the fleet apart leave two entries of one version number.
                    held != null && i == 0
                            && FleetDocument.version(held) == FleetDocument.version(entry)));
        }
        return history;
    }

    /** Whether this panel follows what the whole fleet shares. */
    static String setFollowsFleet(Context context, boolean follows) {
        return writeList(context, null, null, (identity, list) -> FleetDocument
                .withMemberFollowsFleet(list, KioskConfig.deviceIdOf(context), identity.spki,
                        follows, java.time.Instant.now().toString()),
                follows ? "This panel follows the fleet's shared settings"
                        : "This panel keeps its own values for what the whole fleet shares");
    }

    /** How the next list is built from the one held, under the lock. */
    private interface Change {
        java.util.Map<String, Object> next(FleetIdentity identity,
                java.util.Map<String, Object> held);
    }

    /**
     * The next list, built by {@code change} on the list held at that moment, signed here, kept
     * and handed to every member off the caller's thread. {@code group} and {@code section} name
     * a section this panel just wrote from its own values into a group, so there is nothing of
     * it to apply back.
     */
    private static String writeList(Context context, String group, String section, Change change,
            String why) {
        FleetIdentity identity = FleetIdentity.load(context);
        if (identity == null) {
            return "this panel's fleet key cannot be read";
        }
        String text;
        synchronized (DOCUMENT_LOCK) {
            java.util.Map<String, Object> held = KioskConfig.fleetDocumentOf(context);
            if (held == null) {
                return "this panel holds no fleet list yet";
            }
            java.util.Map<String, Object> next = change.next(identity, held);
            try {
                text = FleetDocument.sign(next, identity.key, identity.certificate.getPublicKey());
            } catch (java.security.GeneralSecurityException failed) {
                Log.w(TAG, "The fleet list could not be signed", failed);
                return "this panel could not sign the fleet's list";
            }
            String before = pageWithFacts(context);
            KioskConfig.edit(context).fleetDocument(context, text).apply();
            reloadPageIfFactsChanged(context, before);
            if (section != null) {
                // Written from this panel's own values: nothing of it to apply back.
                profileApplied.put(section, group + ":" + FleetDocument.version(next));
                KioskRuntimeState.publishFleetProfileRefused(section,
                        java.util.Collections.emptyList());
            }
        }
        Log.i(TAG, why + ", version " + FleetDocument.version(FleetDocument.parse(text)));
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

    /** Per section, the group and version ({@code group:at}) whose values this panel applied last. */
    private static final java.util.concurrent.ConcurrentHashMap<String, String> profileApplied =
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
        String self = KioskConfig.deviceIdOf(context);
        java.util.Map<String, Object> me = FleetDocument.memberByKey(held, identity.spki);
        java.util.List<String> mine = FleetDocument.memberGroups(held, me);
        boolean follows = FleetDocument.followsFleet(me);
        for (String section : FleetDocument.SECTIONS) {
            // The latest of this panel's groups that shares the section; else the whole fleet,
            // while the panel follows it.
            java.util.Map<String, Long> by = FleetDocument.sharedBy(held, section);
            String group = null;
            long at = 0;
            for (java.util.Map.Entry<String, Long> candidate : by.entrySet()) {
                if (!FleetDocument.EVERYONE.equals(candidate.getKey())
                        && mine.contains(candidate.getKey()) && candidate.getValue() > at) {
                    group = candidate.getKey();
                    at = candidate.getValue();
                }
            }
            if (group == null && follows && by.containsKey(FleetDocument.EVERYONE)) {
                group = FleetDocument.EVERYONE;
                at = by.get(FleetDocument.EVERYONE);
            }
            profileSource.put(section, group == null ? "" : group);
            String applied = profileApplied.get(section);
            if (group == null) {
                profileApplied.put(section, "");
                KioskRuntimeState.publishFleetProfileRefused(section,
                        java.util.Collections.emptyList());
                continue;
            }
            java.util.Map<String, Object> shared = FleetDocument.sectionValues(held, group, section);
            if (!(group + ":" + at).equals(applied)) {
                java.util.List<String> refused = FleetProfile.apply(context, section, shared);
                profileApplied.put(section, group + ":" + at);
                KioskRuntimeState.publishFleetProfileRefused(section, refused);
                Log.i(TAG, "Applied the group " + FleetDocument.groupName(held, group) + "'s "
                        + section + " (version " + at + ", " + why + ")"
                        + (refused.isEmpty() ? "" : ", refused: " + refused));
                continue;
            }
            if (!KioskRuntimeState.fleetProfileRefused(section).isEmpty()) {
                continue;
            }
            java.util.Map<String, Object> own = FleetProfile.read(context, section);
            // A secret is a change only when both sides have one and they differ: one this
            // panel cannot read right now, or one the group shares empty (a member keeps its
            // own then), must not be pushed out as if a person had typed it.
            for (String key : FleetDocument.SECRET_KEYS) {
                if (shared.containsKey(key) && (String.valueOf(own.get(key)).isEmpty()
                        || String.valueOf(shared.get(key)).isEmpty())) {
                    own.put(key, shared.get(key));
                }
            }
            // A key this build does not know (a newer panel's) is kept as the group shares it:
            // without this, an older panel would write the section back without it and undo
            // the newer panels' setting (seen with mqtt_tls across two builds).
            for (java.util.Map.Entry<String, Object> entry : shared.entrySet()) {
                if (!own.containsKey(entry.getKey())) {
                    own.put(entry.getKey(), entry.getValue());
                }
            }
            if (!FleetProfile.same(own, shared)) {
                String name = FleetDocument.groupName(held, group);
                Log.i(TAG, "This panel changed the shared " + section + "; told the group " + name);
                String sharing = group;
                writeList(context, sharing, section, (who, list) -> FleetDocument.withSection(list,
                        self, sharing, section, own, java.time.Instant.now().toString()),
                        "Changed " + section + " in the group " + name);
                held = KioskConfig.fleetDocumentOf(context);
                if (held == null) {
                    return;
                }
            }
        }
    }

    /** The page as this panel loads it now, with its facts filled in. */
    private static String pageWithFacts(Context context) {
        return FleetProfile.fillPlaceholders(context, KioskConfig.load(context).dashboardUrl);
    }

    /**
     * A page with placeholders is loaded again when the facts they stand for changed with the
     * list (this panel renamed, moved into a group, the fleet renamed): shown everywhere within
     * seconds, as a change must be.
     */
    private static void reloadPageIfFactsChanged(Context context, String before) {
        String url = KioskConfig.load(context).dashboardUrl;
        if (!before.equals(FleetProfile.fillPlaceholders(context, url))) {
            KioskService service = KioskService.liveService();
            if (service != null) {
                service.setDashboardUrl(url);
            }
        }
    }

    /** Per section, the group whose values apply on this panel; empty when none does. */
    private static final java.util.concurrent.ConcurrentHashMap<String, String> profileSource =
            new java.util.concurrent.ConcurrentHashMap<>();

    static String profileSource(String section) {
        String group = profileSource.get(section);
        return group == null ? "" : group;
    }

    /** The fleet whose combinations this panel took at its setup, for the Join screen's line. */
    private static volatile String setupTakenFrom = "";

    static String setupTakenFrom() {
        return setupTakenFrom;
    }

    /**
     * {@code POST /fleet/setup} from a key this panel trusts, with the caller's escape
     * combinations and PIN hash: the way out, once, at setup. Sent only after the join reply
     * said {@code needs_setup}, so the adder's secrets reach a member that asked, never a
     * panel that merely showed a code. A panel that has combinations keeps them: never shared
     * afterwards. The combinations must be two, valid and different, as the recorder insists.
     */
    private void setup(String caller, byte[] body, OutputStream output) throws IOException {
        JSONObject setup;
        try {
            setup = new JSONObject(new String(body, StandardCharsets.UTF_8));
        } catch (JSONException notJson) {
            respond(output, 400, error("the body must be a JSON object"));
            return;
        }
        KioskConfig config = KioskConfig.load(context);
        if (config.escapeSequencesConfigured()) {
            respond(output, 409, error("this panel has its combinations already"));
            return;
        }
        String settings = setup.optString("settings_sequence", "");
        String launcher = setup.optString("launcher_sequence", "");
        if (!EscapeSequence.isValid(EscapeSequence.parse(settings))
                || !EscapeSequence.isValid(EscapeSequence.parse(launcher))
                || settings.equals(launcher)) {
            respond(output, 400, error("two valid, different combinations are needed"));
            return;
        }
        String pinHash = setup.optString("escape_pin_hash", "");
        try {
            config.settingsSequence = settings;
            config.launcherSequence = launcher;
            config.saveEscapeSequences(context);
            // The PIN as the adder has it: none clears one set here before the combinations.
            KioskConfig.setEscapePinHash(context, pinHash.isEmpty() ? null : pinHash);
        } catch (RuntimeException failed) {
            Log.w(TAG, "The fleet's combinations could not be kept", failed);
            respond(output, 500, error("this panel could not keep the combinations"));
            return;
        }
        setupTakenFrom = KioskConfig.fleetNameOf(context);
        Log.i(TAG, "Took the fleet's combinations" + (pinHash.isEmpty() ? "" : " and PIN")
                + " at setup, from key " + FleetTls.shortForm(caller));
        KioskService.publishTelemetrySoon(context);
        respond(output, 200, fleetState());
    }

    /**
     * {@code POST /fleet/left} from a member that is leaving the fleet from its own panel or
     * web admin: taken off the fleet's list here and the list handed to the others, so it
     * disappears from every Fleet page at once (fleet design: Leave tells one manager).
     */
    private void left(String caller, OutputStream output) throws IOException {
        String refused = removeMember(context, caller, false);
        if (refused != null) {
            respond(output, 409, error(refused));
        } else {
            try {
                respond(output, 200, new JSONObject().put("status", "removed"));
            } catch (JSONException impossible) {
                throw new IllegalStateException(impossible);
            }
        }
    }

    /** {@code POST /fleet/leave} from a key this panel trusts: forget the fleet. */
    private void leave(String caller, OutputStream output) throws IOException {
        leaveFleet(context, "at the request of key " + FleetTls.shortForm(caller));
        respond(output, 200, fleetState());
    }

    /**
     * {@code GET /fleet/screenshot} from a member: this panel's screen as the wall shows it,
     * the broker-size JPEG (KioskService.forBroker, the long side at most 1280 px: the Fleet
     * page shows thumbnails), or the capture's refusal with 409. The member key is the gate,
     * as for every route here; no switch, as the web admin's picture has none.
     */
    private void screenshot(OutputStream output) throws IOException {
        Picture picture = ownPicture();
        if (picture.jpeg == null) {
            respond(output, 409, error(picture.problem));
        } else {
            respond(output, 200, "image/jpeg", picture.jpeg);
        }
    }

    /** {@code POST /fleet/identify} from a member: Identify on this panel, or its refusal. */
    private void identify(OutputStream output) throws IOException {
        String problem = ownIdentify();
        if (problem != null) {
            respond(output, 409, error(problem));
        } else {
            try {
                respond(output, 200, new JSONObject().put("status", "accepted"));
            } catch (JSONException impossible) {
                throw new IllegalStateException(impossible);
            }
        }
    }

    /** A panel's picture for the Fleet page: the JPEG, or the reason there is none. */
    static final class Picture {
        final byte[] jpeg;
        final String problem;

        Picture(byte[] jpeg, String problem) {
            this.jpeg = jpeg;
            this.problem = problem;
        }
    }

    /** What a panel's area on the Fleet page says when it was asked and nothing came back. */
    static final String NO_PICTURE = "No picture: the panel did not answer";
    /** A panel whose service is not up cannot capture or identify; said instead of guessing. */
    private static final String SERVICE_DOWN = "The panel's service is not running";
    /** A screen's JPEG at 1280 px is a few hundred kilobytes; this is the ceiling, not a size. */
    private static final int MAX_PICTURE = 4 * 1024 * 1024;

    private static Picture ownPicture() {
        KioskService service = KioskService.liveService();
        if (service == null) {
            return new Picture(null, SERVICE_DOWN);
        }
        KioskService.Screenshot shot = service.captureScreen();
        if (shot.png == null) {
            return new Picture(null, shot.problem);
        }
        byte[] jpeg = KioskService.forBroker(shot.png);
        return jpeg == null ? new Picture(null, "The panel could not encode its screen")
                : new Picture(jpeg, null);
    }

    private static String ownIdentify() {
        KioskService service = KioskService.liveService();
        return service == null ? SERVICE_DOWN : service.identify();
    }

    /** The member entry with device id {@code id}, or null. */
    static java.util.Map<String, Object> memberById(Context context, String id) {
        for (java.util.Map<String, Object> member
                : FleetDocument.members(KioskConfig.fleetDocumentOf(context))) {
            if (id != null && !id.isEmpty()
                    && id.equals(TinyJson.string(member, FleetDocument.ID, ""))) {
                return member;
            }
        }
        return null;
    }

    private static boolean isSelf(Context context, java.util.Map<String, Object> member) {
        return KioskConfig.deviceIdOf(context).equals(TinyJson.string(member, FleetDocument.ID, ""));
    }

    /**
     * One panel's picture of its screen for the Fleet page of both surfaces: this panel's
     * through its own capture, another's through {@code GET /fleet/screenshot} at its member
     * endpoint, within the call's deadline. A panel that does not answer gets
     * {@link #NO_PICTURE}, never a picture from before. Not on the main thread.
     */
    static Picture screenshotOf(Context context, java.util.Map<String, Object> member) {
        if (isSelf(context, member)) {
            return ownPicture();
        }
        String spki = TinyJson.string(member, FleetDocument.SPKI, "");
        String address = addressOf(member, spki);
        if (address.isEmpty()) {
            return new Picture(null, NO_PICTURE);
        }
        try {
            Reply reply = call(context, address, portOf(member, spki), spki, "GET",
                    "/fleet/screenshot", null, MAX_PICTURE);
            if (reply.status == 200 && reply.contentType.startsWith("image/jpeg")
                    && reply.content.length > 0) {
                return new Picture(reply.content, null);
            }
            return new Picture(null, reply.status == 200 ? NO_PICTURE : reply.reason());
        } catch (IOException | RuntimeException failed) {
            Log.d(TAG, "No picture from key " + FleetTls.shortForm(spki) + ": "
                    + failed.getMessage());
            return new Picture(null, NO_PICTURE);
        }
    }

    /**
     * Identify on one panel, this one or another through {@code POST /fleet/identify}; null
     * when accepted, else the refusal. Not on the main thread.
     */
    static String identifyOn(Context context, java.util.Map<String, Object> member) {
        if (isSelf(context, member)) {
            return ownIdentify();
        }
        String spki = TinyJson.string(member, FleetDocument.SPKI, "");
        String address = addressOf(member, spki);
        if (address.isEmpty()) {
            return "the panel did not answer";
        }
        try {
            Reply reply = call(context, address, portOf(member, spki), spki, "POST",
                    "/fleet/identify", new JSONObject());
            return reply.status == 200 ? null : reply.reason();
        } catch (IOException | RuntimeException failed) {
            Log.d(TAG, "Identify not answered by key " + FleetTls.shortForm(spki) + ": "
                    + failed.getMessage());
            return "the panel did not answer";
        }
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
        respond(output, status, "application/json; charset=utf-8",
                (body.toString() + "\n").getBytes(StandardCharsets.UTF_8));
    }

    private static void respond(OutputStream output, int status, String contentType,
            byte[] content) throws IOException {
        String head = "HTTP/1.1 " + status + " " + reason(status) + "\r\n"
                + "Content-Type: " + contentType + "\r\n"
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
