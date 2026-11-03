package dev.plume

import org.junit.Assert.*
import org.junit.Test

class WifiStatusTest {
    @Test fun aDisconnectedNetworkCannotKeepItsOldSignalLevel() {
        assertNull(WifiStatus.signalLevel(false, 2))
        assertNull(WifiStatus.signalLevel(true, null))
    }

    @Test fun connectedSignalLevelsStayWithinTheThreeDisplayedLevels() {
        assertEquals(0, WifiStatus.signalLevel(true, 0))
        assertEquals(1, WifiStatus.signalLevel(true, 1))
        assertEquals(2, WifiStatus.signalLevel(true, 2))
        assertEquals(0, WifiStatus.signalLevel(true, -1))
        assertEquals(2, WifiStatus.signalLevel(true, 5))
    }

    @Test fun onlyAnActiveDisconnectedWifiIndicatorBlinks() {
        assertTrue(WifiStatus.shouldBlink(false, false, true))
        assertFalse(WifiStatus.shouldBlink(true, false, true))
        assertFalse(WifiStatus.shouldBlink(false, true, true))
        assertFalse(WifiStatus.shouldBlink(false, false, false))
    }
}
