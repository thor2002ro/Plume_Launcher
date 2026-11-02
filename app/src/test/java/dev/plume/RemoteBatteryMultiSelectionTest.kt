package dev.plume

import org.junit.Assert.*
import org.junit.Test

class RemoteBatteryMultiSelectionTest {
    @Test fun bothSelectedDevicesAreReadAndOtherDevicesAreExcluded() {
        val selected = setOf("remote", "headphones")
        assertTrue(RemoteBattery.matchesSelection("remote", selected))
        assertTrue(RemoteBattery.matchesSelection("headphones", selected))
        assertFalse(RemoteBattery.matchesSelection("controller", selected))
    }

    @Test fun noDevicesSelectedDoesNotFallBackToAllDevices() {
        assertFalse(RemoteBattery.matchesSelection("remote", emptySet()))
    }

    @Test fun existingSingleChoiceIsPreservedWhenUpgrading() {
        assertEquals(setOf("remote"), RemoteBattery.selection(null, "remote"))
        assertNull(RemoteBattery.selection(null, null))
    }

    @Test fun explicitSelectionIncludingEmptyOverridesTheOldSingleChoice() {
        assertEquals(setOf("remote", "headphones"), RemoteBattery.selection(setOf("remote", "headphones"), "old"))
        assertEquals(emptySet<String>(), RemoteBattery.selection(emptySet(), "old"))
    }
}
