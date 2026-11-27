package io.github.dqot.toplinedate;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Once a day (system job, any network) asks GitHub for the latest release and posts a notification
 * when it is newer than the installed version. The only network access in the app: one small
 * request to api.github.com, nothing about the device or the screen is sent.
 */
public class UpdateCheck extends JobService {
    private static final int JOB_ID = 1;
    private static final long DAY_MS = 24L * 60 * 60 * 1000;
    private static final String API = "https://api.github.com/repos/DQOT/TopLineDate/releases/latest";
    private static final String CHANNEL = "updates";
    private static final String STATE = "update";   // own prefs file: the service listens to "cfg"

    /** Schedules the daily check when enabled (keeps an existing schedule), cancels it otherwise. */
    static void schedule(Context context) {
        JobScheduler js = context.getSystemService(JobScheduler.class);
        if (js == null) return;
        boolean on = context.getSharedPreferences(Prefs.FILE, MODE_PRIVATE)
                .getBoolean(Prefs.UPDATE_CHECK, Prefs.DEF_UPDATE_CHECK);
        if (!on) {
            js.cancel(JOB_ID);
            return;
        }
        if (js.getPendingJob(JOB_ID) != null) return;
        js.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(context, UpdateCheck.class))
                .setPeriodic(DAY_MS)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .build());
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        new Thread(() -> {
            boolean retry = false;
            try {
                check();
            } catch (Exception e) {
                Log.w(DateService.TAG, "update check failed", e);
                retry = true;
            }
            jobFinished(params, retry);
        }, "update-check").start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }

    private void check() throws Exception {
        HttpURLConnection http = (HttpURLConnection) new URL(API).openConnection();
        http.setConnectTimeout(15000);
        http.setReadTimeout(15000);
        http.setRequestProperty("Accept", "application/vnd.github+json");
        http.setRequestProperty("User-Agent", "TopLineDate");
        try {
            int code = http.getResponseCode();
            if (code == 404) return;   // no release yet
            if (code != 200) throw new IllegalStateException("HTTP " + code);
            JSONObject release = new JSONObject(read(http.getInputStream()));
            String tag = release.optString("tag_name", "");
            String url = release.optString("html_url", "https://github.com/DQOT/TopLineDate/releases");
            String installed = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            Log.i(DateService.TAG, "update check: latest " + tag + ", installed " + installed);
            SharedPreferences state = getSharedPreferences(STATE, MODE_PRIVATE);
            if (compare(tag, installed) <= 0 || tag.equals(state.getString("notified", ""))) return;
            if (notifyUpdate(tag, url)) state.edit().putString("notified", tag).apply();
        } finally {
            http.disconnect();
        }
    }

    private boolean notifyUpdate(String tag, String url) {
        // A runtime permission from Android 13; before that it reads as denied though notifications work.
        if (android.os.Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, getString(R.string.update_channel),
                NotificationManager.IMPORTANCE_DEFAULT));
        String version = tag.startsWith("v") || tag.startsWith("V") ? tag.substring(1) : tag;
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        nm.notify(3, new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(getString(R.string.update_title, version))
                .setContentText(getString(R.string.update_text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build());
        return true;
    }

    /** Compares dotted versions ("v1.10.0" > "1.9.2"); non-numeric parts count as 0. */
    static int compare(String a, String b) {
        String[] x = a.replaceFirst("^[vV]", "").split("[.-]");
        String[] y = b.replaceFirst("^[vV]", "").split("[.-]");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? number(x[i]) : 0;
            int q = i < y.length ? number(y[i]) : 0;
            if (p != q) return Integer.compare(p, q);
        }
        return 0;
    }

    private static int number(String s) {
        try {
            return Integer.parseInt(s.replaceAll("\\D.*$", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String read(InputStream in) throws Exception {
        try (InputStream stream = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = stream.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }
}
