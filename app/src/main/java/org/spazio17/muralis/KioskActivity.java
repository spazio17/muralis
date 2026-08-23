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
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

public final class KioskActivity extends Activity {
    private static final String TAG = "MuralisActivity";
    /**
     * How long to wait before retrying an unreachable dashboard.
     *
     * <p>Ten seconds, chosen for an ordinary home: the dashboard is unreachable mainly while Home
     * Assistant restarts or updates, which takes tens of seconds to minutes, and this retry repeats for
     * as long as that lasts because each failed attempt schedules the next. Fast enough that the panel
     * is back within seconds of the server returning, slow enough not to hammer a machine that is
     * already busy coming up.
     */
    private static final long RELOAD_BACKOFF_MS = 10_000L;
    /**
     * How long a load may take before it is treated as hung and retried.
     *
     * <p>Generous on purpose. A Home Assistant dashboard is a heavy single-page app and measured
     * 10-15 seconds to reach {@code onPageFinished} on the interim MediaPad, so a tight timeout would
     * reload a page that was merely slow and never let it finish. This only needs to catch a load that
     * is never going to complete.
     */
    private static final long LOAD_TIMEOUT_MS = 60_000L;
    /**
     * How long the single load issued by renderer-death recovery may take before it is retried.
     *
     * <p>Much tighter than {@link #LOAD_TIMEOUT_MS}, because this is not the situation that constant
     * is generous for. A renderer death is already known rather than suspected: the process that
     * draws the page is gone, the WebView is brand new, and the panel is showing nothing at all
     * until this load lands. Spending the full minute discovering the replacement load is not
     * coming turned a 1.4-second rebuild into 66 seconds of blank panel, measured on the API 26
     * panel 2026-08-21, 15:25:57 to 15:27:03, and both rebuilds were visible from across the room.
     *
     * <p>Twenty-five seconds rather than less, on purpose. The load after a renderer death is a
     * cold one: the new process has no warm cache, and a Home Assistant dashboard measured 10-15
     * seconds to {@code onPageFinished} on this hardware even warm. A tighter bound would retry a
     * load that was merely slow, which costs exactly the extra flash this exists to remove.
     *
     * <p>Applies to one load only, the one recovery issued. Every retry after it reverts to
     * {@link #LOAD_TIMEOUT_MS}, so a dashboard that is slow rather than dead can never be caught in
     * a fast reload loop.
     */
    private static final long RENDERER_DEATH_LOAD_TIMEOUT_MS = 25_000L;
    /**
     * How often the recovery clock looks at the dashboard. Two seconds, so a retry goes out within a
     * couple of seconds of becoming due; the work per tick is two long comparisons.
     */
    private static final long SUPERVISOR_INTERVAL_MS = 2_000L;
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
    /**
     * How many consecutive unchanged checks before the dashboard is treated as frozen: three, i.e.
     * fifteen minutes with not one visible change.
     *
     * <p>Still a heuristic, but no longer one that needs an operator-facing off switch to be safe.
     * The switch existed because a legitimately static page, one unchanging image, no live tiles, * is indistinguishable from a frozen one <em>by a single observation</em>. Over time it is not:
     * a static page never changed, while a frozen one was changing and stopped. So a reload now
     * requires having observed this generation of the page change at least once
     * ({@link #sawPageChange}); until then "unchanged" carries no information and nothing fires.
     *
     * <p>The trade is deliberate and in the safe direction: a dashboard that freezes before the
     * first observed change is not caught here. That case already belongs to the load-failure and
     * hung-load paths, and to the nightly recycle. Reloading a working panel every fifteen minutes
     * forever is the worse failure, and it is the one this rules out.
     */
    private static final int FROZEN_PAGE_STALE_CHECKS_TO_TRIGGER = 3;
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
     * Apps allowed to run while lock-task mode is on. Muralis itself plus the two escape hatches, so
     * a locked-down tablet is still recoverable by hand at the glass.
     */
    /**
     * Lock-task allowlist, resolved at runtime rather than hardcoded.
     *
     * <p>The privileged build could hardcode {@code com.android.launcher3} because it shipped the
     * launcher in its own ROM. This build runs on whatever stock Android it is installed on, and the
     * interim device is a Huawei tablet whose launcher is {@code com.huawei.android.launcher}, so a
     * fixed list would silently omit the launcher and turn the nine-tap escape hatch into a dead
     * end. That is the worst failure this hardening can produce, so the launcher is looked up.
     *
     * <p>Settings stays on the list as the second line of recovery, also resolved rather than
     * assumed.
     */
    private String[] lockTaskPackages() {
        java.util.LinkedHashSet<String> packages = new java.util.LinkedHashSet<>();
        packages.add(getPackageName());
        String launcher = systemLauncherPackage();
        if (launcher != null) {
            packages.add(launcher);
        }
        android.content.pm.ResolveInfo settings = getPackageManager().resolveActivity(
                new Intent(android.provider.Settings.ACTION_SETTINGS), 0);
        if (settings != null && settings.activityInfo != null) {
            packages.add(settings.activityInfo.packageName);
        }
        return packages.toArray(new String[0]);
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
    /** When the load in flight began, on the uptime clock, or 0 when nothing is loading. */
    private long loadStartedAtMs;
    /** When the next recovery attempt is due, on the uptime clock, or 0 when none is pending. */
    private long nextRetryAtMs;
    private int dashboardRetries;
    /** The last content fingerprint the frozen-page probe read, or null before the first check. */
    private String lastPageFingerprint;
    /** How many consecutive checks have read the identical fingerprint. */
    private int unchangedPageChecks;
    /**
     * Whether this generation of the page has ever been observed to change. Gates the frozen-page
     * reload: a page that has never changed may simply be static, and reloading it every fifteen
     * minutes forever would be the worse failure. See
     * {@link #FROZEN_PAGE_STALE_CHECKS_TO_TRIGGER}.
     */
    private boolean sawPageChange;
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
     * How to draw the screen that is currently up, so a rotation can redraw it. Null on the
     * dashboard, which needs no redraw: a WebView reflows itself, and rebuilding it would reload
     * the page. See {@link #onConfigurationChanged}.
     */
    private Runnable currentScreen;
    private final java.util.List<EscapeSequence.Tap> escapeTaps = new java.util.ArrayList<>();
    private boolean recorderVisible;
    private boolean recordingForLauncher;
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
            // Never on screen: the slider is disabled whenever the sensor is in charge, so the only
            // way here is a permission the operator can fix, and offerWriteSettingsGrant says that
            // in the one place that can act on it.
            Log.w(TAG, "Brightness not applied: " + problem);
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
        if (loadStartedAtMs != 0 || nextRetryAtMs != 0) {
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
        if (fingerprint.equals(lastPageFingerprint)) {
            unchangedPageChecks++;
        } else {
            // Not on the first probe of a generation: there is nothing to have changed from, and
            // counting it would let a page that has only ever been observed once look "live".
            if (lastPageFingerprint != null) {
                sawPageChange = true;
            }
            lastPageFingerprint = fingerprint;
            unchangedPageChecks = 0;
        }
        if (sawPageChange && unchangedPageChecks >= FROZEN_PAGE_STALE_CHECKS_TO_TRIGGER) {
            unchangedPageChecks = 0;
            long minutes = FROZEN_PAGE_CHECK_INTERVAL_MS * FROZEN_PAGE_STALE_CHECKS_TO_TRIGGER
                    / 60_000L;
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

    private void resetFrozenPageTracking() {
        lastPageFingerprint = null;
        unchangedPageChecks = 0;
        sawPageChange = false;
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
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // A wall kiosk has nothing to protect behind a swipe-to-unlock screen, and after a reboot
        // the dashboard would otherwise sit invisible behind the keyguard until somebody walked up
        // to the tablet. The product also disables the lockscreen by default; this covers the case
        // where one has been re-enabled on the device.
        showWhenLockedAndTurnScreenOn();
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
        if (config.dashboardUrl.isEmpty()) {
            showConfiguration(config);
        } else {
            showDashboard(config.dashboardUrl);
        }
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
    }

    @Override
    protected void onPause() {
        // Belt and braces for the same invariant: a bar disabled while nothing is pinned is a
        // tablet nobody can use.
        disableStatusBarIfPinned();
        // Deliberately do not call WebView.onPause(): the kiosk contract keeps
        // JavaScript and its Home Assistant WebSocket alive while visually off.
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacksAndMessages(null);
        releaseKioskPolicy();
        unregisterReceiver(controlReceiver);
        if (unlockReceiverRegistered) {
            unregisterReceiver(unlockReceiver);
            unlockReceiverRegistered = false;
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
    private boolean navigateBack() {
        if (recorderVisible) {
            // Same destination the recorder's own Cancel button uses, rather than a second opinion
            // about where the recorder goes back to.
            recorderVisible = false;
            showEscapeSequences(KioskConfig.load(this));
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
     * Shows over the keyguard and turns the screen on, on every supported API level.
     *
     * <p>{@code Activity.setShowWhenLocked} and {@code setTurnScreenOn} are <b>API 27</b>, one level
     * above this app's minSdk, and the interim MediaPad is API 26, so calling them unconditionally
     * threw {@code NoSuchMethodError} on the first frame. Verified on that hardware. The window
     * flags they replaced are deprecated from API 27 but present from API 1, so each level uses the
     * mechanism it actually has.
     *
     * <p>{@code FLAG_TURN_SCREEN_ON} is also what replaces the privileged build's
     * {@code PowerManager.wakeUp} for {@code display.wake}; see {@code KioskService.wakeDisplay()}.
     */
    @SuppressWarnings("deprecation")
    private void showWhenLockedAndTurnScreenOn() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
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
                redraw.run();
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
        boolean left = x <= zone;
        boolean right = x >= content.getWidth() - zone;
        boolean top = y <= zone;
        boolean bottom = y >= content.getHeight() - zone;
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
     * <p>Only reached when this device actually has a light sensor, so it never appears on hardware
     * where automatic brightness is impossible anyway. Lock task is released first: it would
     * otherwise refuse the launch outright, which is the same trap {@link #openSystemLauncher()}
     * documents.
     */
    private void offerWriteSettingsGrant() {
        Toast.makeText(this, R.string.auto_brightness_needs_permission, Toast.LENGTH_LONG).show();
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
            Toast.makeText(this, "The web admin password must be at least "
                    + MIN_HTTP_ADMIN_PASSWORD_LENGTH
                    + " characters, or blank to switch the web admin off",
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (typed.isEmpty()) {
            // Deliberate: an explicit clear must work even if the Keystore was unreadable when this
            // config loaded, which is exactly the case KioskConfig.save() now declines to persist.
            KioskConfig.clearHttpAdminPassword(this);
        } else {
            config.httpAdminPassword = typed;
            config.save(this);
        }
        KioskService.reloadConfiguration(this);
        Toast.makeText(this,
                typed.isEmpty() ? "Web admin switched off" : "Web admin password updated",
                Toast.LENGTH_SHORT).show();
    }

    /**
     * Applies one instantly-applied setting the moment it is touched, the way the web admin's
     * equivalent controls already do, and tells Home Assistant about it without waiting for the
     * next telemetry tick. These controls sit in the card each one is about rather than collected
     * into a box of their own, so this is what they have in common, not where they are.
     *
     * <p>Three things here are load-bearing. It loads a **fresh** {@link KioskConfig} instead of
     * mutating the snapshot the screen was built from, so a concurrent change from another surface
     * survives. It needs no controller restart, because every one of these settings is read live by
     * whoever consumes it (the overlay ticker, the telemetry loop). And it calls
     * {@link KioskService#publishTelemetrySoon} so the MQTT switch in
     * Home Assistant reflects the new value in under a second rather than up to a full interval
     * later, the same reason {@code KioskService.dispatch} republishes after an accepted command.
     */
    private void applyLiveSetting(java.util.function.Consumer<KioskConfig> change) {
        if (syncingLiveControls) {
            // Our own write, echoed back by liveSettingSyncTask. Saving it again would be harmless
            // but pointless, and would republish state for a change nobody made.
            return;
        }
        KioskConfig fresh = KioskConfig.load(this);
        change.accept(fresh);
        fresh.save(this);
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

    private KioskTheme currentTheme() {
        return KioskTheme.of(getSharedPreferences(UI_PREFERENCES, MODE_PRIVATE)
                .getBoolean(LIGHT_CONFIGURATION_THEME, false));
    }

    private void showConfiguration(KioskConfig config) {
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
        destroyWebView();
        kioskStopped = false;
        configurationVisible = true;
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
        urlInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        addField(dashboardCard, theme, "Dashboard URL", urlInput);
        EditText deviceIdInput = themedInput(theme, config.deviceId, false);
        addField(dashboardCard, theme, "Device ID", deviceIdInput);
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
        addField(mqttCard, theme, "Broker host", brokerInput);
        EditText portInput = themedInput(theme, Integer.toString(config.mqttPort), false);
        portInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        addField(mqttCard, theme, "Broker port", portInput);
        EditText usernameInput = themedInput(theme, config.mqttUsername, false);
        addField(mqttCard, theme, "Username", usernameInput);
        EditText passwordInput = themedInput(theme, config.mqttPassword, true);
        addField(mqttCard, theme, "Password", passwordInput);



        LinearLayout httpCard = card(theme, "Local web admin");
        EditText httpPortInput = themedInput(theme, Integer.toString(config.httpPort), false);
        httpPortInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        addField(httpCard, theme, "Port", httpPortInput);
        EditText httpAdminPasswordInput = themedInput(theme, config.httpAdminPassword, true);
        addField(httpCard, theme, "Admin password (blank disables the web admin)",
                httpAdminPasswordInput);
        // Applied on blur, not on the aggregate Save: waiting for "Open dashboard" meant the web
        // admin stayed dark, or kept an old password, until the operator happened to leave the
        // screen for an unrelated reason. Same shape as the auto-brightness checkbox above: a
        // field losing focus is as much a deliberate action as a click is.
        httpAdminPasswordInput.setOnFocusChangeListener((view, hasFocus) -> {
            if (!hasFocus) {
                applyHttpAdminPassword(httpAdminPasswordInput.getText().toString());
            }
        });
        // Whether the surface actually holds a socket, and at which address. It fails closed by
        // design, so without this the difference between "listening" and "silently off because the
        // password is too short" was one line in logcat, invisible from the panel itself.
        TextView httpState = new TextView(this);
        httpState.setTextSize(13);
        SystemStats.RuntimeFacts httpFacts = KioskRuntimeState.lastFacts();
        String address = httpFacts == null || httpFacts.ipAddress.isEmpty()
                ? "" : httpFacts.ipAddress;
        if (KioskRuntimeState.httpAdminListening()) {
            httpState.setTextColor(theme.ok);
            httpState.setText("Listening at http://" + (address.isEmpty() ? "this-tablet" : address)
                    + ":" + KioskRuntimeState.httpAdminPort());
        } else {
            httpState.setTextColor(theme.warn);
            httpState.setText("Not listening, set a password of at least "
                    + MIN_HTTP_ADMIN_PASSWORD_LENGTH + " characters");
        }
        LinearLayout.LayoutParams httpStateParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        httpStateParams.topMargin = dp(10);
        httpCard.addView(httpState, httpStateParams);


        // Everything about how the glass looks, in one card: the backlight, which way up the
        // panel is, and the colours of this screen itself. The web admin splits the last of those
        // into the header pill because it has a header to put it in; this screen does not.
        LinearLayout displayCard = card(theme, "Display");
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
                } else if (!KioskService.canWriteSystemSettings(this)) {
                    // The hardware can do it and the app cannot, which is a fixable state, so say so
                    // and offer the one screen that fixes it.
                    button.setChecked(!checked);
                    offerWriteSettingsGrant();
                    return;
                }
                applyBrightnessEnabledState(brightnessInput, brightnessValue, theme);
            });
            displayCard.addView(autoBrightnessInput, matchWrap());
        } else {
            // No sensor, so no control: a toggle that cannot work is worse than no toggle.
            autoBrightnessInput = null;
            TextView noSensor = new TextView(this);
            noSensor.setTextColor(theme.subtext);
            noSensor.setTextSize(13);
            noSensor.setText("This tablet has no ambient light sensor, so brightness is manual "
                    + "only. Set it remotely with display.brightness.");
            displayCard.addView(noSensor, matchWrap());
        }

        // Brightness, applied as it moves, exactly like the web admin's slider and for the same
        // reason: it is a standalone control, so a Save button between the operator and the panel
        // getting brighter is pure ceremony.
        TextView brightnessCaption = new TextView(this);
        brightnessCaption.setText("Brightness");
        brightnessCaption.setTextColor(theme.subtext);
        brightnessCaption.setTextSize(13);
        LinearLayout.LayoutParams brightnessCaptionParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        brightnessCaptionParams.topMargin = dp(14);
        displayCard.addView(brightnessCaption, brightnessCaptionParams);

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
        displayCard.addView(brightnessRow, matchWrap());

        TextView brightnessNote = new TextView(this);
        brightnessNote.setTextColor(theme.subtext);
        brightnessNote.setTextSize(12);
        displayCard.addView(brightnessNote, matchWrap());
        brightnessModeNote = brightnessNote;
        applyBrightnessEnabledState(brightnessInput, brightnessValue, theme);

        CheckBox portraitInput = themedCheckBox(theme, "Use portrait mode", config.portrait);
        portraitInput.setOnCheckedChangeListener((button, checked) -> {
            applyLiveSetting(fresh -> fresh.portrait = checked);
            // Applied here as well as saved, because this screen is the one surface that does not go
            // through KioskService and so never receives the broadcast that turns the window.
            applyOrientation();
        });
        displayCard.addView(portraitInput, matchWrap());

        // Kept in UI preferences rather than KioskConfig, and so deliberately outside
        // applyLiveSetting: it is a preference of whoever is standing at the tablet reading this
        // screen, not a property of the device, and nothing else has any business following it.
        CheckBox lightThemeInput = themedCheckBox(theme, "Light theme", theme.light);
        lightThemeInput.setOnCheckedChangeListener((button, checked) -> {
            getSharedPreferences(UI_PREFERENCES, MODE_PRIVATE).edit()
                    .putBoolean(LIGHT_CONFIGURATION_THEME, checked)
                    .apply();
            // Loaded fresh, not the snapshot this screen was built from. Redrawing from a stale
            // snapshot put old text in the URL and broker fields, and "Open dashboard" then wrote
            // those back over whatever another surface had changed meanwhile. Same rule the
            // rotation redraw and the save button already follow.
            showConfiguration(KioskConfig.load(this));
        });
        displayCard.addView(lightThemeInput, matchWrap());

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
                (button, checked) -> applyLiveSetting(fresh -> fresh.statsOverlay = checked));
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
                    setCheckedIfChanged(portraitInput,
                            KioskConfig.portraitEnabled(KioskActivity.this));
                } finally {
                    syncingLiveControls = false;
                }
                mainHandler.postDelayed(this, LIVE_SETTING_SYNC_INTERVAL_MS);
            }
        };
        mainHandler.postDelayed(liveSettingSyncTask, LIVE_SETTING_SYNC_INTERVAL_MS);

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

        LinearLayout aboutCard = card(theme, "About");
        TextView buildLine = new TextView(this);
        buildLine.setTextColor(theme.subtext);
        buildLine.setTextSize(13);
        buildLine.setText(appVersionSummary());
        aboutCard.addView(buildLine, matchWrap());
        Button aboutButton = secondaryButton(theme, "Version, privacy and terms");
        aboutButton.setOnClickListener(view -> showAbout());
        aboutCard.addView(aboutButton, matchWrap());

        page.addView(cardGrid(theme, java.util.Arrays.<View>asList(
                dashboardCard, mqttCard, httpCard, displayCard, statsCard, escapeCard,
                aboutCard)),
                matchWrap());

        Button open = primaryButton(theme, "Open dashboard");
        open.setOnClickListener(view -> {
            String url = normalizeUrl(urlInput.getText().toString());
            if (!url.isEmpty()) {
                // Loaded fresh rather than reusing the snapshot this screen was built from:
                // KioskConfig.save() writes every field, so saving the stale object would revert
                // anything MQTT or the web admin changed while the screen sat open.
                //
                // Only the fields with a text box on this screen are taken from the form. The
                // overlay switch, the publish interval, portrait, the brightness pair and the
                // admin password already applied themselves when touched, so they must be left at
                // whatever the fresh load holds.
                KioskConfig saving = KioskConfig.load(this);
                saving.dashboardUrl = url;
                saving.deviceId = deviceIdInput.getText().toString().trim();
                saving.mqttHost = brokerInput.getText().toString().trim();
                saving.mqttPort = parsePort(portInput.getText().toString(), 1883);
                saving.mqttUsername = usernameInput.getText().toString();
                saving.mqttPassword = passwordInput.getText().toString();
                saving.httpPort = parsePort(
                        httpPortInput.getText().toString(), KioskConfig.DEFAULT_HTTP_PORT);
                saving.save(this);
                KioskService.reloadConfiguration(this);
                showDashboard(url);
            }
        });
        // No "Configure Wi-Fi" button: Android Settings draws no navigation bar under this ROM,
        // so handing it the screen left no way back. Wi-Fi is set up once during provisioning, and
        // Settings is still reachable through the escape sequence when it is genuinely needed.
        page.addView(actionRow(java.util.Arrays.<View>asList(open)), matchWrap());

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
            pending.mqttPassword = passwordInput.getText().toString();
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
        renderSequenceRecorder(forLauncher);
    }

    /** Draws the recorder from whatever has been tapped so far. See {@link #showSequenceRecorder}. */
    private void renderSequenceRecorder(boolean forLauncher) {
        clearStatusChip();
        recorderVisible = true;
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

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER);
        panel.setBackground(theme.outlinedPanel(theme.surface, dp(18), dp(1)));
        int pad = dp(24);
        panel.setPadding(pad, pad, pad, pad);

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

        Button cancel = secondaryButton(theme, "Cancel");
        cancel.setOnClickListener(view -> {
            recorderVisible = false;
            showEscapeSequences(KioskConfig.load(this));
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
        root.addView(target, params);
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
        return scroll;
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
        if (getPackageName().equals(resolvedHomePackage())) {
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
     * <p>Reported, not judged. There was briefly a check here that warned below a hardcoded Chromium
     * major version; it was removed on 2026-08-19 because the threshold was invented. Home Assistant
     * publishes no minimum WebView version, and nothing can detect whether a page actually rendered
     * correctly, so the banner was an arbitrary number presented as a requirement. Stating the version
     * lets whoever is diagnosing a strange-looking dashboard see it and decide; that is the honest
     * amount of help this app can give. Do not reintroduce a threshold without a real source for it.
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
                R.string.privacy_policy_title, R.raw.privacy_policy));
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
     * <p>The draft banner is not decoration. Until real reviewed text replaces
     * {@code res/values/legal.xml}, anyone reading these screens must be able to tell.
     */
    private void showLegalDocument(int titleRes, int bodyRes) {
        currentScreen = () -> showLegalDocument(titleRes, bodyRes);
        configurationVisible = true;
        recorderVisible = false;
        applyKioskPolicy();
        KioskTheme theme = currentTheme();
        LinearLayout page = pageColumn(theme);
        // The version, not the app name: the terms themselves say "the version they apply to is shown
        // on the About screen", and a document should say which build it belongs to.
        page.addView(pageHeading(theme, getString(titleRes), appVersionSummary()), matchWrap());

        // Both the banner and the document below use this width, so they read as one column rather
        // than a full-bleed banner sitting over a centred body.
        int documentWidth = getResources().getConfiguration().screenWidthDp >= 720
                ? dp(640) : ViewGroup.LayoutParams.MATCH_PARENT;

        TextView draft = new TextView(this);
        draft.setText(getString(R.string.legal_draft_warning));
        draft.setTextSize(14);
        draft.setTextColor(theme.warn);
        draft.setPadding(dp(18), dp(14), dp(18), dp(14));
        draft.setBackground(theme.outlinedPanel(theme.surface, dp(12), dp(1)));
        LinearLayout.LayoutParams draftParams = new LinearLayout.LayoutParams(
                documentWidth, ViewGroup.LayoutParams.WRAP_CONTENT);
        draftParams.gravity = Gravity.CENTER_HORIZONTAL;
        draftParams.topMargin = dp(16);
        page.addView(draft, draftParams);

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

    private void addField(LinearLayout parent, KioskTheme theme, String label, EditText input) {
        TextView caption = new TextView(this);
        caption.setText(label);
        caption.setTextColor(theme.subtext);
        caption.setTextSize(13);
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
        input.setInputType(InputType.TYPE_CLASS_TEXT | (secret
                ? InputType.TYPE_TEXT_VARIATION_PASSWORD
                : InputType.TYPE_TEXT_VARIATION_NORMAL));
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
        recorderVisible = false;
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
        kioskStopped = false;
        resetLoadTracking();
        resetFrozenPageTracking();
        mainHandler.removeCallbacks(dashboardSupervisor);
        mainHandler.postDelayed(dashboardSupervisor, SUPERVISOR_INTERVAL_MS);
        mainHandler.removeCallbacks(frozenPageCheckTask);
        mainHandler.postDelayed(frozenPageCheckTask, FROZEN_PAGE_CHECK_INTERVAL_MS);
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

    private void destroyWebView() {
        mainHandler.removeCallbacks(overlayTask);
        mainHandler.removeCallbacks(dashboardSupervisor);
        mainHandler.removeCallbacks(frozenPageCheckTask);
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
            case "kiosk.stop":
                kioskStopped = true;
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
                break;
            case "display.wake":
                if (blackout != null && !kioskStopped) {
                    blackout.setVisibility(View.GONE);
                }
                // Clear the visual-off dimming and let the system brightness apply again, rather than
                // restoring a remembered level: which level is correct is the system's business now.
                setWindowBrightness(-1);
                break;
            case "display.portrait_on":
            case "display.portrait_off":
                // Both directions call the same method, which reads the setting KioskService has
                // already stored, so there is one source of truth rather than a boolean carried in
                // the broadcast that could disagree with what was saved.
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
        getSharedPreferences("kiosk_runtime", MODE_PRIVATE).edit()
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
        long now = android.os.SystemClock.uptimeMillis();
        KioskRuntimeState.recordPageError(description);
        loadFailedAtMs = now;
        nextRetryAtMs = now + RELOAD_BACKOFF_MS;
        // A dashboard going down is exactly the kind of thing worth knowing about before the next
        // scheduled telemetry tick, which can now be as much as five minutes away.
        KioskService.publishTelemetrySoon(this);
    }

    /**
     * Decides, every {@link #SUPERVISOR_INTERVAL_MS}, whether the dashboard needs reloading.
     *
     * <p>This is what makes the panel come back on its own after Home Assistant, or whatever serves
     * the page, has been down: each failed attempt makes the next one due {@link #RELOAD_BACKOFF_MS}
     * later, so it keeps trying for as long as the outage lasts and stops the moment a load succeeds.
     * There is no attempt limit on purpose. A wall panel has nobody to press a button, and an outage
     * that outlasts a limit would leave it dark until somebody noticed.
     *
     * <p>Exactly one recovery load is ever in flight: issuing one clears the pending attempt, and every
     * outcome puts one back. An error re-arms it after a backoff, a success clears it for good, and a
     * load that neither finishes nor errors is caught by the hung-load check below, which is the case
     * nothing else notices, a server that accepts the connection and then never answers.
     */
    private void superviseDashboard() {
        if (kioskStopped || webView == null) {
            return;
        }
        long now = android.os.SystemClock.uptimeMillis();

        long loadTimeoutMs = loadFollowsRendererDeath
                ? RENDERER_DEATH_LOAD_TIMEOUT_MS : LOAD_TIMEOUT_MS;
        if (nextRetryAtMs == 0 && loadStartedAtMs != 0
                && now - loadStartedAtMs > loadTimeoutMs) {
            Log.w(TAG, "Dashboard load has not finished in " + loadTimeoutMs + "ms; retrying");
            recordLoadFailure("load timed out");
            nextRetryAtMs = now;
        }
        if (nextRetryAtMs == 0 || now < nextRetryAtMs) {
            return;
        }
        if (!NetworkGate.isOnline(this)) {
            // The attempt stays due rather than being spent, so it goes out as soon as there is a
            // network again. Loading now would only render an error page and burn the backoff, which
            // is what a router reboot used to look like from the panel.
            return;
        }
        String url = KioskConfig.load(this).dashboardUrl;
        if (url.isEmpty()) {
            return;
        }
        dashboardRetries++;
        Log.i(TAG, "Dashboard retry " + dashboardRetries + ": " + url);
        // beginLoad(), not the three assignments this used to make by hand. It is the only place
        // that also calls resetFrozenPageTracking(), and this is the one path that services a
        // frozen-page reload: recordLoadFailure("page appears frozen") only arms nextRetryAtMs, and
        // the retry lands here. Setting the timestamps inline left sawPageChange and the last
        // fingerprint from the *previous* generation in place, so the reload never cleared the state
        // that authorised it, three more identical probes and it fired again, every fifteen
        // minutes, on a panel that was working. That is the failure the gate exists to prevent, so
        // every path that starts a generation must go through here.
        //
        // Cleared here rather than in beginLoad(): this is the one place that turns a *pending*
        // recovery into an issued load, so it is the point at which the tighter post-renderer-death
        // bound has been spent. beginLoad() also runs from showDashboard, which is what recovery
        // calls to create the WebView in the first place, so clearing it there would clear the flag
        // before the load it applies to had even started.
        loadFollowsRendererDeath = false;
        beginLoad();
        loadStartedAtMs = now;
        // loadUrl rather than reload(): reload() re-runs the last request, which after an error is the
        // request that produced the cached error page. loadUrl always asks for the configured
        // dashboard, which is what kiosk.restart does and is known to work.
        webView.loadUrl(url);
    }

    /** Forgets any pending recovery and any load in flight. Nothing is loading after this. */
    private void resetLoadTracking() {
        loadStartedAtMs = 0;
        nextRetryAtMs = 0;
        loadFailedAtMs = 0;
        loadFollowsRendererDeath = false;
        resetFrozenPageTracking();
    }

    /**
     * Marks a load as starting now, at every site that asks the WebView to load something.
     *
     * <p>Not left to {@code onPageStarted}, deliberately. That callback fires when the WebView starts
     * receiving a page, so for the failure this hung-load timeout exists to catch, a server that
     * accepts the connection and then never answers, it may not fire at all. Arming the clock from the
     * callback would mean the one case nothing else notices is also the one case the timeout misses.
     */
    private void beginLoad() {
        loadStartedAtMs = android.os.SystemClock.uptimeMillis();
        nextRetryAtMs = 0;
        loadFailedAtMs = 0;
        resetFrozenPageTracking();
    }

    /**
     * Whether the load currently in flight has already reported a failure.
     *
     * <p>This exists because of a bug that made the retry logic useless. An error page is still a page:
     * for an HTTP 502 the WebView reports {@code onReceivedHttpError} and then {@code onPageFinished},
     * because the proxy's error body loaded perfectly well. {@code onPageFinished} cancelled the pending
     * reload, so **the retry was cancelled by the very failure that scheduled it** and the panel sat on
     * the error page indefinitely. Measured 2026-08-19: one 502 logged, zero reloads.
     */
    /**
     * When the load in flight last reported a failure, on the uptime clock, or 0 for never.
     *
     * <p>A timestamp rather than a boolean, because **the WebView callbacks do not arrive in the order
     * they read like**. Measured 2026-08-19: for an HTTP 502 the sequence was `onReceivedHttpError`,
     * then `onPageStarted`, then `onPageFinished`. A boolean cleared in `onPageStarted` was therefore
     * wiped by the very load that had just failed, `onPageFinished` then treated the error page as a
     * success, cancelled the pending retry and recorded a healthy load. Net effect: one 502 logged, the
     * retry never ran, and the panel sat on the error page. A timestamp cannot be undone by a
     * late-arriving callback.
     */
    private long loadFailedAtMs;

    /**
     * How recently a failure must have been reported for the load that is finishing to count as failed.
     * Generous enough to absorb out-of-order callbacks, far shorter than any retry interval.
     */
    private static final long LOAD_FAILURE_WINDOW_MS = 5_000L;

    /**
     * Whether the load in flight is the one {@code onRenderProcessGone} issued, and so is held to
     * {@link #RENDERER_DEATH_LOAD_TIMEOUT_MS} rather than {@link #LOAD_TIMEOUT_MS}.
     *
     * <p>Set after {@code showDashboard} returns, never before: that method calls
     * {@link #resetLoadTracking()}, which clears this along with the rest of the load bookkeeping,
     * so setting it first would have it wiped by the very rebuild it describes.
     */
    private boolean loadFollowsRendererDeath;

    private final class KioskWebViewClient extends WebViewClient {
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
            loadStartedAtMs = android.os.SystemClock.uptimeMillis();
            // A new document, and often one this app did not ask for: a JS location change, a login
            // redirect, a server 302. Whatever the last document's content looked like says nothing
            // about this one, and carrying sawPageChange across meant a static page inherited
            // permission to be declared frozen. Deliberately not the whole of beginLoad(): the
            // retry bookkeeping belongs to whoever issued the load, and clearing nextRetryAtMs here
            // would cancel a pending recovery.
            resetFrozenPageTracking();
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            // An error page is still a page: for an HTTP 502 the WebView reports onReceivedHttpError
            // and then onPageFinished, because the proxy's error body loaded perfectly well. Treating
            // that as a successful load would clear the pending retry and make the health figures
            // claim a dashboard that never came.
            if (android.os.SystemClock.uptimeMillis() - loadFailedAtMs < LOAD_FAILURE_WINDOW_MS) {
                return;
            }
            loadStartedAtMs = 0;
            nextRetryAtMs = 0;
            loadFollowsRendererDeath = false;
            KioskRuntimeState.recordPageFinished(url);
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
                    // supervisor now holds that load to RENDERER_DEATH_LOAD_TIMEOUT_MS instead of
                    // the minute a merely-slow dashboard is owed, because a panel that already
                    // knows its renderer died should not spend that minute showing nothing.
                    loadFollowsRendererDeath = true;
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
     * Turns the panel upright or on its side, from the stored setting.
     *
     * <p>Applies unconditionally, not behind the device-owner gate the immersive chrome sits behind:
     * a wall panel is mounted one way whether or not it is provisioned, and an operator who ticks
     * "use portrait mode" means it on any install.
     *
     * <p>The *sensor* variants rather than the fixed ones, matching the manifest's own
     * {@code sensorLandscape}: a panel screwed to the wall the other way up then still renders the
     * right way round, and neither variant lets the dashboard flip between landscape and portrait on
     * its own, which is the behaviour a wall mount actually wants.
     *
     * <p>Cheap to call repeatedly. Android ignores a request for the orientation already in force,
     * and {@code configChanges} in the manifest already covers {@code orientation|screenSize}, so a
     * change rotates the window without recreating the activity or reloading the dashboard.
     */
    private void applyOrientation() {
        setRequestedOrientation(KioskConfig.portraitEnabled(this)
                ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
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
        // Only claimed when it is not already ours. applyKioskPolicy runs from onResume, and
        // addPersistentPreferredActivity appends rather than replaces, so calling it unconditionally
        // added an entry to the package manager's persistent-preferred list on every resume and grew
        // package-restrictions.xml without bound. The guard also keeps the normal case free: this is
        // needed once, after ownership is granted to an already-running app, which is what happened
        // on the MediaPad where lock task only engaged after a restart.
        if (!getPackageName().equals(resolvedHomePackage())) {
            KioskDeviceAdminReceiver.pinAsHomeActivity(this);
        }
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
