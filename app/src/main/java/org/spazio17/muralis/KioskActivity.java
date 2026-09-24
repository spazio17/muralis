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
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
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
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
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
    /** The picture read permission, asked for only on an install that is not the device owner. */
    private static final int REQUEST_PICTURE_READ = 21;
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
    /**
     * Whether display.visual_off's film is in force in this process: the black view on the screens
     * that have one, the 1% window brightness on every screen. Kept as a field so a tap anywhere can
     * be answered without a preference read per touch; the persisted truth is the boot-keyed flag
     * in KioskConfig, and the two are written together.
     */
    private boolean filmOn;
    // The screensaver (2026-09-09): the clock, where the panel is in ScreensaverPolicy's diagram,
    // when the current stage began, the layer the web-page mode draws in (under the black view,
    // so Display off still covers it), and which mode is on the glass, null for none.
    private static final long SCREENSAVER_TICK_MS = 1_000L;
    private ScreensaverPolicy.Stage screensaverStage = ScreensaverPolicy.Stage.DASHBOARD;
    private long screensaverSinceMs = android.os.SystemClock.uptimeMillis();
    private FrameLayout screensaverLayer;
    private WebView screensaverWebView;
    private String screensaverShowing;
    /** The address or the dim floor the showing mode was built with, so a change re-applies. */
    private String screensaverShowingDetail = "";
    /** Display off resolved to a real sleep; cleared by the wake, whichever way it arrives. */
    private boolean asleep;
    /**
     * The showing screensaver is the settings page's "Show it now": the touch that ends it goes
     * back to that page, not to the dashboard (Juri, 2026-09-09: a test must not leave the test
     * page). Ends with the screensaver; a remote stop or the display going dark drops it.
     */
    private boolean screensaverPreview;
    /**
     * Where a touch on a preview asked for from the web or MQTT goes back to: the settings
     * screen that was open when it arrived. Null for the settings page's own Preview button,
     * which goes back to that page.
     */
    private Runnable screensaverPreviewReturn;
    /** The strip that names a preview; without it the dimmed page is just the page, darker. */
    private TextView screensaverPreviewCaption;
    // The Pictures mode: the frame in the layer, the set being shown and where in it we are, the
    // clock that advances it, and how many screensavers this process has shown (one-per-cycle
    // takes the next picture each time). Pictures are decoded on PictureLibrary's worker.
    private PictureFrame pictureFrame;
    private java.util.List<PictureSources.Picture> pictureSet;
    private int pictureIndex;
    private int pictureCycles;
    private long lastPictureRefreshCheckMs;
    private String lastSeenPictureSource = "";
    private ScreensaverPolicy.Settings pictureSettings;
    private volatile int pictureGeneration;
    private final Runnable pictureAdvance = this::advancePicture;
    /**
     * When the last wake was judged. A wake from sleep reaches this activity twice, from
     * onResume and from the display.wake broadcast, in either order; the second arrival within
     * this window is the same wake and must not undo what the first decided.
     */
    private long lastWakeDecisionMs = -WAKE_GRACE_MS;
    private static final long WAKE_GRACE_MS = 3_000L;
    private final Runnable screensaverClock = new Runnable() {
        @Override
        public void run() {
            tickScreensaver();
            mainHandler.postDelayed(this, SCREENSAVER_TICK_MS);
        }
    };
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
    /** True from the moment a launcher hand-over starts until it has been made or abandoned. */
    private boolean launcherHandoff;
    /** Bumped per hand-over, so a late callback or a repeated escape cannot start a second one. */
    private int launcherHandoffGeneration;
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
            // wall-mounted tablet can read (seen 2026-09-07).
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
                // The overlay belongs to the dashboard: it stays over the dimmed page, which is
                // the dashboard, and goes while the film, a web page or pictures cover it.
                boolean covered = screensaverShowing != null
                        && !ScreensaverPolicy.DIM.equals(screensaverShowing);
                boolean enabled = KioskConfig.statsOverlayEnabled(KioskActivity.this) && !covered;
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
                configStatsView.setText(renderOverlay(currentTheme().light));
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
                    intent.getStringExtra(KioskActions.EXTRA_URL),
                    intent.getStringExtra(KioskActions.EXTRA_DISPLAY_OFF_METHOD));
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
        mainHandler.postDelayed(screensaverClock, SCREENSAVER_TICK_MS);
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
     * purchase made on another device is picked up by the next nightly restart on its own. Decided
     * 2026-08-27.
     *
     * <p><b>Never before the first-start wizard has recorded both escape combinations.</b> Muralis
     * is free with or without a Google account, so a panel nobody has set up yet has no business
     * asking Google anything, and somebody who cannot leave Muralis yet must not be shown a Google
     * screen on the way in. This is ordering only, not a new capability: the wizard finishes,
     * {@link #continueAfterFirstStartWizard} starts this, and every later launch takes the path
     * above. Decided 2026-09-03.
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
        KioskRuntimeState.publishActivityInFront(true);
        // Every Muralis screen is fullscreen, including configuration: a kiosk should never show a
        // system bar, and the settings screen used to keep the navigation bar for the keyboard's
        // dismiss key, which also handed anyone standing at the panel a Back button.
        enterImmersiveMode();
        // Lock task is held on every Muralis screen, so re-apply it here too: coming back from
        // another app (Settings, the launcher) otherwise leaves the policy released. Not while a
        // launcher hand-over is pending: the keyguard's own dismissal resumes this activity for a
        // moment, and re-pinning there would put the screen back under lock task mid-retry and
        // leave the person looking at the kiosk they just asked to leave.
        if (!launcherHandoff) {
            applyKioskPolicy();
        }
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
        PowerManager power = getSystemService(PowerManager.class);
        if (asleep && (power == null || power.isInteractive())) {
            // The screen came back by the power button or a remote wake; the display.wake
            // broadcast may follow, and both paths end in the same decision, once.
            asleep = false;
            onDisplayWoke();
        } else if (!asleep && screensaverStage == ScreensaverPolicy.Stage.DASHBOARD) {
            screensaverSinceMs = android.os.SystemClock.uptimeMillis();
        }
    }

    @Override
    protected void onPause() {
        inFront = false;
        KioskRuntimeState.publishActivityInFront(false);
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
     * way back, the arrow in its app bar or a Cancel button, so nothing is unreachable.
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
        KioskRuntimeState.publishWizardOnScreen(wizardVisible);
        KioskRuntimeState.publishRecorderOnScreen(recorderVisible);
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
                    // The verdict is the border alone, not a tinted fill: the field's own box,
                    // one stroke width up so the colour reads at arm's length.
                    outlineField(field, theme, verdict.ok ? theme.ok : theme.bad, 2);
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
        outlineField(field, theme, theme.border, 1);
    }

    /**
     * The web admin controls, from live state rather than a build-time snapshot: they follow the
     * flag and the socket while the screen sits open (liveSettingSyncTask) and refresh right
     * after the local toggle. Both together on purpose, the button's label is state exactly like
     * the line under it, and only the line syncing made a toggle from Home Assistant flip the
     * text while the button kept offering the wrong direction. Green with the address while
     * listening; grey "disabled" while the operator has the surface off; a warning when it
     * should be up and is not, and when it is up but nothing can reach it: a panel with Wi-Fi
     * off keeps its server listening and has no address to print, and the line used to fill
     * the gap with a made-up host name (Juri, 2026-09-24).
     */
    private void refreshWebAdminControls(Button toggle, TextView httpState,
            TextView fingerprintView, KioskTheme theme) {
        boolean enabled = KioskConfig.webAdminEnabled(this);
        toggle.setText(enabled ? "Turn web admin off" : "Turn web admin on");
        // The fingerprint in a block of its own, monospaced on the darker plate, as the web page
        // and the drawings show it: 95 characters with no space to break at ran off the end of
        // the green status line (Juri, 2026-09-23).
        String fingerprint = KioskRuntimeState.httpAdminFingerprint();
        boolean showFingerprint = KioskRuntimeState.httpAdminListening() && !fingerprint.isEmpty();
        fingerprintView.setVisibility(showFingerprint ? View.VISIBLE : View.GONE);
        if (showFingerprint) {
            fingerprintView.setText(fingerprint);
        }
        if (KioskRuntimeState.httpAdminListening()) {
            SystemStats.RuntimeFacts httpFacts = KioskRuntimeState.lastFacts();
            String address = httpFacts == null ? "" : httpFacts.ipAddress;
            if (address.isEmpty()) {
                httpState.setTextColor(theme.warn);
                httpState.setText("Web admin is on, port " + KioskRuntimeState.httpAdminPort()
                        + ", but this device has no network connection");
            } else {
                httpState.setTextColor(theme.ok);
                httpState.setText("Listening at " + KioskRuntimeState.httpAdminScheme() + address
                        + ":" + KioskRuntimeState.httpAdminPort());
            }
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
     * the full width, and the Open dashboard row kept the wrong axis. The MQTT interval buttons are the
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
            // A touch proves the screen is on: a sleep that never darkened, or a wake this
            // activity was not told about, must not leave the screensaver's clock blocked.
            asleep = false;
            if (screensaverStage == ScreensaverPolicy.Stage.DASHBOARD) {
                screensaverSinceMs = event.getEventTime();
            }
            // The screensaver: the first touch brings the page back and never reaches it, and a
            // corner tap still counts for the escape combinations, which work from every screen.
            if (screensaverShowing != null) {
                boolean preview = screensaverPreview;
                Runnable back = screensaverPreviewReturn;
                stopScreensaver("touch");
                if (preview) {
                    if (back != null) {
                        back.run();
                    } else {
                        showScreensaverSettings();
                    }
                } else if (zone != null) {
                    handleEscapeTap(zone, event.getEventTime());
                }
                return true;
            }
            // A tap on a darkened panel means "wake", on every screen. The black view that used to
            // be the only thing answering a tap exists on the dashboard and the parking page; the
            // configuration screen had the 1% dimming and nothing to tap, so a tap on the
            // tablet did nothing (2026-09-08). Consumed, as the black view consumes it:
            // the first touch on a dark panel must not also press whatever sits under the finger.
            if (filmOn) {
                handleUiCommand("display.wake", -1, null);
                return true;
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
                    gateBehindPin("To leave Muralis for the home screen", true, this::openSystemLauncher);
                } else {
                    gateBehindPin("To open Muralis settings", false, () -> {
                        showConfiguration(KioskConfig.load(this));
                        Toast.makeText(this, R.string.configuration_escape_opened,
                                Toast.LENGTH_SHORT).show();
                    });
                }
                return true;
            }
        }
        return false;
    }

    /** Wrong PINs are counted like wrong web admin passwords: five, then a lockout that doubles. */
    private final AuthThrottle pinThrottle = new AuthThrottle();
    private static final String PIN_THROTTLE_KEY = "pin";

    /**
     * The optional second lock behind a tap combination (2026-09-09): a combination can be
     * watched and repeated, a PIN has to be known. Free on every panel. With no PIN set the
     * combination alone does what it always did.
     */
    private void gateBehindPin(String purpose, boolean leavesTheApp, Runnable action) {
        if (!KioskConfig.escapePinSet(this)) {
            action.run();
            return;
        }
        Runnable backToDashboard = () -> showDashboard(KioskConfig.load(this).dashboardUrl);
        // An action that leaves the app (the launcher) does not replace the screen, so the prompt
        // would still be the content when the panel comes back; the dashboard is restored first.
        showPinPrompt(purpose, leavesTheApp ? () -> {
            backToDashboard.run();
            action.run();
        } : action, backToDashboard);
    }

    /** A prompt nobody answers goes away on its own; a wall panel must not be parked on it. */
    private static final long PIN_PROMPT_TIMEOUT_MS = 60_000L;

    private void showPinPrompt(String purpose, Runnable onSuccess, Runnable onCancel) {
        configurationVisible = true;
        recorderVisible = false;
        publishOperatorScreenState();
        applyKioskPolicy();
        KioskTheme theme = currentTheme();
        LinearLayout page = pageColumn(theme);
        // A panel, centred and near the top, rather than a page: one question does not need
        // a screen's width, and on a wall panel the eye goes to the middle first (2026-09-09).
        LinearLayout panel = card(theme, "Enter the PIN");
        TextView why = new FlushText(this);
        why.setText(purpose);
        why.setTextColor(theme.subtext);
        why.setTextSize(14);
        panel.addView(why, matchWrap());
        EditText input = themedInput(theme, "", true);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        input.setHint(EscapePin.MIN_LENGTH + " to " + EscapePin.MAX_LENGTH + " digits");
        panel.addView(input, matchWrap());
        TextView verdict = new FlushText(this);
        verdict.setTextColor(theme.bad);
        verdict.setTextSize(14);
        verdict.setVisibility(View.GONE);
        panel.addView(verdict, matchWrap());
        long waitNow = pinThrottle.lockedOutFor(PIN_THROTTLE_KEY, android.os.SystemClock.elapsedRealtime());
        if (waitNow > 0) {
            verdict.setText("Too many wrong PINs. Try again in " + (waitNow + 999) / 1000 + " s.");
            verdict.setVisibility(View.VISIBLE);
        }
        Runnable thisPrompt = () -> showPinPrompt(purpose, onSuccess, onCancel);
        Runnable expire = () -> {
            if (currentScreen == thisPrompt) {
                hideKeyboard(input);
                onCancel.run();
            }
        };
        mainHandler.postDelayed(expire, PIN_PROMPT_TIMEOUT_MS);
        Button unlock = primaryButton(theme, "Unlock");
        unlock.setOnClickListener(view -> {
            long now = android.os.SystemClock.elapsedRealtime();
            long wait = pinThrottle.lockedOutFor(PIN_THROTTLE_KEY, now);
            if (wait > 0) {
                verdict.setText("Too many wrong PINs. Try again in " + (wait + 999) / 1000 + " s.");
                verdict.setVisibility(View.VISIBLE);
                return;
            }
            if (EscapePin.matches(input.getText().toString(), KioskConfig.escapePinHash(this))) {
                pinThrottle.recordSuccess(PIN_THROTTLE_KEY, now);
                mainHandler.removeCallbacks(expire);
                hideKeyboard(input);
                onSuccess.run();
                return;
            }
            input.setText("");
            if (pinThrottle.recordFailure(PIN_THROTTLE_KEY, now)) {
                long locked = pinThrottle.lockedOutFor(PIN_THROTTLE_KEY, now);
                verdict.setText("Too many wrong PINs. Try again in " + (locked + 999) / 1000 + " s.");
            } else {
                verdict.setText("Wrong PIN.");
            }
            verdict.setVisibility(View.VISIBLE);
        });
        Button cancel = secondaryButton(theme, "Cancel");
        cancel.setOnClickListener(view -> {
            mainHandler.removeCallbacks(expire);
            hideKeyboard(input);
            onCancel.run();
        });
        panel.addView(buttonRow(unlock, cancel), matchWrap());
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        LinearLayout.LayoutParams panelParams = new LinearLayout.LayoutParams(
                Math.min(dp(480), screenWidth - dp(40)), ViewGroup.LayoutParams.WRAP_CONTENT);
        panelParams.gravity = Gravity.CENTER_HORIZONTAL;
        panelParams.topMargin = dp(48);
        page.addView(panel, panelParams);
        setContentView(scrollPage(theme, page));
        currentScreen = thisPrompt;
        input.requestFocus();
    }

    /**
     * Hands the screen to the system launcher, dealing with the keyguard a sleep left behind.
     *
     * <p>A real screen-off ({@code lockNow}) while lock task hides the keyguard leaves Android
     * with a keyguard to reshow: {@code KeyguardViewMediator} records "reshow when re-enabled"
     * before it ever checks that the device owner disabled the lock screen, and
     * {@code stopLockTask()} re-enables it. Measured on the Lenovo 2026-09-09: the launcher came
     * up behind a lock screen with a clock and notifications, needing a swipe. It cannot be
     * dismissed while hidden, the request errors, so the pin is dropped first, any brightness
     * override is lifted, and only then is the keyguard dismissed.
     *
     * <p><b>Only for a device owner whose keyguard is not secure</b>, where the lock screen is
     * disabled and the dismissal is therefore silent. A real credential stays Android's to ask
     * for and an ordinary install never asks: measured on both panels 2026-09-10 and on the API 26
     * tablet 2026-09-19, a device PIN produced no dismissal request at all (Android's lock screen
     * took the screen on the Lenovo; on the tablet the launcher started 9 ms after the pin dropped).
     *
     * <p>A callback is not a deadline. Some vendors never deliver a dismissal result, so an
     * independent 1.2 s timer hands the screen over regardless; on the Lenovo the request went at
     * +0 ms, a retry at +113 ms and the launcher started at +1,206 ms, so the deadline is what
     * completed it. EMUI 8 (the Huawei BAH2-W19, API 26, measured 2026-09-19 after a
     * {@code lockNow} sleep and after a power-button sleep) leaves nothing to reshow, so
     * {@code isKeyguardLocked} stays false and the same deadline starts the launcher at +1.21 s
     * with no lock screen over it. {@code onResume} does not re-apply the kiosk policy while a
     * hand-over is pending, which would otherwise re-pin the screen mid-retry, and each hand-over
     * carries a generation, so two escapes in a row start the launcher exactly once (measured on
     * the Lenovo).
     */
    private void openSystemLauncher() {
        stopScreensaver("leaving for the launcher");
        liftVisualOff();
        if (blackout != null && !kioskStopped) {
            blackout.setVisibility(View.GONE);
        }
        launcherHandoff = true;
        int generation = ++launcherHandoffGeneration;
        releaseForOtherApp();
        Runnable finish = () -> {
            if (!launcherHandoff || generation != launcherHandoffGeneration
                    || isFinishing() || isDestroyed()) {
                return;
            }
            launcherHandoff = false;
            startSystemLauncher();
        };
        android.app.KeyguardManager keyguard = getSystemService(android.app.KeyguardManager.class);
        if (keyguard == null || keyguard.isKeyguardSecure() || !KioskService.isDeviceOwner(this)) {
            // Authentication belongs to Android. The retry fixes only the owner's stale swipe lock.
            finish.run();
            return;
        }
        mainHandler.postDelayed(finish, KEYGUARD_HANDOVER_DEADLINE_MS);
        dismissKeyguardForLauncher(keyguard, generation, finish);
    }

    /** How long a hand-over waits for the keyguard before going ahead without it. */
    private static final long KEYGUARD_HANDOVER_DEADLINE_MS = 1_200L;
    /** How often the reshown keyguard is asked again; it refuses until it is actually showing. */
    private static final long KEYGUARD_POLL_MS = 100L;

    /**
     * Asks the reshown keyguard to go away, then runs {@code finish}.
     *
     * <p>{@code isKeyguardLocked} turns true a moment before the keyguard is actually showing and
     * a dismissal asked in that moment is refused, so both the wait and the refusal are retried on
     * the same clock. Nothing here is a deadline: {@code openSystemLauncher}'s timer is.
     */
    private void dismissKeyguardForLauncher(android.app.KeyguardManager keyguard,
            int generation, Runnable finish) {
        if (!launcherHandoff || generation != launcherHandoffGeneration
                || isFinishing() || isDestroyed()) {
            return;
        }
        Runnable retry = () -> mainHandler.postDelayed(
                () -> dismissKeyguardForLauncher(keyguard, generation, finish), KEYGUARD_POLL_MS);
        if (!keyguard.isKeyguardLocked()) {
            retry.run();
            return;
        }
        try {
            keyguard.requestDismissKeyguard(this,
                    new android.app.KeyguardManager.KeyguardDismissCallback() {
                        @Override public void onDismissSucceeded() { finish.run(); }
                        @Override public void onDismissCancelled() { finish.run(); }
                        @Override public void onDismissError() { retry.run(); }
                    });
        } catch (RuntimeException refused) {
            Log.w(TAG, "Keyguard dismissal refused; handing over with Android's lock intact");
            finish.run();
        }
    }

    private void startSystemLauncher() {
        // Lock-task mode would otherwise refuse the launch outright, and the launcher would be
        // unusable without its navigation bar or status bar.
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


    private static void setCheckedIfChanged(CompoundButton box, boolean value) {
        if (box.isChecked() != value) {
            box.setChecked(value);
        }
    }

    /**
     * The Display off sentence: red while a sleep that Android ended is on record, because then it
     * says the stored method was switched under the operator and why, and that has to be seen.
     */
    private void paintDisplayOffNote(TextView note, KioskTheme theme) {
        note.setText(KioskService.describeDisplayOff(this));
        note.setTextColor(KioskService.displayOffWarning(this) ? theme.bad : theme.subtext);
    }


    /** One radio option, carrying its stored spelling as the tag the listener reads back. */
    private RadioButton radioChoice(
            KioskTheme theme, RadioGroup group, String label, String value) {
        RadioButton radio = new RadioButton(this);
        radio.setText(label);
        radio.setTextColor(theme.text);
        radio.setTextSize(16);
        // Material's 48 dp row: a real touch target, and the same height as a switch row.
        radio.setMinHeight(dp(48));
        radio.setMinimumHeight(dp(48));
        radio.setButtonTintList(selectionTint(theme));
        radio.setId(View.generateViewId());
        radio.setTag(value);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
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
    private static void checkRadioIfChanged(RadioGroup group, String value) {
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
        // The operator asked for the settings, not the screensaver; the record goes too, so the
        // dashboard they open afterwards is the page and not the screensaver coming back.
        stopScreensaver("settings opened");
        destroyWebView();
        kioskStopped = false;
        configurationVisible = true;
        publishOperatorScreenState();
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        enterImmersiveMode();

        KioskTheme theme = currentTheme();
        LinearLayout page = pageColumn(theme);
        // The panel's id under the name, as the web page has it. The theme toggle used to sit at
        // the right of this bar; it is at the right of the Display section's title now, with the
        // other settings for what this screen looks like (Juri, 2026-09-23).
        page.addView(pageHeading(theme, titleText(theme, "Muralis"), config.deviceId, null, null,
                null), matchWrap());
        View provisioningNotice = provisioningNotice(theme);
        if (provisioningNotice != null) {
            page.addView(provisioningNotice, matchWrap());
        }
        Button launcherPrompt = defaultLauncherPrompt(theme);
        if (launcherPrompt != null) {
            page.addView(buttonRow(launcherPrompt), matchWrap());
        }

        LinearLayout dashboardCard = sectionBody(theme);
        // The box holds what is stored and nothing else; the example is a hint. It used to be
        // prefilled with a Home Assistant address as real text, so a fresh panel saved and loaded
        // it at the first press of Open dashboard and showed an error page for a host that does
        // not exist. See KioskCommandDispatcher.EXAMPLE_DASHBOARD_URL for the two reasons.
        EditText urlInput = themedInput(theme, config.dashboardUrl, false);
        urlInput.setHint(KioskCommandDispatcher.EXAMPLE_DASHBOARD_URL);
        urlInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        addField(dashboardCard, theme, "Dashboard URL", urlInput);
        EditText deviceIdInput = themedInput(theme, config.deviceId, false);
        addField(dashboardCard, theme, "Device ID", deviceIdInput);
        // One button beside the aggregate Save ("Open dashboard") at the foot of the screen,
        // because it answers a different question: that one stores what is typed as THE
        // dashboard, this one shows it without changing what is stored. Decided 2026-08-24: a URL
        // with one-off query parameters is exactly what a stored dashboard URL must not become.
        //
        // There used to be a second one here, "Main dashboard", which opened the stored dashboard
        // and saved nothing. Removed 2026-09-07, by decision of that day, after it cost a full set
        // of typed MQTT credentials: two buttons on one screen carrying the word "dashboard", and
        // the one that saves is at the foot and named after what it opens, so the nearer one was
        // pressed as if it were the save and the screen closed without one. Anyone who
        // misunderstands that button makes the same mistake, so it is gone rather than renamed.
        // Nothing is lost: Open dashboard already returns to the stored dashboard, a kiosk
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
        dashboardCard.addView(buttonRow(openOnce), matchWrap());
        String webViewProvider = webViewProviderSummary();
        if (webViewProvider != null) {
            // Plain subtext, deliberately not a warning: see webViewProviderSummary().
            TextView engine = new FlushText(this);
            engine.setTextColor(theme.subtext);
            engine.setTextSize(13);
            engine.setText("Rendering engine: " + webViewProvider);
            dashboardCard.addView(engine, matchWrap());
        }


        LinearLayout mqttCard = sectionBody(theme);
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
        addField(mqttCard, theme, "Password", passwordInput, "Blank keeps the current one.");
        // The broker verdict lives here, on the page where the address is typed, not as a toast
        // over the dashboard: the toast was unreadable in the second before the dashboard took
        // the screen, which is exactly where a misconfiguration must NOT be reported (decided
        // 2026-08-24). Checked when the screen opens and whenever the host or port box is left.
        TextView mqttState = new FlushText(this);
        mqttState.setTextSize(13);
        mqttState.setTextColor(theme.subtext);
        LinearLayout.LayoutParams mqttStateParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mqttStateParams.topMargin = dp(10);
        mqttCard.addView(mqttState, mqttStateParams);



        LinearLayout httpCard = sectionBody(theme);
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
                outlineField(portInput, theme, theme.bad, 2);
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
                    // The page, not the box: from 840 dp only the open section's body is in the
                    // view tree, so this check, which runs when the screen is built, came back to
                    // a detached box and dropped its own answer. The line under the broker host
                    // then read "Checking ..." for as long as the screen stayed up, on every
                    // tablet in landscape (found while taking the store screenshots, 2026-09-23).
                    // Same rule as the live-settings poll below, and for the same reason.
                    if (!page.isAttachedToWindow() || current == null || current != generation) {
                        return;
                    }
                    outlineField(brokerInput, theme, verdict.ok ? theme.ok : theme.bad, 2);
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
        addField(httpCard, theme, "Admin password", httpAdminPasswordInput,
                "Blank keeps the current one.");
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
        TextView httpState = new FlushText(this);
        httpState.setTextSize(13);
        // The sentence the web page carries above the same block, so the two surfaces explain the
        // certificate the same way, and the fingerprint itself in a code block under it.
        TextView certificateNote = new FlushText(this);
        certificateNote.setTextColor(theme.subtext);
        certificateNote.setTextSize(12);
        certificateNote.setText("Served over HTTPS with a certificate this panel made itself, so "
                + "your browser warned once. Its SHA-256 fingerprint, to compare with the one the "
                + "browser shows for that page:");
        TextView fingerprintView = new FlushText(this);
        fingerprintView.setTypeface(Typeface.MONOSPACE);
        fingerprintView.setTextSize(12);
        fingerprintView.setTextColor(theme.subtext);
        fingerprintView.setBackground(theme.panel(theme.lowest(), dp(8)));
        int monoPad = dp(12);
        fingerprintView.setPadding(monoPad, monoPad, monoPad, monoPad);
        fingerprintView.setVisibility(View.GONE);
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
            refreshWebAdminControls(webAdminToggle, httpState, fingerprintView, theme);
            mainHandler.postDelayed(() -> refreshWebAdminControls(webAdminToggle, httpState,
                    fingerprintView, theme), 700);
        });
        httpCard.addView(buttonRow(webAdminToggle), matchWrap());
        refreshWebAdminControls(webAdminToggle, httpState, fingerprintView, theme);
        // The broker is checked as soon as the screen opens, not only after an edit: an operator
        // who comes here because "Home Assistant lost the panel" gets the answer without having
        // to touch a field first.
        checkBroker.run();
        LinearLayout.LayoutParams httpStateParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        httpStateParams.topMargin = dp(10);
        httpCard.addView(httpState, httpStateParams);
        LinearLayout.LayoutParams noteParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        noteParams.topMargin = dp(12);
        httpCard.addView(certificateNote, noteParams);
        LinearLayout.LayoutParams fingerprintParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fingerprintParams.topMargin = dp(8);
        httpCard.addView(fingerprintView, fingerprintParams);


        // Everything about how the glass looks: the backlight and which way up the panel is.
        LinearLayout displayCard = sectionBody(theme);
        final CompoundButton autoBrightnessInput;
        // Remembered so saving the form can tell an actual change from an unchanged checkbox. Without
        // this, every save re-applied the current value, and on a device without the WRITE_SETTINGS
        // app-op that meant every save bounced the operator into Android Settings and dropped the
        // kiosk's lock-task hold. Observed on the panel 2026-08-19.
        final boolean autoBrightnessWasOn = KioskService.isAutoBrightnessOn(this);
        // The slider, and the label that reads back what the panel is actually at. Declared before
        // the checkbox because the checkbox enables and disables it.
        final SeekBar brightnessInput = new SeekBar(this);
        final TextView brightnessValue = new FlushText(this);
        // Caption first, then the sensor checkbox, then the slider: every cluster in this card
        // leads with its heading, so nothing reads as a control floating on its own.
        TextView brightnessCaption = fieldCaption(theme, "Brightness");
        LinearLayout.LayoutParams brightnessCaptionParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        brightnessCaptionParams.topMargin = dp(14);
        displayCard.addView(brightnessCaption, brightnessCaptionParams);
        // The permission both brightness controls need, stated on the card rather than only in a
        // toast at the moment one of them is refused. On a freshly provisioned panel,
        // 2026-09-07: "the brightness toggle does not toggle", and neither the app nor the setup
        // page said why. In this state the card looks like two separate bugs rather than one
        // missing grant: the checkbox cannot write SCREEN_BRIGHTNESS_MODE without WRITE_SETTINGS,
        // and the slider is separately disabled while the light sensor owns the backlight, which
        // on a device nobody has configured yet it does by default. Device-owner status buys
        // nothing here, unlike the Global and Secure namespaces: Settings.System has no
        // device-owner setter, so this is a grant somebody makes once by hand.
        if (!KioskService.canWriteSystemSettings(this)) {
            TextView needsGrant = new FlushText(this);
            needsGrant.setTextColor(theme.bad);
            needsGrant.setTextSize(13);
            needsGrant.setText("Needs the \"Modify system settings\" permission.");
            displayCard.addView(needsGrant, matchWrapClose());
            Button grantWriteSettings = secondaryButton(theme, "Grant it now");
            grantWriteSettings.setOnClickListener(view -> offerWriteSettingsGrant());
            displayCard.addView(buttonRow(grantWriteSettings), matchWrap());
            // The line and the button are a claim about right now, so they must go the moment it
            // stops being true, without waiting for the screen to be rebuilt by something else.
            watchForWriteSettingsGrant();
        } else {
            stopWatchingWriteSettings();
        }
        if (KioskService.hasLightSensor(this)) {
            autoBrightnessInput = themedSwitch(theme,
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
            TextView noSensor = new FlushText(this);
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

        TextView brightnessNote = new FlushText(this);
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
            radioChoice(theme, orientationInput, "Auto-rotate",
                    KioskConfig.ORIENTATION_AUTO);
        }
        radioChoice(theme, orientationInput, "Landscape", KioskConfig.ORIENTATION_LANDSCAPE);
        radioChoice(theme, orientationInput, "Portrait", KioskConfig.ORIENTATION_PORTRAIT);
        checkRadioIfChanged(orientationInput, config.orientation);
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

        // How "Display off" darkens the panel, and one line saying what that means on this
        // tablet right now. The real screen-off is offered only to a device owner, the same gate
        // as auto-rotate behind the accelerometer: an option that cannot work is worse than an
        // absent one, and the line below says why the film is what an ordinary install gets.
        // An ordinary install gets no choice at all: Automatic resolved to the film there, so
        // the two options were one thing under two names (Juri, 2026-09-24). It shows the film,
        // ticked and greyed, and the line under it says what would unlock the rest.
        TextView displayOffLabel = fieldCaption(theme, "Display off");
        LinearLayout.LayoutParams displayOffLabelParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        displayOffLabelParams.topMargin = dp(14);
        displayCard.addView(displayOffLabel, displayOffLabelParams);

        RadioGroup displayOffInput = new RadioGroup(this);
        if (KioskService.isDeviceOwner(this)) {
            radioChoice(theme, displayOffInput, "Automatic", DisplayOffPolicy.AUTO);
            radioChoice(theme, displayOffInput, "Turn the screen off", DisplayOffPolicy.SLEEP);
            radioChoice(theme, displayOffInput, "Black film", DisplayOffPolicy.FILM);
            checkRadioIfChanged(displayOffInput, config.displayOffMethod);
        } else {
            RadioButton film = radioChoice(theme, displayOffInput, "Black film",
                    DisplayOffPolicy.FILM);
            displayOffInput.check(film.getId());
            film.setEnabled(false);
            film.setTextColor(theme.subtext);
        }
        displayCard.addView(displayOffInput, matchWrapClose());

        TextView displayOffNote = new FlushText(this);
        displayOffNote.setTextSize(12);
        paintDisplayOffNote(displayOffNote, theme);
        displayCard.addView(displayOffNote, matchWrapClose());

        displayOffInput.setOnCheckedChangeListener((group, checkedId) -> {
            View checked = group.findViewById(checkedId);
            if (checked == null || syncingLiveControls) {
                return;
            }
            // Through DarkWatch rather than applyLiveSetting, because setting the method also
            // forgets a recorded bad sleep, and the two must never be stored apart.
            DarkWatch.setMethod(this, (String) checked.getTag());
            KioskService.publishTelemetrySoon(this);
            paintDisplayOffNote(displayOffNote, theme);
        });

        // The web admin's System stats box, on the tablet: the same eight rows from the same
        // formatter, with the switch that puts them on the dashboard directly under them. A switch
        // labelled "show system stats" sitting three cards away from the stats it shows was a
        // question the operator had to answer by toggling it and looking somewhere else.
        // The screensaver card: the mode and the one sentence that says what is set; every
        // field, the two times, the page address, the dim floor, the wake choice and "Show it
        // now", is on its own page, reached by the button (Juri, 2026-09-09: the times were on
        // the card too and were the same boxes twice).
        LinearLayout screensaverCard = sectionBody(theme);
        ScreensaverControls screensaverControls =
                addScreensaverControls(screensaverCard, screensaverCard, null, theme, false);
        Button screensaverMore = textButtonOnward(theme, "More screensaver settings");
        screensaverMore.setOnClickListener(view -> showScreensaverSettings());
        screensaverCard.addView(buttonRow(screensaverMore), matchWrap());

        LinearLayout statsCard = sectionBody(theme);
        TextView statsReadout = new FlushText(this);
        statsReadout.setTypeface(Typeface.MONOSPACE);
        statsReadout.setTextSize(13);
        statsReadout.setTextColor(theme.text);
        statsReadout.setLineSpacing(dp(2), 1.1f);
        // Drawn as a code block, matching the web admin's <pre id="stats">: same monospace face, same
        // plate behind it, same padding and corner. The two surfaces show identical rows from
        // identical data, so looking identical is the honest presentation; monospace text sitting
        // bare on the card read as prose that happened to be misaligned. The plate is the lowest
        // surface, white on a light theme and near-black on a dark one, which is the web's
        // --lowest and the plate the certificate fingerprint stands on; it used to be the mantle,
        // which is a light theme's card colour again and left the block with no plate at all
        // (Juri, 2026-09-23).
        statsReadout.setBackground(theme.panel(theme.lowest(), dp(10)));
        int statsPad = dp(10);
        statsReadout.setPadding(statsPad, statsPad, statsPad, statsPad);
        statsReadout.setText(renderOverlay(theme.light));
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
        CompoundButton statsOverlayInput = themedSwitch(theme,
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
                // The page, not one control: on a wide screen only the open section's body is
                // attached, and a control in a closed one would stop this for the whole screen.
                if (!configurationVisible || !page.isAttachedToWindow()) {
                    return;
                }
                syncingLiveControls = true;
                try {
                    setCheckedIfChanged(statsOverlayInput,
                            KioskConfig.statsOverlayEnabled(KioskActivity.this));
                    checkRadioIfChanged(orientationInput,
                            KioskConfig.orientationOf(KioskActivity.this));
                    checkRadioIfChanged(displayOffInput,
                            KioskConfig.displayOffMethodOf(KioskActivity.this));
                    // The sentence changes on its own when a sleep ends badly, so it follows too,
                    // and the method radio with it, since a bad sleep switches the stored method.
                    paintDisplayOffNote(displayOffNote, theme);
                    screensaverControls.sync();
                } finally {
                    syncingLiveControls = false;
                }
                // The web admin button and status line follow the flag and the socket too, so a
                // toggle from MQTT or the web admin itself shows here without reopening the screen.
                refreshWebAdminControls(webAdminToggle, httpState, fingerprintView, theme);
                mainHandler.postDelayed(this, LIVE_SETTING_SYNC_INTERVAL_MS);
            }
        };
        mainHandler.postDelayed(liveSettingSyncTask, LIVE_SETTING_SYNC_INTERVAL_MS);

        // The Pro gate's face. The shape decided on 2026-08-27: the paid cards stay visible and
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

        LinearLayout escapeCard = sectionBody(theme);
        TextView escapeSummary = new FlushText(this);
        escapeSummary.setTextColor(theme.subtext);
        escapeSummary.setTextSize(14);
        escapeSummary.setText("Settings: "
                + EscapeSequence.describe(EscapeSequence.parse(config.settingsSequence))
                + "\nHome screen: "
                + EscapeSequence.describe(EscapeSequence.parse(config.launcherSequence))
                + "\nPIN: " + (KioskConfig.escapePinSet(this) ? "set" : "not set"));
        escapeCard.addView(escapeSummary, matchWrap());
        Button manageSequences = textButtonOnward(theme, "Manage escape sequences");
        manageSequences.setOnClickListener(view -> showEscapeSequences(KioskConfig.load(this)));
        escapeCard.addView(buttonRow(manageSequences), matchWrap());

        // The Pro state lives in About (moved 2026-08-29, by decision of that day): it is a fact about this
        // installation, like the version line beside it, not a card-sized feature of its own.
        // The purchase still lives where the features it unlocks are: the locked MQTT and web
        // admin cards stay visible, complete and inert, each with its own Buy button. The button
        // here only appears while Play says the product is buyable, so a bought panel shows one
        // quiet status line.
        LinearLayout aboutCard = sectionBody(theme);
        TextView buildLine = new FlushText(this);
        buildLine.setTextColor(theme.subtext);
        buildLine.setTextSize(13);
        buildLine.setText(appVersionSummary());
        aboutCard.addView(buildLine, matchWrap());
        TextView proCaption = fieldCaption(theme, "Muralis Pro");
        LinearLayout.LayoutParams proCaptionParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        proCaptionParams.topMargin = dp(14);
        aboutCard.addView(proCaption, proCaptionParams);
        TextView proState = new FlushText(this);
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
            if (proCardBuilt[0] && !page.isAttachedToWindow()) {
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
            // A compiled-in override is the honest answer on this panel; Play's own detail is
            // not, because it was never consulted.
            String override = ProEntitlement.overrideDetail();
            proState.setText(override != null ? override : proDetail);
            buyPro.setVisibility(buyable && override == null ? View.VISIBLE : View.GONE);
        });
        proCardBuilt[0] = true;

        // Privacy policy, Terms and conditions and the details page as rows with a chevron, as
        // the web's About lists them (Juri, 2026-09-23); the version line above is the same row
        // there, linking to the repository, which a locked panel has no browser for.
        aboutCard.addView(listRow(theme, getString(R.string.privacy_policy_title),
                () -> showLegalDocument(R.string.privacy_policy_title, R.raw.privacy)),
                matchWrap());
        aboutCard.addView(listRow(theme, getString(R.string.terms_title),
                () -> showLegalDocument(R.string.terms_title, R.raw.terms)), matchWrapClose());
        aboutCard.addView(listRow(theme, "Version and device details", this::showAbout),
                matchWrapClose());
        // Ordinary installs only. On a device-owner panel "close" is meaningless (Muralis is HOME,
        // the system relaunches it immediately) and the escape sequence is the deliberate exit, so
        // a close button there is a control whose only use is breaking the panel, the same class
        // of surface the auto-recycle switches were deleted for. Deliberately NOT on the web admin
        // or MQTT either, for the reason system.shutdown was deleted: a remote close has no remote
        // undo, because the thing that would receive the reopen command is what was just closed.
        if (!isDeviceOwner()) {
            Button closeApp = secondaryButton(theme, "Close Muralis");
            closeApp.setOnClickListener(view -> closeCompletely());
            aboutCard.addView(buttonRow(closeApp), matchWrap());
        }

        // One list of sections, each a glyph, its name and a line of what is stored, opening in
        // place, one at a time (Juri's drawing of 2026-09-23); from 840 dp the list stands at the
        // left and the open section at the right. Same order as the web page, which has one more
        // section, Quick actions, for the commands that a person standing at the panel has as
        // Open dashboard and the escape combinations.
        final List<Section> sections = java.util.Arrays.asList(
                new Section(R.drawable.ic_dashboard, "Dashboard", dashboardSummary(config),
                        dashboardCard),
                new Section(R.drawable.ic_mqtt, "MQTT", config.mqttHost.isEmpty()
                        ? "Not configured" : config.mqttHost + ":" + config.mqttPort, mqttCard),
                new Section(R.drawable.ic_web, "Local web admin", webAdminSummary(config), httpCard),
                new Section(R.drawable.ic_escape, "Escape sequences", sequencesSummary(config),
                        escapeCard),
                // The one section with a control of its own beside its name: the day and night
                // toggle, which is what this screen looks like and belongs with the brightness
                // and the orientation rather than in the app bar (Juri, 2026-09-23).
                new Section(R.drawable.ic_display, "Display", displaySummary(), displayCard,
                        themeToggle(theme)),
                new Section(R.drawable.ic_screensaver, "Screensaver", screensaverSummary(),
                        screensaverCard),
                new Section(R.drawable.ic_stats, "System stats", statsSummary(), statsCard),
                new Section(R.drawable.ic_info, "About", appVersionName(), aboutCard));

        Runnable openDashboard = () -> {
            String url = normalizeUrl(urlInput.getText().toString());
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
            //
            // An emptied box is the one input the dispatcher refuses that this button accepts.
            // Clearing the URL and pressing the button that saves is a decision, and it used to be
            // answered with nothing at all: no save, no screen change, no message. Now it saves
            // the other cards, stores the empty URL, and shows the parking page, which says what
            // to do next (decided 2026-09-07). The web admin's Dashboard box keeps refusing empty;
            // a browser is not where somebody blanks a panel on purpose.
            String urlProblem = url.isEmpty() ? null : KioskCommandDispatcher.validateDashboardUrl(url);
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
        };
        // No "Configure Wi-Fi" button: Android Settings draws no navigation bar under this ROM,
        // so handing it the screen left no way back. Wi-Fi is set up once during provisioning, and
        // Settings is still reachable through the escape sequence when it is genuinely needed.
        page.addView(settingsMenu(theme, sections, openDashboard), matchWrap());

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
     * One section of the settings menu: its glyph, its name, one line of what is stored, its body,
     * and at most one control that belongs beside its name wherever that name is drawn, which is
     * the accordion's row or the open card's title.
     */
    private static final class Section {
        final int icon;
        final String title;
        final String summary;
        final LinearLayout body;
        final View action;

        Section(int icon, String title, String summary, LinearLayout body) {
            this(icon, title, summary, body, null);
        }

        Section(int icon, String title, String summary, LinearLayout body, View action) {
            this.icon = icon;
            this.title = title;
            this.summary = summary;
            this.body = body;
            this.action = action;
        }
    }

    /** A section's contents, built like a card's without the card: the menu draws the frame. */
    private LinearLayout sectionBody(KioskTheme theme) {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        return body;
    }

    private static final String OPEN_SECTION = "open_section";

    /**
     * The settings as one menu, in the shape the width allows.
     *
     * <p>Below 840 dp an accordion: every row is a glyph, the name and a summary, a tap opens
     * that section in place and closes the other, and the choice is remembered (Juri, 2026-09-23:
     * one section open at a time). From 840 dp, Material's list-detail: the same rows as a list
     * at the left, 360 dp wide, and the open section as a card at the right, no wider than
     * 640 dp, so no panel ever spans a landscape tablet and turns its boxes into bars.
     */
    private View settingsMenu(KioskTheme theme, List<Section> sections, Runnable onOpenDashboard) {
        android.content.SharedPreferences ui = KioskConfig.storageContext(this)
                .getSharedPreferences(UI_PREFERENCES, MODE_PRIVATE);
        String remembered = ui.getString(OPEN_SECTION, "");
        boolean wide = getResources().getConfiguration().screenWidthDp >= 840;
        View footer = openDashboardRow(theme, onOpenDashboard, wide);
        return wide ? listDetail(theme, sections, remembered, ui, footer)
                : accordion(theme, sections, remembered, ui, footer);
    }

    /**
     * Open dashboard, drawn as a menu of one entry under the menu itself: the same row, the same
     * glyph column, in the main colour, a card's gap below the list (Juri, 2026-09-23). It is the
     * one button of this screen and it saves before it opens, which is why it is apart from the
     * sections rather than inside one.
     *
     * <p>It is the same card as the menu above it, corner for corner, and only the colour sets
     * it apart (Juri, 2026-09-23). {@code inList} is the list-detail layout, where the card holds
     * its rows 8 dp in: the row takes that on as padding rather than shrinking, so the card keeps
     * the menu's width and the two glyph columns still line up.
     */
    private LinearLayout openDashboardRow(KioskTheme theme, Runnable onOpen, boolean inList) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(72));
        int side = dp(inList ? 24 : 16);
        row.setPadding(side, dp(8), side, dp(8));
        row.setBackground(theme.ripple(theme.panel(theme.accent, dp(12)),
                theme.panel(Color.WHITE, dp(12)), theme.onAccent()));
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_dashboard);
        icon.setImageTintList(ColorStateList.valueOf(theme.onAccent()));
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(24), dp(24));
        iconParams.rightMargin = dp(20);
        row.addView(icon, iconParams);
        TextView label = new FlushText(this);
        label.setText("Open dashboard");
        label.setTextColor(theme.onAccent());
        label.setTextSize(16);
        label.setTypeface(MEDIUM);
        row.addView(label, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.setContentDescription("Open dashboard: save these settings and show the page");
        row.setOnClickListener(view -> onOpen.run());
        return row;
    }

    /** The row every section shows closed: 72 dp, the glyph, two lines, and a chevron. */
    private LinearLayout sectionRow(KioskTheme theme, Section section, boolean chevron) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(chevron ? 72 : 64));
        row.setPadding(dp(16), dp(8), dp(16), dp(8));
        ImageView icon = new ImageView(this);
        icon.setImageResource(section.icon);
        icon.setImageTintList(ColorStateList.valueOf(theme.subtext));
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(24), dp(24));
        iconParams.rightMargin = dp(20);
        row.addView(icon, iconParams);
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView title = new FlushText(this);
        title.setText(section.title);
        title.setTextColor(theme.text);
        title.setTextSize(16);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        texts.addView(title);
        TextView summary = new FlushText(this);
        summary.setText(section.summary);
        summary.setTextColor(theme.subtext);
        summary.setTextSize(14);
        summary.setSingleLine(true);
        summary.setEllipsize(android.text.TextUtils.TruncateAt.END);
        texts.addView(summary);
        row.addView(texts, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        // The accordion's row is where that section's name is drawn, so a section's own control
        // rides here; in the list-detail layout the name is the open card's title and it rides
        // there instead. Either way it is beside the name, once.
        if (chevron && section.action != null) {
            row.addView(section.action, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        if (chevron) {
            ImageView arrow = new ImageView(this);
            arrow.setImageResource(R.drawable.ic_expand_more);
            arrow.setImageTintList(ColorStateList.valueOf(theme.subtext));
            arrow.setTag("chevron");
            LinearLayout.LayoutParams arrowParams = new LinearLayout.LayoutParams(dp(24), dp(24));
            arrowParams.leftMargin = dp(12);
            row.addView(arrow, arrowParams);
        }
        row.setBackground(theme.ripple(new android.graphics.drawable.ColorDrawable(
                Color.TRANSPARENT), new android.graphics.drawable.ColorDrawable(Color.WHITE),
                theme.text));
        return row;
    }

    private View accordion(KioskTheme theme, List<Section> sections, String remembered,
            android.content.SharedPreferences ui, View footer) {
        // The menu and the row under it share one column, no wider than 720 dp and centred: a
        // phone fills it, a portrait tablet does not stretch it. Measured inside the page's own
        // gutters, since those are what the column actually has to live in.
        int width = contentWidthDp();
        LinearLayout stack = new LinearLayout(this);
        stack.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams stackParams = new LinearLayout.LayoutParams(
                width > 720 ? dp(720) : ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        stackParams.gravity = Gravity.CENTER_HORIZONTAL;
        stack.setLayoutParams(stackParams);
        LinearLayout menu = new LinearLayout(this);
        menu.setOrientation(LinearLayout.VERTICAL);
        menu.setBackground(theme.panel(theme.card, dp(12)));
        menu.setClipToOutline(true);
        stack.addView(menu, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (footer != null) {
            LinearLayout.LayoutParams footerParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            footerParams.topMargin = dp(16);
            stack.addView(footer, footerParams);
        }
        List<LinearLayout> rows = new ArrayList<>();
        List<LinearLayout> bodies = new ArrayList<>();
        boolean phone = width < 600;
        for (int index = 0; index < sections.size(); index++) {
            Section section = sections.get(index);
            LinearLayout row = sectionRow(theme, section, true);
            LinearLayout body = new LinearLayout(this);
            body.setOrientation(LinearLayout.VERTICAL);
            // The body under the row's words: 68 dp in, past the glyph, where the eye already is;
            // a phone has no width to spare for that and takes the full row.
            body.setPadding(dp(phone ? 16 : 68), dp(8), dp(16), dp(24));
            body.addView(section.body, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            boolean open = section.title.equals(remembered);
            body.setVisibility(open ? View.VISIBLE : View.GONE);
            paintAccordionRow(theme, row, open);
            rows.add(row);
            bodies.add(body);
            menu.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            menu.addView(body, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            if (index < sections.size() - 1) {
                View rule = new View(this);
                rule.setBackgroundColor(theme.outlineVariant);
                menu.addView(rule, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
            }
            final int mine = index;
            row.setOnClickListener(view -> {
                boolean opening = bodies.get(mine).getVisibility() != View.VISIBLE;
                for (int other = 0; other < bodies.size(); other++) {
                    boolean on = opening && other == mine;
                    bodies.get(other).setVisibility(on ? View.VISIBLE : View.GONE);
                    paintAccordionRow(theme, rows.get(other), on);
                }
                ui.edit().putString(OPEN_SECTION, opening ? section.title : "").apply();
                if (opening) {
                    hideKeyboardIfShown();
                }
            });
        }
        return stack;
    }

    /** The open row lifted a step, its chevron turned; the closed rows plain. */
    private void paintAccordionRow(KioskTheme theme, LinearLayout row, boolean open) {
        row.setBackground(theme.ripple(new android.graphics.drawable.ColorDrawable(
                open ? theme.cardHigh : Color.TRANSPARENT),
                new android.graphics.drawable.ColorDrawable(Color.WHITE), theme.text));
        View chevron = row.findViewWithTag("chevron");
        if (chevron != null) {
            chevron.setRotation(open ? 180f : 0f);
        }
    }

    private View listDetail(KioskTheme theme, List<Section> sections, String remembered,
            android.content.SharedPreferences ui, View footer) {
        LinearLayout pair = new LinearLayout(this);
        pair.setOrientation(LinearLayout.HORIZONTAL);
        pair.setBaselineAligned(false);
        // The list is half the open section, the two of them filling the column the gutters
        // leave, with Material's 24 dp gutter between them (Juri's drawing of 2026-09-23: menu
        // 30, gap 5, content 60). Weights rather than a fixed 360 dp list, because the column
        // itself is a share of the screen now and a fixed pane would break the proportion.
        LinearLayout navColumn = new LinearLayout(this);
        navColumn.setOrientation(LinearLayout.VERTICAL);
        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.VERTICAL);
        nav.setBackground(theme.panel(theme.card, dp(12)));
        nav.setPadding(dp(8), dp(8), dp(8), dp(8));
        navColumn.addView(nav, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (footer != null) {
            LinearLayout.LayoutParams footerParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            footerParams.topMargin = dp(16);
            navColumn.addView(footer, footerParams);
        }
        LinearLayout.LayoutParams navParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        navParams.rightMargin = dp(24);
        pair.addView(navColumn, navParams);

        LinearLayout detail = card(theme, null);
        // The title, and at the right of it whatever control that section brings: the day and
        // night toggle for Display, nothing for the rest (Juri, 2026-09-23).
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView detailTitle = cardTitle(theme, "");
        titleRow.addView(detailTitle, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        FrameLayout titleAction = new FrameLayout(this);
        titleRow.addView(titleAction, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        detail.addView(titleRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        pair.addView(detail, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f));

        int openIndex = 0;
        for (int index = 0; index < sections.size(); index++) {
            if (sections.get(index).title.equals(remembered)) {
                openIndex = index;
            }
        }
        List<LinearLayout> rows = new ArrayList<>();
        for (int index = 0; index < sections.size(); index++) {
            Section section = sections.get(index);
            LinearLayout row = sectionRow(theme, section, false);
            rows.add(row);
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            nav.addView(row, rowParams);
            final int mine = index;
            row.setOnClickListener(view -> {
                showDetail(theme, detail, detailTitle, titleAction, sections, rows, mine);
                ui.edit().putString(OPEN_SECTION, section.title).apply();
                hideKeyboardIfShown();
            });
        }
        showDetail(theme, detail, detailTitle, titleAction, sections, rows, openIndex);
        return pair;
    }

    /** Moves one section's body into the detail card and marks its row in the list. */
    private void showDetail(KioskTheme theme, LinearLayout detail, TextView detailTitle,
            FrameLayout titleAction, List<Section> sections, List<LinearLayout> rows, int index) {
        for (int other = 0; other < rows.size(); other++) {
            boolean on = other == index;
            LinearLayout row = rows.get(other);
            row.setBackground(theme.ripple(theme.pill(on ? theme.secondaryContainer
                    : Color.TRANSPARENT), theme.pill(Color.WHITE), theme.text));
            TextView title = (TextView) ((ViewGroup) row.getChildAt(1)).getChildAt(0);
            title.setTextColor(on ? theme.onSecondaryContainer : theme.text);
            title.setTypeface(on ? MEDIUM : Typeface.DEFAULT);
        }
        Section section = sections.get(index);
        while (detail.getChildCount() > 1) {
            detail.removeViewAt(1);
        }
        if (section.body.getParent() instanceof ViewGroup) {
            ((ViewGroup) section.body.getParent()).removeView(section.body);
        }
        detailTitle.setText(section.title);
        titleAction.removeAllViews();
        if (section.action != null) {
            titleAction.addView(section.action, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        detail.addView(section.body, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void hideKeyboardIfShown() {
        View focused = getCurrentFocus();
        if (focused instanceof EditText) {
            hideKeyboard(focused);
        }
    }

    /**
     * A row that opens something: its words and a chevron, Material's list item for navigation,
     * used where the About section leads to the two legal pages and the details page.
     */
    private LinearLayout listRow(KioskTheme theme, String label, Runnable onOpen) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(48));
        TextView text = new FlushText(this);
        text.setText(label);
        text.setTextColor(theme.accent);
        text.setTextSize(16);
        row.addView(text, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        ImageView chevron = new ImageView(this);
        chevron.setImageResource(R.drawable.ic_chevron_right);
        chevron.setImageTintList(ColorStateList.valueOf(theme.subtext));
        row.addView(chevron, new LinearLayout.LayoutParams(dp(24), dp(24)));
        row.setBackground(theme.ripple(new android.graphics.drawable.ColorDrawable(
                Color.TRANSPARENT), new android.graphics.drawable.ColorDrawable(Color.WHITE),
                theme.text));
        row.setOnClickListener(view -> onOpen.run());
        return row;
    }

    /**
     * The sun or the moon in the app bar, switching this screen's palette. Kept in UI preferences
     * rather than KioskConfig, and so deliberately outside applyLiveSetting: it is a preference of
     * whoever is standing at the tablet reading this screen, not a property of the device.
     */
    private ImageButton themeToggle(KioskTheme theme) {
        ImageButton toggle = iconButton(theme, theme.light
                ? R.drawable.ic_theme_moon : R.drawable.ic_theme_sun, theme.light
                ? "Switch this screen to the dark theme" : "Switch this screen to the light theme");
        // The sun in the palette's yellow, the moon in the neutral grey: shapes and colours
        // people already read as day and night.
        toggle.setImageTintList(ColorStateList.valueOf(theme.light ? theme.subtext : theme.warn));
        toggle.setOnClickListener(view -> {
            KioskConfig.storageContext(this).getSharedPreferences(UI_PREFERENCES, MODE_PRIVATE)
                    .edit()
                    .putBoolean(LIGHT_CONFIGURATION_THEME, !theme.light)
                    .apply();
            // Loaded fresh, not the snapshot this screen was built from; same rule the rotation
            // redraw and the save button follow. In place, so a new palette does not take the
            // reader away from where they were.
            redrawInPlace(() -> showConfiguration(KioskConfig.load(this)));
        });
        return toggle;
    }

    // ---- the summaries: stored values only, never a status line the pages do not already carry

    /**
     * Each summary is one line of a list a third of the column wide, so it says the one thing
     * that section is about and leaves out what the page already shows: the device id is the app
     * bar's subtitle, the uptime is in the stats readout, the build number is on the About page.
     */
    private static String dashboardSummary(KioskConfig config) {
        String host = config.dashboardUrl.replaceFirst("^[a-z]+://", "").replaceFirst("/.*$", "");
        return host.isEmpty() ? "No dashboard yet" : host;
    }

    /**
     * The scheme only while the server is up: before it is bound (a panel waits for its network
     * first) the scheme is unknown, and the row read "HTTP" beside a card saying HTTPS (phone,
     * 2026-09-24). The web page's own row always knows, since it is served by that socket.
     */
    private String webAdminSummary(KioskConfig config) {
        return "Port " + config.httpPort
                + (!KioskRuntimeState.httpAdminListening() ? ""
                        : KioskRuntimeState.httpAdminSecure() ? " · HTTPS" : " · HTTP")
                + (config.webAdminEnabled ? "" : " · off");
    }

    private String sequencesSummary(KioskConfig config) {
        if (config.settingsSequence.isEmpty() || config.launcherSequence.isEmpty()) {
            return "Not recorded yet";
        }
        return config.settingsSequence + " · " + config.launcherSequence
                + (KioskConfig.escapePinSet(this) ? " · PIN" : "");
    }

    private String displaySummary() {
        String orientation = KioskConfig.orientationOf(this);
        String method = KioskConfig.displayOffMethodOf(this);
        return (KioskConfig.ORIENTATION_AUTO.equals(orientation) ? "Auto-rotate"
                : KioskConfig.ORIENTATION_PORTRAIT.equals(orientation) ? "Portrait" : "Landscape")
                + " · "
                + (DisplayOffPolicy.SLEEP.equals(method) ? "screen off"
                        : DisplayOffPolicy.FILM.equals(method) ? "black film" : "automatic");
    }

    private String screensaverSummary() {
        ScreensaverPolicy.Settings saver = KioskConfig.screensaverOf(this);
        String name = ScreensaverPolicy.DIM.equals(saver.mode) ? "Dimmed page"
                : ScreensaverPolicy.FILM.equals(saver.mode) ? "Black film"
                : ScreensaverPolicy.URL.equals(saver.mode) ? "Web page"
                : ScreensaverPolicy.PICTURES.equals(saver.mode) ? "Pictures" : "Off";
        return ScreensaverPolicy.OFF.equals(saver.mode) ? name
                : name + " · after " + saver.idleSeconds + " s";
    }

    private String statsSummary() {
        SystemStats.Sample sample = KioskRuntimeState.lastSample();
        StringBuilder text = new StringBuilder();
        if (sample != null && sample.memTotalKb != SystemStats.UNKNOWN) {
            long used = SystemStats.usedPercent(sample.memUsedKb(), sample.memTotalKb);
            if (used >= 0) {
                text.append(used).append("% memory");
            }
            text.append(text.length() > 0 ? " · " : "")
                    .append(SystemStats.percent(sample.cpuBusyPercent)).append(" CPU");
        }
        return text.length() == 0 ? "Live readings" : text.toString();
    }

    /**
     * The screensaver's own page: every field, "Show it now", and the way back. The card on the
     * configuration screen carries the mode alone; this page has the times and the rest.
     */
    private void showScreensaverSettings() {
        // Reached from the configuration screen, where no page is up, and from the touch that
        // ends a "Show it now", where the dashboard is: the same prelude as showConfiguration,
        // so the page never sits over a live WebView and its clocks.
        destroyWebView();
        setDashboardFullscreen(true);
        configurationVisible = true;
        recorderVisible = false;
        wizardVisible = false;
        publishOperatorScreenState();
        applyKioskPolicy();
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        enterImmersiveMode();
        KioskTheme theme = currentTheme();
        LinearLayout page = pageColumn(theme);
        // The title alone, as the web page has it: the subtitle and the paragraph that explained
        // the two timers went on 2026-09-19 at Juri's request, the panels say it themselves.
        page.addView(pageHeading(theme, "Screensaver", null,
                () -> showConfiguration(KioskConfig.load(this))), matchWrap());

        // Two concerns, two panels, and the width comes right as a side effect: cardGrid lays
        // them into two columns from 720 dp, which is what every sibling settings page already
        // does and what this page did not, so the screensaver panel alone spanned the whole
        // screen in landscape (the brief's A1 and A2, Juri's field notes).
        LinearLayout modeCard = card(theme, "Screensaver mode");
        LinearLayout optionsCard = card(theme, null);
        // The heading is added here rather than by card(), because it has to be repainted when the
        // mode changes: the whole point of the second panel is that it says which mode its options
        // belong to, so a person is never reading settings without knowing what they apply to.
        TextView optionsTitle = new FlushText(this);
        optionsTitle.setTextColor(theme.text);
        optionsTitle.setTextSize(18);
        optionsCard.addView(optionsTitle);
        // A third panel for the playlists, split out of the Pictures options on 2026-09-11 at
        // Juri's request, so the two surfaces are arranged alike: mode, that mode's options, and
        // the playlist. It is the panel's own playlists, so it keeps the Create button
        // and the page behind it rather than copying the web admin's inline browser.
        LinearLayout playlistCard = card(theme, "Playlist");
        ScreensaverControls controls =
                addScreensaverControls(modeCard, optionsCard, playlistCard, theme, true);
        controls.optionsTitle = optionsTitle;
        controls.optionsCard = optionsCard;
        controls.playlistCard = playlistCard;
        controls.paintOptionsTitle(KioskConfig.screensaverOf(this).mode);
        controls.applyMode(KioskConfig.screensaverOf(this).mode);
        LinearLayout.LayoutParams gridParams = matchWrap();
        gridParams.topMargin = dp(16);
        page.addView(cardGrid(theme,
                java.util.Arrays.<View>asList(modeCard, optionsCard, playlistCard)),
                gridParams);

        Button showNow = tonalButton(theme, "Preview");
        showNow.setOnClickListener(view -> {
            ScreensaverPolicy.Settings settings = KioskConfig.screensaverOf(this);
            String problem = !settings.enabled() ? "the screensaver mode is off"
                    : KioskConfig.kioskStopped(this) ? "the kiosk is stopped"
                    : ScreensaverPolicy.modeProblem(settings.mode, settings.url);
            if (problem != null) {
                Toast.makeText(this, "Not shown: " + problem + ".", Toast.LENGTH_LONG).show();
                return;
            }
            showDashboard(KioskConfig.load(this).dashboardUrl);
            if (startScreensaver(settings, "show it now")) {
                screensaverPreview = true;
                showScreensaverPreviewCaption(settings);
            } else {
                Toast.makeText(this, "Not shown: the display is off.", Toast.LENGTH_LONG).show();
            }
        });
        // Inside the options card, under the sentence that says what the settings add up to, where
        // the web page keeps it (Juri, 2026-09-19); the way back is the arrow in the app bar.
        optionsCard.addView(buttonRow(showNow), matchWrap());

        setContentView(scrollPage(theme, page));
        currentScreen = this::showScreensaverSettings;

        Runnable sync = new Runnable() {
            @Override
            public void run() {
                if (!configurationVisible || !controls.summary.isAttachedToWindow()) {
                    return;
                }
                controls.sync();
                mainHandler.postDelayed(this, LIVE_SETTING_SYNC_INTERVAL_MS);
            }
        };
        mainHandler.postDelayed(sync, LIVE_SETTING_SYNC_INTERVAL_MS);
    }

    /**
     * The screensaver's controls, on the card and on the page, all of them applied the moment
     * they are touched like the Display card's, and followed from storage so a change made in
     * the web admin or over MQTT appears here. A text box is not overwritten while it has the
     * focus, so nothing is typed over.
     */
    private final class ScreensaverControls {
        final KioskTheme theme;
        final RadioGroup modeInput;
        final EditText idleInput;
        final EditText offInput;
        final EditText urlInput;
        final EditText dimInput;
        final RadioGroup onWakeInput;
        final TextView summary;
        TextView urlCaption;
        TextView dimCaption;
        /** The wake choice's caption, so the whole field can leave for a mode that has none. */
        TextView onWakeLabel;
        // The Pictures mode's controls, one group shown for that mode only.
        LinearLayout picturesGroup;
        RadioGroup sourceInput;
        TextView sourceState;
        Button refreshButton;
        /** The Create button and the playlist rows under it. */
        LinearLayout playlistsGroup;
        /** The options panel's heading, which names the mode its fields belong to. */
        TextView optionsTitle;
        /** The whole options panel, hidden for Off: a screensaver that is off has no settings. */
        LinearLayout optionsCard;
        /** The Playlist panel, which belongs to the Pictures mode with this panel as the source. */
        LinearLayout playlistCard;
        LinearLayout sourceButtons;
        EditText pictureSecondsInput;
        RadioGroup transitionInput;
        RadioGroup fitInput;
        CompoundButton shuffleBox;
        CompoundButton onePerCycleBox;
        CompoundButton creditBox;
        RadioGroup cornerInput;

        ScreensaverControls(KioskTheme theme, RadioGroup modeInput, EditText idleInput,
                EditText offInput, EditText urlInput, EditText dimInput, RadioGroup onWakeInput,
                TextView summary) {
            this.theme = theme;
            this.modeInput = modeInput;
            this.idleInput = idleInput;
            this.offInput = offInput;
            this.urlInput = urlInput;
            this.dimInput = dimInput;
            this.onWakeInput = onWakeInput;
            this.summary = summary;
        }

        /**
         * Only the fields the chosen mode uses are on the page (Juri, 2026-09-09): the address
         * for the web page, the floor for the dimmed page; the two times and the wake choice
         * for every mode, the wake choice greyed out for the film, which has nothing to glance
         * at, with the reason under it.
         */
        void applyMode(String mode) {
            if (urlInput == null) {
                return;
            }
            int url = ScreensaverPolicy.URL.equals(mode) ? View.VISIBLE : View.GONE;
            fieldOf(urlCaption).setVisibility(url);
            int dim = ScreensaverPolicy.DIM.equals(mode) ? View.VISIBLE : View.GONE;
            fieldOf(dimCaption).setVisibility(dim);
            // Gone, not greyed out: the black film has nothing to glance at, so the choice does
            // not apply, and a control that can never be enabled is clutter (Juri, 2026-09-11).
            int wake = ScreensaverPolicy.wakeChoiceApplies(mode) ? View.VISIBLE : View.GONE;
            onWakeInput.setVisibility(wake);
            if (onWakeLabel != null) {
                onWakeLabel.setVisibility(wake);
            }

            picturesGroup.setVisibility(
                    ScreensaverPolicy.PICTURES.equals(mode) ? View.VISIBLE : View.GONE);
            // Off has no options panel at all (Juri, 2026-09-11): "it is Off so there is no
            // settings for it in any case". The times are still stored and still apply the moment
            // a mode is picked; they are simply not shown beside a screensaver that is not on.
            if (optionsCard != null) {
                optionsCard.setVisibility(
                        ScreensaverPolicy.OFF.equals(mode) ? View.GONE : View.VISIBLE);
            }
            if (playlistCard != null) {
                playlistCard.setVisibility(ScreensaverPolicy.PICTURES.equals(mode)
                        && PictureSources.LOCAL.equals(
                                KioskConfig.screensaverOf(KioskActivity.this).source)
                        ? View.VISIBLE : View.GONE);
            }
        }

        /**
         * The source decides the rest of the group: playlist access belongs to the local source,
         * fetching to online sources, whose credit switch remains forced on.
         */
        /** "Dimmed page options", and so on: the mode named where its settings are. */
        void paintOptionsTitle(String mode) {
            if (optionsTitle == null) {
                return;
            }
            String name = ScreensaverPolicy.OFF.equals(mode) ? "No screensaver"
                    : ScreensaverPolicy.DIM.equals(mode) ? "Dimmed page"
                    : ScreensaverPolicy.FILM.equals(mode) ? "Black film"
                    : ScreensaverPolicy.URL.equals(mode) ? "Web page"
                    : ScreensaverPolicy.PICTURES.equals(mode) ? "Pictures" : "Screensaver";
            optionsTitle.setText(name + " options");
        }

        void applySource(String source) {
            if (picturesGroup == null) {
                return;
            }
            boolean local = PictureSources.LOCAL.equals(source);
            // The playlists belong to the local source; fetching belongs to the online ones.
            // Nothing here opens a system picker on either device any more (2026-09-10).
            if (playlistCard != null) {
                // The panel belongs to this source only: there is nothing to browse when the
                // pictures come from Bing, and an empty panel is worse than no panel.
                playlistCard.setVisibility(local
                        && ScreensaverPolicy.PICTURES.equals(KioskConfig.screensaverOf(
                                KioskActivity.this).mode)
                        ? View.VISIBLE : View.GONE);
            }
            if (playlistsGroup != null) {
                playlistsGroup.setVisibility(View.VISIBLE);
            }
            // The row itself, not only the button in it: an empty row still spends its own 16 dp
            // margin, and that margin plus the playlists' own is the gap Juri measured between
            // the source sentence and Create (2026-09-10, B1).
            refreshButton.setVisibility(local ? View.GONE : View.VISIBLE);
            sourceButtons.setVisibility(local ? View.GONE : View.VISIBLE);
            creditBox.setEnabled(local);
            creditBox.setAlpha(local ? 1f : 0.45f);
            if (!local) {
                boolean wasSyncing = syncingLiveControls;
                syncingLiveControls = true;
                try {
                    setCheckedIfChanged(creditBox, true);
                } finally {
                    syncingLiveControls = wasSyncing;
                }
            }
            paintSourceState(source);
        }

        /** The source's sentence, read on the worker so storage never blocks the main thread. */
        void paintSourceState(String source) {
            PictureLibrary library = PictureLibrary.get(KioskActivity.this);
            TextView target = sourceState;
            library.run(() -> {
                String sentence = library.state(source);
                // Red for a folder whose grant is gone as well as a failed fetch: the operator
                // has to point at it again, which is the rule the brightness grant follows.
                boolean bad = library.problem(source) != null;
                library.onMain(() -> {
                    if (target.isAttachedToWindow()) {
                        target.setText(sentence);
                        target.setTextColor(bad ? theme.bad : theme.subtext);
                    }
                });
            });
        }

        void sync() {
            ScreensaverPolicy.Settings settings = KioskConfig.screensaverOf(KioskActivity.this);
            boolean wasSyncing = syncingLiveControls;
            syncingLiveControls = true;
            try {
                checkRadioIfChanged(modeInput, settings.mode);
                followIfIdle(idleInput, String.valueOf(settings.idleSeconds));
                followIfIdle(offInput, String.valueOf(settings.offSeconds));
                followIfIdle(urlInput, settings.url);
                followIfIdle(dimInput, String.valueOf(settings.dimPercent));
                if (onWakeInput != null) {
                    checkRadioIfChanged(onWakeInput, settings.onWake);
                }
                if (picturesGroup != null) {
                    checkRadioIfChanged(sourceInput, settings.source);
                    followIfIdle(pictureSecondsInput, String.valueOf(settings.pictureSeconds));
                    checkRadioIfChanged(transitionInput, settings.transition);
                    checkRadioIfChanged(fitInput, settings.pictureFit);
                    setCheckedIfChanged(shuffleBox, settings.shuffle);
                    setCheckedIfChanged(onePerCycleBox, settings.onePerCycle);
                    setCheckedIfChanged(creditBox, settings.creditShown());
                    checkRadioIfChanged(cornerInput, settings.creditCorner);
                    applySource(settings.source);
                }
                applyMode(settings.mode);
            } finally {
                syncingLiveControls = wasSyncing;
            }
            paintSummary();
        }

        void paintSummary() {
            ScreensaverPolicy.Settings settings = KioskConfig.screensaverOf(KioskActivity.this);
            summary.setText(ScreensaverPolicy.describe(settings,
                    KioskRuntimeState.screensaverActive()));
            boolean problem = settings.enabled()
                    && ScreensaverPolicy.modeProblem(settings.mode, settings.url) != null;
            summary.setTextColor(problem ? theme.bad : theme.subtext);
        }

        private void followIfIdle(EditText input, String value) {
            if (input == null || input.hasFocus()) {
                return;
            }
            if (!value.equals(input.getText().toString())) {
                input.setText(value);
            }
        }
    }

    private ScreensaverControls addScreensaverControls(LinearLayout parent, LinearLayout options,
            LinearLayout playlists, KioskTheme theme, boolean full) {
        ScreensaverPolicy.Settings settings = KioskConfig.screensaverOf(this);
        TextView summary = new FlushText(this);
        summary.setTextSize(14);

        RadioGroup modeInput = new RadioGroup(this);
        radioChoice(theme, modeInput, "Off", ScreensaverPolicy.OFF);
        radioChoice(theme, modeInput, "Dimmed page", ScreensaverPolicy.DIM);
        radioChoice(theme, modeInput, "Black film", ScreensaverPolicy.FILM);
        radioChoice(theme, modeInput, "Web page", ScreensaverPolicy.URL);
        radioChoice(theme, modeInput, "Pictures", ScreensaverPolicy.PICTURES);
        checkRadioIfChanged(modeInput, settings.mode);
        LinearLayout.LayoutParams modeParams = matchWrapClose();
        modeParams.topMargin = dp(6);
        parent.addView(modeInput, modeParams);

        EditText idleInput = null;
        EditText offInput = null;
        EditText urlInput = null;
        EditText dimInput = null;
        RadioGroup onWakeInput = null;
        TextView urlCaption = null;
        TextView dimCaption = null;
        TextView onWakeLabel = null;
        if (full) {
            idleInput = secondsInput(theme, settings.idleSeconds);
            addField(options, theme, "Idle before the screensaver (seconds, 0 = off)", idleInput);
            offInput = secondsInput(theme, settings.offSeconds);
            addField(options, theme, "Screensaver before display off (seconds, 0 = never)",
                    offInput);
            urlInput = themedInput(theme, settings.url, false);
            urlInput.setHint(KioskCommandDispatcher.EXAMPLE_DASHBOARD_URL);
            urlInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI
                    | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            urlCaption = addField(options, theme, "Web page to show", urlInput);
            dimInput = themedInput(theme, String.valueOf(settings.dimPercent), false);
            dimInput.setInputType(InputType.TYPE_CLASS_NUMBER);
            dimCaption = addField(options, theme, "Brightness while dimmed (percent)", dimInput);

            onWakeLabel = fieldCaption(theme, "After a wake from display off");
            LinearLayout.LayoutParams onWakeLabelParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            onWakeLabelParams.topMargin = dp(14);
            options.addView(onWakeLabel, onWakeLabelParams);
            onWakeInput = new RadioGroup(this);
            radioChoice(theme, onWakeInput, "Show the screensaver first, a touch opens the page",
                    ScreensaverPolicy.WAKE_SCREENSAVER);
            radioChoice(theme, onWakeInput, "Show the page at once",
                    ScreensaverPolicy.WAKE_DASHBOARD);
            checkRadioIfChanged(onWakeInput, settings.onWake);
            options.addView(onWakeInput, matchWrapClose());
        }
        LinearLayout picturesGroup = full ? new LinearLayout(this) : null;
        if (full) {
            picturesGroup.setOrientation(LinearLayout.VERTICAL);
            options.addView(picturesGroup, matchWrapClose());
        }

        LinearLayout.LayoutParams summaryParams = matchWrapClose();
        summaryParams.topMargin = dp(12);
        options.addView(summary, summaryParams);

        ScreensaverControls controls = new ScreensaverControls(theme, modeInput, idleInput,
                offInput, urlInput, dimInput, onWakeInput, summary);
        controls.urlCaption = urlCaption;
        controls.dimCaption = dimCaption;
        controls.onWakeLabel = onWakeLabel;
        if (full) {
            addPicturesControls(picturesGroup, playlists, theme, settings, controls);
        }
        controls.applyMode(settings.mode);
        controls.paintSummary();

        modeInput.setOnCheckedChangeListener((group, checkedId) -> {
            View checked = group.findViewById(checkedId);
            if (checked == null || syncingLiveControls) {
                return;
            }
            KioskConfig.edit(this).screensaverMode((String) checked.getTag()).apply();
            KioskService.publishTelemetrySoon(this);
            controls.applyMode((String) checked.getTag());
            controls.paintOptionsTitle((String) checked.getTag());
            controls.paintSummary();
        });
        if (full) {
            EditText idle = idleInput;
            onApply(idle, () -> {
                Integer seconds = ScreensaverPolicy.parseSeconds(idle.getText().toString());
                if (seconds == null) {
                    Toast.makeText(this, "Not saved: the idle time "
                            + ScreensaverPolicy.SECONDS_RULE + ".", Toast.LENGTH_LONG).show();
                    idle.setText(String.valueOf(KioskConfig.screensaverOf(this).idleSeconds));
                    return;
                }
                if (seconds != KioskConfig.screensaverOf(this).idleSeconds) {
                    KioskConfig.edit(this).screensaverIdleSeconds(seconds).apply();
                    KioskService.publishTelemetrySoon(this);
                }
                controls.paintSummary();
            });
            EditText off = offInput;
            onApply(off, () -> {
                Integer seconds = ScreensaverPolicy.parseSeconds(off.getText().toString());
                if (seconds == null) {
                    Toast.makeText(this, "Not saved: the time before display off "
                            + ScreensaverPolicy.SECONDS_RULE + ".", Toast.LENGTH_LONG).show();
                    off.setText(String.valueOf(KioskConfig.screensaverOf(this).offSeconds));
                    return;
                }
                if (seconds != KioskConfig.screensaverOf(this).offSeconds) {
                    KioskConfig.edit(this).screensaverOffSeconds(seconds).apply();
                    KioskService.publishTelemetrySoon(this);
                }
                controls.paintSummary();
            });
            EditText url = urlInput;
            onApply(url, () -> {
                String typed = url.getText().toString().trim();
                String value = typed.isEmpty() ? "" : normalizeUrl(typed);
                if (!value.isEmpty()) {
                    String problem = KioskCommandDispatcher.validateDashboardUrl(value);
                    if (problem != null) {
                        Toast.makeText(this, "Not saved: " + problem + ".", Toast.LENGTH_LONG)
                                .show();
                        url.setText(KioskConfig.screensaverOf(this).url);
                        return;
                    }
                }
                if (!value.equals(KioskConfig.screensaverOf(this).url)) {
                    KioskConfig.edit(this).screensaverUrl(value).apply();
                    KioskService.publishTelemetrySoon(this);
                    url.setText(value);
                }
                controls.paintSummary();
            });
            EditText dim = dimInput;
            onApply(dim, () -> {
                Integer percent = ScreensaverPolicy.parseDimPercent(dim.getText().toString());
                if (percent == null) {
                    Toast.makeText(this, "Not saved: the dimmed brightness "
                            + ScreensaverPolicy.DIM_RULE + ".", Toast.LENGTH_LONG).show();
                    dim.setText(String.valueOf(KioskConfig.screensaverOf(this).dimPercent));
                    return;
                }
                if (percent != KioskConfig.screensaverOf(this).dimPercent) {
                    KioskConfig.edit(this).screensaverDimPercent(percent).apply();
                    KioskService.publishTelemetrySoon(this);
                }
            });
            onWakeInput.setOnCheckedChangeListener((group, checkedId) -> {
                View checked = group.findViewById(checkedId);
                if (checked == null || syncingLiveControls) {
                    return;
                }
                KioskConfig.edit(this).screensaverOnWake((String) checked.getTag()).apply();
                KioskService.publishTelemetrySoon(this);
            });
        }
        return controls;
    }

    /**
     * The Pictures mode's controls on the Screensaver page: the source with its sentence and its
     * button (permission for the folder, fetch for the online sources), then how long each picture
     * stays, how it changes, shuffle, one per cycle, and the credit line with its corner.
     */
    private void addPicturesControls(LinearLayout group, LinearLayout playlistCard,
            KioskTheme theme, ScreensaverPolicy.Settings settings, ScreensaverControls controls) {
        controls.picturesGroup = group;
        TextView sourceCaption = fieldCaption(theme, "Pictures from");
        LinearLayout.LayoutParams captionParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        captionParams.topMargin = dp(14);
        group.addView(sourceCaption, captionParams);
        RadioGroup sourceInput = new RadioGroup(this);
        radioChoice(theme, sourceInput, "This panel: uploads and folders of your own",
                PictureSources.LOCAL);
        radioChoice(theme, sourceInput, "Bing image of the day (unofficial, credited)",
                PictureSources.BING);
        radioChoice(theme, sourceInput, "Wikimedia Commons picture of the day (credited)",
                PictureSources.WIKIMEDIA);
        checkRadioIfChanged(sourceInput, settings.source);
        group.addView(sourceInput, matchWrapClose());
        controls.sourceInput = sourceInput;

        TextView sourceState = new FlushText(this);
        sourceState.setTextColor(theme.subtext);
        sourceState.setTextSize(12);
        LinearLayout.LayoutParams stateParams = matchWrapClose();
        stateParams.topMargin = dp(6);
        group.addView(sourceState, stateParams);
        controls.sourceState = sourceState;

        Button refresh = secondaryButton(theme, "Fetch the pictures again");
        refresh.setOnClickListener(view -> {
            String source = KioskConfig.screensaverOf(this).source;
            sourceState.setText("Fetching...");
            PictureLibrary.get(this).refresh(source, () -> controls.paintSourceState(source));
        });
        controls.refreshButton = refresh;
        LinearLayout sourceButtons = buttonRow(refresh);
        controls.sourceButtons = sourceButtons;
        group.addView(sourceButtons, matchWrap());

        // The playlists, under the button that makes one, which is how Juri asked for it on
        // 2026-09-10: "The button 'Choose pictures' must be renamed to 'Create playlist' and if
        // playlists exist then list them under the same button with a 'Use' button on the right
        // side of it to mark it active. Other buttons are 'Edit' and 'Delete'."
        LinearLayout playlists = new LinearLayout(this);
        playlists.setOrientation(LinearLayout.VERTICAL);
        // In the Playlist panel, not in this one, since 2026-09-11. On a card of its own they are
        // one subject rather than a list buried between the source and the time per picture.
        (playlistCard == null ? group : playlistCard).addView(playlists, matchWrapClose());
        controls.playlistsGroup = playlists;
        addPlaylistRows(playlists, theme);

        EditText pictureSeconds = themedInput(theme, String.valueOf(settings.pictureSeconds), false);
        pictureSeconds.setInputType(InputType.TYPE_CLASS_NUMBER);
        addField(group, theme, "Each picture stays for (seconds)", pictureSeconds);
        controls.pictureSecondsInput = pictureSeconds;

        TextView transitionCaption = fieldCaption(theme, "Change of picture");
        LinearLayout.LayoutParams transitionParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        transitionParams.topMargin = dp(14);
        group.addView(transitionCaption, transitionParams);
        RadioGroup transitionInput = new RadioGroup(this);
        radioChoice(theme, transitionInput, "Cut", ScreensaverPolicy.TRANSITION_NONE);
        radioChoice(theme, transitionInput, "Fade", ScreensaverPolicy.TRANSITION_FADE);
        radioChoice(theme, transitionInput, "Slide", ScreensaverPolicy.TRANSITION_SLIDE);
        checkRadioIfChanged(transitionInput, settings.transition);
        group.addView(transitionInput, matchWrapClose());
        controls.transitionInput = transitionInput;

        // How a picture is laid on the glass, one setting for all of them (Juri, 2026-09-23).
        // Fit is the default and what the panel always did: these are somebody's photographs and
        // somebody's licensed work, so nothing is cropped or pulled out of shape unless the
        // operator asks for it.
        TextView fitCaption = fieldCaption(theme, "How a picture fills the screen");
        LinearLayout.LayoutParams fitParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fitParams.topMargin = dp(14);
        group.addView(fitCaption, fitParams);
        RadioGroup fitInput = new RadioGroup(this);
        radioChoice(theme, fitInput, "Fit", ScreensaverPolicy.FIT_WHOLE);
        radioChoice(theme, fitInput, "Fill", ScreensaverPolicy.FIT_FILL);
        radioChoice(theme, fitInput, "Stretch", ScreensaverPolicy.FIT_STRETCH);
        radioChoice(theme, fitInput, "Actual size", ScreensaverPolicy.FIT_ACTUAL);
        checkRadioIfChanged(fitInput, settings.pictureFit);
        group.addView(fitInput, matchWrapClose());
        controls.fitInput = fitInput;
        TextView fitNote = new FlushText(this);
        fitNote.setText("Fit shows the whole picture, Fill crops it to the edges, Stretch pulls "
                + "it out of shape, Actual size does not scale it.");
        fitNote.setTextColor(theme.subtext);
        fitNote.setTextSize(12);
        group.addView(fitNote, matchWrapClose());

        CompoundButton shuffle = themedSwitch(theme, "Shuffle the order", settings.shuffle);
        LinearLayout.LayoutParams boxParams = matchWrapClose();
        boxParams.topMargin = dp(8);
        group.addView(shuffle, boxParams);
        controls.shuffleBox = shuffle;
        CompoundButton onePerCycle = themedSwitch(theme,
                "One picture per screensaver", settings.onePerCycle);
        group.addView(onePerCycle, matchWrapClose());
        controls.onePerCycleBox = onePerCycle;
        CompoundButton credit = themedSwitch(theme,
                "Show the title and credit line", settings.creditShown());
        group.addView(credit, matchWrapClose());
        controls.creditBox = credit;

        TextView cornerCaption = fieldCaption(theme, "Credit line in the corner");
        LinearLayout.LayoutParams cornerParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cornerParams.topMargin = dp(14);
        group.addView(cornerCaption, cornerParams);
        RadioGroup cornerInput = new RadioGroup(this);
        radioChoice(theme, cornerInput, "Bottom left", ScreensaverPolicy.CORNER_BOTTOM_LEFT);
        radioChoice(theme, cornerInput, "Bottom right", ScreensaverPolicy.CORNER_BOTTOM_RIGHT);
        radioChoice(theme, cornerInput, "Top left", ScreensaverPolicy.CORNER_TOP_LEFT);
        radioChoice(theme, cornerInput, "Top right", ScreensaverPolicy.CORNER_TOP_RIGHT);
        checkRadioIfChanged(cornerInput, settings.creditCorner);
        group.addView(cornerInput, matchWrapClose());
        controls.cornerInput = cornerInput;

        sourceInput.setOnCheckedChangeListener((radios, checkedId) -> {
            View checked = radios.findViewById(checkedId);
            if (checked == null || syncingLiveControls) {
                return;
            }
            String source = (String) checked.getTag();
            KioskConfig.edit(this).screensaverSource(source).apply();
            KioskService.publishTelemetrySoon(this);
            controls.applySource(source);
            controls.paintSummary();
            PictureLibrary.get(this).refreshIfStale(source, () -> controls.paintSourceState(source));
        });
        onApply(pictureSeconds, () -> {
            Integer seconds = ScreensaverPolicy.parsePictureSeconds(pictureSeconds.getText().toString());
            if (seconds == null) {
                Toast.makeText(this, "Not saved: the time per picture "
                        + ScreensaverPolicy.PICTURE_SECONDS_RULE + ".", Toast.LENGTH_LONG).show();
                pictureSeconds.setText(String.valueOf(KioskConfig.screensaverOf(this).pictureSeconds));
                return;
            }
            if (seconds != KioskConfig.screensaverOf(this).pictureSeconds) {
                KioskConfig.edit(this).screensaverPictureSeconds(seconds).apply();
                KioskService.publishTelemetrySoon(this);
            }
        });
        transitionInput.setOnCheckedChangeListener((radios, checkedId) -> {
            View checked = radios.findViewById(checkedId);
            if (checked == null || syncingLiveControls) {
                return;
            }
            KioskConfig.edit(this).screensaverTransition((String) checked.getTag()).apply();
            KioskService.publishTelemetrySoon(this);
        });
        fitInput.setOnCheckedChangeListener((radios, checkedId) -> {
            View checked = radios.findViewById(checkedId);
            if (checked == null || syncingLiveControls) {
                return;
            }
            KioskConfig.edit(this).screensaverPictureFit((String) checked.getTag()).apply();
            KioskService.publishTelemetrySoon(this);
        });
        shuffle.setOnCheckedChangeListener((box, on) -> {
            if (!syncingLiveControls) {
                KioskConfig.edit(this).screensaverShuffle(on).apply();
                KioskService.publishTelemetrySoon(this);
            }
        });
        onePerCycle.setOnCheckedChangeListener((box, on) -> {
            if (!syncingLiveControls) {
                KioskConfig.edit(this).screensaverOnePerCycle(on).apply();
                KioskService.publishTelemetrySoon(this);
            }
        });
        credit.setOnCheckedChangeListener((box, on) -> {
            if (!syncingLiveControls) {
                KioskConfig.edit(this).screensaverCredit(on).apply();
                KioskService.publishTelemetrySoon(this);
            }
        });
        cornerInput.setOnCheckedChangeListener((radios, checkedId) -> {
            View checked = radios.findViewById(checkedId);
            if (checked == null || syncingLiveControls) {
                return;
            }
            KioskConfig.edit(this).screensaverCreditCorner((String) checked.getTag()).apply();
            KioskService.publishTelemetrySoon(this);
        });
        controls.applySource(settings.source);
    }

    /**
     * Opens the system's folder picker for the Pictures screensaver. Lock task is released first
     * and the picker is another app, exactly as the brightness grant screen is handled: releasing
     * is what makes the hand-over legal, and no allowlist entry is needed or wanted.
     */
    /**
     * The Playlist panel, the web page's copied line for line (Juri, 2026-09-19): one row per
     * playlist, its name, how many pictures it holds, Use or "In use", Edit and Delete, and under
     * the list the box that names a new playlist with Create beside it.
     *
     * <p>Create makes the playlist at once and empty, as the web does; Edit is where its pictures
     * are picked. It used to open the playlist page with a draft, and the button used to sit above
     * the list. Rebuilt in place rather than being a screen of its own, because a panel with one
     * playlist should not make somebody walk through a list page to reach it.
     */
    private void addPlaylistRows(LinearLayout group, KioskTheme theme) {
        group.removeAllViews();
        PictureLibrary library = PictureLibrary.get(this);
        if (!library.browsesOwnStorage()) {
            // An ordinary install before anybody has answered the dialog. Not an error, and not a
            // reason to hide the feature: the permission is the whole of what is missing.
            TextView why = new FlushText(this);
            why.setTextColor(theme.subtext);
            why.setTextSize(12);
            why.setText("Muralis needs permission to read this panel's pictures before it can "
                    + "show you any folders.");
            group.addView(why, matchWrapClose());
            Button allow = secondaryButton(theme, "Allow Muralis to read pictures");
            allow.setOnClickListener(view -> requestPicturePermission());
            group.addView(buttonRow(allow), matchWrap());
            return;
        }
        PlaylistDocument document = library.playlists().load();
        List<PlaylistDocument.Playlist> all = document.all();
        if (all.isEmpty()) {
            TextView none = new FlushText(this);
            none.setTextColor(theme.subtext);
            none.setTextSize(12);
            none.setText("No playlists yet. Name one below, then open it and pick its pictures.");
            group.addView(none, matchWrapClose());
        }
        for (int index = 0; index < all.size(); index++) {
            group.addView(playlistRow(theme, all.get(index)), new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            if (index < all.size() - 1) {
                View rule = new View(this);
                rule.setBackgroundColor(theme.outlineVariant);
                group.addView(rule, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
            }
        }

        // The box with its label on the border and Create beside it, the button on the box's
        // centre line and not the label's (Juri, 2026-09-23). The word "playlist" is the panel's
        // title and is not repeated in the button.
        LinearLayout maker = new LinearLayout(this);
        maker.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout fieldColumn = new LinearLayout(this);
        fieldColumn.setOrientation(LinearLayout.VERTICAL);
        EditText newName = proseInput(theme, "");
        newName.setFilters(new android.text.InputFilter[] {
                new android.text.InputFilter.LengthFilter(PlaylistDocument.MAX_NAME_LENGTH)});
        addField(fieldColumn, theme, "New playlist name", newName);
        maker.addView(fieldColumn, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView problem = new FlushText(this);
        problem.setTextColor(theme.bad);
        problem.setTextSize(12);
        problem.setVisibility(View.GONE);
        Button create = primaryButton(theme, "Create");
        Runnable createIt = () -> {
            String typed = newName.getText().toString().trim();
            String refusal = library.playlists().load().nameProblem(typed, null);
            if (refusal != null) {
                problem.setText(PictureLibrary.capitalise(refusal));
                problem.setVisibility(View.VISIBLE);
                return;
            }
            hideKeyboard(newName);
            changePlaylists(edited -> edited.create(library.playlists().newId(), typed,
                    System.currentTimeMillis()));
        };
        create.setOnClickListener(view -> createIt.run());
        newName.setOnEditorActionListener((view, actionId, event) -> {
            createIt.run();
            return true;
        });
        addBesideBox(maker, create);
        group.addView(maker, matchWrapClose());
        group.addView(problem, matchWrapClose());
    }


    /**
     * One playlist as a Material list row: the radio in front says which one plays and is the
     * control that switches it, then the name over its count, then Edit and Delete as icon
     * buttons at the end (Juri, 2026-09-23: Use is not a button any more, tap the circle).
     */
    private LinearLayout playlistRow(KioskTheme theme, PlaylistDocument.Playlist playlist) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(72));

        RadioButton use = new RadioButton(this);
        use.setChecked(playlist.active);
        use.setButtonTintList(selectionTint(theme));
        use.setContentDescription("Use " + playlist.name);
        use.setMinWidth(dp(48));
        use.setMinimumWidth(dp(48));
        use.setOnClickListener(view -> {
            if (playlist.active) {
                return;
            }
            changePlaylists(document -> {
                document.activate(playlist.id);
                return null;
            });
        });
        row.addView(use, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView name = new FlushText(this);
        name.setText(playlist.name);
        name.setTextColor(theme.text);
        name.setTextSize(16);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        texts.addView(name);
        TextView count = new FlushText(this);
        int pictures = playlist.items.size();
        String countText = pictures + (pictures == 1 ? " picture" : " pictures");
        if (playlist.active) {
            SpannableString styled = new SpannableString(countText + " · in use");
            styled.setSpan(new android.text.style.ForegroundColorSpan(theme.ok),
                    countText.length() + 3, styled.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            count.setText(styled);
        } else {
            count.setText(countText);
        }
        count.setTextColor(theme.subtext);
        count.setTextSize(14);
        texts.addView(count);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        textParams.leftMargin = dp(8);
        row.addView(texts, textParams);

        ImageButton edit = iconButton(theme, R.drawable.ic_pencil, "Edit " + playlist.name);
        edit.setOnClickListener(view -> {
            PlaylistDraft draft = new PlaylistDraft();
            draft.id = playlist.id;
            draft.name = playlist.name;
            draft.items.addAll(playlist.items);
            showPlaylistPage(draft);
        });
        row.addView(edit, new LinearLayout.LayoutParams(dp(48), dp(48)));
        ImageButton delete = iconButton(theme, R.drawable.ic_trash, "Delete " + playlist.name);
        delete.setOnClickListener(view -> confirmDeletePlaylist(playlist));
        row.addView(delete, new LinearLayout.LayoutParams(dp(48), dp(48)));
        return row;
    }

    /**
     * A yes-or-no question as one of this app's own screens.
     *
     * <p>Not an {@code AlertDialog}, and that is the point: on the API 26 tablet a system dialog
     * came up in Android's light theme over a dark panel <em>and took the immersive mode with it</em>,
     * so a navigation bar appeared on a locked kiosk (measured 2026-09-10). Every other question
     * this app asks is a screen it draws itself, and this is now no exception.
     */
    private void showConfirm(String title, String message, String confirmLabel,
            boolean destructive, Runnable onConfirm, Runnable onCancel) {
        KioskTheme theme = currentTheme();
        enterImmersiveMode();
        LinearLayout page = pageColumn(theme);
        page.addView(pageHeading(theme, title, ""), matchWrap());
        LinearLayout box = card(theme, null);
        TextView text = new FlushText(this);
        text.setTextColor(theme.text);
        text.setTextSize(15);
        text.setText(message);
        box.addView(text, matchWrapClose());
        page.addView(box, matchWrap());
        Button keep = textButton(theme, "Cancel");
        keep.setOnClickListener(view -> onCancel.run());
        // Red when the button destroys something: the one screen where a wrong tap costs the most
        // is the one where the colour has to say so, and the only place red appears (2026-09-23).
        Button go = destructive ? dangerButton(theme, confirmLabel)
                : primaryButton(theme, confirmLabel);
        go.setOnClickListener(view -> onConfirm.run());
        page.addView(buttonRow(keep, go), matchWrap());
        setContentView(scrollPage(theme, page));
        currentScreen = () ->
                showConfirm(title, message, confirmLabel, destructive, onConfirm, onCancel);
    }

    /**
     * Asks before deleting, and names the playlist while asking.
     *
     * <p>Deleting the one in use leaves nothing in use, which the Pictures sentence then says out
     * loud ("No playlist is in use."), because a screensaver that goes black without explanation is
     * the worse failure.
     */
    private void confirmDeletePlaylist(PlaylistDocument.Playlist playlist) {
        showConfirm("Delete " + playlist.name + "?",
                playlist.active
                        ? "It is the playlist in use, so the screensaver will have none until you "
                                + "choose another. The pictures themselves are not deleted."
                        : "The pictures themselves are not deleted.",
                "Delete it",
                true,
                () -> {
                    showScreensaverSettings();
                    changePlaylists(document -> {
                        document.delete(playlist.id);
                        return null;
                    });
                },
                this::showScreensaverSettings);
    }

    /** One edit of the stored playlists, off the main thread, with the screen redrawn after it. */
    private void changePlaylists(java.util.function.Function<PlaylistDocument, String> change) {
        PictureLibrary library = PictureLibrary.get(this);
        library.run(() -> {
            // Under the library's lock with the web admin's and Home Assistant's edits, the same
            // door every surface uses since 2026-09-19.
            final String said = library.editPlaylists(change);
            library.onMain(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (said != null) {
                    Toast.makeText(this, PictureLibrary.capitalise(said), Toast.LENGTH_LONG).show();
                }
                KioskService.publishTelemetrySoon(this);
                if (currentScreen != null) {
                    redrawInPlace(currentScreen);
                }
            });
        });
    }

    /**
     * Asks Android for the picture permission, which is the one thing an ordinary install cannot
     * be given silently.
     *
     * <p>A device owner never reaches this: {@code KioskService.grantOwnRuntimePermissions} has
     * already granted it with no dialog, which is what makes the same browser usable on a screen
     * nobody is standing at.
     */
    private void requestPicturePermission() {
        try {
            requestPermissions(PictureBrowser.permissionsToRequest(), REQUEST_PICTURE_READ);
        } catch (RuntimeException refused) {
            Log.w(TAG, "Cannot ask for the picture permission", refused);
            Toast.makeText(this, "This device would not show the permission request.",
                    Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] granted) {
        super.onRequestPermissionsResult(requestCode, permissions, granted);
        if (requestCode != REQUEST_PICTURE_READ) {
            return;
        }
        PictureLibrary library = PictureLibrary.get(this);
        library.browser().refresh();
        library.forgetLocalCount();
        // Judged by what the browser can now do, not by the first answer alone: Android 14's
        // "Select photos" grants the second permission asked for and denies the first.
        boolean allowed = library.browser().canReadStorage();
        Toast.makeText(this, allowed
                ? "Muralis can read this panel's pictures now."
                : "Without that permission Muralis can only show pictures uploaded to it.",
                Toast.LENGTH_LONG).show();
        if (currentScreen != null && configurationVisible) {
            redrawInPlace(currentScreen);
        }
    }

    /**
     * A playlist being created or edited, held here so a rotation redraws it rather than losing it.
     *
     * <p>Nothing is written until Save, which is what makes Cancel mean something and what keeps a
     * half-picked playlist from ever reaching the screensaver.
     */
    private static final class PlaylistDraft {
        /** Null for a new playlist. */
        String id;
        String name = "";
        final List<String> items = new ArrayList<>();
        /**
         * The folder open in the right pane, or null when none has been tapped yet.
         *
         * <p>Null rather than "", because "" is a real folder (the top of the volume) and the two
         * were the same value at first: the top row read as open while the right pane said to pick
         * a folder, which is two screens disagreeing (2026-09-10).
         */
        String folder;
        int offset;
        /** How many pictures Content shows at once; the same four choices as the web page. */
        int pageSize = PictureBrowser.DEFAULT_PAGE_SIZE;
        boolean edited;
    }

    private PlaylistDraft playlistDraft;

    /**
     * A card whose heading and contents can both be replaced after it has been built.
     *
     * <p>{@link #card} paints its heading and forgets it, which is right for a card that never
     * changes and wrong for a pane that has to say which folder it is showing.
     */
    private final class Pane {
        final LinearLayout card;
        final TextView heading;
        /** The row the heading sits in, with room at its right for a control: the view button. */
        final LinearLayout head;
        final LinearLayout body;

        Pane(KioskTheme theme, String title) {
            card = card(theme, null);
            head = new LinearLayout(KioskActivity.this);
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setGravity(Gravity.CENTER_VERTICAL);
            heading = cardTitle(theme, title);
            head.addView(heading, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            card.addView(head, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            body = new LinearLayout(KioskActivity.this);
            body.setOrientation(LinearLayout.VERTICAL);
            card.addView(body, matchWrapClose());
        }

        /** Puts one control at the right of the heading, replacing whatever was there. */
        void trailing(View control) {
            while (head.getChildCount() > 1) {
                head.removeViewAt(1);
            }
            if (control != null) {
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                params.topMargin = -dp(8);
                params.bottomMargin = -dp(8);
                head.addView(control, params);
            }
        }

        void title(String title) {
            heading.setText(title);
        }

        void clear() {
            body.removeAllViews();
        }
    }

    /**
     * The Picture playlist page's own views, so a tap can repaint one pane instead of the screen.
     *
     * <p><b>Why this class exists.</b> The first version answered every tap by calling
     * {@link #showPlaylistPage} again, which threw the whole page away and built it back. It was
     * correct and it was unusable: opening a folder, ticking a picture or removing one all jumped
     * the reader back to the top of the page, so picking twenty pictures meant scrolling down
     * twenty times (Juri, 2026-09-10, A1, A2 and A4 of his report). Nothing about a tick changes
     * the folder list, and nothing about opening a folder changes what is already picked, so each
     * of those taps now repaints only the pane it actually changed and the page never moves.
     *
     * <p>The tree is held here as well as drawn, because moving the "open" mark from one folder to
     * another must not cost a second query: the left pane is repainted from memory and only the
     * right pane goes back to the index.
     */
    private final class PlaylistPage {
        final PlaylistDraft draft;
        final KioskTheme theme;
        final Pane folders;
        final Pane pictures;
        final Pane selected;
        /** The tick box on screen for each picture, so Remove can untick without a repaint. */
        final java.util.Map<String, CheckBox> boxes = new java.util.LinkedHashMap<>();
        /**
         * Each held picture's "./folder/name" label, looked up once on the worker. The label is
         * two MediaStore queries per picture, and the first version made them on the main thread
         * on every tick, which on a big playlist was seconds of lag (review of 2026-09-19).
         */
        final java.util.Map<String, String> paths = new java.util.HashMap<>();
        /** True while this class sets a box itself, so the box's listener ignores that change. */
        boolean syncing;
        /** The header box over the open folder's page, its words, and the page it stands over. */
        CheckBox selectAll;
        TextView selectAllWords;
        /** The platform's own box drawable, to put back after the dash has been shown. */
        android.graphics.drawable.Drawable selectAllDefault;
        PictureBrowser.Page listing;
        List<PictureBrowser.Folder> tree = new ArrayList<>();
        /**
         * The folders whose children are shown. Seeded once, with the top of the volume and the
         * way down to whatever folder is open, so the tree opens on something useful and not on
         * every folder this panel has ever held (Juri, 2026-09-23).
         */
        final java.util.Set<String> expanded = new java.util.HashSet<>();
        /** The folders that have a folder under them, so only those are drawn with a caret. */
        final java.util.Set<String> parents = new java.util.HashSet<>();
        boolean treeSeeded;
        int uploadCount;
        String treeProblem;

        PlaylistPage(PlaylistDraft draft, KioskTheme theme) {
            this.draft = draft;
            this.theme = theme;
            folders = new Pane(theme, "Folders");
            pictures = new Pane(theme, "Content");
            selected = new Pane(theme, "In this playlist");
        }

        /**
         * Whether a background read that is finishing now belongs to a page nobody is looking at:
         * either this page has been left, or a newer one has replaced it.
         */
        boolean gone() {
            return playlistPage != this || !folders.body.isAttachedToWindow();
        }
    }

    private PlaylistPage playlistPage;

    /**
     * The Picture playlist page: folders on the left, that folder's pictures on the right, and
     * everything picked so far underneath.
     *
     * <p>Built from Juri's sketch of 2026-09-10 (`folders-pictures-selection.jpg`): tapping a
     * folder row opens its contents beside it rather than replacing the screen, so moving between
     * folders never costs the sense of where you are, and the ticked pictures are listed below
     * both panes with the folder prepended so two files with one name read apart.
     *
     * <p>One page for both creating and editing, because he said Edit opens the same page with
     * that playlist loaded. That also means there is no wizard state to lose halfway.
     *
     * <p>This method builds the page. It is called on arrival and on a rotation, and by nothing
     * else: every tap inside the page goes through {@link PlaylistPage} instead.
     */
    private void showPlaylistPage(PlaylistDraft draft) {
        playlistDraft = draft;
        stopScreensaver("choosing pictures");
        destroyWebView();
        configurationVisible = true;
        recorderVisible = false;
        wizardVisible = false;
        publishOperatorScreenState();
        setDashboardFullscreen(true);
        applyKioskPolicy();
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        enterImmersiveMode();
        KioskTheme theme = currentTheme();
        LinearLayout page = pageColumn(theme);
        PlaylistPage screen = new PlaylistPage(draft, theme);
        playlistPage = screen;
        page.addView(playlistHeading(screen), matchWrap());
        // The view button in the Content card's head: the view in force, and the next one on a
        // tap (list, details, small thumbnails, big thumbnails), one preference for the panel
        // and the web page (Juri, 2026-09-23).
        paintViewButton(screen);
        // Two lanes where there is room, one under the other on a phone: the same rule and the
        // same 720 dp boundary as every other pair of cards in this app. The grid deals the cards
        // round-robin, so Folders and In this playlist share the left lane and Content has the
        // right one to itself, the same placement as the web page (Juri, 2026-09-19).
        page.addView(cardGrid(theme, java.util.Arrays.<View>asList(
                screen.folders.card, screen.pictures.card, screen.selected.card)), matchWrap());

        Button cancel = textButton(theme, "Cancel");
        cancel.setOnClickListener(view -> leavePlaylistPage(draft));
        Button save = primaryButton(theme, "Save");
        save.setOnClickListener(view -> savePlaylist(draft));
        // Paired rather than stacked: two short labels fit across a phone's card, and stacking
        // them read as two separate decisions instead of one either-or (Juri, 2026-09-10, B4).
        page.addView(pairedButtonRow(cancel, save), matchWrap());

        setContentView(scrollPage(theme, page));
        currentScreen = () -> showPlaylistPage(draft);

        screen.folders.body.addView(
                paneNote(theme, "Reading this panel's pictures...", false), matchWrapClose());
        loadFolderPane(screen);
        loadPicturePane(screen);
        paintSelectedPane(screen);
    }

    /**
     * The page's title is the playlist's own name, with a pencil beside it that turns the title
     * into a box, the way a pull request's title is edited (Juri, 2026-09-19). The Name card it
     * replaces was one more card for a thing nobody does daily.
     *
     * <p>A new playlist opens with the box already showing, since a name is the first thing it
     * needs. The name is part of the draft like everything else on this page and reaches disk
     * with the page's own Save; the box's Save only settles what the title says.
     */
    private LinearLayout playlistHeading(PlaylistPage screen) {
        KioskTheme theme = screen.theme;
        PlaylistDraft draft = screen.draft;
        PictureLibrary library = PictureLibrary.get(this);
        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.VERTICAL);

        TextView title = titleText(theme, draft.name.isEmpty() ? "New playlist" : draft.name);
        // The pencil as an icon button against the title, the way the web page draws it; the
        // arrow at the left is the way back and asks about unsaved changes like Cancel does.
        ImageButton pencil = iconButton(theme, R.drawable.ic_pencil, "Rename this playlist");
        LinearLayout shown = pageHeading(theme, title,
                draft.id == null ? "Name it, then pick its pictures"
                        : "Change which pictures it shows", pencil,
                () -> leavePlaylistPage(draft), null);

        // The box: the name, Save and Cancel on one row, and the reason a name is refused under
        // it, inline while they type it rather than after the picking is done.
        LinearLayout editor = new LinearLayout(this);
        editor.setOrientation(LinearLayout.VERTICAL);
        editor.setVisibility(View.GONE);
        LinearLayout boxRow = new LinearLayout(this);
        boxRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout nameColumn = new LinearLayout(this);
        nameColumn.setOrientation(LinearLayout.VERTICAL);
        EditText name = proseInput(theme, draft.name);
        name.setTextSize(20);
        addField(nameColumn, theme, "Playlist name", name);
        boxRow.addView(nameColumn, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button keep = primaryButton(theme, "Save");
        Button drop = textButton(theme, "Cancel");
        addBesideBox(boxRow, keep, drop);
        TextView problem = new FlushText(this);
        problem.setTextColor(theme.bad);
        problem.setTextSize(12);
        problem.setVisibility(View.GONE);
        editor.addView(boxRow, matchWrapClose());
        editor.addView(problem, matchWrapClose());
        heading.addView(shown, matchWrapClose());
        heading.addView(editor, matchWrapClose());

        Runnable open = () -> {
            name.setText(draft.name);
            name.setSelection(name.getText().length());
            problem.setVisibility(View.GONE);
            shown.setVisibility(View.GONE);
            editor.setVisibility(View.VISIBLE);
            name.requestFocus();
            showKeyboard(name);
        };
        Runnable close = () -> {
            hideKeyboard(name);
            editor.setVisibility(View.GONE);
            shown.setVisibility(View.VISIBLE);
        };
        pencil.setOnClickListener(view -> open.run());
        drop.setOnClickListener(view -> close.run());
        keep.setOnClickListener(view -> {
            String typed = name.getText().toString().trim();
            String refusal = library.playlists().load().nameProblem(typed, draft.id);
            if (refusal != null) {
                problem.setText(PictureLibrary.capitalise(refusal));
                problem.setVisibility(View.VISIBLE);
                return;
            }
            draft.name = typed;
            draft.edited = true;
            title.setText(typed);
            close.run();
        });
        if (draft.id == null && draft.name.isEmpty()) {
            open.run();
        }
        return heading;
    }

    /** The open folder's name, the first line of Content, or that none has been tapped. */
    private String openFolderName(PlaylistDraft draft) {
        return draft.folder == null ? "Pictures"
                : PictureBrowser.UPLOADS.equals(draft.folder) ? "Uploaded to Muralis"
                : draft.folder.isEmpty() ? "Internal storage" : trimSlash(draft.folder);
    }

    /** The open folder's name, first line of Content, the same line the web page carries. */
    private void paintWhere(PlaylistPage screen) {
        TextView where = new FlushText(this);
        where.setText(openFolderName(screen.draft));
        where.setTextColor(screen.theme.subtext);
        where.setTextSize(14);
        where.setTypeface(MEDIUM);
        where.setPadding(0, 0, 0, dp(4));
        screen.pictures.body.addView(where, matchWrapClose());
    }

    /** "Pictures/holidays/" reads as "Pictures/holidays" in a heading. */
    private static String trimSlash(String path) {
        return path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    }

    /** One line of explanation inside a pane, in the pane's own quiet size. */
    private TextView paneNote(KioskTheme theme, String text, boolean bad) {
        TextView note = new FlushText(this);
        note.setTextColor(bad ? theme.bad : theme.subtext);
        note.setTextSize(12);
        note.setText(text);
        return note;
    }

    /** Reads this panel's folder tree on the worker, then draws the left pane from it. */
    private void loadFolderPane(PlaylistPage screen) {
        PictureLibrary library = PictureLibrary.get(this);
        library.run(() -> {
            List<PictureBrowser.Folder> tree = library.browser().folders();
            // On the worker with the tree, not on the main thread while painting: this reads the
            // uploads directory, and a pane must not do storage work while it is being drawn.
            int uploads = library.uploadCount();
            String problem = library.browser().problem();
            library.onMain(() -> {
                if (screen.gone()) {
                    return;
                }
                screen.tree = tree;
                screen.uploadCount = uploads;
                screen.treeProblem = problem;
                paintFolderPane(screen);
            });
        });
    }

    /**
     * The left pane: the folder tree, indented by depth, with the open one marked and a caret on
     * every folder that has folders under it.
     *
     * <p>It used to show every folder the panel holds at once, which on a real library is a list
     * nobody can read (Juri, 2026-09-23). A branch opens and closes on its caret, the head's
     * button opens or closes the lot, and a tap on the folder itself opens it and its branch, then
     * closes the branch on the next tap, so the row and the caret agree. There is no depth limit: a cap was built on the
     * morning of 2026-09-10 and removed the same day, since a folder five levels down is ordinary
     * and MediaStore hands back its path either way.
     *
     * <p>Drawn from what {@link PlaylistPage} is holding, so moving the open mark from one row to
     * another, or opening a branch, costs no query at all.
     */
    private void paintFolderPane(PlaylistPage screen) {
        seedTree(screen);
        screen.folders.trailing(screen.tree.isEmpty() ? null : collapseAllButton(screen));
        screen.folders.clear();
        screen.folders.body.addView(folderRow(screen, "Uploaded to Muralis",
                PictureBrowser.UPLOADS, 0, screen.uploadCount, false, false), matchWrapClose());
        if (screen.tree.isEmpty()) {
            screen.folders.body.addView(paneNote(screen.theme,
                    screen.treeProblem != null ? screen.treeProblem
                            : "No pictures on this panel yet.",
                    screen.treeProblem != null), matchWrapClose());
            return;
        }
        for (PictureBrowser.Folder folder : screen.tree) {
            if (!folderShown(screen, folder.path)) {
                continue;
            }
            screen.folders.body.addView(folderRow(screen, folder.name, folder.path, folder.depth,
                    folder.pictures, screen.parents.contains(folder.path),
                    screen.expanded.contains(folder.path)), matchWrapClose());
        }
    }

    /**
     * Which folders have folders under them, and which branches start open: the top of the volume
     * and the way down to the folder that is open. Done once per page, then left to the reader.
     */
    private void seedTree(PlaylistPage screen) {
        if (screen.treeSeeded || screen.tree.isEmpty()) {
            return;
        }
        screen.treeSeeded = true;
        for (PictureBrowser.Folder folder : screen.tree) {
            if (!folder.path.isEmpty()) {
                screen.parents.add(PictureBrowser.parentOf(folder.path));
            }
        }
        screen.expanded.add("");
        String walk = screen.draft.folder;
        while (walk != null && !walk.isEmpty() && !PictureBrowser.UPLOADS.equals(walk)) {
            walk = PictureBrowser.parentOf(walk);
            screen.expanded.add(walk);
        }
    }

    /** A folder is on screen when every folder above it is open. The top of the volume always is. */
    private boolean folderShown(PlaylistPage screen, String path) {
        String walk = path;
        while (!walk.isEmpty()) {
            walk = PictureBrowser.parentOf(walk);
            if (!screen.expanded.contains(walk)) {
                return false;
            }
        }
        return true;
    }

    /** True while every branch is open, which is what the head's button has to say. */
    private boolean treeAllExpanded(PlaylistPage screen) {
        return screen.expanded.containsAll(screen.parents);
    }

    /**
     * The one control in the Folders head: closes every branch, or opens every one when they are
     * already closed. The glyph says which it will do, the way the Content head's view button
     * says which view is in force.
     */
    private ImageButton collapseAllButton(PlaylistPage screen) {
        boolean all = treeAllExpanded(screen);
        ImageButton button = iconButton(screen.theme,
                all ? R.drawable.ic_unfold_less : R.drawable.ic_unfold_more,
                all ? "Close every folder" : "Open every folder");
        button.setOnClickListener(view -> {
            if (all) {
                screen.expanded.clear();
            } else {
                screen.expanded.addAll(screen.parents);
            }
            // The top of the volume stays open: closing it would leave one row saying
            // "Internal storage" and nothing to reach the pictures with.
            screen.expanded.add("");
            paintFolderPane(screen);
        });
        return button;
    }


    /**
     * One folder as a row: its glyph, its name and its count as a badge, the open one a pill in
     * the secondary container, the way the web page and the settings list mark a selection.
     */
    private LinearLayout folderRow(PlaylistPage screen, String label, String path, int depth,
            int count, boolean hasChildren, boolean branchOpen) {
        KioskTheme theme = screen.theme;
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(44));
        boolean open = path.equals(screen.draft.folder);
        row.setBackground(theme.ripple(theme.pill(open ? theme.secondaryContainer
                : Color.TRANSPARENT), theme.pill(Color.WHITE), theme.text));
        row.setPadding(dp(4) + dp(20) * Math.min(depth, 6), 0, dp(8), 0);
        // The caret, its own target inside the row: it opens and closes the branch and leaves the
        // pictures alone, which is what the reader asked of it. A folder with nothing under it
        // keeps the space, so every name in the tree starts on one line.
        if (hasChildren) {
            ImageButton caret = new ImageButton(this);
            caret.setImageResource(R.drawable.ic_expand_more);
            caret.setImageTintList(ColorStateList.valueOf(open ? theme.onSecondaryContainer
                    : theme.subtext));
            caret.setRotation(branchOpen ? 0f : -90f);
            caret.setScaleType(ImageView.ScaleType.CENTER);
            caret.setBackground(theme.ripple(new android.graphics.drawable.ColorDrawable(
                    Color.TRANSPARENT), theme.pill(Color.WHITE), theme.text));
            caret.setContentDescription((branchOpen ? "Close " : "Open ") + label);
            caret.setOnClickListener(view -> {
                if (!screen.expanded.remove(path)) {
                    screen.expanded.add(path);
                }
                paintFolderPane(screen);
            });
            row.addView(caret, new LinearLayout.LayoutParams(dp(32), dp(40)));
        } else {
            row.addView(new View(this), new LinearLayout.LayoutParams(dp(32), dp(1)));
        }
        ImageView glyph = new ImageView(this);
        glyph.setImageResource(PictureBrowser.UPLOADS.equals(path) ? R.drawable.ic_upload
                : path.isEmpty() ? R.drawable.ic_storage : R.drawable.ic_folder);
        glyph.setImageTintList(ColorStateList.valueOf(open ? theme.onSecondaryContainer
                : theme.subtext));
        LinearLayout.LayoutParams glyphParams = new LinearLayout.LayoutParams(dp(24), dp(24));
        glyphParams.rightMargin = dp(12);
        row.addView(glyph, glyphParams);
        TextView text = new FlushText(this);
        text.setText(label);
        text.setTextColor(open ? theme.onSecondaryContainer : theme.text);
        text.setTypeface(open ? MEDIUM : Typeface.DEFAULT);
        text.setTextSize(14);
        text.setSingleLine(true);
        text.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        row.addView(text, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        // The count as a badge, as the web page sets it: the pictures directly in the folder,
        // not everything below it, which read as a miscount (Juri, 2026-09-19).
        TextView number = new FlushText(this);
        number.setText(String.valueOf(count));
        number.setTextSize(11);
        number.setTypeface(MEDIUM);
        number.setGravity(Gravity.CENTER);
        number.setMinWidth(dp(16));
        number.setPadding(dp(4), 0, dp(4), 0);
        if (count == 0) {
            number.setTextColor(theme.subtext);
        } else {
            number.setTextColor(theme.text);
            number.setBackground(theme.pill(theme.surfaceAlt));
        }
        LinearLayout.LayoutParams numberParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(16));
        numberParams.leftMargin = dp(8);
        row.addView(number, numberParams);
        // The rest of the row is the target, not a button inside it: the sketch says "user taps
        // this row". It opens the folder and its branch with it, and a second tap on the folder
        // that is already open closes the branch again, so the row does what the caret does and
        // nobody has to find the caret (Juri, 2026-09-23: "make it dumb-proof").
        row.setOnClickListener(view -> {
            if (hasChildren) {
                if (path.equals(screen.draft.folder) && screen.expanded.contains(path)) {
                    screen.expanded.remove(path);
                } else {
                    screen.expanded.add(path);
                }
            }
            openFolder(screen, path);
        });
        return row;
    }

    /**
     * Opens a folder in the right pane.
     *
     * <p>Two panes are repainted and the rest of the page is left exactly as it stands: the left
     * one because the open mark moved, the right one because its contents did. Nothing that has
     * been picked changes, so the Selected pane is not touched and the page does not move.
     */
    private void openFolder(PlaylistPage screen, String path) {
        if (path.equals(screen.draft.folder)) {
            // The branch may still have opened under the tap, so the pane is redrawn even here.
            paintFolderPane(screen);
            return;
        }
        screen.draft.folder = path;
        screen.draft.offset = 0;
        paintFolderPane(screen);
        loadPicturePane(screen);
    }

    /**
     * Reads the open folder on the worker and draws the right pane from it.
     *
     * <p>The answer is dropped when a second folder was tapped while this one was being read, so
     * a slow volume cannot paint a folder over the one somebody has since asked for.
     */
    private void loadPicturePane(PlaylistPage screen) {
        PlaylistDraft draft = screen.draft;
        screen.pictures.clear();
        screen.boxes.clear();
        if (draft.folder == null) {
            screen.pictures.body.addView(paneNote(screen.theme, "Pick a folder to begin.", false),
                    matchWrapClose());
            return;
        }
        paintWhere(screen);
        screen.pictures.body.addView(paneNote(screen.theme, "Reading...", false), matchWrapClose());
        final String folder = draft.folder;
        final int offset = draft.offset;
        final int size = draft.pageSize;
        PictureLibrary library = PictureLibrary.get(this);
        library.run(() -> {
            PictureBrowser.Page listing = PictureBrowser.UPLOADS.equals(folder)
                    ? library.uploads(offset, size)
                    : library.browser().pictures(folder, offset, size);
            library.onMain(() -> {
                if (screen.gone() || !folder.equals(draft.folder) || offset != draft.offset
                        || size != draft.pageSize) {
                    return;
                }
                screen.pictures.clear();
                paintWhere(screen);
                paintPicturePane(screen, listing);
            });
        });
    }


    /**
     * The right pane: the open folder's pictures in the view in force, ticked or not, a page at a
     * time. List and details are rows with a check box; small and big are tiles with the check
     * circle in the corner, laid in as many columns as the pane allows (2026-09-23, from the
     * drawings in media/drafts/material3/). Above them the header box that ticks or clears the
     * page, in the same column as the rows' boxes.
     */
    private void paintPicturePane(PlaylistPage screen, PictureBrowser.Page listing) {
        KioskTheme theme = screen.theme;
        PlaylistDraft draft = screen.draft;
        if (listing.problem != null) {
            screen.pictures.body.addView(paneNote(theme, listing.problem, true), matchWrapClose());
            return;
        }
        if (listing.entries.isEmpty()) {
            screen.pictures.body.addView(
                    paneNote(theme, "No pictures directly in this folder.", false),
                    matchWrapClose());
            return;
        }
        String view = KioskConfig.pictureViewOf(this);
        boolean tiles = PictureBrowser.VIEW_SMALL.equals(view) || PictureBrowser.VIEW_BIG.equals(view);
        screen.pictures.body.addView(selectAllRow(screen, listing), matchWrapClose());
        if (tiles) {
            paintTiles(screen, listing, PictureBrowser.VIEW_BIG.equals(view));
        } else {
            boolean details = PictureBrowser.VIEW_DETAILS.equals(view);
            for (int index = 0; index < listing.entries.size(); index++) {
                PictureBrowser.Entry entry = listing.entries.get(index);
                screen.pictures.body.addView(pictureRow(screen, entry, details),
                        new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT));
                if (index < listing.entries.size() - 1) {
                    View rule = new View(this);
                    rule.setBackgroundColor(theme.outlineVariant);
                    screen.pictures.body.addView(rule, new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
                }
            }
        }
        if (listing.available > listing.entries.size()) {
            // Previous and Next as chevrons around the count: a pager everybody has seen.
            LinearLayout pager = new LinearLayout(this);
            pager.setOrientation(LinearLayout.HORIZONTAL);
            pager.setGravity(Gravity.CENTER_VERTICAL);
            ImageButton previous = iconButton(theme, R.drawable.ic_chevron_left, "Previous page");
            previous.setEnabled(draft.offset > 0);
            previous.setAlpha(draft.offset > 0 ? 1f : 0.38f);
            previous.setOnClickListener(v -> {
                draft.offset = Math.max(0, draft.offset - draft.pageSize);
                loadPicturePane(screen);
            });
            pager.addView(previous, new LinearLayout.LayoutParams(dp(48), dp(48)));
            TextView count = paneNote(theme, (draft.offset + 1) + " to "
                    + (draft.offset + listing.entries.size()) + " of " + listing.available, false);
            count.setTextSize(14);
            count.setGravity(Gravity.CENTER);
            pager.addView(count, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            ImageButton next = iconButton(theme, R.drawable.ic_chevron_right, "Next page");
            next.setEnabled(listing.more);
            next.setAlpha(listing.more ? 1f : 0.38f);
            next.setOnClickListener(v -> {
                draft.offset += draft.pageSize;
                loadPicturePane(screen);
            });
            pager.addView(next, new LinearLayout.LayoutParams(dp(48), dp(48)));
            screen.pictures.body.addView(pager, matchWrapClose());
        }
        if (listing.available > PictureBrowser.PAGE_SIZES[0]) {
            screen.pictures.body.addView(pageSizeRow(screen), matchWrapClose());
        }
    }

    /** One picture as a row: its box, in details its thumbnail and its measurements, its name. */
    private LinearLayout pictureRow(PlaylistPage screen, PictureBrowser.Entry entry,
            boolean details) {
        KioskTheme theme = screen.theme;
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(details ? 72 : 56));
        CheckBox box = pictureBox(screen, entry);
        row.addView(box, new LinearLayout.LayoutParams(dp(48), dp(48)));
        if (details) {
            ImageView thumb = thumbnailView(theme, dp(48));
            LinearLayout.LayoutParams thumbParams = new LinearLayout.LayoutParams(dp(48), dp(48));
            thumbParams.rightMargin = dp(12);
            row.addView(thumb, thumbParams);
            loadThumbnail(thumb, entry.uri);
        }
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView name = new FlushText(this);
        name.setText(entry.name);
        name.setTextColor(theme.text);
        name.setTextSize(16);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        texts.addView(name);
        if (details && !entry.details().isEmpty()) {
            TextView meta = paneNote(theme, entry.details(), false);
            meta.setTextSize(14);
            meta.setSingleLine(true);
            meta.setEllipsize(android.text.TextUtils.TruncateAt.END);
            texts.addView(meta);
        }
        row.addView(texts, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        // The whole row ticks the box: the name is the bigger target and the honest one.
        row.setOnClickListener(v -> box.toggle());
        if (PictureLibrary.get(this).isUpload(entry.uri)) {
            ImageButton bin = iconButton(theme, R.drawable.ic_trash, "Delete the file");
            bin.setOnClickListener(v -> confirmDeleteUpload(screen, entry));
            row.addView(bin, new LinearLayout.LayoutParams(dp(48), dp(48)));
        }
        return row;
    }

    /** The box that puts a picture in the draft or takes it out, remembered for the page. */
    private CheckBox pictureBox(PlaylistPage screen, PictureBrowser.Entry entry) {
        PlaylistDraft draft = screen.draft;
        CheckBox box = new CheckBox(this);
        box.setChecked(draft.items.contains(entry.uri));
        box.setButtonTintList(selectionTint(screen.theme));
        box.setContentDescription("In the playlist");
        box.setOnCheckedChangeListener((button, checked) -> {
            // The box already shows its own new state, and the folder list is unaffected, so
            // a tick repaints one pane: the list of what has been picked.
            if (screen.syncing) {
                return;
            }
            if (checked) {
                if (!draft.items.contains(entry.uri)) {
                    draft.items.add(entry.uri);
                }
            } else {
                draft.items.remove(entry.uri);
            }
            draft.edited = true;
            paintSelectedPane(screen);
            paintSelectAll(screen);
        });
        screen.boxes.put(entry.uri, box);
        return box;
    }

    /**
     * Tiles, as many across as the pane allows: small ones about 100 dp, big ones about 160 dp,
     * with the check circle in the corner and the name under the picture; a big tile carries the
     * measurements too. Rows of a LinearLayout rather than a grid view, because a page is at
     * most a hundred tiles and the rest of this page is built the same way.
     */
    private void paintTiles(PlaylistPage screen, PictureBrowser.Page listing, boolean big) {
        KioskTheme theme = screen.theme;
        int gap = dp(10);
        int paneWidth = paneWidthPx();
        int minTile = dp(big ? 150 : 96);
        int columns = Math.max(2, (paneWidth + gap) / (minTile + gap));
        int tile = (paneWidth - gap * (columns - 1)) / columns;
        LinearLayout row = null;
        for (int index = 0; index < listing.entries.size(); index++) {
            if (index % columns == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rowParams.topMargin = index == 0 ? dp(8) : gap;
                screen.pictures.body.addView(row, rowParams);
            }
            PictureBrowser.Entry entry = listing.entries.get(index);
            LinearLayout.LayoutParams tileParams = new LinearLayout.LayoutParams(tile,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            tileParams.leftMargin = index % columns == 0 ? 0 : gap;
            row.addView(tileView(screen, entry, tile, big), tileParams);
        }
    }

    /** The width the Content pane's body has for its tiles, from the width of the screen. */
    private int paneWidthPx() {
        int room = contentWidthDp();
        int paneDp = getResources().getConfiguration().screenWidthDp >= 840
                ? (room - 16) / 2 : room;
        // The card's own padding, 16 dp each side.
        return dp(paneDp - 32);
    }

    private LinearLayout tileView(PlaylistPage screen, PictureBrowser.Entry entry, int tile,
            boolean big) {
        KioskTheme theme = screen.theme;
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        FrameLayout pic = new FrameLayout(this);
        ImageView thumb = thumbnailView(theme, tile);
        int height = big ? tile * 3 / 4 : tile;
        pic.addView(thumb, new FrameLayout.LayoutParams(tile, height));
        loadThumbnail(thumb, entry.uri);
        CheckBox box = pictureBox(screen, entry);
        // A check circle rather than a square, the mark every gallery puts on a picture, white
        // on a translucent disc so it reads on any photograph.
        box.setButtonDrawable(R.drawable.check_circle);
        box.setButtonTintList(new ColorStateList(new int[][] {
                new int[] {android.R.attr.state_checked}, new int[0]},
                new int[] {theme.accent, Color.WHITE}));
        box.setBackground(theme.pill(Color.argb(0x66, 0x1e, 0x1e, 0x2e)));
        FrameLayout.LayoutParams boxParams = new FrameLayout.LayoutParams(dp(40), dp(40));
        boxParams.gravity = Gravity.TOP | Gravity.START;
        boxParams.leftMargin = dp(2);
        boxParams.topMargin = dp(2);
        pic.addView(box, boxParams);
        if (PictureLibrary.get(this).isUpload(entry.uri)) {
            ImageButton bin = iconButton(theme, R.drawable.ic_trash, "Delete the file");
            bin.setImageTintList(ColorStateList.valueOf(Color.WHITE));
            bin.setBackground(theme.ripple(theme.pill(Color.argb(0x8c, 0x1e, 0x1e, 0x2e)),
                    theme.pill(Color.WHITE), Color.WHITE));
            bin.setPadding(dp(8), dp(8), dp(8), dp(8));
            bin.setOnClickListener(v -> confirmDeleteUpload(screen, entry));
            FrameLayout.LayoutParams binParams = new FrameLayout.LayoutParams(dp(36), dp(36));
            binParams.gravity = Gravity.TOP | Gravity.END;
            binParams.rightMargin = dp(4);
            binParams.topMargin = dp(4);
            pic.addView(bin, binParams);
        }
        pic.setOnClickListener(v -> box.toggle());
        column.addView(pic, new LinearLayout.LayoutParams(tile, height));
        TextView name = new FlushText(this);
        name.setText(entry.name);
        name.setTextColor(theme.text);
        name.setTextSize(12);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setPadding(0, dp(4), 0, 0);
        column.addView(name, new LinearLayout.LayoutParams(tile, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (big && !entry.details().isEmpty()) {
            TextView meta = paneNote(theme, entry.details(), false);
            meta.setTextSize(11);
            meta.setSingleLine(true);
            meta.setEllipsize(android.text.TextUtils.TruncateAt.END);
            column.addView(meta, new LinearLayout.LayoutParams(tile,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        return column;
    }

    /** The plate a thumbnail lands on: rounded, in the quiet colour until the picture arrives. */
    private ImageView thumbnailView(KioskTheme theme, int size) {
        ImageView view = new ImageView(this);
        view.setScaleType(ImageView.ScaleType.CENTER_CROP);
        view.setBackground(theme.panel(theme.surfaceAlt, dp(8)));
        view.setClipToOutline(true);
        return view;
    }

    /**
     * Thumbnails already decoded, so scrolling back through a page costs nothing: a page of a
     * hundred small tiles at 256 px is about 25 MB of ARGB, and this holds a quarter of that;
     * what falls out is read again from the panel's thumbnail files, not decoded from the photo.
     */
    private final android.util.LruCache<String, android.graphics.Bitmap> thumbnails =
            new android.util.LruCache<String, android.graphics.Bitmap>(6 * 1024 * 1024) {
                @Override
                protected int sizeOf(String key, android.graphics.Bitmap value) {
                    return value.getByteCount();
                }
            };

    /**
     * Puts a picture's thumbnail into a view once the worker has it, and never into a view that
     * has since been given another picture: the tag says which one it is waiting for.
     */
    private void loadThumbnail(ImageView view, String uri) {
        view.setTag(uri);
        android.graphics.Bitmap known = thumbnails.get(uri);
        if (known != null) {
            view.setImageBitmap(known);
            return;
        }
        view.setImageDrawable(null);
        PictureLibrary library = PictureLibrary.get(this);
        library.run(() -> {
            java.io.File file = library.thumbnail(uri);
            android.graphics.Bitmap bitmap = file == null ? null
                    : android.graphics.BitmapFactory.decodeFile(file.getPath());
            if (bitmap == null) {
                return;
            }
            thumbnails.put(uri, bitmap);
            library.onMain(() -> {
                if (uri.equals(view.getTag()) && view.isAttachedToWindow()) {
                    view.setImageBitmap(bitmap);
                }
            });
        });
    }

    /**
     * The header check box, in the same column as the rows' boxes: ticked when every picture on
     * the page is in the draft, a dash while only some are, empty otherwise, and a tap ticks or
     * clears the page. The page shown, not the whole folder: what the box stands over is what it
     * governs, the way a table's header box works (2026-09-23; the Select all and Select none
     * buttons, which took the whole folder, went with it).
     */
    private LinearLayout selectAllRow(PlaylistPage screen, PictureBrowser.Page listing) {
        KioskTheme theme = screen.theme;
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(40));
        CheckBox all = new CheckBox(this);
        all.setButtonTintList(selectionTint(theme));
        all.setContentDescription("Select all on this page");
        all.setOnClickListener(v -> {
            boolean want = all.isChecked();
            for (PictureBrowser.Entry entry : listing.entries) {
                if (want) {
                    if (!screen.draft.items.contains(entry.uri)) {
                        screen.draft.items.add(entry.uri);
                    }
                } else {
                    screen.draft.items.remove(entry.uri);
                }
                tickBox(screen, entry.uri, want);
            }
            screen.draft.edited = true;
            if (screen.draft.items.size() > PlaylistDocument.MAX_PICTURES) {
                Toast.makeText(this, "A playlist holds at most "
                        + PlaylistDocument.MAX_PICTURES + " pictures.", Toast.LENGTH_LONG).show();
            }
            paintSelectedPane(screen);
            paintSelectAll(screen);
        });
        row.addView(all, new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView words = paneNote(theme, "", false);
        words.setTextSize(14);
        row.addView(words, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        View rule = new View(this);
        rule.setBackgroundColor(theme.outlineVariant);
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        wrapper.addView(rule, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        screen.selectAll = all;
        screen.selectAllDefault = all.getButtonDrawable();
        screen.selectAllWords = words;
        screen.listing = listing;
        paintSelectAll(screen);
        return wrapper;
    }

    /** Re-reads the header box from the draft: all, some (a dash) or none of the page. */
    private void paintSelectAll(PlaylistPage screen) {
        if (screen.selectAll == null || screen.listing == null) {
            return;
        }
        int held = 0;
        for (PictureBrowser.Entry entry : screen.listing.entries) {
            if (screen.draft.items.contains(entry.uri)) {
                held++;
            }
        }
        int shown = screen.listing.entries.size();
        boolean all = held == shown && shown > 0;
        boolean some = held > 0 && !all;
        screen.selectAll.setChecked(all);
        // The platform box has no third state, so the dash is a drawable of its own while only
        // some of the page is in.
        if (some) {
            screen.selectAll.setButtonDrawable(R.drawable.ic_check_indeterminate);
            screen.selectAll.setButtonTintList(ColorStateList.valueOf(screen.theme.accent));
        } else if (screen.selectAll.getButtonDrawable() != screen.selectAllDefault) {
            screen.selectAll.setButtonDrawable(screen.selectAllDefault);
            screen.selectAll.setButtonTintList(selectionTint(screen.theme));
        }
        screen.selectAllWords.setText("Select all"
                + (screen.listing.available > shown ? " on this page" : "")
                + " · " + held + " of " + screen.listing.available + " in the playlist");
    }

    /** The view button in the Content card's head, redrawn for the view in force. */
    private void paintViewButton(PlaylistPage screen) {
        String view = KioskConfig.pictureViewOf(this);
        String next = PictureBrowser.nextView(view);
        int glyph = PictureBrowser.VIEW_DETAILS.equals(view) ? R.drawable.ic_view_details
                : PictureBrowser.VIEW_SMALL.equals(view) ? R.drawable.ic_view_small
                : PictureBrowser.VIEW_BIG.equals(view) ? R.drawable.ic_view_big
                : R.drawable.ic_view_list;
        ImageButton button = iconButtonSelected(screen.theme, glyph,
                PictureBrowser.viewLabel(view) + ". Switch to "
                        + PictureBrowser.viewLabel(next).toLowerCase(java.util.Locale.ROOT));
        button.setOnClickListener(v -> {
            KioskConfig.edit(this).pictureView(next).apply();
            paintViewButton(screen);
            if (screen.draft.folder != null) {
                loadPicturePane(screen);
            }
        });
        screen.pictures.trailing(button);
    }

    /**
     * Asks before deleting one of Muralis's own uploads from the panel, then takes it out of the
     * draft as well as the store; the web page has had the bin since 2026-09-10, the panel gets
     * it with the redesign so the two surfaces offer the same thing in the same place.
     */
    private void confirmDeleteUpload(PlaylistPage screen, PictureBrowser.Entry entry) {
        PlaylistDraft draft = screen.draft;
        showConfirm("Delete " + entry.name + "?",
                "The file is removed from this panel and from every playlist that holds it.",
                "Delete it",
                true,
                () -> {
                    PictureLibrary library = PictureLibrary.get(this);
                    library.run(() -> {
                        String refusal = library.deleteLocal(entry.uri);
                        library.onMain(() -> {
                            if (isFinishing() || isDestroyed()) {
                                return;
                            }
                            if (refusal != null) {
                                Toast.makeText(this, PictureLibrary.capitalise(refusal),
                                        Toast.LENGTH_LONG).show();
                            } else {
                                draft.items.remove(entry.uri);
                            }
                            KioskService.publishTelemetrySoon(this);
                            showPlaylistPage(draft);
                        });
                    });
                },
                () -> showPlaylistPage(draft));
    }

    /**
     * Show 10, 25, 50 or 100: the same four the web page offers, the one in force filled. Only
     * where there is more than the smallest page to show. A new size starts the folder over,
     * since page three of ten is nowhere in particular at fifty.
     */
    private LinearLayout pageSizeRow(PlaylistPage screen) {
        KioskTheme theme = screen.theme;
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(8), 0, 0);
        for (int size : PictureBrowser.PAGE_SIZES) {
            Button choice = size == screen.draft.pageSize
                    ? rowButton(theme, String.valueOf(size), RowColour.MAIN)
                    : rowButton(theme, String.valueOf(size));
            choice.setOnClickListener(view -> {
                screen.draft.pageSize = size;
                screen.draft.offset = 0;
                loadPicturePane(screen);
            });
            LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            gap.rightMargin = dp(8);
            row.addView(choice, gap);
        }
        return row;
    }

    /** Puts a picture's box in a given state without its own listener answering the change. */
    private void tickBox(PlaylistPage screen, String uri, boolean on) {
        CheckBox box = screen.boxes.get(uri);
        if (box == null || box.isChecked() == on) {
            return;
        }
        boolean wasSyncing = screen.syncing;
        screen.syncing = true;
        try {
            box.setChecked(on);
        } finally {
            screen.syncing = wasSyncing;
        }
    }






    /**
     * Everything picked so far: the picture, the folder and the name, then the box that names the
     * picture for its credit with the save tick inside and the remove button beside it, both
     * 40 dp on one line (Juri, 2026-09-23; the Save and Remove words that stood there read as
     * noise repeated down the list).
     *
     * <p>The path is prepended because this list is the one place two pictures with the same name
     * from two folders sit next to each other, and one folder cannot hold both, so the folder is
     * what tells them apart (Juri, 2026-09-10: a bare "test.jpg" twice named neither).
     */
    private void paintSelectedPane(PlaylistPage screen) {
        KioskTheme theme = screen.theme;
        PlaylistDraft draft = screen.draft;
        screen.selected.clear();
        if (draft.items.isEmpty()) {
            screen.selected.body.addView(
                    paneNote(theme, "Nothing picked yet. Open a folder above and tick its pictures.",
                            false), matchWrapClose());
            return;
        }
        screen.selected.body.addView(paneNote(theme, draft.items.size()
                + (draft.items.size() == 1 ? " picture" : " pictures"), false), matchWrapClose());
        PictureLibrary library = PictureLibrary.get(this);
        java.util.Map<String, String> captions = library.captions();
        List<String> unnamed = new ArrayList<>();
        List<String> items = new ArrayList<>(draft.items);
        for (int index = 0; index < items.size(); index++) {
            String uri = items.get(index);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, dp(10), 0, dp(10));
            ImageView thumb = thumbnailView(theme, dp(40));
            LinearLayout.LayoutParams thumbParams = new LinearLayout.LayoutParams(dp(40), dp(40));
            thumbParams.rightMargin = dp(12);
            thumbParams.topMargin = dp(2);
            row.addView(thumb, thumbParams);
            loadThumbnail(thumb, uri);

            LinearLayout column = new LinearLayout(this);
            column.setOrientation(LinearLayout.VERTICAL);
            TextView path = new FlushText(this);
            String shown = screen.paths.get(uri);
            if (shown == null) {
                unnamed.add(uri);
                shown = uri.substring(uri.lastIndexOf('/') + 1);
            }
            path.setText(shown);
            path.setTextColor(theme.text);
            path.setTextSize(14);
            path.setSingleLine(true);
            path.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            column.addView(path, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            // The credit field, dense, with its save tick inside the box, and the minus beside it
            // on the same line: the name belongs to the file and is stored at once, only a
            // refusal is said.
            LinearLayout acts = new LinearLayout(this);
            acts.setOrientation(LinearLayout.HORIZONTAL);
            acts.setGravity(Gravity.CENTER_VERTICAL);
            EditText credit = proseInput(theme, captions.getOrDefault(uri, ""));
            credit.setTextSize(14);
            credit.setMinHeight(dp(40));
            credit.setMinimumHeight(dp(40));
            credit.setPadding(dp(12), 0, dp(48), 0);
            credit.setOnEditorActionListener((view, actionId, event) -> {
                saveCredit(uri, credit);
                return true;
            });
            FrameLayout field = new FrameLayout(this);
            FrameLayout.LayoutParams creditParams = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            creditParams.topMargin = dp(8);
            field.addView(credit, creditParams);
            TextView label = new FlushText(this);
            label.setText("Name for the credit");
            label.setTextColor(theme.subtext);
            label.setTextSize(12);
            label.setBackgroundColor(theme.card);
            label.setPadding(dp(4), 0, dp(4), 0);
            FrameLayout.LayoutParams labelParams = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            labelParams.leftMargin = dp(8);
            field.addView(label, labelParams);
            ImageButton keep = iconButtonPrimary(theme, R.drawable.ic_check, "Save the name");
            keep.setOnClickListener(view -> saveCredit(uri, credit));
            FrameLayout.LayoutParams keepParams = new FrameLayout.LayoutParams(dp(48), dp(48));
            keepParams.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
            keepParams.topMargin = dp(4);
            field.addView(keep, keepParams);
            acts.addView(field, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            ImageButton drop = iconButton(theme, R.drawable.ic_remove_circle,
                    "Remove from the playlist");
            drop.setOnClickListener(view -> {
                draft.items.remove(uri);
                draft.edited = true;
                // The box for it, if that folder happens to be open, so the two panes never
                // disagree about what is picked.
                tickBox(screen, uri, false);
                paintSelectedPane(screen);
                paintSelectAll(screen);
            });
            LinearLayout.LayoutParams dropParams = new LinearLayout.LayoutParams(dp(48), dp(48));
            dropParams.topMargin = dp(4);
            acts.addView(drop, dropParams);
            LinearLayout.LayoutParams actsParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            actsParams.topMargin = dp(2);
            column.addView(acts, actsParams);
            row.addView(column, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            screen.selected.body.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            if (index < items.size() - 1) {
                View rule = new View(this);
                rule.setBackgroundColor(theme.outlineVariant);
                screen.selected.body.addView(rule, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
            }
        }
        if (!unnamed.isEmpty()) {
            // The labels arrive from the worker and the pane is painted once more; every name is
            // in the map by then, so that second paint asks for nothing and this ends.
            library.run(() -> {
                java.util.Map<String, String> named = new java.util.HashMap<>();
                for (String uri : unnamed) {
                    named.put(uri, library.displayPath(uri));
                }
                library.onMain(() -> {
                    if (screen.gone()) {
                        return;
                    }
                    screen.paths.putAll(named);
                    paintSelectedPane(screen);
                });
            });
        }
    }

    /** Stores one picture's credit name from its box, at once and quietly; only a refusal is said. */
    private void saveCredit(String uri, EditText credit) {
        String typed = credit.getText().toString();
        hideKeyboard(credit);
        PictureLibrary library = PictureLibrary.get(this);
        library.run(() -> {
            String refusal = library.setCaption(uri, typed);
            library.onMain(() -> {
                if (refusal != null && !isFinishing() && !isDestroyed()) {
                    Toast.makeText(this, PictureLibrary.capitalise(refusal), Toast.LENGTH_LONG)
                            .show();
                }
            });
        });
    }

    /** Leaving with unsaved changes asks first; leaving an untouched page just goes back. */
    private void leavePlaylistPage(PlaylistDraft draft) {
        if (!draft.edited) {
            playlistDraft = null;
            showScreensaverSettings();
            return;
        }
        showConfirm("Discard this playlist?",
                draft.id == null
                        ? "It has not been saved, so nothing will be kept."
                        : "The changes you made will not be kept.",
                "Discard",
                true,
                () -> {
                    playlistDraft = null;
                    showScreensaverSettings();
                },
                () -> showPlaylistPage(draft));
    }

    /**
     * Writes the draft.
     *
     * <p>Refused, with the reason on the screen, until the name is a name nothing else uses and at
     * least one picture is ticked. A new playlist becomes the one in use when nothing else is,
     * because somebody who has just picked pictures means to see them.
     */
    private void savePlaylist(PlaylistDraft draft) {
        PictureLibrary library = PictureLibrary.get(this);
        if (draft.items.isEmpty()) {
            Toast.makeText(this, "Tick at least one picture first.", Toast.LENGTH_LONG).show();
            return;
        }
        library.run(() -> {
            // One edit under the library's lock, like every other surface's: a Save that loaded,
            // changed and stored on its own could store over a switch Home Assistant made meanwhile.
            final String said = library.editPlaylists(document -> {
                String refusal = document.nameProblem(draft.name, draft.id);
                String id = draft.id;
                long now = System.currentTimeMillis();
                if (refusal == null && id == null) {
                    id = library.playlists().newId();
                    refusal = document.create(id, draft.name, now);
                } else if (refusal == null) {
                    refusal = document.rename(id, draft.name, now);
                }
                if (refusal != null) {
                    return refusal;
                }
                PlaylistDocument.Playlist playlist = document.byId(id);
                List<String> gone = new ArrayList<>(playlist.items);
                gone.removeAll(draft.items);
                document.remove(id, gone, now);
                refusal = document.add(id, draft.items, now);
                if (refusal == null) {
                    refusal = document.reorder(id, draft.items, now);
                }
                if (document.active() == null) {
                    document.activate(id);
                }
                return refusal;
            });
            library.onMain(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (said != null) {
                    Toast.makeText(this, PictureLibrary.capitalise(said), Toast.LENGTH_LONG).show();
                    return;
                }
                playlistDraft = null;
                KioskService.publishTelemetrySoon(this);
                Toast.makeText(this, "Playlist saved.", Toast.LENGTH_SHORT).show();
                showScreensaverSettings();
            });
        });
    }

    private EditText secondsInput(KioskTheme theme, int seconds) {
        EditText input = themedInput(theme, String.valueOf(seconds), false);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        return input;
    }

    /**
     * Runs the apply when the box loses the focus, which is also what the keyboard's Done does
     * ({@link #hideKeyboard} clears the focus), so the two ways a person says "that is the
     * value" run it exactly once.
     */
    private void onApply(EditText input, Runnable apply) {
        input.setOnFocusChangeListener((view, hasFocus) -> {
            if (!hasFocus) {
                apply.run();
            }
        });
        input.setOnEditorActionListener((view, actionId, event) -> {
            hideKeyboard(view);
            return true;
        });
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
                "Tap combinations that unlock the kiosk",
                () -> showConfiguration(KioskConfig.load(this))), matchWrap());

        TextView explain = new FlushText(this);
        explain.setTextColor(theme.subtext);
        explain.setTextSize(14);
        explain.setText("A combination is a series of taps in the corners of the screen. Record "
                + "your own so it is not the same on every device, and keep it to yourself, "
                + "anyone who watches you perform it can repeat it.");
        page.addView(explain, matchWrap());

        page.addView(cardGrid(theme, java.util.Arrays.<View>asList(
                sequenceCard(theme, "Open Muralis settings", config.settingsSequence, false),
                sequenceCard(theme, "Leave Muralis for the home screen", config.launcherSequence, true),
                pinCard(theme))),
                matchWrap());

        setContentView(scrollPage(theme, page));
        currentScreen = () -> showEscapeSequences(KioskConfig.load(this));
    }

    /** The optional PIN, on the same page as the two combinations it stands behind. */
    private LinearLayout pinCard(KioskTheme theme) {
        boolean set = KioskConfig.escapePinSet(this);
        LinearLayout item = card(theme, "PIN after a combination");
        TextView current = new FlushText(this);
        current.setTextColor(set ? theme.accentAlt : theme.subtext);
        current.setTextSize(14);
        current.setText(set
                ? "A PIN is asked after either combination."
                : "Optional. A combination can be watched and repeated; a PIN has to be known.");
        item.addView(current, matchWrap());
        EditText pin = themedInput(theme, "", true);
        pin.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        pin.setHint("New PIN, " + EscapePin.MIN_LENGTH + " to " + EscapePin.MAX_LENGTH + " digits");
        item.addView(pin, matchWrap());
        EditText again = themedInput(theme, "", true);
        again.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        again.setHint("Repeat it");
        item.addView(again, matchWrap());
        Button save = secondaryButton(theme, set ? "Change the PIN" : "Set the PIN");
        save.setOnClickListener(view -> {
            String typed = pin.getText().toString();
            String problem = EscapePin.validationProblem(typed);
            if (problem != null) {
                Toast.makeText(this, "Not saved: " + problem + ".", Toast.LENGTH_LONG).show();
                return;
            }
            if (!typed.equals(again.getText().toString())) {
                Toast.makeText(this, "Not saved: the two PINs differ.", Toast.LENGTH_LONG).show();
                return;
            }
            KioskConfig.setEscapePinHash(this, EscapePin.hash(typed));
            hideKeyboard(pin);
            Toast.makeText(this, "PIN set.", Toast.LENGTH_SHORT).show();
            redrawInPlace(() -> showEscapeSequences(KioskConfig.load(this)));
        });
        if (!set) {
            item.addView(buttonRow(save), matchWrap());
        }
        if (set) {
            // Confirmed with the PIN itself (2026-09-09): a settings screen left open must not be
            // enough to take the second lock away. The web admin's removal stays unconfirmed on
            // purpose, it is the reset for a forgotten PIN.
            Button remove = dangerButton(theme, "Remove the PIN");
            remove.setOnClickListener(view -> showPinPrompt("To remove the PIN", () -> {
                KioskConfig.setEscapePinHash(this, null);
                Toast.makeText(this, "PIN removed.", Toast.LENGTH_SHORT).show();
                showEscapeSequences(KioskConfig.load(this));
            }, () -> showEscapeSequences(KioskConfig.load(this))));
            item.addView(buttonRow(save, remove), matchWrap());
        }
        return item;
    }

    private LinearLayout sequenceCard(KioskTheme theme, String title, String sequence,
            boolean forLauncher) {
        LinearLayout item = card(theme, title);
        TextView current = new FlushText(this);
        current.setTextColor(theme.accentAlt);
        current.setTextSize(16);
        current.setText(EscapeSequence.describe(EscapeSequence.parse(sequence)));
        item.addView(current, matchWrap());
        Button record = secondaryButton(theme, "Record a new combination");
        record.setOnClickListener(view -> showSequenceRecorder(forLauncher));
        item.addView(buttonRow(record), matchWrap());
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
                "First, your way back out"), matchWrap());

        // Four short blocks rather than one paragraph, and the first line carries the whole
        // message on its own. Watched on 2026-09-08: a tester who had never seen Muralis skipped
        // the old 75-word block entirely and found the settings combination by pressing corners at
        // random. Someone who reads one sentence here and stops still learns what corner taps are
        // for; the two combinations are a list because they used to sit mid-sentence after a
        // colon, and the secrecy note is last because it is the only part she could not have
        // discovered by poking.
        TextView lead = new FlushText(this);
        lead.setTextColor(theme.text);
        lead.setTextSize(15);
        lead.setText("Muralis covers the whole screen. Tapping the corners in your own order is "
                + "how you get back out.");
        page.addView(lead, matchWrap());

        TextView listCaption = new FlushText(this);
        listCaption.setTextColor(theme.subtext);
        listCaption.setTextSize(14);
        listCaption.setText("Record two combinations now:");
        page.addView(listCaption, matchWrap());

        TextView list = new FlushText(this);
        list.setTextColor(theme.text);
        list.setTextSize(15);
        list.setLineSpacing(dp(6), 1f);
        list.setText("1.   opens Muralis settings\n2.   leaves Muralis for the home screen");
        page.addView(list, matchWrapClose());

        TextView note = new FlushText(this);
        note.setTextColor(theme.subtext);
        note.setTextSize(13);
        note.setText("You can change them later in settings. Keep them to yourself, anyone who "
                + "watches you tap can do it too.");
        page.addView(note, matchWrap());

        Button start = primaryButton(theme, "Record the first one");
        start.setOnClickListener(view -> showWizardRecorder(false));
        page.addView(buttonRow(start), matchWrap());

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
            TextView step = new FlushText(this);
            step.setText(forLauncher ? "Step 2 of 2" : "Step 1 of 2");
            step.setTextColor(theme.accentAlt);
            step.setTextSize(13);
            step.setGravity(Gravity.CENTER);
            panel.addView(step, matchWrap());
        }

        TextView title = new FlushText(this);
        title.setText(forLauncher ? "Leave Muralis for the home screen" : "Open Muralis settings");
        title.setTextColor(theme.text);
        title.setTextSize(22);
        title.setGravity(Gravity.CENTER);
        panel.addView(title, matchWrap());

        TextView hint = new FlushText(this);
        hint.setText("Tap the highlighted corners in the order you want. Between "
                + EscapeSequence.MIN_LENGTH + " and " + EscapeSequence.MAX_LENGTH
                + " taps, and do not stop for more than "
                + (EscapeSequence.MAX_GAP_MS / 1000) + " seconds.");
        hint.setTextColor(theme.subtext);
        hint.setTextSize(14);
        hint.setGravity(Gravity.CENTER);
        panel.addView(hint, matchWrap());

        recorderReadout = new FlushText(this);
        recorderReadout.setTextColor(theme.ok);
        recorderReadout.setTextSize(18);
        recorderReadout.setGravity(Gravity.CENTER);
        recorderReadout.setText("nothing recorded yet");
        panel.addView(recorderReadout, matchWrap());

        Button save = primaryButton(theme, "Save this combination");
        save.setOnClickListener(view -> saveRecordedSequence());
        panel.addView(centeredButtonRow(save), matchWrap());

        Button clear = secondaryButton(theme, "Start over");
        clear.setOnClickListener(view -> {
            recordedZones.clear();
            updateRecorderReadout();
        });
        panel.addView(centeredButtonRow(clear), matchWrap());

        Button cancel = secondaryButton(theme, recordingForWizard ? "Back" : "Cancel");
        cancel.setOnClickListener(view -> {
            recorderVisible = false;
            if (recordingForWizard) {
                showFirstStartWizard();
            } else {
                showEscapeSequences(KioskConfig.load(this));
            }
        });
        panel.addView(centeredButtonRow(cancel), matchWrap());

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
        TextView target = new FlushText(this);
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
     *
     * <p><b>Below API 30 it must not ask {@code getSystemWindowInsets}, which is what it used to
     * do.</b> Those are the CONTENT insets, and {@code SYSTEM_UI_FLAG_LAYOUT_STABLE}, which the
     * kiosk sets precisely so hiding a bar does not reshuffle the layout, freezes them at the bar
     * heights whether or not a bar is on screen. So a device-owner panel with both bars hidden
     * for the whole life of the process still reported a navigation bar, and both the drawn
     * corner targets and the listening band dodged a bar that was not there.
     *
     * <p>Measured on the Lenovo, Android 10 device owner, 1280x800 with a 48px bar, 2026-09-08:
     * the bottom targets were drawn 56px above the screen edge while the top ones sat 8px from
     * it, and the bottom band ran from y=656 to the last row, 144px instead of the 96 it is meant
     * to be. The squares were seen floating above a bar that had never been visible (2026-09-08). The tablet's
     * own escape combination still worked, because the drawn square and the band had moved
     * together, which is why this survived unnoticed on the wall panel too.
     *
     * <p>The visible display frame is asked instead, because "where is this window actually
     * visible" is the real question and it is the only spelling of it the older API has. The
     * keyboard shrinks that frame as well, so the difference is capped at the edge's stable inset
     * by {@link SystemBarOverlap}: at most one bar, zero once the bar is gone, and no size
     * written down anywhere.
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
        int[] contentOrigin = new int[2];
        int[] decorOrigin = new int[2];
        content.getLocationOnScreen(contentOrigin);
        decor.getLocationOnScreen(decorOrigin);
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
            // enterImmersiveMode. Every number here is measured: the frame comes from the window
            // and the caps from the platform's own stable insets, so a 48px bar at 160dpi and an
            // 82px one at 272dpi need no distinguishing and nothing is written down.
            //
            // Each edge's cap is zero while that bar is hidden, which is what makes a hidden bar
            // collapse to nothing even with the keyboard up. Without the gate the cap alone would
            // report a full bar whenever the visible frame shrank for any reason, and the soft
            // keyboard shrinks it by hundreds of pixels on the configuration screen, where the
            // escape combination has to keep working.
            Rect visible = new Rect();
            decor.getWindowVisibleDisplayFrame(visible);
            int visibility = decor.getWindowSystemUiVisibility();
            boolean navigationHidden = (visibility & View.SYSTEM_UI_FLAG_HIDE_NAVIGATION) != 0;
            boolean statusHidden = (visibility & View.SYSTEM_UI_FLAG_FULLSCREEN) != 0
                    || (getWindow().getAttributes().flags
                            & WindowManager.LayoutParams.FLAG_FULLSCREEN) != 0;
            left = SystemBarOverlap.leading(decorOrigin[0], visible.left,
                    navigationHidden ? 0 : insets.getStableInsetLeft());
            top = SystemBarOverlap.leading(decorOrigin[1], visible.top,
                    statusHidden ? 0 : insets.getStableInsetTop());
            right = SystemBarOverlap.trailing(decorOrigin[0] + decor.getWidth(), visible.right,
                    navigationHidden ? 0 : insets.getStableInsetRight());
            bottom = SystemBarOverlap.trailing(decorOrigin[1] + decor.getHeight(), visible.bottom,
                    navigationHidden ? 0 : insets.getStableInsetBottom());
        }
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

    /** Opens the keyboard on a box the page itself put the focus in, which alone does not open it. */
    private void showKeyboard(View focused) {
        android.view.inputmethod.InputMethodManager keyboard = (android.view.inputmethod
                .InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (keyboard != null) {
            keyboard.showSoftInput(focused, 0);
        }
    }

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
     * a window carrying {@link WindowManager.LayoutParams#FLAG_FULLSCREEN}, which every screen of a
     * device-owner panel does, because a kiosk has no business showing a status bar. The window
     * therefore never shrinks and the keyboard is simply drawn on top of it. The IME does still reduce
     * the window's visible display frame, though, so the gap between the bottom of the content and that
     * frame's bottom is what the keyboard covers.
     *
     * <p><b>Why it is measured against the content view and not the decor:</b> an ordinary install
     * shows its system bars, so its window is not fullscreen, and there {@code adjustResize} does work:
     * Android itself shrinks the content to the keyboard's top edge. Measuring against the decor's
     * height, which does not shrink, reported the keyboard's height a second time, and both callers
     * then took it away again: on the phone a third of the settings form was visible, then a band of
     * background the size of the keyboard, then the keyboard (seen 2026-09-07; in every build since
     * the bars were shown on ordinary installs, 2026-08-21). Against the content's own bottom edge the
     * gap is the keyboard on a fullscreen window and zero on one the system already resized, which is
     * exactly the amount the caller still has to give back.
     *
     * <p><b>Why the keyboard is read as a window inset from API 30:</b> the visible display frame is the
     * old report and it is fading out. An app that draws edge to edge, which every app targeting API 35
     * does, gets no resized window and no shrunk visible frame when the keyboard opens; the keyboard
     * arrives only as {@code WindowInsets.Type.ime()}, and only through an insets pass, which need not
     * trigger any layout. Found on the Pixel 9 Pro XL, Android 17, 2026-09-07: the keyboard covered the
     * lower half of the settings form and the focused box with it, and this method measured zero.
     * So from API 30 the keyboard's top edge is the window's bottom less the IME inset, the measurement
     * also runs whenever insets are applied, and the older report stays for the Android 8 and 9 devices.
     *
     * <p>Both callers use this to give back the space themselves: the configuration screens as scroll
     * padding, the dashboard by shrinking the WebView. Worst in landscape, the orientation a wall panel
     * is fixed in, because the keyboard takes a much larger share of a short screen.
     *
     * @param anchor a view in the hierarchy, used only for its window and lifecycle
     * @param onInset called with the covered height in pixels, or 0 when nothing is covered
     */
    private void trackKeyboardInset(View anchor, java.util.function.IntConsumer onInset) {
        // Remembers the last value so the listener, which fires on every layout pass, does not
        // re-trigger itself by changing layout.
        final int[] applied = {-1};
        final Runnable measure = () -> {
            View decor = getWindow().getDecorView();
            View content = findViewById(android.R.id.content);
            int[] location = new int[2];
            content.getLocationOnScreen(location);
            int contentBottom = location[1] + content.getHeight();
            int keyboardTop;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.view.WindowInsets rootInsets = decor.getRootWindowInsets();
                int ime = rootInsets == null ? 0
                        : rootInsets.getInsets(android.view.WindowInsets.Type.ime()).bottom;
                if (ime == 0) {
                    keyboardTop = Integer.MAX_VALUE;
                } else {
                    decor.getLocationOnScreen(location);
                    keyboardTop = location[1] + decor.getHeight() - ime;
                }
            } else {
                Rect visible = new Rect();
                decor.getWindowVisibleDisplayFrame(visible);
                keyboardTop = visible.bottom;
            }
            int inset = Math.max(0, contentBottom - keyboardTop);
            // A navigation bar or display cutout also shrinks the visible frame. Only a gap big enough
            // to be a keyboard counts, so ordinary layout does not gain phantom padding.
            if (inset < dp(MIN_KEYBOARD_INSET_DP)) {
                inset = 0;
            }
            if (inset != applied[0]) {
                applied[0] = inset;
                Log.d(TAG, "Keyboard covers " + inset + "px of the content (content bottom "
                        + contentBottom + ", keyboard top " + keyboardTop + ")");
                onInset.accept(inset);
            }
        };
        // Registered on attach and removed on detach. Without the removal these leak: a fresh view is
        // built on every screen change, ViewTreeObserver listeners are not dropped when a view is
        // detached, and the observer belongs to the window rather than the view, so every screen visit
        // would leave another listener firing forever against a dead view.
        final android.view.ViewTreeObserver.OnGlobalLayoutListener layoutListener = measure::run;
        // The insets pass is the only signal an edge-to-edge window gets when the keyboard opens or
        // closes, and it does not lay anything out by itself, so measure after it too. Nothing is
        // consumed: the insets go on down to the children unchanged.
        anchor.setOnApplyWindowInsetsListener((view, insets) -> {
            view.post(measure);
            return insets;
        });
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
            // Scrolled by hand, against the part of the scroll view the keyboard does not cover,
            // which is its height less the bottom padding the measurement adds. ScrollView's own
            // requestRectangleOnScreen judges against its full height and ignores that padding,
            // so on every window that is padded rather than resized (the kiosk, and every
            // edge-to-edge phone) it saw the field as already visible and did nothing; the field
            // stayed under the keyboard (Pixel 9 Pro XL, Android 17, 2026-09-07). Asks for a
            // little more than the field's own height so the next field, and any error text
            // under it, are not left flush against the keyboard.
            scroll.post(() -> {
                if (focused.getWindowToken() == null || scroll.getWindowToken() == null) {
                    return;
                }
                int[] fieldAt = new int[2];
                focused.getLocationInWindow(fieldAt);
                int[] scrollAt = new int[2];
                scroll.getLocationInWindow(scrollAt);
                int visibleTop = scrollAt[1] + scroll.getPaddingTop();
                int visibleBottom = scrollAt[1] + scroll.getHeight() - scroll.getPaddingBottom();
                int fieldTop = fieldAt[1];
                int fieldBottom = fieldAt[1] + focused.getHeight() + dp(24);
                if (fieldBottom > visibleBottom) {
                    scroll.smoothScrollBy(0, fieldBottom - visibleBottom);
                } else if (fieldTop < visibleTop) {
                    scroll.smoothScrollBy(0, fieldTop - visibleTop - dp(24));
                }
            });
        };
        trackKeyboardInset(scroll, inset -> {
            scroll.setPadding(scroll.getPaddingLeft(), scroll.getPaddingTop(),
                    scroll.getPaddingRight(), inset);
            if (inset > 0) {
                revealFocused.run();
            }
        });
        // On an ordinary install the window itself shrinks for the keyboard (see
        // trackKeyboardInset), so the inset above stays zero and ScrollView's own onSizeChanged
        // does the scrolling, flush against the keyboard. Same margin as the fullscreen case.
        scroll.addOnLayoutChangeListener((view, left, top, right, bottom,
                oldLeft, oldTop, oldRight, oldBottom) -> {
            if (bottom - top < oldBottom - oldTop) {
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
     * Cards in two lanes from 840 dp, Material's expanded width, and one under the other below
     * it: the same boundary the web page uses for its two columns, so the two surfaces break at
     * the same place.
     */
    private ViewGroup cardGrid(KioskTheme theme, List<View> cards) {
        int columns = getResources().getConfiguration().screenWidthDp >= 840 ? 2 : 1;
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
            int gap = dp(8);
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

        TextView notice = new FlushText(this);
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
    private Button defaultLauncherPrompt(KioskTheme theme) {
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
        page.addView(pageHeading(theme, "About", appVersionSummary(),
                () -> showConfiguration(KioskConfig.load(this))), matchWrap());

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
        // The parking page shows the Android robot, shared by Google under CC BY 3.0, and this is
        // the credit for it: author, licence, modified, like the two rows above it. Here rather than
        // on the parking page itself, because the page is a full-screen illustration and this is
        // where the app already says who it owes what to. See the string's comment for Google's
        // own longer phrasing and why the row does not carry it.
        addAboutRow(creditsCard, theme, "Android robot", getString(R.string.android_robot_attribution));
        // Every glyph on both surfaces is drawn after Google's Material Symbols, as a stroke path
        // of our own; the shapes are theirs and this says so.
        addAboutRow(creditsCard, theme, "Icons", "After Material Symbols by Google (Apache 2.0)");

        // No Legal card here. The privacy policy and the terms are rows of the About section on
        // the settings page, two taps from the panel, and this page is one tap further in: the
        // same two documents in both places was one of them too many (Juri, 2026-09-23).

        // Order chosen for cardGrid's round-robin: the two short cards share a lane, the taller
        // device card takes the other.
        page.addView(cardGrid(theme, java.util.Arrays.<View>asList(
                appCard, deviceCard, creditsCard)), matchWrap());

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
        page.addView(pageHeading(theme, getString(titleRes), appVersionSummary(),
                () -> showConfiguration(KioskConfig.load(this))), matchWrap());

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
        TextView published = new FlushText(this);
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
            TextView view = new FlushText(this);
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

        TextView labelView = new FlushText(this);
        labelView.setText(label);
        labelView.setTextSize(14);
        labelView.setTextColor(theme.subtext);
        row.addView(labelView, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 4f));

        TextView valueView = new FlushText(this);
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


    /**
     * The widest a page's content ever gets, whatever the panel's own width.
     *
     * <p>Beyond this the gutters grow instead of the column: a settings page stretched across a
     * very wide display turns every field into a bar, which is the thing the gutters exist to
     * prevent in the first place.
     */
    private static final int MAX_CONTENT_DP = 1000;

    /**
     * Every page's column: the content centred with a gutter each side, so nothing is ever laid
     * out against the glass (Juri, 2026-09-23, with the drawing in media/drafts/material3/).
     *
     * <p>5% of the width, whatever the shape of the screen (Juri, 2026-09-23: the 10% that
     * landscape had was too much). Floored at Material's own margin for the size class, 16 dp
     * compact and 24 dp from medium, so a genuinely small screen still has a readable column, and
     * raised past the percentage once the column would pass {@link #MAX_CONTENT_DP}.
     */
    private LinearLayout pageColumn(KioskTheme theme) {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setBackgroundColor(theme.base);
        int side = dp(gutterDp());
        column.setPadding(side, dp(12), side, dp(32));
        return column;
    }

    /** The gutter each side of a page: see {@link #pageColumn}. */
    private int gutterDp() {
        int widthDp = getResources().getConfiguration().screenWidthDp;
        int sideDp = Math.max(widthDp >= 600 ? 24 : 16, Math.round(widthDp * 0.05f));
        return widthDp - sideDp * 2 > MAX_CONTENT_DP
                ? (widthDp - MAX_CONTENT_DP) / 2 : sideDp;
    }

    /** What a page has to lay out in, the gutters taken off. */
    private int contentWidthDp() {
        return getResources().getConfiguration().screenWidthDp - gutterDp() * 2;
    }


    private LinearLayout pageHeading(KioskTheme theme, String title, String subtitle) {
        return pageHeading(theme, titleText(theme, title), subtitle, null, null, null);
    }

    /** The heading of a page below the settings: its name, and the arrow that leads back. */
    private LinearLayout pageHeading(KioskTheme theme, String title, String subtitle,
            Runnable back) {
        return pageHeading(theme, titleText(theme, title), subtitle, null, back, null);
    }


    /** The page title, Material's headline: the text colour, not the accent, since 2026-09-23. */
    private TextView titleText(KioskTheme theme, String title) {
        TextView main = new FlushText(this);
        main.setText(title);
        main.setTextColor(theme.text);
        main.setTextSize(24);
        return main;
    }


    private LinearLayout pageHeading(KioskTheme theme, TextView main, String subtitle,
            View beside) {
        return pageHeading(theme, main, subtitle, beside, null, null);
    }

    /**
     * The app bar: the way back at the left where a page has one, the title with an optional view
     * against its last letter (the playlist page's pencil), the subtitle under it, then the status
     * chip and, on the settings screen, the theme toggle at the right.
     *
     * <p>Back is the arrow in the app bar, once, where every Material screen has it; the "← Back"
     * buttons at the head and foot of a page went with the redesign of 2026-09-23.
     */
    private LinearLayout pageHeading(KioskTheme theme, TextView main, String subtitle,
            View beside, Runnable back, View trailing) {
        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.HORIZONTAL);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.setMinimumHeight(dp(64));

        if (back != null) {
            ImageButton arrow = iconButton(theme, R.drawable.ic_back, "Back");
            arrow.setOnClickListener(view -> back.run());
            LinearLayout.LayoutParams arrowParams = new LinearLayout.LayoutParams(dp(48), dp(48));
            arrowParams.rightMargin = dp(8);
            heading.addView(arrow, arrowParams);
        }

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        if (beside == null) {
            titles.addView(main);
        } else {
            // Against the title's last letter, not out by the chip: the pencil belongs to the name
            // it edits (Juri, 2026-09-19). The title's width is capped to what leaves the pencil
            // room, since a horizontal LinearLayout measures in order and a long name would
            // otherwise push the pencil off the row.
            LinearLayout titleRow = new LinearLayout(this);
            titleRow.setOrientation(LinearLayout.HORIZONTAL);
            titleRow.setGravity(Gravity.CENTER_VERTICAL);
            titleRow.addView(main, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            LinearLayout.LayoutParams besideParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            besideParams.leftMargin = dp(4);
            titleRow.addView(beside, besideParams);
            titleRow.addOnLayoutChangeListener((view, l, t, r, b, ol, ot, or, ob) -> {
                int room = (r - l) - beside.getWidth() - besideParams.leftMargin;
                if (room > 0 && main.getMaxWidth() != room) {
                    main.setMaxWidth(room);
                }
            });
            titles.addView(titleRow);
        }
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView sub = new FlushText(this);
            sub.setText(subtitle);
            sub.setTextColor(theme.subtext);
            sub.setTextSize(12);
            titles.addView(sub);
            if (getResources().getConfiguration().screenWidthDp < 840) {
                // This line is the narrow app bar's chip: the readings are appended to what the
                // page has to say for itself, and the same tick keeps them current.
                statusLine = sub;
                statusLinePrefix = subtitle;
                updateStatusChip();
            }
        }
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        // A gap the title cannot be laid out into: on a phone "Picture playlist" filled its column
        // exactly and ran straight into "75% charging" with no space between them (2026-09-10).
        titleParams.rightMargin = dp(12);
        heading.addView(titles, titleParams);

        // The chip only where the app bar has room for it, which is the rule the web page
        // already follows: on a phone the title, three rows of readings and the theme toggle do
        // not fit one bar, and the title was squeezed to "Murali / s" (measured 2026-09-23). Below
        // 840 dp the same readings go on the subtitle line instead, one line, still live.
        if (getResources().getConfiguration().screenWidthDp >= 840) {
            heading.addView(buildStatusChip(theme), new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        if (trailing != null) {
            LinearLayout.LayoutParams trailingParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            trailingParams.leftMargin = dp(8);
            heading.addView(trailing, trailingParams);
        }
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

    /** The narrow app bar's status line, and what it says before the readings. */
    private TextView statusLine;
    private String statusLinePrefix = "";

    /**
     * Writes the latest reading into the chip, or into the narrow app bar's line. Returns false
     * once the screen holding either has gone, so the tick can stop.
     */
    private boolean updateStatusChip() {
        boolean painted = false;
        if (statusLine != null) {
            if (statusLine.getParent() == null) {
                statusLine = null;
            } else {
                SystemStats.RuntimeFacts line = KioskRuntimeState.lastFacts();
                StringBuilder said = new StringBuilder(statusLinePrefix);
                if (line != null && line.batteryPercent >= 0) {
                    said.append(" · ").append(Math.round(line.batteryPercent))
                            .append("% ").append(SystemStats.chargeStateLabel(line));
                }
                if (line != null && !line.ipAddress.isEmpty()) {
                    said.append(" · ").append(line.ipAddress);
                }
                statusLine.setText(said);
                painted = true;
            }
        }
        if (batteryValue == null || statusChipTheme == null) {
            return painted;
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
        statusLine = null;
        statusLinePrefix = "";
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
        TextView view = new FlushText(this);
        view.setText(text);
        view.setTextSize(textSize);
        view.setTextColor(textSize >= 18 ? theme.text : theme.subtext);
        return view;
    }


    /**
     * A Material card: the base lifted a step, a 12 dp corner, 16 dp inside, its title in the
     * medium weight. No border since 2026-09-23; the framed box with the accent hairline went with
     * the redesign, on both surfaces.
     */
    private LinearLayout card(KioskTheme theme, String title) {
        LinearLayout group = new LinearLayout(this);
        group.setOrientation(LinearLayout.VERTICAL);
        group.setBackground(theme.panel(theme.card, dp(12)));
        int pad = dp(16);
        group.setPadding(pad, pad, pad, dp(20));
        // An empty title adds no view at all. Passing "" used to leave a blank heading TextView
        // reserving a line, which read as an unexplained gap at the top of the card.
        if (title != null && !title.isEmpty()) {
            group.addView(cardTitle(theme, title));
        }
        return group;
    }

    /** A card's name: Material's title-medium, 16 sp in the medium weight. */
    private TextView cardTitle(KioskTheme theme, String title) {
        TextView heading = new FlushText(this);
        heading.setText(title);
        heading.setTextColor(theme.text);
        heading.setTextSize(16);
        heading.setTypeface(MEDIUM);
        heading.setPadding(0, 0, 0, dp(4));
        return heading;
    }


    /**
     * A caption over a group of radios, e.g. "Orientation": Material's label, 13 sp in the medium
     * weight, in the quieter colour. A parenthetical tail like " (seconds)" is information rather
     * than heading, so it stays at the plain weight.
     */
    private TextView fieldCaption(KioskTheme theme, String label) {
        TextView caption = new FlushText(this);
        int aside = label.indexOf(" (");
        if (aside >= 0) {
            SpannableString styled = new SpannableString(label);
            styled.setSpan(new RelativeSizeSpan(12f / 13f), aside, label.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            caption.setText(styled);
        } else {
            caption.setText(label);
        }
        caption.setTextColor(theme.subtext);
        caption.setTextSize(13);
        caption.setTypeface(MEDIUM);
        return caption;
    }


    /**
     * An outlined text field: the box with its label on the border, Material's shape for a value
     * somebody types (2026-09-23). The label floats where the border is, on a patch of the card's
     * own colour, so it reads as part of the box and the layout never moves.
     *
     * <p>Returns the label, as before, for the callers that show or hide a field with its mode;
     * {@link #fieldOf} gives them the whole box to hide.
     */
    private TextView addField(LinearLayout parent, KioskTheme theme, String label,
            EditText input) {
        FrameLayout frame = new FrameLayout(this);
        FrameLayout.LayoutParams inputParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        // Room above the box for the half of the label that stands over the border.
        inputParams.topMargin = dp(8);
        frame.addView(input, inputParams);
        TextView caption = new FlushText(this);
        caption.setText(label);
        caption.setTextColor(theme.subtext);
        caption.setTextSize(12);
        caption.setSingleLine(true);
        caption.setEllipsize(android.text.TextUtils.TruncateAt.END);
        caption.setBackgroundColor(theme.card);
        caption.setPadding(dp(4), 0, dp(4), 0);
        FrameLayout.LayoutParams captionParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        captionParams.gravity = Gravity.TOP | Gravity.START;
        captionParams.leftMargin = dp(12);
        frame.addView(caption, captionParams);
        LinearLayout.LayoutParams frameParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        // 24 dp between fields on Material's grid, less the 8 dp the label already stands in.
        frameParams.topMargin = dp(16);
        parent.addView(frame, frameParams);
        return caption;
    }

    /**
     * The same field with a helper line under the box, in the field's own slot: "Blank keeps the
     * current one." for a password box, which used to sit in the label's brackets (2026-09-23).
     */
    private TextView addField(LinearLayout parent, KioskTheme theme, String label,
            EditText input, String support) {
        TextView caption = addField(parent, theme, label, input);
        TextView note = new FlushText(this);
        note.setText(support);
        note.setTextColor(theme.subtext);
        note.setTextSize(12);
        note.setPadding(dp(16), 0, dp(16), 0);
        LinearLayout.LayoutParams noteParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        noteParams.topMargin = dp(4);
        parent.addView(note, noteParams);
        return caption;
    }

    /**
     * What {@link #addField} leaves above a box: 16 dp of grid, then the 8 dp the floating label
     * stands in. A button beside the box has to start below both to sit on the box's centre line.
     */
    private static final int FIELD_BOX_TOP_DP = 24;

    /**
     * Buttons beside a field, on the centre line of the box and not of the label (Juri,
     * 2026-09-23). They stand in a slot that begins where the box begins and is as tall as the
     * box, whatever height the box turns out to have, so nothing here depends on guessing that
     * height: the old version aligned the button's foot to the box's foot and a taller box lifted
     * it off centre.
     *
     * <p>{@code row} is the horizontal layout that already holds the field's own column.
     */
    private void addBesideBox(LinearLayout row, View... buttons) {
        LinearLayout slot = new LinearLayout(this);
        slot.setOrientation(LinearLayout.HORIZONTAL);
        slot.setGravity(Gravity.CENTER_VERTICAL);
        for (View button : buttons) {
            LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            gap.leftMargin = dp(8);
            slot.addView(button, gap);
        }
        LinearLayout.LayoutParams slotParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
        slotParams.topMargin = dp(FIELD_BOX_TOP_DP);
        row.addView(slot, slotParams);
    }

    /** The whole field a caption from {@link #addField} belongs to, for showing or hiding it. */
    private static View fieldOf(TextView caption) {
        return caption.getParent() instanceof View ? (View) caption.getParent() : caption;
    }

    /**
     * Paints a field's outline: the resting hairline, or a verdict's colour one step thicker so
     * it reads at arm's length. One method, so the field, the pre-check and the broker check
     * draw the same box.
     */
    private void outlineField(EditText field, KioskTheme theme, int colour, int strokeDp) {
        field.setBackground(theme.outlinedPanel(Color.TRANSPARENT, dp(4), dp(strokeDp), colour));
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
        // "test.mosquitto.org" typed into the broker box arrived as "test. mosquito. org" (seen
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
        input.setTextSize(16);
        input.setHintTextColor(theme.subtext);
        // Material's outlined box: a hairline on the card, 4 dp corners, 56 dp tall, 16 dp in.
        outlineField(input, theme, theme.border, 1);
        input.setMinHeight(dp(56));
        input.setMinimumHeight(dp(56));
        input.setGravity(Gravity.CENTER_VERTICAL);
        input.setPadding(dp(16), 0, dp(16), 0);
        return input;
    }

    /**
     * A box for words rather than a machine value: a playlist's name, a picture's credit. The same
     * box as {@link #themedInput}, but without the visible-password variation, which Android draws
     * in monospace and which read as a terminal beside the web page's ordinary text box
     * (2026-09-19). Suggestions stay off, as the web page's {@code autocorrect="off"} has them.
     */
    private EditText proseInput(KioskTheme theme, String value) {
        EditText input = themedInput(theme, value, false);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setTypeface(Typeface.DEFAULT);
        return input;
    }


    /** The accent for a checked box or radio, the quiet colour for an empty one. */
    private static ColorStateList selectionTint(KioskTheme theme) {
        return new ColorStateList(new int[][] {
                new int[] {-android.R.attr.state_enabled},
                new int[] {android.R.attr.state_checked}, new int[0]},
                new int[] {theme.subtext, theme.accent, theme.subtext});
    }

    /**
     * An on/off setting as a switch: the words at the left, the switch at the right, Material's
     * row for a setting that applies the moment it is touched (2026-09-23). Still a
     * {@link CompoundButton} to its callers, which read {@code isChecked} and listen for changes
     * exactly as they did with the check box it replaces.
     *
     * <p>Track and handle are drawn from the palette instead of being the platform's tinted: the
     * stock track is a translucent bar, so the accent reached the screen as a muddy purple beside
     * check boxes in the bright one (Juri, 2026-09-23). These are Material's own measurements, a
     * 52 by 32 dp track with a 20 dp handle, which is also what the web admin's switch is.
     */
    private Switch themedSwitch(KioskTheme theme, String label, boolean checked) {
        Switch toggle = new Switch(this);
        toggle.setText(label);
        toggle.setTextColor(theme.text);
        toggle.setTextSize(16);
        toggle.setChecked(checked);
        toggle.setMinHeight(dp(48));
        toggle.setMinimumHeight(dp(48));
        toggle.setShowText(false);
        // Nothing to tint: the two drawables carry their own colours, and a tint left over from
        // the platform theme would paint over them.
        toggle.setThumbTintList(null);
        toggle.setTrackTintList(null);
        toggle.setTrackDrawable(switchTrack(theme));
        toggle.setThumbDrawable(switchThumb(theme));
        toggle.setSwitchMinWidth(dp(52));
        toggle.setSwitchPadding(dp(16));
        toggle.setThumbTextPadding(0);
        return toggle;
    }

    /**
     * The switch's track: the accent filled when it is on, a hollow pill when it is off.
     *
     * <p>Its side padding is how the platform's Switch is told where the handle may travel, so
     * 4 dp of track shows at each end. The width the Switch measures for itself from the handle,
     * 2 x 20 + 4 + 4, comes to less than {@code switchMinWidth}, so the minimum decides it and
     * the track is Material's 52 dp.
     */
    private android.graphics.drawable.Drawable switchTrack(KioskTheme theme) {
        android.graphics.drawable.StateListDrawable track =
                new android.graphics.drawable.StateListDrawable();
        track.addState(new int[] {android.R.attr.state_checked}, switchTrackPill(theme, true));
        track.addState(new int[0], switchTrackPill(theme, false));
        return track;
    }

    private android.graphics.drawable.Drawable switchTrackPill(KioskTheme theme, boolean on) {
        android.graphics.drawable.GradientDrawable pill =
                theme.pill(on ? theme.accent : theme.surfaceAlt);
        if (!on) {
            pill.setStroke(dp(2), theme.border);
        }
        pill.setSize(dp(52), dp(32));
        android.graphics.drawable.LayerDrawable held =
                new android.graphics.drawable.LayerDrawable(
                        new android.graphics.drawable.Drawable[] {pill});
        held.setPadding(dp(4), 0, dp(4), 0);
        return held;
    }

    /** The switch's handle: 20 dp, the colour that reads on the track it is standing on. */
    private android.graphics.drawable.Drawable switchThumb(KioskTheme theme) {
        android.graphics.drawable.StateListDrawable thumb =
                new android.graphics.drawable.StateListDrawable();
        thumb.addState(new int[] {android.R.attr.state_checked}, switchHandle(theme.onAccent()));
        thumb.addState(new int[0], switchHandle(theme.border));
        return thumb;
    }

    /**
     * One state of the handle, held in 6 dp of nothing above and below: the Switch hands the
     * handle the whole height of the track to draw in, and a bare oval there comes out as an
     * ellipse as tall as the track.
     *
     * <p>A layer with an inset and not an {@code InsetDrawable}, which reports its inset as
     * padding: the Switch takes a thumb's padding off the track it draws, and the track came out
     * 20 dp tall instead of 32. A layer's inset is invisible to it, so only the handle shrinks.
     */
    private android.graphics.drawable.Drawable switchHandle(int fill) {
        android.graphics.drawable.GradientDrawable handle =
                new android.graphics.drawable.GradientDrawable();
        handle.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        handle.setColor(fill);
        handle.setSize(dp(20), dp(20));
        android.graphics.drawable.LayerDrawable held =
                new android.graphics.drawable.LayerDrawable(
                        new android.graphics.drawable.Drawable[] {handle});
        held.setLayerInset(0, 0, dp(6), 0, dp(6));
        return held;
    }

    /**
     * The weight every button with a body carries, matching the web admin's 600.
     *
     * <p>Android has no stock 600, so this is sans-serif-medium (500), the nearest family that
     * exists on API 26. The outlined button keeps the plain face, exactly as the web's does: the
     * weight is part of what separates a button with a body from one without.
     */
    private static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);


    /**
     * Material's button: a 40 dp pill, 24 dp in at the sides, 14 sp in the medium weight, and a
     * ripple for the press. Four weights, none of them hollow of meaning: filled for a card's one
     * main action, tonal for the second, outlined for a command that acts now and stores nothing,
     * text for navigation (decided 2026-09-23, from the drawings in media/drafts/material3/).
     * The platform Button's 88 dp minimum width and its elevation animator are cleared: the
     * first made short labels into slabs, the second fought the flat Material face.
     */
    private Button pillShaped(Button button, String label, int padXdp) {
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setTypeface(MEDIUM);
        button.setPadding(dp(padXdp), 0, dp(padXdp), 0);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(dp(40));
        button.setMinimumHeight(dp(40));
        button.setStateListAnimator(null);
        button.setElevation(0);
        return button;
    }

    private Button primaryButton(KioskTheme theme, String label) {
        return filledButton(theme, label, theme.accent);
    }

    /** The filled pill in a chosen colour: the accent for the main action, red inside a confirm. */
    private Button filledButton(KioskTheme theme, String label, int fill) {
        Button button = pillShaped(new Button(this), label, 24);
        button.setTextColor(theme.onAccent());
        button.setBackground(theme.ripple(theme.pill(fill), null, theme.onAccent()));
        return button;
    }

    /**
     * Red: this button deletes something, and it appears only on the confirm screen, where the
     * one wrong tap that costs the most is the one the colour has to warn about.
     */
    private Button dangerButton(KioskTheme theme, String label) {
        return filledButton(theme, label, theme.bad);
    }

    /**
     * The second weight: the hue's container behind the hue's own ink, for a card's second action
     * (Open once, Preview) and for a way to a page (More screensaver settings). Tonal rather than
     * a bare text button because a wall panel has no hover, and a control with no body until you
     * touch it is a control nobody finds.
     */
    private Button tonalButton(KioskTheme theme, String label) {
        Button button = pillShaped(new Button(this), label, 24);
        button.setTextColor(theme.onSecondaryContainer);
        button.setBackground(theme.ripple(theme.pill(theme.secondaryContainer), null,
                theme.onSecondaryContainer));
        return button;
    }

    /** The outlined pill: a command that acts now and stores nothing. */
    private Button secondaryButton(KioskTheme theme, String label) {
        Button button = pillShaped(new Button(this), label, 24);
        button.setTextColor(theme.accent);
        button.setBackground(theme.ripple(theme.pillOutlined(dp(1), theme.border),
                theme.pill(Color.WHITE), theme.accent));
        return button;
    }

    /** Words alone: navigation and the quiet half of a pair (Cancel beside Save). */
    private Button textButton(KioskTheme theme, String label) {
        Button button = pillShaped(new Button(this), label, 12);
        button.setTextColor(theme.accent);
        button.setBackground(theme.ripple(new android.graphics.drawable.ColorDrawable(
                Color.TRANSPARENT), theme.pill(Color.WHITE), theme.accent));
        return button;
    }

    /** A text button with a chevron after its words: it opens a page of its own. */
    private Button textButtonOnward(KioskTheme theme, String label) {
        Button button = textButton(theme, label);
        android.graphics.drawable.Drawable chevron =
                getDrawable(R.drawable.ic_chevron_right).mutate();
        chevron.setTint(theme.accent);
        chevron.setBounds(0, 0, dp(18), dp(18));
        button.setCompoundDrawablesRelative(null, null, chevron, null);
        button.setCompoundDrawablePadding(dp(4));
        button.setPadding(dp(12), 0, dp(8), 0);
        return button;
    }

    /**
     * A 40 dp icon button with a 48 dp touch target, the shape for anything repeated in a row:
     * edit, delete, remove, the pager's chevrons, the view. The description is its spoken name
     * and its long-press tooltip, so a glyph is never the only word for what it does.
     */
    private ImageButton iconButton(KioskTheme theme, int drawable, String description) {
        ImageButton button = new ImageButton(this);
        button.setImageResource(drawable);
        button.setImageTintList(ColorStateList.valueOf(theme.subtext));
        button.setContentDescription(description);
        button.setTooltipText(description);
        button.setScaleType(ImageView.ScaleType.CENTER);
        button.setBackground(theme.ripple(new android.graphics.drawable.ColorDrawable(
                Color.TRANSPARENT), theme.pill(Color.WHITE), theme.text));
        button.setMinimumWidth(dp(48));
        button.setMinimumHeight(dp(48));
        button.setPadding(dp(12), dp(12), dp(12), dp(12));
        return button;
    }

    /** The same, its glyph in the accent: the one that saves. */
    private ImageButton iconButtonPrimary(KioskTheme theme, int drawable, String description) {
        ImageButton button = iconButton(theme, drawable, description);
        button.setImageTintList(ColorStateList.valueOf(theme.accent));
        return button;
    }

    /** The same on the primary container's disc: the view that is on. */
    private ImageButton iconButtonSelected(KioskTheme theme, int drawable, String description) {
        ImageButton button = iconButton(theme, drawable, description);
        button.setImageTintList(ColorStateList.valueOf(theme.onPrimaryContainer));
        android.graphics.drawable.GradientDrawable disc = theme.pill(theme.primaryContainer);
        android.graphics.drawable.LayerDrawable inset =
                new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[] {disc});
        inset.setLayerInset(0, dp(4), dp(4), dp(4), dp(4));
        button.setBackground(theme.ripple(inset, theme.pill(Color.WHITE), theme.onPrimaryContainer));
        return button;
    }

    /**
     * The dashboard view of a panel that has no dashboard URL.
     *
     * <p>Reached three ways, all of them "show the dashboard" with nothing to show: the foot button
     * pressed with the URL box emptied, {@code kiosk.home} or a kiosk restart with nothing stored,
     * and the nightly and pressure rebuilds on such a panel. Not reached at boot: an unconfigured
     * panel still opens its settings screen from {@code initializeUserInterface}, because that is
     * where the missing URL gets typed. Before this existed the empty case was a black WebView
     * loading "", or nothing happening at all, depending on the route in (found 2026-09-07).
     *
     * <p>It is the dashboard state in every respect but the WebView: fullscreen, lock task held,
     * the escape combinations working from its corners, and the blackout honoured, so
     * {@code kiosk.stop} and {@code display.visual_off} blank this screen exactly as they blank a
     * dashboard. There is no supervisor, no frozen-page check and no server probe, because there
     * is no page; {@link #destroyWebView} has already cancelled them. Rebuilt on rotation like
     * every native screen.
     *
     * <p>The text says the one thing an operator standing in front of the panel needs, and the web
     * admin address when there is one, because "open the settings" presumes they know the corner
     * combination and a browser on another device presumes nothing. Above the text sits the
     * illustration chosen from five on 2026-09-07 (media/drafts/parking-page/, sketch E): the Muralis
     * billboard with a fresh poster pasted perfectly, upside down, and the Android robot on its
     * ladder wondering, the one the old black screen with the upside-down robot inspired. It is
     * {@code R.drawable.parking_billboard}, generated by make-parking-drawable.py beside the
     * sketches, and the About screen carries the CC BY 3.0 sentence Google requires for the robot.
     */
    private void showParkingPage() {
        currentScreen = this::showParkingPage;
        clearStatusChip();
        destroyWebView();
        configurationVisible = false;
        if (proBilling != null) {
            proBilling.detachListener();
        }
        recorderVisible = false;
        wizardVisible = false;
        publishOperatorScreenState();
        setDashboardFullscreen(true);
        disableStatusBarIfPinned();
        enterImmersiveMode();

        KioskTheme theme = currentTheme();
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(theme.base);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER);
        int pad = dp(28);
        column.setPadding(pad, pad, pad, pad);

        // The text first, because the picture gets whatever height the text leaves.
        android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
        int textWidth = metrics.widthPixels - 2 * pad;
        java.util.List<View> lines = new java.util.ArrayList<>();
        TextView title = new FlushText(this);
        title.setText("No dashboard yet.");
        title.setTextColor(theme.text);
        title.setTextSize(30);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        title.setLayoutParams(matchWrap());
        lines.add(title);
        TextView body = new FlushText(this);
        body.setText("Open the Muralis settings and enter one. The panel does the rest.");
        body.setTextColor(theme.subtext);
        body.setTextSize(18);
        body.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bodyParams.topMargin = dp(10);
        body.setLayoutParams(bodyParams);
        lines.add(body);
        if (KioskRuntimeState.httpAdminListening()) {
            // The address only when there is one: a panel without a network has nothing another
            // device could open, and the line says that instead of inventing a host name.
            SystemStats.RuntimeFacts facts = KioskRuntimeState.lastFacts();
            String address = facts == null ? "" : facts.ipAddress;
            TextView admin = new FlushText(this);
            admin.setText(address.isEmpty()
                    ? "The web admin is on, but this device has no network connection."
                    : "Or from another device: " + KioskRuntimeState.httpAdminScheme() + address
                            + ":" + KioskRuntimeState.httpAdminPort());
            admin.setTextColor(address.isEmpty() ? theme.warn : theme.subtext);
            admin.setTextSize(16);
            admin.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams adminParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            adminParams.topMargin = dp(22);
            admin.setLayoutParams(adminParams);
            lines.add(admin);
        }
        // What the text will take, measured at the width it will get, so the picture can be
        // capped to the rest. A fixed share of the height was the first version, and on a phone
        // held sideways (360dp tall) the picture's share plus three lines of text was more than
        // the screen: the title sat at the bottom edge and the lines under it were off the screen
        // (measured 2026-09-07). The tablet never showed it, the phone in landscape always did.
        int textHeight = 0;
        for (View line : lines) {
            line.measure(View.MeasureSpec.makeMeasureSpec(textWidth, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) line.getLayoutParams();
            textHeight += line.getMeasuredHeight() + (params == null ? 0 : params.topMargin);
        }
        int sceneGap = dp(24);
        int sceneRoom = metrics.heightPixels - 2 * pad - textHeight - sceneGap;

        // The easter egg, and the reason this screen is worth looking at: the billboard with the
        // poster pasted perfectly, upside down, and the robot wondering. Decorative, so no content
        // description; the text under it carries the meaning. Scaled to fit whatever is left of the
        // screen after the text, keeping its shape, so it works in portrait and landscape alike:
        // never wider than 86% of the screen, never taller than 58% of it, and never taller than
        // the room the text leaves.
        ImageView scene = new ImageView(this);
        scene.setImageResource(R.drawable.parking_billboard);
        scene.setAdjustViewBounds(true);
        scene.setScaleType(ImageView.ScaleType.FIT_CENTER);
        scene.setMaxWidth((int) (metrics.widthPixels * 0.86f));
        scene.setMaxHeight(Math.max(dp(48),
                Math.min((int) (metrics.heightPixels * 0.58f), sceneRoom)));
        LinearLayout.LayoutParams sceneParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sceneParams.gravity = Gravity.CENTER_HORIZONTAL;
        sceneParams.bottomMargin = sceneGap;
        column.addView(scene, sceneParams);
        for (View line : lines) {
            column.addView(line);
        }
        root.addView(column, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        addScreensaverLayer(root);
        // The same blackout the dashboard carries, for the same two commands, so a panel with no
        // dashboard can still be blanked and woken like one.
        blackout = new View(this);
        blackout.setBackgroundColor(Color.BLACK);
        blackout.setVisibility(View.GONE);
        blackout.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                handleUiCommand("display.wake", -1, null);
            }
            return true;
        });
        root.addView(blackout, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        addScreensaverPreviewCaption(root);
        setContentView(root);
        applyKioskPolicy();
        kioskStopped = KioskConfig.kioskStopped(this);
        if (kioskStopped) {
            blackout.setVisibility(View.VISIBLE);
        }
        int visualOffBoot = KioskConfig.visualOffBootCount(this);
        if (visualOffBoot >= 0 && visualOffBoot == currentBootCount()) {
            blackout.setVisibility(View.VISIBLE);
            setWindowBrightness(1);
            filmOn = true;
        } else {
            setWindowBrightness(-1);
        }
        restoreScreensaverAfterRebuild();
    }

    private void showDashboard(String url) {
        if (url == null || url.trim().isEmpty()) {
            showParkingPage();
            return;
        }
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
        addScreensaverLayer(dashboard);
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
        addScreensaverPreviewCaption(dashboard);
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
            filmOn = true;
        } else {
            // A fresh dashboard starts with the window at the system setting.
            setWindowBrightness(-1);
        }
        restoreScreensaverAfterRebuild();
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
        networkWaitLabel = new FlushText(this);
        networkWaitLabel.setText("Waiting for the network");
        networkWaitLabel.setTextColor(KioskTheme.darkPalette().subtext);
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
        statsOverlay = new FlushText(this);
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
        // Top-right: the bottom corners are the nine-tap escape zones. The top margin is not a
        // constant: see placeStatsOverlay, which puts the block one pixel under the status bar.
        params.gravity = Gravity.TOP | Gravity.END;
        params.topMargin = 1;
        params.rightMargin = dp(4);
        dashboard.addView(statsOverlay, params);
        // Placed once the view is attached and knows its insets, and again whenever the insets
        // change (bars shown or hidden, a rotation). The listener consumes nothing.
        statsOverlay.setOnApplyWindowInsetsListener((view, insets) -> {
            view.post(this::placeStatsOverlay);
            return insets;
        });
        statsOverlay.post(this::placeStatsOverlay);

        mainHandler.removeCallbacks(overlayTask);
        mainHandler.post(overlayTask);
    }

    /**
     * Puts the readout one pixel under the status bar, whatever kind of window this is.
     *
     * <p>Reported 2026-09-07: "on all devices too high". A fixed 4dp from the top of the dashboard view
     * meant three different things: under the clock on a phone that draws edge to edge (Android 15
     * and later, where the content starts at the screen's top edge), four pixels under the bar on an
     * older ordinary install (whose window already starts below the bar), and hard against the top
     * edge on the kiosk, which hides its bars. One rule instead: the block's top edge sits one pixel
     * below the bar, and where the bar is hidden, one pixel below where it would be, from the
     * system's own dimension for it, so the readout lands in the same place on every device.
     */
    private void placeStatsOverlay() {
        if (statsOverlay == null || statsOverlay.getParent() == null) {
            return;
        }
        View content = findViewById(android.R.id.content);
        int[] location = new int[2];
        content.getLocationOnScreen(location);
        android.view.WindowInsets insets = statsOverlay.getRootWindowInsets();
        int bar;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            bar = insets == null ? 0
                    : insets.getInsets(android.view.WindowInsets.Type.statusBars()).top;
        } else {
            bar = insets == null ? 0 : insets.getSystemWindowInsetTop();
        }
        if (location[1] > 0) {
            // The window itself starts below the bar: nothing of the bar is inside this view.
            bar = 0;
        } else if (bar == 0) {
            // Content from the very top and no bar reported: the kiosk, bars hidden. Where the bar
            // would be, from the system's own dimension, so the readout sits where it does on a
            // phone rather than against the edge.
            int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
            bar = id == 0 ? dp(24) : getResources().getDimensionPixelSize(id);
        }
        int margin = bar + 1;
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) statsOverlay.getLayoutParams();
        if (params.topMargin != margin) {
            params.topMargin = margin;
            statsOverlay.setLayoutParams(params);
        }
    }

    /**
     * The sampler publishes coloured HTML; TextView renders a useful subset of it. Falls back to the
     * plain-text block if the HTML parser ever returns nothing, so the readout cannot go blank.
     */
    private CharSequence renderOverlay() {
        return renderOverlay(false);
    }

    /**
     * The readout, coloured for the surface it lands on.
     *
     * <p>The service bakes the colours into the markup because it has no screen, and over the
     * dashboard they are right: that block sits on a black plate. On the configuration screen's
     * card in the light theme they were being drawn on a pale surface, where every one of them
     * measured under 2.2:1.
     */
    private CharSequence renderOverlay(boolean onALightCard) {
        String html = KioskRuntimeState.overlayHtml();
        if (html.isEmpty()) {
            return KioskRuntimeState.overlayText();
        }
        if (onALightCard) {
            html = SystemStats.forLightSurface(html);
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
        TextView needs = new FlushText(this);
        needs.setTextColor(theme.subtext);
        needs.setTextSize(14);
        needs.setText("Needs Muralis Pro.");
        LinearLayout.LayoutParams needsParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        needsParams.topMargin = dp(10);
        cardView.addView(needs, needsParams);
        Button unlock = secondaryButton(theme, "Buy Muralis Pro");
        unlock.setOnClickListener(view -> proBilling.buy(this));
        cardView.addView(buttonRow(unlock), matchWrap());
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
        hideScreensaverSurface();
        screensaverLayer = null;
        screensaverPreviewCaption = null;
        screensaverStage = ScreensaverPolicy.Stage.DASHBOARD;
        screensaverSinceMs = android.os.SystemClock.uptimeMillis();
        blackout = null;
    }

    private void handleUiCommand(String command, int brightnessPercent, String url) {
        handleUiCommand(command, brightnessPercent, url, null);
    }

    private void handleUiCommand(String command, int brightnessPercent, String url,
            String displayOffMethod) {
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
                stopScreensaver("kiosk started");
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
                stopScreensaver("page shown");
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
                    // Only reachable for kiosk.home on a panel with no dashboard URL. Home is the
                    // dashboard view, and with nothing stored the dashboard view is the parking
                    // page, so that is where home goes. It used to log a line and do nothing,
                    // which on a panel that had just been blanked on purpose read as a dead command.
                    showDashboard("");
                    break;
                }
                // A one-off URL also lifts a kiosk.stop and a visual-off, exactly as
                // kiosk.set_url does: asking for a page is asking to see it.
                kioskStopped = false;
                KioskConfig.edit(this).kioskStopped(false).apply();
                liftVisualOff();
                stopScreensaver("page shown");
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
                stopScreensaver("kiosk stopped");
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
                screensaverStage = ScreensaverPolicy.Stage.DARK;
                screensaverPreview = false;
                screensaverPreviewReturn = null;
                if (DisplayOffPolicy.SLEEP.equals(displayOffMethod)) {
                    // The service put the screen to sleep. Nothing is drawn: the dark state is
                    // the screen being off, and the power button or a remote wake ends it. A film
                    // drawn here as well would greet the power button with black and a second
                    // tap, which is not what a person pressing it meant. A showing screensaver
                    // stays as it is too, so nothing flashes on the way down; the wake decides
                    // what comes back (onDisplayWoke). Not "active" meanwhile: dark is dark.
                    asleep = true;
                    pictureGeneration++;
                    mainHandler.removeCallbacks(pictureAdvance);
                    KioskRuntimeState.publishScreensaver(false);
                    KioskConfig.recordScreensaverBootCount(this, -1);
                    if (screensaverWebView != null) {
                        // Asleep, the page has no viewer: its scripts, media and network stop
                        // until a wake keeps it (onResume in startScreensaver) or drops it.
                        screensaverWebView.onPause();
                    }
                    break;
                }
                // filmOn first: hideScreensaverSurface leaves the brightness alone under the film,
                // so the dim floor is not written back to the system level and then to 1%.
                filmOn = true;
                hideScreensaverSurface();
                KioskConfig.recordScreensaverBootCount(this, -1);
                if (blackout != null) {
                    blackout.setVisibility(View.VISIBLE);
                }
                setWindowBrightness(1);
                // Keyed to this boot: survives the nightly restart, never a reboot.
                KioskConfig.recordVisualOffBootCount(this, currentBootCount());
                break;
            case "display.wake": {
                // Judged before the flags are cleared: a wake from a dark panel applies the user's
                // on-wake choice; "Display on" or the presence blueprint reaching a lit panel that
                // is showing its screensaver brings the page back, as a touch would. A broadcast
                // that follows an onResume already judged as the wake is the same wake.
                boolean wasDark = asleep || filmOn
                        || screensaverStage == ScreensaverPolicy.Stage.DARK
                        || android.os.SystemClock.uptimeMillis() - lastWakeDecisionMs
                                < WAKE_GRACE_MS;
                asleep = false;
                if (blackout != null && !kioskStopped) {
                    blackout.setVisibility(View.GONE);
                }
                liftVisualOff();
                if (wasDark) {
                    onDisplayWoke();
                } else {
                    stopScreensaver("display on");
                }
                break;
            }
            case "screensaver.start": {
                if (wizardVisible || recorderVisible) {
                    // The service refuses both; a broadcast that still arrives here is ignored
                    // rather than drawn over the wizard or a recording in progress.
                    break;
                }
                ScreensaverPolicy.Settings settings = KioskConfig.screensaverOf(this);
                if (configurationVisible) {
                    // Somebody is in the panel's settings: the same thing the settings page's own
                    // Preview button does, the page under, the screensaver over it, the caption
                    // saying a tap goes back, and the tap returns to the screen that was open.
                    // Until 2026-09-24 the service refused this ("Muralis settings are open on
                    // the panel") and a Preview pressed on the web looked broken (Juri: "Preview
                    // should work anyway").
                    Runnable back = currentScreen;
                    showDashboard(KioskConfig.load(this).dashboardUrl);
                    if (startScreensaver(settings, "asked for")) {
                        screensaverPreview = true;
                        screensaverPreviewReturn = back;
                        showScreensaverPreviewCaption(settings);
                    } else if (back != null) {
                        back.run();
                    }
                    break;
                }
                startScreensaver(settings, "asked for");
                break;
            }
            case "screensaver.stop":
                stopScreensaver("asked for");
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
        filmOn = false;
        KioskConfig.recordVisualOffBootCount(this, -1);
        setWindowBrightness(-1);
        // A wake by tap goes through no dispatcher, so Home Assistant would otherwise learn of it
        // at the next minute tick; a duplicate from the command paths is coalesced.
        KioskService.publishTelemetrySoon(this);
    }

    /**
     * The screensaver's clock, once a second: {@link ScreensaverPolicy} decides, this method only
     * supplies the facts and does what it says. Blocked while an operator is on a settings screen,
     * while the kiosk is stopped, while the panel is already dark, while the activity is not in
     * front, and when no page root exists to draw in. A mode changed under a showing screensaver
     * ends it: the next one comes after the idle time, in the new mode.
     */
    private void tickScreensaver() {
        ScreensaverPolicy.Settings settings = KioskConfig.screensaverOf(this);
        if (screensaverShowing != null && !screensaverShowing.equals(settings.mode)) {
            if (screensaverStage == ScreensaverPolicy.Stage.DARK) {
                // Changed while the panel is dark: the surface goes, the stage stays dark, and
                // the wake decides as it would have (measured 2026-09-09: a stop here made a
                // sleeping panel count as "dashboard" until the wake put it right).
                hideScreensaverSurface();
            } else {
                stopScreensaver("mode changed");
            }
        } else if (screensaverShowing != null
                && screensaverStage == ScreensaverPolicy.Stage.SCREENSAVER
                && !screensaverDetailOf(settings).equals(screensaverShowingDetail)) {
            // Same mode, new address or new dim floor: re-applied in place, with the clock
            // towards display off left where it was.
            long since = screensaverSinceMs;
            hideScreensaverSurface();
            startScreensaver(settings, "settings changed");
            screensaverSinceMs = since;
            KioskConfig.recordScreensaverSinceMs(this, since);
        }
        boolean blocked = configurationVisible || recorderVisible || wizardVisible || kioskStopped
                || launcherHandoff
                || filmOn || asleep || !inFront || screensaverLayer == null;
        long now = android.os.SystemClock.uptimeMillis();
        if (ScreensaverPolicy.PICTURES.equals(settings.mode)) {
            // The online set is kept fresh while the panel sits on the page, so the screensaver
            // never waits for the network when it starts; a changed source fetches at once.
            boolean sourceChanged = !settings.source.equals(lastSeenPictureSource);
            if (sourceChanged || now - lastPictureRefreshCheckMs > 60 * 60_000L) {
                lastSeenPictureSource = settings.source;
                lastPictureRefreshCheckMs = now;
                PictureLibrary.get(this).refreshIfStale(settings.source, null);
            }
        }
        switch (ScreensaverPolicy.next(settings, screensaverStage, blocked, screensaverSinceMs,
                now)) {
            case START:
                startScreensaver(settings, "idle for "
                        + ScreensaverPolicy.describeDuration(settings.idleSeconds));
                break;
            case DISPLAY_OFF:
                // Set here, before the service answers with display.visual_off, so the next tick
                // does not ask twice; the answer sets it again and settles sleep or film.
                screensaverStage = ScreensaverPolicy.Stage.DARK;
                Log.i(TAG, "Screensaver showing for "
                        + ScreensaverPolicy.describeDuration(settings.offSeconds)
                        + ", asking for display off");
                KioskService.displayOff(this);
                break;
            case NONE:
            default:
                break;
        }
    }

    /**
     * Puts the screensaver on the glass in the stored mode, or restarts its clock when that mode
     * is already showing, which is what a wake into "screensaver first" needs: the page that
     * survived the sleep stays rather than reloading.
     *
     * @return false when nothing can be shown: mode off, the web-page mode without an address,
     *         a stopped kiosk, or no page root on screen
     */
    private boolean startScreensaver(ScreensaverPolicy.Settings settings, String reason) {
        if (!ScreensaverPolicy.runnable(settings) || screensaverLayer == null || kioskStopped
                || filmOn || asleep) {
            // Nothing goes over a dark panel: the dim floor would lift the film's brightness and
            // the page would draw under the black view while reporting itself active.
            return false;
        }
        if (settings.mode.equals(screensaverShowing) && screensaverWebView != null) {
            screensaverWebView.onResume();
        }
        if (!settings.mode.equals(screensaverShowing)
                || !screensaverDetailOf(settings).equals(screensaverShowingDetail)) {
            hideScreensaverSurface();
            switch (settings.mode) {
                case ScreensaverPolicy.DIM:
                    setWindowBrightness(settings.dimPercent);
                    break;
                case ScreensaverPolicy.FILM:
                    if (blackout != null) {
                        blackout.setVisibility(View.VISIBLE);
                    }
                    setWindowBrightness(1);
                    break;
                case ScreensaverPolicy.URL:
                    showScreensaverPage(settings.url);
                    break;
                case ScreensaverPolicy.PICTURES:
                    showPictures(settings);
                    break;
                default:
                    return false;
            }
            screensaverShowing = settings.mode;
            screensaverShowingDetail = screensaverDetailOf(settings);
            KioskConfig.recordScreensaverBootCount(this, currentBootCount());
            Log.i(TAG, "Screensaver on: " + settings.mode + " (" + reason + ")");
        }
        screensaverStage = ScreensaverPolicy.Stage.SCREENSAVER;
        // display.wake clears the window override before it reaches here. Reapply even when
        // keeping the same dim surface after sleep, otherwise telemetry says dim on a bright page.
        if (ScreensaverPolicy.DIM.equals(settings.mode)) setWindowBrightness(settings.dimPercent);
        if (ScreensaverPolicy.FILM.equals(settings.mode)) setWindowBrightness(1);
        if (pictureFrame != null) {
            mainHandler.removeCallbacks(pictureAdvance);
            if (pictureSet == null) loadPictureSet(settings);
            else if (!pictureSet.isEmpty() && !pictureFrame.hasPicture()) {
                // Sleep may invalidate the first decode after the catalogue has arrived.
                // One-per-cycle has no advance timer to recover that otherwise empty surface.
                showPictureAt(pictureIndex, settings, false, 0);
            }
            else if (!settings.onePerCycle) mainHandler.postDelayed(pictureAdvance,
                    settings.pictureSeconds * 1000L);
        }
        screensaverSinceMs = android.os.SystemClock.uptimeMillis();
        KioskConfig.recordScreensaverSinceMs(this, screensaverSinceMs);
        KioskRuntimeState.publishScreensaver(true);
        KioskService.publishTelemetrySoon(this);
        return true;
    }

    /** What, besides the mode, the showing surface was built from. */
    private String screensaverDetailOf(ScreensaverPolicy.Settings settings) {
        switch (settings.mode) {
            case ScreensaverPolicy.URL:
                return settings.url;
            case ScreensaverPolicy.DIM:
                return String.valueOf(settings.dimPercent);
            case ScreensaverPolicy.PICTURES:
                return settings.source + "|" + settings.pictureSeconds + "|" + settings.transition
                        + "|" + settings.shuffle + "|" + settings.onePerCycle + "|"
                        + settings.creditShown() + "|" + settings.creditCorner + "|"
                        + PictureLibrary.get(this).revision();
            default:
                return "";
        }
    }

    /**
     * After a WebView rebuild in the same boot (the nightly clean, the pressure rebuild, a
     * kiosk restart), the screensaver that was on the glass comes back at once, like the film
     * does, instead of the page lighting up for the idle time. A process restart in the same
     * boot is covered by the same record; a reboot is not, by the rule every dark state follows.
     */
    private void restoreScreensaverAfterRebuild() {
        int recorded = KioskConfig.screensaverBootCount(this);
        if (recorded >= 0 && recorded == currentBootCount() && !filmOn && !kioskStopped) {
            long since = KioskConfig.screensaverSinceMs(this);
            startScreensaver(KioskConfig.screensaverOf(this), "restored after a rebuild");
            if (since >= 0 && since <= android.os.SystemClock.uptimeMillis()) {
                screensaverSinceMs = since;
                KioskConfig.recordScreensaverSinceMs(this, since);
            }
        }
    }

    /** Back to the page, and the idle time starts again. Idempotent. */
    private void stopScreensaver(String reason) {
        boolean shown = screensaverShowing != null;
        screensaverPreview = false;
        screensaverPreviewReturn = null;
        hideScreensaverSurface();
        KioskConfig.recordScreensaverBootCount(this, -1);
        screensaverStage = ScreensaverPolicy.Stage.DASHBOARD;
        screensaverSinceMs = android.os.SystemClock.uptimeMillis();
        if (shown) {
            Log.i(TAG, "Screensaver off (" + reason + ")");
            KioskService.publishTelemetrySoon(this);
        }
    }

    /**
     * Takes the screensaver off the glass without deciding what comes next: the callers that
     * darken the panel keep the film's brightness and black view, the others get the page back.
     */
    private void hideScreensaverSurface() {
        String mode = screensaverShowing;
        screensaverShowing = null;
        KioskRuntimeState.publishScreensaver(false);
        if (screensaverPreviewCaption != null) {
            screensaverPreviewCaption.setVisibility(View.GONE);
        }
        if (mode == null) {
            return;
        }
        if (screensaverWebView != null) {
            screensaverWebView.stopLoading();
            if (screensaverLayer != null) {
                screensaverLayer.removeAllViews();
            }
            screensaverWebView.destroy();
            screensaverWebView = null;
        }
        if (pictureFrame != null) {
            pictureGeneration++;
            mainHandler.removeCallbacks(pictureAdvance);
            pictureFrame.release();
            if (screensaverLayer != null) {
                screensaverLayer.removeAllViews();
            }
            pictureFrame = null;
            pictureSet = null;
            pictureSettings = null;
            KioskRuntimeState.publishScreensaverPicture("", "");
        }
        if (screensaverLayer != null) {
            screensaverLayer.setVisibility(View.GONE);
        }
        if (ScreensaverPolicy.FILM.equals(mode) && blackout != null && !kioskStopped && !filmOn) {
            blackout.setVisibility(View.GONE);
        }
        if (!ScreensaverPolicy.URL.equals(mode) && !filmOn) {
            setWindowBrightness(-1);
        }
    }

    /**
     * The display is lit again, by a touch on the film, a remote wake, the presence blueprint or
     * the power button: the user's on-wake choice decides between the screensaver and the page.
     * Idempotent, because a wake from sleep reaches here twice, from onResume and from the
     * display.wake broadcast, in either order.
     */
    private void onDisplayWoke() {
        lastWakeDecisionMs = android.os.SystemClock.uptimeMillis();
        ScreensaverPolicy.Settings settings = KioskConfig.screensaverOf(this);
        if (ScreensaverPolicy.screensaverFirst(settings) && startScreensaver(settings, "wake")) {
            return;
        }
        stopScreensaver("wake");
    }

    /**
     * The web-page screensaver's own WebView, made when the mode starts and destroyed when it
     * ends: the dashboard stays loaded underneath, so a touch brings it back at once, and the
     * second renderer costs memory only while it is on the glass. Same web-scheme rule as the
     * dashboard: a page that hands control to another app is an exit from the kiosk.
     */
    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    private void showScreensaverPage(String url) {
        screensaverWebView = new WebView(this);
        screensaverWebView.setBackgroundColor(Color.BLACK);
        WebSettings settings = screensaverWebView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        screensaverWebView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                if (view != screensaverWebView) return true;
                screensaverWebView = null;
                if (view.getParent() instanceof ViewGroup) {
                    ((ViewGroup) view.getParent()).removeView(view);
                }
                view.destroy();
                stopScreensaver("screensaver renderer ended");
                return true;
            }
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String scheme = request.getUrl() != null ? request.getUrl().getScheme() : null;
                if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
                    return false;
                }
                Log.w(TAG, "Blocked screensaver navigation to a non-web scheme: " + scheme);
                return true;
            }
        });
        screensaverLayer.addView(screensaverWebView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        screensaverLayer.setVisibility(View.VISIBLE);
        screensaverWebView.loadUrl(url);
    }

    /**
     * The Pictures mode: a frame in the layer, the source's current set, one picture at a time.
     * The set is read and every picture decoded on {@link PictureLibrary}'s worker, and each
     * result is checked against the frame it was meant for, so a screensaver that ended while a
     * decode was in flight gets nothing drawn over the page.
     */
    private void showPictures(ScreensaverPolicy.Settings settings) {
        pictureSettings = settings;
        pictureFrame = new PictureFrame(this, settings.creditCorner, settings.pictureFit);
        screensaverLayer.addView(pictureFrame, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        screensaverLayer.setVisibility(View.VISIBLE);
        pictureCycles++;
        PictureLibrary library = PictureLibrary.get(this);
        PictureFrame frame = pictureFrame;
        // A stale online set is refreshed in the background; when the fetch lands and nothing
        // could be shown so far (a first start with no cache), the new set is loaded.
        library.refreshIfStale(settings.source, () -> {
            if (pictureFrame == frame && (pictureSet == null || pictureSet.isEmpty())) {
                loadPictureSet(settings);
            }
        });
    }

    private void loadPictureSet(ScreensaverPolicy.Settings settings) {
        PictureLibrary library = PictureLibrary.get(this);
        PictureFrame frame = pictureFrame;
        int generation = ++pictureGeneration;
        library.run(() -> {
            if (pictureGeneration != generation) return;
            java.util.List<PictureSources.Picture> set = library.catalog(settings.source);
            if (settings.shuffle) {
                java.util.Collections.shuffle(set);
            }
            String empty = set.isEmpty() ? library.state(settings.source) : null;
            library.onMain(() -> {
                if (pictureFrame != frame || pictureGeneration != generation) {
                    return;
                }
                pictureSet = set;
                if (set.isEmpty()) {
                    frame.showMessage(empty);
                    return;
                }
                // One per cycle: the next picture at each start, shuffled or in order; otherwise
                // from the top and onwards every picture_s seconds.
                pictureIndex = settings.onePerCycle ? (pictureCycles - 1) % set.size() : 0;
                showPictureAt(pictureIndex, settings, false, 0);
            });
        });
    }

    private void showPictureAt(int index, ScreensaverPolicy.Settings settings, boolean animate,
            int failures) {
        java.util.List<PictureSources.Picture> set = pictureSet;
        PictureFrame frame = pictureFrame;
        if (frame == null || set == null || set.isEmpty()) {
            return;
        }
        PictureSources.Picture picture = set.get(index % set.size());
        int generation = ++pictureGeneration;
        PictureLibrary library = PictureLibrary.get(this);
        android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
        library.run(() -> {
            if (pictureGeneration != generation) return;
            android.graphics.Bitmap bitmap = library.decode(settings.source, picture,
                    metrics.widthPixels, metrics.heightPixels);
            library.onMain(() -> {
                if (pictureFrame != frame || pictureSet != set || pictureGeneration != generation) {
                    if (bitmap != null) {
                        bitmap.recycle();
                    }
                    return;
                }
                if (bitmap == null) {
                    // An unreadable file (deleted meanwhile, a format this Android cannot decode)
                    // is skipped; when every picture fails the frame says so instead of looping.
                    if (failures + 1 >= set.size()) {
                        frame.showMessage("None of the pictures can be shown.");
                        KioskRuntimeState.publishScreensaverPicture("", "");
                        mainHandler.postDelayed(pictureAdvance, 30_000L);
                        return;
                    }
                    pictureIndex = (index + 1) % set.size();
                    showPictureAt(pictureIndex, settings, animate, failures + 1);
                    return;
                }
                if (!frame.show(bitmap, creditLineFor(picture, settings), settings.transition, animate)) {
                    bitmap.recycle();
                    KioskRuntimeState.publishScreensaverPicture("", "");
                    mainHandler.removeCallbacks(pictureAdvance);
                    mainHandler.postDelayed(pictureAdvance, 30_000L);
                    return;
                }
                KioskRuntimeState.publishScreensaverPicture(picture.title, picture.credit);
                KioskService.publishTelemetrySoon(this);
                mainHandler.removeCallbacks(pictureAdvance);
                if (!settings.onePerCycle && set.size() > 1) {
                    mainHandler.postDelayed(pictureAdvance, settings.pictureSeconds * 1000L);
                }
            });
        });
    }

    private void advancePicture() {
        if (pictureFrame == null || pictureSet == null || pictureSet.isEmpty()) {
            return;
        }
        if (asleep || !inFront) {
            // Nothing is on the glass, so nothing rotates: no timer is re-posted here (decided
            // 2026-09-10). Turning a screensaver into a real sleep used to leave this advancing
            // once a second behind a dark screen, decoding pictures nobody could see. The wake
            // restarts the clock in startScreensaver, and because the frame keeps the picture it
            // was showing, "screensaver first" comes back to the last used image and one touch on
            // it opens the dashboard.
            return;
        }
        // The catalogue and its attribution always travel with their source snapshot.
        pictureIndex = (pictureIndex + 1) % pictureSet.size();
        showPictureAt(pictureIndex, pictureSettings, true, 0);
    }

    /**
     * Title and credit as the source demands them, or null for a local picture whose owner has
     * switched the line off. The online sources' lines are never switched off: they are the
     * attribution their licences require.
     */
    private static String creditLineFor(PictureSources.Picture picture,
            ScreensaverPolicy.Settings settings) {
        if (!settings.creditShown()) {
            return null;
        }
        if (PictureSources.LOCAL.equals(settings.source)) {
            return picture.title.isEmpty() ? picture.credit : picture.title;
        }
        // The title and the names, never the addresses. A wall panel is read from across a room
        // and nobody types a licence URL off one, so a line of link text costs the readability of
        // the credit that has to be read and buys nothing (2026-09-10). The source page URL is
        // kept in the picture and in the cache manifest, just not on the glass.
        if (picture.title.isEmpty()) {
            return picture.credit;
        }
        return picture.title + "\n" + picture.credit;
    }

    /**
     * The operator's "How a picture fills the screen" as the platform's own scaling: the whole
     * picture inside the screen, cropped to cover it, pulled out of shape to cover it, or left at
     * the size it was taken (Juri, 2026-09-23).
     */
    private static ImageView.ScaleType scaleTypeOf(String fit) {
        if (ScreensaverPolicy.FIT_FILL.equals(fit)) {
            return ImageView.ScaleType.CENTER_CROP;
        }
        if (ScreensaverPolicy.FIT_STRETCH.equals(fit)) {
            return ImageView.ScaleType.FIT_XY;
        }
        if (ScreensaverPolicy.FIT_ACTUAL.equals(fit)) {
            return ImageView.ScaleType.CENTER;
        }
        return ImageView.ScaleType.FIT_CENTER;
    }

    /**
     * Two picture views that take turns, so a fade or a slide has both the old and the new
     * picture on screen, and a caption in the chosen corner.
     *
     * <p>How a picture is laid in them is the operator's setting since 2026-09-23, and it is Fit
     * unless they say otherwise: these are somebody's photographs and somebody's licensed work,
     * so nothing is cropped or pulled out of shape by default.
     */
    /**
     * A label that stands on its ink. A TextView lays its first glyph out from the glyph's origin,
     * and the visible letter starts a side bearing to the right of that, a distance that grows
     * with the type size and differs from letter to letter: on the settings screen "Muralis" at
     * 24 sp began 1 dp to the right of "kiosk-..." at 12 sp under it, and a section's title and
     * its summary line missed each other by 2 px (measured on the phone, 2026-09-24). Every left
     * edge on a page is meant to be one line, so a left-aligned label here takes its first glyph's
     * bearing off its left padding and the ink lands on the edge. Centred and end-aligned text,
     * and a label read right to left, are left to the platform.
     */
    private static final class FlushText extends TextView {
        private final Rect ink = new Rect();
        private String bearingGlyph;
        private float bearingSize;
        private Typeface bearingFace;
        private int bearing;

        FlushText(Context context) {
            super(context);
        }

        @Override
        public int getCompoundPaddingLeft() {
            return super.getCompoundPaddingLeft() - bearing();
        }

        private int bearing() {
            // Reached from TextView's own constructor, before this class's fields exist.
            if (ink == null) {
                return 0;
            }
            CharSequence text = getText();
            if (text == null || text.length() == 0 || getLayoutDirection() == LAYOUT_DIRECTION_RTL) {
                return 0;
            }
            int horizontal = getGravity() & Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK;
            if (horizontal != Gravity.START && horizontal != Gravity.LEFT
                    && horizontal != Gravity.NO_GRAVITY) {
                return 0;
            }
            String glyph = text.subSequence(0, Character.charCount(
                    Character.codePointAt(text, 0))).toString();
            float size = getTextSize();
            Typeface face = getTypeface();
            if (!glyph.equals(bearingGlyph) || size != bearingSize || face != bearingFace) {
                getPaint().getTextBounds(glyph, 0, glyph.length(), ink);
                bearing = ink.left;
                bearingGlyph = glyph;
                bearingSize = size;
                bearingFace = face;
            }
            return bearing;
        }
    }

    private final class PictureFrame extends FrameLayout {
        private final ImageView[] views = new ImageView[2];
        private int front;
        private final TextView caption;
        private final TextView message;
        private String displayedCredit;

        PictureFrame(Context context, String corner, String fit) {
            super(context);
            setBackgroundColor(Color.BLACK);
            for (int i = 0; i < 2; i++) {
                views[i] = new ImageView(context);
                views[i].setScaleType(scaleTypeOf(fit));
                views[i].setAlpha(0f);
                addView(views[i], new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            }
            message = new TextView(context);
            message.setTextColor(0xFFB8B8C8);
            message.setTextSize(16);
            message.setGravity(Gravity.CENTER);
            int pad = dp(32);
            message.setPadding(pad, pad, pad, pad);
            message.setVisibility(View.GONE);
            addView(message, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER));
            caption = new TextView(context);
            caption.setTextColor(Color.WHITE);
            caption.setTextSize(13);
            // Set again on every size change: a rotation makes the old limit wrong.
            caption.setMaxWidth((int) (getResources().getDisplayMetrics().widthPixels * 0.7f));
            int padX = dp(12);
            int padY = dp(7);
            caption.setPadding(padX, padY, padX, padY);
            android.graphics.drawable.GradientDrawable pill =
                    new android.graphics.drawable.GradientDrawable();
            pill.setColor(0x99000000);
            pill.setCornerRadius(dp(10));
            caption.setBackground(pill);
            caption.setVisibility(View.GONE);
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.gravity = gravityFor(corner);
            int margin = dp(16);
            params.setMargins(margin, margin, margin, margin);
            addView(caption, params);
        }

        @Override
        protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            super.onSizeChanged(width, height, oldWidth, oldHeight);
            caption.setMaxWidth((int) (width * 0.7f));
        }

        private int gravityFor(String corner) {
            switch (corner) {
                case ScreensaverPolicy.CORNER_TOP_LEFT:
                    return Gravity.TOP | Gravity.START;
                case ScreensaverPolicy.CORNER_TOP_RIGHT:
                    return Gravity.TOP | Gravity.END;
                case ScreensaverPolicy.CORNER_BOTTOM_RIGHT:
                    return Gravity.BOTTOM | Gravity.END;
                case ScreensaverPolicy.CORNER_BOTTOM_LEFT:
                default:
                    return Gravity.BOTTOM | Gravity.START;
            }
        }

        boolean hasPicture() {
            return views[0].getDrawable() != null || views[1].getDrawable() != null;
        }

        boolean show(android.graphics.Bitmap bitmap, String credit, String transition,
                boolean animate) {
            if (!captionFits(credit)) {
                showMessage("This picture's full credit does not fit on this screen.");
                return false;
            }
            // The outgoing picture keeps the line until it has left the glass, and only then does
            // the incoming one take it: one credit panel at a time. Stacking both attributions was
            // tried on 2026-09-10 and rejected the same day, because a second panel appearing over
            // a picture that is still on screen reads as a fault rather than as a credit. Nothing
            // on the glass is left uncredited by this: the caption always names a picture that is
            // visible, and the new one takes over the moment the old picture is gone.
            boolean firstPicture = displayedCredit == null || displayedCredit.isEmpty();
            message.setVisibility(View.GONE);
            int back = 1 - front;
            ImageView in = views[back];
            ImageView out = views[front];
            in.animate().cancel();
            out.animate().cancel();
            in.setImageBitmap(bitmap);
            in.setTranslationX(0f);
            if (!animate || ScreensaverPolicy.TRANSITION_NONE.equals(transition)) {
                in.setAlpha(1f);
                out.setAlpha(0f);
                out.setImageDrawable(null);
            } else if (ScreensaverPolicy.TRANSITION_SLIDE.equals(transition)) {
                in.setAlpha(1f);
                in.setTranslationX(getWidth());
                in.animate().translationX(0f).setDuration(650);
                out.animate().translationX(-getWidth()).setDuration(650).withEndAction(() -> {
                    out.setAlpha(0f);
                    out.setTranslationX(0f);
                    out.setImageDrawable(null);
                    setCaption(credit);
                });
            } else {
                in.setAlpha(0f);
                in.animate().alpha(1f).setDuration(700).withEndAction(() -> {
                    out.setAlpha(0f);
                    out.setImageDrawable(null);
                    setCaption(credit);
                });
            }
            front = back;
            caption.animate().cancel();
            caption.setAlpha(1f);
            // A cut has nothing to wait for, and the first picture of a cycle has no outgoing
            // credit to keep, so both take the line at once; every other transition hands it over
            // in the animation's end action above.
            if (firstPicture || !animate || ScreensaverPolicy.TRANSITION_NONE.equals(transition)) {
                setCaption(credit);
            }
            displayedCredit = credit;
            return true;
        }

        private boolean captionFits(String credit) {
            if (credit == null || credit.isEmpty()) return true;
            // Refuse rather than truncate oversized remote metadata before Android lays it out.
            if (credit.length() > 8192) return false;
            // Measure the actual text at the current font scale. Ellipsizing is not attribution.
            TextView measure = new TextView(getContext());
            measure.setTextSize(13);
            measure.setPadding(caption.getPaddingLeft(), caption.getPaddingTop(),
                    caption.getPaddingRight(), caption.getPaddingBottom());
            measure.setText(credit);
            int width = getWidth() > 0 ? getWidth() : getResources().getDisplayMetrics().widthPixels;
            int height = getHeight() > 0 ? getHeight() : getResources().getDisplayMetrics().heightPixels;
            measure.measure(View.MeasureSpec.makeMeasureSpec((int) (width * 0.7f), View.MeasureSpec.AT_MOST),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            return measure.getMeasuredHeight() <= height - dp(32);
        }

        private void setCaption(String credit) {
            if (credit == null || credit.isEmpty()) {
                caption.setVisibility(View.GONE);
            } else {
                caption.setText(credit);
                caption.setVisibility(View.VISIBLE);
            }
        }

        /** Nothing to show: the source's own sentence, centred, instead of a black frame. */
        void showMessage(String text) {
            release();
            caption.setVisibility(View.GONE);
            message.setText(text);
            message.setVisibility(View.VISIBLE);
        }

        void release() {
            displayedCredit = null;
            caption.animate().cancel();
            for (ImageView view : views) {
                view.animate().cancel();
                view.setImageDrawable(null);
            }
        }
    }

    /** Under the black view, over the page: Display off covers the screensaver like anything else. */
    private void addScreensaverLayer(FrameLayout root) {
        screensaverLayer = new FrameLayout(this);
        screensaverLayer.setBackgroundColor(Color.BLACK);
        screensaverLayer.setVisibility(View.GONE);
        root.addView(screensaverLayer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    /**
     * Over everything, the black view included, so it reads on the film too. Only a "Show it
     * now" shows it: the real screensaver has nothing to explain, a preview has to say that the
     * darker dashboard on the glass is the preview (2026-09-09: the dimmed page was taken for
     * the dashboard having opened, and the test was thought to have done nothing).
     */
    private void addScreensaverPreviewCaption(FrameLayout root) {
        TextView caption = new FlushText(this);
        caption.setTextColor(Color.WHITE);
        caption.setTextSize(15);
        caption.setGravity(Gravity.CENTER);
        int padX = dp(18);
        int padY = dp(10);
        caption.setPadding(padX, padY, padX, padY);
        android.graphics.drawable.GradientDrawable pill =
                new android.graphics.drawable.GradientDrawable();
        pill.setColor(0xCC1E1E2E);
        pill.setCornerRadius(dp(14));
        caption.setBackground(pill);
        caption.setVisibility(View.GONE);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        // Top centre: at the bottom it covered a picture's own credit line, which is a licence
        // obligation and outranks a preview's hint (seen on the phone 2026-09-09). The stats
        // overlay is hidden under every screensaver that covers the page, so nothing collides.
        params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        params.topMargin = dp(28);
        params.leftMargin = dp(24);
        params.rightMargin = dp(24);
        root.addView(caption, params);
        screensaverPreviewCaption = caption;
    }

    private void showScreensaverPreviewCaption(ScreensaverPolicy.Settings settings) {
        if (screensaverPreviewCaption == null) {
            return;
        }
        String what = ScreensaverPolicy.modeName(settings.mode);
        if (ScreensaverPolicy.DIM.equals(settings.mode)) {
            what += " at " + settings.dimPercent + " %";
        } else if (ScreensaverPolicy.PICTURES.equals(settings.mode)) {
            what += " from " + PictureSources.sourceName(settings.source);
        }
        screensaverPreviewCaption.setText("Screensaver preview: " + what
                + ". Tap anywhere to go back to the settings.");
        screensaverPreviewCaption.setVisibility(View.VISIBLE);
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


    /**
     * Buttons sized to their label, side by side, starting at the left edge, 8 dp apart on
     * Material's grid: a button that spans a 1280 px card reads as a bar, not a button.
     */
    private LinearLayout buttonRow(Button... buttons) {
        LinearLayout row = new LinearLayout(this);
        // Side by side on a tablet; one under the other on a phone, where two long labels and
        // their gap do not fit a card (the Pixel is 393 dp wide). A LinearLayout never wraps.
        boolean wide = getResources().getConfiguration().screenWidthDp >= 600;
        row.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        // Start, not centred: LinearLayout adds half a centred child's top margin to its offset,
        // which pushed every button 5 px below the row and cut off its bottom edge (2026-09-09).
        row.setBaselineAligned(false);
        row.setGravity(Gravity.START | Gravity.TOP);
        for (int i = 0; i < buttons.length; i++) {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0 && wide) {
                params.leftMargin = dp(8);
            }
            params.topMargin = dp(8);
            row.addView(buttons[i], params);
        }
        return row;
    }

    /**
     * The same row, centred, for a narrow centred card rather than a full-width page.
     *
     * <p>Only the tap recorder uses it. Its panel is a 460 dp card floating in the middle of the
     * screen and every other line in it, the step, the title, the hint and the readout, is
     * centred, so a button hugging the card's left edge reads as a misalignment rather than as
     * the house style (Juri, 2026-09-10). On a full-width settings page the left edge is exactly
     * right, which is why this is a second method and not a change to {@link #buttonRow}.
     *
     * <p>Horizontal only, deliberately: vertical centring is what pushed every button below its
     * row and cut off its bottom edge, see the comment in {@link #buttonRow}.
     */
    private LinearLayout centeredButtonRow(Button... buttons) {
        LinearLayout row = buttonRow(buttons);
        row.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.TOP);
        return row;
    }

    /**
     * Two or three buttons that stay side by side at any width, sharing the row equally.
     *
     * <p>{@link #buttonRow} stacks below 600 dp, which is right for a pair of long labels on a
     * phone and wrong for a pair of short ones: "Select all" over "Select none" and "Cancel" over
     * "Save" read as separate decisions rather than as one either-or, and cost two rows of a card
     * that has none to spare (Juri, 2026-09-10, B3 and B4). These labels fit across a phone's card
     * with room left, so they are laid out as a pair.
     *
     * <p>Weighted rather than sized to the label, so the pair is symmetrical: an either-or whose
     * halves are different widths reads as one option being the expected one.
     */
    private LinearLayout pairedButtonRow(Button... buttons) {
        // Where a row is wide the ordinary sizing is already right, and stretching two buttons
        // across a 1200 px card would turn each of them into a bar, which is the thing buttonRow
        // exists to avoid. A single button is not a pair either: "Next" on its own would be
        // stretched the whole width by the weights below. This method is only about what a phone
        // does with two or three buttons that belong together.
        if (buttons.length < 2 || getResources().getConfiguration().screenWidthDp >= 600) {
            return buttonRow(buttons);
        }
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        // Both off, for the reason buttonRow gives: a centred child's margin lands in the row's
        // own offset and cuts the bottom edge off every button in it.
        row.setBaselineAligned(false);
        row.setGravity(Gravity.START | Gravity.TOP);
        for (int i = 0; i < buttons.length; i++) {
            // A weighted child is given its share whatever its minimum says, and a 160 dp minimum
            // on a 393 dp phone would make the pair wider than the card it sits in.
            buttons[i].setMinWidth(0);
            buttons[i].setMinimumWidth(0);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) {
                params.leftMargin = dp(12);
            }
            params.topMargin = dp(10);
            row.addView(buttons[i], params);
        }
        return row;
    }

    /**
     * A compact action button for a list row: Use, Edit, Delete, Name, Remove.
     *
     * <p>Every other button in this app is sized for a wall panel touched from standing distance,
     * and at that size three of them fill a playlist row and crowd out the name they belong to
     * (Juri, 2026-09-10, A3 and B2). A row's actions are read after its name and only once the eye
     * has stopped there, so this is the one place where the smaller button is the honest one.
     *
     * <p>Every minimum is cleared, not only the width: a platform Button carries a 48 dp minimum
     * height that padding alone cannot get under, and that height is most of what made these read
     * as slabs.
     */
    /** What a row's action does, which is what decides its colour. See {@link #filledButton}. */
    private enum RowColour { PLAIN, MAIN, DANGER, ADD }

    private Button rowButton(KioskTheme theme, String label) {
        return rowButton(theme, label, RowColour.PLAIN);
    }


    /**
     * A chip: 32 dp, an 8 dp corner, the words at 14 sp. The page sizes under a folder are chips,
     * the one in force filled with the secondary container and a tick before its number.
     */
    private Button rowButton(KioskTheme theme, String label, RowColour colour) {
        Button chip = pillShaped(new Button(this), label, 12);
        chip.setMinHeight(dp(32));
        chip.setMinimumHeight(dp(32));
        boolean on = colour != RowColour.PLAIN;
        chip.setTextColor(on ? theme.onSecondaryContainer : theme.subtext);
        android.graphics.drawable.GradientDrawable face = theme.panel(
                on ? theme.secondaryContainer : Color.TRANSPARENT, dp(8));
        if (!on) {
            face.setStroke(dp(1), theme.border);
        }
        chip.setBackground(theme.ripple(face, theme.panel(Color.WHITE, dp(8)), theme.text));
        if (on) {
            chip.setText("✓ " + label);
        }
        return chip;
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
