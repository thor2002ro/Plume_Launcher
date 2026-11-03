package dev.plume

import android.app.ActivityManager
import android.content.Context
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

internal enum class SystemStatsMode(val key: String) {
    OFF("off"), MINIMAL("minimal"), DETAILED("detailed");

    companion object {
        fun from(value: String?) = entries.firstOrNull { it.key == value } ?: OFF
    }
}

internal const val SYSTEM_STATS_REFRESH_MS = 1_000L

internal fun newSystemStatsExecutor() = Executors.newSingleThreadExecutor()

internal fun shouldSampleSystemStats(resumed: Boolean, visible: Boolean, interactive: Boolean, mode: SystemStatsMode) =
    resumed && visible && interactive && mode != SystemStatsMode.OFF

internal data class CpuSnapshot(val idle: Long, val total: Long)

internal data class SystemCpuSnapshot(val total: CpuSnapshot, val cores: Map<Int, CpuSnapshot>)

internal fun parseSystemCpuSnapshot(text: String): SystemCpuSnapshot? {
    val counters = text.lineSequence().filter { it.startsWith("cpu") }.mapNotNull { line ->
        val fields = line.trim().split(Regex("\\s+"))
        val label = fields.first()
        if (label != "cpu" && label.removePrefix("cpu").toIntOrNull() == null) return@mapNotNull null
        // Guest time is already included in user/nice; only the first eight counters contribute to total.
        val values = fields.drop(1).take(8).map { it.toLongOrNull()?.takeIf { value -> value >= 0 } }
        if (values.size < 4 || values.any { it == null }) return@mapNotNull null
        val total = values.sumOf { it!! }
        if (total <= 0) return@mapNotNull null
        label to CpuSnapshot(values[3]!! + (values.getOrNull(4) ?: 0), total)
    }.toMap()
    val total = counters["cpu"] ?: return null
    return SystemCpuSnapshot(total, counters.filterKeys { it != "cpu" }.mapKeys { it.key.removePrefix("cpu").toInt() })
}

internal fun parseCpuSnapshot(text: String) = parseSystemCpuSnapshot(text)?.total

internal fun cpuUsage(previous: CpuSnapshot, current: CpuSnapshot): Float? {
    val total = current.total - previous.total
    val idle = current.idle - previous.idle
    if (total <= 0 || idle < 0) return null
    return (100.0 * (1.0 - idle.toDouble() / total)).coerceIn(0.0, 100.0).toFloat()
}

internal data class IdleCpuSnapshot(val uptimeMs: Long, val idleMicros: List<Long?>)

internal fun idleCpuCorePercents(previous: IdleCpuSnapshot?, current: IdleCpuSnapshot): List<Float?> {
    val elapsedMicros = previous?.let { (current.uptimeMs - it.uptimeMs) * 1_000L } ?: 0L
    return current.idleMicros.mapIndexed { core, idle ->
        if (idle == null) return@mapIndexed 0f
        val delta = previous?.idleMicros?.getOrNull(core)?.let { idle - it }
        if (delta == null || delta < 0 || elapsedMicros <= 0) null
        else (100.0 * (1.0 - delta.toDouble() / elapsedMicros)).coerceIn(0.0, 100.0).toFloat()
    }
}

internal fun idleCpuPercent(previous: IdleCpuSnapshot?, current: IdleCpuSnapshot): Float? {
    if (previous == null || previous.idleMicros.size != current.idleMicros.size || current.idleMicros.isEmpty()) return null
    val cores = idleCpuCorePercents(previous, current)
    if (cores.any { it == null } || current.idleMicros.all { it == null }) return null
    return cores.sumOf { it!!.toDouble() }.div(cores.size).toFloat()
}

internal data class ProcessCpuSnapshot(val cpuTimeMs: Long, val elapsedMs: Long)

internal fun processCpuPercent(previous: ProcessCpuSnapshot, current: ProcessCpuSnapshot, coreCount: Int): Float? {
    val cpuDelta = current.cpuTimeMs - previous.cpuTimeMs
    val elapsedDelta = current.elapsedMs - previous.elapsedMs
    if (cpuDelta < 0 || elapsedDelta <= 0) return null
    return (cpuDelta.toFloat() / elapsedDelta * 100f / coreCount.coerceAtLeast(1)).coerceIn(0f, 100f)
}

internal data class SystemStatsSample(
    val cpuPercent: Float?,
    val freeMemoryBytes: Long?,
    val totalMemoryBytes: Long?,
    val cpuCores: Map<Int, Float?> = emptyMap(),
    val appCpu: Boolean = false,
) {
    val ramPercent: Float? get() = totalMemoryBytes?.takeIf { it > 0 }?.let { total ->
        freeMemoryBytes?.let { free -> ((total - free).toFloat() / total * 100).coerceIn(0f, 100f) }
    }
}

internal fun systemStatsSample(
    previous: CpuSnapshot?,
    current: CpuSnapshot?,
    availableMemoryBytes: Long,
    totalMemoryBytes: Long,
): SystemStatsSample {
    val total = totalMemoryBytes.takeIf { it > 0 }
    val free = total?.let { availableMemoryBytes.coerceIn(0, it) }
    return SystemStatsSample(
        cpuPercent = if (previous != null && current != null) cpuUsage(previous, current) else null,
        freeMemoryBytes = free,
        totalMemoryBytes = total,
    )
}

internal data class SystemStatsHistory(
    val cpu: List<Float?> = emptyList(),
    val ram: List<Float?> = emptyList(),
) {
    fun add(sample: SystemStatsSample) = SystemStatsHistory(
        cpu = (cpu + sample.cpuPercent).takeLast(SAMPLE_COUNT),
        ram = (ram + sample.ramPercent).takeLast(SAMPLE_COUNT),
    )

    companion object {
        const val SAMPLE_COUNT = 60
    }
}

internal fun formatMemory(bytes: Long?): String = when {
    bytes == null -> "–"
    bytes >= GIB -> String.format(Locale.US, "%.1fG", bytes.toDouble() / GIB)
    else -> String.format(Locale.US, "%.0fM", bytes.toDouble() / MIB)
}

internal fun formatPercent(percent: Float?) = percent?.let { "${it.toInt()}%" } ?: "–"

internal class SystemStatsSampler(context: Context) {
    private val manager = context.applicationContext.getSystemService(ActivityManager::class.java)
    private val memory = ActivityManager.MemoryInfo()
    private var previousCpu: SystemCpuSnapshot? = null
    private var previousIdleCpu: IdleCpuSnapshot? = null
    private var previousProcessCpu: ProcessCpuSnapshot? = null
    private var systemCpuAvailable = true
    private var processCpuAvailable = true
    private val clockTicks by lazy { runCatching { Os.sysconf(OsConstants._SC_CLK_TCK) }.getOrDefault(100L).takeIf { it > 0 } ?: 100L }
    private val idleCores by lazy {
        runCatching {
            File("/sys/devices/system/cpu").listFiles().orEmpty()
                .filter { it.name.matches(Regex("cpu\\d+")) }
                .sortedBy { it.name.removePrefix("cpu").toInt() }
                .map { core -> core to File(core, "cpuidle").listFiles().orEmpty()
                    .filter { it.name.startsWith("state") }.map { File(it, "time") } }
                .takeIf { cores -> cores.isNotEmpty() && cores.all { (_, states) -> states.isNotEmpty() && states.all(File::canRead) } }
        }.getOrNull()
    }

    fun sample(): SystemStatsSample {
        val cpu = sampleCpu()
        val memoryRead = runCatching { manager?.getMemoryInfo(memory); manager != null }.getOrDefault(false)
        val total = memory.totalMem.takeIf { memoryRead && it > 0 }
        return SystemStatsSample(cpu.percent, total?.let { memory.availMem.coerceIn(0, it) }, total, cpu.cores, cpu.app)
    }

    fun reset() {
        previousCpu = null
        previousIdleCpu = null
        previousProcessCpu = null
    }

    private fun sampleCpu(): CpuReading {
        readSystemCpu()?.let { current ->
            val before = previousCpu
            previousCpu = current
            return CpuReading(
                before?.let { cpuUsage(it.total, current.total) },
                current.cores.mapValues { (core, value) -> before?.cores?.get(core)?.let { cpuUsage(it, value) } },
            )
        }
        readIdleCpu()?.let { current ->
            val before = previousIdleCpu
            previousIdleCpu = current
            previousProcessCpu = null
            val cores = idleCpuCorePercents(before, current)
            return CpuReading(idleCpuPercent(before, current), cores.mapIndexed { index, value -> idleCores!![index].first.name.removePrefix("cpu").toInt() to value }.toMap())
        }
        readProcessCpu()?.let { current ->
            val before = previousProcessCpu
            previousProcessCpu = current
            return CpuReading(before?.let { processCpuPercent(it, current, Runtime.getRuntime().availableProcessors()) }, app = true)
        }
        return CpuReading(null)
    }

    private fun readSystemCpu(): SystemCpuSnapshot? {
        if (!systemCpuAvailable) return null
        val value = runCatching { parseSystemCpuSnapshot(File("/proc/stat").readText()) }.getOrNull()
        if (value == null) systemCpuAvailable = false
        return value
    }

    private fun readIdleCpu(): IdleCpuSnapshot? {
        val cores = idleCores ?: return null
        val values = cores.map { (core, states) ->
            val online = File(core, "online").takeIf(File::exists)?.let { file ->
                when (runCatching { file.readText().trim() }.getOrNull()) { "0" -> false; "1" -> true; else -> return null }
            } ?: true
            if (!online) null else states.sumOf { state ->
                runCatching { state.readText().trim().toLongOrNull()?.takeIf { it >= 0 } }.getOrNull() ?: return null
            }
        }
        return IdleCpuSnapshot(SystemClock.uptimeMillis(), values).takeIf { values.any { it != null } }
    }

    private fun readProcessCpu(): ProcessCpuSnapshot? {
        if (!processCpuAvailable) return null
        val stat = runCatching { File("/proc/self/stat").readText() }.getOrNull()
        val end = stat?.lastIndexOf(')') ?: -1
        val fields = stat?.takeIf { end >= 0 && end + 2 < it.length }?.substring(end + 2)?.trim()?.split(Regex("\\s+"))
        val user = fields?.getOrNull(11)?.toLongOrNull()
        val system = fields?.getOrNull(12)?.toLongOrNull()
        if (user == null || system == null) {
            processCpuAvailable = false
            return null
        }
        return ProcessCpuSnapshot((user + system) * 1_000L / clockTicks, SystemClock.elapsedRealtime())
    }

    private data class CpuReading(val percent: Float?, val cores: Map<Int, Float?> = emptyMap(), val app: Boolean = false)
}

private const val MIB = 1_048_576L
private const val GIB = 1_073_741_824L
