package com.metrash.liveupdate;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.RemoteViews;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import java.util.Arrays;
import android.annotation.TargetApi;

public class LiveUpdateManager {
    private static final String CHANNEL_ID = "live_updates";
    // Pre-Android 16 path: HIGH importance so start() pops a heads-up card. Separate channel
    // because an app cannot raise an existing channel's importance.
    private static final String ALERT_CHANNEL_ID = "live_updates_alert";
    private static final int NOTIF_ID = 4201;
    private static final String TAG = "LiveUpdateManager";
    // requestCode the host Activity receives in onRequestPermissionsResult
    public static final int PERMISSION_REQUEST_CODE = 4202;

    // Call from JS. progress is 0-100. Returns false when Android will not show the
    // notification (permission missing or notifications blocked); nothing is posted then.
    // tapDeepLink (all overloads): URI opened in this app when the user taps the notification,
    // e.g. "myapp://trip/42". null/empty (or no activity handling it) opens the launcher activity.
    public static boolean start(Context ctx, String title, String text, int progress, String chipText, boolean alertOnEveryUpdate) {
        return start(ctx, title, text, progress, chipText, alertOnEveryUpdate, null);
    }

    public static boolean start(Context ctx, String title, String text, int progress, String chipText,
                                boolean alertOnEveryUpdate, String tapDeepLink) {
        return post(ctx, title, text, progress, chipText, alertOnEveryUpdate, tapDeepLink);
    }

    public static boolean update(Context ctx, String title, String text, int progress, String chipText, boolean alertOnEveryUpdate) {
        return update(ctx, title, text, progress, chipText, alertOnEveryUpdate, null);
    }

    public static boolean update(Context ctx, String title, String text, int progress, String chipText,
                                 boolean alertOnEveryUpdate, String tapDeepLink) {
        return post(ctx, title, text, progress, chipText, alertOnEveryUpdate, tapDeepLink);   // same ID = in-place update
    }

    // Trip card: app icon, dashed route with a boat at `progress` (0-100), and
    // "Departure 10:30 AM" / "Arrival 11:45 AM" underneath. Labels are passed in so they can be
    // localised. Android 16: promoted Live Update (ProgressStyle, boat as tracker icon).
    // Older: custom layout. Call again with a new progress to update in place.
    public static boolean showTrip(Context ctx, String departureLabel, String departureTime, String arrivalLabel, String arrivalTime,
                                   int progress, String chipText, boolean alertOnEveryUpdate) {
        return showTrip(ctx, departureLabel, departureTime, arrivalLabel, arrivalTime,
                progress, chipText, alertOnEveryUpdate, null);
    }

    public static boolean showTrip(Context ctx, String departureLabel, String departureTime, String arrivalLabel, String arrivalTime,
                                   int progress, String chipText, boolean alertOnEveryUpdate, String tapDeepLink) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        boolean promoted = canShowPromoted(ctx);
        if (!ready(ctx, nm, promoted)) return false;
        PendingIntent tap = tapIntent(ctx, tapDeepLink);
        int icon = smallIcon(ctx);
        Notification n = promoted
                ? Api36.buildTrip(ctx, icon, departureLabel, departureTime,
                        arrivalLabel, arrivalTime, progress, chipText, alertOnEveryUpdate, tap)
                : tripFallback(ctx, icon, departureLabel, departureTime,
                        arrivalLabel, arrivalTime, progress, alertOnEveryUpdate, tap);
        nm.notify(NOTIF_ID, n);
        return true;
    }

    // True when the trip card can be a real Live Update: Android 16, the user allows promotion,
    // and the OS has the promotion API (16 QPR1 / 36.1+; plain 16.0 such as Samsung One UI 8
    // never promotes). Otherwise showTrip() uses the custom layout, which has the full design.
    private static boolean canShowPromoted(Context ctx) {
        boolean api = false;
        try {
            Notification.Builder.class.getMethod("setRequestPromotedOngoing", boolean.class);
            api = true;
        } catch (Throwable ignored) { }
        boolean allowed = Build.VERSION.SDK_INT >= 36 && canPromote(ctx);
        Log.i(TAG, "showTrip: SDK=" + Build.VERSION.SDK_INT + " promotionApi=" + api
                + " userAllowsPromotion=" + allowed
                + " -> " + ((allowed && api) ? "Live Update (ProgressStyle)" : "custom layout"));
        return allowed && api;
    }

    // True when notifications from this app can be shown (Android 13+ runtime permission
    // granted and not switched off in system settings).
    public static boolean hasPermission(Context ctx) {
        return NotificationManagerCompat.from(ctx).areNotificationsEnabled();
    }

    // Shows the system "Allow notifications?" dialog on Android 13+ if not yet granted.
    // The answer goes to the Activity's onRequestPermissionsResult, not here, so check
    // hasPermission() afterwards. Android stops showing the dialog after the user denies it
    // twice; use openNotificationSettings() then. Returns true if already allowed.
    public static boolean requestPermission(Activity activity) {
        if (hasPermission(activity)) return true;
        if (Build.VERSION.SDK_INT >= 33 && activity.checkSelfPermission(
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(
                    new String[]{Manifest.permission.POST_NOTIFICATIONS}, PERMISSION_REQUEST_CODE);
        } else {
            Log.w(TAG, "Notifications are blocked in system settings; no dialog to show");
        }
        return false;
    }

    // Opens this app's notification settings so the user can switch notifications on.
    public static void openNotificationSettings(Context ctx) {
        Intent i;
        if (Build.VERSION.SDK_INT >= 26) {
            i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.getPackageName());
        } else {
            i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + ctx.getPackageName()));
        }
        if (!(ctx instanceof Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(i);
    }

    public static void end(Context ctx) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        nm.cancel(NOTIF_ID);
    }

    public static boolean canPromote(Context ctx) {
        if (Build.VERSION.SDK_INT < 36) return false;
        return ctx.getSystemService(NotificationManager.class)
                .canPostPromotedNotifications();
    }

    // True when this device can ever show a promoted Live Update: Android 16 plus the promotion API.
    // Does not depend on notification permission or the user's per-app Live Updates setting.
    static boolean isPromotionSupported() {
        if (Build.VERSION.SDK_INT < 36) return false;
        try {
            Notification.Builder.class.getMethod("setRequestPromotedOngoing", boolean.class);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean post(Context ctx, String title, String text,
                                int progress, String chipText, boolean alertOnEveryUpdate, String tapDeepLink) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (!ready(ctx, nm, Build.VERSION.SDK_INT >= 36)) return false;
        PendingIntent tap = tapIntent(ctx, tapDeepLink);
        int icon = smallIcon(ctx);

        Notification n = (Build.VERSION.SDK_INT >= 36)
                ? Api36.build(ctx, icon, title, text, progress, chipText, alertOnEveryUpdate, tap)
                : fallback(ctx, icon, title, text, progress, alertOnEveryUpdate, tap);

        nm.notify(NOTIF_ID, n);
        return true;
    }

    // Creates channels and checks permission / channel state; false = nothing may be posted
    private static boolean ready(Context ctx, NotificationManager nm, boolean promoted) {
        ensureChannels(nm, promoted);
        if (!hasPermission(ctx)) {
            Log.w(TAG, "Notification not shown: permission missing or notifications blocked");
            return false;
        }
        if (isChannelBlocked(nm, promoted)) {
            Log.w(TAG, "Notification not shown: 'Live updates' category turned off by the user");
            return false;
        }
        return true;
    }

    // Tap action: the deep link if one is given and this app has an activity for it, else the launcher
    private static PendingIntent tapIntent(Context ctx, String tapDeepLink) {
        PackageManager packageManager = ctx.getPackageManager();
        Intent intent = null;

        if (tapDeepLink != null && !tapDeepLink.isEmpty()) {
            Intent deepLinkIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(tapDeepLink));
            deepLinkIntent.setPackage(ctx.getPackageName());   // only our own app can receive it
            deepLinkIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (deepLinkIntent.resolveActivity(packageManager) != null) {
                intent = deepLinkIntent;
            } else {
                Log.w(TAG, "No activity handles " + tapDeepLink + "; falling back to launcher");
            }
        }
        if (intent == null) {
            intent = packageManager.getLaunchIntentForPackage(ctx.getPackageName());
        }
        return PendingIntent.getActivity(ctx, 0, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static int smallIcon(Context ctx) {
        int icon = ctx.getResources().getIdentifier(
                "ic_stat_notify", "drawable", ctx.getPackageName()); // your small icon
        return icon != 0 ? icon : R.drawable.liveupdate_default_icon;   // bundled fallback
    }

    private static int clampProgress(int progress) {
        return Math.max(0, Math.min(100, progress));
    }

    // Custom trip layout filled in for `progress`; package-private so tests can render it
    static RemoteViews tripViews(Context ctx, String depLabel, String depTime,
                                 String arrLabel, String arrTime, int progress) {
        RemoteViews rv = new RemoteViews(ctx.getPackageName(), R.layout.liveupdate_trip);
        rv.setTextViewText(R.id.liveupdate_dep_label, depLabel);
        rv.setTextViewText(R.id.liveupdate_dep_time, depTime);
        rv.setTextViewText(R.id.liveupdate_arr_label, arrLabel);
        rv.setTextViewText(R.id.liveupdate_arr_time, arrTime);

        // Show the boat row nearest to progress (rows exist every 2%, all hidden by default)
        int step = Math.round(clampProgress(progress) / 2f) * 2;
        int boatId = ctx.getResources().getIdentifier(
                "liveupdate_boat_" + step, "id", ctx.getPackageName());
        if (boatId != 0) rv.setViewVisibility(boatId, View.VISIBLE);
        int lineId = ctx.getResources().getIdentifier(
                "liveupdate_line_" + step, "id", ctx.getPackageName());
        if (lineId != 0) rv.setViewVisibility(lineId, View.VISIBLE);
        return rv;
    }

    private static Notification tripFallback(Context ctx, int icon, String depLabel, String depTime, String arrLabel, String arrTime, int progress,
                                             boolean alertOnEveryUpdate, PendingIntent tap) {
        RemoteViews rv = tripViews(ctx, depLabel, depTime, arrLabel, arrTime, progress);

        return new NotificationCompat.Builder(ctx, ALERT_CHANNEL_ID)
                .setSmallIcon(icon)
                .setStyle(new NotificationCompat.DecoratedCustomViewStyle())
                .setCustomContentView(rv).setCustomBigContentView(rv)
                .setContentTitle(depLabel + " " + depTime)   // shown where custom views aren't (e.g. watches)
                .setContentText(arrLabel + " " + arrTime)
                .setOngoing(true).setOnlyAlertOnce(!alertOnEveryUpdate)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setDefaults(NotificationCompat.DEFAULT_SOUND)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setContentIntent(tap).build();
    }

    private static boolean isChannelBlocked(NotificationManager nm, boolean promoted) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false;
        NotificationChannel ch = nm.getNotificationChannel(
                promoted ? CHANNEL_ID : ALERT_CHANNEL_ID);
        return ch != null && ch.getImportance() == NotificationManager.IMPORTANCE_NONE;
    }

    private static Notification fallback(Context ctx, int icon, String title, String text, int progress, boolean alertOnEveryUpdate, PendingIntent tap) {
        // Heads-up card on the first post only; setOnlyAlertOnce keeps update() calls silent
        return new NotificationCompat.Builder(ctx, ALERT_CHANNEL_ID)
                .setSmallIcon(icon).setContentTitle(title).setContentText(text)
                .setOngoing(true).setOnlyAlertOnce(!alertOnEveryUpdate)
                .setPriority(NotificationCompat.PRIORITY_HIGH)   // heads-up on API 25 (no channels)
                .setDefaults(NotificationCompat.DEFAULT_SOUND)  // pre-O heads-up requires sound or vibration
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)   // full content on lock screen
                .setProgress(100, progress, false)
                .setContentIntent(tap).build();
    }

    private static void ensureChannels(NotificationManager nm, boolean promoted) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;   // channels don't exist below API 26
        if (promoted) {
            ensureChannel(nm, CHANNEL_ID, NotificationManager.IMPORTANCE_DEFAULT);
        } else {
            ensureChannel(nm, ALERT_CHANNEL_ID, NotificationManager.IMPORTANCE_HIGH);
        }
    }

    @TargetApi(26)
    private static void ensureChannel(NotificationManager nm, String id, int importance) {
        if (nm.getNotificationChannel(id) == null) {
            NotificationChannel ch = new NotificationChannel(id, "Live updates", importance);
            ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            nm.createNotificationChannel(ch);
        }
    }

    // Isolated so older devices never load Android 16 classes
    @TargetApi(36)
    static class Api36 {
        // Promoted notifications can't use custom layouts, so the trip look is built from
        // ProgressStyle's start/end icons and tracker icon (the boat).
        static Notification buildTrip(Context ctx, int icon, String depLabel, String depTime, String arrLabel, String arrTime, int progress,
                                      String chipText, boolean alertOnEveryUpdate, PendingIntent tap) {
            Notification.ProgressStyle style = new Notification.ProgressStyle()
                    .setProgressSegments(Arrays.asList(new Notification.ProgressStyle.Segment(100)))
                    .setProgress(clampProgress(progress))
                    .setStyledByProgress(true)   // travelled part full colour, remainder faded
                    .setProgressStartIcon(Icon.createWithResource(ctx, R.drawable.liveupdate_ic_endpoint))
                    .setProgressEndIcon(Icon.createWithResource(ctx, R.drawable.liveupdate_ic_endpoint))
                    .setProgressTrackerIcon(Icon.createWithResource(ctx, R.drawable.sail_boat_tracker));   // padded copy: smaller, never clipped

            Notification.Builder b = new Notification.Builder(ctx, CHANNEL_ID)
                    .setSmallIcon(icon)
                    .setContentTitle(depLabel + " " + depTime)
                    .setContentText(arrLabel + " " + arrTime)
                    .setOngoing(true).setOnlyAlertOnce(!alertOnEveryUpdate)
                    .setVisibility(Notification.VISIBILITY_PUBLIC)
                    .setContentIntent(tap)
                    .setStyle(style);
            setChip(b, chipText);
            requestPromotion(b);
            return b.build();
        }

        // Status-bar chip text; left unset when blank so no empty chip is requested
        private static void setChip(Notification.Builder b, String chipText) {
            if (chipText != null && !chipText.trim().isEmpty()) b.setShortCriticalText(chipText);
        }

        private static void requestPromotion(Notification.Builder b) {
            try {   // only on 36.1+ devices; absent on plain 36.0
                Notification.Builder.class
                        .getMethod("setRequestPromotedOngoing", boolean.class)
                        .invoke(b, true);
            } catch (Throwable ignored) {
                b.getExtras().putBoolean("android.requestPromotedOngoing", true);
            }
        }

        static Notification build(Context ctx, int icon, String title, String text, int progress, String chipText, boolean alertOnEveryUpdate, PendingIntent tap) {

            Notification.ProgressStyle style = new Notification.ProgressStyle()
                    .setProgressSegments(Arrays.asList(
                            new Notification.ProgressStyle.Segment(33),
                            new Notification.ProgressStyle.Segment(33),
                            new Notification.ProgressStyle.Segment(34)))
                    .setProgress(progress);

            Notification.Builder b = new Notification.Builder(ctx, CHANNEL_ID)
                    .setSmallIcon(icon).setContentTitle(title).setContentText(text)
                    .setOngoing(true).setOnlyAlertOnce(!alertOnEveryUpdate)
                    .setVisibility(Notification.VISIBILITY_PUBLIC)   // full content on lock screen
                    .setContentIntent(tap)
                    .setStyle(style);
            setChip(b, chipText);

            requestPromotion(b);

            return b.build();
        }
    }
}