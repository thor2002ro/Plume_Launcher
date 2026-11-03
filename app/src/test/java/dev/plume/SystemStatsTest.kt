package dev.plume

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SystemStatsTest {
    @Test fun modesUseOnlyTheThreeStoredValuesAndDefaultToOff() {
        assertEquals(SystemStatsMode.OFF, SystemStatsMode.from(null))
        assertEquals(SystemStatsMode.OFF, SystemStatsMode.from("off"))
        assertEquals(SystemStatsMode.MINIMAL, SystemStatsMode.from("minimal"))
        assertEquals(SystemStatsMode.DETAILED, SystemStatsMode.from("detailed"))
        assertEquals(SystemStatsMode.OFF, SystemStatsMode.from("unexpected"))
    }

    @Test fun aggregateCpuCountersMatchLinuxProcStatRules() {
        assertEquals(CpuSnapshot(idle = 125, total = 400),
            parseCpuSnapshot("cpu 100 20 30 100 25 40 50 35 999 999\ncpu0 1 2 3 4"))
        assertNull(parseCpuSnapshot("cpu 100 broken 30 100"))
        assertNull(parseCpuSnapshot("cpu 100 20 30"))
        assertNull(parseCpuSnapshot("cpu -1 20 30 100"))
        assertNull(parseCpuSnapshot("cpu0 100 20 30 100"))
    }

    @Test fun systemCpuCountersIncludeEveryAvailableCore() {
        val snapshot = parseSystemCpuSnapshot(
            "cpu 100 20 30 100 25 40 50 35\n" +
                "cpu0 40 10 20 50 10 5 10 5\n" +
                "cpu1 60 10 10 50 15 35 40 30",
        )!!
        assertEquals(CpuSnapshot(125, 400), snapshot.total)
        assertEquals(mapOf(0 to CpuSnapshot(60, 150), 1 to CpuSnapshot(65, 250)), snapshot.cores)
    }

    @Test fun cpuUsageUsesDeltasAndRejectsCounterResets() {
        assertEquals(75f, cpuUsage(CpuSnapshot(100, 200), CpuSnapshot(125, 300))!!, 0.001f)
        assertNull(cpuUsage(CpuSnapshot(100, 200), CpuSnapshot(90, 300)))
        assertNull(cpuUsage(CpuSnapshot(100, 200), CpuSnapshot(110, 200)))
    }

    @Test fun idleCountersProvideWholeDeviceAndPerCoreFallback() {
        val previous = IdleCpuSnapshot(1_000, listOf(100_000, 200_000))
        val current = IdleCpuSnapshot(2_000, listOf(350_000, 700_000))
        assertEquals(listOf(75f, 50f), idleCpuCorePercents(previous, current))
        assertEquals(62.5f, idleCpuPercent(previous, current)!!, 0.001f)
        assertNull(idleCpuPercent(null, current))
    }

    @Test fun processCpuFallbackIsLabelledAndNormalisedAcrossCores() {
        assertEquals(5f, processCpuPercent(
            ProcessCpuSnapshot(cpuTimeMs = 100, elapsedMs = 1_000),
            ProcessCpuSnapshot(cpuTimeMs = 300, elapsedMs = 2_000),
            coreCount = 4,
        )!!, 0.001f)
        assertNull(processCpuPercent(
            ProcessCpuSnapshot(cpuTimeMs = 300, elapsedMs = 2_000),
            ProcessCpuSnapshot(cpuTimeMs = 200, elapsedMs = 3_000),
            coreCount = 4,
        ))
    }

    @Test fun samplesNeedTwoCpuSnapshotsAndClampAvailableMemory() {
        val current = CpuSnapshot(125, 300)
        assertEquals(SystemStatsSample(null, 400, 1_000), systemStatsSample(null, current, 400, 1_000))
        assertEquals(SystemStatsSample(75f, 1_000, 1_000),
            systemStatsSample(CpuSnapshot(100, 200), current, 2_000, 1_000))
        assertEquals(SystemStatsSample(75f, 0, 1_000),
            systemStatsSample(CpuSnapshot(100, 200), current, -1, 1_000))
        assertEquals(SystemStatsSample(75f, null, null),
            systemStatsSample(CpuSnapshot(100, 200), current, 100, 0))
    }

    @Test fun memoryFormattingUsesCompactBinaryUnits() {
        assertEquals("–", formatMemory(null))
        assertEquals("0M", formatMemory(0))
        assertEquals("512M", formatMemory(512L * 1_048_576L))
        assertEquals("1.5G", formatMemory(3L * 1_073_741_824L / 2))
    }

    @Test fun visibleValuesUseWholePercentagesAndFreeOverTotalMemory() {
        val sample = SystemStatsSample(37.9f, 3L * 1_073_741_824L / 2, 4L * 1_073_741_824L)
        assertEquals("37%", formatPercent(sample.cpuPercent))
        assertEquals("1.5G", formatMemory(sample.freeMemoryBytes))
        assertEquals("4.0G", formatMemory(sample.totalMemoryBytes))
        assertEquals("–", formatPercent(null))
    }

    @Test fun unavailableReadingsRemainGapsInTheGraphs() {
        val history = SystemStatsHistory().add(SystemStatsSample(null, null, null))
        assertEquals(listOf<Float?>(null), history.cpu)
        assertEquals(listOf<Float?>(null), history.ram)
    }

    @Test fun samplingRequiresAVisibleAwakeLauncherAndAnEnabledMode() {
        assertTrue(shouldSampleSystemStats(true, true, true, SystemStatsMode.MINIMAL))
        assertTrue(shouldSampleSystemStats(true, true, true, SystemStatsMode.DETAILED))
        assertFalse(shouldSampleSystemStats(true, true, true, SystemStatsMode.OFF))
        assertFalse(shouldSampleSystemStats(false, true, true, SystemStatsMode.MINIMAL))
        assertFalse(shouldSampleSystemStats(true, false, true, SystemStatsMode.MINIMAL))
        assertFalse(shouldSampleSystemStats(true, true, false, SystemStatsMode.MINIMAL))
        assertEquals(1_000L, SYSTEM_STATS_REFRESH_MS)
    }

    @Test fun statsSamplingIsNotBlockedByTheSharedDownloadExecutor() {
        val releaseDownload = CountDownLatch(1)
        val sampleFinished = CountDownLatch(1)
        val downloads = Executors.newSingleThreadExecutor()
        val stats = newSystemStatsExecutor()
        try {
            downloads.execute { releaseDownload.await() }
            stats.execute { sampleFinished.countDown() }
            assertTrue(sampleFinished.await(2, TimeUnit.SECONDS))
        } finally {
            releaseDownload.countDown()
            downloads.shutdownNow()
            stats.shutdownNow()
        }
    }

    @Test fun historyKeepsTheLatestSixtyCpuAndRamUsageSamples() {
        val history = (0..60).fold(SystemStatsHistory()) { value, index ->
            value.add(SystemStatsSample(index.toFloat(), 100L - index, 100))
        }
        assertEquals(60, history.cpu.size)
        assertEquals(1f, history.cpu.first()!!, 0f)
        assertEquals(60f, history.cpu.last()!!, 0f)
        assertEquals(60, history.ram.size)
        assertEquals(1f, history.ram.first()!!, 0.001f)
        assertEquals(60f, history.ram.last()!!, 0.001f)
    }

    @Test fun coreViewAddsSpaceWithoutTakingWidthFromTheHistoryGraphs() {
        assertEquals(136f, systemStatsGraphWidthDp(116f), 0f)
        assertEquals(160f, systemStatsGraphWidthDp(160f), 0f)
        assertEquals(146f, systemStatsWidthDp(SystemStatsMode.DETAILED, cores = false, clockWidthDp = 116f), 0f)
        assertEquals(184f, systemStatsWidthDp(SystemStatsMode.DETAILED, cores = true, clockWidthDp = 116f), 0f)
        assertEquals(170f, systemStatsWidthDp(SystemStatsMode.DETAILED, cores = false, clockWidthDp = 160f), 0f)
        assertEquals(208f, systemStatsWidthDp(SystemStatsMode.DETAILED, cores = true, clockWidthDp = 160f), 0f)
        assertEquals(146f, systemStatsWidthDp(SystemStatsMode.MINIMAL, cores = true, clockWidthDp = 160f), 0f)
    }
}
