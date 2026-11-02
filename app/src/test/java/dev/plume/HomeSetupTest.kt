package dev.plume

import org.junit.Assert.*
import org.junit.Test

class HomeSetupTest {
    @Test fun recoveryNeedsConsentAndNeverOverridesOffOrAnAmbiguousRemoval() {
        assertFalse(HomeSetup.recover(false, false, false))
        assertTrue(HomeSetup.recover(true, false, false))
        assertFalse(HomeSetup.recover(true, true, false))
        assertFalse(HomeSetup.recover(true, false, true))
    }
    @Test fun nativeHomeUsesTheResolverIncludingSoleLauncherInstallations() {
        assertTrue(HomeSetup.native("dev.plume", "dev.plume"))
        assertFalse(HomeSetup.native("android", "dev.plume"))
        assertFalse(HomeSetup.native(null, "dev.plume"))
        assertTrue(HomeSetup.sole(listOf("dev.plume", "dev.plume"), "dev.plume"))
        assertFalse(HomeSetup.sole(emptyList(), "dev.plume"))
        assertFalse(HomeSetup.sole(listOf("dev.plume", "stock"), "dev.plume"))
    }

    @Test fun recognizesBothComponentFormsWithoutMatchingAnotherService() {
        assertTrue(HomeSetup.listed("other/Service:dev.plume/.HomeOverride", "dev.plume"))
        assertTrue(HomeSetup.listed("dev.plume/dev.plume.HomeOverride:other/Service", "dev.plume"))
        assertFalse(HomeSetup.listed("other/.HomeOverride", "dev.plume"))
        assertFalse(HomeSetup.listed(null, "dev.plume"))
    }

    @Test fun olderAndroidNeedsOnlyTheSecureSettingsGrant() {
        for (sdk in listOf(26, 30, 32)) assertEquals(
            listOf("adb shell pm grant dev.plume android.permission.WRITE_SECURE_SETTINGS"),
            HomeSetup.commands("dev.plume", sdk),
        )
    }

    @Test fun restrictedAndroidAlsoNeedsTheAppopForTheInstalledPackage() {
        for (sdk in listOf(33, 34, 36)) assertEquals(listOf(
            "adb shell pm grant dev.plume.debug android.permission.WRITE_SECURE_SETTINGS",
            "adb shell appops set dev.plume.debug ACCESS_RESTRICTED_SETTINGS allow",
        ), HomeSetup.commands("dev.plume.debug", sdk))
    }
}
