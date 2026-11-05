package cc.star0.xposed.hyperstylus.runtime.keys;

import cc.star0.xposed.hyperstylus.common.Reflect;
import cc.star0.xposed.hyperstylus.module.FocusStylusModule;

import android.os.Handler;
import android.graphics.PointF;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import java.lang.reflect.Method;
import io.github.libxposed.api.XposedInterface;

/** Adapts focus laser, remote paging and native Pro commands without changing physical pen identity. */
public final class NativeStylusDispatch {
    private final FocusStylusModule module;
    private final ClassLoader loader;
    private final StylusKeyInjector keys;
    public NativeStylusDispatch(FocusStylusModule module, ClassLoader loader, StylusKeyInjector keys) {
        this.module = module; this.loader = loader; this.keys = keys;
    }

    public boolean focus(Object manager, KeyEvent original, boolean down) {
        try {
            Method intercept = manager.getClass().getDeclaredMethod("shouldInterceptKey", KeyEvent.class);
            KeyEvent edge = new KeyEvent(original.getDownTime(), SystemClock.uptimeMillis(),
                    down ? KeyEvent.ACTION_DOWN : KeyEvent.ACTION_UP, original.getKeyCode(), 0,
                    original.getMetaState(), original.getDeviceId(), original.getScanCode(), original.getFlags(), original.getSource());
            module.getInvoker(intercept).setType(XposedInterface.Invoker.Type.ORIGIN).invoke(manager, edge);
            return true;
        } catch (Throwable failure) { module.error("Native focus key", failure); return false; }
    }

    public Runnable focusLong(Object manager, KeyEvent original) {
        try {
            Object laser = Reflect.field(manager, "mLaserPointerController");
            if (laser == null) {
                Object service = Reflect.field(manager, "mMiuiInputManagerInternal");
                laser = Reflect.call(service, "obtainLaserPointerController");
                Reflect.set(manager, "mLaserPointerController", laser);
            }
            Object controller = laser;
            Class<?> type = Reflect.find(loader, "com.miui.server.input.stylus.laser.LaserPointerController");
            if (type == null || !type.isInstance(controller) || !(Reflect.field(controller, "mHandler") instanceof Handler handler)) return null;
            if (type.getDeclaredMethod("onLaserKeyLongPressed").getReturnType() != void.class
                    || type.getDeclaredMethod("interceptLaserKeyUp").getReturnType() != void.class
                    || !(Reflect.field(controller, "mIsKeyDown") instanceof Boolean)) return null;
            return NativeLaser.start(module, handler,
                    () -> focus(manager, original, true) && Boolean.TRUE.equals(Reflect.field(controller, "mIsKeyDown")),
                    () -> Reflect.call(controller, "interceptLaserKeyUp"));
        } catch (Throwable incompatible) {
            module.error("Native long-focus controller unavailable", incompatible);
            return null;
        }
    }

    public void slide(Object manager, int device, boolean up) throws Throwable {
        if (Boolean.TRUE.equals(Reflect.call(manager, "isSupportStylusRemoteControl"))) {
            Reflect.call(manager, up ? "previous" : "next");
            return;
        }
        Object app = Reflect.call(manager, "getPackageName");
        if (app instanceof String && Boolean.TRUE.equals(Reflect.call(manager, "isSupportSlidePage", app))) {
            keys.deliver(up ? KeyEvent.KEYCODE_PAGE_UP : KeyEvent.KEYCODE_PAGE_DOWN, -1, InputDevice.SOURCE_KEYBOARD);
            return;
        }
        gesture(up ? 196 : 197, device);
    }

    private Object film() throws Throwable {
        return Reflect.call(loader.loadClass("com.miui.server.input.stylus.MiuiStylusTouchFilmManager"), "getInstance");
    }
    public void gesture(int code, int device) throws Throwable {
        MappedProGesture.execute(code, device, new MappedProGesture.Endpoint() {
            @Override public boolean usesShortcutPanel() throws Throwable {
                Object target = film();
                return Reflect.field(target, "mCurrentPinchFunction") instanceof Number function && function.intValue() == 5
                        && Reflect.field(target, "mCurrentVirtualLaserStatus") instanceof Number laser && laser.intValue() == 0;
            }
            @Override public void showShortcutPanel(int pen) throws Throwable {
                Object target = film();
                StylusHover.ensureMonitoring(module, target);
                PointF point = StylusHover.pointFor(target, pen);
                Reflect.call(target, "updateShortcutPanelDefaultPosition", point);
                Object service = Reflect.field(target, "mMiuiInputManagerInternal");
                if (service == null) throw new IllegalStateException("Native pinch panel service unavailable");
                // This void callback is the actual ROM action; never replay 194 based on the queue's false return.
                Reflect.call(service, "showStylusPinchShortcutPanel", point);
                module.info("Mapped pinch panel requested once");
            }
            @Override public boolean closeVirtualLaser(int pen) throws Throwable {
                Object manager = Reflect.call(loader.loadClass("com.miui.server.input.stylus.MiuiStylusShortcutManager"), "getInstance");
                KeyEvent down = StylusKeyInjector.packet(195, pen, InputDevice.SOURCE_STYLUS | InputDevice.SOURCE_KEYBOARD)[0];
                Object result = Reflect.call(manager, "onVirtualLaserDoubleClickEvent", down);
                return result instanceof Integer handled && handled == 0;
            }
            @Override public void sendApplicationKey(int gesture, int pen) throws Throwable {
                keys.deliverMappedPro(gesture, pen);
                module.info("Mapped Pro application key queued with pen device=" + pen + ", key=" + gesture);
            }
        });
    }
}
