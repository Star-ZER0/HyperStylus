package cc.star0.xposed.hyperstylus.runtime.keys;

import cc.star0.xposed.hyperstylus.config.ActionConfig;

import static org.junit.Assert.*;
import android.view.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class StylusActionDispatchTest {
    private static final class Receiver implements StylusActions.Sink {
        public final List<Integer> keys = new ArrayList<>();
        public final List<String> commands = new ArrayList<>();
        @Override public void key(int code) { keys.add(code); }
        @Override public void command(String action) { commands.add(action); }
    }

    @Test public void everyProActionReachesItsCommandInsteadOfUnboxingANullKey() throws Throwable {
        Receiver receiver = new Receiver();
        List<String> pro = List.of("pinch", "double_tap", "slide_up", "slide_down");
        for (String action : pro) StylusActions.dispatch(action, 0, false, receiver);
        assertEquals(pro, receiver.commands);
        assertTrue(receiver.keys.isEmpty());
    }

    @Test public void nonKeyboardSystemActionsAlsoReachTheCommandBranch() throws Throwable {
        Receiver receiver = new Receiver();
        List<String> actions = List.of("home", "recents", "partial_screenshot", "full_screenshot", "quick_note",
                "volume_up", "volume_down", "flashlight", "screen_off", "voice_assistant", "none");
        for (String action : actions) StylusActions.dispatch(action, 1, false, receiver);
        assertEquals(actions, receiver.commands);
        assertTrue(receiver.keys.isEmpty());
    }

    @Test public void keyboardBindingsKeepTheKeyboardRoute() throws Throwable {
        Receiver receiver = new Receiver();
        StylusActions.dispatch("dpad_up", 0, false, receiver);
        StylusActions.dispatch("back", 0, false, receiver);
        StylusActions.dispatch("keyboard:66", 0, false, receiver);
        assertEquals(List.of(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ENTER), receiver.keys);
        assertTrue(receiver.commands.isEmpty());
    }

    @Test public void defaultPageButtonsRetainSeparateShortAndLongActions() throws Throwable {
        Receiver receiver = new Receiver();
        StylusActions.dispatch(ActionConfig.DEFAULT, 1, false, receiver);
        StylusActions.dispatch(ActionConfig.DEFAULT, 2, false, receiver);
        StylusActions.dispatch(ActionConfig.DEFAULT, 1, true, receiver);
        StylusActions.dispatch(ActionConfig.DEFAULT, 2, true, receiver);
        assertEquals(List.of(KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN), receiver.keys);
        assertEquals(List.of("partial_screenshot", "quick_note"), receiver.commands);
    }

    @Test public void commandFailuresRemainVisibleToTheCallingEntry() {
        IllegalStateException failure = new IllegalStateException("native action failure");
        StylusActions.Sink receiver = new StylusActions.Sink() {
            @Override public void key(int code) { fail("Pro action must not become a keyboard key"); }
            @Override public void command(String action) { throw failure; }
        };
        assertSame(failure, assertThrows(IllegalStateException.class, () -> StylusActions.dispatch("pinch", 0, false, receiver)));
    }
}
