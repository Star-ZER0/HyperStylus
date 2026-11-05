package cc.star0.xposed.hyperstylus.common;


import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Collections;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/** Cached ROM reflection; generation retirement clears host ClassValue associations. */
public final class Reflect {
    private record Call(String name, boolean isStatic, List<Class<?>> arguments) {}
    private static final class Members {
        public final Map<String, Field> fields = new ConcurrentHashMap<>();
        public final Map<Call, Method> methods = new ConcurrentHashMap<>();
    }
    private static final Map<Class<?>, Boolean> CACHED_TYPES = Collections.synchronizedMap(new WeakHashMap<>());
    private static final ClassValue<Members> CACHE = new ClassValue<>() {
        @Override protected Members computeValue(Class<?> type) {
            CACHED_TYPES.put(type, Boolean.TRUE);
            return new Members();
        }
    };
    private static final Map<Class<?>, Class<?>> BOXED = Map.of(
            boolean.class, Boolean.class, byte.class, Byte.class, char.class, Character.class,
            short.class, Short.class, int.class, Integer.class, long.class, Long.class,
            float.class, Float.class, double.class, Double.class);
    private Reflect() {}

    public static void clearCaches() {
        List<Class<?>> types;
        synchronized (CACHED_TYPES) {
            types = List.copyOf(CACHED_TYPES.keySet());
            CACHED_TYPES.clear();
        }
        types.forEach(CACHE::remove);
    }

    public static Class<?> find(ClassLoader loader, String... names) {
        for (String name : names) {
            try { return Class.forName(name, false, loader); }
            catch (ClassNotFoundException ignored) { }
        }
        return null;
    }
    public static Object field(Object target, String... names) throws ReflectiveOperationException {
        Class<?> type = target instanceof Class<?> value ? value : target.getClass();
        for (String name : names) {
            try { return resolveField(type, name).get(target instanceof Class<?> ? null : target); }
            catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(type.getName() + ": " + String.join(", ", names));
    }
    public static void set(Object target, String name, Object value) throws ReflectiveOperationException {
        resolveField(target.getClass(), name).set(target, value);
    }
    private static Field resolveField(Class<?> type, String name) throws NoSuchFieldException {
        Field cached = CACHE.get(type).fields.get(name);
        if (cached != null) return cached;
        for (Class<?> cursor = type; cursor != null; cursor = cursor.getSuperclass()) {
            try {
                Field field = cursor.getDeclaredField(name);
                field.setAccessible(true);
                CACHE.get(type).fields.putIfAbsent(name, field);
                return field;
            } catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(type.getName() + "." + name);
    }
    public static Object call(Object target, String name, Object... arguments) throws Throwable {
        boolean isStatic = target instanceof Class<?>;
        Class<?> type = isStatic ? (Class<?>) target : target.getClass();
        List<Class<?>> signature = Arrays.stream(arguments)
                .map(value -> value == null ? Void.class : value.getClass()).toList();
        Call key = new Call(name, isStatic, signature);
        Method method = CACHE.get(type).methods.get(key);
        if (method == null) {
            method = resolveMethod(type, name, isStatic, arguments);
            method.setAccessible(true);
            CACHE.get(type).methods.putIfAbsent(key, method);
        }
        try { return method.invoke(isStatic ? null : target, arguments); }
        catch (InvocationTargetException error) { throw error.getCause(); }
    }
    private static Method resolveMethod(Class<?> type, String name, boolean isStatic, Object[] arguments)
            throws NoSuchMethodException {
        for (Class<?> cursor = type; cursor != null; cursor = cursor.getSuperclass()) {
            Method selected = null;
            int best = -1;
            for (Method candidate : cursor.getDeclaredMethods()) {
                if (!name.equals(candidate.getName()) || candidate.getParameterCount() != arguments.length
                        || isStatic && !Modifier.isStatic(candidate.getModifiers())) continue;
                int score = score(candidate.getParameterTypes(), arguments);
                if (score >= 0 && (score > best || score == best && narrower(candidate, selected))) {
                    selected = candidate; best = score;
                }
            }
            if (selected != null) return selected;
        }
        throw new NoSuchMethodException(type.getName() + "." + name + "/" + arguments.length);
    }
    private static int score(Class<?>[] parameters, Object[] arguments) {
        int score = 0;
        for (int i = 0; i < parameters.length; i++) {
            Class<?> parameter = parameters[i];
            Object value = arguments[i];
            if (value == null) { if (parameter.isPrimitive()) return -1; continue; }
            Class<?> compatible = parameter.isPrimitive() ? BOXED.get(parameter) : parameter;
            if (compatible == null || !compatible.isInstance(value)) return -1;
            if (compatible == value.getClass()) score++;
        }
        return score;
    }
    private static boolean narrower(Method candidate, Method selected) {
        if (selected == null) return true;
        Class<?>[] next = candidate.getParameterTypes(), previous = selected.getParameterTypes();
        boolean changed = false;
        for (int i = 0; i < next.length; i++) {
            if (previous[i] == next[i]) continue;
            if (!previous[i].isAssignableFrom(next[i])) return false;
            changed = true;
        }
        return changed;
    }
}
