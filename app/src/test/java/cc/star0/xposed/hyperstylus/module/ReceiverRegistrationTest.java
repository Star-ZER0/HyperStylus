package cc.star0.xposed.hyperstylus.module;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import androidx.test.core.app.ApplicationProvider;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class ReceiverRegistrationTest {
    private static final String ACTION = "test.hyperstylus.FOCUS";
    private static BroadcastReceiver receiver(AtomicInteger received) {
        return new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) { received.incrementAndGet(); }
        };
    }
    private static final class Registry implements ReceiverRegistration.Registry {
        final Map<BroadcastReceiver, Context> owners = new IdentityHashMap<>();
        int inspections, recoveries;
        RuntimeException inspectionFailure;
        @Override public Context owner(BroadcastReceiver receiver) {
            inspections++;
            if (inspectionFailure != null) throw inspectionFailure;
            return owners.get(receiver);
        }
        @Override public void unregister(Context owner, BroadcastReceiver receiver) {
            recoveries++; owner.unregisterReceiver(receiver);
        }
    }
    private static class Host extends ContextWrapper {
        final Registry registry;
        int unregisterCalls;
        RuntimeException unregisterFailure, registerFailure;
        Host(Registry registry) { super(null); this.registry = registry; }
        @Override public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter,
                String permission, Handler scheduler, int flags) {
            registry.owners.put(receiver, this);
            if (registerFailure != null) throw registerFailure;
            return null;
        }
        @Override public void unregisterReceiver(BroadcastReceiver receiver) {
            unregisterCalls++;
            if (unregisterFailure != null) throw unregisterFailure;
            if (registry.owners.get(receiver) != this) throw new IllegalArgumentException("Receiver not registered: " + receiver);
            registry.owners.remove(receiver);
        }
    }
    private static final class Wrapper extends ContextWrapper {
        Context delegate;
        Wrapper(Context delegate) { super(delegate); this.delegate = delegate; }
        @Override public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter,
                String permission, Handler scheduler, int flags) {
            return delegate.registerReceiver(receiver, filter, permission, scheduler, flags);
        }
        @Override public void unregisterReceiver(BroadcastReceiver receiver) { delegate.unregisterReceiver(receiver); }
    }
    private static ReceiverRegistration register(Context context, Registry registry, BroadcastReceiver receiver) {
        ReceiverRegistration result = new ReceiverRegistration(context, receiver, registry);
        result.register(new IntentFilter(ACTION), new Handler(Looper.getMainLooper()), Context.RECEIVER_EXPORTED);
        return result;
    }

    @Test public void contextWrapperChangesStillReleaseTheActualRegisteredOwner() throws Exception {
        Registry registry = new Registry(); Host first = new Host(registry), second = new Host(registry);
        Wrapper wrapper = new Wrapper(first); BroadcastReceiver receiver = receiver(new AtomicInteger());
        ReceiverRegistration registration = register(wrapper, registry, receiver);
        wrapper.delegate = second;
        registration.close();
        assertTrue(registry.owners.isEmpty()); assertEquals(1, registry.recoveries);
        assertEquals(1, first.unregisterCalls); assertEquals(1, second.unregisterCalls);
        registration.close(); assertEquals(1, registry.recoveries);
    }
    @Test public void anExternallyRemovedReceiverIsAcceptedOnlyAfterRegistryVerification() throws Exception {
        Registry registry = new Registry(); Host host = new Host(registry);
        BroadcastReceiver receiver = receiver(new AtomicInteger());
        ReceiverRegistration registration = register(host, registry, receiver);
        host.unregisterReceiver(receiver); registration.close();
        assertEquals(1, registry.inspections); assertEquals(0, registry.recoveries);
        registration.close(); assertEquals(1, registry.inspections);
    }
    @Test public void unknownUnregistrationFailuresAreNotSilenced() {
        Registry registry = new Registry(); Host host = new Host(registry);
        ReceiverRegistration registration = register(host, registry, receiver(new AtomicInteger()));
        host.unregisterFailure = new IllegalArgumentException("Unrelated platform failure");
        assertThrows(IllegalArgumentException.class, registration::close);
        assertEquals(0, registry.inspections); assertFalse(registry.owners.isEmpty());
    }
    @Test public void anUnavailableRegistryCannotPretendThatTheReceiverWasRemoved() {
        Registry registry = new Registry(); Host host = new Host(registry);
        ReceiverRegistration registration = register(host, registry, receiver(new AtomicInteger()));
        host.unregisterFailure = new IllegalArgumentException("Receiver not registered: test");
        registry.inspectionFailure = new IllegalStateException("Registry unavailable");
        assertThrows(IllegalStateException.class, registration::close);
        assertFalse(registry.owners.isEmpty());
    }
    @Test public void partialRegistrationFailureReleasesTheLocallyCreatedDispatcher() {
        Registry registry = new Registry(); Host host = new Host(registry);
        host.registerFailure = new SecurityException("Register rejected after local dispatcher creation");
        assertThrows(SecurityException.class, () -> register(host, registry, receiver(new AtomicInteger())));
        assertTrue(registry.owners.isEmpty()); assertEquals(1, host.unregisterCalls);
    }
    @Test public void isolatedAndroidContextReceivesAndStopsBeforeASecondClose() throws Exception {
        Context host = ApplicationProvider.getApplicationContext(); AtomicInteger received = new AtomicInteger();
        ReceiverRegistration registration = ReceiverRegistration.attach(host, receiver(received), new IntentFilter(ACTION),
                new Handler(Looper.getMainLooper()), Context.RECEIVER_EXPORTED);
        assertNotSame(host, registration.context());
        host.sendBroadcast(new Intent(ACTION)); shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1, received.get());
        registration.close(); registration.close();
        host.sendBroadcast(new Intent(ACTION)); shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1, received.get());
    }
}
