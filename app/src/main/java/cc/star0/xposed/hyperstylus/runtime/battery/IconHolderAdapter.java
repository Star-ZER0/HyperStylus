package cc.star0.xposed.hyperstylus.runtime.battery;


import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Set;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/** The supplied OTA uses a constructorless, four-field StatusBarIconHolder. */
public final class IconHolderAdapter {
    public interface SpecialAllocator { public Object allocate(Class<?> type) throws Throwable; }
    public static final class Created {
        public final Object holder;
        public final String route;
        public Created(Object holder, String route) { this.holder = holder; this.route = route; }
    }
    private static final String HOLDER = "com.android.systemui.statusbar.phone.StatusBarIconHolder";
    private static final Set<String> INSTANCE_FIELDS = Set.of("icon", "type", "tag", "networkSpeedState");
    private record Layout(Field icon, Field type, Field tag, Field networkSpeed) { }
    private static final Map<Class<?>, Boolean> CACHED_TYPES = Collections.synchronizedMap(new WeakHashMap<>());
    private static final ClassValue<Layout> LAYOUTS = new ClassValue<>() {
        @Override protected Layout computeValue(Class<?> type) {
            CACHED_TYPES.put(type, Boolean.TRUE);
            try {
                Set<String> fields = Arrays.stream(type.getDeclaredFields())
                        .filter(field -> !Modifier.isStatic(field.getModifiers()))
                        .map(Field::getName).collect(java.util.stream.Collectors.toSet());
                if (!HOLDER.equals(type.getName()) || type.getSuperclass() != Object.class
                        || type.isInterface() || Modifier.isAbstract(type.getModifiers())
                        || type.getDeclaredConstructors().length != 0 || !fields.equals(INSTANCE_FIELDS)) {
                    throw new NoSuchMethodException("Unexpected OTA holder shape: " + type.getName());
                }
                Field icon = type.getDeclaredField("icon"), kind = type.getDeclaredField("type");
                Field tag = type.getDeclaredField("tag"), network = type.getDeclaredField("networkSpeedState");
                if (!icon.getType().getName().equals("com.android.internal.statusbar.StatusBarIcon")
                        || kind.getType() != int.class || tag.getType() != int.class
                        || !network.getType().getName().equals("com.android.systemui.statusbar.policy.NetworkSpeedState")) {
                    throw new NoSuchFieldException("Unexpected OTA holder field types");
                }
                for (Field field : new Field[]{icon, kind, tag, network}) field.setAccessible(true);
                return new Layout(icon, kind, tag, network);
            } catch (ReflectiveOperationException error) {
                throw new IllegalArgumentException("Cannot use the current SystemUI icon holder", error);
            }
        }
    };
    private IconHolderAdapter() { }

    public static void clearCaches() {
        java.util.List<Class<?>> types;
        synchronized (CACHED_TYPES) {
            types = java.util.List.copyOf(CACHED_TYPES.keySet());
            CACHED_TYPES.clear();
        }
        types.forEach(LAYOUTS::remove);
    }

    public static Created create(Class<?> type, Object icon, SpecialAllocator allocator) throws Throwable {
        Layout layout = LAYOUTS.get(type);
        if (!layout.icon.getType().isInstance(icon)) throw new IllegalArgumentException("Expected a current StatusBarIcon");
        if (allocator == null) throw new IllegalArgumentException("API 102 holder allocator is required");
        Object holder = allocator.allocate(type);
        if (!type.isInstance(holder)) throw new InstantiationException("API 102 allocated the wrong holder class");
        layout.icon.set(holder, icon);
        layout.type.setInt(holder, 0);
        layout.tag.setInt(holder, 0);
        layout.networkSpeed.set(holder, null);
        if (layout.icon.get(holder) != icon || layout.type.getInt(holder) != 0
                || layout.tag.getInt(holder) != 0 || layout.networkSpeed.get(holder) != null) {
            throw new InstantiationException("Could not initialize the current TYPE_ICON holder");
        }
        return new Created(holder, "API 102 Object.<init>.newInstanceSpecial");
    }
}
