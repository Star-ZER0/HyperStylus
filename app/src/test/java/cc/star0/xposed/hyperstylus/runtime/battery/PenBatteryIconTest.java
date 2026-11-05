package cc.star0.xposed.hyperstylus.runtime.battery;


import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import androidx.test.core.app.ApplicationProvider;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

/** Current-renderer tests cover battery colors, unknown readings and attachment geometry. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class PenBatteryIconTest {
    @Test public void chargingColorTakesPriorityOverLowBatteryColor() {
        Context context = atDensity(320);
        Bitmap low = PenBatteryIcon.render(context, 20, false, false, Color.WHITE, 2);
        Bitmap charging = PenBatteryIcon.render(context, 20, true, false, Color.WHITE, 2);
        assertTrue(pixelsWithColor(low, PenBatteryIcon.LOW_COLOR) > 0);
        assertEquals(0, pixelsWithColor(low, PenBatteryIcon.CHARGE_COLOR));
        assertTrue(pixelsWithColor(charging, PenBatteryIcon.CHARGE_COLOR) > 0);
        assertEquals(0, pixelsWithColor(charging, PenBatteryIcon.LOW_COLOR));
    }

    @Test public void anUnknownBatteryDoesNotPretendToBeLowAndSignalUnknownUsesUnlitWaves() {
        Context context = atDensity(320);
        Bitmap unknown = PenBatteryIcon.render(context, -1, false, false, Color.WHITE, -1);
        Bitmap belowSignalThreshold = PenBatteryIcon.render(context, -1, false, false, Color.WHITE, 0);
        Bitmap knownSignal = PenBatteryIcon.render(context, -1, false, false, Color.WHITE, 2);
        assertEquals(0, pixelsWithColor(unknown, PenBatteryIcon.LOW_COLOR));
        assertEquals(0, pixelsWithColor(unknown, PenBatteryIcon.CHARGE_COLOR));
        assertTrue(unknown.sameAs(belowSignalThreshold));
        assertFalse(unknown.sameAs(knownSignal));
    }

    @Test public void nativeDrawingProducesVisiblePixelsAndAttachmentTrimsOnlyWaves() {
        Context context = ApplicationProvider.getApplicationContext();
        Bitmap writing = PenBatteryIcon.render(context, 60, false, false, Color.WHITE, 2);
        Bitmap attached = PenBatteryIcon.render(context, 60, false, true, Color.WHITE, 2);
        float density = context.getResources().getDisplayMetrics().density;
        assertEquals(Math.round(25 * density), writing.getWidth());
        assertEquals(Math.round(20 * density), attached.getWidth());
        assertEquals(writing.getHeight(), attached.getHeight());
        int[] pixels = new int[writing.getWidth() * writing.getHeight()];
        writing.getPixels(pixels, 0, writing.getWidth(), 0, 0, writing.getWidth(), writing.getHeight());
        boolean painted = false;
        for (int pixel : pixels) painted |= Color.alpha(pixel) > 0;
        assertTrue("Native renderer must actually draw glyphs", painted);
    }

    private static Context atDensity(int density) {
        Context base = ApplicationProvider.getApplicationContext();
        Configuration configuration = new Configuration(base.getResources().getConfiguration());
        configuration.densityDpi = density;
        return base.createConfigurationContext(configuration);
    }

    @Test public void disablingSignalTrimsWavesWithoutChangingBatteryOrChargingState() {
        Context context = atDensity(320);
        Bitmap enabled = PenBatteryIcon.render(context, 60, true, false, Color.WHITE, 2, true);
        Bitmap disabled = PenBatteryIcon.render(context, 60, true, false, Color.WHITE, 2, false);
        float density = context.getResources().getDisplayMetrics().density;
        assertEquals(Math.round(20 * density), disabled.getWidth());
        assertEquals(enabled.getHeight(), disabled.getHeight());
        assertEquals(pixelsWithColor(enabled, PenBatteryIcon.CHARGE_COLOR), pixelsWithColor(disabled, PenBatteryIcon.CHARGE_COLOR));
        Bitmap glyphsWithoutWaves = PenBatteryIcon.render(context, 60, true, true, Color.WHITE, 2, true);
        assertTrue(disabled.sameAs(glyphsWithoutWaves));
        assertSame(disabled, PenBatteryIcon.render(context, 60, true, false, Color.WHITE, 2, false));
        assertSame(enabled, PenBatteryIcon.render(context, 60, true, false, Color.WHITE, 2, true));
    }

    private static int pixelsWithColor(Bitmap bitmap, int color) {
        int[] pixels = new int[bitmap.getWidth() * bitmap.getHeight()];
        bitmap.getPixels(pixels, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());
        int count = 0;
        for (int pixel : pixels) if (pixel == color) count++;
        return count;
    }
}
