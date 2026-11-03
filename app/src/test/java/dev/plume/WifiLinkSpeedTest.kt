package dev.plume

import org.junit.Assert.*
import org.junit.Test

class WifiLinkSpeedTest {
    @Test fun onlyAnActiveWifiLinkWithAPositiveSpeedIsShown() {
        assertEquals(866, WifiLinkSpeed.shown(wifi = true, speed = 866))
        assertNull(WifiLinkSpeed.shown(wifi = false, speed = 866))
        assertNull(WifiLinkSpeed.shown(wifi = true, speed = -1))
        assertNull(WifiLinkSpeed.shown(wifi = true, speed = 0))
        assertNull(WifiLinkSpeed.shown(wifi = true, speed = null))
    }

    @Test fun theLabelUsesMbpsOrADashWhenTheReadingIsUnavailable() {
        assertEquals("866 Mbps", WifiLinkSpeed.label(866))
        assertEquals("– Mbps", WifiLinkSpeed.label(null))
    }

    @Test fun signalPollingRequiresAVisibleAwakeLauncherEvenWhenSpeedIsHidden() {
        assertTrue(WifiLinkSpeed.shouldPoll(true, true, true))
        assertFalse(WifiLinkSpeed.shouldPoll(false, true, true))
        assertFalse(WifiLinkSpeed.shouldPoll(true, false, true))
        assertFalse(WifiLinkSpeed.shouldPoll(true, true, false))
        assertEquals(10_000L, WifiLinkSpeed.REFRESH_MS)
    }
}
