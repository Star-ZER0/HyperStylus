package cc.star0.xposed.hyperstylus.runtime.keys;

import cc.star0.xposed.hyperstylus.module.FocusStylusModule;
import cc.star0.xposed.hyperstylus.module.ModuleSession;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import java.util.concurrent.atomic.AtomicBoolean;

/** A cancellable laser press owns both edges and executes them on the native controller looper. */
public final class NativeLaser {
    private static final int NATIVE_LONG_PRESS = 5;

    public static Runnable start(FocusStylusModule module, Handler handler, ProGestureAdapter.Dispatch<Boolean> down,
                          ProGestureAdapter.Dispatch<?> up) {
        if (!module.session().isActive()) return null;
        Press press = new Press(module, handler, down, up);
        press.register(module.session().register("input.nativeLaserPress", press::close));
        return press.begin() ? press::release : null;
    }

    private static final class Press {
        private final FocusStylusModule module;
        private final Handler controller;
        private final ProGestureAdapter.Dispatch<Boolean> down;
        private final ProGestureAdapter.Dispatch<?> up;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        // Only the native looper changes active, preventing reordered DOWN/UP state updates.
        private boolean active, activating, finishing;
        private volatile boolean completed;
        private boolean begun;
        private ModuleSession.Registration registration;
        private Throwable releaseFailure;
        private final Runnable activation = this::activate, completion = this::finish;

        public Press(FocusStylusModule module, Handler controller, ProGestureAdapter.Dispatch<Boolean> down,
              ProGestureAdapter.Dispatch<?> up) {
            this.module = module; this.controller = controller; this.down = down; this.up = up;
        }

        private synchronized void register(ModuleSession.Registration registration) {
            this.registration = registration;
            // A late registration may have synchronously closed this press before returning.
            if (completed) registration.forget();
        }

        private synchronized boolean begin() {
            if (completed || cancelled.get() || !module.session().isActive()) { complete(); return false; }
            begun = true;
            if (enqueue(activation)) return true;
            cancelled.set(true);
            complete();
            return false;
        }

        public boolean enqueue(Runnable work) {
            if (Looper.myLooper() == controller.getLooper()) { work.run(); return true; }
            return controller.post(work);
        }

        public void activate() {
            if (completed || cancelled.get() || !module.session().isActive()) { complete(); return; }
            try {
                activating = true;
                try { active = Boolean.TRUE.equals(down.run()); }
                finally { activating = false; }
                if (!active) { complete(); return; }
                // Reentrant release during the native DOWN must balance that accepted edge.
                if (cancelled.get()) { finish(); return; }
                accelerateArmedTimer();
            } catch (Throwable failure) {
                module.error("Activate native laser long press", failure);
                finish();
            }
        }

        private void accelerateArmedTimer() {
            if (!controller.hasMessages(NATIVE_LONG_PRESS)) return;
            controller.removeMessages(NATIVE_LONG_PRESS);
            Message timer = controller.obtainMessage(NATIVE_LONG_PRESS);
            timer.setAsynchronous(true);
            try { controller.dispatchMessage(timer); }
            finally { timer.recycle(); }
        }

        public synchronized void release() {
            if (completed) return;
            if (cancelled.compareAndSet(false, true) && !enqueue(completion)) {
                module.info("Native laser controller looper stopped before release");
            }
        }

        public void close() throws Throwable {
            cancelled.set(true);
            synchronized (this) {
                if (completed) return;
                // No work was enqueued when an inactive session closes a late registration.
                if (!begun) { complete(); return; }
            }
            ModuleSession.onLooper(controller, () -> {
                controller.removeCallbacks(activation); controller.removeCallbacks(completion); finish();
                if (releaseFailure != null) throw releaseFailure;
                if (activating || finishing) throw new IllegalStateException("Native laser edge is still in flight");
            });
        }

        private void finish() {
            // A release reentered from DOWN is balanced once DOWN reports its accepted edge.
            if (activating || finishing || releaseFailure != null) return;
            if (!active) { complete(); return; }
            active = false;
            finishing = true;
            try { up.run(); }
            catch (Throwable failure) { releaseFailure = failure; module.error("Release native laser press", failure); }
            finally { finishing = false; }
            if (releaseFailure == null) complete();
        }

        private synchronized void complete() {
            if (completed) return;
            completed = true;
            controller.removeCallbacks(activation);
            controller.removeCallbacks(completion);
            if (registration != null) registration.forget();
        }
    }
    private NativeLaser() { }
}
