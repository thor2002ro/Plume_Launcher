package dev.plume

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import kotlin.math.ceil

internal object WifiLinkSpeed {
    const val REFRESH_MS = 10_000L

    fun shown(wifi: Boolean, speed: Int?) = speed?.takeIf { wifi && it > 0 }

    fun label(speed: Int?) = "${speed ?: "–"} Mbps"

    fun shouldPoll(resumed: Boolean, visible: Boolean, interactive: Boolean) =
        resumed && visible && interactive
}

internal class WifiLinkSpeedIcon(private val density: Float, private val textSize: Float,
    private val icon: Drawable, private val label: String) : Drawable() {
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
        this.textSize = this@WifiLinkSpeedIcon.textSize
    }

    override fun getIntrinsicWidth() = ceil(density * 29 + maxOf(ink.measureText("99999 Mbps"), ink.measureText(label))).toInt()
    override fun getIntrinsicHeight() = ceil(maxOf(density * 24, ink.fontMetrics.descent - ink.fontMetrics.ascent)).toInt()

    override fun draw(canvas: Canvas) {
        val top = bounds.exactCenterY() - density * 12
        icon.setBounds(bounds.left, top.toInt(), (bounds.left + density * 24).toInt(), (top + density * 24).toInt())
        icon.draw(canvas)
        canvas.drawText(label, bounds.left + density * 29,
            bounds.exactCenterY() - (ink.fontMetrics.ascent + ink.fontMetrics.descent) / 2, ink)
    }

    override fun setAlpha(alpha: Int) {
        ink.alpha = alpha
        icon.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(filter: ColorFilter?) {
        ink.colorFilter = filter
        icon.colorFilter = filter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    override fun getConstantState(): ConstantState? {
        val state = icon.constantState ?: return null
        return object : ConstantState() {
            override fun newDrawable(): Drawable = WifiLinkSpeedIcon(density, textSize, state.newDrawable().mutate(), label)
            override fun getChangingConfigurations() = 0
        }
    }
}
