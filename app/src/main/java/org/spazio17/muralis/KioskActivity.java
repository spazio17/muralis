/*
 * Copyright 2026 Muralis contributors
 * All rights reserved. See LICENSE at the repository root.
 */
package org.spazio17.muralis;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.StatusBarManager;
import android.app.admin.DevicePolicyManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.UserManager;
import android.provider.Settings;
import android.text.Html;
import android.text.InputType;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.RelativeSizeSpan;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.inputmethod.InputMethodManager;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.util.Log;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

public final class KioskActivity extends Activity {
    private static final String TAG = "MuralisActivity";
    /**
     * How often the recovery clock looks at the dashboard. Two seconds, so a retry goes out within a
     * couple of seconds of becoming due; the work per tick is two long comparisons.
     */
    private static final long SUPERVISOR_INTERVAL_MS = 2_000L;
    /**
     * How often the dashboard's server is probed for reachability while the page is settled.
     *
     * <p>Fifteen seconds: a Home Assistant restart keeps the server down for tens of seconds to a
     * couple of minutes, so an outage that matters spans several probes, and the reload lands
     * within one probe interval plus one backoff of the server coming back. An outage shorter than
     * one interval can slip between two probes, deliberately: nothing that recovers that fast kept
     * the page's websocket down for long enough to strand a card, and the frontend reconnects it
     * by itself. The probe is a HEAD request any web server answers from memory; at this rate it
     * costs nothing worth measuring, even against a remote instance.
     */
    private static final long SERVER_PROBE_INTERVAL_MS = 15_000L;
    /**
     * Connect and read timeout for one probe request. A healthy server on a home network answers a
     * HEAD in milliseconds, and one that takes longer than this to accept or answer is, for the
     * page's purposes, down. Well under the probe interval, so a probe always finishes, one way or
     * the other, before the next one is due.
     */
    private static final int SERVER_PROBE_TIMEOUT_MS = 5_000;
    /**
     * How long the brightness slider waits before writing. A SeekBar reports every pixel of a drag
     * and each report is a settings write, so without this a single swipe writes dozens of times.
     * Short enough that the panel still follows the finger.
     */
    private static final long BRIGHTNESS_APPLY_DELAY_MS = 150L;
    /**
     * How often the frozen-page probe runs: every five minutes, so a dashboard genuinely repainting
     * anything, a chart, a live sensor tile, a clock, has almost certainly done so between two
     * checks, while running rarely enough that it costs nothing worth measuring.
     */
    private static final long FROZEN_PAGE_CHECK_INTERVAL_MS = 5 * 60 * 1_000L;
    private static final long OVERLAY_REFRESH_MS = 1_000L;
    /**
     * How often the configuration screen re-reads its instantly applied controls from storage, so
     * a change made over MQTT or from the web admin is reflected here. Matches the web admin's own
     * poll.
     */
    private static final long LIVE_SETTING_SYNC_INTERVAL_MS = 5_000L;
    private static final int OVERLAY_TEXT_SP = 15;
    private static final int ADMIN_ESCAPE_ZONE_DP = 96;

    /**
     * Returns the loaded page's background luminance, 0 dark to 255 light, or -1 when the page
     * gave no usable colour. Body first, then the document root for pages whose body is
     * transparent; a fully transparent answer counts as no answer. The perceptual weights are
     * the ordinary Rec. 601 ones. See {@link #probePageLuminance}.
     */
    private static final String PAGE_LUMINANCE_PROBE =
            "(function(){function c(e){if(!e)return null;"
            + "var m=getComputedStyle(e).backgroundColor"
            + ".match(/rgba?\\(([\\d.]+)[ ,]+([\\d.]+)[ ,]+([\\d.]+)"
            + "(?:[ ,\\/]+([\\d.]+%?))?\\)/);"
            + "if(!m)return null;"
            + "if(m[4]!==undefined&&parseFloat(m[4])===0)return null;"
            + "return 0.299*m[1]+0.587*m[2]+0.114*m[3];}"
            + "var v=c(document.body);if(v===null)v=c(document.documentElement);"
            + "return v===null?-1:Math.round(v);})()";
    /**
     * Smallest visible-frame reduction treated as a keyboard rather than a system bar or cutout.
     * The software keyboard on this hardware is several hundred dp even in landscape; a navigation
     * bar is around 48.
     */
    private static final int MIN_KEYBOARD_INSET_DP = 120;
    /** Mirrors HttpAdminServer's own floor: below it the surface refuses to bind at all. */
    private static final int MIN_HTTP_ADMIN_PASSWORD_LENGTH = 8;
    /** Grace for the lock-task pin to actually land before the status-bar policy is judged. */
    private static final long LOCK_TASK_SETTLE_MS = 750L;
    /**
     * Lock-task allowlist: this package, and deliberately nothing else.
     *
     * <p>The allowlist is the list of packages Android will let run <em>while lock task is held</em>.
     * Anything on it can be brought to the front over the kiosk and stays there, because lock task is
     * doing what it was told. It used to name the launcher and Settings as "second lines of
     * recovery", and that made Settings exactly such a door: measured 2026-09-03,
     * {@code am start -a android.settings.SETTINGS} put the whole Settings app on screen over a
     * locked panel and Muralis did not take the screen back, while Chrome, the Play Store and the
     * dialer were all refused by the platform. Everything a wall panel must refuse was refused; the
     * two packages this method volunteered were not.
     *
     * <p>Neither entry bought anything, which is why removing them costs nothing. Every sanctioned
     * hand-over, the launcher escape and the two Settings screens Muralis itself offers, calls
     * {@link #releaseForOtherApp()} first, and that <em>ends</em> lock task; an app started after
     * that needs no allowlist entry at all. The entries were only ever reachable by something else
     * starting them, which is the case they had to prevent.
     */
    private String[] lockTaskPackages() {
        return new String[] {getPackageName()};
    }

    /**
     * The package that actually handles HOME, excluding Muralis itself.
     *
     * <p>Once Muralis is the default HOME it wins this resolution, so a match on our own package means
     * "ask the package manager for every HOME handler and take the other one" instead.
     */
    private String systemLauncherPackage() {
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        android.content.pm.ResolveInfo resolved = getPackageManager().resolveActivity(home, 0);
        if (resolved != null && resolved.activityInfo != null
                && !getPackageName().equals(resolved.activityInfo.packageName)) {
            return resolved.activityInfo.packageName;
        }
        for (android.content.pm.ResolveInfo candidate
                : getPackageManager().queryIntentActivities(home, 0)) {
            if (candidate.activityInfo != null
                    && !getPackageName().equals(candidate.activityInfo.packageName)) {
                return candidate.activityInfo.packageName;
            }
        }
        return null;
    }
    private static final String UI_PREFERENCES = "kiosk_ui";
    private static final String LIGHT_CONFIGURATION_THEME = "light_configuration_theme";

    private WebView webView;
    private View blackout;
    private TextView statsOverlay;
    /**
     * The System stats card's readout on the configuration screen. Distinct from
     * {@link #statsOverlay}, which is the block drawn over the dashboard; the two are never on
     * screen at the same time but are fed the same text by the same tick.
     */
    private TextView configStatsView;
    private TextView networkWaitLabel;
    private NetworkGate dashboardGate;
    private TextView brightnessModeNote;
    private int pendingBrightnessPercent = -1;
    /**
     * Every retry/timeout/frozen-page decision, as pure host-tested state; this class only wires
     * it to the WebView, the clock and the handlers. Time is always
     * {@code SystemClock.uptimeMillis()}. See {@link RecoveryPolicy} for the design rules and the
     * bug history that shaped them.
     */
    private final RecoveryPolicy recovery = new RecoveryPolicy();
    private int dashboardRetries;
    /** Turns server probe verdicts into at most one reload per outage. */
    private final ServerProbePolicy serverProbePolicy = new ServerProbePolicy();
    /** Whether a probe request is in flight. Only ever touched on the main thread. */
    private boolean serverProbeInFlight;
    /**
     * Bumped whenever a load is issued or forgotten, so a probe verdict that was in flight across
     * that boundary is discarded rather than recorded against a page it never observed.
     */
    private int serverProbeGeneration;
    // The status readout on the configuration screens: built once, updated on the sampler tick.
    private KioskTheme statusChipTheme;
    private StatusIcon batteryIcon;
    private TextView batteryValue;
    private TextView addressValue;
    private TextView ramValue;
    private TextView cpuValue;
    private boolean kioskStopped;
    private boolean userInterfaceInitialized;
    private boolean unlockReceiverRegistered;
    private boolean configurationVisible;
    /**
     * Set while {@link #liveSettingSyncTask} is writing a control's state back from storage, so
     * the change listener that would normally save it recognises the write as its own echo. Without
     * this, following an external change would immediately re-save it, and a value being changed
     * from two surfaces at once could ping-pong.
     */
    private boolean syncingLiveControls;
    /** Re-reads the live settings so a change from MQTT or the web admin shows up here too. */
    private Runnable liveSettingSyncTask;
    /**
     * The stored connection fields as of when the configuration screen was built, for the Save
     * button's stale-form check. See {@link #connectionBaselineOf}.
     */
    private String connectionBaseline = "";
    /**
     * The Play Billing client, created at startup in {@link #initializeUserInterface} and kept for
     * the activity's life: the client holds a service binding, and rebuilding it on every screen
     * rebuild would churn connections for nothing. Asked at startup rather than when the
     * configuration screen opens, because what it finds is stored by {@link ProEntitlement} and
     * that is what gates MQTT and the web admin, which start with nobody looking at a screen.
     */
    private ProBilling proBilling;
    /**
     * How to draw the screen that is currently up, so a rotation can redraw it. Null on the
     * dashboard, which needs no redraw: a WebView reflows itself, and rebuilding it would reload
     * the page. See {@link #onConfigurationChanged}.
     */
    private Runnable currentScreen;
    /**
     * Set while the Display card says the WRITE_SETTINGS grant is missing, so the grant is shown
     * the moment it is made and the operator is brought back from Settings. See
     * {@link #watchForWriteSettingsGrant()}.
     */
    private android.app.AppOpsManager.OnOpChangedListener writeSettingsWatch;
    /** Between onResume and onPause. The grant watcher only starts this activity when it is not. */
    private boolean inFront;
    /**
     * The scroll offset a redraw is carrying across, or -1 when the next screen is a genuine
     * arrival and belongs at the top. See {@link #redrawInPlace}.
     */
    private int carriedScrollY = -1;
    private final java.util.List<EscapeSequence.Tap> escapeTaps = new java.util.ArrayList<>();
    private boolean recorderVisible;
    private boolean recordingForLauncher;
    /**
     * Whether the recorder was entered from the first-start wizard rather than the settings
     * page. Same screen, different exits: Save advances to the wizard's next step (or finishes
     * it), Back returns to the wizard's intro instead of the sequences page.
     */
    private boolean recordingForWizard;
    /** Whether the first-start wizard's intro screen is up. See {@link #showFirstStartWizard}. */
    private boolean wizardVisible;
    /**
     * Whether the loaded page told {@link #probePageLuminance} its background is light, which is
     * what decides the status bar icon shade on an ordinary install. False until a page answers,
     * which keeps the default light icons this app's own dark screens want.
     */
    private boolean dashboardPageIsLight;
    private final java.util.List<String> recordedZones = new java.util.ArrayList<>();
    private TextView recorderReadout;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    /**
     * The dashboard's recovery clock. Ticks for as long as a dashboard is on screen, and is the only
     * thing that ever reloads it after a failure.
     *
     * <p><b>It is never cancelled by a page callback</b>, which is the whole point. The previous design
     * had every callback posting and cancelling one shared {@code reloadTask}, with two different
     * meanings, a 10-second retry and a 60-second hung-load timeout, riding on the same handle. That
     * could not work, and did not: the WebView reports an HTTP 502 as
     * {@code onReceivedHttpError} then {@code onPageStarted} then {@code onPageFinished}, so
     * {@code onPageStarted} removed the 10-second retry the error had just scheduled and replaced it
     * with the 60-second timeout, every cycle. Measured on the panel 2026-08-19: one 502 logged, zero
     * reloads, and a dashboard that never came back. Two attempts to fix the ordering failed, because
     * the ordering was not the problem: sharing one cancellable handle between two intents was.
     *
     * <p>So the callbacks now only record facts, and this decides what to do about them.
     */
    /** Writes the level the slider has settled on. See the listener for why it is coalesced. */
    private final Runnable brightnessApplyTask = () -> {
        if (pendingBrightnessPercent < 0) {
            return;
        }
        String problem = KioskService.applyBrightness(this, pendingBrightnessPercent);
        if (problem != null) {
            // Said out loud, not only logged. The old reasoning was that the slider is disabled
            // whenever the sensor is in charge, so the only way here is the missing WRITE_SETTINGS
            // grant, which the settings screen already offers. That leaves one real hole: a device
            // with no light sensor has nothing to disable the slider, so without the grant the
            // slider moved, the panel did not, and the only record was a logcat line nobody on a
            // wall-mounted tablet can read (Juri, 2026-09-07).
            Log.w(TAG, "Brightness not applied: " + problem);
            Toast.makeText(this, "Brightness not applied: " + problem, Toast.LENGTH_LONG).show();
        } else {
            // The house rule (CLAUDE.md): anything applied outside the dispatcher republishes,
            // or Home Assistant shows the old value until the next 60-second tick. The slider
            // and the auto switch were the two controls that never did.
            KioskService.publishTelemetrySoon(this);
        }
    };

    /**
     * Keeps the slider in step with who is actually in charge of the backlight.
     *
     * <p>Disabled while automatic brightness is on, because since 2026-08-20 a level written then is
     * refused: the auto-brightness algorithm owns the backlight and the write would move the stored
     * number without moving the panel. Matching the web admin, and for the same reason: a control
     * that moves and is then ignored is worse than one that plainly cannot be moved.
     */
    private void applyBrightnessEnabledState(SeekBar slider, TextView value, KioskTheme theme) {
        boolean sensorInCharge = KioskService.isAutoBrightnessOn(this);
        slider.setEnabled(!sensorInCharge);
        slider.setAlpha(sensorInCharge ? 0.4f : 1f);
        value.setAlpha(sensorInCharge ? 0.4f : 1f);
        int percent = KioskService.currentBrightnessPercent(this);
        if (percent >= 1) {
            slider.setProgress(percent);
        }
        value.setText(Math.max(1, slider.getProgress()) + "%");
        if (brightnessModeNote != null) {
            brightnessModeNote.setText(sensorInCharge
                    ? "The light sensor is setting the brightness. "
                            + "Clear the box above to set it by hand."
                    : "Set by hand. Tick the box above to hand it back to the light sensor.");
            brightnessModeNote.setTextColor(theme.subtext);
        }
    }

    private final Runnable dashboardSupervisor = new Runnable() {
        @Override
        public void run() {
            mainHandler.postDelayed(this, SUPERVISOR_INTERVAL_MS);
            superviseDashboard();
        }
    };

    /**
     * Catches the failure nothing else notices: HTTP succeeded, the renderer is alive, but whatever
     * the page depends on to stay live, a websocket, a poll loop, died, and the page sits on
     * whatever it last rendered forever. No callback fires for this, because nothing at the network
     * or renderer level ever fails.
     *
     * <p>Deliberately generic rather than Home-Assistant-specific: no scanning for the words
     * "disconnected" or "reconnecting", which would only work for pages that happen to say them in
     * English and is exactly the kind of app-specific assumption this project's own self-recovery
     * work avoided elsewhere. Instead it watches whether the page's own rendered content is moving
     * at all, which any live dashboard, a clock, a chart, a sensor tile, does on its own.
     */
    private final Runnable frozenPageCheckTask = new Runnable() {
        @Override
        public void run() {
            mainHandler.postDelayed(this, FROZEN_PAGE_CHECK_INTERVAL_MS);
            checkForFrozenPage();
        }
    };

    private void checkForFrozenPage() {
        if (webView == null || kioskStopped) {
            return;
        }
        // Only while the dashboard is in its settled, successfully-loaded state. A load already in
        // flight or already queued for retry is the existing HTTP/hang recovery path's business, not
        // this one's, and evaluating JavaScript mid-transition would only read a half-built page.
        if (!recovery.settled()) {
            return;
        }
        WebView probed = webView;
        // Text length plus element count: cheap, and together far less likely to stay identical
        // across a genuinely live page than either alone. Not a hash of the full DOM, deliberately;
        // this only has to change when the page changes, not identify exactly how.
        probed.evaluateJavascript(
                "(function(){if(!document.body){return '0|0';}"
                        + "return document.body.innerText.length+'|'"
                        + "+document.getElementsByTagName('*').length;})()",
                result -> onFrozenPageProbeResult(probed, result));
    }

    private void onFrozenPageProbeResult(WebView probed, String result) {
        if (webView != probed || result == null) {
            // The dashboard was replaced or torn down while the probe was in flight.
            return;
        }
        String fingerprint = unquoteJavascriptResult(result);
        if (recovery.recordFingerprint(fingerprint)) {
            long minutes = FROZEN_PAGE_CHECK_INTERVAL_MS
                    * RecoveryPolicy.FROZEN_PAGE_STALE_CHECKS_TO_TRIGGER / 60_000L;
            Log.w(TAG, "Dashboard content has not changed in " + minutes
                    + "m; treating it as frozen and reloading");
            recordLoadFailure("page appears frozen");
        }
    }

    /** {@code evaluateJavascript}'s callback receives a JSON-quoted string; undo that quoting. */
    private static String unquoteJavascriptResult(String result) {
        if (result.length() >= 2 && result.startsWith("\"") && result.endsWith("\"")) {
            return result.substring(1, result.length() - 1);
        }
        return result;
    }

    /**
     * Catches the other failure nothing else notices: the page loaded fine, and then the server
     * restarted underneath it. No main-frame request happens, so the error callbacks stay silent;
     * no load is in flight, so the hung-load timeout is idle; and after a Home Assistant restart
     * the frontend reconnects its own websocket and most cards recover, so the frozen-page probe
     * correctly sees a live page. What is left is the odd card stuck on an error tile, observed on
     * the panel after an ordinary Home Assistant restart, and until this existed nothing ever
     * reloaded it.
     *
     * <p>The probe only observes; the decision is {@link ServerProbePolicy}'s, and the reload is
     * the supervisor's, armed through {@link #recordLoadFailure} so it inherits the backoff, the
     * network gate and the single-load-in-flight guarantee. The backoff is also useful in itself
     * here: a server that has only just started answering again gets ten more seconds to warm up
     * before being asked for the whole dashboard.
     */
    private final Runnable serverProbeTask = new Runnable() {
        @Override
        public void run() {
            mainHandler.postDelayed(this, SERVER_PROBE_INTERVAL_MS);
            probeDashboardServer();
        }
    };

    private void probeDashboardServer() {
        if (webView == null || kioskStopped || serverProbeInFlight) {
            return;
        }
        // Only while the dashboard is settled. A load in flight or a pending retry already belongs
        // to the load-failure path, and its own outcome says everything a probe could.
        if (!recovery.settled()) {
            return;
        }
        String url = KioskConfig.load(this).dashboardUrl;
        if (url.isEmpty()) {
            return;
        }
        int generation = serverProbeGeneration;
        serverProbeInFlight = true;
        // A thread per probe rather than an executor kept warm for one request every fifteen
        // seconds; the in-flight guard means there is never more than one.
        new Thread(() -> {
            boolean serverUp = probeServerOnce(url);
            mainHandler.post(() -> onServerProbeResult(generation, serverUp));
        }, "MuralisServerProbe").start();
    }

    /**
     * One HEAD request to the dashboard URL, off the main thread. Any answered status counts as
     * up, including 401/404/405, because an answer proves the server is there; see
     * {@link ServerProbePolicy#statusMeansServerUp}. Every exception counts as down: refused,
     * timed out, unresolved and no-network-at-all look the same from the page's point of view,
     * and treating a dead router like a dead server is right here, because a router reboot kills
     * the page's websocket exactly the way a server restart does.
     */
    private static boolean probeServerOnce(String url) {
        java.net.HttpURLConnection connection = null;
        try {
            connection = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            connection.setConnectTimeout(SERVER_PROBE_TIMEOUT_MS);
            connection.setReadTimeout(SERVER_PROBE_TIMEOUT_MS);
            connection.setRequestMethod("HEAD");
            return ServerProbePolicy.statusMeansServerUp(connection.getResponseCode());
        } catch (Exception e) {
            return false;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private void onServerProbeResult(int generation, boolean serverUp) {
        serverProbeInFlight = false;
        if (webView == null || kioskStopped) {
            return;
        }
        // The page this verdict describes is gone: a load was issued, or another detector armed a
        // retry, while the request was in flight. Recording it anyway could make the new page's
        // first healthy probe read as a recovery and reload a page that was never down.
        if (generation != serverProbeGeneration || !recovery.settled()) {
            return;
        }
        if (serverProbePolicy.recordResult(serverUp)) {
            Log.w(TAG, "Dashboard server is back after an outage; reloading");
            recordLoadFailure("server restored after an outage");
        }
    }

    private void resetServerProbeTracking() {
        serverProbePolicy.reset();
        serverProbeGeneration++;
    }

    /**
     * Repaints the stats block from the sampler's last published reading. Deliberately does no I/O
     * itself: reading procfs on the UI thread of the one process that must never stutter would be a
     * poor trade for a diagnostic readout.
     */
    private final Runnable overlayTask = new Runnable() {
        @Override
        public void run() {
            boolean anythingToRepaint = false;
            if (statsOverlay != null) {
                boolean enabled = KioskConfig.statsOverlayEnabled(KioskActivity.this);
                statsOverlay.setVisibility(enabled ? View.VISIBLE : View.GONE);
                if (enabled) {
                    statsOverlay.setText(renderOverlay());
                }
                anythingToRepaint = true;
            }
            // Dropped as soon as the screen holding it has gone, rather than waiting for something
            // to remember to clear it: About and the legal pages replace the content view without
            // touching this field, and repainting a detached view tree once a second is a leak
            // that keeps the whole configuration screen alive behind the dashboard.
            if (configStatsView != null && !configStatsView.isAttachedToWindow()) {
                configStatsView = null;
            }
            if (configStatsView != null) {
                configStatsView.setText(renderOverlay());
                anythingToRepaint = true;
            }
            // The configuration screen's chip used to be a snapshot taken when the screen was built,
            // so pulling the charger out changed nothing while somebody stood there watching it.
            if (updateStatusChip()) {
                anythingToRepaint = true;
            }
            if (anythingToRepaint) {
                mainHandler.postDelayed(this, OVERLAY_REFRESH_MS);
            }
        }
    };

    private final BroadcastReceiver controlReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            handleUiCommand(
                    intent.getStringExtra(KioskActions.EXTRA_COMMAND),
                    intent.getIntExtra(KioskActions.EXTRA_BRIGHTNESS, -1),
                    intent.getStringExtra(KioskActions.EXTRA_URL));
        }
    };

    private final BroadcastReceiver unlockReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_USER_UNLOCKED.equals(intent.getAction())) {
                initializeUserInterface();
                unregisterReceiver(this);
                unlockReceiverRegistered = false;
            }
        }
    };

    // UnspecifiedRegisterReceiverFlag suppressed with cause: the flagged registerReceiver call is
    // the else-branch of an explicit SDK_INT >= TIRAMISU check, taken only where the flag constant
    // does not exist. Lint does not follow the branch. The exported/not-exported question is
    // answered on every level that can express it.
    @Override
    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag")
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        // First, before anything here can fail: the service's supervisor relaunches the dashboard
        // when no instance exists, and must not do so over one that is halfway through onCreate.
        KioskRuntimeState.publishDashboardAlive(true);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // A wall kiosk has nothing to protect behind a swipe-to-unlock screen, and after a reboot
        // the dashboard would otherwise sit invisible behind the keyguard until somebody walked up
        // to the tablet. The product also disables the lockscreen by default; this covers the case
        // where one has been re-enabled on the device.
        showWhenLocked();
        watchForRevealedSystemBars();
        keepDisplayAwake();
        // hideStatusBarForKiosk() is gone: it wrote Settings.Global "policy_control" =
        // "immersive.full=<pkg>", which needs WRITE_SECURE_SETTINGS. That is a signature permission
        // with no public or device-owner equivalent, so this build genuinely cannot stop SystemUI
        // *drawing* a transient bar on an edge swipe. What it can do, and does: lock task removes
        // Home and Overview, setStatusBarDisabled removes the shade, and onBackPressed is inert, so
        // a revealed bar has nothing working on it. Known, measured regression, not an oversight.
        enterImmersiveMode();
        applyOrientation();
        requestNotificationPermissionIfNeeded();
        keepBackInert();
        // KioskService sends this package-scoped, but package-scoping constrains the *sender*, not
        // who may reach the receiver. A context-registered receiver was implicitly EXPORTED before
        // Android 14, and RECEIVER_NOT_EXPORTED is only API 33, so on the API 26 hardware this app
        // targets, the unflagged branch left any installed app able to broadcast UI_CONTROL and
        // repoint, blank or dim the kiosk. Both branches therefore also require a signature-level
        // permission, which works on every version; the flag stays because from API 34 Android
        // demands the export intent be stated explicitly and throws if neither flag is passed.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(controlReceiver, new IntentFilter(KioskActions.UI_CONTROL),
                    KioskActions.PERMISSION_UI_CONTROL, null, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(controlReceiver, new IntentFilter(KioskActions.UI_CONTROL),
                    KioskActions.PERMISSION_UI_CONTROL, null);
        }
        KioskService.start(this);

        UserManager users = getSystemService(UserManager.class);
        if (users != null && !users.isUserUnlocked()) {
            View waiting = new View(this);
            waiting.setBackgroundColor(Color.BLACK);
            setContentView(waiting);
            registerReceiver(unlockReceiver, new IntentFilter(Intent.ACTION_USER_UNLOCKED));
            unlockReceiverRegistered = true;
            return;
        }
        initializeUserInterface();
    }

    private void initializeUserInterface() {
        if (userInterfaceInitialized) {
            return;
        }
        userInterfaceInitialized = true;
        // No setup-wizard handoff here, deliberately. The privileged build checked
        // DEVICE_PROVISIONED plus USER_SETUP_COMPLETE and launched Lineage's SetupWizard, because on
        // a freshly wiped tablet HOME could resolve to Muralis before the wizard was available. None
        // of that transfers: there is no LineageOS SetupWizard on stock Android to hand off to, and
        // Settings.Secure.USER_SETUP_COMPLETE is not public API. More importantly the situation
        // cannot arise, because an app-build Muralis only becomes HOME once it is already installed
        // and provisioned, which is necessarily after setup has finished.
        KioskConfig config = KioskConfig.load(this);
        // The wizard outranks everything, Google included: without both escape combinations the
        // kiosk has no way out, so neither the dashboard nor the ordinary configuration screen is
        // safe to show, and nothing that can put a window in front of it may run first.
        if (!config.escapeSequencesConfigured()) {
            showFirstStartWizard();
            return;
        }
        startProBilling();
        if (config.dashboardUrl.isEmpty()) {
            showConfiguration(config);
        } else {
            showDashboard(config.dashboardUrl);
        }
    }

    /**
     * Asks Play what this Google account owns, once the panel is a panel.
     *
     * <p>Asked once per process start, which on this app means at least nightly: the pass at
     * QUIET_HOUR ends in System.exit and the relaunch alarm brings this activity back, so "when the
     * app is launched", which is where Google's guide puts this, is a recurring event here rather
     * than a once-per-boot one. Deliberately after the user-unlock gate in
     * {@link #initializeUserInterface}, because Play cannot answer for a locked user.
     *
     * <p>Not deferred to the configuration screen: the entitlement gates MQTT and the web admin,
     * and both start at boot without anybody opening a screen, so an answer that only arrives when
     * somebody taps their way into settings arrives too late to gate anything. It also means a
     * purchase made on another device is picked up by the next nightly restart on its own. Juri,
     * 2026-08-27.
     *
     * <p><b>Never before the first-start wizard has recorded both escape combinations.</b> Muralis
     * is free with or without a Google account, so a panel nobody has set up yet has no business
     * asking Google anything, and somebody who cannot leave Muralis yet must not be shown a Google
     * screen on the way in. This is ordering only, not a new capability: the wizard finishes,
     * {@link #continueAfterFirstStartWizard} starts this, and every later launch takes the path
     * above. Juri, 2026-09-03.
     */
    private void startProBilling() {
        if (proBilling == null) {
            proBilling = new ProBilling(this);
        }
        proBilling.refresh();
    }

    /**
     * A wall kiosk should never blank, in two layers because one is not enough.
     *
     * <p>{@code FLAG_KEEP_SCREEN_ON}, already set in {@link #onCreate}, only holds while this
     * activity is in front, so the panel still sleeps whenever anything else takes the foreground.
     * The privileged build covered that gap by writing {@code Settings.System.SCREEN_OFF_TIMEOUT}
     * to {@code Integer.MAX_VALUE}; that needs {@code WRITE_SETTINGS}, which is user-grantable but
     * only through an {@code ACTION_MANAGE_WRITE_SETTINGS} trip to Settings that a wall-mounted
     * panel's owner should not have to make.
     *
     * <p>The replacement is device-owner {@code STAY_ON_WHILE_PLUGGED_IN}, applied once in
     * {@link KioskService#applyResourceGuarantees()}, which needs no user grant and is strictly
     * better for a permanently-powered panel. This method therefore only reports which layers are
     * actually in force, so a blanking panel can be diagnosed from the log rather than guessed at.
     */
    private void keepDisplayAwake() {
        DevicePolicyManager policy = getSystemService(DevicePolicyManager.class);
        boolean deviceOwner = policy != null && policy.isDeviceOwnerApp(getPackageName());
        Log.i(TAG, "Display stays awake via FLAG_KEEP_SCREEN_ON"
                + (deviceOwner ? " plus device-owner stay-on-while-plugged-in"
                        : "; NOT device owner, so the panel will sleep whenever Muralis is not in "
                                + "front"));
    }

    @Override
    protected void onResume() {
        super.onResume();
        inFront = true;
        // Every Muralis screen is fullscreen, including configuration: a kiosk should never show a
        // system bar, and the settings screen used to keep the navigation bar for the keyboard's
        // dismiss key, which also handed anyone standing at the panel a Back button.
        enterImmersiveMode();
        // Lock task is held on every Muralis screen, so re-apply it here too: coming back from
        // another app (Settings, the launcher) otherwise leaves the policy released.
        applyKioskPolicy();
        if (webView != null) {
            webView.onResume();
        }
        // Google's guide asks for a purchases re-query in onResume, and on this app the case that
        // makes it matter is concrete: closing the Play purchase sheet resumes this activity, so
        // this is what confirms a purchase the moment the sheet closes, including one that
        // completed while onPurchasesUpdated was not delivered. At startup this runs right after
        // onCreate while the first connection is still being made, and the connecting guard in
        // refresh() deduplicates that for free. Null while a direct-boot start waits for unlock.
        if (proBilling != null) {
            proBilling.refresh();
        }
        // Back from the Settings screen that "Grant it now" opened, whether the watcher brought
        // the activity back or the operator did: the card was drawn with the grant missing and the
        // grant is there now, so redraw before the red line is read again. Here rather than in the
        // watcher's callback because this screen re-applies lock task as it is built, and that is
        // only allowed once this task is in the foreground.
        if (writeSettingsWatch != null && configurationVisible && currentScreen != null
                && KioskService.canWriteSystemSettings(this)) {
            stopWatchingWriteSettings();
            redrawInPlace(currentScreen);
        }
    }

    @Override
    protected void onPause() {
        inFront = false;
        // Belt and braces for the same invariant: a bar disabled while nothing is pinned is a
        // tablet nobody can use.
        disableStatusBarIfPinned();
        // Deliberately do not call WebView.onPause(): the kiosk contract keeps
        // JavaScript and its Home Assistant WebSocket alive while visually off.
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        KioskRuntimeState.publishDashboardAlive(false);
        mainHandler.removeCallbacksAndMessages(null);
        // No screen means no operator screen; without this, exiting to the launcher from the
        // configuration screen would leave the pressure rebuild deferred until the app returns.
        KioskRuntimeState.publishOperatorOnScreen(false);
        releaseKioskPolicy();
        stopWatchingWriteSettings();
        unregisterReceiver(controlReceiver);
        if (unlockReceiverRegistered) {
            unregisterReceiver(unlockReceiver);
            unlockReceiverRegistered = false;
        }
        if (proBilling != null) {
            proBilling.release();
            proBilling = null;
        }
        destroyWebView();
        super.onDestroy();
    }

    /**
     * Deliberately does nothing.
     *
     * <p>Android offers no way to remove the Back button: lock task has feature flags for Home,
     * Overview, notifications and the rest, but Back is always available, so on this hardware an
     * edge swipe can still surface a navigation bar with a working Back. Making it inert is
     * therefore the only way to stop it being a way out of the kiosk, and it needs no permission,
     * so it holds in an app-only build too.
     *
     * <p>It used to call {@code webView.goBack()}, which let anyone standing at the panel walk the
     * dashboard's history backwards. Every Muralis screen that needs to go back has an explicit
     * button for it ("Back to configuration", "Cancel"), so nothing is unreachable.
     */
    // GestureBackNavigation suppressed with cause: lint's advice is to migrate to AndroidX's
    // OnBackPressedDispatcher, which this project cannot use (android.useAndroidX=false, and adding
    // AndroidX for one callback is not worth it on a 2 GB panel). The platform equivalent is already
    // registered in keepBackInert() for API 33+, so both halves of the supported range are covered:
    // this override below 33, OnBackInvokedDispatcher at 33 and above. Removing either reopens Back.
    @Override
    @SuppressWarnings("deprecation")
    @android.annotation.SuppressLint("GestureBackNavigation")
    public void onBackPressed() {
        // Inert only in a kiosk. Below API 33 this is the only hook, so the decision lives here and
        // in the OnBackInvokedCallback both.
        if (isDeviceOwner()) {
            return;
        }
        if (!navigateBack()) {
            super.onBackPressed();
        }
    }

    /**
     * Moves one screen back, for an ordinary install where Back is a working button.
     *
     * @return true when this consumed the gesture, false on the dashboard, where there is nowhere
     *     left to go and the caller should let the system finish the activity, which is what leaving
     *     any other app looks like.
     */
    /**
     * Tells the service whether an operator screen is up, so the pressure rebuild can wait
     * instead of destroying a half-edited form (see {@code RecyclePolicy.decide}). Called after
     * every mutation of {@link #configurationVisible}/{@link #recorderVisible}/
     * {@link #wizardVisible}; not derived inside {@code applyKioskPolicy()} because some screens
     * set the flags after calling it.
     */
    private void publishOperatorScreenState() {
        KioskRuntimeState.publishOperatorOnScreen(
                configurationVisible || recorderVisible || wizardVisible);
        // Rides along here because this is already the choke point every screen transition
        // passes through: the app's own screens are dark and want light icons, the dashboard
        // wants whatever probePageLuminance last measured.
        applyBarIconContrast();
    }

    /**
     * The tablet half of the settings pre-check (see {@link SettingProbe}): probes off the main
     * thread and answers by tinting the field, green for "would work right now", red for "would
     * not". Advisory exactly like the web page's version (admin_check.js): the Save path keeps
     * its own refusals. Callers capture every value they need on the UI thread before building
     * the supplier; the supplier runs on a worker.
     */
    private void runPrecheck(EditText field, KioskTheme theme,
            java.util.function.Supplier<SettingProbe.Verdict> probe) {
        // Tag every probe with the field's current generation and drop a verdict that is no
        // longer about what the box says. Without it, a slow probe on a dead host (the full 3s)
        // could land after a fast probe on the corrected value and repaint a good field red.
        int generation = precheckGeneration.merge(field, 1, Integer::sum);
        new Thread(() -> {
            SettingProbe.Verdict verdict = probe.get();
            mainHandler.post(() -> {
                Integer current = precheckGeneration.get(field);
                if (field.isAttachedToWindow() && current != null && current == generation) {
                    // The verdict is the border alone, not a tinted fill: same fill and radius as
                    // themedInput, one stroke width up so the colour reads at arm's length.
                    field.setBackground(theme.outlinedPanel(theme.surfaceAlt, dp(10), dp(2),
                            verdict.ok ? theme.ok : theme.bad));
                }
            });
        }, "MuralisPrecheck").start();
    }

    /** Cleared with the screen; identity-keyed because the views are rebuilt, not reused. */
    private final java.util.Map<EditText, Integer> precheckGeneration =
            new java.util.IdentityHashMap<>();

    /**
     * Cancels any verdict still in flight for {@code field} and restores its resting border. Also
     * the "no verdict" state: an empty box, or a check that could not run, must not keep an old
     * colour, because a stale verdict is worse than none.
     */
    private void clearPrecheck(EditText field, KioskTheme theme) {
        precheckGeneration.merge(field, 1, Integer::sum);
        field.setBackground(theme.outlinedPanel(theme.surfaceAlt, dp(10), dp(1)));
    }

    /**
     * The web admin controls, from live state rather than a build-time snapshot: they follow the
     * flag and the socket while the screen sits open (liveSettingSyncTask) and refresh right
     * after the local toggle. Both together on purpose, the button's label is state exactly like
     * the line under it, and only the line syncing made a toggle from Home Assistant flip the
     * text while the button kept offering the wrong direction. Green with the address while
     * listening; grey "disabled" while the operator has the surface off; a warning only when it
     * should be up and is not.
     */
    private void refreshWebAdminControls(Button toggle, TextView httpState, KioskTheme theme) {
        boolean enabled = KioskConfig.webAdminEnabled(this);
        toggle.setText(enabled ? "Turn web admin off" : "Turn web admin on");
        if (KioskRuntimeState.httpAdminListening()) {
            SystemStats.RuntimeFacts httpFacts = KioskRuntimeState.lastFacts();
            String address = httpFacts == null || httpFacts.ipAddress.isEmpty()
                    ? "this-tablet" : httpFacts.ipAddress;
            httpState.setTextColor(theme.ok);
            httpState.setText("Listening at http://" + address + ":"
                    + KioskRuntimeState.httpAdminPort());
        } else if (!enabled) {
            httpState.setTextColor(theme.subtext);
            httpState.setText("Web admin disabled");
        } else if ("port unavailable".equals(KioskRuntimeState.httpAdminDownReason())) {
            // Named rather than guessed: this line used to blame the password for every silence,
            // including a port another service had taken, which is the one failure this screen
            // exists to explain.
            httpState.setTextColor(theme.warn);
            httpState.setText("Not listening, port " + KioskRuntimeState.httpAdminPort()
                    + " is unavailable");
        } else {
            httpState.setTextColor(theme.warn);
            httpState.setText("Not listening, set a password of at least "
                    + MIN_HTTP_ADMIN_PASSWORD_LENGTH + " characters");
        }
    }

    private boolean navigateBack() {
        if (recorderVisible) {
            // Same destination the recorder's own Cancel/Back button uses, rather than a second
            // opinion about where the recorder goes back to.
            recorderVisible = false;
            if (recordingForWizard) {
                showFirstStartWizard();
            } else {
                showEscapeSequences(KioskConfig.load(this));
            }
            return true;
        }
        if (wizardVisible) {
            // Until the combinations exist the wizard IS this app's home state; there is nowhere
            // back to go, and leaving would reveal an unlockable kiosk with no way to return.
            return true;
        }
        if (configurationVisible) {
            String url = KioskConfig.load(this).dashboardUrl;
            // Mirrors onCreate's routing. With nothing configured the configuration screen *is* this
            // app's home state, so Back leaves the app rather than revealing the empty dashboard
            // behind it. Observed on the API 28 phone before this guard: a fresh install, one press
            // of Back, and the screen was black apart from the stats overlay, with no visible route
            // back. The corner-tap escape still worked, but nothing on screen said so, and that is
            // the first thing a closed-test tester would have met.
            if (url.isEmpty()) {
                return false;
            }
            showDashboard(url);
            return true;
        }
        // Deliberately NOT webView.goBack(). That was removed on purpose: it let anyone standing at
        // the panel walk the dashboard's history backwards, and on a Home Assistant frontend it
        // strands the viewer on some earlier view with no obvious way forward.
        return false;
    }

    /**
     * Keeps Back inert on API 33+, where {@link #onBackPressed} alone no longer does it.
     *
     * <p><b>This is a real regression the privileged build never had to handle</b>, and it matters
     * because an inert Back is the *only* thing this build has to stop a revealed navigation bar
     * being a way out of the kiosk: {@code policy_control} is gone with WRITE_SECURE_SETTINGS, and
     * Android has no lock-task feature flag for Back. The ROM targeted API 29, where overriding
     * onBackPressed was sufficient.
     *
     * <p>From API 33, with predictive back, the framework routes the gesture through
     * {@link android.window.OnBackInvokedDispatcher} and stops calling {@code onBackPressed} for it,
     * so the override silently becomes dead code and Back starts working again. Registering a
     * do-nothing callback at {@code PRIORITY_OVERLAY} re-establishes the behaviour, and consumes the
     * gesture before anything else can act on it.
     */
    private void keepBackInert() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
            return;
        }
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_OVERLAY,
                () -> {
                    // Swallowed in a kiosk: every Muralis screen that needs to go back has an
                    // explicit button, so nothing becomes unreachable.
                    if (isDeviceOwner()) {
                        return;
                    }
                    // Otherwise Back works. A registered callback consumes the gesture whatever it
                    // does, so finishing has to be explicit here; there is no falling through to the
                    // system default the way onBackPressed can call super.
                    if (!navigateBack()) {
                        finish();
                    }
                });
    }

    /**
     * Shows over the keyguard, on every supported API level.
     *
     * <p>{@code Activity.setShowWhenLocked} is <b>API 27</b>, one level above this app's minSdk,
     * and the interim MediaPad is API 26, so calling it unconditionally threw
     * {@code NoSuchMethodError} on the first frame. Verified on that hardware. The window flags it
     * replaced are deprecated from API 27 but present from API 1, so each level uses the mechanism
     * it actually has.
     *
     * <p>This window does <b>not</b> carry {@code FLAG_TURN_SCREEN_ON}/{@code setTurnScreenOn}. It
     * did, as the unprivileged stand-in for {@code PowerManager.wakeUp}, and the price was that
     * every relaunch of this activity switched the panel on: the nightly restart's relaunch alarm
     * was waking screens somebody had deliberately turned off with the power button (verified by
     * replaying the relaunch over adb on the MediaPad, 2026-08-24). Turning the screen on is a
     * command, not a property of this window; it lives in {@code KioskService.wakeDisplay()}.
     */
    @SuppressWarnings("deprecation")
    private void showWhenLocked() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        }
    }

    /**
     * Redraws whichever screen is up when the panel is turned.
     *
     * <p>The manifest lists {@code orientation|screenSize|screenLayout|smallestScreenSize} in
     * {@code configChanges}, so Android does not recreate this activity, and nothing here used to
     * react to the change either. Every layout decision that reads {@code screenWidthDp} was
     * therefore frozen at whatever the width had been when the screen was built: {@link #cardGrid}
     * kept two lanes in portrait, or one lane in landscape with every card and every field stretched
     * the full width, and {@link #actionRow} kept the wrong axis. The MQTT interval buttons are the
     * most visible casualty, since they share a row at weight 1 and simply elongate. Leaving the
     * screen for the dashboard and coming back fixed it, because that rebuilt the view tree, which
     * is precisely what this does without making the operator do it.
     *
     * <p>Not {@code recreate()}: this activity is the HOME activity and is holding lock task, and
     * tearing it down to rebuild a form is a much bigger hammer than the problem needs.
     */
    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        Runnable redraw = currentScreen;
        if (redraw == null) {
            return;
        }
        // Posted rather than run inline: getConfiguration() is updated before this callback, but
        // the window has not been resized yet, and a view tree built against the old window size
        // measures against it once and reads as stretched all over again.
        mainHandler.post(() -> {
            if (currentScreen == redraw) {
                redrawInPlace(redraw);
            }
        });
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            // Screen coordinates, because cornerZoneAt compares against the content view's position
            // on screen. getX/getY are window-relative and were part of the offset that made the
            // recorder's targets miss on a device with visible system bars.
            String zone = cornerZoneAt(event.getRawX(), event.getRawY());
            if (recorderVisible) {
                if (zone != null) {
                    recordZone(zone);
                }
                // Passed on rather than consumed: the recorder's own Save, Start over and Cancel
                // buttons are ordinary views inside this screen and have to keep receiving touches.
                // The corner targets are plain labels with no click listener, so letting the event
                // through costs nothing.
                return super.dispatchTouchEvent(event);
            }
            // Deliberately not gated on configurationVisible: an escape hatch that stops working
            // the moment you are inside settings is not an escape hatch. This is exactly the
            // situation an operator stuck on the configuration screen needs it for.
            if (zone != null && handleEscapeTap(zone, event.getEventTime())) {
                return true;
            }
            if (configurationVisible) {
                dismissKeyboardIfTapOutsideInput(event);
            }
        }
        return super.dispatchTouchEvent(event);
    }

    /**
     * Closes the soft keyboard when a tap lands outside the focused text field.
     *
     * <p>Needed because the configuration screen is now fullscreen. It used to keep the navigation
     * bar precisely so the stock keyboard's dismiss key was reachable; with the bar gone, a tap on
     * the page is the way out, and without this there was no way to close the keyboard at all.
     */
    private void dismissKeyboardIfTapOutsideInput(MotionEvent event) {
        View focused = getCurrentFocus();
        if (!(focused instanceof EditText)) {
            return;
        }
        int[] origin = new int[2];
        focused.getLocationOnScreen(origin);
        float x = event.getRawX();
        float y = event.getRawY();
        boolean insideField = x >= origin[0] && x <= origin[0] + focused.getWidth()
                && y >= origin[1] && y <= origin[1] + focused.getHeight();
        if (insideField) {
            return;
        }
        focused.clearFocus();
        InputMethodManager keyboard = getSystemService(InputMethodManager.class);
        if (keyboard != null) {
            keyboard.hideSoftInputFromWindow(focused.getWindowToken(), 0);
        }
    }

    /**
     * Which corner a touch landed in, or null for anywhere else on the screen.
     *
     * <p>Measured against the <b>content view</b>, in screen coordinates, and that is the whole
     * point. It used to measure against the decor view, which spans the window including the system
     * bars, while the recorder draws its four corner targets inside the content view, which is inset
     * below the status bar and above the navigation bar. On a device-owner install the two are the
     * same rectangle, because every screen is fullscreen, so nothing looked wrong. Where the bars are
     * visible they differ by exactly the height of those bars, and the targets end up offset from the
     * region that listens for them.
     *
     * <p>Measured on the API 28 phone 2026-08-23: the bottom-left target was drawn from y=1839 to
     * y=2120 with its label at y=1982, while the listening region started at y=1992
     * (2280 minus 96dp). A tap on the label recorded nothing; the same tap 120px lower recorded. So
     * the half of the button a person actually aims at was dead, which is why recording a
     * combination worked on the tablet and almost never worked on the phone.
     *
     * <p>Anchoring to the content view keeps the two in step on both: fullscreen or not, the corner
     * that listens is the corner that is drawn.
     */
    private String cornerZoneAt(float x, float y) {
        View content = findViewById(android.R.id.content);
        if (content == null || content.getWidth() == 0 || content.getHeight() == 0) {
            return null;
        }
        int[] origin = new int[2];
        content.getLocationOnScreen(origin);
        x -= origin[0];
        y -= origin[1];
        if (x < 0 || y < 0 || x > content.getWidth() || y > content.getHeight()) {
            return null;
        }
        int zone = dp(ADMIN_ESCAPE_ZONE_DP);
        // Shifted by however far the system's tap-eating chrome intrudes into the content view,
        // so the listening band starts where a tap can actually land. Same measurement the drawn
        // targets are offset by in offsetCornerTargets; see systemBarOverlap for the why.
        Rect overlap = systemBarOverlap();
        boolean left = x <= overlap.left + zone;
        boolean right = x >= content.getWidth() - overlap.right - zone;
        boolean top = y <= overlap.top + zone;
        boolean bottom = y >= content.getHeight() - overlap.bottom - zone;
        if (left && top) {
            return EscapeSequence.TOP_LEFT;
        }
        if (right && top) {
            return EscapeSequence.TOP_RIGHT;
        }
        if (left && bottom) {
            return EscapeSequence.BOTTOM_LEFT;
        }
        if (right && bottom) {
            return EscapeSequence.BOTTOM_RIGHT;
        }
        return null;
    }

    /** Returns true when the tap completed a configured sequence and has been acted on. */
    private boolean handleEscapeTap(String zone, long eventTimeMs) {
        List<EscapeSequence.Tap> trimmed = EscapeSequence.trim(
                escapeTaps, eventTimeMs, EscapeSequence.MAX_GAP_MS, EscapeSequence.MAX_LENGTH);
        escapeTaps.clear();
        escapeTaps.addAll(trimmed);
        escapeTaps.add(new EscapeSequence.Tap(zone, eventTimeMs));

        KioskConfig config = KioskConfig.load(this);
        List<String> toLauncher = EscapeSequence.parse(config.launcherSequence);
        List<String> toSettings = EscapeSequence.parse(config.settingsSequence);

        // Longer first: if one sequence is a suffix of the other, the more specific one wins.
        boolean launcherLonger = toLauncher.size() >= toSettings.size();
        for (int pass = 0; pass < 2; pass++) {
            boolean checkLauncher = (pass == 0) == launcherLonger;
            List<String> sequence = checkLauncher ? toLauncher : toSettings;
            if (EscapeSequence.matchesTail(escapeTaps, sequence, EscapeSequence.MAX_GAP_MS)) {
                escapeTaps.clear();
                if (checkLauncher) {
                    openSystemLauncher();
                } else {
                    showConfiguration(KioskConfig.load(this));
                    Toast.makeText(this, R.string.configuration_escape_opened,
                            Toast.LENGTH_SHORT).show();
                }
                return true;
            }
        }
        return false;
    }

    private void openSystemLauncher() {
        // Lock-task mode would otherwise refuse the launch outright, and the launcher would be
        // unusable without its navigation bar or status bar.
        releaseForOtherApp();
        // Resolved, not hardcoded: the ROM's com.android.launcher3/.lineage.LineageLauncher does
        // not exist on stock Android, and naming a missing component here would make the escape
        // hatch fail exactly when someone needs it to work.
        String launcherPackage = systemLauncherPackage();
        if (launcherPackage == null) {
            // Muralis is the only HOME handler on the device. Releasing lock task is still the right
            // thing, so the configuration screen and Settings remain reachable, but there is no
            // other launcher to hand the screen to.
            Toast.makeText(this, R.string.admin_escape_unavailable, Toast.LENGTH_LONG).show();
            return;
        }
        Intent launcher = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .setPackage(launcherPackage)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(launcher);
            Toast.makeText(this, R.string.admin_escape_opened, Toast.LENGTH_SHORT).show();
        } catch (ActivityNotFoundException unavailable) {
            Toast.makeText(this, R.string.admin_escape_unavailable, Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Sends the operator to the one Settings screen that can grant {@code WRITE_SETTINGS}.
     *
     * <p>Reached from the automatic-brightness checkbox, and from the button the card shows while
     * the grant is missing: the slider needs the same permission, and on a device with no light
     * sensor the checkbox is not there to offer it. Lock task is released first: it would
     * otherwise refuse the launch outright, which is the same trap {@link #openSystemLauncher()}
     * documents. The log line names the adb route for a panel being provisioned over a cable,
     * which is the only route on an OEM build that hides this Settings screen.
     */
    private void offerWriteSettingsGrant() {
        Toast.makeText(this, R.string.brightness_needs_permission, Toast.LENGTH_LONG).show();
        releaseForOtherApp();
        try {
            startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS)
                    .setData(android.net.Uri.parse("package:" + getPackageName()))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException unavailable) {
            // Some OEM builds hide this screen. adb is then the only route, so name it in the log.
            Log.w(TAG, "No WRITE_SETTINGS grant screen; use `adb shell appops set "
                    + getPackageName() + " WRITE_SETTINGS allow`", unavailable);
        }
    }

    /**
     * Shows the {@code WRITE_SETTINGS} grant the moment it is made, and brings the operator back.
     *
     * <p>Registered while the Display card carries its red line, and idle otherwise. Without it,
     * the card was a snapshot: granted in Settings, back in Muralis, and the line and the "Grant it
     * now" button were still there until something else rebuilt the screen (found on the phone,
     * 2026-09-07). Worse on the panel itself, where Settings is a screen with no navigation bar
     * and "come back" is not a thing the operator can be expected to know how to do; the setup
     * page promises that Muralis comes back on its own when they are done, and this is what keeps
     * that promise. {@link android.app.AppOpsManager#startWatchingMode} reports changes to this
     * package's own op with no permission on every Android version this app runs on; the callback
     * arrives on a binder thread and is posted to the main one.
     *
     * <p>When the op flips to allowed: if this screen is in front, the configuration screen is
     * redrawn in place, half-typed boxes and scroll position kept, so the line is gone before it is
     * read again. If Settings is in front, this activity is started, which for a singleTask
     * activity means brought forward, and onResume does the redraw once it is, because building the
     * configuration screen re-applies lock task and Android refuses that for a task that is not in
     * the foreground. A device owner may start an activity from the background on every Android
     * version. An ordinary install on Android 10 or later has that refused, silently; such a device
     * has a navigation bar, the operator comes back by hand, and the same onResume does the redraw.
     * A change to anything but allowed (revoked, or the op touched for another reason) leaves the
     * card as it stands and keeps watching.
     */
    private void watchForWriteSettingsGrant() {
        if (writeSettingsWatch != null) {
            return;
        }
        android.app.AppOpsManager appOps = getSystemService(android.app.AppOpsManager.class);
        if (appOps == null) {
            return;
        }
        writeSettingsWatch = (op, packageName) -> mainHandler.post(this::onWriteSettingsChanged);
        appOps.startWatchingMode(android.app.AppOpsManager.OPSTR_WRITE_SETTINGS, getPackageName(),
                writeSettingsWatch);
    }

    private void stopWatchingWriteSettings() {
        if (writeSettingsWatch == null) {
            return;
        }
        android.app.AppOpsManager appOps = getSystemService(android.app.AppOpsManager.class);
        if (appOps != null) {
            appOps.stopWatchingMode(writeSettingsWatch);
        }
        writeSettingsWatch = null;
    }

    private void onWriteSettingsChanged() {
        if (isDestroyed() || isFinishing() || !KioskService.canWriteSystemSettings(this)) {
            return;
        }
        if (!configurationVisible || currentScreen == null) {
            // Granted over adb while a dashboard is up: nothing on screen claims otherwise, and
            // the next visit to the settings screen draws the card without the line.
            stopWatchingWriteSettings();
            return;
        }
        if (inFront) {
            stopWatchingWriteSettings();
            redrawInPlace(currentScreen);
            return;
        }
        // Settings is in front. Only come back here; the redraw waits for onResume, which keeps
        // the watcher until then. Redrawing now would rebuild the configuration screen while
        // another task holds the screen, and that screen re-applies lock task as it is built,
        // which Android refuses for a task that is not in the foreground.
        startActivity(new Intent(this, KioskActivity.class));
    }

    /**
     * Applies a freshly typed web admin password the moment the field loses focus.
     *
     * <p>Loads a fresh {@link KioskConfig} rather than reusing the one the screen was built with,
     * so this cannot clobber some other field changed since. A value equal to what is already
     * stored is a no-op, so merely tabbing through the field without editing it does not restart
     * the admin server for nothing.
     */
    private void applyHttpAdminPassword(String typed) {
        KioskConfig config = KioskConfig.load(this);
        if (typed.equals(config.httpAdminPassword)) {
            return;
        }
        if (!typed.isEmpty() && typed.length() < MIN_HTTP_ADMIN_PASSWORD_LENGTH) {
            Toast.makeText(this, "Not saved: the web admin password must be at least "
                    + MIN_HTTP_ADMIN_PASSWORD_LENGTH + " characters",
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (typed.isEmpty()) {
            // The field's only job is setting a new password; on/off is its own toggle now, so an
            // empty value has nothing to say.
            return;
        }
        KioskConfig.edit(this).httpAdminPassword(typed).apply();
        KioskService.reloadConfiguration(this);
        // The house rule for anything applied outside the dispatcher, even though no telemetry
        // field carries the password or the listening state today: the rule is what keeps the next
        // field that does from going stale.
        KioskService.publishTelemetrySoon(this);
        Toast.makeText(this, "Web admin password updated", Toast.LENGTH_SHORT).show();
    }

    /**
     * Applies one instantly-applied setting the moment it is touched, the way the web admin's
     * equivalent controls already do, and tells Home Assistant about it without waiting for the
     * next telemetry tick. These controls sit in the card each one is about rather than collected
     * into a box of their own, so this is what they have in common, not where they are.
     *
     * <p>Two things here are load-bearing. It needs no controller restart, because every one of
     * these settings is read live by whoever consumes it (the overlay ticker, the telemetry loop).
     * And it calls {@link KioskService#publishTelemetrySoon} so the MQTT switch in
     * Home Assistant reflects the new value in under a second rather than up to a full interval
     * later, the same reason {@code KioskService.dispatch} republishes after an accepted command.
     */
    private void applyLiveSetting(java.util.function.Consumer<KioskConfig.Editor> change) {
        if (syncingLiveControls) {
            // Our own write, echoed back by liveSettingSyncTask. Saving it again would be harmless
            // but pointless, and would republish state for a change nobody made.
            return;
        }
        KioskConfig.Editor editor = KioskConfig.edit(this);
        change.accept(editor);
        editor.apply();
        KioskService.publishTelemetrySoon(this);
    }

    /**
     * Sets a checkbox only when it actually differs, so following an external change never fires a
     * listener for a value that was already correct.
     */
    private static void setCheckedIfChanged(CheckBox box, boolean value) {
        if (box.isChecked() != value) {
            box.setChecked(value);
        }
    }

    /** One orientation option, carrying its stored spelling as the tag the listener reads back. */
    private RadioButton orientationChoice(
            KioskTheme theme, RadioGroup group, String label, String value) {
        RadioButton radio = new RadioButton(this);
        radio.setText(label);
        radio.setTextColor(theme.text);
        radio.setTextSize(15);
        // The platform default is a 48dp row, which stacked three high reads as a slab next to
        // the card's other controls; 36dp keeps a real touch target without the dead band.
        radio.setMinHeight(dp(36));
        radio.setMinimumHeight(dp(36));
        radio.setId(View.generateViewId());
        radio.setTag(value);
        // Not matchWrap(): its 16dp gap separates controls in a card, and between the rows of one
        // radio group it read as three separate controls with room for a fourth in each gap.
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.topMargin = dp(2);
        group.addView(radio, rowParams);
        return radio;
    }

    /**
     * The radio-group spelling of {@link #setCheckedIfChanged}: checks the option whose tag
     * matches, and only when it is not already checked, so following an external change never
     * fires the listener for a value that was already correct. A stored "auto" on a device whose
     * group does not offer it (no accelerometer) matches nothing and changes nothing, which
     * mirrors what {@code applyOrientation} does with the same value.
     */
    private static void checkOrientationIfChanged(RadioGroup group, String value) {
        for (int index = 0; index < group.getChildCount(); index++) {
            View child = group.getChildAt(index);
            if (value.equals(child.getTag())) {
                if (group.getCheckedRadioButtonId() != child.getId()) {
                    group.check(child.getId());
                }
                return;
            }
        }
    }

    private KioskTheme currentTheme() {
        return KioskTheme.of(KioskConfig.storageContext(this)
                .getSharedPreferences(UI_PREFERENCES, MODE_PRIVATE)
                .getBoolean(LIGHT_CONFIGURATION_THEME, false));
    }

    /**
     * The seven connection fields joined on NUL (which none of them can contain, same trick as the
     * service's controller fingerprints), so the Save button can tell whether any of them changed
     * on another surface while the screen sat open.
     */
    private static String connectionBaselineOf(KioskConfig config) {
        return config.dashboardUrl + '\u0000' + config.deviceId + '\u0000' + config.mqttHost
                + '\u0000' + config.mqttPort + '\u0000' + config.mqttUsername + '\u0000'
                + config.mqttPassword + '\u0000' + config.httpPort;
    }

    private void showConfiguration(KioskConfig config) {
        // What is actually stored right now, not what the (possibly rotation-carried) display
        // model says: the Save button compares against this to detect a form gone stale. Loaded
        // fresh even though a config was passed in, because after a rotation the passed object
        // carries half-typed field values that are exactly what must NOT be in the baseline.
        connectionBaseline = connectionBaselineOf(KioskConfig.load(this));
        // Deliberately stays inside lock-task mode: DevicePolicyManager.setStatusBarDisabled is
        // documented to have no effect outside it, so releasing lock task here, which is what this
        // screen used to do, to keep the navigation bar for dismissing the keyboard, left a fully
        // drawn status bar on the kiosk's own settings page. The keyboard is dismissed by the
        // Done action and by tapping outside a field instead.
        applyKioskPolicy();
        // Fullscreen here too: the status bar has no business on a kiosk's own settings screen.
        // The navigation bar is deliberately left alone so the stock keyboard stays dismissable.
        setDashboardFullscreen(true);
        recorderVisible = false;
        wizardVisible = false;
        destroyWebView();
        kioskStopped = false;
        configurationVisible = true;
        publishOperatorScreenState();
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        enterImmersiveMode();

        KioskTheme theme = currentTheme();
        LinearLayout page = pageColumn(theme);
        page.addView(pageHeading(theme, "Muralis", "Configuration"), matchWrap());
        View provisioningNotice = provisioningNotice(theme);
        if (provisioningNotice != null) {
            page.addView(provisioningNotice, matchWrap());
        }
        View launcherPrompt = defaultLauncherPrompt(theme);
        if (launcherPrompt != null) {
            page.addView(launcherPrompt, matchWrap());
        }

        LinearLayout dashboardCard = card(theme, "Dashboard");
        EditText urlInput = themedInput(theme, config.dashboardUrl.isEmpty()
                ? "http://homeassistant.local:8123/" : config.dashboardUrl, false);
        urlInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        addField(dashboardCard, theme, "Dashboard URL", urlInput);
        EditText deviceIdInput = themedInput(theme, config.deviceId, false);
        addField(dashboardCard, theme, "Device ID", deviceIdInput);
        // One button beside the aggregate Save ("Open dashboard") at the foot of the screen,
        // because it answers a different question: that one stores what is typed as THE
        // dashboard, this one shows it without changing what is stored. Juri, 2026-08-24: a URL
        // with one-off query parameters is exactly what a stored dashboard URL must not become.
        //
        // There used to be a second one here, "Main dashboard", which opened the stored dashboard
        // and saved nothing. Removed 2026-09-07 at Juri's decision, after it cost him a full set
        // of typed MQTT credentials: two buttons on one screen carrying the word "dashboard", and
        // the one that saves is at the foot and named after what it opens, so the nearer one was
        // pressed as if it were the save and the screen closed without one. Anyone who
        // misunderstands that button makes the same mistake, so it is gone rather than renamed.
        // Nothing is lost: the foot button already returns to the stored dashboard, a kiosk
        // restart does too, and Home Assistant keeps its own kiosk.home button.
        Button openOnce = secondaryButton(theme, "Open once");
        openOnce.setOnClickListener(view -> {
            String once = normalizeUrl(urlInput.getText().toString());
            String problem = KioskCommandDispatcher.validateDashboardUrl(once);
            if (problem != null) {
                Toast.makeText(this, "Not opened: " + problem + ".", Toast.LENGTH_LONG).show();
                return;
            }
            Toast.makeText(this, "Opened once, not saved as the dashboard",
                    Toast.LENGTH_SHORT).show();
            showDashboard(once);
        });
        dashboardCard.addView(openOnce, matchWrap());
        String webViewProvider = webViewProviderSummary();
        if (webViewProvider != null) {
            // Plain subtext, deliberately not a warning: see webViewProviderSummary().
            TextView engine = new TextView(this);
            engine.setTextColor(theme.subtext);
            engine.setTextSize(13);
            engine.setText("Rendering engine: " + webViewProvider);
            dashboardCard.addView(engine, matchWrap());
        }


        LinearLayout mqttCard = card(theme, "MQTT");
        EditText brokerInput = themedInput(theme, config.mqttHost, false);
        // The URL keyboard, dot and slash to hand, and no sentence habits: see themedInput.
        brokerInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        addField(mqttCard, theme, "Broker host", brokerInput);
        EditText portInput = themedInput(theme, Integer.toString(config.mqttPort), false);
        portInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        addField(mqttCard, theme, "Broker port", portInput);
        EditText usernameInput = themedInput(theme, config.mqttUsername, false);
        addField(mqttCard, theme, "Username", usernameInput);
        // Rendered blank, never prefilled: the stored plaintext in a masked EditText is one
        // input-type toggle (or one accessibility service) away from being read, and the web
        // admin has always rendered this blank for that reason. Blank keeps the current one,
        // the same rule the web admin's form follows; the Save path skips an empty box.
        EditText passwordInput = themedInput(theme, "", true);
        addField(mqttCard, theme, "Password (blank keeps the current one)", passwordInput);
        // The broker verdict lives here, on the page where the address is typed, not as a toast
        // over the dashboard: the toast was unreadable in the second before the dashboard took
        // the screen, which is exactly where a misconfiguration must NOT be reported (Juri,
        // 2026-08-24). Checked when the screen opens and whenever the host or port box is left.
        TextView mqttState = new TextView(this);
        mqttState.setTextSize(13);
        mqttState.setTextColor(theme.subtext);
        LinearLayout.LayoutParams mqttStateParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mqttStateParams.topMargin = dp(10);
        mqttCard.addView(mqttState, mqttStateParams);



        LinearLayout httpCard = card(theme, "Local web admin");
        EditText httpPortInput = themedInput(theme, Integer.toString(config.httpPort), false);
        httpPortInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        addField(httpCard, theme, "Port", httpPortInput);
        // The same blur pre-checks the web admin's page runs (SettingProbe): the field's tint
        // answers whether the value would work from this device right now. Values are captured
        // here on the UI thread; only the probe itself runs on the worker.
        urlInput.setOnFocusChangeListener((view, hasFocus) -> {
            clearPrecheck(urlInput, theme);
            String typed = normalizeUrl(urlInput.getText().toString());
            if (!hasFocus && !typed.isEmpty()) {
                runPrecheck(urlInput, theme, () -> SettingProbe.dashboardUrl(typed));
            }
        });
        Runnable checkBroker = () -> {
            clearPrecheck(brokerInput, theme);
            String host = brokerInput.getText().toString().trim();
            Integer brokerPort = parsePortStrict(portInput.getText().toString());
            if (host.isEmpty()) {
                mqttState.setTextColor(theme.subtext);
                mqttState.setText("No broker configured");
                return;
            }
            if (brokerPort == null) {
                // The port is not a port, so there is nothing to probe and nothing to guess: the
                // old code substituted 1883 and then reported on an address nobody typed.
                clearPrecheck(portInput, theme);
                portInput.setBackground(theme.outlinedPanel(theme.surfaceAlt, dp(10), dp(2),
                        theme.bad));
                mqttState.setTextColor(theme.bad);
                mqttState.setText("Broker port must be a number between 1 and 65535");
                return;
            }
            mqttState.setTextColor(theme.subtext);
            mqttState.setText("Checking " + host + ":" + brokerPort + "...");
            int generation = precheckGeneration.merge(brokerInput, 1, Integer::sum);
            new Thread(() -> {
                SettingProbe.Verdict verdict = SettingProbe.mqttHost(host, brokerPort);
                mainHandler.post(() -> {
                    Integer current = precheckGeneration.get(brokerInput);
                    if (!brokerInput.isAttachedToWindow() || current == null
                            || current != generation) {
                        return;
                    }
                    brokerInput.setBackground(theme.outlinedPanel(theme.surfaceAlt, dp(10), dp(2),
                            verdict.ok ? theme.ok : theme.bad));
                    mqttState.setTextColor(verdict.ok ? theme.ok : theme.bad);
                    mqttState.setText(verdict.ok
                            ? "Broker reachable at " + host + ":" + brokerPort
                            : "Broker unreachable: " + verdict.detail);
                });
            }, "MuralisPrecheck").start();
        };
        brokerInput.setOnFocusChangeListener((view, hasFocus) -> {
            if (!hasFocus) {
                checkBroker.run();
            }
        });
        // The port belongs to the same address, so leaving it re-checks the pair. Its own box only
        // ever shows red, for a value that is not a port at all; whether the broker answers is the
        // host line's verdict.
        portInput.setOnFocusChangeListener((view, hasFocus) -> {
            if (!hasFocus) {
                clearPrecheck(portInput, theme);
                checkBroker.run();
            }
        });
        httpPortInput.setOnFocusChangeListener((view, hasFocus) -> {
            clearPrecheck(httpPortInput, theme);
            String typed = httpPortInput.getText().toString().trim();
            int bound = KioskRuntimeState.httpAdminListening()
                    ? KioskRuntimeState.httpAdminPort() : -1;
            if (!hasFocus && !typed.isEmpty()) {
                runPrecheck(httpPortInput, theme, () -> SettingProbe.adminPort(typed, bound));
            }
        });
        // Rendered blank, never prefilled, same reasoning as the broker password above, and the
        // same words as the web admin's form so the two surfaces describe one rule. Blank means
        // exactly what the label says, including after an edit: switching the web admin off is
        // its own button below, not a gesture hidden inside an empty field.
        EditText httpAdminPasswordInput = themedInput(theme, "", true);
        addField(httpCard, theme, "Admin password (blank keeps the current one)",
                httpAdminPasswordInput);
        // Applied on blur, not on the aggregate Save: waiting for "Open dashboard" meant the web
        // admin stayed dark, or kept an old password, until the operator happened to leave the
        // screen for an unrelated reason. Same shape as the auto-brightness checkbox above: a
        // field losing focus is as much a deliberate action as a click is.
        httpAdminPasswordInput.setOnFocusChangeListener((view, hasFocus) -> {
            String typed = httpAdminPasswordInput.getText().toString();
            if (!hasFocus && !typed.isEmpty()) {
                applyHttpAdminPassword(typed);
            }
        });
        // Whether the surface actually holds a socket, and at which address. It fails closed by
        // design, so without this the difference between "listening" and "silently off because the
        // password is too short" was one line in logcat, invisible from the panel itself.
        TextView httpState = new TextView(this);
        httpState.setTextSize(13);
        // On/off is its own stored flag, never the password: turning the surface off must not
        // cost the credential, and turning it back on must not require retyping one. The label is
        // the state, so the button always says what pressing it does.
        Button webAdminToggle = secondaryButton(theme,
                config.webAdminEnabled ? "Turn web admin off" : "Turn web admin on");
        webAdminToggle.setOnClickListener(view -> {
            KioskConfig current = KioskConfig.load(this);
            boolean enable = !current.webAdminEnabled;
            KioskConfig.edit(this).webAdminEnabled(enable).apply();
            KioskService.reloadConfiguration(this);
            KioskService.publishTelemetrySoon(this);
            if (enable && current.httpAdminPassword.length() < MIN_HTTP_ADMIN_PASSWORD_LENGTH) {
                Toast.makeText(this, "Web admin on, but it will not start until an admin "
                        + "password of at least " + MIN_HTTP_ADMIN_PASSWORD_LENGTH
                        + " characters is set", Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, enable ? "Web admin on" : "Web admin off",
                        Toast.LENGTH_SHORT).show();
            }
            // The label flips at once; the state line waits a beat, the bind happens on the
            // service's queue, and the live-sync poll keeps both honest after that.
            refreshWebAdminControls(webAdminToggle, httpState, theme);
            mainHandler.postDelayed(
                    () -> refreshWebAdminControls(webAdminToggle, httpState, theme), 700);
        });
        httpCard.addView(webAdminToggle, matchWrap());
        refreshWebAdminControls(webAdminToggle, httpState, theme);
        // The broker is checked as soon as the screen opens, not only after an edit: an operator
        // who comes here because "Home Assistant lost the panel" gets the answer without having
        // to touch a field first.
        checkBroker.run();
        LinearLayout.LayoutParams httpStateParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        httpStateParams.topMargin = dp(10);
        httpCard.addView(httpState, httpStateParams);


        // Everything about how the glass looks, in one card: the backlight, which way up the
        // panel is, and the colours of this screen itself. That last one sits in the card's
        // header as a sun/moon toggle, the same corner the web admin keeps its theme pick in: it
        // is a view control for whoever is reading this screen, not one more device setting, and
        // as a checkbox at the bottom of the card it read as an orphan.
        LinearLayout displayCard = card(theme, null);
        LinearLayout displayHeader = new LinearLayout(this);
        displayHeader.setOrientation(LinearLayout.HORIZONTAL);
        displayHeader.setGravity(Gravity.CENTER_VERTICAL);
        TextView displayTitle = new TextView(this);
        displayTitle.setText("Display");
        displayTitle.setTextColor(theme.text);
        displayTitle.setTextSize(18);
        displayHeader.addView(displayTitle, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        // Kept in UI preferences rather than KioskConfig, and so deliberately outside
        // applyLiveSetting: it is a preference of whoever is standing at the tablet reading this
        // screen, not a property of the device, and nothing else has any business following it.
        ImageView themeToggle = new ImageView(this);
        themeToggle.setImageResource(theme.light
                ? R.drawable.ic_theme_moon : R.drawable.ic_theme_sun);
        // The sun in the palette's yellow, the moon in the neutral subtext grey: shapes and
        // colours people already read as day and night, where the accent-blue font glyphs read
        // as neither.
        themeToggle.setColorFilter(theme.light ? theme.subtext : theme.warn);
        themeToggle.setContentDescription(theme.light
                ? "Switch this screen to the dark theme"
                : "Switch this screen to the light theme");
        themeToggle.setMinimumWidth(dp(48));
        themeToggle.setBackground(theme.outlinedPanel(theme.surfaceAlt, dp(18), dp(1)));
        themeToggle.setPadding(dp(14), dp(6), dp(14), dp(6));
        themeToggle.setOnClickListener(view -> {
            KioskConfig.storageContext(this).getSharedPreferences(UI_PREFERENCES, MODE_PRIVATE)
                    .edit()
                    .putBoolean(LIGHT_CONFIGURATION_THEME, !theme.light)
                    .apply();
            // Loaded fresh, not the snapshot this screen was built from; same rule the rotation
            // redraw and the save button follow. In place, because the operator is looking at the
            // Display card and a new palette is no reason to take it away from them.
            redrawInPlace(() -> showConfiguration(KioskConfig.load(this)));
        });
        displayHeader.addView(themeToggle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        displayCard.addView(displayHeader, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        final CheckBox autoBrightnessInput;
        // Remembered so saving the form can tell an actual change from an unchanged checkbox. Without
        // this, every save re-applied the current value, and on a device without the WRITE_SETTINGS
        // app-op that meant every save bounced the operator into Android Settings and dropped the
        // kiosk's lock-task hold. Observed on the panel 2026-08-19.
        final boolean autoBrightnessWasOn = KioskService.isAutoBrightnessOn(this);
        // The slider, and the label that reads back what the panel is actually at. Declared before
        // the checkbox because the checkbox enables and disables it.
        final SeekBar brightnessInput = new SeekBar(this);
        final TextView brightnessValue = new TextView(this);
        // Caption first, then the sensor checkbox, then the slider: every cluster in this card
        // leads with its heading, so nothing reads as a control floating on its own.
        TextView brightnessCaption = fieldCaption(theme, "Brightness");
        LinearLayout.LayoutParams brightnessCaptionParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        brightnessCaptionParams.topMargin = dp(14);
        displayCard.addView(brightnessCaption, brightnessCaptionParams);
        // The permission both brightness controls need, stated on the card rather than only in a
        // toast at the moment one of them is refused. Juri, on a freshly provisioned panel
        // 2026-09-07: "the brightness toggle does not toggle", and neither the app nor the setup
        // page said why. In this state the card looks like two separate bugs rather than one
        // missing grant: the checkbox cannot write SCREEN_BRIGHTNESS_MODE without WRITE_SETTINGS,
        // and the slider is separately disabled while the light sensor owns the backlight, which
        // on a device nobody has configured yet it does by default. Device-owner status buys
        // nothing here, unlike the Global and Secure namespaces: Settings.System has no
        // device-owner setter, so this is a grant somebody makes once by hand.
        if (!KioskService.canWriteSystemSettings(this)) {
            TextView needsGrant = new TextView(this);
            needsGrant.setTextColor(theme.bad);
            needsGrant.setTextSize(13);
            needsGrant.setText("Brightness cannot be set until Muralis has the \"Modify system "
                    + "settings\" permission. It is the only thing on this panel that needs it.");
            displayCard.addView(needsGrant, matchWrapClose());
            Button grantWriteSettings = secondaryButton(theme, "Grant it now");
            grantWriteSettings.setOnClickListener(view -> offerWriteSettingsGrant());
            displayCard.addView(grantWriteSettings, matchWrap());
            // The line and the button are a claim about right now, so they must go the moment it
            // stops being true, without waiting for the screen to be rebuilt by something else.
            watchForWriteSettingsGrant();
        } else {
            stopWatchingWriteSettings();
        }
        if (KioskService.hasLightSensor(this)) {
            autoBrightnessInput = themedCheckBox(theme,
                    "Adjust brightness automatically",
                    autoBrightnessWasOn);
            // Applied the moment it is touched, not on save.
            //
            // It used to wait for save, to avoid re-applying an unchanged value and prompting for
            // WRITE_SETTINGS on every save; that bug was in applying a value nobody had changed, and
            // a click is by definition a change. Waiting is now actively wrong: the slider below is
            // enabled only while the sensor is not in charge, and if the checkbox on screen did not
            // match the system until save, the slider would sit enabled while a brightness written
            // through it was refused. Same behaviour as the web admin's checkbox, which has always
            // applied immediately.
            autoBrightnessInput.setOnCheckedChangeListener((button, checked) -> {
                if (KioskService.applyAutoBrightness(this, checked)) {
                    // Automatic mode does nothing until any per-window override is out of the way,
                    // in either direction; brightness itself is the system setting now.
                    setWindowBrightness(-1);
                    // Same house rule as the slider: applied outside the dispatcher, so republish,
                    // or the HA switch shows the old side for up to a minute.
                    KioskService.publishTelemetrySoon(this);
                } else if (!KioskService.canWriteSystemSettings(this)) {
                    // The hardware can do it and the app cannot, which is a fixable state, so say so
                    // and offer the one screen that fixes it.
                    button.setChecked(!checked);
                    offerWriteSettingsGrant();
                    return;
                }
                applyBrightnessEnabledState(brightnessInput, brightnessValue, theme);
            });
            displayCard.addView(autoBrightnessInput, matchWrapClose());
        } else {
            // No sensor, so no control: a toggle that cannot work is worse than no toggle.
            autoBrightnessInput = null;
            TextView noSensor = new TextView(this);
            noSensor.setTextColor(theme.subtext);
            noSensor.setTextSize(13);
            noSensor.setText("This tablet has no ambient light sensor, so brightness is manual "
                    + "only. Set it remotely with display.brightness.");
            displayCard.addView(noSensor, matchWrapClose());
        }

        // Brightness, applied as it moves, exactly like the web admin's slider and for the same
        // reason: it is a standalone control, so a Save button between the operator and the panel
        // getting brighter is pure ceremony.
        LinearLayout brightnessRow = new LinearLayout(this);
        brightnessRow.setOrientation(LinearLayout.HORIZONTAL);
        brightnessRow.setGravity(Gravity.CENTER_VERTICAL);
        brightnessInput.setMax(100);
        int startingPercent = KioskService.currentBrightnessPercent(this);
        brightnessInput.setProgress(startingPercent < 1 ? 50 : startingPercent);
        brightnessInput.getProgressDrawable().setColorFilter(
                theme.accent, android.graphics.PorterDuff.Mode.SRC_IN);
        brightnessInput.getThumb().setColorFilter(
                theme.accent, android.graphics.PorterDuff.Mode.SRC_IN);
        brightnessValue.setTextColor(theme.text);
        brightnessValue.setTextSize(14);
        brightnessValue.setMinWidth(dp(52));
        brightnessValue.setGravity(Gravity.END);
        brightnessInput.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                brightnessValue.setText(Math.max(1, progress) + "%");
                if (!fromUser) {
                    return;
                }
                // Coalesced while the finger is moving. A SeekBar reports every pixel, and each
                // report is a settings write.
                mainHandler.removeCallbacks(brightnessApplyTask);
                pendingBrightnessPercent = Math.max(1, progress);
                mainHandler.postDelayed(brightnessApplyTask, BRIGHTNESS_APPLY_DELAY_MS);
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                // The final position always lands, whatever the coalescing did with the ones before.
                mainHandler.removeCallbacks(brightnessApplyTask);
                pendingBrightnessPercent = Math.max(1, bar.getProgress());
                brightnessApplyTask.run();
            }
        });
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        brightnessRow.addView(brightnessInput, barParams);
        brightnessRow.addView(brightnessValue, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        displayCard.addView(brightnessRow, matchWrapClose());

        TextView brightnessNote = new TextView(this);
        brightnessNote.setTextColor(theme.subtext);
        brightnessNote.setTextSize(12);
        displayCard.addView(brightnessNote, matchWrapClose());
        brightnessModeNote = brightnessNote;
        applyBrightnessEnabledState(brightnessInput, brightnessValue, theme);

        TextView orientationLabel = fieldCaption(theme, "Orientation");
        LinearLayout.LayoutParams orientationLabelParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        orientationLabelParams.topMargin = dp(14);
        displayCard.addView(orientationLabel, orientationLabelParams);

        RadioGroup orientationInput = new RadioGroup(this);
        // "Auto-rotate", the platform's own name for it, is offered only where a sensor exists to
        // follow, the same gate the auto-brightness checkbox sits behind just above.
        if (KioskService.hasAccelerometer(this)) {
            orientationChoice(theme, orientationInput, "Auto-rotate",
                    KioskConfig.ORIENTATION_AUTO);
        }
        orientationChoice(theme, orientationInput, "Landscape", KioskConfig.ORIENTATION_LANDSCAPE);
        orientationChoice(theme, orientationInput, "Portrait", KioskConfig.ORIENTATION_PORTRAIT);
        checkOrientationIfChanged(orientationInput, config.orientation);
        orientationInput.setOnCheckedChangeListener((group, checkedId) -> {
            View checked = group.findViewById(checkedId);
            if (checked == null) {
                return;
            }
            String value = (String) checked.getTag();
            applyLiveSetting(editor -> editor.orientation(value));
            // Applied here as well as saved, because this screen is the one surface that does not go
            // through KioskService and so never receives the broadcast that turns the window.
            applyOrientation();
        });
        displayCard.addView(orientationInput, matchWrapClose());

        // The web admin's System stats box, on the tablet: the same eight rows from the same
        // formatter, with the switch that puts them on the dashboard directly under them. A switch
        // labelled "show system stats" sitting three cards away from the stats it shows was a
        // question the operator had to answer by toggling it and looking somewhere else.
        LinearLayout statsCard = card(theme, "System stats");
        TextView statsReadout = new TextView(this);
        statsReadout.setTypeface(Typeface.MONOSPACE);
        statsReadout.setTextSize(13);
        statsReadout.setTextColor(theme.text);
        statsReadout.setLineSpacing(dp(2), 1.1f);
        // Drawn as a code block, matching the web admin's <pre id="stats">: same monospace face, same
        // darker plate behind it, same padding and corner. The two surfaces show identical rows from
        // identical data, so looking identical is the honest presentation; monospace text sitting
        // bare on the card read as prose that happened to be misaligned.
        statsReadout.setBackground(theme.panel(theme.mantle, dp(10)));
        int statsPad = dp(10);
        statsReadout.setPadding(statsPad, statsPad, statsPad, statsPad);
        statsReadout.setText(renderOverlay());
        statsCard.addView(statsReadout, matchWrap());
        // Repainted by overlayTask on the same one-second tick as the dashboard overlay and the
        // status chip, and for the same reason: a stats block that was a snapshot taken when the
        // screen was built is a worse readout than none, because it looks live.
        configStatsView = statsReadout;

        // Applies the moment it is touched, and is deliberately absent from the "Open dashboard"
        // save below. It is a standalone setting read live by whoever uses it, exactly like its
        // counterpart in the web admin, which has no Save button for the same reason. Leaving it
        // to the aggregate save was a real bug: the whole KioskConfig snapshot this screen was
        // built from got written back, so a value changed over MQTT or HTTP while the screen sat
        // open was silently reverted on save.
        //
        // The dashboard-recycle and frozen-page checkboxes used to sit beside it. Both are gone:
        // they are recovery mechanisms, not preferences, and a switch whose only use is to stop
        // the panel healing itself is surface area that can only be used to break it. See
        // RecyclePolicy and checkForFrozenPage, which now run unconditionally.
        CheckBox statsOverlayInput = themedCheckBox(theme,
                "Show system stats on the dashboard", config.statsOverlay);
        statsOverlayInput.setOnCheckedChangeListener(
                (button, checked) -> applyLiveSetting(editor -> editor.statsOverlay(checked)));
        statsCard.addView(statsOverlayInput, matchWrap());

        // Follow these controls while the screen sits open, so a change made over MQTT or from the
        // web admin shows up here rather than leaving two surfaces disagreeing. The web admin has
        // done this from the start via its five-second /api/stats poll; this is the tablet's
        // equivalent. Only the instantly applied controls are followed: the text fields are things
        // the operator may be part-way through typing, and snatching those back would be hostile.
        if (liveSettingSyncTask != null) {
            mainHandler.removeCallbacks(liveSettingSyncTask);
        }
        liveSettingSyncTask = new Runnable() {
            @Override
            public void run() {
                // Stops itself rather than needing every other screen to remember to cancel it.
                // The attachment test is the load-bearing half: About, the legal pages and the
                // escape-sequence screen all leave configurationVisible true while replacing the
                // content view, so without it this would keep polling and holding a detached
                // view tree for as long as the panel stayed up.
                if (!configurationVisible || !statsOverlayInput.isAttachedToWindow()) {
                    return;
                }
                syncingLiveControls = true;
                try {
                    setCheckedIfChanged(statsOverlayInput,
                            KioskConfig.statsOverlayEnabled(KioskActivity.this));
                    checkOrientationIfChanged(orientationInput,
                            KioskConfig.orientationOf(KioskActivity.this));
                } finally {
                    syncingLiveControls = false;
                }
                // The web admin button and status line follow the flag and the socket too, so a
                // toggle from MQTT or the web admin itself shows here without reopening the screen.
                refreshWebAdminControls(webAdminToggle, httpState, theme);
                mainHandler.postDelayed(this, LIVE_SETTING_SYNC_INTERVAL_MS);
            }
        };
        mainHandler.postDelayed(liveSettingSyncTask, LIVE_SETTING_SYNC_INTERVAL_MS);

        // The Pro gate's face. Juri's chosen shape (2026-08-27): the paid cards stay visible and
        // complete but inert, each carrying one line and its own Buy button, because a feature
        // nobody can see is a feature nobody buys, and a hidden card would also make a bought
        // panel and a free one different screens. Evaluated once per build; the ProBilling
        // listener below rebuilds this screen when the answer changes, so a purchase made at the
        // panel unlocks the cards while the buyer is still standing there.
        boolean proActive = ProEntitlement.isActive(this);
        if (!proActive) {
            lockCardForPro(theme, mqttCard);
            lockCardForPro(theme, httpCard);
        }

        LinearLayout escapeCard = card(theme, "Escape sequences");
        TextView escapeSummary = new TextView(this);
        escapeSummary.setTextColor(theme.subtext);
        escapeSummary.setTextSize(14);
        escapeSummary.setText("Settings: "
                + EscapeSequence.describe(EscapeSequence.parse(config.settingsSequence))
                + "\nLauncher: "
                + EscapeSequence.describe(EscapeSequence.parse(config.launcherSequence)));
        escapeCard.addView(escapeSummary, matchWrap());
        Button manageSequences = secondaryButton(theme, "Manage escape sequences");
        manageSequences.setOnClickListener(view -> showEscapeSequences(KioskConfig.load(this)));
        escapeCard.addView(manageSequences, matchWrap());

        // The Pro state lives in About (moved 2026-08-29, Juri's call): it is a fact about this
        // installation, like the version line beside it, not a card-sized feature of its own.
        // The purchase still lives where the features it unlocks are: the locked MQTT and web
        // admin cards stay visible, complete and inert, each with its own Buy button. The button
        // here only appears while Play says the product is buyable, so a bought panel shows one
        // quiet status line.
        LinearLayout aboutCard = card(theme, "About");
        TextView buildLine = new TextView(this);
        buildLine.setTextColor(theme.subtext);
        buildLine.setTextSize(13);
        buildLine.setText(appVersionSummary());
        aboutCard.addView(buildLine, matchWrap());
        TextView proCaption = fieldCaption(theme, "Muralis Pro");
        LinearLayout.LayoutParams proCaptionParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        proCaptionParams.topMargin = dp(14);
        aboutCard.addView(proCaption, proCaptionParams);
        TextView proState = new TextView(this);
        proState.setTextColor(theme.subtext);
        proState.setTextSize(14);
        proState.setText("Checking Google Play…");
        aboutCard.addView(proState, matchWrapClose());
        Button buyPro = secondaryButton(theme, "Buy Muralis Pro");
        buyPro.setVisibility(View.GONE);
        buyPro.setOnClickListener(view -> proBilling.buy(this));
        aboutCard.addView(buyPro, matchWrapClose());
        // Created at startup, not here: this screen only attaches its row to it. setListener
        // publishes what is already known, synchronously, before re-asking, so the card shows the
        // last answer immediately rather than flashing "Checking Google Play…" on every rebuild.
        // That synchronous call arrives while this tree is still being built and not yet attached
        // to the window, which is why the staleness guard below must not run for it: an
        // attachment test alone would silently eat exactly the paint the synchronous publish
        // exists to deliver (and did, before the flag).
        final boolean[] proCardBuilt = {false};
        proBilling.setListener((proDetail, owned, buyable) -> {
            // This screen is rebuilt wholesale on rotation and after every save; a result that
            // arrives later for a replaced view tree must not touch it. Same attachment rule as
            // the live-settings sync above, skipped only for the build-time paint.
            if (proCardBuilt[0] && !proState.isAttachedToWindow()) {
                return;
            }
            // The entitlement changed while this screen was up, which outside a debug override
            // means the purchase just completed: rebuild so the locked cards open. Only on a real
            // transition, because a rebuild re-registers this listener and re-publishes, and a
            // rebuild on every publish would loop.
            if (proCardBuilt[0] && ProEntitlement.isActive(KioskActivity.this) != proActive) {
                redrawInPlace(() ->
                        showConfiguration(KioskConfig.load(KioskActivity.this)));
                return;
            }
            proState.setText(proDetail);
            buyPro.setVisibility(buyable ? View.VISIBLE : View.GONE);
        });
        proCardBuilt[0] = true;

        Button aboutButton = secondaryButton(theme, "Version, privacy and terms");
        aboutButton.setOnClickListener(view -> showAbout());
        aboutCard.addView(aboutButton, matchWrap());
        // Ordinary installs only. On a device-owner panel "close" is meaningless (Muralis is HOME,
        // the system relaunches it immediately) and the escape sequence is the deliberate exit, so
        // a close button there is a control whose only use is breaking the panel, the same class
        // of surface the auto-recycle switches were deleted for. Deliberately NOT on the web admin
        // or MQTT either, for the reason system.shutdown was deleted: a remote close has no remote
        // undo, because the thing that would receive the reopen command is what was just closed.
        if (!isDeviceOwner()) {
            Button closeApp = secondaryButton(theme, "Close Muralis");
            closeApp.setOnClickListener(view -> closeCompletely());
            aboutCard.addView(closeApp, matchWrap());
        }

        page.addView(cardGrid(theme, java.util.Arrays.<View>asList(
                dashboardCard, mqttCard, httpCard, displayCard, statsCard, escapeCard,
                aboutCard)),
                matchWrap());

        Button open = primaryButton(theme, "Open dashboard");
        open.setOnClickListener(view -> {
            String url = normalizeUrl(urlInput.getText().toString());
            if (!url.isEmpty()) {
                // Stale-form guard, the same rule the escape recorder and the web admin boxes
                // follow: this form holds the values that were current when the screen was built,
                // and if another surface changed any of them since, writing the form back would
                // silently revert that change. Refused with an explanation, and the screen is
                // rebuilt showing what is actually stored now.
                KioskConfig current = KioskConfig.load(this);
                if (!connectionBaselineOf(current).equals(connectionBaseline)) {
                    Toast.makeText(this, "Not saved: these settings were changed from another "
                            + "surface while this screen was open. Showing the current values.",
                            Toast.LENGTH_LONG).show();
                    redrawInPlace(() -> showConfiguration(current));
                    return;
                }
                // The dispatcher's rules, shared with the web admin: normalizeUrl already adds a
                // missing scheme, but an over-long URL or an id MQTT cannot carry must be refused
                // here, where the operator is, not discovered later as a logcat line.
                String urlProblem = KioskCommandDispatcher.validateDashboardUrl(url);
                if (urlProblem != null) {
                    Toast.makeText(this, "Not saved: " + urlProblem + ".",
                            Toast.LENGTH_LONG).show();
                    return;
                }
                String deviceId = deviceIdInput.getText().toString().trim();
                String idProblem = KioskCommandDispatcher.validateDeviceId(deviceId);
                if (idProblem != null) {
                    Toast.makeText(this, "Not saved: " + idProblem + ".",
                            Toast.LENGTH_LONG).show();
                    return;
                }
                // Refused, never substituted, and the same three rules the web save applies: a
                // number, inside 1024-65535 (a privileged port passes a plain range check and
                // fails at bind time, killing the web admin), and not already held by another
                // service. parsePort's clamp is for reading storage back, not for a form.
                Integer typedAdminPort = parsePortStrict(httpPortInput.getText().toString());
                if (typedAdminPort == null) {
                    Toast.makeText(this, "Not saved: the web admin port must be a number "
                            + "between 1 and 65535", Toast.LENGTH_LONG).show();
                    return;
                }
                int adminPort = typedAdminPort;
                String portProblem = KioskCommandDispatcher.validateAdminPort(adminPort);
                if (portProblem != null) {
                    Toast.makeText(this, "Not saved: " + portProblem + ".",
                            Toast.LENGTH_LONG).show();
                    return;
                }
                KioskConfig storedNow = KioskConfig.load(this);
                if (adminPort != storedNow.httpPort && !SettingProbe.portFree(adminPort)) {
                    // Same gate as the web save: the advisory border is a warning, this is the
                    // refusal, so a save cannot put the admin into a bind failure.
                    Toast.makeText(this, "Not saved: port " + adminPort + " is already in use on "
                            + "this device", Toast.LENGTH_LONG).show();
                    return;
                }
                Integer typedBrokerPort = parsePortStrict(portInput.getText().toString());
                if (typedBrokerPort == null) {
                    Toast.makeText(this, "Not saved: the broker port must be a number between 1 "
                            + "and 65535", Toast.LENGTH_LONG).show();
                    return;
                }
                // Only the fields with a text box on this screen are written. The overlay switch,
                // portrait, the brightness pair and the admin password already applied themselves
                // when touched, and the Editor cannot touch what it was not given.
                KioskConfig.Editor editor = KioskConfig.edit(this)
                        .dashboardUrl(url)
                        .deviceId(deviceId)
                        .mqttHost(brokerInput.getText().toString())
                        .mqttPort(typedBrokerPort)
                        .mqttUsername(usernameInput.getText().toString())
                        .httpPort(adminPort);
                String mqttPassword = passwordInput.getText().toString();
                if (!mqttPassword.isEmpty()) {
                    // The box renders blank and blank means "keep", exactly like the web admin's
                    // form; writing the empty string here would wipe the stored password on every
                    // unrelated save.
                    editor.mqttPassword(mqttPassword);
                }
                editor.apply();
                KioskService.reloadConfiguration(this);
                // House rule: applied outside the dispatcher, so republish. dashboard_url and
                // device id ride in the telemetry document, and this save used to leave Home
                // Assistant on the old values for up to a minute.
                KioskService.publishTelemetrySoon(this);
                showDashboard(url);
            }
        });
        // No "Configure Wi-Fi" button: Android Settings draws no navigation bar under this ROM,
        // so handing it the screen left no way back. Wi-Fi is set up once during provisioning, and
        // Settings is still reachable through the escape sequence when it is genuinely needed.
        page.addView(actionRow(java.util.Arrays.<View>asList(open)), matchWrap());

        // The page itself takes the initial focus, so no field holds it uninvited. Without this,
        // the first EditText (the dashboard URL) silently owned the focus from the moment the
        // screen was built, and the operator's first tap into any other field blurred it, which
        // ran a pre-check on a field they never visited: reported 2026-08-24 as the URL box
        // turning green while resetting the admin password. A probe should only ever follow a
        // deliberate visit.
        page.setFocusableInTouchMode(true);
        page.requestFocus();
        setContentView(scrollPage(theme, page));
        // Rotating rebuilds this screen, so it has to carry the half-typed fields across. Rebuilt
        // from a fresh load with the boxes laid over it, which is the rule the Save button follows
        // too: the snapshot this screen was built from is stale the moment another surface writes.
        currentScreen = () -> {
            KioskConfig pending = KioskConfig.load(this);
            pending.dashboardUrl = urlInput.getText().toString();
            pending.deviceId = deviceIdInput.getText().toString();
            pending.mqttHost = brokerInput.getText().toString();
            pending.mqttPort = parsePort(portInput.getText().toString(), pending.mqttPort);
            pending.mqttUsername = usernameInput.getText().toString();
            // The password deliberately does not ride across a rotation: the box renders blank by
            // design, and carrying a half-typed secret in the display model would re-render it.
            pending.httpPort = parsePort(httpPortInput.getText().toString(), pending.httpPort);
            showConfiguration(pending);
        };
    }

    /**
     * Both escape sequences on one page, reached by a single button from the configuration screen,
     * one place to see what the combinations currently are, rather than two buttons whose labels
     * have to carry that information.
     */
    private void showEscapeSequences(KioskConfig config) {
        configurationVisible = true;
        recorderVisible = false;
        publishOperatorScreenState();
        applyKioskPolicy();
        KioskTheme theme = currentTheme();
        LinearLayout page = pageColumn(theme);
        page.addView(pageHeading(theme, "Escape sequences",
                "Tap combinations that unlock the kiosk"), matchWrap());

        TextView explain = new TextView(this);
        explain.setTextColor(theme.subtext);
        explain.setTextSize(14);
        explain.setText("A combination is a series of taps in the corners of the screen. Record "
                + "your own so it is not the same on every device, and keep it to yourself, "
                + "anyone who watches you perform it can repeat it.");
        page.addView(explain, matchWrap());

        page.addView(cardGrid(theme, java.util.Arrays.<View>asList(
                sequenceCard(theme, "Open Muralis settings", config.settingsSequence, false),
                sequenceCard(theme, "Exit to the system launcher", config.launcherSequence, true))),
                matchWrap());

        Button back = primaryButton(theme, "Back to configuration");
        back.setOnClickListener(view -> showConfiguration(KioskConfig.load(this)));
        page.addView(back, matchWrap());

        setContentView(scrollPage(theme, page));
        currentScreen = () -> showEscapeSequences(KioskConfig.load(this));
    }

    private LinearLayout sequenceCard(KioskTheme theme, String title, String sequence,
            boolean forLauncher) {
        LinearLayout item = card(theme, title);
        TextView current = new TextView(this);
        current.setTextColor(theme.accentAlt);
        current.setTextSize(16);
        current.setText(EscapeSequence.describe(EscapeSequence.parse(sequence)));
        item.addView(current, matchWrap());
        Button record = secondaryButton(theme, "Record a new combination");
        record.setOnClickListener(view -> showSequenceRecorder(forLauncher));
        item.addView(record, matchWrap());
        return item;
    }

    /**
     * Full-screen recorder. The user performs the combination they want on the real corners of the
     * real panel, which is both easier than describing it and the only way to be sure the gesture
     * is comfortable to perform where the tablet is actually mounted.
     */
    private void showSequenceRecorder(boolean forLauncher) {
        recordedZones.clear();
        recordingForWizard = false;
        renderSequenceRecorder(forLauncher);
    }

    /**
     * First start: both escape combinations must exist before anything else, because they are
     * the only way off the kiosk once it locks and there is no compiled-in default to fall back
     * to (see the fields in {@link KioskConfig}). So an install without them gets this wizard
     * instead of a dashboard, and {@link #applyKioskPolicy} declines to pin until it is done.
     *
     * <p>Also shown once after updating an install that predates the wizard: those devices ran
     * on the old fixed defaults, which were never persisted, so they re-record. Deliberate, not
     * an oversight: pre-publication the installed base is the two test devices, and stamping the
     * retired defaults into them as if a user had chosen them would defeat the removal.
     */
    private void showFirstStartWizard() {
        KioskConfig config = KioskConfig.load(this);
        if (config.escapeSequencesConfigured()) {
            // Both combinations arrived while the wizard was up, typed into the web admin of an
            // updated install. Nothing left to record.
            continueAfterFirstStartWizard();
            return;
        }
        wizardVisible = true;
        recorderVisible = false;
        configurationVisible = false;
        publishOperatorScreenState();
        applyKioskPolicy();
        setDashboardFullscreen(true);
        enterImmersiveMode();

        KioskTheme theme = currentTheme();
        LinearLayout page = pageColumn(theme);
        page.addView(pageHeading(theme, "Welcome to Muralis",
                "Two tap combinations before anything else"), matchWrap());

        TextView explain = new TextView(this);
        explain.setTextColor(theme.subtext);
        explain.setTextSize(14);
        explain.setText("Muralis locks this tablet to one page. The only way back out is a "
                + "combination of taps in the corners of the screen, so those are recorded "
                + "first: one that opens Muralis settings, one that exits to the system "
                + "launcher. You will perform each on the real corners, and you can change "
                + "them later in settings. Keep them to yourself, anyone who watches you "
                + "perform one can repeat it.");
        page.addView(explain, matchWrap());

        Button start = primaryButton(theme, "Record the first combination");
        start.setOnClickListener(view -> showWizardRecorder(false));
        page.addView(start, matchWrap());

        setContentView(scrollPage(theme, page));
        currentScreen = this::showFirstStartWizard;
    }

    /**
     * Wizard entry to the recorder: the same screen as {@link #showSequenceRecorder}, but Save
     * advances to the next step or finishes the wizard, and Back returns to the wizard's intro.
     */
    private void showWizardRecorder(boolean forLauncher) {
        recordedZones.clear();
        recordingForWizard = true;
        wizardVisible = false;
        renderSequenceRecorder(forLauncher);
    }

    /**
     * Where the wizard hands over once both combinations exist: the same routing
     * {@link #initializeUserInterface} applies, minus the wizard check that just passed. On a
     * fresh install the dashboard URL is still empty, so this lands on the configuration screen;
     * on an updated install it goes straight back to the dashboard.
     */
    private void continueAfterFirstStartWizard() {
        wizardVisible = false;
        // There is a way out of the kiosk now, so Play may be asked. See startProBilling.
        startProBilling();
        KioskConfig config = KioskConfig.load(this);
        if (config.dashboardUrl.isEmpty()) {
            showConfiguration(config);
        } else {
            showDashboard(config.dashboardUrl);
        }
    }

    /** Draws the recorder from whatever has been tapped so far. See {@link #showSequenceRecorder}. */
    private void renderSequenceRecorder(boolean forLauncher) {
        clearStatusChip();
        recorderVisible = true;
        publishOperatorScreenState();
        recordingForLauncher = forLauncher;
        applyKioskPolicy();
        setDashboardFullscreen(true);
        enterImmersiveMode();

        KioskTheme theme = currentTheme();
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(theme.base);

        int size = dp(ADMIN_ESCAPE_ZONE_DP);
        addCornerTarget(root, theme, Gravity.TOP | Gravity.START, size, "top-left");
        addCornerTarget(root, theme, Gravity.TOP | Gravity.END, size, "top-right");
        addCornerTarget(root, theme, Gravity.BOTTOM | Gravity.START, size, "bottom-left");
        addCornerTarget(root, theme, Gravity.BOTTOM | Gravity.END, size, "bottom-right");
        // Once now for the common case where the window is already attached and the insets are
        // known, and again from the listener for the first-ever render, where they are not yet.
        offsetCornerTargets(root);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            offsetCornerTargets(root);
            return insets;
        });

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER);
        panel.setBackground(theme.outlinedPanel(theme.surface, dp(18), dp(1)));
        int pad = dp(24);
        panel.setPadding(pad, pad, pad, pad);

        if (recordingForWizard) {
            TextView step = new TextView(this);
            step.setText(forLauncher ? "Step 2 of 2" : "Step 1 of 2");
            step.setTextColor(theme.accentAlt);
            step.setTextSize(13);
            step.setGravity(Gravity.CENTER);
            panel.addView(step, matchWrap());
        }

        TextView title = new TextView(this);
        title.setText(forLauncher ? "Exit to the system launcher" : "Open Muralis settings");
        title.setTextColor(theme.text);
        title.setTextSize(22);
        title.setGravity(Gravity.CENTER);
        panel.addView(title, matchWrap());

        TextView hint = new TextView(this);
        hint.setText("Tap the highlighted corners in the order you want. Between "
                + EscapeSequence.MIN_LENGTH + " and " + EscapeSequence.MAX_LENGTH
                + " taps, no pause longer than " + (EscapeSequence.MAX_GAP_MS / 1000) + "s.");
        hint.setTextColor(theme.subtext);
        hint.setTextSize(14);
        hint.setGravity(Gravity.CENTER);
        panel.addView(hint, matchWrap());

        recorderReadout = new TextView(this);
        recorderReadout.setTextColor(theme.ok);
        recorderReadout.setTextSize(18);
        recorderReadout.setGravity(Gravity.CENTER);
        recorderReadout.setText("nothing recorded yet");
        panel.addView(recorderReadout, matchWrap());

        Button save = primaryButton(theme, "Save this combination");
        save.setOnClickListener(view -> saveRecordedSequence());
        panel.addView(save, matchWrap());

        Button clear = secondaryButton(theme, "Start over");
        clear.setOnClickListener(view -> {
            recordedZones.clear();
            updateRecorderReadout();
        });
        panel.addView(clear, matchWrap());

        Button cancel = secondaryButton(theme, recordingForWizard ? "Back" : "Cancel");
        cancel.setOnClickListener(view -> {
            recorderVisible = false;
            if (recordingForWizard) {
                showFirstStartWizard();
            } else {
                showEscapeSequences(KioskConfig.load(this));
            }
        });
        panel.addView(cancel, matchWrap());

        // 460dp was a fixed width, and a phone in portrait is 360dp or less, so the panel and
        // every button in it ran off both edges with no way to reach Save. Capped instead: as wide
        // as it wants up to 460dp, and never wider than the viewport less a margin.
        //
        // Wrapped in a scroller for the other half of the same bug. Six stacked children at
        // WRAP_CONTENT height overflow a short viewport (a phone in landscape) just as surely, and
        // clipping falls on the buttons at the bottom. The corner targets stay reachable either
        // way: taps are matched by coordinate in dispatchTouchEvent, not by which view is on top.
        int margin = dp(16);
        int panelWidth = Math.min(dp(460),
                Math.max(dp(240), dp(getResources().getConfiguration().screenWidthDp) - 2 * margin));
        ScrollView panelScroll = new ScrollView(this);
        panelScroll.addView(panel, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(
                panelWidth, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        root.addView(panelScroll, panelParams);
        setContentView(root);
        // Rebuilt on rotation like every other screen, without clearing what has been tapped so
        // far: see onConfigurationChanged. showSequenceRecorder is the entry point that resets the
        // recording; this one only redraws it.
        currentScreen = () -> renderSequenceRecorder(forLauncher);
        updateRecorderReadout();
    }

    private void addCornerTarget(FrameLayout root, KioskTheme theme, int gravity, int size,
            String label) {
        TextView target = new TextView(this);
        target.setText(label);
        target.setTextColor(theme.onAccent());
        target.setTextSize(12);
        target.setGravity(Gravity.CENTER);
        target.setBackground(theme.panel(theme.accentAlt, dp(14)));
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(size, size);
        params.gravity = gravity;
        int margin = dp(8);
        params.setMargins(margin, margin, margin, margin);
        // The gravity doubles as the tag so offsetCornerTargets can tell which edges this
        // square hangs from without keeping a parallel list of views.
        target.setTag(gravity);
        root.addView(target, params);
    }

    /**
     * Pushes the drawn corner squares clear of the system bars, so the corner a person aims at
     * is a corner that can hear the tap.
     *
     * <p>On a device-owner panel the bars are hidden and this moves nothing. On an ordinary
     * install from Android 15 the platform lays the app out edge to edge, the status bar is drawn
     * over the top band of both top squares, and the system eats every tap in that band: measured
     * on the Pixel 9 Pro XL on 2026-08-29, three taps inside the drawn top-left square at the
     * bar's height recorded nothing while the same taps below it recorded normally. The same
     * class of bug as the drawn-versus-listening drift documented on {@link #cornerZoneAt}, with
     * the platform's own chrome as the cause this time.
     */
    private void offsetCornerTargets(FrameLayout root) {
        Rect overlap = systemBarOverlap();
        int margin = dp(8);
        for (int index = 0; index < root.getChildCount(); index++) {
            View child = root.getChildAt(index);
            if (!(child.getTag() instanceof Integer)) {
                continue;
            }
            int gravity = (Integer) child.getTag();
            boolean top = (gravity & Gravity.VERTICAL_GRAVITY_MASK) == Gravity.TOP;
            boolean start = (gravity & Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK) == Gravity.START;
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) child.getLayoutParams();
            params.setMargins(
                    margin + (start ? overlap.left : 0),
                    margin + (top ? overlap.top : 0),
                    margin + (start ? 0 : overlap.right),
                    margin + (top ? 0 : overlap.bottom));
            child.setLayoutParams(params);
        }
    }

    /**
     * How far the system's tap-eating chrome intrudes into the content view, in content
     * coordinates, all four edges.
     *
     * <p>Zero on a device-owner panel, where the bars are hidden, and zero wherever the window is
     * laid out below the bars, which is every ordinary install before the platform's Android 15
     * edge-to-edge enforcement. The tappable-element insets are asked rather than the bar heights
     * because they answer the actual question: gesture navigation's bottom strip passes taps
     * through and reports zero, a three-button bar eats them and reports its height.
     */
    @SuppressWarnings("deprecation")
    private Rect systemBarOverlap() {
        Rect overlap = new Rect();
        View content = findViewById(android.R.id.content);
        View decor = getWindow().getDecorView();
        android.view.WindowInsets insets = decor.getRootWindowInsets();
        if (content == null || insets == null || content.getWidth() == 0) {
            return overlap;
        }
        int left;
        int top;
        int right;
        int bottom;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.graphics.Insets bars = insets.getInsets(
                    android.view.WindowInsets.Type.tappableElement()
                            | android.view.WindowInsets.Type.displayCutout());
            left = bars.left;
            top = bars.top;
            right = bars.right;
            bottom = bars.bottom;
        } else {
            // Deprecated from API 30 but the only spelling below it, the same both-paths rule as
            // enterImmersiveMode. Hidden bars report zero here too.
            left = insets.getSystemWindowInsetLeft();
            top = insets.getSystemWindowInsetTop();
            right = insets.getSystemWindowInsetRight();
            bottom = insets.getSystemWindowInsetBottom();
        }
        int[] contentOrigin = new int[2];
        int[] decorOrigin = new int[2];
        content.getLocationOnScreen(contentOrigin);
        decor.getLocationOnScreen(decorOrigin);
        // The insets are window-relative. Where the content view already sits below a bar, the
        // subtraction lands at zero and nothing moves.
        overlap.left = Math.max(0, decorOrigin[0] + left - contentOrigin[0]);
        overlap.top = Math.max(0, decorOrigin[1] + top - contentOrigin[1]);
        overlap.right = Math.max(0, contentOrigin[0] + content.getWidth()
                - (decorOrigin[0] + decor.getWidth() - right));
        overlap.bottom = Math.max(0, contentOrigin[1] + content.getHeight()
                - (decorOrigin[1] + decor.getHeight() - bottom));
        return overlap;
    }

    private void recordZone(String zone) {
        if (recordedZones.size() >= EscapeSequence.MAX_LENGTH) {
            Toast.makeText(this, "That is the longest combination allowed",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        recordedZones.add(zone);
        updateRecorderReadout();
    }

    private void updateRecorderReadout() {
        if (recorderReadout == null) {
            return;
        }
        recorderReadout.setText(recordedZones.isEmpty()
                ? "nothing recorded yet"
                : EscapeSequence.describe(recordedZones)
                        + "  (" + recordedZones.size() + " taps)");
    }

    private void saveRecordedSequence() {
        if (!EscapeSequence.isValid(recordedZones)) {
            Toast.makeText(this, "Record between " + EscapeSequence.MIN_LENGTH + " and "
                    + EscapeSequence.MAX_LENGTH + " corner taps first", Toast.LENGTH_LONG).show();
            return;
        }
        KioskConfig config = KioskConfig.load(this);
        String recorded = EscapeSequence.format(recordedZones);
        // Refuse a collision: two identical combinations would make one of the two escapes
        // unreachable, with nothing on screen to explain why.
        String other = recordingForLauncher ? config.settingsSequence : config.launcherSequence;
        if (recorded.equals(other)) {
            Toast.makeText(this, "That is already the other combination, record a different one",
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (recordingForLauncher) {
            config.launcherSequence = recorded;
        } else {
            config.settingsSequence = recorded;
        }
        config.saveEscapeSequences(this);
        recorderVisible = false;
        Toast.makeText(this, "Saved: " + EscapeSequence.describe(recordedZones),
                Toast.LENGTH_LONG).show();
        if (recordingForWizard) {
            if (recordingForLauncher) {
                // Both combinations exist now; the wizard is done and the pin may engage.
                recordingForWizard = false;
                continueAfterFirstStartWizard();
            } else {
                showWizardRecorder(true);
            }
            return;
        }
        showEscapeSequences(KioskConfig.load(this));
    }

    // --- themed building blocks -------------------------------------------------------------

    private void hideKeyboard(View focused) {
        android.view.inputmethod.InputMethodManager manager =
                getSystemService(android.view.inputmethod.InputMethodManager.class);
        if (manager != null && focused != null) {
            manager.hideSoftInputFromWindow(focused.getWindowToken(), 0);
        }
        focused.clearFocus();
    }

    private ScrollView scrollPage(KioskTheme theme, LinearLayout content) {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(theme.base);
        // Tapping the background dismisses the keyboard, which is the other half of having no
        // navigation bar on this screen.
        scroll.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                View focused = getCurrentFocus();
                if (focused instanceof EditText) {
                    hideKeyboard(focused);
                }
            }
            return false;
        });
        scroll.setFillViewport(true);
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        keepFocusedFieldAboveKeyboard(scroll);
        restoreCarriedScroll(scroll);
        return scroll;
    }

    /**
     * Redraws the screen that is already up without losing the reader's place.
     *
     * <p>These screens are rebuilt rather than repainted because every view is coloured at
     * construction, so a palette change cannot be applied to views that already exist. Rebuilding
     * is the right call; discarding the scroll offset with it is not, and it reads as the app
     * reloading itself. Only redraws that mean "same screen, new appearance" come through here.
     * Real navigation does not, because arriving somewhere should start at the top.
     */
    private void redrawInPlace(Runnable redraw) {
        carriedScrollY = currentPageScrollY();
        try {
            redraw.run();
        } finally {
            // Cleared whether or not it was used: a screen that does not scroll, or one reached
            // by a redraw that navigated away instead, must not inherit an offset from this one.
            carriedScrollY = -1;
        }
    }

    /** How far the page on screen is scrolled, or -1 when what is up is not one of these pages. */
    private int currentPageScrollY() {
        View content = findViewById(android.R.id.content);
        if (content instanceof ViewGroup && ((ViewGroup) content).getChildCount() > 0) {
            View top = ((ViewGroup) content).getChildAt(0);
            if (top instanceof ScrollView) {
                return ((ScrollView) top).getScrollY();
            }
        }
        return -1;
    }

    /**
     * Puts a rebuilt page back where the outgoing one was, once it has been laid out.
     *
     * <p>After layout rather than in {@link View#post}: a view runs its queued runnables when it
     * is attached to the window, which happens before the first measure, and a ScrollView whose
     * content has no measured height clamps every offset it is given to zero. The listener takes
     * itself off on its first call, which is the removal the comment in {@link #trackKeyboardInset}
     * is about: these observers belong to the window and outlive the view that registered them.
     */
    private void restoreCarriedScroll(ScrollView scroll) {
        if (carriedScrollY <= 0) {
            return;
        }
        final int offset = carriedScrollY;
        scroll.getViewTreeObserver().addOnGlobalLayoutListener(
                new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        scroll.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                        // ScrollView clamps this to the content it ended up with, so a page that
                        // came back shorter lands at its own end rather than out of range.
                        scroll.scrollTo(0, offset);
                    }
                });
    }

    /**
     * Reports the software keyboard's height whenever it changes.
     *
     * <p><b>Why measuring is necessary at all:</b> Android ignores {@code SOFT_INPUT_ADJUST_RESIZE} on
     * a window carrying {@link WindowManager.LayoutParams#FLAG_FULLSCREEN}, which every Muralis screen
     * does, because a kiosk has no business showing a status bar. The window therefore never shrinks
     * and the keyboard is simply drawn on top of it. The IME does still reduce the window's visible
     * display frame, though, so the gap between the decor height and that frame's bottom is the
     * keyboard height, whether or not the window resized.
     *
     * <p>Both callers use this to give back the space themselves: the configuration screens as scroll
     * padding, the dashboard by shrinking the WebView. Worst in landscape, the orientation a wall panel
     * is fixed in, because the keyboard takes a much larger share of a short screen.
     *
     * @param anchor a view in the hierarchy, used only for its window and lifecycle
     * @param onInset called with the keyboard height in pixels, or 0 when it is closed
     */
    private void trackKeyboardInset(View anchor, java.util.function.IntConsumer onInset) {
        // Remembers the last value so the listener, which fires on every layout pass, does not
        // re-trigger itself by changing layout.
        final int[] applied = {-1};
        final Runnable measure = () -> {
            View decor = getWindow().getDecorView();
            Rect visible = new Rect();
            decor.getWindowVisibleDisplayFrame(visible);
            int inset = Math.max(0, decor.getHeight() - visible.bottom);
            // A navigation bar or display cutout also shrinks the visible frame. Only a gap big enough
            // to be a keyboard counts, so ordinary layout does not gain phantom padding.
            if (inset < dp(MIN_KEYBOARD_INSET_DP)) {
                inset = 0;
            }
            if (inset != applied[0]) {
                applied[0] = inset;
                onInset.accept(inset);
            }
        };
        // Registered on attach and removed on detach. Without the removal these leak: a fresh view is
        // built on every screen change, ViewTreeObserver listeners are not dropped when a view is
        // detached, and the observer belongs to the window rather than the view, so every screen visit
        // would leave another listener firing forever against a dead view.
        final android.view.ViewTreeObserver.OnGlobalLayoutListener layoutListener = measure::run;
        anchor.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View view) {
                view.getViewTreeObserver().addOnGlobalLayoutListener(layoutListener);
            }

            @Override
            public void onViewDetachedFromWindow(View view) {
                view.getViewTreeObserver().removeOnGlobalLayoutListener(layoutListener);
            }
        });
    }

    /**
     * Keeps the focused field visible on Muralis's own forms when the keyboard opens.
     *
     * <p>Turns the measured keyboard height into bottom padding, which restores the scrollable range
     * {@code adjustResize} would have produced, then scrolls the focused field into it. The focus
     * listener covers the second way to end up hidden: moving between fields while the keyboard is
     * already open.
     */
    private void keepFocusedFieldAboveKeyboard(ScrollView scroll) {
        final Runnable revealFocused = () -> {
            View focused = getCurrentFocus();
            if (focused == null) {
                return;
            }
            // Asks for a little more than the field's own height so the next field, and any error text
            // under it, are not left flush against the keyboard.
            scroll.post(() -> focused.requestRectangleOnScreen(
                    new Rect(0, 0, focused.getWidth(), focused.getHeight() + dp(24)), false));
        };
        trackKeyboardInset(scroll, inset -> {
            scroll.setPadding(scroll.getPaddingLeft(), scroll.getPaddingTop(),
                    scroll.getPaddingRight(), inset);
            if (inset > 0) {
                revealFocused.run();
            }
        });
        final android.view.ViewTreeObserver.OnGlobalFocusChangeListener focusListener =
                (oldFocus, newFocus) -> scroll.post(revealFocused);
        scroll.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View view) {
                view.getViewTreeObserver().addOnGlobalFocusChangeListener(focusListener);
            }

            @Override
            public void onViewDetachedFromWindow(View view) {
                view.getViewTreeObserver().removeOnGlobalFocusChangeListener(focusListener);
            }
        });
    }

    /**
     * Lays cards out side by side when there is room, the way Home Assistant's sections view does.
     * A wall tablet in landscape is 1280 px wide; a single column there means enormous text fields
     * and a lot of scrolling for a form that easily fits on one screen.
     */
    private ViewGroup cardGrid(KioskTheme theme, List<View> cards) {
        int columns = getResources().getConfiguration().screenWidthDp >= 720 ? 2 : 1;
        if (columns == 1) {
            LinearLayout single = new LinearLayout(this);
            single.setOrientation(LinearLayout.VERTICAL);
            for (View card : cards) {
                single.addView(card, matchWrap());
            }
            return single;
        }
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout[] lanes = new LinearLayout[columns];
        for (int index = 0; index < columns; index++) {
            lanes[index] = new LinearLayout(this);
            lanes[index].setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams laneParams = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            int gap = dp(7);
            laneParams.leftMargin = index == 0 ? 0 : gap;
            laneParams.rightMargin = index == columns - 1 ? 0 : gap;
            row.addView(lanes[index], laneParams);
        }
        // Round-robin rather than split-in-half: the cards differ a lot in height, and alternating
        // keeps the two lanes closer in length without measuring anything.
        for (int index = 0; index < cards.size(); index++) {
            lanes[index % columns].addView(cards.get(index), matchWrap());
        }
        return row;
    }

    /** Actions sit in a row on a wide screen and stack on a narrow one. */
    private ViewGroup actionRow(List<View> buttons) {
        LinearLayout row = new LinearLayout(this);
        boolean wide = getResources().getConfiguration().screenWidthDp >= 720;
        row.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        for (int index = 0; index < buttons.size(); index++) {
            LinearLayout.LayoutParams params = wide
                    ? new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT);
            params.topMargin = dp(16);
            if (wide && index > 0) {
                params.leftMargin = dp(14);
            }
            row.addView(buttons.get(index), params);
        }
        return row;
    }

    /**
     * A warning banner shown when Muralis is not the device owner, or null when it is.
     *
     * <p>Deliberately a banner and not a hard failure: an un-provisioned install still works as a
     * dashboard, so refusing to start would take away a working panel to punish a setup mistake. It
     * warns and keeps running.
     *
     * <p>Deliberately one short line, in the theme's own warning colour rather than a bespoke one, so
     * it reads as part of the product. The reason a warning is needed at all is that the
     * un-provisioned state is otherwise completely silent: everything looks correct except that the
     * kiosk is not actually locked down. The specific fix depends on how the app was installed and is
     * written to the log rather than the screen, since it is an instruction for whoever has the cable,
     * not for whoever walks past the panel.
     */
    private View provisioningNotice(KioskTheme theme) {
        String installer = installerPackageName();
        Provisioning.Advice advice = Provisioning.adviceForInstaller(installer, isDeviceOwner());
        if (advice == Provisioning.Advice.NONE) {
            return null;
        }
        Log.w(TAG, advice == Provisioning.Advice.PLAY_INSTALL_NOT_PROVISIONED
                ? "Installed from Play (" + installer + ") and not device owner. This cannot be "
                        + "fixed in place: Android only accepts a device owner while no accounts are "
                        + "signed in. Factory reset, then enrol during setup by QR, or run "
                        + "`adb shell dpm set-device-owner "
                        + "org.spazio17.muralis/.KioskDeviceAdminReceiver` before signing in."
                : "Not device owner (installer=" + installer + "). Fix with `adb shell dpm "
                        + "set-device-owner org.spazio17.muralis/.KioskDeviceAdminReceiver`, which "
                        + "requires that no accounts are signed in on the device.");

        TextView notice = new TextView(this);
        notice.setText(getString(R.string.provisioning_warning));
        notice.setTextSize(15f);
        notice.setTextColor(theme.warn);
        notice.setPadding(dp(20), dp(16), dp(20), dp(16));
        // Same rounded plate as every card on this screen, so the warning sits in the design rather
        // than on top of it.
        notice.setBackground(theme.outlinedPanel(theme.surface, dp(12), dp(1)));
        return notice;
    }

    /**
     * Offers the standard "make me the default launcher" route, or null when it is not needed.
     *
     * <p>Two paths, because only one of them is available at a time. As device owner, HOME is claimed
     * silently in {@link KioskDeviceAdminReceiver#pinAsHomeActivity(Context)} and no button is shown
     * at all, which is what a wall panel wants: nobody is standing there to answer a prompt.
     * Without device owner that API is unavailable, so Muralis does what every third-party launcher
     * does and sends the user to Android's own home-app picker.
     *
     * <p>Hidden once Muralis actually is HOME, so the configuration screen does not carry a permanent
     * button for something already done.
     */
    private View defaultLauncherPrompt(KioskTheme theme) {
        // The device-owner half of the javadoc above used to be true only by coincidence: HOME was
        // pinned at enrolment, so by the time this screen existed the resolution check below already
        // said "ours". The pin now happens in applyKioskPolicy, twenty lines before this is built,
        // and whether the check sees it depends on the package manager having applied the policy by
        // then. It does today, synchronously, but a button that is hidden by statement order is not
        // hidden by design. Device owner means the policy pins HOME itself; there is never a prompt.
        if (KioskDeviceAdminReceiver.isDeviceOwner(this)
                || getPackageName().equals(resolvedHomePackage())) {
            return null;
        }
        Button setDefault = secondaryButton(theme, getString(R.string.set_default_launcher));
        setDefault.setOnClickListener(view -> {
            // Lock task would refuse the launch outright, the same trap openSystemLauncher documents.
            releaseForOtherApp();
            try {
                startActivity(new Intent(android.provider.Settings.ACTION_HOME_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (ActivityNotFoundException unavailable) {
                // Some OEM builds bury or omit this screen; say where to go instead of failing mute.
                Toast.makeText(this, R.string.set_default_launcher_unavailable,
                        Toast.LENGTH_LONG).show();
            }
        });
        return setDefault;
    }

    /** The package that currently handles HOME, whoever it is, or null if nothing does. */
    private String resolvedHomePackage() {
        android.content.pm.ResolveInfo resolved = getPackageManager().resolveActivity(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0);
        return resolved == null || resolved.activityInfo == null
                ? null : resolved.activityInfo.packageName;
    }

    /**
     * Which package is rendering the dashboard, and its version, for display.
     *
     * <p>Reported, never judged, and this is now a settled decision with three attempts behind
     * it, so do not add a warning here in any form. A hardcoded version floor was removed on
     * 2026-08-19 as an invented number. On 2026-08-24 a floor with measured evidence, and then a
     * runtime feature probe (CSS.supports checks run inside the engine itself), were both built
     * and both removed the same day, at the user's direction: the probe only worked by knowing
     * which features "the dashboard" needs, and Muralis is dashboard-agnostic, it shows whatever
     * URL the operator configures, so any such list is an assumption about somebody else's page.
     * Keeping the system's browser engine updated is the operator's responsibility; Muralis's
     * whole job here is to state which engine is in use so a person diagnosing a strange-looking
     * page can see it.
     *
     * <p>{@code WebView.getCurrentWebViewPackage()} is API 26, exactly this app's minSdk, so no
     * fallback branch is needed. The package name matters as well as the version: the provider can be
     * Chrome rather than the standalone WebView package, which is the case on this hardware and is
     * surprising enough to be worth showing.
     */
    private String webViewProviderSummary() {
        try {
            android.content.pm.PackageInfo provider = WebView.getCurrentWebViewPackage();
            if (provider == null) {
                return null;
            }
            return provider.packageName + " " + provider.versionName;
        } catch (RuntimeException unavailable) {
            Log.w(TAG, "Could not read the WebView provider version", unavailable);
            return null;
        }
    }

    /** True when Muralis holds device-owner status. Never throws; false when it cannot be determined. */
    private boolean isDeviceOwner() {
        try {
            DevicePolicyManager policy = getSystemService(DevicePolicyManager.class);
            return policy != null && policy.isDeviceOwnerApp(getPackageName());
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    /**
     * Which installer Android recorded for this package, or null for none.
     *
     * <p>{@code getInstallSourceInfo} is API 30; {@code getInstallerPackageName} is deprecated from
     * API 30 but present since API 5 and is the only option on the API 26 MediaPad, so both ship.
     * A sideloaded APK genuinely has no installer recorded, which is not an error and is exactly
     * what {@link Provisioning#classify} treats as SIDELOADED.
     */
    @SuppressWarnings("deprecation")
    private String installerPackageName() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                return getPackageManager().getInstallSourceInfo(getPackageName())
                        .getInstallingPackageName();
            }
            return getPackageManager().getInstallerPackageName(getPackageName());
        } catch (Exception unavailable) {
            // NameNotFoundException cannot really happen for our own package, but an OEM package
            // manager throwing here must not stop the kiosk from starting.
            Log.w(TAG, "Could not determine the install source", unavailable);
            return null;
        }
    }

    /**
     * Version, device and legal information.
     *
     * <p>A separate screen rather than more cards on the configuration page, for the same reason the
     * escape sequences have one: the configuration screen is already six cards of things an operator
     * changes, and none of this is changeable. Built from the same helpers, so it picks up the palette,
     * the card treatment and the keyboard-aware scroll container without restating any of them.
     *
     * <p>Stays inside lock task like every other Muralis screen; {@code applyKioskPolicy} is what keeps
     * the status bar suppressed here.
     */
    private void showAbout() {
        currentScreen = this::showAbout;
        configurationVisible = true;
        recorderVisible = false;
        publishOperatorScreenState();
        applyKioskPolicy();
        KioskTheme theme = currentTheme();
        LinearLayout page = pageColumn(theme);
        page.addView(pageHeading(theme, "About", appVersionSummary()), matchWrap());

        // Two cards rather than one titled "This build": half these facts are about the app and half
        // about the tablet, so a single title could only be vague. Splitting also balances the lanes,
        // which one seven-row card did not.
        LinearLayout appCard = card(theme, getString(R.string.app_name));
        addAboutRow(appCard, theme, "Version", appVersionName());
        addAboutRow(appCard, theme, "Build", Long.toString(appVersionCode()));
        addAboutRow(appCard, theme, "Package", getPackageName());

        LinearLayout deviceCard = card(theme, "This tablet");
        addAboutRow(deviceCard, theme, "Model",
                android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL);
        addAboutRow(deviceCard, theme, "Android",
                android.os.Build.VERSION.RELEASE + " (API " + android.os.Build.VERSION.SDK_INT + ")");
        String engine = webViewProviderSummary();
        addAboutRow(deviceCard, theme, "Rendering engine", engine == null ? "unknown" : engine);
        addAboutRow(deviceCard, theme, "Device owner", isDeviceOwner() ? "yes" : "no");

        LinearLayout creditsCard = card(theme, "Credits");
        addAboutRow(creditsCard, theme, "Eclipse Paho", "MQTT client (EPL/EDL)");
        addAboutRow(creditsCard, theme, "Catppuccin", "Colour palette (MIT)");

        // No explanatory line above these two buttons. They are rendered in-app rather than linked
        // out because a kiosk under lock task has no browser to hand a URL to, which is a fact
        // about the design, not something a reader of the Legal card needs told.
        LinearLayout legalCard = card(theme, "Legal");
        Button privacy = secondaryButton(theme, getString(R.string.privacy_policy_title));
        privacy.setOnClickListener(view -> showLegalDocument(
                R.string.privacy_policy_title, R.raw.privacy));
        legalCard.addView(privacy, matchWrap());
        Button terms = secondaryButton(theme, getString(R.string.terms_title));
        terms.setOnClickListener(view -> showLegalDocument(
                R.string.terms_title, R.raw.terms));
        legalCard.addView(terms, matchWrap());

        // Order chosen for cardGrid's round-robin: the two short cards share a lane, the taller
        // device card takes the other.
        page.addView(cardGrid(theme, java.util.Arrays.<View>asList(
                appCard, deviceCard, creditsCard, legalCard)), matchWrap());

        Button back = primaryButton(theme, "Back to configuration");
        back.setOnClickListener(view -> showConfiguration(KioskConfig.load(this)));
        page.addView(back, matchWrap());

        setContentView(scrollPage(theme, page));
    }

    /**
     * Renders one legal document as in-app text.
     *
     * <p>In-app rather than a link, deliberately: under lock task there is no browser to hand the URL
     * to, and a policy an operator cannot read is not a policy. Play requires the same text at a public
     * URL as well, which is a listing task rather than an app one.
     *
     * <p>Nothing is added to the document here. What this screen shows is the canonical text
     * byte for byte, so the panel, the web admin and the published page cannot say different
     * things; the only line this app contributes is the "also published at" address below, which
     * is about where to find the document rather than part of it. A banner that lived here said
     * the text was a draft placeholder that must be replaced before distribution, and it outlived
     * both halves of that claim: the real text landed on 2026-09-02 and the app is on Play. It is
     * gone rather than reworded, because any sentence about the document belongs in the document,
     * in the muralis-site repo, where one edit reaches every surface at once.
     */
    private void showLegalDocument(int titleRes, int bodyRes) {
        currentScreen = () -> showLegalDocument(titleRes, bodyRes);
        configurationVisible = true;
        recorderVisible = false;
        publishOperatorScreenState();
        applyKioskPolicy();
        KioskTheme theme = currentTheme();
        LinearLayout page = pageColumn(theme);
        // The version, not the app name: the terms themselves say "the version they apply to is shown
        // on the About screen", and a document should say which build it belongs to.
        page.addView(pageHeading(theme, getString(titleRes), appVersionSummary()), matchWrap());

        // The document and the line under it share this width, so they read as one column.
        int documentWidth = getResources().getConfiguration().screenWidthDp >= 720
                ? dp(640) : ViewGroup.LayoutParams.MATCH_PARENT;

        LinearLayout bodyCard = card(theme, "");
        addDocumentBlocks(bodyCard, theme, readRawText(bodyRes));
        // Capped line length. At full width on this 1920 px panel a paragraph ran to roughly 180
        // characters, which is close to unreadable for continuous prose; every other screen in this app
        // is short form and does not have the problem. 640dp lands near 100 characters. Centred, and
        // only capped where there is room to cap it.
        LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(
                documentWidth, ViewGroup.LayoutParams.WRAP_CONTENT);
        bodyParams.gravity = Gravity.CENTER_HORIZONTAL;
        bodyParams.topMargin = dp(16);
        page.addView(bodyCard, bodyParams);

        // Where the public copy of the same document lives (canonically: the site is generated
        // from the same text this screen renders). Plain text rather than a tappable link on
        // purpose: under lock task there is no browser to hand it to, so this is an address to
        // read on another machine, not something to open here.
        TextView published = new TextView(this);
        published.setText(getString(R.string.legal_also_published, getString(
                bodyRes == R.raw.privacy
                        ? R.string.legal_privacy_url : R.string.legal_terms_url)) + ".");
        published.setTextSize(13);
        published.setTextColor(theme.subtext);
        published.setPadding(dp(18), 0, dp(18), 0);
        LinearLayout.LayoutParams publishedParams = new LinearLayout.LayoutParams(
                documentWidth, ViewGroup.LayoutParams.WRAP_CONTENT);
        publishedParams.gravity = Gravity.CENTER_HORIZONTAL;
        publishedParams.topMargin = dp(8);
        page.addView(published, publishedParams);

        Button back = primaryButton(theme, "Back to about");
        back.setOnClickListener(view -> showAbout());
        page.addView(back, matchWrap());

        setContentView(scrollPage(theme, page));
    }

    /**
     * Renders a plain-text document as styled blocks.
     *
     * <p>The text comes from {@code res/raw} rather than a string resource, because aapt collapses
     * newlines in a {@code <string>} and the first version of these screens rendered the whole policy
     * as one unbroken paragraph with the headings absorbed mid-sentence.
     *
     * <p>Blocks are separated by blank lines. A block in ALL CAPS is a heading and gets the accent
     * colour and letter-spacing the rest of the app uses for labels, so a long document is scannable
     * rather than a wall. Everything else is a paragraph.
     */
    private void addDocumentBlocks(LinearLayout parent, KioskTheme theme, String text) {
        for (String block : text.trim().split("\n\\s*\n")) {
            String content = block.trim();
            if (content.isEmpty()) {
                continue;
            }
            TextView view = new TextView(this);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (isDocumentHeading(content)) {
                view.setText(content);
                view.setTextSize(13);
                view.setTextColor(theme.accent);
                view.setLetterSpacing(0.10f);
                params.topMargin = dp(26);
            } else {
                view.setText(content);
                view.setTextSize(14);
                view.setTextColor(theme.text);
                // Read standing at a wall panel rather than sitting at a desk, so a little more air
                // between lines than the forms use.
                view.setLineSpacing(dp(4), 1.0f);
                // Selectable so a clause can be copied out, which is otherwise impossible on a device
                // with no browser.
                view.setTextIsSelectable(true);
                params.topMargin = dp(8);
            }
            parent.addView(view, params);
        }
    }

    /**
     * True for a block that is a section heading.
     *
     * <p>Recognised by being entirely upper case rather than by a marker, so the source documents stay
     * plain readable text that anyone can edit or hand to a reviewer without learning a syntax. The
     * letter test ignores punctuation and requires at least one real letter, so a bullet line or a bare
     * number cannot be mistaken for a heading.
     */
    private static boolean isDocumentHeading(String block) {
        if (block.length() > 60 || block.indexOf('\n') >= 0) {
            return false;
        }
        boolean sawLetter = false;
        for (char c : block.toCharArray()) {
            if (Character.isLetter(c)) {
                sawLetter = true;
                if (Character.isLowerCase(c)) {
                    return false;
                }
            }
        }
        return sawLetter;
    }

    /** Reads a {@code res/raw} text file in full. */
    private String readRawText(int rawRes) {
        try (java.io.InputStream in = getResources().openRawResource(rawRes);
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            return new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException | RuntimeException unavailable) {
            Log.w(TAG, "Could not read document resource", unavailable);
            return "This document could not be loaded.";
        }
    }

    /**
     * One label-beside-value line, laid out as a spec sheet rather than a stack.
     *
     * <p>Stacked label-over-value was the first attempt and looked wrong: seven facts became a very
     * tall column of mostly whitespace while the card beside it was short. These read as a list of
     * values, so the label belongs on the same line and the values line up down the card.
     */
    private void addAboutRow(LinearLayout parent, KioskTheme theme, String label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        TextView labelView = new TextView(this);
        labelView.setText(label);
        labelView.setTextSize(14);
        labelView.setTextColor(theme.subtext);
        row.addView(labelView, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 4f));

        TextView valueView = new TextView(this);
        valueView.setText(value);
        valueView.setTextSize(14);
        valueView.setTextColor(theme.text);
        // Values get the larger share: some, like the rendering-engine string, are long.
        row.addView(valueView, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 6f));

        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.topMargin = dp(10);
        parent.addView(row, rowParams);
    }

    /** "Muralis 0.1.0-staging (build 1)", for headings and the configuration card. */
    private String appVersionSummary() {
        return getString(R.string.app_name) + " " + appVersionName()
                + " (build " + appVersionCode() + ")";
    }

    /**
     * Version read from the installed package rather than {@code BuildConfig}.
     *
     * <p>Same value, but it needs no {@code buildFeatures { buildConfig true }} in Gradle, and it
     * reports what is actually installed rather than what this source was compiled from, which is the
     * more useful answer when diagnosing a panel remotely.
     */
    private String appVersionName() {
        try {
            String name = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            return name == null ? "unknown" : name;
        } catch (Exception unavailable) {
            Log.w(TAG, "Could not read the app version name", unavailable);
            return "unknown";
        }
    }

    @SuppressWarnings("deprecation")
    private long appVersionCode() {
        try {
            android.content.pm.PackageInfo info =
                    getPackageManager().getPackageInfo(getPackageName(), 0);
            // getLongVersionCode is API 28; the int field is the only option on the API 26 MediaPad.
            return android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P
                    ? info.getLongVersionCode() : info.versionCode;
        } catch (Exception unavailable) {
            Log.w(TAG, "Could not read the app version code", unavailable);
            return -1;
        }
    }

    private LinearLayout pageColumn(KioskTheme theme) {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setBackgroundColor(theme.base);
        int pad = dp(28);
        column.setPadding(pad, pad, pad, pad);
        return column;
    }

    private LinearLayout pageHeading(KioskTheme theme, String title, String subtitle) {
        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.HORIZONTAL);
        heading.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView main = new TextView(this);
        main.setText(title);
        main.setTextColor(theme.accent);
        main.setTextSize(30);
        titles.addView(main);
        TextView sub = new TextView(this);
        sub.setText(subtitle);
        sub.setTextColor(theme.subtext);
        sub.setTextSize(15);
        titles.addView(sub);
        heading.addView(titles, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        heading.addView(buildStatusChip(theme), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // The dashboard's overlay posts this tick for itself; a configuration screen has to ask.
        mainHandler.removeCallbacks(overlayTask);
        mainHandler.post(overlayTask);
        return heading;
    }

    /**
     * Battery, network, address and load at a glance, opposite the title. Fed by the same sampler as
     * the dashboard overlay, so it costs nothing beyond reading the last published values, and it
     * answers the questions somebody standing at a wall panel actually has, including the address,
     * which is the one thing you cannot look up remotely because you need it to look anything up
     * remotely.
     *
     * <p>Every row is a value and a glyph, in the same monochrome family as a Pixel status bar, so a
     * bare percentage is never left to be guessed at. See {@link StatusIcon}.
     *
     * <p>Built once and then <em>updated in place</em> on the sampler tick. Rebuilding the views each
     * second also worked, but it relaid out the heading continuously, so the window never reached
     * idle, enough to break {@code uiautomator dump}, and a needless cost on a screen somebody is
     * typing into.
     */
    private View buildStatusChip(KioskTheme theme) {
        LinearLayout chip = new LinearLayout(this);
        chip.setOrientation(LinearLayout.VERTICAL);
        // No panel behind it: the readout is text on the page, with the glyphs in one column down the
        // right-hand edge so both the icons and the ends of the values line up.
        chip.setGravity(Gravity.END);

        batteryIcon = new StatusIcon(this, theme, StatusIcon.Kind.BATTERY);
        batteryValue = chipValue(theme, "--", 14);
        addChipRow(chip, chipRow(dp(17), batteryIcon, batteryValue));

        addressValue = chipValue(theme, "--", 14);
        addChipRow(chip, chipRow(dp(17),
                new StatusIcon(this, theme, StatusIcon.Kind.ADDRESS), addressValue));

        LinearLayout load = new LinearLayout(this);
        load.setOrientation(LinearLayout.HORIZONTAL);
        load.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        ramValue = chipValue(theme, "--", 14);
        load.addView(chipRow(dp(17),
                new StatusIcon(this, theme, StatusIcon.Kind.MEMORY), ramValue));
        cpuValue = chipValue(theme, "--", 14);
        LinearLayout cpu = chipRow(dp(17),
                new StatusIcon(this, theme, StatusIcon.Kind.CPU), cpuValue);
        LinearLayout.LayoutParams cpuParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cpuParams.leftMargin = dp(12);
        load.addView(cpu, cpuParams);
        LinearLayout.LayoutParams loadParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        loadParams.topMargin = dp(2);
        loadParams.gravity = Gravity.END;
        chip.addView(load, loadParams);

        statusChipTheme = theme;
        updateStatusChip();
        return chip;
    }

    /**
     * Writes the latest reading into the chip. Returns false once the screen holding it has gone, so
     * the tick can stop.
     */
    private boolean updateStatusChip() {
        if (batteryValue == null || statusChipTheme == null) {
            return false;
        }
        if (batteryValue.getParent() == null) {
            clearStatusChip();
            return false;
        }
        KioskTheme theme = statusChipTheme;
        SystemStats.RuntimeFacts facts = KioskRuntimeState.lastFacts();
        SystemStats.Sample sample = KioskRuntimeState.lastSample();

        int percent = facts != null && facts.batteryPercent >= 0
                ? (int) Math.round(facts.batteryPercent) : -1;
        boolean charging = facts != null && facts.charging;
        batteryValue.setText(percent < 0 || facts == null
                ? "--" : percent + "% " + SystemStats.chargeStateLabel(facts));
        batteryValue.setTextColor(
                percent >= 0 && percent < StatusIcon.LOW_BATTERY_PERCENT && !charging
                        ? theme.bad : theme.subtext);
        batteryIcon.battery(percent, facts != null && facts.plugged);

        addressValue.setText(facts == null || facts.ipAddress.isEmpty() ? "--" : facts.ipAddress);

        if (sample != null && sample.memTotalKb != SystemStats.UNKNOWN) {
            long used = SystemStats.usedPercent(sample.memUsedKb(), sample.memTotalKb);
            ramValue.setText(used < 0 ? "--" : used + "%");
            cpuValue.setText(SystemStats.percent(sample.cpuBusyPercent));
        }
        return true;
    }

    private void clearStatusChip() {
        batteryValue = null;
        batteryIcon = null;
        addressValue = null;
        ramValue = null;
        cpuValue = null;
        statusChipTheme = null;
    }

    /**
     * Adds a chip line with explicit wrap-content width. Not optional: a vertical LinearLayout hands
     * out MATCH_PARENT by default, and a MATCH_PARENT child does not widen a wrap-content parent, so
     * rows added the lazy way get silently clipped to whatever the other rows happen to need, which
     * is what rendered "AccessToTheIoT" as "AccessToThe".
     */
    private void addChipRow(LinearLayout chip, LinearLayout row) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.gravity = Gravity.END;
        chip.addView(row, params);
    }

    /** One chip line: its value, then its glyph, so every glyph sits in the same right-hand column. */
    private LinearLayout chipRow(int iconSize, StatusIcon icon, TextView value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        row.addView(value);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(iconSize, iconSize);
        iconParams.leftMargin = dp(6);
        row.addView(icon, iconParams);
        return row;
    }

    private TextView chipValue(KioskTheme theme, String text, int textSize) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(textSize);
        view.setTextColor(textSize >= 18 ? theme.text : theme.subtext);
        return view;
    }

    private LinearLayout card(KioskTheme theme, String title) {
        LinearLayout group = new LinearLayout(this);
        group.setOrientation(LinearLayout.VERTICAL);
        // Trying the accent colour for card borders in place of the neutral one, at the user's
        // request, to see whether it reads better than grey from across a room.
        group.setBackground(theme.outlinedPanel(theme.surface, dp(16), dp(1), theme.accent));
        int pad = dp(18);
        group.setPadding(pad, pad, pad, pad);
        // An empty title adds no view at all. Passing "" used to leave a blank heading TextView
        // reserving a line, which read as an unexplained gap at the top of the card.
        if (title != null && !title.isEmpty()) {
            TextView heading = new TextView(this);
            heading.setText(title);
            heading.setTextColor(theme.text);
            heading.setTextSize(18);
            group.addView(heading);
        }
        return group;
    }

    /**
     * A field caption, e.g. "Dashboard URL": a third-level heading, so a step bigger than the
     * informational 13sp lines it used to be indistinguishable from ("Web admin disabled",
     * "Rendering engine: ..."). A parenthetical tail like " (blank keeps the current one)" is
     * itself information rather than heading, so it stays at the informational size.
     */
    private TextView fieldCaption(KioskTheme theme, String label) {
        TextView caption = new TextView(this);
        int aside = label.indexOf(" (");
        if (aside >= 0) {
            SpannableString styled = new SpannableString(label);
            styled.setSpan(new RelativeSizeSpan(13f / 15f), aside, label.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            caption.setText(styled);
        } else {
            caption.setText(label);
        }
        caption.setTextColor(theme.subtext);
        caption.setTextSize(15);
        return caption;
    }

    private void addField(LinearLayout parent, KioskTheme theme, String label, EditText input) {
        TextView caption = fieldCaption(theme, label);
        LinearLayout.LayoutParams captionParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        captionParams.topMargin = dp(14);
        parent.addView(caption, captionParams);
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        inputParams.topMargin = dp(4);
        parent.addView(input, inputParams);
    }

    private EditText themedInput(KioskTheme theme, String value, boolean secret) {
        EditText input = new EditText(this);
        input.setText(value);
        input.setSingleLine(true);
        // With no navigation bar there is no Back key to dismiss the keyboard, so every field
        // offers Done, and tapping anywhere outside a field also closes it.
        input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        input.setOnEditorActionListener((view, actionId, event) -> {
            hideKeyboard(view);
            return true;
        });
        // Every box on this screen holds a machine value: an address, a host, an id, a username,
        // a port. None of it is prose, so the keyboard's prose habits are wrong for all of it,
        // and one of them corrupts the value silently: the panel's own keyboard (SwiftKey on both
        // Huawei test devices) reads a full stop as the end of a sentence, adds a space after it
        // and capitalises what follows, and corrects "mosquitto" to "mosquito" on the way, so
        // "test.mosquitto.org" typed into the broker box arrived as "test. mosquito. org" (Juri,
        // 2026-09-07, and the capture script had documented the same on 2026-08-31). Measured on
        // the phone the same day: TYPE_TEXT_FLAG_NO_SUGGESTIONS alone stops the correcting but
        // not the space after the full stop ("mqtt.user" still came back as "mqtt. user"), so
        // the plain boxes use the visible-password variation, which every keyboard treats as
        // "type exactly this": no predictions, no auto-space, no capitals. The dashboard URL and
        // the broker host set the URI variation on top of this, for the URL keyboard, and ports
        // the number class; both were measured to come back intact too.
        input.setInputType(InputType.TYPE_CLASS_TEXT | (secret
                ? InputType.TYPE_TEXT_VARIATION_PASSWORD
                : InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                        | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS));
        input.setTextColor(theme.text);
        input.setHintTextColor(theme.subtext);
        input.setBackground(theme.outlinedPanel(theme.surfaceAlt, dp(10), dp(1)));
        int padX = dp(14);
        int padY = dp(12);
        input.setPadding(padX, padY, padX, padY);
        return input;
    }

    private CheckBox themedCheckBox(KioskTheme theme, String label, boolean checked) {
        CheckBox box = new CheckBox(this);
        box.setText(label);
        box.setTextColor(theme.text);
        box.setTextSize(15);
        box.setChecked(checked);
        return box;
    }

    private Button primaryButton(KioskTheme theme, String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(16);
        button.setTextColor(theme.onAccent());
        int primaryEdge = KioskTheme.darken(theme.accent, 0.72f);
        button.setBackground(theme.pressable(
                theme.raisedButton(theme.filledButton(theme.accent, dp(12)),
                        primaryEdge, dp(12), dp(3)),
                theme.pressedButton(theme.filledButton(theme.accent, dp(12)),
                        primaryEdge, dp(12), dp(3))));
        button.setPadding(dp(20), dp(14), dp(20), dp(14));
        raiseSlightly(button, dp(3));
        return button;
    }

    /**
     * A small, constant elevation, so every button reads as slightly raised off its card rather
     * than printed flat onto it. {@code setStateListAnimator(null)} matters: the platform Button
     * style otherwise animates elevation on press, which would fight a fixed value and make it
     * flicker back to flat mid-tap.
     */
    private void raiseSlightly(Button button, float elevationPx) {
        button.setStateListAnimator(null);
        button.setElevation(elevationPx);
    }

    private Button secondaryButton(KioskTheme theme, String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(15);
        button.setTextColor(theme.accentAlt);
        button.setBackground(theme.pressable(
                theme.raisedButton(
                        theme.outlinedButton(dp(12), dp(1), theme.accentAlt, theme.surface),
                        theme.accentAlt, dp(12), dp(2)),
                theme.pressedButton(
                        theme.outlinedButton(dp(12), dp(1), theme.accentAlt, theme.surface),
                        theme.accentAlt, dp(12), dp(2))));
        button.setPadding(dp(20), dp(12), dp(20), dp(12));
        // Less than the primary button's, so the hierarchy between them still reads at a glance.
        raiseSlightly(button, dp(2));
        return button;
    }

    private void showDashboard(String url) {
        // Cleared, not set: a WebView reflows itself on rotation, and rebuilding this screen would
        // reload the dashboard every time somebody turned the panel. See onConfigurationChanged.
        currentScreen = null;
        clearStatusChip();
        destroyWebView();
        configurationVisible = false;
        // The dashboard draws no price, so Play stops being asked for one. See ProBilling.askPlay.
        if (proBilling != null) {
            proBilling.detachListener();
        }
        recorderVisible = false;
        wizardVisible = false;
        publishOperatorScreenState();
        setDashboardFullscreen(true);
        // Was setDashboardSystemUiRestricted(true); the shade is now handled by device-owner
        // policy, which applyKioskPolicy keeps tied to whether the lock-task pin is actually held.
        disableStatusBarIfPinned();
        enterImmersiveMode();
        webView = new WebView(this);
        webView.setBackgroundColor(Color.BLACK);
        webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        // Deliberately NOT enabling setOffscreenPreRaster: it exists to keep an *offscreen*
        // WebView rastered and is documented to increase memory use. The kiosk WebView is always
        // the visible surface, so it bought nothing and cost RAM on a device where the renderer is
        // already the largest process and gets killed for memory.
        settings.setUserAgentString(settings.getUserAgentString() + " Muralis/0.1");

        // On a userdebug/eng ROM, expose the renderer to chrome://inspect over adb. A blank or
        // half-rendered Home Assistant dashboard is otherwise almost unfalsifiable from outside the
        // device, which is exactly the situation this project keeps paying for.
        if ("userdebug".equals(Build.TYPE) || "eng".equals(Build.TYPE)) {
            WebView.setWebContentsDebuggingEnabled(true);
        }

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
        webView.setWebViewClient(new KioskWebViewClient());
        webView.setWebChromeClient(new KioskWebChromeClient());

        FrameLayout dashboard = new FrameLayout(this);
        dashboard.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // Give the keyboard its space back by shrinking the WebView, not by scrolling anything here.
        // Padding the FrameLayout reduces the WebView's measured height, which reduces the page's
        // viewport height, and Chromium then reflows and scrolls the focused input into view by itself.
        //
        // Without this, any dashboard with a text field is unusable: reported on the panel while
        // logging in to Home Assistant, where the login form sat under the keyboard and the page could
        // not scroll because, as far as it knew, the viewport was still full height. Not a Home
        // Assistant bug, and not fixable from the page.
        trackKeyboardInset(dashboard,
                inset -> dashboard.setPadding(0, 0, 0, inset));
        blackout = new View(this);
        blackout.setBackgroundColor(Color.BLACK);
        blackout.setVisibility(View.GONE);
        blackout.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                handleUiCommand("display.wake", -1, null);
            }
            return true;
        });
        dashboard.addView(blackout, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        addStatsOverlay(dashboard);
        addNetworkWaitLabel(dashboard);
        setContentView(dashboard);
        applyKioskPolicy();
        // Restored, not reset. This used to hard-code false, which is how both maintenance passes
        // cancelled a deliberate kiosk.stop: the nightly restart came back through here and so did
        // the pressure rebuild, each lighting up a panel somebody had blanked on purpose.
        kioskStopped = KioskConfig.kioskStopped(this);
        if (kioskStopped) {
            blackout.setVisibility(View.VISIBLE);
        }
        // display.visual_off is restored the same way, but only within the boot it was engaged
        // in: the stored value is the boot count, so the blackout survives the nightly restart
        // and the pressure rebuild while a reboot invalidates it by construction, and the panel
        // always boots lit. The reboot rule and restoring the WHOLE state are what separate this
        // from the earlier persisted 1% override, which outlived reboots on its own and came back
        // without its blackout: a panel stranded dark, every brightness command answering
        // accepted while a window attribute outranked the setting they write, and
        // applied_brightness_percent pinned at 1% so Home Assistant's slider sprang back to it.
        // Here the blackout, the dimming and every exit (a tap, display.wake, showing any page)
        // come back together.
        int visualOffBoot = KioskConfig.visualOffBootCount(this);
        if (visualOffBoot >= 0 && visualOffBoot == currentBootCount()) {
            blackout.setVisibility(View.VISIBLE);
            setWindowBrightness(1);
        } else {
            // A fresh dashboard starts with the window at the system setting.
            setWindowBrightness(-1);
        }
        resetLoadTracking();
        mainHandler.removeCallbacks(dashboardSupervisor);
        mainHandler.postDelayed(dashboardSupervisor, SUPERVISOR_INTERVAL_MS);
        mainHandler.removeCallbacks(frozenPageCheckTask);
        mainHandler.postDelayed(frozenPageCheckTask, FROZEN_PAGE_CHECK_INTERVAL_MS);
        mainHandler.removeCallbacks(serverProbeTask);
        mainHandler.postDelayed(serverProbeTask, SERVER_PROBE_INTERVAL_MS);
        loadWhenOnline(url);
    }

    /**
     * Loads the dashboard once the panel has a network, rather than immediately after boot.
     *
     * <p>Muralis is on screen a couple of seconds after boot, which is the behaviour a wall panel
     * wants and is also earlier than Wi-Fi associates. The first load therefore failed with
     * {@code ERR_NAME_NOT_RESOLVED}, and until the ten-second retry came round the panel showed a
     * Chromium error page: a booting panel looked like a broken one. Waiting costs nothing, because
     * there is nothing to show until the network is up anyway.
     *
     * <p>{@link NetworkGate} gives up after a minute and loads regardless, at which point the
     * existing {@code onReceivedError} retry loop takes over exactly as before. So this only ever
     * removes a spurious error page; it can never leave the panel waiting on a network that is
     * never coming.
     */
    private void loadWhenOnline(String url) {
        cancelDashboardGate();
        if (NetworkGate.isOnline(this)) {
            beginLoad();
            webView.loadUrl(url);
            return;
        }
        showNetworkWaitLabel(true);
        dashboardGate = NetworkGate.whenOnline(this, mainHandler, "The dashboard", () -> {
            showNetworkWaitLabel(false);
            if (webView != null && !kioskStopped) {
                beginLoad();
                webView.loadUrl(url);
            }
        });
    }

    private void cancelDashboardGate() {
        if (dashboardGate != null) {
            dashboardGate.cancel();
            dashboardGate = null;
        }
    }

    /**
     * The one thing on screen while the gate above waits.
     *
     * <p>Not developer output: an operator watching a freshly powered panel needs to be able to tell
     * "still coming up" from "hung", and a plain black screen cannot. It says what is being waited
     * for and nothing else, and it is gone the moment the load starts.
     */
    private void addNetworkWaitLabel(FrameLayout dashboard) {
        networkWaitLabel = new TextView(this);
        networkWaitLabel.setText("Waiting for the network");
        networkWaitLabel.setTextColor(KioskTheme.mocha().subtext);
        networkWaitLabel.setTextSize(18);
        networkWaitLabel.setGravity(Gravity.CENTER);
        networkWaitLabel.setClickable(false);
        networkWaitLabel.setFocusable(false);
        networkWaitLabel.setVisibility(View.GONE);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.gravity = Gravity.CENTER;
        dashboard.addView(networkWaitLabel, params);
    }

    private void showNetworkWaitLabel(boolean visible) {
        if (networkWaitLabel != null) {
            networkWaitLabel.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }

    /**
     * Adds the live stats block over the dashboard. It is the last child of the same FrameLayout the
     * WebView lives in, so it needs no overlay permission and no second window. It is left
     * non-clickable so touches fall through to the dashboard underneath and the corner tap gestures
     * keep working.
     */
    private void addStatsOverlay(FrameLayout dashboard) {
        statsOverlay = new TextView(this);
        statsOverlay.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        // Sized to be read from across a room, which is the whole point of an on-glass readout;
        // the colours carry the meaning, so it does not have to be studied.
        statsOverlay.setTextSize(OVERLAY_TEXT_SP);
        statsOverlay.setBackgroundColor(Color.argb(150, 0, 0, 0));
        int padding = dp(10);
        statsOverlay.setPadding(padding, padding, padding, padding);
        statsOverlay.setLineSpacing(dp(2), 1.1f);
        statsOverlay.setShadowLayer(dp(2), 0, 0, Color.BLACK);
        statsOverlay.setClickable(false);
        statsOverlay.setFocusable(false);
        statsOverlay.setText(renderOverlay());
        statsOverlay.setVisibility(KioskConfig.statsOverlayEnabled(this) ? View.VISIBLE : View.GONE);

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        // Top-right: the bottom corners are the nine-tap escape zones.
        params.gravity = Gravity.TOP | Gravity.END;
        params.topMargin = dp(4);
        params.rightMargin = dp(4);
        dashboard.addView(statsOverlay, params);

        mainHandler.removeCallbacks(overlayTask);
        mainHandler.post(overlayTask);
    }

    /**
     * The sampler publishes coloured HTML; TextView renders a useful subset of it. Falls back to the
     * plain-text block if the HTML parser ever returns nothing, so the readout cannot go blank.
     */
    private CharSequence renderOverlay() {
        String html = KioskRuntimeState.overlayHtml();
        if (html.isEmpty()) {
            return KioskRuntimeState.overlayText();
        }
        CharSequence rendered = Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY);
        return rendered == null || rendered.length() == 0
                ? KioskRuntimeState.overlayText() : rendered;
    }

    /**
     * Makes a paid card inert without hiding what it is: every control it holds so far is
     * disabled and dimmed, and one plain line plus a Buy button is appended at full strength.
     * The fields keep their stored values on purpose, since a broker configured before the gate
     * landed (or on another panel) is a real state the operator should see, not a secret.
     *
     * <p>Appending the button after the disable pass is what keeps it tappable; order matters.
     */
    private void lockCardForPro(KioskTheme theme, LinearLayout cardView) {
        for (int i = 0; i < cardView.getChildCount(); i++) {
            View child = cardView.getChildAt(i);
            setEnabledDeeply(child, false);
            // Dimmed, not recolored: alpha grays whatever a control draws, including the themed
            // field backgrounds and the card title, without a second palette to maintain.
            child.setAlpha(0.45f);
        }
        TextView needs = new TextView(this);
        needs.setTextColor(theme.subtext);
        needs.setTextSize(14);
        needs.setText("Needs Muralis Pro.");
        LinearLayout.LayoutParams needsParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        needsParams.topMargin = dp(10);
        cardView.addView(needs, needsParams);
        Button unlock = secondaryButton(theme, "Buy Muralis Pro");
        unlock.setOnClickListener(view -> proBilling.buy(this));
        cardView.addView(unlock, matchWrap());
    }

    private static void setEnabledDeeply(View view, boolean enabled) {
        view.setEnabled(enabled);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                setEnabledDeeply(group.getChildAt(i), enabled);
            }
        }
    }

    /**
     * Actually closes the app, which takes two calls, and both matter. {@code stopService} takes
     * down {@link KioskService}, whose foreground notification and open sockets are what makes a
     * finished activity still read as "Muralis is running"; {@code finishAndRemoveTask} ends this
     * activity and drops the recents card. Reachable only from the ordinary-install Close button,
     * never on a device-owner panel; see the button's comment in showConfiguration.
     */
    private void closeCompletely() {
        stopService(new Intent(this, KioskService.class));
        finishAndRemoveTask();
    }

    private void destroyWebView() {
        mainHandler.removeCallbacks(overlayTask);
        mainHandler.removeCallbacks(dashboardSupervisor);
        mainHandler.removeCallbacks(frozenPageCheckTask);
        mainHandler.removeCallbacks(serverProbeTask);
        cancelDashboardGate();
        statsOverlay = null;
        networkWaitLabel = null;
        if (webView != null) {
            webView.stopLoading();
            // Detach before destroying. Android's contract is that destroy() must follow removal
            // from the view hierarchy; destroying in place leaves the old tree to deliver
            // onDetachedFromWindow to a dead WebView, which crashes or leaks depending on the
            // provider, and the provider here is updated by Play, so "fine today" is not durable.
            // onRenderProcessGone already does it in this order. This path runs on every nightly
            // recycle, every memory-pressure recycle and every kiosk.restart.
            ViewGroup parent = webView.getParent() instanceof ViewGroup
                    ? (ViewGroup) webView.getParent() : null;
            if (parent != null) {
                parent.removeView(webView);
            }
            // Drop the HTTP disk cache while a WebView still exists to do it: the cache is
            // app-global and survives even the nightly process exit, so without this every
            // restart path rebuilt the view on the same stale files. Cache only, deliberately:
            // cookies and WebStorage hold the dashboard's login, and clearing those would turn
            // a routine restart into a logged-out panel.
            webView.clearCache(true);
            webView.destroy();
            webView = null;
        }
        blackout = null;
    }

    private void handleUiCommand(String command, int brightnessPercent, String url) {
        if (command == null) {
            return;
        }
        switch (command) {
            case "kiosk.start":
                kioskStopped = false;
                // Persisted (here and in kiosk.stop/set_url) because the flag used to be process
                // state only: the nightly restart and the pressure rebuild both forgot a
                // deliberate stop and lit the panel back up.
                KioskConfig.edit(this).kioskStopped(false).apply();
                liftVisualOff();
                if (blackout != null) {
                    blackout.setVisibility(View.GONE);
                }
                if (webView != null) {
                    beginLoad();
                    webView.loadUrl(KioskConfig.load(this).dashboardUrl);
                }
                break;
            case "kiosk.set_url":
                kioskStopped = false;
                KioskConfig.edit(this).kioskStopped(false).apply();
                liftVisualOff();
                if (blackout != null) {
                    blackout.setVisibility(View.GONE);
                }
                if (webView == null) {
                    showDashboard(url);
                } else {
                    beginLoad();
                    webView.loadUrl(url);
                }
                break;
            case "kiosk.open_url":
            case "kiosk.home": {
                // One shape for both: the only difference is which URL is loaded, and neither
                // writes anything. kiosk.open_url shows a URL that is deliberately NOT stored, so
                // a restart, a reboot or the nightly clean comes back to the stored dashboard;
                // kiosk.home is the way back on demand.
                String target = "kiosk.home".equals(command)
                        ? KioskConfig.load(this).dashboardUrl
                        : url;
                if (target == null || target.trim().isEmpty()) {
                    // Only reachable for kiosk.home on a panel with no dashboard configured yet:
                    // loading "" would blank a working screen for nothing.
                    Log.w(TAG, "No dashboard URL to show");
                    break;
                }
                // A one-off URL also lifts a kiosk.stop and a visual-off, exactly as
                // kiosk.set_url does: asking for a page is asking to see it.
                kioskStopped = false;
                KioskConfig.edit(this).kioskStopped(false).apply();
                liftVisualOff();
                if (blackout != null) {
                    blackout.setVisibility(View.GONE);
                }
                if (webView == null) {
                    showDashboard(target);
                } else {
                    beginLoad();
                    webView.loadUrl(target);
                }
                break;
            }
            case "kiosk.stop":
                kioskStopped = true;
                KioskConfig.edit(this).kioskStopped(true).apply();
                // A stop supersedes a visual-off: the blackout now belongs to the stop, and the
                // eventual kiosk.start must come back at system brightness, not at 1%.
                liftVisualOff();
                resetLoadTracking();
                if (webView != null) {
                    webView.stopLoading();
                }
                if (blackout != null) {
                    blackout.setVisibility(View.VISIBLE);
                }
                break;
            case "kiosk.reload":
                if (webView != null) {
                    beginLoad();
                    webView.reload();
                }
                break;
            case "kiosk.restart":
                showDashboard(KioskConfig.load(this).dashboardUrl);
                break;
            case "display.visual_off":
                if (blackout != null) {
                    blackout.setVisibility(View.VISIBLE);
                }
                setWindowBrightness(1);
                // Keyed to this boot: survives the nightly restart, never a reboot.
                KioskConfig.recordVisualOffBootCount(this, currentBootCount());
                break;
            case "display.wake":
                if (blackout != null && !kioskStopped) {
                    blackout.setVisibility(View.GONE);
                }
                liftVisualOff();
                break;
            case "display.orientation":
                // The method reads the setting KioskService has already stored, so there is one
                // source of truth rather than a value carried in the broadcast that could
                // disagree with what was saved.
                applyOrientation();
                break;
            case "display.auto_brightness_on":
            case "display.auto_brightness_off":
                // Both directions do the same thing: drop any window override so the system setting,
                // whether the sensor is driving it or the operator is, is what reaches the panel.
                // Neither re-applies a stored level any more; brightness lives in the system setting.
                setWindowBrightness(-1);
                break;
            // No display.brightness case: KioskService writes Settings.System.SCREEN_BRIGHTNESS
            // directly now, so there is nothing for the window to do. Setting a per-window override
            // here is what used to pin the panel above automatic mode indefinitely.
            default:
                break;
        }
    }

    /**
     * The device's boot counter, or -1 when it cannot be read. {@code BOOT_COUNT} is public API
     * since 24 and always present on this app's minSdk; the -1 is fail-soft, and it fails toward
     * a lit panel, never toward restoring a blackout that may belong to a previous boot.
     */
    private int currentBootCount() {
        try {
            return Settings.Global.getInt(getContentResolver(), Settings.Global.BOOT_COUNT);
        } catch (Settings.SettingNotFoundException missing) {
            Log.w(TAG, "BOOT_COUNT unavailable; a visual-off will not survive a restart");
            return -1;
        }
    }

    /**
     * Lifts display.visual_off entirely: the persisted flag and the window dimming, letting the
     * system brightness apply again rather than restoring a remembered level, because which level
     * is correct is the system's business now. Called by display.wake and by everything that
     * shows a page (kiosk.start/set_url/open_url/home), asking for a page is asking to see it,
     * the same rule those commands already apply to kiosk.stop; and by kiosk.stop itself, so a
     * stopped panel that is later started comes back at system brightness rather than at the 1%
     * a visual-off left in force.
     */
    private void liftVisualOff() {
        KioskConfig.recordVisualOffBootCount(this, -1);
        setWindowBrightness(-1);
    }

    /**
     * Applies, or clears, this window's brightness override.
     *
     * <p>The applied value is also persisted, and that is not redundant with the stored
     * {@code brightness_percent}: that one is the last level an operator *chose*, kept so automatic
     * mode can be switched back off and land where it was. This one is whether an override is *in
     * force right now*, which is what any remote surface needs in order to report the truth. Confusing
     * the two made {@code /api/stats} claim 20% while the panel sat at 60%.
     *
     * <p>A shared preference rather than a field, because the reader is {@link KioskService}, in the
     * same process but with no handle on this activity's window.
     */
    private void setWindowBrightness(int percent) {
        WindowManager.LayoutParams attributes = getWindow().getAttributes();
        attributes.screenBrightness = percent < 0
                ? WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                : Math.max(0.01f, Math.min(1.0f, percent / 100.0f));
        getWindow().setAttributes(attributes);
        KioskConfig.storageContext(this).getSharedPreferences("kiosk_runtime", MODE_PRIVATE)
                .edit()
                .putInt(KioskService.APPLIED_BRIGHTNESS_KEY, percent < 0 ? -1 : percent)
                .apply();
    }

    /**
     * Records that the load in flight failed, and makes a retry due one backoff from now.
     *
     * <p>Deliberately does not reload anything itself. A failure can be reported more than once for a
     * single load, and from callbacks that arrive in an order this class does not control; recording a
     * time is idempotent, issuing a load is not.
     */
    private void recordLoadFailure(String description) {
        KioskRuntimeState.recordPageError(description);
        recovery.recordLoadFailure(android.os.SystemClock.uptimeMillis());
        // A dashboard going down is exactly the kind of thing worth knowing about before the next
        // scheduled telemetry tick, which can now be as much as five minutes away.
        KioskService.publishTelemetrySoon(this);
    }

    /**
     * The recovery clock's tick, every {@link #SUPERVISOR_INTERVAL_MS}. All decisions are
     * {@link RecoveryPolicy}'s; this method only supplies the clock, the preconditions that need
     * Android (a network, a configured URL), and the WebView. The preconditions are checked only
     * once an attempt is actually due, because {@code KioskConfig.load} decrypts three secrets and
     * has no business running twice a second, and a blocked attempt stays due so it goes out the
     * moment they clear, which is what a router reboot used to not look like from the panel.
     */
    private void superviseDashboard() {
        if (kioskStopped || webView == null) {
            return;
        }
        long now = android.os.SystemClock.uptimeMillis();
        String hung = recovery.checkHungLoad(now);
        if (hung != null) {
            Log.w(TAG, "Dashboard " + hung + "; retrying");
            KioskRuntimeState.recordPageError(hung);
            KioskService.publishTelemetrySoon(this);
        }
        if (!recovery.retryDue(now) || !NetworkGate.isOnline(this)) {
            return;
        }
        String url = KioskConfig.load(this).dashboardUrl;
        if (url.isEmpty()) {
            return;
        }
        dashboardRetries++;
        Log.i(TAG, "Dashboard retry " + dashboardRetries + ": " + url);
        recovery.issueLoad(now);
        resetServerProbeTracking();
        // loadUrl rather than reload(): reload() re-runs the last request, which after an error is the
        // request that produced the cached error page. loadUrl always asks for the configured
        // dashboard, which is what kiosk.restart does and is known to work.
        webView.loadUrl(url);
    }

    /** Forgets any pending recovery and any load in flight. Nothing is loading after this. */
    private void resetLoadTracking() {
        recovery.reset();
        resetServerProbeTracking();
    }

    /**
     * Marks a load as starting now, at every site that asks the WebView to load something.
     *
     * <p>Not left to {@code onPageStarted}, deliberately. That callback fires when the WebView starts
     * receiving a page, so for the failure the hung-load timeout exists to catch, a server that
     * accepts the connection and then never answers, it may not fire at all. Arming the clock from the
     * callback would mean the one case nothing else notices is also the one case the timeout misses.
     */
    private void beginLoad() {
        recovery.beginLoad(android.os.SystemClock.uptimeMillis());
        // The probe state too: this load's outcome belongs to the load-failure path, and an outage
        // observed before it would otherwise make the new page's first healthy probe read as a
        // recovery and demand a second reload.
        resetServerProbeTracking();
    }

    private final class KioskWebViewClient extends WebViewClient {
        /**
         * Refuses navigation to anything that is not a web page.
         *
         * <p>Only non-web schemes are blocked, nothing else: intent://, tel:, market: and their
         * kind hand control to another app or to Android's chooser, which on a kiosk is an escape
         * hatch a dashboard link (or an injected one, this WebView runs arbitrary configured
         * pages with JavaScript on) must not be able to open. Every http(s) navigation is
         * allowed, deliberately: Muralis is dashboard-agnostic, dashboards legitimately navigate
         * across hosts, and confining them to one origin was considered and refused with the
         * user on 2026-08-24 for exactly that reason.
         */
        @Override
        public boolean shouldOverrideUrlLoading(WebView view,
                android.webkit.WebResourceRequest request) {
            String scheme = request.getUrl() != null ? request.getUrl().getScheme() : null;
            if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
                return false;
            }
            Log.w(TAG, "Blocked dashboard navigation to a non-web scheme: " + scheme);
            return true;
        }

        /**
         * Notes when the load in flight began, so {@link #superviseDashboard()} can catch one that
         * never finishes: a server that accepts the connection and then does not answer, which is a
         * normal shape for a reverse proxy whose backend is restarting. {@code onReceivedError} never
         * fires for it, because at the network level nothing failed.
         *
         * <p>Records the time and nothing else. It used to cancel and repost the shared retry handle,
         * which is precisely what stopped the panel ever recovering; see {@link #dashboardSupervisor}.
         * It also deliberately does not clear the failure marker, because this callback can arrive
         * after the error for the same load.
         */
        @Override
        public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
            recovery.pageStarted(android.os.SystemClock.uptimeMillis());
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            // The policy decides whether this finish is a success or the error page of a failure
            // reported moments ago; only a genuine success is recorded as one.
            if (recovery.pageFinished(android.os.SystemClock.uptimeMillis())) {
                KioskRuntimeState.recordPageFinished(url);
            }
            // Asked whatever the policy decided: the icons sit over what is actually on the
            // glass, success or not.
            probePageLuminance(view);
        }

        @Override
        public void onReceivedError(
                WebView view, WebResourceRequest request, WebResourceError error) {
            if (request.isForMainFrame()) {
                String description = error.getErrorCode() + " " + error.getDescription();
                Log.w(TAG, "Dashboard load failed: " + description + " " + request.getUrl());
                recordLoadFailure(description);
            }
        }

        @Override
        public void onReceivedHttpError(
                WebView view, WebResourceRequest request, WebResourceResponse response) {
            // Without this an HTTP 502 from a reverse proxy renders as a blank page and logs
            // nothing at all, which is indistinguishable from a hung renderer.
            if (request.isForMainFrame()) {
                String description = "HTTP " + response.getStatusCode();
                Log.w(TAG, "Dashboard " + description + " for " + request.getUrl());
                // An HTTP error status is the *normal* way a dashboard goes away: a reverse proxy
                // answers 502 or 503 for as long as its backend is restarting, which is exactly what a
                // Home Assistant update looks like from here.
                recordLoadFailure(description);
            }
        }

        @Override
        public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            if (view != webView) {
                return true;
            }
            KioskRuntimeState.recordRendererDeath();
            KioskService.publishTelemetrySoon(KioskActivity.this);
            Log.w(TAG, "WebView renderer gone, crashed=" + detail.didCrash()
                    + " priorityAtExit=" + detail.rendererPriorityAtExit()
                    + " deaths=" + KioskRuntimeState.rendererDeaths());
            webView = null;
            mainHandler.post(() -> {
                if (view.getParent() instanceof ViewGroup) {
                    ((ViewGroup) view.getParent()).removeView(view);
                }
                view.destroy();
                if (!isFinishing() && !isDestroyed() && !kioskStopped) {
                    showDashboard(KioskConfig.load(KioskActivity.this).dashboardUrl);
                    // After showDashboard, which resets the load bookkeeping this belongs to. The
                    // supervisor now holds that load to the tighter renderer-death bound instead
                    // of the minute a merely-slow dashboard is owed, because a panel that already
                    // knows its renderer died should not spend that minute showing nothing.
                    recovery.markLoadFollowsRendererDeath();
                }
            });
            return true;
        }
    }

    /**
     * Surfaces page errors into logcat. Only errors: logging every console message was invaluable
     * while hunting the cause of a slow dashboard, but a live Home Assistant frontend chatters
     * constantly and writing all of it to logcat is pure overhead once the answer is known.
     */
    private static final class KioskWebChromeClient extends WebChromeClient {
        @Override
        public boolean onConsoleMessage(ConsoleMessage message) {
            if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                // Logged but deliberately NOT recorded as the kiosk's last error: a live Home
                // Assistant dashboard emits console errors routinely, and letting them fill that
                // slot would keep the overlay permanently red and hide a real load failure.
                Log.w(TAG, "console error: " + message.message() + " ("
                        + message.sourceId() + ":" + message.lineNumber() + ")");
            }
            return true;
        }
    }

    /**
     * Immersive-sticky hides the bars but its documented behaviour is to draw them back
     * transiently on an edge swipe, which is exactly what still happened on the dashboard even
     * with lock-task mode blanking the status bar. FLAG_FULLSCREEN removes the status bar at the
     * window level, so there is nothing left to reveal.
     */
    /**
     * Turns the panel to the stored orientation: a fixed landscape or portrait, or "auto", which
     * follows the accelerometer around all four ways up until the operator fixes one, the same
     * shape auto-brightness has with the light sensor.
     *
     * <p>Applies unconditionally, not behind the device-owner gate the immersive chrome sits behind:
     * a wall panel is mounted one way whether or not it is provisioned, and an operator who picks
     * an orientation means it on any install.
     *
     * <p>The fixed choices are the *sensor* variants rather than truly fixed ones, matching the
     * manifest's own default: a panel screwed to the wall the other way up then still renders the
     * right way round, and neither variant lets the dashboard flip between landscape and portrait
     * on its own, which is the behaviour a wall mount actually wants.
     *
     * <p>Cheap to call repeatedly. Android ignores a request for the orientation already in force,
     * and {@code configChanges} in the manifest already covers {@code orientation|screenSize}, so a
     * change rotates the window without recreating the activity or reloading the dashboard.
     */
    private void applyOrientation() {
        String orientation = KioskConfig.orientationOf(this);
        int request;
        switch (orientation) {
            case KioskConfig.ORIENTATION_PORTRAIT:
                request = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT;
                break;
            case KioskConfig.ORIENTATION_LANDSCAPE:
                request = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE;
                break;
            default:
                // Auto. FULL_SENSOR is the four-way follow; a device with no accelerometer has
                // nothing to follow, so the stored default falls back to the fixed landscape a
                // wall panel would have had anyway rather than to a wrong reading.
                request = KioskService.hasAccelerometer(this)
                        ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
                        : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE;
                break;
        }
        setRequestedOrientation(request);
    }

    private void setDashboardFullscreen(boolean fullscreen) {
        // Same gate as enterImmersiveMode, and needed separately: FLAG_FULLSCREEN removes the status
        // bar at the window level, so leaving it set would keep the bar gone on an ordinary install
        // no matter what the immersive flags said.
        if (fullscreen && !isDeviceOwner()) {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            return;
        }
        if (fullscreen) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        }
    }

    /**
     * Hides both system bars, by whichever mechanism this Android version actually has.
     *
     * <p><b>Both paths ship on purpose.</b> {@code SYSTEM_UI_FLAG_*} and
     * {@code setSystemUiVisibility} are deprecated from API 30, while
     * {@link android.view.WindowInsetsController} does not exist before API 30. minSdk 26 with
     * targetSdk 36 therefore spans both, and the interim MediaPad is API 26, so the deprecated path
     * is not legacy here, it is the only one that device will ever run. Deleting either branch
     * breaks one end of the supported range.
     */
    @SuppressWarnings("deprecation")
    private void enterImmersiveMode() {
        // Kiosk chrome only when this app is actually running a kiosk. Hiding the bars on an
        // ordinary install, where lock task never engages and Home and Overview work normally,
        // bought nothing and cost the app its manners: a plain Play install looked broken, which is
        // exactly the impression a closed-test tester forms in the first ten seconds. Checked here
        // rather than at the six call sites, and at call time rather than once at startup, so
        // granting device-owner status to an already-running app takes effect on the next resume.
        if (!isDeviceOwner()) {
            showSystemBars();
            return;
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            android.view.WindowInsetsController insets =
                    getWindow().getInsetsController();
            if (insets != null) {
                getWindow().setDecorFitsSystemWindows(false);
                insets.hide(android.view.WindowInsets.Type.systemBars());
                // BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE is the modern spelling of
                // IMMERSIVE_STICKY: a swipe still reveals a transient bar, which is exactly the
                // limitation documented on the deprecated path, and the reason onBackPressed is
                // inert rather than relying on the bar being absent.
                insets.setSystemBarsBehavior(android.view.WindowInsetsController
                        .BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                return;
            }
        }
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    /**
     * Gives the status and navigation bars back, the exact inverse of {@link #enterImmersiveMode()}.
     *
     * <p>Both version paths again, for the same reason: the flags and the insets controller are the
     * only mechanisms their respective halves of the supported range have. This runs on every
     * non-device-owner install, so it is a normal path rather than a fallback.
     */
    @SuppressWarnings("deprecation")
    private void showSystemBars() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            android.view.WindowInsetsController insets = getWindow().getInsetsController();
            if (insets != null) {
                getWindow().setDecorFitsSystemWindows(true);
                insets.show(android.view.WindowInsets.Type.systemBars());
                return;
            }
        }
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        applyBarIconContrast();
    }

    /**
     * Asks the loaded page how light its background is, so the status bar icons can be flipped
     * dark over a light dashboard. Without this an ordinary install showing a light page renders
     * the bar white on white, clock and icons gone, only the battery fill surviving; found on the
     * Pixel 9 Pro XL over the Home Assistant demo, 2026-08-29.
     *
     * <p>The page's computed background colour is the honest source: this app does not choose
     * the dashboard's palette and must not guess it from a URL. Body first, the document root as
     * the fallback for pages whose body is transparent, and no answer at all leaves the icons
     * where they are.
     */
    private void probePageLuminance(WebView view) {
        if (isDeviceOwner()) {
            // The panel hides its bars; there are no icons to contrast.
            return;
        }
        view.evaluateJavascript(PAGE_LUMINANCE_PROBE, result -> {
            double luminance;
            try {
                luminance = Double.parseDouble(result);
            } catch (NumberFormatException | NullPointerException unanswered) {
                return;
            }
            if (luminance < 0) {
                return;
            }
            dashboardPageIsLight = luminance >= 128;
            applyBarIconContrast();
        });
    }

    /**
     * Dark status and navigation icons over a light dashboard, the default light icons
     * everywhere else. The app's own screens are dark by design, so only the WebView, the one
     * surface whose colour this app does not choose, ever earns the flip.
     *
     * <p>Both API paths for the same reason as {@link #enterImmersiveMode}: the flags are all
     * that exists below API 30, the controller is the only non-deprecated spelling from it.
     */
    @SuppressWarnings("deprecation")
    private void applyBarIconContrast() {
        if (isDeviceOwner()) {
            return;
        }
        boolean dashboardShowing = !configurationVisible && !recorderVisible && !wizardVisible;
        boolean darkIcons = dashboardShowing && dashboardPageIsLight;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.view.WindowInsetsController insets = getWindow().getInsetsController();
            if (insets != null) {
                int mask = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                insets.setSystemBarsAppearance(darkIcons ? mask : 0, mask);
                return;
            }
        }
        View decor = getWindow().getDecorView();
        int visibility = decor.getSystemUiVisibility();
        int flags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        decor.setSystemUiVisibility(darkIcons ? visibility | flags : visibility & ~flags);
    }

    /**
     * The notification {@link KioskService} posts needs a runtime grant from API 33.
     *
     * <p>A denial is survivable and must stay that way: the foreground service still runs, the
     * notification is just not shown, which costs the "return to Muralis" tap target the escape-hatch
     * toast mentions. Not asked for below API 33, where the permission does not exist.
     */
    private void requestNotificationPermissionIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
            return;
        }
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return;
        }
        // A device owner grants this to itself without a dialog, in
        // KioskService.grantOwnRuntimePermissions(). Prompting is the un-provisioned fallback, and on
        // a wall panel with nobody in front of it a dialog is a hang rather than a question, so it is
        // worth saying in the log which of the two paths this device is on.
        DevicePolicyManager policy = getSystemService(DevicePolicyManager.class);
        if (policy != null && policy.isDeviceOwnerApp(getPackageName())) {
            Log.i(TAG, "POST_NOTIFICATIONS not granted yet; device-owner auto-grant should cover "
                    + "it on the next service start rather than prompting");
            return;
        }
        try {
            requestPermissions(new String[] {android.Manifest.permission.POST_NOTIFICATIONS}, 1);
        } catch (RuntimeException refused) {
            Log.w(TAG, "Could not ask for POST_NOTIFICATIONS", refused);
        }
    }

    /**
     * Re-hides the bars the moment the system reports them visible again.
     *
     * <p>Every flag combination Android 10 offers lets an edge swipe reveal a bar; the difference
     * is only whether it stays. Watching for the change and immediately reasserting the flags is
     * the one approach that returns the screen to clean without waiting for a timeout.
     */
    private void watchForRevealedSystemBars() {
        getWindow().getDecorView().setOnSystemUiVisibilityChangeListener(visibility -> {
            // Third place the same gate is needed, and the easiest to miss: without it this watcher
            // would drag the bars back off screen 400ms after every reveal on an ordinary install,
            // undoing showSystemBars() and making the bars flicker instead of simply staying.
            if (!isDeviceOwner()) {
                return;
            }
            if ((visibility & View.SYSTEM_UI_FLAG_FULLSCREEN) != 0) {
                return;
            }
            mainHandler.removeCallbacks(rehideBarsTask);
            // A short delay, because reasserting inside the callback is ignored while the system
            // is still animating the bar in.
            mainHandler.postDelayed(rehideBarsTask, 400L);
        });
    }

    private final Runnable rehideBarsTask = new Runnable() {
        @Override
        public void run() {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            enterImmersiveMode();
        }
    };

    /**
     * Puts the dashboard into lock-task mode with every system-bar feature switched off.
     *
     * <p><b>Lock task alone does not remove the bars.</b> Measured on this tablet 2026-08-19: with
     * device owner active and {@code LOCK_TASK_FEATURE_NONE} applied, an edge swipe still produced a
     * navigation bar with a <em>working</em> Back button, because Android has no lock-task feature
     * flag for Back: the flags cover Home, Overview, notifications, system info, global actions and
     * keyguard, and nothing else. Three things are therefore needed together: this policy (kills
     * Home and Overview), {@code policy_control=immersive.full} where the permission exists (stops
     * the bars being drawn at all), and an inert {@link #onBackPressed} (works everywhere, including
     * an app-only build).
     *
     * <p>Silently does nothing unless Muralis has been provisioned as device owner, so an
     * un-provisioned tablet keeps working exactly as before.
     */
    private void applyKioskPolicy() {
        DevicePolicyManager policy = getSystemService(DevicePolicyManager.class);
        if (policy == null || !policy.isDeviceOwnerApp(getPackageName())) {
            return;
        }
        ComponentName admin = KioskDeviceAdminReceiver.componentName(this);
        try {
            policy.setLockTaskPackages(admin, lockTaskPackages());
            // setLockTaskFeatures and LOCK_TASK_FEATURE_NONE are both API 28. The interim MediaPad
            // is API 26, so this branch is not a courtesy for old devices, it is the path the only
            // current test device takes.
            //
            // Defined API 26/27 fallback, decided rather than discovered on hardware: do nothing
            // here and rely on bare startLockTask() below, which exists back to API 21. What that
            // costs, precisely: startLockTask() blocks Home and Overview by itself, so the
            // *behaviour* of LOCK_TASK_FEATURE_NONE is nearly matched. What is missing is the
            // ability to mask the remaining features individually, and on API 26 lock task also
            // shows a "screen is pinned" toast the newer API suppresses. Both are cosmetic. The
            // shade is still handled by setStatusBarDisabled, which is API 23, and Back is still
            // handled by the inert onBackPressed, which needs no API at all. So no capability that
            // matters to this kiosk is actually lost below API 28.
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                policy.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_NONE);
            } else {
                Log.i(TAG, "API " + android.os.Build.VERSION.SDK_INT + " predates "
                        + "setLockTaskFeatures; using bare startLockTask, which still blocks Home "
                        + "and Overview");
            }
            // Survives a data wipe, unlike `locksettings set-disabled`, so a re-provisioned tablet
            // still boots straight to the dashboard.
            policy.setKeyguardDisabled(admin, true);
        } catch (SecurityException notOwner) {
            Log.w(TAG, "Device-owner policy refused; kiosk hardening unavailable", notOwner);
            return;
        }
        // No recorded way out, no pin and no HOME. The escape combinations have no compiled-in
        // default any more (see KioskConfig), so until the first-start wizard has recorded both, a
        // pinned screen would be a bricked panel: the tap handler matches nothing and Back is inert
        // by design.
        //
        // Becoming the device's persistent HOME sits behind the same gate, and for the same reason
        // rather than a related one. It used to run above, unconditionally, and the admin receiver
        // ran it earlier still, during QR enrolment, before the app had ever been opened. That is
        // what turned a bad first launch into a wiped tablet on 2026-09-03: the Play-signed 0.4.5 on
        // a panel with no Google account met Google's own injected licence check, which finished
        // KioskActivity about a second after every launch, and because Muralis was already the only
        // HOME, Android started it straight back into the same wall. The wrapper that did it is
        // switched off now, but this guard is not about that wrapper. Anything at all can go wrong
        // before an operator has finished setting a panel up, and while HOME still belongs to the
        // shipped launcher, every one of those is a bad afternoon rather than a factory reset.
        //
        // The rule, then: Muralis does not make itself the only way out of the device before the
        // operator has a way out of Muralis. Everything above still applies, the lock-task
        // allowlist stays current, and HOME, the safe-mode block and the pin all engage on the
        // first applyKioskPolicy after the wizard finishes. The status bar is left alone for the same reason
        // disableStatusBarIfPinned exists: it only ever acts once the pin is actually held.
        if (!KioskConfig.load(this).escapeSequencesConfigured()) {
            return;
        }
        // Only claimed when it is not already ours. applyKioskPolicy runs from onResume, and
        // addPersistentPreferredActivity appends rather than replaces, so calling it unconditionally
        // added an entry to the package manager's persistent-preferred list on every resume and grew
        // package-restrictions.xml without bound. The guard also keeps the normal case free: this is
        // needed once, after ownership is granted to an already-running app, which is what happened
        // on the MediaPad where lock task only engaged after a restart.
        if (!getPackageName().equals(resolvedHomePackage())) {
            KioskDeviceAdminReceiver.pinAsHomeActivity(this);
        }
        // Safe mode starts the device with every installed app disabled: no Muralis, no lock task,
        // no service. On a finished panel that is an escape needing neither the combination nor a
        // cable, so the device owner blocks it. Behind this gate for the same reason HOME is: until
        // the wizard has recorded a way out, safe mode IS one, and blocking it earlier, which the
        // service used to do at every boot, bought nothing and cost a recovery route. A user
        // restriction persists across reboots, so setting it on the first resume after the wizard
        // covers every later boot; it is idempotent, so re-setting it on every resume costs one
        // binder call and nothing else. DISALLOW_SAFE_BOOT is API 23, so no version gate.
        try {
            policy.addUserRestriction(admin, android.os.UserManager.DISALLOW_SAFE_BOOT);
        } catch (SecurityException | IllegalArgumentException refused) {
            Log.w(TAG, "Could not block safe-mode boot", refused);
        }
        ActivityManager activityManager = getSystemService(ActivityManager.class);
        if (activityManager != null
                && activityManager.getLockTaskModeState()
                        == ActivityManager.LOCK_TASK_MODE_NONE) {
            try {
                startLockTask();
            } catch (IllegalStateException | IllegalArgumentException notNow) {
                // Thrown when the activity is not resumed, which happens because this is also
                // called while the configuration screen is being built during onCreate. The pin is
                // re-attempted from onResume, so losing this attempt costs nothing, whereas an
                // uncaught throw here restarts a persistent app and blinks the dashboard out.
                Log.w(TAG, "Lock task could not start yet; will retry on resume", notNow);
            }
        }
        // The status bar is disabled only once the pin is actually in place, and never before.
        // Disabling it unconditionally is how a dead bar ended up stranded on Trebuchet's screen:
        // a resume that raced the hand-over re-disabled the bar, startLockTask did not take, and
        // the policy, which only responds inside lock task, could no longer be undone from here.
        disableStatusBarIfPinned();
        mainHandler.postDelayed(this::disableStatusBarIfPinned, LOCK_TASK_SETTLE_MS);
    }

    /**
     * Keeps one invariant: the status bar is disabled if and only if Muralis holds the lock-task pin.
     * Called again shortly after {@code startLockTask()} because the pin is not always in place by
     * the time that call returns.
     */
    private void disableStatusBarIfPinned() {
        ActivityManager activityManager = getSystemService(ActivityManager.class);
        boolean pinned = activityManager != null
                && activityManager.getLockTaskModeState() != ActivityManager.LOCK_TASK_MODE_NONE;
        setStatusBarDisabled(pinned);
    }

    /**
     * Disables the status bar as device-owner policy. Applied on every Muralis screen, not just the
     * dashboard: the configuration screen used to release it, which left a fully working shade,
     * toggles, notifications and a route into Android Settings, one swipe from a kiosk.
     */
    private void setStatusBarDisabled(boolean disabled) {
        DevicePolicyManager policy = getSystemService(DevicePolicyManager.class);
        if (policy == null || !policy.isDeviceOwnerApp(getPackageName())) {
            return;
        }
        try {
            policy.setStatusBarDisabled(KioskDeviceAdminReceiver.componentName(this), disabled);
        } catch (SecurityException notOwner) {
            Log.w(TAG, "Status-bar policy refused", notOwner);
        }
    }

    /** Drops the lock-task pin so another app can legitimately take the foreground. */
    private void releaseKioskPolicy() {
        DevicePolicyManager policy = getSystemService(DevicePolicyManager.class);
        if (policy == null || !policy.isDeviceOwnerApp(getPackageName())) {
            return;
        }
        ActivityManager activityManager = getSystemService(ActivityManager.class);
        if (activityManager != null
                && activityManager.getLockTaskModeState()
                        != ActivityManager.LOCK_TASK_MODE_NONE) {
            try {
                stopLockTask();
            } catch (IllegalStateException alreadyReleased) {
                // The state check above can race the system's own release.
                Log.w(TAG, "Lock task was already released", alreadyReleased);
            }
        }
    }

    /**
     * Full release, used only when handing the screen to another app such as Trebuchet.
     *
     * <p><b>Order matters and is the whole point of this method.</b>
     * {@code setStatusBarDisabled} only takes effect while the caller is in lock-task mode, the same
     * documented rule that made it inert on the configuration screen on 2026-08-18. Re-enabling the
     * bar <em>after</em> {@code stopLockTask()} was therefore a no-op, so Trebuchet came up with a
     * status bar and navigation bar that were drawn but dead: no shade, no Home, no Recents, and no
     * way to use the tablet normally. Re-enable first, while the policy still applies, then drop the
     * pin.
     */
    private void releaseForOtherApp() {
        setStatusBarDisabled(false);
        releaseKioskPolicy();
    }

    /*
     * setDashboardSystemUiRestricted() is deleted, not ported.
     *
     * It called StatusBarManager.disable/disable2, which needs the signature STATUS_BAR permission
     * and is @SystemApi besides, so the constants are not even in the public SDK. Nothing here is
     * lost that is not covered elsewhere, which is why this is a deletion and not a stub:
     *
     *   DISABLE_EXPAND, DISABLE2_NOTIFICATION_SHADE,   ->  setStatusBarDisabled(true), which is
     *   DISABLE2_QUICK_SETTINGS                            public API and device-owner-gated
     *   DISABLE_HOME, DISABLE_RECENT                   ->  LOCK_TASK_FEATURE_NONE (API 28+) or
     *                                                      bare startLockTask() below
     *   DISABLE_SEARCH                                 ->  no equivalent; long-press Assistant is
     *                                                      blocked by lock task in practice
     *
     * Every former call site now calls setStatusBarDisabled or disableStatusBarIfPinned instead.
     */

    private static String normalizeUrl(String value) {
        String url = value.trim();
        if (url.isEmpty()) {
            return "";
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return "http://" + url;
        }
        return url;
    }

    private LinearLayout.LayoutParams matchWrap() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(16);
        return params;
    }

    /**
     * {@link #matchWrap()}'s spacing separates whole controls; this one keeps a control visually
     * attached to the caption or sibling directly above it, inside one captioned cluster.
     */
    private LinearLayout.LayoutParams matchWrapClose() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(6);
        return params;
    }



    /**
     * The number in a port box, or null when it is not one, or is outside 1-65535.
     *
     * <p>Separate from {@link #parsePort} on purpose. That one clamps, which is right for reading
     * a stored value back (something usable has to come out whatever is in storage) and wrong for
     * a form: silently substituting 8080 for the 99999 somebody typed is the quiet substitution
     * this project keeps deleting, and it let the tablet store a different port than the operator
     * asked for while the web form refused the identical input.
     */
    private static Integer parsePortStrict(String value) {
        if (value == null) {
            return null;
        }
        try {
            int port = Integer.parseInt(value.trim());
            return port >= 1 && port <= 65535 ? port : null;
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    private static int parsePort(String value, int fallback) {
        try {
            int port = Integer.parseInt(value.trim());
            if (port >= 1 && port <= 65535) {
                return port;
            }
        } catch (NumberFormatException ignored) {
            // Fall through to the default.
        }
        return fallback;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
