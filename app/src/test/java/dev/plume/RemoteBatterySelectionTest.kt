package dev.plume

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteBatterySelectionTest {
    private val remote = "00:00:00:00:00:01"
    private val controller = "00:00:00:00:00:02"

    @Test fun automaticReadsEitherPairedDevice() {
        assertTrue(RemoteBattery.matchesSelection(remote, null))
        assertTrue(RemoteBattery.matchesSelection(controller, null))
    }

    @Test fun choosingRemoteExcludesOtherPairedDevices() {
        assertTrue(RemoteBattery.matchesSelection(remote, remote))
        assertFalse(RemoteBattery.matchesSelection(controller, remote))
    }

    @Test fun aMissingSelectedDeviceDoesNotFallBackToAnotherDevice() {
        assertFalse(RemoteBattery.matchesSelection(remote, controller))
    }
}
