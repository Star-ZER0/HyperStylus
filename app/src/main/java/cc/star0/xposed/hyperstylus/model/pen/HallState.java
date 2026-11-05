package cc.star0.xposed.hyperstylus.model.pen;


/** Either active-low sensor proves attachment; detachment requires both inactive sensors. */
public final class HallState {
    private final int[] sensors = {-1, -1};
    public int update(int three, int four) {
        int[] incoming = {three, four};
        for (int i = 0; i < sensors.length; i++) if (incoming[i] == 0 || incoming[i] == 1) sensors[i] = incoming[i];
        if (sensors[0] == 0 || sensors[1] == 0) return 1;
        return sensors[0] == 1 && sensors[1] == 1 ? 0 : -1;
    }
    public android.os.Bundle saveState() {
        android.os.Bundle state = new android.os.Bundle(); state.putIntArray("sensors", sensors.clone()); return state;
    }
    public void restoreState(android.os.Bundle state) {
        int[] values = state == null ? null : state.getIntArray("sensors");
        if (values != null && values.length == 2) { sensors[0] = values[0]; sensors[1] = values[1]; }
    }
}
