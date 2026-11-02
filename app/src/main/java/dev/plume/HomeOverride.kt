package dev.plume

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Google TV's launcher has to stay enabled (disabling it boot-loops the TV), so we take the Home key before
 * the system sees it and open our home directly; Google TV never flashes up. Holding Home still opens the
 * Google TV dashboard. Covering Google TV whenever it shows stays as the fallback for anything else that
 * opens it (e.g. Back out of the last app). On Fire TV that cover is the only way: Fire OS keeps Home to itself.
 */
class HomeOverride : AccessibilityService() {
    override fun onServiceConnected() {
        if (turningOff) {
            disableSelf()
            return
        }
        running = this
        getSharedPreferences("home", MODE_PRIVATE).edit().remove("home_off").remove("home_repair")
            .putBoolean("home_requested", true).apply()
        applyKeyFilter()
        loadHomes()
        if (!listening) {
            listening = true
            registerReceiver(packageReceiver, IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_CHANGED)
                addDataScheme("package")
            })
            registerReceiver(screenReceiver, IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            })
        }
        HomeAlarm.engage(this)
    }

    /**
     * Seeing keys costs volume its hold on TVs: Android acts on Volume before any service sees it and the key never
     * repeats. So Smooth volume turns key filtering off, unless remote keys need it (set, or one being learned).
     * Home is then caught by the window watcher below, which shows the TV's own home for about a second.
     * Fire OS never passes remote keys to us, so there filtering is always off.
     */
    fun applyKeyFilter() {
        val filter = !fireTv(this) && (!getSharedPreferences("home", MODE_PRIVATE).getBoolean("smooth_volume", false) ||
            Remap.all(this).isNotEmpty() || Remap.learn != null)
        val flag = AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        serviceInfo = serviceInfo.apply { flags = if (filter) flags or flag else flags and flag.inv() }
    }

    /** Turned off, from Plume's switch or Android's Accessibility settings: the app mustn't turn it back on. */
    override fun onUnbind(intent: Intent?): Boolean {
        stop()
        turningOff = false
        // A disconnect while still enabled is not a request to leave Home switched off.
        if (!HomeSetup.listed(android.provider.Settings.Secure.getString(contentResolver,
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), packageName) ||
            android.provider.Settings.Secure.getInt(contentResolver, android.provider.Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 0) {
            getSharedPreferences("home", MODE_PRIVATE).edit().putBoolean("home_repair", true).apply()
        }
        return super.onUnbind(intent)
    }

    override fun onKeyEvent(e: KeyEvent): Boolean {
        if (e.keyCode != KeyEvent.KEYCODE_HOME) return remap(e)
        if (nativeHome()) return false
        // Decided on release, by how long it was held: this TV's remote doesn't reliably send key repeats.
        if (e.action == KeyEvent.ACTION_UP && !e.isCanceled) {
            if (e.eventTime - e.downTime < LONG_PRESS_MS) goHome() else performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
        }
        return true
    }

    /** Keys the user changed in Plume settings (see [Remap]); any other key goes on to the system untouched. */
    private fun remap(e: KeyEvent): Boolean {
        if (Remap.kept(e)) return false
        val id = Remap.id(e)
        if (id == Remap.pending) { // the key just learned: its repeats and release never reach anyone
            if (e.action == KeyEvent.ACTION_UP) Remap.pending = null
            return true
        }
        Remap.learn?.let { learn ->
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) {
                Remap.pending = id
                learn(e)
            }
            return true
        }
        val m = Remap.lookup(Remap.all(this), e.keyCode, e.scanCode) ?: return false
        // Decided on release, like Home: this TV's remote doesn't reliably send key repeats.
        if (e.action == KeyEvent.ACTION_UP && !e.isCanceled) {
            val action = if (e.eventTime - e.downTime >= LONG_PRESS_MS && m.long.isNotEmpty()) m.long else m.short
            if (action.isNotEmpty()) Remap.run(this, action)
        }
        return true
    }

    private val handler = Handler(Looper.getMainLooper())
    private val watch by lazy { HomeWatch(fireTv(this)) { googleHomes } }
    private var googleHomes = emptySet<String>()
    private var listening = false
    private val cover by lazy { HomeCover(this, handler) }
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            watch.jumped()
            cancelJump()
            if (i.action == Intent.ACTION_SCREEN_ON && !nativeHome() && watch.wake()) handler.postDelayed(jump, 250)
        }
    }
    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = loadHomes()
    }
    private val jump = Runnable { watch.jumped(); goHome() }

    fun pauseHome() { watch.pause(); cancelJump() }
    fun resumeHome() { watch.resume() }
    fun homeDrawn() { watch.jumped(); cancelJump() }

    private fun cancelJump() {
        handler.removeCallbacks(jump)
        HomeAlarm.cancel(this)
        cover.hide()
    }

    private fun stop() {
        if (running === this) running = null
        watch.jumped()
        cancelJump()
        HomeAlarm.release(this)
        if (listening) {
            listening = false
            unregisterReceiver(packageReceiver)
            unregisterReceiver(screenReceiver)
        }
    }

    override fun onDestroy() { stop(); super.onDestroy() }

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (!getSystemService(PowerManager::class.java).isInteractive) { watch.jumped(); cancelJump(); return }
        if (nativeHome()) { watch.jumped(); cancelJump(); return }
        val pkg = e.packageName?.toString() ?: return
        if (pkg == packageName && e.className?.toString() != MainActivity::class.java.name) return
        val after = watch.opened(pkg, e.className?.toString(), SystemClock.uptimeMillis())
        if (after == HomeWatch.KEEP) return // the waiting jump stays
        if (pkg == packageName) {
            handler.removeCallbacks(jump)
            HomeAlarm.cancel(this) // keep the cover until the first Home frame
        } else cancelJump()
        after?.let { handler.postDelayed(jump, it) }
    }

    /** The Google TV launcher's home screens, asked of the system when the service starts and when a package changes. */
    private fun loadHomes() {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        googleHomes = googleTvHomes(packageManager.queryIntentActivities(home, 0).map {
            val a = it.activityInfo
            HomeEntry(a.packageName, a.name, a.targetActivity)
        })
    }

    // Marked as Home so the setup check can tell it from Plume being opened from the apps list.
    private fun goHome() {
        if (!getSystemService(PowerManager::class.java).isInteractive) return
        val home = Intent(this, MainActivity::class.java).setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val start = {
            try {
                if (!HomeAlarm.start(this, home)) startActivity(home)
            } catch (_: RuntimeException) { cancelJump() }
            Unit
        }
        if (fireTv(this) && !nativeHome()) cover.show(start) else start()
    }

    override fun onInterrupt() {}

    companion object {
        /** The bound service, so Plume's switch can turn it off without any permission. */
        var running: HomeOverride? = null
            private set
        internal var turningOff = false
        private const val LONG_PRESS_MS = 500L

        /** Fire OS keeps Amazon's home and ignores the home role; Plume can only cover it (see [HomeWatch]). */
        fun fireTv(c: Context) = c.packageManager.hasSystemFeature("amazon.hardware.fire_tv")
    }
}
