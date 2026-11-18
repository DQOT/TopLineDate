package io.github.dqot.toplinedate;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.display.DisplayManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;
import android.view.OrientationEventListener;
import android.view.Surface;

/**
 * Labs: turns the screen to landscape while a chosen app is fullscreen (YouTube's tablet UI never
 * asks for it). Large screens ignore app orientation requests since Android 16, so this uses the
 * system rotation lock: the user's auto-rotate and rotation are saved first (durably, so a crash
 * cannot lose them), the lock is set to landscape and follows the tilt between the two landscape
 * sides, and the saved values are put back when fullscreen ends.
 */
final class FullscreenRotator {
    private static final String TAG = DateService.TAG;
    static final long SETTLE_MS = 1500;   // the bar comes and goes while the screen turns
    private static final long QUIET_MS = 1200;
    private static final long STALE_WAIT_MS = 2000;

    private final Context context;
    private final SharedPreferences prefs;
    private final SharedPreferences state;
    private final Display display;

    private boolean locked;
    private String app = "";
    private long lockedAt;
    private long releasedAt = -10_000;
    private int rotation;
    private long staleSince;   // a lock left by an earlier run, not yet put back or taken over
    private OrientationEventListener tilt;

    FullscreenRotator(Context context, SharedPreferences prefs) {
        this.context = context;
        this.prefs = prefs;
        this.state = context.getSharedPreferences("rotator", Context.MODE_PRIVATE);
        this.display = context.getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
    }

    boolean locked() {
        return locked;
    }

    String app() {
        return app;
    }

    /** Right after locking, the bar's comings and goings are the rotation, not the app. */
    boolean settling() {
        return locked && SystemClock.uptimeMillis() - lockedAt < SETTLE_MS;
    }

    boolean enabled() {
        return prefs.getBoolean(Prefs.LAB_ROTATE, Prefs.DEF_LAB_ROTATE);
    }

    static boolean canWrite(Context context) {
        return Settings.System.canWrite(context);
    }

    /** An app just went fullscreen: lock to landscape if it is on the list. */
    void onFullscreen(String pkg) {
        if (locked || !enabled() || pkg.isEmpty() || !Prefs.labApps(prefs).contains(pkg)) return;
        if (SystemClock.uptimeMillis() - releasedAt < QUIET_MS || !canWrite(context)) return;
        ContentResolver cr = context.getContentResolver();
        if (staleSince != 0) {
            // Restarted (an update) while that very app is still fullscreen: carry on with the
            // lock already in place instead of turning the screen back and forth.
            boolean same = pkg.equals(state.getString("app", ""));
            staleSince = 0;
            if (same) {
                int now = display.getRotation();
                rotation = now == Surface.ROTATION_90 || now == Surface.ROTATION_270 ? now : Surface.ROTATION_90;
                locked = true;
                app = pkg;
                lockedAt = SystemClock.uptimeMillis();
                startTilt();
                Log.i(TAG, "labs: kept the landscape lock for " + pkg);
                return;
            }
            restoreSaved();
        }
        state.edit()
                .putString("app", pkg)
                .putBoolean("locked", true)
                .putInt("accel", Settings.System.getInt(cr, Settings.System.ACCELEROMETER_ROTATION, 1))
                .putInt("userRot", Settings.System.getInt(cr, Settings.System.USER_ROTATION, Surface.ROTATION_0))
                .commit();
        int now = display.getRotation();
        rotation = now == Surface.ROTATION_90 || now == Surface.ROTATION_270 ? now : Surface.ROTATION_90;
        Settings.System.putInt(cr, Settings.System.ACCELEROMETER_ROTATION, 0);
        Settings.System.putInt(cr, Settings.System.USER_ROTATION, rotation);
        locked = true;
        app = pkg;
        lockedAt = SystemClock.uptimeMillis();
        startTilt();
        Log.i(TAG, "labs: landscape for " + pkg);
    }

    void release(String why) {
        if (!locked) return;
        locked = false;
        releasedAt = SystemClock.uptimeMillis();
        if (tilt != null) {
            tilt.disable();
            tilt = null;
        }
        restoreSaved();
        Log.i(TAG, "labs: rotation back (" + why + ")");
    }

    /**
     * After a crash, a kill or an update while locked, the user's settings are still saved. The
     * same app may still be fullscreen (onFullscreen takes the lock over); otherwise put them back
     * on the first check that says so, or after a moment.
     */
    void restoreIfStale() {
        if (state.getBoolean("locked", false)) staleSince = SystemClock.uptimeMillis();
    }

    /** Called on every status check: settles a lock left by an earlier run. */
    void settleStale(boolean barListed) {
        if (staleSince == 0) return;
        if (barListed || SystemClock.uptimeMillis() - staleSince > STALE_WAIT_MS) {
            staleSince = 0;
            Log.i(TAG, "labs: restoring rotation left by an earlier run");
            restoreSaved();
        }
    }

    private void restoreSaved() {
        if (!state.getBoolean("locked", false)) return;
        if (canWrite(context)) {
            ContentResolver cr = context.getContentResolver();
            Settings.System.putInt(cr, Settings.System.USER_ROTATION, state.getInt("userRot", Surface.ROTATION_0));
            Settings.System.putInt(cr, Settings.System.ACCELEROMETER_ROTATION, state.getInt("accel", 1));
        }
        state.edit().putBoolean("locked", false).commit();
    }

    /** Follow the tilt between the two landscape sides while locked. */
    private void startTilt() {
        if (tilt != null) return;
        tilt = new OrientationEventListener(context) {
            @Override
            public void onOrientationChanged(int degrees) {
                if (!locked || degrees == ORIENTATION_UNKNOWN) return;
                int side;
                if (degrees >= 60 && degrees <= 120) side = Surface.ROTATION_270;
                else if (degrees >= 240 && degrees <= 300) side = Surface.ROTATION_90;
                else return;
                if (side != rotation) {
                    rotation = side;
                    Settings.System.putInt(context.getContentResolver(), Settings.System.USER_ROTATION, side);
                }
            }
        };
        if (tilt.canDetectOrientation()) tilt.enable();
    }
}
