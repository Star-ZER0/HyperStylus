package cc.star0.xposed.hyperstylus.runtime.bluetooth;


import static org.junit.Assert.*;
import android.bluetooth.BluetoothGatt;
import java.lang.reflect.Field;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class PenGattSourceTest {
    /** Exact e/f/g field layout observed in the current BluetoothExtension DEX. */
    private static final class CurrentService {
        public BluetoothGatt e, f, g;
    }
    private static final class WrongType {
        public Object e;
        public BluetoothGatt other;
    }
    private static final class WrongField {
        public BluetoothGatt other;
    }
    @Test public void batteryGattIsSelectedAmongOtherServiceConnections() {
        Field field = PenGattSource.resolve(CurrentService.class);
        assertNotNull(field); assertEquals("e", field.getName());
        assertEquals(CurrentService.class, field.getDeclaringClass());
        assertEquals(BluetoothGatt.class, field.getType());
    }
    @Test public void unsupportedLayoutNeverSubstitutesAnUnrelatedGatt() {
        assertNull(PenGattSource.resolve(WrongType.class));
        assertNull(PenGattSource.resolve(WrongField.class));
        assertNull(PenGattSource.resolve(null));
    }
}
