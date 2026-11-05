package cc.star0.xposed.hyperstylus.runtime.battery;


import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

/** Tests the current constructorless holder shape and safe ordering without vendor code execution. */
public class CurrentIconModelTest {
    private static final String ICON = "com.android.internal.statusbar.StatusBarIcon";
    private static final String HOLDER = "com.android.systemui.statusbar.phone.StatusBarIconHolder";
    private static final String NETWORK = "com.android.systemui.statusbar.policy.NetworkSpeedState";

    @Test public void constructorlessHolderReceivesAnIconAndZeroedNativeState() throws Throwable {
        Models models = models(false);
        Object icon = allocate(models.icon);
        IconHolderAdapter.Created created = IconHolderAdapter.create(models.holder, icon, CurrentIconModelTest::allocate);
        assertSame(icon, models.holder.getField("icon").get(created.holder));
        assertEquals(0, models.holder.getField("type").getInt(created.holder));
        assertEquals(0, models.holder.getField("tag").getInt(created.holder));
        assertNull(models.holder.getField("networkSpeedState").get(created.holder));
        assertEquals(0, models.holder.getDeclaredConstructors().length);
    }

    @Test public void allocationRejectsDifferentLayoutsOrWrongAllocatorResults() throws Throwable {
        Models wrongLayout = models(true);
        Object icon = allocate(wrongLayout.icon);
        try { IconHolderAdapter.create(wrongLayout.holder, icon, CurrentIconModelTest::allocate); fail("Different holder layouts must be rejected"); }
        catch (IllegalArgumentException expected) { assertNotNull(expected.getCause()); }
        Models current = models(false);
        try { IconHolderAdapter.create(current.holder, allocate(current.icon), type -> new Object()); fail("Wrong allocation result must be rejected"); }
        catch (InstantiationException expected) { assertTrue(expected.getMessage().contains("wrong holder")); }
    }

    @Test public void initialOrderingKeepsUnrelatedIconsAndAddsOneStylusSlot() {
        String stylus = SystemUiController.SLOT;
        assertArrayEquals(new String[]{"clock", "bluetooth", stylus, "wifi"},
                IconSlotOrder.insertAfterBluetooth(new String[]{stylus, "clock", "bluetooth", stylus, "wifi"}));
        String[] noBluetooth = {"clock", "wifi"};
        String[] copied = IconSlotOrder.insertAfterBluetooth(noBluetooth);
        assertArrayEquals(noBluetooth, copied);
        assertNotSame(noBluetooth, copied);
    }

    @Test public void lateRegistrationUsesTheCurrentControllerAndSlotMethods() throws Throwable {
        CurrentController controller = new CurrentController();
        controller.mStatusBarIconList.mSlots.add(new Slot("wifi"));
        controller.mStatusBarIconList.mSlots.add(new Slot("bluetooth"));
        assertTrue(IconSlotOrder.prepare(controller));
        assertEquals(List.of("wifi", "bluetooth", SystemUiController.SLOT),
                controller.mStatusBarIconList.mSlots.stream().map(slot -> slot.mName).toList());
    }

    @Test public void aPopulatedSlotCannotBeMovedAcrossExistingViewIndices() throws Throwable {
        CurrentController controller = new CurrentController();
        Slot stylus = new Slot(SystemUiController.SLOT);
        stylus.mHolder = new Object();
        controller.mStatusBarIconList.mSlots.add(stylus);
        controller.mStatusBarIconList.mSlots.add(new Slot("bluetooth"));
        try { IconSlotOrder.prepare(controller); fail("A populated slot cannot be relocated"); }
        catch (IllegalStateException expected) { assertSame(stylus, controller.mStatusBarIconList.mSlots.get(0)); }
    }

    public static final class CurrentController { public final CurrentList mStatusBarIconList = new CurrentList(); }
    public static final class CurrentList {
        public final ArrayList<Slot> mSlots = new ArrayList<>();
        public int findOrInsertSlot(String name) { mSlots.add(0, new Slot(name)); return 0; }
    }
    public static final class Slot {
        public final String mName;
        public Object mHolder;
        public final ArrayList<Object> mSubSlots = new ArrayList<>();
        public Slot(String name) { mName = name; }
        public boolean hasIconsInSlot() { return mHolder != null || !mSubSlots.isEmpty(); }
    }

    private record Models(Class<?> icon, Class<?> holder) { }
    private static Models models(boolean extraField) throws Exception {
        class ModelLoader extends ClassLoader {
            public ModelLoader() { super(CurrentIconModelTest.class.getClassLoader()); }
            public Class<?> define(String name, Map<String, String> fields) throws Exception {
                byte[] bytecode = classBytes(name, fields);
                return defineClass(name, bytecode, 0, bytecode.length);
            }
        }
        ModelLoader loader = new ModelLoader();
        Class<?> icon = loader.define(ICON, Map.of());
        loader.define(NETWORK, Map.of());
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("icon", "L" + ICON.replace('.', '/') + ";");
        fields.put("networkSpeedState", "L" + NETWORK.replace('.', '/') + ";");
        fields.put("type", "I"); fields.put("tag", "I");
        if (extraField) fields.put("unexpected", "I");
        return new Models(icon, loader.define(HOLDER, fields));
    }

    private static Object allocate(Class<?> type) throws Throwable {
        Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
        Field singleton = unsafeType.getDeclaredField("theUnsafe"); singleton.setAccessible(true);
        Object unsafe = singleton.get(null);
        return unsafeType.getMethod("allocateInstance", Class.class).invoke(unsafe, type);
    }

    /** Minimal JVM data classes deliberately contain no methods or constructors, matching this DEX shape. */
    private static byte[] classBytes(String name, Map<String, String> fields) throws Exception {
        String internal = name.replace('.', '/');
        LinkedHashSet<String> names = new LinkedHashSet<>();
        names.add(internal); names.add("java/lang/Object");
        fields.forEach((field, descriptor) -> { names.add(field); names.add(descriptor); });
        List<String> pool = new ArrayList<>(names);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);
        output.writeInt(0xcafebabe); output.writeShort(0); output.writeShort(61);
        output.writeShort(pool.size() + 3);
        for (String text : pool) { output.writeByte(1); output.writeUTF(text); }
        output.writeByte(7); output.writeShort(pool.indexOf(internal) + 1);
        output.writeByte(7); output.writeShort(pool.indexOf("java/lang/Object") + 1);
        output.writeShort(0x21); output.writeShort(pool.size() + 1); output.writeShort(pool.size() + 2);
        output.writeShort(0); output.writeShort(fields.size());
        for (Map.Entry<String, String> field : fields.entrySet()) {
            output.writeShort(1); output.writeShort(pool.indexOf(field.getKey()) + 1);
            output.writeShort(pool.indexOf(field.getValue()) + 1); output.writeShort(0);
        }
        output.writeShort(0); output.writeShort(0); output.flush();
        return bytes.toByteArray();
    }
}
