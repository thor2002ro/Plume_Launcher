package dev.plume

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeWatchTest {
    @Test fun declaredHomeActivitiesWorkAcrossAndroidTvXiaomiGoogleTvAndOtherBoxes() {
        for ((pkg, home) in listOf(
            "com.google.android.tvlauncher" to "com.google.android.tvlauncher.MainActivity",
            "com.mitv.tvhome" to "com.mitv.tvhome.NewHome",
            "com.google.android.apps.tv.launcherx" to "com.google.android.apps.tv.launcherx.home.HomeActivity",
            "com.vendor.box.launcher" to "com.vendor.box.launcher.MainActivity",
        )) {
            val w = HomeWatch(false) { listOf(entry(pkg, "$pkg.HomeAlias", home)) }
            assertEquals(pkg, 0L, w.opened(pkg, home, 1_000))
            assertNull(pkg, w.opened(pkg, "$pkg.SettingsActivity", 2_000))
            assertNull(pkg, w.opened(pkg, "android.widget.FrameLayout", 3_000))
        }
    }

    @Test fun changedHomeComponentsAreReadWithoutRestartingTheWatcher() {
        var found = listOf(entry("vendor.home", "vendor.home.OldHome"))
        val w = HomeWatch(false) { found }
        assertEquals(0L, w.opened("vendor.home", "vendor.home.OldHome", 1_000))
        found = listOf(entry("vendor.home", "vendor.home.NewHome"))
        assertNull(w.opened("vendor.home", "vendor.home.OldHome", 2_000))
        assertEquals(0L, w.opened("vendor.home", "vendor.home.NewHome", 3_000))
    }

    @Test fun discoveryNeverRedirectsPlumeOrSystemWindows() {
        val w = HomeWatch(false) { listOf(entry("dev.plume", "dev.plume.MainActivity"), entry("android", "Resolver")) }
        assertNull(w.opened("dev.plume", "dev.plume.MainActivity", 1_000))
        assertEquals(HomeWatch.KEEP, w.opened("android", "Resolver", 2_000))
        assertNull(w.opened("com.vendor.box.launcher", "MainActivity", 3_000))
    }

    @Test fun anotherDeclaredLauncherCanStayOpenWhileInterceptionIsPaused() {
        val pkg = "vendor.home"
        val w = HomeWatch(false) { listOf(entry(pkg, "$pkg.Home")) }
        w.pause()
        assertNull(w.opened(pkg, "$pkg.Home", 1_000))
        w.opened("com.netflix.ninja", "Player", 2_000)
        assertEquals(0L, w.opened(pkg, "$pkg.Home", 3_000))
    }

    @Test fun aHomeDrawThatCancelsAWaitingRedirectDoesNotBlockLaterHomePresses() {
        val w = watch()
        w.opened("dev.plume", "dev.plume.MainActivity", 1_000)
        assertEquals(HomeWatch.SETTINGS_WAIT_MS, w.opened(amazon, amazonHome, 2_000))
        assertEquals(HomeWatch.KEEP, w.opened("dev.plume", "dev.plume.MainActivity", 2_100))
        w.jumped() // Home drew, so the service cancels the pending redirect
        assertEquals(HomeWatch.SETTINGS_WAIT_MS, w.opened(amazon, amazonHome, 4_000))
    }
    @Test fun wakeOnlyRecoversTheHomeThatWasVisibleBeforeStandby() {
        val w = watch()
        w.opened(amazon, amazonHome, 1_000)
        w.opened("com.android.systemui", "Overlay", 2_000)
        assertEquals(true, w.wake())
        w.opened("com.netflix.ninja", "Player", 3_000)
        assertEquals(false, w.wake())
    }

    @Test fun stockHomeEscapeLastsUntilAnotherRealAppOpens() {
        val w = watch()
        w.pause()
        assertNull(w.opened(amazon, amazonHome, 1_000))
        w.opened("android", "Overlay", 2_000)
        assertEquals(false, w.wake())
        assertNull(w.opened(amazon, amazonHome, 3_000))
        w.opened("com.netflix.ninja", "Player", 4_000)
        assertEquals(0L, w.opened(amazon, amazonHome, 5_000))
    }

    private val amazon = "com.amazon.tv.launcher"
    private val amazonHome = "com.amazon.tv.launcher.ui.HomeActivity_vNext"
    private val starter = "com.amazon.firehomestarter"
    private val gtv = "com.google.android.apps.tv.launcherx"
    private val gtvHome = "$gtv.home.HomeActivity"
    private val gtvKids = "$gtv.home.VanillaModeHomeActivity"
    private val oldFire = "com.amazon.firelauncher"
    private fun watch(fire: Boolean = true, homes: Set<String> = setOf(gtvHome, gtvKids)) =
        HomeWatch(fire) { homes.map { entry(gtv, it) } }

    @Test fun amazonHomeFromAnAppJumpsAtOnce() {
        val w = watch()
        assertNull(w.opened("com.netflix.ninja", "Player", 1_000))
        assertEquals(0L, w.opened(amazon, amazonHome, 2_000))
    }

    @Test fun onePressJumpsOnce() {
        val w = watch()
        assertEquals(0L, w.opened(amazon, amazonHome, 1_000))
        assertEquals(HomeWatch.KEEP, w.opened(amazon, amazonHome, 1_200))
        assertEquals(0L, w.opened(amazon, amazonHome, 2_000))
    }

    // Another window of the same press must not drop the jump that waits for Settings to go away.
    @Test fun aSecondHomeWindowOfThePressKeepsTheWaitingJump() {
        val w = watch()
        w.opened("com.amazon.tv.settings.v2", "Settings", 1_000)
        assertEquals(HomeWatch.SETTINGS_WAIT_MS, w.opened(amazon, amazonHome, 2_000))
        assertEquals(HomeWatch.KEEP, w.opened(amazon, "$amazon.ui.HomeActivity", 2_100))
    }

    @Test fun theStarterOfTheAmazonHomeIsNotAHomeWindow() {
        val w = watch()
        assertNull(w.opened("com.netflix.ninja", "Player", 1_000))
        assertNull(w.opened(starter, "$starter.HomeStarterActivity", 2_000))
        assertEquals(0L, w.opened(amazon, amazonHome, 2_050))
    }

    @Test fun otherAmazonLauncherScreensAreLeftAlone() {
        assertNull(watch().opened(amazon, "com.amazon.tv.launcher.ui.QuickSettingsActivity", 1_000))
    }

    @Test fun everyHomeActivityOfTheAmazonLauncherCounts() {
        assertEquals(0L, watch().opened(amazon, "$amazon.ui.HomeActivity", 1_000))
        assertEquals(0L, watch().opened(amazon, "$amazon.ui.HomeActivity_v2", 1_000))
    }

    @Test fun waitsWhenSettingsMayFollow() {
        val w = watch()
        w.opened("com.amazon.tv.settings.v2", "Settings", 1_000)
        assertEquals(HomeWatch.SETTINGS_WAIT_MS, w.opened(amazon, amazonHome, 2_000))
        w.jumped()
        w.opened("dev.plume", "dev.plume.MainActivity", 3_000)
        assertEquals(HomeWatch.SETTINGS_WAIT_MS, w.opened(amazon, amazonHome, 4_000))
    }

    @Test fun theWaitIsOnlyForFireTv() {
        val w = watch(fire = false)
        w.opened("dev.plume", "dev.plume.MainActivity", 1_000)
        assertEquals(0L, w.opened(gtv, gtvHome, 2_000))
        w.opened("dev.plume", "dev.plume.MainActivity", 3_000)
        assertEquals(0L, w.opened(amazon, amazonHome, 4_000))
    }

    @Test fun olderFireLauncher() {
        assertEquals(0L, watch().opened(oldFire, "$oldFire.Launcher", 1_000))
    }

    @Test fun googleTvHomeJumpsAtOnce() {
        val w = watch(fire = false)
        w.opened("dev.plume", "dev.plume.MainActivity", 2_000)
        assertEquals(0L, w.opened(gtv, gtvHome, 3_000))
        assertEquals(0L, w.opened(gtv, gtvKids, 4_000))
    }

    // #150: the Google TV launcher also draws notices and sign-in screens, and Plume took each for the home screen.
    @Test fun otherGoogleTvLauncherWindowsAreLeftAlone() {
        val w = watch(fire = false)
        for ((i, cls) in listOf(
            "$gtv.networkreauth.NetworkReauthActivity",
            "$gtv.dashboard.DashboardActivity",
            "$gtv.gemini.chat.ChatActivity",
            "android.widget.FrameLayout", // a notice drawn by the launcher
        ).withIndex()) assertNull(cls, w.opened(gtv, cls, 1_000L + i * 1_000))
    }

    @Test fun missingGoogleTvDataUsesNarrowFallbacksWithoutCatchingNotices() {
        val w = watch(fire = false, homes = emptySet())
        assertEquals(0L, w.opened(gtv, gtvHome, 1_000))
        assertNull(w.opened(gtv, "$gtv.dashboard.DashboardActivity", 3_000))
        assertNull(w.opened(gtv, "$gtv.networkreauth.NetworkReauthActivity", 4_000))
        assertNull(w.opened(gtv, "android.widget.FrameLayout", 5_000))
    }

    @Test fun aWindowWithoutAClassIsLeftAlone() {
        assertNull(watch().opened(gtv, null, 1_000))
        assertNull(watch().opened(amazon, null, 1_000))
    }

    @Test fun aLauncherThatIsNotInTheListIsLeftAlone() {
        assertNull(watch().opened("com.spocky.projengmenu", "com.spocky.projengmenu.ui.home.MainActivity", 1_000))
    }

    private fun entry(pkg: String, cls: String, target: String? = null) = HomeEntry(pkg, cls, target)

    @Test fun theGoogleTvHomesAreWhatTheLauncherDeclaresForHome() {
        val found = listOf(
            entry(gtv, gtvHome),
            entry(gtv, gtvKids),
            entry(amazon, amazonHome),
            entry("dev.plume", "dev.plume.MainActivity"),
        )
        assertEquals(setOf(gtvHome, gtvKids), homeClasses(found, gtv))
    }

    @Test fun anAliasCountsUnderItsOwnNameAndItsRealClass() {
        val found = listOf(entry(gtv, "$gtv.HomeAlias", target = "$gtv.home.RealHome"))
        assertEquals(setOf("$gtv.HomeAlias", "$gtv.home.RealHome"), homeClasses(found, gtv))
    }

    @Test fun noGoogleTvLauncherGivesAnEmptySet() {
        assertEquals(emptySet<String>(), homeClasses(listOf(entry(amazon, amazonHome)), gtv))
    }

    // A second Home window must leave the jump waiting when Home is pressed from Plume.
    @Test fun moreWindowsOfTheSamePressKeepTheWaitingJump() {
        val w = watch()
        w.opened("dev.plume", "dev.plume.MainActivity", 1_000)
        assertEquals(HomeWatch.SETTINGS_WAIT_MS, w.opened(amazon, amazonHome, 2_000))
        assertEquals(HomeWatch.KEEP, w.opened(amazon, amazonHome, 2_100))
        assertEquals(HomeWatch.KEEP, w.opened("com.android.systemui", "Panel", 2_200))
        assertEquals(HomeWatch.KEEP, w.opened("dev.plume", "dev.plume.MainActivity", 2_300))
    }

    @Test fun anotherAppOpeningDropsTheWaitingJump() { // Settings on top of Amazon's home
        val w = watch()
        w.opened("dev.plume", "dev.plume.MainActivity", 1_000)
        assertEquals(HomeWatch.SETTINGS_WAIT_MS, w.opened(amazon, amazonHome, 2_000))
        assertNull(w.opened("com.amazon.tv.settings.v2", "Settings", 2_200))
    }

    @Test fun waitingJumpSurvivesTheGapUntilItRuns() {
        val w = watch()
        w.opened("dev.plume", "dev.plume.MainActivity", 1_000)
        assertEquals(HomeWatch.SETTINGS_WAIT_MS, w.opened(amazon, amazonHome, 2_000))
        assertEquals(HomeWatch.KEEP, w.opened(amazon, amazonHome, 2_000 + HomeWatch.SETTINGS_WAIT_MS))
        assertEquals(HomeWatch.KEEP, w.opened(amazon, amazonHome, 2_575))
        w.jumped()
        assertEquals(HomeWatch.SETTINGS_WAIT_MS, w.opened(amazon, amazonHome, 3_000))
    }

    @Test fun anotherAmazonLauncherScreenDropsTheWaitingJump() {
        val w = watch()
        w.opened("dev.plume", "dev.plume.MainActivity", 1_000)
        assertEquals(HomeWatch.SETTINGS_WAIT_MS, w.opened(amazon, amazonHome, 2_000))
        assertNull(w.opened(amazon, "com.amazon.tv.launcher.ui.QuickSettingsActivity", 2_200))
    }

    @Test fun androidWindowDoesNotDropTheWaitingJump() {
        val w = watch()
        w.opened("dev.plume", "dev.plume.MainActivity", 1_000)
        assertEquals(HomeWatch.SETTINGS_WAIT_MS, w.opened(amazon, amazonHome, 2_000))
        assertEquals(HomeWatch.KEEP, w.opened("android", "Dialog", 2_200))
        assertEquals(HomeWatch.KEEP, w.opened(amazon, amazonHome, 2_300))
    }

    @Test fun xiaomiHomeJumpsAtOnce() {
        val w = watch(fire = false)
        val xiaomi = "com.mitv.tvhome"
        w.opened("dev.plume", "dev.plume.MainActivity", 1_000)
        assertEquals(0L, w.opened(xiaomi, "com.mitv.tvhome.MainActivityUserMode", 2_000))
        assertEquals(HomeWatch.KEEP, w.opened(xiaomi, "com.mitv.tvhome.MainActivityUserMode", 2_100)) // sent twice per press
        assertNull(w.opened(xiaomi, "com.mitv.tvhome.SomeOtherActivity", 3_000))
    }
}
