package io.github.dqot.toplinedate;

import android.accessibilityservice.AccessibilityService;
import android.animation.ValueAnimator;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.view.animation.PathInterpolator;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Draws the date next to the status bar clock as an accessibility overlay (the only non-root
 * window type above the status bar).
 *
 * What it reads: window bounds from the accessibility window list, and inside the status bar only
 * the clock and the icons right after it. In colour "auto" mode it also takes a screenshot on app
 * switches and looks at the brightness of the bar strip. Nothing is stored or sent anywhere.
 *
 * The key observations it relies on (ColorOS 17, OPPO Pad mini):
 * - The bar is a full-width TYPE_SYSTEM window at the top; it leaves the window list in fullscreen
 *   and is replaced by a tall window for the shade and lock screen.
 * - SystemUI marks its clock invisible when it hides it (recents, fullscreen entry, unlock), which
 *   is the earliest signal for hiding; leaving fullscreen, SystemUI stirs ~0.3 s before the bar is
 *   listed again, which is used to show early.
 * - The whole bar shifts by a pixel a minute (burn-in protection), equally in both orientations.
 */
public class DateService extends AccessibilityService
        implements SharedPreferences.OnSharedPreferenceChangeListener {

    static final String TAG = "TopLineDate";

    private static final String SYSTEM_UI = "com.android.systemui";
    private static final String LAUNCHER = "com.android.launcher";
    private static final int DARK_TEXT = 0xCC000000;   // the ColorOS clock is 80% alpha
    private static final int LIGHT_TEXT = 0xCCFFFFFF;
    private static final PathInterpolator EASE = new PathInterpolator(0.4f, 0f, 0.2f, 1f);
    private static final android.view.animation.Interpolator ACCEL = new android.view.animation.AccelerateInterpolator();
    // The bar drops in fast and settles slowly.
    private static final android.view.animation.Interpolator DROP = new android.view.animation.DecelerateInterpolator(1.5f);
    private static final Pattern TIME = Pattern.compile("^[ ]*[0-9]{1,2}[ ]*[:.][ ]*[0-9]{2}.*");

    private static volatile String lastStatus = "not connected";

    static String status() {
        return lastStatus;
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager windowManager;
    private KeyguardManager keyguard;
    private PowerManager power;
    private SharedPreferences prefs;
    private BroadcastReceiver receiver;
    private TextView view;
    private WindowManager.LayoutParams params;
    private boolean attached;

    // ---- system state -------------------------------------------------------------------------
    private boolean screenOn = true;
    private boolean unlocked = true;
    private boolean wasLocked;
    private String foreground = "";                 // package of the last app window to open
    private int barHeight;
    private int barWindowId = -1;
    private boolean statusBarVisible = true;        // the bar window is in the window list
    private boolean barExpanded;                    // shade or lock screen covers the bar
    private boolean menuOverlay;                    // a floating window's menu covers the list
    private boolean wasOverlay;
    private boolean wasExpanded;
    private boolean floatingSeen;                   // a floating app window was on screen
    private final HashSet<Integer> shadeIds = new HashSet<>();
    private long barGoneSince;
    private boolean unlockWaiting;                  // after unlock: until the clock shows
    private boolean unlockFade;                     // the next show fades in like the clock
    private boolean hiddenByClock;                  // hidden while the bar stayed (recents)
    private static final long UNLOCK_CLOCK_TIMEOUT = 1500;

    private long lastConfigAt = -10_000;

    // ---- clock and anchor ---------------------------------------------------------------------
    private boolean needClock = true;               // rescan the bar on the next check
    private AccessibilityNodeInfo clockNode;
    private boolean clockVisible = true;
    private int clockRight = -1;
    private int iconsWidth;                         // e.g. a notification badge after the clock
    private int anchorRight = -1;                   // clock right edge plus those icons
    private int anchorCenterY;
    private boolean anchorHasIcons;
    private int dpi;                                // profile whose size/placement applies
    private int screenDpi;                          // real density; clock geometry depends on it
    private final int[] clockBase = {-1, -1};       // clock right minus burn-in shift [port, land]
    private final int[] clockCenterY = {0, 0};
    private int shift;                              // current burn-in shift of the bar
    private boolean freshRotation;

    // ---- visibility ---------------------------------------------------------------------------
    private boolean shown = true;
    private long hiddenAt = -10_000;
    private long holdUntil;                         // no showing before (unlock, rotation)
    private long rotationGraceUntil;                // stay up through a rotation until
    private long settleUntil;                       // ignore clock flicker until
    private boolean predictShown;                   // shown early while leaving fullscreen
    private boolean fromFullscreen;                 // hidden because an app went fullscreen
    private String fullscreenApp = "";
    private ValueAnimator slider;
    private FullscreenRotator rotator;              // labs: landscape while fullscreen

    // ---- colour -------------------------------------------------------------------------------
    private int autoColor = LIGHT_TEXT;
    private final HashMap<String, Integer> colorByApp = new HashMap<>();
    private long lastShot;

    private final Runnable clearPredictShow = () -> {
        predictShown = false;
        checkStatusBar();
    };
    private boolean predictHidden;
    private int lastBarTop = Integer.MIN_VALUE;
    private int dropFrom;                           // bar found part-way down: start the date there
    private long clockHiddenAt;
    private boolean menuWaitArmed;
    private long menuEndedAt;
    private long barListedAt;
    private long handleStirAt;
    private long appShownAt;
    private static final long MENU_WAIT_MS = 60;
    private static final long MENU_GAP_MS = 120;
    private long barPendingSince;                   // the bar left the list in a transition
    private static final long BAR_PENDING_MAX = 1500;
    private final Runnable clearPredictHide = () -> {
        predictHidden = false;
        checkStatusBar();
    };
    private final Runnable sampler = this::sampleBar;
    private long pollUntil;
    private final Runnable clockPoll = new Runnable() {
        @Override
        public void run() {
            if (clockVisible || !statusBarVisible || SystemClock.uptimeMillis() > pollUntil) return;
            quickClockCheck();
            if (!clockVisible) handler.postDelayed(this, 50);
        }
    };

    // ============================================================================================
    // Lifecycle
    // ============================================================================================

    @Override
    protected void onServiceConnected() {
        prefs = Prefs.get(this);
        SharedPreferences.Editor cleanup = prefs.edit();
        for (String k : Prefs.OBSOLETE_KEYS) cleanup.remove(k);
        cleanup.apply();
        prefs.registerOnSharedPreferenceChangeListener(this);

        windowManager = getSystemService(WindowManager.class);
        keyguard = getSystemService(KeyguardManager.class);
        power = getSystemService(PowerManager.class);
        screenOn = power.isInteractive();
        unlocked = screenOn && !keyguard.isKeyguardLocked();
        barHeight = resourceBarHeight();
        dpi = Prefs.activeDpi(this, prefs);
        screenDpi = getResources().getConfiguration().densityDpi;
        loadClockGeometry();
        rotator = new FullscreenRotator(this, prefs);
        rotator.restoreIfStale();

        view = new TextView(this);
        view.setSingleLine(true);
        view.setIncludeFontPadding(false);
        view.setGravity(Gravity.CENTER_VERTICAL);
        // Overlays are not told whether the bar is shown, but they do get its height.
        view.setOnApplyWindowInsetsListener((v, insets) -> {
            int top = insets.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars()).top;
            if (top > 0 && top != barHeight) {
                barHeight = top;
                handler.post(this::position);
            }
            return insets;
        });

        params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, barHeight,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.LEFT;
        params.setFitInsetsTypes(0);
        params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        params.setTitle("TopLineDate");

        applyStyle();
        updateText();
        computePosition();
        windowManager.addView(view, params);
        attached = true;
        refreshVisibility();
        registerSystemEvents();
        recheckIn(200);
        Log.i(TAG, "connected barHeight=" + barHeight + " dpi=" + screenDpi);
    }

    private void registerSystemEvents() {
        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                // Time ticks keep the text current and pick up the bar's burn-in shift; screen
                // and unlock events may be missed while ColorOS freezes the app, so the state
                // itself is always read live in shouldShow().
                updateText();
                needClock = true;
                checkStatusBar();
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_TIME_TICK);
        filter.addAction(Intent.ACTION_TIME_CHANGED);
        filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        filter.addAction(Intent.ACTION_DATE_CHANGED);
        filter.addAction(Intent.ACTION_LOCALE_CHANGED);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        else registerReceiver(receiver, filter);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        lastConfigAt = SystemClock.uptimeMillis();
        if (newConfig.densityDpi != screenDpi) {
            // Display size changed: other clock geometry, other size/placement profile.
            screenDpi = newConfig.densityDpi;
            dpi = Prefs.activeDpi(this, prefs);
            loadClockGeometry();
            Log.i(TAG, "display density " + screenDpi + " profile " + dpi);
        }
        // The bar leaves the list a moment before this callback on a rotation: not fullscreen.
        if (fromFullscreen && lastConfigAt - barGoneSince < 500) fromFullscreen = false;

        barHeight = resourceBarHeight();
        needClock = true;
        freshRotation = true;
        int o = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE ? 1 : 0;
        // Jump to where the clock will be: its spot in this orientation, today's shift, and the
        // icons that follow it right now.
        anchorRight = clockBase[o] < 0 ? -1 : clockBase[o] + shift + iconsWidth;
        anchorCenterY = clockCenterY[o];
        anchorHasIcons = iconsWidth > 0;

        {   // home too: its bar turns with the screen like an app's
            int grace = prefs.getInt(Prefs.ROTATE_GRACE, Prefs.DEF_ROTATE_GRACE);
            // The clock may vanish just before this callback and hide the date a moment early.
            // (not when it hid because the app went fullscreen: then it should stay hidden)
            boolean wasUp = shown || (lastConfigAt - hiddenAt < 400 && statusBarVisible && !fromFullscreen);
            Log.i(TAG, "rotate land=" + (o == 1) + " up=" + wasUp + " anchor=" + anchorRight);
            if (grace > 0 && wasUp && anchorRight >= 0) {
                // Like a plain overlay: move at once and stay up while SystemUI re-lays the bar.
                rotationGraceUntil = lastConfigAt + grace;
                handler.post(this::refreshVisibility);
                recheckIn(grace + 5);
            } else {
                hold(prefs.getInt(Prefs.ROTATE_DELAY, Prefs.DEF_ROTATE_DELAY));
                if (shown) {
                    shown = false;
                    view.animate().cancel();
                    view.setAlpha(0f);
                    view.setVisibility(View.INVISIBLE);
                }
            }
        }
        handler.post(() -> {
            applyStyle();
            position();
            checkStatusBar();
            scheduleSample(600);
        });
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if (view == null) return;
        dpi = Prefs.activeDpi(this, prefs);
        if (rotator.locked() && (!rotator.enabled() || !Prefs.labApps(prefs).contains(rotator.app()))) {
            rotator.release("설정 변경");
        }
        applyStyle();
        updateText();
        position();
        refreshVisibility();
    }

    @Override
    public void onInterrupt() {}

    @Override
    public boolean onUnbind(Intent intent) {
        teardown();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        teardown();
        super.onDestroy();
    }

    private void teardown() {
        handler.removeCallbacksAndMessages(null);
        if (rotator != null) rotator.release("서비스 종료");
        if (receiver != null) {
            try { unregisterReceiver(receiver); } catch (IllegalArgumentException ignored) {}
            receiver = null;
        }
        if (prefs != null) prefs.unregisterOnSharedPreferenceChangeListener(this);
        if (attached) {
            try { windowManager.removeView(view); } catch (IllegalArgumentException ignored) {}
            attached = false;
        }
        lastStatus = "disconnected";
    }

    // ============================================================================================
    // Events
    // ============================================================================================

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        int type = event.getEventType();
        CharSequence pkg = event.getPackageName();
        boolean fromSystemUi = pkg != null && SYSTEM_UI.contentEquals(pkg);

        // While the clock is hidden, any activity may mean it is back: SystemUI reports that late,
        // so look right away and keep looking briefly after a screen change.
        if (!clockVisible && statusBarVisible) {
            quickClockCheck();
            if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) startClockPoll();
        }

        if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            if (!fromSystemUi) return;   // only the bar's own changes matter
            quickClockCheck();           // one call: is the system clock still shown?
            needClock = true;            // icons next to the clock may have changed
        }

        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && pkg != null && !fromSystemUi) {
            CharSequence cls = event.getClassName();
            // Toasts pop up over whatever app is in front, and our own date window announces
            // itself when shown; neither is an app coming to the front.
            boolean ownOverlay = pkg.toString().equals(getPackageName())
                    && (cls == null || !cls.toString().contains("SettingsActivity"));
            if (!ownOverlay && (cls == null || !cls.toString().contains("Toast"))
                    && !isKeyboard(pkg.toString())) {
                onAppShown(pkg.toString());   // the keyboard is not an app coming to the front
            }
        }

        // A touch on the gesture area stirs the handle window without a source first, then relays it
        // out like fullscreen does; fullscreen has no such stir.
        if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && fromSystemUi && event.getSource() == null
                && event.getWindowId() != barWindowId) {
            handleStirAt = SystemClock.uptimeMillis();
        }
        // Entering fullscreen: the app's window handle relays out ~30 ms before the clock slides up,
        // while the bar can stay listed until 0.3 s after the clock is gone.
        if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && fromSystemUi && statusBarVisible
                && shown && !predictHidden && !barExpanded && !menuOverlay && screenOn && unlocked
                && SystemClock.uptimeMillis() - lastConfigAt > 1500
                && SystemClock.uptimeMillis() >= rotationGraceUntil
                && SystemClock.uptimeMillis() - barListedAt > 300   // the handle settles after the bar returns
                && SystemClock.uptimeMillis() - handleStirAt > 200
                && isHandleRelayout(event)
                && !LAUNCHER.equals(focusedAppPackage())) {   // an app closing relays it out too
            Log.i(TAG, "predict-hide");
            predictHidden = true;
            handler.removeCallbacks(clearPredictHide);
            handler.postDelayed(clearPredictHide, 600);   // undone unless the bar leaves
        }

        // Leaving fullscreen: SystemUI stirs ~0.2-0.3 s before the bar is listed again: the narrow
        // window handle in landscape, the handle window's whole relayout in portrait. A rotation
        // stirs it too while staying fullscreen, so not right after one.
        long now = SystemClock.uptimeMillis();
        if (fromSystemUi && !statusBarVisible && !barExpanded && screenOn && unlocked
                && prefs.getBoolean(Prefs.PREDICT, Prefs.DEF_PREDICT)
                && now - barGoneSince > 300 && now - lastConfigAt > 1500 && !rotator.settling()
                && (withinBarStrip(event) || isHandleRelayout(event))) {
            if (!predictShown) {
                AccessibilityNodeInfo src = event.getSource();
                Rect r = new Rect();
                if (src != null) src.getBoundsInScreen(r);
                Log.i(TAG, "predict-show " + AccessibilityEvent.eventTypeToString(type)
                        + " win=" + event.getWindowId() + " src=" + (src == null ? "null" : src.getClassName()
                        + " " + r.toShortString()) + " windows=" + windowSummary());
            }
            predictShown = true;
            handler.removeCallbacks(clearPredictShow);
            handler.postDelayed(clearPredictShow, 700);   // undone unless the bar comes back
        }
        checkStatusBar();
    }

    /**
     * The bar coming back first stirs the window handle: a narrow box at the top exactly as tall
     * as the bar (about 267 x 105 here). A heads-up notification stirs a wide, short card as it
     * drops in ([730,0][1790,40]), which must not count.
     */
    private boolean withinBarStrip(AccessibilityEvent event) {
        AccessibilityNodeInfo src = event.getSource();
        if (src == null) return false;
        Rect r = new Rect();
        src.getBoundsInScreen(r);
        int screenWidth = windowManager.getCurrentWindowMetrics().getBounds().width();
        return r.top <= 2 && Math.abs(r.height() - barHeight) <= 6 && r.width() > 0
                && r.width() < screenWidth * 0.2f;
    }

    /** Keyboards announce their window like an app does (and not always from a listed window). */
    private boolean isKeyboard(String pkg) {
        android.view.inputmethod.InputMethodManager imm = getSystemService(android.view.inputmethod.InputMethodManager.class);
        if (imm == null) return false;
        for (android.view.inputmethod.InputMethodInfo info : imm.getEnabledInputMethodList()) {
            if (info.getPackageName().equals(pkg)) return true;
        }
        return false;
    }

    /** The whole-screen root of an app's window handle (a window-control window) laid out again. */
    private boolean isHandleRelayout(AccessibilityEvent event) {
        AccessibilityNodeInfo src = event.getSource();
        if (src == null || src.getParent() != null) return false;
        Rect r = new Rect();
        src.getBoundsInScreen(r);
        Rect screen = windowManager.getCurrentWindowMetrics().getBounds();
        if (r.top > 0 || r.height() < screen.height() * 0.9f || r.width() < screen.width() * 0.9f) return false;
        // The keyboard coming or going relays it out the same way; its window is listed by then.
        boolean handle = false;
        for (AccessibilityWindowInfo w : getWindows()) {
            if (w.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) return false;
            if (w.getId() == event.getWindowId()) handle = w.getType() == 7;   // TYPE_WINDOW_CONTROL
        }
        return handle;
    }

    private String windowSummary() {
        StringBuilder sb = new StringBuilder();
        Rect b = new Rect();
        for (AccessibilityWindowInfo w : getWindows()) {
            w.getBoundsInScreen(b);
            sb.append('[').append(w.getType()).append(" id").append(w.getId()).append(' ')
              .append(b.toShortString()).append(w.isFocused() ? " F" : "").append(']');
        }
        return sb.toString();
    }

    private void onAppShown(String app) {
        foreground = app;
        appShownAt = SystemClock.uptimeMillis();
        Integer remembered = colorByApp.get(app);
        if (remembered != null && autoColorMode() && remembered != autoColor) {
            autoColor = remembered;   // right colour from the first frame
            view.setTextColor(autoColor);
        }
        scheduleSample(250);
    }

    // ============================================================================================
    // Status bar state
    // ============================================================================================

    private void checkStatusBar() {
        if (!attached) return;
        Rect screen = windowManager.getCurrentWindowMetrics().getBounds();
        int screenWidth = screen.width();
        List<AccessibilityWindowInfo> windows = getWindows();

        AccessibilityWindowInfo bar = null;
        AccessibilityWindowInfo moving = null;   // the bar part-way off the top edge
        int movingTop = Integer.MIN_VALUE;
        boolean expanded = false;
        boolean floating = false;
        Rect b = new Rect();
        for (AccessibilityWindowInfo w : windows) {
            w.getBoundsInScreen(b);
            boolean topWide = b.top == 0 && b.width() >= screenWidth * 0.8f;
            if (w.getType() == AccessibilityWindowInfo.TYPE_SYSTEM) {
                if (topWide && b.height() > barHeight * 2) expanded = true;
                else if (topWide && b.height() > 0 && bar == null) bar = w;
                else if (b.top < 0 && b.bottom > 0 && b.width() >= screenWidth * 0.8f
                        && Math.abs(b.height() - barHeight) <= 6 && moving == null) {
                    moving = w;
                    movingTop = b.top;
                }
            } else if (w.getType() == AccessibilityWindowInfo.TYPE_APPLICATION
                    && (b.width() < screenWidth * 0.95f || b.height() < screen.height() * 0.95f)) {
                floating = true;
            }
        }
        // In split screen the bar stays listed and slides; the list catches up late, so while it is
        // hidden, take it as coming back as soon as it is seen lower than before.
        // Moving down (or holding still while shown) counts as listed; moving up as gone.
        boolean slidingIn = bar == null && moving != null
                && (movingTop > lastBarTop || (movingTop == lastBarTop && statusBarVisible));
        if (slidingIn) {
            bar = moving;
            if (!statusBarVisible) dropFrom = movingTop;   // the date starts where the bar is
        } else if (bar == null && moving != null && statusBarVisible) {
            dropFrom = movingTop;   // already on its way up: the date leaves from there too
        }
        lastBarTop = bar != null && !slidingIn ? 0 : movingTop;
        boolean found = bar != null;
        // The bar can drop out of the list while it stays on screen: mid-transition the list
        // empties (an app closing into a rotation), and the home screen loses it until it has
        // turned. Fullscreen keeps its app window listed, and the home screen never goes
        // fullscreen, so wait a little for those before believing the bar is gone.
        if (!found && statusBarVisible && !expanded && screenOn && !keyguard.isKeyguardLocked()) {
            int apps = 0;
            boolean focusedApp = false, smallApp = false;
            for (AccessibilityWindowInfo w : windows) {
                if (w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                apps++;
                w.getBoundsInScreen(b);
                if (w.isFocused() || w.isActive()) focusedApp = true;
                if (b.width() < screenWidth * 0.95f || b.height() < screen.height() * 0.95f) smallApp = true;
            }
            boolean menuLike = windows.size() == 1 && windows.get(0).getType() == AccessibilityWindowInfo.TYPE_SYSTEM;
            long now = SystemClock.uptimeMillis();
            // In between, no app has focus or the only app window is still animating; split screen
            // and floating windows list two or more.
            boolean between = !menuLike && (!focusedApp || (apps == 1 && smallApp));
            if (between || LAUNCHER.equals(focusedAppPackage())) {
                if (barPendingSince == 0) barPendingSince = now;
                if (now - barPendingSince < BAR_PENDING_MAX) {
                    recheckIn(100);
                    return;
                }
            }
        }
        barPendingSince = 0;
        if (found) {
            barWindowId = bar.getId();
            if (!statusBarVisible) needClock = true;
            if (needClock && !slidingIn) findAnchor(bar, screenWidth);   // its clock is off-place yet
        }
        // The shade is one long-lived window, seen collapsed to zero or bar size; remember it.
        for (AccessibilityWindowInfo w : windows) {
            if (w.getType() != AccessibilityWindowInfo.TYPE_SYSTEM || w.getId() == barWindowId) continue;
            w.getBoundsInScreen(b);
            if (b.isEmpty() || (b.top == 0 && b.height() <= barHeight * 2)) shadeIds.add(w.getId());
        }

        // A floating window's menu: the list shrinks to one focused full-screen system window
        // (a new one each time) while the real bar and clock stay on screen behind it.
        boolean overlay = false;
        if (!found && windows.size() == 1) {
            AccessibilityWindowInfo w = windows.get(0);
            w.getBoundsInScreen(b);
            overlay = w.getType() == AccessibilityWindowInfo.TYPE_SYSTEM && w.isFocused()
                    && b.top == 0 && b.width() >= screenWidth * 0.8f && b.height() > barHeight * 4
                    && !keyguard.isKeyguardLocked() && !shadeIds.contains(w.getId())
                    && (menuOverlay || floatingSeen);
            // Pulling the shade over the menu closes the menu, and the shade, listed just like it,
            // follows within ~30 ms: nobody reopens the menu that fast. Remember it as the shade.
            if (overlay && !menuOverlay && SystemClock.uptimeMillis() - menuEndedAt < 500) {
                shadeIds.add(w.getId());
                overlay = false;
            }
        }
        if (found) floatingSeen = floating;   // focus leaves the floating window before its menu
        if (overlay != menuOverlay) {
            if (!overlay) {
                menuEndedAt = SystemClock.uptimeMillis();
                recheckIn(MENU_GAP_MS + 5);
            }
            menuOverlay = overlay;
            Log.i(TAG, "menu overlay=" + overlay);
        }
        barExpanded = expanded && !found && !menuOverlay;
        if (barExpanded || menuOverlay) hiddenByClock = false;   // the shade, not recents

        // Back from the shade or the menu: the clock flickers in the a11y tree while it settles.
        // (Not after the lock screen: there the clock is really hidden for a while, and the date
        // waits for it - see onBarListedChanged.)
        if (found && (wasOverlay || (!statusBarVisible && wasExpanded && !wasLocked))) {
            clockVisible = true;
            settleUntil = SystemClock.uptimeMillis() + 500;
            needClock = true;
            recheckIn(520);
        }
        wasExpanded = barExpanded || (wasExpanded && !found);
        wasOverlay = menuOverlay;

        if (found) {
            predictShown = false;
            handler.removeCallbacks(clearPredictShow);
        } else if (predictHidden) {
            predictHidden = false;   // the bar did leave: from here on its absence keeps the date hidden
            handler.removeCallbacks(clearPredictHide);
        }
        if (found != statusBarVisible) onBarListedChanged(found, expanded);

        if (!unlocked) wasLocked = true;
        else if (statusBarVisible) wasLocked = false;
        checkRotator(found);
        updateText();
        refreshVisibility();
        dropFrom = 0;   // only for a show on this very check
    }

    /** Labs: end the landscape lock when fullscreen ends, the app changes or the screen locks. */
    private void checkRotator(boolean barListed) {
        if (rotator == null) return;
        rotator.settleStale(barListed);
        if (!rotator.locked()) return;
        if (!power.isInteractive() || keyguard.isKeyguardLocked()) {
            rotator.release("화면 꺼짐");
        } else if (!rotator.settling()) {
            String app = focusedAppPackage();
            if (barListed || barExpanded) rotator.release("전체 화면 해제");
            else if (!app.isEmpty() && !app.equals(rotator.app())) rotator.release("다른 앱으로 이동");
        }
    }

    private void onBarListedChanged(boolean found, boolean expanded) {
        long now = SystemClock.uptimeMillis();
        // Back from the lock screen: SystemUI keeps the clock hidden a while (about 0.2-0.3 s on
        // the home screen, 0.45 s over an app). Appear a moment after the clock does.
        if (found && wasLocked) {
            unlockFade = true;
            if (clockVisible) {
                hold(prefs.getInt(Prefs.AFTER_CLOCK_MS, Prefs.DEF_AFTER_CLOCK_MS));
            } else {
                unlockWaiting = true;
                holdUntil = now + UNLOCK_CLOCK_TIMEOUT;   // in case the clock is never reported
                recheckIn(UNLOCK_CLOCK_TIMEOUT + 5);
            }
        }
        statusBarVisible = found;
        if (found) {
            barListedAt = now;
            rotationGraceUntil = 0;   // a rotation is over once the bar has left and come back
        } else {
            barGoneSince = now;
            if (!expanded && !menuOverlay && unlocked && now - lastConfigAt > 1500) {
                // The bar really left (recents keeps it listed): an app went fullscreen.
                fromFullscreen = true;
                fullscreenApp = focusedAppPackage();
                Log.i(TAG, "fullscreen in " + fullscreenApp);
                rotator.onFullscreen(fullscreenApp);
                if (rotator.locked()) recheckIn(FullscreenRotator.SETTLE_MS + 10);
            }
        }
        Log.i(TAG, "status bar listed=" + found + " fg=" + foreground);
    }

    // ============================================================================================
    // Clock and anchor
    // ============================================================================================

    /**
     * Finds the clock (the TextView whose text looks like a time) and walks right through the
     * icons touching it, so the date sits after a notification badge when one is shown.
     */
    private void findAnchor(AccessibilityWindowInfo window, int screenWidth) {
        AccessibilityNodeInfo root = window.getRoot();
        if (root == null) return;
        ArrayList<Rect> boxes = new ArrayList<>();
        Rect clock = null;
        boolean visible = false;
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        int visited = 0;
        while (!queue.isEmpty() && visited++ < 300) {
            AccessibilityNodeInfo node = queue.poll();
            Rect r = new Rect();
            node.getBoundsInScreen(r);
            CharSequence text = node.getText();
            if (clock == null && text != null && !r.isEmpty()
                    && "android.widget.TextView".contentEquals(node.getClassName())
                    && TIME.matcher(text).matches()) {
                clock = r;
                visible = node.isVisibleToUser();
                clockNode = node;
            } else if (r.width() > 0 && r.width() < screenWidth / 2) {
                boxes.add(r);
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.add(child);
            }
        }
        needClock = false;
        setClockVisible(clock != null && visible, false);
        if (clock == null) return;

        int right = clock.right;
        for (boolean grew = true; grew; ) {
            grew = false;
            for (Rect r : boxes) {
                if (r.left >= clock.left && r.left <= right + 12 && r.right > right) {
                    right = r.right;
                    grew = true;
                }
            }
        }
        if (right != anchorRight || clock.centerY() != anchorCenterY) {
            // Slide only when icons appear or vanish, not right after a rotation.
            boolean slide = anchorRight >= 0 && clock.centerY() == anchorCenterY
                    && SystemClock.uptimeMillis() - lastConfigAt > 1500;
            anchorRight = right;
            anchorHasIcons = right > clock.right;
            anchorCenterY = clock.centerY();
            position(slide);
        }
        clockRight = clock.right;
        iconsWidth = right - clock.right;   // the same in both orientations at one display size
        learnClockGeometry();
    }

    /** Refreshes only the remembered clock node: one IPC to follow the system clock. */
    private void quickClockCheck() {
        if (clockNode == null || !statusBarVisible) return;
        boolean alive = clockNode.refresh();
        if (!alive) clockNode = null;
        setClockVisible(alive && clockNode.isVisibleToUser(), true);
    }

    private void setClockVisible(boolean visible, boolean now) {
        if (visible == clockVisible) return;
        clockVisible = visible;
        Log.i(TAG, "clock visible=" + visible);
        if (visible && unlockWaiting) {
            // Replace the safety timeout with "a moment after the clock" (0 means at once).
            unlockWaiting = false;
            unlockFade = true;
            int ms = Math.max(0, prefs.getInt(Prefs.AFTER_CLOCK_MS, Prefs.DEF_AFTER_CLOCK_MS));
            holdUntil = SystemClock.uptimeMillis() + ms;
            recheckIn(ms + 5);
        }
        if (!visible) {
            clockHiddenAt = SystemClock.uptimeMillis();
            menuWaitArmed = floatingSeen;   // as it was then: recents turns apps into small cards
            if (menuWaitArmed) recheckIn(MENU_WAIT_MS + 5);
        }
        if (now) refreshVisibility();
        else handler.post(this::refreshVisibility);
    }

    private void startClockPoll() {
        pollUntil = SystemClock.uptimeMillis() + 1500;
        handler.removeCallbacks(clockPoll);
        handler.postDelayed(clockPoll, 50);
    }

    private void loadClockGeometry() {
        SharedPreferences saved = getSharedPreferences("anchor", MODE_PRIVATE);
        shift = saved.getInt("shift@" + screenDpi, 0);
        for (int o = 0; o < 2; o++) {
            clockBase[o] = saved.getInt("base" + o + "@" + screenDpi, Prefs.defClockBase(o, screenDpi));
            clockCenterY[o] = saved.getInt("cy" + o + "@" + screenDpi, Prefs.defClockCenterY(o, screenDpi));
        }
    }

    /**
     * Keeps base[o] + shift equal to where the clock is. Right after a rotation the shift carried
     * over is trusted and this orientation's base is corrected; otherwise a moving clock means the
     * bar's burn-in shift changed.
     */
    private void learnClockGeometry() {
        if (clockRight < 0) return;
        int o = landscape() ? 1 : 0;
        // Mid-rotation the clock can still have the other orientation's geometry.
        if (clockCenterY[o] != 0 && Math.abs(anchorCenterY - clockCenterY[o]) > 4) return;
        SharedPreferences.Editor edit = getSharedPreferences("anchor", MODE_PRIVATE).edit();
        if (freshRotation || clockBase[o] < 0) {
            freshRotation = false;
            int base = clockRight - shift;
            if (base == clockBase[o] && clockCenterY[o] == anchorCenterY) return;
            clockBase[o] = base;
            clockCenterY[o] = anchorCenterY;
            edit.putInt("base" + o + "@" + screenDpi, base).putInt("cy" + o + "@" + screenDpi, anchorCenterY);
            Log.i(TAG, "clock base land=" + (o == 1) + " " + base + " shift " + shift);
        } else {
            int now = clockRight - clockBase[o];
            if (now == shift) return;
            shift = now;
            edit.putInt("shift@" + screenDpi, shift);
        }
        edit.apply();
    }

    // ============================================================================================
    // Placement
    // ============================================================================================

    /** Anchored: after the clock (and its icons), centred on it. Manual: fixed x, bar centre. */
    private void computePosition() {
        boolean land = landscape();
        int offsetY = Prefs.getInt(prefs, land ? Prefs.LANDSCAPE_Y : Prefs.PORTRAIT_Y, dpi);
        int height = Math.max(1, barHeight);
        if (prefs.getBoolean(Prefs.ANCHOR, Prefs.DEF_ANCHOR) && anchorRight >= 0) {
            params.x = anchorRight + Prefs.getInt(prefs, anchorHasIcons ? Prefs.GAP_ICONS : Prefs.GAP, dpi);
            params.y = anchorCenterY - height / 2 + offsetY;
        } else {
            params.x = Prefs.getInt(prefs, land ? Prefs.LANDSCAPE_X : Prefs.PORTRAIT_X, dpi);
            params.y = offsetY;
        }
        params.height = height;
    }

    private void position() {
        position(false);
    }

    private void position(boolean slide) {
        if (!attached) return;
        int oldX = params.x, oldY = params.y, oldH = params.height;
        if (slider != null) {
            slider.cancel();
            slider = null;
        }
        computePosition();
        int targetX = params.x;
        int ms = targetX < oldX ? prefs.getInt(Prefs.SLIDE_BACK_MS, Prefs.DEF_SLIDE_BACK_MS)
                                : prefs.getInt(Prefs.SLIDE_MS, Prefs.DEF_SLIDE_MS);
        if (slide && shown && ms > 0 && targetX != oldX && params.y == oldY && params.height == oldH) {
            params.x = oldX;
            slider = ValueAnimator.ofInt(oldX, targetX);
            slider.setDuration(ms);
            slider.setInterpolator(EASE);
            slider.addUpdateListener(a -> {
                params.x = (Integer) a.getAnimatedValue();
                if (attached) windowManager.updateViewLayout(view, params);
            });
            slider.start();
        } else if (params.x != oldX || params.y != oldY || params.height != oldH) {
            windowManager.updateViewLayout(view, params);
        }
        report();
    }

    private float fineY() {
        return Prefs.getFloat(prefs, landscape() ? Prefs.FINE_Y_LANDSCAPE : Prefs.FINE_Y_PORTRAIT, dpi);
    }

    // ============================================================================================
    // Visibility
    // ============================================================================================

    private boolean shouldShow() {
        screenOn = power.isInteractive();
        unlocked = screenOn && !keyguard.isKeyguardLocked();
        long now = SystemClock.uptimeMillis();
        boolean rotating = now < rotationGraceUntil;
        // The clock state only means something while the bar is listed (in fullscreen it is
        // stale and must not block the early show); the menu overlay and settling ignore it.
        // A floating window's menu hides the clock ~20 ms before the menu is listed: wait for it.
        boolean clockHidden = statusBarVisible && !clockVisible && !menuOverlay
                && !rotating && now >= settleUntil && !(menuWaitArmed && now - clockHiddenAt < MENU_WAIT_MS)
                && barPendingSince == 0;   // mid-transition the clock node goes stale with the bar
        // Closing the menu, the bar is listed again ~40 ms after the menu leaves: bridge the gap.
        boolean menuGap = now - menuEndedAt < MENU_GAP_MS && !barExpanded;
        boolean barUp = statusBarVisible || predictShown || menuOverlay || menuGap
                || (rotating && !barExpanded)
                || (!prefs.getBoolean(Prefs.HIDE_FULLSCREEN, Prefs.DEF_HIDE_FULLSCREEN) && !barExpanded);
        return prefs.getBoolean(Prefs.ENABLED, Prefs.DEF_ENABLED)
                && screenOn && unlocked && now >= holdUntil && !predictHidden
                && !(clockHidden && prefs.getBoolean(Prefs.HIDE_RECENTS, Prefs.DEF_HIDE_RECENTS))
                && barUp;
    }

    private void refreshVisibility() {
        if (!attached) return;
        boolean show = shouldShow();
        if (show != shown) {
            shown = show;
            Log.i(TAG, "date " + (show ? "SHOW" : "HIDE") + " bar=" + statusBarVisible
                    + " clock=" + clockVisible + " predict=" + predictShown + " fg=" + foreground);
            view.animate().cancel();
            if (show) {
                // Back from fullscreen in the same app: drop in with the bar. Going home, the
                // launcher has focus already while the app window is still drawn on top.
                // Only when early (predicted): found already in place, the bar has dropped in.
                boolean drop = fromFullscreen && predictShown && prefs.getInt(Prefs.BAR_SLIDE_MS, Prefs.DEF_BAR_SLIDE_MS) > 0
                        && focusedAppPackage().equals(fullscreenApp);
                showNow(drop);
            } else if (screenOn) {
                hiddenAt = SystemClock.uptimeMillis();
                hiddenByClock = statusBarVisible && !clockVisible && !predictHidden;
                int upMs = prefs.getInt(Prefs.BAR_HIDE_MS, Prefs.DEF_BAR_HIDE_MS);
                boolean up = upMs > 0 && unlocked && !barExpanded && !menuOverlay
                        && (predictHidden || (fromFullscreen && hiddenAt >= rotationGraceUntil
                                && (dropFrom < 0 || hiddenAt - appShownAt < 600)));
                // Gone without being seen to move (the list caught up late, or the app faded the
                // bar out in place like Ridi): the clock is already gone, so is the date. An app
                // opening straight into fullscreen slides it up while its window grows, though.
                if (up) {
                    // Into fullscreen the clock slides up into the top edge, speeding up: follow it.
                    // Seen late (split screen), start from where the bar already is.
                    float height = Math.max(1, params.height);
                    float done = dropFrom < 0 ? Math.min(1f, -dropFrom / height) : 0f;
                    if (done > 0) view.setTranslationY(fineY() + dropFrom);
                    view.animate().translationY(fineY() - height).setInterpolator(done > 0 ? new android.view.animation.LinearInterpolator() : ACCEL)
                            .setDuration((long) (upMs * (1f - done) + 0.5f))
                            .withEndAction(() -> { if (!shown) view.setVisibility(View.INVISIBLE); }).start();
                } else {
                    view.animate().alpha(0f).setInterpolator(null).setDuration(prefs.getInt(Prefs.FADE_OUT, Prefs.DEF_FADE_OUT))
                            .withEndAction(() -> { if (!shown) view.setVisibility(View.INVISIBLE); }).start();
                }
            } else {
                fromFullscreen = false;
                hiddenByClock = false;
                view.setAlpha(0f);
                view.setVisibility(View.INVISIBLE);
            }
        }
        report();
    }

    private void showNow(boolean drop) {
        float baseY = fineY();
        int partFrom = dropFrom;
        dropFrom = 0;
        int slideMs = prefs.getInt(Prefs.BAR_SLIDE_MS, Prefs.DEF_BAR_SLIDE_MS);
        int lateMs = prefs.getInt(Prefs.LATE_DROP_MS, Prefs.DEF_LATE_DROP_MS);
        if (!drop && partFrom == 0 && fromFullscreen && lateMs > 0 && statusBarVisible) {
            // The bar was only seen once already down (split screen): a short drop for the motion.
            float height = Math.max(1, params.height);
            view.setAlpha(1f);
            view.setTranslationY(baseY - height);
            view.setVisibility(View.VISIBLE);
            view.animate().translationY(baseY).setInterpolator(new android.view.animation.DecelerateInterpolator())
                    .setDuration(lateMs).start();
        } else if (!drop && partFrom < 0 && slideMs > 0) {
            // Seen late, part-way down (split screen): only the rest of the way, at the bar's pace.
            float height = Math.max(1, params.height);
            view.setAlpha(1f);
            view.setTranslationY(baseY + partFrom);
            view.setVisibility(View.VISIBLE);
            view.animate().translationY(baseY).setInterpolator(new android.view.animation.DecelerateInterpolator())
                    .setDuration((long) (slideMs * Math.min(1f, -partFrom / height))).start();
        } else if (drop) {
            view.setAlpha(1f);
            view.setTranslationY(baseY - Math.max(1, params.height));
            view.setVisibility(View.VISIBLE);
            // Landscape hears the bar ~0.3 s ahead, portrait only just ahead: there the date has to
            // start fast to keep up with a bar already on its way (both measured on recordings).
            view.animate().translationY(baseY).setInterpolator(landscape() ? EASE : DROP)
                    .setDuration(prefs.getInt(Prefs.BAR_SLIDE_MS, Prefs.DEF_BAR_SLIDE_MS)).start();
        } else if (unlockFade || (hiddenByClock && statusBarVisible && !predictShown)) {
            // After unlock (~0.33 s) and back from recents (~0.25 s) the system clock fades in at an
            // even pace: match it.
            view.setTranslationY(baseY);
            view.setAlpha(0f);
            view.setVisibility(View.VISIBLE);
            view.animate().alpha(1f).setInterpolator(new android.view.animation.LinearInterpolator())
                    .setDuration(unlockFade ? prefs.getInt(Prefs.UNLOCK_FADE_MS, Prefs.DEF_UNLOCK_FADE_MS)
                            : prefs.getInt(Prefs.CLOCK_BACK_FADE_MS, Prefs.DEF_CLOCK_BACK_FADE_MS)).start();
        } else {
            view.setTranslationY(baseY);
            if (view.getVisibility() != View.VISIBLE) {
                view.setAlpha(0f);
                view.setVisibility(View.VISIBLE);
            }
            view.animate().alpha(1f).setInterpolator(null)   // default curve (the animator keeps the last one)
                    .setDuration(prefs.getInt(Prefs.FADE_IN, Prefs.DEF_FADE_IN)).start();
        }
        unlockFade = false;
        hiddenByClock = false;
        fromFullscreen = false;
        scheduleSample(150);   // the background may have changed while hidden
    }

    private void hold(int ms) {
        if (ms <= 0) return;
        holdUntil = SystemClock.uptimeMillis() + ms;
        recheckIn(ms + 5);
    }

    /** Re-evaluates once a timed state ends; each caller gets its own, so none cancels another. */
    private void recheckIn(long ms) {
        handler.postDelayed(this::checkStatusBar, ms);
    }

    // ============================================================================================
    // Appearance
    // ============================================================================================

    private void applyStyle() {
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, Prefs.getFloat(prefs, Prefs.SIZE, dpi));
        view.getPaint().setFlags(view.getPaint().getFlags()
                | Paint.SUBPIXEL_TEXT_FLAG | Paint.LINEAR_TEXT_FLAG);
        String family = prefs.getString(Prefs.FONT, Prefs.DEF_FONT);
        Typeface base = family == null || family.isEmpty()
                ? Typeface.DEFAULT : Typeface.create(family, Typeface.NORMAL);
        int weight = Math.max(100, Math.min(900, prefs.getInt(Prefs.WEIGHT, Prefs.DEF_WEIGHT)));
        view.setTypeface(Typeface.create(base, weight, false));
        view.setTextColor(currentColor());
        if (shown) view.setTranslationY(fineY());
    }

    private void updateText() {
        String pattern = prefs.getString(Prefs.FORMAT, Prefs.DEF_FORMAT);
        String text;
        try {
            text = new SimpleDateFormat(pattern, Locale.getDefault()).format(new Date());
        } catch (IllegalArgumentException bad) {
            text = new SimpleDateFormat(Prefs.DEF_FORMAT, Locale.getDefault()).format(new Date());
        }
        if (!text.contentEquals(view.getText())) view.setText(text);
    }

    private boolean autoColorMode() {
        String color = prefs.getString(Prefs.COLOR, Prefs.DEF_COLOR);
        return color == null || color.equalsIgnoreCase("auto");
    }

    private int currentColor() {
        if (autoColorMode()) return autoColor;
        try {
            return Color.parseColor(prefs.getString(Prefs.COLOR, Prefs.DEF_COLOR).trim());
        } catch (IllegalArgumentException bad) {
            return autoColor;
        }
    }

    private void scheduleSample(long delayMs) {
        if (view == null || !autoColorMode()) return;
        handler.removeCallbacks(sampler);
        handler.postDelayed(sampler, delayMs);
    }

    /** Screenshot, then dark or light text depending on the bar strip's brightness. */
    private void sampleBar() {
        if (!attached || view.getVisibility() != View.VISIBLE || !autoColorMode()) return;
        long now = SystemClock.uptimeMillis();
        if (now - lastShot < 1100) {   // the screenshot API is rate limited
            handler.postDelayed(sampler, 1100 - (now - lastShot));
            return;
        }
        lastShot = now;
        takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(), new TakeScreenshotCallback() {
            @Override
            public void onSuccess(ScreenshotResult result) {
                HardwareBuffer buffer = result.getHardwareBuffer();
                Bitmap hardware = Bitmap.wrapHardwareBuffer(buffer, result.getColorSpace());
                Bitmap soft = hardware == null ? null : hardware.copy(Bitmap.Config.ARGB_8888, false);
                if (hardware != null) hardware.recycle();
                buffer.close();
                if (soft == null) return;
                float luminance = barLuminance(soft, Math.max(4, barHeight));
                soft.recycle();
                int color = luminance > 0.55f ? DARK_TEXT : LIGHT_TEXT;
                colorByApp.put(foreground, color);
                autoColor = color;
                if (autoColorMode()) view.setTextColor(autoColor);
            }

            @Override
            public void onFailure(int errorCode) {
                Log.w(TAG, "screenshot failed " + errorCode);
            }
        });
    }

    /** Median brightness of two rows near the top and bottom of the bar, away from the text. */
    private static float barLuminance(Bitmap bitmap, int height) {
        int width = bitmap.getWidth();
        int[] rows = {2, Math.min(bitmap.getHeight() - 1, height - 3)};
        int step = Math.max(1, width / 160);
        float[] values = new float[rows.length * (width / step + 1)];
        int n = 0;
        for (int y : rows) {
            for (int x = 0; x < width; x += step) {
                int c = bitmap.getPixel(x, y);
                values[n++] = (0.2126f * Color.red(c) + 0.7152f * Color.green(c)
                        + 0.0722f * Color.blue(c)) / 255f;
            }
        }
        Arrays.sort(values, 0, n);
        return n == 0 ? 0f : values[n / 2];
    }

    // ============================================================================================
    // Helpers
    // ============================================================================================

    /** Package of the application window with focus (falls back to the top-most one). */
    private String focusedAppPackage() {
        AccessibilityWindowInfo pick = null;
        for (AccessibilityWindowInfo w : getWindows()) {
            if (w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
            if (w.isFocused() || w.isActive()) { pick = w; break; }
            if (pick == null || w.getLayer() > pick.getLayer()) pick = w;
        }
        AccessibilityNodeInfo root = pick == null ? null : pick.getRoot();
        CharSequence pkg = root == null ? null : root.getPackageName();
        return pkg == null ? "" : pkg.toString();
    }

    private boolean isHome() {
        return foreground.isEmpty() || foreground.equals(LAUNCHER);
    }

    private boolean landscape() {
        return getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
    }

    private int resourceBarHeight() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : 60;
    }

    private void report() {
        lastStatus = (shown ? "표시 중" : "숨김") + " · " + (landscape() ? "가로" : "세로")
                + " · 배율 " + screenDpi + " · x " + params.x + " · " + view.getText();
    }
}
