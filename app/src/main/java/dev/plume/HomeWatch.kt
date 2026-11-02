package dev.plume

/** One activity that answers the Home intent, as the system lists it. */
class HomeEntry(val pkg: String, val cls: String, val target: String?)

/**
 * The classes that the Google TV launcher declares for the Home intent: its home screens. The launcher draws
 * other windows too (notices, sign-in, the dashboard). An alias is listed under its own name but its window
 * carries the real class, so both count.
 */
fun homeClasses(found: List<HomeEntry>, pkg: String): Set<String> =
    found.filter { it.pkg == pkg }.flatMap { listOfNotNull(it.cls, it.target) }.toSet()

/**
 * Decides, for each window that opens, whether Plume should cover the TV's own home screen. Fire OS never
 * hands the Home key to an accessibility service, so there this is the only way Home reaches Plume.
 * [homes] gives declared Home activities and their alias targets, including vendor launchers.
 * Narrow known fallbacks cover missing package data. Other launcher windows stay untouched.
 */
class HomeWatch(private val fire: Boolean, private val homes: () -> List<HomeEntry>) {
    private var settingsMayFollow = false
    private var lastJump = -JUMP_GAP_MS
    private var waitingPkg: String? = null
    private var lastHome = false
    private var paused = false

    fun pause() { paused = true; waitingPkg = null }
    fun resume() { paused = false }

    fun wake() = fire && lastHome && !paused

    /**
     * [KEEP]: leave any jump still waiting. Null: drop it. Otherwise: go home after this many ms.
     */
    fun opened(pkg: String, cls: String?, now: Long): Long? {
        if (pkg == "android" || pkg == "com.android.systemui") return KEEP
        lastHome = isHome(pkg, cls)
        if (paused) {
            if (lastHome || pkg == PLUME || pkg.startsWith(AMAZON_SETTINGS)) return null
            paused = false
        }
        // System UI events do not show that the user left Home; a different launcher screen does.
        if (waitingPkg != null && ((pkg == waitingPkg && isHome(pkg, cls)) ||
                pkg == PLUME || pkg == "android" || pkg == "com.android.systemui")) return KEEP
        waitingPkg = null
        if (!isHome(pkg, cls)) {
            // Fire OS shows its home on the way into (and out of) Amazon Settings, then Settings on top.
            settingsMayFollow = pkg == PLUME || pkg.startsWith(AMAZON_SETTINGS)
            return null
        }
        if (now - lastJump < JUMP_GAP_MS) return KEEP // one Home press sends several of these
        lastJump = now
        if (!settingsMayFollow || !fire) return 0
        waitingPkg = pkg
        return SETTINGS_WAIT_MS
    }

    fun jumped() { waitingPkg = null }

    private fun isHome(pkg: String, cls: String?): Boolean {
        if (cls == null || pkg == PLUME || pkg == "android" || pkg == "com.android.systemui") return false
        if (pkg == GOOGLE_TV && cls.contains("dashboard", ignoreCase = true)) return false
        val declared = homeClasses(homes(), pkg)
        if (cls in declared) return true
        return when (pkg) {
            GOOGLE_TV -> declared.isEmpty() && cls in setOf("$GOOGLE_TV.home.HomeActivity", "$GOOGLE_TV.home.VanillaModeHomeActivity")
            AMAZON -> cls.startsWith("$AMAZON.ui.HomeActivity") // HomeActivity_vNext on current builds
            OLD_AMAZON -> cls == "$OLD_AMAZON.Launcher"
            XIAOMI -> cls == "$XIAOMI.MainActivityUserMode"
            else -> false
        }
    }

    companion object {
        private const val PLUME = "dev.plume"
        const val GOOGLE_TV = "com.google.android.apps.tv.launcherx"
        private const val AMAZON = "com.amazon.tv.launcher"
        private const val OLD_AMAZON = "com.amazon.firelauncher"
        private const val XIAOMI = "com.mitv.tvhome"
        private const val AMAZON_SETTINGS = "com.amazon.tv.settings"
        private const val JUMP_GAP_MS = 600L
        const val SETTINGS_WAIT_MS = 550L
        const val KEEP = -1L
    }
}
