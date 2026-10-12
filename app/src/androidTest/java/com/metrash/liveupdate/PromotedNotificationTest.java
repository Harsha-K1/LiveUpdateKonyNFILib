package com.metrash.liveupdate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.app.Notification;
import android.content.Context;
import android.os.Build;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

// Builds the Android 16 (promoted Live Update) notifications and asks the real framework whether
// they meet the promotion requirements. Does not post anything, so it also runs where the OS
// itself can't promote (16.0): the framework check is local to the Notification object.
@RunWith(AndroidJUnit4.class)
public class PromotedNotificationTest {
    private final Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();

    private static void assertPromotable(Notification n) {
        assertNotNull(n.extras.getCharSequence(Notification.EXTRA_TITLE));
        assertEquals("android.app.Notification$ProgressStyle", n.extras.getString(Notification.EXTRA_TEMPLATE));
        assertTrue("promotion must be requested", n.extras.getBoolean("android.requestPromotedOngoing"));
        assertTrue("must be ongoing", (n.flags & Notification.FLAG_ONGOING_EVENT) != 0);
        assertNotNull("chip text", n.extras.getCharSequence("android.shortCriticalText"));
        assertEquals(null, n.contentView);
        assertEquals(null, n.bigContentView);
        assertFalse("colorized notifications can't be promoted", n.extras.getBoolean(Notification.EXTRA_COLORIZED));
        assertTrue("framework says: not promotable", n.hasPromotableCharacteristics());
    }

    @Test
    public void tripNotificationIsPromotable() {
        assumeTrue(Build.VERSION.SDK_INT >= 36);
        Notification n = LiveUpdateManager.Api36.buildTrip(ctx, R.drawable.liveupdate_default_icon,
                "Departure", "10:30 AM", "Arrival", "11:45 AM", 40, "11:45 AM", false, null);
        assertPromotable(n);
        assertEquals("no large icon", null, n.getLargeIcon());
    }

    @Test
    public void progressNotificationIsPromotable() {
        assumeTrue(Build.VERSION.SDK_INT >= 36);
        Notification n = LiveUpdateManager.Api36.build(ctx, R.drawable.liveupdate_default_icon,
                "Order #1042", "Out for delivery", 70, "6 min", false, null);
        assertPromotable(n);
    }
}
