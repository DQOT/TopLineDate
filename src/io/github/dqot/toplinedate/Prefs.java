package io.github.dqot.toplinedate;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Setting keys and defaults. Everything lives in one SharedPreferences file; size and placement
 * are stored per display size (density) as "key@dpi". Defaults are tuned on the OPPO Pad mini.
 */
final class Prefs {
    static final String FILE = "cfg";

    // ---- general ------------------------------------------------------------------------------
    static final String ENABLED = "enabled";               // boolean
    static final String FORMAT = "fmt";                    // SimpleDateFormat pattern
    static final String COLOR = "color";                   // "auto" or #RRGGBB / #AARRGGBB
    static final String FONT = "font";                     // system font family, "" = default
    static final String WEIGHT = "weight";                 // 100..900
    static final String SHOW_IN_RECENTS = "showInRecents"; // boolean, settings screen card

    static final boolean DEF_ENABLED = true;
    static final String DEF_FORMAT = "M월 d일 EEEE";
    static final String DEF_COLOR = "auto";
    static final String DEF_FONT = "";
    static final int DEF_WEIGHT = 700;                     // matches the clock's stroke width

    // ---- when to hide -------------------------------------------------------------------------
    static final String HIDE_FULLSCREEN = "hideFs";        // hide when the bar leaves (fullscreen)
    static final String HIDE_RECENTS = "hideRecents";      // hide when the system clock hides
    static final String PREDICT = "predict";               // show early when leaving fullscreen

    static final boolean DEF_HIDE_FULLSCREEN = true;
    static final boolean DEF_HIDE_RECENTS = true;
    static final boolean DEF_PREDICT = true;

    // ---- timing (ms) --------------------------------------------------------------------------
    static final String SLIDE_MS = "slideMs";              // move right when icons appear
    static final String SLIDE_BACK_MS = "slideBackMs";     // move back when they go
    static final String BAR_SLIDE_MS = "barSlideMs";       // drop in from above after fullscreen
    static final String BAR_HIDE_MS = "barHideMs";         // slide up into the top edge on fullscreen
    static final String FADE_IN = "fadeIn";
    static final String FADE_OUT = "fadeOut";
    static final String ROTATE_GRACE = "rotateGrace";      // stay up through a rotation over an app
    static final String ROTATE_DELAY = "rotateDelay";      // wait after a rotation when grace is 0
    static final String AFTER_CLOCK_MS = "afterClockMs";   // after unlock: wait this long once the clock shows
    static final String CLOCK_BACK_FADE_MS = "clockBackFadeMs"; // the clock shows again (back from recents)
    static final String UNLOCK_FADE_MS = "unlockFadeMs";   // after unlock: fade in this long (the clock takes ~0.33 s)

    static final int DEF_SLIDE_MS = 100;
    static final int DEF_SLIDE_BACK_MS = 100;
    static final int DEF_BAR_SLIDE_MS = 220;
    static final int DEF_BAR_HIDE_MS = 140;
    static final int DEF_FADE_IN = 60;
    static final int DEF_FADE_OUT = 120;
    static final int DEF_ROTATE_GRACE = 1500;
    static final int DEF_ROTATE_DELAY = 0;
    static final int DEF_AFTER_CLOCK_MS = 0;
    static final int DEF_UNLOCK_FADE_MS = 280;
    static final int DEF_CLOCK_BACK_FADE_MS = 250;

    // ---- size and placement, per display size --------------------------------------------------
    static final String ANCHOR = "anchor";                 // boolean: follow the status bar clock
    static final String SIZE = "size";                     // float px
    static final String GAP = "gap";                       // int px between clock and date
    static final String GAP_ICONS = "gapIcons";            // int px between a badge and date
    static final String PORTRAIT_Y = "py";                 // int px offset from the clock's centre
    static final String LANDSCAPE_Y = "ly";
    static final String FINE_Y_PORTRAIT = "fineYp";        // float px, sub-pixel nudge
    static final String FINE_Y_LANDSCAPE = "fineYl";
    static final String PORTRAIT_X = "px";                 // int px, only when ANCHOR is off
    static final String LANDSCAPE_X = "lx";

    static final boolean DEF_ANCHOR = true;
    static final String[] PROFILE_KEYS = {SIZE, GAP, GAP_ICONS, PORTRAIT_Y, LANDSCAPE_Y,
            FINE_Y_PORTRAIT, FINE_Y_LANDSCAPE, PORTRAIT_X, LANDSCAPE_X};

    /** Which density's values apply: "auto" follows the current display size. */
    static final String PROFILE = "profile";
    static final String DEF_PROFILE = "auto";
    static final int DPI_SMALL = 356;      // display size "작게"
    static final int DPI_STANDARD = 420;   // display size "표준" (device default)

    // ---- labs ---------------------------------------------------------------------------------
    static final String LAB_ROTATE = "labRotate";          // landscape while a listed app is fullscreen
    static final String LAB_APPS = "labApps";              // Set<String> of package names
    static final boolean DEF_LAB_ROTATE = false;
    static final java.util.Set<String> DEF_LAB_APPS = java.util.Collections.unmodifiableSet(
            new java.util.HashSet<>(java.util.Arrays.asList(
                    "com.google.android.youtube", "com.netflix.mediaclient")));

    static java.util.Set<String> labApps(SharedPreferences prefs) {
        return new java.util.HashSet<>(prefs.getStringSet(LAB_APPS, DEF_LAB_APPS));
    }

    /** Keys from earlier versions, removed on start. */
    static final String[] OBSOLETE_KEYS = {"predictHide", "dropDecideMs", "probe", "probe2",
            "probe4", "unlockDelay", "homeUnlockDelay", "headsup", SIZE, GAP, GAP_ICONS, PORTRAIT_Y, LANDSCAPE_Y, FINE_Y_PORTRAIT,
            FINE_Y_LANDSCAPE, PORTRAIT_X, LANDSCAPE_X};

    private Prefs() {}

    static SharedPreferences get(Context context) {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    static String key(String key, int dpi) {
        return key + "@" + dpi;
    }

    static boolean isProfileKey(String key) {
        for (String k : PROFILE_KEYS) if (k.equals(key)) return true;
        return false;
    }

    static int activeDpi(Context context, SharedPreferences prefs) {
        String chosen = prefs.getString(PROFILE, DEF_PROFILE);
        if (chosen != null && !chosen.equals(DEF_PROFILE)) {
            try { return Integer.parseInt(chosen); } catch (NumberFormatException ignored) {}
        }
        return context.getResources().getConfiguration().densityDpi;
    }

    /** Built-in profile values; an unknown density scales the standard text size. */
    static float defFloat(String key, int dpi) {
        switch (key) {
            case SIZE:
                if (dpi == DPI_SMALL) return 28.0f;
                if (dpi == DPI_STANDARD) return 32.0f;
                return Math.round(32.0f * dpi / DPI_STANDARD * 2) / 2f;
            case FINE_Y_PORTRAIT: return -1.5f;
            case FINE_Y_LANDSCAPE: return -1.1f;
            default: return 0f;
        }
    }

    static int defInt(String key, int dpi) {
        switch (key) {
            case GAP: return 20;
            case GAP_ICONS: return 14;
            case PORTRAIT_Y:
            case LANDSCAPE_Y: return -2;
            case PORTRAIT_X:
            case LANDSCAPE_X: return 230;
            default: return 0;
        }
    }

    static float getFloat(SharedPreferences prefs, String key, int dpi) {
        return prefs.getFloat(key(key, dpi), defFloat(key, dpi));
    }

    static int getInt(SharedPreferences prefs, String key, int dpi) {
        return prefs.getInt(key(key, dpi), defInt(key, dpi));
    }

    // ---- clock geometry seeds ------------------------------------------------------------------
    // Right edge of the clock (minus the burn-in shift) and its vertical centre, used until the app
    // has seen an orientation itself. Pairs were measured at the same shift. Index 0 = portrait.

    static int defClockBase(int orientation, int dpi) {
        if (dpi == DPI_SMALL) return orientation == 0 ? 166 : 210;
        if (dpi == DPI_STANDARD) return orientation == 0 ? 206 : 242;
        return -1;
    }

    static int defClockCenterY(int orientation, int dpi) {
        if (dpi == DPI_SMALL || dpi == DPI_STANDARD) return orientation == 0 ? 63 : 52;
        return 0;
    }
}
