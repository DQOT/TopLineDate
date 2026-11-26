package io.github.dqot.toplinedate;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;

import java.util.Map;
import java.util.TreeMap;

/**
 * adb-only tuning hook (protected by the DUMP permission in the manifest).
 *   adb shell am broadcast -n io.github.dqot.toplinedate/.TuneReceiver --ei gap@420 18 --ef size@420 31.5
 *   adb shell am broadcast -n io.github.dqot.toplinedate/.TuneReceiver --ez reset true
 * Every call logs the resulting settings under the TopLineDate tag. Size and placement keys
 * carry the display density, e.g. size@420 (see Prefs.PROFILE_KEYS).
 */
public class TuneReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        SharedPreferences prefs = Prefs.get(context);
        SharedPreferences.Editor edit = prefs.edit();
        Bundle extras = intent.getExtras();
        if (extras != null) {
            if (extras.getBoolean("reset", false)) edit.clear();
            for (String key : extras.keySet()) {
                if (key.equals("reset") || key.equals("headsup")) continue;
                Object value = extras.get(key);
                if (value instanceof Integer) edit.putInt(key, (Integer) value);
                else if (value instanceof Float) edit.putFloat(key, (Float) value);
                else if (value instanceof Boolean) edit.putBoolean(key, (Boolean) value);
                else if (value instanceof String) edit.putString(key, (String) value);
            }
        }
        edit.commit();
        if (extras != null && extras.getBoolean("headsup", false)) postHeadsUp(context);
        Map<String, ?> all = new TreeMap<>(prefs.getAll());
        Log.i(DateService.TAG, "settings " + all + " service=" + DateService.status());
    }

    /** Test aid: a high-importance notification, which drops in as a heads-up card. */
    private static void postHeadsUp(Context context) {
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("headsup", context.getString(R.string.headsup_channel),
                NotificationManager.IMPORTANCE_HIGH));
        nm.notify(2, new Notification.Builder(context, "headsup")
                .setSmallIcon(android.R.drawable.ic_menu_my_calendar)
                .setContentTitle(context.getString(R.string.headsup_title))
                .setContentText(context.getString(R.string.headsup_text))
                .setAutoCancel(true)
                .build());
    }
}
