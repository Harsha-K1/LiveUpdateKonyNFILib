package com.metrash.liveupdate;

import android.app.Activity;
import android.content.Context;
import android.util.Log;
import com.konylabs.android.KonyMain;

// Entry point for VoltMX JS (Native Function API). Same operations as LiveUpdateManager, but
// the Context/Activity is resolved here, so JS needs no KonyMain lookups. Never throws into JS.
public class LiveUpdateBridge {
    private static final String TAG = "LiveUpdateBridge";

    // tapDeepLink (all overloads): URI opened in this app when the user taps the notification,
    // e.g. "myapp://trip/42"; null/empty opens the app's launcher activity.
    public static boolean start(String title, String text, int progress, String chipText, boolean alertOnEveryUpdate) {
        return start(title, text, progress, chipText, alertOnEveryUpdate, null);
    }

    public static boolean start(String title, String text, int progress, String chipText,
                                boolean alertOnEveryUpdate, String tapDeepLink) {
        Context ctx = appContext();
        return ctx != null && LiveUpdateManager.start(ctx, title, text, clamp(progress), chipText,
                alertOnEveryUpdate, tapDeepLink);
    }

    public static boolean update(String title, String text, int progress, String chipText, boolean alertOnEveryUpdate) {
        return update(title, text, progress, chipText, alertOnEveryUpdate, null);
    }

    public static boolean update(String title, String text, int progress, String chipText,
                                 boolean alertOnEveryUpdate, String tapDeepLink) {
        Context ctx = appContext();
        return ctx != null && LiveUpdateManager.update(ctx, title, text, clamp(progress), chipText,
                alertOnEveryUpdate, tapDeepLink);
    }

    // Trip card (dashed route + boat). Call again with a new progress to update in place.
    public static boolean showTrip(String departureLabel, String departureTime, String arrivalLabel, String arrivalTime,
                                   int progress, String chipText, boolean alertOnEveryUpdate) {
        return showTrip(departureLabel, departureTime, arrivalLabel, arrivalTime,
                progress, chipText, alertOnEveryUpdate, null);
    }

    public static boolean showTrip(String departureLabel, String departureTime, String arrivalLabel, String arrivalTime,
                                   int progress, String chipText, boolean alertOnEveryUpdate, String tapDeepLink) {
        Context ctx = appContext();
        return ctx != null && LiveUpdateManager.showTrip(ctx, departureLabel, departureTime,
                arrivalLabel, arrivalTime, clamp(progress), chipText, alertOnEveryUpdate, tapDeepLink);
    }

    public static void end() {
        Context ctx = appContext();
        if (ctx != null) LiveUpdateManager.end(ctx);
    }

    public static boolean hasPermission() {
        Context ctx = appContext();
        return ctx != null && LiveUpdateManager.hasPermission(ctx);
    }

    // Shows the Android 13+ permission dialog; returns true if already allowed.
    public static boolean requestPermission() {
        Activity act = activity();
        return act != null && LiveUpdateManager.requestPermission(act);
    }

    public static void openNotificationSettings() {
        Context ctx = appContext();
        if (ctx != null) LiveUpdateManager.openNotificationSettings(ctx);
    }

    public static boolean canPromote() {
        Context ctx = appContext();
        return ctx != null && LiveUpdateManager.canPromote(ctx);
    }

    public static boolean isPromotionSupported() {
        return LiveUpdateManager.isPromotionSupported();
    }

    private static int clamp(int progress) {
        return Math.max(0, Math.min(100, progress));
    }

    private static Context appContext() {
        try {
            Context ctx = KonyMain.getAppContext();
            if (ctx == null) Log.w(TAG, "VoltMX app context not available yet");
            return ctx;
        } catch (Throwable t) {
            Log.e(TAG, "Cannot get VoltMX app context", t);
            return null;
        }
    }

    private static Activity activity() {
        try {
            return KonyMain.getActivityContext();   // KonyMain is an Activity
        } catch (Throwable t) {
            Log.e(TAG, "Cannot get VoltMX activity", t);
            return null;
        }
    }
}
