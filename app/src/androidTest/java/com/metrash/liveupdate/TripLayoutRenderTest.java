package com.metrash.liveupdate;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.RemoteViews;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileOutputStream;
import org.junit.Test;
import org.junit.runner.RunWith;

// Inflates the custom trip layout exactly as the notification shade does and renders it to PNGs
// (app cache dir) so the route line / boat can be inspected. Fails if inflation throws.
@RunWith(AndroidJUnit4.class)
public class TripLayoutRenderTest {
    @Test
    public void renderTripLayout() throws Exception {
        Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Context themed = new ContextThemeWrapper(ctx, android.R.style.Theme_DeviceDefault_Light);
        for (int p : new int[]{0, 40, 100}) {
            RemoteViews rv = LiveUpdateManager.tripViews(
                    ctx, "Departure", "10:30 AM", "Arrival", "11:45 AM", p);
            final Bitmap[] out = new Bitmap[1];
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                FrameLayout parent = new FrameLayout(themed);
                View v = rv.apply(themed, parent);
                parent.addView(v);
                int w = View.MeasureSpec.makeMeasureSpec(
                        (int) (360 * ctx.getResources().getDisplayMetrics().density), View.MeasureSpec.EXACTLY);
                parent.measure(w, View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                parent.layout(0, 0, parent.getMeasuredWidth(), parent.getMeasuredHeight());
                Bitmap b = Bitmap.createBitmap(parent.getWidth(), parent.getHeight(), Bitmap.Config.ARGB_8888);
                Canvas c = new Canvas(b);
                c.drawColor(0xFFFFFFFF);
                parent.draw(c);
                out[0] = b;
            });
            try (FileOutputStream f = new FileOutputStream(new File(ctx.getExternalCacheDir(), "trip_" + p + ".png"))) {
                out[0].compress(Bitmap.CompressFormat.PNG, 100, f);
            }
        }
    }
}
