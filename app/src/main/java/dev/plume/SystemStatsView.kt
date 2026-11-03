package dev.plume

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import kotlin.math.max

private const val MIN_GRAPH_WIDTH_DP = 136
private const val MIN_STATS_WIDTH_DP = 146
private const val CORE_VIEW_WIDTH_DP = 38
private const val STATS_SIDE_PADDING_DP = 5

internal fun systemStatsGraphWidthDp(clockWidthDp: Float) =
    maxOf(MIN_GRAPH_WIDTH_DP.toFloat(), clockWidthDp)

internal fun systemStatsWidthDp(mode: SystemStatsMode, cores: Boolean, clockWidthDp: Float) =
    if (mode != SystemStatsMode.DETAILED) MIN_STATS_WIDTH_DP.toFloat()
    else systemStatsGraphWidthDp(clockWidthDp) + STATS_SIDE_PADDING_DP * 2 + if (cores) CORE_VIEW_WIDTH_DP else 0

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
            requestLayout()
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
        val width = dp(systemStatsWidthDp(mode, coreView, clockWidthPx / density))
        val height = if (mode == SystemStatsMode.DETAILED) dp(65) else dp(36)
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
        val radius = dp(4).toFloat()
        paint.color = 0xD1000000.toInt()
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), radius, radius, paint)
        val cores = sample.cpuCores.takeIf { values -> values.values.any { it?.isFinite() == true } }
        val graphLeft = dp(STATS_SIDE_PADDING_DP).toFloat()
        val graphRight = minOf(
            graphLeft + systemStatsGraphWidthDp(clockWidthPx / density) * density,
            width - dp(STATS_SIDE_PADDING_DP + if (coreView) CORE_VIEW_WIDTH_DP else 0).toFloat(),
        ).coerceAtLeast(graphLeft)
        drawGraph(canvas, cpu, history.cpu, 0xFF00BCD4.toInt(), graphLeft, graphRight, dp(4).toFloat())
        if (cores != null) drawCoreBars(canvas, cores, graphRight + dp(4), width - dp(5).toFloat(), dp(4).toFloat())
        drawGraph(canvas, ram, history.ram, 0xFFFFB74D.toInt(), graphLeft, graphRight, dp(34).toFloat())
    }

    private fun drawGraph(canvas: Canvas, label: String, values: List<Float?>, color: Int, left: Float, right: Float, top: Float) {
        text.color = Color.WHITE
        text.textSize = sp(9)
        text.typeface = Typeface.MONOSPACE
        val baseline = top - text.fontMetrics.top
        canvas.drawText(label, left, baseline, text)
        val graphTop = top + dp(11)
        val graphBottom = graphTop + dp(14)
        val graph = RectF(left, graphTop, right, graphBottom)
        paint.color = 0x14FFFFFF
        canvas.drawRoundRect(graph, dp(2).toFloat(), dp(2).toFloat(), paint)
        paint.color = 0x1FFFFFFF
        paint.strokeWidth = max(1f, density)
        canvas.drawLine(left, (graphTop + graphBottom) / 2, right, (graphTop + graphBottom) / 2, paint)
        val slot = graph.width() / SystemStatsHistory.SAMPLE_COUNT
        val bar = max(1f, slot - density)
        paint.color = color and 0xBFFFFFFF.toInt()
        values.takeLast(SystemStatsHistory.SAMPLE_COUNT).forEachIndexed { index, value ->
            val percent = value?.takeIf(Float::isFinite)?.coerceIn(0f, 100f) ?: return@forEachIndexed
            val barHeight = max(density, graph.height() * percent / 100f)
            val x = left + (SystemStatsHistory.SAMPLE_COUNT - values.size + index) * slot
            canvas.drawRoundRect(x, graphBottom - barHeight, x + bar, graphBottom, density, density, paint)
        }
    }

    private fun drawCoreBars(canvas: Canvas, cores: Map<Int, Float?>, left: Float, right: Float, top: Float) {
        val values = cores.toSortedMap().values
        if (values.isEmpty()) return
        val gap = max(1f, density / 2)
        val barWidth = (right - left - gap * (values.size - 1)) / values.size
        if (barWidth < density) return
        text.color = Color.WHITE
        text.typeface = Typeface.MONOSPACE
        text.textSize = sp((barWidth / density / (4 * .62f)).coerceIn(1f, 5.5f))
        val baseline = top - text.fontMetrics.top
        val graphTop = top + dp(11)
        val graphBottom = graphTop + dp(14)
        values.forEachIndexed { index, percent ->
            val x = left + index * (barWidth + gap)
            val value = formatPercent(percent)
            canvas.drawText(value, x + (barWidth - text.measureText(value)) / 2, baseline, text)
            paint.color = 0x14FFFFFF
            canvas.drawRect(x, graphTop, x + barWidth, graphBottom, paint)
            val fraction = percent?.takeIf(Float::isFinite)?.coerceIn(0f, 100f)?.div(100f) ?: return@forEachIndexed
            paint.color = 0xBF00BCD4.toInt()
            canvas.drawRect(x, graphBottom - (graphBottom - graphTop) * fraction, x + barWidth, graphBottom, paint)
        }
    }

    private fun dp(value: Int) = (value * density + .5f).toInt()
    private fun dp(value: Float) = (value * density + .5f).toInt()
    private fun sp(value: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value.toFloat(), resources.displayMetrics)
    private fun sp(value: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)
}
