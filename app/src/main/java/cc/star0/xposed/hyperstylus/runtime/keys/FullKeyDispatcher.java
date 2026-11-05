package cc.star0.xposed.hyperstylus.runtime.keys;

import cc.star0.xposed.hyperstylus.config.ActionConfig;

import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Mirrors physical key edges; merged target owners keep a shared key held until its last release. */
public final class FullKeyDispatcher {
    public interface Sink { void send(KeyEvent event) throws Throwable; }
    public interface Scheduler { void post(Runnable task, long delay); void remove(Runnable task); }
    private record Physical(int device, int code) { }
    private record Target(int code) { }
    private static final class Held {
        final Target key;
        final KeyEvent down;
        final Set<Physical> owners = new LinkedHashSet<>();
        final Set<Integer> devices = new LinkedHashSet<>();
        Runnable timer;
        int repeats;
        Held(Target key, KeyEvent down) { this.key = key; this.down = down; }
        Physical repeatingOwner() { return owners.isEmpty() ? null : owners.iterator().next(); }
    }
    private static final class Press {
        final long downTime;
        final Held target;
        int lastRepeat;
        Press(long downTime, Held target) { this.downTime = downTime; this.target = target; }
    }

    private final Sink sink;
    private final LongSupplier clock;
    private final Scheduler scheduler;
    private final long repeatTimeout, repeatDelay;
    private final Consumer<Throwable> onFailure;
    private final Map<Physical, Press> presses = new HashMap<>();
    private final Map<Physical, Long> latestDown = new HashMap<>();
    private final Map<Target, Held> held = new LinkedHashMap<>();
    private boolean closed;

    public FullKeyDispatcher(Sink sink) { this(sink, SystemClock::uptimeMillis); }
    public FullKeyDispatcher(Sink sink, LongSupplier clock) {
        this(sink, clock, null, 0, 0, null);
    }
    public FullKeyDispatcher(Sink sink, LongSupplier clock, Scheduler scheduler,
                             long repeatTimeout, long repeatDelay, Consumer<Throwable> onFailure) {
        this.sink = Objects.requireNonNull(sink); this.clock = Objects.requireNonNull(clock);
        if (scheduler != null && (repeatTimeout < 0 || repeatDelay <= 0))
            throw new IllegalArgumentException("Invalid keyboard repeat timing");
        this.scheduler = scheduler; this.repeatTimeout = repeatTimeout; this.repeatDelay = repeatDelay;
        this.onFailure = scheduler == null ? error -> { } : Objects.requireNonNull(onFailure);
    }

    public synchronized void accept(KeyEvent physical, int targetCode) throws Throwable {
        Objects.requireNonNull(physical, "Physical key event");
        if (closed || KeyboardMapping.isEvent(physical)) return;
        if (physical.getDeviceId() < 0) throw new IllegalArgumentException("A physical input device is required");
        Physical key = new Physical(physical.getDeviceId(), physical.getKeyCode());
        Press press = presses.get(key);
        if (physical.getAction() == KeyEvent.ACTION_DOWN && physical.getRepeatCount() == 0) {
            Long previousDown = latestDown.get(key);
            if (previousDown != null && physical.getDownTime() <= previousDown) return;
            if (!ActionConfig.validKeyCode(targetCode)) throw new IllegalArgumentException("Invalid mapped key code");
            latestDown.put(key, physical.getDownTime());
            if (press != null) release(key, press, canceled(press.target));
            begin(key, physical, targetCode);
            return;
        }
        if (press == null || press.downTime != physical.getDownTime()) return;
        if (physical.getAction() == KeyEvent.ACTION_DOWN) {
            if (scheduler != null) return;
            if (physical.getRepeatCount() <= press.lastRepeat) return;
            press.lastRepeat = physical.getRepeatCount();
            if (key.equals(press.target.repeatingOwner())) sink.send(mapped(physical, press.target));
        } else if (physical.getAction() == KeyEvent.ACTION_UP) {
            release(key, press, mapped(physical, press.target));
        }
    }

    private void begin(Physical key, KeyEvent physical, int code) throws Throwable {
        Target target = new Target(code);
        Held group = held.get(target);
        // Failed UPs stay owned until cleanup succeeds; never place a new DOWN over one.
        if (group != null && group.owners.isEmpty()) {
            finishRelease(group, canceled(group));
            group = null;
        }
        if (group != null) {
            group.owners.add(key);
            group.devices.add(key.device);
            presses.put(key, new Press(physical.getDownTime(), group));
            return;
        }
        KeyEvent down = new KeyEvent(physical.getDownTime(), physical.getEventTime(), KeyEvent.ACTION_DOWN,
                code, physical.getRepeatCount(), physical.getMetaState(), KeyCharacterMap.VIRTUAL_KEYBOARD,
                KeyboardMapping.scanCode(code), physical.getFlags(), InputDevice.SOURCE_KEYBOARD);
        group = new Held(target, down);
        group.owners.add(key);
        group.devices.add(key.device);
        Press next = new Press(physical.getDownTime(), group);
        presses.put(key, next); held.put(target, group);
        try {
            sink.send(new KeyEvent(down));
            if (scheduler != null && !closed && held.get(target) == group && !group.owners.isEmpty()) {
                Held scheduled = group;
                group.timer = () -> repeat(scheduled);
                scheduler.post(group.timer, Math.max(0, repeatTimeout - Math.max(0, clock.getAsLong() - down.getDownTime())));
            }
        }
        catch (Throwable failure) {
            try { release(key, next, canceled(group)); }
            catch (Throwable cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    private static KeyEvent mapped(KeyEvent physical, Held group) {
        return new KeyEvent(group.down.getDownTime(), physical.getEventTime(), physical.getAction(),
                group.key.code, physical.getRepeatCount(), physical.getMetaState(), KeyCharacterMap.VIRTUAL_KEYBOARD,
                group.down.getScanCode(), physical.getFlags(), InputDevice.SOURCE_KEYBOARD);
    }
    private KeyEvent canceled(Held group) {
        return new KeyEvent(group.down.getDownTime(), Math.max(group.down.getEventTime(), clock.getAsLong()),
                KeyEvent.ACTION_UP, group.key.code, 0, group.down.getMetaState(), KeyCharacterMap.VIRTUAL_KEYBOARD,
                group.down.getScanCode(), group.down.getFlags() | KeyEvent.FLAG_CANCELED,
                InputDevice.SOURCE_KEYBOARD);
    }
    private void release(Physical key, Press press, KeyEvent up) throws Throwable {
        presses.remove(key, press);
        press.target.owners.remove(key);
        if (press.target.owners.isEmpty()) finishRelease(press.target, up);
    }
    private void finishRelease(Held group, KeyEvent up) throws Throwable {
        Throwable failure = null;
        if (group.timer != null) {
            Runnable task = group.timer;
            group.timer = null;
            try { scheduler.remove(task); } catch (Throwable error) { failure = error; }
        }
        try { sink.send(up); held.remove(group.key, group); }
        catch (Throwable error) {
            if (failure == null) failure = error; else if (failure != error) failure.addSuppressed(error);
        }
        if (failure != null) throw failure;
    }
    private synchronized void repeat(Held group) {
        if (closed || group.timer == null || group.owners.isEmpty() || held.get(group.key) != group) return;
        int count = group.repeats + 1;
        int flags = group.down.getFlags() & ~KeyEvent.FLAG_LONG_PRESS;
        if (count == 1) flags |= KeyEvent.FLAG_LONG_PRESS;
        KeyEvent event = new KeyEvent(group.down.getDownTime(), Math.max(group.down.getEventTime(), clock.getAsLong()),
                KeyEvent.ACTION_DOWN, group.key.code, count, group.down.getMetaState(), KeyCharacterMap.VIRTUAL_KEYBOARD,
                group.down.getScanCode(), flags, InputDevice.SOURCE_KEYBOARD);
        try {
            sink.send(event); group.repeats = count;
            if (!closed && group.timer != null && !group.owners.isEmpty() && held.get(group.key) == group)
                scheduler.post(group.timer, repeatDelay);
        } catch (Throwable failure) {
            presses.entrySet().removeIf(entry -> entry.getValue().target == group);
            group.owners.clear();
            try { finishRelease(group, canceled(group)); }
            catch (Throwable cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
            try { onFailure.accept(failure); }
            catch (Throwable reporting) { if (reporting != failure) failure.addSuppressed(reporting); }
        }
    }

    public synchronized void forget(int device) throws Throwable {
        presses.keySet().removeIf(key -> key.device == device);
        latestDown.keySet().removeIf(key -> key.device == device);
        Throwable failure = null;
        for (Held group : new ArrayList<>(held.values())) {
            if (!group.devices.contains(device)) continue;
            group.owners.removeIf(key -> key.device == device);
            if (!group.owners.isEmpty()) continue;
            try { finishRelease(group, canceled(group)); }
            catch (Throwable error) {
                if (failure == null) failure = error; else if (failure != error) failure.addSuppressed(error);
            }
        }
        if (failure != null) throw failure;
    }
    public synchronized boolean hasHeldKeys() { return !held.isEmpty(); }
    public synchronized void close() throws Throwable {
        closed = true; presses.clear(); latestDown.clear();
        Throwable failure = null;
        for (Held group : new ArrayList<>(held.values())) {
            group.owners.clear();
            try { finishRelease(group, canceled(group)); }
            catch (Throwable error) {
                if (failure == null) failure = error; else if (failure != error) failure.addSuppressed(error);
            }
        }
        if (failure != null) throw failure;
    }
}
