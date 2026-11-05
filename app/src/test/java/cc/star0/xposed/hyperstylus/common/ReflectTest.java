package cc.star0.xposed.hyperstylus.common;


import org.junit.Test;
import static org.junit.Assert.*;

public class ReflectTest {
    private static class Parent { private String inherited = "old"; }
    private static class Target extends Parent {
        private String pick(Object value) { return "object"; }
        private String pick(String value) { return "string"; }
        private String primitive(double d, char c, byte b, short s) { return d + "/" + c + "/" + b + "/" + s; }
        private static int twice(int value) { return value * 2; }
        private void fail() { throw new IllegalArgumentException("original"); }
    }
    @Test public void exactAndNullOverloadsChooseSpecificMethodAcrossCachedCalls() throws Throwable {
        Target target = new Target();
        assertEquals("string", Reflect.call(target, "pick", "x"));
        assertEquals("string", Reflect.call(target, "pick", new Object[]{null}));
        assertEquals("object", Reflect.call(target, "pick", new Object()));
        assertEquals("string", Reflect.call(target, "pick", "cached"));
    }
    @Test public void inheritancePrimitiveCoverageAndExceptionIdentityArePreserved() throws Throwable {
        Target target = new Target();
        assertEquals("old", Reflect.field(target, "missing", "inherited"));
        Reflect.set(target, "inherited", "new"); assertEquals("new", Reflect.field(target, "inherited"));
        assertEquals("1.5/z/2/3", Reflect.call(target, "primitive", 1.5, 'z', (byte) 2, (short) 3));
        assertEquals(8, Reflect.call(Target.class, "twice", 4));
        try { Reflect.call(target, "fail"); fail("Expected original exception"); }
        catch (IllegalArgumentException expected) { assertEquals("original", expected.getMessage()); }
    }
    @Test public void retirementRemovesOldModuleValuesAttachedToHostClasses() throws Throwable {
        java.lang.reflect.Field storage = Reflect.class.getDeclaredField("CACHE");
        storage.setAccessible(true);
        ClassValue<?> cache = (ClassValue<?>) storage.get(null);
        Object oldMembers = cache.get(Target.class);
        assertEquals("string", Reflect.call(new Target(), "pick", "before"));
        Reflect.clearCaches();
        assertNotSame(oldMembers, cache.get(Target.class));
        assertEquals("string", Reflect.call(new Target(), "pick", "after"));
    }
}
