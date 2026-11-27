package io.github.dqot.toplinedate;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

/**
 * Settings, built in code and grouped into cards. Every change is saved at once, so the overlay
 * updates live while this screen is open.
 */
public class SettingsActivity extends Activity {
    private static final int TEST_ID = 1;
    private static final String TEST_CHANNEL = "test";

    private SharedPreferences prefs;
    private int dpi;
    private boolean night;
    private LinearLayout page;
    private LinearLayout card;   // the card rows are added to
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = Prefs.get(this);
        dpi = Prefs.activeDpi(this, prefs);
        night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;

        if (getActionBar() != null) getActionBar().hide();   // the page has its own title
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(night ? 0xFF000000 : 0xFFF2F2F7);
        scroll.addView(page);
        // Edge to edge: scroll below the status bar (content is clipped there) and stay clear of
        // the cutout, the navigation bar and the keyboard.
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars()
                    | android.view.WindowInsets.Type.displayCutout() | android.view.WindowInsets.Type.ime());
            scroll.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            page.setPadding(dp(16), dp(16), dp(16), dp(32));
            return insets;
        });
        setContentView(scroll);

        header();
        if (getIntent().getBooleanExtra(EXTRA_LABS, false)) {
            labsPage();
            return;
        }

        section(getString(R.string.section_keep_app), getString(R.string.section_keep_app_help));
        toggle(getString(R.string.show_in_recents), null, Prefs.SHOW_IN_RECENTS, true, this::applyRecents);

        section(getString(R.string.section_display), null);
        toggle(getString(R.string.show_date), null, Prefs.ENABLED, Prefs.DEF_ENABLED, null);
        text(getString(R.string.format), getString(R.string.format_help), Prefs.FORMAT, Prefs.defFormat(this));
        text(getString(R.string.text_color), getString(R.string.text_color_help), Prefs.COLOR, Prefs.DEF_COLOR);
        text(getString(R.string.font), getString(R.string.font_help), Prefs.FONT, Prefs.DEF_FONT);
        number(getString(R.string.weight), getString(R.string.weight_help), Prefs.WEIGHT, Prefs.DEF_WEIGHT, 100, false);

        section(getString(R.string.section_size_position), getString(R.string.section_size_position_help));
        profileChooser();
        number(getString(R.string.text_size), getString(R.string.unit_px), Prefs.SIZE, 0, 0.5f, true);
        number(getString(R.string.gap_clock), getString(R.string.gap_clock_help), Prefs.GAP, 0, 1, false);
        number(getString(R.string.gap_badge), getString(R.string.gap_badge_help), Prefs.GAP_ICONS, 0, 1, false);
        number(getString(R.string.portrait_y), getString(R.string.unit_px), Prefs.PORTRAIT_Y, 0, 1, false);
        number(getString(R.string.portrait_fine_y), getString(R.string.unit_px_decimal), Prefs.FINE_Y_PORTRAIT, 0, 0.25f, true);
        number(getString(R.string.landscape_y), getString(R.string.unit_px), Prefs.LANDSCAPE_Y, 0, 1, false);
        number(getString(R.string.landscape_fine_y), getString(R.string.unit_px_decimal), Prefs.FINE_Y_LANDSCAPE, 0, 0.25f, true);
        toggle(getString(R.string.anchor), getString(R.string.anchor_help), Prefs.ANCHOR, Prefs.DEF_ANCHOR, null);
        number(getString(R.string.portrait_x), getString(R.string.fixed_x_help), Prefs.PORTRAIT_X, 0, 1, false);
        number(getString(R.string.landscape_x), getString(R.string.fixed_x_help), Prefs.LANDSCAPE_X, 0, 1, false);

        section(getString(R.string.section_badge), getString(R.string.section_badge_help));
        testButtons();
        number(getString(R.string.slide_in), getString(R.string.slide_help), Prefs.SLIDE_MS, Prefs.DEF_SLIDE_MS, 10, false);
        number(getString(R.string.slide_out), getString(R.string.slide_help), Prefs.SLIDE_BACK_MS, Prefs.DEF_SLIDE_BACK_MS, 10, false);

        section(getString(R.string.section_hiding), null);
        toggle(getString(R.string.hide_fullscreen), null, Prefs.HIDE_FULLSCREEN, Prefs.DEF_HIDE_FULLSCREEN, null);
        toggle(getString(R.string.hide_with_clock), getString(R.string.hide_with_clock_help),
                Prefs.HIDE_RECENTS, Prefs.DEF_HIDE_RECENTS, null);
        toggle(getString(R.string.predict), getString(R.string.predict_help),
                Prefs.PREDICT, Prefs.DEF_PREDICT, null);

        section(getString(R.string.section_timing), getString(R.string.section_timing_help));
        number(getString(R.string.bar_slide), getString(R.string.zero_fades_help), Prefs.BAR_SLIDE_MS, Prefs.DEF_BAR_SLIDE_MS, 10, false);
        number(getString(R.string.bar_hide), getString(R.string.zero_fades_help), Prefs.BAR_HIDE_MS, Prefs.DEF_BAR_HIDE_MS, 10, false);
        number(getString(R.string.late_drop), getString(R.string.late_drop_help), Prefs.LATE_DROP_MS, Prefs.DEF_LATE_DROP_MS, 10, false);
        number(getString(R.string.fade_in), null, Prefs.FADE_IN, Prefs.DEF_FADE_IN, 10, false);
        number(getString(R.string.fade_out), null, Prefs.FADE_OUT, Prefs.DEF_FADE_OUT, 10, false);
        number(getString(R.string.rotate_grace), getString(R.string.rotate_grace_help), Prefs.ROTATE_GRACE, Prefs.DEF_ROTATE_GRACE, 100, false);
        number(getString(R.string.rotate_delay), getString(R.string.rotate_delay_help), Prefs.ROTATE_DELAY, Prefs.DEF_ROTATE_DELAY, 10, false);
        number(getString(R.string.clock_back_fade), getString(R.string.clock_like_fade_help),
                Prefs.CLOCK_BACK_FADE_MS, Prefs.DEF_CLOCK_BACK_FADE_MS, 10, false);
        number(getString(R.string.after_clock), getString(R.string.after_clock_help),
                Prefs.AFTER_CLOCK_MS, Prefs.DEF_AFTER_CLOCK_MS, 10, false);
        number(getString(R.string.unlock_fade), getString(R.string.clock_like_fade_help),
                Prefs.UNLOCK_FADE_MS, Prefs.DEF_UNLOCK_FADE_MS, 10, false);

        section(getString(R.string.section_updates), null);
        toggle(getString(R.string.update_check), getString(R.string.update_check_help),
                Prefs.UPDATE_CHECK, Prefs.DEF_UPDATE_CHECK, this::applyUpdateCheck);
        trimDivider();
        UpdateCheck.schedule(this);
        if (prefs.getBoolean(Prefs.UPDATE_CHECK, Prefs.DEF_UPDATE_CHECK)
                && !getSharedPreferences("update", MODE_PRIVATE).getBoolean("askedNotify", false)) {
            getSharedPreferences("update", MODE_PRIVATE).edit().putBoolean("askedNotify", true).apply();
            askNotifications();
        }

        section(getString(R.string.section_labs), null);
        LinearLayout entry = row();
        entry.addView(labels(getString(R.string.section_labs), getString(R.string.labs_entry_help)),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        labEntryState = small("");
        labEntryState.setTextSize(15);
        entry.addView(labEntryState);
        entry.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)
                .putExtra(EXTRA_LABS, true)));
        card.addView(entry);
        trimDivider();
    }

    private void applyUpdateCheck() {
        UpdateCheck.schedule(this);
        if (prefs.getBoolean(Prefs.UPDATE_CHECK, Prefs.DEF_UPDATE_CHECK)) askNotifications();
    }

    /** The update notice needs the notification permission (Android 13+). */
    private void askNotifications() {
        if (android.os.Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 2);
        }
    }

    private static final String EXTRA_LABS = "labs";
    private TextView labEntryState;

    /** The labs page: experimental features, apart from the date settings. */
    private void labsPage() {
        section(getString(R.string.section_labs), getString(R.string.labs_page_help));
        toggle(getString(R.string.lab_rotate), getString(R.string.lab_rotate_help),
                Prefs.LAB_ROTATE, Prefs.DEF_LAB_ROTATE, null);
        labPermissionRow();
        labApps();
        trimDivider();
    }

    private TextView labPermission;

    /** The rotation goes through the system rotation lock, which needs "modify system settings". */
    private void labPermissionRow() {
        LinearLayout row = row();
        LinearLayout text = labels(getString(R.string.lab_permission), getString(R.string.lab_permission_help));
        labPermission = small("");
        text.addView(labPermission);
        row.addView(text, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Button open = new Button(this);
        open.setText(R.string.lab_permission_allow);
        open.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                android.net.Uri.parse("package:" + getPackageName()))));
        row.addView(open);
        card.addView(row);
        divider();
    }

    /** Launchable apps with a checkbox each; checked ones first, then by name. */
    private void labApps() {
        android.content.pm.PackageManager pm = getPackageManager();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        java.util.Set<String> chosen = Prefs.labApps(prefs);
        java.util.Set<String> seen = new java.util.HashSet<>();
        java.util.List<android.content.pm.ResolveInfo> apps = new java.util.ArrayList<>();
        for (android.content.pm.ResolveInfo info : pm.queryIntentActivities(launcher, 0)) {
            String pkg = info.activityInfo.packageName;
            if (!pkg.equals(getPackageName()) && seen.add(pkg)) apps.add(info);
        }
        java.text.Collator collator = java.text.Collator.getInstance();
        apps.sort((a, b) -> {
            boolean ca = chosen.contains(a.activityInfo.packageName);
            boolean cb = chosen.contains(b.activityInfo.packageName);
            if (ca != cb) return ca ? -1 : 1;
            return collator.compare(a.loadLabel(pm).toString(), b.loadLabel(pm).toString());
        });
        for (android.content.pm.ResolveInfo info : apps) {
            String pkg = info.activityInfo.packageName;
            LinearLayout row = row();
            android.widget.ImageView icon = new android.widget.ImageView(this);
            icon.setImageDrawable(info.loadIcon(pm));
            LinearLayout.LayoutParams iconSize = new LinearLayout.LayoutParams(dp(32), dp(32));
            iconSize.setMarginEnd(dp(12));
            row.addView(icon, iconSize);
            row.addView(labels(info.loadLabel(pm).toString(), null),
                    new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            android.widget.CheckBox box = new android.widget.CheckBox(this);
            box.setChecked(chosen.contains(pkg));
            box.setOnCheckedChangeListener((b, on) -> {
                java.util.Set<String> set = Prefs.labApps(prefs);
                if (on) set.add(pkg); else set.remove(pkg);
                prefs.edit().putStringSet(Prefs.LAB_APPS, set).apply();
            });
            row.addView(box);
            row.setOnClickListener(v -> box.toggle());
            card.addView(row);
            divider();
        }
    }

    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable statusTicker = new Runnable() {
        @Override
        public void run() {
            updateStatus();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onResume() {
        super.onResume();
        applyRecents();
        reEnableService();
        handler.post(statusTicker);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(statusTicker);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) postTest();
    }

    // ============================================================================================
    // Sections with behaviour
    // ============================================================================================

    private void header() {
        status = new TextView(this);
        status.setTextSize(13);
        status.setTextColor(secondary());
        status.setPadding(dp(4), 0, dp(4), dp(12));
        page.addView(status);

        Button accessibility = new Button(this);
        accessibility.setText(R.string.open_accessibility_settings);
        accessibility.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        page.addView(accessibility, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    private void updateStatus() {
        if (labEntryState != null) {
            labEntryState.setText(prefs.getBoolean(Prefs.LAB_ROTATE, Prefs.DEF_LAB_ROTATE) ? R.string.labs_on : R.string.labs_off);
        }
        if (labPermission != null) {
            labPermission.setText(FullscreenRotator.canWrite(this) ? R.string.lab_permission_granted : R.string.lab_permission_needed);
        }
        int screen = getResources().getConfiguration().densityDpi;
        status.setText(getString(R.string.status_line, DateService.status(this), screenLabel(screen),
                screenLabel(dpi)));
    }

    /** Display size profile: "auto" follows the tablet's display size setting. */
    private void profileChooser() {
        RadioGroup group = new RadioGroup(this);
        group.setOrientation(RadioGroup.HORIZONTAL);
        String chosen = prefs.getString(Prefs.PROFILE, Prefs.DEF_PROFILE);
        String[][] options = {
                {Prefs.DEF_PROFILE, getString(R.string.profile_auto)},
                {String.valueOf(Prefs.DPI_SMALL), getString(R.string.profile_small)},
                {String.valueOf(Prefs.DPI_STANDARD), getString(R.string.profile_standard)}};
        for (String[] option : options) {
            RadioButton button = new RadioButton(this);
            button.setText(option[1]);
            button.setTextColor(primary());
            button.setChecked(option[0].equals(chosen));
            button.setOnClickListener(v -> {
                prefs.edit().putString(Prefs.PROFILE, option[0]).apply();
                recreate();   // reload the rows for the chosen profile
            });
            group.addView(button, new RadioGroup.LayoutParams(0, RadioGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        card.addView(group);
        divider();
    }

    private void testButtons() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button post = new Button(this);
        post.setText(R.string.post_test_notification);
        post.setOnClickListener(v -> postTest());
        Button clear = new Button(this);
        clear.setText(R.string.clear_test_notification);
        clear.setOnClickListener(v -> getSystemService(NotificationManager.class).cancel(TEST_ID));
        row.addView(post, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(clear, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(row);
        divider();
    }

    /** Posts (or re-posts) one notification so the date's move next to the badge can be checked. */
    private void postTest() {
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 1);
            return;
        }
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(TEST_CHANNEL, getString(R.string.test_channel),
                NotificationManager.IMPORTANCE_DEFAULT));
        nm.cancel(TEST_ID);
        nm.notify(TEST_ID, new Notification.Builder(this, TEST_CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_my_calendar)
                .setContentTitle(getString(R.string.test_title))
                .setContentText(getString(R.string.test_text))
                .setAutoCancel(true)
                .setContentIntent(PendingIntent.getActivity(this, 0,
                        new Intent(this, SettingsActivity.class), PendingIntent.FLAG_IMMUTABLE))
                .build());
    }

    private void applyRecents() {
        boolean show = prefs.getBoolean(Prefs.SHOW_IN_RECENTS, true);
        for (ActivityManager.AppTask task : getSystemService(ActivityManager.class).getAppTasks()) {
            task.setExcludeFromRecents(!show);
        }
    }

    /**
     * ColorOS drops a force-stopped app from the enabled accessibility list. With
     * WRITE_SECURE_SETTINGS (granted over adb), opening this screen puts this service back.
     */
    private void reEnableService() {
        if (checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) return;
        String me = getPackageName() + "/" + DateService.class.getName();
        String list = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (list != null) {
            for (String entry : list.split(":")) {
                if (entry.equalsIgnoreCase(me) || entry.equalsIgnoreCase(getPackageName() + "/.DateService")) return;
            }
        }
        Settings.Secure.putString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                list == null || list.isEmpty() ? me : list + ":" + me);
        Settings.Secure.putInt(getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 1);
        Toast.makeText(this, R.string.service_reenabled, Toast.LENGTH_SHORT).show();
    }

    private String screenLabel(int screenDpi) {
        if (screenDpi == Prefs.DPI_SMALL) return getString(R.string.profile_small);
        if (screenDpi == Prefs.DPI_STANDARD) return getString(R.string.profile_standard);
        return getString(R.string.profile_dpi, screenDpi);
    }

    // ============================================================================================
    // Building blocks
    // ============================================================================================

    /** Starts a new card with a title and an optional explanation above it. */
    private void section(String title, String help) {
        trimDivider();
        TextView label = new TextView(this);
        label.setText(title);
        label.setTextSize(15);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setTextColor(accent());
        label.setPadding(dp(4), dp(24), dp(4), dp(6));
        page.addView(label);
        if (help != null) {
            TextView note = small(help);
            note.setPadding(dp(4), 0, dp(4), dp(8));
            page.addView(note);
        }
        card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(4), dp(16), dp(4));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(night ? 0xFF1C1C1E : 0xFFFFFFFF);
        bg.setCornerRadius(dp(16));
        card.setBackground(bg);
        page.addView(card);
    }

    private void toggle(String title, String help, String key, boolean def, Runnable after) {
        LinearLayout row = row();
        row.addView(labels(title, help), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Switch sw = new Switch(this);
        sw.setChecked(prefs.getBoolean(key, def));
        sw.setOnCheckedChangeListener((b, on) -> {
            prefs.edit().putBoolean(key, on).apply();
            if (after != null) after.run();
        });
        row.addView(sw);
        row.setOnClickListener(v -> sw.toggle());
        card.addView(row);
        divider();
    }

    private void text(String title, String help, String key, String def) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(12), 0, dp(8));
        box.addView(labels(title, help));
        EditText edit = new EditText(this);
        edit.setSingleLine(true);
        edit.setTextColor(primary());
        edit.setText(prefs.getString(key, def));
        edit.setImeOptions(EditorInfo.IME_ACTION_DONE);
        // An untouched default is not stored, so the date format default keeps following the language.
        Runnable save = () -> {
            String value = edit.getText().toString();
            if (value.equals(def)) prefs.edit().remove(key).apply();
            else prefs.edit().putString(key, value).apply();
        };
        edit.setOnEditorActionListener((v, id, e) -> { save.run(); return false; });
        edit.setOnFocusChangeListener((v, focus) -> { if (!focus) save.run(); });
        box.addView(edit);
        card.addView(box);
        divider();
    }

    /**
     * Title on the left, [−] value [+] on the right. Profile keys (size and placement) read and
     * write the value of the active display size profile.
     */
    private void number(String title, String help, String key, float def, float step, boolean isFloat) {
        if (Prefs.isProfileKey(key)) {
            def = isFloat ? Prefs.defFloat(key, dpi) : Prefs.defInt(key, dpi);
            key = Prefs.key(key, dpi);
        }
        final String storeKey = key;
        final float fallback = def;

        LinearLayout row = row();
        row.addView(labels(title, help), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        EditText edit = new EditText(this);
        edit.setSingleLine(true);
        edit.setGravity(Gravity.CENTER);
        edit.setTextColor(primary());
        edit.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED
                | (isFloat ? InputType.TYPE_NUMBER_FLAG_DECIMAL : 0));
        edit.setImeOptions(EditorInfo.IME_ACTION_DONE);
        edit.setText(format(isFloat ? prefs.getFloat(storeKey, fallback) : prefs.getInt(storeKey, (int) fallback), isFloat));
        Runnable save = () -> {
            try { store(storeKey, Float.parseFloat(edit.getText().toString().trim()), isFloat); }
            catch (NumberFormatException ignored) {}
        };
        edit.setOnEditorActionListener((v, id, e) -> { save.run(); return false; });
        edit.setOnFocusChangeListener((v, focus) -> { if (!focus) save.run(); });

        Button minus = stepButton("−");
        Button plus = stepButton("+");
        View.OnClickListener bump = v -> {
            float value;
            try { value = Float.parseFloat(edit.getText().toString().trim()); }
            catch (NumberFormatException bad) { value = fallback; }
            value += v == plus ? step : -step;
            edit.setText(format(value, isFloat));
            store(storeKey, value, isFloat);
        };
        minus.setOnClickListener(bump);
        plus.setOnClickListener(bump);

        row.addView(minus, new LinearLayout.LayoutParams(dp(44), dp(44)));
        row.addView(edit, new LinearLayout.LayoutParams(dp(72), LinearLayout.LayoutParams.WRAP_CONTENT));
        row.addView(plus, new LinearLayout.LayoutParams(dp(44), dp(44)));
        card.addView(row);
        divider();
    }

    private void store(String key, float value, boolean isFloat) {
        if (isFloat) prefs.edit().putFloat(key, value).apply();
        else prefs.edit().putInt(key, Math.round(value)).apply();
        updateStatus();
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(52));
        row.setPadding(0, dp(6), 0, dp(6));
        return row;
    }

    private LinearLayout labels(String title, String help) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(16);
        t.setTextColor(primary());
        box.addView(t);
        if (help != null) box.addView(small(help));
        return box;
    }

    private TextView small(String text) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(13);
        v.setTextColor(secondary());
        return v;
    }

    private Button stepButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(18);
        b.setPadding(0, 0, 0, 0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        return b;
    }

    /** Thin line between rows; the last one in a card is removed when the next card starts. */
    private void divider() {
        View line = new View(this);
        line.setTag("divider");
        line.setBackgroundColor(night ? 0xFF38383A : 0xFFE5E5EA);
        card.addView(line, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1));
    }

    private void trimDivider() {
        if (card == null || card.getChildCount() == 0) return;
        View last = card.getChildAt(card.getChildCount() - 1);
        if ("divider".equals(last.getTag())) card.removeView(last);
    }

    private static String format(float value, boolean isFloat) {
        if (!isFloat) return Integer.toString(Math.round(value));
        String s = String.format(Locale.US, "%.2f", value).replaceAll("0+$", "");
        return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
    }

    private int primary() {
        return night ? 0xFFFFFFFF : 0xFF000000;
    }

    private int secondary() {
        return night ? 0xFF98989F : 0xFF6C6C70;
    }

    private int accent() {
        return night ? 0xFF64A8FF : 0xFF2F6FDB;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
