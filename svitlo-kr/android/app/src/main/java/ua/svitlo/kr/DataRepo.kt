package ua.svitlo.kr

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Downloads and caches the outage schedule for Dnipro region (incl. Kryvyi Rih). */
object DataRepo {
    const val DATA_URL =
        "https://raw.githubusercontent.com/Baskerville42/outage-data-ua/main/data/dnipro.json"
    private const val MAX_AGE_MS = 10 * 60 * 1000L

    private fun cacheFile(ctx: Context) = File(ctx.filesDir, "dnipro.json")

    data class Result(val data: OutageData, val stale: Boolean)

    /** Must be called off the main thread. Falls back to cache when offline. */
    fun load(ctx: Context, force: Boolean = false): Result? {
        val file = cacheFile(ctx)
        val fresh = file.exists() && System.currentTimeMillis() - file.lastModified() < MAX_AGE_MS
        if (!force && fresh) parseOrNull(file.readText())?.let { return Result(it, false) }
        try {
            val conn = URL(DATA_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 20000
            conn.setRequestProperty("Cache-Control", "no-cache")
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            val parsed = parse(text)
            file.writeText(text)
            return Result(parsed, false)
        } catch (e: Exception) {
            return cached(ctx)?.let { Result(it, true) }
        }
    }

    fun cached(ctx: Context): OutageData? {
        val file = cacheFile(ctx)
        return if (file.exists()) parseOrNull(file.readText()) else null
    }

    private fun parseOrNull(text: String) = try { parse(text) } catch (e: Exception) { null }

    private fun hours(o: JSONObject?): Map<String, String> {
        if (o == null) return emptyMap()
        val m = HashMap<String, String>()
        for (k in o.keys()) m[k] = o.optString(k)
        return m
    }

    fun parse(text: String): OutageData {
        val root = JSONObject(text)
        val fact = HashMap<Long, Map<String, Map<String, String>>>()
        val groups = sortedSetOf<String>()
        root.optJSONObject("fact")?.optJSONObject("data")?.let { days ->
            for (day in days.keys()) {
                val g = days.optJSONObject(day) ?: continue
                val byGroup = HashMap<String, Map<String, String>>()
                for (grp in g.keys()) { byGroup[grp] = hours(g.optJSONObject(grp)); groups.add(grp) }
                day.toLongOrNull()?.let { fact[it] = byGroup }
            }
        }
        val preset = HashMap<String, Map<String, Map<String, String>>>()
        root.optJSONObject("preset")?.optJSONObject("data")?.let { pd ->
            for (grp in pd.keys()) {
                val w = pd.optJSONObject(grp) ?: continue
                val byDay = HashMap<String, Map<String, String>>()
                for (d in w.keys()) byDay[d] = hours(w.optJSONObject(d))
                preset[grp] = byDay
                groups.add(grp)
            }
        }
        if (fact.isEmpty() && preset.isEmpty()) throw IllegalStateException("empty data")
        val updated = root.optJSONObject("fact")?.optString("update").orEmpty()
        return OutageData(fact, preset, updated, groups.filter { Regex("""^GPV\d\.\d$""").matches(it) })
    }
}

/** User settings: chosen address and queue. */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("svitlo", Context.MODE_PRIVATE)
    var street: String
        get() = sp.getString("street", "") ?: ""
        set(v) = sp.edit().putString("street", v).apply()
    var house: String
        get() = sp.getString("house", "") ?: ""
        set(v) = sp.edit().putString("house", v).apply()
    var group: String
        get() = sp.getString("group", "") ?: ""
        set(v) = sp.edit().putString("group", v).apply()

    val address: String
        get() = listOf(street, house).filter { it.isNotBlank() }.joinToString(", ")
}
