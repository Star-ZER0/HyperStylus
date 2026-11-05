package cc.star0.xposed.hyperstylus.storage;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import androidx.test.core.app.ApplicationProvider;
import cc.star0.xposed.hyperstylus.config.EnhanceConfig;
import cc.star0.xposed.hyperstylus.model.pen.BatteryState;
import cc.star0.xposed.hyperstylus.runtime.charge.ChargeLimitPolicy;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class PenBatteryCacheTest {
    private SharedPreferences preferences;
    @Before public void setup() {
        Context context = ApplicationProvider.getApplicationContext();
        preferences = context.getSharedPreferences("battery-cache-test", Context.MODE_PRIVATE);
        preferences.edit().clear().commit();
    }

    @Test public void lastSuccessfulBatterySurvivesOvernightAndControllerRecreation() {
        PenBatteryCache cache = new PenBatteryCache(preferences);
        assertTrue(cache.accept(91, 1_000, 1_000)); cache.persist();
        long morning = 1_000 + 8 * 60 * 60 * 1_000L;
        assertEquals(91, cache.displayLevel(morning));
        PenBatteryCache recreated = new PenBatteryCache(preferences);
        assertEquals(91, recreated.displayLevel(morning)); assertEquals(1_000, recreated.sampledAt());
    }

    @Test public void failedReadsAndDisconnectSamplesNeverOverwriteTheLastValidReading() {
        PenBatteryCache cache = new PenBatteryCache(preferences);
        cache.accept(67, 1_000, 1_000); cache.persist();
        for (int invalid : new int[]{-1, -100, 101}) assertFalse(cache.accept(invalid, 2_000, 2_000));
        cache.persist();
        assertEquals(67, preferences.getInt("level", -1)); assertEquals(1_000, preferences.getLong("time", 0));
    }

    @Test public void aNewReadingReplacesTheCacheAndOlderSnapshotsCannotRollItBack() {
        PenBatteryCache cache = new PenBatteryCache(preferences);
        cache.accept(91, 1_000, 1_000);
        assertTrue(cache.accept(88, 30_000_000, 30_000_000)); cache.persist();
        assertFalse(cache.accept(91, 1_000, 30_000_001));
        assertEquals(88, new PenBatteryCache(preferences).displayLevel(30_000_001));
    }

    @Test public void anOldSnapshotCanPopulateAnEmptyDisplayCacheWithoutRefreshingItsTimestamp() {
        PenBatteryCache cache = new PenBatteryCache(preferences);
        long morning = 30_000_000;
        assertTrue(cache.accept(70, 1_000, morning)); cache.persist();
        assertEquals(70, cache.displayLevel(morning)); assertEquals(1_000, cache.sampledAt());
        assertEquals(1_000, preferences.getLong("time", 0));
    }

    @Test public void zeroPercentIsAValidSuccessfulReadAndFutureOrUndatedSamplesAreNot() {
        PenBatteryCache cache = new PenBatteryCache(preferences);
        assertFalse(cache.accept(50, 0, 1_000)); assertFalse(cache.accept(50, 2_000, 1_000));
        assertEquals(-1, cache.displayLevel(1_000));
        assertTrue(cache.accept(0, 1_000, 1_000)); cache.persist();
        assertEquals(0, new PenBatteryCache(preferences).displayLevel(30_000_000));
    }

    @Test public void displayCacheCannotAdmitChargingUsingAnExpiredBatterySample() {
        PenBatteryCache cache = new PenBatteryCache(preferences);
        BatteryState telemetry = new BatteryState(); telemetry.battery(30, 4, 1_000);
        cache.accept(telemetry.level, telemetry.updatedAt, 1_000);
        long morning = 30_000_000;
        assertEquals(30, cache.displayLevel(morning)); assertEquals(-1, telemetry.displayLevel(morning));
        Bundle settings = EnhanceConfig.defaults().toBundle(); settings.putBoolean("charge_limit_enabled", true);
        ChargeLimitPolicy policy = new ChargeLimitPolicy();
        assertEquals(ChargeLimitPolicy.Command.STOP, policy.evaluate(new EnhanceConfig(settings), telemetry, morning, true));
        assertTrue(policy.blocked);
    }
}
