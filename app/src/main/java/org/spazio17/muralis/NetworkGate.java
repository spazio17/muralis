/*
 * Copyright 2026 Muralis contributors
 * All rights reserved. See LICENSE at the repository root.
 */
package org.spazio17.muralis;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * Runs one piece of work as soon as the panel has a network, and not before.
 *
 * <p>Muralis starts within a couple of seconds of boot, which is exactly what a wall panel wants and
 * is also early enough that Wi-Fi has usually not associated yet. Without this gate the dashboard's
 * very first load fails with {@code ERR_NAME_NOT_RESOLVED} and the panel shows a Chromium error page
 * until the retry backoff comes round, and MQTT logs a connection failure it did not need to make.
 * Neither is a real fault; both look like one to whoever is standing in front of the panel.
 *
 * <p>Three deliberate choices:
 *
 * <ul>
 *   <li><b>{@code NET_CAPABILITY_INTERNET}, never {@code NET_CAPABILITY_VALIDATED}.</b> Validation
 *       means Android reached a Google connectivity-check endpoint. A dashboard on a LAN with no
 *       route to the internet, which is a perfectly ordinary Home Assistant deployment and is this
 *       project's own, would never validate, and a gate that waits for validation would hold such a
 *       panel dark forever.
 *   <li><b>It fires at most once</b>, and unregisters itself when it does. This is a startup gate,
 *       not a connectivity supervisor: once the dashboard is loading, recovery from a network drop
 *       belongs to the WebView retry path in KioskActivity, which already exists.
 *   <li><b>It gives up after {@link #MAX_WAIT_MS} and runs the work anyway.</b> Fail soft, the same
 *       discipline as everywhere else in this app: a device with no ConnectivityManager, a
 *       capability set this code did not anticipate, or Wi-Fi that is genuinely down must end with a
 *       panel that tried and shows an error, not a panel that waits in silence forever.
 * </ul>
 */
final class NetworkGate {
    private static final String TAG = "MuralisNetwork";

    /** Long enough for Wi-Fi to associate and DHCP to finish from cold; short enough to notice. */
    static final long MAX_WAIT_MS = 60_000L;

    private final ConnectivityManager connectivity;
    private final Handler handler;
    private final Runnable work;
    private final String label;
    private ConnectivityManager.NetworkCallback callback;
    private boolean spent;

    private NetworkGate(ConnectivityManager connectivity, Handler handler, Runnable work,
            String label) {
        this.connectivity = connectivity;
        this.handler = handler;
        this.work = work;
        this.label = label;
    }

    /**
     * True when some network is currently carrying traffic. Read from the active network's
     * capabilities rather than the deprecated {@code getActiveNetworkInfo}, and deliberately not
     * requiring validation, for the reason in this class's own documentation.
     */
    static boolean isOnline(Context context) {
        ConnectivityManager connectivity = context.getSystemService(ConnectivityManager.class);
        return connectivity != null && hasUsableNetwork(connectivity);
    }

    private static boolean hasUsableNetwork(ConnectivityManager connectivity) {
        Network active = connectivity.getActiveNetwork();
        if (active == null) {
            return false;
        }
        NetworkCapabilities capabilities = connectivity.getNetworkCapabilities(active);
        return capabilities != null
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    /**
     * Runs {@code work} now if the panel is already on a network, otherwise once one appears or
     * {@link #MAX_WAIT_MS} elapses, whichever comes first. Always runs it on {@code handler}, so
     * callers never have to think about the ConnectivityManager's own callback thread.
     *
     * @param label what is waiting, for the log line; this is the only place the wait is visible.
     * @return a handle to {@link #cancel()} if the caller stops caring first, never null.
     */
    static NetworkGate whenOnline(Context context, Handler handler, String label, Runnable work) {
        ConnectivityManager connectivity = context.getSystemService(ConnectivityManager.class);
        NetworkGate gate = new NetworkGate(connectivity, handler, work, label);
        if (connectivity == null || hasUsableNetwork(connectivity)) {
            gate.fire(connectivity == null ? "no ConnectivityManager" : "network already up");
            return gate;
        }
        Log.i(TAG, label + " is waiting for a network");
        gate.callback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                gate.fire("network became available");
            }
        };
        try {
            connectivity.registerDefaultNetworkCallback(gate.callback);
        } catch (RuntimeException refused) {
            // Documented to throw if too many callbacks are registered. Nothing here justifies
            // holding the panel dark over it.
            Log.w(TAG, "Could not watch for a network, continuing without waiting", refused);
            gate.callback = null;
            gate.fire("could not watch for a network");
            return gate;
        }
        handler.postDelayed(gate.timeout, MAX_WAIT_MS);
        return gate;
    }

    private final Runnable timeout = () -> fire("gave up waiting after " + MAX_WAIT_MS + "ms");

    private void fire(String reason) {
        // Run inline when the caller is already on the handler's thread, which is the common case:
        // the panel is usually online by the time anything asks, and posting there would delay the
        // dashboard's first load by a message-queue turn for no reason at all.
        onHandlerThread(() -> {
            if (spent) {
                return;
            }
            spent = true;
            release();
            Log.i(TAG, label + " starting: " + reason);
            work.run();
        });
    }

    /** Stops waiting, without running the work. Safe to call more than once. */
    void cancel() {
        onHandlerThread(() -> {
            if (spent) {
                return;
            }
            spent = true;
            release();
        });
    }

    private void onHandlerThread(Runnable action) {
        if (Looper.myLooper() == handler.getLooper()) {
            action.run();
        } else {
            handler.post(action);
        }
    }

    private void release() {
        handler.removeCallbacks(timeout);
        if (callback != null && connectivity != null) {
            try {
                connectivity.unregisterNetworkCallback(callback);
            } catch (IllegalArgumentException alreadyGone) {
                // Already unregistered; nothing to undo.
            }
            callback = null;
        }
    }
}
