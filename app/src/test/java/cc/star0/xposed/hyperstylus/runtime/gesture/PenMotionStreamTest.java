package cc.star0.xposed.hyperstylus.runtime.gesture;


import org.junit.Test;
import static org.junit.Assert.*;

public class PenMotionStreamTest {
    @Test public void joiningPenCancelsFingerExactlyOnceAndDropsRemainingStream() {
        PenMotionStream gate = new PenMotionStream(); Object owner = new Object();
        assertEquals(0, gate.decide(owner, 0, 100, 3, false, true));
        assertEquals(2, gate.decide(owner, 5, 100, 3, true, true));
        assertEquals(1, gate.decide(owner, 2, 100, 3, true, true));
        assertEquals(1, gate.decide(owner, 1, 100, 3, false, false));
        assertEquals(0, gate.decide(owner, 0, 200, 3, false, true));
    }
    @Test public void policyIsPinnedToDownAndOwnersAreIndependent() {
        PenMotionStream gate = new PenMotionStream(); Object first = new Object(), second = new Object();
        assertEquals(0, gate.decide(first, 0, 100, 3, true, false));
        assertEquals(0, gate.decide(first, 2, 100, 3, true, true));
        assertEquals(1, gate.decide(second, 0, 100, 3, true, true));
        assertEquals(0, gate.decide(first, 3, 100, 3, true, true));
        assertEquals(1, gate.decide(second, 3, 100, 3, true, false));
    }
    @Test public void unrelatedDeviceAndDownTimeDoNotInheritBlockedState() {
        PenMotionStream gate = new PenMotionStream(); Object owner = new Object();
        assertEquals(1, gate.decide(owner, 0, 100, 3, true, true));
        assertEquals(0, gate.decide(owner, 2, 100, 4, false, true));
        assertEquals(0, gate.decide(owner, 2, 200, 3, false, true));
    }
}
