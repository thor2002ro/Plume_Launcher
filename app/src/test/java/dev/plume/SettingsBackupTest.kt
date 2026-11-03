package dev.plume

import java.io.StringReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsBackupTest {
    @Test fun fakeTvDocumentPickerUsesWorkingFallbacks() {
        val stub = "com.android.tv.frameworkpackagestubs.Stubs\$DocumentsStub"

        assertEquals(SettingsFileRoute.DOWNLOAD, settingsFileRoute(true, listOf(stub), emptyList(), emptyList()))
        assertEquals(SettingsFileRoute.GET, settingsFileRoute(false, emptyList(), listOf(stub), listOf("nextapp.fx.ui.filechooser.ChooserActivity")))
        assertEquals(SettingsFileRoute.CREATE, settingsFileRoute(true, listOf("com.android.documentsui.PickActivity"), emptyList(), emptyList()))
        assertEquals(SettingsFileRoute.OPEN, settingsFileRoute(false, emptyList(), listOf("com.android.documentsui.PickActivity"), emptyList()))
        assertEquals(SettingsFileRoute.NONE, settingsFileRoute(false, emptyList(), listOf(stub), emptyList()))
    }

    @Test fun everyPreferenceTypeRoundTripsDeterministically() {
        val values = linkedMapOf<String, Any>(
            "text" to "one\\two\tthree\nfour\rfive",
            "bool" to true,
            "int" to 7,
            "long" to 8L,
            "float" to 1.25f,
            "set" to setOf("z", "a\tb", "c\\d"),
            "empty" to emptySet<String>(),
        )

        val text = SettingsBackup.encode(values)

        assertEquals(
            "Plume settings\t1\n" +
                "B\tbool\ttrue\n" +
                "A\tempty\t0\n" +
                "F\tfloat\t1.25\n" +
                "I\tint\t7\n" +
                "L\tlong\t8\n" +
                "A\tset\t3\ta\\tb\tc\\\\d\tz\n" +
                "S\ttext\tone\\\\two\\tthree\\nfour\\rfive\n",
            text,
        )
        assertEquals(values, SettingsBackup.decode(text))
    }

    @Test fun excludesOnlyPlannedObservationsAndDeviceBookkeeping() {
        for (key in listOf(
            "rb_level", "rb_failed", "rb_names", "rb_alert_day",
            "wx_temp", "wx_code", "wx_day", "wx_time", "geo_time",
            "upd_ver", "upd_time", "upd_told", "seen_notes",
            "bing_day", "bing_hash", "bing_try",
            "daily_random_day", "daily_random_file", "daily_random_try",
            "wp_photo_light", "wp_photo_dark", "wp_photo_at_light", "wp_photo_at_dark",
            "wp_show_at", "wp_show_key", "wp_show_title", "glass_hue",
            "return_settings", "return_dream", "reopen_settings", "native_home_verified", "home_repair",
            "n_com.example", "t_com.example",
            "futurity_set", "futurity_was", "prev_screensaver", "inputs_moved",
        )) assertTrue(key, SettingsBackup.excluded(key))

        for (key in listOf(
            "rb_devices", "rb_low", "weather", "wx_unit", "geo_city", "geo_lat", "geo_lon", "geo_manual",
            "wp_light", "wp_dark", "wp_every", "wp_flip", "order", "row_hidden", "top_bar", "keymap",
            "lock_pin", "system_stats", "wifi_link_speed", "names", "theme", "home_off",
        )) assertFalse(key, SettingsBackup.excluded(key))
    }

    @Test fun exportAndImportIgnoreExcludedValues() {
        val text = SettingsBackup.encode(mapOf("theme" to "dark", "rb_level" to 20))
        assertFalse(text.contains("rb_level"))
        assertEquals(emptyMap<String, Any>(), SettingsBackup.decode("Plume settings\t1\nI\trb_level\t20\n"))
    }

    @Test fun restoreReplacesEligibleValuesClearsObservationsAndPreservesDeviceState() {
        val current = mapOf<String, Any>(
            "theme" to "light",
            "old_setting" to true,
            "rb_level" to 20,
            "futurity_set" to true,
            "prev_screensaver" to "vendor/.Dream",
        )

        assertEquals(
            mapOf<String, Any>(
                "theme" to "dark",
                "futurity_set" to true,
                "prev_screensaver" to "vendor/.Dream",
            ),
            SettingsBackup.restoreValues(current, mapOf("theme" to "dark")),
        )
    }

    @Test fun malformedOrUnsupportedDocumentsAreRejected() {
        val invalid = listOf(
            "",
            "Plume settings\t2\n",
            "Plume settings\t1\nS\tonly-key\n",
            "Plume settings\t1\nS\tkey\tbad\\x\n",
            "Plume settings\t1\nX\tkey\tvalue\n",
            "Plume settings\t1\nB\tkey\tmaybe\n",
            "Plume settings\t1\nI\tkey\tnot-a-number\n",
            "Plume settings\t1\nA\tkey\t2\tonly-one\n",
            "Plume settings\t1\nS\tkey\tone\nS\tkey\ttwo\n",
            "Plume settings\t1\nS\tkey\tone\nA\tkey\t0\n",
            "Plume settings\t1\nI\trb_level\t20\nI\trb_level\t30\n",
        )

        for (text in invalid) assertThrows(text, IllegalArgumentException::class.java) {
            SettingsBackup.decode(text)
        }
        assertThrows(IllegalArgumentException::class.java) { SettingsBackup.encode(mapOf("double" to 1.0)) }
    }

    @Test fun rejectsAnOversizedDocumentBeforeParsingRows() {
        val text = "Plume settings\t1\nS\tkey\t" + "x".repeat(1_000_001)

        assertThrows(IllegalArgumentException::class.java) { SettingsBackup.decode(StringReader(text)) }
    }

    @Test fun rejectsWrongTypesAndCrashSensitiveValuesForKnownSettings() {
        for (text in listOf(
            "Plume settings\t1\nI\ttheme\t1\n",
            "Plume settings\t1\nB\twp_every\ttrue\n",
            "Plume settings\t1\nS\trb_devices\tremote\n",
            "Plume settings\t1\nS\tclock_size\tbogus\n",
            "Plume settings\t1\nS\tclock_style\tbogus\n",
            "Plume settings\t1\nS\tnames\tbogus\n",
            "Plume settings\t1\nS\tlock_pin\tbogus\n",
        )) assertThrows(text, IllegalArgumentException::class.java) { SettingsBackup.decode(text) }

        assertEquals(mapOf("future_setting" to 1), SettingsBackup.decode("Plume settings\t1\nI\tfuture_setting\t1\n"))
    }

    @Test fun failedWriteRollsBackTheCompletePreviousSnapshot() {
        val before = mapOf<String, Any>("theme" to "light", "rb_level" to 70)
        var stored = before
        var writes = 0

        val result = SettingsBackup.restore(before, mapOf("theme" to "dark")) { values ->
            stored = values
            ++writes > 1
        }

        assertEquals(SettingsBackup.RestoreResult.FAILED, result)
        assertEquals(before, stored)
        assertEquals(2, writes)
    }

    @Test fun reportsWhenBothTheRestoreAndRollbackWritesFail() {
        val before = mapOf<String, Any>("theme" to "light")
        var stored = before

        val result = SettingsBackup.restore(before, mapOf("theme" to "dark")) { values ->
            stored = values
            false
        }

        assertEquals(SettingsBackup.RestoreResult.ROLLBACK_FAILED, result)
        assertEquals(before, stored)
    }
}
