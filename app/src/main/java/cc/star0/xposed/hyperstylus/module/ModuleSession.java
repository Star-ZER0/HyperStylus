package cc.star0.xposed.hyperstylus.module;

import android.os.Handler;
import android.os.Bundle;
import android.os.Looper;
import android.os.SystemClock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.IdentityHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/** Owns one module generation's callbacks and resources; host references may survive its retirement. */
public final class ModuleSession {
    @FunctionalInterface public interface Cleanup { void run() throws Throwable; }
    @FunctionalInterface public interface Registration { void forget(); }
    private record Resource(String name, Cleanup cleanup) { }
    private final BiConsumer<String, Throwable> errors;
    private final List<Resource> resources = new ArrayList<>();
    private final Map<String, Object> hosts = new LinkedHashMap<>();
    private boolean active = true;
    private boolean retired, retirementFailed;
    private int inFlight;

    public ModuleSession(FocusStylusModule module) { this(module::error); }
    ModuleSession(BiConsumer<String, Throwable> errors) { this.errors = errors; }
    public synchronized boolean isActive() { return active; }
    public synchronized boolean enter() {
        if (!active) return false;
        inFlight++;
        return true;
    }
    public synchronized void leave() { inFlight--; notifyAll(); }

    public void onClose(String name, Cleanup cleanup) {
        register(name, cleanup);
    }
    /** A completed short-lived resource can detach its retirement callback. */
    public Registration register(String name, Cleanup cleanup) {
        Resource resource = new Resource(name, cleanup);
        synchronized (this) {
            if (active) {
                resources.add(resource);
                return () -> { synchronized (ModuleSession.this) { resources.remove(resource); } };
            }
        }
        try { cleanup.run(); }
        catch (Throwable failure) {
            synchronized (this) { retirementFailed = true; }
            errors.accept("Close late generation resource " + name, failure);
        }
        return () -> { };
    }
    public <T extends AutoCloseable> T manage(String name, T resource) {
        onClose(name, resource::close);
        return resource;
    }
    public synchronized void remember(String key, Object host) {
        validate(host, new IdentityHashMap<>());
        if (host == null) hosts.remove(key); else hosts.put(key, host);
    }
    public synchronized Object host(String key) { return hosts.get(key); }
    public synchronized boolean hasResource(String name) {
        return resources.stream().anyMatch(resource -> resource.name().equals(name));
    }
    public synchronized Map<String, Object> snapshot() {
        validate(hosts, new IdentityHashMap<>());
        return new LinkedHashMap<>(hosts);
    }
    public synchronized void restore(Map<?, ?> values) {
        values.forEach((key, value) -> { if (key instanceof String name) remember(name, value); });
    }

    /** Inspect neutral containers recursively; opaque host objects must have a host class loader. */
    @SuppressWarnings("deprecation")
    private static void validate(Object value, IdentityHashMap<Object, Boolean> seen) {
        if (value == null || value instanceof String || value instanceof Boolean || value instanceof Character
                || value instanceof Integer || value instanceof Long || value instanceof Float || value instanceof Double
                || value instanceof Byte || value instanceof Short || seen.put(value, true) != null) return;
        ClassLoader old = ModuleSession.class.getClassLoader();
        if (value == old || value instanceof Class<?> type && type.getClassLoader() == old)
            throw new IllegalArgumentException("Module types cannot survive hot reload");
        if (value instanceof Bundle bundle) { for (String key : bundle.keySet()) validate(bundle.get(key), seen); return; }
        if (value.getClass().getClassLoader() == old)
            throw new IllegalArgumentException("Module objects cannot survive hot reload");
        if (value instanceof Map<?, ?> map) map.forEach((key, child) -> { validate(key, seen); validate(child, seen); });
        else if (value instanceof Iterable<?> entries) for (Object child : entries) validate(child, seen);
        else if (value instanceof Object[] entries) for (Object child : entries) validate(child, seen);
    }

    /** Stop new work before waiting for ongoing callbacks; timeout does not destroy active resources. */
    public boolean close(long timeoutMs) {
        List<Resource> closing;
        synchronized (this) {
            if (retired) return !retirementFailed;
            active = false;
            long deadline = SystemClock.uptimeMillis() + timeoutMs;
            while (inFlight != 0) {
                long remaining = deadline - SystemClock.uptimeMillis();
                if (remaining <= 0) { active = true; return false; }
                try { wait(remaining); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); active = true; return false; }
            }
            closing = new ArrayList<>(resources);
            resources.clear();
            retired = true;
        }
        boolean success = true;
        for (int index = closing.size() - 1; index >= 0; index--) {
            Resource resource = closing.get(index);
            try { resource.cleanup().run(); }
            catch (Throwable failure) { success = false; errors.accept("Retire generation resource " + resource.name(), failure); }
        }
        synchronized (this) { retirementFailed |= !success; return !retirementFailed; }
    }

    /** Attempt every release and report all failures before refusing retirement. */
    public static void runAll(Cleanup... cleanups) throws Throwable {
        Throwable failed = null;
        for (Cleanup cleanup : cleanups) {
            try { cleanup.run(); }
            catch (Throwable failure) {
                if (failed == null) failed = failure;
                else if (failed != failure) failed.addSuppressed(failure);
            }
        }
        if (failed != null) throw failed;
    }

    /** Run cleanup on its owning Looper without ever stopping a host Looper. */
    public static void onLooper(Handler handler, Cleanup cleanup) throws Throwable {
        if (Looper.myLooper() == handler.getLooper()) { cleanup.run(); return; }
        CountDownLatch done = new CountDownLatch(1);
        Throwable[] failed = new Throwable[1];
        if (!handler.post(() -> {
            try { cleanup.run(); } catch (Throwable failure) { failed[0] = failure; }
            finally { done.countDown(); }
        })) throw new IllegalStateException("Resource Looper has stopped");
        if (!done.await(2, TimeUnit.SECONDS)) throw new IllegalStateException("Resource Looper cleanup timed out");
        if (failed[0] != null) throw failed[0];
    }
}
