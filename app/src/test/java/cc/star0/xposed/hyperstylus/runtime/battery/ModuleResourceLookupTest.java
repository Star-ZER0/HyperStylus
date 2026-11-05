package cc.star0.xposed.hyperstylus.runtime.battery;

import cc.star0.xposed.hyperstylus.common.Protocol;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/** Injected SystemUI must resolve retained vector resources from the newly installed module package. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ModuleResourceLookupTest {
    @Test public void hostRendererLoadsIconsFromTheCurrentApplicationPackage() {
        Context app = RuntimeEnvironment.getApplication();
        Configuration unique = new Configuration(app.getResources().getConfiguration()); unique.densityDpi = 277;
        class Host extends ContextWrapper {
            public String resourceOwner;
            public Host(Context context) { super(context); }
            @Override public String getPackageName() { return Protocol.SYSTEM_UI; }
            @Override public Context createPackageContext(String name, int flags) throws PackageManager.NameNotFoundException {
                resourceOwner = name;
                return app.createPackageContext(name, flags);
            }
        }
        Host host = new Host(app.createConfigurationContext(unique));
        Bitmap icon = PenBatteryIcon.render(host, 80, false, false, Color.WHITE, 2);
        assertEquals(app.getPackageName(), host.resourceOwner);
        assertTrue(icon.getWidth() > 0 && icon.getHeight() > 0);
        icon.recycle();
    }
}
