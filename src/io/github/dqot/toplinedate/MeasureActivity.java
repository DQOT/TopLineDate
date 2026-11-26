package io.github.dqot.toplinedate;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.TextView;

/**
 * Development aid, not in the launcher: a plain black screen where a tap toggles fullscreen, so the
 * date's timing can be compared with the system clock on a steady background (screen recordings).
 * Open with: adb shell am start -n io.github.dqot.toplinedate/.MeasureActivity
 */
public class MeasureActivity extends Activity {
    private boolean fullscreen;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getActionBar() != null) getActionBar().hide();
        TextView view = new TextView(this);
        view.setBackgroundColor(Color.BLACK);
        view.setTextColor(0xFF555555);
        view.setGravity(Gravity.CENTER);
        view.setText(R.string.measure_hint);
        view.setOnTouchListener((v, e) -> {
            // The left half does nothing: a tap there only dismisses a swiped-in bar.
            if (e.getAction() == android.view.MotionEvent.ACTION_UP && e.getX() > v.getWidth() / 2f) toggle();
            return true;
        });
        setContentView(view);
    }

    private void toggle() {
        fullscreen = !fullscreen;
        WindowInsetsController controller = getWindow().getInsetsController();
        if (controller == null) return;
        controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        if (fullscreen) controller.hide(WindowInsets.Type.statusBars());
        else controller.show(WindowInsets.Type.statusBars());
    }
}
