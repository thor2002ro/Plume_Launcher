package dev.plume

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import kotlin.math.max

private const val MIN_STATS_WIDTH_DP = 146
private const val CORE_VIEW_WIDTH_DP = 44
private const val CORE_VIEW_GAP_DP = 4

internal fun systemStatsWidthDp(mode: SystemStatsMode, clockWidthDp: Float) =
    if (mode != SystemStatsMode.DETAILED) MIN_STATS_WIDTH_DP.toFloat()
    else maxOf(MIN_STATS_WIDTH_DP.toFloat(), clockWidthDp)

internal class SystemStatsView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val density = resources.displayMetrics.density
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(11)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var sample = SystemStatsSample(null, null, null)
    private var history = SystemStatsHistory()
    private var coreView = false

    var clockWidthPx = 0
        set(value) {
            val width = value.coerceAtLeast(0)
            if (field == width) return
            field = width
            if (mode == SystemStatsMode.DETAILED) requestLayout()
            invalidate()
        }

    var mode = SystemStatsMode.OFF
        set(value) {
            if (field == value) return
            field = value
            visibility = if (value == SystemStatsMode.OFF) GONE else VISIBLE
            requestLayout()
            invalidate()
        }

    var ink = Color.WHITE
        set(value) {
            field = value
            invalidate()
        }

    fun show(value: SystemStatsSample) {
        sample = value
        history = history.add(value)
        if (!coreView && value.cpuCores.isNotEmpty()) {
            coreView = true
        }
        val description = resources.getString(
            if (value.appCpu) R.string.system_stats_description_app else R.string.system_stats_description,
            formatPercent(value.cpuPercent),
            formatMemory(value.freeMemoryBytes),
            formatMemory(value.totalMemoryBytes),
        )
        val cores = value.cpuCores.toSortedMap().map { (core, percent) ->
            resources.getString(R.string.system_stats_core, core + 1, formatPercent(percent))
        }
        contentDescription = (listOf(description) + cores).joinToString(", ")
        invalidate()
    }

    fun clear() {
        sample = SystemStatsSample(null, null, null)
        history = SystemStatsHistory()
        contentDescription = null
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = dp(systemStatsWidthDp(mode, clockWidthPx / density))
        val height = if (mode == SystemStatsMode.DETAILED) dp(60) else dp(36)
        setMeasuredDimension(resolveSize(width, widthMeasureSpec), resolveSize(height, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (mode == SystemStatsMode.OFF) return
        val cpu = resources.getString(
            if (sample.appCpu) R.string.system_stats_app_cpu else R.string.system_stats_cpu,
            formatPercent(sample.cpuPercent),
        )
        val ram = resources.getString(
            R.string.system_stats_ram,
            formatMemory(sample.freeMemoryBytes),
            formatMemory(sample.totalMemoryBytes),
        )
        if (mode == SystemStatsMode.MINIMAL) drawMinimal(canvas, cpu, ram)
        else drawDetailed(canvas, cpu, ram)
    }

    private fun drawMinimal(canvas: Canvas, cpu: String, ram: String) {
        text.color = ink
        text.textSize = sp(12)
        text.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        text.setShadowLayer(dp(2).toFloat(), 0f, dp(1).toFloat(), if (ink == Color.WHITE) 0x73000000 else 0x66FFFFFF)
        val baseline = -text.fontMetrics.top
        canvas.drawText(cpu, 0f, baseline, text)
        canvas.drawText(ram, 0f, baseline + dp(17), text)
        text.clearShadowLayer()
    }

    private fun drawDetailed(canvas: Canvas, cpu: String, ram: String) {
        text.color = ink
        text.setShadowLayer(dp(2).toFloat(), 0f, dp(1).toFloat(), if (ink == Color.WHITE) 0x73000000 else 0x66FFFFFF)
        val cpuRight = width - if (coreView) dp(CORE_VIEW_GAP_DP + CORE_VIEW_WIDTH_DP) else 0
        drawGraph(canvas, cpu, history.cpu, cpuRight.toFloat(), 0f)
        sample.cpuCores.takeIf { it.values.any { value -> value?.isFinite() == true } }
            ?.let { drawCoreBars(canvas, it, (cpuRight + dp(CORE_VIEW_GAP_DP)).toFloat(), width.toFloat()) }
        drawGraph(canvas, ram, history.ram, width.toFloat(), dp(31).toFloat())
        text.clearShadowLayer()
    }

    private fun drawGraph(canvas: Canvas, label: String, values: List<Float?>, right: Float, top: Float) {
        text.textSize = sp(12)
        text.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        val baseline = top - text.fontMetrics.top
        canvas.drawText(label, 0f, baseline, text)
        val graphTop = top + dp(16)
        val graphBottom = graphTop + dp(12)
        paint.color = (ink and 0x00FFFFFF) or 0x26000000
        canvas.drawRoundRect(0f, graphTop, right, graphBottom, density, density, paint)
        val slot = right / SystemStatsHistory.SAMPLE_COUNT
        val bar = max(1f, slot - density)
        paint.color = (ink and 0x00FFFFFF) or 0x99000000.toInt()
        values.takeLast(SystemStatsHistory.SAMPLE_COUNT).forEachIndexed { index, value ->
            val percent = value?.takeIf(Float::isFinite)?.coerceIn(0f, 100f) ?: return@forEachIndexed
            val barHeight = max(density, (graphBottom - graphTop) * percent / 100f)
            val x = (SystemStatsHistory.SAMPLE_COUNT - values.size + index) * slot
            canvas.drawRoundRect(x, graphBottom - barHeight, x + bar, graphBottom, density, density, paint)
        }
    }

    private fun drawCoreBars(canvas: Canvas, cores: Map<Int, Float?>, left: Float, right: Float) {
        val values = cores.toSortedMap().values
        val gap = max(1f, density / 2)
        val barWidth = (right - left - gap * (values.size - 1)) / values.size
        if (barWidth < density) return
        text.textSize = sp((barWidth / density / (4 * .62f)).coerceIn(1f, 6f))
        val baseline = -text.fontMetrics.top
        val graphTop = dp(16).toFloat()
        val graphBottom = graphTop + dp(12)
        values.forEachIndexed { index, percent ->
            val x = left + index * (barWidth + gap)
            val value = formatPercent(percent)
            canvas.drawText(value, x + (barWidth - text.measureText(value)) / 2, baseline, text)
            paint.color = (ink and 0x00FFFFFF) or 0x26000000
            canvas.drawRoundRect(x, graphTop, x + barWidth, graphBottom, density, density, paint)
            val fraction = percent?.takeIf(Float::isFinite)?.coerceIn(0f, 100f)?.div(100f) ?: return@forEachIndexed
            paint.color = (ink and 0x00FFFFFF) or 0x99000000.toInt()
            canvas.drawRoundRect(x, graphBottom - (graphBottom - graphTop) * fraction, x + barWidth, graphBottom, density, density, paint)
        }
    }

    private fun dp(value: Int) = (value * density + .5f).toInt()
    private fun dp(value: Float) = (value * density + .5f).toInt()
    private fun sp(value: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value.toFloat(), resources.displayMetrics)
    private fun sp(value: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)
}
