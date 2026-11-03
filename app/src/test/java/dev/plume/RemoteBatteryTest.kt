package dev.plume

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteBatteryTest {
    @Test fun theLabelIsThePercentOrADash() {
        assertEquals("69%", RemoteBattery.label(69))
        assertEquals("0%", RemoteBattery.label(0))
        assertEquals("–", RemoteBattery.label(null))
    }

    @Test fun lowIsAtOrUnderTheChosenLevelAndNeedsAReading() {
        assertTrue(RemoteBattery.isLow(20, 20))
        assertTrue(RemoteBattery.isLow(19, 20))
        assertFalse(RemoteBattery.isLow(21, 20))
        assertTrue(RemoteBattery.isLow(15, 15))
        assertFalse(RemoteBattery.isLow(16, 15))
        assertTrue(RemoteBattery.isLow(0, 20))
        assertFalse(RemoteBattery.isLow(5, 0)) // alert off
        assertFalse(RemoteBattery.isLow(null, 20)) // unknown is not low
        assertEquals(listOf(20, 15, 0), RemoteBattery.LOWS)
    }

    @Test fun theWorstRemoteIsTheOneThatCounts() {
        val r = listOf(RemoteBattery.Reading("Remote", 69), RemoteBattery.Reading("Pad", 31))
        assertEquals(31, RemoteBattery.lowest(r))
        assertEquals("Remote 69%, Pad 31%", RemoteBattery.describe(r))
        assertEquals(null, RemoteBattery.lowest(emptyList()))
    }

    @Test fun androidTwelveAsksForBluetoothConnectAndOlderAndroidNeedsNoQuestion() {
        assertEquals("android.permission.BLUETOOTH_CONNECT", RemoteBattery.permission(31))
        assertEquals("android.permission.BLUETOOTH_CONNECT", RemoteBattery.permission(34))
        assertEquals("android.permission.BLUETOOTH", RemoteBattery.permission(30)) // Android 11: BLUETOOTH_CONNECT does not exist, it is never granted
        assertEquals("android.permission.BLUETOOTH", RemoteBattery.permission(26))
        assertTrue(RemoteBattery.asksFirst(31))
        assertTrue(RemoteBattery.asksFirst(34))
        assertFalse(RemoteBattery.asksFirst(26))
        assertFalse(RemoteBattery.asksFirst(30)) // BLUETOOTH is granted at install: no dialog
    }

    @Test fun aFailedReadHidesTheCachedLevelUntilAReadSucceeds() {
        assertEquals(69, RemoteBattery.shown(69, failed = false))
        assertEquals(null, RemoteBattery.shown(69, failed = true))
        assertEquals(null, RemoteBattery.shown(null, failed = true))
        assertEquals(70, RemoteBattery.shown(70, failed = false))
        assertEquals(null, RemoteBattery.shown(null, failed = false))
    }

    @Test fun firstStartReadsImmediatelyAndLaterResumesWaitFiveSeconds() {
        assertEquals(0L, RemoteBattery.resumeDelay(started = false))
        assertEquals(5_000L, RemoteBattery.resumeDelay(started = true))
    }
}
