package cc.star0.xposed.hyperstylus.runtime.keys;

import static org.junit.Assert.*;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.KeyCharacterMap;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class FullKeyDispatcherTest {
    private static final int DEVICE = 53, FOCUS = 193, UPPER = KeyEvent.KEYCODE_PAGE_UP;
    private static final class Queue implements FullKeyDispatcher.Sink {
        final List<KeyEvent> events = new ArrayList<>();
        final Set<Integer> failedDown = new HashSet<>(), failedUp = new HashSet<>();
        @Override public void send(KeyEvent event) {
            events.add(new KeyEvent(event));
            Set<Integer> failures = event.getAction() == KeyEvent.ACTION_DOWN ? failedDown : failedUp;
            if (failures.contains(event.getKeyCode())) throw new IllegalStateException("Rejected " + event.getAction() + ":" + event.getKeyCode());
        }
    }
    private static FullKeyDispatcher dispatcher(Queue queue) { return new FullKeyDispatcher(queue, () -> 10_000L); }
    private static final class ManualScheduler implements FullKeyDispatcher.Scheduler, LongSupplier {
        long now = 100;
        final Map<Runnable, Long> tasks = new LinkedHashMap<>();
        @Override public long getAsLong() { return now; }
        @Override public void post(Runnable task, long delay) { tasks.put(task, now + delay); }
        @Override public void remove(Runnable task) { tasks.remove(task); }
        void advance(long time) {
            for (;;) {
                Map.Entry<Runnable, Long> next = tasks.entrySet().stream()
                        .min(Map.Entry.comparingByValue()).orElse(null);
                if (next == null || next.getValue() > time) break;
                now = next.getValue(); Runnable task = next.getKey(); tasks.remove(task); task.run();
            }
            now = time;
        }
    }
    private static FullKeyDispatcher repeating(Queue queue, ManualScheduler timer, List<Throwable> failures) {
        return new FullKeyDispatcher(queue, timer, timer, 500, 50, failures::add);
    }
    private static KeyEvent physical(int device, int code, long down, long time, int action, int repeat, int meta, int flags) {
        return new KeyEvent(down, time, action, code, repeat, meta, device, 27, flags,
                InputDevice.SOURCE_STYLUS | InputDevice.SOURCE_KEYBOARD);
    }
    private static KeyEvent edge(int code, long down, long time, int action) {
        return physical(DEVICE, code, down, time, action, 0, 0, 0);
    }

    @Test public void downIsImmediateAndHeldUntilTheMatchingPhysicalUpWithItsMetadata() throws Throwable {
        Queue queue = new Queue(); FullKeyDispatcher dispatcher = dispatcher(queue);
        KeyEvent down = physical(DEVICE, FOCUS, 100, 101, KeyEvent.ACTION_DOWN, 0,
                KeyEvent.META_CTRL_ON, KeyEvent.FLAG_FROM_SYSTEM);
        dispatcher.accept(down, KeyEvent.KEYCODE_VOLUME_UP);
        assertEquals("DOWN does not wait for a click or long-press decision", 1, queue.events.size());
        KeyEvent mapped = queue.events.get(0);
        assertEquals(KeyEvent.ACTION_DOWN, mapped.getAction());
        assertEquals(KeyEvent.KEYCODE_VOLUME_UP, mapped.getKeyCode());
        assertEquals(100, mapped.getDownTime()); assertEquals(101, mapped.getEventTime());
        assertEquals(KeyCharacterMap.VIRTUAL_KEYBOARD, mapped.getDeviceId());
        assertEquals(115, mapped.getScanCode());
        assertEquals(InputDevice.SOURCE_KEYBOARD, mapped.getSource());
        assertEquals(KeyEvent.META_CTRL_ON, mapped.getMetaState());
        assertEquals(KeyEvent.FLAG_FROM_SYSTEM, mapped.getFlags());

        dispatcher.accept(physical(DEVICE, FOCUS, 100, 900, KeyEvent.ACTION_UP, 0,
                KeyEvent.META_ALT_ON, KeyEvent.FLAG_CANCELED), KeyEvent.KEYCODE_VOLUME_DOWN);
        assertEquals(2, queue.events.size());
        mapped = queue.events.get(1);
        assertEquals(KeyEvent.ACTION_UP, mapped.getAction());
        assertEquals("The target remains the one selected at DOWN", KeyEvent.KEYCODE_VOLUME_UP, mapped.getKeyCode());
        assertEquals(100, mapped.getDownTime()); assertEquals(900, mapped.getEventTime());
        assertEquals(KeyEvent.META_ALT_ON, mapped.getMetaState()); assertTrue(mapped.isCanceled());
        assertEquals("The native event remains untouched", FOCUS, down.getKeyCode());
        assertEquals(27, down.getScanCode());
    }

    @Test public void nativeRepeatCountsTimesAndLongPressFlagsAreForwardedOnce() throws Throwable {
        Queue queue = new Queue(); FullKeyDispatcher dispatcher = dispatcher(queue);
        dispatcher.accept(edge(FOCUS, 100, 100, KeyEvent.ACTION_DOWN), KeyEvent.KEYCODE_DPAD_DOWN);
        KeyEvent first = physical(DEVICE, FOCUS, 100, 650, KeyEvent.ACTION_DOWN, 1,
                KeyEvent.META_SHIFT_ON, KeyEvent.FLAG_LONG_PRESS);
        dispatcher.accept(first, KeyEvent.KEYCODE_DPAD_UP);
        dispatcher.accept(first, KeyEvent.KEYCODE_DPAD_UP);
        dispatcher.accept(physical(DEVICE, FOCUS, 100, 700, KeyEvent.ACTION_DOWN, 2, 0, 0), KeyEvent.KEYCODE_DPAD_UP);
        dispatcher.accept(edge(FOCUS, 100, 100, KeyEvent.ACTION_DOWN), KeyEvent.KEYCODE_DPAD_UP);
        dispatcher.accept(edge(FOCUS, 100, 750, KeyEvent.ACTION_UP), KeyEvent.KEYCODE_DPAD_UP);
        assertEquals(List.of(0, 1, 2, 0), queue.events.stream().map(KeyEvent::getRepeatCount).toList());
        assertEquals(List.of(100L, 650L, 700L, 750L), queue.events.stream().map(KeyEvent::getEventTime).toList());
        assertTrue((queue.events.get(1).getFlags() & KeyEvent.FLAG_LONG_PRESS) != 0);
        assertEquals(KeyEvent.META_SHIFT_ON, queue.events.get(1).getMetaState());
        assertTrue(queue.events.stream().allMatch(event -> event.getKeyCode() == KeyEvent.KEYCODE_DPAD_DOWN));
    }

    @Test public void orphanedAndLateEventsCannotReleaseAnotherPressOrRestartAnEndedOne() throws Throwable {
        Queue queue = new Queue(); FullKeyDispatcher dispatcher = dispatcher(queue);
        dispatcher.accept(edge(FOCUS, 100, 200, KeyEvent.ACTION_UP), KeyEvent.KEYCODE_SPACE);
        dispatcher.accept(physical(DEVICE, FOCUS, 100, 300, KeyEvent.ACTION_DOWN, 1, 0, 0), KeyEvent.KEYCODE_SPACE);
        assertTrue(queue.events.isEmpty());
        dispatcher.accept(edge(FOCUS, 200, 200, KeyEvent.ACTION_DOWN), KeyEvent.KEYCODE_SPACE);
        dispatcher.accept(edge(FOCUS, 100, 300, KeyEvent.ACTION_UP), KeyEvent.KEYCODE_SPACE);
        dispatcher.accept(edge(FOCUS, 100, 100, KeyEvent.ACTION_DOWN), KeyEvent.KEYCODE_SPACE);
        assertEquals(1, queue.events.size());
        dispatcher.accept(edge(FOCUS, 200, 400, KeyEvent.ACTION_UP), KeyEvent.KEYCODE_SPACE);
        dispatcher.accept(edge(FOCUS, 200, 200, KeyEvent.ACTION_DOWN), KeyEvent.KEYCODE_SPACE);
        dispatcher.accept(edge(FOCUS, 200, 400, KeyEvent.ACTION_UP), KeyEvent.KEYCODE_SPACE);
        dispatcher.close();
        assertEquals(2, queue.events.size());
    }

    @Test public void twoPhysicalButtonsSharingATargetKeepItHeldUntilTheirLastUp() throws Throwable {
        Queue queue = new Queue(); FullKeyDispatcher dispatcher = dispatcher(queue);
        dispatcher.accept(edge(FOCUS, 100, 100, KeyEvent.ACTION_DOWN), KeyEvent.KEYCODE_SHIFT_LEFT);
        dispatcher.accept(edge(UPPER, 120, 120, KeyEvent.ACTION_DOWN), KeyEvent.KEYCODE_SHIFT_LEFT);
        dispatcher.accept(edge(FOCUS, 100, 200, KeyEvent.ACTION_UP), KeyEvent.KEYCODE_SHIFT_LEFT);
        assertEquals("Releasing one source must not release a target still held by another", 1, queue.events.size());
        dispatcher.accept(edge(UPPER, 120, 300, KeyEvent.ACTION_UP), KeyEvent.KEYCODE_SHIFT_LEFT);
        assertEquals(2, queue.events.size());
        assertEquals(100, queue.events.get(1).getDownTime());
        assertEquals(300, queue.events.get(1).getEventTime());
    }

    @Test public void sharedTargetUsesOneRepeatOwnerAndTransfersItWhenThatOwnerLeaves() throws Throwable {
        Queue queue = new Queue(); FullKeyDispatcher dispatcher = dispatcher(queue);
        dispatcher.accept(edge(FOCUS, 100, 100, KeyEvent.ACTION_DOWN), KeyEvent.KEYCODE_DPAD_RIGHT);
        dispatcher.accept(edge(UPPER, 120, 120, KeyEvent.ACTION_DOWN), KeyEvent.KEYCODE_DPAD_RIGHT);
        dispatcher.accept(physical(DEVICE, FOCUS, 100, 650, KeyEvent.ACTION_DOWN, 1, 0, KeyEvent.FLAG_LONG_PRESS), KeyEvent.KEYCODE_DPAD_RIGHT);
        dispatcher.accept(physical(DEVICE, UPPER, 120, 670, KeyEvent.ACTION_DOWN, 1, 0, KeyEvent.FLAG_LONG_PRESS), KeyEvent.KEYCODE_DPAD_RIGHT);
        dispatcher.accept(edge(FOCUS, 100, 700, KeyEvent.ACTION_UP), KeyEvent.KEYCODE_DPAD_RIGHT);
        dispatcher.accept(physical(DEVICE, UPPER, 120, 720, KeyEvent.ACTION_DOWN, 2, 0, 0), KeyEvent.KEYCODE_DPAD_RIGHT);
        dispatcher.accept(edge(UPPER, 120, 800, KeyEvent.ACTION_UP), KeyEvent.KEYCODE_DPAD_RIGHT);
        assertEquals(List.of(0, 1, 2, 0), queue.events.stream().map(KeyEvent::getRepeatCount).toList());
        assertTrue(queue.events.stream().allMatch(event -> event.getDownTime() == 100));
    }

    @Test public void physicalDevicesKeepIndependentOwnershipOfSharedVirtualTargets() throws Throwable {
        Queue queue = new Queue(); FullKeyDispatcher dispatcher = dispatcher(queue);
        dispatcher.accept(edge(FOCUS, 100, 100, KeyEvent.ACTION_DOWN), KeyEvent.KEYCODE_A);
        dispatcher.accept(edge(UPPER, 120, 120, KeyEvent.ACTION_DOWN), KeyEvent.KEYCODE_B);
        dispatcher.accept(physical(54, FOCUS, 140, 140, KeyEvent.ACTION_DOWN, 0, 0, 0), KeyEvent.KEYCODE_A);
        dispatcher.accept(edge(FOCUS, 100, 200, KeyEvent.ACTION_UP), KeyEvent.KEYCODE_B);
        assertEquals("The other pen still holds A", 2, queue.events.size());
        dispatcher.accept(physical(54, FOCUS, 140, 220, KeyEvent.ACTION_UP, 0, 0, 0), KeyEvent.KEYCODE_B);
        dispatcher.accept(edge(UPPER, 120, 240, KeyEvent.ACTION_UP), KeyEvent.KEYCODE_A);
        assertTrue(queue.events.stream().allMatch(event -> event.getDeviceId() == KeyCharacterMap.VIRTUAL_KEYBOARD));
        assertEquals(List.of(KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_B, KeyEvent.KEYCODE_A,
                KeyEvent.KEYCODE_B), queue.events.stream().map(KeyEvent::getKeyCode).toList());
    }

    @Test public void aReplacementDownCancelsItsPreviousPressAndIgnoresThatPressesLateUp() throws Throwable {
        Queue queue = new Queue(); FullKeyDispatcher dispatcher = dispatcher(queue);
        dispatcher.accept(edge(FOCUS, 100, 100, KeyEvent.ACTION_DOWN), KeyEvent.KEYCODE_A);
        dispatcher.accept(edge(FOCUS, 200, 200, KeyEvent.ACTION_DOWN), KeyEvent.KEYCODE_B);
        assertEquals(3, queue.events.size());
        assertTrue(queue.events.get(1).isCanceled());
        dispatcher.accept(edge(FOCUS, 100, 250, KeyEvent.ACTION_UP), KeyEvent.KEYCODE_A);
        dispatcher.accept(edge(FOCUS, 200, 300, KeyEvent.ACTION_UP), KeyEvent.KEYCODE_A);
        assertEquals(4, queue.events.size());
        assertEquals(KeyEvent.KEYCODE_B, queue.events.get(3).getKeyCode());
    }

    @Test public void aFailedDownAttemptsCanceledUpAndDoesNotReviveFromRepeats() throws Throwable {
        Queue queue = new Queue(); FullKeyDispatcher dispatcher = dispatcher(queue);
        queue.failedDown.add(KeyEvent.KEYCODE_A);
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> dispatcher.accept(edge(FOCUS, 100, 100, KeyEvent.ACTION_DOWN), KeyEvent.KEYCODE_A));
        assertEquals("Rejected 0:" + KeyEvent.KEYCODE_A, failure.getMessage());
        assertEquals(2, queue.events.size()); assertTrue(queue.events.get(1).isCanceled());
        queue.failedDown.clear();
        dispatcher.accept(physical(DEVICE, FOCUS, 100, 650, KeyEvent.ACTION_DOWN, 1, 0, 0), KeyEvent.KEYCODE_A);
        dispatcher.accept(edge(FOCUS, 100, 700, KeyEvent.ACTION_UP), KeyEvent.KEYCODE_A);
        assertEquals(2, queue.events.size());
        dispatcher.accept(edge(FOCUS, 800, 800, KeyEvent.ACTION_DOWN), KeyEvent.KEYCODE_B);
        dispatcher.accept(edge(FOCUS, 800, 900, KeyEvent.ACTION_UP), KeyEvent.KEYCODE_A);
        assertEquals(4, queue.events.size());
    }

    @Test public void failedCleanupIsSuppressedOnTheDownFailureAndRetriedAtClose() throws Throwable {
        Queue queue = new Queue(); FullKeyDispatcher dispatcher = dispatcher(queue);
        queue.failedDown.add(KeyEvent.KEYCODE_A); queue.failedUp.add(KeyEvent.KEYCODE_A);
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> dispatcher.accept(edge(FOCUS, 100, 100, KeyEvent.ACTION_DOWN), KeyEvent.KEYCODE_A));
        assertEquals(1, failure.getSuppressed().length);
        queue.failedUp.clear(); dispatcher.close(); dispatcher.close();
        assertEquals(3, queue.events.size()); assertTrue(queue.events.get(2).isCanceled());
    }

    @Test public void anUpQueueFailureRemainsAvailableForRetirementCleanup() throws Throwable {
        Queue queue = new Queue(); FullKeyDispatcher dispatcher = dispatcher(queue);
        dispatcher.accept(edge(FOCUS, 100, 100, KeyEvent.ACTION_DOWN), KeyEvent.KEYCODE_A);
        queue.failedUp.add(KeyEvent.KEYCODE_A);
        assertThrows(IllegalStateException.class,
                () -> dispatcher.accept(edge(FOCUS, 100, 200, KeyEvent.ACTION_UP), KeyEvent.KEYCODE_A));
        queue.failedUp.clear(); dispatcher.close();
        assertEquals(3, queue.events.size()); assertTrue(queue.events.get(2).isCanceled());
    }

    @Test public void forgettingADeviceReleasesAllItsTargetsAndKeepsOtherDevicesHeld() throws Throwable {
        Queue queue = new Queue(); FullKeyDispatcher dispatcher = dispatcher(queue);
        dispatcher.accept(edge(FOCUS, 100, 100, KeyEvent.ACTION_DOWN), KeyEvent.KEYCODE_A);
        dispatcher.accept(edge(UPPER, 120, 120, KeyEvent.ACTION_DOWN), KeyEvent.KEYCODE_B);
        dispatcher.accept(physical(54, FOCUS, 140, 140, KeyEvent.ACTION_DOWN, 0, 0, 0), KeyEvent.KEYCODE_C);
        dispatcher.forget(DEVICE);
        assertEquals(5, queue.events.size());
        assertTrue(queue.events.get(3).isCanceled()); assertTrue(queue.events.get(4).isCanceled());
        assertEquals(KeyCharacterMap.VIRTUAL_KEYBOARD, queue.events.get(3).getDeviceId());
        assertEquals(KeyCharacterMap.VIRTUAL_KEYBOARD, queue.events.get(4).getDeviceId());
        dispatcher.accept(physical(54, FOCUS, 140, 220, KeyEvent.ACTION_UP, 0, 0, 0), KeyEvent.KEYCODE_A);
        assertEquals(KeyEvent.KEYCODE_C, queue.events.get(5).getKeyCode());
        dispatcher.forget(DEVICE); dispatcher.close();
        assertEquals(6, queue.events.size());
    }

    @Test public void closeAttemptsEveryHeldTargetEvenIfSomeReleasesFail() throws Throwable {
        Queue queue = new Queue(); FullKeyDispatcher dispatcher = dispatcher(queue);
        dispatcher.accept(edge(FOCUS, 100, 100, KeyEvent.ACTION_DOWN), KeyEvent.KEYCODE_A);
        dispatcher.accept(edge(UPPER, 120, 120, KeyEvent.ACTION_DOWN), KeyEvent.KEYCODE_B);
        queue.failedUp.add(KeyEvent.KEYCODE_A);
        assertThrows(IllegalStateException.class, dispatcher::close);
        assertEquals(4, queue.events.size());
        assertTrue(queue.events.get(2).isCanceled()); assertTrue(queue.events.get(3).isCanceled());
        assertEquals(List.of(KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_B), queue.events.subList(2, 4).stream().map(KeyEvent::getKeyCode).toList());
        queue.failedUp.clear(); dispatcher.close(); dispatcher.close();
        dispatcher.accept(edge(FOCUS, 200, 200, KeyEvent.ACTION_DOWN), KeyEvent.KEYCODE_A);
        assertEquals(5, queue.events.size());
    }

    @Test public void invalidTargetsAndSyntheticEventsNeverReachTheQueue() {
        Queue queue = new Queue(); FullKeyDispatcher dispatcher = dispatcher(queue);
        assertThrows(IllegalArgumentException.class,
                () -> dispatcher.accept(edge(FOCUS, 100, 100, KeyEvent.ACTION_DOWN), KeyEvent.KEYCODE_UNKNOWN));
        assertThrows(IllegalArgumentException.class,
                () -> dispatcher.accept(physical(-1, FOCUS, 100, 100, KeyEvent.ACTION_DOWN, 0, 0, 0), KeyEvent.KEYCODE_A));
        KeyEvent synthetic = new KeyEvent(100, 100, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, 0, 0,
                android.view.KeyCharacterMap.VIRTUAL_KEYBOARD, KeyboardMapping.scanCode(KeyEvent.KEYCODE_A),
                0, InputDevice.SOURCE_KEYBOARD);
        assertDoesNotThrow(dispatcher, synthetic);
        assertTrue(queue.events.isEmpty());
    }

    @Test public void keyboardRepeatStartsAtTheSystemTimeoutAndKeepsTheSystemDelay() throws Throwable {
        Queue queue = new Queue(); ManualScheduler timer = new ManualScheduler(); List<Throwable> failures = new ArrayList<>();
        FullKeyDispatcher dispatcher = repeating(queue, timer, failures);
        dispatcher.accept(physical(DEVICE, FOCUS, 100, 100, KeyEvent.ACTION_DOWN, 0,
                KeyEvent.META_CTRL_ON, KeyEvent.FLAG_FROM_SYSTEM), KeyEvent.KEYCODE_DPAD_LEFT);
        timer.advance(599); assertEquals(1, queue.events.size());
        timer.advance(600); assertEquals(2, queue.events.size());
        timer.advance(649); assertEquals(2, queue.events.size());
        timer.advance(650); assertEquals(3, queue.events.size());
        timer.advance(700); assertEquals(4, queue.events.size());
        assertEquals(List.of(0, 1, 2, 3), queue.events.stream().map(KeyEvent::getRepeatCount).toList());
        assertEquals(List.of(100L, 600L, 650L, 700L), queue.events.stream().map(KeyEvent::getEventTime).toList());
        assertTrue((queue.events.get(1).getFlags() & KeyEvent.FLAG_LONG_PRESS) != 0);
        assertFalse((queue.events.get(2).getFlags() & KeyEvent.FLAG_LONG_PRESS) != 0);
        assertTrue(queue.events.stream().allMatch(event -> event.getDownTime() == 100
                && event.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT && event.getDeviceId() == KeyCharacterMap.VIRTUAL_KEYBOARD
                && event.getMetaState() == KeyEvent.META_CTRL_ON
                && (event.getFlags() & KeyEvent.FLAG_FROM_SYSTEM) != 0));
        dispatcher.accept(edge(FOCUS, 100, 725, KeyEvent.ACTION_UP), KeyEvent.KEYCODE_DPAD_RIGHT);
        timer.advance(1_000); assertEquals(5, queue.events.size());
        assertEquals(KeyEvent.ACTION_UP, queue.events.get(4).getAction());
        assertTrue(failures.isEmpty());
    }

    @Test public void delayedDeliveryKeepsRepeatRelativeToPhysicalDownAndIgnoresPhysicalRepeatCopies() throws Throwable {
        Queue queue = new Queue(); ManualScheduler timer = new ManualScheduler(); List<Throwable> failures = new ArrayList<>();
        timer.now = 200;
        FullKeyDispatcher dispatcher = repeating(queue, timer, failures);
        dispatcher.accept(edge(FOCUS, 100, 100, KeyEvent.ACTION_DOWN), KeyEvent.KEYCODE_A);
        dispatcher.accept(edge(FOCUS, 100, 300, KeyEvent.ACTION_DOWN), KeyEvent.KEYCODE_B);
        dispatcher.accept(physical(DEVICE, FOCUS, 100, 400, KeyEvent.ACTION_DOWN, 7, 0, KeyEvent.FLAG_LONG_PRESS), KeyEvent.KEYCODE_B);
        timer.advance(599); assertEquals(1, queue.events.size());
        timer.advance(600); assertEquals(2, queue.events.size());
        assertEquals(1, queue.events.get(1).getRepeatCount());
        assertEquals(KeyEvent.KEYCODE_A, queue.events.get(1).getKeyCode());
        dispatcher.close(); timer.advance(1_000);
        assertEquals(3, queue.events.size());
    }

    @Test public void sameTargetOwnersShareOneTimerAndItsLastReleaseStopsRepeating() throws Throwable {
        Queue queue = new Queue(); ManualScheduler timer = new ManualScheduler(); List<Throwable> failures = new ArrayList<>();
        FullKeyDispatcher dispatcher = repeating(queue, timer, failures);
        dispatcher.accept(edge(FOCUS, 100, 100, KeyEvent.ACTION_DOWN), KeyEvent.KEYCODE_DPAD_DOWN);
        dispatcher.accept(edge(UPPER, 150, 150, KeyEvent.ACTION_DOWN), KeyEvent.KEYCODE_DPAD_DOWN);
        timer.advance(600); assertEquals(2, queue.events.size());
        dispatcher.accept(edge(FOCUS, 100, 620, KeyEvent.ACTION_UP), KeyEvent.KEYCODE_DPAD_DOWN);
        timer.advance(650); assertEquals(3, queue.events.size());
        dispatcher.accept(edge(UPPER, 150, 675, KeyEvent.ACTION_UP), KeyEvent.KEYCODE_DPAD_DOWN);
        timer.advance(1_000);
        assertEquals(4, queue.events.size());
        assertEquals(List.of(0, 1, 2, 0), queue.events.stream().map(KeyEvent::getRepeatCount).toList());
        assertTrue(queue.events.stream().allMatch(event -> event.getDownTime() == 100));
    }

    @Test public void differentTargetTimersRemainIndependentAndDeviceRemovalCancelsOnlyThatDevice() throws Throwable {
        Queue queue = new Queue(); ManualScheduler timer = new ManualScheduler(); List<Throwable> failures = new ArrayList<>();
        FullKeyDispatcher dispatcher = repeating(queue, timer, failures);
        dispatcher.accept(edge(FOCUS, 100, 100, KeyEvent.ACTION_DOWN), KeyEvent.KEYCODE_A);
        dispatcher.accept(physical(54, FOCUS, 120, 120, KeyEvent.ACTION_DOWN, 0, 0, 0), KeyEvent.KEYCODE_B);
        timer.advance(600);
        assertEquals(KeyEvent.KEYCODE_A, queue.events.get(2).getKeyCode());
        timer.advance(620);
        assertEquals(KeyEvent.KEYCODE_B, queue.events.get(3).getKeyCode());
        dispatcher.forget(DEVICE);
        int afterRemoval = queue.events.size();
        timer.advance(720);
        assertTrue(queue.events.subList(afterRemoval, queue.events.size()).stream()
                .allMatch(event -> event.getKeyCode() == KeyEvent.KEYCODE_B));
        assertEquals(List.of(2, 3), queue.events.subList(afterRemoval, queue.events.size()).stream().map(KeyEvent::getRepeatCount).toList());
        dispatcher.close(); int afterClose = queue.events.size();
        timer.advance(5_000); assertEquals(afterClose, queue.events.size());
    }

    @Test public void timerFailureCancelsItsTargetAndReportsTheCauseWhileOtherTargetsKeepRepeating() throws Throwable {
        Queue queue = new Queue(); ManualScheduler timer = new ManualScheduler(); List<Throwable> failures = new ArrayList<>();
        FullKeyDispatcher dispatcher = repeating(queue, timer, failures);
        dispatcher.accept(edge(FOCUS, 100, 100, KeyEvent.ACTION_DOWN), KeyEvent.KEYCODE_A);
        dispatcher.accept(edge(UPPER, 100, 100, KeyEvent.ACTION_DOWN), KeyEvent.KEYCODE_B);
        queue.failedDown.add(KeyEvent.KEYCODE_A);
        timer.advance(600);
        assertEquals(1, failures.size());
        assertEquals("Rejected 0:" + KeyEvent.KEYCODE_A, failures.get(0).getMessage());
        assertEquals(KeyEvent.ACTION_UP, queue.events.get(3).getAction()); assertTrue(queue.events.get(3).isCanceled());
        int afterFailure = queue.events.size(); timer.advance(700);
        assertTrue(queue.events.subList(afterFailure, queue.events.size()).stream().allMatch(event -> event.getKeyCode() == KeyEvent.KEYCODE_B));
        dispatcher.accept(edge(FOCUS, 100, 800, KeyEvent.ACTION_UP), KeyEvent.KEYCODE_A);
        dispatcher.accept(edge(UPPER, 100, 800, KeyEvent.ACTION_UP), KeyEvent.KEYCODE_B);
        int afterUp = queue.events.size(); timer.advance(5_000);
        assertEquals(afterUp, queue.events.size());
    }

    @Test public void removingOnePenKeepsTheOtherPensSharedKeyboardTargetHeldAndRepeating() throws Throwable {
        Queue queue = new Queue(); ManualScheduler timer = new ManualScheduler(); List<Throwable> failures = new ArrayList<>();
        FullKeyDispatcher dispatcher = repeating(queue, timer, failures);
        dispatcher.accept(edge(FOCUS, 100, 100, KeyEvent.ACTION_DOWN), KeyEvent.KEYCODE_5);
        dispatcher.accept(physical(54, FOCUS, 150, 150, KeyEvent.ACTION_DOWN, 0, 0, 0), KeyEvent.KEYCODE_5);
        dispatcher.forget(DEVICE);
        assertEquals(1, queue.events.size());
        timer.advance(650);
        assertEquals(List.of(0, 1, 2), queue.events.stream().map(KeyEvent::getRepeatCount).toList());
        dispatcher.forget(54);
        timer.advance(2_000);
        assertEquals(4, queue.events.size());
        assertTrue(queue.events.get(3).isCanceled());
        assertEquals(KeyEvent.ACTION_UP, queue.events.get(3).getAction());
        assertFalse(dispatcher.hasHeldKeys());
        assertTrue(failures.isEmpty());
    }

    @Test public void deviceCleanupCanRetryAnUnownedVirtualKeyAfterItsUpFailed() throws Throwable {
        Queue queue = new Queue(); FullKeyDispatcher dispatcher = dispatcher(queue);
        dispatcher.accept(edge(FOCUS, 100, 100, KeyEvent.ACTION_DOWN), KeyEvent.KEYCODE_5);
        queue.failedUp.add(KeyEvent.KEYCODE_5);
        assertThrows(IllegalStateException.class,
                () -> dispatcher.accept(edge(FOCUS, 100, 200, KeyEvent.ACTION_UP), KeyEvent.KEYCODE_5));
        dispatcher.forget(54);
        assertEquals(2, queue.events.size()); assertTrue(dispatcher.hasHeldKeys());
        queue.failedUp.clear(); dispatcher.forget(DEVICE);
        assertEquals(3, queue.events.size()); assertFalse(dispatcher.hasHeldKeys());
        assertTrue(queue.events.get(2).isCanceled());
    }

    @Test public void timerCleanupFailureIsReportedAndRetriedAtCloseWithoutAnotherRepeat() throws Throwable {
        Queue queue = new Queue(); ManualScheduler timer = new ManualScheduler(); List<Throwable> failures = new ArrayList<>();
        FullKeyDispatcher dispatcher = repeating(queue, timer, failures);
        dispatcher.accept(edge(FOCUS, 100, 100, KeyEvent.ACTION_DOWN), KeyEvent.KEYCODE_A);
        queue.failedDown.add(KeyEvent.KEYCODE_A); queue.failedUp.add(KeyEvent.KEYCODE_A);
        timer.advance(600);
        assertEquals(1, failures.size()); assertEquals(1, failures.get(0).getSuppressed().length);
        assertEquals(3, queue.events.size());
        timer.advance(1_000); assertEquals(3, queue.events.size());
        queue.failedUp.clear(); dispatcher.close(); timer.advance(5_000);
        assertEquals(4, queue.events.size()); assertTrue(queue.events.get(3).isCanceled());
    }

    @Test public void aQuickOrCanceledPhysicalUpCancelsThePendingFirstRepeat() throws Throwable {
        Queue queue = new Queue(); ManualScheduler timer = new ManualScheduler(); List<Throwable> failures = new ArrayList<>();
        FullKeyDispatcher dispatcher = repeating(queue, timer, failures);
        dispatcher.accept(edge(FOCUS, 100, 100, KeyEvent.ACTION_DOWN), KeyEvent.KEYCODE_A);
        dispatcher.accept(physical(DEVICE, FOCUS, 100, 200, KeyEvent.ACTION_UP, 0, 0, KeyEvent.FLAG_CANCELED), KeyEvent.KEYCODE_A);
        timer.advance(1_000);
        assertEquals(2, queue.events.size()); assertTrue(queue.events.get(1).isCanceled());
        assertTrue(failures.isEmpty());
    }

    @Test public void invalidRepeatTimingCannotCreateAStuckKey() {
        Queue queue = new Queue(); ManualScheduler timer = new ManualScheduler();
        assertThrows(IllegalArgumentException.class, () -> new FullKeyDispatcher(queue, timer, timer, -1, 50, error -> { }));
        assertThrows(IllegalArgumentException.class, () -> new FullKeyDispatcher(queue, timer, timer, 500, 0, error -> { }));
        assertTrue(queue.events.isEmpty());
    }
    private static void assertDoesNotThrow(FullKeyDispatcher dispatcher, KeyEvent event) {
        try { dispatcher.accept(event, KeyEvent.KEYCODE_B); }
        catch (Throwable failure) { throw new AssertionError(failure); }
    }
}
