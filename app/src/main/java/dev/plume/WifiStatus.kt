package dev.plume

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.Drawable

internal object WifiStatus {
    fun signalLevel(connected: Boolean, level: Int?) = level?.coerceIn(0, 2)?.takeIf { connected }

    fun shouldBlink(connected: Boolean, ethernet: Boolean, active: Boolean) =
        !connected && !ethernet && active
}

internal class WifiSignalIcon(private val icon: Drawable, private val connected: Boolean) : Drawable() {
    init { setColorFilter(null) }

    override fun getIntrinsicWidth() = icon.intrinsicWidth
    override fun getIntrinsicHeight() = icon.intrinsicHeight

    override fun draw(canvas: Canvas) {
        icon.bounds = bounds
        icon.draw(canvas)
    }

    override fun setAlpha(alpha: Int) {
        icon.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(filter: ColorFilter?) {
        // Connection green must survive the top bar's theme and focus filters.
        icon.colorFilter = if (connected) PorterDuffColorFilter(0xFF43A047.toInt(), PorterDuff.Mode.SRC_IN) else filter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    override fun getConstantState(): ConstantState? {
        val state = icon.constantState ?: return null
        return object : ConstantState() {
            override fun newDrawable(): Drawable = WifiSignalIcon(state.newDrawable().mutate(), connected)
            override fun getChangingConfigurations() = 0
        }
    }
}
