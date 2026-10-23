/*
 * Copyright 2026 Juri Calleri (spazio17) and the Muralis contributors
 * Licensed under the PolyForm Shield License 1.0.0: see LICENSE.md at the repository root.
 */
package org.spazio17.muralis;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * The network speaker: the panel as a UPnP/DLNA media renderer for sound, so any DLNA
 * controller finds it by itself and can
 * play an address on it, pause, resume, stop, seek where the stream allows, and set the media
 * volume and mute. The protocol is {@link DlnaProtocol}; this class runs it: an HTTP server on
 * its own port for the descriptions, the control calls and the event subscriptions, SSDP on the
 * multicast group for discovery, and a MediaPlayer on the media stream.
 *
 * <p>Off unless a person switches it on, because DLNA has no authentication: while it runs,
 * anyone on the network can play sound on the panel. Started and stopped by KioskService.
 */
final class NetworkSpeaker implements DlnaProtocol.Renderer {
    private static final String TAG = "MuralisSpeaker";

    private static final int WORKER_THREADS = 4;
    private static final int ACCEPT_QUEUE_DEPTH = 8;
    private static final int SOCKET_TIMEOUT_MS = 5_000;
    /** A request is cut off after this whatever it is doing, so a slow sender cannot hold a worker. */
    private static final int REQUEST_DEADLINE_MS = 10_000;
    private static final int MAX_LINE_LENGTH = 4_096;
    private static final int MAX_HEADER_LINES = 40;
    private static final int SHUTDOWN_WAIT_MS = 1_000;
    private static final long ACCEPT_RETRY_DELAY_MS = 250L;

    /** Three services times a few controllers; a controller takes one per service. */
    private static final int MAX_SUBSCRIPTIONS = 16;
    /** At most one LastChange per service in this time, as AVTransport:1 and RenderingControl:1 ask. */
    private static final long EVENT_MODERATION_MS = 200L;
    /** How often volume and mute are compared, so a change made elsewhere is evented. */
    private static final long STATE_CHECK_MS = 1_000L;
    private static final int EVENT_QUEUE_DEPTH = 64;
    private static final int NOTIFY_CONNECT_MS = 2_000;
    private static final int NOTIFY_READ_MS = 3_000;

    /** Announced again well within the 1800 s max-age, so one lost round is never noticed. */
    private static final long ANNOUNCE_EVERY_MS = 600_000L;
    /** UDP gets lost; every announcement round is sent twice, this far apart. */
    private static final long ANNOUNCE_REPEAT_MS = 2_000L;
    private static final long ADDRESS_CHECK_MS = 5_000L;
    private static final int SSDP_TTL = 2;
    /** ConnectionManager's evented values never change, so only its initial event is sent. */
    private static final DlnaProtocol.Service[] EVENTED_BY_LAST_CHANGE = {
            DlnaProtocol.Service.AV_TRANSPORT, DlnaProtocol.Service.RENDERING_CONTROL};
    /**
     * Search answers sent per second at most. A spoofed M-SEARCH would otherwise turn the panel
     * into an amplifier aimed at whatever address it names.
     */
    private static final int SEARCH_ANSWERS_PER_SECOND = 40;

    private final Context context;
    private final int port;
    private final String friendlyName;
    private final String udn;
    private final String server;
    /** Stops the panel's own sound (KioskService.playAudio's) before this one starts. */
    private final Runnable beforeSound;
    private final AudioManager audio;

    private volatile boolean running;
    private ServerSocket serverSocket;
    private Thread acceptThread;
    private ThreadPoolExecutor workers;
    private final List<Socket> liveSockets = new ArrayList<>();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(
            runnable -> new Thread(runnable, "MuralisSpeakerTimer"));
    private final ThreadPoolExecutor delivery = new ThreadPoolExecutor(1, 1, 0L,
            TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(EVENT_QUEUE_DEPTH),
            runnable -> new Thread(runnable, "MuralisSpeakerEvents"));

    // Discovery. The socket and the address change together when the panel's address does.
    private volatile MulticastSocket ssdpSocket;
    private volatile String location = "";
    private Inet4Address announcedAddress;
    private Thread ssdpThread;
    private WifiManager.MulticastLock multicastLock;
    /** BOOTID.UPNP.ORG: raised each time the speaker joins the network again (UDA 1.1). */
    private volatile long bootId;
    private long searchWindowStart;
    private int searchAnswersInWindow;
    private long lastSearchDropLogMs;

    // Events.
    private static final class Subscription {
        final String sid;
        final DlnaProtocol.Service service;
        final java.net.URI callback;
        long expiresAtMs;
        long seq;

        Subscription(String sid, DlnaProtocol.Service service, java.net.URI callback,
                long expiresAtMs) {
            this.sid = sid;
            this.service = service;
            this.callback = callback;
            this.expiresAtMs = expiresAtMs;
        }
    }

    /** Guards itself and {@link #lastSent}. */
    private final Map<String, Subscription> subscriptions = new LinkedHashMap<>();
    private final Map<DlnaProtocol.Service, Map<String, String>> lastSent =
            new EnumMap<>(DlnaProtocol.Service.class);
    private final Object flushLock = new Object();
    private boolean flushPending;
    private long lastFlushAtMs;
    private long lastDeliveryLogMs;

    // Playback, guarded by playerLock. Never held while calling beforeSound: KioskService calls
    // stopPlayback while holding its own lock, so holding this one into its would deadlock.
    private final Object playerLock = new Object();
    private MediaPlayer player;
    /** Raised whenever the player is replaced, so a late callback of an old one is ignored. */
    private int generation;
    private String transport = DlnaProtocol.NO_MEDIA_PRESENT;
    private boolean errorOccurred;
    private String uri = "";
    private String title = "";
    /** Paused by a spoken sentence, to play on when it ends; cleared by any choice of a person. */
    private boolean pausedForSpeech;

    private NetworkSpeaker(Context context, int port, String friendlyName, String udn,
            Runnable beforeSound) {
        this.context = context.getApplicationContext();
        this.port = port;
        this.friendlyName = friendlyName;
        this.udn = udn;
        this.beforeSound = beforeSound;
        this.audio = this.context.getSystemService(AudioManager.class);
        this.server = "Android/" + Build.VERSION.RELEASE + " UPnP/1.1 Muralis/"
                + BuildConfig.VERSION_NAME;
    }

    /**
     * Starts the speaker on {@code port}, or returns null when that port cannot be had, with
     * the reason in the log; another port is never taken instead.
     */
    static NetworkSpeaker start(Context context, int port, String friendlyName, String udn,
            Runnable beforeSound) {
        NetworkSpeaker speaker = new NetworkSpeaker(context, port, friendlyName, udn, beforeSound);
        try {
            speaker.serverSocket = new ServerSocket();
            speaker.serverSocket.setReuseAddress(true);
            speaker.serverSocket.bind(new InetSocketAddress(port));
        } catch (IOException | IllegalArgumentException taken) {
            Log.e(TAG, "Network speaker off: port " + port + " cannot be used ("
                    + taken.getMessage() + "); it is probably in use, choose another", taken);
            speaker.closeServerSocket();
            speaker.timer.shutdownNow();
            speaker.delivery.shutdownNow();
            return null;
        }
        speaker.begin();
        return speaker;
    }

    private void begin() {
        running = true;
        workers = new ThreadPoolExecutor(WORKER_THREADS, WORKER_THREADS, 0L,
                TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(ACCEPT_QUEUE_DEPTH),
                runnable -> new Thread(runnable, "MuralisSpeakerHttp"));
        acceptThread = new Thread(this::acceptLoop, "MuralisSpeakerAccept");
        acceptThread.start();
        WifiManager wifi = context.getSystemService(WifiManager.class);
        if (wifi != null) {
            // Without it many Wi-Fi drivers drop multicast, and the controllers' searches with it.
            multicastLock = wifi.createMulticastLock("MuralisSpeaker");
            multicastLock.setReferenceCounted(false);
            multicastLock.acquire();
        }
        // Seconds since 1970: larger at every start, as BOOTID must be, without storing it.
        bootId = (System.currentTimeMillis() / 1000) & 0x7FFFFFFFL;
        // The baseline later events are told against; every subscriber gets the whole state
        // in its initial event anyway.
        DlnaProtocol.State now = state();
        synchronized (subscriptions) {
            for (DlnaProtocol.Service service : EVENTED_BY_LAST_CHANGE) {
                lastSent.put(service, DlnaProtocol.evented(service, now));
            }
        }
        ssdpThread = new Thread(this::ssdpLoop, "MuralisSpeakerSsdp");
        ssdpThread.start();
        timer.scheduleWithFixedDelay(this::checkAddress, 0, ADDRESS_CHECK_MS,
                TimeUnit.MILLISECONDS);
        timer.scheduleWithFixedDelay(this::announce, ANNOUNCE_EVERY_MS, ANNOUNCE_EVERY_MS,
                TimeUnit.MILLISECONDS);
        timer.scheduleWithFixedDelay(this::checkState, STATE_CHECK_MS, STATE_CHECK_MS,
                TimeUnit.MILLISECONDS);
        Log.i(TAG, "Network speaker \"" + friendlyName + "\" listening on port " + port);
    }

    /** Says goodbye on the network, stops the sound and lets every thread and socket go. */
    void shutdown() {
        running = false;
        try {
            // On the timer: sending is network work, not allowed on the main thread this is called on.
            timer.submit(this::sayGoodbye).get(SHUTDOWN_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (Exception notSent) {
            Log.w(TAG, "Network speaker could not say goodbye on the network", notSent);
        }
        timer.shutdownNow();
        delivery.shutdownNow();
        closeServerSocket();
        MulticastSocket ssdp = ssdpSocket;
        ssdpSocket = null;
        if (ssdp != null) {
            ssdp.close();
        }
        synchronized (liveSockets) {
            for (Socket socket : liveSockets) {
                closeQuietly(socket);
            }
            liveSockets.clear();
        }
        joinQuietly(acceptThread);
        joinQuietly(ssdpThread);
        if (workers != null) {
            workers.shutdownNow();
        }
        if (multicastLock != null && multicastLock.isHeld()) {
            multicastLock.release();
        }
        synchronized (playerLock) {
            releasePlayer();
        }
        Log.i(TAG, "Network speaker stopped");
    }

    /**
     * Stops what the speaker plays, for the panel's own sound or spoken sentence starting, so
     * those never play over it. Takes only this class's lock, so it may be called holding
     * another.
     */
    /**
     * Pauses what plays for a spoken sentence, to go on after it (resumeAfterSpeech). True when
     * something is paused for speech now, including by an earlier sentence still speaking.
     */
    boolean pauseForSpeech() {
        boolean paused = false;
        synchronized (playerLock) {
            if (player != null && DlnaProtocol.PLAYING.equals(transport)) {
                try {
                    player.pause();
                    transport = DlnaProtocol.PAUSED_PLAYBACK;
                    pausedForSpeech = true;
                    paused = true;
                } catch (IllegalStateException refused) {
                    // Not in a state to pause: it plays on under the sentence.
                }
            } else {
                paused = pausedForSpeech;
            }
        }
        if (paused) {
            requestFlush();
        }
        return paused;
    }

    /** The sentence ended: what speech paused plays on, unless someone chose otherwise meanwhile. */
    void resumeAfterSpeech() {
        boolean resumed = false;
        synchronized (playerLock) {
            if (pausedForSpeech && player != null
                    && DlnaProtocol.PAUSED_PLAYBACK.equals(transport)) {
                player.start();
                transport = DlnaProtocol.PLAYING;
                resumed = true;
            }
            pausedForSpeech = false;
        }
        if (resumed) {
            requestFlush();
        }
    }

    /** Whether the speaker is playing now, for the fleet's Speakers card. */
    boolean playing() {
        synchronized (playerLock) {
            return player != null && DlnaProtocol.PLAYING.equals(transport);
        }
    }

    void stopPlayback() {
        boolean stopped;
        synchronized (playerLock) {
            stopped = player != null;
            if (stopped) {
                releasePlayer();
                transport = DlnaProtocol.STOPPED;
            }
        }
        if (stopped) {
            Log.i(TAG, "Network speaker stopped for the panel's own sound");
            requestFlush();
        }
    }

    // ---------------------------------------------------------------------------------------
    // HTTP
    // ---------------------------------------------------------------------------------------

    private void acceptLoop() {
        while (running) {
            Socket socket;
            try {
                socket = serverSocket.accept();
            } catch (IOException failed) {
                if (running) {
                    Log.w(TAG, "Network speaker accept failed", failed);
                    // A persistent cause (no file descriptors left) would otherwise spin.
                    SystemClock.sleep(ACCEPT_RETRY_DELAY_MS);
                }
                continue;
            }
            synchronized (liveSockets) {
                liveSockets.add(socket);
            }
            try {
                workers.execute(() -> serve(socket));
            } catch (RejectedExecutionException busy) {
                forget(socket);
            }
        }
    }

    private void serve(Socket socket) {
        ScheduledFuture<?> deadline = null;
        try {
            deadline = timer.schedule(() -> closeQuietly(socket), REQUEST_DEADLINE_MS,
                    TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException stopping) {
            forget(socket);
            return;
        }
        try {
            socket.setSoTimeout(SOCKET_TIMEOUT_MS);
            InputStream input = new BufferedInputStream(socket.getInputStream());
            OutputStream output = socket.getOutputStream();
            String requestLine = readLine(input);
            if (requestLine == null || requestLine.isEmpty()) {
                return;
            }
            String[] parts = requestLine.split(" ");
            if (parts.length != 3 || !parts[2].startsWith("HTTP/1.")) {
                reply(output, 400, "Bad Request", null, null);
                return;
            }
            Map<String, String> headers = new LinkedHashMap<>();
            for (int i = 0; ; i++) {
                String line = readLine(input);
                if (line == null || i >= MAX_HEADER_LINES) {
                    reply(output, 400, "Bad Request", null, null);
                    return;
                }
                if (line.isEmpty()) {
                    break;
                }
                int colon = line.indexOf(':');
                if (colon > 0) {
                    headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT),
                            line.substring(colon + 1).trim());
                }
            }
            String method = parts[0];
            String path = parts[1];
            int query = path.indexOf('?');
            if (query >= 0) {
                path = path.substring(0, query);
            }
            String remote = socket.getInetAddress() == null ? "?"
                    : socket.getInetAddress().getHostAddress();
            switch (method) {
                case "GET":
                case "HEAD":
                    describe(output, path, method.equals("HEAD"));
                    break;
                case "POST":
                    control(input, output, path, headers, remote);
                    break;
                case "SUBSCRIBE":
                    subscribe(output, path, headers, remote);
                    break;
                case "UNSUBSCRIBE":
                    unsubscribe(output, path, headers);
                    break;
                default:
                    reply(output, 405, "Method Not Allowed", null, null);
                    break;
            }
        } catch (IOException dropped) {
            // A closed, timed-out or cut-off connection: nothing to answer.
        } finally {
            deadline.cancel(false);
            forget(socket);
        }
    }

    private void describe(OutputStream output, String path, boolean headOnly) throws IOException {
        String body;
        if (DlnaProtocol.DESCRIPTION_PATH.equals(path)) {
            body = DlnaProtocol.deviceDescription(friendlyName, udn);
        } else {
            DlnaProtocol.Service service = DlnaProtocol.scpdAt(path);
            if (service == null) {
                reply(output, 404, "Not Found", null, null);
                return;
            }
            body = DlnaProtocol.serviceDescription(service);
        }
        if (headOnly) {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            writeHead(output, 200, "OK", "text/xml; charset=\"utf-8\"", bytes.length, null);
            output.flush();
        } else {
            reply(output, 200, "OK", body, null);
        }
    }

    private void control(InputStream input, OutputStream output, String path,
            Map<String, String> headers, String remote) throws IOException {
        DlnaProtocol.Service service = DlnaProtocol.controlAt(path);
        if (service == null) {
            reply(output, 404, "Not Found", null, null);
            return;
        }
        if (headers.containsKey("transfer-encoding")) {
            Log.i(TAG, "Network speaker refused a chunked call from " + remote
                    + ": a control call needs a Content-Length");
            reply(output, 411, "Length Required", null, null);
            return;
        }
        int length;
        try {
            length = Integer.parseInt(headers.getOrDefault("content-length", "-1"));
        } catch (NumberFormatException notNumber) {
            length = -1;
        }
        if (length < 0) {
            reply(output, 411, "Length Required", null, null);
            return;
        }
        if (length > DlnaProtocol.MAX_SOAP_BYTES) {
            Log.i(TAG, "Network speaker refused a call of " + length + " bytes from " + remote
                    + ": at most " + DlnaProtocol.MAX_SOAP_BYTES);
            reply(output, 413, "Payload Too Large", null, null);
            return;
        }
        byte[] body = new byte[length];
        int read = 0;
        while (read < length) {
            int got = input.read(body, read, length - read);
            if (got < 0) {
                return;
            }
            read += got;
        }
        String soapAction = headers.get("soapaction");
        String action = service.name;
        try {
            DlnaProtocol.Call call = DlnaProtocol.parseCall(service, soapAction, body);
            action = service.name + "#" + call.action;
            String answer = DlnaProtocol.answer(call, this);
            if (!call.action.startsWith("Get")) {
                Log.i(TAG, "Network speaker: " + action + " from " + remote
                        + (call.action.equals("SetAVTransportURI")
                                ? " " + AppLog.withoutSecrets(call.arguments.get("CurrentURI"))
                                : ""));
            }
            reply(output, 200, "OK", answer, null);
        } catch (DlnaProtocol.Fault fault) {
            Log.i(TAG, "Network speaker refused " + action + " from " + remote + ": "
                    + fault.code + " " + fault.getMessage());
            reply(output, 500, "Internal Server Error", DlnaProtocol.fault(fault), null);
        }
    }

    private void subscribe(OutputStream output, String path, Map<String, String> headers,
            String remote) throws IOException {
        DlnaProtocol.Service service = DlnaProtocol.eventAt(path);
        if (service == null) {
            reply(output, 404, "Not Found", null, null);
            return;
        }
        String sid = headers.get("sid");
        boolean renewal = sid != null;
        if (renewal && (headers.containsKey("callback") || headers.containsKey("nt"))) {
            reply(output, 400, "Bad Request", null, null);
            return;
        }
        int seconds = DlnaProtocol.subscriptionSeconds(headers.get("timeout"));
        long now = SystemClock.elapsedRealtime();
        if (renewal) {
            boolean renewed;
            synchronized (subscriptions) {
                expireSubscriptions(now);
                Subscription known = subscriptions.get(sid);
                renewed = known != null && known.service == service;
                if (renewed) {
                    known.expiresAtMs = now + seconds * 1000L;
                }
            }
            if (renewed) {
                reply(output, 200, "OK", null, subscriptionHeaders(sid, seconds));
            } else {
                // Expired or never known: the controller subscribes anew.
                reply(output, 412, "Precondition Failed", null, null);
            }
            return;
        }
        String callback = DlnaProtocol.callbackOf(headers.get("callback"));
        if (!"upnp:event".equals(headers.get("nt")) || callback == null) {
            Log.i(TAG, "Network speaker refused a subscription from " + remote
                    + ": it needs NT upnp:event and an http CALLBACK");
            reply(output, 412, "Precondition Failed", null, null);
            return;
        }
        java.net.URI callbackUri = java.net.URI.create(callback);
        if (!sameHost(callbackUri.getHost(), remote)) {
            // Events go back only to the controller that asked for them: otherwise anyone on
            // the network could make the panel post to any host and port it names.
            Log.i(TAG, "Network speaker refused a subscription from " + remote
                    + ": its CALLBACK names another host");
            reply(output, 412, "Precondition Failed", null, null);
            return;
        }
        Subscription subscription = new Subscription("uuid:" + UUID.randomUUID(), service,
                callbackUri, now + seconds * 1000L);
        boolean full;
        synchronized (subscriptions) {
            expireSubscriptions(now);
            full = subscriptions.size() >= MAX_SUBSCRIPTIONS;
        }
        if (full) {
            Log.w(TAG, "Network speaker refused a subscription from " + remote + ": "
                    + MAX_SUBSCRIPTIONS + " are held already");
            reply(output, 503, "Service Unavailable", null, null);
            return;
        }
        reply(output, 200, "OK", null, subscriptionHeaders(subscription.sid, seconds));
        // The initial event, everything, after the answer that carries the SID; queued under
        // the same lock as later events, so it is the subscriber's first and has SEQ 0.
        String initial = DlnaProtocol.eventBody(service, null,
                DlnaProtocol.evented(service, state()));
        synchronized (subscriptions) {
            subscriptions.put(subscription.sid, subscription);
            deliver(subscription, initial);
        }
    }

    /** Whether a callback's host is the subscriber's own address. */
    private static boolean sameHost(String host, String remote) {
        if (host == null || remote == null) {
            return false;
        }
        String bare = host.startsWith("[") && host.endsWith("]")
                ? host.substring(1, host.length() - 1) : host;
        try {
            return java.net.InetAddress.getByName(bare).equals(java.net.InetAddress.getByName(remote));
        } catch (java.net.UnknownHostException unknown) {
            return false;
        }
    }

    private void unsubscribe(OutputStream output, String path, Map<String, String> headers)
            throws IOException {
        DlnaProtocol.Service service = DlnaProtocol.eventAt(path);
        String sid = headers.get("sid");
        boolean removed;
        synchronized (subscriptions) {
            Subscription known = sid == null ? null : subscriptions.get(sid);
            removed = known != null && known.service == service;
            if (removed) {
                subscriptions.remove(sid);
            }
        }
        if (removed) {
            reply(output, 200, "OK", null, null);
        } else {
            reply(output, 412, "Precondition Failed", null, null);
        }
    }

    private static Map<String, String> subscriptionHeaders(String sid, int seconds) {
        Map<String, String> extra = new LinkedHashMap<>();
        extra.put("SID", sid);
        extra.put("TIMEOUT", "Second-" + seconds);
        return extra;
    }

    private void reply(OutputStream output, int status, String reason, String body,
            Map<String, String> extra) throws IOException {
        byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        writeHead(output, status, reason, body == null ? null : "text/xml; charset=\"utf-8\"",
                bytes.length, extra);
        output.write(bytes);
        output.flush();
    }

    private void writeHead(OutputStream output, int status, String reason, String contentType,
            int length, Map<String, String> extra) throws IOException {
        StringBuilder head = new StringBuilder(256);
        head.append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
                .append("DATE: ").append(DlnaProtocol.httpDate(System.currentTimeMillis()))
                .append("\r\n")
                .append("SERVER: ").append(server).append("\r\n")
                .append("EXT:\r\n");
        if (contentType != null) {
            head.append("CONTENT-TYPE: ").append(contentType).append("\r\n");
        }
        if (extra != null) {
            for (Map.Entry<String, String> header : extra.entrySet()) {
                head.append(header.getKey()).append(": ").append(header.getValue()).append("\r\n");
            }
        }
        head.append("CONTENT-LENGTH: ").append(length).append("\r\n")
                .append("CONNECTION: close\r\n\r\n");
        output.write(head.toString().getBytes(StandardCharsets.US_ASCII));
    }

    /** One header or request line, without its line end; null at the end or when too long. */
    private static String readLine(InputStream input) throws IOException {
        StringBuilder line = new StringBuilder(128);
        while (true) {
            int c = input.read();
            if (c < 0) {
                return line.length() == 0 ? null : line.toString();
            }
            if (c == '\n') {
                int end = line.length();
                if (end > 0 && line.charAt(end - 1) == '\r') {
                    line.setLength(end - 1);
                }
                return line.toString();
            }
            if (line.length() >= MAX_LINE_LENGTH) {
                return null;
            }
            line.append((char) c);
        }
    }

    // ---------------------------------------------------------------------------------------
    // Events
    // ---------------------------------------------------------------------------------------

    /** Something changed: an event goes out at once, or within the moderation time. */
    private void requestFlush() {
        synchronized (flushLock) {
            if (flushPending || !running) {
                return;
            }
            flushPending = true;
            long wait = Math.max(0, lastFlushAtMs + EVENT_MODERATION_MS
                    - SystemClock.elapsedRealtime());
            try {
                timer.schedule(this::flush, wait, TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException stopping) {
                flushPending = false;
            }
        }
    }

    /** Sends each service's changes since the last event to its subscribers. Timer thread. */
    private void flush() {
        synchronized (flushLock) {
            flushPending = false;
            lastFlushAtMs = SystemClock.elapsedRealtime();
        }
        DlnaProtocol.State state = state();
        synchronized (subscriptions) {
            expireSubscriptions(SystemClock.elapsedRealtime());
            for (DlnaProtocol.Service service : EVENTED_BY_LAST_CHANGE) {
                Map<String, String> now = DlnaProtocol.evented(service, state);
                String body = DlnaProtocol.eventBody(service, lastSent.put(service, now), now);
                if (body == null) {
                    continue;
                }
                for (Subscription subscription : subscriptions.values()) {
                    if (subscription.service == service) {
                        deliver(subscription, body);
                    }
                }
            }
        }
    }

    /** Catches volume and mute changed by the buttons or another app. Timer thread. */
    private void checkState() {
        boolean anyone;
        synchronized (subscriptions) {
            anyone = !subscriptions.isEmpty();
        }
        if (anyone) {
            requestFlush();
        }
    }

    /** Caller holds {@link #subscriptions}. */
    private void expireSubscriptions(long now) {
        Iterator<Subscription> each = subscriptions.values().iterator();
        while (each.hasNext()) {
            if (each.next().expiresAtMs <= now) {
                each.remove();
            }
        }
    }

    /** Queues one NOTIFY; a full queue drops it rather than replay a pile later. */
    private void deliver(Subscription subscription, String body) {
        try {
            delivery.execute(() -> send(subscription, body));
        } catch (RejectedExecutionException full) {
            logDeliveryProblem("event dropped, the queue is full");
        }
    }

    private void send(Subscription subscription, String body) {
        long seq;
        synchronized (subscriptions) {
            if (subscriptions.get(subscription.sid) != subscription) {
                return; // Unsubscribed or expired meanwhile.
            }
            seq = subscription.seq;
            // SEQ wraps to 1, never to 0, which is the initial event's alone (UDA 1.1, 4.2).
            subscription.seq = seq >= 0xFFFFFFFFL ? 1 : seq + 1;
        }
        java.net.URI callback = subscription.callback;
        int callbackPort = callback.getPort() < 0 ? 80 : callback.getPort();
        String target = callback.getRawPath() == null || callback.getRawPath().isEmpty() ? "/"
                : callback.getRawPath();
        if (callback.getRawQuery() != null) {
            target += "?" + callback.getRawQuery();
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String head = "NOTIFY " + target + " HTTP/1.1\r\n"
                + "HOST: " + callback.getHost() + ":" + callbackPort + "\r\n"
                + "CONTENT-TYPE: text/xml; charset=\"utf-8\"\r\n"
                + "CONTENT-LENGTH: " + bytes.length + "\r\n"
                + "NT: upnp:event\r\n"
                + "NTS: upnp:propchange\r\n"
                + "SID: " + subscription.sid + "\r\n"
                + "SEQ: " + seq + "\r\n"
                + "CONNECTION: close\r\n\r\n";
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(callback.getHost(), callbackPort),
                    NOTIFY_CONNECT_MS);
            socket.setSoTimeout(NOTIFY_READ_MS);
            OutputStream output = socket.getOutputStream();
            output.write(head.getBytes(StandardCharsets.US_ASCII));
            output.write(bytes);
            output.flush();
            String status = readLine(socket.getInputStream());
            if (status == null || !status.matches("HTTP/1\\.\\d 2\\d\\d.*")) {
                logDeliveryProblem("an event was answered " + status);
            }
        } catch (IOException | IllegalArgumentException unreachable) {
            // Kept until it expires, as UDA 1.1 says: the controller may be back.
            logDeliveryProblem("an event could not be delivered (" + unreachable.getMessage()
                    + ")");
        }
    }

    private void logDeliveryProblem(String what) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastDeliveryLogMs >= 60_000L) {
            lastDeliveryLogMs = now;
            Log.w(TAG, "Network speaker: " + what);
        }
    }

    // ---------------------------------------------------------------------------------------
    // Discovery (SSDP)
    // ---------------------------------------------------------------------------------------

    /**
     * Follows the panel's address. A new one (the first included) means a new socket on its
     * interface, a raised BOOTID and an announcement, so controllers move to the new address
     * at once rather than when the old announcement runs out. Timer thread.
     */
    private void checkAddress() {
        Inet4Address address = null;
        NetworkInterface networkInterface = null;
        ConnectivityManager connectivity = context.getSystemService(ConnectivityManager.class);
        LinkProperties link = connectivity == null ? null
                : connectivity.getLinkProperties(connectivity.getActiveNetwork());
        if (link != null) {
            for (LinkAddress linkAddress : link.getLinkAddresses()) {
                InetAddress inet = linkAddress.getAddress();
                if (inet instanceof Inet4Address && !inet.isLoopbackAddress()
                        && !inet.isLinkLocalAddress()) {
                    address = (Inet4Address) inet;
                    break;
                }
            }
            try {
                networkInterface = link.getInterfaceName() == null ? null
                        : NetworkInterface.getByName(link.getInterfaceName());
            } catch (IOException unknown) {
                networkInterface = null;
            }
        }
        if (address == null ? announcedAddress == null : address.equals(announcedAddress)) {
            return;
        }
        MulticastSocket old = ssdpSocket;
        ssdpSocket = null;
        if (old != null) {
            old.close();
        }
        announcedAddress = address;
        if (address == null) {
            location = "";
            Log.i(TAG, "Network speaker has no IPv4 address; discovery waits for one");
            return;
        }
        try {
            MulticastSocket socket = new MulticastSocket(null);
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(DlnaProtocol.SSDP_PORT));
            socket.setTimeToLive(SSDP_TTL);
            socket.setSoTimeout(1_000);
            InetSocketAddress group = new InetSocketAddress(
                    InetAddress.getByName(DlnaProtocol.SSDP_ADDRESS), DlnaProtocol.SSDP_PORT);
            if (networkInterface != null) {
                socket.setNetworkInterface(networkInterface);
            }
            socket.joinGroup(group, networkInterface);
            location = "http://" + address.getHostAddress() + ":" + port
                    + DlnaProtocol.DESCRIPTION_PATH;
            if (old != null) {
                bootId = (bootId + 1) & 0x7FFFFFFFL;
            }
            ssdpSocket = socket;
            Log.i(TAG, "Network speaker announced at " + location);
            announce();
            timer.schedule(this::announce, ANNOUNCE_REPEAT_MS, TimeUnit.MILLISECONDS);
        } catch (IOException | RuntimeException failed) {
            // The speaker still answers on its port; only finding it by itself fails.
            location = "";
            announcedAddress = null;
            Log.w(TAG, "Network speaker cannot use discovery on "
                    + address.getHostAddress() + "; tried again shortly", failed);
        }
    }

    /** One ssdp:alive round. Timer thread. */
    private void announce() {
        MulticastSocket socket = ssdpSocket;
        String where = location;
        if (socket == null || where.isEmpty() || !running) {
            return;
        }
        for (String[] pair : DlnaProtocol.announcements(udn)) {
            sendDatagram(socket, DlnaProtocol.alive(pair[0], pair[1], where, server, bootId),
                    groupAddress());
        }
    }

    private void sayGoodbye() {
        MulticastSocket socket = ssdpSocket;
        if (socket == null) {
            return;
        }
        for (String[] pair : DlnaProtocol.announcements(udn)) {
            sendDatagram(socket, DlnaProtocol.byebye(pair[0], pair[1], bootId), groupAddress());
        }
    }

    private static SocketAddress groupAddress() {
        return new InetSocketAddress(DlnaProtocol.SSDP_ADDRESS, DlnaProtocol.SSDP_PORT);
    }

    private void ssdpLoop() {
        byte[] buffer = new byte[2048];
        while (running) {
            MulticastSocket socket = ssdpSocket;
            if (socket == null) {
                SystemClock.sleep(1_000);
                continue;
            }
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                socket.receive(packet);
            } catch (SocketTimeoutException quiet) {
                continue;
            } catch (IOException closed) {
                // Replaced for a new address or stopped; the next round picks up the new one.
                continue;
            }
            DlnaProtocol.Search search = DlnaProtocol.parseSearch(new String(packet.getData(), 0,
                    packet.getLength(), StandardCharsets.UTF_8));
            if (search == null) {
                continue;
            }
            List<String> answers = DlnaProtocol.searchResponses(search.target, udn, location,
                    server, bootId, DlnaProtocol.httpDate(System.currentTimeMillis()));
            SocketAddress asker = packet.getSocketAddress();
            for (String answer : answers) {
                if (!searchBudget()) {
                    break;
                }
                // Spread over the wait the searcher allowed, as UDA 1.1 asks.
                long delay = (long) (Math.random() * search.waitSeconds * 1000);
                try {
                    timer.schedule(() -> sendDatagram(socket, answer, asker), delay,
                            TimeUnit.MILLISECONDS);
                } catch (RejectedExecutionException stopping) {
                    return;
                }
            }
        }
    }

    /** Whether one more search answer fits in this second's budget. */
    private boolean searchBudget() {
        long now = SystemClock.elapsedRealtime();
        if (now - searchWindowStart >= 1_000L) {
            searchWindowStart = now;
            searchAnswersInWindow = 0;
        }
        if (searchAnswersInWindow >= SEARCH_ANSWERS_PER_SECOND) {
            if (now - lastSearchDropLogMs >= 60_000L) {
                lastSearchDropLogMs = now;
                Log.w(TAG, "Network speaker: too many searches, some go unanswered");
            }
            return false;
        }
        searchAnswersInWindow++;
        return true;
    }

    private void sendDatagram(MulticastSocket socket, String text, SocketAddress to) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        try {
            socket.send(new DatagramPacket(bytes, bytes.length, to));
        } catch (IOException | RuntimeException failed) {
            Log.d(TAG, "Network speaker: an SSDP message could not be sent", failed);
        }
    }

    // ---------------------------------------------------------------------------------------
    // The renderer
    // ---------------------------------------------------------------------------------------

    @Override
    public DlnaProtocol.State state() {
        synchronized (playerLock) {
            long duration = -1;
            long position = 0;
            if (player != null && (DlnaProtocol.PLAYING.equals(transport)
                    || DlnaProtocol.PAUSED_PLAYBACK.equals(transport))) {
                try {
                    int length = player.getDuration();
                    duration = length > 0 ? length : -1;
                    position = Math.max(0, player.getCurrentPosition());
                } catch (IllegalStateException between) {
                    duration = -1;
                }
            }
            return new DlnaProtocol.State(transport, errorOccurred, uri, title, duration, position,
                    duration > 0, volume(), muted());
        }
    }

    @Override
    public void setUri(String address, String newTitle) throws DlnaProtocol.Fault {
        boolean wasPlaying;
        synchronized (playerLock) {
            wasPlaying = DlnaProtocol.PLAYING.equals(transport)
                    || DlnaProtocol.TRANSITIONING.equals(transport);
            releasePlayer();
            uri = address;
            title = newTitle;
            errorOccurred = false;
            transport = DlnaProtocol.STOPPED;
        }
        if (wasPlaying) {
            play();
        } else {
            requestFlush();
        }
    }

    @Override
    public void play() throws DlnaProtocol.Fault {
        boolean resumed = false;
        synchronized (playerLock) {
            if (DlnaProtocol.PAUSED_PLAYBACK.equals(transport) && player != null) {
                resumed = true;
            } else {
                prepare();
            }
        }
        if (resumed) {
            // Resuming starts sound at once, so the panel's own sound stops first, outside the lock.
            beforeSound.run();
            synchronized (playerLock) {
                if (player != null && DlnaProtocol.PAUSED_PLAYBACK.equals(transport)) {
                    player.start();
                    transport = DlnaProtocol.PLAYING;
                }
            }
        }
        requestFlush();
    }

    /** Caller holds playerLock. Opens the address; it plays once prepared (see {@link #prepared}). */
    private void prepare() throws DlnaProtocol.Fault {
        pausedForSpeech = false;
        releasePlayer();
        errorOccurred = false;
        MediaPlayer next = new MediaPlayer();
        int mine = generation;
        try {
            next.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());
            next.setDataSource(uri);
            next.setOnPreparedListener(ready -> prepared(mine));
            next.setOnCompletionListener(done -> finished(mine, null));
            next.setOnErrorListener((failed, what, extra) -> {
                finished(mine, "error " + what + "/" + extra);
                return true;
            });
            next.prepareAsync();
        } catch (IOException | RuntimeException unplayable) {
            next.release();
            transport = DlnaProtocol.STOPPED;
            errorOccurred = true;
            Log.w(TAG, "Network speaker cannot open " + AppLog.withoutSecrets(uri), unplayable);
            throw new DlnaProtocol.Fault(716, "the panel cannot open that address");
        }
        player = next;
        // TRANSITIONING until the player really starts: PLAYING is never claimed before.
        transport = DlnaProtocol.TRANSITIONING;
    }

    /** The player is ready: it starts, after the panel's own sound stopped. Main thread. */
    private void prepared(int which) {
        synchronized (playerLock) {
            if (which != generation || !DlnaProtocol.TRANSITIONING.equals(transport)) {
                return;
            }
        }
        beforeSound.run();
        synchronized (playerLock) {
            if (which != generation || player == null
                    || !DlnaProtocol.TRANSITIONING.equals(transport)) {
                return;
            }
            try {
                player.start();
                transport = DlnaProtocol.PLAYING;
            } catch (IllegalStateException refused) {
                releasePlayer();
                transport = DlnaProtocol.STOPPED;
                errorOccurred = true;
                Log.w(TAG, "Network speaker could not start " + AppLog.withoutSecrets(uri),
                        refused);
            }
        }
        requestFlush();
    }

    /** The sound ended, by itself or with {@code error}; the address stays for another Play. */
    private void finished(int which, String error) {
        synchronized (playerLock) {
            if (which != generation) {
                return;
            }
            releasePlayer();
            transport = DlnaProtocol.STOPPED;
            errorOccurred = error != null;
        }
        if (error != null) {
            Log.w(TAG, "Network speaker playback failed (" + error + "): "
                    + AppLog.withoutSecrets(uri));
        }
        requestFlush();
    }

    @Override
    public void pause() throws DlnaProtocol.Fault {
        synchronized (playerLock) {
            // A pause asked for by a person stays: the end of a sentence does not undo it.
            pausedForSpeech = false;
            if (player == null || !DlnaProtocol.PLAYING.equals(transport)) {
                throw new DlnaProtocol.Fault(701, "Pause is not available while " + transport);
            }
            try {
                player.pause();
            } catch (IllegalStateException refused) {
                throw new DlnaProtocol.Fault(701, "the player could not pause");
            }
            transport = DlnaProtocol.PAUSED_PLAYBACK;
        }
        requestFlush();
    }

    @Override
    public void stop() {
        synchronized (playerLock) {
            pausedForSpeech = false;
            releasePlayer();
            transport = uri.isEmpty() ? DlnaProtocol.NO_MEDIA_PRESENT : DlnaProtocol.STOPPED;
            errorOccurred = false;
        }
        requestFlush();
    }

    @Override
    public void seek(long positionMs) throws DlnaProtocol.Fault {
        synchronized (playerLock) {
            if (player == null) {
                throw new DlnaProtocol.Fault(701, "nothing is playing");
            }
            try {
                player.seekTo((int) Math.min(Integer.MAX_VALUE, positionMs));
            } catch (IllegalStateException refused) {
                throw new DlnaProtocol.Fault(701, "the player could not seek now");
            }
        }
    }

    /** Caller holds playerLock. */
    private void releasePlayer() {
        generation++;
        if (player == null) {
            return;
        }
        MediaPlayer old = player;
        player = null;
        try {
            old.stop();
        } catch (IllegalStateException notStarted) {
            // Never started or already stopped: releasing is all that is needed.
        }
        old.release();
    }

    @Override
    public void setVolume(int percent) throws DlnaProtocol.Fault {
        if (audio == null || audio.isVolumeFixed()) {
            throw new DlnaProtocol.Fault(501, "this device's volume cannot be changed");
        }
        int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        try {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(max * percent / 100f), 0);
        } catch (SecurityException refused) {
            throw new DlnaProtocol.Fault(501, "Android refused the volume change");
        }
        requestFlush();
    }

    @Override
    public void setMute(boolean mute) throws DlnaProtocol.Fault {
        if (audio == null || audio.isVolumeFixed()) {
            throw new DlnaProtocol.Fault(501, "this device's volume cannot be changed");
        }
        try {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                    mute ? AudioManager.ADJUST_MUTE : AudioManager.ADJUST_UNMUTE, 0);
        } catch (SecurityException refused) {
            throw new DlnaProtocol.Fault(501, "Android refused the mute change");
        }
        requestFlush();
    }

    /** The media volume as 0 to 100, read from the device each time. */
    private int volume() {
        if (audio == null) {
            return 0;
        }
        int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        return max <= 0 ? 0
                : Math.round(audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100f / max);
    }

    private boolean muted() {
        return audio != null && audio.isStreamMute(AudioManager.STREAM_MUSIC);
    }

    // ---------------------------------------------------------------------------------------

    private void forget(Socket socket) {
        synchronized (liveSockets) {
            liveSockets.remove(socket);
        }
        closeQuietly(socket);
    }

    private void closeServerSocket() {
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
                // Being discarded either way.
            }
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Being discarded either way.
        }
    }

    private static void joinQuietly(Thread thread) {
        if (thread == null) {
            return;
        }
        try {
            thread.join(SHUTDOWN_WAIT_MS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
