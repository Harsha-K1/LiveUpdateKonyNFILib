package com.metrash.liveupdate.sample;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
import android.view.View;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.core.app.NotificationManagerCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.metrash.liveupdate.LiveUpdateManager;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.function.BooleanSupplier;

/**
 * Exercises LiveUpdateManager exactly the way the VoltMX app will: static calls with a Context.
 */
public class MainActivity extends Activity {
    // Mirrors LiveUpdateManager.NOTIF_ID (private in the lib); used only to inspect the posted notification
    private static final int LIB_NOTIF_ID = 4201;
    private static final String TITLE = "Order #1042";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView status, log, progressLabel;
    private SeekBar seek;
    private int simProgress = -1;   // -1 = simulation not running

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // targetSdk 35+ draws edge-to-edge; keep content clear of the system bars
        View root = findViewById(R.id.root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets b = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(b.left, b.top, b.right, b.bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        status = findViewById(R.id.status);
        log = findViewById(R.id.log);
        progressLabel = findViewById(R.id.progressLabel);
        seek = findViewById(R.id.progress);

        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                progressLabel.setText("Progress: " + p + "%");
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        seek.setProgress(0);
        progressLabel.setText("Progress: 0%");

        findViewById(R.id.btnPermission).setOnClickListener(v -> requestNotificationPermission());
        findViewById(R.id.btnPromoSettings).setOnClickListener(v -> openPromotionSettings());

        findViewById(R.id.btnStart).setOnClickListener(v -> {
            stopSimulation();
            if (!ensurePermission()) return;
            int p = seek.getProgress();
            call("start", () -> LiveUpdateManager.start(this, TITLE, stageText(p), p, chipText(p), true));
        });
        findViewById(R.id.btnUpdate).setOnClickListener(v -> {
            stopSimulation();
            int p = seek.getProgress();
            call("update", () -> LiveUpdateManager.update(this, TITLE, stageText(p), p, chipText(p), true));
        });
        findViewById(R.id.btnEnd).setOnClickListener(v -> {
            stopSimulation();
            call("end", () -> { LiveUpdateManager.end(this); return true; });
        });
        findViewById(R.id.btnSimulate).setOnClickListener(v -> {
            if (ensurePermission()) startSimulation();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    // ---- Simulation ----

    private final Runnable simStep = new Runnable() {
        @Override public void run() {
            int p = simProgress;
            seek.setProgress(p);
            if (p >= 100) {
                call("update", () -> LiveUpdateManager.update(
                        MainActivity.this, TITLE, "Delivered", 100, "Done", true));
                simProgress = -1;
                handler.postDelayed(() -> call("end", () -> {
                    LiveUpdateManager.end(MainActivity.this);
                    return true;
                }), 4000);
                return;
            }
            String name = (p == 0) ? "start" : "update";
            boolean shown = call(name, () -> (p == 0)
                    ? LiveUpdateManager.start(MainActivity.this, TITLE, stageText(p), p, chipText(p), true)
                    : LiveUpdateManager.update(MainActivity.this, TITLE, stageText(p), p, chipText(p), true));
            if (!shown) {
                stopSimulation();
                return;
            }
            simProgress = p + 5;
            handler.postDelayed(this, 1000);
        }
    };

    private void startSimulation() {
        stopSimulation();
        simProgress = 0;
        handler.post(simStep);
    }

    private void stopSimulation() {
        handler.removeCallbacks(simStep);
        simProgress = -1;
    }

    private static String stageText(int p) {
        if (p < 33) return "Order confirmed";
        if (p < 66) return "Preparing your order";
        if (p < 100) return "Out for delivery";
        return "Delivered";
    }

    private static String chipText(int p) {
        int minsLeft = Math.max(1, (100 - p) / 5);   // ~1 min per 5%
        return p >= 100 ? "Done" : minsLeft + " min";
    }

    // ---- Calls into the lib ----

    // Logs the lib's return value: false means Android will not show the notification
    private boolean call(String name, BooleanSupplier r) {
        try {
            boolean shown = r.getAsBoolean();
            log(name + "(" + seek.getProgress() + "%) " + (shown
                    ? "OK" : "returned false: not shown (permission missing or notifications off)"));
            // The system fills in promotion flags asynchronously after notify()
            handler.postDelayed(this::refreshStatus, 400);
            return shown;
        } catch (Throwable t) {
            log(name + " FAILED: " + t);
            return false;
        }
    }

    // ---- Permission / settings ----

    // Same calls the VoltMX app makes: hasPermission() / requestPermission(activity)
    private boolean ensurePermission() {
        if (LiveUpdateManager.hasPermission(this)) return true;
        log("Notifications not allowed yet; asking first. Tap again after allowing.");
        requestNotificationPermission();
        return false;
    }

    private void requestNotificationPermission() {
        if (LiveUpdateManager.requestPermission(this)) {
            log("Notification permission already granted");
        } else if (Build.VERSION.SDK_INT < 33
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            // No dialog exists for this case: notifications were switched off in settings
            log("Notifications are off in settings; opening settings");
            LiveUpdateManager.openNotificationSettings(this);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == LiveUpdateManager.PERMISSION_REQUEST_CODE) {
            boolean granted = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
            log("POST_NOTIFICATIONS " + (granted ? "granted" : "denied"));
            if (!granted && !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
                log("Permission blocked; opening settings");
                LiveUpdateManager.openNotificationSettings(this);
            }
            refreshStatus();
        }
    }

    private void openPromotionSettings() {
        if (Build.VERSION.SDK_INT >= 36) {
            try {
                startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
                return;
            } catch (Exception e) {
                log("Promotion settings screen unavailable: " + e.getMessage());
            }
        } else {
            log("Live Updates need Android 16; opening normal notification settings");
        }
        LiveUpdateManager.openNotificationSettings(this);
    }

    // ---- Diagnostics ----

    private void refreshStatus() {
        StringBuilder sb = new StringBuilder();
        sb.append("Android ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("Notifications enabled : ")
                .append(NotificationManagerCompat.from(this).areNotificationsEnabled()).append('\n');
        sb.append("canPromote()          : ").append(LiveUpdateManager.canPromote(this)).append('\n');
        sb.append("Promotion API (36.1+) : ").append(hasRequestPromotedOngoing()).append('\n');
        sb.append("Path used by lib      : ")
                .append(Build.VERSION.SDK_INT >= 36 ? "ProgressStyle (Live Update)" : "Compat progress fallback")
                .append('\n');

        Notification n = findPosted();
        if (n == null) {
            sb.append("Posted notification   : none");
        } else {
            sb.append("Posted notification   : yes, ongoing=")
                    .append((n.flags & Notification.FLAG_ONGOING_EVENT) != 0);
            if (Build.VERSION.SDK_INT >= 36) {
                sb.append("\n  promotable chars    : ").append(n.hasPromotableCharacteristics());
                boolean promoted = (n.flags & Notification.FLAG_PROMOTED_ONGOING) != 0;
                sb.append("\n  PROMOTED (Live Upd.): ").append(promoted);
                if (!promoted) sb.append(whyNotPromoted(n));
            }
        }
        status.setText(sb);
    }

    // Checks each Live Update requirement the system applies before setting FLAG_PROMOTED_ONGOING
    private String whyNotPromoted(Notification n) {
        StringBuilder r = new StringBuilder("\n  Not promoted because:");
        int before = r.length();
        if (!n.extras.getBoolean("android.requestPromotedOngoing"))
            r.append("\n   - promotion not requested (extra missing)");
        if (!hasRequestPromotedOngoing())
            r.append("\n   - OS is 16.0; Live Updates need 16 QPR1 (36.1)+");
        if (!LiveUpdateManager.canPromote(this))
            r.append("\n   - Live updates turned off for app (button 2)");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            if (!n.hasPromotableCharacteristics())
                r.append("\n   - notification lacks promotable characteristics");
        }
        NotificationManager nm = getSystemService(NotificationManager.class);
        android.app.NotificationChannel ch = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ch = nm.getNotificationChannel(n.getChannelId());
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (ch != null && ch.getImportance() <= NotificationManager.IMPORTANCE_MIN)
                r.append("\n   - channel importance lowered by user");
        }
        if (r.length() == before)
            r.append("\n   - all checks pass; OEM UI may not support Live Updates");
        return r.toString();
    }

    private Notification findPosted() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        for (StatusBarNotification sbn : nm.getActiveNotifications()) {
            if (sbn.getId() == LIB_NOTIF_ID) return sbn.getNotification();
        }
        return null;
    }

    private static boolean hasRequestPromotedOngoing() {
        try {
            Notification.Builder.class.getMethod("setRequestPromotedOngoing", boolean.class);
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    private void log(String msg) {
        String ts = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        log.setText(ts + "  " + msg + "\n" + log.getText());
    }
}
