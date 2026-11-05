package cc.star0.xposed.hyperstylus.runtime.nativegesture;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import cc.star0.xposed.hyperstylus.config.ConfigBridge;
import cc.star0.xposed.hyperstylus.config.EnhanceConfig;
import cc.star0.xposed.hyperstylus.config.StylusGestureConfig;
import cc.star0.xposed.hyperstylus.module.FocusStylusModule;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** The removal acknowledgement follows journal/settings release; retirement preserves ownership. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class ScopeRemovalPreparationTest {
    private FocusStylusModule module;
    private EnhanceConfig previous;
    private boolean previousReady;
    private final Map<SystemBrushController.Key, String> values = new HashMap<>();
    private final List<Boolean> replies = new ArrayList<>();
    private Map<SystemBrushController.Key, SystemBrushController.Claim> claims;
    private boolean rejectRestore;
    private static Field field(Class<?> type, String name) throws Exception {
        Field value = type.getDeclaredField(name); value.setAccessible(true); return value;
    }
    private static EnhanceConfig disabled(long revision) {
        Bundle value = EnhanceConfig.defaults().toBundle();
        value.putBoolean("battery_enabled", false); value.putBoolean("pro_settings", false);
        value.putLong("revision", revision);
        for (String key : EnhanceConfig.KEY_ENABLED_KEYS) value.putBoolean(key, false);
        return new EnhanceConfig(value);
    }
    @Before public void setup() throws Exception {
        previous = ConfigBridge.current(); previousReady = ConfigBridge.ready();
        field(ConfigBridge.class, "current").set(null, disabled(41)); field(ConfigBridge.class, "ready").setBoolean(null, true);
        module = new FocusStylusModule();
        module.attachFramework((io.github.libxposed.api.XposedInterface) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{io.github.libxposed.api.XposedInterface.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("log")) return null;
                    throw new UnsupportedOperationException(method.getName());
                }), () -> { });
        var pinch = new SystemBrushController.Key(10, StylusGestureConfig.Kind.PINCH);
        var tap = new SystemBrushController.Key(0, StylusGestureConfig.Kind.DOUBLE_TAP);
        values.put(pinch, "5"); values.put(tap, "4");
        claims = Map.of(pinch, new SystemBrushController.Claim("2", "5"), tap, new SystemBrushController.Claim(null, "4"));
        SystemBrushSettings owner = new SystemBrushSettings(module, new Handler(Looper.getMainLooper()), () -> 0,
                new SystemBrushController.Database() {
                    public String get(SystemBrushController.Key key) { return values.get(key); }
                    public boolean put(SystemBrushController.Key key, String value) {
                        if (rejectRestore) return false;
                        if (value == null) values.remove(key); else values.put(key, value); return true;
                    }
                }, new SystemBrushController.Journal() {
                    public Map<SystemBrushController.Key, SystemBrushController.Claim> read() { return claims; }
                    public void write(Map<SystemBrushController.Key, SystemBrushController.Claim> next) { claims = next; }
                });
        field(SystemBrushSettings.class, "active").set(null, owner);
    }
    @After public void cleanup() throws Exception {
        module.session().close(2_000);
        field(SystemBrushSettings.class, "active").set(null, null);
        field(ConfigBridge.class, "current").set(null, previous); field(ConfigBridge.class, "ready").setBoolean(null, previousReady);
    }
    @Test public void nativeClaimsAcrossUsersAreRestoredBeforeAcknowledgement() {
        SystemBrushSettings.prepareScopeRemoval(module, RuntimeEnvironment.getApplication(), ready -> {
            assertTrue("Journal must be empty before a successful reply", claims.isEmpty()); replies.add(ready);
        });
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(List.of(true), replies);
        assertEquals("2", values.get(new SystemBrushController.Key(10, StylusGestureConfig.Kind.PINCH)));
        assertFalse(values.containsKey(new SystemBrushController.Key(0, StylusGestureConfig.Kind.DOUBLE_TAP)));
    }
    @Test public void reenabledConfigurationRejectsTheQueuedRemovalWithoutRestoringClaims() throws Exception {
        SystemBrushSettings.prepareScopeRemoval(module, RuntimeEnvironment.getApplication(), replies::add);
        Bundle next = disabled(42).toBundle(); next.putBoolean("focus_key_enabled", true);
        field(ConfigBridge.class, "current").set(null, new EnhanceConfig(next));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(List.of(false), replies); assertEquals(2, claims.size());
    }
    @Test public void generationRetirementCancelsPreparationAndKeepsTheJournalForTheNextGeneration() {
        SystemBrushSettings.prepareScopeRemoval(module, RuntimeEnvironment.getApplication(), replies::add);
        assertTrue(module.session().close(2_000));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(List.of(false), replies); assertEquals(2, claims.size());
    }
    @Test public void aMissingContextCannotAcknowledgeOrStartAWorker() {
        SystemBrushSettings.prepareScopeRemoval(module, null, replies::add);
        assertEquals(List.of(false), replies); assertEquals(2, claims.size());
    }
    @Test public void aRejectedNativeRestoreCannotAcknowledgeSuccessfulScopeRemoval() {
        rejectRestore = true;
        SystemBrushSettings.prepareScopeRemoval(module, RuntimeEnvironment.getApplication(), replies::add);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(List.of(false), replies); assertEquals(2, claims.size());
    }
}
