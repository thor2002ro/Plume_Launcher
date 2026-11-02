package dev.plume

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectBootTest {
    @Test fun homeRebuildsOnlyOnceAfterUnlock() {
        assertFalse(shouldRebuildHome(false, false, false, false, false))
        assertTrue(shouldRebuildHome(false, false, false, false, true))
        assertFalse(shouldRebuildHome(true, false, false, false, true))
        assertFalse(shouldRebuildHome(false, true, false, false, true))
        assertFalse(shouldRebuildHome(false, false, true, false, true))
        assertFalse(shouldRebuildHome(false, false, false, true, true))
    }
}
