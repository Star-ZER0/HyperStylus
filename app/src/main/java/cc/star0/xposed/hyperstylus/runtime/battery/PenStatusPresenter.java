package cc.star0.xposed.hyperstylus.runtime.battery;

import cc.star0.xposed.hyperstylus.common.Protocol;
import cc.star0.xposed.hyperstylus.common.Reflect;
import cc.star0.xposed.hyperstylus.module.FocusStylusModule;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.Icon;
import android.os.Process;
import java.lang.reflect.Constructor;

/** Creates the exact StatusBarIcon and constructorless holder models present in the supplied OTA. */
public final class PenStatusPresenter {
    public record Appearance(int level, boolean charging, boolean attached, int signal, boolean showSignal) {}
    private final FocusStylusModule module;
    private final ClassLoader loader;
    private Object controller, submitted;
    private String route;
    private boolean visible;

    public PenStatusPresenter(FocusStylusModule module, ClassLoader loader) { this.module = module; this.loader = loader; }
    public Object controller() { return controller; }
    public boolean visible() { return visible; }
    public boolean capture(Object candidate) {
        if (candidate == null || candidate == controller) return false;
        controller = candidate; submitted = null;
        try { module.info("Stylus slot ordered=" + IconSlotOrder.prepare(candidate)); }
        catch (Throwable error) { module.error("Register stylus icon slot", error); }
        return true;
    }
    public void show(Context context, Appearance state, boolean show) throws Throwable {
        if (controller == null) return;
        if (!show) {
            if (submitted != null) Reflect.call(controller, "setIconVisibility", SystemUiController.SLOT, false);
            visible = false; return;
        }
        Icon image = Icon.createWithBitmap(PenBatteryIcon.render(context, state.level, state.charging,
                state.attached, Color.WHITE, state.signal, state.showSignal));
        String description = "触控笔电量 " + (state.level < 0 ? "待更新" : state.level + "%")
                + (state.charging ? "，充电中" : "")
                + (!state.showSignal ? "" : state.signal < 0 ? "，信号待更新" : "，信号 " + state.signal + " 格");
        Class<?> holderType = holderType();
        if (holderType == null) throw new ClassNotFoundException("StatusBarIconHolder");
        IconHolderAdapter.Created result = IconHolderAdapter.create(holderType, statusIcon(image, description), module::allocateIconHolder);
        if (!result.route.equals(route)) { route = result.route; module.info("Stylus holder route=" + route); }
        Reflect.call(controller, "setIcon", SystemUiController.SLOT, result.holder);
        submitted = result.holder;
        Reflect.call(controller, "setIconVisibility", SystemUiController.SLOT, true);
        visible = true;
    }
    private Class<?> holderType() {
        return Reflect.find(loader, "com.android.systemui.statusbar.phone.StatusBarIconHolder");
    }
    @SuppressWarnings({"rawtypes", "unchecked"})
    private Object statusIcon(Icon image, String description) throws Throwable {
        Class<?> type = Reflect.find(loader, "com.android.internal.statusbar.StatusBarIcon");
        if (type == null) throw new ClassNotFoundException("StatusBarIcon");
        Class<?> kind = Reflect.find(loader, "com.android.internal.statusbar.StatusBarIcon$Type");
        if (kind == null) throw new ClassNotFoundException("StatusBarIcon.Type");
        Constructor<?> constructor = type.getDeclaredConstructor(android.os.UserHandle.class, String.class,
                Icon.class, int.class, int.class, CharSequence.class, kind);
        constructor.setAccessible(true);
        return constructor.newInstance(Process.myUserHandle(), Protocol.MODULE, image, 0, 0, description,
                Enum.valueOf((Class<? extends Enum>) kind, "SystemIcon"));
    }
}
