package cc.star0.xposed.hyperstylus.module;

import cc.star0.xposed.hyperstylus.common.Reflect;

import android.app.Application;
import android.content.Context;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Multiplex current Application.attach and discover hosts whose Application is already attached. */
public final class HostLifecycle {
    public interface Action { public void start(Context context) throws Throwable; }
    private record Client(Action action, AtomicBoolean started) {}
    private final FocusStylusModule module;
    private final Map<String, Client> clients = new LinkedHashMap<>();
    private Context application;
    private boolean observing;

    public HostLifecycle(FocusStylusModule module) {
        this.module = module;
    }
    public synchronized void add(String name, Action action) {
        if (application == null && module.session().host("host.application") instanceof Context saved) application = saved;
        Client client = new Client(action, new AtomicBoolean());
        if (clients.putIfAbsent(name, client) != null) return;
        if (!observing) { observing = true; observe(); }
        if (application != null) start(name, client, application);
        else discoverAttachedApplication();
    }
    @android.annotation.SuppressLint({"PrivateApi", "DiscouragedPrivateApi"})
    private void observe() {
        try {
            module.after(Application.class.getDeclaredMethod("attach", Context.class),
                    (receiver, args, result) -> attached((Application) receiver));
        } catch (Throwable error) { module.error("Application.attach observer", error); }
    }
    // Injected hosts can load a package after attach; ActivityThread supplies that existing lifecycle context.
    @android.annotation.SuppressLint("PrivateApi")
    private void discoverAttachedApplication() {
        try {
            Object current = Reflect.call(Class.forName("android.app.ActivityThread"), "currentApplication");
            if (current instanceof Application value) attached(value);
        } catch (Throwable ignored) { /* The attach observer supplies the context during normal startup. */ }
    }
    private synchronized void attached(Application value) {
        if (value == null) return;
        application = value;
        module.session().remember("host.application", value);
        clients.forEach((name, client) -> start(name, client, value));
    }
    private void start(String name, Client client, Context context) {
        if (!client.started.compareAndSet(false, true)) return;
        try { client.action.start(context); }
        catch (Throwable error) {
            client.started.set(false);
            module.error("Start host client " + name, error);
        }
    }
}
