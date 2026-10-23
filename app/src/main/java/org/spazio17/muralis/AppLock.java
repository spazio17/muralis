/*
 * Copyright 2026 Juri Calleri (spazio17) and the Muralis contributors
 * Licensed under the PolyForm Shield License 1.0.0: see LICENSE.md at the repository root.
 */
package org.spazio17.muralis;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.PixelFormat;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * App lock: the panel shows one installed app the person chose instead of the web
 * page, as Fully's Single App Mode and FreeKiosk's External App do. Muralis stays HOME underneath
 * and opens the app again whenever it is uncovered (the app closed, crashed or was left); on a
 * device-owner panel the lock-task allowlist is Muralis and that one app.
 *
 * <p>While the app is in front, Muralis sees nothing of its own screen, so it lays small windows
 * of its own over the app ("Display over other apps"): four transparent squares in the
 * corners, as large as the dashboard's ({@link #CORNER_DP}), which take the taps landing in
 * them for the escape combinations (Android gives a tap to one app only, so the app loses
 * those corners; the owner, 2026-10-15), one of them also told of
 * every other touch, without where, so Ambient knows the panel is in use; and the status light's
 * frame, which takes no touch. Pop-ups and Identify are drawn in windows of their own straight
 * over the app; Ambient and the black display-off, which cover the whole screen anyway, bring
 * Muralis's own screen forward, and when they end the app is opened again.
 */
final class AppLock {
    private static final String TAG = "MuralisAppLock";
    /**
     * The corners as large as the ones the dashboard listens on, so a combination recorded
     * anywhere in them works over the app too (2026-10-15, after testing with Spotify;
     * it replaces the 48 dp of the first version).
     */
    static final int CORNER_DP = KioskActivity.ADMIN_ESCAPE_ZONE_DP;

    /** Muralis opened the app and has not been in front since. */
    static volatile boolean appUp;
    /** Set just before the app is opened, so the pause that follows means "the app is up". */
    static volatile boolean opening;
    /** Muralis was called forward over the app on purpose, so its return is not a crash. */
    static volatile boolean calledForward;
    /** The last touch anywhere on the app, on the uptime clock, for Ambient's idle time. */
    static volatile long lastTouchUptimeMs;

    private static final List<View> windows = new ArrayList<>();
    private static KioskActivity.StatusLightFrame lightFrame;
    private static final List<EscapeSequence.Tap> taps = new ArrayList<>();

    private AppLock() {
    }

    /** One app a person can choose: what Android calls it, and its package. */
    static final class App {
        final String label;
        final String packageName;

        App(String label, String packageName) {
            this.label = label;
            this.packageName = packageName;
        }
    }

    /**
     * Every app with a launcher icon but Muralis, by name, and never Android's own doors out:
     * Settings, a home screen or the Play Store, which would hand the person the whole device.
     * The apps that came with the device (Chrome, the maker's tools) only when {@code withSystem}:
     * left out unless the person asks, since the choice is theirs to make (2026-10-15).
     */
    static List<App> launchable(Context context, boolean withSystem) {
        PackageManager packages = context.getPackageManager();
        List<ResolveInfo> found = packages.queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0);
        List<App> apps = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        java.util.Set<String> doors = doors(context);
        for (ResolveInfo info : found) {
            String name = info.activityInfo == null ? null : info.activityInfo.packageName;
            if (name == null || name.equals(context.getPackageName()) || !seen.add(name)
                    || doors.contains(name)
                    || (!withSystem && info.activityInfo.applicationInfo != null
                            && (info.activityInfo.applicationInfo.flags
                                    & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0)) {
                continue;
            }
            apps.add(new App(String.valueOf(info.loadLabel(packages)), name));
        }
        Collections.sort(apps, (a, b) -> a.label.compareToIgnoreCase(b.label));
        return apps;
    }

    /** The packages that open Android's Settings, a home screen or the Play Store. */
    static java.util.Set<String> doors(Context context) {
        PackageManager packages = context.getPackageManager();
        java.util.Set<String> doors = new java.util.HashSet<>();
        doors.add("com.android.vending");
        for (Intent door : new Intent[] {
            new Intent(android.provider.Settings.ACTION_SETTINGS),
            new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)}) {
            for (ResolveInfo info : packages.queryIntentActivities(door, 0)) {
                if (info.activityInfo != null) {
                    doors.add(info.activityInfo.packageName);
                }
            }
        }
        doors.remove(context.getPackageName());
        return doors;
    }

    /** What Android calls {@code packageName}, or the package itself when it is gone. */
    static String labelOf(Context context, String packageName) {
        try {
            PackageManager packages = context.getPackageManager();
            return String.valueOf(packages.getApplicationLabel(
                    packages.getApplicationInfo(packageName, 0)));
        } catch (PackageManager.NameNotFoundException gone) {
            return packageName;
        }
    }

    /** Whether Muralis may draw over other apps, which App lock needs for its way out. */
    static boolean canDrawOver(Context context) {
        return android.provider.Settings.canDrawOverlays(context);
    }

    /**
     * Why the chosen app cannot be shown now, or null when it can; null too when App lock is
     * off ({@code packageName} empty), which the caller tells apart.
     */
    static String problem(Context context, String packageName) {
        if (packageName.isEmpty()) {
            return null;
        }
        if (context.getPackageManager().getLaunchIntentForPackage(packageName) == null) {
            return "the app " + packageName + " is not installed, or has nothing to open";
        }
        if (!canDrawOver(context)) {
            return "Muralis may not display over other apps yet; without it your corner taps"
                    + " could not reach Muralis over the app";
        }
        return null;
    }

    /** Opens the chosen app over Muralis; false when Android has nothing to open. */
    static boolean open(Context context, String packageName) {
        Intent launch = context.getPackageManager().getLaunchIntentForPackage(packageName);
        if (launch == null) {
            return false;
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        opening = true;
        try {
            context.startActivity(launch);
        } catch (RuntimeException refused) {
            opening = false;
            Log.w(TAG, "The app " + packageName + " could not be opened", refused);
            return false;
        }
        Log.i(TAG, "Opened " + packageName);
        return true;
    }

    /** Brings Muralis's own screen over the app, for a pop-up, Identify, Ambient or the dark. */
    static void bringMuralisForward(Context context) {
        if (!appUp) {
            return;
        }
        // From the application, never from the paused activity itself: started by that activity,
        // Android 8 delivered the intent but left the app's task in front (2026-10-15).
        Context app = context.getApplicationContext();
        calledForward = true;
        try {
            app.startActivity(new Intent(app, KioskActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
        } catch (RuntimeException refused) {
            Log.w(TAG, "Muralis could not come forward over the app", refused);
        }
    }

    /** The windows over the app: the four corners, and the status light when it is on. */
    static void showOverWindows(Context context) {
        Context app = context.getApplicationContext();
        WindowManager manager = app.getSystemService(WindowManager.class);
        if (manager == null || !windows.isEmpty() || !canDrawOver(app)) {
            return;
        }
        int size = Math.round(CORNER_DP * app.getResources().getDisplayMetrics().density);
        String[][] corners = {
            {EscapeSequence.TOP_LEFT, String.valueOf(Gravity.TOP | Gravity.START)},
            {EscapeSequence.TOP_RIGHT, String.valueOf(Gravity.TOP | Gravity.END)},
            {EscapeSequence.BOTTOM_LEFT, String.valueOf(Gravity.BOTTOM | Gravity.START)},
            {EscapeSequence.BOTTOM_RIGHT, String.valueOf(Gravity.BOTTOM | Gravity.END)},
        };
        for (int i = 0; i < corners.length; i++) {
            String zone = corners[i][0];
            boolean watcher = i == 0;
            View corner = new View(app);
            corner.setOnTouchListener((view, event) -> {
                lastTouchUptimeMs = SystemClock.uptimeMillis();
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    cornerTapped(app, zone, event.getEventTime());
                }
                return event.getActionMasked() != MotionEvent.ACTION_OUTSIDE;
            });
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(size, size,
                    overlayType(),
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                            | (watcher ? WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH : 0),
                    PixelFormat.TRANSLUCENT);
            params.gravity = Integer.parseInt(corners[i][1]);
            params.setTitle("Muralis App lock corner");
            wholeScreen(params);
            if (add(manager, corner, params)) {
                windows.add(corner);
            }
        }
        syncLight(app);
        Log.i(TAG, "Corners laid over the app");
    }

    /** Takes the windows away, as Muralis comes back in front. */
    static void hideOverWindows(Context context) {
        WindowManager manager = context.getApplicationContext()
                .getSystemService(WindowManager.class);
        for (View window : windows) {
            remove(manager, window);
        }
        windows.clear();
        if (lightFrame != null) {
            remove(manager, lightFrame);
            lightFrame = null;
        }
        synchronized (taps) {
            taps.clear();
        }
    }

    /** The status light's frame over the app, drawn or taken away as it is set now. */
    static void syncLight(Context context) {
        Context app = context.getApplicationContext();
        WindowManager manager = app.getSystemService(WindowManager.class);
        StatusLight.State light = KioskConfig.statusLightOf(app);
        if (manager == null || windows.isEmpty() || !light.on) {
            if (lightFrame != null) {
                remove(manager, lightFrame);
                lightFrame = null;
            }
            return;
        }
        if (lightFrame == null) {
            KioskActivity.StatusLightFrame frame = new KioskActivity.StatusLightFrame(app,
                    Math.round(16 * app.getResources().getDisplayMetrics().density));
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT, overlayType(),
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            params.setTitle("Muralis status light");
            wholeScreen(params);
            if (!add(manager, frame, params)) {
                return;
            }
            lightFrame = frame;
        }
        lightFrame.setColor(StatusLight.argb(light));
    }

    /**
     * A corner tap over the app, matched against both combinations as the panel's own screen
     * matches them; a match brings Muralis forward to finish it there (the PIN, the settings or
     * the home screen).
     */
    private static void cornerTapped(Context context, String zone, long eventTimeMs) {
        KioskConfig config = KioskConfig.load(context);
        List<String> toLauncher = EscapeSequence.parse(config.launcherSequence);
        List<String> toSettings = EscapeSequence.parse(config.settingsSequence);
        String matched = null;
        synchronized (taps) {
            List<EscapeSequence.Tap> trimmed = EscapeSequence.trim(taps, eventTimeMs,
                    EscapeSequence.MAX_GAP_MS, EscapeSequence.MAX_LENGTH);
            taps.clear();
            taps.addAll(trimmed);
            taps.add(new EscapeSequence.Tap(zone, eventTimeMs));
            // Longer first, as on the panel's screen: the more specific one wins.
            boolean launcherFirst = toLauncher.size() >= toSettings.size();
            for (int pass = 0; pass < 2 && matched == null; pass++) {
                boolean launcher = (pass == 0) == launcherFirst;
                if (EscapeSequence.matchesTail(taps, launcher ? toLauncher : toSettings,
                        EscapeSequence.MAX_GAP_MS)) {
                    matched = launcher ? KioskActivity.ESCAPE_LAUNCHER : KioskActivity.ESCAPE_SETTINGS;
                }
            }
            if (matched != null) {
                taps.clear();
            }
        }
        if (matched == null) {
            return;
        }
        Log.i(TAG, "A combination over the app: " + matched);
        calledForward = true;
        KioskActivity.handEscape(matched);
        try {
            context.startActivity(new Intent(context, KioskActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
        } catch (RuntimeException refused) {
            Log.w(TAG, "Muralis could not come forward for the combination", refused);
        }
    }

    /**
     * Laid against the glass's real edges where no bar takes the tap, as Muralis's own screen
     * is: otherwise Android 11 and newer fit an overlay inside even a gesture navigation bar,
     * and the bottom corners sat a bar's height above the corner a person taps (seen on Android
     * 16). A bar with buttons does take taps, so there the corners stay just clear of it, as
     * they do on Android 10 and older.
     */
    private static void wholeScreen(WindowManager.LayoutParams params) {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            // Clear of what takes taps itself (three-button navigation, a status bar that
            // shows), against the glass under gesture navigation, whose bar takes no tap.
            params.setFitInsetsTypes(android.view.WindowInsets.Type.tappableElement());
            params.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        } else if (android.os.Build.VERSION.SDK_INT >= 28) {
            params.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
    }

    /**
     * A pop-up or Identify drawn straight over the app (2026-10-15: over the locked app,
     * not over Muralis's page): {@code view} in a window of its own over the whole screen,
     * taking touches when {@code touchable} (the pop-up closes on a touch). False when refused.
     */
    static boolean showOver(Context context, View view, boolean touchable) {
        WindowManager manager = context.getApplicationContext()
                .getSystemService(WindowManager.class);
        if (manager == null || !canDrawOver(context)) {
            return false;
        }
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT, overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | (touchable ? 0 : WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        wholeScreen(params);
        if (!touchable) {
            // Android 12 and newer drop a tap that passes through another app's overlay unless
            // the overlay is at most 0.8 opaque, so a window that takes no touch stays at that;
            // fully opaque, it took every tap from the app (Identify).
            params.alpha = 0.8f;
        }
        params.setTitle("Muralis over the app");
        if (!add(manager, view, params)) {
            return false;
        }
        // The corners laid again on top: a pop-up over the whole screen would otherwise take
        // the first tap of a combination (it closes on a touch) before the corners saw it.
        for (View corner : windows) {
            WindowManager.LayoutParams own =
                    (WindowManager.LayoutParams) corner.getLayoutParams();
            remove(manager, corner);
            add(manager, corner, own);
        }
        return true;
    }

    /**
     * The stats readout over the app (2026-10-15): a window the size of the readout at
     * {@code x}, {@code y} from the screen's top left, taking the touches on it alone, so it can
     * be picked up and moved while the app keeps every other tap. False when refused.
     */
    static boolean showReadout(Context context, View view, int x, int y) {
        WindowManager manager = context.getApplicationContext()
                .getSystemService(WindowManager.class);
        if (manager == null || !canDrawOver(context)) {
            return false;
        }
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT, overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        params.gravity = android.view.Gravity.TOP | android.view.Gravity.LEFT;
        params.x = x;
        params.y = y;
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            params.setFitInsetsTypes(0);
        }
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            params.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        params.setTitle("Muralis stats over the app");
        if (!add(manager, view, params)) {
            return false;
        }
        // The corners laid again on top, so a combination tapped there is still theirs.
        for (View corner : windows) {
            WindowManager.LayoutParams own =
                    (WindowManager.LayoutParams) corner.getLayoutParams();
            remove(manager, corner);
            add(manager, corner, own);
        }
        return true;
    }

    /** Moves the readout's window to {@code x}, {@code y} from the screen's top left. */
    static void moveReadout(Context context, View view, int x, int y) {
        if (!(view.getLayoutParams() instanceof WindowManager.LayoutParams)) {
            return;
        }
        WindowManager.LayoutParams params = (WindowManager.LayoutParams) view.getLayoutParams();
        if (params.x == x && params.y == y) {
            return;
        }
        params.x = x;
        params.y = y;
        try {
            context.getApplicationContext().getSystemService(WindowManager.class)
                    .updateViewLayout(view, params);
        } catch (RuntimeException gone) {
            Log.d(TAG, "The readout over the app was already gone");
        }
    }

    /** Takes a window {@link #showOver} laid away. */
    static void removeOver(Context context, View view) {
        remove(context.getApplicationContext().getSystemService(WindowManager.class), view);
    }

    private static int overlayType() {
        return WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
    }

    private static boolean add(WindowManager manager, View view,
            WindowManager.LayoutParams params) {
        try {
            manager.addView(view, params);
            return true;
        } catch (RuntimeException refused) {
            Log.w(TAG, "A window over the app was refused", refused);
            return false;
        }
    }

    private static void remove(WindowManager manager, View view) {
        if (manager == null || view == null) {
            return;
        }
        try {
            manager.removeView(view);
        } catch (RuntimeException alreadyGone) {
            Log.d(TAG, "A window over the app was already gone");
        }
    }
}
