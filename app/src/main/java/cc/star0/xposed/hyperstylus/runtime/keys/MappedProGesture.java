package cc.star0.xposed.hyperstylus.runtime.keys;


/** Route the mapped command once; ROM queue-consumption booleans are not execution acknowledgements. */
public final class MappedProGesture {
    public interface Endpoint {
        public boolean usesShortcutPanel() throws Throwable;
        public void showShortcutPanel(int device) throws Throwable;
        public boolean closeVirtualLaser(int device) throws Throwable;
        public void sendApplicationKey(int code, int device) throws Throwable;
    }

    public static void execute(int code, int device, Endpoint endpoint) throws Throwable {
        if (code < 194 || code > 197 || device < 0) throw new IllegalArgumentException("Invalid mapped pen gesture");
        if (code == 194 && endpoint.usesShortcutPanel()) {
            endpoint.showShortcutPanel(device);
            return;
        }
        if (code == 195 && endpoint.closeVirtualLaser(device)) return;
        endpoint.sendApplicationKey(code, device);
    }

    private MappedProGesture() { }
}
