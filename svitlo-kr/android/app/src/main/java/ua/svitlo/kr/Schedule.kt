package ua.svitlo.kr

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Pure schedule logic (no Android dependencies). Mirrors ios/SvitloKR.js. */

val KYIV: ZoneId = ZoneId.of("Europe/Kyiv")
const val SLOT_MS = 30 * 60 * 1000L
private val HHMM = DateTimeFormatter.ofPattern("HH:mm")

enum class Power { ON, OFF, MAYBE, UNKNOWN }

/**
 * Parsed content of outage-data-ua `dnipro.json`.
 * fact:   day start (unix seconds) -> group -> hour "1".."24" -> value
 * preset: group -> weekday "1".."7" (Mon..Sun) -> hour -> value
 */
data class OutageData(
    val fact: Map<Long, Map<String, Map<String, String>>>,
    val preset: Map<String, Map<String, Map<String, String>>>,
    val updated: String,
    val groups: List<String>,
)

data class Day(val start: Long, val slots: List<Power>, val source: String)

data class Interval(val state: Power, val start: Long, var end: Long)

data class Status(
    val state: Power,
    val title: String,
    val subtitle: String,
    val until: Long?,
    val days: List<Day>,
)

private fun halves(v: String?): List<Power> = when (v) {
    "yes" -> listOf(Power.ON, Power.ON)
    "no" -> listOf(Power.OFF, Power.OFF)
    "maybe" -> listOf(Power.MAYBE, Power.MAYBE)
    "first" -> listOf(Power.OFF, Power.ON)
    "second" -> listOf(Power.ON, Power.OFF)
    "mfirst" -> listOf(Power.MAYBE, Power.ON)
    "msecond" -> listOf(Power.ON, Power.MAYBE)
    else -> listOf(Power.UNKNOWN, Power.UNKNOWN)
}

fun kyivMidnight(ms: Long): Long =
    Instant.ofEpochMilli(ms).atZone(KYIV).toLocalDate().atStartOfDay(KYIV).toInstant().toEpochMilli()

fun kyivDate(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(KYIV).toLocalDate()

fun hhmm(ms: Long): String = Instant.ofEpochMilli(ms).atZone(KYIV).format(HHMM)

fun groupLabel(g: String): String {
    val m = Regex("""^GPV(\d+)\.(\d+)$""").find(g) ?: return g
    return "Черга ${m.groupValues[1]}.${m.groupValues[2]}"
}

fun daySlots(data: OutageData, group: String, dayStart: Long): Day {
    var src = data.fact[dayStart / 1000]?.get(group)
    var source = "fact"
    if (src == null) {
        val wd = kyivDate(dayStart).dayOfWeek.value.toString() // 1 = Monday
        src = data.preset[group]?.get(wd)
        source = if (src != null) "preset" else "none"
    }
    val slots = (1..24).flatMap { h -> halves(src?.get(h.toString())) }
    return Day(dayStart, slots, source)
}

fun buildDays(data: OutageData, group: String, now: Long): List<Day> {
    val d0 = kyivDate(now)
    return (0L..2L).map { daySlots(data, group, d0.plusDays(it).atStartOfDay(KYIV).toInstant().toEpochMilli()) }
}

fun intervals(day: Day): List<Interval> {
    val out = ArrayList<Interval>()
    day.slots.forEachIndexed { i, s ->
        val start = day.start + i * SLOT_MS
        val last = out.lastOrNull()
        if (last != null && last.state == s) last.end = start + SLOT_MS
        else out.add(Interval(s, start, start + SLOT_MS))
    }
    return out
}

fun timeline(days: List<Day>): List<Interval> {
    val all = ArrayList<Interval>()
    for (d in days) for (iv in intervals(d)) {
        val last = all.lastOrNull()
        if (last != null && last.state == iv.state && last.end == iv.start) last.end = iv.end
        else all.add(iv.copy())
    }
    return all
}

fun computeStatus(data: OutageData, group: String, now: Long): Status {
    val days = buildDays(data, group, now)
    val tl = timeline(days)
    val idx = tl.indexOfFirst { it.start <= now && now < it.end }
    val cur = tl.getOrNull(idx)
    val today = days[0].start
    val tomorrow = days[1].start
    fun fmtStart(ms: Long) = if (kyivMidnight(ms) != today) "завтра ${hhmm(ms)}" else hhmm(ms)
    fun fmtUntil(ms: Long) = if (ms == tomorrow) "24:00" else fmtStart(ms)
    fun nextOf(pred: (Power) -> Boolean) = tl.firstOrNull { it.start > now && pred(it.state) }

    if (cur == null || cur.state == Power.UNKNOWN) {
        return Status(Power.UNKNOWN, "Графік недоступний", "Перевірте чергу", null, days)
    }
    val next = tl.getOrNull(idx + 1)
    val end = if (next != null && next.state != Power.UNKNOWN) cur.end else null

    return when (cur.state) {
        Power.OFF -> {
            val n = nextOf { it == Power.OFF }
            Status(
                Power.OFF,
                if (end != null) "Немає світла до ${fmtUntil(end)}" else "Немає світла",
                if (n != null) "Далі: ${fmtStart(n.start)}–${fmtUntil(n.end)}" else "",
                end, days,
            )
        }
        Power.MAYBE -> Status(
            Power.MAYBE,
            if (end != null) "Можливе відключення до ${fmtUntil(end)}" else "Можливе відключення",
            "", end, days,
        )
        else -> {
            val n = nextOf { it == Power.OFF || it == Power.MAYBE }
            when (n?.state) {
                null -> Status(Power.ON, "Світло є", "Відключень не заплановано", end, days)
                Power.OFF -> Status(
                    Power.ON, "Світло є до ${fmtStart(n.start)}",
                    "Відключення ${fmtStart(n.start)}–${fmtUntil(n.end)}", n.start, days,
                )
                else -> {
                    val s = fmtStart(n.start)
                    val when_ = if (s.startsWith("завтра ")) "завтра з ${s.removePrefix("завтра ")}" else "з $s"
                    Status(Power.ON, "Світло є", "Можливе відкл. $when_", n.start, days)
                }
            }
        }
    }
}
