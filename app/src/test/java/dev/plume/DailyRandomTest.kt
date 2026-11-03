package dev.plume

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.random.Random

class DailyRandomTest {
    private fun metadata(name: String) = PackPhoto(name, 12, "a".repeat(40))

    private fun image(text: String) = ("RIFF0000WEBP" + text).toByteArray()

    private fun photo(name: String, data: ByteArray): PackPhoto {
        val hash = MessageDigest.getInstance("SHA-1")
        hash.update("blob ${data.size}\u0000".toByteArray())
        hash.update(data)
        return PackPhoto(name, data.size.toLong(), hash.digest().joinToString("") { "%02x".format(it) })
    }

    private fun inDirectory(test: (File) -> Unit) {
        val dir = Files.createTempDirectory("plume-daily").toFile()
        try { test(dir) } finally { dir.deleteRecursively() }
    }

    @Test fun selectionAvoidsYesterdayWhenPossible() {
        val photos = listOf(metadata("one.webp"), metadata("two.webp"), metadata("three.webp"))
        repeat(20) {
            assertNotEquals("two.webp", chooseDailyPhoto(photos, "two.webp", Random(it))!!.file)
        }
        assertEquals("one.webp", chooseDailyPhoto(listOf(photos[0]), "one.webp", Random(0))!!.file)
        assertNull(chooseDailyPhoto(emptyList(), "one.webp", Random(0)))
    }

    @Test fun completedDayDoesNotRetryAndFailureWaitsAMinute() {
        assertFalse(dailyRandomDue(today = 20, completedDay = 20, now = 100_000, lastTry = 0))
        assertFalse(dailyRandomDue(today = 20, completedDay = 19, now = 100_000, lastTry = 40_001))
        assertTrue(dailyRandomDue(today = 20, completedDay = 19, now = 100_000, lastTry = 40_000))
    }

    @Test fun validDownloadReplacesYesterdayOnlyAfterVerification() = inDirectory { dir ->
        val old = image("old")
        File(dir, "old.webp").writeBytes(old)
        val next = image("next")
        val entry = photo("next.webp", next)

        assertTrue(installDailyPhoto(dir, entry) { ByteArrayInputStream(next) })
        assertEquals(setOf("next.webp"), dir.list()!!.toSet())
        assertArrayEquals(next, File(dir, "next.webp").readBytes())
    }

    @Test fun failedDownloadKeepsYesterday() = inDirectory { dir ->
        val old = image("old")
        File(dir, "old.webp").writeBytes(old)
        val entry = photo("next.webp", image("next"))

        assertThrows(IOException::class.java) {
            installDailyPhoto(dir, entry) { ByteArrayInputStream(byteArrayOf(1)) }
        }
        assertEquals(setOf("old.webp"), dir.list()!!.toSet())
        assertArrayEquals(old, File(dir, "old.webp").readBytes())
    }
}
