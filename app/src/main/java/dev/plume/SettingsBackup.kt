package dev.plume

import java.io.Reader

internal enum class SettingsFileRoute { CREATE, OPEN, GET, DOWNLOAD, NONE }

internal fun settingsFileRoute(
    backup: Boolean,
    createHandlers: Collection<String>,
    openHandlers: Collection<String>,
    getHandlers: Collection<String>,
): SettingsFileRoute {
    fun Collection<String>.usable() = any { it != "com.android.tv.frameworkpackagestubs.Stubs\$DocumentsStub" }
    return if (backup) {
        if (createHandlers.usable()) SettingsFileRoute.CREATE else SettingsFileRoute.DOWNLOAD
    } else when {
        openHandlers.usable() -> SettingsFileRoute.OPEN
        getHandlers.usable() -> SettingsFileRoute.GET
        else -> SettingsFileRoute.NONE
    }
}

internal object SettingsBackup {
    enum class RestoreResult { RESTORED, FAILED, ROLLBACK_FAILED }

    private const val HEADER = "Plume settings\t1"

    private val deviceLocal = setOf("futurity_set", "futurity_was", "prev_screensaver", "inputs_moved")
    private val disposable = setOf(
        "rb_level", "rb_failed", "rb_names", "rb_alert_day",
        "wx_temp", "wx_code", "wx_day", "wx_time", "geo_time",
        "upd_ver", "upd_time", "upd_told", "seen_notes",
        "bing_day", "bing_hash", "bing_try",
        "daily_random_day", "daily_random_file", "daily_random_try",
        "wp_photo_light", "wp_photo_dark", "wp_photo_at_light", "wp_photo_at_dark",
        "wp_show_at", "wp_show_key", "wp_show_title", "glass_hue",
        "return_settings", "return_dream", "reopen_settings", "native_home_verified", "home_repair",
    )
    private val strings = setOf(
        "wp_light", "wp_dark", "theme", "glass_style", "clock_style", "clock_size", "system_stats", "icon_style",
        "row_size", "row_align", "names", "row_show", "sort", "weather", "wx_unit", "geo_city", "ss_folder",
        "keymap", "order", "top_bar", "rb_device", "row_name", "cards_rows", "cards_src", "lock_pin",
    )
    private val booleans = setOf(
        "wp_shared", "setup_shown", "home_requested", "home_off", "app_anim", "fit_all_apps", "row_drop",
        "cw_colour", "smooth_volume", "geo_manual", "video_keep_one", "tip_move", "wifi_link_speed", "glass", "colour_icons",
    )
    private val integers = setOf("wp_every", "rb_low")
    private val floats = setOf("geo_lat", "geo_lon")
    private val stringSets = setOf("wp_flip", "hidden_watch", "cw_off", "ch_flip", "inputs_pinned", "row_hidden", "rb_devices")

    fun excluded(key: String) = key in deviceLocal || disposable(key)

    fun disposable(key: String) = key in disposable || key.startsWith("n_") || key.startsWith("t_")

    fun encode(values: Map<String, *>): String = buildString {
        append(HEADER).append('\n')
        for ((key, value) in values.filterKeys { !excluded(it) }.toSortedMap()) {
            val k = escape(key)
            when (value) {
                is String -> append("S\t$k\t${escape(value)}\n")
                is Boolean -> append("B\t$k\t$value\n")
                is Int -> append("I\t$k\t$value\n")
                is Long -> append("L\t$k\t$value\n")
                is Float -> append("F\t$k\t$value\n")
                is Set<*> -> {
                    val items = value.map { it as? String ?: throw IllegalArgumentException("Unsupported set value") }
                        .sorted().map(::escape)
                    append("A\t$k\t${items.size}")
                    for (item in items) append('\t').append(item)
                    append('\n')
                }
                else -> throw IllegalArgumentException("Unsupported preference value")
            }
        }
    }

    fun decode(text: String): Map<String, Any> {
        require(text.length <= 1_000_000) { "Settings file is too large" }
        val lines = text.lines()
        require(lines.firstOrNull() == HEADER) { "Not a supported Plume settings file" }
        val values = linkedMapOf<String, Any>()
        val seen = HashSet<String>()
        for (line in lines.drop(1).dropLastWhile(String::isEmpty)) {
            require(line.isNotEmpty()) { "Empty preference row" }
            val fields = line.split('\t')
            require(fields.size >= 3) { "Incomplete preference row" }
            val key = unescape(fields[1])
            require(seen.add(key)) { "Duplicate preference" }
            expectedType(key)?.let { require(fields[0] == it) { "Wrong preference type" } }
            val value: Any = when (fields[0]) {
                "S" -> fields.scalar { unescape(it) }
                "B" -> fields.scalar { require(it == "true" || it == "false"); it.toBoolean() }
                "I" -> fields.scalar(String::toInt)
                "L" -> fields.scalar(String::toLong)
                "F" -> fields.scalar(String::toFloat)
                "A" -> {
                    val count = fields[2].toInt()
                    require(count >= 0 && fields.size == count + 3) { "Wrong set size" }
                    fields.drop(3).map(::unescape).toSet().also { require(it.size == count) { "Duplicate set item" } }
                }
                else -> throw IllegalArgumentException("Unknown preference type")
            }
            require(validValue(key, value)) { "Invalid preference value" }
            if (!excluded(key)) values[key] = value
        }
        return values
    }

    fun decode(reader: Reader): Map<String, Any> {
        val text = StringBuilder()
        val buffer = CharArray(8_192)
        while (true) {
            val count = reader.read(buffer)
            if (count < 0) break
            require(text.length + count <= 1_000_000) { "Settings file is too large" }
            text.append(buffer, 0, count)
        }
        return decode(text.toString())
    }

    fun restoreValues(current: Map<String, *>, restored: Map<String, Any>): Map<String, Any> = buildMap {
        for ((key, value) in current) if (key in deviceLocal && value != null) put(key, value)
        putAll(restored.filterKeys { !excluded(it) })
    }

    fun restore(current: Map<String, *>, restored: Map<String, Any>, write: (Map<String, Any>) -> Boolean): RestoreResult {
        val before = current.mapValues { requireNotNull(it.value) }
        if (write(restoreValues(before, restored))) return RestoreResult.RESTORED
        return if (write(before)) RestoreResult.FAILED else RestoreResult.ROLLBACK_FAILED
    }

    private fun expectedType(key: String) = when (key) {
        in strings -> "S"
        in booleans -> "B"
        in integers -> "I"
        in floats -> "F"
        in stringSets -> "A"
        else -> null
    }

    private fun validValue(key: String, value: Any) = when (key) {
        "clock_size" -> value in setOf("38", "48", "60")
        "clock_style" -> value in setOf("light", "bold", "serif")
        "names" -> value in setOf("focus", "always", "never")
        "lock_pin" -> value is String && value.matches(Regex("[0-9a-f]{16}:[0-9a-f]{64}"))
        else -> true
    }

    private inline fun <T> List<String>.scalar(read: (String) -> T): T {
        require(size == 3) { "Wrong scalar size" }
        return read(this[2])
    }

    private fun escape(value: String) = buildString {
        for (c in value) append(when (c) {
            '\\' -> "\\\\"
            '\t' -> "\\t"
            '\n' -> "\\n"
            '\r' -> "\\r"
            else -> c
        })
    }

    private fun unescape(value: String) = buildString {
        var i = 0
        while (i < value.length) {
            val c = value[i++]
            if (c != '\\') append(c) else {
                require(i < value.length) { "Trailing escape" }
                append(when (value[i++]) {
                    '\\' -> '\\'
                    't' -> '\t'
                    'n' -> '\n'
                    'r' -> '\r'
                    else -> throw IllegalArgumentException("Unknown escape")
                })
            }
        }
    }
}
