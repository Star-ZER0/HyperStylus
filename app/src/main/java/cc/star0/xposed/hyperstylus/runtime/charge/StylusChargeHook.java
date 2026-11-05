package cc.star0.xposed.hyperstylus.runtime.charge;

import cc.star0.xposed.hyperstylus.common.Reflect;
import cc.star0.xposed.hyperstylus.module.FocusStylusModule;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** API 102 HAL adapter; charging policy and ownership live in separate runtime components. */
public final class StylusChargeHook {
    private static final String SERVICE = "com.android.server.MiuiBatteryServiceImpl";
    private static final String HAL = "miui.util.IMiCharge";
    private final FocusStylusModule module;
    private final ClassLoader loader;
    private final Handler worker;
    private final WirelessChargeControl control = new WirelessChargeControl();
    private final ChargeLimitRuntime runtime;
    private final HandlerThread ownedThread;
    private volatile boolean closed;
    private record Worker(Handler handler, HandlerThread thread) {}

    public StylusChargeHook(FocusStylusModule module, ClassLoader loader) throws ClassNotFoundException {
        this(module, loader, createWorker(loader));
    }
    private StylusChargeHook(FocusStylusModule module, ClassLoader loader, Worker own) {
        this(module, loader, own.handler(), own.thread());
    }
    public StylusChargeHook(FocusStylusModule module, ClassLoader loader, Handler worker) {
        this(module, loader, worker, null);
    }
    private StylusChargeHook(FocusStylusModule module, ClassLoader loader, Handler worker, HandlerThread ownedThread) {
        this.module = module; this.loader = loader; this.worker = worker; this.ownedThread = ownedThread;
        runtime = new ChargeLimitRuntime(module, worker, control);
        module.session().onClose("charge.worker", this::close);
    }
    @android.annotation.SuppressLint("PrivateApi")
    private static Worker createWorker(ClassLoader loader) throws ClassNotFoundException {
        Class.forName(SERVICE, false, loader); Class.forName(HAL, false, loader);
        HandlerThread thread = new HandlerThread("HyperStylus-charge"); thread.start();
        return new Worker(new Handler(thread.getLooper()), thread);
    }
    public @android.annotation.SuppressLint("PrivateApi")
    void install() throws ReflectiveOperationException {
        Class<?> service = Class.forName(SERVICE, false, loader), hal = Class.forName(HAL, false, loader);
        Method setCharging = hal.getDeclaredMethod("setWirelessChargingEnabled", boolean.class);
        if (setCharging.getReturnType() != int.class) throw new NoSuchMethodException("Unknown wireless charging HAL result");
        module.managedHook(setCharging).intercept(chain -> {
            if (control.ownsInvocation()) return chain.proceed();
            synchronized (control.lock) {
                WirelessChargeControl.NativeRequest request = control.nativeRequest(chain.getThisObject(), Boolean.TRUE.equals(chain.getArg(0)));
                Object result = request.denied() ? chain.proceed(new Object[]{false}) : chain.proceed();
                runtime.nativeCompleted(request, result);
                return result;
            }
        });
        for (Constructor<?> constructor : service.getDeclaredConstructors()) {
            try { module.after(constructor, (receiver, args, result) -> capture(receiver)); }
            catch (Throwable error) { module.error("Observe battery service construction", error); }
        }
        // The current service assigns mContext and creates BatteryHandler in init(Context).
        module.after(service.getDeclaredMethod("init", Context.class), (receiver, args, result) -> capture(receiver));
        Class<?> handler = Reflect.find(loader, SERVICE + "$BatteryHandler");
        module.afterMethods(handler, "handleMessage", (receiver, args, result) -> {
            if (args.length == 0 || !(args[0] instanceof Message message) || message.what != 12) return;
            for (Field field : handler.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || !service.isAssignableFrom(field.getType())) continue;
                field.setAccessible(true); capture(field.get(receiver)); break;
            }
        });
        module.info("Wireless charging HAL adapter installed");
        Object saved = module.session().host("charge.owner");
        if (saved != null && service.isInstance(saved)) capture(saved);
    }
    private void capture(Object service) {
        if (closed || !module.session().isActive() || service == null) return;
        worker.post(() -> {
            if (closed || !module.session().isActive()) return;
            try {
                Object hardware = Reflect.field(service, "mMiCharge");
                if (hardware == null) return;
                module.session().remember("charge.owner", service);
                module.session().remember("charge.hardware", hardware);
                if (control.capture(hardware)) module.info("Captured pen charging HAL");
                Context context = context(service);
                if (context != null) runtime.attach(context);
                runtime.evaluate();
            } catch (Throwable error) { module.error("Attach charging service", error); }
        });
    }
    private Context context(Object service) {
        try { return Reflect.field(service, "mContext") instanceof Context context ? context : null; }
        catch (ReflectiveOperationException | RuntimeException unavailable) { return null; }
    }
    public void close() throws Throwable {
        closed = true; Throwable failure = null;
        try { runtime.close(); } catch (Throwable error) { failure = error; }
        if (ownedThread != null) {
            ownedThread.quitSafely();
            if (Thread.currentThread() == ownedThread) throw new IllegalStateException("Cannot retire from the charge worker itself");
            ownedThread.join(2000);
            if (ownedThread.isAlive()) {
                IllegalStateException error = new IllegalStateException("Charge worker did not terminate");
                if (failure == null) failure = error; else failure.addSuppressed(error);
            }
        }
        if (failure != null) throw failure;
    }
}
