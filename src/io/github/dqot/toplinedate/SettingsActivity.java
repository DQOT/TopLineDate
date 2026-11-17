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

        section("앱 유지", "최근 앱 모두 닫기에 꺼지지 않게 하려면, 최근 앱 화면에서 이 앱 카드를 잠근 뒤 "
                + "'최근 앱에 표시'를 끄세요. 잠금은 유지되고 카드만 숨겨집니다.");
        toggle("최근 앱에 표시", null, Prefs.SHOW_IN_RECENTS, true, this::applyRecents);

        section("표시", null);
        toggle("날짜 표시", null, Prefs.ENABLED, Prefs.DEF_ENABLED, null);
        text("형식", "예: M월 d일 EEEE, M/d E, M.d (E)", Prefs.FORMAT, Prefs.DEF_FORMAT);
        text("글자 색", "auto 또는 #RRGGBB. auto는 상단 바 밝기에 맞춰 바뀝니다.", Prefs.COLOR, Prefs.DEF_COLOR);
        text("글꼴", "비우면 시스템 글꼴", Prefs.FONT, Prefs.DEF_FONT);
        number("굵기", "100-900. 700이 시계 획 두께와 같습니다.", Prefs.WEIGHT, Prefs.DEF_WEIGHT, 100, false);

        section("크기와 위치", "아래 값은 디스플레이 크기마다 따로 저장됩니다.");
        profileChooser();
        number("글자 크기", "px", Prefs.SIZE, 0, 0.5f, true);
        number("시계와의 간격", "알림 표시가 없을 때, px", Prefs.GAP, 0, 1, false);
        number("알림 표시와의 간격", "알림 표시가 있을 때, px", Prefs.GAP_ICONS, 0, 1, false);
        number("세로 화면 높이", "px", Prefs.PORTRAIT_Y, 0, 1, false);
        number("세로 화면 미세 높이", "px, 소수", Prefs.FINE_Y_PORTRAIT, 0, 0.25f, true);
        number("가로 화면 높이", "px", Prefs.LANDSCAPE_Y, 0, 1, false);
        number("가로 화면 미세 높이", "px, 소수", Prefs.FINE_Y_LANDSCAPE, 0, 0.25f, true);
        toggle("시계 옆에 자동으로 붙이기", "끄면 아래 X 값으로 고정", Prefs.ANCHOR, Prefs.DEF_ANCHOR, null);
        number("세로 화면 X", "자동 붙이기를 끈 경우, px", Prefs.PORTRAIT_X, 0, 1, false);
        number("가로 화면 X", "자동 붙이기를 끈 경우, px", Prefs.LANDSCAPE_X, 0, 1, false);

        section("알림 표시", "알림 표시가 생기거나 사라질 때 날짜가 옆으로 움직이는 속도입니다.");
        testButtons();
        number("생길 때 이동 시간", "ms, 0이면 바로 이동", Prefs.SLIDE_MS, Prefs.DEF_SLIDE_MS, 10, false);
        number("사라질 때 이동 시간", "ms, 0이면 바로 이동", Prefs.SLIDE_BACK_MS, Prefs.DEF_SLIDE_BACK_MS, 10, false);

        section("숨김", null);
        toggle("전체 화면에서 숨기기", null, Prefs.HIDE_FULLSCREEN, Prefs.DEF_HIDE_FULLSCREEN, null);
        toggle("시계가 숨으면 같이 숨기기", "최근 앱 화면, 전체 화면 진입, 잠금 해제 직후 등",
                Prefs.HIDE_RECENTS, Prefs.DEF_HIDE_RECENTS, null);
        toggle("전체 화면에서 나올 때 미리 표시", "상단 바보다 약 0.3초 먼저 나타납니다",
                Prefs.PREDICT, Prefs.DEF_PREDICT, null);

        section("애니메이션과 타이밍", "단위는 ms입니다.");
        number("전체 화면에서 내려오는 시간", "0이면 페이드", Prefs.BAR_SLIDE_MS, Prefs.DEF_BAR_SLIDE_MS, 10, false);
        number("나타날 때 페이드", null, Prefs.FADE_IN, Prefs.DEF_FADE_IN, 10, false);
        number("사라질 때 페이드", null, Prefs.FADE_OUT, Prefs.DEF_FADE_OUT, 10, false);
        number("앱 위 회전 중 계속 표시", "0이면 회전할 때 숨김", Prefs.ROTATE_GRACE, Prefs.DEF_ROTATE_GRACE, 100, false);
        number("앱 위 회전 후 대기", "계속 표시를 0으로 했을 때", Prefs.ROTATE_DELAY, Prefs.DEF_ROTATE_DELAY, 10, false);
        number("잠금 해제 후 대기 (앱 위)", null, Prefs.UNLOCK_DELAY, Prefs.DEF_UNLOCK_DELAY, 10, false);
        number("잠금 해제 후 대기 (홈 화면)", null, Prefs.HOME_UNLOCK_DELAY, Prefs.DEF_HOME_UNLOCK_DELAY, 10, false);
        trimDivider();
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
        accessibility.setText("접근성 설정 열기");
        accessibility.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        page.addView(accessibility, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    private void updateStatus() {
        int screen = getResources().getConfiguration().densityDpi;
        status.setText("TopLineDate · 서비스: " + DateService.status() + "\n디스플레이 크기: " + screenLabel(screen)
                + "  ·  적용 중인 프로필: " + screenLabel(dpi));
    }

    /** Display size profile: "auto" follows the tablet's display size setting. */
    private void profileChooser() {
        RadioGroup group = new RadioGroup(this);
        group.setOrientation(RadioGroup.HORIZONTAL);
        String chosen = prefs.getString(Prefs.PROFILE, Prefs.DEF_PROFILE);
        String[][] options = {
                {Prefs.DEF_PROFILE, "자동"},
                {String.valueOf(Prefs.DPI_SMALL), "작게"},
                {String.valueOf(Prefs.DPI_STANDARD), "표준"}};
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
        post.setText("테스트 알림 보내기");
        post.setOnClickListener(v -> postTest());
        Button clear = new Button(this);
        clear.setText("테스트 알림 지우기");
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
        nm.createNotificationChannel(new NotificationChannel(TEST_CHANNEL, "테스트 알림",
                NotificationManager.IMPORTANCE_DEFAULT));
        nm.cancel(TEST_ID);
        nm.notify(TEST_ID, new Notification.Builder(this, TEST_CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_my_calendar)
                .setContentTitle("TopLineDate 테스트")
                .setContentText("상단 바에서 날짜 위치를 확인하세요. 눌러서 지울 수 있습니다.")
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
        Toast.makeText(this, "TopLineDate를 다시 켰습니다", Toast.LENGTH_SHORT).show();
    }

    private static String screenLabel(int screenDpi) {
        if (screenDpi == Prefs.DPI_SMALL) return "작게";
        if (screenDpi == Prefs.DPI_STANDARD) return "표준";
        return "배율 " + screenDpi;
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
        Runnable save = () -> prefs.edit().putString(key, edit.getText().toString()).apply();
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
