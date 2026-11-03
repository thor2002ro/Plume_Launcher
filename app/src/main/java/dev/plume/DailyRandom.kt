package dev.plume

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

private const val DAILY_RETRY = 60_000L

internal fun chooseDailyPhoto(photos: List<PackPhoto>, previous: String?, random: Random = Random.Default): PackPhoto? {
    val choices = photos.filter { photos.size == 1 || it.file != previous }
    return choices.takeIf { it.isNotEmpty() }?.random(random)
}

internal fun dailyRandomDue(today: Long, completedDay: Long, now: Long, lastTry: Long) =
    completedDay != today && now - lastTry >= DAILY_RETRY

/** Download first, then remove yesterday so a failed or invalid response cannot lose it. */
internal fun installDailyPhoto(dir: File, photo: PackPhoto, open: () -> InputStream): Boolean {
    val changed = photo.download(dir, open)
    for (file in dir.listFiles().orEmpty()) {
        if (file.name != photo.file && !file.delete()) throw IOException("Cannot remove ${file.name}")
    }
    return changed
}

/** One verified random entry from Plume's wallpaper catalogue for each local calendar day. */
internal object DailyRandom {
    const val CHOICE = "daily-random"

    private const val CATALOG = "https://api.github.com/repos/aradhyas8/Plume_Launcher/contents/wallpapers?ref=main"
    private const val RAW = "https://raw.githubusercontent.com/aradhyas8/Plume_Launcher/main/wallpapers/"
    private const val DAY = "daily_random_day"
    private const val TRY = "daily_random_try"
    private const val FILE = "daily_random_file"

    @Volatile
    var onChanged: (() -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private val worker = ThreadPoolExecutor(0, 1, 5, TimeUnit.SECONDS, LinkedBlockingQueue())
    private val inFlight = AtomicBoolean(false)
    private val lock = Any()

    private fun dir(c: Context) = File(c.filesDir, "daily-random")

    fun file(c: Context, prefs: SharedPreferences): File {
        val name = prefs.getString(FILE, null)?.takeIf(::packFileName) ?: "missing.webp"
        return File(dir(c), name)
    }

    private fun used(prefs: SharedPreferences) =
        prefs.getString("wp_light", null) == CHOICE || prefs.getString("wp_dark", null) == CHOICE

    fun refresh(c: Context, prefs: SharedPreferences, force: Boolean = false) {
        if (!used(prefs)) return
        val today = LocalDate.now().toEpochDay()
        val now = System.currentTimeMillis()
        val completed = prefs.getLong(DAY, 0).takeIf { file(c, prefs).isFile } ?: 0
        if (completed == today || (!force && !dailyRandomDue(today, completed, now, prefs.getLong(TRY, 0)))) return
        if (!inFlight.compareAndSet(false, true)) return
        prefs.edit().putLong(TRY, now).apply()
        worker.execute {
            try {
                val catalog = parsePackCatalog(connect(CATALOG).inputStream.use { it.readBytes().decodeToString() })
                val previous = prefs.getString(FILE, null)
                val photo = chooseDailyPhoto(catalog, previous) ?: throw IOException("empty wallpaper catalogue")
                installDailyPhoto(dir(c), photo) {
                    connect(RAW + photo.file + "?v=" + photo.sha).inputStream
                }
                synchronized(lock) {
                    if (!used(prefs)) clear(c, prefs)
                    else {
                        prefs.edit().putLong(DAY, today).putString(FILE, photo.file).apply()
                        main.post { onChanged?.invoke() }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("Plume", "daily random wallpaper", e)
            } finally {
                inFlight.set(false)
            }
        }
    }

    fun dropIfUnused(c: Context, prefs: SharedPreferences) = synchronized(lock) {
        if (!used(prefs)) clear(c, prefs)
    }

    private fun clear(c: Context, prefs: SharedPreferences) {
        dir(c).listFiles()?.forEach { it.delete() }
        dir(c).delete()
        prefs.edit().remove(DAY).remove(TRY).remove(FILE).apply()
    }

    private fun connect(url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 8000
        readTimeout = 20_000
    }
}
