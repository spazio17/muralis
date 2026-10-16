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
 * <p>Block 10 of the 0.7 plan, so it answers one read-only request, {@code GET /fleet}: this
 * panel's id, its fleet key and the fleet it is in (none yet). The join, the document and the
 * commands are the next blocks. One request per connection, small and bounded: a fleet call is a
 * few hundred bytes between panels, never a browser's burst.
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
        this.tls = FleetTls.context(identity.key, identity.certificate, this::admits)
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
            // Judged again after the handshake, as Syncthing and KDE Connect do: a resumed TLS
            // session presents no certificate and never reaches the trust manager, so a key
            // revoked since its first call would otherwise get back in.
            String caller = callerOf(channel);
            if (!admits(caller)) {
                Log.i(TAG, "Member endpoint refused key " + FleetTls.shortForm(caller));
                return;
            }
            InputStream input = channel.getInputStream();
            OutputStream output = channel.getOutputStream();
            String requestLine = readLine(input);
            if (requestLine == null) {
                return;
            }
            for (int lines = 0; ; lines++) {
                String header = readLine(input);
                if (header == null || header.isEmpty()) {
                    break;
                }
                if (lines >= MAX_HEADER_LINES) {
                    respond(output, 431, error("too many header lines"));
                    return;
                }
            }
            String[] parts = requestLine.split(" ");
            if (parts.length != 3 || !parts[2].startsWith("HTTP/1.")) {
                respond(output, 400, error("not an HTTP/1.1 request"));
                return;
            }
            Log.i(TAG, "Fleet call " + parts[0] + " " + parts[1] + " from "
                    + FleetTls.shortForm(caller));
            if (!"/fleet".equals(parts[1])) {
                respond(output, 404, error("no such endpoint"));
            } else if (!"GET".equals(parts[0])) {
                respond(output, 405, error("only GET"));
            } else {
                respond(output, 200, fleetState());
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
     * What {@code GET /fleet} answers: who this panel is and which fleet it is in. No fleet yet:
     * the join is block 11. Called only after the caller's key was admitted.
     */
    private JSONObject fleetState() {
        try {
            return new JSONObject()
                    .put("id", KioskConfig.deviceIdOf(context))
                    .put("spki", identity.spki)
                    .put("fleet", JSONObject.NULL);
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
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
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 431: return "Request Header Fields Too Large";
            default: return "Error";
        }
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
