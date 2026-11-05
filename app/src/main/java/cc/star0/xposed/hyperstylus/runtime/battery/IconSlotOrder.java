package cc.star0.xposed.hyperstylus.runtime.battery;

import cc.star0.xposed.hyperstylus.common.Reflect;
import cc.star0.xposed.hyperstylus.module.FocusStylusModule;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Establish slot order before creation, or relocate only a holder-free slot after discovery. */
public final class IconSlotOrder {
    private static final String BLUETOOTH = "bluetooth";
    private IconSlotOrder() { }

    public static void install(FocusStylusModule module, ClassLoader loader) {
        Class<?> type = Reflect.find(loader, "com.android.systemui.statusbar.phone.ui.StatusBarIconList");
        if (type == null) return;
        try {
            Constructor<?> constructor = type.getDeclaredConstructor(String[].class);
            module.managedHook(constructor).intercept(chain -> {
                Object[] arguments = chain.getArgs().toArray();
                try {
                    if (arguments[0] instanceof String[] slots) arguments[0] = insertAfterBluetooth(slots);
                } catch (Throwable error) { module.error("Prepare initial icon slot order", error); }
                return chain.proceed(arguments);
            });
        } catch (Throwable error) { module.error("Hook current icon slot constructor", error); }
    }

    public static String[] insertAfterBluetooth(String[] original) {
        boolean hasBluetooth = false;
        for (String slot : original) {
            Objects.requireNonNull(slot, "slot");
            if (BLUETOOTH.equals(slot)) hasBluetooth = true;
        }
        if (!hasBluetooth) return original.clone();
        List<String> ordered = new ArrayList<>(original.length + 1);
        boolean inserted = false;
        for (String slot : original) {
            if (SystemUiController.SLOT.equals(slot)) continue;
            ordered.add(slot);
            if (!inserted && BLUETOOTH.equals(slot)) {
                ordered.add(SystemUiController.SLOT);
                inserted = true;
            }
        }
        return ordered.toArray(String[]::new);
    }

    public static boolean prepare(Object controller) throws Throwable {
        Object list = Reflect.field(controller, "mStatusBarIconList");
        Object storage = Reflect.field(list, "mSlots");
        if (!(storage instanceof ArrayList<?> slots)) return false;
        int bluetooth = indexOf(slots, BLUETOOTH);
        if (bluetooth < 0) return false;
        int stylus = indexOf(slots, SystemUiController.SLOT);
        if (stylus < 0) {
            insert(list);
            bluetooth = indexOf(slots, BLUETOOTH);
            stylus = indexOf(slots, SystemUiController.SLOT);
        }
        if (stylus < 0) return false;
        if (stylus == bluetooth + 1) return true;
        if (populated(slots.get(stylus))) {
            throw new IllegalStateException("Stylus slot already populated; restart SystemUI to reorder safely");
        }
        relocate(slots, stylus, bluetooth);
        return true;
    }

    private static void insert(Object list) throws Throwable {
        Reflect.call(list, "findOrInsertSlot", SystemUiController.SLOT);
    }

    private static int indexOf(List<?> slots, String name) throws Throwable {
        for (int index = 0; index < slots.size(); index++) {
            Object slot = slots.get(index);
            Object label = Reflect.field(slot, "mName");
            if (name.equals(label)) return index;
        }
        return -1;
    }

    private static boolean populated(Object slot) throws Throwable {
        return Boolean.TRUE.equals(Reflect.call(slot, "hasIconsInSlot"));
    }

    private static <T> void relocate(List<T> slots, int from, int bluetooth) {
        int afterBluetooth = bluetooth + 1;
        T emptySlot = slots.remove(from);
        if (from < afterBluetooth) afterBluetooth--;
        slots.add(afterBluetooth, emptySlot);
    }
}
