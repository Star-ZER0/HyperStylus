package cc.star0.xposed.hyperstylus.runtime.bluetooth;


import android.bluetooth.BluetoothGatt;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Optional;

/** The current BluetoothExtension service owns its pen battery GATT in field e. */
public final class PenGattSource {
    private static final ClassValue<Optional<Field>> FIELD = new ClassValue<>() {
        @Override protected Optional<Field> computeValue(Class<?> owner) {
            try {
                Field field = owner.getDeclaredField("e");
                if (Modifier.isStatic(field.getModifiers()) || field.getType() != BluetoothGatt.class) return Optional.empty();
                field.setAccessible(true);
                return Optional.of(field);
            } catch (ReflectiveOperationException | RuntimeException unavailable) { return Optional.empty(); }
        }
    };
    public static Field resolve(Class<?> owner) { return owner == null ? null : FIELD.get(owner).orElse(null); }
    public static BluetoothGatt read(Object service, Field field) {
        if (service == null || field == null || !field.getDeclaringClass().isInstance(service)) return null;
        try { return field.get(service) instanceof BluetoothGatt gatt ? gatt : null; }
        catch (ReflectiveOperationException | RuntimeException unavailable) { return null; }
    }
    private PenGattSource() {}
}
