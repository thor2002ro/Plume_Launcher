package dev.plume

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager

/** A noninteractive cover, bounded even if Android refuses the Home start. */
internal class HomeCover(private val service: AccessibilityService, private val handler: Handler) {
    private var view: View? = null
    private var launch: Runnable? = null
    private val timeout = Runnable { hide() }
    private val windows get() = service.getSystemService(WindowManager::class.java)

    fun show(start: () -> Unit) {
        hide()
        val cover = View(service).apply {
            setBackgroundColor(Color.BLACK)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val send = Runnable { launch = null; start() }
        launch = send
        var drawn = false
        cover.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                cover.viewTreeObserver.removeOnPreDrawListener(this)
                if (!drawn && launch === send) {
                    drawn = true
                    handler.removeCallbacks(send)
                    // Fire OS 14 needs a visible window before accepting a background activity start.
                    handler.postDelayed(send, if (Build.VERSION.SDK_INT >= 34) 300 else 0)
                }
                return true
            }
        })
        try {
            windows.addView(cover, WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.OPAQUE))
            view = cover
            handler.postDelayed(send, 600) // a window without a draw callback must not block Home
            handler.postDelayed(timeout, 1_500)
        } catch (_: RuntimeException) {
            launch = null
            start()
        }
    }

    fun hide() {
        launch?.let { handler.removeCallbacks(it) }
        launch = null
        handler.removeCallbacks(timeout)
        view?.let { try { windows.removeViewImmediate(it) } catch (_: RuntimeException) {} }
        view = null
    }
}
